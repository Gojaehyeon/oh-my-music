import Accelerate
import Foundation

/// 모노 PCM 을 받아 BPM 을 추정한다(키는 KeyDetector).
/// - BPM: 스펙트럴 플럭스 온셋 엔벨로프의 자기상관 + 120 BPM 중심 로그-가우시안 가중
final class Analyzer {
    struct Key {
        let tonic: Int      // 0 = C
        let minor: Bool
        let confidence: Float

        static let names = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]

        var short: String { Key.names[tonic] + (minor ? "m" : "") }
        var long: String { Key.names[tonic] + (minor ? " minor" : " major") }
        var camelot: String {
            let majorPC = minor ? (tonic + 3) % 12 : tonic
            let number = (majorPC * 7 + 7) % 12 + 1
            return "\(number)\(minor ? "A" : "B")"
        }
    }

    private let onsetFFT = FFT(size: 1024)
    private let onsetHop = 256

    private var bpmHistory: [Float] = []

    func reset() {
        bpmHistory.removeAll()
    }

    /// 원본 샘플레이트의 모노 신호를 약 22~24kHz 로 줄인다(평균 데시메이션).
    static func downsample(_ x: [Float], sampleRate: Double) -> ([Float], Double) {
        let factor = max(1, Int(sampleRate / 22050))
        guard factor > 1 else { return (x, sampleRate) }
        let n = x.count / factor
        var out = [Float](repeating: 0, count: n)
        for i in 0..<n {
            var s: Float = 0
            for j in 0..<factor { s += x[i * factor + j] }
            out[i] = s / Float(factor)
        }
        return (out, sampleRate / Double(factor))
    }

    // MARK: - Tempo

    /// 최근 버퍼에서 BPM 을 추정해 기록에 넣고, 기록의 중앙값을 돌려준다.
    func updateTempo(_ x: [Float], sampleRate sr: Double) -> Float? {
        guard let bpm = Analyzer.estimateTempo(x, sampleRate: sr, fft: onsetFFT, hop: onsetHop) else { return nil }
        bpmHistory.append(bpm)
        if bpmHistory.count > 7 { bpmHistory.removeFirst() }
        let sorted = bpmHistory.sorted()
        return sorted[sorted.count / 2]
    }

    static func estimateTempo(_ x: [Float], sampleRate sr: Double, fft: FFT, hop: Int) -> Float? {
        let n = fft.size
        guard x.count > n * 4 else { return nil }
        let fps = sr / Double(hop)
        let frames = (x.count - n) / hop

        // 스펙트럴 플럭스 (로그 압축, 양의 변화량 합)
        var onset = [Float](repeating: 0, count: frames)
        var prev = [Float](repeating: 0, count: n / 2)
        x.withUnsafeBufferPointer { xp in
            for t in 0..<frames {
                var mag = fft.magnitudes(xp.baseAddress! + t * hop)
                var c: Float = 100
                vDSP_vsmul(mag, 1, &c, &mag, 1, vDSP_Length(mag.count))
                var one: Float = 1
                vDSP_vsadd(mag, 1, &one, &mag, 1, vDSP_Length(mag.count))
                var count = Int32(mag.count)
                vvlogf(&mag, mag, &count)
                if t > 0 {
                    var flux: Float = 0
                    for k in 0..<mag.count {
                        let d = mag[k] - prev[k]
                        if d > 0 { flux += d }
                    }
                    onset[t] = flux
                }
                prev = mag
            }
        }

        // 국소 평균 제거 후 반파 정류
        let win = max(1, Int(fps * 0.4))
        var detrended = [Float](repeating: 0, count: frames)
        for t in 0..<frames {
            let lo = max(0, t - win), hi = min(frames, t + win + 1)
            var s: Float = 0
            for i in lo..<hi { s += onset[i] }
            detrended[t] = max(0, onset[t] - s / Float(hi - lo))
        }

        let minLag = Int(fps * 60 / 210)
        let maxLag = min(Int(fps * 60 / 55), frames / 2)
        guard maxLag > minLag + 2 else { return nil }

        func ac(_ lag: Int) -> Float {
            guard lag < frames else { return 0 }
            var s: Float = 0
            detrended.withUnsafeBufferPointer { p in
                vDSP_dotpr(p.baseAddress!, 1, p.baseAddress! + lag, 1, &s, vDSP_Length(frames - lag))
            }
            return s / Float(frames - lag)
        }

        let acs = (0...(maxLag * 2)).map { ac($0) }
        var scores = [Float](repeating: 0, count: maxLag + 1)
        for lag in minLag...maxLag {
            let bpm = 60 * fps / Double(lag)
            let w = Float(exp(-0.5 * pow(log2(bpm / 120) / 0.9, 2)))
            scores[lag] = w * (acs[lag] + 0.5 * acs[min(lag * 2, acs.count - 1)])
        }
        guard let best = (minLag...maxLag).max(by: { scores[$0] < scores[$1] }), scores[best] > 0 else { return nil }

        // 포물선 보간으로 래그를 소수점까지
        var lag = Double(best)
        if best > minLag, best < maxLag {
            let a = scores[best - 1], b = scores[best], c = scores[best + 1]
            let denom = a - 2 * b + c
            if denom != 0 { lag += Double(0.5 * (a - c) / denom) }
        }
        var bpm = Float(60 * fps / lag)
        while bpm < 70 { bpm *= 2 }
        while bpm > 190 { bpm /= 2 }
        return bpm
    }
}

/// 실수 FFT(해닝 윈도우) 크기 스펙트럼.
final class FFT {
    let size: Int
    private let log2n: vDSP_Length
    private let setup: FFTSetup
    private let window: [Float]

    init(size: Int) {
        self.size = size
        log2n = vDSP_Length(log2(Double(size)))
        setup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))!
        window = vDSP.window(ofType: Float.self, usingSequence: .hanningDenormalized, count: size, isHalfWindow: false)
    }

    deinit { vDSP_destroy_fftsetup(setup) }

    func magnitudes(_ x: UnsafePointer<Float>) -> [Float] {
        var windowed = [Float](repeating: 0, count: size)
        vDSP_vmul(x, 1, window, 1, &windowed, 1, vDSP_Length(size))
        return magnitudes(windowed, windowed: true)
    }

    /// 이미 창을 곱한 신호의 크기 스펙트럼(vDSP 관례대로 실제 DFT 의 2배).
    func magnitudes(_ windowed: [Float], windowed _: Bool) -> [Float] {
        let half = size / 2
        var real = [Float](repeating: 0, count: half)
        var imag = [Float](repeating: 0, count: half)
        var mags = [Float](repeating: 0, count: half)
        real.withUnsafeMutableBufferPointer { rp in
            imag.withUnsafeMutableBufferPointer { ip in
                var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                windowed.withUnsafeBytes { wp in
                    vDSP_ctoz(wp.bindMemory(to: DSPComplex.self).baseAddress!, 2, &split, 1, vDSP_Length(half))
                }
                vDSP_fft_zrip(setup, &split, 1, log2n, FFTDirection(FFT_FORWARD))
                split.imagp[0] = 0  // Nyquist 성분 제거
                vDSP_zvabs(&split, 1, &mags, 1, vDSP_Length(half))
            }
        }
        return mags
    }
}

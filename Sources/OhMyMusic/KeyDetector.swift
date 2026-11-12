import Accelerate
import AVFoundation
import CoreML

/// 학습한 키 분류 CNN(KeyNet)으로 조성을 추정한다.
/// 특징 추출은 eval/prep.py · eval/keynet.py 와 정확히 같아야 한다:
/// 22.05kHz 모노 → 8192점 Hann FFT, hop 2205(10fps) → 반음당 3칸 삼각 필터(MIDI 36~95) → 2프레임 평균(5fps) → log1p
final class KeyDetector {
    static let sampleRate = 22050.0
    static let fftSize = 8192
    static let hop = 2205
    static let midiLo = 36, midiHi = 96, binsPerSemitone = 3
    static let bins = (midiHi - midiLo) * binsPerSemitone
    /// 모델에 넣는 최대 길이(5fps 프레임). 길수록 안정적이고 곡 중간 전조에는 느리게 반응한다
    static let maxFrames = 600

    private let model: MLModel
    private let converter: AVAudioConverter
    private let inFormat: AVAudioFormat
    private let outFormat: AVAudioFormat
    private let fft = FFT(size: KeyDetector.fftSize)
    private let window: [Float]
    private let filters: [(start: Int, weights: [Float])]

    private var samples: [Float] = []      // 22.05kHz, 아직 프레임으로 소비되지 않은 꼬리 포함
    private var pendingFrame: [Float]?     // 10fps → 5fps 짝 맞추기용
    private var frames: [[Float]] = []     // 5fps, log1p 적용

    init(inputSampleRate: Double, model: MLModel) {
        self.model = model
        inFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: inputSampleRate, channels: 1, interleaved: false)!
        outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.sampleRate, channels: 1, interleaved: false)!
        converter = AVAudioConverter(from: inFormat, to: outFormat)!
        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue
        let n = Self.fftSize
        // numpy.hanning 과 같은 대칭 창
        window = (0..<n).map { Float(0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1))) }
        filters = Self.makeFilters()
    }

    static func loadModel() throws -> MLModel {
        let exeDir = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath().deletingLastPathComponent()
        let candidates = [Bundle.main.url(forResource: "KeyNet", withExtension: "mlmodelc"),
                          exeDir.appendingPathComponent("KeyNet.mlmodelc")].compactMap { $0 }
        guard let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
            throw NSError(domain: "OhMyMusic", code: 1, userInfo: [NSLocalizedDescriptionKey: "KeyNet.mlmodelc 를 찾지 못했습니다"])
        }
        let config = MLModelConfiguration()
        config.computeUnits = .cpuOnly  // GPU 경로는 이 모델에서 결과가 틀어진다(실측), CPU 로 충분히 빠르다
        return try MLModel(contentsOf: url, configuration: config)
    }

    /// prep.py 의 filterbank() 와 같은 삼각 필터. 필요한 범위(MIDI 36~95)만 만든다.
    private static func makeFilters() -> [(Int, [Float])] {
        let n = fftSize, bps = binsPerSemitone
        let freqs = (0...(n / 2)).map { Double($0) * sampleRate / Double(n) }
        return (0..<bins).map { i in
            let c = 440 * pow(2, (Double(midiLo - 69) + Double(i - bps / 2) / Double(bps)) / 12)
            let lo = c * pow(2, -1 / Double(12 * bps)), hi = c * pow(2, 1 / Double(12 * bps))
            var w = freqs.map { f -> Double in max(0, 1 - abs(f - c) / (f < c ? c - lo : hi - c)) }
            if w.reduce(0, +) == 0 {
                w[freqs.indices.min(by: { abs(freqs[$0] - c) < abs(freqs[$1] - c) })!] = 1
            }
            let sum = w.reduce(0, +)
            let first = w.firstIndex(where: { $0 > 0 })!, last = w.lastIndex(where: { $0 > 0 })!
            return (first, w[first...last].map { Float($0 / sum) })
        }
    }

    func reset() {
        samples.removeAll()
        pendingFrame = nil
        frames.removeAll()
        converter.reset()
    }

    var seconds: Double { Double(frames.count) / 5 }

    /// 입력 샘플레이트의 모노 샘플을 이어 붙이고 새 프레임을 계산한다.
    func append(_ x: [Float]) {
        guard !x.isEmpty else { return }
        let inBuf = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: AVAudioFrameCount(x.count))!
        inBuf.frameLength = AVAudioFrameCount(x.count)
        x.withUnsafeBufferPointer { inBuf.floatChannelData![0].update(from: $0.baseAddress!, count: x.count) }
        let cap = AVAudioFrameCount(Double(x.count) * Self.sampleRate / inFormat.sampleRate) + 1024
        let outBuf = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: cap)!
        var fed = false
        _ = converter.convert(to: outBuf, error: nil) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return inBuf
        }
        samples.append(contentsOf: UnsafeBufferPointer(start: outBuf.floatChannelData![0], count: Int(outBuf.frameLength)))

        var offset = 0
        var windowed = [Float](repeating: 0, count: Self.fftSize)
        samples.withUnsafeBufferPointer { sp in
            while offset + Self.fftSize <= sp.count {
                vDSP_vmul(sp.baseAddress! + offset, 1, window, 1, &windowed, 1, vDSP_Length(Self.fftSize))
                // vDSP_fft_zrip 는 실제 DFT 의 2배 크기를 낸다
                var mag = fft.magnitudes(windowed, windowed: true)
                var half: Float = 0.5
                vDSP_vsmul(mag, 1, &half, &mag, 1, vDSP_Length(mag.count))
                var frame = [Float](repeating: 0, count: Self.bins)
                for (i, f) in filters.enumerated() {
                    var s: Float = 0
                    vDSP_dotpr(Array(mag[f.start..<(f.start + f.weights.count)]), 1, f.weights, 1, &s, vDSP_Length(f.weights.count))
                    frame[i] = s
                }
                push(frame)
                offset += Self.hop
            }
        }
        samples.removeFirst(offset)
    }

    private func push(_ frame: [Float]) {
        guard let first = pendingFrame else { pendingFrame = frame; return }
        pendingFrame = nil
        frames.append(zip(first, frame).map { log1p(($0 + $1) / 2) })
        if frames.count > Self.maxFrames { frames.removeFirst(frames.count - Self.maxFrames) }
    }

    /// 최근 최대 maxFrames 프레임으로 24개 키 확률을 낸다. (0~11 장조 C..B, 12~23 단조)
    func probabilities() -> [Float]? {
        guard frames.count >= 25 else { return nil }
        let t = frames.count
        guard let input = try? MLMultiArray(shape: [1, NSNumber(value: t), NSNumber(value: Self.bins)], dataType: .float32) else { return nil }
        let p = input.dataPointer.assumingMemoryBound(to: Float.self)
        for (i, f) in frames.enumerated() { f.withUnsafeBufferPointer { (p + i * Self.bins).update(from: $0.baseAddress!, count: Self.bins) } }
        guard let out = try? model.prediction(from: MLDictionaryFeatureProvider(dictionary: ["spec": input])),
              let probs = out.featureValue(for: "probs")?.multiArrayValue else { return nil }
        return (0..<24).map { probs[$0].floatValue }
    }

    func estimate() -> Analyzer.Key? {
        guard let probs = probabilities() else { return nil }
        let sorted = probs.enumerated().sorted { $0.element > $1.element }
        let best = sorted[0].offset
        return Analyzer.Key(tonic: best % 12, minor: best >= 12, confidence: sorted[0].element)
    }
}

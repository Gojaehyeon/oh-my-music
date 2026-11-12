import AppKit
import Accelerate
import AVFoundation

/// 메뉴바에 현재 재생 중인 음악의 키와 BPM 을 표시한다.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let tap = SystemAudioTap(seconds: 12)
    private let analyzer = Analyzer()
    private var keyDetector: KeyDetector?
    private let work = DispatchQueue(label: "ohmymusic.analyze", qos: .utility)
    private var timer: Timer?

    private var listeningSince: Date?
    private var lastSound = Date.distantPast
    private var wasPlaying = false

    private let keyItem = NSMenuItem(title: "키: —", action: nil, keyEquivalent: "")
    private let camelotItem = NSMenuItem(title: "카멜롯: —", action: nil, keyEquivalent: "")
    private let bpmItem = NSMenuItem(title: "BPM: —", action: nil, keyEquivalent: "")
    private let statusLine = NSMenuItem(title: "", action: nil, keyEquivalent: "")

    private static let tick: TimeInterval = 2

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = NSMenu()
        for item in [keyItem, camelotItem, bpmItem] {
            item.isEnabled = false
            menu.addItem(item)
        }
        menu.addItem(.separator())
        statusLine.isEnabled = false
        menu.addItem(statusLine)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "다시 분석", action: #selector(resetAnalysis), keyEquivalent: "r"))
        menu.addItem(NSMenuItem(title: "종료", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        statusItem.menu = menu

        setTitle("♪ —")
        do {
            try tap.start()
            keyDetector = KeyDetector(inputSampleRate: tap.sampleRate, model: try KeyDetector.loadModel())
            setStatus("대기 중: 음악을 재생하면 분석을 시작합니다.")
            statusItem.isVisible = false
        } catch {
            setTitle("♪ ⚠︎")
            setStatus("오디오 캡처를 시작하지 못했습니다: \(error)")
            return
        }
        timer = Timer.scheduledTimer(withTimeInterval: Self.tick, repeats: true) { [weak self] _ in self?.analyze() }
    }

    func applicationWillTerminate(_ notification: Notification) { tap.stop() }

    /// 무음이라 아이콘이 숨겨져 있어도, 앱을 다시 열면 메뉴(종료 등)를 쓸 수 있게 보인다.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        statusItem.isVisible = true
        return false
    }

    @objc private func resetAnalysis() {
        work.async { [weak self] in
            self?.analyzer.reset()
            self?.keyDetector?.reset()
            DispatchQueue.main.async { self?.listeningSince = nil }
        }
        setTitle("♪ …")
    }

    private func analyze() {
        let sr = tap.sampleRate
        let tempoBuffer = tap.snapshot(seconds: 10)
        let fresh = tap.drain()
        guard !fresh.isEmpty else { return }

        var rms: Float = 0
        vDSP_rmsqv(fresh, 1, &rms, vDSP_Length(fresh.count))
        let now = Date()
        if rms > 1e-4 {
            lastSound = now
            if listeningSince == nil { listeningSince = now }
        } else if now.timeIntervalSince(lastSound) > 4 {
            if listeningSince != nil {
                listeningSince = nil
                work.async { [weak self] in
                    self?.analyzer.reset()
                    self?.keyDetector?.reset()
                }
            }
            setTitle("♪ —")
            setStatus("소리가 없습니다. 권한이 없으면 시스템 설정 > 개인정보 보호 및 보안 > 화면 및 시스템 오디오 녹음에서 Oh My Music 을 허용해 주십시오.")
            // 다시 열어서 보이게 한 경우는 메뉴를 쓸 수 있도록 무음 직후 한 번만 숨긴다
            if wasPlaying { statusItem.isVisible = false }
            wasPlaying = false
            return
        }
        guard let since = listeningSince else { return }
        // 직전까지 쌓인 무음 구간이 템포 분석에 섞이지 않도록 재생 시작 이후만 사용
        let heard = now.timeIntervalSince(since) + Self.tick
        let tempoInput = Array(tempoBuffer.suffix(min(tempoBuffer.count, Int(sr * heard))))

        work.async { [weak self] in
            guard let self else { return }
            keyDetector?.append(fresh)
            let key = keyDetector?.estimate()
            var bpm: Float?
            if Double(tempoInput.count) / sr >= 5 {
                let (t, tsr) = Analyzer.downsample(tempoInput, sampleRate: sr)
                bpm = analyzer.updateTempo(t, sampleRate: tsr)
            }
            DispatchQueue.main.async { self.show(key: key, bpm: bpm, heard: heard) }
        }
    }

    private func show(key: Analyzer.Key?, bpm: Float?, heard: TimeInterval) {
        let keyText = key?.short ?? "—"
        let bpmText = bpm.map { String(Int($0.rounded())) } ?? "…"
        setTitle("\(keyText) · \(bpmText)")
        statusItem.isVisible = true
        wasPlaying = true
        keyItem.title = "키: " + (key.map { "\($0.long)  (확신도 \(Int($0.confidence * 100))%)" } ?? "—")
        camelotItem.title = "카멜롯: " + (key?.camelot ?? "—")
        bpmItem.title = "BPM: " + (bpm.map { String(format: "%.1f", $0) } ?? "분석 중")
        setStatus(String(format: "%.0f초째 듣는 중", heard))
    }

    private func setTitle(_ text: String) {
        statusItem.button?.attributedTitle = NSAttributedString(string: text, attributes: [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
        ])
    }

    private func setStatus(_ text: String) { statusLine.title = text }
}

/// `OhMyMusic --analyze <파일>`: 오디오 파일로 분석 알고리즘을 검증한다.
func analyzeFile(_ path: String) {
    do {
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        let format = file.processingFormat
        let frames = AVAudioFrameCount(file.length)
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return }
        try file.read(into: buf)
        let channels = Int(format.channelCount), n = Int(buf.frameLength)
        var mono = [Float](repeating: 0, count: n)
        for c in 0..<channels {
            let p = buf.floatChannelData![c]
            for i in 0..<n { mono[i] += p[i] / Float(channels) }
        }
        let (x, sr) = Analyzer.downsample(mono, sampleRate: format.sampleRate)
        let analyzer = Analyzer()
        var bpm: Float?
        let step = Int(sr * 2), window = Int(sr * 10)
        var end = step
        while end <= x.count {
            if end >= Int(sr * 5) {
                bpm = analyzer.updateTempo(Array(x[max(0, end - window)..<end]), sampleRate: sr)
            }
            end += step
        }
        // 키: 앱과 같은 스트리밍 경로로 2초씩 넣는다
        let detector = KeyDetector(inputSampleRate: format.sampleRate, model: try KeyDetector.loadModel())
        let chunk = Int(format.sampleRate * 2)
        var at20: Analyzer.Key?
        var i = 0
        while i < mono.count {
            detector.append(Array(mono[i..<min(mono.count, i + chunk)]))
            i += chunk
            if at20 == nil, detector.seconds >= 20 { at20 = detector.estimate() }
        }
        let key = detector.estimate()
        func describe(_ k: Analyzer.Key?) -> String { k.map { "\($0.long) \($0.camelot) conf=\($0.confidence)" } ?? "-" }
        print("파일: \((path as NSString).lastPathComponent)")
        print("BPM: \(bpm.map { String(format: "%.1f", $0) } ?? "-")")
        print("키(누적): \(describe(key))")
        print("키(20초): \(describe(at20))")
    } catch {
        print("읽기 실패: \(error)")
    }
}

/// `OhMyMusic --record <초> <out.wav>`: 시스템 오디오를 녹음한다(분석 디버깅용).
func record(seconds: Double, to path: String) {
    let tap = SystemAudioTap(seconds: seconds + 1)
    do { try tap.start() } catch { print("캡처 실패: \(error)"); return }
    Thread.sleep(forTimeInterval: seconds)
    let x = tap.snapshot(seconds: seconds)
    tap.stop()
    let format = AVAudioFormat(standardFormatWithSampleRate: tap.sampleRate, channels: 1)!
    let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(x.count))!
    buf.frameLength = AVAudioFrameCount(x.count)
    x.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress!, count: x.count) }
    do {
        try AVAudioFile(forWriting: URL(fileURLWithPath: path), settings: format.settings).write(from: buf)
        print("저장: \(path) (\(x.count) 샘플, \(tap.sampleRate)Hz)")
    } catch { print("저장 실패: \(error)") }
}

let args = CommandLine.arguments
if args.count == 4, args[1] == "--record", let secs = Double(args[2]) {
    record(seconds: secs, to: args[3])
    exit(0)
}
if args.count >= 3, args[1] == "--analyze" {
    for path in args.dropFirst(2) { analyzeFile(path) }
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()

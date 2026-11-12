import CoreAudio
import Foundation

/// Core Audio 프로세스 탭으로 시스템 전체 출력음을 캡처해 모노 링버퍼에 쌓는다.
/// (macOS 14.2+, 최초 실행 시 "시스템 오디오 녹음" 권한 요청)
final class SystemAudioTap {
    enum TapError: Error, CustomStringConvertible {
        case osStatus(String, OSStatus)
        var description: String {
            switch self { case let .osStatus(step, s): return "\(step) 실패 (OSStatus \(s))" }
        }
    }

    private(set) var sampleRate: Double = 48000
    private let seconds: Double
    private var ring: [Float] = []
    private var writeIndex = 0
    private var filled = 0
    private var totalWritten = 0
    private var drained = 0
    private let lock = NSLock()

    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private let queue = DispatchQueue(label: "ohmymusic.tap", qos: .userInteractive)

    init(seconds: Double) { self.seconds = seconds }

    func start() throws {
        let desc = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        desc.uuid = UUID()
        desc.muteBehavior = .unmuted
        desc.isPrivate = true
        desc.name = "Oh My Music"

        try check("탭 생성", AudioHardwareCreateProcessTap(desc, &tapID))

        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        try check("탭 포맷 조회", AudioObjectGetPropertyData(tapID, &addr, 0, nil, &size, &format))
        sampleRate = format.mSampleRate
        lock.withLock {
            ring = [Float](repeating: 0, count: Int(sampleRate * seconds))
            writeIndex = 0; filled = 0; totalWritten = 0; drained = 0
        }

        let outputUID = try defaultOutputUID()
        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "ohmymusic-tap",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: outputUID,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapDriftCompensationKey: true,
                                               kAudioSubTapUIDKey: desc.uuid.uuidString]],
        ]
        try check("애그리게이트 장치 생성", AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID))

        try check("IOProc 생성", AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, queue) { [weak self] _, input, _, _, _ in
            self?.consume(input)
        })
        try check("캡처 시작", AudioDeviceStart(aggregateID, procID))
    }

    func stop() {
        if aggregateID != kAudioObjectUnknown {
            AudioDeviceStop(aggregateID, procID)
            if let procID { AudioDeviceDestroyIOProcID(aggregateID, procID) }
            AudioHardwareDestroyAggregateDevice(aggregateID)
            aggregateID = AudioObjectID(kAudioObjectUnknown)
            procID = nil
        }
        if tapID != kAudioObjectUnknown {
            AudioHardwareDestroyProcessTap(tapID)
            tapID = AudioObjectID(kAudioObjectUnknown)
        }
    }

    /// 가장 최근 `seconds` 초(또는 그보다 짧게 쌓인 만큼)를 시간 순서대로 돌려준다.
    func snapshot(seconds wanted: Double) -> [Float] {
        lock.withLock {
            let count = min(filled, Int(sampleRate * wanted), ring.count)
            guard count > 0 else { return [] }
            var out = [Float](repeating: 0, count: count)
            let start = (writeIndex - count + ring.count) % ring.count
            for i in 0..<count { out[i] = ring[(start + i) % ring.count] }
            return out
        }
    }

    /// 지난 호출 이후 새로 들어온 샘플만 돌려준다(링버퍼를 넘친 만큼은 버려진다).
    func drain() -> [Float] {
        lock.withLock {
            let count = min(totalWritten - drained, ring.count)
            drained = totalWritten
            guard count > 0 else { return [] }
            let start = (writeIndex - count + ring.count) % ring.count
            return (0..<count).map { ring[(start + $0) % ring.count] }
        }
    }

    private func consume(_ list: UnsafePointer<AudioBufferList>) {
        let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: list))
        guard let first = buffers.first, first.mNumberChannels > 0 else { return }
        let firstChannels = Int(first.mNumberChannels)
        let frames = Int(first.mDataByteSize) / MemoryLayout<Float>.size / firstChannels
        guard frames > 0 else { return }

        var mono = [Float](repeating: 0, count: frames)
        var channelCount = 0
        for buffer in buffers {
            guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let ch = Int(buffer.mNumberChannels)
            let n = min(frames, Int(buffer.mDataByteSize) / MemoryLayout<Float>.size / max(ch, 1))
            for f in 0..<n {
                for c in 0..<ch { mono[f] += data[f * ch + c] }
            }
            channelCount += ch
        }
        guard channelCount > 0 else { return }
        let scale = 1 / Float(channelCount)

        lock.withLock {
            guard !ring.isEmpty else { return }
            for f in 0..<frames {
                ring[writeIndex] = mono[f] * scale
                writeIndex = (writeIndex + 1) % ring.count
            }
            filled = min(ring.count, filled + frames)
            totalWritten += frames
        }
    }

    private func defaultOutputUID() throws -> String {
        var deviceID = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultSystemOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        try check("기본 출력 장치 조회",
                  AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &deviceID))

        var uid: Unmanaged<CFString>?
        size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        addr.mSelector = kAudioDevicePropertyDeviceUID
        try check("출력 장치 UID 조회", AudioObjectGetPropertyData(deviceID, &addr, 0, nil, &size, &uid))
        return uid!.takeRetainedValue() as String
    }

    private func check(_ step: String, _ status: OSStatus) throws {
        if status != noErr { throw TapError.osStatus(step, status) }
    }
}

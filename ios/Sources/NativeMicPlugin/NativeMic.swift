import AVFoundation
import Foundation

enum NativeMicErrorCode: String {
    case permissionDenied = "E_PERMISSION_DENIED"
    case permissionRestricted = "E_PERMISSION_RESTRICTED"
    case alreadyRunning = "E_ALREADY_RUNNING"
    case notRunning = "E_NOT_RUNNING"
    case audioSessionConfig = "E_AUDIO_SESSION_CONFIG"
    case engineStartFailed = "E_ENGINE_START_FAILED"
    case engineStopFailed = "E_ENGINE_STOP_FAILED"
    case converterFailed = "E_CONVERTER_FAILED"
    case routeChangeFailed = "E_ROUTE_CHANGE_FAILED"
    case interrupted = "E_INTERRUPTED"
    case mediaServicesReset = "E_MEDIA_SERVICES_RESET"
    case internalError = "E_INTERNAL"
}

struct NativeMicControllerError: Error {
    let code: NativeMicErrorCode
    let message: String
    let recoverable: Bool
    let nativeCode: String?
}

enum MicPermissionState: String {
    case prompt
    case granted
    case denied
}

enum MicProfile: String {
    case waveform
    case pipecat
}

enum SessionMode: String {
    case measurement
    case voiceChat = "voice_chat"
}

enum OutputStream: String, CaseIterable {
    case pcm16k = "pcm16k_s16le"
    case pcm48k = "pcm48k_s16le"

    var sampleRate: Int {
        switch self {
        case .pcm16k:
            return 16_000
        case .pcm48k:
            return 48_000
        }
    }
}

enum OutputRoute: String {
    case system
    case speaker
    case receiver
}

enum NativeMicState: String {
    case idle
    case running
    case paused
}

struct StartCaptureOptionsModel {
    let profile: MicProfile
    let mode: SessionMode
    let outputStreams: [OutputStream]
    let chunkMs: Int
    let emitAudioLevel: Bool
    let audioLevelIntervalMs: Int
    let voiceProcessing: Bool
    let preferredInputId: String?
    let outputRoute: OutputRoute
}

struct StartCaptureResultModel {
    let captureId: String
    let actualInputSampleRate: Double
    let actualInputChannels: Int
    let chunkMs: Int

    func asDictionary() -> [String: Any] {
        [
            "captureId": captureId,
            "actualInputSampleRate": actualInputSampleRate,
            "actualInputChannels": actualInputChannels,
            "chunkMs": chunkMs
        ]
    }
}

struct StopCaptureResultModel {
    let captureId: String
    let totalFramesIn: Int64
    let totalFramesOut16k: Int64
    let totalFramesOut48k: Int64
    let durationMs: Int64

    func asDictionary() -> [String: Any] {
        [
            "captureId": captureId,
            "totalFramesIn": totalFramesIn,
            "totalFramesOut16k": totalFramesOut16k,
            "totalFramesOut48k": totalFramesOut48k,
            "durationMs": durationMs
        ]
    }
}

struct MicDeviceModel {
    let id: String
    let label: String
    let type: String
    let isDefault: Bool

    func asDictionary() -> [String: Any] {
        [
            "id": id,
            "label": label,
            "type": type,
            "isDefault": isDefault
        ]
    }
}

final class FloatRingBuffer {
    private var buffer: [Float]
    private let capacity: Int
    private var readIndex = 0
    private var writeIndex = 0
    private var count = 0
    private let lock = NSLock()

    init(capacity: Int) {
        self.capacity = max(1, capacity)
        self.buffer = Array(repeating: 0, count: max(1, capacity))
    }

    func write(_ samples: [Float]) -> Int {
        guard !samples.isEmpty else {
            return 0
        }

        lock.lock()
        defer { lock.unlock() }

        var dropped = 0
        for sample in samples {
            if count == capacity {
                readIndex = (readIndex + 1) % capacity
                count -= 1
                dropped += 1
            }

            buffer[writeIndex] = sample
            writeIndex = (writeIndex + 1) % capacity
            count += 1
        }

        return dropped
    }

    func read(maxCount: Int) -> [Float] {
        let readAmount = max(0, maxCount)
        guard readAmount > 0 else {
            return []
        }

        lock.lock()
        defer { lock.unlock() }

        guard count > 0 else {
            return []
        }

        let length = min(readAmount, count)
        var output = Array(repeating: Float(0), count: length)
        for index in 0..<length {
            output[index] = buffer[readIndex]
            readIndex = (readIndex + 1) % capacity
        }
        count -= length
        return output
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        readIndex = 0
        writeIndex = 0
        count = 0
    }

    func availableCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }
}

final class OutputStreamPipeline {
    let stream: OutputStream
    let sampleRate: Int
    let chunkFrames: Int

    private let inputFormat: AVAudioFormat
    private let outputFormat: AVAudioFormat
    private let converter: AVAudioConverter
    private(set) var seq: Int64 = 0
    private(set) var emittedFrames: Int64 = 0
    private let startPtsMs: Int64
    private var pendingSamples: [Int16] = []

    init(
        stream: OutputStream,
        inputFormat: AVAudioFormat,
        chunkMs: Int,
        startPtsMs: Int64
    ) throws {
        self.stream = stream
        self.sampleRate = stream.sampleRate
        self.chunkFrames = (stream.sampleRate * chunkMs) / 1_000
        self.inputFormat = inputFormat
        self.startPtsMs = startPtsMs

        guard
            let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Double(stream.sampleRate),
                channels: 1,
                interleaved: false
            ),
            let converter = AVAudioConverter(from: inputFormat, to: outputFormat)
        else {
            throw NativeMicControllerError(
                code: .converterFailed,
                message: "Unable to create output converter format for \(stream.rawValue).",
                recoverable: false,
                nativeCode: nil
            )
        }

        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue
        self.outputFormat = outputFormat
        self.converter = converter
    }

    func convert(samples: [Float]) throws -> [Int16] {
        guard !samples.isEmpty else {
            return []
        }

        guard let inputBuffer = AVAudioPCMBuffer(
            pcmFormat: inputFormat,
            frameCapacity: AVAudioFrameCount(samples.count)
        ) else {
            throw NativeMicControllerError(
                code: .converterFailed,
                message: "Unable to allocate converter input buffer.",
                recoverable: false,
                nativeCode: nil
            )
        }

        inputBuffer.frameLength = AVAudioFrameCount(samples.count)
        guard let channelData = inputBuffer.floatChannelData else {
            throw NativeMicControllerError(
                code: .converterFailed,
                message: "Input audio buffer missing float channel data.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let channel = channelData[0]
        for index in 0..<samples.count {
            channel[index] = samples[index]
        }

        return try runConverter(inputBuffer: inputBuffer, endOfStream: false)
    }

    func flush() throws -> [Int16] {
        try runConverter(inputBuffer: nil, endOfStream: true)
    }

    func appendConverted(samples: [Int16]) {
        guard !samples.isEmpty else {
            return
        }
        pendingSamples.append(contentsOf: samples)
    }

    func popChunkIfAvailable() -> [Int16]? {
        guard pendingSamples.count >= chunkFrames else {
            return nil
        }
        let chunk = Array(pendingSamples.prefix(chunkFrames))
        pendingSamples.removeFirst(chunkFrames)
        return chunk
    }

    func popFinalChunk() -> [Int16]? {
        guard !pendingSamples.isEmpty else {
            return nil
        }

        if pendingSamples.count < chunkFrames {
            pendingSamples.append(contentsOf: repeatElement(0, count: chunkFrames - pendingSamples.count))
        }

        let chunk = Array(pendingSamples.prefix(chunkFrames))
        pendingSamples.removeAll(keepingCapacity: false)
        return chunk
    }

    func nextChunkMetadata(frames: Int) -> (seq: Int64, ptsMs: Int64) {
        let currentSeq = seq
        let ptsOffsetMs = Int64((Double(emittedFrames) * 1_000.0) / Double(sampleRate))
        let pts = startPtsMs + ptsOffsetMs
        seq += 1
        emittedFrames += Int64(frames)
        return (currentSeq, pts)
    }

    private func runConverter(
        inputBuffer: AVAudioPCMBuffer?,
        endOfStream: Bool
    ) throws -> [Int16] {
        var outputSamples: [Int16] = []
        var inputProvided = false
        var eosSent = false

        while true {
            let frameCapacity = max(chunkFrames * 2, 512)
            guard let outputBuffer = AVAudioPCMBuffer(
                pcmFormat: outputFormat,
                frameCapacity: AVAudioFrameCount(frameCapacity)
            ) else {
                throw NativeMicControllerError(
                    code: .converterFailed,
                    message: "Unable to allocate converter output buffer.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            var conversionError: NSError?
            let status = converter.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
                if !inputProvided, let inputBuffer {
                    inputProvided = true
                    outStatus.pointee = .haveData
                    return inputBuffer
                }

                if endOfStream, !eosSent {
                    eosSent = true
                    outStatus.pointee = .endOfStream
                    return nil
                }

                outStatus.pointee = .noDataNow
                return nil
            }

            if let conversionError {
                throw NativeMicControllerError(
                    code: .converterFailed,
                    message: "Audio conversion failed for \(stream.rawValue).",
                    recoverable: false,
                    nativeCode: "\(conversionError.code)"
                )
            }

            let frameLength = Int(outputBuffer.frameLength)
            if frameLength > 0, let int16Data = outputBuffer.int16ChannelData {
                let channel = int16Data[0]
                outputSamples.append(contentsOf: UnsafeBufferPointer(start: channel, count: frameLength))
            }

            switch status {
            case .haveData:
                if frameLength == 0 {
                    return outputSamples
                }
                continue
            case .inputRanDry, .endOfStream:
                return outputSamples
            case .error:
                throw NativeMicControllerError(
                    code: .converterFailed,
                    message: "Audio conversion failed for \(stream.rawValue).",
                    recoverable: false,
                    nativeCode: nil
                )
            @unknown default:
                return outputSamples
            }
        }
    }
}

@objc public final class NativeMicController: NSObject {
    typealias EventEmitter = (_ eventName: String, _ payload: [String: Any]) -> Void

    private enum Constants {
        static let defaultChunkMs = 20
        static let defaultAudioLevelIntervalMs = 50
        static let defaultFlushTimeoutMs = 150
        static let inputRingCapacityFrames = 48_000 * 4
    }

    private let session = AVAudioSession.sharedInstance()
    private var engine: AVAudioEngine?
    private let inputRingBuffer = FloatRingBuffer(capacity: Constants.inputRingCapacityFrames)
    private let processingQueue = DispatchQueue(label: "com.memora.ai.nativemic.processing")
    private let eventQueue = DispatchQueue(label: "com.memora.ai.nativemic.events")
    private let queueKey = DispatchSpecificKey<Int>()
    private let eventEmitter: EventEmitter

    private var state: NativeMicState = .idle
    private var activeConfig: StartCaptureOptionsModel?
    private var activeCaptureId: String?
    private var tapInstalled = false
    private var outputPipelines: [OutputStream: OutputStreamPipeline] = [:]

    private var captureStartPtsMs: Int64 = 0
    private var totalFramesIn: Int64 = 0
    private var totalFramesOut16k: Int64 = 0
    private var totalFramesOut48k: Int64 = 0
    private var actualInputSampleRate: Double = 0
    private var actualInputChannels: Int = 0
    private var droppedInputFrames: Int64 = 0
    private var mediaServicesResetCount = 0
    private var lastRouteChangeReason = "unknown"

    private var preferredInputId: String?
    private var selectedOutputRoute: OutputRoute = .system
    private var micEnabled = true
    private var expectedResumeAfterInterruption = false

    private var levelSumSquares: Double = 0
    private var levelPeak: Float = 0
    private var levelFrames = 0
    private var levelIntervalFrames = 0

    init(eventEmitter: @escaping EventEmitter) {
        self.eventEmitter = eventEmitter
        super.init()

        processingQueue.setSpecific(key: queueKey, value: 1)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruptionNotification(_:)),
            name: AVAudioSession.interruptionNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChangeNotification(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: session
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMediaServicesResetNotification(_:)),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: session
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func isAvailable() -> [String: Any] {
        if session.isInputAvailable {
            return ["available": true]
        }

        return [
            "available": false,
            "reason": "No audio input is currently available."
        ]
    }

    func checkPermissions() -> MicPermissionState {
        mapPermissionState(from: AVCaptureDevice.authorizationStatus(for: .audio))
    }

    func requestPermissions(completion: @escaping (MicPermissionState) -> Void) {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        if status == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                completion(granted ? .granted : self.checkPermissions())
            }
            return
        }

        completion(mapPermissionState(from: status))
    }

    func getDevices() -> (inputs: [MicDeviceModel], selectedInputId: String?) {
        syncOnProcessingQueue {
            let availableInputs = session.availableInputs ?? []
            let selectedId = session.preferredInput?.uid
                ?? session.currentRoute.inputs.first?.uid
                ?? preferredInputId

            let mapped = availableInputs.map { input -> MicDeviceModel in
                MicDeviceModel(
                    id: input.uid,
                    label: input.portName,
                    type: mapPortType(input.portType),
                    isDefault: input.uid == selectedId
                )
            }

            return (mapped, selectedId)
        }
    }

    func setPreferredInput(_ inputId: String?) throws {
        try syncOnProcessingQueue {
            preferredInputId = inputId
            if state != .idle {
                try applyPreferredInputLocked()
            }
        }
    }

    func setOutputRoute(_ route: OutputRoute) throws {
        try syncOnProcessingQueue {
            selectedOutputRoute = route
            if state != .idle {
                try applyOutputRouteLocked(route)
            }
        }
    }

    func startCapture(options: StartCaptureOptionsModel) throws -> StartCaptureResultModel {
        try syncOnProcessingQueue {
            if state == .running || state == .paused {
                throw NativeMicControllerError(
                    code: .alreadyRunning,
                    message: "Capture is already running.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            try ensurePermissionForStart()
            try validateStartOptions(options)

            let captureId = UUID().uuidString
            activeCaptureId = captureId
            activeConfig = options
            selectedOutputRoute = options.outputRoute
            micEnabled = true
            captureStartPtsMs = monotonicMs()
            expectedResumeAfterInterruption = false

            do {
                try configureAudioSessionLocked(for: options)
            } catch let error as NativeMicControllerError {
                clearCaptureStateLocked()
                throw error
            } catch {
                clearCaptureStateLocked()
                throw NativeMicControllerError(
                    code: .audioSessionConfig,
                    message: "Failed to configure the audio session.",
                    recoverable: false,
                    nativeCode: "\(nsErrorCode(error))"
                )
            }

            let engine = AVAudioEngine()
            let inputNode = engine.inputNode

            if options.voiceProcessing {
                do {
                    try inputNode.setVoiceProcessingEnabled(true)
                } catch {
                    clearCaptureStateLocked()
                    throw NativeMicControllerError(
                        code: .audioSessionConfig,
                        message: "Failed to configure voice processing.",
                        recoverable: false,
                        nativeCode: "\(nsErrorCode(error))"
                    )
                }
            }

            let inputFormat = inputNode.inputFormat(forBus: 0)
            actualInputSampleRate = inputFormat.sampleRate
            actualInputChannels = Int(inputFormat.channelCount)

            guard
                let monoInputFormat = AVAudioFormat(
                    commonFormat: .pcmFormatFloat32,
                    sampleRate: inputFormat.sampleRate,
                    channels: 1,
                    interleaved: false
                )
            else {
                clearCaptureStateLocked()
                throw NativeMicControllerError(
                    code: .converterFailed,
                    message: "Unable to create mono input format.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            outputPipelines.removeAll(keepingCapacity: false)
            for stream in options.outputStreams {
                let pipeline = try OutputStreamPipeline(
                    stream: stream,
                    inputFormat: monoInputFormat,
                    chunkMs: options.chunkMs,
                    startPtsMs: captureStartPtsMs
                )
                outputPipelines[stream] = pipeline
            }

            totalFramesIn = 0
            totalFramesOut16k = 0
            totalFramesOut48k = 0
            droppedInputFrames = 0
            levelSumSquares = 0
            levelPeak = 0
            levelFrames = 0
            levelIntervalFrames = max(
                1,
                Int((actualInputSampleRate * Double(options.audioLevelIntervalMs)) / 1_000.0)
            )
            inputRingBuffer.reset()

            if let requestedInputId = options.preferredInputId {
                preferredInputId = requestedInputId
            }
            try applyPreferredInputLocked()
            try applyOutputRouteLocked(options.outputRoute)

            let tapBufferSize = AVAudioFrameCount(
                max(256, Int((inputFormat.sampleRate * Double(options.chunkMs)) / 1_000.0))
            )
            inputNode.installTap(onBus: 0, bufferSize: tapBufferSize, format: inputFormat) { [weak self] buffer, _ in
                self?.handleInputBuffer(buffer)
            }
            tapInstalled = true

            self.engine = engine
            engine.prepare()

            do {
                try engine.start()
            } catch {
                teardownEngineLocked()
                clearCaptureStateLocked()
                throw NativeMicControllerError(
                    code: .engineStartFailed,
                    message: "Failed to start AVAudioEngine.",
                    recoverable: false,
                    nativeCode: "\(nsErrorCode(error))"
                )
            }

            state = .running
            emitStateChangedLocked(reason: "start_capture")

            return StartCaptureResultModel(
                captureId: captureId,
                actualInputSampleRate: actualInputSampleRate,
                actualInputChannels: actualInputChannels,
                chunkMs: options.chunkMs
            )
        }
    }

    func stopCapture(captureId: String, flushTimeoutMs: Int) throws -> StopCaptureResultModel {
        try syncOnProcessingQueue {
            guard
                let activeCaptureId,
                activeCaptureId == captureId,
                activeConfig != nil
            else {
                throw NativeMicControllerError(
                    code: .notRunning,
                    message: "No active capture matches \(captureId).",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            let clampedTimeoutMs = max(10, flushTimeoutMs)
            let stopStartMs = monotonicMs()

            teardownTapLocked()

            while inputRingBuffer.availableCount() > 0 {
                processInputFramesLocked(maxFrames: 8_192, emitFinal: false)
                if monotonicMs() - stopStartMs > Int64(clampedTimeoutMs) {
                    break
                }
            }

            for pipeline in outputPipelines.values {
                let converted = try pipeline.flush()
                pipeline.appendConverted(samples: converted)
                emitAvailableChunksLocked(from: pipeline, final: false)
                if let finalChunk = pipeline.popFinalChunk() {
                    emitChunkLocked(from: pipeline, samples: finalChunk, final: true)
                }
            }

            teardownEngineLocked()

            do {
                try session.setActive(false, options: [.notifyOthersOnDeactivation])
            } catch {
                emitErrorLocked(
                    code: .engineStopFailed,
                    message: "Failed to deactivate audio session.",
                    recoverable: true,
                    nativeCode: "\(nsErrorCode(error))",
                    captureId: activeCaptureId
                )
            }

            let durationMs = max(0, monotonicMs() - captureStartPtsMs)
            let result = StopCaptureResultModel(
                captureId: activeCaptureId,
                totalFramesIn: totalFramesIn,
                totalFramesOut16k: totalFramesOut16k,
                totalFramesOut48k: totalFramesOut48k,
                durationMs: durationMs
            )

            clearCaptureStateLocked()
            state = .idle
            emitStateChangedLocked(reason: "stop_capture")
            return result
        }
    }

    func setMicEnabled(captureId: String, enabled: Bool) throws {
        try syncOnProcessingQueue {
            guard activeCaptureId == captureId, state != .idle else {
                throw NativeMicControllerError(
                    code: .notRunning,
                    message: "No active capture matches \(captureId).",
                    recoverable: false,
                    nativeCode: nil
                )
            }
            micEnabled = enabled
        }
    }

    func getState() -> NativeMicState {
        syncOnProcessingQueue { state }
    }

    func getDiagnostics() -> [String: Any] {
        syncOnProcessingQueue {
            var diagnostics: [String: Any] = [
                "state": state.rawValue,
                "micEnabled": micEnabled,
                "outputRoute": selectedOutputRoute.rawValue,
                "actualInputSampleRate": actualInputSampleRate,
                "actualInputChannels": actualInputChannels,
                "totalFramesIn": totalFramesIn,
                "totalFramesOut16k": totalFramesOut16k,
                "totalFramesOut48k": totalFramesOut48k,
                "inputFramesDropped": droppedInputFrames,
                "inputRingBufferedFrames": inputRingBuffer.availableCount(),
                "mediaServicesResetCount": mediaServicesResetCount,
                "lastRouteChangeReason": lastRouteChangeReason
            ]

            if let activeCaptureId {
                diagnostics["captureId"] = activeCaptureId
            }
            if let preferredInputId {
                diagnostics["preferredInputId"] = preferredInputId
            }

            return diagnostics
        }
    }

    @objc private func handleInterruptionNotification(_ notification: Notification) {
        processingQueue.async { [weak self] in
            self?.handleInterruptionLocked(notification)
        }
    }

    @objc private func handleRouteChangeNotification(_ notification: Notification) {
        processingQueue.async { [weak self] in
            self?.handleRouteChangeLocked(notification)
        }
    }

    @objc private func handleMediaServicesResetNotification(_ notification: Notification) {
        processingQueue.async { [weak self] in
            self?.handleMediaServicesResetLocked(notification)
        }
    }

    private func handleInputBuffer(_ buffer: AVAudioPCMBuffer) {
        let monoSamples = downmixToMono(buffer)
        if monoSamples.isEmpty {
            return
        }

        let dropped = inputRingBuffer.write(monoSamples)
        if dropped > 0 {
            processingQueue.async { [weak self] in
                self?.droppedInputFrames += Int64(dropped)
            }
        }

        processingQueue.async { [weak self] in
            self?.processInputFramesLocked(maxFrames: 4_096, emitFinal: false)
        }
    }

    private func processInputFramesLocked(maxFrames: Int, emitFinal: Bool) {
        while true {
            let samples = inputRingBuffer.read(maxCount: maxFrames)
            if samples.isEmpty {
                break
            }

            var processedSamples = samples
            if !micEnabled {
                processedSamples = Array(repeating: 0, count: processedSamples.count)
            }

            totalFramesIn += Int64(processedSamples.count)

            if activeConfig?.emitAudioLevel == true {
                accumulateAudioLevelLocked(samples: processedSamples)
            }

            for pipeline in outputPipelines.values {
                do {
                    let converted = try pipeline.convert(samples: processedSamples)
                    pipeline.appendConverted(samples: converted)
                    emitAvailableChunksLocked(from: pipeline, final: false)
                } catch let controllerError as NativeMicControllerError {
                    emitErrorLocked(
                        code: controllerError.code,
                        message: controllerError.message,
                        recoverable: controllerError.recoverable,
                        nativeCode: controllerError.nativeCode,
                        captureId: activeCaptureId
                    )
                    return
                } catch {
                    emitErrorLocked(
                        code: .internalError,
                        message: "Unexpected processing error.",
                        recoverable: false,
                        nativeCode: "\(nsErrorCode(error))",
                        captureId: activeCaptureId
                    )
                    return
                }
            }
        }

        if emitFinal {
            for pipeline in outputPipelines.values {
                emitAvailableChunksLocked(from: pipeline, final: false)
                if let finalChunk = pipeline.popFinalChunk() {
                    emitChunkLocked(from: pipeline, samples: finalChunk, final: true)
                }
            }
        }
    }

    private func accumulateAudioLevelLocked(samples: [Float]) {
        guard !samples.isEmpty else {
            return
        }

        for sample in samples {
            let absoluteValue = abs(sample)
            if absoluteValue > levelPeak {
                levelPeak = absoluteValue
            }
            levelSumSquares += Double(sample * sample)
        }

        levelFrames += samples.count
        if levelFrames >= levelIntervalFrames {
            emitAudioLevelLocked()
        }
    }

    private func emitAudioLevelLocked() {
        guard
            let activeCaptureId,
            levelFrames > 0
        else {
            return
        }

        let rms = sqrt(levelSumSquares / Double(levelFrames))
        let peak = Double(levelPeak)
        let dbfs: Double
        if rms > 0 {
            dbfs = min(0, max(-90, 20 * log10(rms)))
        } else {
            dbfs = -90
        }

        var payload: [String: Any] = [
            "captureId": activeCaptureId,
            "rms": rms,
            "peak": peak,
            "dbfs": dbfs,
            "ptsMs": monotonicMs()
        ]
        payload["vad"] = dbfs > -45
        emitEventLocked(name: "micAudioLevel", payload: payload)

        levelSumSquares = 0
        levelPeak = 0
        levelFrames = 0
    }

    private func emitAvailableChunksLocked(from pipeline: OutputStreamPipeline, final: Bool) {
        while let chunk = pipeline.popChunkIfAvailable() {
            emitChunkLocked(from: pipeline, samples: chunk, final: final)
        }
    }

    private func emitChunkLocked(from pipeline: OutputStreamPipeline, samples: [Int16], final: Bool) {
        guard let activeCaptureId else {
            return
        }

        let metadata = pipeline.nextChunkMetadata(frames: samples.count)
        var payload: [String: Any] = [
            "captureId": activeCaptureId,
            "stream": pipeline.stream.rawValue,
            "sampleRate": pipeline.sampleRate,
            "channels": 1,
            "frames": samples.count,
            "seq": metadata.seq,
            "ptsMs": metadata.ptsMs,
            "dataBase64": encodePcm16(samples)
        ]
        if final {
            payload["final"] = true
        }

        switch pipeline.stream {
        case .pcm16k:
            totalFramesOut16k += Int64(samples.count)
        case .pcm48k:
            totalFramesOut48k += Int64(samples.count)
        }

        emitEventLocked(name: "micPcmChunk", payload: payload)
    }

    private func handleInterruptionLocked(_ notification: Notification) {
        guard let activeCaptureId else {
            return
        }

        let interruptionTypeRaw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 0
        let interruptionType = AVAudioSession.InterruptionType(rawValue: interruptionTypeRaw) ?? .began

        switch interruptionType {
        case .began:
            expectedResumeAfterInterruption = (state == .running)
            if state == .running {
                state = .paused
                emitStateChangedLocked(reason: "interruption_began")
            }

            emitEventLocked(
                name: "micInterruption",
                payload: [
                    "captureId": activeCaptureId,
                    "phase": "began",
                    "reason": "system_interruption"
                ]
            )
            emitErrorLocked(
                code: .interrupted,
                message: "Audio session interruption began.",
                recoverable: true,
                nativeCode: nil,
                captureId: activeCaptureId
            )
        case .ended:
            let optionsRaw = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
            let shouldResume = options.contains(.shouldResume)

            emitEventLocked(
                name: "micInterruption",
                payload: [
                    "captureId": activeCaptureId,
                    "phase": "ended",
                    "shouldResume": shouldResume
                ]
            )

            if shouldResume, expectedResumeAfterInterruption, state != .idle {
                do {
                    try session.setActive(true, options: [])
                    if let engine, !engine.isRunning {
                        try engine.start()
                    }
                    state = .running
                    emitStateChangedLocked(reason: "interruption_resumed")
                } catch {
                    state = .paused
                    emitStateChangedLocked(reason: "interruption_resume_failed")
                    emitErrorLocked(
                        code: .engineStartFailed,
                        message: "Unable to resume after interruption.",
                        recoverable: true,
                        nativeCode: "\(nsErrorCode(error))",
                        captureId: activeCaptureId
                    )
                }
            }

            expectedResumeAfterInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChangeLocked(_ notification: Notification) {
        let reasonRaw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 0
        let reason = AVAudioSession.RouteChangeReason(rawValue: reasonRaw)
        let reasonLabel = mapRouteChangeReason(reason)
        lastRouteChangeReason = reasonLabel

        var payload: [String: Any] = [
            "reason": reasonLabel
        ]

        if let activeCaptureId {
            payload["captureId"] = activeCaptureId
        }
        if let selectedInputId = session.currentRoute.inputs.first?.uid {
            payload["selectedInputId"] = selectedInputId
        }

        emitEventLocked(name: "micRouteChanged", payload: payload)
    }

    private func handleMediaServicesResetLocked(_ _: Notification) {
        mediaServicesResetCount += 1

        emitErrorLocked(
            code: .mediaServicesReset,
            message: "Audio media services were reset.",
            recoverable: true,
            nativeCode: nil,
            captureId: activeCaptureId
        )

        if state != .idle {
            state = .paused
            emitStateChangedLocked(reason: "media_services_reset")
        }
    }

    private func validateStartOptions(_ options: StartCaptureOptionsModel) throws {
        if options.chunkMs != Constants.defaultChunkMs {
            throw NativeMicControllerError(
                code: .internalError,
                message: "Only 20ms chunking is supported.",
                recoverable: false,
                nativeCode: nil
            )
        }

        if options.outputStreams.isEmpty {
            throw NativeMicControllerError(
                code: .internalError,
                message: "At least one output stream must be provided.",
                recoverable: false,
                nativeCode: nil
            )
        }

        switch options.profile {
        case .waveform:
            if options.mode != .measurement {
                throw NativeMicControllerError(
                    code: .internalError,
                    message: "Waveform profile requires measurement mode.",
                    recoverable: false,
                    nativeCode: nil
                )
            }
        case .pipecat:
            if options.mode != .voiceChat {
                throw NativeMicControllerError(
                    code: .internalError,
                    message: "Pipecat profile requires voice_chat mode.",
                    recoverable: false,
                    nativeCode: nil
                )
            }
        }
    }

    private func ensurePermissionForStart() throws {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        switch status {
        case .authorized:
            return
        case .denied:
            throw NativeMicControllerError(
                code: .permissionDenied,
                message: "Microphone permission denied.",
                recoverable: false,
                nativeCode: nil
            )
        case .restricted:
            throw NativeMicControllerError(
                code: .permissionRestricted,
                message: "Microphone access is restricted.",
                recoverable: false,
                nativeCode: nil
            )
        case .notDetermined:
            throw NativeMicControllerError(
                code: .permissionDenied,
                message: "Microphone permission not determined.",
                recoverable: false,
                nativeCode: nil
            )
        @unknown default:
            throw NativeMicControllerError(
                code: .internalError,
                message: "Unknown microphone permission state.",
                recoverable: false,
                nativeCode: nil
            )
        }
    }

    private func configureAudioSessionLocked(for options: StartCaptureOptionsModel) throws {
        let category: AVAudioSession.Category
        let mode: AVAudioSession.Mode
        var categoryOptions: AVAudioSession.CategoryOptions = []

        switch options.profile {
        case .waveform:
            category = .record
            mode = .measurement
        case .pipecat:
            category = .playAndRecord
            mode = .voiceChat
            categoryOptions.insert(.allowBluetoothHFP)
            if options.outputRoute != .receiver {
                categoryOptions.insert(.defaultToSpeaker)
            }
        }

        do {
            try session.setCategory(category, mode: mode, options: categoryOptions)
            try session.setPreferredIOBufferDuration(Double(options.chunkMs) / 1_000.0)
            try session.setActive(true, options: [])
        } catch {
            throw NativeMicControllerError(
                code: .audioSessionConfig,
                message: "Failed to configure AVAudioSession.",
                recoverable: false,
                nativeCode: "\(nsErrorCode(error))"
            )
        }
    }

    private func applyPreferredInputLocked() throws {
        guard let preferredInputId else {
            do {
                try session.setPreferredInput(nil)
                return
            } catch {
                throw NativeMicControllerError(
                    code: .routeChangeFailed,
                    message: "Failed to clear preferred input.",
                    recoverable: true,
                    nativeCode: "\(nsErrorCode(error))"
                )
            }
        }

        guard let availableInput = (session.availableInputs ?? []).first(where: { $0.uid == preferredInputId }) else {
            throw NativeMicControllerError(
                code: .routeChangeFailed,
                message: "Input device \(preferredInputId) was not found.",
                recoverable: false,
                nativeCode: nil
            )
        }

        do {
            try session.setPreferredInput(availableInput)
        } catch {
            throw NativeMicControllerError(
                code: .routeChangeFailed,
                message: "Failed to apply preferred input \(preferredInputId).",
                recoverable: true,
                nativeCode: "\(nsErrorCode(error))"
            )
        }
    }

    private func applyOutputRouteLocked(_ route: OutputRoute) throws {
        do {
            if let activeConfig, activeConfig.profile == .pipecat {
                var categoryOptions: AVAudioSession.CategoryOptions = [.allowBluetoothHFP]
                if route != .receiver {
                    categoryOptions.insert(.defaultToSpeaker)
                }
                try session.setCategory(.playAndRecord, mode: .voiceChat, options: categoryOptions)
            }

            switch route {
            case .speaker:
                try session.overrideOutputAudioPort(.speaker)
            case .system, .receiver:
                try session.overrideOutputAudioPort(.none)
            }
        } catch {
            throw NativeMicControllerError(
                code: .routeChangeFailed,
                message: "Failed to set output route to \(route.rawValue).",
                recoverable: true,
                nativeCode: "\(nsErrorCode(error))"
            )
        }
    }

    private func teardownTapLocked() {
        guard tapInstalled, let engine else {
            return
        }

        engine.inputNode.removeTap(onBus: 0)
        tapInstalled = false
    }

    private func teardownEngineLocked() {
        if tapInstalled {
            teardownTapLocked()
        }

        if let engine {
            engine.stop()
        }

        self.engine = nil
    }

    private func clearCaptureStateLocked() {
        activeConfig = nil
        activeCaptureId = nil
        outputPipelines.removeAll(keepingCapacity: false)
        captureStartPtsMs = 0
        totalFramesIn = 0
        totalFramesOut16k = 0
        totalFramesOut48k = 0
        actualInputSampleRate = 0
        actualInputChannels = 0
        levelSumSquares = 0
        levelPeak = 0
        levelFrames = 0
        levelIntervalFrames = 0
        expectedResumeAfterInterruption = false
        micEnabled = true
        inputRingBuffer.reset()
    }

    private func emitStateChangedLocked(reason: String) {
        var payload: [String: Any] = [
            "state": state.rawValue,
            "reason": reason
        ]
        if let activeCaptureId {
            payload["captureId"] = activeCaptureId
        }
        emitEventLocked(name: "micStateChanged", payload: payload)
    }

    private func emitErrorLocked(
        code: NativeMicErrorCode,
        message: String,
        recoverable: Bool,
        nativeCode: String?,
        captureId: String?
    ) {
        var payload: [String: Any] = [
            "code": code.rawValue,
            "message": message,
            "recoverable": recoverable
        ]
        if let captureId {
            payload["captureId"] = captureId
        }
        if let nativeCode {
            payload["nativeCode"] = nativeCode
        }
        emitEventLocked(name: "micError", payload: payload)
    }

    private func emitEventLocked(name: String, payload: [String: Any]) {
        eventQueue.async { [eventEmitter] in
            eventEmitter(name, payload)
        }
    }

    private func downmixToMono(_ buffer: AVAudioPCMBuffer) -> [Float] {
        let frameLength = Int(buffer.frameLength)
        guard frameLength > 0 else {
            return []
        }

        let channelCount = Int(buffer.format.channelCount)
        guard channelCount > 0 else {
            return []
        }

        var mono = Array(repeating: Float(0), count: frameLength)

        if let floatData = buffer.floatChannelData {
            if channelCount == 1 {
                let source = floatData[0]
                for frame in 0..<frameLength {
                    mono[frame] = source[frame]
                }
                return mono
            }

            let scale = 1.0 / Float(channelCount)
            for channelIndex in 0..<channelCount {
                let channel = floatData[channelIndex]
                for frame in 0..<frameLength {
                    mono[frame] += channel[frame] * scale
                }
            }
            return mono
        }

        if let int16Data = buffer.int16ChannelData {
            if channelCount == 1 {
                let source = int16Data[0]
                for frame in 0..<frameLength {
                    mono[frame] = Float(source[frame]) / 32768.0
                }
                return mono
            }

            let scale = 1.0 / Float(channelCount)
            for channelIndex in 0..<channelCount {
                let channel = int16Data[channelIndex]
                for frame in 0..<frameLength {
                    mono[frame] += (Float(channel[frame]) / 32768.0) * scale
                }
            }
            return mono
        }

        return []
    }

    private func encodePcm16(_ samples: [Int16]) -> String {
        let littleEndianSamples = samples.map { $0.littleEndian }
        let data = littleEndianSamples.withUnsafeBufferPointer { Data(buffer: $0) }
        return data.base64EncodedString()
    }

    private func mapPermissionState(from status: AVAuthorizationStatus) -> MicPermissionState {
        switch status {
        case .authorized:
            return .granted
        case .denied, .restricted:
            return .denied
        case .notDetermined:
            return .prompt
        @unknown default:
            return .prompt
        }
    }

    private func mapPortType(_ portType: AVAudioSession.Port) -> String {
        switch portType {
        case .builtInMic:
            return "built_in"
        case .headsetMic, .headphones, .lineIn:
            return "wired"
        case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
            return "bluetooth"
        case .usbAudio:
            return "usb"
        default:
            return "unknown"
        }
    }

    private func mapRouteChangeReason(_ reason: AVAudioSession.RouteChangeReason?) -> String {
        switch reason {
        case .newDeviceAvailable:
            return "new_device_available"
        case .oldDeviceUnavailable:
            return "old_device_unavailable"
        case .categoryChange:
            return "category_change"
        case .override:
            return "override"
        case .wakeFromSleep:
            return "wake_from_sleep"
        case .noSuitableRouteForCategory:
            return "no_suitable_route"
        case .routeConfigurationChange:
            return "route_configuration_change"
        case .some(.unknown), .none:
            return "unknown"
        @unknown default:
            return "unknown"
        }
    }

    private func nsErrorCode(_ error: Error) -> Int {
        (error as NSError).code
    }

    private func monotonicMs() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1_000.0)
    }

    private func syncOnProcessingQueue<T>(_ block: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return try block()
        }
        return try processingQueue.sync(execute: block)
    }
}

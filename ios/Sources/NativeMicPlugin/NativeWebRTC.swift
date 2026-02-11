import AVFoundation
import Foundation
import WebRTC

enum NativeWebRTCErrorCode: String {
    case webrtcUnavailable = "E_WEBRTC_UNAVAILABLE"
    case pcCreateFailed = "E_PC_CREATE_FAILED"
    case negotiationFailed = "E_NEGOTIATION_FAILED"
    case iceFailed = "E_ICE_FAILED"
    case dataChannelFailed = "E_DATA_CHANNEL_FAILED"
    case alreadyRunning = "E_ALREADY_RUNNING"
    case notRunning = "E_NOT_RUNNING"
    case invalidArgument = "E_INVALID_ARGUMENT"
    case internalError = "E_INTERNAL"
}

struct NativeWebRTCControllerError: Error {
    let code: NativeWebRTCErrorCode
    let message: String
    let recoverable: Bool
    let nativeCode: String?
}

enum NativeWebRTCState: String {
    case idle
    case initializing
    case connecting
    case connected
    case ready
    case reconnecting
    case disconnecting
    case error
}

struct RTCIceServerLikeModel {
    let urls: [String]
    let username: String?
    let credential: String?

    func toRTCIceServer() -> RTCIceServer {
        RTCIceServer(urlStrings: urls, username: username ?? "", credential: credential ?? "")
    }
}

struct WebRTCRequestInfoModel {
    let endpoint: String
    let headers: [String: String]
    let requestData: [String: Any]?
    let timeoutMs: Int
}

struct WebRTCMediaOptionsModel {
    let voiceProcessing: Bool
    let startMicEnabled: Bool
    let preferredInputId: String?
    let outputRoute: OutputRoute
    let outputRouteExplicit: Bool
}

struct WebRTCReconnectOptionsModel {
    let enabled: Bool
    let maxAttempts: Int
    let backoffMs: Int
}

struct NativeWebRTCConnectOptionsModel {
    let connectionId: String
    let webrtcRequest: WebRTCRequestInfoModel
    let iceServers: [RTCIceServerLikeModel]
    let waitForICEGathering: Bool
    let audioCodec: String?
    let videoCodec: String?
    let media: WebRTCMediaOptionsModel
    let reconnect: WebRTCReconnectOptionsModel
}

struct NativeWebRTCConnectResultModel {
    let connectionId: String
    let pcId: String?
    let selectedInputId: String?
    let selectedOutputRoute: OutputRoute
    let state: NativeWebRTCState

    func asDictionary() -> [String: Any] {
        var payload: [String: Any] = [
            "connectionId": connectionId,
            "selectedOutputRoute": selectedOutputRoute.rawValue,
            "state": state.rawValue
        ]

        if let pcId {
            payload["pcId"] = pcId
        }
        if let selectedInputId {
            payload["selectedInputId"] = selectedInputId
        }

        return payload
    }
}

struct NativeWebRTCStateResultModel {
    let connectionId: String
    let state: NativeWebRTCState
    let pcId: String?
    let iceConnectionState: String?
    let signalingState: String?

    func asDictionary() -> [String: Any] {
        var payload: [String: Any] = [
            "connectionId": connectionId,
            "state": state.rawValue
        ]

        if let pcId {
            payload["pcId"] = pcId
        }
        if let iceConnectionState {
            payload["iceConnectionState"] = iceConnectionState
        }
        if let signalingState {
            payload["signalingState"] = signalingState
        }

        return payload
    }
}

@objc public final class NativeWebRTCController: NSObject {
    typealias EventEmitter = (_ eventName: String, _ payload: [String: Any]) -> Void

    private enum Constants {
        static let defaultTimeoutMs = 15_000
        static let defaultReconnectMaxAttempts = 3
        static let defaultReconnectBackoffMs = 2_000
        static let candidateFlushDelayMs = 200
    }

    private let queue = DispatchQueue(label: "com.memora.ai.nativemic.webrtc", qos: .userInitiated)
    private let eventQueue = DispatchQueue(label: "com.memora.ai.nativemic.webrtc.events")
    private let queueKey = DispatchSpecificKey<Int>()
    private let session = AVAudioSession.sharedInstance()
    private let eventEmitter: EventEmitter

    private var state: NativeWebRTCState = .idle

    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var dataChannel: RTCDataChannel?
    private var localAudioSource: RTCAudioSource?
    private var localAudioTrack: RTCAudioTrack?

    private var activeConnectOptions: NativeWebRTCConnectOptionsModel?
    private var activeConnectionId: String?
    private var activePcId: String?
    private var selectedOutputRoute: OutputRoute = .receiver
    private var preferredInputId: String?
    private var micEnabled = true

    private var pendingCandidates: [RTCIceCandidate] = []
    private var canSendIceCandidates = false

    private var reconnectAttempts = 0
    private var manualDisconnectRequested = false

    private var candidateFlushWorkItem: DispatchWorkItem?
    private var reconnectWorkItem: DispatchWorkItem?
    private var statsWorkItem: DispatchWorkItem?

    private var previousCategory: AVAudioSession.Category?
    private var previousMode: AVAudioSession.Mode?
    private var previousCategoryOptions: AVAudioSession.CategoryOptions?

    private var localTrackStarted = false
    private var remoteAudioTrackStarted = false

    init(eventEmitter: @escaping EventEmitter) {
        self.eventEmitter = eventEmitter
        super.init()

        queue.setSpecific(key: queueKey, value: 1)
        RTCInitializeSSL()
    }

    deinit {
        syncOnQueue {
            cleanupConnectionLocked(resetIdentity: true, reason: "deinit")
            closeFactoryLocked()
        }
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

    func connect(options: NativeWebRTCConnectOptionsModel) throws -> NativeWebRTCConnectResultModel {
        try syncOnQueue {
            if state != .idle {
                throw NativeWebRTCControllerError(
                    code: .alreadyRunning,
                    message: "A WebRTC connection is already running.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            try ensureFactoryLocked()
            try validatePreferredInputLocked(options.media.preferredInputId)

            activeConnectOptions = options
            activeConnectionId = options.connectionId
            activePcId = nil
            selectedOutputRoute = options.media.outputRouteExplicit ? options.media.outputRoute : resolveDefaultOutputRoute()
            preferredInputId = options.media.preferredInputId
            micEnabled = options.media.startMicEnabled
            pendingCandidates.removeAll(keepingCapacity: false)
            canSendIceCandidates = false
            reconnectAttempts = 0
            manualDisconnectRequested = false

            updateStateLocked(.initializing, reason: "create_peer_connection")

            try configureAudioSessionLocked(voiceProcessing: options.media.voiceProcessing)
            try applyOutputRouteLocked(selectedOutputRoute)
            try createPeerConnectionLocked()
            try createLocalAudioTrackLocked(voiceProcessing: options.media.voiceProcessing)
            bindLocalAudioTrackLocked()
            try createDataChannelLocked()

            updateStateLocked(.connecting, reason: "create_offer")
            try negotiateLocked(restartPeerConnection: false)

            canSendIceCandidates = true
            flushIceCandidatesLocked()
            startStatsLoopLocked()

            if dataChannel?.readyState == .open {
                updateStateLocked(.ready, reason: "data_channel_open")
            } else {
                updateStateLocked(.connected, reason: "remote_description_set")
            }

            return NativeWebRTCConnectResultModel(
                connectionId: options.connectionId,
                pcId: activePcId,
                selectedInputId: resolveSelectedInputIdLocked(),
                selectedOutputRoute: selectedOutputRoute,
                state: state
            )
        }
    }

    func disconnect(connectionId: String, reason: String?) throws {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)
            manualDisconnectRequested = true
            updateStateLocked(.disconnecting, reason: normalize(reason) ?? "disconnect")
            cleanupConnectionLocked(resetIdentity: true, reason: normalize(reason) ?? "disconnect")
            updateStateLocked(.idle, reason: "disconnect_complete")
        }
    }

    func sendDataMessage(connectionId: String, data: String) throws {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            guard let dataChannel, dataChannel.readyState == .open else {
                throw NativeWebRTCControllerError(
                    code: .dataChannelFailed,
                    message: "Data channel is not open.",
                    recoverable: true,
                    nativeCode: nil
                )
            }

            guard let payload = data.data(using: .utf8) else {
                throw NativeWebRTCControllerError(
                    code: .invalidArgument,
                    message: "Data message is not valid UTF-8.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            let buffer = RTCDataBuffer(data: payload, isBinary: false)
            if !dataChannel.sendData(buffer) {
                throw NativeWebRTCControllerError(
                    code: .dataChannelFailed,
                    message: "Failed to send data channel message.",
                    recoverable: true,
                    nativeCode: nil
                )
            }
        }
    }

    func setMicEnabled(connectionId: String, enabled: Bool) throws {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            guard let localAudioTrack else {
                throw NativeWebRTCControllerError(
                    code: .notRunning,
                    message: "No active local audio track.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            micEnabled = enabled
            localAudioTrack.isEnabled = enabled
        }
    }

    func setPreferredInput(connectionId: String, inputId: String?) throws {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            preferredInputId = normalize(inputId)
            try validatePreferredInputLocked(preferredInputId)

            if let preferredInputId {
                guard let input = (session.availableInputs ?? []).first(where: { $0.uid == preferredInputId }) else {
                    throw NativeWebRTCControllerError(
                        code: .invalidArgument,
                        message: "Input device \(preferredInputId) was not found.",
                        recoverable: false,
                        nativeCode: nil
                    )
                }
                try session.setPreferredInput(input)
            } else {
                try session.setPreferredInput(nil)
            }

            emitRouteChangedLocked(reason: "set_preferred_input")
        }
    }

    func setOutputRoute(connectionId: String, route: OutputRoute) throws {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            selectedOutputRoute = route
            try applyOutputRouteLocked(route)
            emitRouteChangedLocked(reason: "set_output_route")
        }
    }

    func getState(connectionId: String) throws -> NativeWebRTCStateResultModel {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            return NativeWebRTCStateResultModel(
                connectionId: connectionId,
                state: state,
                pcId: activePcId,
                iceConnectionState: peerConnection.map { mapIceConnectionState($0.iceConnectionState) },
                signalingState: peerConnection.map { mapSignalingState($0.signalingState) }
            )
        }
    }

    func getDiagnostics(connectionId: String) throws -> [String: Any] {
        try syncOnQueue {
            try assertConnectionMatches(connectionId)

            var diagnostics: [String: Any] = [
                "connectionId": connectionId,
                "state": state.rawValue,
                "micEnabled": micEnabled,
                "selectedOutputRoute": selectedOutputRoute.rawValue,
                "pendingIceCandidates": pendingCandidates.count,
                "canSendIceCandidates": canSendIceCandidates,
                "reconnectAttempts": reconnectAttempts
            ]

            if let activePcId {
                diagnostics["pcId"] = activePcId
            }
            if let preferredInputId {
                diagnostics["preferredInputId"] = preferredInputId
            }
            if let peerConnection {
                diagnostics["iceConnectionState"] = mapIceConnectionState(peerConnection.iceConnectionState)
                diagnostics["signalingState"] = mapSignalingState(peerConnection.signalingState)
                diagnostics["connectionState"] = mapPeerConnectionState(peerConnection.connectionState)
            }

            return diagnostics
        }
    }

    private func ensureFactoryLocked() throws {
        if peerConnectionFactory != nil {
            return
        }

        let encoderFactory = RTCDefaultVideoEncoderFactory()
        let decoderFactory = RTCDefaultVideoDecoderFactory()
        peerConnectionFactory = RTCPeerConnectionFactory(encoderFactory: encoderFactory, decoderFactory: decoderFactory)

        if peerConnectionFactory == nil {
            throw NativeWebRTCControllerError(
                code: .webrtcUnavailable,
                message: "Native WebRTC initialization failed.",
                recoverable: false,
                nativeCode: nil
            )
        }
    }

    private func createPeerConnectionLocked() throws {
        guard let peerConnectionFactory else {
            throw NativeWebRTCControllerError(
                code: .pcCreateFailed,
                message: "Peer connection factory is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.iceServers = activeConnectOptions?.iceServers.map { $0.toRTCIceServer() } ?? []

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

        peerConnection = peerConnectionFactory.peerConnection(with: config, constraints: constraints, delegate: self)

        guard let peerConnection else {
            throw NativeWebRTCControllerError(
                code: .pcCreateFailed,
                message: "Failed to create peer connection.",
                recoverable: false,
                nativeCode: nil
            )
        }

        _ = peerConnection.addTransceiver(of: .audio)
        _ = peerConnection.addTransceiver(of: .video)
        _ = peerConnection.addTransceiver(of: .video)
    }

    private func createLocalAudioTrackLocked(voiceProcessing: Bool) throws {
        guard let peerConnectionFactory else {
            throw NativeWebRTCControllerError(
                code: .pcCreateFailed,
                message: "Peer connection factory is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let value = voiceProcessing ? "true" : "false"
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "googEchoCancellation": value,
                "googNoiseSuppression": value,
                "googAutoGainControl": value,
                "googHighpassFilter": value
            ],
            optionalConstraints: nil
        )

        localAudioSource = peerConnectionFactory.audioSource(with: constraints)
        localAudioTrack = peerConnectionFactory.audioTrack(with: localAudioSource!, trackId: "native-mic-audio")

        guard let localAudioTrack else {
            throw NativeWebRTCControllerError(
                code: .pcCreateFailed,
                message: "Failed to create local audio track.",
                recoverable: false,
                nativeCode: nil
            )
        }

        localAudioTrack.isEnabled = micEnabled
    }

    private func bindLocalAudioTrackLocked() {
        guard let peerConnection, let localAudioTrack else {
            return
        }

        if let sender = peerConnection.transceivers.first(where: { $0.mediaType == .audio })?.sender {
            sender.track = localAudioTrack
            sender.streamIds = ["native-mic-stream"]
        } else {
            _ = peerConnection.add(localAudioTrack, streamIds: ["native-mic-stream"])
        }

        if !localTrackStarted {
            localTrackStarted = true
            emitTrackEventLocked(eventName: "webrtcTrackStarted", kind: "audio", source: "local")
        }
    }

    private func createDataChannelLocked() throws {
        guard let peerConnection else {
            throw NativeWebRTCControllerError(
                code: .pcCreateFailed,
                message: "Peer connection is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let config = RTCDataChannelConfiguration()
        config.isOrdered = true

        guard let dataChannel = peerConnection.dataChannel(forLabel: "chat", configuration: config) else {
            throw NativeWebRTCControllerError(
                code: .dataChannelFailed,
                message: "Failed to create data channel.",
                recoverable: false,
                nativeCode: nil
            )
        }

        self.dataChannel = dataChannel
        dataChannel.delegate = self
    }

    private func negotiateLocked(restartPeerConnection: Bool) throws {
        guard let activeConnectOptions else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "WebRTC request options are unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let offer = try createOfferLocked()
        try setLocalDescriptionLocked(offer)

        if activeConnectOptions.waitForICEGathering {
            waitForIceGatheringLocked(timeoutMs: 2_000)
        }

        guard let localDescription = peerConnection?.localDescription else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Local description was not set.",
                recoverable: false,
                nativeCode: nil
            )
        }

        var payload: [String: Any] = [
            "sdp": localDescription.sdp,
            "type": sdpTypeString(localDescription.type),
            "restart_pc": restartPeerConnection
        ]

        if let activePcId {
            payload["pc_id"] = activePcId
        } else {
            payload["pc_id"] = NSNull()
        }

        if let requestData = activeConnectOptions.webrtcRequest.requestData {
            payload["requestData"] = requestData
        }

        let answerPayload = try executeJsonRequest(
            endpoint: activeConnectOptions.webrtcRequest.endpoint,
            method: "POST",
            payload: payload,
            headers: activeConnectOptions.webrtcRequest.headers,
            timeoutMs: activeConnectOptions.webrtcRequest.timeoutMs,
            errorCode: .negotiationFailed,
            errorMessage: "Failed to negotiate WebRTC session."
        )

        guard let sdp = answerPayload["sdp"] as? String, !sdp.isEmpty else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "WebRTC answer payload is missing SDP.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let answerType = sdpType(from: (answerPayload["type"] as? String) ?? "answer")
        activePcId = normalize(answerPayload["pc_id"] as? String)
            ?? normalize(answerPayload["pcId"] as? String)
            ?? activePcId

        let answer = RTCSessionDescription(type: answerType, sdp: sdp)
        try setRemoteDescriptionLocked(answer)
    }

    private func createOfferLocked() throws -> RTCSessionDescription {
        guard let peerConnection else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Peer connection is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let semaphore = DispatchSemaphore(value: 0)

        var offeredDescription: RTCSessionDescription?
        var offeredError: Error?

        peerConnection.offer(for: constraints) { description, error in
            offeredDescription = description
            offeredError = error
            semaphore.signal()
        }

        let waitResult = semaphore.wait(timeout: .now() + 10)
        if waitResult == .timedOut {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Timed out while creating WebRTC offer.",
                recoverable: false,
                nativeCode: nil
            )
        }

        if let offeredError {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Failed to create WebRTC offer.",
                recoverable: false,
                nativeCode: "\((offeredError as NSError).code)"
            )
        }

        guard let offeredDescription else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Failed to create WebRTC offer.",
                recoverable: false,
                nativeCode: nil
            )
        }

        return offeredDescription
    }

    private func setLocalDescriptionLocked(_ description: RTCSessionDescription) throws {
        guard let peerConnection else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Peer connection is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let semaphore = DispatchSemaphore(value: 0)
        var callbackError: Error?

        peerConnection.setLocalDescription(description) { error in
            callbackError = error
            semaphore.signal()
        }

        let waitResult = semaphore.wait(timeout: .now() + 10)
        if waitResult == .timedOut {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Timed out while setting local WebRTC description.",
                recoverable: false,
                nativeCode: nil
            )
        }

        if let callbackError {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Failed to set local WebRTC description.",
                recoverable: false,
                nativeCode: "\((callbackError as NSError).code)"
            )
        }
    }

    private func setRemoteDescriptionLocked(_ description: RTCSessionDescription) throws {
        guard let peerConnection else {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Peer connection is unavailable.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let semaphore = DispatchSemaphore(value: 0)
        var callbackError: Error?

        peerConnection.setRemoteDescription(description) { error in
            callbackError = error
            semaphore.signal()
        }

        let waitResult = semaphore.wait(timeout: .now() + 10)
        if waitResult == .timedOut {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Timed out while setting remote WebRTC description.",
                recoverable: false,
                nativeCode: nil
            )
        }

        if let callbackError {
            throw NativeWebRTCControllerError(
                code: .negotiationFailed,
                message: "Failed to set remote WebRTC description.",
                recoverable: false,
                nativeCode: "\((callbackError as NSError).code)"
            )
        }
    }

    private func waitForIceGatheringLocked(timeoutMs: Int) {
        let timeoutSeconds = Double(max(200, timeoutMs)) / 1_000.0
        let deadline = Date().addingTimeInterval(timeoutSeconds)

        while Date() < deadline {
            if peerConnection?.iceGatheringState == .complete {
                return
            }
            usleep(20_000)
        }
    }

    private func scheduleIceCandidateFlushLocked() {
        if candidateFlushWorkItem != nil {
            return
        }

        let workItem = DispatchWorkItem { [weak self] in
            guard let self else {
                return
            }
            self.syncOnQueue {
                self.candidateFlushWorkItem = nil
                self.flushIceCandidatesLocked()
            }
        }

        candidateFlushWorkItem = workItem
        queue.asyncAfter(deadline: .now() + .milliseconds(Constants.candidateFlushDelayMs), execute: workItem)
    }

    private func flushIceCandidatesLocked() {
        guard canSendIceCandidates, let activeConnectOptions, let activePcId, !pendingCandidates.isEmpty else {
            return
        }

        let candidates = pendingCandidates
        pendingCandidates.removeAll(keepingCapacity: true)

        let serializedCandidates: [[String: Any]] = candidates.map { candidate in
            [
                "candidate": candidate.sdp,
                "sdp_mid": candidate.sdpMid ?? NSNull(),
                "sdp_mline_index": candidate.sdpMLineIndex
            ]
        }

        do {
            _ = try executeJsonRequest(
                endpoint: activeConnectOptions.webrtcRequest.endpoint,
                method: "PATCH",
                payload: [
                    "pc_id": activePcId,
                    "candidates": serializedCandidates
                ],
                headers: activeConnectOptions.webrtcRequest.headers,
                timeoutMs: activeConnectOptions.webrtcRequest.timeoutMs,
                errorCode: .iceFailed,
                errorMessage: "Failed to send ICE candidates."
            )
        } catch let error as NativeWebRTCControllerError {
            emitErrorLocked(
                code: error.code,
                message: error.message,
                recoverable: true,
                nativeCode: error.nativeCode,
                connectionId: activeConnectionId
            )
        } catch {
            emitErrorLocked(
                code: .iceFailed,
                message: "Failed to send ICE candidates.",
                recoverable: true,
                nativeCode: "\((error as NSError).code)",
                connectionId: activeConnectionId
            )
        }
    }

    private func executeJsonRequest(
        endpoint: String,
        method: String,
        payload: [String: Any],
        headers: [String: String],
        timeoutMs: Int,
        errorCode: NativeWebRTCErrorCode,
        errorMessage: String
    ) throws -> [String: Any] {
        guard let url = URL(string: endpoint) else {
            throw NativeWebRTCControllerError(
                code: .invalidArgument,
                message: "webrtcRequest.endpoint must be a valid URL.",
                recoverable: false,
                nativeCode: nil
            )
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = Double(max(1_000, timeoutMs)) / 1_000.0
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        for (header, value) in headers {
            request.setValue(value, forHTTPHeaderField: header)
        }

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        } catch {
            throw NativeWebRTCControllerError(
                code: errorCode,
                message: errorMessage,
                recoverable: false,
                nativeCode: "\((error as NSError).code)"
            )
        }

        let semaphore = DispatchSemaphore(value: 0)
        var responseData: Data?
        var responseError: Error?
        var statusCode: Int?

        URLSession.shared.dataTask(with: request) { data, response, error in
            responseData = data
            responseError = error
            statusCode = (response as? HTTPURLResponse)?.statusCode
            semaphore.signal()
        }.resume()

        let waitResult = semaphore.wait(timeout: .now() + request.timeoutInterval + 2)
        if waitResult == .timedOut {
            throw NativeWebRTCControllerError(
                code: errorCode,
                message: errorMessage,
                recoverable: false,
                nativeCode: "timeout"
            )
        }

        if let responseError {
            throw NativeWebRTCControllerError(
                code: errorCode,
                message: errorMessage,
                recoverable: false,
                nativeCode: "\((responseError as NSError).code)"
            )
        }

        if let statusCode, !(200...299).contains(statusCode) {
            throw NativeWebRTCControllerError(
                code: errorCode,
                message: errorMessage,
                recoverable: false,
                nativeCode: "\(statusCode)"
            )
        }

        guard let responseData, !responseData.isEmpty else {
            return [:]
        }

        let object = try JSONSerialization.jsonObject(with: responseData)
        guard let dictionary = object as? [String: Any] else {
            throw NativeWebRTCControllerError(
                code: errorCode,
                message: errorMessage,
                recoverable: false,
                nativeCode: nil
            )
        }

        return dictionary
    }

    private func startStatsLoopLocked() {
        if statsWorkItem != nil {
            return
        }

        let workItem = DispatchWorkItem { [weak self] in
            guard let self else {
                return
            }
            self.syncOnQueue {
                self.statsWorkItem = nil
                self.emitAudioLevelsLocked()
                if self.state != .idle && self.state != .error {
                    self.startStatsLoopLocked()
                }
            }
        }

        statsWorkItem = workItem
        queue.asyncAfter(deadline: .now() + .milliseconds(600), execute: workItem)
    }

    private func emitAudioLevelsLocked() {
        guard let peerConnection, let activeConnectionId else {
            return
        }

        peerConnection.statistics { [weak self] report in
            guard let self else {
                return
            }

            self.syncOnQueue {
                var localLevel: Double = -1
                var remoteLevel: Double = -1

                for statistic in report.statistics.values {
                    let values = statistic.values
                    let type = statistic.type

                    if type == "media-source", self.isAudioStatistic(values: values) {
                        localLevel = max(localLevel, self.normalizeLegacyAudioLevel(self.readNumericValue(values["audioLevel"])))
                    }

                    if (type == "inbound-rtp" || type == "track"), self.isAudioStatistic(values: values) {
                        remoteLevel = max(remoteLevel, self.normalizeLegacyAudioLevel(self.readNumericValue(values["audioLevel"])))
                    }
                }

                if localLevel >= 0 {
                    self.emitEventLocked(
                        name: "webrtcLocalAudioLevel",
                        payload: [
                            "connectionId": activeConnectionId,
                            "level": localLevel
                        ]
                    )
                }

                if remoteLevel >= 0 {
                    self.emitEventLocked(
                        name: "webrtcRemoteAudioLevel",
                        payload: [
                            "connectionId": activeConnectionId,
                            "level": remoteLevel
                        ]
                    )
                }
            }
        }
    }

    private func normalizeLegacyAudioLevel(_ value: Double) -> Double {
        if value < 0 {
            return -1
        }

        if value <= 1 {
            return value
        }

        let normalized = value / 32_767.0
        return min(1, max(0, normalized))
    }

    private func readNumericValue(_ value: NSObject?) -> Double {
        guard let value else {
            return -1
        }

        if let number = value as? NSNumber {
            return number.doubleValue
        }

        if let text = value as? NSString {
            return Double(text as String) ?? -1
        }

        return -1
    }

    private func isAudioStatistic(values: [String: NSObject]) -> Bool {
        if let kind = values["kind"] as? NSString, kind == "audio" {
            return true
        }
        if let mediaType = values["mediaType"] as? NSString, mediaType == "audio" {
            return true
        }
        return false
    }

    private func scheduleReconnectLocked(reason: String) {
        guard let activeConnectOptions, !manualDisconnectRequested, activeConnectionId != nil else {
            return
        }

        if !activeConnectOptions.reconnect.enabled {
            updateStateLocked(.error, reason: reason)
            emitErrorLocked(
                code: .iceFailed,
                message: "WebRTC connection dropped.",
                recoverable: true,
                nativeCode: nil,
                connectionId: activeConnectionId
            )
            return
        }

        if reconnectAttempts >= activeConnectOptions.reconnect.maxAttempts {
            updateStateLocked(.error, reason: "reconnect_limit_reached")
            emitErrorLocked(
                code: .iceFailed,
                message: "Maximum reconnect attempts reached.",
                recoverable: false,
                nativeCode: nil,
                connectionId: activeConnectionId
            )
            return
        }

        if reconnectWorkItem != nil {
            return
        }

        reconnectAttempts += 1
        updateStateLocked(.reconnecting, reason: reason)

        let workItem = DispatchWorkItem { [weak self] in
            guard let self else {
                return
            }

            self.syncOnQueue {
                self.reconnectWorkItem = nil
                do {
                    try self.performReconnectLocked()
                } catch let error as NativeWebRTCControllerError {
                    self.emitErrorLocked(
                        code: error.code,
                        message: error.message,
                        recoverable: true,
                        nativeCode: error.nativeCode,
                        connectionId: self.activeConnectionId
                    )
                    self.scheduleReconnectLocked(reason: "reconnect_failed")
                } catch {
                    self.emitErrorLocked(
                        code: .internalError,
                        message: "Unexpected reconnect failure.",
                        recoverable: true,
                        nativeCode: "\((error as NSError).code)",
                        connectionId: self.activeConnectionId
                    )
                    self.scheduleReconnectLocked(reason: "reconnect_failed")
                }
            }
        }

        reconnectWorkItem = workItem
        queue.asyncAfter(
            deadline: .now() + .milliseconds(activeConnectOptions.reconnect.backoffMs),
            execute: workItem
        )
    }

    private func performReconnectLocked() throws {
        guard let activeConnectOptions else {
            return
        }

        closePeerConnectionOnlyLocked()
        pendingCandidates.removeAll(keepingCapacity: false)
        canSendIceCandidates = false

        try createPeerConnectionLocked()
        try createLocalAudioTrackLocked(voiceProcessing: activeConnectOptions.media.voiceProcessing)
        bindLocalAudioTrackLocked()
        try createDataChannelLocked()
        try negotiateLocked(restartPeerConnection: true)

        canSendIceCandidates = true
        flushIceCandidatesLocked()
        updateStateLocked(.connected, reason: "reconnected")
    }

    private func configureAudioSessionLocked(voiceProcessing: Bool) throws {
        previousCategory = session.category
        previousMode = session.mode
        previousCategoryOptions = session.categoryOptions

        var options: AVAudioSession.CategoryOptions = [.allowBluetoothHFP]
        if selectedOutputRoute != .receiver {
            options.insert(.defaultToSpeaker)
        }

        let mode: AVAudioSession.Mode = voiceProcessing ? .voiceChat : .default
        try session.setCategory(.playAndRecord, mode: mode, options: options)
        try session.setActive(true, options: [])
    }

    private func applyOutputRouteLocked(_ route: OutputRoute) throws {
        switch route {
        case .speaker:
            try session.overrideOutputAudioPort(.speaker)
        case .system, .receiver:
            try session.overrideOutputAudioPort(.none)
        }
    }

    private func resolveDefaultOutputRoute() -> OutputRoute {
        let outputs = session.currentRoute.outputs
        for output in outputs {
            switch output.portType {
            case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .headphones, .headsetMic, .usbAudio:
                return .system
            default:
                break
            }
        }

        return .receiver
    }

    private func validatePreferredInputLocked(_ preferredInputId: String?) throws {
        guard let preferredInputId else {
            return
        }

        let availableInputs = session.availableInputs ?? []
        guard availableInputs.contains(where: { $0.uid == preferredInputId }) else {
            throw NativeWebRTCControllerError(
                code: .invalidArgument,
                message: "Input device \(preferredInputId) was not found.",
                recoverable: false,
                nativeCode: nil
            )
        }
    }

    private func resolveSelectedInputIdLocked() -> String? {
        session.preferredInput?.uid ?? session.currentRoute.inputs.first?.uid ?? preferredInputId
    }

    private func emitRouteChangedLocked(reason: String) {
        var payload: [String: Any] = [
            "reason": reason
        ]

        if let activeConnectionId {
            payload["connectionId"] = activeConnectionId
        }

        if let selectedInputId = resolveSelectedInputIdLocked() {
            payload["selectedInputId"] = selectedInputId
        }

        emitEventLocked(name: "micRouteChanged", payload: payload)
    }

    private func closePeerConnectionOnlyLocked() {
        candidateFlushWorkItem?.cancel()
        candidateFlushWorkItem = nil

        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil

        statsWorkItem?.cancel()
        statsWorkItem = nil

        if let dataChannel {
            dataChannel.delegate = nil
            dataChannel.close()
            self.dataChannel = nil
        }

        if localTrackStarted {
            emitTrackEventLocked(eventName: "webrtcTrackStopped", kind: "audio", source: "local")
            localTrackStarted = false
        }

        if remoteAudioTrackStarted {
            emitTrackEventLocked(eventName: "webrtcTrackStopped", kind: "audio", source: "remote")
            remoteAudioTrackStarted = false
        }

        localAudioTrack?.isEnabled = false
        localAudioTrack = nil
        localAudioSource = nil

        peerConnection?.close()
        peerConnection = nil

        pendingCandidates.removeAll(keepingCapacity: false)
        canSendIceCandidates = false
    }

    private func cleanupConnectionLocked(resetIdentity: Bool, reason: String) {
        closePeerConnectionOnlyLocked()
        teardownAudioSessionLocked()

        if resetIdentity {
            activeConnectOptions = nil
            activeConnectionId = nil
            activePcId = nil
            selectedOutputRoute = .receiver
            preferredInputId = nil
            micEnabled = true
            reconnectAttempts = 0
            manualDisconnectRequested = false
        }

        if state != .idle {
            updateStateLocked(.idle, reason: reason)
        }
    }

    private func teardownAudioSessionLocked() {
        do {
            try session.overrideOutputAudioPort(.none)
            try session.setActive(false, options: [.notifyOthersOnDeactivation])

            if let previousCategory, let previousMode {
                try session.setCategory(previousCategory, mode: previousMode, options: previousCategoryOptions ?? [])
            }
        } catch {
            // best effort
        }

        previousCategory = nil
        previousMode = nil
        previousCategoryOptions = nil
    }

    private func closeFactoryLocked() {
        peerConnectionFactory = nil
    }

    private func assertConnectionMatches(_ connectionId: String) throws {
        guard
            let normalized = normalize(connectionId),
            let activeConnectionId,
            activeConnectionId == normalized,
            state != .idle,
            state != .error
        else {
            throw NativeWebRTCControllerError(
                code: .notRunning,
                message: "No active WebRTC connection matches \(connectionId).",
                recoverable: false,
                nativeCode: nil
            )
        }
    }

    private func updateStateLocked(_ nextState: NativeWebRTCState, reason: String) {
        state = nextState

        var payload: [String: Any] = [
            "state": nextState.rawValue,
            "reason": reason
        ]

        if let activeConnectionId {
            payload["connectionId"] = activeConnectionId
        }
        if let activePcId {
            payload["pcId"] = activePcId
        }

        emitEventLocked(name: "webrtcStateChanged", payload: payload)
    }

    private func emitTrackEventLocked(eventName: String, kind: String, source: String) {
        var payload: [String: Any] = [
            "kind": kind,
            "source": source
        ]

        if let activeConnectionId {
            payload["connectionId"] = activeConnectionId
        }

        emitEventLocked(name: eventName, payload: payload)
    }

    private func emitErrorLocked(
        code: NativeWebRTCErrorCode,
        message: String,
        recoverable: Bool,
        nativeCode: String?,
        connectionId: String?
    ) {
        var payload: [String: Any] = [
            "code": code.rawValue,
            "message": message,
            "recoverable": recoverable
        ]

        if let connectionId {
            payload["connectionId"] = connectionId
        }

        if let nativeCode {
            payload["nativeCode"] = nativeCode
        }

        emitEventLocked(name: "webrtcError", payload: payload)
    }

    private func emitEventLocked(name: String, payload: [String: Any]) {
        eventQueue.async { [eventEmitter] in
            eventEmitter(name, payload)
        }
    }

    private func mapSignalingState(_ state: RTCSignalingState) -> String {
        switch state {
        case .stable:
            return "stable"
        case .haveLocalOffer:
            return "have-local-offer"
        case .haveLocalPrAnswer:
            return "have-local-pranswer"
        case .haveRemoteOffer:
            return "have-remote-offer"
        case .haveRemotePrAnswer:
            return "have-remote-pranswer"
        case .closed:
            return "closed"
        @unknown default:
            return "unknown"
        }
    }

    private func mapIceConnectionState(_ state: RTCIceConnectionState) -> String {
        switch state {
        case .new:
            return "new"
        case .checking:
            return "checking"
        case .connected:
            return "connected"
        case .completed:
            return "completed"
        case .failed:
            return "failed"
        case .disconnected:
            return "disconnected"
        case .closed:
            return "closed"
        case .count:
            return "count"
        @unknown default:
            return "unknown"
        }
    }

    private func mapPeerConnectionState(_ state: RTCPeerConnectionState) -> String {
        switch state {
        case .new:
            return "new"
        case .connecting:
            return "connecting"
        case .connected:
            return "connected"
        case .disconnected:
            return "disconnected"
        case .failed:
            return "failed"
        case .closed:
            return "closed"
        @unknown default:
            return "unknown"
        }
    }

    private func sdpType(from value: String) -> RTCSdpType {
        switch value.lowercased() {
        case "offer":
            return .offer
        case "pranswer":
            return .prAnswer
        case "rollback":
            // Some WebRTC iOS SDK versions do not expose RTCSdpType.rollback.
            // This negotiation flow expects an answer, so fallback safely.
            return .answer
        default:
            return .answer
        }
    }

    private func sdpTypeString(_ type: RTCSdpType) -> String {
        switch type {
        case .offer:
            return "offer"
        case .prAnswer:
            return "pranswer"
        case .answer:
            fallthrough
        @unknown default:
            return "answer"
        }
    }

    private func normalize(_ value: String?) -> String? {
        guard let value else {
            return nil
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private func syncOnQueue<T>(_ block: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return try block()
        }

        return try queue.sync(execute: block)
    }

    private func dispatchOnQueue(_ block: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()
            return
        }

        queue.async(execute: block)
    }
}

extension NativeWebRTCController: RTCPeerConnectionDelegate {
    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            self.pendingCandidates.append(candidate)
            self.scheduleIceCandidateFlushLocked()
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            switch newState {
            case .connected, .completed:
                self.reconnectAttempts = 0
                if self.state == .reconnecting {
                    self.updateStateLocked(.connected, reason: "reconnected")
                }
            case .disconnected, .failed:
                self.scheduleReconnectLocked(reason: "ice_disconnected")
            case .closed:
                if self.state == .connected || self.state == .ready {
                    self.scheduleReconnectLocked(reason: "ice_closed")
                }
            default:
                break
            }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            self.dataChannel = dataChannel
            dataChannel.delegate = self
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCPeerConnectionState) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            if stateChanged == .failed {
                self.scheduleReconnectLocked(reason: "peer_connection_failed")
            }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            if transceiver.mediaType == .audio {
                if !self.remoteAudioTrackStarted {
                    self.remoteAudioTrackStarted = true
                    self.emitTrackEventLocked(eventName: "webrtcTrackStarted", kind: "audio", source: "remote")
                }
            } else if transceiver.mediaType == .video {
                self.emitTrackEventLocked(eventName: "webrtcTrackStarted", kind: "video", source: "remote")
            }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCSignalingState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
}

extension NativeWebRTCController: RTCDataChannelDelegate {
    public func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            switch dataChannel.readyState {
            case .open:
                self.updateStateLocked(.ready, reason: "data_channel_open")
            case .closing, .closed:
                if self.state == .ready || self.state == .connected {
                    self.scheduleReconnectLocked(reason: "data_channel_closed")
                }
            default:
                break
            }
        }
    }

    public func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        dispatchOnQueue { [weak self] in
            guard let self else {
                return
            }
            let payloadString: String
            if buffer.isBinary {
                payloadString = buffer.data.base64EncodedString()
            } else {
                payloadString = String(data: buffer.data, encoding: .utf8) ?? ""
            }

            var payload: [String: Any] = [
                "data": payloadString
            ]
            if let activeConnectionId = self.activeConnectionId {
                payload["connectionId"] = activeConnectionId
            }

            self.emitEventLocked(name: "webrtcDataMessage", payload: payload)
            self.maybeHandleSignallingMessageLocked(payloadString)
        }
    }

    private func maybeHandleSignallingMessageLocked(_ message: String) {
        guard !message.isEmpty,
              let data = message.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (json["type"] as? String) == "signalling",
              let signal = json["message"] as? [String: Any],
              let signalType = signal["type"] as? String
        else {
            return
        }

        if signalType == "renegotiate" {
            scheduleReconnectLocked(reason: "renegotiate_requested")
            return
        }

        if (signalType == "peerLeft" || signalType == "peer_left"), let activeConnectionId {
            do {
                try disconnect(connectionId: activeConnectionId, reason: "peerLeft")
            } catch {
                // best effort
            }
        }
    }
}

extension NativeWebRTCController {
    static func parseConnectOptions(_ rawOptions: [String: Any]) throws -> NativeWebRTCConnectOptionsModel {
        let connectionId = normalizeStatic(rawOptions["connectionId"] as? String) ?? UUID().uuidString

        guard let requestObject = rawOptions["webrtcRequest"] as? [String: Any] else {
            throw NativeWebRTCControllerError(
                code: .invalidArgument,
                message: "webrtcRequest is required.",
                recoverable: false,
                nativeCode: nil
            )
        }

        guard let endpoint = normalizeStatic(requestObject["endpoint"] as? String) else {
            throw NativeWebRTCControllerError(
                code: .invalidArgument,
                message: "webrtcRequest.endpoint is required.",
                recoverable: false,
                nativeCode: nil
            )
        }

        let headers = (requestObject["headers"] as? [String: Any] ?? [:]).reduce(into: [String: String]()) { partial, entry in
            partial[entry.key] = String(describing: entry.value)
        }

        let requestData = requestObject["requestData"] as? [String: Any]
        let timeoutMs = max(1_000, (requestObject["timeoutMs"] as? Int) ?? Constants.defaultTimeoutMs)

        let request = WebRTCRequestInfoModel(
            endpoint: endpoint,
            headers: headers,
            requestData: requestData,
            timeoutMs: timeoutMs
        )

        let iceServers = try parseIceServers(rawOptions)

        let waitForICEGathering = (rawOptions["waitForICEGathering"] as? Bool) ?? false
        let audioCodec = parseNullableCodec(rawOptions["audioCodec"])
        let videoCodec = parseNullableCodec(rawOptions["videoCodec"])

        let mediaObject = rawOptions["media"] as? [String: Any]
        let voiceProcessing = mediaObject?["voiceProcessing"] as? Bool ?? true
        let startMicEnabled = mediaObject?["startMicEnabled"] as? Bool ?? true
        let preferredInputId = normalizeStatic(mediaObject?["preferredInputId"] as? String)

        var outputRoute = OutputRoute.receiver
        var outputRouteExplicit = false
        if let outputRouteValue = mediaObject?["outputRoute"] as? String {
            guard let parsed = OutputRoute(rawValue: outputRouteValue) else {
                throw NativeWebRTCControllerError(
                    code: .invalidArgument,
                    message: "media.outputRoute must be one of: system, speaker, receiver.",
                    recoverable: false,
                    nativeCode: nil
                )
            }
            outputRoute = parsed
            outputRouteExplicit = true
        }

        let media = WebRTCMediaOptionsModel(
            voiceProcessing: voiceProcessing,
            startMicEnabled: startMicEnabled,
            preferredInputId: preferredInputId,
            outputRoute: outputRoute,
            outputRouteExplicit: outputRouteExplicit
        )

        let reconnectObject = rawOptions["reconnect"] as? [String: Any]
        let reconnect = WebRTCReconnectOptionsModel(
            enabled: reconnectObject?["enabled"] as? Bool ?? true,
            maxAttempts: max(0, reconnectObject?["maxAttempts"] as? Int ?? Constants.defaultReconnectMaxAttempts),
            backoffMs: max(250, reconnectObject?["backoffMs"] as? Int ?? Constants.defaultReconnectBackoffMs)
        )

        return NativeWebRTCConnectOptionsModel(
            connectionId: connectionId,
            webrtcRequest: request,
            iceServers: iceServers,
            waitForICEGathering: waitForICEGathering,
            audioCodec: audioCodec,
            videoCodec: videoCodec,
            media: media,
            reconnect: reconnect
        )
    }

    static func parseIceServers(_ rawOptions: [String: Any]) throws -> [RTCIceServerLikeModel] {
        guard
            let iceConfig = rawOptions["iceConfig"] as? [String: Any],
            let rawServers = iceConfig["iceServers"] as? [Any]
        else {
            return []
        }

        return try rawServers.enumerated().map { index, rawServer in
            guard let serverObject = rawServer as? [String: Any] else {
                throw NativeWebRTCControllerError(
                    code: .invalidArgument,
                    message: "iceConfig.iceServers[\(index)] must be an object.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            let urls: [String]
            if let urlsArray = serverObject["urls"] as? [Any] {
                urls = urlsArray.compactMap { value in
                    normalizeStatic(String(describing: value))
                }
            } else {
                urls = [normalizeStatic(serverObject["urls"] as? String)].compactMap { $0 }
            }

            if urls.isEmpty {
                throw NativeWebRTCControllerError(
                    code: .invalidArgument,
                    message: "iceConfig.iceServers[\(index)].urls is required.",
                    recoverable: false,
                    nativeCode: nil
                )
            }

            return RTCIceServerLikeModel(
                urls: urls,
                username: normalizeStatic(serverObject["username"] as? String),
                credential: normalizeStatic(serverObject["credential"] as? String)
            )
        }
    }

    static func parseNullableCodec(_ rawCodec: Any?) -> String? {
        guard let rawCodec else {
            return nil
        }

        let value = normalizeStatic(String(describing: rawCodec))
        guard let value else {
            return nil
        }

        return value.lowercased() == "default" ? nil : value
    }

    private static func normalizeStatic(_ value: String?) -> String? {
        guard let value else {
            return nil
        }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

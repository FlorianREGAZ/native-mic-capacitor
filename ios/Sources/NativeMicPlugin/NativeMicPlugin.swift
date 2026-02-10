import Capacitor
import Foundation

@objc(NativeMicPlugin)
public class NativeMicPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativeMicPlugin"
    public let jsName = "NativeMic"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPreferredInput", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setOutputRoute", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startCapture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopCapture", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setMicEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDiagnostics", returnType: CAPPluginReturnPromise)
    ]

    private lazy var controller = NativeMicController { [weak self] eventName, payload in
        DispatchQueue.main.async {
            self?.notifyListeners(eventName, data: payload)
        }
    }

    @objc func isAvailable(_ call: CAPPluginCall) {
        call.resolve(controller.isAvailable())
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        call.resolve([
            "microphone": controller.checkPermissions().rawValue
        ])
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        controller.requestPermissions { [weak self] state in
            DispatchQueue.main.async {
                guard self != nil else {
                    return
                }
                call.resolve([
                    "microphone": state.rawValue
                ])
            }
        }
    }

    @objc func getDevices(_ call: CAPPluginCall) {
        let devices = controller.getDevices()
        var payload: [String: Any] = [
            "inputs": devices.inputs.map { $0.asDictionary() }
        ]
        if let selectedInputId = devices.selectedInputId {
            payload["selectedInputId"] = selectedInputId
        }
        call.resolve(payload)
    }

    @objc func setPreferredInput(_ call: CAPPluginCall) {
        do {
            try controller.setPreferredInput(call.getString("inputId"))
            call.resolve()
        } catch let error as NativeMicControllerError {
            reject(call, with: error, captureId: nil)
        } catch {
            rejectUnexpected(call, error: error, captureId: nil)
        }
    }

    @objc func setOutputRoute(_ call: CAPPluginCall) {
        guard let routeValue = call.getString("route"), let route = OutputRoute(rawValue: routeValue) else {
            reject(
                call,
                code: .internalError,
                message: "route must be one of: system, speaker, receiver.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        do {
            try controller.setOutputRoute(route)
            call.resolve()
        } catch let error as NativeMicControllerError {
            reject(call, with: error, captureId: nil)
        } catch {
            rejectUnexpected(call, error: error, captureId: nil)
        }
    }

    @objc func startCapture(_ call: CAPPluginCall) {
        guard let profileValue = call.getString("profile"), let profile = MicProfile(rawValue: profileValue) else {
            reject(
                call,
                code: .internalError,
                message: "profile is required and must be waveform or pipecat.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        guard let modeValue = call.getString("mode"), let mode = SessionMode(rawValue: modeValue) else {
            reject(
                call,
                code: .internalError,
                message: "mode is required and must be measurement or voice_chat.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        guard let streams = parseOutputStreams(call), !streams.isEmpty else {
            reject(
                call,
                code: .internalError,
                message: "outputStreams must include pcm16k_s16le, pcm48k_s16le, or both.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        let chunkMs = call.getInt("chunkMs") ?? 20
        let emitAudioLevel = call.getBool("emitAudioLevel") ?? true
        let audioLevelIntervalMs = call.getInt("audioLevelIntervalMs") ?? 50
        let preferredInputId = call.getString("preferredInputId")
        let outputRoute = OutputRoute(rawValue: call.getString("outputRoute") ?? "system") ?? .system

        let voiceProcessingDefault = profile == .pipecat
        let voiceProcessing = call.getBool("voiceProcessing") ?? voiceProcessingDefault

        let options = StartCaptureOptionsModel(
            profile: profile,
            mode: mode,
            outputStreams: streams,
            chunkMs: chunkMs,
            emitAudioLevel: emitAudioLevel,
            audioLevelIntervalMs: max(20, audioLevelIntervalMs),
            voiceProcessing: voiceProcessing,
            preferredInputId: preferredInputId,
            outputRoute: outputRoute
        )

        do {
            let result = try controller.startCapture(options: options)
            call.resolve(result.asDictionary())
        } catch let error as NativeMicControllerError {
            reject(call, with: error, captureId: nil)
        } catch {
            rejectUnexpected(call, error: error, captureId: nil)
        }
    }

    @objc func stopCapture(_ call: CAPPluginCall) {
        guard let captureId = call.getString("captureId"), !captureId.isEmpty else {
            reject(
                call,
                code: .internalError,
                message: "captureId is required.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        let flushTimeoutMs = call.getInt("flushTimeoutMs") ?? 150

        do {
            let result = try controller.stopCapture(captureId: captureId, flushTimeoutMs: flushTimeoutMs)
            call.resolve(result.asDictionary())
        } catch let error as NativeMicControllerError {
            reject(call, with: error, captureId: captureId)
        } catch {
            rejectUnexpected(call, error: error, captureId: captureId)
        }
    }

    @objc func setMicEnabled(_ call: CAPPluginCall) {
        guard let captureId = call.getString("captureId"), !captureId.isEmpty else {
            reject(
                call,
                code: .internalError,
                message: "captureId is required.",
                recoverable: false,
                captureId: nil
            )
            return
        }

        guard let enabled = call.getBool("enabled") else {
            reject(
                call,
                code: .internalError,
                message: "enabled must be a boolean.",
                recoverable: false,
                captureId: captureId
            )
            return
        }

        do {
            try controller.setMicEnabled(captureId: captureId, enabled: enabled)
            call.resolve()
        } catch let error as NativeMicControllerError {
            reject(call, with: error, captureId: captureId)
        } catch {
            rejectUnexpected(call, error: error, captureId: captureId)
        }
    }

    @objc func getState(_ call: CAPPluginCall) {
        call.resolve([
            "state": controller.getState().rawValue
        ])
    }

    @objc func getDiagnostics(_ call: CAPPluginCall) {
        call.resolve(controller.getDiagnostics())
    }

    private func parseOutputStreams(_ call: CAPPluginCall) -> [OutputStream]? {
        guard let rawStreams = call.getArray("outputStreams") else {
            return nil
        }

        var streams: [OutputStream] = []
        var seen = Set<OutputStream>()
        for rawStream in rawStreams {
            guard let streamValue = rawStream as? String, let stream = OutputStream(rawValue: streamValue) else {
                return nil
            }
            if !seen.contains(stream) {
                seen.insert(stream)
                streams.append(stream)
            }
        }

        return streams
    }

    private func reject(_ call: CAPPluginCall, with error: NativeMicControllerError, captureId: String?) {
        reject(
            call,
            code: error.code,
            message: error.message,
            recoverable: error.recoverable,
            captureId: captureId,
            nativeCode: error.nativeCode
        )
    }

    private func reject(
        _ call: CAPPluginCall,
        code: NativeMicErrorCode,
        message: String,
        recoverable: Bool,
        captureId: String?,
        nativeCode: String? = nil
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
        notifyListeners("micError", data: payload)
        call.reject(message, code.rawValue, nil, payload)
    }

    private func rejectUnexpected(_ call: CAPPluginCall, error: Error, captureId: String?) {
        reject(
            call,
            code: .internalError,
            message: "Unexpected native error.",
            recoverable: false,
            captureId: captureId,
            nativeCode: "\((error as NSError).code)"
        )
    }
}

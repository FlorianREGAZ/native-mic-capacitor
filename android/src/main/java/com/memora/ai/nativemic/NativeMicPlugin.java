package com.memora.ai.nativemic;

import android.Manifest;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;

@CapacitorPlugin(name = "NativeMic", permissions = { @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }) })
public class NativeMicPlugin extends Plugin {

    private NativeMic controller;
    private NativeWebRTC webRtcController;

    @Override
    public void load() {
        controller = new NativeMic(getContext(), this::emitEventToJs);
        webRtcController = new NativeWebRTC(getContext(), this::emitEventToJs);
    }

    @Override
    protected void handleOnDestroy() {
        if (controller != null) {
            controller.destroy();
        }
        if (webRtcController != null) {
            webRtcController.destroy();
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        call.resolve(controller.isAvailable());
    }

    @PluginMethod
    @Override
    public void checkPermissions(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("microphone", toMicPermissionState(getPermissionState("microphone")));
        call.resolve(payload);
    }

    @PluginMethod
    @Override
    public void requestPermissions(PluginCall call) {
        requestPermissionForAlias("microphone", call, "permissionsCallback");
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        checkPermissions(call);
    }

    @PluginMethod
    public void getDevices(PluginCall call) {
        call.resolve(controller.getDevices().asJSObject());
    }

    @PluginMethod
    public void setPreferredInput(PluginCall call) {
        String inputId = call.getString("inputId");

        try {
            controller.setPreferredInput(inputId);
            call.resolve();
        } catch (NativeMic.NativeMicControllerError error) {
            reject(call, error, null);
        } catch (Exception exception) {
            rejectUnexpected(call, exception, null);
        }
    }

    @PluginMethod
    public void setOutputRoute(PluginCall call) {
        String routeValue = call.getString("route");
        NativeMic.OutputRoute route = NativeMic.OutputRoute.fromWireValue(routeValue);
        if (route == null) {
            reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "route must be one of: system, speaker, receiver.", false, null, null);
            return;
        }

        try {
            controller.setOutputRoute(route);
            call.resolve();
        } catch (NativeMic.NativeMicControllerError error) {
            reject(call, error, null);
        } catch (Exception exception) {
            rejectUnexpected(call, exception, null);
        }
    }

    @PluginMethod
    public void startCapture(PluginCall call) {
        String profileValue = call.getString("profile");
        NativeMic.MicProfile profile = NativeMic.MicProfile.fromWireValue(profileValue);
        if (profile == null) {
            reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "profile is required and must be waveform or pipecat.", false, null, null);
            return;
        }

        String modeValue = call.getString("mode");
        NativeMic.SessionMode mode = NativeMic.SessionMode.fromWireValue(modeValue);
        if (mode == null) {
            reject(
                call,
                NativeMic.NativeMicErrorCode.INTERNAL,
                "mode is required and must be measurement or voice_chat.",
                false,
                null,
                null
            );
            return;
        }

        List<NativeMic.OutputStream> outputStreams = parseOutputStreams(call.getArray("outputStreams"));
        if (outputStreams == null || outputStreams.isEmpty()) {
            reject(
                call,
                NativeMic.NativeMicErrorCode.INTERNAL,
                "outputStreams must include pcm16k_s16le, pcm48k_s16le, or both.",
                false,
                null,
                null
            );
            return;
        }

        Integer chunkMsValue = call.getInt("chunkMs");
        int chunkMs = chunkMsValue != null ? chunkMsValue : NativeMic.getDefaultChunkMs();

        Boolean emitAudioLevelValue = call.getBoolean("emitAudioLevel");
        boolean emitAudioLevel = emitAudioLevelValue != null ? emitAudioLevelValue : true;

        Integer audioLevelIntervalValue = call.getInt("audioLevelIntervalMs");
        int audioLevelIntervalMs = Math.max(
            20,
            audioLevelIntervalValue != null ? audioLevelIntervalValue : NativeMic.getDefaultAudioLevelIntervalMs()
        );

        boolean voiceProcessingDefault = profile == NativeMic.MicProfile.PIPECAT;
        Boolean voiceProcessingValue = call.getBoolean("voiceProcessing");
        boolean voiceProcessing = voiceProcessingValue != null ? voiceProcessingValue : voiceProcessingDefault;

        String preferredInputId = call.getString("preferredInputId");

        String outputRouteValue = call.getString("outputRoute");
        if (outputRouteValue == null) {
            outputRouteValue = NativeMic.OutputRoute.SYSTEM.wireValue;
        }
        NativeMic.OutputRoute outputRoute = NativeMic.OutputRoute.fromWireValue(outputRouteValue);
        if (outputRoute == null) {
            outputRoute = NativeMic.OutputRoute.SYSTEM;
        }

        try {
            NativeMic.validatePermissionForStart(toMicPermissionState(getPermissionState("microphone")));

            NativeMic.StartCaptureOptionsModel options = new NativeMic.StartCaptureOptionsModel(
                profile,
                mode,
                outputStreams,
                chunkMs,
                emitAudioLevel,
                audioLevelIntervalMs,
                voiceProcessing,
                preferredInputId,
                outputRoute
            );

            NativeMic.StartCaptureResultModel result = controller.startCapture(options);
            call.resolve(result.asJSObject());
        } catch (NativeMic.NativeMicControllerError error) {
            reject(call, error, null);
        } catch (Exception exception) {
            rejectUnexpected(call, exception, null);
        }
    }

    @PluginMethod
    public void stopCapture(PluginCall call) {
        String captureId = call.getString("captureId");
        if (captureId == null || captureId.isEmpty()) {
            reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "captureId is required.", false, null, null);
            return;
        }

        Integer flushTimeoutValue = call.getInt("flushTimeoutMs");
        int flushTimeoutMs = flushTimeoutValue != null ? flushTimeoutValue : NativeMic.getDefaultFlushTimeoutMs();

        try {
            NativeMic.StopCaptureResultModel result = controller.stopCapture(captureId, flushTimeoutMs);
            call.resolve(result.asJSObject());
        } catch (NativeMic.NativeMicControllerError error) {
            reject(call, error, captureId);
        } catch (Exception exception) {
            rejectUnexpected(call, exception, captureId);
        }
    }

    @PluginMethod
    public void setMicEnabled(PluginCall call) {
        String captureId = call.getString("captureId");
        if (captureId == null || captureId.isEmpty()) {
            reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "captureId is required.", false, null, null);
            return;
        }

        Boolean enabled = call.getBoolean("enabled");
        if (enabled == null) {
            reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "enabled must be a boolean.", false, captureId, null);
            return;
        }

        try {
            controller.setMicEnabled(captureId, enabled);
            call.resolve();
        } catch (NativeMic.NativeMicControllerError error) {
            reject(call, error, captureId);
        } catch (Exception exception) {
            rejectUnexpected(call, exception, captureId);
        }
    }

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject payload = new JSObject();
        payload.put("state", controller.getState().wireValue);
        call.resolve(payload);
    }

    @PluginMethod
    public void getDiagnostics(PluginCall call) {
        call.resolve(controller.getDiagnostics());
    }

    @PluginMethod
    public void webrtcIsAvailable(PluginCall call) {
        call.resolve(webRtcController.isAvailable());
    }

    @PluginMethod
    public void webrtcConnect(PluginCall call) {
        try {
            NativeMic.validatePermissionForStart(toMicPermissionState(getPermissionState("microphone")));
            NativeWebRTC.ConnectOptionsModel options = NativeWebRTC.parseConnectOptions(NativeWebRTC.extractMap(call.getData()));
            NativeWebRTC.ConnectResultModel result = webRtcController.connect(options);
            call.resolve(result.asJSObject());
        } catch (NativeMic.NativeMicControllerError error) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                error.message,
                error.recoverable,
                null,
                error.nativeCode,
                null
            );
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, null);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, null);
        }
    }

    @PluginMethod
    public void webrtcDisconnect(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        String reason = call.getString("reason");
        try {
            webRtcController.disconnect(connectionId, reason);
            call.resolve();
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcSendDataMessage(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        String data = call.getString("data");
        if (data == null) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "data is required.",
                false,
                connectionId,
                null,
                null
            );
            return;
        }

        try {
            webRtcController.sendDataMessage(connectionId, data);
            call.resolve();
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcSetMicEnabled(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        Boolean enabled = call.getBoolean("enabled");
        if (enabled == null) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "enabled must be a boolean.",
                false,
                connectionId,
                null,
                null
            );
            return;
        }

        try {
            webRtcController.setMicEnabled(connectionId, enabled);
            call.resolve();
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcSetPreferredInput(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        String inputId = call.getString("inputId");

        try {
            webRtcController.setPreferredInput(connectionId, inputId);
            call.resolve();
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcSetOutputRoute(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        String routeValue = call.getString("route");
        NativeMic.OutputRoute route = NativeMic.OutputRoute.fromWireValue(routeValue);
        if (route == null) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "route must be one of: system, speaker, receiver.",
                false,
                connectionId,
                null,
                null
            );
            return;
        }

        try {
            webRtcController.setOutputRoute(connectionId, route);
            call.resolve();
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcGetState(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        try {
            NativeWebRTC.StateResultModel result = webRtcController.getState(connectionId);
            call.resolve(result.asJSObject());
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    @PluginMethod
    public void webrtcGetDiagnostics(PluginCall call) {
        String connectionId = call.getString("connectionId");
        if (connectionId == null || connectionId.isEmpty()) {
            rejectWebRTC(
                call,
                NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "connectionId is required.",
                false,
                null,
                null,
                null
            );
            return;
        }

        try {
            call.resolve(webRtcController.getDiagnostics(connectionId));
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            rejectWebRTC(call, error, connectionId);
        } catch (Exception exception) {
            rejectUnexpectedWebRTC(call, exception, connectionId);
        }
    }

    static String toMicPermissionState(PermissionState state) {
        if (state == PermissionState.GRANTED) {
            return "granted";
        }
        if (state == PermissionState.DENIED) {
            return "denied";
        }
        return "prompt";
    }

    private List<NativeMic.OutputStream> parseOutputStreams(JSArray rawStreams) {
        if (rawStreams == null) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (int index = 0; index < rawStreams.length(); index += 1) {
            try {
                Object rawValue = rawStreams.get(index);
                if (!(rawValue instanceof String)) {
                    return null;
                }
                values.add((String) rawValue);
            } catch (JSONException exception) {
                return null;
            }
        }

        return NativeMic.parseOutputStreams(values);
    }

    private void reject(PluginCall call, NativeMic.NativeMicControllerError error, String captureId) {
        reject(call, error.code, error.message, error.recoverable, captureId, error.nativeCode);
    }

    private void reject(
        PluginCall call,
        NativeMic.NativeMicErrorCode code,
        String message,
        boolean recoverable,
        String captureId,
        String nativeCode
    ) {
        JSObject payload = new JSObject();
        payload.put("code", code.wireValue);
        payload.put("message", message);
        payload.put("recoverable", recoverable);
        if (captureId != null) {
            payload.put("captureId", captureId);
        }
        if (nativeCode != null) {
            payload.put("nativeCode", nativeCode);
        }

        notifyListeners("micError", payload);
        call.reject(message, code.wireValue, null, payload);
    }

    private void rejectUnexpected(PluginCall call, Exception error, String captureId) {
        reject(call, NativeMic.NativeMicErrorCode.INTERNAL, "Unexpected native error.", false, captureId, String.valueOf(error.hashCode()));
    }

    private void rejectWebRTC(PluginCall call, NativeWebRTC.NativeWebRTCControllerError error, String connectionId) {
        rejectWebRTC(call, error.code, error.message, error.recoverable, connectionId, error.nativeCode, null);
    }

    private void rejectWebRTC(
        PluginCall call,
        NativeWebRTC.NativeWebRTCErrorCode code,
        String message,
        boolean recoverable,
        String connectionId,
        String nativeCode,
        String pcId
    ) {
        JSObject payload = new JSObject();
        payload.put("code", code.wireValue);
        payload.put("message", message);
        payload.put("recoverable", recoverable);
        if (connectionId != null) {
            payload.put("connectionId", connectionId);
        }
        if (pcId != null) {
            payload.put("pcId", pcId);
        }
        if (nativeCode != null) {
            payload.put("nativeCode", nativeCode);
        }

        notifyListeners("webrtcError", payload);
        call.reject(message, code.wireValue, null, payload);
    }

    private void rejectUnexpectedWebRTC(PluginCall call, Exception error, String connectionId) {
        rejectWebRTC(
            call,
            NativeWebRTC.NativeWebRTCErrorCode.INTERNAL,
            "Unexpected native WebRTC error.",
            false,
            connectionId,
            String.valueOf(error.hashCode()),
            null
        );
    }

    private void emitEventToJs(String eventName, JSObject payload) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> notifyListeners(eventName, payload));
            return;
        }
        notifyListeners(eventName, payload);
    }
}

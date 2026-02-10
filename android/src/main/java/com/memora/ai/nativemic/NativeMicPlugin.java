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

    @Override
    public void load() {
        controller = new NativeMic(getContext(), this::emitEventToJs);
    }

    @Override
    protected void handleOnDestroy() {
        if (controller != null) {
            controller.destroy();
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

    private void emitEventToJs(String eventName, JSObject payload) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> notifyListeners(eventName, payload));
            return;
        }
        notifyListeners(eventName, payload);
    }
}

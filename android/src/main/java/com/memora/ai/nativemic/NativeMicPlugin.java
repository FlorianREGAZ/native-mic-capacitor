package com.memora.ai.nativemic;

import android.Manifest;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name = "NativeMic", permissions = { @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }) })
public class NativeMicPlugin extends Plugin {

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", false);
        ret.put("reason", "Native iOS capture pipeline is only available on iOS.");
        call.resolve(ret);
    }

    @PluginMethod
    @Override
    public void checkPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("microphone", toMicPermissionState(getPermissionState("microphone")));
        call.resolve(ret);
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
        JSObject ret = new JSObject();
        ret.put("inputs", new com.getcapacitor.JSArray());
        call.resolve(ret);
    }

    @PluginMethod
    public void setPreferredInput(PluginCall call) {
        call.unimplemented("Use navigator.mediaDevices.getUserMedia on Android.");
    }

    @PluginMethod
    public void setOutputRoute(PluginCall call) {
        call.unimplemented("Use navigator.mediaDevices.getUserMedia on Android.");
    }

    @PluginMethod
    public void startCapture(PluginCall call) {
        call.unimplemented("Use navigator.mediaDevices.getUserMedia on Android.");
    }

    @PluginMethod
    public void stopCapture(PluginCall call) {
        call.unimplemented("Use navigator.mediaDevices.getUserMedia on Android.");
    }

    @PluginMethod
    public void setMicEnabled(PluginCall call) {
        call.unimplemented("Use navigator.mediaDevices.getUserMedia on Android.");
    }

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("state", "idle");
        call.resolve(ret);
    }

    @PluginMethod
    public void getDiagnostics(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", false);
        ret.put("platform", "android");
        call.resolve(ret);
    }

    private String toMicPermissionState(PermissionState state) {
        if (state == PermissionState.GRANTED) {
            return "granted";
        }
        if (state == PermissionState.DENIED) {
            return "denied";
        }
        return "prompt";
    }
}

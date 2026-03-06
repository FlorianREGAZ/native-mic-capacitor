package com.memora.ai.nativemic;

import android.media.AudioDeviceInfo;

final class AndroidAudioRouting {

    private AndroidAudioRouting() {}

    static int resolvePreferredSystemRouteDeviceType(int[] availableDeviceTypes) {
        if (availableDeviceTypes == null || availableDeviceTypes.length == 0) {
            return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }

        for (int deviceType : availableDeviceTypes) {
            if (isExternalRouteDeviceType(deviceType)) {
                return deviceType;
            }
        }

        for (int deviceType : availableDeviceTypes) {
            if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return deviceType;
            }
        }

        for (int deviceType : availableDeviceTypes) {
            if (deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                return deviceType;
            }
        }

        return availableDeviceTypes[0];
    }

    static boolean shouldUseSpeakerphone(int preferredDeviceType) {
        return preferredDeviceType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    static boolean isExternalRouteDeviceType(int deviceType) {
        switch (deviceType) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return true;
            default:
                return false;
        }
    }
}

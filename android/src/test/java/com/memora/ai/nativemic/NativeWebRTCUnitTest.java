package com.memora.ai.nativemic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.media.AudioDeviceInfo;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class NativeWebRTCUnitTest {

    @Test
    public void parseIceServersSupportsSingleAndArrayUrls() throws Exception {
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("urls", "stun:stun.example.com:3478");

        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("urls", Arrays.asList("turn:turn-a.example.com:3478", "turn:turn-b.example.com:3478"));
        multi.put("username", "user");
        multi.put("credential", "pass");

        Map<String, Object> iceConfig = new LinkedHashMap<>();
        iceConfig.put("iceServers", Arrays.asList(single, multi));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("iceConfig", iceConfig);

        List<NativeWebRTC.RTCIceServerLikeModel> parsed = NativeWebRTC.parseIceServers(raw);

        assertEquals(2, parsed.size());
        assertEquals(1, parsed.get(0).urls.size());
        assertEquals("stun:stun.example.com:3478", parsed.get(0).urls.get(0));
        assertEquals(2, parsed.get(1).urls.size());
        assertEquals("user", parsed.get(1).username);
        assertEquals("pass", parsed.get(1).credential);
    }

    @Test
    public void parseConnectOptionsDefaultsReconnectAndConnectionId() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", "https://voice.example.com/offer");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("webrtcRequest", request);

        NativeWebRTC.ConnectOptionsModel options = NativeWebRTC.parseConnectOptions(raw);

        assertTrue(options.connectionId != null && !options.connectionId.isEmpty());
        assertFalse(options.audioOnly);
        assertTrue(options.reconnect.enabled);
        assertEquals(3, options.reconnect.maxAttempts);
        assertEquals(2000, options.reconnect.backoffMs);
        assertEquals(NativeMic.OutputRoute.SYSTEM, options.media.outputRoute);
    }

    @Test
    public void systemRoutePolicyUsesSpeakerExceptForExternalOutputs() {
        assertTrue(
            AndroidAudioRouting.shouldUseSpeakerphone(
                AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(
                    new int[] { AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                )
            )
        );
        assertFalse(
            AndroidAudioRouting.shouldUseSpeakerphone(
                AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(
                    new int[] { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                )
            )
        );
    }

    @Test
    public void parseConnectOptionsRequiresWebRTCRequest() {
        Map<String, Object> raw = new LinkedHashMap<>();

        assertInvalidArgument("webrtcRequest is required.", () -> NativeWebRTC.parseConnectOptions(raw));
    }

    @Test
    public void parseConnectOptionsRequiresEndpoint() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("webrtcRequest", new LinkedHashMap<String, Object>());

        assertInvalidArgument("webrtcRequest.endpoint is required.", () -> NativeWebRTC.parseConnectOptions(raw));
    }

    @Test
    public void parseConnectOptionsRejectsInvalidOutputRoute() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", "https://voice.example.com/offer");

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("outputRoute", "bluetooth");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("webrtcRequest", request);
        raw.put("media", media);

        assertInvalidArgument(
            "media.outputRoute must be one of: system, speaker, receiver.",
            () -> NativeWebRTC.parseConnectOptions(raw)
        );
    }

    @Test
    public void parseConnectOptionsClampsValuesAndHonorsExplicitMedia() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", "https://voice.example.com/offer");
        request.put("headers", new LinkedHashMap<String, Object>() {
            {
                put("Authorization", 123);
            }
        });
        request.put("requestData", new LinkedHashMap<String, Object>() {
            {
                put("mode", "voice");
            }
        });
        request.put("timeoutMs", 250);

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("voiceProcessing", false);
        media.put("startMicEnabled", false);
        media.put("preferredInputId", "  built-in-mic  ");
        media.put("outputRoute", "speaker");

        Map<String, Object> reconnect = new LinkedHashMap<>();
        reconnect.put("enabled", false);
        reconnect.put("maxAttempts", -3);
        reconnect.put("backoffMs", 100);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("connectionId", "  test-connection  ");
        raw.put("webrtcRequest", request);
        raw.put("waitForICEGathering", true);
        raw.put("audioOnly", true);
        raw.put("audioCodec", " DEFAULT ");
        raw.put("videoCodec", "  H264  ");
        raw.put("media", media);
        raw.put("reconnect", reconnect);

        NativeWebRTC.ConnectOptionsModel options = NativeWebRTC.parseConnectOptions(raw);

        assertEquals("test-connection", options.connectionId);
        assertEquals(1_000, options.webrtcRequest.timeoutMs);
        assertEquals("123", options.webrtcRequest.headers.get("Authorization"));
        assertTrue(options.webrtcRequest.requestData != null);
        assertTrue(options.waitForICEGathering);
        assertTrue(options.audioOnly);
        assertNull(options.audioCodec);
        assertEquals("H264", options.videoCodec);
        assertFalse(options.media.voiceProcessing);
        assertFalse(options.media.startMicEnabled);
        assertEquals("built-in-mic", options.media.preferredInputId);
        assertEquals(NativeMic.OutputRoute.SPEAKER, options.media.outputRoute);
        assertTrue(options.media.outputRouteExplicit);
        assertFalse(options.reconnect.enabled);
        assertEquals(0, options.reconnect.maxAttempts);
        assertEquals(250, options.reconnect.backoffMs);
    }

    @Test
    public void parseIceServersRejectsNonObjectEntry() {
        Map<String, Object> iceConfig = new LinkedHashMap<>();
        iceConfig.put("iceServers", Arrays.asList("not-an-object"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("iceConfig", iceConfig);

        assertInvalidArgument(
            "iceConfig.iceServers[0] must be an object.",
            () -> NativeWebRTC.parseIceServers(raw)
        );
    }

    @Test
    public void parseIceServersRejectsBlankUrls() {
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("urls", Arrays.asList(" ", null));

        Map<String, Object> iceConfig = new LinkedHashMap<>();
        iceConfig.put("iceServers", Arrays.asList(invalid));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("iceConfig", iceConfig);

        assertInvalidArgument(
            "iceConfig.iceServers[0].urls is required.",
            () -> NativeWebRTC.parseIceServers(raw)
        );
    }

    @Test
    public void parseIceServersReturnsEmptyWhenConfigMissing() throws Exception {
        assertTrue(NativeWebRTC.parseIceServers(new LinkedHashMap<>()).isEmpty());
        assertTrue(NativeWebRTC.parseIceServers(null).isEmpty());
    }

    @Test
    public void parseNullableCodecMapsDefaultAndNull() {
        assertNull(NativeWebRTC.parseNullableCodec(null));
        assertNull(NativeWebRTC.parseNullableCodec("default"));
        assertNull(NativeWebRTC.parseNullableCodec(" DEFAULT "));
        assertEquals("opus", NativeWebRTC.parseNullableCodec("opus"));
        assertEquals("aac", NativeWebRTC.parseNullableCodec("  aac  "));
    }

    @Test
    public void canStartConnectionFromIdleAndError() {
        assertTrue(NativeWebRTC.canStartConnection(NativeWebRTC.NativeWebRTCState.IDLE));
        assertTrue(NativeWebRTC.canStartConnection(NativeWebRTC.NativeWebRTCState.ERROR));
        assertFalse(NativeWebRTC.canStartConnection(NativeWebRTC.NativeWebRTCState.CONNECTED));
    }

    @Test
    public void shouldResetBeforeConnectForStaleOrErrorState() {
        assertTrue(NativeWebRTC.shouldResetBeforeConnect(NativeWebRTC.NativeWebRTCState.ERROR, true));
        assertTrue(NativeWebRTC.shouldResetBeforeConnect(NativeWebRTC.NativeWebRTCState.IDLE, true));
        assertFalse(NativeWebRTC.shouldResetBeforeConnect(NativeWebRTC.NativeWebRTCState.IDLE, false));
        assertFalse(NativeWebRTC.shouldResetBeforeConnect(NativeWebRTC.NativeWebRTCState.CONNECTED, true));
    }

    @Test
    public void disconnectAndDiagnosticsAllowedFromError() {
        assertTrue(NativeWebRTC.canDisconnect(NativeWebRTC.NativeWebRTCState.ERROR));
        assertTrue(NativeWebRTC.canInspectConnection(NativeWebRTC.NativeWebRTCState.ERROR));
        assertFalse(NativeWebRTC.canDisconnect(NativeWebRTC.NativeWebRTCState.IDLE));
        assertFalse(NativeWebRTC.canInspectConnection(NativeWebRTC.NativeWebRTCState.IDLE));
    }

    private static void assertInvalidArgument(
        String expectedMessage,
        ThrowingNativeWebRTCCall action
    ) {
        try {
            action.run();
            fail("Expected NativeWebRTCControllerError");
        } catch (NativeWebRTC.NativeWebRTCControllerError error) {
            assertEquals(NativeWebRTC.NativeWebRTCErrorCode.INVALID_ARGUMENT, error.code);
            assertEquals(expectedMessage, error.message);
            assertFalse(error.recoverable);
            assertNull(error.nativeCode);
        }
    }

    private interface ThrowingNativeWebRTCCall {
        void run() throws NativeWebRTC.NativeWebRTCControllerError;
    }
}

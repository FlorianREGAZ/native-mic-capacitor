package com.memora.ai.nativemic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertTrue(options.reconnect.enabled);
        assertEquals(3, options.reconnect.maxAttempts);
        assertEquals(2000, options.reconnect.backoffMs);
        assertEquals(NativeMic.OutputRoute.RECEIVER, options.media.outputRoute);
    }

    @Test
    public void parseNullableCodecMapsDefaultAndNull() {
        assertNull(NativeWebRTC.parseNullableCodec(null));
        assertNull(NativeWebRTC.parseNullableCodec("default"));
        assertEquals("opus", NativeWebRTC.parseNullableCodec("opus"));
    }
}

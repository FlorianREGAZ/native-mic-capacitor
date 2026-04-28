package com.memora.ai.nativemic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.media.AudioDeviceInfo;
import com.getcapacitor.PermissionState;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class NativeMicUnitTest {

    @Test
    public void outputStreamSampleRatesMatchSpec() {
        assertEquals(16_000, NativeMic.OutputStream.PCM16K.sampleRate);
        assertEquals(48_000, NativeMic.OutputStream.PCM48K.sampleRate);
    }

    @Test
    public void permissionStatesMapToInterfaceValues() {
        assertEquals("granted", NativeMicPlugin.toMicPermissionState(PermissionState.GRANTED));
        assertEquals("denied", NativeMicPlugin.toMicPermissionState(PermissionState.DENIED));
        assertEquals("prompt", NativeMicPlugin.toMicPermissionState(PermissionState.PROMPT));
    }

    @Test
    public void outputStreamsParseAndDeduplicate() {
        List<NativeMic.OutputStream> parsed = NativeMic.parseOutputStreams(Arrays.asList("pcm16k_s16le", "pcm48k_s16le", "pcm16k_s16le"));

        assertNotNull(parsed);
        assertEquals(2, parsed.size());
        assertEquals(NativeMic.OutputStream.PCM16K, parsed.get(0));
        assertEquals(NativeMic.OutputStream.PCM48K, parsed.get(1));
    }

    @Test
    public void invalidOutputStreamListReturnsNull() {
        assertNull(NativeMic.parseOutputStreams(Arrays.asList("pcm16k_s16le", "invalid")));
    }

    @Test
    public void linearResamplerProducesDataAndFlushes() {
        NativeMic.LinearResampler resampler = new NativeMic.LinearResampler(48_000, 16_000);
        float[] input = new float[480];
        for (int index = 0; index < input.length; index += 1) {
            input[index] = (float) Math.sin((index / 48_000.0) * Math.PI * 2 * 440);
        }

        float[] converted = resampler.process(input, input.length);
        float[] flushed = resampler.flush();

        assertTrue(converted.length > 0);
        assertTrue(flushed.length >= 0);
    }

    @Test
    public void systemRoutePrefersSpeakerWhenOnlyBuiltInOutputsExist() {
        assertTrue(
            AndroidAudioRouting.shouldUseSpeakerphone(
                AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(
                    new int[] { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                )
            )
        );
        assertTrue(AndroidAudioRouting.shouldUseSpeakerphone(AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(new int[0])));
    }

    @Test
    public void systemRouteDefersToExternalOutputsWhenAvailable() {
        assertFalse(
            AndroidAudioRouting.shouldUseSpeakerphone(
                AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(
                    new int[] { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                )
            )
        );
        assertFalse(
            AndroidAudioRouting.shouldUseSpeakerphone(
                AndroidAudioRouting.resolvePreferredSystemRouteDeviceType(new int[] { AudioDeviceInfo.TYPE_WIRED_HEADSET })
            )
        );
    }

    @Test
    public void webRtcConnectPermissionValidationRejectsDeniedWhenStartMicEnabled() throws Exception {
        NativeWebRTC.ConnectOptionsModel options = parseConnectOptions(null);

        try {
            NativeMicPlugin.validatePermissionForWebRTCConnect("denied", options);
            fail("Expected NativeMicControllerError");
        } catch (NativeMic.NativeMicControllerError error) {
            assertEquals(NativeMic.NativeMicErrorCode.PERMISSION_DENIED, error.code);
            assertEquals("Microphone permission denied.", error.message);
            assertFalse(error.recoverable);
        }
    }

    @Test
    public void webRtcConnectPermissionValidationAllowsDeniedWhenStartMicDisabled() throws Exception {
        NativeWebRTC.ConnectOptionsModel options = parseConnectOptions(false);

        NativeMicPlugin.validatePermissionForWebRTCConnect("denied", options);
    }

    @Test
    public void webRtcConnectPermissionValidationAllowsGrantedWhenStartMicDisabled() throws Exception {
        NativeWebRTC.ConnectOptionsModel options = parseConnectOptions(false);

        NativeMicPlugin.validatePermissionForWebRTCConnect("granted", options);
    }

    @Test
    public void webRtcConnectPermissionValidationTreatsOmittedStartMicEnabledAsRequired() throws Exception {
        NativeWebRTC.ConnectOptionsModel options = parseConnectOptions(null);

        assertTrue(options.media.startMicEnabled);

        try {
            NativeMicPlugin.validatePermissionForWebRTCConnect("prompt", options);
            fail("Expected NativeMicControllerError");
        } catch (NativeMic.NativeMicControllerError error) {
            assertEquals(NativeMic.NativeMicErrorCode.PERMISSION_DENIED, error.code);
            assertEquals("Microphone permission not determined.", error.message);
            assertFalse(error.recoverable);
        }
    }

    private static NativeWebRTC.ConnectOptionsModel parseConnectOptions(Boolean startMicEnabled) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("endpoint", "https://voice.example.com/offer");

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("webrtcRequest", request);

        if (startMicEnabled != null) {
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("startMicEnabled", startMicEnabled);
            raw.put("media", media);
        }

        return NativeWebRTC.parseConnectOptions(raw);
    }

}

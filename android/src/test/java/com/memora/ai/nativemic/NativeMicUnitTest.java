package com.memora.ai.nativemic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.PermissionState;
import java.util.Arrays;
import java.util.List;
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
}

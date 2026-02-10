package com.memora.ai.nativemic;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeMic {

    interface EventEmitter {
        void emit(String eventName, JSObject payload);
    }

    enum NativeMicErrorCode {
        PERMISSION_DENIED("E_PERMISSION_DENIED"),
        PERMISSION_RESTRICTED("E_PERMISSION_RESTRICTED"),
        ALREADY_RUNNING("E_ALREADY_RUNNING"),
        NOT_RUNNING("E_NOT_RUNNING"),
        AUDIO_SESSION_CONFIG("E_AUDIO_SESSION_CONFIG"),
        ENGINE_START_FAILED("E_ENGINE_START_FAILED"),
        ENGINE_STOP_FAILED("E_ENGINE_STOP_FAILED"),
        CONVERTER_FAILED("E_CONVERTER_FAILED"),
        ROUTE_CHANGE_FAILED("E_ROUTE_CHANGE_FAILED"),
        INTERRUPTED("E_INTERRUPTED"),
        MEDIA_SERVICES_RESET("E_MEDIA_SERVICES_RESET"),
        INTERNAL("E_INTERNAL");

        final String wireValue;

        NativeMicErrorCode(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    static final class NativeMicControllerError extends Exception {

        final NativeMicErrorCode code;
        final String message;
        final boolean recoverable;
        final String nativeCode;

        NativeMicControllerError(NativeMicErrorCode code, String message, boolean recoverable, String nativeCode) {
            super(message);
            this.code = code;
            this.message = message;
            this.recoverable = recoverable;
            this.nativeCode = nativeCode;
        }
    }

    enum MicProfile {
        WAVEFORM("waveform"),
        PIPECAT("pipecat");

        final String wireValue;

        MicProfile(String wireValue) {
            this.wireValue = wireValue;
        }

        static MicProfile fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            for (MicProfile profile : values()) {
                if (profile.wireValue.equals(value)) {
                    return profile;
                }
            }
            return null;
        }
    }

    enum SessionMode {
        MEASUREMENT("measurement"),
        VOICE_CHAT("voice_chat");

        final String wireValue;

        SessionMode(String wireValue) {
            this.wireValue = wireValue;
        }

        static SessionMode fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            for (SessionMode mode : values()) {
                if (mode.wireValue.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    enum OutputStream {
        PCM16K("pcm16k_s16le", 16_000),
        PCM48K("pcm48k_s16le", 48_000);

        final String wireValue;
        final int sampleRate;

        OutputStream(String wireValue, int sampleRate) {
            this.wireValue = wireValue;
            this.sampleRate = sampleRate;
        }

        static OutputStream fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            for (OutputStream stream : values()) {
                if (stream.wireValue.equals(value)) {
                    return stream;
                }
            }
            return null;
        }
    }

    enum OutputRoute {
        SYSTEM("system"),
        SPEAKER("speaker"),
        RECEIVER("receiver");

        final String wireValue;

        OutputRoute(String wireValue) {
            this.wireValue = wireValue;
        }

        static OutputRoute fromWireValue(String value) {
            if (value == null) {
                return null;
            }
            for (OutputRoute route : values()) {
                if (route.wireValue.equals(value)) {
                    return route;
                }
            }
            return null;
        }
    }

    enum NativeMicState {
        IDLE("idle"),
        RUNNING("running"),
        PAUSED("paused");

        final String wireValue;

        NativeMicState(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    static final class StartCaptureOptionsModel {

        final MicProfile profile;
        final SessionMode mode;
        final List<OutputStream> outputStreams;
        final int chunkMs;
        final boolean emitAudioLevel;
        final int audioLevelIntervalMs;
        final boolean voiceProcessing;
        final String preferredInputId;
        final OutputRoute outputRoute;

        StartCaptureOptionsModel(
            MicProfile profile,
            SessionMode mode,
            List<OutputStream> outputStreams,
            int chunkMs,
            boolean emitAudioLevel,
            int audioLevelIntervalMs,
            boolean voiceProcessing,
            String preferredInputId,
            OutputRoute outputRoute
        ) {
            this.profile = profile;
            this.mode = mode;
            this.outputStreams = outputStreams;
            this.chunkMs = chunkMs;
            this.emitAudioLevel = emitAudioLevel;
            this.audioLevelIntervalMs = audioLevelIntervalMs;
            this.voiceProcessing = voiceProcessing;
            this.preferredInputId = preferredInputId;
            this.outputRoute = outputRoute;
        }
    }

    static final class StartCaptureResultModel {

        final String captureId;
        final double actualInputSampleRate;
        final int actualInputChannels;
        final int chunkMs;

        StartCaptureResultModel(String captureId, double actualInputSampleRate, int actualInputChannels, int chunkMs) {
            this.captureId = captureId;
            this.actualInputSampleRate = actualInputSampleRate;
            this.actualInputChannels = actualInputChannels;
            this.chunkMs = chunkMs;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            object.put("captureId", captureId);
            object.put("actualInputSampleRate", actualInputSampleRate);
            object.put("actualInputChannels", actualInputChannels);
            object.put("chunkMs", chunkMs);
            return object;
        }
    }

    static final class StopCaptureResultModel {

        final String captureId;
        final long totalFramesIn;
        final long totalFramesOut16k;
        final long totalFramesOut48k;
        final long durationMs;

        StopCaptureResultModel(String captureId, long totalFramesIn, long totalFramesOut16k, long totalFramesOut48k, long durationMs) {
            this.captureId = captureId;
            this.totalFramesIn = totalFramesIn;
            this.totalFramesOut16k = totalFramesOut16k;
            this.totalFramesOut48k = totalFramesOut48k;
            this.durationMs = durationMs;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            object.put("captureId", captureId);
            object.put("totalFramesIn", totalFramesIn);
            object.put("totalFramesOut16k", totalFramesOut16k);
            object.put("totalFramesOut48k", totalFramesOut48k);
            object.put("durationMs", durationMs);
            return object;
        }
    }

    static final class MicDeviceModel {

        final String id;
        final String label;
        final String type;
        final boolean isDefault;

        MicDeviceModel(String id, String label, String type, boolean isDefault) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.isDefault = isDefault;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            object.put("id", id);
            object.put("label", label);
            object.put("type", type);
            object.put("isDefault", isDefault);
            return object;
        }
    }

    static final class DeviceSnapshot {

        final List<MicDeviceModel> inputs;
        final String selectedInputId;

        DeviceSnapshot(List<MicDeviceModel> inputs, String selectedInputId) {
            this.inputs = inputs;
            this.selectedInputId = selectedInputId;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            JSArray inputArray = new JSArray();
            for (MicDeviceModel input : inputs) {
                inputArray.put(input.asJSObject());
            }
            object.put("inputs", inputArray);
            if (selectedInputId != null) {
                object.put("selectedInputId", selectedInputId);
            }
            return object;
        }
    }

    static final class LinearResampler {

        private final double step;
        private double nextInputIndex = 0;
        private long chunkStartIndex = 0;
        private float lastSample = 0;
        private boolean hasLastSample = false;

        LinearResampler(int inputSampleRate, int outputSampleRate) {
            this.step = ((double) inputSampleRate) / ((double) outputSampleRate);
        }

        float[] process(float[] input, int length) {
            if (length <= 0) {
                return new float[0];
            }

            FloatBuilder output = new FloatBuilder(Math.max(16, length));
            long chunkStart = chunkStartIndex;
            long chunkEnd = chunkStart + length - 1;

            while (nextInputIndex <= chunkEnd) {
                long baseIndex = (long) Math.floor(nextInputIndex);
                double fraction = nextInputIndex - baseIndex;

                float sampleA;
                if (baseIndex < chunkStart) {
                    if (!hasLastSample) {
                        break;
                    }
                    sampleA = lastSample;
                } else {
                    int inputIndex = (int) (baseIndex - chunkStart);
                    sampleA = inputIndex >= 0 && inputIndex < length ? input[inputIndex] : lastSample;
                }

                long nextIndex = baseIndex + 1;
                float sampleB;
                if (nextIndex <= chunkEnd) {
                    int inputIndex = (int) (nextIndex - chunkStart);
                    sampleB = inputIndex >= 0 && inputIndex < length ? input[inputIndex] : sampleA;
                } else {
                    break;
                }

                output.append((float) (sampleA + (sampleB - sampleA) * fraction));
                nextInputIndex += step;
            }

            chunkStartIndex += length;
            lastSample = input[length - 1];
            hasLastSample = true;

            return output.toArray();
        }

        float[] flush() {
            if (!hasLastSample) {
                return new float[0];
            }

            FloatBuilder output = new FloatBuilder(32);
            long lastIndex = chunkStartIndex - 1;
            while (nextInputIndex <= lastIndex) {
                output.append(lastSample);
                nextInputIndex += step;
            }
            return output.toArray();
        }
    }

    private static final class FloatBuilder {

        private float[] data;
        private int size;

        FloatBuilder(int initialCapacity) {
            this.data = new float[Math.max(1, initialCapacity)];
        }

        void append(float value) {
            ensureCapacity(size + 1);
            data[size] = value;
            size += 1;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        private void ensureCapacity(int desiredCapacity) {
            if (desiredCapacity <= data.length) {
                return;
            }
            int nextCapacity = data.length;
            while (nextCapacity < desiredCapacity) {
                nextCapacity *= 2;
            }
            data = Arrays.copyOf(data, nextCapacity);
        }
    }

    private static final class ShortBuffer {

        private short[] data = new short[0];
        private int size = 0;

        void append(short[] samples) {
            append(samples, samples.length);
        }

        void append(short[] samples, int length) {
            if (length <= 0) {
                return;
            }
            ensureCapacity(size + length);
            System.arraycopy(samples, 0, data, size, length);
            size += length;
        }

        short[] popChunk(int chunkFrames) {
            if (size < chunkFrames) {
                return null;
            }
            short[] output = Arrays.copyOfRange(data, 0, chunkFrames);
            shiftLeft(chunkFrames);
            return output;
        }

        short[] popFinalChunk(int chunkFrames) {
            if (size == 0) {
                return null;
            }
            short[] output = new short[chunkFrames];
            int copyLength = Math.min(chunkFrames, size);
            System.arraycopy(data, 0, output, 0, copyLength);
            size = 0;
            return output;
        }

        void clear() {
            size = 0;
        }

        int size() {
            return size;
        }

        private void shiftLeft(int amount) {
            int remaining = size - amount;
            if (remaining > 0) {
                System.arraycopy(data, amount, data, 0, remaining);
            }
            size = remaining;
        }

        private void ensureCapacity(int desiredCapacity) {
            if (desiredCapacity <= data.length) {
                return;
            }
            int nextCapacity = Math.max(16, data.length);
            while (nextCapacity < desiredCapacity) {
                nextCapacity *= 2;
            }
            data = Arrays.copyOf(data, nextCapacity);
        }
    }

    private static final class StreamPipeline {

        final OutputStream stream;
        final int sampleRate;
        final int chunkFrames;
        final LinearResampler resampler;
        final ShortBuffer pendingSamples = new ShortBuffer();
        long seq = 0;
        long emittedFrames = 0;

        StreamPipeline(OutputStream stream, int inputSampleRate, int chunkMs) {
            this.stream = stream;
            this.sampleRate = stream.sampleRate;
            this.chunkFrames = (stream.sampleRate * chunkMs) / 1_000;
            this.resampler = new LinearResampler(inputSampleRate, stream.sampleRate);
        }

        short[] convert(float[] samples, int length) {
            float[] resampled = resampler.process(samples, length);
            return convertFloatToPcm16(resampled);
        }

        short[] flush() {
            float[] remaining = resampler.flush();
            return convertFloatToPcm16(remaining);
        }
    }

    private static final int DEFAULT_CHUNK_MS = 20;
    private static final int DEFAULT_AUDIO_LEVEL_INTERVAL_MS = 50;
    private static final int DEFAULT_FLUSH_TIMEOUT_MS = 150;
    private static final String PERMISSION_DENIED_MESSAGE = "Microphone permission denied.";

    private final Context appContext;
    private final AudioManager audioManager;
    private final EventEmitter eventEmitter;
    private final Object lock = new Object();
    private final AtomicBoolean captureLoopRunning = new AtomicBoolean(false);

    private NativeMicState state = NativeMicState.IDLE;
    private StartCaptureOptionsModel activeConfig;
    private String activeCaptureId;
    private String preferredInputId;
    private OutputRoute selectedOutputRoute = OutputRoute.SYSTEM;
    private boolean micEnabled = true;

    private long captureStartPtsMs = 0;
    private long totalFramesIn = 0;
    private long totalFramesOut16k = 0;
    private long totalFramesOut48k = 0;
    private double actualInputSampleRate = 0;
    private int actualInputChannels = 0;
    private long droppedInputFrames = 0;
    private int mediaServicesResetCount = 0;
    private String lastRouteChangeReason = "unknown";

    private double levelSumSquares = 0;
    private float levelPeak = 0;
    private int levelFrames = 0;
    private int levelIntervalFrames = 0;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private final Map<OutputStream, StreamPipeline> outputPipelines = new LinkedHashMap<>();

    private boolean interruptionActive = false;
    private boolean expectedResumeAfterInterruption = false;

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private AudioFocusRequest audioFocusRequest;
    private boolean audioFocusRequested = false;

    private Integer previousAudioMode;
    private Boolean previousSpeakerphoneEnabled;

    private final AudioDeviceCallback audioDeviceCallback;

    public NativeMic(Context context, EventEmitter eventEmitter) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.eventEmitter = eventEmitter;

        this.audioFocusChangeListener = (focusChange) -> {
            synchronized (lock) {
                handleAudioFocusChangeLocked(focusChange);
            }
        };

        this.audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                synchronized (lock) {
                    emitRouteChangedLocked("new_device_available");
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                synchronized (lock) {
                    emitRouteChangedLocked("old_device_unavailable");
                }
            }
        };

        registerAudioDeviceCallbacks();
    }

    public void destroy() {
        String captureIdToStop;
        synchronized (lock) {
            captureIdToStop = activeCaptureId;
        }

        if (captureIdToStop != null) {
            try {
                stopCapture(captureIdToStop, DEFAULT_FLUSH_TIMEOUT_MS);
            } catch (NativeMicControllerError ignored) {
                // Best-effort cleanup during teardown.
            }
        }

        unregisterAudioDeviceCallbacks();
    }

    public JSObject isAvailable() {
        JSObject result = new JSObject();
        AudioDeviceInfo[] inputDevices = getInputDevices();
        if (inputDevices.length == 0) {
            result.put("available", false);
            result.put("reason", "No audio input is currently available.");
            return result;
        }

        result.put("available", true);
        return result;
    }

    public DeviceSnapshot getDevices() {
        synchronized (lock) {
            AudioDeviceInfo[] devices = getInputDevices();
            String selectedInputId = resolveSelectedInputIdLocked(devices);

            List<MicDeviceModel> mapped = new ArrayList<>(devices.length);
            for (int index = 0; index < devices.length; index += 1) {
                AudioDeviceInfo device = devices[index];
                String id = String.valueOf(device.getId());
                String label =
                    device.getProductName() != null
                        ? device.getProductName().toString()
                        : String.format(Locale.US, "Microphone %d", index + 1);
                boolean isDefault = selectedInputId != null ? selectedInputId.equals(id) : index == 0;

                mapped.add(new MicDeviceModel(id, label, mapDeviceType(device), isDefault));
            }

            return new DeviceSnapshot(mapped, selectedInputId);
        }
    }

    public void setPreferredInput(String inputId) throws NativeMicControllerError {
        synchronized (lock) {
            preferredInputId = inputId;
            if (state != NativeMicState.IDLE) {
                applyPreferredInputLocked(true);
            }
        }
    }

    public void setOutputRoute(OutputRoute route) throws NativeMicControllerError {
        synchronized (lock) {
            selectedOutputRoute = route;
            if (state != NativeMicState.IDLE) {
                applyOutputRouteLocked(route);
                emitRouteChangedLocked("set_output_route");
            }
        }
    }

    public StartCaptureResultModel startCapture(StartCaptureOptionsModel options) throws NativeMicControllerError {
        AudioRecord recordToStart = null;

        synchronized (lock) {
            if (state == NativeMicState.RUNNING || state == NativeMicState.PAUSED) {
                throw new NativeMicControllerError(NativeMicErrorCode.ALREADY_RUNNING, "Capture is already running.", false, null);
            }

            validateStartOptions(options);
            validatePreferredInputForStartLocked(options.preferredInputId);

            String captureId = UUID.randomUUID().toString();
            activeCaptureId = captureId;
            activeConfig = options;
            selectedOutputRoute = options.outputRoute;
            micEnabled = true;
            captureStartPtsMs = monotonicMs();
            expectedResumeAfterInterruption = false;
            interruptionActive = false;

            if (options.preferredInputId != null) {
                preferredInputId = options.preferredInputId;
            }

            try {
                configureAudioSessionLocked(options);

                int inputSampleRate = resolveInputSampleRate();
                int chunkFrames = Math.max(256, (inputSampleRate * options.chunkMs) / 1_000);
                int minBufferBytes = AudioRecord.getMinBufferSize(
                    inputSampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                );
                if (minBufferBytes <= 0) {
                    throw new NativeMicControllerError(
                        NativeMicErrorCode.AUDIO_SESSION_CONFIG,
                        "Failed to configure AVAudioSession.",
                        false,
                        String.valueOf(minBufferBytes)
                    );
                }

                int desiredBufferBytes = Math.max(minBufferBytes, chunkFrames * 4);
                AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(inputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

                int source = options.voiceProcessing ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC;

                recordToStart = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(desiredBufferBytes)
                    .build();

                if (recordToStart.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new NativeMicControllerError(
                        NativeMicErrorCode.ENGINE_START_FAILED,
                        "Failed to start AVAudioEngine.",
                        false,
                        String.valueOf(recordToStart.getState())
                    );
                }

                actualInputSampleRate = recordToStart.getSampleRate();
                actualInputChannels = Math.max(1, recordToStart.getChannelCount());
                levelIntervalFrames = Math.max(1, (int) ((actualInputSampleRate * options.audioLevelIntervalMs) / 1_000.0));

                outputPipelines.clear();
                for (OutputStream stream : options.outputStreams) {
                    outputPipelines.put(stream, new StreamPipeline(stream, (int) actualInputSampleRate, options.chunkMs));
                }

                totalFramesIn = 0;
                totalFramesOut16k = 0;
                totalFramesOut48k = 0;
                droppedInputFrames = 0;
                levelSumSquares = 0;
                levelPeak = 0;
                levelFrames = 0;

                audioRecord = recordToStart;
                applyPreferredInputLocked(false);
                applyOutputRouteLocked(options.outputRoute);

                audioRecord.startRecording();
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new NativeMicControllerError(
                        NativeMicErrorCode.ENGINE_START_FAILED,
                        "Failed to start AVAudioEngine.",
                        false,
                        String.valueOf(audioRecord.getRecordingState())
                    );
                }

                captureLoopRunning.set(true);
                captureThread = new Thread(this::captureLoop, "NativeMicCapture");
                captureThread.start();

                state = NativeMicState.RUNNING;
                emitStateChangedLocked("start_capture");

                return new StartCaptureResultModel(captureId, actualInputSampleRate, actualInputChannels, options.chunkMs);
            } catch (NativeMicControllerError error) {
                if (recordToStart != null) {
                    releaseAudioRecord(recordToStart);
                }
                audioRecord = null;
                teardownAudioSessionLocked();
                clearCaptureStateLocked();
                state = NativeMicState.IDLE;
                throw error;
            } catch (Exception exception) {
                if (recordToStart != null) {
                    releaseAudioRecord(recordToStart);
                }
                audioRecord = null;
                teardownAudioSessionLocked();
                clearCaptureStateLocked();
                state = NativeMicState.IDLE;
                throw new NativeMicControllerError(
                    NativeMicErrorCode.ENGINE_START_FAILED,
                    "Failed to start AVAudioEngine.",
                    false,
                    String.valueOf(exception.hashCode())
                );
            }
        }
    }

    public StopCaptureResultModel stopCapture(String captureId, int flushTimeoutMs) throws NativeMicControllerError {
        Thread threadToJoin;
        AudioRecord recordToStop;
        String activeId;
        int timeoutMs = Math.max(10, flushTimeoutMs);

        synchronized (lock) {
            if (activeCaptureId == null || !activeCaptureId.equals(captureId) || activeConfig == null) {
                throw new NativeMicControllerError(
                    NativeMicErrorCode.NOT_RUNNING,
                    "No active capture matches " + captureId + ".",
                    false,
                    null
                );
            }

            activeId = activeCaptureId;
            captureLoopRunning.set(false);
            threadToJoin = captureThread;
            captureThread = null;
            recordToStop = audioRecord;
        }

        if (recordToStop != null) {
            try {
                recordToStop.stop();
            } catch (IllegalStateException ignored) {
                // Might already be stopped.
            }
        }

        if (threadToJoin != null) {
            try {
                threadToJoin.join(timeoutMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized (lock) {
            if (activeCaptureId == null || !activeCaptureId.equals(activeId)) {
                throw new NativeMicControllerError(
                    NativeMicErrorCode.NOT_RUNNING,
                    "No active capture matches " + captureId + ".",
                    false,
                    null
                );
            }

            for (StreamPipeline pipeline : outputPipelines.values()) {
                short[] converted = pipeline.flush();
                pipeline.pendingSamples.append(converted);
                emitAvailableChunksLocked(pipeline, false);

                short[] finalChunk = pipeline.pendingSamples.popFinalChunk(pipeline.chunkFrames);
                if (finalChunk != null) {
                    emitChunkLocked(pipeline, finalChunk, true);
                }
            }

            releaseAudioRecord(audioRecord);
            audioRecord = null;

            try {
                teardownAudioSessionLocked();
            } catch (RuntimeException exception) {
                emitErrorLocked(
                    NativeMicErrorCode.ENGINE_STOP_FAILED,
                    "Failed to deactivate audio session.",
                    true,
                    String.valueOf(exception.hashCode()),
                    activeId
                );
            }

            long durationMs = Math.max(0, monotonicMs() - captureStartPtsMs);
            StopCaptureResultModel result = new StopCaptureResultModel(
                activeId,
                totalFramesIn,
                totalFramesOut16k,
                totalFramesOut48k,
                durationMs
            );

            clearCaptureStateLocked();
            state = NativeMicState.IDLE;
            emitStateChangedLocked("stop_capture");

            return result;
        }
    }

    public void setMicEnabled(String captureId, boolean enabled) throws NativeMicControllerError {
        synchronized (lock) {
            if (activeCaptureId == null || !activeCaptureId.equals(captureId) || state == NativeMicState.IDLE) {
                throw new NativeMicControllerError(
                    NativeMicErrorCode.NOT_RUNNING,
                    "No active capture matches " + captureId + ".",
                    false,
                    null
                );
            }
            micEnabled = enabled;
        }
    }

    public NativeMicState getState() {
        synchronized (lock) {
            return state;
        }
    }

    public JSObject getDiagnostics() {
        synchronized (lock) {
            JSObject diagnostics = new JSObject();
            diagnostics.put("state", state.wireValue);
            diagnostics.put("micEnabled", micEnabled);
            diagnostics.put("outputRoute", selectedOutputRoute.wireValue);
            diagnostics.put("actualInputSampleRate", actualInputSampleRate);
            diagnostics.put("actualInputChannels", actualInputChannels);
            diagnostics.put("totalFramesIn", totalFramesIn);
            diagnostics.put("totalFramesOut16k", totalFramesOut16k);
            diagnostics.put("totalFramesOut48k", totalFramesOut48k);
            diagnostics.put("inputFramesDropped", droppedInputFrames);
            diagnostics.put("inputRingBufferedFrames", 0);
            diagnostics.put("mediaServicesResetCount", mediaServicesResetCount);
            diagnostics.put("lastRouteChangeReason", lastRouteChangeReason);

            if (activeCaptureId != null) {
                diagnostics.put("captureId", activeCaptureId);
            }
            if (preferredInputId != null) {
                diagnostics.put("preferredInputId", preferredInputId);
            }
            return diagnostics;
        }
    }

    static void validatePermissionForStart(String microphonePermission) throws NativeMicControllerError {
        if ("granted".equals(microphonePermission)) {
            return;
        }

        if ("denied".equals(microphonePermission)) {
            throw new NativeMicControllerError(NativeMicErrorCode.PERMISSION_DENIED, PERMISSION_DENIED_MESSAGE, false, null);
        }

        throw new NativeMicControllerError(NativeMicErrorCode.PERMISSION_DENIED, "Microphone permission not determined.", false, null);
    }

    static List<OutputStream> parseOutputStreams(List<String> rawStreams) {
        if (rawStreams == null) {
            return null;
        }

        Set<OutputStream> deduped = new LinkedHashSet<>();
        for (String rawStream : rawStreams) {
            OutputStream stream = OutputStream.fromWireValue(rawStream);
            if (stream == null) {
                return null;
            }
            deduped.add(stream);
        }

        return new ArrayList<>(deduped);
    }

    private void validateStartOptions(StartCaptureOptionsModel options) throws NativeMicControllerError {
        if (options.chunkMs != DEFAULT_CHUNK_MS) {
            throw new NativeMicControllerError(NativeMicErrorCode.INTERNAL, "Only 20ms chunking is supported.", false, null);
        }

        if (options.outputStreams == null || options.outputStreams.isEmpty()) {
            throw new NativeMicControllerError(NativeMicErrorCode.INTERNAL, "At least one output stream must be provided.", false, null);
        }

        if (options.profile == MicProfile.WAVEFORM && options.mode != SessionMode.MEASUREMENT) {
            throw new NativeMicControllerError(NativeMicErrorCode.INTERNAL, "Waveform profile requires measurement mode.", false, null);
        }

        if (options.profile == MicProfile.PIPECAT && options.mode != SessionMode.VOICE_CHAT) {
            throw new NativeMicControllerError(NativeMicErrorCode.INTERNAL, "Pipecat profile requires voice_chat mode.", false, null);
        }
    }

    private void validatePreferredInputForStartLocked(String requestedInputId) throws NativeMicControllerError {
        String inputId = requestedInputId != null ? requestedInputId : preferredInputId;
        if (inputId == null) {
            return;
        }

        AudioDeviceInfo[] inputDevices = getInputDevices();
        if (inputDevices.length == 0) {
            return;
        }

        for (AudioDeviceInfo device : inputDevices) {
            if (String.valueOf(device.getId()).equals(inputId)) {
                return;
            }
        }

        throw new NativeMicControllerError(
            NativeMicErrorCode.ROUTE_CHANGE_FAILED,
            "Input device " + inputId + " was not found.",
            false,
            null
        );
    }

    private void configureAudioSessionLocked(StartCaptureOptionsModel options) throws NativeMicControllerError {
        if (audioManager == null) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.AUDIO_SESSION_CONFIG,
                "No audio input is currently available.",
                false,
                null
            );
        }

        try {
            previousAudioMode = audioManager.getMode();
            previousSpeakerphoneEnabled = audioManager.isSpeakerphoneOn();

            if (options.profile == MicProfile.PIPECAT) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }

            requestAudioFocusLocked(options.profile == MicProfile.PIPECAT);
        } catch (Exception exception) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.AUDIO_SESSION_CONFIG,
                "Failed to configure AVAudioSession.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void requestAudioFocusLocked(boolean voiceCommunication) throws NativeMicControllerError {
        if (audioManager == null) {
            return;
        }

        int focusGain = voiceCommunication ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT : AudioManager.AUDIOFOCUS_GAIN;

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(voiceCommunication ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            audioFocusRequest = new AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setWillPauseWhenDucked(false)
                .build();

            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_VOICE_CALL, focusGain);
        }

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.AUDIO_SESSION_CONFIG,
                "Failed to configure AVAudioSession.",
                false,
                String.valueOf(result)
            );
        }

        audioFocusRequested = true;
    }

    private void teardownAudioSessionLocked() {
        if (audioManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        }

        if (previousSpeakerphoneEnabled != null) {
            audioManager.setSpeakerphoneOn(previousSpeakerphoneEnabled);
        } else {
            audioManager.setSpeakerphoneOn(false);
        }

        if (previousAudioMode != null) {
            audioManager.setMode(previousAudioMode);
        } else {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }

        if (audioFocusRequested) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
        }

        audioFocusRequested = false;
        audioFocusRequest = null;
        previousAudioMode = null;
        previousSpeakerphoneEnabled = null;
    }

    private void applyPreferredInputLocked(boolean emitRouteChanged) throws NativeMicControllerError {
        if (audioRecord == null) {
            return;
        }

        if (preferredInputId == null) {
            boolean cleared = audioRecord.setPreferredDevice(null);
            if (!cleared) {
                throw new NativeMicControllerError(NativeMicErrorCode.ROUTE_CHANGE_FAILED, "Failed to clear preferred input.", true, null);
            }
            if (emitRouteChanged) {
                emitRouteChangedLocked("set_preferred_input");
            }
            return;
        }

        AudioDeviceInfo targetDevice = findInputDeviceById(preferredInputId);
        if (targetDevice == null) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.ROUTE_CHANGE_FAILED,
                "Input device " + preferredInputId + " was not found.",
                false,
                null
            );
        }

        boolean applied = audioRecord.setPreferredDevice(targetDevice);
        if (!applied) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.ROUTE_CHANGE_FAILED,
                "Failed to apply preferred input " + preferredInputId + ".",
                true,
                null
            );
        }

        if (emitRouteChanged) {
            emitRouteChangedLocked("set_preferred_input");
        }
    }

    private void applyOutputRouteLocked(OutputRoute route) throws NativeMicControllerError {
        if (audioManager == null) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.ROUTE_CHANGE_FAILED,
                "Failed to set output route to " + route.wireValue + ".",
                true,
                null
            );
        }

        try {
            switch (route) {
                case SPEAKER:
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AudioDeviceInfo speaker = findOutputDeviceByType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
                        if (speaker != null) {
                            audioManager.setCommunicationDevice(speaker);
                        }
                    }
                    break;
                case RECEIVER:
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AudioDeviceInfo receiver = findOutputDeviceByType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
                        if (receiver != null) {
                            audioManager.setCommunicationDevice(receiver);
                        }
                    }
                    break;
                case SYSTEM:
                default:
                    if (activeConfig != null && activeConfig.profile == MicProfile.PIPECAT) {
                        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    } else {
                        audioManager.setMode(AudioManager.MODE_NORMAL);
                    }
                    audioManager.setSpeakerphoneOn(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice();
                    }
                    break;
            }
        } catch (Exception exception) {
            throw new NativeMicControllerError(
                NativeMicErrorCode.ROUTE_CHANGE_FAILED,
                "Failed to set output route to " + route.wireValue + ".",
                true,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void captureLoop() {
        short[] readBuffer;
        synchronized (lock) {
            int bufferFrames = Math.max(256, (int) ((Math.max(16_000, actualInputSampleRate) * DEFAULT_CHUNK_MS) / 1_000));
            readBuffer = new short[bufferFrames];
        }

        while (captureLoopRunning.get()) {
            AudioRecord activeRecord;
            synchronized (lock) {
                activeRecord = audioRecord;
            }
            if (activeRecord == null) {
                break;
            }

            int readCount;
            try {
                readCount = activeRecord.read(readBuffer, 0, readBuffer.length);
            } catch (Exception exception) {
                readCount = AudioRecord.ERROR_INVALID_OPERATION;
            }

            if (readCount > 0) {
                synchronized (lock) {
                    processInputFramesLocked(readBuffer, readCount);
                }
                continue;
            }

            if (readCount == AudioRecord.ERROR_DEAD_OBJECT) {
                synchronized (lock) {
                    mediaServicesResetCount += 1;
                    emitErrorLocked(
                        NativeMicErrorCode.MEDIA_SERVICES_RESET,
                        "Audio media services were reset.",
                        true,
                        null,
                        activeCaptureId
                    );

                    if (state != NativeMicState.IDLE) {
                        state = NativeMicState.PAUSED;
                        emitStateChangedLocked("media_services_reset");
                    }
                }
                captureLoopRunning.set(false);
                break;
            }

            if (readCount < 0) {
                synchronized (lock) {
                    droppedInputFrames += readBuffer.length;
                }
            }
        }
    }

    private void processInputFramesLocked(short[] rawSamples, int frameCount) {
        if (state != NativeMicState.RUNNING || activeCaptureId == null || activeConfig == null) {
            return;
        }

        float[] monoSamples = new float[frameCount];
        for (int index = 0; index < frameCount; index += 1) {
            short pcm = rawSamples[index];
            float sample = pcm / 32768.0f;
            monoSamples[index] = micEnabled ? sample : 0f;
        }

        totalFramesIn += frameCount;

        if (activeConfig.emitAudioLevel) {
            accumulateAudioLevelLocked(monoSamples);
        }

        for (StreamPipeline pipeline : outputPipelines.values()) {
            short[] converted = pipeline.convert(monoSamples, monoSamples.length);
            pipeline.pendingSamples.append(converted);
            emitAvailableChunksLocked(pipeline, false);
        }
    }

    private void accumulateAudioLevelLocked(float[] samples) {
        for (float sample : samples) {
            float absolute = Math.abs(sample);
            if (absolute > levelPeak) {
                levelPeak = absolute;
            }
            levelSumSquares += sample * sample;
        }

        levelFrames += samples.length;
        if (levelFrames >= levelIntervalFrames) {
            emitAudioLevelLocked();
        }
    }

    private void emitAudioLevelLocked() {
        if (activeCaptureId == null || levelFrames <= 0) {
            return;
        }

        double rms = Math.sqrt(levelSumSquares / levelFrames);
        double peak = levelPeak;
        double dbfs = rms > 0 ? Math.min(0, Math.max(-90, 20 * Math.log10(rms))) : -90;

        JSObject payload = new JSObject();
        payload.put("captureId", activeCaptureId);
        payload.put("rms", rms);
        payload.put("peak", peak);
        payload.put("dbfs", dbfs);
        payload.put("vad", dbfs > -45);
        payload.put("ptsMs", monotonicMs());

        emitEventLocked("micAudioLevel", payload);

        levelSumSquares = 0;
        levelPeak = 0;
        levelFrames = 0;
    }

    private void emitAvailableChunksLocked(StreamPipeline pipeline, boolean finalChunk) {
        while (pipeline.pendingSamples.size() >= pipeline.chunkFrames) {
            short[] chunk = pipeline.pendingSamples.popChunk(pipeline.chunkFrames);
            if (chunk == null) {
                break;
            }
            emitChunkLocked(pipeline, chunk, finalChunk);
        }
    }

    private void emitChunkLocked(StreamPipeline pipeline, short[] samples, boolean finalChunk) {
        if (activeCaptureId == null) {
            return;
        }

        long seq = pipeline.seq;
        long ptsOffsetMs = (pipeline.emittedFrames * 1_000L) / pipeline.sampleRate;
        long ptsMs = captureStartPtsMs + ptsOffsetMs;

        pipeline.seq += 1;
        pipeline.emittedFrames += samples.length;

        if (pipeline.stream == OutputStream.PCM16K) {
            totalFramesOut16k += samples.length;
        } else {
            totalFramesOut48k += samples.length;
        }

        JSObject payload = new JSObject();
        payload.put("captureId", activeCaptureId);
        payload.put("stream", pipeline.stream.wireValue);
        payload.put("sampleRate", pipeline.sampleRate);
        payload.put("channels", 1);
        payload.put("frames", samples.length);
        payload.put("seq", seq);
        payload.put("ptsMs", ptsMs);
        payload.put("dataBase64", encodePcm16(samples));
        if (finalChunk) {
            payload.put("final", true);
        }

        emitEventLocked("micPcmChunk", payload);
    }

    private void emitStateChangedLocked(String reason) {
        JSObject payload = new JSObject();
        payload.put("state", state.wireValue);
        payload.put("reason", reason);
        if (activeCaptureId != null) {
            payload.put("captureId", activeCaptureId);
        }

        emitEventLocked("micStateChanged", payload);
    }

    private void emitRouteChangedLocked(String reason) {
        lastRouteChangeReason = reason;

        JSObject payload = new JSObject();
        payload.put("reason", reason);
        if (activeCaptureId != null) {
            payload.put("captureId", activeCaptureId);
        }

        String selectedInputId = resolveSelectedInputIdLocked(getInputDevices());
        if (selectedInputId != null) {
            payload.put("selectedInputId", selectedInputId);
        }

        emitEventLocked("micRouteChanged", payload);
    }

    private void emitErrorLocked(NativeMicErrorCode code, String message, boolean recoverable, String nativeCode, String captureId) {
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

        emitEventLocked("micError", payload);
    }

    private void handleAudioFocusChangeLocked(int focusChange) {
        if (activeCaptureId == null) {
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            expectedResumeAfterInterruption = state == NativeMicState.RUNNING;
            interruptionActive = true;

            if (state == NativeMicState.RUNNING) {
                state = NativeMicState.PAUSED;
                emitStateChangedLocked("interruption_began");
            }

            JSObject interruptionPayload = new JSObject();
            interruptionPayload.put("captureId", activeCaptureId);
            interruptionPayload.put("phase", "began");
            interruptionPayload.put("reason", "system_interruption");
            emitEventLocked("micInterruption", interruptionPayload);

            emitErrorLocked(NativeMicErrorCode.INTERRUPTED, "Audio session interruption began.", true, null, activeCaptureId);
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_GAIN && interruptionActive) {
            JSObject interruptionPayload = new JSObject();
            interruptionPayload.put("captureId", activeCaptureId);
            interruptionPayload.put("phase", "ended");
            interruptionPayload.put("shouldResume", true);
            emitEventLocked("micInterruption", interruptionPayload);

            if (expectedResumeAfterInterruption && state != NativeMicState.IDLE) {
                state = NativeMicState.RUNNING;
                emitStateChangedLocked("interruption_resumed");
            }

            interruptionActive = false;
            expectedResumeAfterInterruption = false;
        }
    }

    private void clearCaptureStateLocked() {
        activeConfig = null;
        activeCaptureId = null;
        outputPipelines.clear();
        captureStartPtsMs = 0;
        totalFramesIn = 0;
        totalFramesOut16k = 0;
        totalFramesOut48k = 0;
        actualInputSampleRate = 0;
        actualInputChannels = 0;
        droppedInputFrames = 0;
        levelSumSquares = 0;
        levelPeak = 0;
        levelFrames = 0;
        levelIntervalFrames = 0;
        expectedResumeAfterInterruption = false;
        interruptionActive = false;
        micEnabled = true;
    }

    private void emitEventLocked(String eventName, JSObject payload) {
        eventEmitter.emit(eventName, payload);
    }

    private void registerAudioDeviceCallbacks() {
        if (audioManager == null) {
            return;
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
    }

    private void unregisterAudioDeviceCallbacks() {
        if (audioManager == null) {
            return;
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
    }

    private AudioDeviceInfo[] getInputDevices() {
        if (audioManager == null) {
            return new AudioDeviceInfo[0];
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
    }

    private AudioDeviceInfo[] getOutputDevices() {
        if (audioManager == null) {
            return new AudioDeviceInfo[0];
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
    }

    private String resolveSelectedInputIdLocked(AudioDeviceInfo[] availableInputs) {
        if (audioRecord != null) {
            AudioDeviceInfo preferred = audioRecord.getPreferredDevice();
            if (preferred != null) {
                return String.valueOf(preferred.getId());
            }

            AudioDeviceInfo routed = audioRecord.getRoutedDevice();
            if (routed != null) {
                return String.valueOf(routed.getId());
            }
        }

        if (preferredInputId != null) {
            return preferredInputId;
        }

        if (availableInputs.length > 0) {
            return String.valueOf(availableInputs[0].getId());
        }

        return null;
    }

    private AudioDeviceInfo findInputDeviceById(String inputId) {
        AudioDeviceInfo[] inputs = getInputDevices();
        for (AudioDeviceInfo input : inputs) {
            if (String.valueOf(input.getId()).equals(inputId)) {
                return input;
            }
        }
        return null;
    }

    private AudioDeviceInfo findOutputDeviceByType(int type) {
        for (AudioDeviceInfo device : getOutputDevices()) {
            if (device.getType() == type) {
                return device;
            }
        }
        return null;
    }

    private int resolveInputSampleRate() {
        int[] candidates = new int[] { 48_000, 44_100, 32_000, 16_000 };
        for (int sampleRate : candidates) {
            int minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer > 0) {
                return sampleRate;
            }
        }
        return 48_000;
    }

    private void releaseAudioRecord(AudioRecord record) {
        if (record == null) {
            return;
        }

        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException ignored) {
            // Ignore cleanup failures.
        }

        try {
            record.release();
        } catch (Exception ignored) {
            // Ignore cleanup failures.
        }
    }

    private long monotonicMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    private static short[] convertFloatToPcm16(float[] samples) {
        if (samples.length == 0) {
            return new short[0];
        }

        short[] pcm16 = new short[samples.length];
        for (int index = 0; index < samples.length; index += 1) {
            float value = Math.max(-1f, Math.min(1f, samples[index]));
            pcm16[index] = value < 0 ? (short) Math.round(value * 32_768f) : (short) Math.round(value * 32_767f);
        }
        return pcm16;
    }

    private String encodePcm16(short[] samples) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(samples.length * 2);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            byteBuffer.putShort(sample);
        }
        return Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP);
    }

    private static String mapDeviceType(AudioDeviceInfo device) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "built_in";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "wired";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "bluetooth";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "usb";
            default:
                return "unknown";
        }
    }

    static int getDefaultChunkMs() {
        return DEFAULT_CHUNK_MS;
    }

    static int getDefaultAudioLevelIntervalMs() {
        return DEFAULT_AUDIO_LEVEL_INTERVAL_MS;
    }

    static int getDefaultFlushTimeoutMs() {
        return DEFAULT_FLUSH_TIMEOUT_MS;
    }
}

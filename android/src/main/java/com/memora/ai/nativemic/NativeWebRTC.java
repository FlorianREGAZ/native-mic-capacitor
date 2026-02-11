package com.memora.ai.nativemic;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class NativeWebRTC {

    interface EventEmitter {
        void emit(String eventName, JSObject payload);
    }

    enum NativeWebRTCErrorCode {
        WEBRTC_UNAVAILABLE("E_WEBRTC_UNAVAILABLE"),
        PC_CREATE_FAILED("E_PC_CREATE_FAILED"),
        NEGOTIATION_FAILED("E_NEGOTIATION_FAILED"),
        ICE_FAILED("E_ICE_FAILED"),
        DATA_CHANNEL_FAILED("E_DATA_CHANNEL_FAILED"),
        ALREADY_RUNNING("E_ALREADY_RUNNING"),
        NOT_RUNNING("E_NOT_RUNNING"),
        INVALID_ARGUMENT("E_INVALID_ARGUMENT"),
        INTERNAL("E_INTERNAL");

        final String wireValue;

        NativeWebRTCErrorCode(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    static final class NativeWebRTCControllerError extends Exception {

        final NativeWebRTCErrorCode code;
        final String message;
        final boolean recoverable;
        final String nativeCode;

        NativeWebRTCControllerError(
            NativeWebRTCErrorCode code,
            String message,
            boolean recoverable,
            String nativeCode
        ) {
            super(message);
            this.code = code;
            this.message = message;
            this.recoverable = recoverable;
            this.nativeCode = nativeCode;
        }
    }

    enum NativeWebRTCState {
        IDLE("idle"),
        INITIALIZING("initializing"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        READY("ready"),
        RECONNECTING("reconnecting"),
        DISCONNECTING("disconnecting"),
        ERROR("error");

        final String wireValue;

        NativeWebRTCState(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    static final class RTCIceServerLikeModel {

        final List<String> urls;
        final String username;
        final String credential;

        RTCIceServerLikeModel(List<String> urls, String username, String credential) {
            this.urls = urls;
            this.username = username;
            this.credential = credential;
        }

        PeerConnection.IceServer toIceServer() {
            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urls);
            if (username != null && !username.isEmpty()) {
                builder.setUsername(username);
            }
            if (credential != null && !credential.isEmpty()) {
                builder.setPassword(credential);
            }
            return builder.createIceServer();
        }
    }

    static final class WebRTCRequestInfoModel {

        final String endpoint;
        final Map<String, String> headers;
        final JSONObject requestData;
        final int timeoutMs;

        WebRTCRequestInfoModel(String endpoint, Map<String, String> headers, JSONObject requestData, int timeoutMs) {
            this.endpoint = endpoint;
            this.headers = headers;
            this.requestData = requestData;
            this.timeoutMs = timeoutMs;
        }
    }

    static final class MediaOptionsModel {

        final boolean voiceProcessing;
        final boolean startMicEnabled;
        final String preferredInputId;
        final NativeMic.OutputRoute outputRoute;
        final boolean outputRouteExplicit;

        MediaOptionsModel(
            boolean voiceProcessing,
            boolean startMicEnabled,
            String preferredInputId,
            NativeMic.OutputRoute outputRoute,
            boolean outputRouteExplicit
        ) {
            this.voiceProcessing = voiceProcessing;
            this.startMicEnabled = startMicEnabled;
            this.preferredInputId = preferredInputId;
            this.outputRoute = outputRoute;
            this.outputRouteExplicit = outputRouteExplicit;
        }
    }

    static final class ReconnectOptionsModel {

        final boolean enabled;
        final int maxAttempts;
        final int backoffMs;

        ReconnectOptionsModel(boolean enabled, int maxAttempts, int backoffMs) {
            this.enabled = enabled;
            this.maxAttempts = maxAttempts;
            this.backoffMs = backoffMs;
        }
    }

    static final class ConnectOptionsModel {

        final String connectionId;
        final WebRTCRequestInfoModel webrtcRequest;
        final List<RTCIceServerLikeModel> iceServers;
        final boolean waitForICEGathering;
        final String audioCodec;
        final String videoCodec;
        final MediaOptionsModel media;
        final ReconnectOptionsModel reconnect;

        ConnectOptionsModel(
            String connectionId,
            WebRTCRequestInfoModel webrtcRequest,
            List<RTCIceServerLikeModel> iceServers,
            boolean waitForICEGathering,
            String audioCodec,
            String videoCodec,
            MediaOptionsModel media,
            ReconnectOptionsModel reconnect
        ) {
            this.connectionId = connectionId;
            this.webrtcRequest = webrtcRequest;
            this.iceServers = iceServers;
            this.waitForICEGathering = waitForICEGathering;
            this.audioCodec = audioCodec;
            this.videoCodec = videoCodec;
            this.media = media;
            this.reconnect = reconnect;
        }
    }

    static final class ConnectResultModel {

        final String connectionId;
        final String pcId;
        final String selectedInputId;
        final NativeMic.OutputRoute selectedOutputRoute;
        final NativeWebRTCState state;

        ConnectResultModel(
            String connectionId,
            String pcId,
            String selectedInputId,
            NativeMic.OutputRoute selectedOutputRoute,
            NativeWebRTCState state
        ) {
            this.connectionId = connectionId;
            this.pcId = pcId;
            this.selectedInputId = selectedInputId;
            this.selectedOutputRoute = selectedOutputRoute;
            this.state = state;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            object.put("connectionId", connectionId);
            if (pcId != null && !pcId.isEmpty()) {
                object.put("pcId", pcId);
            }
            if (selectedInputId != null && !selectedInputId.isEmpty()) {
                object.put("selectedInputId", selectedInputId);
            }
            object.put("selectedOutputRoute", selectedOutputRoute.wireValue);
            object.put("state", state.wireValue);
            return object;
        }
    }

    static final class StateResultModel {

        final String connectionId;
        final NativeWebRTCState state;
        final String pcId;
        final String iceConnectionState;
        final String signalingState;

        StateResultModel(
            String connectionId,
            NativeWebRTCState state,
            String pcId,
            String iceConnectionState,
            String signalingState
        ) {
            this.connectionId = connectionId;
            this.state = state;
            this.pcId = pcId;
            this.iceConnectionState = iceConnectionState;
            this.signalingState = signalingState;
        }

        JSObject asJSObject() {
            JSObject object = new JSObject();
            object.put("connectionId", connectionId);
            object.put("state", state.wireValue);
            if (pcId != null && !pcId.isEmpty()) {
                object.put("pcId", pcId);
            }
            if (iceConnectionState != null && !iceConnectionState.isEmpty()) {
                object.put("iceConnectionState", iceConnectionState);
            }
            if (signalingState != null && !signalingState.isEmpty()) {
                object.put("signalingState", signalingState);
            }
            return object;
        }
    }

    private static final String DATA_CHANNEL_LABEL = "chat";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_ICE_GATHERING_WAIT_MS = 2_000;
    private static final int DEFAULT_RECONNECT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RECONNECT_BACKOFF_MS = 2_000;
    private static final int CANDIDATE_FLUSH_DELAY_MS = 200;

    private final Context appContext;
    private final AudioManager audioManager;
    private final EventEmitter eventEmitter;
    private final ScheduledExecutorService executor;
    private final OkHttpClient httpClient;

    private PeerConnectionFactory peerConnectionFactory;
    private JavaAudioDeviceModule audioDeviceModule;

    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;

    private ConnectOptionsModel activeConnectOptions;
    private String activeConnectionId;
    private String activePcId;
    private String preferredInputId;
    private NativeMic.OutputRoute selectedOutputRoute = NativeMic.OutputRoute.SYSTEM;
    private boolean micEnabled = true;

    private NativeWebRTCState state = NativeWebRTCState.IDLE;
    private boolean manualDisconnectRequested = false;
    private int reconnectAttempts = 0;

    private ScheduledFuture<?> candidateFlushFuture;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> statsFuture;

    private final List<IceCandidate> pendingCandidates = new ArrayList<>();
    private boolean canSendIceCandidates = false;

    private boolean localTrackStarted = false;
    private boolean remoteAudioTrackStarted = false;

    private Integer previousAudioMode;
    private Boolean previousSpeakerphoneEnabled;

    public NativeWebRTC(Context context, EventEmitter eventEmitter) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) this.appContext.getSystemService(Context.AUDIO_SERVICE);
        this.eventEmitter = eventEmitter;
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.httpClient = new OkHttpClient();

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this.appContext).createInitializationOptions()
            );
        } catch (Throwable error) {
            // Handled lazily in ensureWebRTCFactory.
        }
    }

    public void destroy() {
        Future<?> future = executor.submit(() -> {
            cleanupConnectionLocked(false, "destroy");
            closeFactoryLocked();
        });

        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        } finally {
            executor.shutdownNow();
        }
    }

    public JSObject isAvailable() {
        JSObject payload = new JSObject();

        if (audioManager == null) {
            payload.put("available", false);
            payload.put("reason", "AudioManager is unavailable.");
            return payload;
        }

        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (inputs == null || inputs.length == 0) {
            payload.put("available", false);
            payload.put("reason", "No audio input is currently available.");
            return payload;
        }

        payload.put("available", true);
        return payload;
    }

    public ConnectResultModel connect(ConnectOptionsModel options) throws NativeWebRTCControllerError {
        return runBlocking(() -> connectInternal(options));
    }

    public void disconnect(String connectionId, String reason) throws NativeWebRTCControllerError {
        runBlockingVoid(() -> disconnectInternal(connectionId, reason));
    }

    public void sendDataMessage(String connectionId, String data) throws NativeWebRTCControllerError {
        runBlockingVoid(() -> {
            assertConnectionMatches(connectionId);

            if (dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.DATA_CHANNEL_FAILED,
                    "Data channel is not open.",
                    true,
                    null
                );
            }

            ByteBuffer buffer = ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8));
            boolean sent = dataChannel.send(new DataChannel.Buffer(buffer, false));
            if (!sent) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.DATA_CHANNEL_FAILED,
                    "Failed to send data channel message.",
                    true,
                    null
                );
            }
        });
    }

    public void setMicEnabled(String connectionId, boolean enabled) throws NativeWebRTCControllerError {
        runBlockingVoid(() -> {
            assertConnectionMatches(connectionId);

            if (localAudioTrack == null) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.NOT_RUNNING,
                    "No active local audio track.",
                    false,
                    null
                );
            }

            micEnabled = enabled;
            localAudioTrack.setEnabled(enabled);
        });
    }

    public void setPreferredInput(String connectionId, String inputId) throws NativeWebRTCControllerError {
        runBlockingVoid(() -> {
            assertConnectionMatches(connectionId);

            preferredInputId = normalizeNullableString(inputId);
            validatePreferredInputLocked(preferredInputId);
            emitRouteChangedLocked("set_preferred_input");
        });
    }

    public void setOutputRoute(String connectionId, NativeMic.OutputRoute route) throws NativeWebRTCControllerError {
        runBlockingVoid(() -> {
            assertConnectionMatches(connectionId);
            selectedOutputRoute = route;
            applyOutputRouteLocked(route);
            emitRouteChangedLocked("set_output_route");
        });
    }

    public StateResultModel getState(String connectionId) throws NativeWebRTCControllerError {
        return runBlocking(() -> {
            assertConnectionMatches(connectionId);
            return new StateResultModel(
                activeConnectionId,
                state,
                activePcId,
                peerConnection != null ? peerConnection.iceConnectionState().name().toLowerCase(Locale.US) : null,
                peerConnection != null ? peerConnection.signalingState().name().toLowerCase(Locale.US) : null
            );
        });
    }

    public JSObject getDiagnostics(String connectionId) throws NativeWebRTCControllerError {
        return runBlocking(() -> {
            assertConnectionMatches(connectionId);

            JSObject diagnostics = new JSObject();
            diagnostics.put("connectionId", activeConnectionId);
            diagnostics.put("state", state.wireValue);
            diagnostics.put("pcId", activePcId);
            diagnostics.put("micEnabled", micEnabled);
            diagnostics.put("selectedOutputRoute", selectedOutputRoute.wireValue);
            if (preferredInputId != null) {
                diagnostics.put("preferredInputId", preferredInputId);
            }
            diagnostics.put("canSendIceCandidates", canSendIceCandidates);
            diagnostics.put("pendingIceCandidates", pendingCandidates.size());
            diagnostics.put("reconnectAttempts", reconnectAttempts);

            if (peerConnection != null) {
                diagnostics.put("iceConnectionState", peerConnection.iceConnectionState().name().toLowerCase(Locale.US));
                diagnostics.put("signalingState", peerConnection.signalingState().name().toLowerCase(Locale.US));
                diagnostics.put("connectionState", peerConnection.connectionState().name().toLowerCase(Locale.US));
            }

            return diagnostics;
        });
    }

    private ConnectResultModel connectInternal(ConnectOptionsModel options) throws NativeWebRTCControllerError {
        if (state != NativeWebRTCState.IDLE) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.ALREADY_RUNNING,
                "A WebRTC connection is already running.",
                false,
                null
            );
        }

        ensureWebRTCFactoryLocked();
        validatePreferredInputLocked(options.media.preferredInputId);

        activeConnectOptions = options;
        activeConnectionId = options.connectionId;
        preferredInputId = options.media.preferredInputId;
        selectedOutputRoute = options.media.outputRouteExplicit ? options.media.outputRoute : resolveDefaultOutputRoute();
        micEnabled = options.media.startMicEnabled;
        reconnectAttempts = 0;
        manualDisconnectRequested = false;
        activePcId = null;

        updateStateLocked(NativeWebRTCState.INITIALIZING, "create_peer_connection");
        configureAudioSessionLocked(options.media.voiceProcessing);
        applyOutputRouteLocked(selectedOutputRoute);

        createPeerConnectionLocked();
        createLocalAudioTrackLocked(options.media.voiceProcessing);
        bindLocalAudioTrackLocked();
        createDataChannelLocked();

        updateStateLocked(NativeWebRTCState.CONNECTING, "create_offer");
        negotiateLocked(false);

        canSendIceCandidates = true;
        flushIceCandidatesLocked();
        startStatsLoopLocked();

        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            updateStateLocked(NativeWebRTCState.READY, "data_channel_open");
        } else {
            updateStateLocked(NativeWebRTCState.CONNECTED, "remote_description_set");
        }

        return new ConnectResultModel(
            activeConnectionId,
            activePcId,
            resolveSelectedInputIdLocked(),
            selectedOutputRoute,
            state
        );
    }

    private void disconnectInternal(String connectionId, String reason) throws NativeWebRTCControllerError {
        assertConnectionMatches(connectionId);

        manualDisconnectRequested = true;
        updateStateLocked(NativeWebRTCState.DISCONNECTING, reason != null && !reason.isEmpty() ? reason : "disconnect");
        cleanupConnectionLocked(true, reason != null && !reason.isEmpty() ? reason : "disconnect");
        updateStateLocked(NativeWebRTCState.IDLE, "disconnect_complete");
    }

    private void ensureWebRTCFactoryLocked() throws NativeWebRTCControllerError {
        if (peerConnectionFactory != null) {
            return;
        }

        if (audioManager == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.WEBRTC_UNAVAILABLE,
                "AudioManager is unavailable.",
                false,
                null
            );
        }

        try {
            if (audioDeviceModule == null) {
                audioDeviceModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule();
            }

            DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(null, false, false);
            DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(null);

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
        } catch (Throwable error) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.WEBRTC_UNAVAILABLE,
                "Native WebRTC initialization failed.",
                false,
                String.valueOf(error.hashCode())
            );
        }
    }

    private void createPeerConnectionLocked() throws NativeWebRTCControllerError {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        if (activeConnectOptions != null && activeConnectOptions.iceServers != null) {
            for (RTCIceServerLikeModel iceServer : activeConnectOptions.iceServers) {
                servers.add(iceServer.toIceServer());
            }
        }

        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = peerConnectionFactory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                // no-op
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                executor.execute(() -> handleIceConnectionStateLocked(iceConnectionState));
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                // no-op
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                // polled when needed
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                executor.execute(() -> {
                    pendingCandidates.add(iceCandidate);
                    scheduleIceCandidateFlushLocked();
                });
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                // no-op
            }

            @Override
            public void onAddStream(org.webrtc.MediaStream mediaStream) {
                // no-op for unified plan
            }

            @Override
            public void onRemoveStream(org.webrtc.MediaStream mediaStream) {
                // no-op for unified plan
            }

            @Override
            public void onDataChannel(DataChannel incomingDataChannel) {
                executor.execute(() -> {
                    if (dataChannel == null) {
                        dataChannel = incomingDataChannel;
                        registerDataChannelObserverLocked();
                    }
                });
            }

            @Override
            public void onRenegotiationNeeded() {
                // server drives renegotiation through data-channel messages
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, org.webrtc.MediaStream[] mediaStreams) {
                // deprecated callback; rely on onTrack
            }

            @Override
            public void onTrack(RtpTransceiver transceiver) {
                executor.execute(() -> handleRemoteTrackLocked(transceiver));
            }
        });

        if (peerConnection == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.PC_CREATE_FAILED,
                "Failed to create peer connection.",
                false,
                null
            );
        }

        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO);
        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
        peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO);
    }

    private void createLocalAudioTrackLocked(boolean voiceProcessing) throws NativeWebRTCControllerError {
        MediaConstraints constraints = new MediaConstraints();
        String processingValue = voiceProcessing ? "true" : "false";
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", processingValue));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", processingValue));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", processingValue));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", processingValue));

        try {
            localAudioSource = peerConnectionFactory.createAudioSource(constraints);
            localAudioTrack = peerConnectionFactory.createAudioTrack("native-mic-audio", localAudioSource);
            localAudioTrack.setEnabled(micEnabled);
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.PC_CREATE_FAILED,
                "Failed to create local audio track.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void bindLocalAudioTrackLocked() {
        if (peerConnection == null || localAudioTrack == null) {
            return;
        }

        boolean attached = false;
        for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
            MediaStreamTrack track = transceiver.getReceiver() != null ? transceiver.getReceiver().track() : null;
            if (track != null && track.kind().equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
                RtpSender sender = transceiver.getSender();
                if (sender != null) {
                    sender.setTrack(localAudioTrack, false);
                    attached = true;
                    break;
                }
            }
        }

        if (!attached) {
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("native-mic-stream"));
        }

        if (!localTrackStarted) {
            localTrackStarted = true;
            emitTrackEventLocked("webrtcTrackStarted", "audio", "local");
        }
    }

    private void createDataChannelLocked() throws NativeWebRTCControllerError {
        if (peerConnection == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.PC_CREATE_FAILED,
                "Peer connection is unavailable.",
                false,
                null
            );
        }

        DataChannel.Init dataChannelInit = new DataChannel.Init();
        dataChannelInit.ordered = true;

        dataChannel = peerConnection.createDataChannel(DATA_CHANNEL_LABEL, dataChannelInit);
        if (dataChannel == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.DATA_CHANNEL_FAILED,
                "Failed to create data channel.",
                false,
                null
            );
        }

        registerDataChannelObserverLocked();
    }

    private void registerDataChannelObserverLocked() {
        if (dataChannel == null) {
            return;
        }

        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {
                // no-op
            }

            @Override
            public void onStateChange() {
                executor.execute(() -> {
                    if (dataChannel == null) {
                        return;
                    }

                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        updateStateLocked(NativeWebRTCState.READY, "data_channel_open");
                        return;
                    }

                    if (
                        dataChannel.state() == DataChannel.State.CLOSING ||
                        dataChannel.state() == DataChannel.State.CLOSED
                    ) {
                        if (state == NativeWebRTCState.READY || state == NativeWebRTCState.CONNECTED) {
                            scheduleReconnectLocked("data_channel_closed");
                        }
                    }
                });
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                executor.execute(() -> {
                    byte[] bytes = new byte[buffer.data.remaining()];
                    buffer.data.get(bytes);
                    String value;
                    if (buffer.binary) {
                        value = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    } else {
                        value = new String(bytes, StandardCharsets.UTF_8);
                    }

                    JSObject payload = new JSObject();
                    payload.put("connectionId", activeConnectionId);
                    payload.put("data", value);
                    emitEventLocked("webrtcDataMessage", payload);

                    maybeHandleSignallingMessageLocked(value);
                });
            }
        });
    }

    private void maybeHandleSignallingMessageLocked(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return;
        }

        try {
            JSONObject message = new JSONObject(rawMessage);
            if (!"signalling".equals(message.optString("type"))) {
                return;
            }

            JSONObject signal = message.optJSONObject("message");
            if (signal == null) {
                return;
            }

            String signalType = signal.optString("type", "");
            if ("renegotiate".equals(signalType)) {
                scheduleReconnectLocked("renegotiate_requested");
            } else if ("peerLeft".equals(signalType) || "peer_left".equals(signalType)) {
                try {
                    if (activeConnectionId != null) {
                        disconnectInternal(activeConnectionId, "peerLeft");
                    }
                } catch (NativeWebRTCControllerError error) {
                    emitErrorLocked(error.code, error.message, error.recoverable, error.nativeCode, activeConnectionId);
                }
            }
        } catch (JSONException ignored) {
            // not a signalling message
        }
    }

    private void negotiateLocked(boolean restartPc) throws NativeWebRTCControllerError {
        if (peerConnection == null || activeConnectOptions == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Peer connection is unavailable.",
                false,
                null
            );
        }

        SessionDescription offer = createOfferLocked();
        setLocalDescriptionLocked(offer);

        if (activeConnectOptions.waitForICEGathering) {
            waitForIceGatheringLocked(DEFAULT_ICE_GATHERING_WAIT_MS);
        }

        SessionDescription localDescription = peerConnection.getLocalDescription();
        if (localDescription == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Local description was not set.",
                false,
                null
            );
        }

        JSONObject requestPayload = new JSONObject();
        try {
            requestPayload.put("sdp", localDescription.description);
            requestPayload.put("type", localDescription.type.canonicalForm());
            requestPayload.put("pc_id", activePcId != null ? activePcId : JSONObject.NULL);
            requestPayload.put("restart_pc", restartPc);
            if (activeConnectOptions.webrtcRequest.requestData != null) {
                requestPayload.put("requestData", activeConnectOptions.webrtcRequest.requestData);
            }
        } catch (JSONException exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Failed to build offer request payload.",
                false,
                String.valueOf(exception.hashCode())
            );
        }

        JSONObject answer = executeJsonRequest(
            activeConnectOptions.webrtcRequest.endpoint,
            "POST",
            requestPayload,
            activeConnectOptions.webrtcRequest.headers,
            activeConnectOptions.webrtcRequest.timeoutMs,
            NativeWebRTCErrorCode.NEGOTIATION_FAILED,
            "Failed to negotiate WebRTC session."
        );

        String sdp = answer.optString("sdp", "");
        String type = answer.optString("type", "answer");
        String pcId = normalizeNullableString(answer.optString("pc_id", null));
        if (pcId == null) {
            pcId = normalizeNullableString(answer.optString("pcId", null));
        }
        if (pcId != null) {
            activePcId = pcId;
        }

        if (sdp == null || sdp.isEmpty()) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "WebRTC answer payload is missing SDP.",
                false,
                null
            );
        }

        SessionDescription.Type remoteType;
        try {
            remoteType = SessionDescription.Type.fromCanonicalForm(type);
        } catch (IllegalArgumentException exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "WebRTC answer has invalid SDP type.",
                false,
                String.valueOf(exception.hashCode())
            );
        }

        SessionDescription remoteDescription = new SessionDescription(remoteType, sdp);
        setRemoteDescriptionLocked(remoteDescription);
    }

    private SessionDescription createOfferLocked() throws NativeWebRTCControllerError {
        if (peerConnection == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Peer connection is unavailable.",
                false,
                null
            );
        }

        CompletableFuture<SessionDescription> future = new CompletableFuture<>();
        peerConnection.createOffer(new FutureSdpObserver(future), new MediaConstraints());

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Timed out while creating WebRTC offer.",
                false,
                null
            );
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Failed to create WebRTC offer.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void setLocalDescriptionLocked(SessionDescription description) throws NativeWebRTCControllerError {
        if (peerConnection == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Peer connection is unavailable.",
                false,
                null
            );
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setLocalDescription(new FutureSetDescriptionObserver(future), description);

        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Timed out while setting local WebRTC description.",
                false,
                null
            );
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Failed to set local WebRTC description.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void setRemoteDescriptionLocked(SessionDescription description) throws NativeWebRTCControllerError {
        if (peerConnection == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Peer connection is unavailable.",
                false,
                null
            );
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        peerConnection.setRemoteDescription(new FutureSetDescriptionObserver(future), description);

        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeoutException) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Timed out while setting remote WebRTC description.",
                false,
                null
            );
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                "Failed to set remote WebRTC description.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void waitForIceGatheringLocked(int timeoutMs) throws NativeWebRTCControllerError {
        if (peerConnection == null) {
            return;
        }

        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                return;
            }

            try {
                Thread.sleep(20);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.NEGOTIATION_FAILED,
                    "Interrupted while waiting for ICE gathering.",
                    false,
                    null
                );
            }
        }
    }

    private void scheduleIceCandidateFlushLocked() {
        if (candidateFlushFuture != null && !candidateFlushFuture.isDone()) {
            return;
        }

        candidateFlushFuture = executor.schedule(this::flushIceCandidatesLocked, CANDIDATE_FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void flushIceCandidatesLocked() {
        candidateFlushFuture = null;

        if (!canSendIceCandidates || activeConnectOptions == null || activePcId == null || activePcId.isEmpty()) {
            return;
        }

        if (pendingCandidates.isEmpty()) {
            return;
        }

        List<IceCandidate> candidates = new ArrayList<>(pendingCandidates);
        pendingCandidates.clear();

        JSONArray payloadCandidates = new JSONArray();
        for (IceCandidate candidate : candidates) {
            JSONObject candidatePayload = new JSONObject();
            try {
                candidatePayload.put("candidate", candidate.sdp);
                candidatePayload.put("sdp_mid", candidate.sdpMid);
                candidatePayload.put("sdp_mline_index", candidate.sdpMLineIndex);
                payloadCandidates.put(candidatePayload);
            } catch (JSONException exception) {
                // skip malformed candidates
            }
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("pc_id", activePcId);
            payload.put("candidates", payloadCandidates);
        } catch (JSONException exception) {
            emitErrorLocked(
                NativeWebRTCErrorCode.ICE_FAILED,
                "Failed to serialize ICE candidates.",
                true,
                String.valueOf(exception.hashCode()),
                activeConnectionId
            );
            return;
        }

        try {
            executeJsonRequest(
                activeConnectOptions.webrtcRequest.endpoint,
                "PATCH",
                payload,
                activeConnectOptions.webrtcRequest.headers,
                activeConnectOptions.webrtcRequest.timeoutMs,
                NativeWebRTCErrorCode.ICE_FAILED,
                "Failed to send ICE candidates."
            );
        } catch (NativeWebRTCControllerError error) {
            emitErrorLocked(error.code, error.message, true, error.nativeCode, activeConnectionId);
        }
    }

    private JSONObject executeJsonRequest(
        String endpoint,
        String method,
        JSONObject payload,
        Map<String, String> headers,
        int timeoutMs,
        NativeWebRTCErrorCode errorCode,
        String errorMessage
    ) throws NativeWebRTCControllerError {
        OkHttpClient requestClient = httpClient.newBuilder().callTimeout(Math.max(1_000, timeoutMs), TimeUnit.MILLISECONDS).build();

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(payload.toString(), mediaType);

        Headers.Builder headersBuilder = new Headers.Builder().add("Content-Type", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                headersBuilder.set(entry.getKey(), entry.getValue());
            }
        }

        Request.Builder builder = new Request.Builder().url(endpoint).headers(headersBuilder.build());
        if ("PATCH".equalsIgnoreCase(method)) {
            builder.method("PATCH", body);
        } else {
            builder.post(body);
        }

        try (Response response = requestClient.newCall(builder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new NativeWebRTCControllerError(
                    errorCode,
                    errorMessage,
                    false,
                    String.valueOf(response.code())
                );
            }

            if (responseBody == null || responseBody.isEmpty()) {
                return new JSONObject();
            }

            return new JSONObject(responseBody);
        } catch (IOException | JSONException exception) {
            if (exception instanceof InterruptedIOException) {
                throw new NativeWebRTCControllerError(errorCode, errorMessage, false, "timeout");
            }
            throw new NativeWebRTCControllerError(
                errorCode,
                errorMessage,
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void startStatsLoopLocked() {
        if (statsFuture != null && !statsFuture.isDone()) {
            return;
        }

        statsFuture = executor.scheduleAtFixedRate(this::emitAudioLevelsLocked, 600, 600, TimeUnit.MILLISECONDS);
    }

    private void emitAudioLevelsLocked() {
        if (peerConnection == null || activeConnectionId == null) {
            return;
        }

        peerConnection.getStats(report -> executor.execute(() -> publishAudioLevelsFromStatsLocked(report)));
    }

    private void publishAudioLevelsFromStatsLocked(RTCStatsReport report) {
        if (activeConnectionId == null) {
            return;
        }

        double localLevel = -1;
        double remoteLevel = -1;

        Map<String, RTCStats> statsMap = report.getStatsMap();
        for (RTCStats stats : statsMap.values()) {
            Map<String, Object> members = stats.getMembers();
            String type = stats.getType();

            if ("media-source".equals(type) && isAudioKind(members)) {
                localLevel = Math.max(localLevel, normalizeAudioLevel(readNumericMember(members, "audioLevel")));
            }

            if (("inbound-rtp".equals(type) || "track".equals(type)) && isAudioKind(members)) {
                remoteLevel = Math.max(remoteLevel, normalizeAudioLevel(readNumericMember(members, "audioLevel")));
            }
        }

        if (localLevel >= 0) {
            JSObject payload = new JSObject();
            payload.put("connectionId", activeConnectionId);
            payload.put("level", localLevel);
            emitEventLocked("webrtcLocalAudioLevel", payload);
        }

        if (remoteLevel >= 0) {
            JSObject payload = new JSObject();
            payload.put("connectionId", activeConnectionId);
            payload.put("level", remoteLevel);
            emitEventLocked("webrtcRemoteAudioLevel", payload);
        }
    }

    private boolean isAudioKind(Map<String, Object> members) {
        Object kind = members.get("kind");
        if (kind instanceof String && "audio".equals(kind)) {
            return true;
        }

        Object mediaType = members.get("mediaType");
        return mediaType instanceof String && "audio".equals(mediaType);
    }

    private double readNumericMember(Map<String, Object> members, String key) {
        Object value = members.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private double normalizeAudioLevel(double level) {
        if (Double.isNaN(level) || Double.isInfinite(level) || level < 0) {
            return -1;
        }
        if (level > 1) {
            return 1;
        }
        return level;
    }

    private void handleRemoteTrackLocked(RtpTransceiver transceiver) {
        if (transceiver == null || transceiver.getReceiver() == null) {
            return;
        }

        MediaStreamTrack track = transceiver.getReceiver().track();
        if (track == null) {
            return;
        }

        if (MediaStreamTrack.AUDIO_TRACK_KIND.equals(track.kind())) {
            track.setEnabled(true);
            if (!remoteAudioTrackStarted) {
                remoteAudioTrackStarted = true;
                emitTrackEventLocked("webrtcTrackStarted", "audio", "remote");
            }
            return;
        }

        if (MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.kind())) {
            emitTrackEventLocked("webrtcTrackStarted", "video", "remote");
        }
    }

    private void handleIceConnectionStateLocked(PeerConnection.IceConnectionState iceState) {
        if (iceState == PeerConnection.IceConnectionState.CONNECTED || iceState == PeerConnection.IceConnectionState.COMPLETED) {
            reconnectAttempts = 0;
            if (state == NativeWebRTCState.RECONNECTING) {
                updateStateLocked(NativeWebRTCState.CONNECTED, "reconnected");
            }
            return;
        }

        if (iceState == PeerConnection.IceConnectionState.DISCONNECTED || iceState == PeerConnection.IceConnectionState.FAILED) {
            scheduleReconnectLocked("ice_disconnected");
            return;
        }

        if (iceState == PeerConnection.IceConnectionState.CLOSED) {
            if (state == NativeWebRTCState.READY || state == NativeWebRTCState.CONNECTED) {
                scheduleReconnectLocked("ice_closed");
            }
        }
    }

    private void scheduleReconnectLocked(String reason) {
        if (manualDisconnectRequested || activeConnectOptions == null || activeConnectionId == null) {
            return;
        }

        if (!activeConnectOptions.reconnect.enabled) {
            updateStateLocked(NativeWebRTCState.ERROR, reason);
            emitErrorLocked(
                NativeWebRTCErrorCode.ICE_FAILED,
                "WebRTC connection dropped.",
                true,
                null,
                activeConnectionId
            );
            return;
        }

        if (reconnectAttempts >= activeConnectOptions.reconnect.maxAttempts) {
            updateStateLocked(NativeWebRTCState.ERROR, "reconnect_limit_reached");
            emitErrorLocked(
                NativeWebRTCErrorCode.ICE_FAILED,
                "Maximum reconnect attempts reached.",
                false,
                null,
                activeConnectionId
            );
            return;
        }

        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }

        reconnectAttempts += 1;
        updateStateLocked(NativeWebRTCState.RECONNECTING, reason);

        reconnectFuture = executor.schedule(() -> {
            try {
                performReconnectLocked();
            } catch (NativeWebRTCControllerError error) {
                emitErrorLocked(error.code, error.message, true, error.nativeCode, activeConnectionId);
                scheduleReconnectLocked("reconnect_failed");
            }
        }, activeConnectOptions.reconnect.backoffMs, TimeUnit.MILLISECONDS);
    }

    private void performReconnectLocked() throws NativeWebRTCControllerError {
        closePeerConnectionOnlyLocked();

        pendingCandidates.clear();
        canSendIceCandidates = false;

        createPeerConnectionLocked();
        createLocalAudioTrackLocked(activeConnectOptions.media.voiceProcessing);
        bindLocalAudioTrackLocked();
        createDataChannelLocked();

        negotiateLocked(true);
        canSendIceCandidates = true;
        flushIceCandidatesLocked();

        updateStateLocked(NativeWebRTCState.CONNECTED, "reconnected");
    }

    private void configureAudioSessionLocked(boolean voiceProcessing) throws NativeWebRTCControllerError {
        if (audioManager == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "AudioManager is unavailable.",
                false,
                null
            );
        }

        try {
            previousAudioMode = audioManager.getMode();
            previousSpeakerphoneEnabled = audioManager.isSpeakerphoneOn();

            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (voiceProcessing) {
                audioManager.setMicrophoneMute(false);
            }
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "Failed to configure audio session.",
                false,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private void applyOutputRouteLocked(NativeMic.OutputRoute route) throws NativeWebRTCControllerError {
        if (audioManager == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "AudioManager is unavailable.",
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
                    audioManager.setSpeakerphoneOn(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audioManager.clearCommunicationDevice();
                    }
                    break;
            }
        } catch (Exception exception) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "Failed to set output route.",
                true,
                String.valueOf(exception.hashCode())
            );
        }
    }

    private NativeMic.OutputRoute resolveDefaultOutputRoute() {
        if (audioManager == null) {
            return NativeMic.OutputRoute.RECEIVER;
        }

        for (AudioDeviceInfo output : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (output.getType()) {
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
                    return NativeMic.OutputRoute.SYSTEM;
                default:
                    break;
            }
        }

        return NativeMic.OutputRoute.RECEIVER;
    }

    private AudioDeviceInfo findOutputDeviceByType(int type) {
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == type) {
                return device;
            }
        }

        return null;
    }

    private void emitRouteChangedLocked(String reason) {
        JSObject payload = new JSObject();
        payload.put("connectionId", activeConnectionId);
        payload.put("reason", reason);

        String selectedInputId = resolveSelectedInputIdLocked();
        if (selectedInputId != null) {
            payload.put("selectedInputId", selectedInputId);
        }

        emitEventLocked("micRouteChanged", payload);
    }

    private String resolveSelectedInputIdLocked() {
        if (preferredInputId != null && !preferredInputId.isEmpty()) {
            return preferredInputId;
        }

        if (audioManager == null) {
            return null;
        }

        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (inputs == null || inputs.length == 0) {
            return null;
        }

        return String.valueOf(inputs[0].getId());
    }

    private void validatePreferredInputLocked(String preferredInput) throws NativeWebRTCControllerError {
        if (preferredInput == null || preferredInput.isEmpty() || audioManager == null) {
            return;
        }

        for (AudioDeviceInfo input : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (preferredInput.equals(String.valueOf(input.getId()))) {
                return;
            }
        }

        throw new NativeWebRTCControllerError(
            NativeWebRTCErrorCode.INVALID_ARGUMENT,
            "Input device " + preferredInput + " was not found.",
            false,
            null
        );
    }

    private void closePeerConnectionOnlyLocked() {
        stopStatsLoopLocked();
        stopReconnectLoopLocked();

        if (candidateFlushFuture != null) {
            candidateFlushFuture.cancel(true);
            candidateFlushFuture = null;
        }

        if (dataChannel != null) {
            try {
                dataChannel.unregisterObserver();
            } catch (Exception ignored) {
                // best effort
            }
            try {
                dataChannel.close();
            } catch (Exception ignored) {
                // best effort
            }
            dataChannel.dispose();
            dataChannel = null;
        }

        if (localTrackStarted) {
            emitTrackEventLocked("webrtcTrackStopped", "audio", "local");
            localTrackStarted = false;
        }

        if (remoteAudioTrackStarted) {
            emitTrackEventLocked("webrtcTrackStopped", "audio", "remote");
            remoteAudioTrackStarted = false;
        }

        if (peerConnection != null) {
            try {
                for (RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                    if (transceiver != null) {
                        transceiver.stop();
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }

            try {
                for (RtpSender sender : peerConnection.getSenders()) {
                    if (sender != null && sender.track() != null) {
                        sender.track().setEnabled(false);
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }

            try {
                peerConnection.close();
            } catch (Exception ignored) {
                // best effort
            }
            peerConnection.dispose();
            peerConnection = null;
        }

        if (localAudioTrack != null) {
            try {
                localAudioTrack.dispose();
            } catch (Exception ignored) {
                // best effort
            }
            localAudioTrack = null;
        }

        if (localAudioSource != null) {
            try {
                localAudioSource.dispose();
            } catch (Exception ignored) {
                // best effort
            }
            localAudioSource = null;
        }

        pendingCandidates.clear();
        canSendIceCandidates = false;
    }

    private void cleanupConnectionLocked(boolean resetConnectionIdentity, String reason) {
        closePeerConnectionOnlyLocked();
        teardownAudioSessionLocked();

        if (resetConnectionIdentity) {
            activeConnectOptions = null;
            activeConnectionId = null;
            activePcId = null;
            preferredInputId = null;
            micEnabled = true;
            reconnectAttempts = 0;
            manualDisconnectRequested = false;
        }

        if (reason != null && !reason.isEmpty() && state != NativeWebRTCState.IDLE) {
            updateStateLocked(NativeWebRTCState.IDLE, reason);
        }
    }

    private void teardownAudioSessionLocked() {
        if (audioManager == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
        } catch (Exception ignored) {
            // best effort
        }

        if (previousSpeakerphoneEnabled != null) {
            try {
                audioManager.setSpeakerphoneOn(previousSpeakerphoneEnabled);
            } catch (Exception ignored) {
                // best effort
            }
        }

        if (previousAudioMode != null) {
            try {
                audioManager.setMode(previousAudioMode);
            } catch (Exception ignored) {
                // best effort
            }
        }

        previousSpeakerphoneEnabled = null;
        previousAudioMode = null;
    }

    private void closeFactoryLocked() {
        if (peerConnectionFactory != null) {
            try {
                peerConnectionFactory.dispose();
            } catch (Exception ignored) {
                // best effort
            }
            peerConnectionFactory = null;
        }

        if (audioDeviceModule != null) {
            try {
                audioDeviceModule.release();
            } catch (Exception ignored) {
                // best effort
            }
            audioDeviceModule = null;
        }
    }

    private void stopStatsLoopLocked() {
        if (statsFuture != null) {
            statsFuture.cancel(true);
            statsFuture = null;
        }
    }

    private void stopReconnectLoopLocked() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void assertConnectionMatches(String connectionId) throws NativeWebRTCControllerError {
        String normalized = normalizeNullableString(connectionId);
        if (normalized == null || activeConnectionId == null || !activeConnectionId.equals(normalized)) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NOT_RUNNING,
                "No active WebRTC connection matches " + connectionId + ".",
                false,
                null
            );
        }

        if (state == NativeWebRTCState.IDLE || state == NativeWebRTCState.ERROR) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.NOT_RUNNING,
                "No active WebRTC connection matches " + connectionId + ".",
                false,
                null
            );
        }
    }

    private void updateStateLocked(NativeWebRTCState nextState, String reason) {
        state = nextState;

        JSObject payload = new JSObject();
        if (activeConnectionId != null) {
            payload.put("connectionId", activeConnectionId);
        }
        payload.put("state", state.wireValue);
        if (reason != null && !reason.isEmpty()) {
            payload.put("reason", reason);
        }
        if (activePcId != null && !activePcId.isEmpty()) {
            payload.put("pcId", activePcId);
        }

        emitEventLocked("webrtcStateChanged", payload);
    }

    private void emitTrackEventLocked(String eventName, String kind, String source) {
        JSObject payload = new JSObject();
        if (activeConnectionId != null) {
            payload.put("connectionId", activeConnectionId);
        }
        payload.put("kind", kind);
        payload.put("source", source);
        emitEventLocked(eventName, payload);
    }

    private void emitErrorLocked(
        NativeWebRTCErrorCode code,
        String message,
        boolean recoverable,
        String nativeCode,
        String connectionId
    ) {
        JSObject payload = new JSObject();
        payload.put("code", code.wireValue);
        payload.put("message", message);
        payload.put("recoverable", recoverable);
        if (connectionId != null && !connectionId.isEmpty()) {
            payload.put("connectionId", connectionId);
        }
        if (nativeCode != null) {
            payload.put("nativeCode", nativeCode);
        }

        emitEventLocked("webrtcError", payload);
    }

    private void emitEventLocked(String eventName, JSObject payload) {
        eventEmitter.emit(eventName, payload);
    }

    private <T> T runBlocking(Callable<T> callable) throws NativeWebRTCControllerError {
        Future<T> future = executor.submit(callable);
        try {
            return future.get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "Thread interrupted while executing WebRTC operation.",
                false,
                null
            );
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof NativeWebRTCControllerError) {
                throw (NativeWebRTCControllerError) cause;
            }
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INTERNAL,
                "Unexpected WebRTC operation failure.",
                false,
                cause != null ? String.valueOf(cause.hashCode()) : null
            );
        }
    }

    private void runBlockingVoid(ThrowingRunnable runnable) throws NativeWebRTCControllerError {
        runBlocking(() -> {
            runnable.run();
            return null;
        });
    }

    static ConnectOptionsModel parseConnectOptions(Map<String, Object> rawOptions) throws NativeWebRTCControllerError {
        if (rawOptions == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "options are required.",
                false,
                null
            );
        }

        String connectionId = normalizeNullableString(asString(rawOptions.get("connectionId")));
        if (connectionId == null) {
            connectionId = UUID.randomUUID().toString();
        }

        Map<String, Object> requestObject = asMap(rawOptions.get("webrtcRequest"));
        if (requestObject == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "webrtcRequest is required.",
                false,
                null
            );
        }

        String endpoint = normalizeNullableString(asString(requestObject.get("endpoint")));
        if (endpoint == null) {
            throw new NativeWebRTCControllerError(
                NativeWebRTCErrorCode.INVALID_ARGUMENT,
                "webrtcRequest.endpoint is required.",
                false,
                null
            );
        }

        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, Object> headersObject = asMap(requestObject.get("headers"));
        if (headersObject != null) {
            for (Map.Entry<String, Object> entry : headersObject.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                headers.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        JSONObject requestData = null;
        Map<String, Object> requestDataMap = asMap(requestObject.get("requestData"));
        if (requestDataMap != null) {
            requestData = new JSONObject(requestDataMap);
        }

        int timeoutMs = asInt(requestObject.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
        timeoutMs = Math.max(1_000, timeoutMs);

        WebRTCRequestInfoModel request = new WebRTCRequestInfoModel(endpoint, headers, requestData, timeoutMs);

        List<RTCIceServerLikeModel> iceServers = parseIceServers(rawOptions);

        boolean waitForIceGathering = asBoolean(rawOptions.get("waitForICEGathering"), false);
        String audioCodec = parseNullableCodec(rawOptions.get("audioCodec"));
        String videoCodec = parseNullableCodec(rawOptions.get("videoCodec"));

        Map<String, Object> mediaObject = asMap(rawOptions.get("media"));
        boolean voiceProcessing = mediaObject == null || asBoolean(mediaObject.get("voiceProcessing"), true);
        boolean startMicEnabled = mediaObject == null || asBoolean(mediaObject.get("startMicEnabled"), true);
        String preferredInputId = mediaObject != null ? normalizeNullableString(asString(mediaObject.get("preferredInputId"))) : null;

        NativeMic.OutputRoute outputRoute = NativeMic.OutputRoute.RECEIVER;
        boolean outputRouteExplicit = false;
        if (mediaObject != null && mediaObject.containsKey("outputRoute")) {
            String routeValue = asString(mediaObject.get("outputRoute"));
            NativeMic.OutputRoute parsedRoute = NativeMic.OutputRoute.fromWireValue(routeValue);
            if (parsedRoute == null) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.INVALID_ARGUMENT,
                    "media.outputRoute must be one of: system, speaker, receiver.",
                    false,
                    null
                );
            }
            outputRoute = parsedRoute;
            outputRouteExplicit = true;
        }

        MediaOptionsModel media = new MediaOptionsModel(
            voiceProcessing,
            startMicEnabled,
            preferredInputId,
            outputRoute,
            outputRouteExplicit
        );

        Map<String, Object> reconnectObject = asMap(rawOptions.get("reconnect"));
        boolean reconnectEnabled = reconnectObject == null || asBoolean(reconnectObject.get("enabled"), true);
        int reconnectMaxAttempts = reconnectObject != null
            ? Math.max(0, asInt(reconnectObject.get("maxAttempts"), DEFAULT_RECONNECT_MAX_ATTEMPTS))
            : DEFAULT_RECONNECT_MAX_ATTEMPTS;
        int reconnectBackoffMs = reconnectObject != null
            ? Math.max(250, asInt(reconnectObject.get("backoffMs"), DEFAULT_RECONNECT_BACKOFF_MS))
            : DEFAULT_RECONNECT_BACKOFF_MS;

        ReconnectOptionsModel reconnect = new ReconnectOptionsModel(
            reconnectEnabled,
            reconnectMaxAttempts,
            reconnectBackoffMs
        );

        return new ConnectOptionsModel(
            connectionId,
            request,
            iceServers,
            waitForIceGathering,
            audioCodec,
            videoCodec,
            media,
            reconnect
        );
    }

    static List<RTCIceServerLikeModel> parseIceServers(Map<String, Object> rawOptions) throws NativeWebRTCControllerError {
        if (rawOptions == null) {
            return Collections.emptyList();
        }

        Map<String, Object> iceConfigObject = asMap(rawOptions.get("iceConfig"));
        if (iceConfigObject == null) {
            return Collections.emptyList();
        }

        List<Object> iceServersArray = asList(iceConfigObject.get("iceServers"));
        if (iceServersArray == null) {
            return Collections.emptyList();
        }

        List<RTCIceServerLikeModel> parsed = new ArrayList<>();
        for (int index = 0; index < iceServersArray.size(); index += 1) {
            Object rawServer = iceServersArray.get(index);
            Map<String, Object> serverObject = asMap(rawServer);
            if (serverObject == null) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.INVALID_ARGUMENT,
                    "iceConfig.iceServers[" + index + "] must be an object.",
                    false,
                    null
                );
            }

            Object urlsRaw = serverObject.get("urls");

            List<String> urls = new ArrayList<>();
            List<Object> urlsArray = asList(urlsRaw);
            if (urlsArray != null) {
                for (int urlIndex = 0; urlIndex < urlsArray.size(); urlIndex += 1) {
                    String value = normalizeNullableString(asString(urlsArray.get(urlIndex)));
                    if (value != null) {
                        urls.add(value);
                    }
                }
            } else {
                String value = normalizeNullableString(asString(serverObject.get("urls")));
                if (value != null) {
                    urls.add(value);
                }
            }

            if (urls.isEmpty()) {
                throw new NativeWebRTCControllerError(
                    NativeWebRTCErrorCode.INVALID_ARGUMENT,
                    "iceConfig.iceServers[" + index + "].urls is required.",
                    false,
                    null
                );
            }

            String username = normalizeNullableString(asString(serverObject.get("username")));
            String credential = normalizeNullableString(asString(serverObject.get("credential")));

            parsed.add(new RTCIceServerLikeModel(urls, username, credential));
        }

        return parsed;
    }

    static String parseNullableCodec(Object rawCodec) {
        if (rawCodec == null || rawCodec == JSONObject.NULL) {
            return null;
        }

        String value = normalizeNullableString(String.valueOf(rawCodec));
        if (value == null || "default".equalsIgnoreCase(value)) {
            return null;
        }

        return value;
    }

    static String normalizeNullableString(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Map<String, Object> extractMap(Object value) {
        return asMap(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> result = new LinkedHashMap<>();
            JSONArray names = object.names();
            if (names == null) {
                return result;
            }
            for (int index = 0; index < names.length(); index += 1) {
                String key = names.optString(index, null);
                if (key == null) {
                    continue;
                }
                result.put(key, deepConvertJsonValue(object.opt(key)));
            }
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        if (value instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int index = 0; index < jsonArray.length(); index += 1) {
                list.add(jsonArray.opt(index));
            }
            return list;
        }
        return null;
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return String.valueOf(value);
    }

    private static Object deepConvertJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return asMap(value);
        }
        if (value instanceof JSONArray) {
            return asList(value);
        }
        return value;
    }

    private interface ThrowingRunnable {
        void run() throws NativeWebRTCControllerError;
    }

    private static final class FutureSdpObserver implements SdpObserver {

        private final CompletableFuture<SessionDescription> future;

        FutureSdpObserver(CompletableFuture<SessionDescription> future) {
            this.future = future;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            future.complete(sessionDescription);
        }

        @Override
        public void onSetSuccess() {
            // no-op
        }

        @Override
        public void onCreateFailure(String s) {
            future.completeExceptionally(new IllegalStateException(s));
        }

        @Override
        public void onSetFailure(String s) {
            future.completeExceptionally(new IllegalStateException(s));
        }
    }

    private static final class FutureSetDescriptionObserver implements SdpObserver {

        private final CompletableFuture<Void> future;

        FutureSetDescriptionObserver(CompletableFuture<Void> future) {
            this.future = future;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            // no-op
        }

        @Override
        public void onSetSuccess() {
            future.complete(null);
        }

        @Override
        public void onCreateFailure(String s) {
            future.completeExceptionally(new IllegalStateException(s));
        }

        @Override
        public void onSetFailure(String s) {
            future.completeExceptionally(new IllegalStateException(s));
        }
    }
}

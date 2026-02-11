import { WebPlugin } from "@capacitor/core";

import type {
	MicDevice,
	MicPermissionState,
	MicProfile,
	NativeMicPlugin,
	NativeWebRTCConnectOptions,
	NativeWebRTCConnectResult,
	NativeWebRTCErrorCode,
	NativeWebRTCStateResult,
	OutputRoute,
	OutputStream,
	SessionMode,
	StartCaptureOptions,
	StartCaptureResult,
	StopCaptureOptions,
	StopCaptureResult,
} from "./definitions";

type NativeMicState = "idle" | "running" | "paused";

type NativeMicErrorCode =
	| "E_PERMISSION_DENIED"
	| "E_PERMISSION_RESTRICTED"
	| "E_ALREADY_RUNNING"
	| "E_NOT_RUNNING"
	| "E_AUDIO_SESSION_CONFIG"
	| "E_ENGINE_START_FAILED"
	| "E_ENGINE_STOP_FAILED"
	| "E_CONVERTER_FAILED"
	| "E_ROUTE_CHANGE_FAILED"
	| "E_INTERRUPTED"
	| "E_MEDIA_SERVICES_RESET"
	| "E_INTERNAL";

interface NativeMicPluginError extends Error {
	code: NativeMicErrorCode;
	data: Record<string, unknown>;
}

interface NativeWebRTCPluginError extends Error {
	code: NativeWebRTCErrorCode;
	data: Record<string, unknown>;
}

interface CaptureConfig {
	profile: MicProfile;
	mode: SessionMode;
	outputStreams: OutputStream[];
	chunkMs: number;
	emitAudioLevel: boolean;
	audioLevelIntervalMs: number;
	voiceProcessing: boolean;
	preferredInputId?: string;
	outputRoute: OutputRoute;
}

interface StreamPipeline {
	stream: OutputStream;
	sampleRate: 16000 | 48000;
	chunkFrames: number;
	seq: number;
	emittedFrames: number;
	pendingSamples: number[];
	resampler: LinearResampler;
}

class LinearResampler {
	private readonly step: number;
	private nextInputIndex = 0;
	private chunkStartIndex = 0;
	private lastSample = 0;
	private hasLastSample = false;

	constructor(inputSampleRate: number, outputSampleRate: number) {
		this.step = inputSampleRate / outputSampleRate;
	}

	process(input: Float32Array): Float32Array {
		if (input.length === 0) {
			return new Float32Array(0);
		}

		const output: number[] = [];
		const chunkStart = this.chunkStartIndex;
		const chunkEnd = chunkStart + input.length - 1;

		while (this.nextInputIndex <= chunkEnd) {
			const baseIndex = Math.floor(this.nextInputIndex);
			const fraction = this.nextInputIndex - baseIndex;

			let sampleA: number;
			if (baseIndex < chunkStart) {
				if (!this.hasLastSample) {
					break;
				}
				sampleA = this.lastSample;
			} else {
				sampleA = input[baseIndex - chunkStart] ?? this.lastSample;
			}

			const nextIndex = baseIndex + 1;
			let sampleB: number;
			if (nextIndex <= chunkEnd) {
				sampleB = input[nextIndex - chunkStart] ?? sampleA;
			} else {
				break;
			}

			output.push(sampleA + (sampleB - sampleA) * fraction);
			this.nextInputIndex += this.step;
		}

		this.chunkStartIndex += input.length;
		this.lastSample = input[input.length - 1];
		this.hasLastSample = true;

		return Float32Array.from(output);
	}

	flush(): Float32Array {
		if (!this.hasLastSample) {
			return new Float32Array(0);
		}

		const output: number[] = [];
		const lastIndex = this.chunkStartIndex - 1;

		while (this.nextInputIndex <= lastIndex) {
			output.push(this.lastSample);
			this.nextInputIndex += this.step;
		}

		return Float32Array.from(output);
	}
}

const DEFAULT_CHUNK_MS = 20;
const DEFAULT_AUDIO_LEVEL_INTERVAL_MS = 50;
const DEFAULT_FLUSH_TIMEOUT_MS = 150;
const PERMISSION_DENIED_MESSAGE = "Microphone permission denied.";

export class NativeMicWeb extends WebPlugin implements NativeMicPlugin {
	private state: NativeMicState = "idle";
	private fallbackPermissionState: MicPermissionState = "prompt";
	private activeCaptureId?: string;
	private activeConfig?: CaptureConfig;
	private preferredInputId?: string;
	private selectedOutputRoute: OutputRoute = "system";
	private micEnabled = true;

	private captureStartPtsMs = 0;
	private totalFramesIn = 0;
	private totalFramesOut16k = 0;
	private totalFramesOut48k = 0;
	private actualInputSampleRate = 0;
	private actualInputChannels = 0;
	private droppedInputFrames = 0;
	private mediaServicesResetCount = 0;
	private lastRouteChangeReason = "unknown";

	private levelSumSquares = 0;
	private levelPeak = 0;
	private levelFrames = 0;
	private levelIntervalFrames = 0;

	private audioContext: AudioContext | null = null;
	private mediaStream: MediaStream | null = null;
	private mediaStreamSource: MediaStreamAudioSourceNode | null = null;
	private processorNode: ScriptProcessorNode | null = null;
	private sinkNode: GainNode | null = null;

	private readonly pipelines = new Map<OutputStream, StreamPipeline>();

	constructor() {
		super();

		if (this.hasMediaDeviceApi() && navigator.mediaDevices.addEventListener) {
			navigator.mediaDevices.addEventListener(
				"devicechange",
				this.handleDeviceChange,
			);
		}
	}

	async isAvailable(): Promise<{ available: boolean; reason?: string }> {
		if (!this.canCaptureAudio()) {
			return {
				available: false,
				reason: "No audio input is currently available.",
			};
		}

		try {
			const inputs = await this.enumerateAudioInputs();
			if (inputs.length === 0) {
				return {
					available: false,
					reason: "No audio input is currently available.",
				};
			}
		} catch {
			// Some browsers block enumerateDevices until permission is granted.
		}

		return { available: true };
	}

	async checkPermissions(): Promise<{ microphone: MicPermissionState }> {
		if (this.mediaStream) {
			this.fallbackPermissionState = "granted";
			return { microphone: "granted" };
		}

		if (!this.hasMediaDeviceApi()) {
			this.fallbackPermissionState = "denied";
			return { microphone: "denied" };
		}

		try {
			if (!navigator.permissions?.query) {
				return { microphone: this.fallbackPermissionState };
			}
			const result = await navigator.permissions.query({
				name: "microphone" as PermissionName,
			});
			const microphone = this.mapPermissionState(result.state);
			this.fallbackPermissionState = microphone;
			return { microphone };
		} catch {
			return { microphone: this.fallbackPermissionState };
		}
	}

	async requestPermissions(): Promise<{ microphone: MicPermissionState }> {
		if (!this.canCaptureAudio()) {
			return { microphone: "denied" };
		}

		try {
			const stream = await navigator.mediaDevices.getUserMedia({
				audio: true,
				video: false,
			});
			this.stopStreamTracks(stream);
			this.fallbackPermissionState = "granted";
			return { microphone: "granted" };
		} catch (error) {
			if (this.isPermissionDeniedError(error)) {
				this.fallbackPermissionState = "denied";
				return { microphone: "denied" };
			}
			const result = await this.checkPermissions();
			this.fallbackPermissionState = result.microphone;
			return result;
		}
	}

	async getDevices(): Promise<{
		inputs: MicDevice[];
		selectedInputId?: string;
	}> {
		if (!this.hasMediaDeviceApi() || !navigator.mediaDevices.enumerateDevices) {
			return { inputs: [] };
		}

		const devices = await navigator.mediaDevices.enumerateDevices();
		const audioInputs = devices.filter(
			(device) => device.kind === "audioinput",
		);

		const selectedInputId = this.getSelectedInputId();
		const fallbackSelectedId =
			selectedInputId ?? this.preferredInputId ?? audioInputs[0]?.deviceId;

		const inputs = audioInputs.map((device, index): MicDevice => {
			const id = device.deviceId;
			return {
				id,
				label: device.label || `Microphone ${index + 1}`,
				type: this.mapInputType(device.label),
				isDefault:
					id === fallbackSelectedId || (!fallbackSelectedId && index === 0),
			};
		});

		return {
			inputs,
			selectedInputId: fallbackSelectedId,
		};
	}

	async setPreferredInput(options: { inputId: string | null }): Promise<void> {
		const inputId =
			typeof options?.inputId === "string" ? options.inputId : null;
		this.preferredInputId = inputId ?? undefined;

		if (this.state !== "idle") {
			await this.applyPreferredInputForActiveCapture();
		}
	}

	async setOutputRoute(options: { route: OutputRoute }): Promise<void> {
		const route = options?.route;
		if (!this.isOutputRoute(route)) {
			this.reject(
				"E_INTERNAL",
				"route must be one of: system, speaker, receiver.",
				false,
				undefined,
			);
		}

		this.selectedOutputRoute = route;

		if (this.state !== "idle") {
			this.emitRouteChanged("set_output_route");
		}
	}

	async startCapture(
		options: StartCaptureOptions,
	): Promise<StartCaptureResult> {
		if (this.state === "running" || this.state === "paused") {
			this.reject(
				"E_ALREADY_RUNNING",
				"Capture is already running.",
				false,
				undefined,
			);
		}

		const config = this.normalizeStartOptions(options);
		await this.ensurePermissionForStart();
		await this.validatePreferredInputForStart(config.preferredInputId);

		this.selectedOutputRoute = config.outputRoute;
		if (config.preferredInputId !== undefined) {
			this.preferredInputId = config.preferredInputId;
		}

		const captureId = this.createCaptureId();

		try {
			this.mediaStream = await this.acquireMediaStream(config);
			this.audioContext = await this.createAudioContext();

			const context = this.audioContext;
			const stream = this.mediaStream;

			if (!context || !stream) {
				this.reject(
					"E_ENGINE_START_FAILED",
					"Failed to start AVAudioEngine.",
					false,
					undefined,
				);
			}

			this.mediaStreamSource = context.createMediaStreamSource(stream);
			const processorBufferSize = this.resolveProcessorBufferSize(
				context.sampleRate,
				config.chunkMs,
			);
			this.processorNode = context.createScriptProcessor(
				processorBufferSize,
				Math.max(1, this.mediaStreamSource.channelCount),
				1,
			);
			this.sinkNode = context.createGain();
			this.sinkNode.gain.value = 0;

			this.activeCaptureId = captureId;
			this.activeConfig = config;
			this.captureStartPtsMs = this.monotonicMs();
			this.micEnabled = true;

			this.totalFramesIn = 0;
			this.totalFramesOut16k = 0;
			this.totalFramesOut48k = 0;
			this.droppedInputFrames = 0;
			this.levelSumSquares = 0;
			this.levelPeak = 0;
			this.levelFrames = 0;

			this.actualInputSampleRate = context.sampleRate;
			this.actualInputChannels = this.resolveInputChannelCount(
				this.mediaStreamSource,
				stream,
			);
			this.levelIntervalFrames = Math.max(
				1,
				Math.floor(
					(this.actualInputSampleRate * config.audioLevelIntervalMs) / 1_000,
				),
			);

			this.setupPipelines(
				config.outputStreams,
				this.actualInputSampleRate,
				config.chunkMs,
			);

			this.processorNode.onaudioprocess = (event: AudioProcessingEvent) => {
				this.handleInputBuffer(event.inputBuffer);
			};

			context.onstatechange = () => {
				this.handleAudioContextStateChange();
			};

			this.mediaStreamSource.connect(this.processorNode);
			this.processorNode.connect(this.sinkNode);
			this.sinkNode.connect(context.destination);

			this.attachTrackEndedListener(stream);

			if (context.state === "suspended") {
				await context.resume();
			}

			this.state = "running";
			this.emitStateChanged("start_capture");

			return {
				captureId,
				actualInputSampleRate: this.actualInputSampleRate,
				actualInputChannels: this.actualInputChannels,
				chunkMs: config.chunkMs,
			};
		} catch (error) {
			await this.teardownAudioGraph();
			this.clearCaptureState();
			this.state = "idle";

			if (this.isPluginError(error)) {
				throw error;
			}

			this.reject(
				"E_ENGINE_START_FAILED",
				"Failed to start AVAudioEngine.",
				false,
				undefined,
				this.nativeCodeFromError(error),
			);
		}
	}

	async stopCapture(options: StopCaptureOptions): Promise<StopCaptureResult> {
		const captureId =
			typeof options?.captureId === "string" ? options.captureId : "";
		if (!captureId) {
			this.reject("E_INTERNAL", "captureId is required.", false, undefined);
		}

		if (
			!this.activeCaptureId ||
			!this.activeConfig ||
			this.activeCaptureId !== captureId
		) {
			this.reject(
				"E_NOT_RUNNING",
				`No active capture matches ${captureId}.`,
				false,
				captureId,
			);
		}

		const flushTimeoutMs = Number.isFinite(options?.flushTimeoutMs)
			? Number(options.flushTimeoutMs)
			: DEFAULT_FLUSH_TIMEOUT_MS;
		const clampedTimeoutMs = Math.max(10, flushTimeoutMs);
		void clampedTimeoutMs;

		const activeCaptureId = this.activeCaptureId;
		const durationMs = Math.max(0, this.monotonicMs() - this.captureStartPtsMs);

		this.flushPipelinesAtStop();

		const teardownError = await this.teardownAudioGraph();
		if (teardownError) {
			this.emitError(
				"E_ENGINE_STOP_FAILED",
				"Failed to deactivate audio session.",
				true,
				activeCaptureId,
				this.nativeCodeFromError(teardownError),
			);
		}

		const result: StopCaptureResult = {
			captureId: activeCaptureId,
			totalFramesIn: this.totalFramesIn,
			totalFramesOut16k: this.totalFramesOut16k,
			totalFramesOut48k: this.totalFramesOut48k,
			durationMs,
		};

		this.clearCaptureState();
		this.state = "idle";
		this.emitStateChanged("stop_capture");

		return result;
	}

	async setMicEnabled(options: {
		captureId: string;
		enabled: boolean;
	}): Promise<void> {
		const captureId =
			typeof options?.captureId === "string" ? options.captureId : "";
		if (!captureId) {
			this.reject("E_INTERNAL", "captureId is required.", false, undefined);
		}

		const enabled = options?.enabled;
		if (typeof enabled !== "boolean") {
			this.reject("E_INTERNAL", "enabled must be a boolean.", false, captureId);
		}

		if (
			!this.activeCaptureId ||
			this.activeCaptureId !== captureId ||
			this.state === "idle"
		) {
			this.reject(
				"E_NOT_RUNNING",
				`No active capture matches ${captureId}.`,
				false,
				captureId,
			);
		}

		this.micEnabled = enabled;
	}

	async getState(): Promise<{ state: "idle" | "running" | "paused" }> {
		return { state: this.state };
	}

	async getDiagnostics(): Promise<Record<string, unknown>> {
		const diagnostics: Record<string, unknown> = {
			state: this.state,
			micEnabled: this.micEnabled,
			outputRoute: this.selectedOutputRoute,
			actualInputSampleRate: this.actualInputSampleRate,
			actualInputChannels: this.actualInputChannels,
			totalFramesIn: this.totalFramesIn,
			totalFramesOut16k: this.totalFramesOut16k,
			totalFramesOut48k: this.totalFramesOut48k,
			inputFramesDropped: this.droppedInputFrames,
			inputRingBufferedFrames: 0,
			mediaServicesResetCount: this.mediaServicesResetCount,
			lastRouteChangeReason: this.lastRouteChangeReason,
		};

		if (this.activeCaptureId) {
			diagnostics.captureId = this.activeCaptureId;
		}
		if (this.preferredInputId) {
			diagnostics.preferredInputId = this.preferredInputId;
		}

		return diagnostics;
	}

	async webrtcIsAvailable(): Promise<{
		available: boolean;
		reason?: string;
	}> {
		return {
			available: false,
			reason: "Native WebRTC is not implemented on web.",
		};
	}

	async webrtcConnect(
		options: NativeWebRTCConnectOptions,
	): Promise<NativeWebRTCConnectResult> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcDisconnect(options: {
		connectionId: string;
		reason?: string;
	}): Promise<void> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcSendDataMessage(options: {
		connectionId: string;
		data: string;
	}): Promise<void> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcSetMicEnabled(options: {
		connectionId: string;
		enabled: boolean;
	}): Promise<void> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcSetPreferredInput(options: {
		connectionId: string;
		inputId: string | null;
	}): Promise<void> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcSetOutputRoute(options: {
		connectionId: string;
		route: OutputRoute;
	}): Promise<void> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcGetState(options: {
		connectionId: string;
	}): Promise<NativeWebRTCStateResult> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	async webrtcGetDiagnostics(options: {
		connectionId: string;
	}): Promise<Record<string, unknown>> {
		const connectionId = this.resolveWebRTCConnectionId(options?.connectionId);
		this.rejectWebRTC(
			"E_WEBRTC_UNAVAILABLE",
			"Native WebRTC is not implemented on web.",
			false,
			connectionId,
		);
	}

	private mapPermissionState(state: PermissionState): MicPermissionState {
		switch (state) {
			case "granted":
				return "granted";
			case "denied":
				return "denied";
			default:
				return "prompt";
		}
	}

	private hasMediaDeviceApi(): boolean {
		return typeof navigator !== "undefined" && !!navigator.mediaDevices;
	}

	private canCaptureAudio(): boolean {
		return (
			this.hasMediaDeviceApi() &&
			typeof navigator.mediaDevices.getUserMedia === "function"
		);
	}

	private async ensurePermissionForStart(): Promise<void> {
		const permission = await this.checkPermissions();
		if (permission.microphone === "granted") {
			return;
		}
		if (permission.microphone === "denied") {
			this.reject(
				"E_PERMISSION_DENIED",
				PERMISSION_DENIED_MESSAGE,
				false,
				undefined,
			);
		}
		this.reject(
			"E_PERMISSION_DENIED",
			"Microphone permission not determined.",
			false,
			undefined,
		);
	}

	private normalizeStartOptions(options: StartCaptureOptions): CaptureConfig {
		if (!this.isMicProfile(options?.profile)) {
			this.reject(
				"E_INTERNAL",
				"profile is required and must be waveform or pipecat.",
				false,
				undefined,
			);
		}

		if (!this.isSessionMode(options?.mode)) {
			this.reject(
				"E_INTERNAL",
				"mode is required and must be measurement or voice_chat.",
				false,
				undefined,
			);
		}

		const outputStreams = this.parseOutputStreams(options?.outputStreams);
		if (!outputStreams || outputStreams.length === 0) {
			this.reject(
				"E_INTERNAL",
				"outputStreams must include pcm16k_s16le, pcm48k_s16le, or both.",
				false,
				undefined,
			);
		}

		const chunkMs = Number.isFinite(options?.chunkMs)
			? Number(options.chunkMs)
			: DEFAULT_CHUNK_MS;
		if (chunkMs !== DEFAULT_CHUNK_MS) {
			this.reject(
				"E_INTERNAL",
				"Only 20ms chunking is supported.",
				false,
				undefined,
			);
		}

		if (options.profile === "waveform" && options.mode !== "measurement") {
			this.reject(
				"E_INTERNAL",
				"Waveform profile requires measurement mode.",
				false,
				undefined,
			);
		}

		if (options.profile === "pipecat" && options.mode !== "voice_chat") {
			this.reject(
				"E_INTERNAL",
				"Pipecat profile requires voice_chat mode.",
				false,
				undefined,
			);
		}

		const emitAudioLevel = options.emitAudioLevel ?? true;
		const audioLevelIntervalMs = Math.max(
			20,
			Number.isFinite(options.audioLevelIntervalMs)
				? Number(options.audioLevelIntervalMs)
				: DEFAULT_AUDIO_LEVEL_INTERVAL_MS,
		);
		const voiceProcessing =
			options.voiceProcessing ?? options.profile === "pipecat";
		const preferredInputId =
			typeof options.preferredInputId === "string"
				? options.preferredInputId
				: undefined;
		const outputRoute = this.isOutputRoute(options.outputRoute)
			? options.outputRoute
			: "system";

		return {
			profile: options.profile,
			mode: options.mode,
			outputStreams,
			chunkMs,
			emitAudioLevel,
			audioLevelIntervalMs,
			voiceProcessing,
			preferredInputId,
			outputRoute,
		};
	}

	private parseOutputStreams(
		streams: OutputStream[] | undefined,
	): OutputStream[] | null {
		if (!Array.isArray(streams)) {
			return null;
		}

		const parsed: OutputStream[] = [];
		const seen = new Set<OutputStream>();

		for (const rawStream of streams) {
			if (!this.isOutputStream(rawStream)) {
				return null;
			}
			if (!seen.has(rawStream)) {
				seen.add(rawStream);
				parsed.push(rawStream);
			}
		}

		return parsed;
	}

	private async validatePreferredInputForStart(
		inputId: string | undefined,
	): Promise<void> {
		const preferredInputId = inputId ?? this.preferredInputId;
		if (!preferredInputId) {
			return;
		}

		const devices = await this.enumerateAudioInputs();
		if (devices.length === 0) {
			return;
		}

		const found = devices.some(
			(device) => device.deviceId === preferredInputId,
		);
		if (!found) {
			this.reject(
				"E_ROUTE_CHANGE_FAILED",
				`Input device ${preferredInputId} was not found.`,
				false,
				undefined,
			);
		}
	}

	private async acquireMediaStream(
		config: CaptureConfig,
	): Promise<MediaStream> {
		if (!this.canCaptureAudio()) {
			this.reject(
				"E_AUDIO_SESSION_CONFIG",
				"No audio input is currently available.",
				false,
				undefined,
			);
		}

		const preferredInputId = config.preferredInputId ?? this.preferredInputId;
		const audioConstraints: MediaTrackConstraints = {
			channelCount: { ideal: 1 },
			echoCancellation: config.voiceProcessing,
			noiseSuppression: config.voiceProcessing,
			autoGainControl: config.voiceProcessing,
		};

		if (preferredInputId) {
			audioConstraints.deviceId = { exact: preferredInputId };
		}

		try {
			const stream = await navigator.mediaDevices.getUserMedia({
				audio: audioConstraints,
				video: false,
			});
			this.fallbackPermissionState = "granted";
			return stream;
		} catch (error) {
			if (this.isPermissionDeniedError(error)) {
				this.fallbackPermissionState = "denied";
				this.reject(
					"E_PERMISSION_DENIED",
					PERMISSION_DENIED_MESSAGE,
					false,
					undefined,
				);
			}

			if (preferredInputId && this.isInputNotFoundError(error)) {
				this.reject(
					"E_ROUTE_CHANGE_FAILED",
					`Input device ${preferredInputId} was not found.`,
					false,
					undefined,
				);
			}

			this.reject(
				"E_ENGINE_START_FAILED",
				"Failed to start AVAudioEngine.",
				false,
				undefined,
				this.nativeCodeFromError(error),
			);
		}
	}

	private async createAudioContext(): Promise<AudioContext> {
		const globalObject = globalThis as typeof globalThis & {
			AudioContext?: typeof AudioContext;
			webkitAudioContext?: typeof AudioContext;
		};
		const AudioContextConstructor =
			globalObject.AudioContext ?? globalObject.webkitAudioContext;

		if (!AudioContextConstructor) {
			this.reject(
				"E_AUDIO_SESSION_CONFIG",
				"Failed to configure AVAudioSession.",
				false,
				undefined,
			);
		}

		return new AudioContextConstructor({
			latencyHint: "interactive",
		});
	}

	private resolveProcessorBufferSize(
		sampleRate: number,
		chunkMs: number,
	): number {
		const targetFrames = Math.max(
			256,
			Math.floor((sampleRate * chunkMs) / 1_000),
		);
		const supportedSizes = [256, 512, 1024, 2048, 4096, 8192, 16384];
		for (const size of supportedSizes) {
			if (size >= targetFrames) {
				return size;
			}
		}
		return 16384;
	}

	private resolveInputChannelCount(
		source: MediaStreamAudioSourceNode,
		stream: MediaStream,
	): number {
		const track = stream.getAudioTracks()[0];
		const fromTrack = track?.getSettings().channelCount;
		if (
			typeof fromTrack === "number" &&
			Number.isFinite(fromTrack) &&
			fromTrack > 0
		) {
			return fromTrack;
		}
		return Math.max(1, source.channelCount);
	}

	private setupPipelines(
		streams: OutputStream[],
		inputSampleRate: number,
		chunkMs: number,
	): void {
		this.pipelines.clear();
		for (const stream of streams) {
			const sampleRate = this.getSampleRateForStream(stream);
			this.pipelines.set(stream, {
				stream,
				sampleRate,
				chunkFrames: (sampleRate * chunkMs) / 1_000,
				seq: 0,
				emittedFrames: 0,
				pendingSamples: [],
				resampler: new LinearResampler(inputSampleRate, sampleRate),
			});
		}
	}

	private handleInputBuffer(buffer: AudioBuffer): void {
		if (
			this.state !== "running" ||
			!this.activeCaptureId ||
			!this.activeConfig
		) {
			return;
		}

		const monoSamples = this.downmixToMono(buffer);
		if (monoSamples.length === 0) {
			return;
		}

		if (!this.micEnabled) {
			monoSamples.fill(0);
		}

		this.totalFramesIn += monoSamples.length;

		if (this.activeConfig.emitAudioLevel) {
			this.accumulateAudioLevel(monoSamples);
		}

		for (const pipeline of this.pipelines.values()) {
			const resampled = pipeline.resampler.process(monoSamples);
			if (resampled.length > 0) {
				const pcm16 = this.floatToPcm16(resampled);
				for (let index = 0; index < pcm16.length; index += 1) {
					pipeline.pendingSamples.push(pcm16[index]);
				}
			}
			this.emitAvailableChunks(pipeline, false);
		}
	}

	private downmixToMono(buffer: AudioBuffer): Float32Array {
		const frameLength = buffer.length;
		if (frameLength === 0) {
			return new Float32Array(0);
		}

		const channelCount = buffer.numberOfChannels;
		if (channelCount <= 0) {
			return new Float32Array(0);
		}

		if (channelCount === 1) {
			return new Float32Array(buffer.getChannelData(0));
		}

		const mono = new Float32Array(frameLength);
		const scale = 1 / channelCount;

		for (let channelIndex = 0; channelIndex < channelCount; channelIndex += 1) {
			const channel = buffer.getChannelData(channelIndex);
			for (let frame = 0; frame < frameLength; frame += 1) {
				mono[frame] += channel[frame] * scale;
			}
		}

		return mono;
	}

	private floatToPcm16(samples: Float32Array): Int16Array {
		const pcm16 = new Int16Array(samples.length);
		for (let index = 0; index < samples.length; index += 1) {
			const sample = Math.max(-1, Math.min(1, samples[index]));
			pcm16[index] =
				sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767);
		}
		return pcm16;
	}

	private accumulateAudioLevel(samples: Float32Array): void {
		for (let index = 0; index < samples.length; index += 1) {
			const sample = samples[index];
			const absoluteValue = Math.abs(sample);
			if (absoluteValue > this.levelPeak) {
				this.levelPeak = absoluteValue;
			}
			this.levelSumSquares += sample * sample;
		}

		this.levelFrames += samples.length;
		if (this.levelFrames >= this.levelIntervalFrames) {
			this.emitAudioLevel();
		}
	}

	private emitAudioLevel(): void {
		if (!this.activeCaptureId || this.levelFrames <= 0) {
			return;
		}

		const rms = Math.sqrt(this.levelSumSquares / this.levelFrames);
		const peak = this.levelPeak;
		const dbfs =
			rms > 0 ? Math.min(0, Math.max(-90, 20 * Math.log10(rms))) : -90;

		this.notifyListeners("micAudioLevel", {
			captureId: this.activeCaptureId,
			rms,
			peak,
			dbfs,
			vad: dbfs > -45,
			ptsMs: this.monotonicMs(),
		});

		this.levelSumSquares = 0;
		this.levelPeak = 0;
		this.levelFrames = 0;
	}

	private emitAvailableChunks(pipeline: StreamPipeline, final: boolean): void {
		while (pipeline.pendingSamples.length >= pipeline.chunkFrames) {
			const chunk = pipeline.pendingSamples.splice(0, pipeline.chunkFrames);
			this.emitChunk(pipeline, chunk, final);
		}
	}

	private emitChunk(
		pipeline: StreamPipeline,
		samples: number[],
		final: boolean,
	): void {
		if (!this.activeCaptureId) {
			return;
		}

		const seq = pipeline.seq;
		const ptsOffsetMs = Math.floor(
			(pipeline.emittedFrames * 1_000) / pipeline.sampleRate,
		);
		const ptsMs = this.captureStartPtsMs + ptsOffsetMs;
		pipeline.seq += 1;
		pipeline.emittedFrames += samples.length;

		if (pipeline.stream === "pcm16k_s16le") {
			this.totalFramesOut16k += samples.length;
		} else {
			this.totalFramesOut48k += samples.length;
		}

		const payload: Record<string, unknown> = {
			captureId: this.activeCaptureId,
			stream: pipeline.stream,
			sampleRate: pipeline.sampleRate,
			channels: 1,
			frames: samples.length,
			seq,
			ptsMs,
			dataBase64: this.encodePcm16(samples),
		};

		if (final) {
			payload.final = true;
		}

		this.notifyListeners("micPcmChunk", payload);
	}

	private flushPipelinesAtStop(): void {
		for (const pipeline of this.pipelines.values()) {
			const remaining = pipeline.resampler.flush();
			if (remaining.length > 0) {
				const pcm16 = this.floatToPcm16(remaining);
				for (let index = 0; index < pcm16.length; index += 1) {
					pipeline.pendingSamples.push(pcm16[index]);
				}
			}

			this.emitAvailableChunks(pipeline, false);

			const finalChunk = this.popFinalChunk(pipeline);
			if (finalChunk) {
				this.emitChunk(pipeline, finalChunk, true);
			}
		}
	}

	private popFinalChunk(pipeline: StreamPipeline): number[] | null {
		if (pipeline.pendingSamples.length === 0) {
			return null;
		}

		const finalChunk = pipeline.pendingSamples.splice(
			0,
			pipeline.pendingSamples.length,
		);
		while (finalChunk.length < pipeline.chunkFrames) {
			finalChunk.push(0);
		}

		return finalChunk;
	}

	private encodePcm16(samples: number[]): string {
		const bytes = new Uint8Array(samples.length * 2);
		const view = new DataView(bytes.buffer);

		for (let index = 0; index < samples.length; index += 1) {
			view.setInt16(index * 2, samples[index], true);
		}

		if (typeof btoa === "function") {
			let binary = "";
			for (let index = 0; index < bytes.length; index += 1) {
				binary += String.fromCharCode(bytes[index]);
			}
			return btoa(binary);
		}

		const nodeGlobal = globalThis as typeof globalThis & {
			Buffer?: {
				from: (array: Uint8Array) => { toString: (encoding: string) => string };
			};
		};
		if (nodeGlobal.Buffer) {
			return nodeGlobal.Buffer.from(bytes).toString("base64");
		}

		this.reject(
			"E_INTERNAL",
			"Unable to encode PCM output.",
			false,
			this.activeCaptureId,
		);
	}

	private emitStateChanged(reason: string): void {
		const payload: Record<string, unknown> = {
			state: this.state,
			reason,
		};

		if (this.activeCaptureId) {
			payload.captureId = this.activeCaptureId;
		}

		this.notifyListeners("micStateChanged", payload);
	}

	private emitRouteChanged(reason: string): void {
		this.lastRouteChangeReason = reason;

		const payload: Record<string, unknown> = {
			reason,
		};

		if (this.activeCaptureId) {
			payload.captureId = this.activeCaptureId;
		}

		const selectedInputId = this.getSelectedInputId();
		if (selectedInputId) {
			payload.selectedInputId = selectedInputId;
		}

		this.notifyListeners("micRouteChanged", payload);
	}

	private emitError(
		code: NativeMicErrorCode,
		message: string,
		recoverable: boolean,
		captureId: string | undefined,
		nativeCode?: string,
	): void {
		const payload: Record<string, unknown> = {
			code,
			message,
			recoverable,
		};

		if (captureId) {
			payload.captureId = captureId;
		}
		if (nativeCode !== undefined) {
			payload.nativeCode = nativeCode;
		}

		this.notifyListeners("micError", payload);
	}

	private reject(
		code: NativeMicErrorCode,
		message: string,
		recoverable: boolean,
		captureId: string | undefined,
		nativeCode?: string,
	): never {
		const payload: Record<string, unknown> = {
			code,
			message,
			recoverable,
		};

		if (captureId) {
			payload.captureId = captureId;
		}
		if (nativeCode !== undefined) {
			payload.nativeCode = nativeCode;
		}

		this.notifyListeners("micError", payload);

		const error = new Error(message) as NativeMicPluginError;
		error.code = code;
		error.data = payload;
		throw error;
	}

	private isPluginError(error: unknown): error is NativeMicPluginError {
		return (
			typeof error === "object" &&
			error !== null &&
			"code" in error &&
			"data" in error &&
			"message" in error
		);
	}

	private resolveWebRTCConnectionId(connectionId: unknown): string | undefined {
		if (typeof connectionId !== "string") {
			return undefined;
		}
		const trimmed = connectionId.trim();
		return trimmed.length > 0 ? trimmed : undefined;
	}

	private rejectWebRTC(
		code: NativeWebRTCErrorCode,
		message: string,
		recoverable: boolean,
		connectionId?: string,
		nativeCode?: string,
	): never {
		const payload: Record<string, unknown> = {
			code,
			message,
			recoverable,
		};

		if (connectionId) {
			payload.connectionId = connectionId;
		}
		if (nativeCode !== undefined) {
			payload.nativeCode = nativeCode;
		}

		this.notifyListeners("webrtcError", payload);

		const error = new Error(message) as NativeWebRTCPluginError;
		error.code = code;
		error.data = payload;
		throw error;
	}

	private nativeCodeFromError(error: unknown): string | undefined {
		if (!error || typeof error !== "object") {
			return undefined;
		}

		const maybeError = error as { code?: unknown; name?: unknown };
		if (
			typeof maybeError.code === "string" ||
			typeof maybeError.code === "number"
		) {
			return String(maybeError.code);
		}
		if (typeof maybeError.name === "string") {
			return maybeError.name;
		}

		return undefined;
	}

	private isPermissionDeniedError(error: unknown): boolean {
		if (!error || typeof error !== "object") {
			return false;
		}

		const name = (error as { name?: unknown }).name;
		return name === "NotAllowedError" || name === "SecurityError";
	}

	private isInputNotFoundError(error: unknown): boolean {
		if (!error || typeof error !== "object") {
			return false;
		}

		const name = (error as { name?: unknown }).name;
		return name === "NotFoundError" || name === "OverconstrainedError";
	}

	private handleTrackEnded = (): void => {
		if (!this.activeCaptureId || this.state === "idle") {
			return;
		}

		this.state = "paused";
		this.emitStateChanged("interruption_began");

		this.notifyListeners("micInterruption", {
			captureId: this.activeCaptureId,
			phase: "began",
			reason: "system_interruption",
		});

		this.emitError(
			"E_INTERRUPTED",
			"Audio session interruption began.",
			true,
			this.activeCaptureId,
			undefined,
		);
	};

	private attachTrackEndedListener(stream: MediaStream): void {
		const track = stream.getAudioTracks()[0];
		if (track) {
			track.addEventListener("ended", this.handleTrackEnded);
		}
	}

	private detachTrackEndedListener(stream: MediaStream | null): void {
		const track = stream?.getAudioTracks()[0];
		if (track) {
			track.removeEventListener("ended", this.handleTrackEnded);
		}
	}

	private handleAudioContextStateChange(): void {
		if (!this.audioContext || !this.activeCaptureId) {
			return;
		}

		if (this.audioContext.state === "suspended" && this.state === "running") {
			this.state = "paused";
			this.emitStateChanged("interruption_began");
			this.notifyListeners("micInterruption", {
				captureId: this.activeCaptureId,
				phase: "began",
				reason: "system_interruption",
			});
			this.emitError(
				"E_INTERRUPTED",
				"Audio session interruption began.",
				true,
				this.activeCaptureId,
				undefined,
			);
			return;
		}

		if (this.audioContext.state === "running" && this.state === "paused") {
			this.notifyListeners("micInterruption", {
				captureId: this.activeCaptureId,
				phase: "ended",
				shouldResume: true,
			});
			this.state = "running";
			this.emitStateChanged("interruption_resumed");
		}
	}

	private async applyPreferredInputForActiveCapture(): Promise<void> {
		if (this.state === "idle" || !this.activeConfig) {
			return;
		}

		const preferredInputId = this.preferredInputId;

		if (preferredInputId) {
			const inputs = await this.enumerateAudioInputs();
			if (
				inputs.length > 0 &&
				!inputs.some((input) => input.deviceId === preferredInputId)
			) {
				this.reject(
					"E_ROUTE_CHANGE_FAILED",
					`Input device ${preferredInputId} was not found.`,
					false,
					this.activeCaptureId,
				);
			}
		}

		if (!this.audioContext || !this.processorNode) {
			this.reject(
				"E_ROUTE_CHANGE_FAILED",
				preferredInputId
					? `Failed to apply preferred input ${preferredInputId}.`
					: "Failed to clear preferred input.",
				true,
				this.activeCaptureId,
			);
		}

		let nextStream: MediaStream | null = null;
		let nextSource: MediaStreamAudioSourceNode | null = null;

		try {
			nextStream = await this.acquireMediaStream({
				...this.activeConfig,
				preferredInputId,
			});
			nextSource = this.audioContext.createMediaStreamSource(nextStream);

			if (this.mediaStreamSource) {
				this.mediaStreamSource.disconnect();
			}
			nextSource.connect(this.processorNode);

			this.detachTrackEndedListener(this.mediaStream);
			this.stopStreamTracks(this.mediaStream);

			this.mediaStream = nextStream;
			this.mediaStreamSource = nextSource;
			this.actualInputChannels = this.resolveInputChannelCount(
				nextSource,
				nextStream,
			);

			this.attachTrackEndedListener(nextStream);
			this.emitRouteChanged("set_preferred_input");
		} catch (error) {
			if (nextSource) {
				nextSource.disconnect();
			}
			if (nextStream) {
				this.stopStreamTracks(nextStream);
			}

			this.reject(
				"E_ROUTE_CHANGE_FAILED",
				preferredInputId
					? `Failed to apply preferred input ${preferredInputId}.`
					: "Failed to clear preferred input.",
				true,
				this.activeCaptureId,
				this.nativeCodeFromError(error),
			);
		}
	}

	private async teardownAudioGraph(): Promise<unknown> {
		const context = this.audioContext;
		this.audioContext = null;

		if (context) {
			context.onstatechange = null;
		}

		if (this.mediaStreamSource) {
			this.mediaStreamSource.disconnect();
			this.mediaStreamSource = null;
		}

		if (this.processorNode) {
			this.processorNode.onaudioprocess = null;
			this.processorNode.disconnect();
			this.processorNode = null;
		}

		if (this.sinkNode) {
			this.sinkNode.disconnect();
			this.sinkNode = null;
		}

		this.detachTrackEndedListener(this.mediaStream);

		if (this.mediaStream) {
			this.stopStreamTracks(this.mediaStream);
			this.mediaStream = null;
		}

		if (!context || context.state === "closed") {
			return null;
		}

		try {
			await context.close();
			return null;
		} catch (error) {
			return error;
		}
	}

	private clearCaptureState(): void {
		this.activeConfig = undefined;
		this.activeCaptureId = undefined;
		this.pipelines.clear();
		this.captureStartPtsMs = 0;
		this.totalFramesIn = 0;
		this.totalFramesOut16k = 0;
		this.totalFramesOut48k = 0;
		this.actualInputSampleRate = 0;
		this.actualInputChannels = 0;
		this.droppedInputFrames = 0;
		this.levelSumSquares = 0;
		this.levelPeak = 0;
		this.levelFrames = 0;
		this.levelIntervalFrames = 0;
		this.micEnabled = true;
	}

	private stopStreamTracks(stream: MediaStream | null): void {
		stream?.getTracks().forEach((track) => track.stop());
	}

	private getSampleRateForStream(stream: OutputStream): 16000 | 48000 {
		return stream === "pcm16k_s16le" ? 16000 : 48000;
	}

	private createCaptureId(): string {
		if (
			typeof crypto !== "undefined" &&
			typeof crypto.randomUUID === "function"
		) {
			return crypto.randomUUID();
		}
		return `capture-${Date.now()}-${Math.floor(Math.random() * 1_000_000_000)}`;
	}

	private getSelectedInputId(): string | undefined {
		const track = this.mediaStream?.getAudioTracks()[0];
		const fromTrack = track?.getSettings().deviceId;
		if (typeof fromTrack === "string" && fromTrack.length > 0) {
			return fromTrack;
		}

		return this.preferredInputId;
	}

	private mapInputType(label: string): MicDevice["type"] {
		const value = label.toLowerCase();

		if (value.includes("bluetooth") || value.includes("airpods")) {
			return "bluetooth";
		}

		if (value.includes("usb")) {
			return "usb";
		}

		if (
			value.includes("headset") ||
			value.includes("headphone") ||
			value.includes("earbud") ||
			value.includes("wired")
		) {
			return "wired";
		}

		if (
			value.includes("built") ||
			value.includes("internal") ||
			value.includes("default") ||
			value.includes("microphone")
		) {
			return "built_in";
		}

		return "unknown";
	}

	private async enumerateAudioInputs(): Promise<MediaDeviceInfo[]> {
		if (!this.hasMediaDeviceApi() || !navigator.mediaDevices.enumerateDevices) {
			return [];
		}

		const devices = await navigator.mediaDevices.enumerateDevices();
		return devices.filter((device) => device.kind === "audioinput");
	}

	private isMicProfile(value: unknown): value is MicProfile {
		return value === "waveform" || value === "pipecat";
	}

	private isSessionMode(value: unknown): value is SessionMode {
		return value === "measurement" || value === "voice_chat";
	}

	private isOutputStream(value: unknown): value is OutputStream {
		return value === "pcm16k_s16le" || value === "pcm48k_s16le";
	}

	private isOutputRoute(value: unknown): value is OutputRoute {
		return value === "system" || value === "speaker" || value === "receiver";
	}

	private monotonicMs(): number {
		if (
			typeof performance !== "undefined" &&
			typeof performance.now === "function"
		) {
			return Math.floor(performance.now());
		}
		return Date.now();
	}

	private readonly handleDeviceChange = (): void => {
		this.emitRouteChanged("device_change");
	};
}

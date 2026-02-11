import type { PluginListenerHandle } from '@capacitor/core';

export type MicPermissionState = 'prompt' | 'granted' | 'denied';
export type MicProfile = 'waveform' | 'pipecat';
export type SessionMode = 'measurement' | 'voice_chat';
export type OutputStream = 'pcm16k_s16le' | 'pcm48k_s16le';
export type OutputRoute = 'system' | 'speaker' | 'receiver';
export type NativeWebRTCState =
  | 'idle'
  | 'initializing'
  | 'connecting'
  | 'connected'
  | 'ready'
  | 'reconnecting'
  | 'disconnecting'
  | 'error';
export type NativeWebRTCErrorCode =
  | 'E_WEBRTC_UNAVAILABLE'
  | 'E_PC_CREATE_FAILED'
  | 'E_NEGOTIATION_FAILED'
  | 'E_ICE_FAILED'
  | 'E_DATA_CHANNEL_FAILED'
  | 'E_ALREADY_RUNNING'
  | 'E_NOT_RUNNING'
  | 'E_INVALID_ARGUMENT'
  | 'E_INTERNAL';

export type RTCIceServerLike = {
  urls: string | string[];
  username?: string;
  credential?: string;
};

export interface StartCaptureOptions {
  profile: MicProfile;
  mode: SessionMode;
  outputStreams: OutputStream[];
  chunkMs?: number;
  emitAudioLevel?: boolean;
  audioLevelIntervalMs?: number;
  voiceProcessing?: boolean;
  preferredInputId?: string;
  outputRoute?: OutputRoute;
}

export interface StartCaptureResult {
  captureId: string;
  actualInputSampleRate: number;
  actualInputChannels: number;
  chunkMs: number;
}

export interface StopCaptureOptions {
  captureId: string;
  flushTimeoutMs?: number;
}

export interface StopCaptureResult {
  captureId: string;
  totalFramesIn: number;
  totalFramesOut16k: number;
  totalFramesOut48k: number;
  durationMs: number;
}

export interface MicDevice {
  id: string;
  label: string;
  type: 'built_in' | 'wired' | 'bluetooth' | 'usb' | 'unknown';
  isDefault: boolean;
}

export interface MicStateChangedEvent {
  captureId?: string;
  state: 'idle' | 'running' | 'paused';
  reason?: string;
}

export interface MicPcmChunkEvent {
  captureId: string;
  stream: OutputStream;
  sampleRate: 16000 | 48000;
  channels: 1;
  frames: number;
  seq: number;
  ptsMs: number;
  dataBase64: string;
  final?: boolean;
}

export interface MicAudioLevelEvent {
  captureId: string;
  rms: number;
  peak: number;
  dbfs: number;
  vad?: boolean;
  ptsMs: number;
}

export interface MicRouteChangedEvent {
  captureId?: string;
  reason?: string;
  selectedInputId?: string;
}

export interface MicInterruptionEvent {
  captureId: string;
  phase: 'began' | 'ended';
  shouldResume?: boolean;
  reason?: string;
}

export interface MicErrorEvent {
  captureId?: string;
  code: string;
  message: string;
  recoverable: boolean;
  nativeCode?: string;
}

export interface WebRTCRequestInfo {
  endpoint: string;
  headers?: Record<string, string>;
  requestData?: Record<string, unknown>;
  timeoutMs?: number;
}

export interface NativeWebRTCConnectOptions {
  connectionId?: string;
  webrtcRequest: WebRTCRequestInfo;
  iceConfig?: { iceServers?: RTCIceServerLike[] };
  waitForICEGathering?: boolean;
  audioCodec?: string | 'default' | null;
  videoCodec?: string | 'default' | null;
  media?: {
    voiceProcessing?: boolean;
    startMicEnabled?: boolean;
    preferredInputId?: string;
    outputRoute?: OutputRoute;
  };
  reconnect?: {
    enabled?: boolean;
    maxAttempts?: number;
    backoffMs?: number;
  };
}

export interface NativeWebRTCConnectResult {
  connectionId: string;
  pcId?: string;
  selectedInputId?: string;
  selectedOutputRoute: OutputRoute;
  state: NativeWebRTCState;
}

export interface NativeWebRTCStateResult {
  connectionId: string;
  state: NativeWebRTCState;
  pcId?: string;
  iceConnectionState?: string;
  signalingState?: string;
}

export interface NativeWebRTCStateChangedEvent {
  connectionId: string;
  state: NativeWebRTCState;
  reason?: string;
  pcId?: string;
}

export interface NativeWebRTCDataMessageEvent {
  connectionId: string;
  data: string;
}

export interface NativeWebRTCTrackEvent {
  connectionId: string;
  kind: 'audio' | 'video' | 'screenVideo';
  source: 'remote' | 'local';
}

export interface NativeWebRTCAudioLevelEvent {
  connectionId: string;
  level: number;
}

export interface NativeWebRTCErrorEvent {
  connectionId?: string;
  code: NativeWebRTCErrorCode;
  message: string;
  recoverable: boolean;
  nativeCode?: string;
}

export interface NativeMicPlugin {
  isAvailable(): Promise<{ available: boolean; reason?: string }>;
  checkPermissions(): Promise<{ microphone: MicPermissionState }>;
  requestPermissions(): Promise<{ microphone: MicPermissionState }>;

  getDevices(): Promise<{ inputs: MicDevice[]; selectedInputId?: string }>;
  setPreferredInput(options: { inputId: string | null }): Promise<void>;
  setOutputRoute(options: { route: OutputRoute }): Promise<void>;

  startCapture(options: StartCaptureOptions): Promise<StartCaptureResult>;
  stopCapture(options: StopCaptureOptions): Promise<StopCaptureResult>;
  setMicEnabled(options: { captureId: string; enabled: boolean }): Promise<void>;
  getState(): Promise<{ state: 'idle' | 'running' | 'paused' }>;
  getDiagnostics(): Promise<Record<string, unknown>>;

  webrtcIsAvailable(): Promise<{ available: boolean; reason?: string }>;
  webrtcConnect(options: NativeWebRTCConnectOptions): Promise<NativeWebRTCConnectResult>;
  webrtcDisconnect(options: { connectionId: string; reason?: string }): Promise<void>;
  webrtcSendDataMessage(options: { connectionId: string; data: string }): Promise<void>;
  webrtcSetMicEnabled(options: { connectionId: string; enabled: boolean }): Promise<void>;
  webrtcSetPreferredInput(options: { connectionId: string; inputId: string | null }): Promise<void>;
  webrtcSetOutputRoute(options: { connectionId: string; route: OutputRoute }): Promise<void>;
  webrtcGetState(options: { connectionId: string }): Promise<NativeWebRTCStateResult>;
  webrtcGetDiagnostics(options: { connectionId: string }): Promise<Record<string, unknown>>;

  addListener(
    eventName: 'micStateChanged',
    listenerFunc: (event: MicStateChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'micPcmChunk',
    listenerFunc: (event: MicPcmChunkEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'micAudioLevel',
    listenerFunc: (event: MicAudioLevelEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'micRouteChanged',
    listenerFunc: (event: MicRouteChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'micInterruption',
    listenerFunc: (event: MicInterruptionEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'micError',
    listenerFunc: (event: MicErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcStateChanged',
    listenerFunc: (event: NativeWebRTCStateChangedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcDataMessage',
    listenerFunc: (event: NativeWebRTCDataMessageEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcTrackStarted',
    listenerFunc: (event: NativeWebRTCTrackEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcTrackStopped',
    listenerFunc: (event: NativeWebRTCTrackEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcLocalAudioLevel',
    listenerFunc: (event: NativeWebRTCAudioLevelEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcRemoteAudioLevel',
    listenerFunc: (event: NativeWebRTCAudioLevelEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'webrtcError',
    listenerFunc: (event: NativeWebRTCErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

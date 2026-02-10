import type { PluginListenerHandle } from '@capacitor/core';

export type MicPermissionState = 'prompt' | 'granted' | 'denied';
export type MicProfile = 'waveform' | 'pipecat';
export type SessionMode = 'measurement' | 'voice_chat';
export type OutputStream = 'pcm16k_s16le' | 'pcm48k_s16le';
export type OutputRoute = 'system' | 'speaker' | 'receiver';

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
  removeAllListeners(): Promise<void>;
}

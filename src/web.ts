import { WebPlugin } from '@capacitor/core';

import type {
  MicDevice,
  MicPermissionState,
  NativeMicPlugin,
  OutputRoute,
  StartCaptureOptions,
  StartCaptureResult,
  StopCaptureOptions,
  StopCaptureResult,
} from './definitions';

export class NativeMicWeb extends WebPlugin implements NativeMicPlugin {
  async isAvailable(): Promise<{ available: boolean; reason?: string }> {
    return {
      available: false,
      reason: 'NativeMic plugin capture pipeline is only available on iOS.',
    };
  }

  async checkPermissions(): Promise<{ microphone: MicPermissionState }> {
    try {
      const result = await navigator.permissions.query({
        name: 'microphone' as PermissionName,
      });
      return { microphone: this.mapPermissionState(result.state) };
    } catch {
      return { microphone: 'prompt' };
    }
  }

  async requestPermissions(): Promise<{ microphone: MicPermissionState }> {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach((track) => track.stop());
      return { microphone: 'granted' };
    } catch {
      return { microphone: 'denied' };
    }
  }

  async getDevices(): Promise<{ inputs: MicDevice[]; selectedInputId?: string }> {
    if (!navigator.mediaDevices?.enumerateDevices) {
      return { inputs: [] };
    }

    const devices = await navigator.mediaDevices.enumerateDevices();
    const inputs = devices
      .filter((device) => device.kind === 'audioinput')
      .map((device, index): MicDevice => {
        return {
          id: device.deviceId,
          label: device.label || `Microphone ${index + 1}`,
          type: 'unknown',
          isDefault: index === 0,
        };
      });

    return {
      inputs,
      selectedInputId: inputs.find((input) => input.isDefault)?.id,
    };
  }

  async setPreferredInput(_options: { inputId: string | null }): Promise<void> {
    void _options;
    throw this.unimplemented('Use getUserMedia audio constraints on web/Android.');
  }

  async setOutputRoute(_options: { route: OutputRoute }): Promise<void> {
    void _options;
    throw this.unimplemented('Output routing is handled by the browser on web/Android.');
  }

  async startCapture(_options: StartCaptureOptions): Promise<StartCaptureResult> {
    void _options;
    throw this.unimplemented('Use navigator.mediaDevices.getUserMedia on web/Android.');
  }

  async stopCapture(_options: StopCaptureOptions): Promise<StopCaptureResult> {
    void _options;
    throw this.unimplemented('Use navigator.mediaDevices.getUserMedia on web/Android.');
  }

  async setMicEnabled(_options: { captureId: string; enabled: boolean }): Promise<void> {
    void _options;
    throw this.unimplemented('Use MediaStreamTrack.enabled on web/Android.');
  }

  async getState(): Promise<{ state: 'idle' | 'running' | 'paused' }> {
    return { state: 'idle' };
  }

  async getDiagnostics(): Promise<Record<string, unknown>> {
    return {
      available: false,
      platform: 'web',
    };
  }

  private mapPermissionState(state: PermissionState): MicPermissionState {
    switch (state) {
      case 'granted':
        return 'granted';
      case 'denied':
        return 'denied';
      default:
        return 'prompt';
    }
  }
}

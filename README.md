# native-mic-capacitor

Get native stream of your microphone

## Install

```bash
npm install native-mic-capacitor
npx cap sync
```

## API

<docgen-index>

* [`isAvailable()`](#isavailable)
* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions()`](#requestpermissions)
* [`getDevices()`](#getdevices)
* [`setPreferredInput(...)`](#setpreferredinput)
* [`setOutputRoute(...)`](#setoutputroute)
* [`startCapture(...)`](#startcapture)
* [`stopCapture(...)`](#stopcapture)
* [`setMicEnabled(...)`](#setmicenabled)
* [`getState()`](#getstate)
* [`getDiagnostics()`](#getdiagnostics)
* [`webrtcIsAvailable()`](#webrtcisavailable)
* [`webrtcConnect(...)`](#webrtcconnect)
* [`webrtcDisconnect(...)`](#webrtcdisconnect)
* [`webrtcSendDataMessage(...)`](#webrtcsenddatamessage)
* [`webrtcSetMicEnabled(...)`](#webrtcsetmicenabled)
* [`webrtcSetPreferredInput(...)`](#webrtcsetpreferredinput)
* [`webrtcSetOutputRoute(...)`](#webrtcsetoutputroute)
* [`webrtcGetState(...)`](#webrtcgetstate)
* [`webrtcGetDiagnostics(...)`](#webrtcgetdiagnostics)
* [`addListener('micStateChanged', ...)`](#addlistenermicstatechanged-)
* [`addListener('micPcmChunk', ...)`](#addlistenermicpcmchunk-)
* [`addListener('micAudioLevel', ...)`](#addlistenermicaudiolevel-)
* [`addListener('micRouteChanged', ...)`](#addlistenermicroutechanged-)
* [`addListener('micInterruption', ...)`](#addlistenermicinterruption-)
* [`addListener('micError', ...)`](#addlistenermicerror-)
* [`addListener('webrtcStateChanged', ...)`](#addlistenerwebrtcstatechanged-)
* [`addListener('webrtcDataMessage', ...)`](#addlistenerwebrtcdatamessage-)
* [`addListener('webrtcTrackStarted', ...)`](#addlistenerwebrtctrackstarted-)
* [`addListener('webrtcTrackStopped', ...)`](#addlistenerwebrtctrackstopped-)
* [`addListener('webrtcLocalAudioLevel', ...)`](#addlistenerwebrtclocalaudiolevel-)
* [`addListener('webrtcRemoteAudioLevel', ...)`](#addlistenerwebrtcremoteaudiolevel-)
* [`addListener('webrtcError', ...)`](#addlistenerwebrtcerror-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### isAvailable()

```typescript
isAvailable() => Promise<{ available: boolean; reason?: string; }>
```

**Returns:** <code>Promise&lt;{ available: boolean; reason?: string; }&gt;</code>

--------------------


### checkPermissions()

```typescript
checkPermissions() => Promise<{ microphone: MicPermissionState; }>
```

**Returns:** <code>Promise&lt;{ microphone: <a href="#micpermissionstate">MicPermissionState</a>; }&gt;</code>

--------------------


### requestPermissions()

```typescript
requestPermissions() => Promise<{ microphone: MicPermissionState; }>
```

**Returns:** <code>Promise&lt;{ microphone: <a href="#micpermissionstate">MicPermissionState</a>; }&gt;</code>

--------------------


### getDevices()

```typescript
getDevices() => Promise<{ inputs: MicDevice[]; selectedInputId?: string; }>
```

**Returns:** <code>Promise&lt;{ inputs: MicDevice[]; selectedInputId?: string; }&gt;</code>

--------------------


### setPreferredInput(...)

```typescript
setPreferredInput(options: { inputId: string | null; }) => Promise<void>
```

| Param         | Type                                      |
| ------------- | ----------------------------------------- |
| **`options`** | <code>{ inputId: string \| null; }</code> |

--------------------


### setOutputRoute(...)

```typescript
setOutputRoute(options: { route: OutputRoute; }) => Promise<void>
```

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code>{ route: <a href="#outputroute">OutputRoute</a>; }</code> |

--------------------


### startCapture(...)

```typescript
startCapture(options: StartCaptureOptions) => Promise<StartCaptureResult>
```

| Param         | Type                                                                |
| ------------- | ------------------------------------------------------------------- |
| **`options`** | <code><a href="#startcaptureoptions">StartCaptureOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#startcaptureresult">StartCaptureResult</a>&gt;</code>

--------------------


### stopCapture(...)

```typescript
stopCapture(options: StopCaptureOptions) => Promise<StopCaptureResult>
```

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#stopcaptureoptions">StopCaptureOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#stopcaptureresult">StopCaptureResult</a>&gt;</code>

--------------------


### setMicEnabled(...)

```typescript
setMicEnabled(options: { captureId: string; enabled: boolean; }) => Promise<void>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code>{ captureId: string; enabled: boolean; }</code> |

--------------------


### getState()

```typescript
getState() => Promise<{ state: 'idle' | 'running' | 'paused'; }>
```

**Returns:** <code>Promise&lt;{ state: 'idle' | 'running' | 'paused'; }&gt;</code>

--------------------


### getDiagnostics()

```typescript
getDiagnostics() => Promise<Record<string, unknown>>
```

**Returns:** <code>Promise&lt;<a href="#record">Record</a>&lt;string, unknown&gt;&gt;</code>

--------------------


### webrtcIsAvailable()

```typescript
webrtcIsAvailable() => Promise<{ available: boolean; reason?: string; }>
```

**Returns:** <code>Promise&lt;{ available: boolean; reason?: string; }&gt;</code>

--------------------


### webrtcConnect(...)

```typescript
webrtcConnect(options: NativeWebRTCConnectOptions) => Promise<NativeWebRTCConnectResult>
```

| Param         | Type                                                                              |
| ------------- | --------------------------------------------------------------------------------- |
| **`options`** | <code><a href="#nativewebrtcconnectoptions">NativeWebRTCConnectOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#nativewebrtcconnectresult">NativeWebRTCConnectResult</a>&gt;</code>

--------------------


### webrtcDisconnect(...)

```typescript
webrtcDisconnect(options: { connectionId: string; reason?: string; }) => Promise<void>
```

| Param         | Type                                                    |
| ------------- | ------------------------------------------------------- |
| **`options`** | <code>{ connectionId: string; reason?: string; }</code> |

--------------------


### webrtcSendDataMessage(...)

```typescript
webrtcSendDataMessage(options: { connectionId: string; data: string; }) => Promise<void>
```

| Param         | Type                                                 |
| ------------- | ---------------------------------------------------- |
| **`options`** | <code>{ connectionId: string; data: string; }</code> |

--------------------


### webrtcSetMicEnabled(...)

```typescript
webrtcSetMicEnabled(options: { connectionId: string; enabled: boolean; }) => Promise<void>
```

| Param         | Type                                                     |
| ------------- | -------------------------------------------------------- |
| **`options`** | <code>{ connectionId: string; enabled: boolean; }</code> |

--------------------


### webrtcSetPreferredInput(...)

```typescript
webrtcSetPreferredInput(options: { connectionId: string; inputId: string | null; }) => Promise<void>
```

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code>{ connectionId: string; inputId: string \| null; }</code> |

--------------------


### webrtcSetOutputRoute(...)

```typescript
webrtcSetOutputRoute(options: { connectionId: string; route: OutputRoute; }) => Promise<void>
```

| Param         | Type                                                                                  |
| ------------- | ------------------------------------------------------------------------------------- |
| **`options`** | <code>{ connectionId: string; route: <a href="#outputroute">OutputRoute</a>; }</code> |

--------------------


### webrtcGetState(...)

```typescript
webrtcGetState(options: { connectionId: string; }) => Promise<NativeWebRTCStateResult>
```

| Param         | Type                                   |
| ------------- | -------------------------------------- |
| **`options`** | <code>{ connectionId: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#nativewebrtcstateresult">NativeWebRTCStateResult</a>&gt;</code>

--------------------


### webrtcGetDiagnostics(...)

```typescript
webrtcGetDiagnostics(options: { connectionId: string; }) => Promise<Record<string, unknown>>
```

| Param         | Type                                   |
| ------------- | -------------------------------------- |
| **`options`** | <code>{ connectionId: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#record">Record</a>&lt;string, unknown&gt;&gt;</code>

--------------------


### addListener('micStateChanged', ...)

```typescript
addListener(eventName: 'micStateChanged', listenerFunc: (event: MicStateChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                      |
| ------------------ | ----------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micStateChanged'</code>                                                            |
| **`listenerFunc`** | <code>(event: <a href="#micstatechangedevent">MicStateChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('micPcmChunk', ...)

```typescript
addListener(eventName: 'micPcmChunk', listenerFunc: (event: MicPcmChunkEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                              |
| ------------------ | --------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micPcmChunk'</code>                                                        |
| **`listenerFunc`** | <code>(event: <a href="#micpcmchunkevent">MicPcmChunkEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('micAudioLevel', ...)

```typescript
addListener(eventName: 'micAudioLevel', listenerFunc: (event: MicAudioLevelEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micAudioLevel'</code>                                                          |
| **`listenerFunc`** | <code>(event: <a href="#micaudiolevelevent">MicAudioLevelEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('micRouteChanged', ...)

```typescript
addListener(eventName: 'micRouteChanged', listenerFunc: (event: MicRouteChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                      |
| ------------------ | ----------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micRouteChanged'</code>                                                            |
| **`listenerFunc`** | <code>(event: <a href="#microutechangedevent">MicRouteChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('micInterruption', ...)

```typescript
addListener(eventName: 'micInterruption', listenerFunc: (event: MicInterruptionEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                      |
| ------------------ | ----------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micInterruption'</code>                                                            |
| **`listenerFunc`** | <code>(event: <a href="#micinterruptionevent">MicInterruptionEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('micError', ...)

```typescript
addListener(eventName: 'micError', listenerFunc: (event: MicErrorEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                        |
| ------------------ | --------------------------------------------------------------------------- |
| **`eventName`**    | <code>'micError'</code>                                                     |
| **`listenerFunc`** | <code>(event: <a href="#micerrorevent">MicErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcStateChanged', ...)

```typescript
addListener(eventName: 'webrtcStateChanged', listenerFunc: (event: NativeWebRTCStateChangedEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                        |
| ------------------ | ----------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcStateChanged'</code>                                                                           |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtcstatechangedevent">NativeWebRTCStateChangedEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcDataMessage', ...)

```typescript
addListener(eventName: 'webrtcDataMessage', listenerFunc: (event: NativeWebRTCDataMessageEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                      |
| ------------------ | --------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcDataMessage'</code>                                                                          |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtcdatamessageevent">NativeWebRTCDataMessageEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcTrackStarted', ...)

```typescript
addListener(eventName: 'webrtcTrackStarted', listenerFunc: (event: NativeWebRTCTrackEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                          |
| ------------------ | --------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcTrackStarted'</code>                                                             |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtctrackevent">NativeWebRTCTrackEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcTrackStopped', ...)

```typescript
addListener(eventName: 'webrtcTrackStopped', listenerFunc: (event: NativeWebRTCTrackEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                          |
| ------------------ | --------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcTrackStopped'</code>                                                             |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtctrackevent">NativeWebRTCTrackEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcLocalAudioLevel', ...)

```typescript
addListener(eventName: 'webrtcLocalAudioLevel', listenerFunc: (event: NativeWebRTCAudioLevelEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcLocalAudioLevel'</code>                                                                    |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtcaudiolevelevent">NativeWebRTCAudioLevelEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcRemoteAudioLevel', ...)

```typescript
addListener(eventName: 'webrtcRemoteAudioLevel', listenerFunc: (event: NativeWebRTCAudioLevelEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                    |
| ------------------ | ------------------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcRemoteAudioLevel'</code>                                                                   |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtcaudiolevelevent">NativeWebRTCAudioLevelEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('webrtcError', ...)

```typescript
addListener(eventName: 'webrtcError', listenerFunc: (event: NativeWebRTCErrorEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                          |
| ------------------ | --------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'webrtcError'</code>                                                                    |
| **`listenerFunc`** | <code>(event: <a href="#nativewebrtcerrorevent">NativeWebRTCErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

--------------------


### Interfaces


#### MicDevice

| Prop            | Type                                                                    |
| --------------- | ----------------------------------------------------------------------- |
| **`id`**        | <code>string</code>                                                     |
| **`label`**     | <code>string</code>                                                     |
| **`type`**      | <code>'built_in' \| 'wired' \| 'bluetooth' \| 'usb' \| 'unknown'</code> |
| **`isDefault`** | <code>boolean</code>                                                    |


#### StartCaptureResult

| Prop                        | Type                |
| --------------------------- | ------------------- |
| **`captureId`**             | <code>string</code> |
| **`actualInputSampleRate`** | <code>number</code> |
| **`actualInputChannels`**   | <code>number</code> |
| **`chunkMs`**               | <code>number</code> |


#### StartCaptureOptions

| Prop                       | Type                                                |
| -------------------------- | --------------------------------------------------- |
| **`profile`**              | <code><a href="#micprofile">MicProfile</a></code>   |
| **`mode`**                 | <code><a href="#sessionmode">SessionMode</a></code> |
| **`outputStreams`**        | <code>OutputStream[]</code>                         |
| **`chunkMs`**              | <code>number</code>                                 |
| **`emitAudioLevel`**       | <code>boolean</code>                                |
| **`audioLevelIntervalMs`** | <code>number</code>                                 |
| **`voiceProcessing`**      | <code>boolean</code>                                |
| **`preferredInputId`**     | <code>string</code>                                 |
| **`outputRoute`**          | <code><a href="#outputroute">OutputRoute</a></code> |


#### StopCaptureResult

| Prop                    | Type                |
| ----------------------- | ------------------- |
| **`captureId`**         | <code>string</code> |
| **`totalFramesIn`**     | <code>number</code> |
| **`totalFramesOut16k`** | <code>number</code> |
| **`totalFramesOut48k`** | <code>number</code> |
| **`durationMs`**        | <code>number</code> |


#### StopCaptureOptions

| Prop                 | Type                |
| -------------------- | ------------------- |
| **`captureId`**      | <code>string</code> |
| **`flushTimeoutMs`** | <code>number</code> |


#### NativeWebRTCConnectResult

| Prop                      | Type                                                            |
| ------------------------- | --------------------------------------------------------------- |
| **`connectionId`**        | <code>string</code>                                             |
| **`pcId`**                | <code>string</code>                                             |
| **`selectedInputId`**     | <code>string</code>                                             |
| **`selectedOutputRoute`** | <code><a href="#outputroute">OutputRoute</a></code>             |
| **`state`**               | <code><a href="#nativewebrtcstate">NativeWebRTCState</a></code> |


#### NativeWebRTCConnectOptions

| Prop                      | Type                                                                                                                                                    |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`connectionId`**        | <code>string</code>                                                                                                                                     |
| **`webrtcRequest`**       | <code><a href="#webrtcrequestinfo">WebRTCRequestInfo</a></code>                                                                                         |
| **`iceConfig`**           | <code>{ iceServers?: RTCIceServerLike[]; }</code>                                                                                                       |
| **`waitForICEGathering`** | <code>boolean</code>                                                                                                                                    |
| **`audioCodec`**          | <code>string \| null</code>                                                                                                                             |
| **`videoCodec`**          | <code>string \| null</code>                                                                                                                             |
| **`media`**               | <code>{ voiceProcessing?: boolean; startMicEnabled?: boolean; preferredInputId?: string; outputRoute?: <a href="#outputroute">OutputRoute</a>; }</code> |
| **`reconnect`**           | <code>{ enabled?: boolean; maxAttempts?: number; backoffMs?: number; }</code>                                                                           |


#### WebRTCRequestInfo

| Prop              | Type                                                             |
| ----------------- | ---------------------------------------------------------------- |
| **`endpoint`**    | <code>string</code>                                              |
| **`headers`**     | <code><a href="#record">Record</a>&lt;string, string&gt;</code>  |
| **`requestData`** | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> |
| **`timeoutMs`**   | <code>number</code>                                              |


#### NativeWebRTCStateResult

| Prop                     | Type                                                            |
| ------------------------ | --------------------------------------------------------------- |
| **`connectionId`**       | <code>string</code>                                             |
| **`state`**              | <code><a href="#nativewebrtcstate">NativeWebRTCState</a></code> |
| **`pcId`**               | <code>string</code>                                             |
| **`iceConnectionState`** | <code>string</code>                                             |
| **`signalingState`**     | <code>string</code>                                             |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### MicStateChangedEvent

| Prop            | Type                                         |
| --------------- | -------------------------------------------- |
| **`captureId`** | <code>string</code>                          |
| **`state`**     | <code>'idle' \| 'running' \| 'paused'</code> |
| **`reason`**    | <code>string</code>                          |


#### MicPcmChunkEvent

| Prop             | Type                                                  |
| ---------------- | ----------------------------------------------------- |
| **`captureId`**  | <code>string</code>                                   |
| **`stream`**     | <code><a href="#outputstream">OutputStream</a></code> |
| **`sampleRate`** | <code>16000 \| 48000</code>                           |
| **`channels`**   | <code>1</code>                                        |
| **`frames`**     | <code>number</code>                                   |
| **`seq`**        | <code>number</code>                                   |
| **`ptsMs`**      | <code>number</code>                                   |
| **`dataBase64`** | <code>string</code>                                   |
| **`final`**      | <code>boolean</code>                                  |


#### MicAudioLevelEvent

| Prop            | Type                 |
| --------------- | -------------------- |
| **`captureId`** | <code>string</code>  |
| **`rms`**       | <code>number</code>  |
| **`peak`**      | <code>number</code>  |
| **`dbfs`**      | <code>number</code>  |
| **`vad`**       | <code>boolean</code> |
| **`ptsMs`**     | <code>number</code>  |


#### MicRouteChangedEvent

| Prop                  | Type                |
| --------------------- | ------------------- |
| **`captureId`**       | <code>string</code> |
| **`reason`**          | <code>string</code> |
| **`selectedInputId`** | <code>string</code> |


#### MicInterruptionEvent

| Prop               | Type                            |
| ------------------ | ------------------------------- |
| **`captureId`**    | <code>string</code>             |
| **`phase`**        | <code>'began' \| 'ended'</code> |
| **`shouldResume`** | <code>boolean</code>            |
| **`reason`**       | <code>string</code>             |


#### MicErrorEvent

| Prop              | Type                 |
| ----------------- | -------------------- |
| **`captureId`**   | <code>string</code>  |
| **`code`**        | <code>string</code>  |
| **`message`**     | <code>string</code>  |
| **`recoverable`** | <code>boolean</code> |
| **`nativeCode`**  | <code>string</code>  |


#### NativeWebRTCStateChangedEvent

| Prop               | Type                                                            |
| ------------------ | --------------------------------------------------------------- |
| **`connectionId`** | <code>string</code>                                             |
| **`state`**        | <code><a href="#nativewebrtcstate">NativeWebRTCState</a></code> |
| **`reason`**       | <code>string</code>                                             |
| **`pcId`**         | <code>string</code>                                             |


#### NativeWebRTCDataMessageEvent

| Prop               | Type                |
| ------------------ | ------------------- |
| **`connectionId`** | <code>string</code> |
| **`data`**         | <code>string</code> |


#### NativeWebRTCTrackEvent

| Prop               | Type                                             |
| ------------------ | ------------------------------------------------ |
| **`connectionId`** | <code>string</code>                              |
| **`kind`**         | <code>'audio' \| 'video' \| 'screenVideo'</code> |
| **`source`**       | <code>'remote' \| 'local'</code>                 |


#### NativeWebRTCAudioLevelEvent

| Prop               | Type                |
| ------------------ | ------------------- |
| **`connectionId`** | <code>string</code> |
| **`level`**        | <code>number</code> |


#### NativeWebRTCErrorEvent

| Prop               | Type                                                                    |
| ------------------ | ----------------------------------------------------------------------- |
| **`connectionId`** | <code>string</code>                                                     |
| **`code`**         | <code><a href="#nativewebrtcerrorcode">NativeWebRTCErrorCode</a></code> |
| **`message`**      | <code>string</code>                                                     |
| **`recoverable`**  | <code>boolean</code>                                                    |
| **`nativeCode`**   | <code>string</code>                                                     |


### Type Aliases


#### MicPermissionState

<code>'prompt' | 'granted' | 'denied'</code>


#### OutputRoute

<code>'system' | 'speaker' | 'receiver'</code>


#### MicProfile

<code>'waveform' | 'pipecat'</code>


#### SessionMode

<code>'measurement' | 'voice_chat'</code>


#### OutputStream

<code>'pcm16k_s16le' | 'pcm48k_s16le'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### NativeWebRTCState

<code>'idle' | 'initializing' | 'connecting' | 'connected' | 'ready' | 'reconnecting' | 'disconnecting' | 'error'</code>


#### RTCIceServerLike

<code>{ urls: string | string[]; username?: string; credential?: string; }</code>


#### NativeWebRTCErrorCode

<code>'E_WEBRTC_UNAVAILABLE' | 'E_PC_CREATE_FAILED' | 'E_NEGOTIATION_FAILED' | 'E_ICE_FAILED' | 'E_DATA_CHANNEL_FAILED' | 'E_ALREADY_RUNNING' | 'E_NOT_RUNNING' | 'E_INVALID_ARGUMENT' | 'E_INTERNAL'</code>

</docgen-api>

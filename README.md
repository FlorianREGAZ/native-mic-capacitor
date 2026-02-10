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

</docgen-api>

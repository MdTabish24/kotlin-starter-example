# RunAnywhereBridge Native Methods - Complete Analysis

## ALL Native Methods in RunAnywhereBridge

### Core SDK

| #   | Method                  | Signature                                             |
| --- | ----------------------- | ----------------------------------------------------- |
| 1   | `racInit`               | `() → int`                                            |
| 2   | `racShutdown`           | `() → int`                                            |
| 3   | `racIsInitialized`      | `() → boolean`                                        |
| 4   | `racSetPlatformAdapter` | `(Object) → int`                                      |
| 5   | `racGetPlatformAdapter` | `() → Object`                                         |
| 6   | `racConfigureLogging`   | `(int, String) → int`                                 |
| 7   | `racLog`                | `(int, String, String) → void`                        |
| 8   | `racSdkInit`            | `(int, String, String, String, String, String) → int` |

### LLM Component (17 methods)

| #   | Method                                      | Signature                                        |
| --- | ------------------------------------------- | ------------------------------------------------ |
| 9   | `racLlmComponentCreate`                     | `() → long`                                      |
| 10  | `racLlmComponentDestroy`                    | `(long) → void`                                  |
| 11  | **`racLlmComponentConfigure`**              | **`(long, String) → int`**                       |
| 12  | `racLlmComponentIsLoaded`                   | `(long) → boolean`                               |
| 13  | `racLlmComponentGetModelId`                 | `(long) → String`                                |
| 14  | `racLlmComponentLoadModel`                  | `(long, String, String, String) → int`           |
| 15  | `racLlmComponentUnload`                     | `(long) → int`                                   |
| 16  | `racLlmComponentCleanup`                    | `(long) → int`                                   |
| 17  | `racLlmComponentCancel`                     | `(long) → int`                                   |
| 18  | `racLlmComponentGenerate`                   | `(long, String, String) → String`                |
| 19  | `racLlmComponentGenerateStream`             | `(long, String, String) → String`                |
| 20  | `racLlmComponentGenerateStreamWithCallback` | `(long, String, String, TokenCallback) → String` |
| 21  | `racLlmComponentSupportsStreaming`          | `(long) → boolean`                               |
| 22  | `racLlmComponentGetState`                   | `(long) → int`                                   |
| 23  | `racLlmComponentGetMetrics`                 | `(long) → String`                                |
| 24  | **`racLlmComponentGetContextSize`**         | **`(long) → int`**                               |
| 25  | `racLlmComponentTokenize`                   | `(long, String) → int`                           |
| 26  | `racLlmSetCallbacks`                        | `(Object, Object) → void`                        |

### STT Component (14 methods)

| #   | Method                             | Signature                              |
| --- | ---------------------------------- | -------------------------------------- |
| 27  | `racSttComponentCreate`            | `() → long`                            |
| 28  | `racSttComponentDestroy`           | `(long) → void`                        |
| 29  | `racSttComponentIsLoaded`          | `(long) → boolean`                     |
| 30  | `racSttComponentLoadModel`         | `(long, String, String, String) → int` |
| 31  | `racSttComponentUnload`            | `(long) → int`                         |
| 32  | `racSttComponentCancel`            | `(long) → int`                         |
| 33  | `racSttComponentTranscribe`        | `(long, byte[], String) → String`      |
| 34  | `racSttComponentTranscribeFile`    | `(long, String, String) → String`      |
| 35  | `racSttComponentTranscribeStream`  | `(long, byte[], String) → String`      |
| 36  | `racSttComponentSupportsStreaming` | `(long) → boolean`                     |
| 37  | `racSttComponentGetState`          | `(long) → int`                         |
| 38  | `racSttComponentGetLanguages`      | `(long) → String`                      |
| 39  | `racSttComponentDetectLanguage`    | `(long, byte[]) → String`              |
| 40  | `racSttSetCallbacks`               | `(Object, Object) → void`              |

### TTS Component (14 methods)

| #   | Method                            | Signature                               |
| --- | --------------------------------- | --------------------------------------- |
| 41  | `racTtsComponentCreate`           | `() → long`                             |
| 42  | `racTtsComponentDestroy`          | `(long) → void`                         |
| 43  | `racTtsComponentIsLoaded`         | `(long) → boolean`                      |
| 44  | `racTtsComponentLoadModel`        | `(long, String, String, String) → int`  |
| 45  | `racTtsComponentUnload`           | `(long) → int`                          |
| 46  | `racTtsComponentCancel`           | `(long) → int`                          |
| 47  | `racTtsComponentSynthesize`       | `(long, String, String) → byte[]`       |
| 48  | `racTtsComponentSynthesizeToFile` | `(long, String, String, String) → long` |
| 49  | `racTtsComponentSynthesizeStream` | `(long, String, String) → byte[]`       |
| 50  | `racTtsComponentGetVoices`        | `(long) → String`                       |
| 51  | `racTtsComponentSetVoice`         | `(long, String) → int`                  |
| 52  | `racTtsComponentGetState`         | `(long) → int`                          |
| 53  | `racTtsComponentGetLanguages`     | `(long) → String`                       |
| 54  | `racTtsSetCallbacks`              | `(Object, Object) → void`               |

### VAD Component (13 methods)

| #   | Method                           | Signature                                 |
| --- | -------------------------------- | ----------------------------------------- |
| 55  | `racVadComponentCreate`          | `() → long`                               |
| 56  | `racVadComponentDestroy`         | `(long) → void`                           |
| 57  | `racVadComponentIsLoaded`        | `(long) → boolean`                        |
| 58  | `racVadComponentLoadModel`       | `(long, String, String) → int`            |
| 59  | `racVadComponentUnload`          | `(long) → int`                            |
| 60  | `racVadComponentCancel`          | `(long) → int`                            |
| 61  | `racVadComponentProcess`         | `(long, byte[], String) → String`         |
| 62  | `racVadComponentProcessStream`   | `(long, byte[], String) → String`         |
| 63  | `racVadComponentProcessFrame`    | `(long, byte[], String) → String`         |
| 64  | `racVadComponentReset`           | `(long) → int`                            |
| 65  | `racVadComponentSetThreshold`    | `(long, float) → int`                     |
| 66  | `racVadComponentGetState`        | `(long) → int`                            |
| 67  | `racVadComponentGetMinFrameSize` | `(long) → int`                            |
| 68  | `racVadComponentGetSampleRates`  | `(long) → String`                         |
| 69  | `racVadSetCallbacks`             | `(Object, Object, Object, Object) → void` |

### Download Manager (3 methods)

| #   | Method                   | Signature                         |
| --- | ------------------------ | --------------------------------- |
| 70  | `racDownloadStart`       | `(String, String, Object) → long` |
| 71  | `racDownloadCancel`      | `(long) → int`                    |
| 72  | `racDownloadGetProgress` | `(long) → String`                 |

### Model Registry (5 methods)

| #   | Method                                 | Signature                                                                           |
| --- | -------------------------------------- | ----------------------------------------------------------------------------------- |
| 73  | `racModelRegistrySave`                 | `(String, String, int, int, int, String, String, long, int, boolean, String) → int` |
| 74  | `racModelRegistryGet`                  | `(String) → String`                                                                 |
| 75  | `racModelRegistryGetAll`               | `() → String`                                                                       |
| 76  | `racModelRegistryGetDownloaded`        | `() → String`                                                                       |
| 77  | `racModelRegistryRemove`               | `(String) → int`                                                                    |
| 78  | `racModelRegistryUpdateDownloadStatus` | `(String, String) → int`                                                            |

### Audio Utilities (3 methods)

| #   | Method                  | Signature                |
| --- | ----------------------- | ------------------------ |
| 79  | `racAudioFloat32ToWav`  | `(byte[], int) → byte[]` |
| 80  | `racAudioInt16ToWav`    | `(byte[], int) → byte[]` |
| 81  | `racAudioWavHeaderSize` | `() → int`               |

### Device Manager (5 methods)

| #   | Method                              | Signature             |
| --- | ----------------------------------- | --------------------- |
| 82  | `racDeviceManagerSetCallbacks`      | `(Object) → int`      |
| 83  | `racDeviceManagerRegisterIfNeeded`  | `(int, String) → int` |
| 84  | `racDeviceManagerIsRegistered`      | `() → boolean`        |
| 85  | `racDeviceManagerClearRegistration` | `() → void`           |
| 86  | `racDeviceManagerGetDeviceId`       | `() → String`         |

### Telemetry & Analytics (13 methods)

| #   | Method                                  | Signature                                                                                                                      |
| --- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| 87  | `racTelemetryManagerCreate`             | `(int, String, String, String) → long`                                                                                         |
| 88  | `racTelemetryManagerDestroy`            | `(long) → void`                                                                                                                |
| 89  | `racTelemetryManagerSetDeviceInfo`      | `(long, String, String) → void`                                                                                                |
| 90  | `racTelemetryManagerSetHttpCallback`    | `(long, Object) → void`                                                                                                        |
| 91  | `racTelemetryManagerFlush`              | `(long) → int`                                                                                                                 |
| 92  | `racAnalyticsEventsSetCallback`         | `(long) → int`                                                                                                                 |
| 93  | `racAnalyticsEventEmitDownload`         | `(int, String, double, long, long, double, long, String, int, String) → int`                                                   |
| 94  | `racAnalyticsEventEmitSdkLifecycle`     | `(int, double, int, int, String) → int`                                                                                        |
| 95  | `racAnalyticsEventEmitStorage`          | `(int, long, int, String) → int`                                                                                               |
| 96  | `racAnalyticsEventEmitDevice`           | `(int, String, int, String) → int`                                                                                             |
| 97  | `racAnalyticsEventEmitSdkError`         | `(int, int, String, String, String) → int`                                                                                     |
| 98  | `racAnalyticsEventEmitNetwork`          | `(int, boolean) → int`                                                                                                         |
| 99  | `racAnalyticsEventEmitLlmGeneration`    | `(int, String, String, String, int, int, double, double, boolean, double, int, float, int, int, int, String) → int`            |
| 100 | `racAnalyticsEventEmitLlmModel`         | `(int, String, String, long, double, int, int, String) → int`                                                                  |
| 101 | `racAnalyticsEventEmitSttTranscription` | `(int, String, String, String, String, float, double, double, int, int, double, String, int, boolean, int, int, String) → int` |
| 102 | `racAnalyticsEventEmitTtsSynthesis`     | `(int, String, String, String, int, double, int, double, double, int, int, int, String) → int`                                 |
| 103 | `racAnalyticsEventEmitVad`              | `(int, double, float) → int`                                                                                                   |

### Dev Config (5 methods)

| #   | Method                       | Signature      |
| --- | ---------------------------- | -------------- |
| 104 | `racDevConfigIsAvailable`    | `() → boolean` |
| 105 | `racDevConfigGetSupabaseUrl` | `() → String`  |
| 106 | `racDevConfigGetSupabaseKey` | `() → String`  |
| 107 | `racDevConfigGetBuildToken`  | `() → String`  |
| 108 | `racDevConfigGetSentryDsn`   | `() → String`  |

**Total: 108 native methods**

---

## KEY FINDINGS FOR CONTEXT SIZE / MODEL CONFIG

### 🔑 `racLlmComponentConfigure(long handle, String jsonConfig) → int`

- **THIS IS THE KEY METHOD** - accepts a handle and a **JSON string** for configuration
- **BUT it is NEVER called from CppBridgeLLM.kt!** The SDK's Kotlin wrapper never invokes it.
- This means the SDK has a native configure method available but doesn't expose it through the high-level API.

### 🔑 `racLlmComponentGetContextSize(long handle) → int`

- **READ-ONLY** - only gets the context size, cannot set it
- Called by CppBridgeLLM to retrieve current context size after model loading

### ❌ Methods that DO NOT exist:

- `racLlmSetContextSize` - DOES NOT EXIST
- `racLlmComponentSetConfig` - DOES NOT EXIST

### ✅ Methods that DO exist:

- `racLlmComponentConfigure(long, String)` - EXISTS but unused by SDK wrapper!

---

## CppBridgeLLM$ModelConfig Fields & Methods

### Fields (with defaults):

| Field           | Type    | Default |
| --------------- | ------- | ------- |
| `contextLength` | int     | 4096    |
| `gpuLayers`     | int     | -1      |
| `threads`       | int     | -1      |
| `batchSize`     | int     | 512     |
| `useMemoryMap`  | boolean | true    |
| `useLocking`    | boolean | false   |

### Methods:

- `getContextLength()` → int
- `getGpuLayers()` → int
- `getThreads()` → int
- `getBatchSize()` → int
- `getUseMemoryMap()` → boolean
- `getUseLocking()` → boolean
- **`toJson()` → String** — serializes all fields to JSON
- `copy(...)` — Kotlin data class copy
- `toString()`, `hashCode()`, `equals()`

### ⚠️ CRITICAL FINDING:

**ModelConfig is accepted by `loadModel()` but COMPLETELY IGNORED!**

In the `loadModel` bytecode, the 4th parameter (`ModelConfig`) is received but the method only passes
`(handle, modelId, modelPath, backendHint)` to `racLlmComponentLoadModel`. The `ModelConfig` object
is never read — no `toJson()` call, no field access, nothing. It's a dead parameter.

The `toJson()` method IS used, but only on **`GenerationConfig`** (not ModelConfig) when calling
`racLlmComponentGenerate` and `racLlmComponentGenerateStreamWithCallback`.

---

## STRATEGY: How to Set Context Size

Since `racLlmComponentConfigure(long, String)` exists as a native method but is unused,
you can call it directly via `RunAnywhereBridge.racLlmComponentConfigure(handle, jsonConfig)`.

The question is: what JSON format does it expect? Since ModelConfig.toJson() builds a JSON with
these fields, it likely expects the same format:

```json
{
  "context_length": 4096,
  "gpu_layers": -1,
  "threads": -1,
  "batch_size": 512,
  "use_memory_map": true,
  "use_locking": false
}
```

**Call it AFTER `racLlmComponentCreate()` but BEFORE `racLlmComponentLoadModel()`.**

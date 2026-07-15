# Implementation Status

This file is a compact status note for the current repository state. It avoids
machine-specific paths and credentials so the project can be shared safely.

## Current Scope

- Android multi-module Gradle project with Compose UI for chat, images, model management, Agent diagnostics, tuning, and settings.
- Local chat runtimes: `llama.cpp`, MNN CPU, and separately admitted GenieX/QAIRT bundles.
- Local image runtimes: `stable-diffusion.cpp`, experimental MNN-Diffusion, and exact-bundle/SoC-gated QNN/HTP.
- Cloud chat/image integrations for OpenAI-compatible, Anthropic Messages, DashScope, and custom endpoints.
- ModelScope recommendations, resumable downloads, local import, manifests, SHA-256 validation, and atomic MNN component/ZIP installation.
- Authenticated OpenAI-compatible Local API with model/runtime/profile/tuning control surfaces and redacted request/media trace evidence.
- Per-model, per-device, per-runtime execution profiles with six signatures, pending/active/LKG transactions, journal recovery, and bounded rollback.
- Capability discovery, safe bootstrap profiles, correctness canaries, quick/standard/deep tuning, and runtime-specific parameter adapters.

## Runtime and Parameter Semantics

- Model load parameters, hot execution parameters, assistant generation parameters, session bindings, and internal evaluation parameters are isolated.
- Assistant or session changes do not silently rewrite load-bound fields. Authorized load changes produce a pending profile and explicit reload transaction.
- `llama.cpp`, MNN, and QAIRT use separate field policies; unsupported or unknown advanced fields are quarantined instead of crossing runtime boundaries.
- First load runs capability discovery and a safe correctness canary. Normal reloads reuse the committed profile for that exact model/device/runtime identity.
- UI and Local API share the same coordinator, model lifecycle, profile state, signatures, preflight, and request sequencing.
- Generation start is visible as `GENERATING`; normal completion, errors, stop, clear-chat, and app-background paths return to `READY`, `ERROR`, or `UNLOADED` as appropriate.
- An idle control plane reports `busy=false`, `code=idle`, and an empty message. Concurrent generation is rejected as `generation_in_progress` before a second request starts.

## Stability and Admission Notes

- MNN text and multimodal chat use the complete split runtime (`MNN`, `MNN_Express`, `MNN_CL`, `MNNOpenCV`, `MNNAudio`, and `llm`).
- MNN multimodal input is open by default on compatible `arm64-v8a` devices after one representative device passes both production surfaces. It is not gated by chipset names or per-device `visionValidated` metadata.
- The representative-device result opens the feature contract only. Every device still derives its own execution profile; thread, batch, KV, context, and tuning values are never copied from the representative device.
- MNN-Diffusion image generation is a separate experimental capability and must not be confused with MNN multimodal chat.
- QNN/QAIRT admission remains exact-bundle, chipset, runtime, memory, and real-execution gated. MNN default-open policy does not weaken those constraints.
- Local GGUF remains supported through the updated `llama.cpp` path, including CPU-safe parameter filtering and load-signature recovery.

## Current Verification

- Full JVM unit-test matrix: 658 tests, 0 failures, 0 errors, 7 skipped.
- `arm64-v8a :app:assembleDebug`: passed with MNN vendor/runtime provenance and typed QAIRT/QNN header verification.
- Final debug APK: 187,399,493 bytes; SHA-256 `2534434C49993384C3DEC9BCAE49E8ABE05FC872167E52BFD7A7C8C8FB45B341`.
- APK Signature Scheme v2: verified; certificate SHA-256 `2619AC4CE0AD8397B84C77DF6BA165801FD4FAB1460470F22F1EB7B3E4F9A9CF`.
- The same APK hash was verified from the installed Elite `base.apk`.
- Formal Elite MainActivity + authenticated Local API MNN vision acceptance passed with distinct request IDs, native sequences 2 then 3, different image hashes, stable model/profile/signatures, `RuntimeOverride=NONE`, `engineLifecycle=ready`, and `generationActive=false`.
- The bounded log window had no App FATAL, ANR, SIGSEGV, SIGABRT, OOM, crash-buffer entry, or process death.

This MNN campaign is not evidence for every model/runtime matrix. Qwen3.6 35B,
Gemma 4, QAIRT, and local image engines keep their own product-surface
acceptance records.

## Build Verification

Use JDK 17, an Android SDK, the configured native dependencies, and run:

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
$env:ANDROID_HOME='<path-to-android-sdk>'
.\gradlew.bat testDebugUnitTest
.\gradlew.bat :app:assembleDebug -Pmca.abis=arm64-v8a
```

## Runtime Validation Policy

- A release claim requires the formal `MainActivity` UI and the authenticated Local API; debug activities, native direct calls, and shadow services are diagnostic only.
- For multimodal acceptance, UI and API must use different real images, request IDs, and monotonically increasing native generation sequences.
- Record APK/install hash, certificate, device/SoC/ABI, App PID/UID, model/profile identities, all six signatures, media counts/hashes, visible answers, performance, lifecycle closure, and crash-window results.
- One representative compatible ARM64 device passing both production surfaces completes MNN vision admission. A second device is optional auxiliary evidence only when it shortens the run.
- Later device-specific failures become explicit exceptions or targeted optimizations; they do not restore a global per-device MNN allowlist.

# Implementation Status

This file is a compact status note for the current repository state. It avoids
machine-specific paths and credentials so the project can be shared safely.

## Current Scope

- Android multi-module Gradle project with Compose UI for chat, images, model management, Agent diagnostics, tuning, and settings.
- Local chat runtimes: `llama.cpp`, MNN CPU, and separately admitted GenieX/QAIRT bundles.
- Local image runtimes: `stable-diffusion.cpp`, experimental MNN-Diffusion, and QNN/HTP bundles. Device information ranks recommendations and selects a runtime transport; package integrity and real native load/graph execution determine compatibility.
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
- QNN/QAIRT uses device information for advisory package ranking and runtime transport selection. It never removes a user-facing action solely for an unlisted device; corrupt packages, missing required binaries, failed native loads, and failed graph execution remain concrete rejection conditions.
- Local GGUF remains supported through the updated `llama.cpp` path, including CPU-safe parameter filtering and load-signature recovery.
- Sparse MoE admission is based on GGUF architecture metadata rather than a `35B-A3B` filename. On devices with up to 16 GiB physical RAM, sparse MoE uses reclaimable file-backed mmap pages, disables mlock and whole-file prefetch, forbids a large-model non-mmap fallback, keeps one sequence, and caps context/batch/ubatch at `4096/2048/256`.
- Exact verified Qwen3.6 35B-A3B artifacts receive Q4 KV, Flash Attention, and `draft-mtp/2`. Adaptive tuning is now generated for the model being loaded, and the rule-set fingerprint invalidates an earlier profile that accidentally borrowed another model's `spec_type=none` plan. SHA-derived MTP capability still works if the user renames the model.

## Current Verification

- Full JVM `testDebugUnitTest` matrix: passed on JDK 17.
- Signed `arm64-v8a :app:assembleRelease`: passed with MNN vendor/runtime provenance and typed QAIRT/QNN header verification.
- Release packaging is v0.2.1 (`versionCode` 5). The GitHub Release contains the signed arm64 APK and its SHA-256 checksum.
- APK Signature Scheme v2: verified; certificate SHA-256 `2619AC4CE0AD8397B84C77DF6BA165801FD4FAB1460470F22F1EB7B3E4F9A9CF`.
- Formal Elite MainActivity + authenticated Local API MNN vision acceptance passed with distinct request IDs, native sequences 2 then 3, different image hashes, stable model/profile/signatures, `RuntimeOverride=NONE`, `engineLifecycle=ready`, and `generationActive=false`.
- Formal Elite MainActivity + authenticated Local API Qwen3.6 35B-A3B acceptance also passed on the 12 GB-class device. Effective settings were mmap on, mlock off, no mmap fallback or prefetch, `4096/2048/256`, Q4 KV, Flash Attention, and `draft-mtp/2`; UI request `ui-0bd6319ae25f4bc4a2f68804118fbffc` used native sequence 2 and returned `ELITE_UI_35B_OK`, while API request `chatcmpl-51173d5c49ec4181b78ed446d1e10e8b` used sequence 3 and returned `ELITE_API_35B_OK`.
- The bounded log window had no App FATAL, ANR, SIGSEGV, SIGABRT, OOM, crash-buffer entry, or process death.

The MNN and Qwen3.6 35B campaigns remain separate feature records. Gemma 4,
QAIRT, and local image engines keep their own product-surface acceptance
records.

## Build Verification

Use JDK 17, an Android SDK, the configured native dependencies, and run:

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
$env:ANDROID_HOME='<path-to-android-sdk>'
.\gradlew.bat testDebugUnitTest
.\gradlew.bat :app:assembleRelease "-Pmca.abis=arm64-v8a" "-PmcaQnnSdkRoot=<path-to-qairt-sdk>"
```

## Runtime Validation Policy

- A release claim requires the formal `MainActivity` UI and the authenticated Local API; debug activities, native direct calls, and shadow services are diagnostic only.
- For multimodal acceptance, UI and API must use different real images, request IDs, and monotonically increasing native generation sequences.
- Record APK/install hash, certificate, device/SoC/ABI, App PID/UID, model/profile identities, all six signatures, media counts/hashes, visible answers, performance, lifecycle closure, and crash-window results.
- One representative compatible ARM64 device passing both production surfaces completes MNN vision admission. A second device is optional auxiliary evidence only when it shortens the run.
- Later device-specific failures become explicit exceptions or targeted optimizations; they do not restore a global per-device MNN allowlist.

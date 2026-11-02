# Third-Party Notices

This repository uses third-party open-source projects through Git submodules
and Gradle dependencies.

## Native Submodules

### llama.cpp

- Path: `third_party/llama.cpp`
- Upstream: https://github.com/ggml-org/llama.cpp
- License: MIT

### stable-diffusion.cpp

- Path: `third_party/stable-diffusion.cpp`
- Upstream: https://github.com/leejet/stable-diffusion.cpp
- License: MIT

MCA keeps Android-specific integration patches under `third_party/patches`.

### Prebuilt Wan engine

- Path: `core/sd-native/prebuilt/wan-engine-arm64-v8a.zip`
- Source: HappyYzy `stable-diffusion.cpp` at `a63954d`, plus the MCA Wan patch
  queue `0001..0008` (see `docs/proposals/mca-native-video-workflow-v1/build-engine.sh`)
- License: MIT (`stable-diffusion.cpp` and `ggml`); the archive contains only
  compiled libraries, no model weights
- Pins: `core/sd-native/prebuilt/WAN_ENGINE_BASELINE.properties`
- The `unpackWanEngine` Gradle task verifies the archive and every extracted
  library against these pins before packaging.

### tokenizers-cpp

- Path: `third_party/tokenizers-cpp`
- Upstream: https://github.com/mlc-ai/tokenizers-cpp
- License: Apache-2.0
- MCA patch: `third_party/patches/tokenizers-cpp-mca.patch` (base commit and SHA-256 are recorded in `vendor/tokenizers-cpp.properties`)

## Android / Kotlin Dependencies

The Android app uses Gradle dependencies declared in `gradle/libs.versions.toml`,
including AndroidX, Jetpack Compose, Kotlin coroutines, OkHttp, Room, WorkManager,
and test libraries. Their licenses are governed by their respective upstream
projects.

### GenieX Android

- Current build input: `vendor/geniex/geniex-android-0.10.0-mca1.aar`
- Upstream: https://github.com/qualcomm/GenieX, tag `v0.10.0`
- License: BSD-3-Clause; license and notice are retained in `vendor/geniex/`
- SHA-256: recorded in `vendor/geniex/geniex-vendor.properties`

The `0.3.12` AAR and its patch are retained as rollback/reference artifacts; current app and
engine variants use the unpatched `0.10.0` AAR.

## Models

Model weights are not included in this repository. Users are responsible for
checking and following each model provider's license, acceptable use policy, and
redistribution terms.

### Wan2.2 TI2V-5B video assets

The video workspace downloads these files at the user's request; the app pins each
file's size and SHA-256 and never bundles weights in the APK. Byte-identical copies
are mirrored in the project's ModelScope repository `lyydfys/mca-wan22-video-assets`
(pinned revisions; license texts and per-file hashes are listed in its README).

- DiT and VAE: QuantStack/Wan2.2-TI2V-5B-GGUF (Hugging Face), Apache-2.0; base model
  Wan-AI/Wan2.2-TI2V-5B, Apache-2.0.
- Text encoder: city96/umt5-xxl-encoder-gguf (Hugging Face), Apache-2.0; base model
  google/umt5-xxl.
- 4-step Turbo LoRA: hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF (Hugging Face), Apache-2.0;
  ModelScope mirror Kijai/WanVideo_comfy.
- TAEHV head: madebyollin/taehv (GitHub), MIT.

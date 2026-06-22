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

## Android / Kotlin Dependencies

The Android app uses Gradle dependencies declared in `gradle/libs.versions.toml`,
including AndroidX, Jetpack Compose, Kotlin coroutines, OkHttp, Room, WorkManager,
and test libraries. Their licenses are governed by their respective upstream
projects.

## Models

Model weights are not included in this repository. Users are responsible for
checking and following each model provider's license, acceptable use policy, and
redistribution terms.

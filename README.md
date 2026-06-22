# MCA - MuYu Chat Agent

MCA is an Android-native, local-first AI workspace for people who want direct
control over models, inference backends, and cloud API connections.

The project currently focuses on:

- Local GGUF chat inference through `llama.cpp`.
- Cloud chat engines through OpenAI-compatible and Anthropic Messages protocols.
- Local and cloud image-generation engine management.
- ModelScope-oriented model discovery and resumable downloads.
- A Compose mobile UI for chat, model management, image generation, agent
  diagnostics, settings, and local API tools.

MCA does not include model weights or API keys. Users bring their own local
models, cloud endpoints, and provider credentials.

## Status

This repository is an active Android app workspace. The chat and model
management surfaces are usable, while local image generation is still
experimental and should be tested per device and model bundle.

Recommended public positioning:

> Android local-first AI workspace: local GGUF, cloud APIs, model management,
> and image-generation engines under user control.

## Features

- **Local chat**: native `llama.cpp` bridge, streaming generation, stop support,
  token speed labels, reasoning-content filtering, and local benchmark support.
- **Cloud chat**: user-configured OpenAI-compatible or Anthropic Messages
  endpoints with locally encrypted API key storage.
- **Image page**: GPT-like image workspace with local/cloud engine switching,
  prompt composer, generation states, template cards, and image library.
- **Local image engines**: `stable-diffusion.cpp` bridge with progress/cancel
  hooks and bundle-aware model registration.
- **Model hub**: local/imported models, ModelScope recommendations, resumable
  downloads, file classification, and engine grouping.
- **Agent diagnostics**: local device profiling, model recommendations,
  benchmark-based tuning, and explainable parameter plans.
- **Local API**: loopback OpenAI-style API scaffolding for local integrations.

## Repository Layout

- `:app` - Android application, navigation, ViewModel, cloud/local providers.
- `:core:native` - `llama.cpp` C++/JNI bridge for local chat.
- `:core:sd-native` - `stable-diffusion.cpp` C++/JNI bridge for local image generation.
- `:core:engine` - single-active-generation inference service.
- `:core:modelstore` - GGUF import, manifest, SHA-256, managed model storage.
- `:core:download` - ModelScope parsing, file listing, and resumable downloads.
- `:core:telemetry` - runtime metrics, SoC detection, and JSONL logging.
- `:core:deviceprofile` - device capability and thermal profiling.
- `:core:tuning` - parameter plan generation.
- `:core:benchmark` - short local benchmark runner.
- `:core:advisor` - local recommendation engine and agent logs.
- `:api:local` - AIDL service and loopback REST server skeleton.
- `:feature:chat` - chat and image-generation UI.
- `:feature:agent` - agent diagnostics UI.
- `:feature:modelhub` - model management UI.
- `:feature:settings` - runtime, logs, and local API UI.

## Build Requirements

- Android Studio or command-line Gradle.
- JDK 17.
- Android SDK with:
  - Android platform matching `compileSdk`.
  - Android build tools.
  - CMake 3.31.6 or compatible version configured by Gradle.
  - Android NDK matching `gradle/libs.versions.toml`.

Create a local `local.properties` file or set `ANDROID_HOME`:

```properties
sdk.dir=/path/to/android-sdk
```

`local.properties` is ignored by Git.

## Clone

This repository uses submodules for native inference backends:

```bash
git clone --recurse-submodules <repo-url>
cd mym
```

If you cloned without submodules:

```bash
git submodule update --init --recursive
```

## Build

PowerShell:

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
$env:ANDROID_HOME='<path-to-android-sdk>'
.\gradlew.bat :app:assembleDebug
```

Bash:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Native Backends

### Local Chat

`core/native` builds `libmca_native.so`. With `third_party/llama.cpp` present,
the module links the `llama.cpp` Android CPU backend. The project keeps a stub
fallback for development builds where `llama.cpp` is temporarily unavailable.

### Local Image Generation

`core/sd-native` builds `libmca_sd_native.so` against
`third_party/stable-diffusion.cpp`. MCA stores its Android-specific patch in:

```text
third_party/patches/stable-diffusion.cpp-mca-android.patch
```

Gradle applies this patch when needed before the native CMake build.

Local image generation is model-bundle sensitive. Some newer image models need
a diffusion model plus VAE/AE and text encoder/LLM components in the same
engine directory.

## Privacy

- Local chat and local image generation run on device.
- Cloud chat and cloud image generation send prompts to the user-configured
  provider endpoint.
- Cloud API keys are stored locally with Android Keystore-backed encryption.
- The repository does not contain API keys, model weights, or private user data.

See [PRIVACY.md](PRIVACY.md) for more detail.

## Third-Party Code

The repository references upstream native projects through submodules:

- `llama.cpp` - MIT License.
- `stable-diffusion.cpp` - MIT License.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Contributing

MCA is early-stage and changes quickly. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) before opening issues or pull requests.

## License

MCA is licensed under the MIT License. See [LICENSE](LICENSE).

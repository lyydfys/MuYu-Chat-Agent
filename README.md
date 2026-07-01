# MCA - MuYu Chat Agent

Android local-first AI workspace: local GGUF chat, user-configured cloud APIs,
model management, and image-generation engines under user control.

[![Android CI](https://github.com/lyydfys/MCA/actions/workflows/android-ci.yml/badge.svg)](https://github.com/lyydfys/MCA/actions/workflows/android-ci.yml)
[![Release](https://img.shields.io/github/v/release/lyydfys/MCA?include_prereleases&label=release)](https://github.com/lyydfys/MCA/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

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

## Screenshots

Real-device screenshots from the Android app:

| Chat | Workspace | Images | Model recommendations |
|---|---|---|---|
| ![Chat screen](docs/assets/screenshots/01-home.png) | ![Workspace navigation](docs/assets/screenshots/02-workspace-nav.png) | ![Image generation screen](docs/assets/screenshots/03-images.png) | ![Model recommendations](docs/assets/screenshots/04-model-management.png) |

<details>
<summary>View more screenshots</summary>

| Settings | Cloud engines | Local engines | Model market |
|---|---|---|---|
| ![Settings screen](docs/assets/screenshots/05-settings.png) | ![Cloud model engines](docs/assets/screenshots/06-model-cloud.png) | ![Local model engines](docs/assets/screenshots/07-model-local.png) | ![Model market](docs/assets/screenshots/08-model-market.png) |

| Model picker | Local API |
|---|---|
| ![Model picker](docs/assets/screenshots/09-model-picker.png) | ![Local API redacted](docs/assets/screenshots/10-local-api.png) |

The Local API screenshot is redacted before publishing.

</details>

![MCA demo walkthrough](docs/assets/demo/mca-demo.gif)

The lightweight GIF above is generated from real-device screenshots. A higher
quality MP4 is available at [docs/assets/demo/mca-demo.mp4](docs/assets/demo/mca-demo.mp4).

## Status

This repository is an active Android app workspace. The chat and model
management surfaces are usable, while local image generation is still
experimental and should be tested per device and model bundle.

Current release status:

- Alpha APKs are published through
  [GitHub Releases](https://github.com/lyydfys/MCA/releases).
- The first public package target is `arm64-v8a` Android devices.
- Local chat is the primary stable local path.
- Local image generation is experimental and requires complete model bundles.
  Do not treat phone-side image generation as a guaranteed stable feature yet.

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
- **Local API**: OpenAI-compatible local server for trusted same-device and
  same-LAN clients, including `/v1/models`, `/v1/chat/completions`, JSON
  replies, and SSE streaming.

## Install

Download the latest alpha APK from
[GitHub Releases](https://github.com/lyydfys/MCA/releases). Android may ask you
to allow installation from your browser or file manager.

The APK does not include model weights or cloud credentials. After installing:

1. Add a local GGUF chat model or configure a cloud chat engine.
2. Configure an image engine if you want cloud or local image generation.
3. Check [docs/PERMISSIONS.md](docs/PERMISSIONS.md) before enabling network or
   local API workflows.
4. Check [docs/MODEL_COMPATIBILITY.md](docs/MODEL_COMPATIBILITY.md) before
   choosing local image bundles or cloud provider protocols.

Release APKs are signed by the project maintainer. Debug APKs are not intended
for public installation.

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
engine directory. It is currently an experimental capability and should be
validated on each target device before being promoted as stable.

## Model and API Compatibility

See [docs/MODEL_COMPATIBILITY.md](docs/MODEL_COMPATIBILITY.md) for the current
compatibility matrix covering local GGUF chat, OpenAI-compatible chat,
Anthropic Messages, OpenAI Images, DashScope Image, custom image paths, and
experimental local image bundles.

## Local API

MCA can expose the currently loaded local chat model through an
OpenAI-compatible API for trusted clients.

Use this when you want another app, browser, desktop client, or local tool to
talk to the model running on the phone.

Recommended client settings:

| Field | Value |
|---|---|
| Protocol | OpenAI-compatible |
| Same-device Base URL | `http://127.0.0.1:11435/v1` |
| Same-LAN Base URL | `http://<phone-lan-ip>:11435/v1` |
| API key | Generated inside MCA Settings -> Local API |
| Model | Pick from `/v1/models`, or enter the returned model `id` manually |

Supported paths:

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`
- `GET /` for the built-in web chat page

`/v1/chat/completions` supports standard JSON responses and `stream=true`
Server-Sent Events. Same-LAN access requires enabling the in-app "open port"
switch and should only be used on trusted networks.

## Privacy

- Local chat and local image generation run on device.
- Cloud chat and cloud image generation send prompts to the user-configured
  provider endpoint.
- Cloud API keys are stored locally with Android Keystore-backed encryption.
- The repository does not contain API keys, model weights, or private user data.

See [PRIVACY.md](PRIVACY.md) and [docs/PERMISSIONS.md](docs/PERMISSIONS.md) for
more detail.

## Roadmap

- **v0.1 alpha**: local chat, cloud chat, cloud image engines, image workspace,
  model management, ModelScope-oriented downloads, and release packaging.
- **v0.2**: stabilize local image bundles, improve device compatibility
  reporting, and refine image generation progress/cancel behavior.
- **v0.3**: expand mobile agent diagnostics and local API workflows.

MCA intentionally avoids bundling model weights. Model recommendations and
download sources must respect each upstream model's license.

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

# Implementation Status

This file is a compact status note for the current repository state. It avoids
machine-specific paths so the project can be shared safely.

## Current Scope

- Android multi-module Gradle project.
- Compose mobile UI for chat, images, model management, agent diagnostics, and settings.
- Native `llama.cpp` chat backend through `:core:native`.
- Native `stable-diffusion.cpp` image backend through `:core:sd-native`.
- Cloud chat support for OpenAI-compatible and Anthropic Messages protocols.
- Cloud image support for OpenAI Images, DashScope Image, and custom-path style endpoints.
- ModelScope recommendation and resumable download plumbing.
- Local model import, model manifest, and SHA-256 validation.
- Agent diagnostics with device profiling, local benchmark support, and parameter recommendations.
- Local API scaffolding for loopback OpenAI-compatible calls.

## Stability Notes

- Local GGUF chat is the primary local inference path.
- Cloud chat/image engines are user-configured and depend on provider behavior.
- Local image generation is experimental and requires complete engine bundles for many modern models.
- GPU/NPU acceleration experiments are not part of the current committed product path.

## Build Verification

Use a local JDK 17 and Android SDK, then run:

```powershell
$env:JAVA_HOME='<path-to-jdk-17>'
$env:ANDROID_HOME='<path-to-android-sdk>'
.\gradlew.bat :app:assembleDebug
```

or:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

## Runtime Validation Checklist

- Install debug APK on a real `arm64-v8a` Android device.
- Import a small GGUF chat model and verify streaming chat.
- Run a short benchmark and confirm decode speed is reported.
- Configure a cloud chat endpoint and run the quick test.
- Configure a cloud image endpoint and verify a small generation request.
- Import or download a complete local image bundle before testing local image generation.


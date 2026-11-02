# Pinned external Wan engine

`:core:sd-native` packages three prebuilt arm64 libraries that are not produced by
Gradle: the external `stable-diffusion.cpp` engine, its Wan JNI adapter, and the
HTP DSP skeleton. The shipped `libstable-diffusion.so` is 162,475,200 B, which is
over GitHub's 100 MB per-file limit, so the exact bytes are stored here as a
single ZIP (46.6 MB) instead of three loose `.so` files.

- `wan-engine-arm64-v8a.zip` — the three libraries under `arm64-v8a/`
- `WAN_ENGINE_BASELINE.properties` — archive and per-file size/SHA-256 pins

The `unpackWanEngine` Gradle task in `../build.gradle.kts` extracts this archive
into `src/main/jniLibs` (git-ignored) before `preBuild`, verifies every file's
size and SHA-256, and fails the build on a mismatch. A fresh clone therefore
gets the same engine bytes without LFS.

Reproduce the archive from source with
`docs/proposals/mca-native-video-workflow-v1/build-engine.sh`; the script prints
the size/SHA-256 of every produced artifact. The libraries are MIT-licensed
`stable-diffusion.cpp` / `ggml` builds with the MCA patch queue applied; see
`THIRD_PARTY_NOTICES.md`. No model weights are stored in this repository.

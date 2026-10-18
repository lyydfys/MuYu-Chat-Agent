# Contributing

Thanks for your interest in MCA.

MCA is an early Android local-first AI workspace. The codebase changes quickly,
so contributions should be small, focused, and easy to test.

## Mandatory Project Contract

Before changing code, read the [MCA architecture and maintainability hard lock](docs/architecture/MCA_PROJECT_HARD_LOCK.md).
It defines required ownership and dependency boundaries, feature and data compatibility,
UI preservation, verification, exceptions, and handoff evidence for maintainers and agents.
Existing architecture debt must not expand, and passing a scoped check is not proof of
complete production acceptance. Include the relevant evidence and unverified items with
each contribution.

## Development Setup

1. Install JDK 21 to run the complete verification toolchain (Java source targets remain defined by the build scripts).
2. Install Android SDK, NDK, and CMake versions compatible with `gradle/libs.versions.toml`.
3. Clone with submodules:

```bash
git clone --recurse-submodules <repo-url>
```

4. Build the standard complete APK. The project build contract is documented in
   [docs/BUILD.md](docs/BUILD.md); it requires both `arm64-v8a` and `x86_64`
   and a configured QAIRT/QNN SDK:

```bash
export MCA_QNN_SDK_ROOT=/path/to/qairt-sdk
./gradlew :app:assembleDebug \
  -Pmca.abis=arm64-v8a,x86_64 \
  -PmcaQnnSdkRoot="$MCA_QNN_SDK_ROOT" \
  -PmcaQnnTypedBindingsRequired=true \
  --no-daemon --console=plain
```

On Windows, use the guarded project script:

```powershell
.\scripts\build.ps1
```

The script rejects a missing QNN SDK or an APK that is missing either ABI or
the required native runtime libraries. Do not replace it with an arm64-only
debug build.

## Contribution Guidelines

- Keep changes scoped to one feature or bug fix.
- Do not commit API keys, model weights, personal paths, generated APKs, or build outputs.
- Do not vendor large model files into the repository.
- Prefer existing UI patterns and module boundaries.
- Add or update tests when changing shared parsing, download, storage, or native bridge behavior.
- Clearly label experimental device-specific inference changes.

## Local Image Generation

Local image generation is experimental. When changing image engine behavior,
please include:

- Device model and SoC.
- Model bundle name and component list.
- Image size, steps, thread count, and elapsed time.
- Crash logs or native output when applicable.

## Pull Request Checklist

- `git status` is clean except intended changes.
- No secrets or private paths are present.
- Submodules are not accidentally bumped.
- Build or focused tests were run, or the PR states why they were not.

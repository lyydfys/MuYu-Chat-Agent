# Open Source Checklist

Use this before making the repository public.

## Repository Hygiene

- [x] `git status --short --ignored` shows no unexpected tracked or ignored files.
- [x] `local.properties`, APKs, build folders, and `.cxx` folders are ignored.
- [x] No model weights are committed.
- [x] Top-level submodules are initialized and pinned intentionally.
- [x] `README.md`, `LICENSE`, `PRIVACY.md`, `SECURITY.md`, and `THIRD_PARTY_NOTICES.md` are present.
- [x] `docs/MODEL_COMPATIBILITY.md` and `docs/PERMISSIONS.md` are linked from the README.

## Sensitive Information

- [x] No API keys, tokens, passwords, or provider credentials are committed.
- [x] No private local paths or device-specific secrets are present in docs.
- [x] Logs and screenshots do not expose prompts, API keys, account IDs, or private file paths.

## Product Claims

- [x] Local chat is described as the primary stable local path.
- [x] Local image generation is clearly marked experimental.
- [x] Cloud features are described as user-configured provider integrations.
- [x] Local image generation is described as experimental in README and release notes.
- [x] Model licenses are left to the user/provider and are not implied by MCA.

## Build Verification

- [x] `:core:download:testDebugUnitTest`
- [x] `testDebugUnitTest`
- [x] `:app:assembleDebug`
- [x] Real-device smoke test for local chat.
- [ ] Optional real-device smoke test for local image generation with a complete bundle.

Last public-readiness check: 2026-06-24.

# Open Source Checklist

Use this before making the repository public.

## Repository Hygiene

- [ ] `git status --short --ignored` shows no unexpected tracked or ignored files.
- [ ] `local.properties`, APKs, build folders, and `.cxx` folders are ignored.
- [ ] No model weights are committed.
- [ ] Submodules are initialized and pinned intentionally.
- [ ] `README.md`, `LICENSE`, `PRIVACY.md`, `SECURITY.md`, and `THIRD_PARTY_NOTICES.md` are present.

## Sensitive Information

- [ ] No API keys, tokens, passwords, or provider credentials are committed.
- [ ] No private email addresses, local account paths, or device-specific secrets are present in docs.
- [ ] Logs and screenshots do not expose prompts, API keys, account IDs, or private file paths.

## Product Claims

- [ ] Local chat is described as the primary stable local path.
- [ ] Local image generation is clearly marked experimental.
- [ ] Cloud features are described as user-configured provider integrations.
- [ ] Model licenses are left to the user/provider and are not implied by MCA.

## Build Verification

- [ ] `:core:download:testDebugUnitTest`
- [ ] `:app:assembleDebug`
- [ ] Real-device smoke test for local chat.
- [ ] Optional real-device smoke test for local image generation with a complete bundle.

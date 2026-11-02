# scripts/dsh 鈥?Android DSH payload staging

This directory stages the official DeepSeek Harness (DSH) Android payload that
`core:dsh-runtime` installs and executes. It contains **scripts and audit metadata only**:
payload binaries are never committed.

## Provenance

| Item | Value |
| --- | --- |
| Official DSH source | `deepseek-ai/deepseek-harness` @ `687f282e8625c6b8d11ff58211a90524be2e4317`, package `0.2.1-rc.2` (MIT) |
| Candidate Android runtime | `woaiys3/deepseek-harness-android-app` release `v1.20.1` APK |
| Reference APK | `DeepSeekHarness.apk`, 162,006,797 bytes, sha256 `c324272bff00e8cce06e0baa2ebb9b88e524a5d246e06f8cb55d39df7c66dafa` |
| Inner payload | `assets/payload.zip`, 160,464,248 bytes, sha256 `975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab` |
| Kernel revision | `20261018072502` |
| Kernel version | `0.2.1-rc.2` |

The Android app is reference material, not official DSH Android support. Its Launcher keeps
`targetSdk 28` because it executes Node from writable app storage; MCA stays on
`targetSdk 36` and executes the Node ELF from `nativeLibraryDir` instead (proven by the R01
probe, see `E:\tmp\dsh-runtime\evidence`). The reference APK's own `cordis.patch.yml`
(`danger-full-access` + `approval: never` + Android bridge plugins) is **not** shipped into the
MCA runtime home; MCA seeds its own overlay.

## Command

```powershell
# from the repository root; JAVA/SDK not required for staging
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dsh\stage.ps1 `
    -Apk E:\tmp\dsh-runtime\DeepSeekHarness.apk
```

Optional:

```powershell
# stage from an already-extracted payload.zip
... -PayloadZip E:\tmp\dsh-runtime\payload.zip

# re-pin payload-lock.json after auditing a NEW source build (never do this to silence a mismatch)
... -UpdateLock

# additionally extract the full payload tree to third_party/dsh/payload for inspection
... -Extract
```

The script fails closed on:

- a missing required executable (`node`, `rg`, `curl`, `git`, `git-remote-http(s)`),
- an executable that is not an ELF file,
- any sha256/size/version difference against `scripts/dsh/payload-lock.json` (unless
  `-UpdateLock` is explicitly passed after re-audit),
- a failed copy of the payload archive into the module assets.

## Emitted layout

```
core/dsh-runtime/src/main/assets/dsh/payload.zip      # official payload (git-ignored)
core/dsh-runtime/src/main/assets/dsh/MANIFEST.json    # runtime sha256 manifest (git-ignored)
core/dsh-runtime/src/main/jniLibs/arm64-v8a/           # ELF executables renamed lib*.so (git-ignored)
  libdshnode.so            <- runtime/bin/node
  libdshrg.so              <- runtime/bin/rg
  libdshcurl.so            <- runtime/bin/curl
  libdshgit.so             <- bin/git
  libdshgitremotehttp.so   <- git/libexec/git-core/git-remote-http
  libdshgitremotehttps.so  <- git/libexec/git-core/git-remote-https
  libdshpython.so          <- python/bin/python3.14
third_party/dsh/MANIFEST.json   # source/version/sha256/sizes/licenses (git-ignored)
third_party/dsh/FILES.tsv       # full payload file list for audit (git-ignored)
scripts/dsh/payload-lock.json   # committed pin: apk/payload/executables/critical-file sha256
```

`PackageManager` extracts `jniLibs/*/lib*.so` into `app.info.nativeLibraryDir` at install
time, which is outside the writable app home and therefore executable on `targetSdk 36`.
Everything else (the kernel tree, `runtime/lib/*.so`, `dshhome` template) is extracted from
`payload.zip` into `files/dsh/payload` and either read or `dlopen`ed; it is never `exec`ed.

The app assembly creates symlinks `files/dsh/payload/bin/<tool> -> <nativeLibraryDir>/libdsh<tool>.so`
so PATH-based tool spawns (Node child processes) resolve to an executable location.

## Executable command dispatcher (D11)

	argetSdk 36 cannot execute the payload's own `/system/bin/sh` wrapper scripts
(in/bash, in/npm, in/npx, in/pnpm, in/pip, in/python) from app storage, so
those names need an executable that lives in 
ativeLibraryDir. src/main/cpp/dsh_cmd.c is that
dispatcher: it maps its own rgv[0] to the real entry point (node + 
pm/bin/npm-cli.js,
pnpm/bin/pnpm.cjs with the copy-import flag, <python> -m pip, or /system/bin/sh with
bash-only leading options stripped) and execs it. It is an executable ELF, which AGP's
externalNativeBuild does not package, so it is built and staged separately:

`powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dsh\build-exec-dispatch.ps1
# optional: -NdkRoot <path>, -CheckOnly (build + verify, do not stage)
`

The script compiles the C with the project's NDK for rm64-v8a, verifies the result is an
ET_DYN executable with the /system/bin/linker64 interpreter, then copies it to
jniLibs/arm64-v8a/libdshcmd.so (git-ignored, like the payload ELFs).

Runtime side:

- DshNodeCommand.environment exports DSH_PAYLOAD_DIR so the dispatcher resolves the JS/Python
  entries without guessing;
- prepareRuntimeLinks should link in/{npm,npx,pnpm,pnpx,pip,pip3,bash} to
  libdshcmd.so (without those links the dispatcher is packaged but not reachable, and the
  closure reports those names as typed-blocked rather than pretending they work);
- DshCommandClosure reports the whole closure (DIRECT / NODE_SCRIPT / PYTHON_MODULE /
  DISPATCHER / BLOCKED(reason)) and never claims GNU bash: in/bash is a sh compatibility
  layer (gnuBash=false).

## Android Landlock sandbox runner (D01)

The pinned payload ships the official `bash-sandbox` / `sandbox-local` seam but no Android
Landlock launcher, so the reference overlay disables `bash-sandbox`. MCA supplies the missing
platform runner as an executable PIE and passes it through the official provider's
`runnerCommand`; the payload bytes and `payload-lock.json` are not changed.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dsh\build-sandbox-runner.ps1
# optional: -NdkRoot <path>, -CheckOnly (build + verify, do not stage)
```

It compiles `core/dsh-runtime/src/main/cpp/dsh_sandbox.c` for `arm64-v8a`, verifies the
ET_DYN/linker64 contract, and stages `jniLibs/arm64-v8a/libdshsandbox.so` (git-ignored). The
seeder runs its `--probe` before composing the overlay:

- usable kernel: keep official `bash-sandbox`, configure `sandbox.runnerCommand`, omit
  `bash-local`, record `# mca-sandbox: kernel`;
- unusable/missing: disable `bash-sandbox`, insert `bash-local`, record
  `# mca-sandbox: advisory (reason)`.

The fallback is deliberately honest: without the kernel runner the UI must not claim workspace
isolation. Device acceptance is in `artifacts/dsh-v2/verification/LANDLOCK-WORKSPACE-WRITE.md`.

## Real PTY (D12)

src/main/cpp/dsh_pty.c implements a real POSIX PTY (paired posix_openpt/grantpt/unlockpt/
ptsname_r, setsid+TIOCSCTTY, 	ermios, TIOCSWINSZ, bounded SIGTERM/SIGKILL stop) and is
built as libdshpty.so by the module's externalNativeBuild. DshPtyNative loads it lazily; a
missing/broken library is a typed NATIVE_LOAD_FAILED, and DshTerminalFactory then reports
PIPE_FALLBACK (no isatty, no resize, no SIGWINCH, no job control) instead of pretending to be
a terminal. The real PTY is unverified without an arm64 device.

## Licensing

The manifest lists the full dependency closure with licenses. Notes that need review before
public distribution:

- `readline` (GPL-3.0) and `git` (GPL-2.0) are included in the reference payload.
- The reference payload's `python/npm/pnpm` **wrapper scripts** cannot be executed from
  writable app storage on `targetSdk 36`; only the ELF executables above are mirrored. Node,
  `rg`, `curl`, `git` and `python3.14` need their wrapper scripts re-implemented as symlinks or
  native executables before those tools can run from DSH on MCA.
- DSH itself is MIT; the reference Android app is MIT (its `vscreen` LGPL parts are not used
  by MCA).

## D24 closure upgrade device acceptance

`closure-upgrade-acceptance.ps1` and `D24-CLOSURE-UPGRADE-ACCEPTANCE.md` verify the candidate ->
trial -> stop-proof -> recovery path on a real device. A same-version candidate is materialised
**on the device** from the APK's own verified payload archive by the debug-only staging source
(`app/src/debug`, absent from release variants); nothing is hand-crafted through `adb push`/`run-as`.
The trial itself is started from the DSH console, because no API/agent entry may start an upgrade.
The checklist is **NOT RUN** in the authoring slice and only reports PASS from captured evidence.


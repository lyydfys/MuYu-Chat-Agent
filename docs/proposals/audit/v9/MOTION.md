# Motion & craft layer (V9 frontend slice)

Status: implemented and unit-tested; device frame evidence captured for the base animations.
Last updated: round 3 (commits `9054004c`, `4fc1e875`, `4ac592bb`, `3c788881`, `5e912ff9`,
`0ea22405`, `12d6d629`, `59437209`).

## 1. One motion language

`core/designsystem/.../McaMotion.kt` is the only source of animation specs:

| token | spec | use |
|---|---|---|
| `press` | spring 1.0 / 1100 | press weight (`mcaPressScale`, 0.97) |
| `contentEnter` | spring 0.9 / 380 | entrance / stagger (`mcaEnterReveal`) |
| `sizeChange` | spring 0.86 / 420 | Float size/rotation |
| `sizeChangePx` | same, `IntSize` | `animateContentSize`, size transitions |
| `pageSlide` | spring 1.0 / 760 + `IntOffset` threshold | `animateItem` placement, page slide |
| `fade(ms)` | tween | paired fades |

`McaPageMotion` (240 ms enter / 180 ms dismiss / 140 ms fade) is untouched; `McaMotion.pageSlide`
uses the same damping/stiffness as `McaPageMotion.enterSlide`, asserted by
`McaMotionContractTest`.

## 2. What animates, and what does not

- **Shell**: overlay tabs crossfade in 200 ms; chat stays the always-composed base layer so its
  view model, draft, scroll and focus are never disposed. Startup screen crossfades into the app
  in 220 ms.
- **History drawer**: content staggers in when the sheet crosses ~15 % visibility (`key(epoch)` +
  `mcaEnterReveal`), with a short window so scrolling does not replay it. The drawer owns back via
  a `BackHandler` that calls `DrawerState.close()` (`2fcc66c5`). Material3 1.4 does ship
  `DrawerPredictiveBackHandler`, but `ModalNavigationDrawer` does **not** wire it automatically:
  on device, removing our callback made a back gesture exit the activity to the previous app
  (recorded in `artifacts/ui-quality/r2/r4-back.mp4`). A finger-following predictive sheet would
  require replacing `ModalNavigationDrawer` with a custom predictive container, which changes the
  frozen drawer geometry, so it is deliberately out of scope for this slice.
- **Chat surfaces**: search/title swap animates size through `McaMotion.sizeChangePx`; gallery rows
  use `animateItem`; the image preview scales 0.96→1 with a fade and finishes before dismissing.
- **Modules**: settings/modelhub/agent lists use `animateItem`; expandable rows animate height;
  cards stagger; status tags crossfade with zero geometry change (new text overlaid on the old one
  instead of Compose `Crossfade`, which would animate the layout box).
- **Icons**: every feature surface uses Material **Rounded** glyphs at the same sizes, plus the
  shared `McaIconButton` (48 dp, bounded ripple, press weight, required accessible name, optional
  tonal selected state).

Design language is frozen: no colour, type, spacing, radius, layout-geometry or copy change was
introduced by the motion slices. The only geometry animation is an explicit expand/collapse.

## 3. Performance rules

- Running values are read in `graphicsLayer`/draw lambdas; no animation value is read during
  composition (asserted structurally by `McaMotionContractTest` and `McaIconButtonContractTest`).
- Predictive-back progress for `SwipeBackPage` is funnelled through a conflated `Channel` instead
  of `scope.launch { snapTo(...) }` per frame, and settle uses springs.
- All animations are standard Compose animations, so the system animator scale applies; at
  scale 0 the values settle immediately (verified in `McaMotionContractTest`).
- `Modifier.animateItem` is only applied at the `LazyColumn` item boundary that actually owns the
  item; grid animation is per-row because the existing grid is a chunked `LazyColumn` and changing
  it to `LazyVerticalGrid` would change layout.

## 4. Evidence

- Unit tests: app 2521, chat 346, settings 30, modelhub 63, agent 22, designsystem 44 (incl. the
  new motion/icon contracts) — all green.
- `scripts/verify-ui-motion.ps1` records `adb screenrecord` → ffmpeg frames → per-frame mean
  difference, writes `motion.json`/`RUN.md`, and refuses to pass without motion. Device self-tests:
  drawer PASS (6 motion frames, max 25.61), menu+model PASS (7 frames), static FAIL, animation
  scale 0 → NOT_RUN, unloaded-model send → BLOCKED.
- Round 5 on the installed 0.2.4 build: after the readiness fix (`8d1a0b08`) the script reports
  **PASS** for `drawer,menu` (11 motion frames, max 23.11) — evidence
  `artifacts/ui-motion/chat-20261019-060310-23468`.
- Round 5 drawer-back verification: with the restored `BackHandler` (`2fcc66c5`), a back gesture
  while the drawer is open closes the drawer and **stays in the chat** (`r5-drawer.png` before,
  `r5-back.mp4` during, `r5-after-back.png` after). Before the fix the same gesture exited to the
  previous app (`r4-after-back.png`).
- Dark mode: `r5-dark-chat.png`, `r5-dark-drawer.png`; large font (2.0): `r5-font20-chat.png`.
- `ChatMotionContractTest` pins the compositor-only translation and the shared spring/channel use.

## 5. Known limits

- Frame evidence proves pixels moved and the expected surface appeared; it does not prove curve
  quality, 120 Hz smoothness, or TalkBack order.
- Macrobenchmark cannot run on the current device: the OEM ROM rejects first-install of a new
  package (`INSTALL_FAILED_USER_RESTRICTED`). Reported as BLOCKED, never as FAIL.
- Release APKs must be produced by `scripts/release.ps1`, which passes
  `-Pmca.keepNativeSymbols=false`. A manual `assembleRelease` keeps native DWARF and inflates the
  APK (211.8 MB vs the stripped artifact), but does not change runtime behaviour.
- **Startup readiness (rounds 4–6, build 0.2.4):** activity first frame 420 ms, but the chat
  surface appeared at ~22 s on the first launch after an update and ~8.7–16 s on later cold starts.
  Round 6 attribution (release logcat + MIUI AppScout main-thread stack): the startup path itself
  is **266–383 ms**; the surface was held by **synchronous SHA-256 of the local image catalog on
  the main thread** — every cold process re-read 4.2 GB `dit.mnn.weight`, 4.5 GB `llm.mnn.weight`,
  483 MB `vae_decoder.mnn` and 370 MB `embeddings_int4.bin` (`hash … t=5927ms thread=main`, then
  7768 ms, then smaller ones), twice (worker + main). The in-process digest cache never survived a
  process restart.
  Fix: `ImageFileDigestStore` persists each component observation in `files/image-file-digests.json`
  using the **same** reuse key as the memory cache — size, mtime and the Linux `ctime/inode` change
  key — so an unchanged weight is verified without being read, while a same-length replacement whose
  mtime was restored still invalidates it (that exact guarantee is pinned by
  `LocalImageModelStoreIntegrationTest`). After the fix the device measured `TotalTime 217–219 ms`
  and the chat frame was visible in the first recorded frames (≤ ~0.5 s); the per-hash logs
  disappeared. Expect a one-time re-hash on the first launch after upgrading, because the old
  catalog rows carry no ctime and must be observed once before they can be reused.
  Verification (round 7, shipped persistent-store build):
  the first launch after the upgrade logs the one-time `hash-on-main dit.mnn.weight` + hashing pass
  (startup code path itself 1390 ms, then the background fill). The second and third cold starts
  log **no hash lines at all** and measure `am start -W TotalTime 220–224 ms` with the chat frame
  already present in the recording (`r7/run2.mp4`, `r7/run3.mp4`). Image catalog still intact on
  device: 生图 (4) lists Qwen-Image-2.1 (9.45 GB, 17 components) and Stable Diffusion Turbo
  (4.86 GB, 2 components) with the current selection preserved (`r7/image-models.png`).
  Full app unit tests: **2927 tests, 1 failure** — `McaGenerationForegroundServiceContractTest`,
  whose source file is being edited by the parallel agent session (their uncommitted change adds a
  second promote call; the contract counts 1). `LocalImageModelStoreIntegrationTest` passes
  26/26, including the same-length + restored-mtime invalidation case.
- **First-launch hashing is off the UI thread (round 8).** The residual one-time fill ran inside the
  generation collector's main-thread tool-turn handling (`handleAssistantImageToolCalls` →
  runtime verification → `LocalImageModelIdentityKt` → SHA-256; MIUI AppScout stack). That call is
  now wrapped in `withContext(Dispatchers.IO)` in `ChatExecutionOperations`, so the first image-proof
  computation still happens once but no longer freezes the surface. Compile-verified; the device
  first-launch scenario could not be re-created without clearing user data, and the round's test run
  was blocked by the shared build directory (parallel session held it for >6 minutes). Re-verify in
  a clean window.
- **Shared checkout: build under the MCA lock.** A parallel agent session runs
  `scripts/agent-verify.ps1`, which force-stops every `GradleDaemon`/`KotlinCompileDaemon` after
  taking `tools/build-guards/McaBuildLock.psm1`. A manual `gradlew` invocation that does not hold
  that lock is killed mid-build (observed three times inside R8/ksp). Hold the same lock around any
  long build; that made `:app:assembleRelease` complete in 11m26s on the same machine.

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
- `ChatMotionContractTest` pins the compositor-only translation and the shared spring/channel use.

## 5. Known limits

- Frame evidence proves pixels moved and the expected surface appeared; it does not prove curve
  quality, 120 Hz smoothness, or TalkBack order.
- Macrobenchmark cannot run on the current device: the OEM ROM rejects first-install of a new
  package (`INSTALL_FAILED_USER_RESTRICTED`). Reported as BLOCKED, never as FAIL.
- Release APKs must be produced by `scripts/release.ps1`, which passes
  `-Pmca.keepNativeSymbols=false`. A manual `assembleRelease` keeps native DWARF and inflates the
  APK (211.8 MB vs the stripped artifact), but does not change runtime behaviour.
- **Startup readiness (round 4 device measurement, build 0.2.4):** activity first frame 420 ms,
  but the chat surface appeared at ~22 s on the first launch after an update and ~8.7 s on the
  following cold start. This is the launch-screen duration users actually see and is the next
  performance target; the launch surface itself is already animated and phase-accurate.
- Device verification of the restored drawer back handler is pending a build window: the parallel
  agent session runs `scripts/release.ps1`, whose `gradlew --stop` terminates concurrent builds in
  this shared checkout.

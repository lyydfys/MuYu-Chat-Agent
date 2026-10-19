# Frontend completion report (V9)

Date: 2026-10-20 · Branch: `codex/enterprise-refactor` · Baseline: `1a6bc5a`

This is the short, user-facing answer to "how far is the frontend?" The detailed row-by-row matrix
and raw evidence live in `FINAL-MATRIX.md`, `MOTION.md` and `artifacts/`.

## 1. Numbers

| metric | before this work | final measured | evidence |
|---|---|---|---|
| perceived cold launch | 8.7–22 s on a 13 GB model library | **187–207 ms to ready**, 239–292 ms TotalTime | `McaStartup` timeline, 3 runs |
| one-time startup hashing | main thread, ~5.9 s + 7.8 s | never repeats (persistent digest cache) | `ImageFileDigestStore` + tests |
| mixed interaction frames | P95 13–16 ms, P99 22–36 ms | **PASS**: 727 frames, janky 0.96 %, P95 16 ms | `artifacts/frames/20261020-003015-16080` |
| settings scroll frames | not measured | **PASS**: 2330 frames, janky 0.21 %, P95 16 ms, 0 missed vsync | `artifacts/frames/20261020-003122-22288` |
| chat accessibility | not audited | **PASS**: 86 nodes, 17 interactive, 0 violations | `artifacts/a11y/20261020-001843-17172` |
| module tests | — | designsystem 46, chat 355, settings 32, modelhub 67, all green | test reports |

## 2. What changed (by workstream)

- **Launch**: the launch surface now shows one ambient signal, a truthful phase label without a fake
  percentage, real safe-drawing insets, and a single `McaStartup` timeline. Diagnostics no longer
  sit on the ready path.
- **Chat**: clickable Markdown links, tables with shared column widths (pure function + five tests),
  failures rendered as a short reason with the full diagnostic kept in the details dialog, an answer
  tail grouped at 12 dp / 4 dp with complete metric fields and tabular figures, copy confirmation
  driven by the real clipboard result, empty-composer send disabled and dimmed.
- **Motion**: shared press/enter/size primitives; entry, drag, cancel and dismiss of the back gesture
  share one animator owner and one position (the 8 % pre-jump is gone); the composer menu and
  reasoning panel grow with `animateContentSize` instead of hard cuts.
- **Images**: grid and single-preview images use Loading / Ready / Failed, with in-place retry that
  visibly returns to Loading.
- **Chrome and system**: header buttons unified on `McaIconButton` (bounded ripple, press scale,
  explicit disabled colours), send/edit/replay/add/pin/more glyphs optically corrected, composer
  shadow 14→10 dp, header capsules unified at 5 dp, system-bar writes tied to theme changes.
- **Settings / model hub**: five section tabs and all switch cards behave at 2.0x; logs empty state;
  release notes render as structured content; model hub tabs/import/save/status/delete follow the
  same rules; one filled primary on the local API page; contrast fixes for pills and active tabs.
- **Tooling**: `measure-frames.ps1` (gfxinfo lanes, honest PASS/FAIL/NOT_RUN/BLOCKED) and
  `verify-a11y.ps1` (touch targets, duplicate labels, missing names; merged Compose semantics
  handled). Both have been run on device; the a11y probe converges to 0 violations.

## 3. Known issues (honest, not hidden)

1. **Settings open-transition spikes**: short windows (open + 6 scrolls) fail the 16.7 ms budget by
   1–2 ms (P95 17–18 ms, P99 46–65 ms). Steady scrolling passes. The spike is the page composition
   inside the open transition, not sustained work.
2. **Long-table screenshot** not captured on device (needs a chat message containing a table); the
   layout rule is covered by unit tests.
3. **Composer menu at 2.0x** is capped at 520 dp and scrolls; not yet captured on device.
4. **Status severity** still derives from Chinese keywords in the composer path; making it structured
   requires a `statusMessage` type change across the app shell (owned by the parallel session).

## 4. Blocked by external work (not this frontend slice)

- `agent-workspace` acceptance: the parallel session is still editing it (35+ dirty files at the time
  of writing); its two `McaAgentLocalModelAdapterTest` failures are also theirs.
- **edge-to-edge**: adopting it changes insets and window geometry; needs a product decision.
- **Macrobenchmark**: the OEM ROM rejects first-install of a new package.

## 5. Verification commands

```powershell
# module gate
./gradlew :core:designsystem:testDebugUnitTest :feature:chat:testDebugUnitTest `
          :feature:settings:testDebugUnitTest :feature:modelhub:testDebugUnitTest

# device frame sample (needs a foreground chat)
./scripts/measure-frames.ps1 -DeviceSerial <serial> -Actions drawer,scroll,model
./scripts/measure-frames.ps1 -DeviceSerial <serial> -Actions settings -ScrollCount 20

# accessibility probe
./scripts/verify-a11y.ps1 -DeviceSerial <serial>

# release APK (holds the shared build lock)
powershell -File tools/_build_locked.ps1 -LogPath artifacts/build-final.log
```

## 6. Rejected experiment (recorded so it is not retried blindly)

Deferring the settings section content by one frame (to keep the heavy LazyColumn out of the opening
frame) was tried, built and measured on device: settings short-window P95 went **17–18 ms -> 19–22 ms**
and P99 46–65 -> 53–73 ms. Splitting the composition moved the cost into the middle of the fade and
created more janky frames, so the change was reverted and the build rebuilt. The known issue in
section 3 stays as documented.


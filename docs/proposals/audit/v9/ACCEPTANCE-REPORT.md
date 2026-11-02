# V9 frontend acceptance report

Scope: the frontend slice of `MCA-GEMINI-GRADE-PATCH-V9.md` — craft and perceived performance,
with the existing design language frozen (palette, type scale, spacing, radii, layout geometry,
wallpapers and the user/assistant bubble contracts).

Status as of round 11. Evidence paths are relative to `artifacts/` (git-excluded).

## 1. Performance

### Cold start (release, one physical device)

| stage | before | after |
|---|---|---|
| activity first frame | 397–420 ms | 250 ms |
| chat surface ready (code path) | 266–383 ms | 249–325 ms |
| **user-visible launch screen** | **8.7–22 s** | **≤ ~0.5 s** |

Root cause found in round 6 with release logcat + the OEM's own watchdog stack: every cold process
re-hashed the local image catalog on the main thread (dit 4.2 GB ≈ 5.9 s, llm 4.5 GB ≈ 7.8 s,
vae 483 MB, embeddings 370 MB; twice — worker and main). `ImageFileDigestStore` now persists each
observed digest keyed by size + mtime + the Linux ctime/inode change key, so unchanged weights are
never read again; a same-length replacement with a restored mtime still invalidates the digest
(`LocalImageModelStoreIntegrationTest`). The one-time fill after an upgrade runs on IO
(`ChatExecutionOperations` wraps `handleAssistantImageToolCalls` in `Dispatchers.IO`).

### Scrolling (release, before → after, per device)

| device | P50 | P90 | P95 | P99 | janky |
|---|---|---|---|---|---|
| device-b | 5 → 5 ms | 5 → 5 | 5 → 5 | 9 → 8 | 0.12 % → 0.12 % |
| device-a | 7 → 5 ms | 9 → 8 | 10 → 9 | 14 → 13 | 0.10 % → 0.19 % (1→2 frames) |

### Package size

The release APK is 212 MB, dominated by native inference stacks (SD 93.7 MB, QNN prepare 83.7 MB,
llama-common 72.3 MB, MNN 53.5 MB uncompressed) bundled for on-device inference. Manual
`assembleRelease` keeps DWARF (`ndk.debugSymbolLevel = "FULL"`); the release wrapper additionally
strips via `-Pmca.keepNativeSymbols=false`. Not a frontend lever, recorded for completeness.

## 2. Motion

One shared language (`core/designsystem/McaMotion.kt`): press spring 1.0/1100, content enter
0.9/380, size 0.86/420 (+ `IntSize` variant), page slide 1.0/760, fade helper.

- Shell: overlay tabs crossfade 200 ms; chat stays composed as the base layer so its view model,
  draft and scroll never reset; startup → app crossfade 220 ms.
- Drawer: staggered row entrance when the sheet crosses ~15 % visibility; back closes with the
  Material3 slide (a predictive-back attempt was reverted after device testing showed the activity
  exiting to the previous app).
- Chat: search/title size transition, gallery row animation, image preview scale+fade before
  dismiss, press weight on message actions/model pills.
- Modules: `animateItem` lists, animated expand/collapse, staggered cards, geometry-neutral status
  crossfade.

Device evidence: `scripts/verify-ui-motion.ps1` → **PASS** on drawer + menu (13 motion frames, max
28.36; `ui-motion/chat-20261019-092844-22864`). Contract tests pin compositor-only translation,
the conflated-channel swipe-back and the zero-animation-scale behaviour.

## 3. Visual craft

- Startup surface rebuilt: brand mark with breathing halo and orbiting sparks, chat-shaped skeleton
  with travelling highlight, determinate phase progress with real phase labels and percentage,
  slow-start notice card with 44 dp actions. Verified at 1.0/2.0 font scale and in dark mode.
- Icons: Material Rounded across chat/settings/modelhub/agent; `McaIconButton` (48 dp target, bounded
  ripple, press weight, required accessible name, optional tonal selected state).
- Drawer: search capsule, section labels, 44 dp rows, list animations.
- Settings: release notes render as structured sections/bullets/paragraphs instead of raw Markdown
  (`r11/settings-notes-final.png`); dark mode verified. Round-12 craft fixes: the web-search enable
  switch sits on the title row so it never overlays the description, the log actions use
  Refresh/Description glyphs instead of two identical save icons, and provider/trigger chips wrap
  instead of clipping at the right edge (`r12/settings-websearch-chips.png`,
  `r12/settings-logs-fixed.png`). Round-14: the five section tabs wrap through FlowRow so none is
  clipped at 1.0/2.0 font scale, the header reserves the gear slot, and the update/privacy switch
  cards keep their switch on the title row (`r14/settings-font20-final.png`,
  `r14/settings-update-card-fixed.png`, `r14/settings-privacy-fixed.png`). The logs/diagnostics tab now has a centred empty state (icon,
  title, one-line hint) instead of a blank page (`r13/logs-empty-state.png`).
- Dark mode / large font evidence: `r2/after-device-b-dark-main.png`, `r5/r5-dark-chat.png`,
  `r5/r5-dark-drawer.png`, `r5/r5-font20-chat.png`, `r11/agent-light.png`,
  `r11/agent-dark.png`, `r11/agent-font20-dark.png`.

## 4. Tests

| module | tests | result |
|---|---|---|
| app | 2927 | 0 failures (run by the parallel session on this working tree) |
| feature:chat | 346 | 0 failures |
| feature:settings | 30 | 0 failures (incl. 429-string surface golden) |
| feature:modelhub | 63 | 0 failures |
| feature:agent | 22 | 0 failures |
| core:designsystem | 44 | 0 failures (motion/icon contracts) |

New contract tests added by this slice: `McaMotionContractTest`, `McaIconButtonContractTest`,
`MainShellMotionContractTest`, `InlineMarkdownStabilityTest`, `ChatProjection*Test`,
`ChatSessionTitleReuseTest`, `ChatMotionContractTest` updates.

## 4b. Round-15 review follow-ups (rich text, states, tail, startup)

- **Rich text**: Markdown links and bare URLs now carry `LinkAnnotation.Url` while keeping their
  blue/underline span, so the styled text opens the target; table columns share one width per column
  (widest cell) instead of sizing every cell independently, so rows line up.
- **Failure presentation**: the assistant body receives a short, internals-free reason
  (`failureSummaryLine`) instead of the raw worker/runtime diagnostic; the full message stays in the
  status card and its 错误详情 dialog, so history, export and diagnostics keep their data.
- **Composer**: an empty composer without an attachment no longer presents an enabled send action —
  the FAB is disabled and dimmed (the stop state still works while generating, preflight still
  disables it). Copy confirmation is driven by the successful clipboard write: the copy glyph swaps
  to a check for ~1.4s with unchanged geometry (`r15/copied-check.png`).
- **Answer tail**: metrics → sources → actions form one group with a 4dp inner rhythm while the 12dp
  body boundary is untouched; metrics render as complete fields (`上行 315 tokens`, `下行 9 tokens`,
  `0.4 tok/s`, `用时 1分43秒`) in a wrapping row with tabular figures, so a number never separates
  from its unit and no orphan `·` starts a line (`r15/chat-tail.png`).
- **Image states**: generated-image tiles distinguish Loading / Ready / Failed; a failed decode shows
  an in-place retry instead of keeping "图片加载中…" forever.
- **Startup**: only one ambient signal remains (skeleton sweep); brand breath/orbit and the progress
  sweep are removed, and the fixed phase weights are no longer presented as a percentage
  (`r15/startup-220.png`). The phase label still reflects real work.
- **Chrome/motion**: history/new-chat/menu use `McaIconButton` (bounded ripple + press scale),
  `McaIconButton` has explicit disabled tint/container, and the app-menu row uses a vector arrow.
  `SwipeBackPage` and the app menu now share one animator owner and one position source: per-frame
  progress posts to a conflated channel, the 8% jump is gone, cancelled gestures resume from the
  current offset, and repeated backs dismiss once.
- **Verification**: `:app:testDebugUnitTest` 2970 tests / 0 failures, `:feature:chat` and
  `:core:designsystem` suites green; release install verified on the validation device. Motion
  capture after the transition rework: PASS, 16 motion frames, `maxFrameDiff 24.05`
  (`ui-motion/chat-20261019-134452-47080`); the `send` action was NOT_RUN because the chat model is
  unloaded on the validation device.

The 360 dp / 2.0x-font check now passes: metrics wrap as complete fields and the model capsule
keeps two clean ellipsised lines instead of a vertical character stack
(`r15/chat-360dp-font20-capsule.png`).

Round-16 surface and motion numbers:
- surface weight: composer shell 14dp -> 10dp, the three header capsules share 5dp, and the overflow
  glyph is 20dp against 18dp copy/replay for optical balance
  (`r16/shadow-before-light.png` -> `r16/shadow-after-light.png`, `r16/shadow-after-dark.png`).
- frame sample on the same build (3 drawer open/close, model sheet, 6 scrolls): 714 frames, 7 janky
  (0.98%), P50 7 ms, P90 11 ms, P95 13 ms, P99 22 ms, 2 missed vsync
  (`ui-quality/r16/gfxinfo.txt`).

Round-17 icon optics (zoomed before/after in `r17/`): the send glyph shifts 2dp right so its ink
mass sits on the FAB centre, the header pencil uses the outlined Edit glyph to match the menu/history
stroke weight, and the replay glyph is 19dp against the fuller 18dp copy shape. App unit tests:
2985 / 0 failures.

Round-18 closes the glyph pass: the composer add uses the outlined plus, and pin actions are
outlined while unpinned and filled/primary-tinted while pinned, so the state is visible in the glyph
(`r18/add-button-after.png`, `r18/user-pin-copy-after2.png`).

Still open: the agent-workspace acceptance work owned by the parallel session and the edge-to-edge
decision (a product choice, not a code blocker).

## 5. Blocked / deferred

- **Macrobenchmark**: dependencies resolve, but the OEM ROM rejects first-install of a new package
  (`INSTALL_FAILED_USER_RESTRICTED`). Recorded as BLOCKED, never as FAIL.
- **Edge-to-edge**: design-system produced a plan; adopting it changes insets and window geometry,
  which the frozen visual contract does not authorise. Deferred as a product decision.
- **First-launch one-time hash**: moved to IO; the device re-verification needs a clean data state
  (not done — it would destroy user data).
- **Agent workspace page**: currently owned by a parallel session (`feature/agent-workspace`); the
  empty state has a tight label/switch gap at the default font (observed, not edited here).

## 6. How to reproduce

```pwsh
# signed arm64 release, must hold the shared MCA build lock or the parallel session kills the daemon
powershell -File scripts/release.ps1   # holds the shared MCA build lock itself
adb install -r app/build/outputs/apk/release/app-release.apk

# motion evidence (temporarily enables animation scales, restores them)
./scripts/verify-ui-motion.ps1 -DeviceSerial <serial> -Actions drawer,menu -EnableAnimationScale

# startup attribution
adb logcat -s McaStartup
```

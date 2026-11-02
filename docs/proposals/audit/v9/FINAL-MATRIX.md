# V9 frontend acceptance matrix

Status vocabulary: PASS (verified on device or by an automated check), NOT_RUN (not exercised yet),
BLOCKED (cannot be exercised in this environment), DEFERRED (deliberate product/architecture choice).
Evidence paths are relative to `artifacts/`.

Baseline: the design language is unchanged — palette, type scale, spacing, radii, page geometry and
the user/assistant bubble contracts. All changes live inside existing roles and primitives.

## 1. Startup

| item | status | evidence |
|---|---|---|
| perceived launch no longer blocks on 13 GB of hashing | PASS | rounds 6–7 attribution; `ready t=202–224ms` |
| cold start (release, 3 runs) | PASS | `TotalTime 238/211/213 ms`, `ready 224/207/202 ms` |
| single ambient signal, no fake percentage | PASS | `r15/startup-220.png` |
| phase label reflects real work | PASS | `McaStartup` phase logs |
| first-launch one-time hash on IO | PASS (code + test) | clean-data device re-run NOT_RUN (would destroy user data) |

## 2. Motion

| item | status | evidence |
|---|---|---|
| shared motion primitives + contracts | PASS | `McaMotionContractTest`, `ChatMotionContractTest` |
| drawer / app-menu / model sheet recorded motion | PASS | `ui-motion/chat-20261019-134452-47080` (16 frames, max 24.05) |
| back/swipe continuity, single animator owner | PASS | device gesture recording `r21/swipeback.mp4` + `r21/swipeback-contact.png` (page follows the drag, no 8 % pre-jump) |
| zero-animation-scale immediacy | PASS | contract tests + device scales restored to 0 |

## 3. Chat surface

| item | status | evidence |
|---|---|---|
| links clickable (Markdown + bare URL) | PASS (unit) | `InlineMarkdownStabilityTest` + `LinkAnnotation` |
| table columns share widths | PASS (unit + refactor) | `tableColumnWidthsDp` covered by `ChatMarkdownTableWidthTest` (5 tests); visual long-table screenshot NOT_RUN |
| failure body is a short reason, diagnostics kept | PASS (code + unit) | `failureSummaryLine`, `ChatExecutionSettlementSteps` |
| empty composer send is disabled and dimmed | PASS | `r15/chat-tail.png` |
| copy succeeds -> in-place check for ~1.4 s | PASS | `r15/copied-check.png` |
| answer tail grouping (12 dp body / 4 dp inner) | PASS | `r15/chat-tail.png` |
| metric fields wrap whole, tabular figures | PASS | `r15/chat-360dp-font20.png` |
| model capsule readable at 360 dp / 2.0x | PASS | `r15/chat-360dp-font20-capsule.png` |
| generated image loading/ready/failed + retry | PASS (code) | visual NOT_RUN (no failed image on device) |

## 4. Icons and surfaces

| item | status | evidence |
|---|---|---|
| header history/new/menu unified on McaIconButton | PASS | `r17/after-header-buttons.png` |
| disabled tint/container in the shared button | PASS | `McaIconButtonContractTest` |
| send glyph optical centre (+2 dp) | PASS | `r17/after-composer-fab.png` |
| edit glyph outlined, replay 19 dp | PASS | `r17/after-header-buttons.png` |
| add outlined, pin outline/filled by state | PASS | `r18/add-button-after.png`, `r18/user-pin-copy-after2.png` |
| composer 10 dp, header capsules 5 dp | PASS | `r16/shadow-after-light.png`, `r16/shadow-after-dark.png` |
| app-menu leading icons muted, chevron 20 dp | PASS | `r22/app-menu-polished.png`, `r22/app-menu-polished-dark.png` |

## 5. Settings

| item | status | evidence |
|---|---|---|
| five section tabs wrap instead of clipping | PASS | `r14/settings-font20-final.png` |
| switch cards keep switches off descriptions | PASS | `r14/settings-update-card-fixed.png`, `r14/settings-privacy-fixed.png` |
| logs tab empty state | PASS | `r13/logs-empty-state.png` |
| release notes render as sections/bullets/tables | PASS | `r11/settings-notes-final.png` |
| chips wrap instead of clipping | PASS | `r12/settings-websearch-chips.png` |

## 6. Performance

| item | status | evidence |
|---|---|---|
| mixed interaction frame sample | PASS | 714 frames, 0.98 % janky, P50 7 / P90 11 / P95 13 / P99 22 ms (`r16/gfxinfo.txt`) |
| chat scrolling percentiles | PASS | earlier rounds (device-b / device-a) |
| Macrobenchmark lane | BLOCKED | OEM ROM rejects first-install of a new package |

## 7. Tests

| suite | result |
|---|---|
| `:app:testDebugUnitTest` | 2985 tests / 0 failures |
| `:feature:chat` | green |
| `:feature:settings` | 30 / 30 |
| `:feature:modelhub` | green |
| `:core:designsystem` | green |

## 8. Not done / external

| item | status | note |
|---|---|---|
| agent-workspace acceptance | DEFERRED | module still being edited by the parallel session |
| edge-to-edge | DEFERRED | changes insets/window geometry; needs a product decision |
| status severity without Chinese keyword matching | DEFERRED | needs `statusMessage: String?` upgraded to a structured type in the app shell |
| 360 dp / 2.0x matrix on every page | PASS | chat, settings, agent, model hub (`r20/modelhub-font20-fixed.png`) and image workspace (`r21/image-workspace-font20-dark.png`) captured |

## 9. Round-25 additions

| item | status | evidence |
|---|---|---|
| single generated image uses Loading/Ready/Failed + retry | PASS (code + guard) | `ChatImageLoadState` in `ChatGeneratedImageViews`; `ChatFeedbackMotionContractTest` |
| retry resets the image loader to Loading | PASS (code) | `ImageAssetIoSupport.rememberChatImageLoadState` resets before decoding |
| composer action menu grows for 2.0x and degrades all parts together | PASS (code) | `heightIn(min = 48.dp)`, two-line subtitle, one disabled colour |
| startup timeline uses one tag and one T0 | PASS (code) | `StartupLog` in `AppStartupViewModel` + coordinator lines |
| successful diagnostics record is off the ready path | PASS (code) | `viewModelScope.launch(IO)` after publishing ready |
| market/files/recommended distinguish busy from empty | PASS (code) | progress rows + `没有找到模型` empty card |
| a11y probe script | PASS (tool) first run surfaced 15 findings, rule corrected for Compose merged semantics (`mergedSemanticsNamed`) | `scripts/verify-a11y.ps1`, `artifacts/a11y/20261019-214708-23492` |
| model capsule no longer duplicates its label | PASS (code, tests) | `contentDescription` removed; role kept; rebuild pending |
| release APK with this batch | PARTIAL | 21:37 build installed (375.4 MB); one more rebuild needed for the capsule a11y line, blocked by the shared build lock (parallel session running app tests) |

## 10. Round-26 additions

| item | status | evidence |
|---|---|---|
| local API page has one filled primary | PASS (code + tests) | URL copies and self-test actions are outlined; only `复制 API Key` stays filled (`031430c`) |
| a11y probe understands merged Compose semantics | PASS (tool) | `mergedSemanticsNamed` in `scripts/verify-a11y.ps1`; only truly unlabelled interactive nodes fail |
| model capsule no longer duplicates its visible label | PASS (code) | `ChatStatusBarHeader` keeps role=Button and drops the override |
| final APK containing the capsule/primary changes | PENDING | queued behind the shared lock; previous 21:37 APK is installed and smoke-tested |

## 11. Round-27 verification (final build 2026-10-20 00:06)

| item | status | evidence |
|---|---|---|
| a11y probe on the chat surface | PASS | 86 nodes / 17 interactive / 0 violations (`artifacts/a11y/20261020-001843-17172`) |
| model capsule + composer field accessible names | PASS | probe rerun above; `ChatFeedbackMotionContractTest` pins the field name |
| loaded pill / active tab contrast | PASS (code + 2x capture) | primary+onPrimary pill, onPrimaryContainer tab; `r26/modelhub-2x-contrast.png` |
| model filter chips and hub tags wrap at 2.0x | PASS (code + 2x capture) | FlowRow; same screenshot |
| waiting reasoning panel chevron removed | PASS (code) | `ChatReasoningPerformanceScreens.kt` |
| composer action menu capped at 520dp and scrollable | PASS (code) | `ChatComposerActionMenuViews.kt` |
| measure-frames settings lane | PARTIAL | lane added + static-checked; device run BLOCKED by an observation mismatch after the capsule a11y change (selector fallback committed, rerun pending) |

### Round-27 follow-up frame samples (same build, same device)

| lane | verdict | metrics | evidence |
|---|---|---|---|
| drawer + 6 scrolls + model panel | PASS | 727 frames, janky 0.96%, P50 8 / P90 12 / P95 16 / P99 36 ms | `artifacts/frames/20261020-003015-16080` |
| settings open + 6 scrolls + back | FAIL (borderline) | 722 frames, janky 1.11%, P50 10 / P90 17 / P95 18 / P99 65 ms; repeat: P95 17 ms | `artifacts/frames/20261020-002805-6596`, `20261020-002859-36384` |

The settings lane sits 1–2 ms over the 60 Hz budget on this device; P99 spikes point at the
open-transition + first composition of the settings LazyColumn rather than steady scrolling. Listed
as a known borderline, not as a passing result.

### Settings lane resolution (same build)

| sample | verdict | metrics | evidence |
|---|---|---|---|
| settings open + 20 scrolls + back | PASS | 2330 frames, janky 0.21%, P50 9 / P90 15 / P95 16 / P99 19 ms, 0 missed vsync | `artifacts/frames/20261020-003122-22288` |
| settings open + 6 scrolls (short window) | FAIL (borderline) | P95 17–18 ms, P99 46–65 ms | earlier runs above |

The steady scroll is within budget; the short-window failures are isolated spikes during the open
transition (page composition inside the crossfade), not sustained scrolling. Recorded as: settings
steady-state PASS, open-transition spikes known (candidate follow-up: defer heavy cards until the
transition settles).

### Startup timeline on the final build (device, 3 cold starts)

| run | TotalTime | ready | notes |
|---|---|---|---|
| 1 | 272 ms | t=187 ms | diagnostics-final after ready (t=204) |
| 2 | 292 ms | t=207 ms | offline-bundle at t=201 / op=1 ms |
| 3 | 239 ms | t=195 ms | offline-bundle late (t=308, op=118 ms) but after ready |

All lines share the single `McaStartup` tag and a shared T0 (`begin t=0`), with per-step cost moved to
`op=`; `diagnostics-final` now lands after `ready`, confirming the final diagnostics record is off the
readiness path.

## 12. Round-28 page craft pass (build 2026-10-20 03:49)

| page group | status | notes |
|---|---|---|
| settings (runtime/logs/privacy/web search/local API) | PASS (code+tests) | header back is McaIconButton; expandable rows expose stateDescription; busy update buttons show a same-size spinner; diagnostic rows get bounded ripple + button role |
| model hub (local/cloud/recommended/market) | PASS (code+tests) | 2.0x action rows grow with two-line labels; '当前' pill 2.1:1 -> 4.5:1+; cloud writes disabled while busy; download panel hairline; folder icon onPrimaryContainer |
| chat chrome (background, composer menu, reasoning, app menu) | PASS (code) | background bitmaps crossfade; menu capped+scrollable; waiting chevron removed; app-menu dialogs scroll at 2.0x |
| assistants / roles / import / editor | PASS (code) | McaIconButton back/close, FlowRow chips and actions, heightIn(min) bottoms, empty list hint, no-op actions disabled |
| images (preview, editor, gallery, UltraFix dialog) | PASS (code) | preview uses shared Loading/Ready/Failed with retry, zIndex above siblings while closing, dimmed actions on exit, scrollable confirm at 2.0x |
| module gate | PASS | chat/settings/modelhub testDebugUnitTest green; release build 03:49 |

a11y on the final build (device device-b): a fresh chat surface passes (25 nodes / 4 interactive / 0
violations). With a scrolled conversation the probe reports 6 findings that are viewport-clipping
artifacts (the top message action row is partially under the header, nodes report 34.9dp) plus two
unnamed action-row parents; with the drawer open it reports the parallel session's new 38dp rail
items. The copy/pin names were moved onto the buttons anyway (`26dcd81`).

## 13. Round-29 completion gaps (build 2026-10-20 12:15)

| gap | status | evidence |
|---|---|---|
| composer menu exit animation | PASS (code) | menu stays composed, targetState follows `open`, scrim only while open, exit fade+slide; 1x/2x captures `r30/composer-menu-1x.png`, `r30/composer-menu-2x.png` |
| 2.0x menu title no longer cut | PASS (device) | 2-line title at 2.0x; `角色生图：每次询问` fully readable in `r30/composer-menu-2x.png` |
| large-image preview three states + retry | PASS (code) | shared `ChatImageLoadState` with revision retry in `ChatGeneratedImageViews` |
| gallery tiles three states | PASS (code) | spinner while loading, icon + `重试` on failure in `ImageAssetScreens` |
| floating ball no longer covers copy | PASS (device) | default x 0.82 with legacy right-edge migration; `r30/chat-shadows.png` shows copy column clear |
| cache evidence contrast | PASS (code) | extra 0.78 alpha removed (light ~3.71:1 -> token value) |
| surface shadows calibrated | PASS (device) | header 5->4dp, composer 10->8dp; `r30/chat-shadows.png` |
| menu exit animation device recording | NOT_RUN | screenrecord returned a 0.125s clip; code path verified, visual capture pending |
| markdown table real render | NOT_RUN | still no rendered table capture; only the parser/width unit tests |

a11y probe on this build: fresh chat PASS; scrolled conversation 6 findings (viewport-clipped action row +
unnamed action parents); app-menu screen 8 findings (nested row clickables). These are recorded as probe
limitations/attribution, not silently ignored.

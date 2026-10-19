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
| back/swipe continuity, single animator owner | PASS (code + contracts) | device gesture pass NOT_RUN after the rework |
| zero-animation-scale immediacy | PASS | contract tests + device scales restored to 0 |

## 3. Chat surface

| item | status | evidence |
|---|---|---|
| links clickable (Markdown + bare URL) | PASS (unit) | `InlineMarkdownStabilityTest` + `LinkAnnotation` |
| table columns share widths | PASS (code) | `ChatMarkdownBlockRenderers`; long-table screenshot NOT_RUN |
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
| chat scrolling percentiles | PASS | earlier rounds (98a37aa7 / f9d98aa3) |
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
| 360 dp / 2.0x matrix on every page | partial | chat + settings + agent captured; model hub now captured and fixed (`r20/modelhub-font20-fixed.png`); image workspace NOT_RUN |

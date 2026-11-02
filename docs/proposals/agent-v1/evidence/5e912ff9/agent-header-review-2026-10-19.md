# Agent Home Header Review — 2026-10-19

Status: **read-only review. No code, build, or device action was performed.** All "current" values are
either exact layout boxes from the reviewed source or measured from render evidence; both are labelled.

Scope requested: `AgentWorkspaceHomeScreen.kt`, `AgentHomeDrawer.kt`, `AgentHomeComposer.kt`,
`docs/proposals/agent-v1/evidence/5e912ff9/agent-home-optimized-2026-10-19.png`, compared against
`feature/chat/.../ChatStatusBarHeader.kt`. The claim under review: "48 dp circular Surface menu at
18 dp start, 14 dp top / 10 dp bottom, plus an `Agent` title; body moved up by removing
`statusBarsPadding`."

---

## 0. Evidence status — the named screenshot is stale (read this first)

| File | mtime | What it actually shows |
| --- | --- | --- |
| `agent-home-release-2026-10-19.png` | 22:50:21 | pre-change home |
| `agent-home-optimized-2026-10-19.png` (**the file named in the request**) | 23:00:45 | **pre-change header**: bare 22 dp glyph, **no** 48 dp Surface, **no** white header bar, **no** "Agent" title, body still below a duplicated status-bar inset |
| `agent-header-optimized-2026-10-19.png` | **23:40:41** | post-change header (current source) — used below |
| `agent-drawer-optimized-2026-10-19.png` | **23:41:18** | post-change drawer (current source) — used below |

`AgentWorkspaceHomeScreen.kt` mtime is **23:18:51**, i.e. 18 min *after* the named screenshot. In that
screenshot the menu ink centre is at y≈92.4 dp versus y≈66.0 dp in the 23:40 render: the old capture
still contains the extra status-bar inset that this change removed. **It cannot accept or reject the
reviewed header.** The 23:40/23:41 renders post-date the source edit and match the source to ≤1 dp
(menu ink, bar height, title ink, list pitch all agree), so they are the valid render evidence.
None of the four PNGs is referenced by any `.md` in the repository (grep, 2026-10-19); add them to a
manifest with revision/device/scale or they will drift again.

Geometry calibration used below: both post-change images are 2136×3200 px at ≈**2.78 px/dp**
(768 dp-wide window; solved from the rendered bar 66 dp ↔ 184 px, the list pitch 72 dp ↔ 200 px, and
the menu 18 dp start / 48 dp box ↔ 50 px ink at x=91 px). Status-bar inset = **32 dp**;
navigation-bar inset = **≈16 dp** (drawer sheet bottom and composer bottom gaps both resolve to it).

---

## 1. Measured geometry: Agent home vs the Chat header

Chat values are exact layout boxes from `ChatStatusBarHeader.kt` plus computed Roboto baselines
(±1 dp); no Chat render was available, so they are *not* pixel-measured here.

| Property | Agent home (render 23:40) | Chat header (source) | Delta |
| --- | --- | --- | --- |
| Top bar top edge | 32.4 dp (status bar only) | 32 dp (same host) | 0 |
| Top bar height | **65.4–66 dp** (`10+48+8`) | **76 dp** (`14+52+10`; right cluster is 52 dp) | **−10 dp** |
| Vertical padding in source | **10 / 8 dp** | **14 / 10 dp** | −4 / −2 |
| Menu control | 48 dp Surface at x=18, y=10 | 48 dp Surface at x=18, y=16 (centred in 52 dp) | — |
| Menu ink (24 dp `Menu`) | x 32.7–50.3, y 60.1–71.9 dp; centre **(41.5, 66.0)** | centre **(42, 72.4)** | **menu 6.4 dp higher** |
| First text left | **78 dp** (ink 77.7) | **120 dp** (capsule at 78 + 10 pad + 24 logo + 8) | **42 dp left** |
| First text style | `titleMedium` **16 sp / 24 sp**, SemiBold | 13 sp / 15 sp, Semibold | +3 sp |
| First-line baseline | ≈**72.3 dp** abs (cap ink centre 66.35 = menu centre 66.0) | ≈**70.4 dp** abs (computed) | +1.9 dp |
| Right-side content | **none** (empty 124 → 750 dp) | model capsule + new/edit cluster | — |
| Body container top | **+4 dp** list `contentPadding` (then 20 dp section top) | **+26 dp** list `contentPadding` | −22 dp |
| First body text | section-label ink at **+27.7 dp** below bar (abs 126.1 dp) | first message/card at +26 dp below bar (abs 134.4 dp) | **−8.3 dp absolute** |
| First task row | **+60 dp** below bar = abs 158.4 dp (4+48+8) | first message item +26 dp = abs 134.4 dp | **+24 dp lower** |

The 66 dp bar, 78 dp title origin, 18 dp start, 48 dp control and 10/8 padding are all confirmed by the
render; they also confirm that the implementation **does not match the stated 18/14/10 grid** — the
code comment at `AgentWorkspaceHomeScreen.kt:224-225` claims it does.

---

## 2. Findings, ranked

### H1 (P1) — The bar is one Chat padding step short, so the whole header sits 6–10 dp high

`AgentWorkspaceHomeScreen.kt:221` uses `padding(start=18, end=18, top=10, bottom=8)`; the claimed and
Chat value is `14 / 10` (`ChatStatusBarHeader.kt:84`). Consequences, all render-confirmed or exact:

- bar **66 dp** vs Chat **76 dp** (Chat's 52 dp right cluster makes its row taller than its 48 dp menu);
- menu centre **66.0 dp** from the window top vs Chat **72.4 dp** → the hamburger jumps **≈6.4 dp up**
  when switching Chat → Agent;
- the header/body seam sits at **98.4 dp** vs Chat **108.4 dp** → the body starts **10 dp higher**;
- the code comment says "18/14/10 grid", which is false and will mislead the next edit.

Fix: `top = 14.dp, bottom = 10.dp` (bar 72 dp) and correct the comment. If *exact* Chat parity is
wanted, the 52 dp Chat row means **16 / 12** (bar 76 dp, menu centre 40 dp from bar top); 14/10 still
leaves a 2 dp menu and 4 dp body residual.

### H2 (P1) — Drawer header is 40 dp below the home header: second status-bar inset + 8 dp extra padding

The drawer sheet is created inside the already padded host
(`MainActivity.kt:290-291` Scaffold → `Box(Modifier.padding(padding))`), but
`AgentWorkspaceHomeScreen.kt:100` lets `ModalDrawerSheet` keep its default
`windowInsets = DrawerDefaults.windowInsets`, which material3 1.4.0 defines as
`systemBarsForVisualComponents.only(Vertical + Start)`. The drawer therefore re-applies the top
(32 dp) and bottom (16 dp) system insets inside a host that already applied them.

Render proof (drawer 23:41): sheet starts at 32.4 dp (host padding) and the "MCA" brand row content
centre is at **106.3 dp**; home menu centre is **66.0 dp** → **40.3 dp lower**. The delta decomposes
exactly as 32.4 dp duplicated top inset + 7.9 dp (drawer `top=18` vs header `top=10`). The drawer
footer is also pushed up: last footer ink ends 33 dp above the sheet bottom because the sheet's own
16 dp bottom inset stacks on top of the row's 14 dp padding.

Fix: pass `windowInsets = WindowInsets(0, 0, 0, 0)` to the Agent `ModalDrawerSheet`
(add `androidx.compose.foundation.layout.WindowInsets`) and change `DrawerBrandHeader` top padding
18 → 10 (`AgentHomeDrawer.kt:262`). Then brand-row centre = 32.4 + 10 + 24 = 66.4 dp = home menu centre.
Note: Chat's history drawer uses the same default (`ChatHistoryDrawerScreens.kt:224`) and is even
worse at the bottom (it adds `.navigationBarsPadding()` at `:240`); fixing Agent only makes Agent
correct and Chat's drawer a separate, pre-existing issue.

### H3 (P1) — Same single-owner break on the task detail bar: opening a task adds a second 32 dp

`AgentTaskDetailScreen.kt:184` calls `.statusBarsPadding()` on a screen that is already inside the
host Scaffold (the home render proves the Scaffold supplies the 32 dp top inset). Opening a task
therefore drops the detail header by another **≈32 dp** relative to the home header, and the code
contradicts the new home behaviour. This is the "double inset risk" in its most visible form; it is
code-provable but **NOT_RUN** here (no detail render). The earlier suggestion in
`frontend-parity-review.md:218` to add `statusBarsPadding()` is wrong for this host and should be
corrected when the detail bar is touched. Remove that one line (keep the 56 dp min height and the
`horizontal = 4` padding only if the 14 dp left jump versus home is intended).

### H4 (P2) — The hamburger reads as a floating disc, not as a header control

- The Surface colour equals the bar colour: light `surface = #FFFFFF` on `surface = #FFFFFF`
  (`McaTheme.kt:58`), dark `#202124` on `#202124` (`:101`). The fill is invisible in both themes; the
  only cue is the 5 dp shadow ring (light) or almost nothing (dark), which is exactly what the 23:40
  render shows (white disc + soft ring, no boundary).
- In Chat the identical 48 dp circle is drawn before the 42 dp capsule and the 52 dp right cluster, so
  those later white surfaces cover its shadow and the row reads as one object. In Agent the next
  element is 12 dp of air and then text, so the full ring is exposed → "detached".
- `Icons.Filled.Menu` (`:239`) vs Chat's `Icons.Rounded.Menu` (`ChatStatusBarHeader.kt:103`): square vs
  rounded bar ends; the same affordance has two glyph families.
- The comment's "no visible hit surface" claim is not met by this change: the old build already had a
  48 dp `McaIconButton` target; the new Surface adds only the shadow, not a visible surface.

Fix options (pick one, then verify in light + dark): (a) drop `shadowElevation` and keep the 48 dp
target bare; (b) 1 dp `outlineVariant` border instead of shadow; (c) `surfaceVariant` /
`surfaceContainerLow` fill. Also switch to `Icons.Rounded.Menu`.

### H5 (P2) — Title scale/weight, not its baseline

Measured title ink: x **77.7–122.6 dp**, y **60.4–75.5 dp**; cap ink 60.4–72.3 dp. The cap-ink centre
(66.35 dp) coincides with the menu ink centre (66.0 dp) within 0.4 dp, so **there is no baseline
defect at 1.0×**: the 24 sp line box is centred in 48 dp and the "g" descender is the only part below
centre (normal). Two real issues remain:

- **Scale**: 16 sp SemiBold vs Chat's 13 sp/15 sp SemiBold name and the DSH reference's 14–15 sp
  (`DSH_UI_PARITY_MATRIX.md:76,101`). The title is the only text in the bar and reads as a page
  heading; its ink is ≈50 % taller than the Chat name.
- **No shared text grid**: 78 dp = exactly the Chat capsule's *outer* edge (18+48+12), which is a clean
  grid, but Chat's *text* starts at 120 dp. The title's baseline (72.3 dp) is 1.9 dp lower than
  Chat's model-name baseline (70.4 dp, computed), and at ≥1.5× Chat hides its logo so its text moves
  to 88 dp — leaving the Agent title only 10 dp to the left at large font.

Recommendation: keep 78 dp (it is the only defensible grid) and use **14 sp / 20 sp, Medium (not
SemiBold)**; if text-grid parity with Chat is required instead, move to 120 dp and accept the larger
tap-to-text gap.

### H6 (P2) — Body top rhythm is split between two systems

The bar's 8 dp bottom + the list's **4 dp** top (`AgentWorkspaceHomeScreen.kt:167`) + the section
header's 20 dp top (`AgentWorkspaceComponents.kt:92`) give a section-label ink 27.7 dp below the bar
(render), while the first task row is 60 dp below it (4 + 20 + 20 + 8 + 8). Chat's list uses 26 dp top
and 18 dp horizontal (`ChatMessageListSection.kt:117-126`), so the Agent label sits **≈8 dp higher**
in absolute terms than Chat's first content, while its first actionable row sits **≈24 dp lower**
(158.4 dp vs 134.4 dp). The two screens do not share a top rhythm; restoring the Chat bar height
(H1, +10 dp) alone would move the label to ≈1.7 dp *below* Chat's first content and the first row to
≈34 dp below it, so the rhythm has to be fixed together with the bar or not at all.
Not a "too tight" bug in the empty state (that is centred, `:154-159`), but it should be normalised
(suggest list top 8 dp + section top 12 dp, or adopt Chat's 26 dp list top).

### H7 (P2) — Drawer width cap is ignored: 553 dp on a 768 dp window

`fillMaxWidth(0.72f).widthIn(max = 300.dp)` (`AgentWorkspaceHomeScreen.kt:102-104`) renders at
**552.6 dp = 72.0 %** of this window, i.e. the 300 dp cap has no effect (modifier order: the fixed
fraction constraint wins). The drawer occupies three quarters of the screen and leaves ≈215 dp of
page. Fix by computing the width (`min(0.72 * maxWidth, 300.dp)` via `BoxWithConstraints`) rather
than chaining `fillMaxWidth` before `widthIn`, then re-render 360 dp and 768 dp.

### H8 (P3) — Drawer/header/first-level left insets are 16/18/20 dp

| Surface | Left inset | Source |
| --- | --- | --- |
| Home menu control | 18 dp (ink 32.7) | `AgentWorkspaceHomeScreen.kt:221` |
| Home title | 78 dp | `:245-251` |
| Drawer brand mark | 20 dp (ink 25.9) | `AgentHomeDrawer.kt:262` |
| Drawer new-task button | 16 dp (render 15.8) | `AgentHomeDrawer.kt:92` |
| Drawer rows / workspace label | 20 dp (render 20.9) | `:308,343,375,401` |

2–4 dp inconsistencies; the 16 dp button is visibly left of the 20 dp rows. Normalise the drawer to
20 dp (button + rows + brand) and leave the home bar at 18 dp only if Chat retains 18 dp; the title at
78 dp is intentional.

### H9 (P3) — Evidence hygiene

No manifest, no device/scale record, and the two new renders appeared after the request. The named
screenshot is the pre-change build. This is a process defect, not a UI defect, but it is why the
header change cannot currently be accepted as "verified".

### H10 (P3, outside the header) — task-row containers are invisible in light mode

`AgentHomeTaskRow` paints a 16 dp rounded `surfaceContainerLow` (`AgentWorkspaceHomeScreen.kt:358-364`),
but light `surfaceContainerLow = #F8F9FA` equals `background = #F8F9FA` (`McaTheme.kt:56,79`), so the
rows have no boundary in the 23:40 render (dark is `#202124` vs `#121212`, visible). Either use
`surface`/`surfaceContainerLowest` with a 1 dp border, or drop the Surface deliberately. Not part of
the header slice, but it is the surface directly under the reviewed header.

---

## 3. Verified OK — do not change in this slice

- **Leading inset**: 18 dp start is identical to Chat; both menu inks start at ≈33 dp; the 48 dp box,
  CircleShape, 5 dp shadow, 24 dp glyph and `LocalContentColor` tint match Chat's menu cell
  (`:226-243` vs `ChatStatusBarHeader.kt:87-107`).
- **`statusBarsPadding` removal is correct**: render shows the bar top at 32.4 dp = the Scaffold's
  status-bar inset, no duplicate. The host is the single top-inset owner
  (`MainActivity.kt:290-291`; material3 1.4.0 `ScaffoldDefaults.contentWindowInsets` =
  `systemBarsForVisualComponents`, applied by `ScaffoldLayout` when no top bar exists).
- **Title vertical placement**: cap-ink centre = menu-ink centre (0.4 dp); no clipping at 1.5×/2.0×
  (line box 36/48 dp still fits the 48 dp control).
- **Touch/a11y**: 48 dp target and `contentDescription = "打开任务与工具抽屉"` present; the drawer
  rows keep 48 dp targets.

---

## 4. Double-inset matrix (single-owner analysis)

| Surface | Insets it applies | Host already applies | Result |
| --- | --- | --- | --- |
| Host `Scaffold` | `contentWindowInsets` = system bars, `.padding(padding)` | — | **Owner (correct)** |
| Home top bar | none | top 32 dp | single ✅ (render-confirmed) |
| Agent drawer sheet | `DrawerDefaults.windowInsets` = system bars (Vertical+Start) | top 32 / bottom 16 / start (landscape) | **double**: header 32 dp low, footer 16 dp high; start doubles in landscape/cutout |
| Home composer | `navigationBarsPadding()` (`AgentHomeComposer.kt:97`) | bottom 16 dp | **double**: measured card bottom 35.3 dp above the window bottom ≈ 2 + 16.5 + 16.5 ✅; same pattern as Chat's `ChatComposerScreens.kt:318`, so app-wide and pre-existing |
| Task detail bar | `statusBarsPadding()` (`AgentTaskDetailScreen.kt:184`) | top 32 dp | **double**: header drops ≈32 dp on task open (code-provable, NOT_RUN render) |
| Previews (`AgentWorkspacePreviews.kt`) | none | none | insets 0 — fine for previews only |

Rule for this workspace: the host Scaffold owns system-bar insets; feature surfaces must not add
`statusBarsPadding`/`navigationBarsPadding` for the same side, and `ModalDrawerSheet` must be given
`WindowInsets(0)` when it lives inside that host. The composer's nav inset is a separate, app-wide
cleanup (Chat shares it) — do not change only Agent.

---

## 5. Top 3 fixes (ranked)

1. **Restore the Chat vertical grid on the home bar (H1).**
   `AgentWorkspaceHomeScreen.kt:221`: `top = 14.dp` (optionally `16.dp`) and `bottom = 10.dp`
   (optionally `12.dp` for exact 76 dp parity); fix the 18/14/10 comment at `:224-225`.
   Expected: bar 72 dp (or 76 dp), menu centre 70.4 dp absolute with 14/10 (72.4 dp with 16/12,
   = Chat) against Chat's 72.4 dp — no 6 dp hop; body seam moves 4–10 dp down. Verify against a Chat
   capture on the same device, light and dark.
2. **Make the drawer use the host's insets and align its header (H2).**
   `AgentWorkspaceHomeScreen.kt:100`: add `windowInsets = WindowInsets(0, 0, 0, 0)`;
   `AgentHomeDrawer.kt:262`: `top = 10.dp`. Expected: drawer brand-row centre equals the home menu
   centre (66 dp), no 40 dp drop, footer no longer double-padded. (Then schedule the same for
   `ChatHistoryDrawerScreens.kt:224` and the detail bar's `statusBarsPadding` — H3 — so all four
   surfaces share one inset owner; neither is in this slice's write set.)
3. **De-detach the menu affordance (H4) and settle the title scale (H5).**
   Replace the surface-on-surface shadow-only circle with a real container: 1 dp `outlineVariant`
   border (or `surfaceVariant` fill) and drop `shadowElevation`, **or** keep the shadow only if a
   right-side control is added so the row reads as one object. Switch `Icons.Filled.Menu` →
   `Icons.Rounded.Menu`. Set the title to 14 sp / 20 sp Medium at the same 78 dp origin (or move to
   Chat's 120 dp text grid if text parity is the goal). Expected: no floating disc in light or dark;
   the same affordance in both headers; the title no longer competes with the bar.

Follow-up (not in the top 3): H6 body rhythm, H7 drawer width cap, H8 drawer left-grid normalisation,
H9 evidence manifest, H10 task-row container contrast.

---

## 6. Verification recipe (all NOT_RUN here; forbidden by the task)

1. Rebuild and capture, on one device: Agent home 1.0× light + dark, Chat home 1.0× light + dark,
   Agent home 2.0× font, drawer open, task detail open, landscape/cutout.
2. Measure in px and convert: bar top, bar bottom, menu ink box/centre, title ink left + cap top +
   baseline, first label ink, first row top, drawer brand ink centre, drawer right edge, composer
   bottom.
3. Assert: bar top = one status-bar inset; bar height = 72–76 dp; menu centre = Chat's ±1 dp; title
   cap-ink centre = menu centre ±1 dp; drawer brand centre = home menu centre ±1 dp; drawer width =
   min(72 %, 300 dp); detail bar top = home bar top; composer bottom = 2 dp + one nav inset.
4. Repeat drawer/detail in landscape for the start-inset double.
5. Record device, density, font scale and file hashes next to the PNGs.

---

## 7. Source map

| Claim | Source |
| --- | --- |
| Home bar 18/10/8, Surface 48 dp, title, comment | `feature/agent-workspace/.../ui/AgentWorkspaceHomeScreen.kt:213-254` |
| Home list top 4 dp, drawer sheet + width modifier | `AgentWorkspaceHomeScreen.kt:100-104, 161-170` |
| Home row container `surfaceContainerLow`, 16 dp shape | `AgentWorkspaceHomeScreen.kt:354-404` |
| Section header 20/8 dp | `feature/agent-workspace/.../ui/AgentWorkspaceComponents.kt:86-110` |
| Drawer brand 20/18/8, 34 dp mark, button 16 dp, rows 20 dp | `AgentHomeDrawer.kt:88-108, 257-294, 308, 343, 375, 401` |
| Composer `imePadding` + `navigationBarsPadding` + 12/6/2 | `AgentHomeComposer.kt:93-99` |
| Chat bar 18/14/10, menu 48 dp, capsule 42 dp, right cluster 52 dp | `feature/chat/.../ChatStatusBarHeader.kt:80-107, 110-202, 226-256` |
| Chat list 26 dp top / 18 dp horizontal / 18 dp spacing | `feature/chat/.../ChatMessageListSection.kt:117-126` |
| Detail bar `statusBarsPadding()` | `AgentTaskDetailScreen.kt:180-186` |
| Host Scaffold + padded Box | `app/.../MainActivity.kt:290-291`; Agent overlay `:370-389` |
| McaIconButton 48 dp / 22 dp default glyph | `core/designsystem/.../McaButtons.kt:52-53, 73-93` |
| Light/dark `background`, `surface`, `surfaceContainerLow` | `core/designsystem/.../McaTheme.kt:56-58, 79-82, 99-102, 121-124` |
| Scaffold/Drawer default insets | material3 1.4.0 (BOM 2026.05.01) `ScaffoldDefaults.contentWindowInsets`, `DrawerDefaults.windowInsets` |

---

## 8. Limits of this review

- No Chat or Detail render was supplied, so the Chat/Dsh comparisons are layout-math, not pixels.
- Density was solved from rendered dp geometry (2.78 px/dp); ±0.5 dp tolerance on converted values.
- Large-font, dark-mode, landscape and TalkBack states are **NOT_RUN**; the 16 sp title / 48 dp circle
  balance and the drawer start-inset double are the main risks there.
- The 23:00 screenshot was not used for any current-state conclusion.

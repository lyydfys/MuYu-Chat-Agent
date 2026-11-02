# Agent v1 visual language — top bar, run stats strip, tool-call rows

Status: **PROPOSAL / read-only research. NOT_IMPLEMENTED, NOT_RENDERED.**
This file is the only write of this task. No Gradle, no build, no device, no screenshot of MCA was
produced. Every MCA number below is a source literal from the pinned snapshot; every reference
number is a pixel measurement of the supplied images.

Baseline: branch `codex/enterprise-refactor`, HEAD `959de21`, working tree dirty.
`feature/agent-workspace/`, `core/agent-contract/`, `core/agent-runtime/` are **untracked**, so no
committed revision pins them. The UI files were **still being edited while this was written**
(mtimes 02:18–02:21 on 2026-10-20); the snapshot below is pinned by SHA-256, not by HEAD.

## 0. Top 5 specs (the short version)

1. **Top bar is three bare controls on a 48 dp strip, no brand mark.** Leading 48 dp target at
   ≈0–2 dp, 14 sp/500 title starting at ≈58–59 dp, trailing 16 dp outlined panel glyph whose ink
   ends ≈19 dp from the right edge. One divider exists, and it belongs to the tab row, not the bar.
2. **Tab row:** 13 sp labels, ≈36.7 dp gap, 2 dp indicator equal to the intrinsic 2-CJK-char label
   width (≈26.3 dp), sitting **on** the 1 px `#E3E3E3` divider; selected `#4575D7`, unselected
   `#7A7A7A`.
3. **Run stats strip:** one 12 sp line under the composer — 24 dp side insets, three groups 35–39 dp
   apart, 13 dp icons, 8 dp icon→label gap, `#797979` ink, 6.5 dp below the card. Grammar:
   `N 轮 N 步 · N tok/s` | `823M tok · 缓存命中 99.5%` | `◔ 54%`. Context/cache segments are omitted,
   never faked.
4. **Tool-call rows use third-party's compact grammar, not cards:** 4 dp top rhythm, 16 dp glyph, 8 dp
   gaps, 12 sp/500 primary title clamped 80–120 dp, 12 sp summary at 70 % ink, one line, 160-char
   cap. Result rows indent 24 dp with an 18 dp corner arrow + 14 dp status + 8 dp gaps.
5. **MCA deltas that matter (pinned snapshot):** home bar is 72 dp and a 30 dp brand mark pushes the
   title to 118 dp; the stats bar uses 4 dp insets / 11 sp / 10 dp segment gaps and folds `tok/s`
   into the token group; the tool row is a 48 dp, 18 dp-glyph, 10 dp-gap block; detail tabs are
   48 dp tall, 16 dp apart, 14 sp, with a 2 dp gap above the divider. §6 ranks the eight fixes.

## 1. Evidence, calibration and limits

### 1.1 Images

| ID | Object (sha256 prefix) | Format | What it proves |
| --- | --- | --- | --- |
| A | `c5171e63…` | JPEG 1200×2608 | Conversation surface at one DSH state: top bar (menu / title / panel), `对话·轨迹` tabs, user bubble + meta, status line, body, action row, composer, bottom stats strip |
| B | `2d2d9ccd…` | PNG 390×25 | Crop of A's stats strip at 0.325× (same group x-positions within 1 px), confirming the 12 sp/13 dp/8 dp metric |
| C | `2ee63c90…` | PNG 591×38 | The same strip in another state (`23 轮 1942 步 · 252 tok/s`, `823M tok · 缓存命中 99.5%`, `54%`) with the **active-group chip** visible; a different capture density, used for grammar, chip and ring arc, **not** for dp |

B is a downscale of A: A's group 1 spans x 73–406 (333 px) → B 24–132 (108 px), ratio 0.324;
A's group 2 starts 515 → B 170 (0.330). C's text ink is 11–13 px with a 22 px chip and a different
ink color (`#61666B` vs A's `#797979`), so C is a different renderer/density and cannot be converted
to dp with A's factor.

### 1.2 Calibration and colour method

- A is 1200 px wide. This repo already calibrates it as **≈3.077 px/dp** (390 dp reference width,
  `DSH_UI_PARITY_MATRIX.md` §1.3). All dp below use that factor, tolerance **±0.5 dp** for ink
  boxes and **±1 dp** for inferred containers. Raw px are quoted so the factor can be re-solved.
- Cross-check: the send disc is 100 px ≈ 32.5 dp and the composer card is 296 px ≈ 96.2 dp; both
  land on whole-dp design values, so the factor is consistent to <1 %.
- Colours are sRGB **core-pixel** samples excluding anti-aliasing and JPEG ringing; report them as
  `#RRGGBB ±2/channel`, not brand truth. Text colours are additionally reported as the median of the
  darkest 5 %, which is darker than the rendered colour; the core sample is the better estimate.
- Font family is **not** provable: DSH CJK renders like system Noto Sans CJK; MCA has no custom
  `Typography` (`McaTheme.kt`), so both fall back to the platform font. Sizes are inferred from CJK
  ink height (≈0.86–0.88 em) and Latin cap height (≈0.72 em).

### 1.3 Pinned MCA snapshot (read this before implementing)

| File | SHA-256 | mtime | Values used below |
| --- | --- | --- | --- |
| `ui/AgentWorkspaceHomeScreen.kt` | `4E52A11D119AB3BE09151DBCE5CCC0E5269C3278CAC6984B82B2922D8FA31C59` | 02:19:21 | home bar 18/14/10, 48 dp target, 24 dp menu, 12 dp gap, `BrandMark(30.dp)`, 10 dp gap, `titleSmall` Medium `MCA Agent`, divider |
| `ui/AgentTaskDetailScreen.kt` | `769E8E2CDDC7F521F13B05ACD9A33A9B822D5A13D1B3F2E153CC9AF5E019225C` | 02:19:31 | detail bar 56 dp/min, 4 dp horizontal, back 48, mark 30, 10 dp title, `titleSmall`; tabs start 48, gap 16, 48 dp height, 12 dp padding, `labelLarge` 14 sp, 6 dp + 2 dp indicator + 2 dp |
| `ui/AgentRunStatsBar.kt` | `19C041D75287D0421CF41AB5FF7D3A0ED91ADD9C9FFF0E22F1000A7F311E304B` | 02:21:15 | 4 dp horizontal / 2 dp vertical, 10 dp gaps, 12 dp running ring, 13 dp icons, 4 dp icon gap, 11 sp text, `onSurfaceVariant` |
| `ui/AgentTimelineRowItem.kt` | `F4FA0C99384A154E45004C0C37957A1C23F0D67C5EBF44DA4C929DE17088695F` | 02:18:07 | 10 dp clip, min 48 dp, 10/6 padding, 18 dp glyph, 10 dp gap, `labelMedium` primary title, 1-line `bodySmall` summary, status glyph 14/16 dp, 18 dp chevron, 28 dp detail indent |
| `AgentRunStatsProjection.kt` | `0485B466CFDC28EAC1C997F5DC10ADF1E20D0E6ED282552ED5B6709DED04105C` | 02:21:08 | `projectRunStats`, `formatRunRoundsAndSteps`, `formatRunTokens`, `formatRunContextPercent`, `formatRunCacheHitPercent` |
| `AgentWorkspaceModels.kt` | `CDCACB662E775667B2D2292817A26DD418D09615244698DFFCEA25FB6D41E272` | 02:21:40 | `AgentRunStatsUiState`, null-until-sourced contract comment |

**Concurrency warning:** between 02:12 and 02:22 the home bar changed from a bare `Agent` title to
the brand-mark header above. The "current MCA" column is therefore a snapshot at those hashes; re-read
the files immediately before editing and do not treat §2.6 as a live description.

### 1.4 What this file does not prove

- No dark-mode reference exists. Every dark value is a **proposal** mapping the measured light roles
  onto `McaTheme`'s dark scheme, with contrast arithmetic — not an observation.
- No reference image shows a DSH tool-call row. §4's row language comes from the third-party source
  (exact literals) plus the DSH chrome's density/color discipline; it is not a DSH parity measurement.
- Hover/pressed chip state comes from C only; no touch state was observed.
- Font family, fontScale, RTL, landscape, tablets, IME and TalkBack are NOT_RUN.
- Container heights of the DSH bar/tabs are **inferred** from ink centres (the bar background is the
  same white as the page, so no edge is visible); only ink boxes and dividers are direct pixels.

## 2. Zone A — Agent top bar

### 2.1 Reference anatomy (A, px → dp at 3.077)

| Element | Ink box (px) | Ink box (dp) | Core colour | Type / icon |
| --- | --- | --- | --- | --- |
| Leading menu | x 53–102, y 205–238 | 16.3 × 11.1 at x 17.2, y 66.6 | `#666666` (median `#5F5F5F`) | 3 bars, each 6 px = 2.0 dp, pitch 14 px = 4.6 dp → a 20–22 dp rounded-menu glyph; 48 dp target not observable |
| Title | x 182–344, y 214–252 | 52.7 × 12.4 at x 59.1, y 69.5 | `#070707` | 4 CJK chars, advance 42 px = 13.7 dp → **14 sp**, weight ≈500 |
| Trailing panel | x 1103–1141, y 215–253 | 12.7 × 12.4 at x 358.5, y 69.9 | `#5D5D5D` | outlined rounded square + right vertical rule, stroke ≈1.2 dp → 16 dp glyph |
| Bar content centre | — | y 75.7 | — | vertical centre of menu/title/panel |
| Tabs | x 178–255 and 368–442, y 328–367 | 25.1 × 12.7 at x 57.8 / 119.6 | selected `#4575D7`, unselected `#7A7A7A` | **13 sp**, selected ≈500 |
| Tab gap | 255 → 368 | 36.7 | — | between ink boxes |
| Indicator | x 178–259, y 396–402 | 26.3 × 2.0 | `#4575D7` | full label width, radius 2 dp, **on** the divider |
| Divider | y 399, full width | 129.7, 1 px | `#E3E3E3` | one line, under the tab row only |

Inferred vertical grid (background is white on white, so this is ink-derived): status inset ≈50 dp
(device-owned; status time ink centres at y 25.3 dp), **top bar ≈48 dp**, tab strip ≈31.7 dp, divider
at 129.7 dp. The title's cap centre (75.7 dp) is 25.7 dp into the bar, i.e. a centred 48 dp bar.

### 2.2 Grid the bar must land on

```text
0 ─ 2 ───────── 50 ─ 58 ─────────── 371 ─ 390 dp   (390 dp reference width)
    [48 dp menu]  |   14 sp title          [16 dp panel]
     ink 17–33    gap≈26                ink 358.5–371
```

- Leading target 48 dp flush at **0–2 dp**; the 16.3 dp ink then lands at 17.2–33.1 dp.
- Title origin **58–59 dp** = target end + 8–10 dp; do not add a brand mark or logo in this bar.
- Trailing target 48 dp against a **16–19 dp** right inset; 16 dp glyph, no filled container.
- No divider under the bar; the only divider is the tab-row rule. No back arrow, no overflow menu in
  this bar (the reference's trailing glyph means “panel”, not “task menu”).

### 2.3 Light colours (measured) → MCA mapping (proposal)

| Role | Reference | MCA token | Note |
| --- | --- | --- | --- |
| Bar surface | `#FFFFFF` | `surface` `#FFFFFF` | identical |
| Page surface | `#FFFFFF` (whole conversation) | `background` `#F8F9FA` | reference is pure white; a global page change is outside this slice |
| Primary / selected tab + indicator | `#4575D7` (indicator core; parity note's `#4C72D5` is within JPEG error) | `primary` `#1A73E8` | **do not copy the periwinkle**; keep MCA brand |
| Primary ink | `#070707` | `onSurface` `#202124` | reference is darker; `onSurface` is acceptable |
| Secondary ink | `#7A7A7A` | `onSurfaceVariant` `#5F6368` | reference is lighter; keep token, do not fork a grey |
| Divider | `#E3E3E3` | `outlineVariant` `#DADCE0` | 1 dp, full width |
| Menu/panel glyph | `#666666` / `#5D5D5D` | `onSurfaceVariant` | one tint for both |

### 2.4 Dark (PROPOSED — not observed)

| Role | Value | Contrast |
| --- | --- | --- |
| Bar surface | `#202124` (`surface`) | — |
| Page | `#121212` (`background`) | — |
| Title | `#E8EAED` (`onSurface`) | 13.1:1 |
| Menu/panel glyph | `#9AA0A6` (`onSurfaceVariant`) | 6.1:1 |
| Selected tab + indicator | `#8AB4F8` (`primary`) | 8.1:1 |
| Unselected tab | `#9AA0A6` | 6.1:1 |
| Divider | `#3C4043` | 1.6:1 non-text rule |

The 48 dp bar, 14 sp title, 16 dp panel and 2 dp indicator carry over unchanged; only tokens swap.

### 2.5 States

- Selected tab: primary ink + 2 dp indicator; unselected: secondary ink, no indicator.
- Pressed: rely on the 48 dp ripple; do not add a filled container (the current `1 dp` ring exists
  only to make the target visible on a white bar — on the reference bar it is **not** visible, so
  either drop it or keep it at `outlineVariant` only if the unselected bar has no other affordance).
- Long title: single line, ellipsis; the title must not push the trailing control below 48 dp.
- No title? Not applicable: title is the session display name (fallback “任务”).

### 2.6 MCA current (pinned) vs reference

| Property | Reference | MCA home (pinned) | MCA detail (pinned) | Delta |
| --- | --- | --- | --- | --- |
| Bar height | ≈48 dp | **72 dp** (14 + 48 + 10) | **56 dp** min | +24 / +8 dp |
| Leading inset | ≈0–2 dp (48 dp target) | 18 dp | 4 dp | home +16 dp; detail close |
| Leading glyph | 20–22 dp rounded menu | 24 dp `Rounded.Menu` | `ArrowBack` | home glyph +2–4 dp; detail has a back arrow, ref has none |
| Title origin | 58–59 dp | **118 dp** (18+48+12+30+10) | **100 dp** (4+48+8+30+10) | brand mark pushes title +59 / +41 dp |
| Title size/weight | 14 sp / 500 | `titleSmall` 14 sp / Medium | same | matches size; position does not |
| Trailing control | 16 dp panel, ink ends 371 dp | none | `MoreVert` menu | home has no trailing affordance; detail's means “menu”, not “panel” |
| Divider under bar | none | 1 px `outlineVariant` | none | home adds a rule the reference does not have |
| Tabs | 13 sp, 36.7 dp gap, indicator on divider | **no tabs on home** | 14 sp, 16 dp gap, 48 dp tall, 2 dp indicator 2 dp above divider | detail label +1 sp, gap −20.7 dp, indicator not on the divider |
| Tab start | 57.8 dp | — | 48 dp | detail −9.8 dp |

The reference is a **two-level chrome** (48 dp bar + 32 dp tab strip) on one conversation surface;
MCA splits the same information into a home bar without tabs and a detail bar with a 48 dp tab row.

### 2.7 Implementation spec (proposal)

- One `AgentTopBar` composable used by home and detail. Height 48 dp; horizontal padding 2 dp start /
  16 dp end; `NavigationIcon` slot 48×48 dp with a 20 dp `Icons.Rounded.Menu`; title `14.sp`,
  `FontWeight.Medium`, origin at 58 dp, `maxLines=1`, ellipsis; trailing 48 dp target with a 16 dp
  panel glyph (open drawer/panel), not a menu.
- Home and detail share the bar; detail title = `displayTitle`, home title = “MCA Agent” or session
  name; move `BrandMark(30.dp)` out of the bar (drawer header or empty state) — it is the single
  largest geometric divergence from the reference.
- Put `对话 / 轨迹` in a 32 dp tab strip directly under the bar on both surfaces, with the 2 dp
  indicator straddling the 1 px divider (see M6).
- Host owns the status-bar inset; the bar must not call `statusBarsPadding()` (the existing
  double-inset rule in `agent-header-review-2026-10-19.md` §4 still applies).
- Authorization: bar height, title origin/absence of brand mark and the added tab strip are
  **V-CHANGE** (frozen visual contract §9.1) and must be authorised separately; the 72 dp/118 dp
  implementation and `AgentHomeHeaderContractTest` currently encode the frozen state and must be
  updated in the same authorised slice.

## 3. Zone B — Run stats strip

### 3.1 Content grammar (A, B, C together)

Three groups, one line, fixed order:

```text
[◷] N 轮 N 步 · N tok/s      [▤] N tok · 缓存命中 NN.N%      [◔] N%
      activity group                cost group                  context group
```

- Separator **within** a group: ` · ` (U+00B7) with ≈8 dp air on both sides.
- Separator **between** groups: layout gap only, **≈36 dp** (35.4 / 38.7 measured).
- Each group is icon + text; icon 13 dp, gap 8 dp.
- A group disappears when its data is missing; a missing value must not render `0`, `--` or a fake
  percentage. C is the only group whose icon is a progress drawing; when `contextPercent` is null the
  whole group is absent.
- B may contain both total tokens and cache hit; either half may be absent, but the ` · ` is a
  joiner, never a dangling separator.
- C's label is the integer percent; the ring shows the same fraction (12 o'clock, clockwise).

### 3.2 Measured geometry (A)

| Property | px | dp | Note |
| --- | --- | --- | --- |
| Composer card bottom | 2462 | 800.1 | 1 px border `#DFDFDF` |
| Strip ink top | 2482 | 806.6 | **6.5 dp** below the card |
| Strip ink box | y 2483–2522 | 806.9–819.6 (12.7) | one line, vertically centred |
| Left inset | 73 | 23.7 | icon ink; group box starts ≈23 dp |
| Right inset | 1121 → 1200 | 25.7 | last label ink to edge |
| Group 1 | 73–406 | 23.7–132.0 | ◷ + “1 轮 1 步 · 23…” |
| Group 2 | 515–889 | 167.4–288.9 | ▤ + “14.6K tok · 缓存…” |
| Group 3 | 1008–1121 | 327.6–364.3 | ◔ + “1%” |
| Group gaps | 406→515, 889→1008 | **35.4, 38.7** | layout gap, no dot |
| Icon size | 39–40 px | **13 dp** | clock 39×35, db 37×40, ring 40×39 |
| Icon→label | 25/26/24 px | **8.1 / 8.4 / 7.8 dp** | one 8 dp gap |
| Label size | digit cap 27 px; CJK ink 34 px | **12 sp** | CJK 0.88 em → 12.5 sp; digits 0.72 em → 12.2 sp |
| Label colour | core `#818181`, median `#797979` | — | 4.6:1 on white, AA for 12 sp |
| Icon colour | `#787878` | — | same family as label |
| Middle dot | 4×4 px | 1.3 dp | U+00B7 at x 303–306, 756–760, 391–406 (the last is the ellipsis “…” in A) |
| Ring | 40 px, stroke ≈4 px | 13 dp, **1.3 dp** | track `#DDDDDD`, progress `#7F7F7F`; 12 o'clock start |

B reproduces the same vertical box (25 px tall, text ink 10–12 px) at 0.325×, confirming 12 sp /
13 dp / 8 dp. C adds a distinct state: the active group is wrapped in a full-radius chip.

### 3.3 Ring (C and A)

- Diameter 13 dp, stroke 1.3 dp; at 1 % the progress is a dot at 12 o'clock, at 54 % it is a clean
  half arc (C).
- Track is a full circle; progress is drawn **on top**, clockwise from 12 o'clock, round caps.
- Track never disappears; progress colour is the secondary ink (`#7F7F7F`), not the accent — the
  strip is telemetry, not a CTA.
- Label sits to the right with the same 8 dp gap as any icon; do not put the number inside the ring
  (the reference does not). The MCA ring must be drawn (`Canvas`/`drawArc`), not a static
  `DonutLarge` glyph, and must not be an indeterminate spinner at rest.

### 3.4 Active/hover chip (C only)

- C: chip x 25–240, y 9–30 → **215 × 22 px**, radius 11 px (full), fill `#F0F1F2`; label ink
  `#61666B`; the pill wraps only the activity group; padding ≈11 px each side (≈0.85× ink height).
- Ratios to port: chip height ≈ **1.7×** text ink height, padding-x ≈ **0.8×** ink height, radius =
  height/2.
- MCA mapping: 20 dp high capsule, 8 dp horizontal padding, `surfaceVariant` `#F1F3F4` light /
  `#303134` dark, shown only on press/hover of an interactive group — not as a permanent decoration.
  (If the group is not interactive in MCA v1, do **not** draw the chip at all.)

### 3.5 Truthfulness and overflow

- The strip is a single line; no horizontal scroll (the whole line must fit 390 dp at 1.0× font).
- Per-group `maxLines=1` with ellipsis; never shrink the ring or icons. When all groups are wide,
  drop the `tok/s` half before dropping the group, then shorten the cache text
  (`缓存命中 99.5%` → `缓存 99.5%`), then ellipsize the total tokens.
- At 2.0× font, the strip may become two rows (activity / cost + context) but must never clip the
  ring or overlap the navigation inset.
- No value is invented: this is already the contract comment on `AgentRunStatsUiState`
  (`contextPercent` and `cacheHitPercent` are null until the run contract exposes usage).

### 3.6 MCA data contract (pinned, already present)

- `AgentRunStatsUiState(rounds, steps, tokensPerSecond, totalTokens, contextPercent, cacheHitPercent,
  running)` — every field optional (`AgentWorkspaceModels.kt`, `AgentRunStatsUiState`).
- `projectRunStats` fills `rounds = spent.modelAttempts`, `steps = modelAttempts + toolInvocations`,
  `tokensPerSecond = generatedTokens/modelWallTime`, `totalTokens = generatedTokens`,
  `contextPercent = null`, `cacheHitPercent = null`; `running = state == RUNNING`
  (`AgentRunStatsProjection.kt`).
- Formatters: `formatRunRoundsAndSteps` → “3 轮 5 步”; `formatRunTokens` → “23 tok/s · 14.6K tok”;
  `formatTokenCount` → “14.6K tok” / “823M tok”; `formatRunContextPercent` → “54%”;
  `formatRunCacheHitPercent` → “缓存命中 99%” (integer).
- **Group-order defect to fix:** the reference puts `tok/s` in the **activity** group and total tokens
  in the **cost** group. MCA's `formatRunTokens` joins `tok/s · total tok` into one segment under one
  database icon. Splitting the formatter (or the call) is part of M2.
- **Precision gap:** the reference shows one decimal on cache hit (`99.5%`); the MCA formatter is
  `Int`. If the contract later exposes a ratio, floor to one decimal (third-party's
  `formatCacheHitRate` floors to two decimals — use the same “truncate, never round up” rule).

### 3.7 MCA current vs reference

| Property | Reference | MCA `AgentRunStatsBar` (pinned) | Delta |
| --- | --- | --- | --- |
| Side inset | 23.7 dp | 4 dp (+ parent padding) | −19.7 dp |
| Group gap | 35–39 dp | 10 dp (`spacedBy`) | −27 dp |
| Icon size | 13 dp | 13 dp | matches |
| Icon→label | 8 dp | 4 dp | −4 dp |
| Text size | 12 sp | 11 sp | −1 sp |
| Text colour | `#797979` | `onSurfaceVariant` `#5F6368` | darker; keep token |
| Groups | 3 (activity / tok+cache / context) | up to 5 segments, order: running, activity, tok/s+total tok, cache, context | order/grammar differ |
| `tok/s` placement | activity group | inside the token group | defect |
| Context icon | 13 dp progress ring | `DonutLarge` static glyph | semantics differ |
| Running state | not shown as a separate glyph | 12 dp indeterminate ring as a 4th element | extra element |
| Vertical gap below card | 6.5 dp | 4 dp top modifier + 2 dp row padding | ≈matches |
| Chip state | 20 dp capsule on active group | none | missing |

### 3.8 Dark (PROPOSED)

Text `#9AA0A6` on `#202124` (6.1:1); icons same; ring track `#3C4043`, progress `#9AA0A6`; chip
`#303134`; chip label `#E8EAED`; no accent colour anywhere in the strip (the reference is neutral in
light; keep it neutral in dark). The 6.5 dp top gap and the 36 dp group gap do not change.

### 3.9 Implementation spec

- Row: `heightIn(min=20.dp)`, horizontal padding **24 dp**, top padding **6 dp** (4 dp call site +
  2 dp row), group gap **36 dp**, no scroll.
- Segment: 13 dp icon + 8 dp + `12.sp` text, `onSurfaceVariant`; tabular figures for numbers.
- Activity segment: ◷ + `formatRunRoundsAndSteps` + (` · ` + `tok/s` when present).
- Cost segment: ▤ + total tokens + (` · ` + cache hit when present).
- Context segment: Canvas ring 13 dp + 8 dp + `NN%`; absent when null.
- Optional chip only when the group is pressable; otherwise static.
- Remove the stand-alone running spinner from the strip (the run status already lives in the bar and
  the status line); if a running affordance is required, pulse the activity icon, do not add a 4th
  element.

## 4. Zone C — Tool-call rows

No reference image contains a DSH tool row. The row language below is taken from the **third-party
source** (`ToolDisplayComponents.kt`, `XmlCanvasSummaryComponents.kt`, `ToolResultDisplay.kt`,
`BubbleAiMessageComposable.kt`) because it is the only exact source available, then constrained by
the DSH chrome's type/icon density from §2–§3. It is labelled `SOURCE-DERIVED`, not DSH-measured.

### 4.1 Compact tool-call row (third-party, exact literals)

| Property | Value | Source |
| --- | --- | --- |
| Row height | 4 dp top padding + 16 dp content = **20 dp**; no bottom padding | `CanvasToolSummaryRow` |
| Glyph | **16 dp**, tint `titleColor.copy(alpha=0.7f)` | same |
| Gap glyph→title | **8 dp** | same |
| Title | `labelMedium` (12 sp / 16 sp line / 0.5 sp tracking / 500), colour `primary` | same |
| Title width | clamped **80 dp min / 120 dp max**, 1 line, ellipsis | same |
| Gap title→summary | **8 dp** | same |
| Summary | `bodySmall` (12 sp / 16 sp), colour `textColor@70%` (message ink, normally `onSurface`) | `CompactToolDisplay` |
| Summary length | one line, ellipsis; pre-truncated at **160 chars** + “…” | `CanvasToolSummaryRow` + `toCanvasSingleLineText` |
| Interaction | whole row clickable; ripple; opens a parameter dialog when params exist; semantics “工具调用: name, 参数: summary” | same |
| Card variant | `DetailedToolDisplay` reuses the same row; summary becomes the params byte-size label | `ToolDisplayComponents.kt` |
| Bubble context | assistant bubble: 32 dp avatar, 8 dp gap, 12 dp content padding, 4/20/20/20 radius, min height 44 dp, 4 dp message gap | `BubbleAiMessageComposable.kt` |

### 4.2 Tool-result row (third-party, exact literals)

| Property | Value |
| --- | --- |
| Start / end padding | **24 dp / 16 dp** |
| Vertical padding | **2 dp** (height = 4 + content, ≈22 dp) |
| Corner arrow | **18 dp** `SubdirectoryArrowRight`, tint `primary@70%` / `error@70%` |
| Status glyph | **14 dp** check / close, tint `primary` / `error` |
| Gaps | **8 dp** arrow→status and status→text |
| Text | `bodySmall` 12 sp, `onSurface@80%` (success) / `error@80%` (failure), 1 line, ellipsis |
| Copy | 14 dp glyph inside a **24 dp** trailing slot, `primary@60%`; bounded ripple |
| Detail dialog | 16 dp card radius, `titleMedium` bold title, divider, content `surfaceVariant@50%`, 8 dp radius, 12 dp padding |

The 24 dp indent aligns the result under the call's title (4 + 16 + 8 = 28 dp in the source's icon
grid; 24 dp is the visible optical indent used by the result row).

### 4.3 MCA current tool row (pinned)

- `Column` clipped to `RoundedCornerShape(10.dp)`; `heightIn(min = 48.dp)`; padding 10 dp / 6 dp.
- 18 dp category glyph, tint by tone (primary, primary@72 %, error); 10 dp gap.
- Title `labelMedium` (12 sp), colour `primary`, 1 line; `×N` and time `labelSmall` beside it.
- Summary `bodySmall` 12 sp, 1 line, `onSurfaceVariant`, 1 dp top.
- Status glyph: 14 dp spinner when ACTIVE, 16 dp check/error/warning otherwise.
- 18 dp chevron; expanded rows get a `surfaceContainerLow` fill and 28 dp-indented details with 4 dp
  line gaps (no border, no icon tile).
- **This already matches third-party's compact grammar in structure**; the deltas are metric, not
  architectural: 48 dp visual height vs 20 dp, 18 vs 16 dp glyph, 10 vs 8 dp gap, 28 vs 24 dp indent.

### 4.4 Proposed anatomy and states

- Visual line: 24 dp (min) with 4 dp top spacing; 16 dp glyph; 8 dp; title 12 sp/500 `primary`
  (80–120 dp clamp); 8 dp; summary 12 sp `onSurfaceVariant`, 1 line; trailing 14/16 dp status and
  16 dp chevron.
- Touch target: keep **48 dp** via `Modifier.minimumInteractiveComponentSize()` (or 12 dp outer
  vertical padding on the clickable), not by inflating the visible row; the reference is dense and
  the current 48 dp visual block is the main density regression.
- Expanded: `surfaceContainerLow`, 10 dp radius, content indent 28 dp (4 + 16 + 8), 12 sp body,
  4 dp line gap; no border; keep `SelectionContainer`.
- Tone: ACTIVE = 14 dp spinner `primary`; SUCCESS = `primary`; ERROR = `error`; WARNING =
  `onSurfaceVariant`; NEUTRAL = no trailing glyph. Never colour the title itself by tone (the title
  is always the tool key); tone lives in the glyph/status.
- Result rows (new): 24 dp indent, 18 dp corner arrow + 14 dp status + 8 dp + 12 sp at 80 % ink,
  optional 24 dp copy slot; nested directly under their call row.
- No cards, no 1 dp borders, no 30–32 dp icon tiles; the row is a line of text with one glyph.

### 4.5 Dark (PROPOSED)

Glyph `primary #8AB4F8` (active/success) / `#F28B82` (error) / `#9AA0A6` (warning/neutral); title
`#8AB4F8` at 500; summary `#9AA0A6`; expanded fill `#202124`; status same tints; divider none. The
10 dp radius and all metrics carry over.

## 5. Cross-zone tokens

### 5.1 Spacing (dp)

`2` bar stroke / 1 px rules · `4` row top rhythm · `6` compact vertical (tool row 10/6) · `8` icon
gap / result gaps · `10` composer control gap (not these zones) · `12` content padding · `16`
control gap · `24` strip inset / result indent · `36` stats group gap.

### 5.2 Type

| Role | Size / line | Weight | Colour (light) |
| --- | --- | --- | --- |
| Bar title | 14 / 20 sp | 500 | `onSurface` |
| Tab label | 13 / 18 sp | 500 selected, 400 idle | `primary` / `onSurfaceVariant` |
| Stats label | 12 / 16 sp | 400, tabular digits | `#797979` ≈ `onSurfaceVariant` |
| Tool title | 12 / 16 sp (labelMedium) | 500 | `primary` |
| Tool summary / result | 12 / 16 sp (bodySmall) | 400 | `onSurfaceVariant` / 80 % ink |
| Message meta (13:07) | 11 / 16 sp | 400 | `#7A7A7A` |

### 5.3 Icons

`20` menu (reference ink 16.3 dp suggests 20–22 dp glyph) · `16` panel/tool glyph/chevron · `18`
corner arrow (results) · `14` status/copy · `13` stats icons and ring · `12` running spinner.
One family only (Material Rounded/Symbols); the reference's menu is rounded, its panel is an outlined
custom glyph, and the stats clock/database/ring are outline weights ≈1.2–1.3 dp. Do not mix filled
and outlined for the same semantic.

### 5.4 Colour roles (light measured / dark proposed)

| Role | Light | Dark | Contrast (light/dark) |
| --- | --- | --- | --- |
| Bar + card surface | `#FFFFFF` | `#202124` | — |
| Page | `#FFFFFF` (ref) / `#F8F9FA` (MCA) | `#121212` | — |
| Primary ink | `#070707` | `#E8EAED` | 19:1 / 13:1 |
| Secondary ink | `#7A7A7A` | `#9AA0A6` | 4.6:1 / 6.1:1 |
| Accent | `#4575D7` (ref) / `#1A73E8` (MCA) | `#8AB4F8` | 4.4 / 8.1 |
| Rule | `#E3E3E3` | `#3C4043` | 1.25 / 1.6 |
| Ring track | `#DDDDDD` | `#3C4043` | decorative |
| Ring progress | `#7F7F7F` | `#9AA0A6` | 3.9 / 6.1 |
| Chip | `#F0F1F2` | `#303134` | — |
| Error | — | `#F28B82` | — (MCA light `#EA4335`) |

Rules: (1) never restyle MCA's accent to the DSH periwinkle; (2) 12 sp text must keep ≥4.5:1 —
`#7A7A7A` on white is the floor, `#5F6368` is safer; (3) ring track is decorative and exempt, ring
progress is meaningful and must stay ≥3:1.

## 6. Prioritized 8-item implementation list for MCA

Priority is impact × certainty. `V-CHANGE` = changes frozen visual language and needs explicit
authorisation under `MCA_PROJECT_HARD_LOCK.md` §9.1; `NEW` = a surface that does not exist yet;
`STRUCTURE` / `VERIFICATION` = no user-visible restyle by themselves.

| # | P | Item | Concrete change | Acceptance | Auth |
| --- | --- | --- | --- | --- | --- |
| M1 | P0 | **One 48 dp top bar** | New `AgentTopBar`: 48 dp high, 2 dp start, 48 dp menu target (20 dp `Rounded.Menu`), title 14 sp/500 at 58 dp, 48 dp trailing target with 16 dp panel glyph; remove the divider from the bar; remove `BrandMark` from the bar (move to drawer/empty state); use it on home and detail | Ink boxes within ±2 dp of §2.1; title origin 58±2 dp; bar top = host status inset; no `statusBarsPadding()` in the bar; light+dark render | V-CHANGE |
| M2 | P0 | **Stats strip geometry + grammar** | 24 dp side insets, 36 dp group gaps, 8 dp icon gap, 13 dp icons, 12 sp text, 6 dp top gap; split `tok/s` into the activity group and total tokens into the cost group; three groups, no 4th running element; keep hide-when-null | Line fits 390 dp at 1.0×; group starts/ends match §3.2 ±2 dp; no `0`/`--`/fake segment; screenshot diff vs reference | V-CHANGE |
| M3 | P1 | **Context ring + percent semantics** | Draw 13 dp ring (1.3–1.5 dp stroke, track `outlineVariant`, progress `onSurfaceVariant`, 12 o'clock, round caps); label right; replace `DonutLarge`; show only when `contextPercent != null`; keep the running spinner out of the strip | Ring diameter 13±0.5 dp; at 1 % a dot at 12 o'clock; absent at null; no spinner at rest | V-CHANGE |
| M4 | P1 | **Flat tool-call row** | 24 dp visual line, 4 dp top rhythm, 16 dp glyph, 8 dp gaps, 12 sp/500 primary title, 12 sp summary at 70 % ink, 1 line, 160-char cap; 48 dp hit area via `minimumInteractiveComponentSize`; expanded block 10 dp radius + 28 dp indent | Row visual height 20–24 dp; glyph 16; gaps 8; expanded indent 28; 48 dp hit target verified; TalkBack “展开/收起” state preserved | V-CHANGE |
| M5 | P1 | **Tool-result sub-row** | 24 dp indent, 18 dp corner arrow, 14 dp status, 8 dp gaps, 12 sp at 80 % ink, optional copy 14 dp in a 24 dp slot; success `primary`, failure `error` | Rendered indent 24±1 dp; tap target ≥48 dp on the copy slot; no card/border | NEW |
| M6 | P2 | **Tab strip** | 13 sp, 36 dp gap, 48 dp tap height, indicator = intrinsic label width × 2 dp, straddling the 1 px divider, start ≈58 dp; selected `primary`/500, idle `onSurfaceVariant`/400 | Indicator width = label width ±1 dp; indicator centre = divider y ±0.5 dp; label 13 sp; works at 2.0× | V-CHANGE |
| M7 | P2 | **`AgentVisualTokens` + delete ad-hoc literals** | One internal object with the §5 type/icon/space/colour roles; replace literals in `AgentWorkspaceHomeScreen`, `AgentTaskDetailScreen`, `AgentRunStatsBar`, `AgentTimelineRowItem`; add a unit contract test for the values and a contrast test for the light/dark ink roles | Grep shows no surviving bar/strip/row magic numbers; token test passes; light/dark contrast ≥4.5 for 12 sp text and ≥3 for the ring progress | STRUCTURE |
| M8 | P3 | **A11y, states, verification harness** | Content descriptions for the three groups (“1 轮 1 步，14.6K tok，上下文 1%”), throttled live-region updates, 48 dp targets, 2.0× font, dark render, golden box assertions for M1/M2/M6 | TalkBack reads one sentence per strip; no overlap at 2.0×; two-round light/dark render evidence recorded; measurements match §2–§3 | VERIFICATION |

Sequencing: M1 and M2 are the visible identity/density wins and are independent; M4/M5 together
convert the current timeline into the flat conversation grammar; M3 can ship hidden until the usage
projection exists (the ring is layout-independent); M6 completes the shared chrome; M7 must land
before M8 so the golden assertions reference tokens, not literals.

Do **not** do in these slices: copy DSH's `#4575D7` over MCA `#1A73E8`; fabricate `contextPercent` or
`cacheHitPercent`; keep a card/border/icon tile in tool rows; add a brand mark or back arrow to the
conversation bar; change the page background from `#F8F9FA` to white as a side effect.

## 7. Verification (all NOT_RUN)

Static (allowed now): grep the pinned literals; assert the M1–M6 values appear in source; run
`AgentRunStatsProjectionContractTest`-style JVM tests only if the integrator authorises a build.
Render (NOT_RUN, needs authorisation): one device, 1.0× light + dark, 2.0× font, 390 dp and 768 dp;
measure ink boxes with the same method as §2.1; assert bar height 48±2, title origin 58±2, strip
insets 24±2, group gap 36±2, icon 13±0.5, label 12 sp, row visual 20–24, hit 48.

## 8. Limits and risks

- The DSH bar/tab container heights are inferred (white bar on white page); only ink and the divider
  are direct. A 1–2 dp error is possible.
- The JPEG compresses chroma near the blue indicator; `#4575D7` is the core-pixel estimate, not a
  brand value.
- C is a different density; its 22 px chip and 11–13 px text are ratios only.
- The MCA files are untracked and were edited during this review; re-read at the pinned hashes before
  changing anything.
- The tool-row language is third-party source-derived; whether DSH itself uses the same row is **not**
  proven by the supplied images.
- No dark or tool-row reference exists; the dark table is a proposal with arithmetic, not a
  measurement.

## 9. Delivery record (hard-lock §16)

```text
Task / scope: read-only visual-language extraction for Agent top bar, run stats strip and
  tool-call rows; one proposal file written; no code, build, device or runtime action.
Baseline: branch codex/enterprise-refactor, HEAD 959de21; feature/agent-workspace,
  core/agent-contract, core/agent-runtime untracked; UI snapshot pinned by SHA-256 (§1.3) at
  2026-10-20 02:21 +08:00; the files changed during the read (see concurrency warning).
Files written: docs/proposals/agent-v1/evidence/5e912ff9/agent-visual-language.md (this file).
Facts: reference ink boxes/colours measured from A/B/C; MCA values read from the pinned sources;
  no value is claimed as rendered.
NOT_RUN: Gradle, tests, APK, device, TalkBack, dark render, 2.0× font, landscape, tablets.
NOT_APPLICABLE: runtime/native/state-owner checks (documentation-only task).
Risks: inferred bar heights; JPEG chroma; C density unknown; moving MCA source; third-party-derived
  tool rows are not a DSH parity measurement.
Rollback: delete this file; no other change.
```

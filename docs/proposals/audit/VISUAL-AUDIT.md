# MCA Visual / UX Craft Audit

**Scope:** the shipped Compose UI of MCA, measured against Google Gemini Android and OpenAI ChatGPT Android.
**Constraint honoured:** every finding below is a *craft* change. None requires altering `McaTheme.kt` light/dark `ColorScheme`, the seven `ChatColorTokens.kt` values, the user-bubble `20/6` corner radius, the full-width no-bubble assistant block, the `CircleShape` input bar, or `McaLogoMark`.
**Date:** 2026-10-17 · **Method:** static read of live sources + computed WCAG ratios + measured dp/sp histograms.

> **Note on measurement basis.** `git grep` under-reports: 62 of 142 files under `feature/chat/src/main/` are untracked in the working tree, so `git grep fontSize=` returns 44 when the compiling source contains **73**. All counts in this document come from a filesystem scan of `feature/**`, `app/src/main/**`, `core/**` excluding `build/`, `test/`, `androidTest/` (975 `.kt` files). Treat any `git grep`-derived number in older docs as low.

---

## 0. Scorecard

Gap severity: **1** = parity with Gemini/ChatGPT · **5** = reads as indie prototype.

| # | Axis | MCA now | Gemini | ChatGPT | Gap | Root cause (file:line) |
|---|---|---|---|---|---|---|
| 1 | Type scale | 15 distinct `fontSize` literals (8→25sp), 16 `lineHeight` literals; **0** `letterSpacing`; no `Typography(...)` object at all | 5-step scale, optical tracking, 1.55–1.6 body leading | 4-step scale, 1.5 body leading | **4** | `McaTheme.kt:84-87` passes only `colorScheme`, never `typography`; `ChatMarkdownBlockRenderers.kt:126-127` (15/23) vs `ChatMessageBubbleScreens.kt:280` (16/24) vs `ChatReasoningPerformanceScreens.kt:128-129` (13/20) |
| 2 | Spacing rhythm | 27 distinct padding dp values in `feature/chat` alone; 4/6/7/8/9/10/12/13/14/18 all in active use | 4dp base, 8/12/16/24 only | 4dp base, 8/12/16/24 only | **4** | `ChatMessageListSection.kt:63-65` (18/26/18) vs `ChatComposerScreens.kt:258` (16/10) vs `ChatStatusBarHeader.kt:69` (18/14/10) vs `ChatMessageBubbleScreens.kt:228` (17/13) |
| 3 | Elevation / surfaces | 7 distinct `shadowElevation` values (2,5,6,7,8,10,12,14,18); **no tinted shadows**; ~1.05:1 surface separation | tonal layering, 0–3dp, ambient tint | tonal layering, hairline borders | **4** | `ChatComposerScreens.kt:263` (14dp), `ChatModelSwitcherScreens.kt:79` (18dp), `ChatHistoryDrawerRows.kt:91` (5dp on selected row), `ChatHistoryDrawerRows.kt:172` (18dp) |
| 4 | Motion | 64 `tween`, **0 `spring`**, **0** `FastOutSlowInEasing`/`CubicBezierEasing`; 2 dead `AnimatedVisibility(visible = true)` | spring-driven, ~200ms, physical | spring-driven, interruptible | **5** | `SmoothPage.kt:48,50,67`, `SwipeBackPage.kt:49,71,95,97`, `ChatComposerActionMenuViews.kt:126-127`, `ChatComposerResearchModeViews.kt:114-115` |
| 5 | State design | Spinner-first: 10 `CircularProgressIndicator`, 20 `LinearProgressIndicator`; **0 skeleton**; **0 optimistic UI**; **0 first-token affordance** | skeleton + shimmer + optimistic echo | skeleton + optimistic echo + typing | **4** | `ChatBubbleMessageBlocks.kt:194` (bare 18dp spinner), `AppStartupScreen.kt:28`, `ChatComposerScreens.kt:451-455` |
| 6 | Micro-details | **0 haptics**, **0 ripple override**, **0 overscroll**, **0 scroll-to-bottom**, **0 list edge fade**, **0 `animateItem`**, **0 `LocalTextSelectionColors`** | haptics on send/stop, scroll-to-bottom FAB, edge fade | haptics, scroll-to-bottom, edge fade | **5** | project-wide zero counts; `ChatScreen.kt:251-258` hard `scrollToItem`, no intent check |
| 7 | Dark parity / contrast | Both schemes ≥4.5:1 for body; **fails** for 4 alpha-reduced secondary styles and the fixed-dark code block | passes AA throughout | passes AA throughout | **3** | `ChatReasoningPerformanceScreens.kt:126` (3.29:1 light), `ChatHistoryDrawerRows.kt:51,150` (3.16:1), `ChatMarkdownBlockRenderers.kt:218` (1.28:1 on dark) |
| 8 | Density / hierarchy | Top bar = 3 elevated capsules + 3 text tiers inside a 44dp pill; composer nests 4 surfaces | 1 header row, flat | 1 header row, flat | **4** | `ChatStatusBarHeader.kt:69-194`, `ChatComposerScreens.kt:254-266` |

**Composite read:** the *structure* is already flagship-grade (full-width assistant block, 20/6 bubble, CircleShape composer are all correct calls). The **execution layer** — type scale, motion physics, feedback, and surface discipline — is where it reads indie.

---

## 1. Typography system

### 1.1 There is no type scale

`McaTheme.kt:84-87`:

```kotlin
MaterialTheme(
    colorScheme = colorScheme,
    content = content
)
```

No `typography = ...` argument. MCA therefore ships **stock Material 3 baseline typography** — Roboto at M3's default sizes — and then overrides it ad hoc at ~73 call sites.

| Metric | Count |
|---|---|
| `fontSize = ` literals | **73** |
| distinct `fontSize` values | **15** (8, 9, 9.5, 10, 10.5, 11, 11.5, 12, 13, 13.5, 14, 15, 16, 17, 18, 20, 22, 25) |
| `lineHeight = ` literals | **47** |
| distinct `lineHeight` values | **16** (9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24) |
| `letterSpacing` | **0** |
| `Typography(` declaration | **0** |
| `FontWeight.Bold` | **97** |
| `FontWeight.SemiBold` | **54** |
| `FontWeight.Medium` | **35** |
| `FontWeight.Black` | **2** |

The `fontSize` distribution is a dead giveaway of per-screen tuning rather than a scale:

```
 8sp : 1     9sp : 3     9.5sp : 2   10sp : 6    10.5sp : 2
11sp : 6   11.5sp : 3   12sp : 8    13sp : 9    13.5sp : 1
14sp : 3    15sp : 17   16sp : 4    17sp : 3    18sp : 2
20sp : 1   22sp : 1     25sp : 1
```

`9.5sp`, `10.5sp`, `11.5sp`, `13.5sp` are the tell — half-pixel sizes only appear when someone is eyeballing a single screen.

### 1.2 The three body-text measurements disagree

Assistant body, user bubble body, and reasoning body are three *different* text systems:

| Surface | fontSize | lineHeight | ratio | Source |
|---|---|---|---|---|
| Assistant paragraph | **15sp** | **23sp** | 1.53 | `ChatMarkdownBlockRenderers.kt:126-127` |
| Assistant list item | 15sp | **22sp** | 1.47 | `ChatMarkdownBlockRenderers.kt:144,151` |
| User bubble | **16sp** | **24sp** | 1.50 | `ChatMessageBubbleScreens.kt:280` |
| Composer input | **15sp** | **20sp** | 1.33 | `ChatComposerScreens.kt:429-430` |
| Composer placeholder | 15sp | 20sp | 1.33 | `ChatComposerScreens.kt:419` |
| Reasoning body | **13sp** | 20sp | 1.54 | `ChatReasoningPerformanceScreens.kt:128-129` |
| Quote block | 14sp | 21sp | 1.50 | `ChatMarkdownBlockRenderers.kt:199` |
| Table cell | 13sp | 18sp | 1.38 | `ChatMarkdownBlockRenderers.kt:308-309` |
| Code | 13sp | 19sp | 1.46 | `ChatMarkdownBlockRenderers.kt:268` |

Two things stand out:

1. **User text is larger than assistant text** (16 vs 15). Gemini and ChatGPT both render the user's own message *at or below* the assistant's size — the user already knows what they typed; the answer is the content. This inverts the hierarchy.
2. **Composer line-height is 1.33** while the bubble is 1.50. Typing a 3-line draft is visibly tighter than reading the same text after send — the text "jumps" in leading at the moment of send. `ChatComposerScreens.kt:430` vs `ChatMessageBubbleScreens.kt:280`.

### 1.3 Measure (line length) is uncontrolled

Assistant text is `Modifier.fillMaxWidth()` inside a list padded `18.dp` each side (`ChatMessageListSection.kt:63`), with the assistant Surface adding `end = 4.dp` (`ChatBubbleMessageBlocks.kt:129`):

| Screen | Text width | Latin chars/line | CJK chars/line |
|---|---|---|---|
| 360dp | 320dp | ~43 | ~21 |
| 393dp | 353dp | ~47 | ~23 |
| 411dp | 371dp | ~50 | ~24 |

43–50 Latin chars is at the top of the comfortable range (45–75 is typographic guidance; Gemini targets ~50 on phones). It is *acceptable on a phone* but there is **no `maxWidth` cap**, so on a tablet or in landscape a single line runs the full display width — the classic "indie on tablets" tell. There is no `WindowSizeClass` anywhere in the project (0 hits) and no `LocalConfiguration` read.

The user bubble, conversely, is capped at a hard `280.dp` (`ChatMessageBubbleScreens.kt:211`) — 78% of a 360dp screen, 65% of a 432dp screen. On a narrow phone the bubble is *too wide* relative to the cap's intent; on a wide phone it's fine. A percentage-based cap (`fillMaxWidth(0.82f)` with a `maxWidth`) behaves consistently; a fixed 280dp does not.

### 1.4 No letter-spacing anywhere

`letterSpacing` = 0 occurrences. This matters most at the small end: `9.5sp` and `10sp` text (`ChatHistoryDrawerRows.kt:149`, `ModelSwitcherChoicePill.kt:148,174,191`) renders cramped because CJK/Latin mixes have no tracking compensation. Material 3's own `labelSmall` baseline uses `+0.5sp`; MCA strips that whenever it calls `.copy(fontSize = 10.sp)` without a `letterSpacing`.

### 1.5 Weight inflation

97 `Bold` + 54 `SemiBold` = 151 heavy weights against 5 `Normal`. When nearly everything is semi-bold, nothing is emphasised. Examples of Bold used where Medium would read better: `ChatHistoryDrawerRows.kt:126` (a pinned badge), `ChatMarkdownBlockRenderers.kt:145` (list bullets), `ChatStatusBarHeader.kt:141` (`API` chip), `ModelSwitcherChoicePill.kt:149`.

---

## 2. Spacing & rhythm

### 2.1 The scale is 4dp in name only

Padding dp values across `feature/chat` (27 distinct values, 381 call sites):

```
0dp:2   1dp:4   2dp:19  3dp:1   4dp:39  5dp:8   6dp:20  7dp:16
8dp:33  9dp:9  10dp:30 11dp:2  12dp:43 13dp:5  14dp:17 15dp:1
16dp:17 17dp:1 18dp:30 20dp:3  22dp:2  24dp:7  28dp:2  48dp:1
50dp:1  76dp:1 86dp:2
```

A real 4dp system has 5–7 values. MCA has 27, and the **odd ones are not rare outliers** — `7dp` appears 16 times, `9dp` 9 times, `13dp` 5 times, `18dp` 30 times. `18dp` in particular is used as if it were a scale token.

### 2.2 The three most important horizontal gutters all differ

| Surface | Horizontal padding | Source |
|---|---|---|
| Message list | `18.dp` | `ChatMessageListSection.kt:63` |
| Top bar | `start = 18.dp, end = 18.dp` | `ChatStatusBarHeader.kt:69` |
| Composer outer | `16.dp` | `ChatComposerScreens.kt:258` |
| User bubble inner | `17.dp` | `ChatMessageBubbleScreens.kt:228` |
| Assistant block | `end = 4.dp` + inner `2.dp` | `ChatBubbleMessageBlocks.kt:129,132` |

16 / 17 / 18 all coexist on the same vertical axis. Because the composer is 16 and the list is 18, **the composer's left edge sits 2dp outside every message's left edge** — a misalignment the eye reads as sloppiness even when it can't name it. This is the single cheapest fix in the document.

### 2.3 Vertical rhythm

- List `contentPadding = PaddingValues(top = 26.dp, bottom = 118.dp)` (`ChatMessageListSection.kt:64`). `26.dp` top is not a scale value; `118.dp` is a hand-tuned magic number that must be manually kept in sync with composer height (see §5.3).
- Item gap `Arrangement.spacedBy(18.dp)` (`ChatMessageListSection.kt:65`).
- Inside the assistant block: `spacedBy(12.dp)` (`ChatBubbleMessageBlocks.kt:133`), then the rich-text renderer adds *another* `spacedBy(6.dp)` (`ChatMarkdownBlockRenderers.kt:67`) around a body that itself uses `spacedBy(10.dp)` (`ChatMarkdownBlockRenderers.kt:85`). Three nested spacing authorities for one column.
- `ChatStatusBarHeader.kt:69` uses `top = 14.dp, bottom = 10.dp` — asymmetric by 4dp with no visual reason.
- `ChatHistoryDrawerScreens.kt:136,138` uses `Spacer(height = 28.dp)` then `Spacer(height = 34.dp)` — two arbitrary values where 24/32 would hold rhythm.

### 2.4 Magic numbers

`ChatMessageListSection.kt:64` (118), `ChatScreen.kt:276` (`.coerceAtLeast(120.dp)`), `ChatComposerScreens.kt:342` (`start = 50.dp, end = 48.dp` — asymmetric), `ChatHistoryDrawerScreens.kt:150` (`bottom = 92.dp`), `ChatComposerActionMenuViews.kt:134` (`bottom = 86.dp`), `ChatHistoryDrawerScreens.kt:182` (`end = 24.dp, bottom = 24.dp`).

---

## 3. Elevation, surfaces, borders

### 3.1 Nine shadow values, none tinted

`shadowElevation` appears at 22 sites with **7 distinct values**: `2, 5, 6, 7, 8, 10, 12, 14, 18` dp.

```
ChatComposerScreens.kt:263         shadowElevation = 14.dp   <- composer
ChatModelSwitcherScreens.kt:79     shadowElevation = 18.dp   <- dropdown
ChatComposerActionMenuViews.kt:141 shadowElevation = 18.dp   <- action menu
ChatHistoryDrawerScreens.kt:120    shadowElevation = 8.dp    <- header capsule
ChatHistoryDrawerScreens.kt:187    shadowElevation = 12.dp   <- new-chat FAB
ChatHistoryDrawerRows.kt:172       shadowElevation = 18.dp   <- row menu
ChatStatusBarHeader.kt:76          shadowElevation = 6.dp    <- history button
ChatStatusBarHeader.kt:98          shadowElevation = 5.dp    <- model pill
ChatStatusBarHeader.kt:180         shadowElevation = 6.dp    <- action capsule
ChatDeviceStatusScreens.kt:217     shadowElevation = 10.dp
ChatDeviceStatusScreens.kt:464     shadowElevation = 8.dp
ImageGenerationResultScreens.kt:91,144 shadowElevation = 7.dp
```

All of these are **default black shadows**. Compose's `Surface(shadowElevation = n)` draws `DefaultShadowColor` = pure black at Material's ambient/spot alphas. At 14dp and 18dp on a `#F8F9FA` background this produces the soft grey halo that reads as "web app card circa 2016". Gemini and ChatGPT both either (a) stay at 0–3dp and use tonal layering, or (b) use a colour-tinted shadow.

**Zero tinted shadows in the project.** The fix is a 3-line helper — no colour value changes, because the shadow tint is derived from `colorScheme.onSurface` at low alpha, not from the palette.

### 3.2 Surface separation is nearly invisible

Computed contrast between adjacent surfaces (light):

| Pair | Ratio |
|---|---|
| user bubble `#EEF4FF` vs page `#F8F9FA` | **1.047:1** |
| composer shell `#FEFEFF` vs page `#F8F9FA` | **1.046:1** |
| input field `#F3F7FF` vs shell `#FEFEFF` | **1.065:1** |
| input field `#F3F7FF` vs page `#F8F9FA` | **1.019:1** |
| `surface` `#FFFFFF` vs page `#F8F9FA` | **1.054:1** |

1.02–1.07:1 is below the ~1.1:1 threshold where a boundary is reliably perceptible on a mid-range LCD in daylight. That is *why* the composer needs `shadowElevation = 14.dp`: the shadow is compensating for a surface that cannot separate itself tonally. Fix the layering and the 14dp shadow can drop to 2–3dp.

Dark mode is better (1.16–1.24:1) because `#121212`/`#202124`/`#303134` are genuinely distinct steps.

### 3.3 Hairline borders are inconsistent

`BorderStroke(` at 23 sites, `.border(` at 1, `HorizontalDivider` at 58. There is no border token: some borders use `outline` (`ChatHistoryDrawerRows.kt:208`, alpha 0.4), some `outlineVariant` (`ChatMarkdownBlockRenderers.kt:287`, alpha 0.74), some `outlineVariant.copy(alpha = 0.7f)` (`ChatMessageTextRenderers.kt:114`), some `outlineVariant` raw (`ChatDeviceStatusScreens.kt:216`).

The critical issue: **`outlineVariant` is never declared in `McaTheme.kt`.** The scheme declares `outline` only (`McaTheme.kt:40` light `#DADCE0`, `:60` dark `#5F6368`). All 14 `outlineVariant` reads therefore fall through to the **Material 3 baseline purple-grey** `#CAC4D0` (light) / `#49454F` (dark):

| Border | Contrast vs surface |
|---|---|
| M3 default `outlineVariant` light `#CAC4D0` on `#FFFFFF` | **1.70:1** |
| MCA `outline` light `#DADCE0` on `#FFFFFF` | **1.37:1** |
| M3 default `outlineVariant` dark `#49454F` on `#202124` | **1.72:1** |

So the app's borders are drawn in **two different greys that belong to two different design systems** — the declared Google-grey and an undeclared M3 purple-grey. On the blue-tinted `#F3F7FF` input field, a `#CAC4D0` border is visibly violet. This is a *real* colour bug that violates the user's "don't change the palette" rule in the opposite direction: something is already off-palette.

Also note `errorContainer` / `onErrorContainer` are undeclared and used at `ModelHubSharedComponents.kt:54-55` — again M3 baseline.

### 3.4 Cards and chips

- `CardBox` uses `CardDefaults.cardElevation(defaultElevation = 1.dp)` with `RoundedCornerShape(18.dp)` (`ModelHubSharedComponents.kt:123-124`) — this one is fine.
- `EmptyChatPanel` uses `RoundedCornerShape(8.dp)` + `tonalElevation = 1.dp` (`ChatBackgroundScreens.kt:348-349`). 8dp is the smallest radius in the app and reads as "Material 2 button", not "2026 chat app". Every other card is 12–18dp.
- Corner radii: **15 distinct values** (2, 3, 8, 10, 11, 12, 14, 18, 20, 22, 24, 26, 30, 36, 99) across 163 `RoundedCornerShape` calls, plus 76 `CircleShape`. The bubble's `20/6` is correct and should stay; the *rest* should collapse to 3 values.
- Selected history row uses `shadowElevation = if (item.selected) 5.dp else 0.dp` (`ChatHistoryDrawerRows.kt:91`) — a 5dp black shadow as a *selection* indicator. Selection should be a tonal fill, not a shadow.

### 3.5 Dark mode code block is nearly invisible

`CodeBlock` hardcodes `color = Color(0xFF1F2937)` (`ChatMarkdownBlockRenderers.kt:218`) in **both** themes:

| | Ratio |
|---|---|
| light: block `#1F2937` on page `#F8F9FA` | 13.93:1 (separates) |
| **dark: block `#1F2937` on page `#121212`** | **1.28:1** |
| **dark: block `#1F2937` on card `#202124`** | **1.10:1** |

In dark mode the code block has almost no boundary — it reads as a hole rather than a surface. This is a dark-parity defect, and the fix (a dark-theme variant of the same block colour, or a `surfaceVariant`-based block) does not change the light palette at all.

---

## 4. Motion

### 4.1 Full inventory

| # | Location | Property | Spec | Easing |
|---|---|---|---|---|
| 1 | `SmoothPage.kt:48` | page enter slide | `tween(240)` | unspecified |
| 2 | `SmoothPage.kt:50` | page enter fade | `tween(140)` | unspecified |
| 3 | `SmoothPage.kt:67` | page dismiss | `tween(180)` | unspecified |
| 4 | `SwipeBackPage.kt:49` | page dismiss | `tween(180)` | unspecified |
| 5 | `SwipeBackPage.kt:71` | back-cancel settle | `tween(160)` | unspecified |
| 6 | `SwipeBackPage.kt:95` | page enter slide | `tween(260)` | unspecified |
| 7 | `SwipeBackPage.kt:97` | page enter fade | `tween(180)` | unspecified |
| 8 | `ChatMessageTextRenderers.kt:147` | details expand | `fadeIn(tween(100)) + expandVertically(tween(160))` | unspecified |
| 9 | `ChatMessageTextRenderers.kt:148` | details collapse | `fadeOut(tween(80)) + shrinkVertically(tween(120))` | unspecified |
| 10 | `ChatModelSwitcherScreens.kt:222` | source capsule in | `fadeIn(120) + slideInHorizontally(160, -it/4)` | unspecified |
| 11 | `ChatModelSwitcherScreens.kt:223` | source capsule out | `fadeOut(100)` | unspecified |
| 12 | `ChatModelSwitcherScreens.kt:242` | reasoning capsule in | `fadeIn(120) + slideInHorizontally(160, -it/4)` | unspecified |
| 13 | `ChatModelSwitcherScreens.kt:243` | reasoning capsule out | `fadeOut(100)` | unspecified |
| 14 | `ChatComposerActionMenuViews.kt:126-132` | action menu in (**dead**) | `fadeIn(120) + slideInHorizontally(180, -it/5)` | unspecified |
| 15 | `ChatComposerResearchModeViews.kt:114-117` | research capsule in (**dead**) | `fadeIn(120) + slideInHorizontally(160, -it/4)` | unspecified |
| 16 | `McaBrandMarks.kt:327` | assistant mark spin | `tween(1300, LinearEasing)`, infinite | Linear |
| 17 | `ImageGenerationResultScreens.kt:314` | image phase | `tween(1600, LinearEasing)`, infinite | Linear |
| 18 | `ImageGenerationResultScreens.kt:320` | image pulse | `tween(900)`, Reverse, infinite | unspecified |
| 19 | `AppMenuTransitions.kt:293` | app menu dismiss | `tween(180)` | unspecified |

Counts: `tween` **64** call sites, `spring` **0**, `AnimatedVisibility` **12**, `Crossfade` **0**, `AnimatedContent` **0**, `animateContentSize` **0**, `animateItem` **0**, `animateFloatAsState` **1**, `animateDpAsState` **0**, `animateColorAsState` **0**, `graphicsLayer` **2**, `rememberInfiniteTransition` **19**, `infiniteRepeatable` **20**.

Duration histogram: `60, 80, 90x2, 100x5, 120x7, 140x4, 160x6, 180x7, 240x2, 260, 350, 900, 1200, 1300, 1600` ms.

### 4.2 Assessment: reads as "electronic", not "physical"

Three structural reasons:

1. **Zero springs.** Every user-initiated transition is a fixed-duration `tween`. A `tween` has no velocity and no interruption behaviour: grab a page mid-dismiss and it restarts from zero. Gemini/ChatGPT use `spring(stiffness = ...)` so a gesture that's already moving continues at its current velocity. `spring(` = 0 project-wide is the single most legible "not native" signal in this app.
2. **No named easing.** `FastOutSlowInEasing`, `LinearOutSlowInEasing`, `CubicBezierEasing`, `EaseOut` — all 0. The only `easing =` (5 occurrences) are `LinearEasing` for infinite spinners. So all 64 tweens use `tween`'s default curve, which is `FastOutSlowInEasing` — not wrong, but it means a 60ms collapse and a 260ms page entry share the same curve regardless of distance travelled. Physical motion uses a shorter, snappier curve for short distances.
3. **Two dead animations.** `ChatComposerActionMenuViews.kt:126` and `ChatComposerResearchModeViews.kt:114` both declare `AnimatedVisibility(visible = true, enter = ...)`. When the composable first enters composition with `visible` already `true`, `AnimatedVisibility` does **not** run the `enter` transition — the initial state is the target state. So the carefully written `fadeIn(120) + slideInHorizontally(180, -it/5)` **never plays**. The action sheet pops in with zero animation, which is exactly the "cheap" feel the code was trying to avoid.

### 4.3 Highest-leverage motion touchpoints

Ranked by perceived-quality-per-line:

| Rank | Touchpoint | Now | Why it matters |
|---|---|---|---|
| 1 | **Send button** | Static `FloatingActionButton`, no press scale, no icon crossfade to Stop | The most-tapped control in the app. Gemini scales the FAB ~0.92 on press and crossfades send<->stop. |
| 2 | **Dead menu enters** | `AnimatedVisibility(visible = true)` -> no animation | Two one-line fixes (`MutableTransitionState(false).apply { targetState = true }`) restore motion the author already wrote. |
| 3 | **Message appear** | New message is inserted with no transition; `animateItem` = 0 | ChatGPT's list settles with a 180ms item-placement animation; MCA jumps. |
| 4 | **Page transitions** | `tween(240)` slide, `tween(180)` dismiss, no velocity | `spring(stiffness = Spring.StiffnessMediumLow)` + predictive-back velocity is the Gemini feel. |
| 5 | **Streaming text** | No cursor/caret at all; text just grows | A 1.2s blink or a 2dp fading caret makes generation legible as "alive". |
| 6 | **Stop button** | Instant icon swap to `Icons.Default.Stop` | Should crossfade from Send over ~150ms; the abrupt swap is the "did it register?" moment. |
| 7 | **Model switch** | `DropdownMenu` open/close, plus `AnimatedVisibility` capsules | Dropdown open uses M3 default (fine); the inline capsule reveal is the one to spring. |

---

## 5. State design

### 5.1 Loading is spinner-first

`CircularProgressIndicator(` usage: **10**. `LinearProgressIndicator(`: **20**. Skeleton: **0** (the only "skeleton" hits are engine-code comments, not UI).

The bare-spinner case is `ChatBubbleMessageBlocks.kt:194`:

```kotlin
} else if (previewPending) {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
}
```

An 18dp indeterminate spinner alone in a full-width assistant row — no label, no skeleton line, no reserved height. This is the exact visual of "app is thinking... maybe". Gemini reserves the message's line box and shimmers it.

`AppStartupScreen.kt:28` is a bare `CircularProgressIndicator()` + three lines of text on a `28.dp`-padded column. Cold start is the *first* impression and it is a plain spinner on a plain page — no logo, no motion, no progress estimate, even though the screen already tracks `state.slow` (`AppStartupScreen.kt:31`).

### 5.2 No first-token affordance

`PendingReasoningPanel` (`ChatReasoningPerformanceScreens.kt:152-264`) does real work — elapsed seconds, generation phase label, token progress, persist progress — but it is **text plus a 2dp `LinearProgressIndicator`**. Between "send tapped" and "first token", the user sees a phase label and a progress bar, not the model's presence. A three-dot pulse or the existing `AnimatedMcaAssistantMark` ring (`McaBrandMarks.kt:318-358`) with an eased opacity would fill that latency with *personality* rather than telemetry.

### 5.3 Scroll anchoring has no user-intent check

`ChatScreen.kt:251-258`:

```kotlin
LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket, ...) {
    if (browserSearchCardVisible) {
        listState.scrollToItem(state.messages.size + if (state.messages.isEmpty()) 1 else 0)
    } else if (state.messages.isNotEmpty()) {
        listState.scrollToItem(state.messages.lastIndex)
    }
}
```

`isScrollInProgress` = **0** occurrences. `animateScrollToItem` = **0**. So:

- Streaming **yanks** the list to the last item every 512 characters (`STREAMING_SCROLL_CHAR_STEP`, `ChatMarkdownRenderSupport.kt:314`) — a hard `scrollToItem`, not even animated.
- If the user scrolls up to re-read while generating, the next bucket **snaps them back down**. There is no "you're reading history, hold position" guard.
- There is no "jump to latest" button to get back — `scrollToBottom`/`newMessages` affordances: **0**.

This is the most damaging *behavioural* craft defect in the app, and it's ~8 lines to fix.

### 5.4 Empty state

`EmptyChatPanel` (`ChatBackgroundScreens.kt:339-371`) is an 8dp-radius card with `tonalElevation = 1.dp`, `padding(18.dp)`, containing the literal string `"MCA"` at `headlineSmall`, one line of body, and up to three full-width buttons. There is no logo mark, no greeting, no model identity, no suggestion chips. Gemini's empty state is the brand moment: large gradient mark, "Hello, <name>", 3–4 prompt chips. MCA's is a settings card.

`HistoryEmptyState` (`ChatHistoryDrawerRows.kt:57-71`) is the same pattern at 18dp radius.

### 5.5 Error state

`ComposerStatusMessage` (`ChatComposerStatusViews.kt:91-132`) classifies errors by **substring matching Chinese words**:

```kotlin
internal fun composerStatusIsError(message: String): Boolean =
    listOf("失败", "无法", "未发送", "未加载", "请先", "超过").any(message::contains)
```

Beyond the i18n fragility, the visual treatment is a 16dp `Info` icon + `labelSmall` text + a close button — error and info differ only by text colour. No `errorContainer` surface, no retry affordance in the banner.

### 5.6 Optimistic UI: none

`ChatScreen.kt:170-202` (`submitChatMessage`) does an async preflight (`chatAttachmentPreflightRunning`) before calling `onSend`. During that window the send button shows a spinner **inside** the FAB (`ChatComposerScreens.kt:450-455`) and the input stays populated. There is no optimistic user-message echo — the user's own message does not appear until the send pipeline produces it. ChatGPT appends the user message instantly and shows the failure on the bubble if it fails.

---

## 6. Micro-details

Complete inventory of what separates flagship from indie:

| Detail | Gemini | ChatGPT | MCA | Evidence |
|---|---|---|---|---|
| Haptic feedback | yes | yes | **no** | `HapticFeedback` / `LocalHapticFeedback` = 0 |
| Custom ripple | tonal, subtle | tonal | default M3 | `ripple(` = 0, `LocalIndication` = 0 |
| Press/scale state | yes | yes | **no** | `animateFloatAsState` = 1 (unrelated), `graphicsLayer` = 2 |
| Overscroll stretch | yes (Android 12+) | yes | **no** | `OverscrollEffect` = 0 |
| IME transition | animated | animated | **none** | `ChatScreen.kt:319` bare `.imePadding()`; `adjustResize` (`MainActivity.kt:57`) |
| List edge fade | yes | yes | **no** | only gradient is the table hint, `ChatMarkdownBlockRenderers.kt:329-333` |
| Long-press menus | message actions | message actions | partial | `combinedClickable` = 12, but 0 on message bubbles |
| Text selection | native | native | **partial** | `SelectionContainer` present on assistant text (`ChatBubbleMessageBlocks.kt:183,196`) but **not** on user bubble text (`ChatMessageBubbleScreens.kt:272`) |
| Selection colour | themed | themed | **default purple** | `LocalTextSelectionColors` / `TextSelectionColors` = 0 |
| Copy affordance | inline on hover/long-press | inline | 17dp icon in a 32dp target | `ChatMessageBubbleScreens.kt:295-305`, `ChatAssistantActionViews.kt:120-130` |
| Scroll-to-bottom | FAB appears | FAB appears | **none** | 0 |
| "New messages" chip | yes | yes | **none** | 0 |
| Image placeholder | blurhash/thumb | blurhash | **none** | `rememberChatImageBitmap` returns `null` then full bitmap — `ImageAssetIoSupport.kt:59-73`; no `LruCache` in chat |
| Code block chrome | language + copy + wrap toggle | language + copy | language + copy (good) | `ChatMarkdownBlockRenderers.kt:230-262` |
| Code line numbers | optional | no | no | — |
| Table overflow affordance | yes | yes | **yes** (good) | `ChatMarkdownBlockRenderers.kt:325-338` |
| Streaming caret | yes | yes | **no** | 0 |

### 6.1 Tap targets below the 48dp guideline

19 `IconButton`s are sized under 44dp. The worst:

| Control | Size | Source |
|---|---|---|
| History row overflow menu | **26.dp** | `ChatHistoryDrawerRows.kt:156` |
| Composer image-mode close | **28.dp** | `ChatComposerScreens.kt:320` |
| Message copy / regenerate / more | **32.dp** x3 | `ChatAssistantActionViews.kt:110,122,134` |
| User bubble copy | **32.dp** | `ChatMessageBubbleScreens.kt:297` |
| User bubble pin | **32.dp** | `ChatMessageBubbleScreens.kt:287` |
| Assistant pin (collapsed) | **32.dp** | `ChatBubbleMessageBlocks.kt:265` |
| Status message dismiss | **28.dp** | `ChatComposerStatusViews.kt:123` |

Android's minimum is 48dp. Compose's `IconButton` default is 48dp; every one of these overrides it *down*. Combined with `HapticFeedback` = 0, this is the tactile reason the app feels less premium than it looks.

### 6.2 Icon sizing is ad hoc

Icon sizes in the chat feature: 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24, 25, 28 dp. A real system has 16/20/24. The `17.dp` and `19.dp` values exist only because someone nudged them per screen (`ChatAssistantActionViews.kt:115,127,139` = 17; `ChatComposerScreens.kt:452` = 19).

### 6.3 No scroll position restoration discipline

`LazyColumn` key includes index: `key = { index, message -> "${message.role.name}:${message.createdAt}:$index" }` (`ChatMessageListSection.kt:81`). Including `index` in a stable key defeats the purpose — when a message is deleted, every subsequent item's key changes and the whole tail recomposes without any `animateItem` to smooth it.

---

## 7. Dark mode parity and contrast

### 7.1 Computed WCAG ratios (both schemes)

**Light** (`background #F8F9FA`, `surface #FFFFFF`, `surfaceVariant #F1F3F4`):

| Foreground | On | Ratio | AA normal (4.5) |
|---|---|---|---|
| `onSurface` `#202124` | background | **15.27:1** | pass |
| `onSurface` `#202124` | surface | **16.10:1** | pass |
| `onSurfaceVariant` `#5F6368` | background | **5.74:1** | pass |
| `onSurfaceVariant` `#5F6368` | surface | **6.05:1** | pass |
| `onSurfaceVariant` `#5F6368` | surfaceVariant | **5.44:1** | pass |
| `primary` `#1A73E8` | surface | **4.51:1** | pass (barely) |
| `primary` `#1A73E8` | background | **4.27:1** | FAIL |
| `error` `#EA4335` | surface | **3.92:1** | FAIL |
| `outline` `#DADCE0` | surface | **1.37:1** | FAIL (needs 3:1) |
| `onPrimaryContainer` `#185ABC` | primaryContainer | 5.68:1 | pass |

**Dark** (`background #121212`, `surface #202124`, `surfaceVariant #303134`):

| Foreground | On | Ratio | AA normal |
|---|---|---|---|
| `onSurface` `#E8EAED` | background | **15.54:1** | pass |
| `onSurface` `#E8EAED` | surface | **13.36:1** | pass |
| `onSurfaceVariant` `#9AA0A6` | background | **7.09:1** | pass |
| `onSurfaceVariant` `#9AA0A6` | surface | **6.10:1** | pass |
| `onSurfaceVariant` `#9AA0A6` | surfaceVariant | **4.93:1** | pass |
| `primary` `#8AB4F8` | background | **8.89:1** | pass |
| `error` `#F28B82` | surface | **6.74:1** | pass |
| `outline` `#5F6368` | surface | **2.66:1** | FAIL (needs 3:1) |
| `onPrimaryContainer` `#E8F0FE` | primaryContainer `#1A73E8` | **3.93:1** | FAIL |

**Verdict: the base palette is good.** Both schemes clear AA for all body and secondary text. The failures are all in *derived* values — alpha reductions and undeclared tokens — not in the palette itself.

### 7.2 Where contrast actually fails (alpha reductions)

These are real failures created by multiplying an already-correct token:

| Style | Computed | Where |
|---|---|---|
| `onSurfaceVariant.copy(alpha = 0.72f)` on light bg | **3.16:1** FAIL | `ChatHistoryDrawerRows.kt:51`, `:150` |
| `onSurfaceVariant.copy(alpha = 0.74f)` reasoning body, light | **3.29:1** FAIL | `ChatReasoningPerformanceScreens.kt:126` |
| `onSurfaceVariant.copy(alpha = 0.78f)` on light surface | **3.71:1** FAIL | `ChatMessageBubbleScreens.kt:303` |
| `onSurfaceVariant.copy(alpha = 0.82f)` on light bg | **3.87:1** FAIL | `ChatReasoningPerformanceScreens.kt:315` |
| same, dark (`0.74`) | **4.40:1** FAIL (just under) | `ChatReasoningPerformanceScreens.kt:126` |
| same, dark (`0.72`) | **4.22:1** FAIL | `ChatHistoryDrawerRows.kt:51` |

`onSurfaceVariant` is *already* the "secondary text" role. Multiplying it by 0.72–0.82 makes the text fail AA. There is even a comment in the codebase that already discovered this for one case — `ChatReasoningPerformanceScreens.kt:387-389` notes the same pattern was removed from `GenerationPerformanceLine` — but the fix was not applied to `ReasoningPanel` or the drawer.

### 7.3 Dark-mode-specific defects

1. **Code block** — fixed `#1F2937` gives 1.28:1 separation on `#121212` (see §3.5).
2. **Send button** — `McaPrimaryBlue #3F7DE8` on dark shell `#202124` is **4.07:1**; the theme's dark `primary` is `#8AB4F8` at 8.89:1. The FAB ignores the dark primary entirely.
3. **Window chrome is always light.** `styles.xml:2` uses `parent="android:style/Theme.Material.Light.NoActionBar"` with `statusBarColor`/`navigationBarColor` hardcoded `#FAFAFA`, and there is **no `values-night/`** directory. `McaTheme.kt:73-81` corrects the bars in a `SideEffect` — i.e. *after* first frame. On a dark-theme cold start the user sees a **light** status/nav bar and (from `Theme.Material.Light`) a **light window background** flash before Compose takes over.
4. **No `enableEdgeToEdge()`** anywhere (0 hits). The app targets SDK 36, where edge-to-edge is enforced; combined with `Scaffold { padding -> Box(Modifier.padding(padding)) }` at `MainActivity.kt:233-234`, insets handling is split between the platform theme and Compose with no single owner. `statusBarsPadding` appears **0** times.

---

## 8. Information density & hierarchy

### 8.1 Top bar carries 11 distinct elements in ~68dp

`ChatStatusBarHeader.kt:65-194`:

1. 44dp circular Surface (shadow 6dp) -> menu IconButton
2. 12dp spacer
3. Full-width circular Surface (shadow 5dp) containing:
   - 24dp `McaLogoMark`
   - model name (13sp SemiBold)
   - subtitle (11sp onSurfaceVariant)
   - **third** line: cache evidence (10sp onSurfaceVariant @0.78) — `ChatStatusBarHeader.kt:122-130`
   - `API` chip (10sp Bold)
   - 15dp chevron
4. 12dp spacer
5. Circular Surface (shadow 6dp) with **two** 38dp IconButtons (new chat, app menu)

So the header is **three overlapping elevated capsules**, one of which has **three stacked text lines** in an ~44dp pill (13sp + 11sp + 10sp = 34sp of text plus line boxes ~ 40dp — it overflows the pill on any device with large font scale). `labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)` for cache evidence is telemetry, not navigation, and it competes with the model name.

Gemini's top bar: back/menu, model chip (name only), new-chat, avatar. Four elements, one elevation level.

### 8.2 Composer nests four surfaces

`ChatComposerScreens.kt:254-476`:

- outer `Surface` radius 36dp, shadow 14dp (`:260-261`)
- 7dp inner padding + `navigationBarsPadding()` (`:265-266`)
- "+" button: 40dp Box, `CircleShape`, tinted fill (`:389-398`)
- text field: `Surface` `CircleShape`, `#F3F7FF` (`:400-408`)
- send: `FloatingActionButton` 40dp, elevation 0 (`:445-448`)

Three different circular shapes at three different fills, plus a 36dp-radius shell. It is *legible* but it is busy: the eye has to parse shell -> field -> FAB. Gemini uses one rounded rect with the "+" and send as plain icons inside it. The user's constraint (CircleShape input bar) is fine — the excess is the 14dp shadow and the `#F3F7FF` field that only separates at 1.065:1, forcing the shadow.

### 8.3 Message action row is 3x32dp of low-contrast icons

`ChatAssistantActionViews.kt:101-205` — regenerate / copy / more, each `32.dp` with 17dp icons at `tint.copy(alpha = 0.78f)`. On light this is **3.71:1** (§7.2) at 17dp. The row is `Arrangement.End` and always visible for the last assistant message (no hover/long-press reveal). ChatGPT/Gemini show actions on long-press or with a subtle fade; always-on 32dp icons at 3.7:1 look like toolbar leftovers.

### 8.4 Chrome vs content ratio

At rest (no messages): the top bar consumes ~68dp, the empty panel is a card, the composer ~66dp + insets. On a 411x891dp phone that is roughly **15% chrome**. Gemini's at-rest chat is ~10% (smaller header, no card).

---

## 9. Ten things that make it read as indie

Each fix is colour-preserving: it changes sizes, weights, durations, alphas, or derives tints from existing roles — never a palette value.

### 1. No type scale — 15 font sizes, 16 line heights, zero tracking

**Fix:** declare one `Typography` in `McaTheme.kt:84-87` and stop per-call `.copy(fontSize = ...)`.

```kotlin
// McaTheme.kt — add before MaterialTheme(...)
private val McaTypography = Typography(
    bodyLarge   = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium  = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodySmall   = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelLarge  = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium),
    labelSmall  = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.SemiBold),
    titleLarge  = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = 0.sp,  fontWeight = FontWeight.SemiBold)
)
MaterialTheme(colorScheme = colorScheme, typography = McaTypography, content = content)
```

Then delete the 73 `fontSize =` overrides. **Largest single quality jump in this list.**

### 2. Two dead animations — the action sheet has no entrance

**Fix:** `ChatComposerActionMenuViews.kt:126` and `ChatComposerResearchModeViews.kt:114` — `AnimatedVisibility(visible = true, ...)` never plays `enter`. Replace with a `MutableTransitionState`:

```kotlin
val enterState = remember { MutableTransitionState(false).apply { targetState = true } }
AnimatedVisibility(
    visibleState = enterState,
    enter = fadeIn(tween(120)) + slideInHorizontally(tween(180)) { -it / 5 },
    exit = fadeOut(tween(100))
) { ... }
```

Restores motion the author already wrote. **~4 lines, two files.**

### 3. Zero haptics on the most-tapped controls

**Fix:** add `LocalHapticFeedback` at send, stop, model switch, copy, and destructive confirms. `minSdk = 26`, so `HapticFeedbackConstants.CONFIRM` (API 30) needs a fallback.

```kotlin
val haptics = LocalHapticFeedback.current
// send / stop / model-loaded / copied:
if (Build.VERSION.SDK_INT >= 30) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
else haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
```

Gemini fires `Confirm` on send and `Reject` on a blocked action. **~6 lines, 5 call sites.** Highest perceived-quality-per-line in the whole document.

### 4. Send button has no press feedback and no send<->stop transition

**Fix:** in `ChatComposerScreens.kt:440-473`, wrap the FAB content in `AnimatedContent` with a 150ms fade, and add a press scale:

```kotlin
val interaction = remember { MutableInteractionSource() }
val pressed by interaction.collectIsPressedAsState()
val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = Spring.StiffnessMedium))
FloatingActionButton(
    onClick = ...,
    interactionSource = interaction,
    modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }
) {
    AnimatedContent(
        targetState = isGenerating,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }
    ) { generating ->
        Icon(if (generating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, ...)
    }
}
```

Keep `containerColor = McaPrimaryBlue` — unchanged.

### 5. Composer is 14dp of black shadow compensating for a 1.065:1 surface

**Fix:** drop `shadowElevation` from `14.dp` to `2.dp` (`ChatComposerScreens.kt:263`) and give the shell a hairline `BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.5f))`. A 1dp border separates surfaces more crisply than a 14dp blur and costs no GPU. Then the `#F3F7FF` field at 1.065:1 vs `#FEFEFF` stops mattering.

### 6. Zero springs — every transition is fixed-duration

**Fix:** replace `tween` with `spring` on every *user-initiated* transition (page enter/dismiss, capsule reveal, details expand). Keep `tween` for infinite/ambient loops.

```kotlin
// SmoothPage.kt:48
enter = slideInHorizontally(
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)
) { it }
// SwipeBackPage.kt:49,71 — dismissal and cancel settle
animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
```

This is what makes gestures feel like they have mass. **~8 edits.**

### 7. Streaming yanks the list; no scroll-to-bottom, no user-intent guard

**Fix:** in `ChatScreen.kt:251-258`, gate the auto-scroll on user intent and animate it; add a "jump to latest" pill.

```kotlin
val userScrolledAway by remember {
    derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last < listState.layoutInfo.totalItemsCount - 2
    }
}
LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket) {
    if (!userScrolledAway && state.messages.isNotEmpty()) {
        listState.animateScrollToItem(state.messages.lastIndex)
    }
}
```

Then render a 40dp circular `Surface` with `Icons.Default.KeyboardArrowDown` when `userScrolledAway && state.isGenerating`, `shadowElevation = 2.dp`. **~15 lines, fixes the worst behavioural defect.**

### 8. Selection colour is Material purple; user-bubble text isn't selectable

**Fix:** provide themed selection colours (derived, no new hex):

```kotlin
CompositionLocalProvider(
    LocalTextSelectionColors provides TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    )
) { content() }
```

Wrap in `McaTheme`. Then add `SelectionContainer { }` around `PagedPlainMessageText` at `ChatMessageBubbleScreens.kt:272` — assistant text is selectable, user text is not, which is backwards.

### 9. Error/secondary text fails AA because of stacked alpha

**Fix:** delete the `alpha = 0.72f / 0.74f / 0.78f / 0.82f` multipliers on `onSurfaceVariant` — it is already the secondary role. Six sites: `ChatHistoryDrawerRows.kt:51,150`, `ChatReasoningPerformanceScreens.kt:126,315`, `ChatMessageBubbleScreens.kt:303`, `ChatAssistantActionViews.kt:128,140`. Each becomes `onSurfaceVariant` at full alpha. Contrast goes 3.16 -> 5.74 (light) and 4.22 -> 7.09 (dark). **Zero visual "loudness" cost** because the role already encodes hierarchy.

### 10. Empty state is a settings card

**Fix:** `ChatBackgroundScreens.kt:339-371` — replace the `Surface` with a centred column: `McaLogoMark(size = 56.dp, cornerRadius = 16.dp)` (already exists, unchanged), `headlineSmall` greeting, one `bodyMedium` line, then 3 suggestion chips as `Surface(CircleShape, color = surfaceVariant)` rows. Delete `RoundedCornerShape(8.dp)` and `tonalElevation = 1.dp`. This is the only screen where the brand mark appears at a size that reads as a brand.

---

## 10. What's already good — do not touch

These are correct calls that already match or beat the reference apps. Any refactor should preserve them exactly.

1. **Assistant messages are full-width with no bubble** (`ChatBubbleMessageBlocks.kt:121-130`, `Surface(color = Color.Transparent, shape = RoundedCornerShape(0.dp))`). This is the modern correct answer and matches Gemini. Wrapping it in a card would be a regression.
2. **User bubble corner radius `20/6`** (`ChatMessageBubbleScreens.kt:217-222`) — `topStart 20 / topEnd 20 / bottomStart 20 / bottomEnd 6`. The asymmetric tail is a deliberate, distinctive signature. Keep.
3. **`CircleShape` composer** (`ChatComposerScreens.kt:260` radius 36dp on a ~66dp tall shell, plus `CircleShape` field). Keep the shape; only the shadow needs work.
4. **`McaLogoMark`** (`McaBrandMarks.kt:267-315`) — hand-drawn bubble + 4-point star + 3 dot highlights, pure `Canvas`, no bitmap. It scales cleanly (used at 12–66dp) and is genuinely good. Its one problem is that it appears *too small and too rarely*; fix by using it larger in the empty state and startup screen, not by redrawing it.
5. **`AnimatedMcaAssistantMark`** (`McaBrandMarks.kt:318-358`) — 1300ms linear ring with a blue arc + green accent arc. This is the app's most "designed" motion asset. It is currently only used for reasoning/generating. Keep the asset; consider using it as the first-token indicator.
6. **Table horizontal-overflow gradient** (`ChatMarkdownBlockRenderers.kt:325-338`) — a *functional* edge fade gated on `scrollState.canScrollForward`, with a comment explaining it exists to signal truncation rather than decorate. This is exactly the right instinct and the pattern should be copied to the message list edge.
7. **Code block chrome** (`ChatMarkdownBlockRenderers.kt:217-275`) — language/file label + copy button with a 1400ms confirmation + `FontFamily.Monospace`. Matches ChatGPT. Only the dark-mode background colour needs attention.
8. **Markdown renderer depth** — headings (`:105-120`), ordered/unordered lists (`:137-182`), quotes (`:185-204`), tables with header row + cell dividers (`:278-339`), dividers. Most indie chat apps have none of this.
9. **Paged long-text rendering** (`MESSAGE_RENDER_PAGE_CHARS = 16_384`, `ChatMarkdownRenderSupport.kt:313`) with a "show more (n/N)" affordance. A real performance decision that protects the frame budget on long answers.
10. **Predictive back** — `SystemBackMotionHandler` (`BackMotion.kt:26-67`) wired to both `SwipeBackPage.kt:60-83` and `MainActivity`, with IME-awareness (`SwipeBackPage` guards on `WindowInsets.isImeVisible`). Most apps never implement this.
11. **`SmoothRightToLeftPage` / `SwipeBackPage` deduplication** (`core/designsystem/SmoothPage.kt`) — the shared 240/140/180ms page motion is consistent across Agent/Models/API/Settings because it was extracted rather than copy-pasted.
12. **`ContentScale.Crop` on attachments with `inSampleSize` decoding** (`ImageAssetIoSupport.kt:45-56`) — bounds-first decode, power-of-two sampling. Correct and cheap.
13. **Semantics discipline** — `mergeDescendants`, `stateDescription`, `liveRegion = LiveRegionMode.Polite` (`ChatComposerStatusViews.kt:105`), `contentDescription` on nearly every icon, `CustomAccessibilityAction` in the device-status panel. Better than most production chat apps.
14. **`McaInputField #F3F7FF` / `McaInputText #1F2937`** pair — 13.67:1 contrast, a genuinely comfortable input surface. Keep the values.

---

## 11. Exact-value prescriptions

All grounded in what the code already uses. Nothing here invents a new colour.

### 11.1 Chat body text

| Property | Current | Prescribed | Rationale |
|---|---|---|---|
| assistant body size | 15sp (`ChatMarkdownBlockRenderers.kt:126`) | **15sp** | keep — correct for CJK+Latin |
| assistant body lineHeight | 23sp (1.53) | **23sp** | keep — already correct |
| assistant body letterSpacing | 0 | **0.1sp** | optical; invisible at 15sp but fixes CJK/Latin mix |
| assistant body maxWidth | none (`fillMaxWidth`) | **640.dp** via `fillMaxWidth().widthIn(max = 640.dp)` | stops full-bleed on tablets; no effect on phones |
| assistant horizontal padding | `18.dp` list + `4.dp` block (`ChatMessageListSection.kt:63`, `ChatBubbleMessageBlocks.kt:129`) | **20.dp** list, **0.dp** block | one gutter, matches Gemini |
| user bubble size | 16sp (`ChatMessageBubbleScreens.kt:280`) | **15sp / 22sp** | user text should not exceed assistant text |
| user bubble maxWidth | `280.dp` fixed (`ChatMessageBubbleScreens.kt:211`) | `fillMaxWidth(0.82f)` + `widthIn(max = 560.dp)` | percentage scales; fixed dp does not |
| user bubble padding | `17.dp / 13.dp` (`ChatMessageBubbleScreens.kt:228`) | **16.dp / 11.dp** | on-scale; visually identical |
| composer text | 15sp/20sp (`ChatComposerScreens.kt:429-430`) | **15sp / 22sp** | matches bubble leading; no jump on send |
| reasoning body | 13sp/20sp @0.74 alpha (`ChatReasoningPerformanceScreens.kt:126-129`) | **13sp / 20sp, alpha 1.0** | 3.29:1 -> 5.74:1 |
| code | 13sp/19sp (`ChatMarkdownBlockRenderers.kt:268`) | **13sp / 20sp** | 1.54 ratio for monospace |
| labelSmall floor | 9.5sp (`ChatHistoryDrawerRows.kt:149`), 9sp (`ModelSwitcherChoicePill.kt:191`) | **11sp minimum** | below 11sp is unreadable at 411dp |

### 11.2 Spacing scale

Adopt exactly **six** values and migrate:

```
4.dp   — intra-component (icon<->label, chip padding)
8.dp   — related elements
12.dp  — grouped items
16.dp  — component padding, list gutter
24.dp  — section separation
32.dp  — screen section breaks
```

Migration map for the current 27 values:
`1,2,3 -> 4` · `5,6,7 -> 8` · `9,10,11 -> 12` · `13,14,15 -> 16` · `17,18,20 -> 16 or 24` · `22 -> 24` · `26 -> 24` · `28 -> 32`

Specific:

- `ChatMessageListSection.kt:63` `18.dp` -> **16.dp**
- `ChatMessageListSection.kt:64` `top = 26.dp` -> **24.dp**; `bottom = 118.dp` -> computed from measured composer height (already available as `bottomChromeHeight`, `ChatScreen.kt:274-276`) instead of a literal
- `ChatMessageListSection.kt:65` `spacedBy(18.dp)` -> **16.dp**
- `ChatStatusBarHeader.kt:69` `top = 14.dp, bottom = 10.dp` -> **12.dp / 8.dp**
- `ChatComposerScreens.kt:258` `16.dp` — keep (now matches list)
- `ChatComposerScreens.kt:265` `padding(7.dp)` -> **8.dp**
- `ChatComposerScreens.kt:342` `start = 50.dp, end = 48.dp` -> **48.dp / 48.dp**
- `ChatBubbleMessageBlocks.kt:132` `vertical = 8.dp` -> **8.dp** (keep), `:133` `spacedBy(12.dp)` -> **12.dp** (keep)
- `ChatMarkdownBlockRenderers.kt:67` `spacedBy(6.dp)` -> **8.dp**; `:85` `spacedBy(10.dp)` -> **8.dp**

### 11.3 Elevation

| Surface | Current | Prescribed |
|---|---|---|
| composer shell | `14.dp` (`ChatComposerScreens.kt:263`) | **2.dp** + `BorderStroke(1.dp, outline.copy(alpha = 0.5f))` |
| top bar capsules | `6/5/6.dp` (`ChatStatusBarHeader.kt:76,98,180`) | **0.dp** + `surfaceVariant.copy(alpha = 0.5f)` fill, or keep 2.dp max |
| dropdown / menus | `18.dp` (`ChatModelSwitcherScreens.kt:79`, `ChatHistoryDrawerRows.kt:172`, `ChatComposerActionMenuViews.kt:141`) | **6.dp** tinted |
| history row (selected) | `5.dp` (`ChatHistoryDrawerRows.kt:91`) | **0.dp** + `surfaceVariant` fill |
| FAB (drawer) | `12.dp` (`ChatHistoryDrawerScreens.kt:187`) | **4.dp** |
| cards | `1.dp` (`ModelHubSharedComponents.kt:123`) | keep |

Tinted shadow helper (derives from an existing role — no new hex):

```kotlin
fun Modifier.mcaShadow(elevation: Dp, shape: Shape, color: Color) = this.shadow(
    elevation = elevation,
    shape = shape,
    ambientColor = color.copy(alpha = 0.12f),
    spotColor = color.copy(alpha = 0.16f)
)
// usage: Modifier.mcaShadow(6.dp, RoundedCornerShape(20.dp), MaterialTheme.colorScheme.onSurface)
```

### 11.4 Motion

| Touchpoint | Current | Prescribed |
|---|---|---|
| page enter slide | `tween(240)` (`SmoothPage.kt:48`) | `spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)` |
| page enter fade | `tween(140)` | `tween(140, easing = LinearOutSlowInEasing)` |
| page dismiss | `tween(180)` (`SmoothPage.kt:67`) | `spring(stiffness = Spring.StiffnessMediumLow)` |
| back-cancel settle | `tween(160)` (`SwipeBackPage.kt:71`) | `spring(stiffness = Spring.StiffnessMedium)` |
| capsule reveal | `fadeIn(120) + slideInHorizontally(160)` | `fadeIn(tween(140)) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow))` |
| details expand | `fadeIn(100) + expandVertically(160)` (`ChatMessageTextRenderers.kt:147`) | keep — correct |
| action sheet enter | **dead** (`ChatComposerActionMenuViews.kt:126`) | `MutableTransitionState` + `fadeIn(tween(120)) + slideInHorizontally(tween(180)) { -it / 5 }` |
| send<->stop icon | instant swap | `AnimatedContent(fadeIn(tween(150)) togetherWith fadeOut(tween(150)))` |
| send press | none | `animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = Spring.StiffnessMedium))` |
| list item placement | none | `Modifier.animateItem(fadeInSpec = tween(180), placementSpec = spring(stiffness = Spring.StiffnessMediumLow))` on the `itemsIndexed` item |
| streaming caret | none | 2dp x 16dp `Box` after last character, `infiniteRepeatable(tween(900), RepeatMode.Reverse)` alpha 0.35<->1.0 |
| assistant ring | `1300ms LinearEasing` (`McaBrandMarks.kt:327`) | keep |

Duration budget: **user-initiated <= 220ms**, **ambient/infinite 900–1600ms**, **nothing above 260ms**.

### 11.5 Contrast corrections (alpha removal only)

| Site | Change |
|---|---|
| `ChatHistoryDrawerRows.kt:51` | `onSurfaceVariant.copy(alpha = 0.72f)` -> `onSurfaceVariant` |
| `ChatHistoryDrawerRows.kt:150` | same |
| `ChatReasoningPerformanceScreens.kt:126` | `muted.copy(alpha = 0.74f)` -> `muted` |
| `ChatReasoningPerformanceScreens.kt:315` | `onSurfaceVariant.copy(alpha = 0.82f)` -> `onSurfaceVariant` |
| `ChatMessageBubbleScreens.kt:303` | `onSurfaceVariant.copy(alpha = 0.78f)` -> `onSurfaceVariant` |
| `ChatAssistantActionViews.kt:128,140` | `tint.copy(alpha = 0.78f)` -> `tint` |
| `ChatStatusBarHeader.kt:128` | `onSurfaceVariant.copy(alpha = 0.78f)` -> `onSurfaceVariant` (or delete the cache-evidence line entirely, §8.1) |

Declare the two missing tokens so the M3 baseline stops leaking (values chosen to match the *existing* grey family, not a new palette):

```kotlin
// McaTheme.kt — LightColors
outlineVariant = Color(0xFFE8EAED),   // one step lighter than outline #DADCE0
errorContainer = Color(0xFFFCE8E6),   // Google red-tint family
onErrorContainer = Color(0xFF601410),
// DarkColors
outlineVariant = Color(0xFF3C4043),   // Google dark grey family
errorContainer = Color(0xFF601410),
onErrorContainer = Color(0xFFF28B82),
```

These extend the existing Google-grey/red family; they do not introduce a new hue.

### 11.6 Touch targets

| Control | Current | Prescribed |
|---|---|---|
| `ChatHistoryDrawerRows.kt:156` | `26.dp` | **48.dp** (icon stays 17dp) |
| `ChatComposerScreens.kt:320` | `28.dp` | **48.dp** |
| `ChatAssistantActionViews.kt:110,122,134` | `32.dp` | **44.dp** |
| `ChatMessageBubbleScreens.kt:287,297` | `32.dp` | **44.dp** |
| `ChatBubbleMessageBlocks.kt:265` | `32.dp` | **44.dp** |
| `ChatComposerStatusViews.kt:123` | `28.dp` | **40.dp** |

### 11.7 Dark mode

1. Add `app/src/main/res/values-night/styles.xml` with `parent="android:style/Theme.Material.NoActionBar"` (dark), `windowLightStatusBar=false`, and dark `statusBarColor`/`navigationBarColor` — kills the light-bar flash on cold start.
2. `ChatMarkdownBlockRenderers.kt:218` — make the code surface theme-aware: `if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1F2937)`. Light value unchanged.
3. `ChatComposerScreens.kt:445` — `containerColor = McaPrimaryBlue` -> `if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else McaPrimaryBlue`. Dark FAB contrast 4.07:1 -> 8.89:1. (The existing `onPrimary` for dark is `#002D6B`, so also swap the icon tint from `Color.White` to `MaterialTheme.colorScheme.onPrimary` in dark.)

---

## 12. Ranked by perceived quality per line of code

| Rank | Fix | Lines | Files | Perceived gain | Axis |
|---|---|---|---|---|---|
| **1** | Delete the 6 stacked-alpha secondary-text multipliers (§11.5) | ~6 | 4 | **Very high** — fixes AA failures, makes all secondary text legible, zero design risk | 7 |
| **2** | Fix the 2 dead `AnimatedVisibility(visible = true)` (§9.2) | ~8 | 2 | **Very high** — restores motion already authored; the action sheet stops popping | 4 |
| **3** | Add haptics to send/stop/copy/model-switch (§9.3) | ~10 | 4 | **Very high** — the single strongest "native" signal, 0 visual risk | 6 |
| **4** | Align the three gutters to 16dp (§11.2) | ~5 | 3 | **Very high** — the composer/list misalignment is visible to everyone | 2 |
| **5** | Composer shadow 14dp -> 2dp + 1dp border (§11.3) | ~3 | 1 | **High** — removes the single most "2016 card" element | 3 |
| **6** | User-intent scroll guard + scroll-to-bottom pill (§9.7) | ~18 | 1 | **High** — fixes the worst *behavioural* defect | 5/6 |
| **7** | Declare `outlineVariant` / `errorContainer` / `onErrorContainer` (§11.5) | ~6 | 1 | **High** — removes off-palette purple borders from the whole app | 3/7 |
| **8** | Send button press-scale + send<->stop crossfade (§9.4) | ~14 | 1 | **High** — the most-tapped control becomes physical | 4/6 |
| **9** | One `Typography` in `McaTheme` + delete 73 overrides (§9.1) | ~15 + deletions | ~20 | **Very high** (large blast radius) | 1 |
| **10** | `spring` on page enter/dismiss (§11.4) | ~8 | 2 | **High** — gestures gain mass | 4 |
| **11** | Tap targets 26/28/32dp -> 44/48dp (§11.6) | ~12 | 6 | **Medium-high** — miss-taps drop, feels native | 6 |
| **12** | Dark code block + dark FAB colour (§11.7.2-3) | ~4 | 2 | **Medium-high** — fixes 1.28:1 and 4.07:1 | 7 |
| **13** | Themed text-selection colours (§9.8) | ~6 | 1 | **Medium** — removes the Material purple from every long-press | 6 |
| **14** | Empty state -> logo + greeting + chips (§9.10) | ~30 | 1 | **Medium-high** — the only brand moment | 5/8 |
| **15** | `values-night/styles.xml` (§11.7.1) | ~10 | 1 | **Medium** — kills cold-start white flash | 7 |
| **16** | `animateItem` on message list (§11.4) | ~2 | 1 | **Medium** — insert/delete stops jumping | 4 |
| **17** | Streaming caret (§11.4) | ~10 | 1 | **Medium** — generation reads as alive | 4/6 |
| **18** | `maxWidth = 640.dp` on assistant text (§11.1) | ~2 | 1 | **Medium** (tablet only) | 1 |
| **19** | Skeleton for `previewPending` (§5.1) | ~20 | 1 | **Medium** — replaces the bare 18dp spinner | 5 |
| **20** | Top-bar: drop the third text line, merge capsules (§8.1) | ~25 | 1 | **Medium-high** but riskier — touches the header the user likes | 8 |

**Recommended execution order:** 1 -> 2 -> 3 -> 4 -> 5 -> 7 -> 8 -> 6 -> 9 -> 10. The first eight are ~60 lines total across 12 files and address five of the eight axes. Item 9 (the type scale) is the largest single quality jump but has the widest blast radius and should land after the cheap wins are verified on device.

---

## Appendix A — Measured counters (reproducible)

Scan set: `feature/**`, `app/src/main/**`, `core/**`, `.kt`, excluding `build/`, `test/`, `androidTest/` -> **975 files**.

| Metric | Count |
|---|---|
| `fontSize =` | 73 (15 distinct values) |
| `lineHeight =` | 47 (16 distinct values) |
| `letterSpacing` | 0 |
| `Typography(` | 0 |
| `FontWeight.Bold` / `SemiBold` / `Medium` / `Black` | 97 / 54 / 35 / 2 |
| `tween(` | 64 |
| `spring(` | **0** |
| `FastOutSlowInEasing` / `CubicBezierEasing` / `EaseOut` | 0 / 0 / 0 |
| `AnimatedVisibility(` | 12 (2 with `visible = true`) |
| `AnimatedContent(` / `Crossfade(` / `animateContentSize` | 0 / 0 / 0 |
| `animateItem` | 0 |
| `rememberInfiniteTransition` / `infiniteRepeatable` | 19 / 20 |
| `HapticFeedback` / `LocalHapticFeedback` | **0 / 0** |
| `ripple(` / `LocalIndication` | 0 / 0 |
| `OverscrollEffect` | 0 |
| `InteractionSource` / `interactionSource` | 0 / 0 |
| `animateScrollToItem` / `scrollToItem` | 0 / 2 |
| `isScrollInProgress` | 0 |
| `FocusRequester` | 0 |
| `LocalTextSelectionColors` / `TextSelectionColors` | 0 / 0 |
| `CircularProgressIndicator(` usage / `LinearProgressIndicator(` usage | 10 / 20 |
| skeleton UI | **0** |
| `blurhash` / `AsyncImage` | 0 / 0 |
| `derivedStateOf` | 0 |
| `WindowSizeClass` / `enableEdgeToEdge` / `statusBarsPadding` | 0 / 0 / 0 |
| `shadowElevation` sites / distinct values | 22 / 7 (2,5,6,7,8,10,12,14,18) |
| `tonalElevation` sites | 11 |
| `BorderStroke(` | 23 |
| `RoundedCornerShape(` / distinct radii | 163 / 15 |
| `CircleShape` | 76 |
| `Color(0xFF...)` literals in `feature/chat` | 46 |
| `Color(0xFF...)` literals project-wide | 110 |
| distinct padding dp values in `feature/chat` | 27 |
| `IconButton` sized < 44dp | 19 |

## Appendix B — Method note on the `git grep` discrepancy

62 of 142 files under `feature/chat/src/main/` are **untracked** in the working tree (`git ls-files` returns 80 of 142). `git grep` searches the index, so it silently omits them:

| Query | `git grep` | filesystem |
|---|---|---|
| `fontSize =` | 44 | **73** |
| `lineHeight =` | 28 | **47** |
| `CircularProgressIndicator(` | 5 | **10** |

Any prior audit that quoted `git grep` counts under-reported typography and state-UI density by ~40%. Verify with a filesystem scan before citing.

## Appendix C — Files inspected

`core/designsystem/`: `McaTheme.kt`, `SmoothPage.kt`, `BackMotion.kt`

`feature/chat/`: `ChatColorTokens.kt`, `ChatStatusBarHeader.kt`, `ChatComposerScreens.kt`, `ChatComposerStatusViews.kt`, `ChatComposerActionMenuViews.kt`, `ChatComposerResearchModeViews.kt`, `ChatMessageBubbleScreens.kt`, `ChatBubbleMessageBlocks.kt`, `ChatMessageListSection.kt`, `ChatMessageTextRenderers.kt`, `ChatMarkdownBlockRenderers.kt`, `ChatMarkdownRenderSupport.kt`, `ChatScreen.kt`, `ChatBackgroundScreens.kt`, `ChatHistoryDrawerScreens.kt`, `ChatHistoryDrawerRows.kt`, `ChatModelSwitcherScreens.kt`, `ModelSwitcherChoicePill.kt`, `McaBrandMarks.kt`, `ChatDeviceStatusScreens.kt`, `ChatReasoningPerformanceScreens.kt`, `ChatAssistantActionViews.kt`, `ImageAssetIoSupport.kt`, `ImageGenerationResultScreens.kt`, `ChatImagesWorkspaceMainPage.kt`, `ImageWorkspaceScreens.kt`, `AppMenuTransitions.kt`

`feature/modelhub/`: `ModelHubScreen.kt`, `ModelHubSharedComponents.kt`, `RecommendedModelCard.kt`

`app/`: `MainActivity.kt`, `AppStartupScreen.kt`, `res/values/styles.xml`

`gradle/`: `libs.versions.toml` (compose BOM 2026.05.01, compileSdk 36, minSdk 26, targetSdk 36)

---

*No repository file other than this document was modified.*

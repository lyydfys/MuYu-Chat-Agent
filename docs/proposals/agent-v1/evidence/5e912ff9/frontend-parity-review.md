# Agent Workspace v1 — frontend parity review (DSH reference vs `feature/agent-workspace/.../ui`)

Status: **read-only review; no code, build or device run**. This is a static source review of
`feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/**` compared with two
DSH Android reference screenshots. The app itself was **not rendered** for this review (no Gradle, no
ADB, no screenshots of the MCA build were supplied), so every app-side number below is derived from
source; every DSH-side number is measured from the reference images.

Task contract read: `docs/architecture/MCA_PROJECT_HARD_LOCK.md` (esp. §3.1, §9.1–9.3, §12.1,
§16). Existing visual language, navigation, density and tokens are treated as frozen: all fixes below
are either defect repair inside the frozen contract or are explicitly marked as a visual change that
needs separate authorisation.

---

## 0. Scope and provenance

| Item | Value |
| --- | --- |
| Reviewed source | `feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/**` (16 files, ≈5,475 physical lines) |
| Supporting reads | `AgentWorkspaceRoute.kt`, `AgentWorkspaceProjection.kt`, `AgentWorkspaceModels.kt`, `AgentWorkspaceViewModel.kt` (read-only, to confirm what state exists and who owns it) |
| Host context | `MainActivity.kt:369-388` (AGENT_WORKSPACE `CrossfadeTabPage` overlay, `onBack = closePage`), `SwipeBackPage.kt:157-164`, `McaTheme.kt` |
| Reference 1 (conversation) | `C:\Users\example\.dsh\attachments\v1\objects\c5\c5171e6394f01dd3391c4df195ca50ec546b7bb0f2099556ddad07e3592544f0` (JPEG 1200×2608) |
| Reference 2 (drawer + home behind it) | `C:\Users\example\.dsh\attachments\v1\objects\50\502c18ad4c647a67af7545e2230934230d50eb0cd9f876b313590acd1f4892d8` (JPEG 1200×2608) |
| Verification run | NONE. No Gradle, no `verify-code.ps1`, no device, no Compose preview render. |
| Only file written by this task | this document |

Because the app was not rendered, findings are split into:

- **code-provable defect** — behaviour follows deterministically from the Compose code;
- **reference-margin mismatch** — the app's dp values differ from the measured DSH values, with the
  visual consequence described;
- **needs render confirmation** — hypothesis only; listed in §7, never ranked as a defect.

### 0.1 Reference geometry (for the numbers used below)

Both screenshots are 1200×2608. Assuming 3.0 px/dp (400×869 dp) — the small errors this introduces
(≈9 % if the device is 2.75 px/dp) do **not** affect any ratio quoted below; ratios are the load-bearing
evidence.

| Measured on DSH | px | dp @3.0 | App source today |
| --- | --- | --- | --- |
| Top-bar title left edge ("你好问候") | 182 | 60.7 | centered, `padding(horizontal = 64.dp)` (`AgentTaskDetailScreen.kt:193-204`) |
| Tab label left edge ("对话") | 189 | 63.0 | 28 dp row inset (`AgentTaskDetailScreen.kt:250`) |
| Tab underline | 179–257 (79 px) | 59.7–85.7, **26.3 wide** | fixed `.width(40.dp)` (`AgentTaskDetailScreen.kt:287-294`) |
| Label ink width ("对话") | 189–252 (64 px) | 21.3 (glyph ink; advance box ≈ 26) | — |
| Tab underline height / rows | 396–401 | 2.0 | `.height(2.dp)` — matches |
| Tab header divider | ≈408 | ≈136 | immediately below the 48 dp row (`:263`) |
| Conversation body left / right | 101 / ≈1100 | 33.7 / ≈366.7 (**≈32 dp insets**) | 20 dp (`AgentTaskDetailScreen.kt:143`) |
| User bubble right edge | 1091 | 363.7 | 20 dp inset + card |
| Run status line ink height ("已完成，用时 1 秒") | 41 px (rows 739–779) | 13.7 | `bodySmall` 12 sp (`:420-437`) |
| Answer body ink height | 43 px (rows 887–929) | 14.3 | `bodyLarge` 16 sp — matches |
| Composer card left / right edges | 49–55 / 1139–1141 | **16.3 / 380.3 → 16 dp margins** | home 20 dp, detail 12 dp |
| Footer metrics row | ≈2458–2476 | ≈819–825 | `ComposerStats` (detail only) |
| Drawer right edge (ref 2) | ≈833–840 | **≈278–280 dp (70 %)** | `fillMaxWidth(0.76f)` → 304 dp (`AgentWorkspaceHomeScreen.kt:84-86`) |
| Drawer row pitch "插件"→"自动化任务" | ≈120 | **≈40 dp** | 56 dp (`AgentHomeDrawer.kt:273-278`) |
| Drawer "新会话" button height | ≈116 | ≈39 | `heightIn(min = 52.dp)` (`AgentHomeDrawer.kt:92`) |

The most important structural fact: **DSH aligns the tab labels with the top-bar title** (both ≈60 dp)
and lets the selected underline hug the label. The MCA detail screen has a centered title, tabs at
28 dp and a fixed 40 dp underline, i.e. three different horizontal anchors in one header.

---

## 1. Verdict

- **P0: none.** No crash, data-loss, or blocked-primary-flow defect is provable from this UI package.
  The one place that could look like a P0 on a device — clipped/clipped-looking tabs — has no clipping
  path in this source: the tab `Row` is not fixed-width and the label is content-sized. The visible
  defect is the **fixed 40 dp underline and its 10 dp float**, ranked P1-1.
- **P1: 10 findings** (all primary-surface, all fixable inside this feature without state-ownership
  changes, except P1-9's missing timestamp/metrics which need a projection field).
- **P2: 10 findings** (polish, robustness, accessibility labelling, verification coverage).
- The biggest alignment defect is that the workspace is a **DSH-shaped copy with a different header
  anatomy**: DSH = leading title + menu + content-width tab underline + composer 16 dp margins;
  MCA = centered title + back arrow + 40 dp underline + home/detail composers with 20/12 dp margins.

---

## 2. P1 findings (ranked, highest impact first)

### P1-1 — Tab bar: fixed 40 dp underline, 10 dp float above the divider, 40 dp-wide touch target

**Surface:** task detail, both tabs (`对话` / `轨迹`) — first screen a user sees after opening a task.
**Type:** code-provable defect + measured reference mismatch.

**Evidence**

- `AgentTaskDetailScreen.kt:287-294` — the indicator is a literal `Box(Modifier.width(40.dp).height(2.dp))`
  centred under the label. The label is `labelLarge` (14 sp) and both tab labels are 2 CJK characters,
  i.e. ≈26–28 dp advance width (26 dp ink measured in the reference). The bar therefore overhangs the
  word by ≈6–7 dp on each side.
- `AgentTaskDetailScreen.kt:269-295` — the tab cell is a `Column` with `heightIn(min = 48.dp)` and
  `padding(top = 10.dp)`; the indicator is the last child, so its bottom lands at ≈38 dp of a 48 dp
  cell. The `HorizontalDivider` at `:263` is drawn after the row, so **the underline floats ≈10 dp above
  the divider**. DSH measures 2 dp (underline rows 396–401, divider ≈408).
- `AgentTaskDetailScreen.kt:271` — the `selectable` is on that Column, whose width is the widest child
  = the 40 dp bar. **Tap target = 40 × 48 dp**: narrower than the 48 dp minimum the rest of the page
  enforces (`McaButtons.kt:75` `size(48.dp)`), and the 30 dp inter-tab spacer (`:254`) is dead space.
  DSH's tab ink gap is ≈41 dp (252 px → 376 px), so the app needs a deliberate gap, not a 30 dp hole
  between two 40 dp targets whose labels are only 26 dp wide.
- Font-scale drift: at `fontScale ≈1.5` the 2-character label reaches ≈42 dp and the bar becomes
  *narrower* than its word. With a 4-character label it is wrong at 1.0×.
- No `selectableGroup()` on the row (`:249-262`), so TalkBack has no "1 of 2" context (see P1-8).
- Note on clipping: there is no hard clipping path in this source. If a device render shows the tab
  text cut, treat it as this same geometry bug (label/indicator widths) rather than an unrelated defect.

**DSH target:** underline follows the label (26.3 dp for a 2-char label), sits 2 dp above the divider,
label left edge aligned with the header title.

**Exact change (suggested; not applied)**

```kotlin
// AgentTaskDetailScreen.kt — replace DetailTabs + DetailTabItem
@Composable
private fun DetailTabs(selected: AgentDetailTab, onSelect: (AgentDetailTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()                       // TalkBack: "1 of 2"
                .padding(start = 48.dp, end = 20.dp),    // label ink lands at the 60 dp title anchor
            verticalAlignment = Alignment.Bottom
        ) {
            AgentDetailTab.entries.forEachIndexed { index, tab ->
                if (index > 0) Spacer(Modifier.width(16.dp))  // ≈40 dp ink gap, measured 41 dp
                DetailTabItem(tab = tab, selected = selected == tab, onClick = { onSelect(tab) })
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun DetailTabItem(tab: AgentDetailTab, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .widthIn(min = 48.dp)                        // >= 48 dp target even for a 1-char label
            .height(48.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.width(IntrinsicSize.Max), // width == label intrinsic width
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()                      // == label width, tracks fontScale/locale
                    .height(2.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
            )
            Spacer(Modifier.height(2.dp))                // DSH: underline sits 2 dp above divider
        }
    }
}
```

Imports added: `androidx.compose.foundation.layout.IntrinsicSize`, `androidx.compose.foundation.layout.width`,
`androidx.compose.foundation.layout.widthIn`, `androidx.compose.foundation.selection.selectableGroup`.
`width(IntrinsicSize.Max)` makes the underline exactly the text width; do **not** use
`Modifier.fillMaxWidth()` inside the outer Column (it would span the row).

**Do not** replace this with M3 `PrimaryTabRow`: its indicator spans the whole tab slot (half the
screen), which is further from DSH than the current 40 dp.

**Verify:** 320 dp width, 400 dp width, fontScale 1.0/1.3/2.0, both locales; measure the gap between
the underline bottom and the divider (target 2 dp); TalkBack announces "对话, tab, 1 of 2, selected".

---

### P1-2 — Detail top bar: centered title wastes width, back label is wrong, sidebar glyph opens an overflow menu, no drawer access

**Surface:** task detail header + the only navigation affordance out of a conversation.
**Type:** code-provable defect + measured reference mismatch.

**Evidence**

- `AgentTaskDetailScreen.kt:193-204` — title is `Alignment.Center` with `padding(horizontal = 64.dp)`.
  On a 360 dp phone that leaves 232 dp for the title; long goals (the feature's own examples:
  "整理 notes.md 并写入 summary.md") exceed it and are ellipsized even though the right side only holds
  one 48 dp button. DSH's title is **leading-aligned** (ink starts at 60.7 dp) and runs to the right
  control.
- `AgentTaskDetailScreen.kt:186-192` — back button uses `ArrowBack` with `padding(start = 4.dp)`; the
  home's menu button uses `padding(horizontal = 8.dp)` (`AgentWorkspaceHomeScreen.kt:107`). The leading
  glyph jumps 4 dp between the two headers.
- `AgentTaskDetailScreen.kt:187` — `contentDescription = "返回任务列表"`, but `onBackToHome` lands on the
  composer-first home (`AgentWorkspaceViewModel.kt:331-337`); there is no task list. The same wrong
  string is on Skills/MCP (`AgentSkillsScreen.kt:70`, `AgentMcpScreen.kt:69`). `AgentWorkspaceRouteTest.kt:184`
  pins the string, so the test must change with it.
- `AgentTaskDetailScreen.kt:205-241` — right control is `Icons.AutoMirrored.Filled.ViewSidebar`
  (a panel-toggle glyph, the same family as DSH's sidebar affordance) but opens a **dropdown menu**
  (导出/停止/删除). The affordance says "panel", the action is "overflow".
- **No drawer on the detail screen.** `ModalNavigationDrawer` exists only inside
  `AgentWorkspaceHomeScreen.kt:79-97`. Switching tasks from a conversation is: tap Back → wait for home
  → tap hamburger → tap task (4 interactions, plus a scroll in the drawer). DSH's conversation header
  keeps the menu affordance, so task switching stays one tap + one tap.

**Exact change (suggested; not applied)**

```kotlin
// AgentTaskDetailScreen.kt — DetailTopBar body
Row(
    modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .statusBarsPadding()
        .heightIn(min = 56.dp)
        .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    McaIconButton(contentDescription = "打开任务与工具抽屉", onClick = onOpenDrawer) {
        Icon(Icons.Filled.Menu, contentDescription = null)      // DSH anatomy, gives drawer access
    }
    Text(
        text = taskDisplayTitle(detail.goal.ifBlank { "任务" }),
        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp), // 60 dp ink start, matches DSH
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Box {
        McaIconButton(contentDescription = "任务操作", enabled = !detail.loading, onClick = onOpenMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = null) // glyph now matches the action
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) { /* unchanged items */ }
    }
}
```

Requires hoisting the drawer state up to `AgentWorkspaceContent` (single owner) so both Home and Detail
can open the same `AgentHomeDrawerContent`; keep the close-then-navigate behaviour
(`closeThen`) exactly as in `AgentWorkspaceHomeScreen.kt:71-77`. If the drawer move is out of scope for
this slice, the minimum is: `MoreVert` for the menu, and rename the back description to
"返回工作区" (plus `AgentWorkspaceRouteTest.kt:184`).

**Verify:** 360/400 dp; a 40-character goal shows an ellipsis only at the right actions, never on both
sides; TalkBack hears "返回工作区, button"; opening a task from detail is possible in ≤2 taps.

---

### P1-3 — Detail composer toolbar: 84 dp magic padding truncates the model name, overlaps the egress control, and uses 40/44 dp targets

**Surface:** the detail screen's bottom composer — the primary action surface while a task runs.
**Type:** code-provable defect.

**Evidence**

- `AgentQuestionCard.kt:436-459` — the model `TextButton` is `align(Center)` with
  `.padding(horizontal = 84.dp)`. That reserves 168 dp of the toolbar on every width, regardless of
  the real left cluster (40 dp + 2 dp + ≈50 dp egress row = 92 dp) or the 44 dp send button.
  Toolbar content width = screen − 24 dp (outer) − 26 dp (surface padding): **310 dp at 360 dp wide**,
  **270 dp at 320 dp**. The button's max width becomes 142 dp / 102 dp. Subtract its default 24 dp
  content padding + 16 dp arrow + 2 dp spacer → **≈100 dp / ≈60 dp of text**. `DeepSeek-V4.1-Flash`
  at `labelLarge` is ≈126 dp, so it ellipsizes on every ≤360 dp phone and on 320 dp it shows ~4 chars.
  DSH shows the full name at the same 400 dp reference width.
- Touch-target collision: the model button's box is `align(Center)` with 84 dp padding on both sides,
  so when its content is width-constrained (360 dp, or a long model label) the padded box fills the
  toolbar and its clickable starts at 84 dp from the content start, while the egress ("联网策略")
  clickable ends at ≈92 dp (`:372-402`). Because the model button is drawn after the left row, its
  **8 dp overlap wins the tap**, so the rightmost strip of the network policy control is unresponsive.
  At 400 dp with a short label the overlap shrinks to ≈1 dp — it appears exactly on the phone widths
  where the label is already truncating.
- `AgentQuestionCard.kt:510-521` — `ComposerCircleButton` is `Modifier.size(40.dp)`; `:530-547` —
  `ComposerSendButton` is `Modifier.size(44.dp)`. Both are below the 48 dp baseline used by
  `McaIconButton` (`McaButtons.kt:52,75`) and by the rest of the feature; the send button is the
  most-tapped control on the screen.
- `AgentQuestionCard.kt:443` — `semantics { contentDescription = "选择模型" }` **replaces** the merged
  child text, so TalkBack never hears the selected model name (see P1-8).
- `AgentQuestionCard.kt:535-542` — dead branch: `containerColor = if (enabled) primary else
  primary.copy(alpha = .35f)`; when `enabled == false` M3 uses `disabledContainerColor` (`.20`), so the
  `.35` is unreachable. Misleading, not user-visible.

**Exact change (suggested; not applied)**

```kotlin
// AgentQuestionCard.kt — replace the Box(align Center) model button with a weighted Row slot
Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    ComposerCircleButton(
        icon = Icons.Filled.Add,
        contentDescription = "新建任务",
        onClick = actions.onBackToHome,
        enabled = enabled
    )
    Spacer(Modifier.width(2.dp))
    Box {
        Row(/* egress policy, unchanged; keep its 8 dp padding */)
        DropdownMenu(/* unchanged */)
    }
    TextButton(
        onClick = actions.onOpenModelPicker,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("agent.detail.model")
            // no contentDescription: the visible model name is the accessible name
    ) {
        Text(
            text = state.models.selected?.displayName ?: "选择模型",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
    }
    ComposerSendButton(/* ... unchanged, but size 48.dp */)
}
```

```kotlin
// AgentQuestionCard.kt:513 and :533
modifier = Modifier.size(48.dp)   // was 40.dp / 44.dp
```

Do **not** keep the 84 dp padding and shrink the text; the padding is the root cause.

**Verify:** 320/360/400 dp with `DeepSeek-V4.1-Flash` and a 30-character model label; no ellipsis at
360 dp; tapping the right 8 dp of the egress control opens the policy menu; the send target measures
48 × 48 dp in Layout Inspector; TalkBack reads the model name.

---

### P1-4 — Home composer toolbar: three 48 dp leading buttons starve the model name; `AttachFile` duplicates the plus sheet; the card is ≈192 dp tall

**Surface:** composer-first home — the first screen of the workspace.
**Type:** code-provable defect + measured reference mismatch.

**Evidence**

- `AgentHomeComposer.kt:254-315` — the toolbar is `+` (48) → `AttachFile` (48) → `Shield` (48) →
  weighted model button → `Send` (48). Leading cluster = 144 dp; send + spacer = 54 dp.
- Available model width (360 dp, 20 dp list padding, 18/12 dp card padding): 290 − 144 − 54 = **92 dp**;
  `TextButton` default content padding is 24 dp → 68 dp for text + 18 dp chevron + 2 dp spacer →
  **≈48 dp of text ≈ 8 Latin characters**. `DeepSeek-V4.1-Flash` will always be ellipsized on 360 dp.
  Even at 400 dp (330 − 144 − 54 = 132 → ≈88 dp of text) a 20-character name is clipped. DSH keeps only
  two leading controls (plus, shield) and shows the full model name at the same reference width.
- `AgentHomeComposer.kt:263-268` — `AttachFile` calls `actions.onImportTaskPackage`, the exact same
  action as the plus sheet's "文件 → 导入任务包" row (`AgentHomePlusSheet.kt:45-49`). It is a duplicate
  control that costs 48 dp of the toolbar the model name needs.
- `AgentHomeComposer.kt:238-251` — `BasicTextField` has `heightIn(min = 112.dp)`; with the card's
  16 dp top and 8 dp bottom plus the 48 dp toolbar, the closed card is ≈192 dp tall (22 % of an 869 dp
  screen). DSH's conversation composer card measures ≈99 dp (border rows 2162→2458), and the app's own
  detail composer is ≈126 dp — the home card is roughly double the reference. The reference home
  composer is only partially visible behind the drawer, so treat 99 dp as the conversation target and
  126 dp as the app's existing compact baseline.
- `AgentHomeComposer.kt:275-294` — the model `TextButton` has `weight(1f)`, so its **whole remaining
  row is tappable**; tapping the empty space between the shield and the model name opens the model
  picker. That is a large, unnamed hit area (TalkBack hears only the button label).
- `AgentHomeComposer.kt:230-237` — the placeholder is a sibling `Text` behind an invisible
  `BasicTextField`, not a `decorationBox` (TalkBack node duplication; see P1-8).

**Exact change (suggested; not applied)**

```kotlin
// AgentHomeComposer.kt:254-315 — remove the duplicate AttachFile button
Row(verticalAlignment = Alignment.CenterVertically) {
    McaIconButton(contentDescription = "更多输入选项", onClick = { plusMenuOpen = true }) {
        Icon(Icons.Filled.Add, contentDescription = null)
    }
    // AttachFile button deleted: AgentHomePlusSheet "文件" already calls onImportTaskPackage.
    McaIconButton(contentDescription = "网络与权限说明", onClick = { egressOpen = true }) {
        Icon(Icons.Filled.Shield, contentDescription = null)
    }
    TextButton(
        onClick = actions.onOpenModelPicker,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("agent.model")
    ) {
        Text(
            text = models.selected?.displayName ?: if (models.loaded) "选择模型" else "载入模型…",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f, fill = false)
        )
        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(6.dp))
    FilledIconButton(/* unchanged send, 48 dp */)
}
```

```kotlin
// AgentHomeComposer.kt:243
.heightIn(min = 84.dp)   // was 112.dp; keeps ~3 lines and removes ~28 dp of empty card
```

**Verify:** 360 dp with the longest production model name; no ellipsis at 360 dp; the card height at
rest (target ≈156 dp, DSH-relative) and with 3 lines of text; the plus sheet still exposes file import.

---

### P1-5 — Keyboard/IME: the home composer scrolls under the IME (no pinning, no bring-into-view), and the detail composer adds the IME and navigation-bar insets

**Surface:** both composers, while the user is typing — the state in which the feature is actually used.
**Type:** code-provable defect.

**Evidence**

- `AgentWorkspaceHomeScreen.kt:118-126` — the composer is item 3 of the `LazyColumn`; the **only**
  IME handling is `.imePadding()` on the list. The viewport shrinks, but the send/model row is *below*
  the focused text field inside a 112 dp field + 48 dp toolbar card. Compose's text-field
  bring-into-view guarantees the **field**, not the toolbar under it. Content above the composer
  consumes ≈316 dp (20 top + 80 spacer + ≈46 brand + 32 + selectors 120 + 18), while a 360×640 dp
  device with a 300 dp IME leaves ≈260 dp of viewport → the composer is entirely below the fold; on
  400×869 dp the viewport is ≈489 dp and the composer bottom is ≈508 dp, so its toolbar is clipped
  even when the field itself is scrolled into view. There is no pinned bottom slot and no
  `BringIntoViewRequester` on the card.
- `AgentQuestionCard.kt:211-218` — detail composer: `.imePadding().navigationBarsPadding()`. These
  are **additive**. With the IME open, `ime` already covers the bottom of the window, while
  `navigationBars` is still non-zero (gesture bar ≈24 dp, 3-button nav ≈48 dp), so the composer floats
  24–48 dp above the keyboard with a dead band. The correct inset is the **union/max**, not the sum.
- `AgentTaskDetailScreen.kt:140-151` — when the IME opens and the composer grows, the list viewport
  shrinks but the list is not scrolled to the bottom, so the latest message/activity disappears behind
  the composer. DSH follows the newest content.

**Exact change (suggested; not applied)**

```kotlin
// AgentQuestionCard.kt:211-218 — one inset source, not two
Column(
    modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        .padding(horizontal = 12.dp)
        .padding(top = 6.dp, bottom = 2.dp)
)
```

```kotlin
// AgentWorkspaceHomeScreen.kt — keep the composer in the scroll content, but bring the whole card
// into view when the field is focused (works for both the sheet's "目标" action and a direct tap)
val bringComposerIntoView = remember { BringIntoViewRequester() }
// composer card: Modifier.bringIntoViewRequester(bringComposerIntoView)
// input: Modifier.onFocusChanged { focus -> if (focus.isFocused) scope.launch { bringComposerIntoView.bringIntoView() } }
```

If a rendered home shows the composer fully below the fold with the keyboard open, the alternative is to
pin it: `Scaffold(bottomBar = { AgentHomeComposer(...) })` with the brand/selectors/banners in the
scrollable body. That is a visual change (DSH's home composer is mid-screen), so it needs authorisation;
the bring-into-view fix preserves the current composition.

**Verify:** 360×640 and 400×869, fontScale 1.0/1.3; open the keyboard by tapping the field and by using
the plus sheet's "目标" row; the send button and the model picker are fully visible above the IME; the
composer's bottom gap equals the keyboard top (no extra 24/48 dp band); TalkBack focus order does not
jump behind the keyboard.

---

### P1-6 — Light/dark color roles: cards are page-coloured in dark, the canvas changes between home and detail, and one composer branches on the system theme instead of the app theme

**Surface:** every card on the detail screen, both composers, dark mode.
**Type:** code-provable defect (values are literal in `McaTheme.kt`).

**Evidence**

- `McaTheme.kt:102,123` — dark `surface = #202124` and `surfaceContainerLow = #202124` are **the same
  colour**. The detail page paints its canvas with `surface` (`AgentTaskDetailScreen.kt:124-127`) and
  every card uses `surfaceContainerLow`:
  `AgentTimelineRowItem.kt:69`, `AgentQuestionCard.kt:80`, inline approval `AgentTaskDetailScreen.kt:450`,
  artifact row `AgentArtifactsSection.kt:52`. In dark, those cards have **no tonal separation at all**;
  only their border remains: `outlineVariant #3C4043` at 55 % over #202124 → ≈#2C2F32, ≈1.15:1, below the
  3:1 non-text contrast threshold. The activity/block structure of the conversation effectively vanishes.
- `AgentWorkspaceHomeScreen.kt:100` paints `background` (#121212 dark / #F8F9FA light) while
  `AgentTaskDetailScreen.kt:127` paints `surface` (#202124 / #FFFFFF). The canvas changes across a
  navigation step that should feel continuous, and in light mode the home composer's white card becomes
  darker than the page while the detail composer's #F8F9FA card becomes darker than a white page — the
  **card/page relationship inverts** between the two screens.
- `AgentHomeComposer.kt:206` — `val dark = isSystemInDarkTheme()`, then `:216-221` picks
  `surfaceContainerHigh` (#303134) in dark and `surface` (white) in light. `McaTheme`
  (`McaTheme.kt:137,141`) accepts a `darkTheme` parameter; using the system value means a forced dark
  theme (or a `McaTheme(darkTheme = true)` preview/render test) gets a **white composer card on a dark
  canvas**. This is a latent bug today (MainActivity uses the default) and a preview-blind one (all
  feature previews use `McaTheme {}`).
- The detail composer does not get the same treatment at all (`AgentQuestionCard.kt:222`
  `surfaceContainerLow` in both themes), so home and detail disagree about which role a composer card is.
- The tonal elevations (`AgentQuestionCard.kt:223` 2 dp, `AgentHomeComposer.kt:226` shadow 2 dp,
  `AgentTaskDetailScreen.kt:738` 2 dp) tint the surface with `primary` at ≈7 % — a colour treatment the
  file comments claim was not introduced (`AgentWorkspaceComponents.kt:32-34`). Cards end up
  blue-tinted in light and nearly invisible in dark.

**Exact change (suggested; not applied)**

```kotlin
// core/designsystem/McaTheme.kt — expose the theme's own dark flag (single owner)
val LocalMcaDarkTheme = staticCompositionLocalOf { false }

@Composable
fun McaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = mcaColorScheme(darkTheme)
    // ... existing window code ...
    CompositionLocalProvider(LocalMcaDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
```

```kotlin
// AgentHomeComposer.kt and AgentQuestionCard.kt — one composer-card rule for both screens
val composerContainer = if (LocalMcaDarkTheme.current) {
    MaterialTheme.colorScheme.surfaceContainerHigh
} else {
    MaterialTheme.colorScheme.surface
}
```

For the cards, the smallest dark-only fix is to stop relying on `surfaceContainerLow` as a fill. Add one
design-system semantic alias (this is exactly the "real reuse" evidence D07 asks for):

```kotlin
// core/designsystem/McaTheme.kt
object McaSurfaceRoles {
    val card: Color @Composable get() =
        if (LocalMcaDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow
}
```

Then replace the four `surfaceContainerLow` card fills and their 0.55-alpha borders with `McaSurfaceRoles.card`
+ `outlineVariant` at full alpha. **Light mode keeps today's #F8F9FA; only dark changes to #303134.**
This is a visual change in dark and needs the §9.1/§10 sign-off; without it, the dark conversation cannot
read as a grouped list.

Also unify the canvas: pick one role for both screens (recommended `background`, matching DSH's uniform
canvas) and paint the composer card over it.

**Verify:** light + dark device captures of home, detail (idle/running/approval), drawer, model sheet;
measure the card-vs-canvas delta (dark target ≥ 1.5:1 fill separation, border ≥ 3:1) and confirm the
home↔detail transition has no canvas flash.

---

### P1-7 — Drawer: fixed chrome can overflow, width/density miss DSH, and the empty state hides archived tasks

**Surface:** left drawer and its empty/loading states.
**Type:** code-provable defect + reference mismatch.

**Evidence**

- `AgentHomeDrawer.kt:80-224` — the drawer is a `Column(fillMaxSize)` whose only flexible child is the
  task list at `:152` (`weight(1f)`). Fixed content: header ≈60 dp, new-task 52 + 16 dp spacers,
  2 expandable rows 56 each, workspace header ≈44 dp, footer ≈70 dp → **≈354 dp** before any expansion,
  +192 dp when both sections are expanded. In landscape (~330 dp available height after insets) or at
  fontScale 1.5 on a small phone the weighted list collapses to 0 and the footer is clipped by the
  drawer sheet. There is no scroll container for the chrome. DSH's drawer scrolls as one column.
- `AgentWorkspaceHomeScreen.kt:84-86` — `fillMaxWidth(0.76f).widthIn(max = 340.dp)` → **304 dp** on the
  400 dp reference; DSH's drawer edge is at **≈280 dp (70 %)**. On a 320 dp phone the drawer collapses
  to 243 dp, which makes task rows and the workspace header cramped.
- `AgentHomeDrawer.kt:266-300` — expandable rows are `heightIn(min = 56.dp)` with 20 dp horizontal
  padding; DSH's row pitch is ≈40 dp with ≈16 dp padding. `:302-333` sub-rows indent the label to 56 dp
  (DSH's labels start at ≈44 dp). 56 dp is compliant but visibly airier than the reference; 48 dp keeps
  the target and closes half the gap.
- `AgentHomeDrawer.kt:452-473` — the empty state says "暂无任务 / 从上方新任务开始", but `home.empty` is
  computed over **visible** tasks only (`AgentWorkspaceProjection.kt:334-342` filters
  `IMPORTED_ARCHIVED` unless `includeArchived`), and the archived toggle is hidden inside the collapsed
  "自动化任务" section (`AgentHomeDrawer.kt:129-146`). A user whose only tasks are archived is told there
  are none, with no hint and no path to them.
- `AgentHomeDrawer.kt:148-150` — `WorkspaceHeader` shows "工作区 … N 个任务" where DSH has the
  search/filter/new-folder actions. The omission is deliberate and documented (`:62-68`: no handlers),
  so it is **not** a defect; the count is an acceptable honest substitute. Keep the note.

**Exact change (suggested; not applied)**

```kotlin
// AgentWorkspaceHomeScreen.kt:84-86 — reference width
modifier = Modifier
    .fillMaxWidth(0.72f)
    .widthIn(max = 300.dp)
```

```kotlin
// AgentHomeDrawer.kt — one scroll owner for chrome + rows; footer at the end of the scroll
LazyColumn(modifier = Modifier.fillMaxSize().testTag("agent.drawer")) {
    item("drawer.header") { DrawerBrandHeader(onClose) }
    item("drawer.new") { NewTaskButton(onClick = { closeThen { actions.onDraftChange("") } }) }
    item("drawer.plugins") { DrawerExpandableRow(/* ... */) }
    if (pluginsExpanded) { item("drawer.skills") { ... }; item("drawer.mcp") { ... } }
    item("drawer.automation") { DrawerExpandableRow(/* ... */) }
    if (automationExpanded) { item("drawer.archived") { ... }; item("drawer.import") { ... } }
    item("drawer.workspace") { WorkspaceHeader(taskCount) }
    taskItems()                       // active header/rows, recent header/rows, load-more
    item("drawer.footer") { DrawerFooter() }
}
```

If the footer must stay pinned, keep `weight(1f)` for the list but wrap the top chrome + workspace
header in `Column(Modifier.heightIn(max = 40.dp * 7))`… in practice the single `LazyColumn` is simpler
and matches DSH; the footer then scrolls, which is the DSH behaviour for its account row on short
screens.

```kotlin
// AgentHomeDrawer.kt:273-278 and :309-313
.heightIn(min = 48.dp)          // was 56.dp
.padding(start = 48.dp, ...)    // sub-row indent was 56.dp
```

```kotlin
// AgentHomeDrawer.kt empty state — tell the truth about hidden archived tasks
AgentHomeUiState.empty copy for a new `archivedHiddenCount` (projection:
  visibleTasks.size == 0 && allTasks.any { it.lifecycle == IMPORTED_ARCHIVED })
// text: "暂无任务" + if (archivedHiddenCount > 0) "已隐藏 N 个归档任务" + TextButton("显示归档")
```

**Verify:** landscape 640×360; fontScale 2.0 portrait; 10 tasks + both sections expanded; only-archived
state shows the hidden count and a working "显示归档"; drawer width ratio ≈0.70–0.72 on 320/400/600 dp.

---

### P1-8 — TalkBack/accessibility semantics: tabs ungrouped, question options lose their selected state, the detail model name is replaced, and the visible label disappears from the accessible name

**Surface:** every focusable control on the detail and home screens.
**Type:** code-provable defect.

**Evidence**

- Tabs: no `selectableGroup()` (`AgentTaskDetailScreen.kt:249-262`) → no "1 of 2" (fixed in P1-1).
- Question options (`AgentQuestionCard.kt:120-172`): selection is visual only. The row is a plain
  `clickable(onClickLabel = "选择这个回答")`; it does not use `selectable`/`selected`, so TalkBack cannot
  say which option is chosen; the check icon is `contentDescription = null` (`:163-168`). Wrap the option
  list in `Modifier.selectableGroup()` and use:
  ```kotlin
  Modifier
      .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
  ```
  (remove the `clickable`/`onClickLabel`).
  (`AgentQuestionCard.kt:100-108` owns the option block; the `agent.question` surface is at `:78`.)
- Detail model button (`AgentQuestionCard.kt:443`): `contentDescription = "选择模型"` overrides the
  model name → TalkBack never reads the current selection. Either remove the semantics or use
  `contentDescription = "选择模型：${state.models.selected?.displayName ?: "未选择"}"`.
- Home selector rows (`AgentHomeComposer.kt:168-169`): `clickable(onClickLabel = contentDescription)` **and**
  `.semantics { this.contentDescription = "打开任务历史" }` replace the visible title/subtitle
  ("本地工作区", "0 个进行中 · 任务与工具"). This violates WCAG 2.5.3 (label in name): a voice-control
  user saying the visible words cannot activate the row. Remove the `semantics` block and keep
  `onClickLabel` only; the visible text then becomes the accessible name.
- Timeline rows (`AgentTimelineRowItem.kt:75-81`): `stateDescription = if (row.expanded) "已展开" else "已折叠"`
  is applied to **every** row, including `expandable == false` rows (e.g. model/run rows) → TalkBack
  announces a state on a non-interactive element and implies a control that does not exist. Apply the
  semantics only when `row.expandable`, and add `role = Role.Button` to the clickable.
- Home brand (`AgentHomeComposer.kt:107-113`): `BrandMark` hardcodes `contentDescription = "MCA 品牌标志"`,
  and it is used decoratively next to the visible "MCA Agent" text and again in the drawer footer
  (`AgentHomeDrawer.kt:209`). TalkBack reads the logo twice before any meaningful content. Add
  `contentDescription: String? = null` to `BrandMark` and pass null at both call sites.
- Home placeholder (`AgentHomeComposer.kt:230-237`): a sibling `Text` behind an undecorated
  `BasicTextField` creates a second, separate text node. TalkBack focus order becomes "描述你想要构建的内容…"
  (plain text) then "text field" with no hint. Use `decorationBox` so the hint belongs to the field.
- Wrong back descriptions: `AgentTaskDetailScreen.kt:187`, `AgentSkillsScreen.kt:70`, `AgentMcpScreen.kt:69`
  all say "返回任务列表"; none of them returns to a task list.
- Home in-flight send (`AgentHomeComposer.kt:303`): `contentDescription = if (busy) "正在创建任务" else "开始任务"`
  is unreachable, because `canSend` requires `commandInFlight == null` (`:207-210`) — while creating, the
  button is disabled and there is no visible progress (see P2-1).

**Exact change:** as above per control; plus

```kotlin
// AgentHomeComposer.kt:168-169
.clickable(onClickLabel = contentDescription, onClick = onClick)
// delete the following .semantics { this.contentDescription = contentDescription } line
```

**Verify:** TalkBack traversal of home → drawer → detail → approval; each control's announced name
contains its visible text; tab/option groups announce position and selection; decorative images are
skipped.

---

### P1-9 — Typography and horizontal grid: three content anchors in one header, the run status is 12 sp, and two DSH message affordances are missing

**Surface:** conversation typography and the shared horizontal grid.
**Type:** measured reference mismatch (plus one missing-state gap).

**Evidence**

- **Three anchors:** tabs at 28 dp row inset (`AgentTaskDetailScreen.kt:250`) → label ink at
  ≈35 dp (the 26 dp label is centred over the 40 dp bar), conversation content at 20 dp (`:143`),
  detail composer at 12 dp (`AgentQuestionCard.kt:216`), top-bar title centred (`:193-204`).
  DSH uses title ≈60.7 dp, tab label ≈63 dp, content ≈32 dp, composer **16 dp** margins. The app's
  content grid is internally inconsistent (the tabs are ≈15 dp right of the body they label), and the
  composer width changes 8 dp between home (20 dp) and detail (12 dp).
- **Run status line:** `AgentTaskDetailScreen.kt:420-437` uses `bodySmall` (12 sp) for
  "Agent 任务 · 已完成 · 用时 1 秒". DSH's "已完成，用时 1 秒" is ≈14.5–16 sp (41 px ink vs 43 px for the
  16 sp answer body). The primary run status is the smallest text in the conversation, below
  `bodyLarge` answer text and below the dotted-list body. Use `bodyMedium` (14 sp) and drop the
  redundant "Agent 任务 · " prefix (DSH shows only the state + duration).
- **User message affordances:** DSH shows the clock time ("13:07", ink rows ≈615–645, 14-ish sp) and a
  copy button under the user bubble, right-aligned. The app's `UserGoalBubble` (`:386-410`) has neither;
  the only time is on the *assistant* action row and comes from `detail.timeline.lastOrNull()?.timeText`
  (`:560-566`). There is no task-created timestamp in `AgentTaskDetailUiState`
  (`AgentWorkspaceModels.kt:240-265`), so this needs a projection field (see §4 P2-8) before the UI can
  show it — do not fabricate a time.
- **Metrics footer:** DSH shows steps/tokens/cache/context; `ComposerStats` shows run label / activity
  count / artifact count (`AgentQuestionCard.kt:550-587`) and disappears entirely when empty. The
  reference's token/cache values do not exist in the page state, so the right fix is either an
  explicitly-owned projection extension or a documented reduced footer — not invented strings.
- **Home selector hierarchy:** `AgentHomeComposer.kt:174-185` uses `labelLarge` (14 sp) for the primary
  navigation row title and `labelSmall` (11 sp) for the subtitle — smaller than the composer placeholder
  (`bodyLarge` 16 sp) and the brand headline (24 sp). The two selector rows are the second-most important
  controls on the home and read as captions. Use `bodyLarge` + `bodySmall`.
- **Drawer density:** see P1-7 (56 dp rows vs 40 dp reference).

**Exact change (suggested; not applied)**

```kotlin
// A single grid for the feature (new private object in the ui package; no design-system change needed)
private object AgentWorkspaceDimens {
    val content: Dp = 20.dp        // conversation + home list content padding (current value kept)
    val composer: Dp = 16.dp       // DSH-measured composer margin; replaces 12.dp (detail) / 20.dp (home)
    val headerInk: Dp = 60.dp      // title ink start: 4 dp + 48 dp button + 8 dp
    val tabInk: Dp = 60.dp         // tab label ink start, aligned with the title
}
```

```kotlin
// AgentTaskDetailScreen.kt:420-437
Text(
    text = "Agent 任务",
    style = MaterialTheme.typography.bodyMedium,     // was bodySmall
    ...
)
Text(
    text = detail.statusLabel + suffix,             // drop the "Agent 任务 ·" duplication
    style = MaterialTheme.typography.bodyMedium,
    ...
)
```

```kotlin
// AgentHomeComposer.kt:174-185
style = MaterialTheme.typography.bodyLarge          // title, was labelLarge
style = MaterialTheme.typography.bodySmall          // subtitle, was labelSmall
```

```kotlin
// AgentQuestionCard.kt:216 and AgentWorkspaceHomeScreen.kt:118-126
.padding(horizontal = AgentWorkspaceDimens.composer)   // 16 dp on both composers
```

**Verify:** 400 dp reference capture overlaid on the DSH screenshot (same content: a 2-char user goal,
a short answer, one status line); compare the title/tab/body/composer anchors and the status-line
optical weight. Time/timestamp work blocked on §4 P2-8.

---

### P1-10 — Detail scroll anchor is global across tasks, so a task opens at another task's scroll offset

**Surface:** navigating home → task B after scrolling task A.
**Type:** code-provable defect.

**Evidence**

- `AgentWorkspaceViewModel.kt:494-500` stores **one** `KEY_DETAIL_SCROLL_INDEX` for every task;
  `AgentWorkspaceRoute.kt:112-113` passes it into `AgentWorkspaceContent` for whichever detail is open;
  `AgentTaskDetailScreen.kt:103-105` restores it unconditionally via
  `rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0))`.
- Walk: open A → scroll to item 12 → Back → home → open B → B composes at item 12 (clamped if B has fewer
  items), so the user lands mid-conversation with no context. Re-entering A later is no better: the
  index now belongs to B (the `snapshotFlow` at `:118-121` overwrote the single slot).
- The tab selection is *not* affected (it resets because the detail screen leaves composition when home
  shows), so this is a single-key bug, not compound state.

**Exact change (suggested; not applied)** — key the anchor by task:

```kotlin
// AgentWorkspaceViewModel.kt
fun onDetailScrollAnchorChanged(taskId: String, index: Int) {
    savedStateHandle["agent.workspace.detailScroll.$taskId"] = index.coerceAtLeast(0)
}

fun detailScrollAnchor(taskId: String?): Int =
    taskId?.let { savedStateHandle.get<Int>("agent.workspace.detailScroll.$it") } ?: 0
```

```kotlin
// AgentWorkspaceRoute.kt:112-113
detailScrollAnchor = viewModel.detailScrollAnchor(state.detail?.taskId),
```

```kotlin
// AgentTaskDetailScreen.kt:118-121 — bind the write to this task
snapshotFlow { listState.firstVisibleItemIndex }
    .collect { actions.onDetailScrollAnchor(detail.taskId, it) }
```

This changes the `AgentWorkspaceActions.onDetailScrollAnchor` signature; update the single Route mapping
and the call site. If preserving scroll across tasks is not a product requirement (DSH opens at the
newest content), the cheaper correct fix is to drop the anchor for detail entirely and always start at
item 0 (or at the bottom when a run is active). Do not keep a single global slot.

**Verify:** two tasks with different lengths, scroll both, reopen in both orders; each opens at its own
position (or at 0/bottom by design); process-death restore does not mix tasks.

---

## 3. P2 findings

### P2-1 — Creating a task has no visible in-flight state
`AgentHomeComposer.kt:207-210` disables the send button when `commandInFlight != null`, and the only
description change (`:303`) is unreachable. After the tap the composer just goes dead while the task is
created. Add a static line under the composer while
`state.commandInFlight == AgentWorkspaceCommandKind.CREATE_TASK` ("正在创建任务…", bodySmall /
onSurfaceVariant), matching the feature's existing static-progress convention
(`AgentPackageUi.kt:66-79`, `AgentSkillsScreen.kt:311-332`).

### P2-2 — Home status/error banners are inserted *after* the composer
`AgentWorkspaceHomeScreen.kt:149-198` places notice/error/package/model banners after the composer item.
On a screen where the composer sits near the fold, the error caused by the send attempt is rendered
below it and is not visible without scrolling. Move non-model status items above `home.composer` (keep
the model-capability banners below the selector they explain), or show the error as a transient surface
anchored above the composer.

### P2-3 — Three different top-bar implementations in one workspace
`AgentWorkspaceHomeScreen.kt:102-117` (custom 56 dp Box), `AgentTaskDetailScreen.kt:179-244`
(custom 56 dp Box), `AgentSkillsScreen.kt:62-90` / `AgentMcpScreen.kt:61-89` (M3 `TopAppBar`, default
64 dp). The header grows 8 dp and the title/actions re-anchor when entering Skills/MCP. Extract one
`AgentTopBar` composable (title, leading, actions, 56 dp, status-bar inset) and use it in all four
surfaces.

### P2-4 — Elevation/shadow contradicts the feature's own "no elevation" statement
`AgentHomeComposer.kt:226` `shadowElevation = 2.dp`; `AgentQuestionCard.kt:223` `tonalElevation = 2.dp`;
`AgentTaskDetailScreen.kt:738` `tonalElevation = 2.dp`, while `AgentWorkspaceComponents.kt:31-34` claims
"no new colours, cards, gradients or elevation were introduced". M3 tonal elevation tints the surface
with `primary` at ≈7 %, which is a colour change in both modes. Either remove the elevation and rely on
the border (DSH's cards are border/flat), or correct the claim and record the visual change. Related to
P1-6's card separation.

### P2-5 — Drawer "新任务" only clears the home draft
`AgentHomeDrawer.kt:88` `closeThen { actions.onDraftChange("") }`. It does not clear
`detailGoalDraft`/`questionAnswerDraft` (harmless today because the drawer is home-only), does not
dismiss notices, and does not focus the composer. DSH's "新会话" opens a fresh, focused input. Minimum:
have the home observe a one-shot "focus composer" intent after the drawer closes; otherwise the label
promises a fresh start while the previous notice banner and draft-side state may still be present.

### P2-6 — Skills/MCP empty states are top-aligned plain paragraphs
`AgentSkillsScreen.kt:143-157` and `AgentMcpScreen.kt:131-145` render two `Text` lines at the top of the
list; DSH centres its empty state with a glyph. These are secondary surfaces, so this is polish, not a
blocker. If changed, use the same centred `EmptyTaskHistory` pattern (`AgentHomeDrawer.kt:452-473`) and
keep the import/add buttons directly adjacent (they already are).

### P2-7 — Empty-state contrast relies on alpha in dark mode
`AgentHomeDrawer.kt:455-471` uses `onSurfaceVariant.copy(alpha = 0.55f)` for the icon and
`copy(alpha = 0.75f)` for "从上方新任务开始". In dark, 75 % of #9AA0A6 over #202124 composites to
≈#7B8086 → ≈4.1:1 on `labelSmall` (11 sp), below the 4.5:1 text threshold; the icon composites to
≈2.8:1. Use the full `onSurfaceVariant` role for the text, and either the full colour for the icon or
mark it clearly decorative (`contentDescription = null` already) and accept lower contrast for the
glyph only.

### P2-8 — Per-message time / footer metrics need a projection field before the UI can show them
`AgentTaskDetailUiState` (`AgentWorkspaceModels.kt:240-265`) has no task timestamp and no token/usage
values. To close the DSH gap in P1-9, add read-only fields to `projectDetail`
(`AgentWorkspaceProjection.kt:442-...`): e.g. `createdText = formatClockTime(task.createdAtMillis)`
(already available via `AgentWorkspaceFormatting.kt:27`) and, only if the owner exposes it, token/cache
counters. Owner stays `projectDetail`; the UI must not compute or estimate these.

### P2-9 — Sheet height is expressed against the window, not the available sheet space
`AgentModelPickerSheet.kt:54-60` uses `fillMaxHeight(0.85f)` inside `ModalBottomSheet`, and
`AgentApprovalSheet.kt:63-68` uses `fillMaxHeight(0.92f)`. Both ignore the sheet's drag handle and
insets, so in landscape the fixed header rows plus 92 % content can exceed the sheet and leave the list
with 0 dp. Prefer letting the sheet measure to its content with
`Modifier.heightIn(max = ...)`, or `fillMaxHeight()` on the content and `weight(1f)` on the scrollable
list (already present) without the fraction.

### P2-10 — Verification coverage for this feature is absent (no dark/font-scale/drawer preview, no golden, instrumentation NOT_RUN)
`AgentWorkspacePreviews.kt` covers light states at 390×844 only. There is no preview of the drawer
(it is internal state), dark theme, fontScale ≥ 1.3, long task titles, IME-open, or the empty-archived
case. `AgentWorkspaceRouteTest.kt:24-30` states the instrumentation tests are NOT_RUN in the current
environment, and the tests do not cover tabs, drawer overflow, IME or dark mode. Per
`MCA_PROJECT_HARD_LOCK.md` §9.3/§12.1, every fix in this report needs a real render before/after
(light+dark, 360/400 dp, fontScale 1.0/2.0, keyboard open). Recommend adding at least:
`@Preview(name = "Detail dark", darkTheme = true)` via an explicit `McaTheme(darkTheme = true)` wrapper
in `PreviewFrame`, a `fontScale = 2f` preview, and one androidTest that asserts both tabs are displayed
and switching tabs keeps the underline under the selected label.

---

## 4. Recommended sequence (smallest reversible slices)

1. **P1-1** tab geometry (self-contained file; unblocks the header anatomy).
2. **P1-3 + P1-4** composer toolbars (self-contained; 48 dp targets, remove 84 dp padding and the
   duplicate attach button). These are the two most visible mismatches against the reference.
3. **P1-2** top bar + drawer access (needs the drawer lifted to `AgentWorkspaceContent`; the back
   description/test rename can ship first).
4. **P1-5** IME insets + bring-into-view (no state changes).
5. **P1-6** colour roles (design-system alias; only dark-mode values change → needs the §9.1/§10
   authorisation).
6. **P1-7** drawer structure/empty state (projection field for hidden-archived count).
7. **P1-8** semantics pass (pairs naturally with 1/3/4).
8. **P1-9** typography/grid (grid constants + status line; timestamp/metrics blocked on P2-8).
9. **P1-10** per-task scroll anchor (VM + Route signature).

Do not batch 5 (visual token change) with 1–4; the freeze contract treats them differently.

---

## 5. Cross-cutting proposals (for the integrator)

- **One dimension object** for the feature (`AgentWorkspaceDimens`): content 20 dp, composer 16 dp,
  header/tab ink 60 dp, toolbar target 48 dp, card radius 16 dp (already uniform), composer radius 28 dp
  (already uniform). Today the radii are consistent (16/28) but the horizontal grid is not.
- **One composer component** parameterised by mode, so home/detail cannot drift again (they already
  differ in container colour, margins, field height, leading actions and target sizes). The mode enum
  already exists (`AgentQuestionCard.kt:174-183`); home could reuse it with a `HOME` mode rather than a
  second hand-written toolbar.
- **One card surface role** (`McaSurfaceRoles.card`), as in P1-6, so the four detail cards and the two
  composers share the same fill rule; this is the smallest design-system change that fixes dark mode
  without a broad re-theme.
- **One header/tab anchor** (title and tabs at the same ink start), as in P1-1/P1-2; this is the single
  structural change that makes the screen read as DSH-shaped rather than "DSH-ish".

---

## 6. What was not verified (honest status)

| Item | Status | Why |
| --- | --- | --- |
| App build / compile | NOT_RUN | read-only review; no Gradle per task instruction |
| Device renders of any screen | NOT_RUN | no device/emulator used |
| Light/dark visual comparison | NOT_RUN | only DSH light screenshots supplied; dark values are read from `McaTheme.kt` |
| Font-scale / landscape / RTL | NOT_RUN | derived from layout code only |
| TalkBack traversal | NOT_RUN | semantics inspected in source only |
| IME screenshots | NOT_RUN | behaviour reasoned from insets/layout code |
| Compose preview render | NOT_RUN | no build |
| AndroidTest suite | NOT_RUN | stated as requiring a device (`AgentWorkspaceRouteTest.kt:24-30`) |

Anything in this report that the integrator cannot reproduce on a real render must stay open rather than
be closed on the strength of this document (HARD_LOCK §12.4).

---

## 7. Needs render confirmation (not ranked as defects)

1. **Home composer outline style:** reference 2's visible sliver of the home composer shows a
   *dashed* 1 dp outline; the app uses a solid border + 2 dp shadow (`AgentHomeComposer.kt:222-226`).
   The sliver is partially occluded by the drawer, so this may be a focus/preview artefact. Confirm with
   a dedicated DSH home capture before changing the border style.
2. **User-bubble shape:** DSH's bubble appears uniformly rounded (≈13 dp) in the reference; the app uses
   a 20/20/20/6 dp "tail" corner (`AgentTaskDetailScreen.kt:392-397`). Confirm radii on a clean capture.
3. **Drawer row pitch:** the 40 dp reference pitch is below the 48 dp Android target; if the team
   deliberately keeps 56 dp for accessibility, document the deviation instead of copying DSH.
4. **Icon optical sizes:** `McaIconButton` glyphs are 22 dp (`McaButtons.kt:53`), DSH's look ≈24 dp in
   the top bar. Measure both headers before adjusting; this affects every screen using the shared
   button.

---

## 8. Appendix — file:line index of the reviewed surfaces

| Area | File:line |
| --- | --- |
| Home scaffold, top bar, list padding, IME | `ui/AgentWorkspaceHomeScreen.kt:79-97, 98-117, 118-127` |
| Drawer sheet width | `ui/AgentWorkspaceHomeScreen.kt:82-88` |
| Brand + preview chip | `ui/AgentHomeComposer.kt:66-113` |
| Selector rows | `ui/AgentHomeComposer.kt:128-194` |
| Home composer card / field / toolbar | `ui/AgentHomeComposer.kt:196-352` (card 212-227, field 238-251, toolbar 254-315) |
| Egress dialog | `ui/AgentHomeComposer.kt:358-393` |
| Drawer content / chrome / list / footer | `ui/AgentHomeDrawer.kt:80-224, 227-300, 366-386, 452-575` |
| Detail top bar / tabs | `ui/AgentTaskDetailScreen.kt:170-296` |
| Conversation items / bubble / status | `ui/AgentTaskDetailScreen.kt:300-443` |
| Assistant actions / section labels / jump pill | `ui/AgentTaskDetailScreen.kt:512-568, 699-748` |
| Detail composer / toolbar / stats | `ui/AgentQuestionCard.kt:187-292, 346-501, 503-587` |
| Question option rows | `ui/AgentQuestionCard.kt:120-172` |
| Timeline row | `ui/AgentTimelineRowItem.kt:52-162` |
| Artifact row / dialog | `ui/AgentArtifactsSection.kt:39-113, 116-184` |
| Approval sheet | `ui/AgentApprovalSheet.kt:47-142` |
| Model picker / plus sheet | `ui/AgentModelPickerSheet.kt:40-160`, `ui/AgentHomePlusSheet.kt:28-118` |
| Skills / MCP screens | `ui/AgentSkillsScreen.kt:55-196`, `ui/AgentMcpScreen.kt:54-197` |
| Shared banners/buttons | `ui/AgentWorkspaceComponents.kt:36-207` |
| Theme values (light/dark roles) | `core/designsystem/McaTheme.kt:43-127` |
| Host overlay/back | `app/MainActivity.kt:369-388`, `feature/chat/.../SwipeBackPage.kt:157-164` |
| Scroll anchor owner | `AgentWorkspaceViewModel.kt:490-500`, `AgentWorkspaceRoute.kt:109-115` |

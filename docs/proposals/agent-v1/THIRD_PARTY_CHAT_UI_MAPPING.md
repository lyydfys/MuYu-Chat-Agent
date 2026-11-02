# third-party 聊天/Agent 界面 → MCA Agent 视觉与行为映射（v1 提案）

> 作用域：把 `feature/agent-workspace` 的「DSH 落地页 + 任务详情」形态，改成 **MCA Chat 页的会话形态**；以 third-party `ui/features/chat/**` 作为 **Agent 会话内容组织与行为** 的参照，以 `feature/chat` 作为 **视觉语言** 的唯一真值。
> 状态：`PROPOSAL / READ_ONLY_RESEARCH / NOT_IMPLEMENTED`。本文件是本次任务唯一写入；**未运行 Gradle、未做真机渲染、未改任何代码**。
> 基线：分支 `codex/enterprise-refactor`，HEAD `98eb8e4`；工作区 52 项未提交改动，`feature/agent-workspace/` 整体为 untracked。
> **并发事实**：本文撰写期间（2026-10-19 19:15–19:23）`feature/agent-workspace/.../ui/` 下 `AgentWorkspaceHomeScreen.kt`、`AgentHomeComposer.kt`、`AgentTaskDetailScreen.kt`、`AgentQuestionCard.kt`、`AgentTimelineRowItem.kt` 正被并行重写（落地页 hero 已删除、composer 已改为贴底 24dp 壳）。因此本文的 **「现状」行只代表 19:23 读取时刻**，并且一律用 **函数职责** 定位；行号仅供快速跳转，不作为合同。
> 姊妹文档：`DSH_UI_PARITY_MATRIX.md` 负责「MCA Agent ↔ DSH」屏幕级差距与内容层 P0（`RUN_TERMINAL.summary` 当正文）；本文负责「MCA Agent ↔ third-party 会话结构 ↔ MCA Chat 视觉语言」的 **可实现布局规格与复用清单**。两者的 API 编号不互相替代。

---

## 0. 一页结论

1. **目标形态只有一个会话面**：MCA Chat 的骨架是「顶栏 → 消息列表（weight 1）→ 贴底输入卡」，third-party 的 `AIChatScreen` 同样是「Header → ChatArea → 贴底 InputSection」，没有落地页。MCA Agent 应当收敛成同一骨架：**HOME = 同一会话面的空态**，任务历史留在抽屉里。
2. **视觉语言全部取 MCA Chat 的既有 token**（36dp 输入壳、18dp 列表边距、18dp 行距、20/20/20/6 用户气泡、48dp 圆形图标按钮、`McaMotion` 弹簧），**不复制 third-party 的圆角/配色/玻璃效果**。third-party 只提供内容组织方式与交互语义。
3. **Agent 特有的内容层 = 三段式 assistant turn**：运行状态行 → 可折叠「执行活动」工具行组 → 答案正文 → 动作行。third-party 的工具行是「16dp 图标 + 工具名 + 单行参数摘要，点击看详情」，不是每事件一张卡片；这与 `MCA_AGENT_V1_ARCHITECTURE.md` §13.3「默认列表与分隔，不给每个工具事件加大卡片」一致。
4. **权限提示保留 MCA 的既有表面**（内联卡 + 92% ModalBottomSheet），信息结构参考 third-party 的 `PermissionRequestOverlay`（盾牌图标 / 操作 / 工具 / 参数分组 / 拒绝-允许-始终允许）。「始终允许」需要契约新枚举，V1 不假实现。
5. **复用是「规格复用」而不是「模块依赖」**：`feature:agent-workspace` 的 `build.gradle.kts` 明确只依赖 `core:agent-contract` + `core:designsystem`，不得 import `feature:chat`。零风险复用只有 design system 组件；其余要么提升到 `core:designsystem`，要么在本模块按同一 dp 值实现（§7）。
6. **最大风险不是视觉，而是内容**：`finalAnswer` 在生产链路里等于终态枚举名（见 `DSH_UI_PARITY_MATRIX.md` §6.1）。本文给出的「assistant 正文」规格只有在 API-1 落地后才有显示价值；视觉改造不应掩盖这一点。

---

## 1. 证据与方法

### 1.1 只读范围

| 侧 | 文件 | 读取内容 |
| --- | --- | --- |
| third-party | `THIRD_PARTY/app/src/main/java/com/ai/assistance/thirdparty/ui/features/chat/screens/AIChatScreen.kt`（2087 行） | 页面骨架（`CustomScaffold` + `ChatScreenContent` + 贴底 `ChatInputBottomBar`）、历史面板 300ms 滑入、输入栏装配 |
| third-party | `.../components/ChatScreenHeader.kt`（294 行） | 顶栏 Row、32dp 图标槽、上下文占用环、token 下拉 |
| third-party | `.../components/ChatHeader.kt`（198 行） | 32dp 图标按钮、角色切换 chip（24dp 头像 + 116dp 文本）、运行任务角标 |
| third-party | `.../components/ChatArea.kt`（1628 行） | 消息列（`verticalScroll` 而非 Lazy）、8dp 行距、加载三点、右侧滚动导航器 `offset(y=-56dp)` |
| third-party | `.../components/style/bubble/BubbleAiMessageComposable.kt` / `BubbleUserMessageComposable.kt` | 32dp 头像、4/20/20/20 与 20/4/20/20 气泡、12dp 内容内边距、代码/表格/XML 时气泡转全宽 |
| third-party | `.../components/style/input/agent/AgentChatInputSection.kt`（3339 行） | Agent 输入卡：12/8 内边距、min 44dp 输入、maxLines 6、模型 pill、Tune、+、36dp 圆形动作键（外圈 40dp 进度环）、附件 LazyRow、待发队列、处理状态行、token 超限提示、三档权限分段控件 |
| third-party | `.../components/part/ToolDisplayComponents.kt` + `part/XmlCanvasSummaryComponents.kt` | `CompactToolDisplay` / `CanvasToolSummaryRow`（16dp 图标、8dp 间距、labelMedium 工具名 + bodySmall 70% 摘要单行 120 字符）/ `CanvasToolResultRow`（箭头 + 成功勾/失败叉 + 复制） |
| third-party | `.../ui/permissions/PermissionRequestOverlay.kt`（475 行） | 工具权限遮罩卡：黑 60% scrim、85%×65% 卡、24dp 圆角、36dp 盾牌、20sp 标题、参数分组、44dp 拒绝/允许、12sp「始终允许」、底部提醒 |
| third-party | `.../components/ChatScreenContent.kt` + `AttachmentSelector.kt` | 历史侧面板 280dp；附件选择面板顶部 16dp 圆角的贴底面板 |
| MCA | `feature/chat/.../ChatScreen.kt`、`ChatStatusBarHeader.kt`、`ChatMessageListSection.kt`、`ChatMessageBubbleScreens.kt`、`ChatBubbleMessageBlocks.kt`、`ChatComposerScreens.kt`、`ChatComposerActionMenuViews.kt`、`ChatReasoningPerformanceScreens.kt`、`ChatAssistantActionViews.kt`、`ChatViewportContract.kt`、`ChatHistoryDrawerScreens.kt` | 目标 token 与骨架（§3） |
| MCA | `core/designsystem/.../McaTheme.kt`、`McaButtons.kt`、`McaMotion.kt` | 唯一色彩/图标按钮/动效权威 |
| MCA | `feature/agent-workspace/.../ui/*`、`AgentWorkspaceModels.kt`、`AgentWorkspaceRoute.kt`、`build.gradle.kts` | 现状、可复用件、模块边界 |

`THIRD_PARTY` = `C:\Users\sy427\AppData\Local\Temp\thirdparty-src`（只读参照仓库，未构建、未修改）。

### 1.2 度量口径

- MCA 侧数值 **全部取自源码字面量**（dp/sp/Color）；third-party 侧同样取自源码字面量，不使用截图折算。
- 本文不给出「像素级 parity」结论；只声明「同一 dp token、同一结构顺序、同一状态覆盖」。
- 颜色只引用 `McaTheme` 角色名或 `ChatColorTokens.kt` 的既有字面量，不新增品牌色。

### 1.3 证据缺口（必须先声明）

- 没有真机/模拟器渲染，没有浅深色对比，没有 1.5×/2.0× 字体实测，没有 TalkBack、IME、长中文/代码/表格下的实际换行结果。
- third-party 未找到「向用户提问」工具的内联 UI：全仓 grep `ask_user` / `AskUserQuestion` 无命中，唯一相关命中是 `ToolPermissionSystem.kt` 里的权限等级解析 `"ASK"`。提问卡形态只能沿用 MCA 既有实现，third-party 不提供参照。
- third-party 的附件体系（相机/相册/文件/屏幕/通知/位置/记忆/包）远超 MCA Agent V1 的运行时可执行面；本文只规格化「槽位与状态」，**不承诺**不可执行的动作。
- 并行改写进行中（见页首），「现状」表可能在合并时已部分过时。

---

## 2. third-party 目标形态（源码事实）

### 2.1 单一会话面

`AIChatScreen` 的稳定骨架（`AIChatScreen.kt` `ChatInputBottomBar` 装配处）：

```
Box(fillMaxSize)
└ CustomScaffold(containerColor = Transparent)
  └ Box(fillMaxSize)                        // 键盘位移层
    ├ Box(matchParentSize, translationY)     // 视口上移
    │ └ ChatScreenContent(fillMaxSize, bottomInset = bottomBarHeightDp)
    └ Box(align = BottomCenter, onGloballyPositioned 记录高度)
      └ ChatInputBottomBar(...)              // 贴底常驻，不随列表滚动
```

- 列表底部内边距 = 输入卡实测高度（`bottomInset = bottomBarHeightDp`），**不是拍脑袋的常数**。MCA Chat 用同一思路但更明确：`ChatViewportContract.kt` 规定 `composerOverlap = 实测高度 ?: 100dp`、`listBottomPadding = overlap + 18dp`。
- 历史不占主布局：`showChatHistorySelector` 时先铺 30% 黑遮罩，再从左侧 `slideInHorizontally(-it)` 滑入 **280dp** 面板（`ChatHistorySelectorPanel`）。
- 没有「首页 / 详情」两套 chrome：新对话就是空消息列表。**这正是「Agent 页不要看起来像 DSH 落地页」的直接答案**。

### 2.2 顶栏（third-party 参照值，不作为 MCA 目标）

| 项 | 值 |
| --- | --- |
| 外层 | `Row(fillMaxWidth, padding(horizontal = 16.dp, vertical = 6.dp))`，背景 `surfaceVariant.copy(alpha = 0.2f)` 或透明 |
| 图标按钮 | 32dp 圆形容器，glyph 20dp；历史 / 画中画 |
| 运行角标 | 运行任务 ≥2 时，历史按钮变成 32dp 胶囊（`primaryContainer`）：20dp 图标 + 任务数 `labelSmall` |
| 角色 chip | `widthIn(max = 176.dp)`，头像 24dp 圆形，名称 `bodyMedium` 上限 116dp |
| 右侧 | 32dp 上下文占用环（`CircularProgressIndicator` stroke 3dp，中心 9sp 百分比）+ 下拉明细 |

### 2.3 消息区

| 项 | 值（`ChatArea.kt`） |
| --- | --- |
| 容器 | `Column(padding(horizontal = horizontalPadding))` + `verticalScroll(scrollState)`（非 Lazy） |
| 内边距 | `top = headerHeight`（覆盖式顶栏）、`bottom = bottomInset` |
| 行距 | 每条消息后 `Spacer(8.dp)` |
| 滚动导航 | `ChatScrollNavigator` 对齐 `CenterEnd`、`offset(y = -56.dp)`、`padding(end = 10.dp)` |
| 加载态 | 三点动画 `LoadingDotsIndicator`，BUBBLE 样式额外 `offset(y = -24.dp)` |
| 长按 | 气泡长按出上下文菜单；多选模式选中项加 `primary@10%` 圆角 8dp 底 |

### 2.4 用户 / AI 气泡

| 项 | 用户 | AI |
| --- | --- | --- |
| 头像 | 32dp（可选显示） | 32dp |
| 形状 | `RoundedCornerShape(20, 4, 20, 20)` | `RoundedCornerShape(4, 20, 20, 20)` |
| 内容内边距 | 12dp（可配置左右） | 12dp |
| 最小高 | 44dp | 44dp |
| 最大宽 | 行宽 85%（无头像布局）或行宽（有头像布局） | 同左 |
| 全宽切换 | — | 含代码块/表格/XML/图片/块级 LaTeX 时转全宽 |
| 数据来源 | 气泡头（头像 + 名字 + 时间）+ 文本 | 流式文本 + 思考区 + 工具区 + 正文 |

### 2.5 工具调用与结果行（Agent 内容层的关键参照）

- **调用行**（`CanvasToolSummaryRow`）：`16dp` 图标 + `8dp` + 工具名（`labelMedium`，`primary`，宽度 80–120dp 上限）+ `8dp` + 参数摘要（`bodySmall`，`onSurface@70%`，**单行**，120 字符截断），整行可点击，无边框、无卡片、无阴影。
- **结果行**（`CanvasToolResultRow`）：`SubdirectoryArrowRight` 图标 + 摘要（`bodySmall`，成功 `onSurface@80%` / 失败 `error@80%`）+ 尾部成功勾或失败叉 + 复制按钮。
- **详情**：点击调用行 → `ContentDetailDialog`（标题「工具名 调用参数」，等宽正文）；展开不是内联，是弹窗。
- **分组**：`ThinkToolsXmlNodeGrouper` 把思考与工具节点按 `<think>`/`<tool>` 流分组，组可整体展开/收起（`expandThinkToolsGroups`）。

### 2.6 Agent 风格输入卡

| 项 | 值（`AgentChatInputSection.kt`） |
| --- | --- |
| 卡片形状 | 贴底：`RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)`；悬浮：`RoundedCornerShape(22.dp)` |
| 卡片内边距 | `padding(horizontal = 12.dp, vertical = 8.dp)` |
| 输入框 | `OutlinedTextField` 四边透明、`shape = 14dp`、`heightIn(min = 44.dp)`、`maxLines = 6`、`minLines = 1`、`fontSize 14.sp / lineHeight 20.sp`；尾部全屏图标 |
| 工具行 | 模型 pill（`weight(1f)`、`widthIn(max = 220.dp)`、`RoundedCornerShape(12.dp)`、1dp `outline@20%` 边框、`padding(10,6)`、文本上限 160dp + 18dp 箭头）｜ Tune 34dp｜ `+` 36dp｜动作键 |
| 动作键 | 36dp 圆形，外套 40dp 容器；四态：发送（primary）/ 队列（tertiary，`+`）/ 取消（error，`X`）/ 语音（mic）；处理中在容器内画 2dp 进度环 |
| 附件条 | 输入卡上方 `LazyRow`，`padding(12,0,12,6)`，chip 间距 6dp |
| 状态行 | 输入卡上方 `bodySmall onSurface@80%`；进度色按 `InputProcessingState` 变化 |
| 待发队列 | 输入卡上方面板，可展开/删除/编辑/立即发送 |
| token 超限 | `labelSmall`、`error`，`padding(horizontal = 12, bottom = 4)` |

### 2.7 权限提示（third-party）

```
Box(fillMaxSize, bg = Black@60%)
└ ElevatedCard(fillMaxWidth(0.85f), fillMaxHeight(0.65f), radius 24dp, elevation 6dp, surfaceContainerHigh)
  └ Column(padding 20)
    ├ 滚动区（weight 1）
    │ ├ 36dp 盾牌图标（primary）
    │ ├ 「权限请求」20sp Bold
    │ ├ 「AI 助手请求执行操作」15sp onSurfaceVariant，居中
    │ └ 详情面板（surfaceContainer，radius 16dp，padding 16dp，纵向 16dp 间距）
    │   ├ 请求的操作 / 使用的工具（labelMedium 15sp + bodyLarge 17sp）
    │   └ 参数列表（surfaceContainerLow，radius 12dp，padding 10dp，参数名 primary / 值 onSurfaceVariant）
    └ 操作区
      ├ 拒绝（OutlinedButton，weight 1，h 44dp，radius 12dp）
      ├ 允许（Button，weight 1，h 44dp）
      ├ 「始终允许」（12sp primary 文本链，居中）
      └ 底部提醒（12sp onSurfaceVariant，居中）
```

行为：仅在 `PermissionLevel.ASK` 时弹出；结果 `ALLOW / DENY / ALWAYS_ALLOW`；点击后立即 dismiss。会话级三档权限（`FORBID / ASK / ALLOW`）在输入区「额外设置」里，是 **28dp 胶囊分段控件**（`AgentPermissionSegmentedControl`，选中 `primary` 填充，未选 1dp `outline@65%`）。

---

## 3. MCA Chat 的视觉语言（本项目唯一目标 token）

### 3.1 骨架（`ChatScreen.kt`）

```
ModalNavigationDrawer(drawer = ChatHistoryDrawer)
└ Box(fillMaxSize, bg = colorScheme.background, imePadding())
  ├ ChatBackground
  ├ Column(fillMaxSize)
  │ ├ ChatStatusBar(...)                 // 顶栏
  │ ├ PromptContextUsageLine(usage)?     // 可选状态带
  │ └ ChatMessageListSection(weight 1f, fillMaxWidth)
  └ ChatInputBar(align BottomCenter)      // 叠加在列表之上
```

### 3.2 Token 表

| 区域 | Token | 值 | 依据 |
| --- | --- | --- | --- |
| 顶栏 | 外框 | `Row(fillMaxWidth, bg = surface, padding(start 18, end 18, top 14, bottom 10))` | `ChatStatusBarHeader.kt` `ChatStatusBar` |
| 顶栏 | 左侧按钮 | `Surface(size 48.dp, CircleShape, surface, shadow 5dp)` + `McaIconButton(glyphSize 24.dp)` | 同上 |
| 顶栏 | 模型胶囊 | `CircleShape`、`shadow 5dp`、`padding(start 10, end 12, top 7, bottom 7)`；`McaLogoMark(24.dp)` + 8dp + 名称 `13sp/15 SemiBold` + 副标题 `11sp/13` + 状态 pill `10sp` + 15dp 箭头 | 同上 |
| 顶栏 | 右侧簇 | `Surface(CircleShape, shadow 5dp)` 内 `Row(padding(h 4, v 2))` + 两个 48dp `McaIconButton`（22dp / 28dp glyph） | 同上 |
| 列表 | LazyColumn | `padding(horizontal = 18.dp)`、`contentPadding(top 26.dp, bottom = overlap + 18.dp)`、`spacedBy(18.dp)` | `ChatMessageListSection.kt` |
| 列表 | 底部预留 | `composerOverlap = 实测高度 ?: 100dp`；`bottomPadding = overlap + 18dp` | `ChatViewportContract.kt` |
| 列表 | 进/出动画 | `animateItem(fadeIn = spring(dampingRatio, stiffness), placement = spring, fadeOut = tween(140))` | `ChatMessageListSection.kt` |
| 用户气泡 | 容器 | 右对齐 `Row(horizontalArrangement = End)`；`Column(widthIn(max = 280.dp), horizontalAlignment = End)` | `ChatMessageBubbleScreens.kt` `UserMessageBubble` |
| 用户气泡 | 形状 / 内边距 | `RoundedCornerShape(20, 20, 20, 6)`；`padding(horizontal 17.dp, vertical 13.dp)`，内部 `spacedBy(10.dp)` | 同上 |
| 用户气泡 | 颜色 | 亮色 `Color(0xFFEEF4FF)` + 文本 `0xFF202124`；暗色 `primaryContainer@42%` + `onPrimaryContainer` | 同上 |
| 用户气泡 | 文本 | `bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)` | 同上 |
| 用户气泡 | 动作 | 气泡下 48dp 图标按钮（固定 18dp、复制 18dp），`onSurfaceVariant@78%` | 同上 |
| 助手块 | 容器 | `Surface(color = Transparent, fillMaxWidth, padding(end = 4.dp))`；内 `Column(padding(h 2, v 8), spacedBy(12.dp))` | `ChatBubbleMessageBlocks.kt` `AssistantMessageBlock` |
| 助手块 | 头像 / 文本 | `AnimatedMcaAssistantMark` + 10dp + 内容（推理区 `weight(1f)`） | 同上 |
| 推理区 | 形态 | 标题 `bodySmall Medium` + 18dp 下箭头（`graphicsLayer` 旋转 180°）+ 左侧 2dp `onSurfaceVariant@16%` 竖条 + 10dp + `13sp/20` 文本 | `ChatReasoningPerformanceScreens.kt` `ReasoningPanel` |
| 动作行 | 形态 | 右对齐，3 个 48dp `IconButton`（重新生成 / 复制 / 更多），glyph 18–20dp | `ChatAssistantActionViews.kt` `AssistantActionRow` |
| 输入卡 | 外壳 | `Surface(fillMaxWidth, padding(horizontal 16, vertical 10), RoundedCornerShape(36.dp), shadow 10dp)`；内 `Column(padding 7.dp, navigationBarsPadding())` | `ChatComposerScreens.kt` `ChatInputBar` |
| 输入卡 | 加号 | 40dp 圆形，底色 `McaInputIconSurface` / 暗色 `surfaceVariant`，glyph 24dp `McaPrimaryBlue` | 同上 |
| 输入卡 | 输入胶囊 | `Surface(weight 1f, heightIn(min 40.dp), CircleShape, McaInputField)`，内 `Box(padding(h 16, v 10))`；`BasicTextField(maxLines 4, 15sp/20sp)`，占位同字号 `McaInputPlaceholder` | 同上 |
| 输入卡 | 主按钮 | `FloatingActionButton(size 40.dp, CircleShape, McaPrimaryBlue, elevation 0)`，`Crossfade` 在 发送/停止/生成图 间切换；禁用 `alpha = 0.55f` | 同上 |
| 输入卡 | 状态行 | 壳内顶部 `AnimatedVisibility` + `McaMotion.sizeChangePx` 弹簧，`labelSmall` 文案 | 同上 |
| 空态 | 卡片 | `Surface(surface, RoundedCornerShape(8.dp), tonalElevation = 1.dp, fillMaxWidth)` + `Column(padding 18.dp, spacedBy 10.dp)`；`headlineSmall` 标题 + `bodyMedium` 说明 | `ChatBackgroundScreens.kt` `EmptyChatPanel` |
| 抽屉 | 容器 | `ModalDrawerSheet(fillMaxSize, containerColor = background)`；内容 `padding(horizontal 20, vertical 18)`；边缘滑动返回 | `ChatHistoryDrawerScreens.kt` |
| 颜色 | 角色 | `primary #1A73E8`、`primaryContainer #E8F0FE`、`onPrimaryContainer #185ABC`、`background #F8F9FA`、`surface #FFFFFF`、`surfaceVariant #F1F3F4`、`onSurfaceVariant #5F6368`、`outlineVariant #DADCE0` | `McaTheme.kt` |
| 颜色 | chat 专用字面量 | `McaPrimaryBlue #3F7DE8`、`McaInputShell #FEFEFF`、`McaInputField #F3F7FF`、`McaInputIconSurface #EAF1FF`、`McaInputText #1F2937`、`McaInputPlaceholder #8A93A3` | `ChatColorTokens.kt` |
| 动效 | 统一弹簧 | `McaMotion.press(1.0/1100)`、`sizeChange(0.86/420)`、`sizeChangePx`、`pageSlide`、`fade(ms)`；`mcaPressScale`；`mcaEnterReveal` | `McaMotion.kt` |

---

## 4. 差距：当前 Agent 页 ↔ 目标

> 现状取 2026-10-19 19:23 读取结果；并行改写进行中，合并前需重查。

| 区域 | 当前（`feature/agent-workspace`） | 目标（MCA Chat 形态） | 差距 |
| --- | --- | --- | --- |
| 页面数量 | HOME（任务列表 + 贴底 composer）与 DETAIL（返回箭头 + 对话/轨迹 tab + 贴底 composer）两套 chrome | 一个会话面；HOME = 空态；任务历史在抽屉 | 结构级；需合并 |
| 列表度量 | DETAIL：`contentPadding(20, 14, 20, 12)`、`spacedBy(10.dp)`；HOME：`(20, 4, 20, 16)`、`spacedBy(8.dp)` | `padding(horizontal 18)`、`contentPadding(top 26, bottom overlap+18)`、`spacedBy(18)` | 三处都不一致 |
| 用户目标 | 320dp 上限、`20/20/20/6`、`primaryContainer`、`padding(16,12)`、`bodyLarge` | 280dp、同形状、`#EEF4FF`、`padding(17,13)`、`16sp/24` | 尺寸与配色不一致 |
| Assistant | 单个 `Text(finalAnswer)`；活动在独立「执行活动」区（DETAIL）或 tab | 头像 + 推理/工具/正文/动作 的单条答复 | 结构级 |
| 工具行 | `AgentTimelineRowItem`：16dp 圆角卡 + 1dp 边框 + 30dp 图标砖 + 标题 + 2 行摘要 + 展开详情块 | third-party 式无边框紧凑行（18dp 图标 + 标题 + 单行摘要 + 展开） | 形态不一致（卡片过重） |
| 输入卡 | 24dp 壳、`padding(12)`、min 56–120dp、48dp 发送、plus/附件/盾牌/模型 四个 48dp 按钮 | 36dp 壳、`padding(h16,v10)` + 内 7dp、40dp 加号 + 胶囊输入 + 40dp 主按钮 | 形态与尺寸不一致 |
| plus | `ModalBottomSheet`（scrim + 把手） | MCA Chat：锚定浮层卡 238dp 宽 / 30dp 圆角 / 48dp 行 / 34dp 图标砖 | 形态不一致 |
| 审批 | 内联 16dp 卡 + 92% sheet；仅 拒绝/允许一次 | 保留表面；补 third-party 的信息分组与（契约允许后的）「本会话始终允许」 | 信息结构 partial |
| 提问 | 内联卡，选项只写草稿，需再手动发送 | 选项即提交；自由文本走 composer | 交互 partial |
| 空态 | 居中图标 + 两行文案（HOME 列表空态） | 会话内空态卡（8dp 圆角、18dp padding、headlineSmall） | 形态不一致 |
| 抽屉 | 76%（≤340dp）、surface 底 | Chat：`fillMaxSize` sheet、`background` 底、20/18 padding、边缘滑动返回 | 度量不一致 |

---

## 5. 映射总表（third-party → MCA Agent）

| # | third-party 元素 | third-party 行为 | MCA Agent 目标 | 承载组件（新/旧） | 复用状态 |
| --- | --- | --- | --- | --- | --- |
| M1 | 单一会话面（`AIChatScreen`） | 顶栏 + 消息列 + 贴底输入卡；新对话 = 空列表 | 同一骨架；任务历史入抽屉 | `AgentChatScreen`（新） | 结构照搬，代码重写 |
| M2 | 顶栏（`ChatScreenHeader`） | 32dp 图标 + 角色 chip + token 环 | 48dp 圆形菜单 + 模型胶囊（模型名 + 运行副标题 + 本地/联网 pill）+ 新建 + 品牌菜单 | `AgentChatTopBar`（新） | 用 Chat 几何，内容重写 |
| M3 | 消息列（`ChatArea`） | 8dp 行距、贴底预留 | 18dp 列表边距 / 18dp 行距 / overlap+18 | `AgentChatMessageList`（新） | 常量照搬 `ChatViewportContract` 思路 |
| M4 | 用户气泡 | 85%/全宽、20/4/20/20、12dp | 280dp、20/20/20/6、17/13、`#EEF4FF` | `AgentUserGoalBubble`（新） | Chat 规格 |
| M5 | AI 气泡 | 32dp 头像、4/20/20/20、全宽切换 | 无气泡底、`AnimatedMcaAssistantMark` + 全宽正文 | `AgentTurnBlock`（新） | 需品牌 mark 提升（§7） |
| M6 | 思考/工具分组 | 组可整体展开；工具行无边框 | 「执行活动」可折叠组 + 紧凑工具行 | `AgentToolActivityGroup` / `AgentToolActivityRow`（新） | third-party 形态 + MCA token |
| M7 | 工具结果行 | 箭头 + 成功/失败 + 复制 | 在行尾显示状态（勾/叉/进度）+ 展开详情 | 同上 | 形态沿用 |
| M8 | Agent 输入卡 | 20dp 顶圆角、12/8、模型 pill + Tune + `+` + 36dp 四态键 | 36dp 壳、40dp `+` + 胶囊输入 + 40dp 主键（发送/停止/授权） | `AgentChatComposer`（新，替换 `AgentHomeComposer` 与 `AgentDetailComposer`） | Chat 外壳 + Agent 动作 |
| M9 | 附件条 + 附件面板 | LazyRow chip + 贴底选择面板 | 保留槽位；V1 只有真实动作（导入任务包）时不显示 chip | `AgentAttachmentStrip`（新，条件渲染） | 槽位照搬，数据受限于 API |
| M10 | 待发队列 | 队列面板（展开/编辑/重发） | 不实现（Agent 一次一个 run；契约无队列） | — | 不做，记录原因 |
| M11 | 处理状态行 | 状态文案 + 进度环 | 顶栏副标题 + 工具行进度 + composer 停止键 | `AgentChatTopBar` + `AgentToolActivityRow` | 分散承载 |
| M12 | 三档权限分段 | FORBID/ASK/ALLOW 28dp 胶囊 | plus 面板内「工具策略」分段（需契约） | `AgentPolicySegmented`（新，V1 不接线） | 形态照搬，API 待定 |
| M13 | 权限遮罩卡 | 85%×65% 卡、盾牌、参数分组、拒绝/允许/始终允许 | 内联卡（轻）+ 92% sheet（重）；始终允许需 API | `AgentApprovalInlineCard`（新）+ `AgentApprovalSheet`（旧，微调） | MCA 表面 + third-party 信息结构 |
| M14 | 历史 280dp 侧板 | 左侧滑入 + 30% 遮罩 | 抽屉（保持现有内容，容器向 Chat 对齐） | `AgentHomeDrawer`（旧） | 保留，P2 对齐 |
| M15 | 空态 | 无（列表空即空） | 会话内空态卡 | `AgentChatEmptyState`（新） | Chat `EmptyChatPanel` 规格 |
| M16 | 滚动导航器 | 右侧 `-56dp` 浮动按钮 | 「回到最新」胶囊（现有 `JumpToLatestPill`） | 旧，保留 | 保留 |
| M17 | 全屏输入 / 语音 | 有 | 不实现（无契约） | — | 不做 |

---

## 6. Compose 布局规格（逐区）

> 以下为可实现规格；`▸` 后为落地文件建议。伪代码只表达结构与常量，不是最终代码。

### 6.1 页面骨架

```
Box(Modifier.fillMaxSize().background(colorScheme.background).imePadding()) {
    Column(Modifier.fillMaxSize()) {
        AgentChatTopBar(...)                                  // 含 statusBarsPadding
        state.notice / state.error / package / model 横幅      // 现有 AgentInlineBanner
        AgentChatMessageList(Modifier.weight(1f).fillMaxWidth())
    }
    AgentChatComposer(Modifier.align(Alignment.BottomCenter)) // 实测高度回传列表
}
```

规则：
- 根 `Box` 承担 `imePadding()`（与 `ChatScreen.kt` 一致），composer 内部不再重复 `imePadding`，只用 `navigationBarsPadding()`。
- composer 用 `onSizeChanged` 回传高度 `composerOverlapPx`；列表 `bottom = resolveOverlap(overlap) + 18.dp`，首帧回退 100dp（照搬 `ChatViewportContract` 语义，常量在 agent 模块内自带，避免跨 feature 依赖）。
- 不再有 tab 行；`对话 / 轨迹` 的二选一由「主会话面 + 轨迹 sheet」替代。

▸ 新增 `ui/AgentChatScreen.kt`（目标 ≤ 320 行）。

### 6.2 顶栏

```
Row(Modifier.fillMaxWidth().statusBarsPadding().background(surface)
        .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
    verticalAlignment = CenterVertically) {
    Surface(size 48.dp, CircleShape, surface, shadow 5.dp) {
        McaIconButton("打开任务与工具抽屉", onOpenDrawer, glyphSize = 24.dp, tint = onSurface) { Icon(Menu) }
    }
    Spacer(12.dp)
    Box(Modifier.weight(1f)) {
        Surface(fillMaxWidth, CircleShape, surface, shadow 5.dp) {
            Row(padding(start 10, end 12, top 7, bottom 7)) {
                BrandMark(24.dp)                                   // 现有 ic_agent_brand
                Spacer(8.dp)
                Column(Modifier.weight(1f)) {
                    Text(model.displayName ?: "选择模型", 13.sp/15.sp SemiBold, onSurface, maxLines 1, ellipsis)
                    Text(statusSubtitle, 11.sp/13.sp, onSurfaceVariant, maxLines 1, ellipsis)
                    // statusSubtitle 例："本地 · 已完成" / "正在执行 · 第 2 步" / "等待授权"
                }
                Surface(CircleShape, if (networkEnabled) primaryContainer else surfaceVariant) {
                    Text(if (networkEnabled) "联网" else "本地",
                         Modifier.padding(horizontal 8.dp, vertical 4.dp),
                         style = labelSmall.copy(fontSize = 10.sp), Bold)
                }
                Icon(KeyboardArrowDown, 15.dp, onSurfaceVariant, Modifier.padding(start = 4.dp))
            }
        }
    }
    Spacer(12.dp)
    Surface(CircleShape, surface, shadow 5.dp) {
        Row(Modifier.padding(horizontal 4.dp, vertical 2.dp)) {
            McaIconButton("新建任务", onNewTask, glyphSize = 22.dp, tint = LocalContentColor.current) { Icon(Edit) }
            McaIconButton("Agent 菜单", onOpenMenu, glyphSize = 28.dp) { BrandMark(28.dp) }
        }
    }
}
```

- 胶囊点击：`focusManager.clearFocus(force = true)` + 隐藏键盘后再展开 —— 与 `ChatStatusBar` 完全一致，避免键盘与下拉打架。
- 下拉内容（全部接真实动作）：模型（`onOpenModelPicker`）、联网策略（`onNetworkEnabledChange`）、Skills（`onOpenSkills`）、MCP（`onOpenMcp`）、执行轨迹（打开轨迹 sheet）、导出任务包（`onExportTaskPackage`）、删除任务（`onRequestDeleteTask`）。
- 无障碍：胶囊 `semantics(mergeDescendants = true) { contentDescription = "选择模型与运行状态"; role = Button }`；所有图标按钮必须传 `contentDescription`（`McaIconButton` 强制）。

▸ 新增 `ui/AgentChatTopBar.kt`（目标 ≤ 180 行，含下拉）。

### 6.3 消息列表与 item 顺序

```
LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
    contentPadding = PaddingValues(top = 26.dp, bottom = composerOverlap + 18.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp)
) {
    item("goal")      { AgentUserGoalBubble(detail.goal) }           // 右对齐
    item("turn")      { AgentTurnBlock(detail, ...) }                 // 状态行 + 活动 + 正文 + 动作
    if (waitingForApproval) item("approval") { AgentApprovalInlineCard(...) }
    detail.question?.let { item("question") { AgentQuestionCard(...) } }
    items(artifacts)  { AgentArtifactRow(it) }                        // 现有
    item("actions")   { AgentAssistantActionRow(...) }                // 复制 / 分享 / 导出
}
```

- 空态（无 `detail`，即 HOME）：只放 `item("empty") { AgentChatEmptyState(...) }`，**不渲染** hero/品牌区。
- item key 使用稳定业务键（`goal`、`turn`、`approval-<id>`、`question-<id>`、`artifact-<id>`）；`animateItem` 用 `McaMotion` 弹簧，与 Chat 一致。
- 滚动跟随：`listState.canScrollForward == false` 视为「贴底」，此时新 item 到达才自动滚到底；用户上滑后不强制跟随。发送、创建任务、提交回答后显式 `animateScrollToItem(lastIndex)`。
- 「回到最新」胶囊：`listState.canScrollForward == true` 时显示，`align(BottomCenter).padding(bottom = 12.dp)`（复用现有 `JumpToLatestPill`）。

▸ 新增 `ui/AgentChatMessageList.kt`（目标 ≤ 260 行）。

### 6.4 用户目标气泡（= MCA 用户气泡）

```
Row(fillMaxWidth, horizontalArrangement = End) {
    Column(widthIn(max = 280.dp), horizontalAlignment = End) {
        Surface(color = if (dark) primaryContainer.copy(alpha = .42f) else Color(0xFFEEF4FF),
                contentColor = if (dark) onPrimaryContainer else LocalContentColor.current,
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                modifier = fillMaxWidth) {
            Column(Modifier.fillMaxWidth().padding(horizontal 17.dp, vertical 13.dp)) {
                SelectionContainer {
                    Text(goal, bodyMedium.copy(fontSize 16.sp, lineHeight 24.sp),
                         color = if (dark) onPrimaryContainer else Color(0xFF202124))
                }
            }
        }
        // 动作：固定/复制（48dp，18dp glyph，onSurfaceVariant@78%）
    }
}
```

注意：当前 `UserGoalBubble` 是 320dp + `primaryContainer` + `bodyLarge`；改动是**纯视觉**，`detail.goal` 数据与 intent 不变。

### 6.5 Assistant turn（三段式）

```
Row(fillMaxWidth, verticalAlignment = Top) {
    AnimatedMcaAssistantMark(isGenerating, Modifier.padding(top = 1.dp))   // 38dp，生成中旋转环
    Spacer(10.dp)
    Column(Modifier.weight(1f), verticalArrangement = spacedBy(8.dp)) {
        // ① 运行状态行
        Row { Text("Agent 任务", bodySmall, onSurfaceVariant); Text(" · "); Text(statusLabel + runLabel) }
        // ② 执行活动组
        AgentToolActivityGroup(rows = detail.timeline, ...)
        // ③ 正文
        if (finalAnswer != null) SelectionContainer { Text(finalAnswer, bodyLarge, onSurface) }
        // ④ 产物入口（现有 AgentArtifactRow + 分组标题）
        // ⑤ 动作行（复制 / 分享 / 导出，右对齐 48dp 图标）
    }
}
```

- 三段之间 `8.dp`，段内 `4.dp`（与 Chat 的「统计 → 来源 → 操作」4dp 节奏一致）。
- 状态行禁止输出遥测矩阵：最多两段文本（状态 + 运行次数），`bodySmall`、`onSurfaceVariant`、2 行截断。
- 正文用 `SelectionContainer`；markdown 渲染在 API-1 落地后按 §7.3 处理，V1 保持纯文本，不做半套解析。

### 6.6 工具调用行（紧凑行 + 折叠组）

**组头（折叠时唯一可见行）**

```
Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { toggle() }) {
    Text("执行活动", labelMedium Medium, onSurfaceVariant)
    Spacer(6.dp)
    Text("· ${rows.size} 步", labelSmall, onSurfaceVariant)
    Spacer(weight 1f)
    if (running) CircularProgressIndicator(14.dp, strokeWidth = 2.dp)
    Icon(KeyboardArrowDown/Up, 18.dp, onSurfaceVariant,
         Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f })
}
```

**单行（展开后）**

```
Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = row.expandable, onClick = toggleRow)) {
        Icon(timelineIcon(row.kind), 18.dp, tint = toneTint(row.tone).copy(alpha = .8f))
        Spacer(10.dp)
        Text(row.title, labelMedium Medium, onSurface, maxLines 1, ellipsis, Modifier.weight(1f))
        if (row.repeatCount > 1) { Text("×${row.repeatCount}", labelSmall, onSurfaceVariant); Spacer(6.dp) }
        when {
            running  -> CircularProgressIndicator(14.dp, 2.dp, tint = primary)
            failed   -> Icon(Icons.Filled.Close, 16.dp, error)
            else     -> Icon(if (expanded) KeyboardArrowUp else KeyboardArrowDown, 18.dp, onSurfaceVariant)
        }
    }
    if (!expanded && row.summary != null)
        Text(row.summary, bodySmall, onSurfaceVariant, maxLines 2, ellipsis, Modifier.padding(start = 28.dp))
    if (expanded) {
        Surface(RoundedCornerShape(12.dp), surfaceContainerLow) {
            SelectionContainer {
                Column(Modifier.padding(10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    row.details.forEachIndexed { i, d ->
                        Text(d, bodySmall.copy(fontFamily = FontFamily.Monospace), onSurface.copy(alpha = .86f),
                             Modifier.padding(top = if (i == 0) 0.dp else 6.dp))
                    }
                }
            }
        }
    }
}
```

规则：
- **默认密度**：`> 6` 步或已终态 → 组折叠；运行中 → 展开最近 3 步，更早的折叠为「更早的 N 步」。折叠状态是页面本地状态（`rememberSaveable(taskId)`），不写入 `AgentWorkspaceUiState`。
- **单个执行中**：行尾进度环，组头同时显示进度；失败行 `error` 色 + 关闭图标，展开后详情全文可见、可长按复制。
- **禁止**：给每行加 16dp 圆角卡 / 阴影 / 卡片内再嵌卡片（这是当前 `AgentTimelineRowItem` 的形态，仅保留在轨迹 sheet 中作为审计视图）。
- 展开/收起用 `McaMotion.sizeChangePx`；图标旋转在 `graphicsLayer` 读写，不在组合期读动画值。

▸ 新增 `ui/AgentToolActivityRow.kt`（行 + 组头 + 图标映射，目标 ≤ 240 行）；复用现有 `timelineIcon` / `timelineTint`（从 `AgentTimelineRowItem.kt` 提升为 `internal` 共享函数，或在同文件改为可复用）。

### 6.7 输入卡（Composer）

```
Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    Surface(fillMaxWidth, RoundedCornerShape(36.dp),
            color = if (dark) surface else McaInputShell, shadowElevation = 10.dp) {
        Column(Modifier.padding(7.dp).navigationBarsPadding()) {
            // ① 状态行（可选）：AnimatedVisibility + sizeChangePx
            //    Text(状态文案, labelSmall, onSurfaceVariant)  — 如「模型正在准备工具…」
            // ② 附件条（V1 无附件契约 → 不渲染；见 §9 API-9）
            // ③ 主行
            Row(verticalAlignment = CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape)
                        .background(if (running) surfaceVariant else McaInputIconSurface)
                        .clickable(enabled = !running) { onOpenPlus() },
                    contentAlignment = Center) {
                    Icon(Icons.Outlined.Add, 24.dp, tint = if (running) placeholder else McaPrimaryBlue)
                }
                Spacer(10.dp)
                Surface(Modifier.weight(1f).heightIn(min = 40.dp), CircleShape, color = if (dark) surfaceVariant else McaInputField) {
                    Box(Modifier.fillMaxWidth().padding(horizontal 16.dp, vertical 10.dp)) {
                        if (draft.isEmpty()) Text(placeholder, 15.sp/20.sp, color = inputPlaceholder)
                        BasicTextField(draft, onDraftChange, maxLines = 4,
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = inputText),
                            cursorBrush = SolidColor(McaPrimaryBlue),
                            enabled = !running || allowTypingWhileRunning)
                    }
                }
                Spacer(10.dp)
                FloatingActionButton(size 40.dp, CircleShape, McaPrimaryBlue, elevation 0,
                    modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else .55f }) {
                    Crossfade(action) { when (it) { Send -> SendGlyph; Stop -> StopGlyph; Approve -> LockGlyph } }
                }
            }
        }
    }
    // ④ 卡下统计（可选，11sp onSurfaceVariant）：「第 N 次运行 · 工具 x/y · 00:12」
}
```

主按钮状态机（唯一真值，避免 `ComposerMode` 七态膨胀）：

| 条件 | 图标 | 点击 | 无障碍名 |
| --- | --- | --- | --- |
| `commandInFlight == CREATE_TASK` | 进度环（19dp, 2dp, white） | 无 | 正在创建任务 |
| `detail.canStop` | Stop | `onStop` | 停止任务 |
| `waitingForApproval` | Lock | `onOpenApproval` | 查看授权请求 |
| `waitingForUser && question != null` | Send | `onAnswerQuestion`（草稿非空） | 提交回答 |
| `draft` 非空且可执行（home: `models.canCreate`；detail: `canContinue`/`canStart`） | Send | `onCreateTask` / `onContinue` / `onStartTask` | 开始任务 / 继续 |
| 其它 | Send（禁用，alpha .55） | 无 | 请先输入目标 |

输入可用性：`create_task`/`stop` 期间 `BasicTextField.enabled = false`（与 MCA Chat 生成期间一致）；等待授权/等待回答时可输入（回答需要）。

占位符：`"描述目标，或输入要 Agent 继续做的事"`（不再承诺 `/`、`@` 解析，除非真的实现——`DSH_UI_PARITY_MATRIX.md` §6.3 已记录当前占位符说谎）。

▸ 新增 `ui/AgentChatComposer.kt`（目标 ≤ 300 行）；删除 `AgentHomeComposer.kt` 中的 composer 主体（`BrandMark` 若仍被顶栏使用则迁出保留）与 `AgentQuestionCard.kt` 中的 `AgentDetailComposer`。

### 6.8 权限提示（内联卡 + sheet）

**内联卡（会话流内，位置 = 待授权那一步之后）**

```
Surface(fillMaxWidth, RoundedCornerShape(16.dp), surfaceContainerLow,
        border = BorderStroke(1.dp, outlineVariant.copy(alpha = .55f))) {
    Column(Modifier.padding(14.dp), verticalArrangement = spacedBy(6.dp)) {
        Row { Icon(Lock, 16.dp, primary); Spacer(6.dp)
              Text("工具授权请求", labelMedium, primary); Spacer(weight 1f)
              Text(approval.statusLabel, labelSmall, onSurfaceVariant) }
        Text(approval.toolAction, titleSmall/bodyLarge, onSurface)
        approval.writeTargets.firstOrNull()?.let { Text("写入：$it", labelSmall, onSurfaceVariant, maxLines 1, ellipsis) }
        approval.networkTargets.firstOrNull()?.let { Text("网络：$it", labelSmall, onSurfaceVariant, maxLines 1, ellipsis) }
        Text(approval.expiresText, labelSmall, onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton("拒绝", Modifier.heightIn(min = 48.dp), enabled = !inFlight)
            TextButton("查看详情", Modifier.heightIn(min = 48.dp), onClick = onOpenApproval)
            Button("允许一次", Modifier.heightIn(min = 48.dp), enabled = !inFlight)
        }
    }
}
```

**Sheet**：保留现有 `AgentApprovalSheet`（92% 高、28dp 顶圆角、拒绝/允许一次左右等分、48dp 高、默认焦点不在允许上），补充 third-party 的信息分组顺序：

1. 头部：42dp `primaryContainer` 圆角砖 + Lock 20dp；标题「工具授权请求」`labelMedium primary`；`toolAction` `titleMedium`；`"${statusLabel} · ${expiresText}"` `labelSmall`。
2. 分组卡（14dp 圆角、`surfaceContainerLow`、1dp 边框）：读取目标 / 写入目标 / 网络目标 / 参数摘要（等宽）/ 覆盖变更 / 冻结绑定。
3. 底部固定操作区（不随内容滚动）：拒绝、允许一次；`稍后处理` 文本按钮。

**「始终允许」**：third-party 有，MCA 契约只有 `ALLOW_ONCE` / `DENY`。V1 **不渲染**该入口；若产品要求，须先扩 `AgentApprovalDecision`（见 §9 API-10），且 UI 必须说明作用域（本会话 / 本任务）与撤销路径。

**弹出策略**：`waitingForApproval && approval != null && dismissedApprovalId != approval.approvalId` 时自动打开 sheet 一次；用户「稍后处理」后只保留内联卡，主按钮变 Lock，不得再次自动打断。

**草稿输入**：`AgentApprovalInlineCard` 改为独立文件（从 `AgentTaskDetailScreen.kt` 内联函数抽出），目标 ≤ 160 行。

### 6.9 提问卡

```
Surface(fillMaxWidth, RoundedCornerShape(16.dp), surfaceContainerLow, border 1dp outlineVariant@.55) {
    Column(Modifier.padding(14.dp)) {
        Text("模型需要你的回答", labelMedium, primary)
        Spacer(4.dp); Text(question.text, bodyLarge)
        question.options.forEach { option ->
            QuestionOptionRow(option, selected = answerDraft == option,
                              enabled = !busy, onClick = { onSelectQuestionOption(option) })   // ← 点击即提交
        }
        Text("也可以直接在下方输入回答", labelSmall, onSurfaceVariant)
    }
}
```

- 选项行：`RoundedCornerShape(14.dp)`、`heightIn(min = 48.dp)`、选中 `primaryContainer` + 1dp `primary@45%`、尾部 18dp 勾。
- **行为变更**：选项点击 = 立即 `answerQuestion(questionId, option)`（新增页面 action `onSelectQuestionOption(String)`，VM 内部复用现有 `answerQuestion` 命令），不再「只写草稿」。这解决 `DSH_UI_PARITY_MATRIX.md` 记录的选择/提交分离。
- 自由文本仍走 composer 的 ANSWER 态。

### 6.10 空态 / 错误 / 状态条

| 状态 | 形态 |
| --- | --- |
| 新任务（无 detail） | `EmptyChatPanel` 规格：`Surface(surface, RoundedCornerShape(8.dp), tonalElevation 1.dp)` + `Column(padding 18.dp, spacedBy 10.dp)`；标题 `"Agent"` `headlineSmall Bold`；说明 `"描述目标，Agent 会在本机执行并写入产物。"` `bodyMedium onSurfaceVariant`；无模型时追加两个按钮：「选择推荐模型」`Button`、「导入已有模型」`OutlinedButton`、「去聊天页加载」`TextButton` |
| 载入中 | 列表内单行 `"正在载入任务…"` `bodySmall onSurfaceVariant`，不铺 skeleton |
| 无模型 / 协议不支持 / 离线不兼容 | 现有 `AgentInlineBanner`（`isError` 区分），列表顶部 |
| 运行失败 | banner + assistant turn 内 `[重试载入]` `TextButton`（`onRetryDetail`） |
| 归档只读 | 列表顶部 `"这是导入的归档任务，只读"`；底部以一行 48dp 说明替代 composer（与 Chat 的 `conversationReadOnly` 处理一致） |
| 通知 / 包操作 | 现有 `AgentInlineBanner` / `AgentPackageStatusBanner` |

### 6.11 抽屉与轨迹

- **抽屉**：保留 `AgentHomeDrawer` 的内容与 testTag（`agent.drawer`、`agent.drawer.new` 等）；容器向 Chat 靠拢（`ModalDrawerSheet(fillMaxSize, containerColor = background)` + `padding(horizontal 20, vertical 18)` + 边缘滑动返回）列为 P2，因为它不影响「会话面看起来像聊天」。
- **轨迹 sheet（新）**：`ModalBottomSheet(92% 高, 28dp 顶圆角)` + `LazyColumn`，直接渲染现有 `AgentTimelineRowItem`（卡片 + 展开全部详情）。这是原「轨迹」tab 的能力保留入口，从顶栏菜单进入；`agent.detail` 标签迁到 sheet 根（或保留兼容别名）。

▸ 新增 `ui/AgentTrajectorySheet.kt`（目标 ≤ 200 行）；`AgentTaskDetailScreen.kt` 退化为会话内容装配（≤ 300 行）。

---

## 7. 可复用组件清单

### 7.1 直接复用（零风险，`import` 即可）

| 组件 | 位置 | 用途 |
| --- | --- | --- |
| `McaTheme` | `core:designsystem` `McaTheme.kt` | 全部颜色角色 |
| `McaIconButton` | `core:designsystem` `McaButtons.kt` | 顶栏、动作行、48dp 触摸目标、强制无障碍名 |
| `mcaPressScale` / `mcaEnterReveal` / `McaMotion` | `core:designsystem` `McaMotion.kt` | 按压反馈、列表入场、`sizeChangePx` 折叠动画 |
| `McaPageMotion` / `BackMotion` / `SmoothPage` | `core:designsystem` | tab 页进出、返回动效（宿主已用） |
| `AgentInlineBanner` / `AgentErrorBanner` / `AgentStatusChip` / `AgentTag` / `AgentSectionHeader` / `AgentPrimaryButton` / `AgentMetaText` | `feature:agent-workspace` `AgentWorkspaceComponents.kt` | 状态、错误、标签 |
| `AgentApprovalSheet` | `.../AgentApprovalSheet.kt` | 审批主表面（仅补信息分组） |
| `AgentQuestionCard` 的卡体 / `QuestionOptionRow` | `.../AgentQuestionCard.kt` | 提问卡（去掉「只写草稿」提示） |
| `AgentArtifactRow` / `AgentArtifactInfoDialog` | `.../AgentArtifactsSection.kt` | 产物入口 |
| `AgentTimelineRowItem`（+ `timelineIcon` / `timelineTint`） | `.../AgentTimelineRowItem.kt` | **轨迹 sheet 的审计视图**；图标映射给内联行复用 |
| `AgentModelPickerSheet` | `.../AgentModelPickerSheet.kt` | 模型选择（顶栏胶囊下拉可打开它） |
| `AgentHomeDrawer` | `.../AgentHomeDrawer.kt` | 任务历史 |
| `AgentPackageStatusBanner` / `AgentDeleteTaskDialog` | `.../AgentPackageUi.kt` | 任务包状态与删除 |

### 7.2 只能「规格复用」（不能 import，需按 §3 常量重写或提升）

`feature:agent-workspace` 的 `build.gradle.kts` 注释明确：*"It depends on the contract and the design system only"*，且 `MCA_PROJECT_HARD_LOCK.md` §4.3 禁止 feature 依赖其它 feature 的内部实现。因此下表只能二选一：**提升**（提升后再复用）或 **照规格重写**（本模块自带常量）。

| Chat 组件 | 当前可见性 | 建议 |
| --- | --- | --- |
| `ChatStatusBar` | `internal`（feature 私有） | 不提升；Agent 顶栏内容不同（模型胶囊 + 运行状态），照 §6.2 规格重写 |
| `ChatMessageListSection` 度量 | `internal` | 抽 `ChatSurfaceTokens` 到 `core:designsystem`（纯 `Dp` 常量对象，零行为）后两边共用；Chat 侧迁移单独做视觉等值切片 |
| `UserMessageBubble` 形状/颜色 | `private`/`internal` | 同 `ChatSurfaceTokens`（shape/颜色字面量）；气泡本体照 §6.4 重写 |
| `ChatInputBar` | `internal` | 外壳几何进 `ChatSurfaceTokens`；Agent 输入卡因工具栏不同必须单独实现 |
| `EmptyChatPanel` | `internal` | 抽 `McaEmptyConversationCard(title, description, actions)` 到 designsystem（约 60 行，通用语义） |
| `CompactInputActionMenu`（锚定浮层） | `internal` | 抽 `McaAnchoredActionMenu`（238dp / 30dp 圆角 / 48dp 行 / 34dp 图标砖）或按规格重写 |
| `ReasoningPanel` / `PendingReasoningPanel` | `internal` | **暂不提升**：Agent 契约没有推理文本字段（见 §9 API-12），提升后无输入可用 |
| markdown 渲染（`PagedAssistantRichText` 等） | `internal`，依赖 jsoup | **暂不提升**：Agent 正文当前不是模型输出（API-1）；等有真实正文再决定新模块 `:core:chat-render` 或设计系统化 |
| `AssistantActionRow` | `internal` | 本模块重写（3 个 `McaIconButton`，约 60 行），不必提升 |
| `McaLogoMark` / `AnimatedMcaAssistantMark` | `internal`（`McaBrandMarks.kt`，396 行） | **建议提升**到 `core:designsystem`（品牌与助手头像两个 feature 都要用）；若不提升，Agent 用现有 `BrandMark` + 自定义 24dp mark |
| `ChatHistoryDrawer` | `internal` | 不提升；Agent 抽屉内容不同 |
| `ChatScrollCoordinator`（跟随/脱离状态机） | `internal`，绑定 `ChatUiState` | 不提升；Agent 用 `canScrollForward` 的简化跟随即可（Agent 不是逐 token 流式） |

### 7.3 不合并 / 明确重写

- **两种工具行密度共存**：会话内「紧凑行」与轨迹 sheet「审计卡」读起来是两种用途（读进度 vs 查证据），不强行合并；它们共享同一 `AgentTimelineRow` 投影与图标映射，不产生第二份数据模型。
- **不复制 third-party 视觉**：半透明玻璃、24dp 圆角权限卡、85% 宽遮罩卡、上下文占用环都不进入 MCA。它们的**语义**（进度、授权、参数查看）由 MCA 既有表面承载。
- **不引入第三方库**、不新增颜色字面量（除已在 `ChatColorTokens.kt` 存在的输入区 token）。

### 7.4 模块边界与文件预算

| 文件 | 动作 | 目标行数（硬锁 §8：Screen ≤ 500–600，Route ≤ 150） |
| --- | --- | --- |
| `ui/AgentChatScreen.kt` | 新增 | ≤ 320 |
| `ui/AgentChatTopBar.kt` | 新增 | ≤ 180 |
| `ui/AgentChatMessageList.kt` | 新增 | ≤ 260 |
| `ui/AgentTurnBlock.kt` | 新增 | ≤ 280 |
| `ui/AgentToolActivityRow.kt` | 新增 | ≤ 240 |
| `ui/AgentChatComposer.kt` | 新增 | ≤ 300 |
| `ui/AgentApprovalInlineCard.kt` | 新增（抽出） | ≤ 160 |
| `ui/AgentTrajectorySheet.kt` | 新增 | ≤ 200 |
| `ui/AgentChatEmptyState.kt` | 新增（或并入 list） | ≤ 120 |
| `ui/AgentWorkspaceContent.kt` | 改（HOME/DETAIL 统一分发） | ≤ 120 |
| `ui/AgentTaskDetailScreen.kt` | 改（退化为会话装配，去 tab） | ≤ 300 |
| `ui/AgentWorkspaceHomeScreen.kt` | 改（只保留空态/任务列表抽屉入口，去 composer 重复） | ≤ 200 |
| `ui/AgentHomeComposer.kt` | 删 composer 主体；保留 `BrandMark`（若顶栏用） | — |
| `ui/AgentQuestionCard.kt` | 删 `AgentDetailComposer`；保留卡体 | ≤ 220 |
| `core/designsystem/chat/ChatSurfaceTokens.kt` | 新增（若采纳提升） | ≤ 70 |
| `core/designsystem/McaBrandMarks.kt` 或 `McaAssistantMark.kt` | 提升（若采纳） | ≤ 120 |

约束：
- 不新增 Module（除非 markdown 复用立项）；不新增 feature→feature 依赖；不新增全局状态字段（页面本地状态用 `rememberSaveable`）。
- `AgentWorkspaceUiState` 只增「纯 UI 投影」字段时需评审；本方案**不要求**新增 state 字段（展开态、滚动态都在页面内）。

---

## 8. 行为合同

| 场景 | 规则 |
| --- | --- |
| 进入页面 | 无 detail 且无任务 → 空态卡；有任务但未选 → 空态卡 + 抽屉提示；正在运行 → 直接落到该任务的会话面 |
| 发送 | 先 `clearFocus` + 隐藏键盘，再提交；提交后 `animateScrollToItem(last)`；禁止重复点击（`commandInFlight` 期间主按钮禁用） |
| 运行中 | 主按钮 = 停止；列表跟随仅在贴底时；输入框禁用（作答/审批场景除外） |
| 等待授权 | 内联卡常驻；sheet 自动弹一次；「稍后处理」后不再自动弹；主按钮 = Lock |
| 等待回答 | 提问卡常驻；选项点击即提交；自由文本走 composer；提交后主按钮回到 Send/Stop |
| 取消/停止 | 只发 `onStop`，不弹二次确认（与 Chat 停止一致）；停止后保留已产生活动行 |
| 返回键 | `BackHandler`：sheet 打开 → 关 sheet；否则 `viewModel.handleBack()`（现有），最后 `onBack`。返回**不停止运行** |
| 滚动 | 用户上滑后不强制跟随；「回到最新」胶囊出现；新 item 到达且贴底时自动跟随 |
| IME | 根 `imePadding()`；composer `navigationBarsPadding()`；键盘弹出时列表不被遮挡（composerOverlap 实测） |
| 恢复 | `home/detail` 滚动锚点继续上报；草稿、展开行集合由 ViewModel 持有的保持；页面本地展开态在进程死亡后允许重置（记录为已知限制） |
| 无障碍 | 所有图标按钮有 `contentDescription`；状态变化 `liveRegion = Polite`（进度/授权/错误）；触摸目标 ≥ 48dp；大字体 1.5×/2.0× 不裁切模型名与状态副标题（参照 Chat 的 `compactForLargeText` 策略） |
| 只读归档 | 隐藏 composer，底部显示说明；动作行只保留复制/分享/导出 |

---

## 9. API 与契约缺口

> 原则：能在现有投影里算出来的不新增 API；必须新增的给最小形态。编号与 `DSH_UI_PARITY_MATRIX.md` §5 不冲突（沿用其 API-1/2/3，新增 API-9+）。

| ID | 缺口 | 最小形态 | 影响的本文条目 | 优先级 |
| --- | --- | --- | --- | --- |
| API-1（沿用） | assistant 正文不是模型输出 | 见姊妹文档 | §6.5 正文、§4 内容层 | P0（阻断） |
| API-2（沿用） | 任务显示名 | `AgentTask.displayTitle` + rename 命令 | §6.2 顶栏标题（可选） | P1 |
| API-9 | 附件契约 | `AgentAttachment(ref, kind, size)` + `CreateAgentTask(goal, attachments)`；未落地前 composer 不显示附件 chip | §6.7 附件槽 | P2 |
| API-10 | 审批作用域 | `AgentApprovalDecision.ALLOW_SESSION`（或 `ALLOW_ALWAYS` + 策略版本绑定） | §6.8「始终允许」 | P2 |
| API-11 | 选项即提交（页面级） | `AgentWorkspaceActions.onSelectQuestionOption(String)`；VM 内调用现有 `answerQuestion` | §6.9 | P1 |
| API-12 | 推理摘要（可选） | `AgentRunSnapshot.reasoningSummary: String?` | 若启用 `ReasoningPanel` 复用 | P3 |
| API-13（页面级） | 轨迹入口 | `actions.onOpenTrajectory` / `onDismissTrajectory`（页面本地 sheet，可纯 UI） | §6.11 | P1（纯 UI 可先做） |
| API-14（页面级） | 组展开默认策略 | 页面本地 `rememberSaveable`，无需契约 | §6.6 | — |

已确认**不需要**新增契约的部分：工具行数据（`AgentTimelineRow` 已含 kind/tone/title/summary/time/details/repeatCount/expandable/expanded）、审批信息（`AgentApprovalUi` 已含读写/网络/参数/覆盖/有效期/哈希）、运行统计（`detail.runLabel` / `statusDetail`）、产物（`AgentArtifactUi`）。

---

## 10. 实施切片（6 片，可独立验证/回退）

| # | 切片 | 内容 | 验证 | 回退点 |
| --- | --- | --- | --- | --- |
| S1 | 会话骨架 | 新增 `AgentChatScreen` + `AgentChatMessageList`；HOME/DETAIL 共用；删除 tab 行；抽屉保留 | Compose 测试：空态/会话两种状态、列表顺序、testTag 稳定 | 单文件删除 + `AgentWorkspaceContent` 分发回退 |
| S2 | 顶栏 | `AgentChatTopBar`：48dp 菜单、模型胶囊（模型 + 运行副标题 + 本地/联网）、新建、Agent 菜单（模型/联网/Skills/MCP/轨迹/导出/删除） | 48dp 触摸目标、contentDescription、长模型名 1.5×/2.0× 字体 ellipsis | 恢复旧 `DetailTopBar`/`AgentHomeTopBar` |
| S3 | 气泡与 turn | 用户目标气泡改 280dp/#EEF4FF/17-13；assistant 三段式；动作行 | 对照 Chat 同宽渲染；长目标换行；SelectionContainer 可复制 | 保留旧 `UserGoalBubble` 分支 |
| S4 | 工具行 | 紧凑行 + 可折叠组 + 展开详情 + 状态图标；轨迹 sheet 承接旧卡视图 | 6/20/200 行三档密度；运行中进度；失败错误色；展开/折叠动画 | 旧 `AgentTimelineRowItem` 仍可用 |
| S5 | 输入卡 | `AgentChatComposer`（36dp 壳 + 40dp `+` + 胶囊 + 40dp 主键状态机）；plus 改锚定浮层 | 发送/停止/授权/回答四态；禁用 alpha；IME 不遮挡；返回键先关浮层 | 旧 `AgentHomeComposer`/`AgentDetailComposer` |
| S6 | 审批与提问 | 内联审批卡抽文件；sheet 补信息分组；审批弹出策略；选项即提交 | 拒绝/允许一次；稍后处理不重复弹；选项提交后状态流转 | 旧内联卡与旧选项行为 |

依赖顺序：S1 → S2 → S3 → S5 →（S4 与 S6 可并行，S6 依赖 S1）。每个切片独立可渲染、可回退；不得把「去 tab」「换 composer」「改审批」混在一个提交里。

### 验证计划（按硬锁 §12）

| 类别 | 内容 | 状态 |
| --- | --- | --- |
| 纯 UI 结构（本次提案） | 链接、规则一致性、diff、敏感信息 | 本文件：自检 |
| Compose 行为测试 | `AgentWorkspaceRouteTest` 现有 + 新增 `AgentChatSurfaceTest`（空态/会话/审批/问题四态）、`AgentComposerStateTest`（六态按钮映射） | NOT_RUN（本次只读） |
| 视觉 | 两轮真机渲染：浅/深色 × 1.0/1.5/2.0 字体 × 长中文/英文/代码；与 Chat 同宽截图对照几何（token 等值，不做像素 parity） | NOT_RUN |
| 架构 | `python tools/architecture/check_architecture_rules.py`；`.\scripts\verify-code.ps1 -FeatureTests` | NOT_RUN |
| 无障碍 | TalkBack 走查：顶栏、工具行「已展开/已折叠」`stateDescription`、审批按钮顺序、发送禁用态播报 | NOT_RUN |
| 回归 | Chat 页不受影响（本方案不改 `feature:chat`；`ChatSurfaceTokens` 提升后 Chat 迁移另立等值切片） | NOT_RUN |

---

## 11. 风险与未决

1. **内容层 P0 未解**：没有真实 assistant 文本时，无论排版多像 Chat，会话都读不出「回答」。视觉改造必须先声明此依赖，避免用「页面已改版」掩盖 API-1。
2. **并行改写冲突**：`feature/agent-workspace/ui/*` 正在被并行修改（19:15–19:23 多次落盘）。本提案的 S1–S6 需要在合并后的基线上重排；`AgentWorkspaceHomeScreen`/`AgentHomeComposer` 可能已被部分实现为本文 §6 的形态，届时以「差距」而非「从零」执行。
3. **去 tab 的能力保留**：轨迹 tab 是现有可发现入口，迁到顶栏菜单 + sheet 属于导航变更，必须在验收矩阵中登记为「入口迁移、行为保留」，不能算功能删减。
4. **「像 Chat」的边界**：Chat 的顶栏是「菜单 + 模型胶囊 + 新建 + 品牌」，Agent 需要模型/状态/联网/新任务，四者挤在 48dp 行内需要 1.5×/2.0× 字体回归；极端字体下允许副标题降级为单状态 pill（Chat 已有 `compactForLargeText` 先例）。
5. **权限「始终允许」**：不加契约就不加按钮；加了必须同时给撤销入口，否则违反最小授权原则。
6. **提升组件的迁移成本**：`McaLogoMark`/`AnimatedMcaAssistantMark` 提升涉及 Chat 侧引用改写，必须单独视觉等值切片；`ChatSurfaceTokens` 只承载常量，风险最低，可作为第一个提升项。
7. **未决问题**：a) 空态是否保留「选择推荐模型/导入已有模型」两个按钮（Chat 空态有）；b) 运行中是否允许继续输入（Chat 禁止；third-party 允许并排队）；c) 轨迹 sheet 是否需要保留 `agent.detail` testTag 兼容别名。

---

## 12. 交付证据

- **做了什么**：只读比对 third-party `ui/features/chat/**` 与 MCA `feature/chat`、`feature/agent-workspace`、`core/designsystem`，产出本映射与布局规格；未改代码、未构建、未渲染。
- **owner / 状态**：本文件仅描述 UI 投影与页面本地状态；不新增业务 owner、不新增 `AgentWorkspaceUiState` 写入者、不引入 feature→feature 依赖。运行、审批、产物仍由现有 `AgentWorkspaceQueries/Commands` 与 ViewModel 持有。
- **功能/数据/协议影响**：无（提案）。潜在行为变更点已在 §8 逐条登记（tab → sheet 的入口迁移、选项即提交、审批自动弹出策略）。
- **未验证**：Compose 测试、真机渲染、大字体、TalkBack、IME、架构脚本、`verify-code.ps1` 全部 `NOT_RUN`。
- **临时路径/例外**：无。无新增例外。
- **回退**：本文件可整体删除；若后续按 S1–S6 实施，每片独立可回退（见 §10）。

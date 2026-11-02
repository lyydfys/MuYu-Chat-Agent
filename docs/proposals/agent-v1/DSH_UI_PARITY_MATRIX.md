# DSH 与 MCA Agent 工作区 UI 对齐矩阵（v1 草案）

> 作用域：DSH Android 截图（3 张，见 §1.1）↔ `feature/agent-workspace` 当前实现（HOME/DETAIL/SKILLS/MCP + drawer + plus + approval + question）。
> 本文件只做证据化对比与缺口排序；**不修改任何代码，不运行 Gradle，不声称真机渲染结果**。
> 基线：分支 `codex/enterprise-refactor`，HEAD `2368c72`（2026-10-19 18:23 +08:00）；工作区有 59 个已修改文件，且 `core/agent-contract/`、`core/agent-runtime/`、`feature/agent-workspace/` 均为 **untracked**（无已提交 revision 可引用）。本文件是本次唯一写入。
> 日期：2026-10-19。状态：`PROPOSAL / NOT_IMPLEMENTED`。
> 姊妹文档：`DSH_KERNEL_PARITY.md` 负责内核/持久会话/上下文压缩/计划/子代理的**结构级**对标；本文件只做**屏幕级 UI/UX**，两者的结论不互相替代。

## 0. 一页结论

1. **内容层是 P0，不是视觉细节**：会话里唯一的 assistant 文本来自 `RUN_TERMINAL` 事件的 `summary`，而生产代码把它写成枚举名 `SUCCEEDED/FAILED/CANCELLED`；UI 侧又把该 summary 当回答正文渲染（§6.1）。因此“最终回答”在真实运行中显示的是状态 token，不是模型输出；预览和单测用的是伪造的自然语言 summary，所以测试全绿也发现不了。
2. **DSH 的会话面在 MCA 被拆成两个屏幕**：DSH 进入即“有标题 + 对话/轨迹 + 消息列表 + 贴底输入卡”的同一会话面，抽屉一键可达；MCA 的 HOME 是品牌落地页，DETAIL 才是会话，但 DETAIL 换成“返回箭头 + 居中标题 + 下拉菜单”，且 DETAIL 内没有抽屉。两套 chrome 使同一功能读起来像两个产品（§3 Home/Detail/Drawer）。
3. **形态层的四处高影响差异**：输入卡高度约 2 倍（MCA HOME ≈192dp vs DSH ≈96dp）、plus 菜单从“锚定浮层 + 命令面板”变成“ModalBottomSheet + 设置项”、抽屉宽度 76% vs 69.3% 且行高 56dp vs ≈40dp、消息动作 3×48dp 按钮 vs 5×≈12dp 图标（§4）。
4. **不要再用“DSH 1:1”描述现状**：`docs/adr/0002-agent-v1-runtime-and-delivery.md:160` 与 `MCA_AGENT_V1_HANDOFF.md:21` 的“DSH 1:1 / 按 DSH 模型复刻”只覆盖了 composer 外形和抽屉分区，会话结构、标题、plus 菜单、度量均不成立（§6.2）。

## 1. 证据与方法

### 1.1 截图

| 编号 | 文件（attachment object） | 可见内容 | 可直接量取的事实（1200×2608 px） |
| --- | --- | --- | --- |
| S1 | `c5/c5171e6394f01dd3391c4df195ca50ec546b7bb0f2099556ddad07e3592544f0` | 会话页：顶栏（菜单／标题／面板图标）、对话·轨迹 tab、用户气泡 + 时间/复制、状态行 + 分隔线、assistant 正文（段落 + 列表）、5 个动作图标 + 时间、贴底输入卡、卡下统计行 | 标题左起 x≈182；tab 左起 x≈180、指示器 82×8；正文左起 x≈96；气泡填充 `#EFF3FF`、文字≈`#0C101C`、高≈128；状态行 `#858585`；动作图标 + 时间文本共 6 簇 x=99…723（5 个图标 glyph 33–40px，pitch≈110）；输入卡 x=43…1151、y=2162…2458（高 296）；发送 105px；统计行 y=2483…2521 |
| S2 | `50/502c18ad4c647a67af7545e2230934230d50eb0cd9f876b313590acd1f4892d8` | 抽屉：wordmark + HARNESS 徽标 + 收起图标、新会话按钮、插件/自动化任务、工作区 + 搜索/筛选/新建文件夹、空态、底部头像 + 用户名；右侧露出底层新会话面的虚线输入框轮廓与“预览版” pill | 抽屉右缘 x≈832（69.3%）；新会话按钮 y=354…466（高 112）；导航文字 y=536…579、y=658…697（pitch≈122）；空态图标 y≈1150…1188、文案 y≈1241…1277；底栏 y≈2416…2487；抽屉底色≈`#F9FAFC` |
| S3 | `64/6425a54629b3c4dc3f20593a584f9c9008bd7d9e4e095bba058f9ef6a3999ab3` | plus 面板：添加（文件/目标/计划/反馈）、指令（压缩/权限/模型）、每行图标 + 中文 + 拉丁 token + 右侧灰色说明；下方仍露出输入卡 | 面板 x=46…1146、y≈1327…2163（高 836，边距 46）；行文字带 y=1359…2108（7 行 pitch≈102）；首行图标 x=94…115、中文 x=146…210、token x=243…284；说明文字 `#858585` |

### 1.2 源码证据（本文件引用的关键事实）

| 事实 | 位置 |
| --- | --- |
| HOME 顶栏只有菜单；80dp 空档 + 46dp 品牌 + “预览版”；20dp 内容边距；输入卡是 LazyColumn 的 item | `feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentWorkspaceHomeScreen.kt:101-147` |
| HOME 输入卡：28dp 圆角、min 112dp 输入、+ / 附件 / 盾牌 / 模型 / 48dp 发送；附件按钮=导入任务包 | `.../ui/AgentHomeComposer.kt:212-315` |
| 抽屉 76%（≤340dp）、52dp 新任务按钮（点击清空 draft）、56dp 可展开行 + 48dp 子行、无搜索/筛选/新建、底部两行说明 | `.../ui/AgentHomeDrawer.kt:80-146, 366-386, 202-223` |
| DETAIL 返回箭头 + 居中 titleMedium + `ViewSidebar` 图标开 Dropdown（导出/停止/删除） | `.../ui/AgentTaskDetailScreen.kt:171-244` |
| DETAIL tab：28dp inset、30dp gap、40×2dp 固定指示器、labelLarge 14sp | `.../ui/AgentTaskDetailScreen.kt:246-296` |
| 气泡 320dp 上限、20/20/20/6dp、`primaryContainer`/`onPrimaryContainer`；无消息时间/复制 | `.../ui/AgentTaskDetailScreen.kt:385-410` |
| 状态行 bodySmall 12sp + “第 N 次运行 · 模型 x/y · 工具 x/y · 时长” | `.../ui/AgentTaskDetailScreen.kt:412-443`；`AgentWorkspaceProjection.kt:269-280` |
| 回答是单个 `Text(bodyLarge)`，无 markdown 渲染 | `.../ui/AgentTaskDetailScreen.kt:328-352` |
| 动作行 3×48dp 按钮（22dp glyph） | `.../ui/AgentTaskDetailScreen.kt:511-568` |
| DETAIL composer：12dp 边距、28dp 圆角、52dp 输入、+ 绑定 `onBackToHome`、模型标签居中 + 84dp 左右 padding、44dp 发送 | `.../ui/AgentQuestionCard.kt:187-292, 346-501` |
| 问题卡选项只写入草稿，卡内无提交；文案“在下方输入回答并发送” | `.../ui/AgentQuestionCard.kt:69-118` |
| plus = ModalBottomSheet（skipPartiallyExpanded）；5 行；无图标/无拉丁 token/无说明；“任务历史”打开抽屉 | `.../ui/AgentHomePlusSheet.kt:38-73, 86-118` |
| 审批 = 内联卡 + 92% 高 ModalBottomSheet；仅 ALLOW_ONCE / DENY | `.../ui/AgentApprovalSheet.kt:57-141`；`core/agent-contract/.../AgentInvocationTypes.kt:97` |
| 任务行 `displayTitle` 存在 Room，但 `AgentTask` 契约没有该字段 | `app/.../agent/data/export/AgentTaskPackageModel.kt:217-224`；`core/agent-contract/.../AgentTaskTypes.kt:31-52` |
| 只观察 `task.lastRunId` 一个 run，因此没有多轮历史 | `.../AgentWorkspaceViewModel.kt:207-232` |
| 导出/导入/删除后 `retryTasks()` 重置第 1 页 | `.../AgentWorkspaceViewModel.kt:465-471`；`AgentWorkspacePackageCoordinator` 的 `onTasksChanged` |
| 生产代码把 `RUN_TERMINAL.summary` 写成 `display.name` | `core/agent-runtime/.../AgentOwnerExecution.kt:395-414` |
| UI 把该 summary 当回答正文 | `AgentTimelineProjection.kt:67-75`；`AgentWorkspaceProjection.kt:457-461` |
| 预览与单测伪造自然语言 terminal summary | `AgentWorkspacePreviews.kt:139`；`AgentWorkspaceProjectionTest.kt:269-287` |
| 占位符承诺 `/` 与 `@`，全模块无解析 | `AgentHomeComposer.kt:232`；grep `startsWith("/")`／`@` 无匹配 |
| `AgentWorkspaceQueries` 只有 tasks/run/events 三个观察口 | `core/agent-contract/.../AgentWorkspacePorts.kt:9-15` |
| 决策枚举只有两种 | `core/agent-contract/.../AgentInvocationTypes.kt:97` |

### 1.3 度量口径

- DSH 截图为 1200×2608 px，按参考宽 390dp 折算 **≈3.077 px/dp**；下文 dp 均为该折算值，**±2dp**，不声称是 DSH 的真实 dp token。
- 颜色取截图 sRGB 众数；文字色取该区域最暗像素簇（含抗锯齿），只用于判断深浅与色相，不作为品牌色真值。
- MCA 侧数值一律取自源码 dp/sp 字面量，不靠截图猜测。
- 字体：`McaTheme` 未提供自定义 `Typography`（`core/designsystem/.../McaTheme.kt:158-161`），Agent 页实际使用 Material3 默认 Roboto/系统中文字体回退；DSH 字体族无法从截图确认。

### 1.4 证据缺口（必须先声明）

- DSH **审批、问题、任务操作菜单、插件页、自动化任务页、错误态、暗色、平板、大字体、IME、TalkBack** 均未出现在三张截图中；相应行只能评估 MCA 自身一致性，不能判定 parity。
- S2 底层的新会话面只露出虚线输入框轮廓与发送按钮，**不足以判定 DSH 的落地页布局**；本文件不据此要求 MCA 删除或保留 hero，只指出“hero 无 DSH 证据且占用首屏”。
- 本文件没有真机渲染、没有 Compose Preview 截图、没有暗色对比；所有“视觉差距”均为静态源码 + 截图度量，真机两轮渲染仍是 `NOT_RUN`。

## 2. 判定口径

- **差距**：`exact`（结构与度量均对得上）／`partial`（有对应能力，形态或度量明显不同）／`missing`（DSH 有、MCA 无，或 MCA 承诺了但无实现）。
- **优先级**：`P0` 阻断核心会话内容或显示错误内容；`P1` 首屏读起来是另一个产品/导航或语义说谎；`P2` 组件级形态与一致性；`P3` 需要品牌授权或属于 V1 外的新能力。
- **需要的 API**：`EXISTING` = 现有契约足够，纯 UI 改动；`API-n` = 见 §5 的契约变更。不得用“先放个入口”绕过——本模块现有的“每个控件都接真实动作”规则应继续保留。

## 3. 主矩阵

| 区域 | DSH 行为 (DSH behavior) | MCA 现状 (MCA current behavior) | 差距 (exact / partial / missing) | 需要的 API (required API) | 优先级 |
| --- | --- | --- | --- | --- | --- |
| **Home** | ① 会话优先：进入即有会话标题、对话/轨迹、消息列表与贴底输入卡（S1）。② 顶栏 = 菜单 + 会话名（左对齐 x≈59dp，≈14–15sp）+ 面板图标；会话态不显示品牌 hero。③ 正文/状态/输入共用 ≈31dp 内容列。④ 新会话态布局未在截图中出现（S2 只露出虚线输入框）。⑤ 抽屉在会话页一键可达。 | ① HOME 是独立落地页：56dp 顶栏只有菜单，无标题/tab/消息。② 80dp 空档 + 46dp 品牌（合计 ≈126dp 品牌区）+ 24sp “探索未至之境” + 11sp “预览版” + 两条 56dp 选择器；20dp 边距。③ 输入卡是 LazyColumn 的一个 item（`imePadding` 作用于列表），不是贴底常驻；总高 ≈192dp。④ 进入任务后 DETAIL 换另一套 chrome，抽屉消失。⑤ `home.loading/empty/errorMessage` 在 HOME 完全不渲染。 | ①missing ②partial（hero 无 DSH 证据但占 ≈126dp）③partial ④missing ⑤missing | API-1、API-2；其余 EXISTING | P1 |
| **Drawer** | ① 宽 832/1200 = 69.3%（≈270dp@390dp），右缘阴影，会话从右侧露出。② 顶部 wordmark + HARNESS 反白徽标，右上为“收起面板”同款图标，无关闭 X。③ “新会话”：全宽、≈36dp 高、1dp 边框、≈12dp 圆角、居中加号。④ 两行顶层导航（插件／自动化任务），pitch ≈40dp，无 chevron，图标 ≈20dp。⑤ “工作区”+ 搜索／筛选／新建文件夹三图标。⑥ 空态 ≈40dp 线框气泡 + 单行“暂无会话”（`#868789`）。⑦ 底部头像 + 用户名，无分隔线。 | ① 76%（≤340dp）= 296dp@390dp。② 34dp mark + “MCA”22sp + AGENT 徽标 + 关闭 X。③ “新任务”：52dp 高、16dp 圆角、16dp 边距；点击只清空 draft，不新建会话。④ 两行**可展开**导航（56dp、22dp 图标、右 chevron）+ 4 个 48dp 子行（Skills/MCP；归档开关/导入任务包）；“自动化任务”被挪用为归档+导入。⑤ “工作区”14sp + “N 个任务”11sp，无搜索/筛选/新建。⑥ 空态 34dp 图标（0.55 alpha）+ 14sp + 11sp 两行**无间距**。⑦ 分隔线 + 28dp mark + 两行环境说明。 | ①②③④⑤⑥⑦ 全部 partial/missing；搜索/筛选/文件夹 missing；“自动化任务”语义 missing | API-4（搜索/筛选/工作区分组）；其余 EXISTING | P1 |
| **Detail** | ① 顶栏与会话页同一 chrome：菜单 + 会话名 + 面板；**无返回箭头**。② tab 左起 ≈58dp，指示器 27×2.6dp 且与文字等宽。③ 用户气泡右对齐：`#EFF3FF`、近黑文字、≈42dp 高、四角同半径。④ 气泡下 13:07 + 复制（消息级元信息）。⑤ 状态行“已完成，用时 1 秒”单句 `#858585`。⑥ 回答按 markdown 排版（段落/列表/粗体/代码）。⑦ 动作行 5 个 ≈12dp 图标（36dp pitch）+ 时间。 | ① 返回箭头 48dp（start 4dp）+ **居中** titleMedium 16sp Medium（goal 首行截 80 字符）+ 同款 `ViewSidebar` 图标打开 Dropdown。② 28dp inset／30dp gap／40×2dp 固定指示器／labelLarge 14sp。③ 320dp 上限、20/20/20/6dp、`primaryContainer #E8F0FE`、`onPrimaryContainer #185ABC`。④ 无消息时间/复制。⑤ 12sp 六段遥测 + 分隔线，最多 2 行。⑥ 单个 `Text(bodyLarge)`，无 markdown；正文来自 `RUN_TERMINAL.summary`（生产值 = `SUCCEEDED`）。⑦ 3×48dp 按钮（22dp glyph）+ 时间；无点赞/点踩/归档/分享会话。 | ①②③⑤ partial；④⑥⑦ missing；⑥ 为 P0 内容缺陷 | API-1（turn 文本/流式）、API-2、API-3；分享 EXISTING | P0 |
| **Composer** | ① 边距 14dp、高 ≈96dp、圆角 ≈24dp、1dp 边框。② 单行占位；工具行：+（≈36dp 圆）、盾牌 + chevron、模型名（`#666`，≈15sp，偏右）、发送（≈34dp，禁用 `#B6C7F3`）。③ 卡下统计：轮/步 · tok·缓存 · 上下文 %（11–12sp `#858585`）三分区。 | ① HOME：20dp 边距、min 112dp 输入、工具行 48dp、总高 ≈192dp；DETAIL：12dp 边距、输入 min 52dp、总高 ≈126dp。② HOME 工具行 4 控件：+／附件（导入任务包）／盾牌／模型 TextButton（labelMedium 12sp 近黑）／48dp 发送；DETAIL：+（=返回）／盾牌／居中模型（84dp 左右 padding）／44dp 发送。③ 统计 11sp 左对齐横滑：“第 N 次运行／N 组活动／N 个产物”，无 token/缓存/上下文。 | ①②③ partial/missing；两个 composer 主体也不一致（圆角容器、发送尺寸、按钮色） | API-3（tok/cache/context）；其余 EXISTING | P1 |
| **Plus menu** | ① 锚定输入卡上方的浮层卡片：左右 ≈15dp、高 ≈272dp、圆角 ≈20dp、1dp 边框、无 scrim、无把手、首行高亮。② “添加”：文件 file／目标 goal／计划 plan／反馈 feedback。③ “指令”：压缩 compact／权限 permission／模型 model。④ 每行 20dp 图标 + 中文 16sp + 拉丁 token 13sp + 右侧灰色说明 13sp。⑤ 即 `/` 命令面板。 | ① ModalBottomSheet（拖拽把手 + scrim，盖住输入卡），全宽。② “添加”：文件（导入任务包）／目标（聚焦输入框）／权限（网络说明）。③ “指令”：模型／任务历史（打开抽屉）。④ 无图标、无拉丁 token、无右说明，16sp + 12sp 副标题 + 右 chevron。⑤ 无 `/`、无 `@` 解析，占位符仍承诺。 | ①missing ②partial ③partial（缺计划/反馈/压缩，权限分组错）④missing ⑤missing | API-5/6/7/8；文件/模型 EXISTING | P1 |
| **Approval** | 截图未出现；只能确认 DSH 有“权限预设 = 沙箱模式与审批策略”（S3 说明文字），审批卡/表形态**未证**。 | 内联“工具授权请求”卡 + pending 时自动弹 92% 高 ModalBottomSheet；拒绝／允许一次／稍后处理；读/写/网络目标、覆盖哈希、参数摘要、冻结绑定；决策仅 `ALLOW_ONCE`/`DENY`。 | DSH parity：无法判定（证据缺失）；MCA 自身：无会话级预设、无“本次允许/始终允许”、自动弹窗打断会话、12sp 多卡信息密度高 | API-5；其余 EXISTING | P2（若把 DSH 权限预设纳入范围则升 P1） |
| **Question** | 截图未出现；assistant 侧提问形态**未证**。 | 内联卡 16dp 圆角/14dp padding；“模型需要你的回答”；≤3 选项（14dp 圆角、48dp 最小行、6dp 间距）**只写入底部草稿**；卡内文案“在下方输入回答并发送。”；composer 切 ANSWER 模式；无卡内提交/跳过。 | DSH 未证；MCA 自身：选择与提交分离、长会话需滚动、无跳过 | EXISTING | P2 |
| **Task actions** | 顶栏右侧 = 面板开合图标（S1/S2 同款）；会话级操作菜单未出现。 | 同款图标 → 3 项 Dropdown（导出任务包／停止任务／删除任务）；抽屉行 overflow（导出/删除）；回答动作行第 3 个图标仍是导出 → **导出 3 处、删除 2 处**；无重命名/置顶/归档/分享会话/反馈。 | partial（同形异义）/ missing（重命名等） | API-2、API-8；分享会话 EXISTING（依赖 API-1） | P1 |
| **Plugins / Skills / MCP** | 抽屉顶层“插件”页（内容未证）；“自动化任务”是独立页面；MCP 入口未证。 | “插件”可展开 → Skills／MCP 服务器 两页；TopAppBar + 刷新；56dp 行 + Switch + 红色卸载 + 分隔线 + 12sp 说明；MCP 添加对话框（名称/HTTPS/本地开关）。 | partial（入口层级与 DSH 顶层页面不同）；DSH 页面内容未证；插件生态不在 V1 | EXISTING（skill/mcp admin ports） | P2 |
| **History / paging** | 工作区列表 + 搜索/筛选/新建文件夹；空态；滚动加载未证。 | 进行中/最近 + 计数；50/页、上限 200；末尾 48dp“加载更多”按钮（手动）；无搜索/筛选；归档开关藏在“自动化任务”展开内；任何导出/导入/删除后 `retryTasks()` 重置到第 1 页（已加载页丢失）。 | missing（搜索/筛选/文件夹/滚动加载）；partial（游标分页存在） | API-4 | P1 |
| **Empty / error states** | 空态 = ≈40dp 线框图标 + 单行“暂无会话”`#868789`；无第二行 CTA；错误态未证。 | 5 种形态：抽屉居中图标+2 行、Skills/MCP 左标题+正文、详情单行 muted、全宽 banner、对话框内进度；空态 11sp 第二行紧贴 14sp 首行；error banner 全宽 `errorContainer #FCE8E6` + 48dp 关闭；HOME 不渲染 feed 空态/错误；加载仅静态文字。 | partial；DSH 错误态未证 | EXISTING | P2 |

## 4. 视觉度量对照（间距 / 字号 / 颜色）

> DSH 值 = 截图 px ÷ 3.077（±2dp）；MCA 值 = 源码字面量。表格只列有实现或截图依据的项。

| 项目 | DSH（截图折算） | MCA（源码） | 差距与影响 | 依据 |
| --- | --- | --- | --- | --- |
| 抽屉宽 | 69.3%（≈270dp） | `fillMaxWidth(0.76f)`、`widthIn(max=340)` = 296dp@390dp | 多 26dp；右侧露出更少，抽屉更像全屏页 | S2；`AgentHomeDrawer.kt` 经 `AgentWorkspaceHomeScreen.kt:85-86` |
| 抽屉底色 | ≈`#F9FAFC`（极浅蓝灰） | `surface` = `#FFFFFF` | 纯白 vs 轻染色；与 HOME 的 `#F8F9FA` 画布组合后，MCA 是“灰底白卡”，DSH 是“白底描边卡” | S2；`McaTheme.kt:43-84` |
| 新任务按钮 | 高 ≈36dp、圆角 ≈12dp | 高 ≥52dp、圆角 16dp、左右 16dp | 头部块多 ≈16dp；把“插件/自动化”整体下推 | S2 y354…466；`AgentHomeDrawer.kt:87-107` |
| 抽屉导航行 | pitch ≈40dp、无 chevron | ≥56dp、22dp 图标、右 chevron + 48dp 子行 | 两行多占 ≈32dp；展开任一分组再增 ≈96dp，把工作区头部继续下推，且多一层导航 | S2 y536…697；`AgentHomeDrawer.kt:110-146, 267-300` |
| 工作区头部 | 标题 + 3 个图标按钮 | 14sp 标题 + 11sp 计数，无图标 | 缺搜索/筛选/新建；标题字号偏小 | S2（视觉）；`AgentHomeDrawer.kt:366-386` |
| 空态 | ≈40dp 线框图标 + 单行 15sp `#868789` | 34dp 图标（α0.55）+ 14sp + 11sp（**两行间无 Spacer**） | 第二行贴着第一行、字号跳 3sp；读作同一段但样式不同 | S2；`AgentHomeDrawer.kt:452-473` |
| 抽屉底栏 | 头像 + 用户名，1 行，无分隔线 | 分隔线 + 28dp mark + 两行（16sp/11sp） | 从“账户入口”变成“环境说明”，且多一条线 | S2；`AgentHomeDrawer.kt:202-223` |
| 会话标题 | 左对齐，起点 ≈59dp，≈14–15sp regular | 居中，16sp Medium，标题区左右各 64dp | 位置与字重都不同；长 goal 截断后悬在屏幕正中 | S1 x182；`AgentTaskDetailScreen.kt:193-204` |
| 返回 affordance | 无返回箭头；菜单常驻 | 返回箭头 48dp（start 4dp），描述“返回任务列表”，实际回落地页 | 会话页失去抽屉入口；a11y 文案与实际目标不符 | S1；`AgentTaskDetailScreen.kt:186-192` |
| 右上图标 | 面板开合（收起/展开侧栏） | 同款 `ViewSidebar` glyph 打开 3 项 Dropdown | 同形异义：用户会期待侧栏，得到菜单 | S1/S2；`AgentTaskDetailScreen.kt:206-211` |
| tab 行 | 左起 ≈58dp、gap ≈37dp、指示器 27×2.6dp（=文字宽） | 28dp、30dp、40×2dp 固定 | 整行左移 30dp；指示器比 2 字标签宽 ≈12dp，两端外露 | S1 x180/82×8；`AgentTaskDetailScreen.kt:247-296` |
| tab 字号 | ≈15sp | labelLarge 14sp | 偏小 1sp，选中/未选中对比更弱 | S1；`AgentTaskDetailScreen.kt:276-284` |
| 会话正文边距 | ≈31dp | 20dp | 每行多 ≈2 个汉字；正文更贴边 | S1 x96；`AgentTaskDetailScreen.kt:143` |
| 用户气泡 | ≈42dp 高、四角同半径、`#EFF3FF` + `#0C101C` | 20/20/20/6dp、`#E8F0FE` + `#185ABC`、padding 16/12 | 形状有“尾巴角”；文字被染成深蓝而不是近黑 | S1；`AgentTaskDetailScreen.kt:385-410` |
| 消息元信息 | 气泡下 13:07 + 复制 | 无 | 缺少消息级时间/复制 | S1；`AgentTaskDetailScreen.kt:300-383` |
| 状态行 | 单句“已完成，用时 1 秒”，≈15sp `#858585` | 12sp `#5F6368`，6 段遥测，可 2 行 | 更大更轻 vs 更小更密；DSH 是“结果”，MCA 是“仪表盘” | S1；`AgentTaskDetailScreen.kt:412-443` |
| 回答排版 | 段落 + 空行 + 列表缩进 + 粗体 | 单 `Text`，无 markdown | **P0**：`-`、`**`、代码围栏原样显示 | S1；grep 无 markdown 渲染 |
| 动作行 | 5 图标（≈12dp glyph、36dp pitch）+ 时间 | 3 按钮（22dp glyph、48dp pitch）+ 时间 | 图标大 83%、间距大 33%、少 2 个动作；整行更空更重 | S1 x99…723；`AgentTaskDetailScreen.kt:511-568` |
| 输入卡 | 边距 14dp、高 ≈96dp、圆角 ≈24dp | HOME 20dp/≈192dp/28dp；DETAIL 12dp/≈126dp/28dp | HOME 高度翻倍；两处主体尺寸不一致 | S1 x43…1151/y2162…2458；`AgentHomeComposer.kt:212-253`、`AgentQuestionCard.kt:211-231` |
| 发送按钮 | ≈34dp 圆、禁用 `#B6C7F3` | HOME 48dp（禁用=Material 灰）；DETAIL 44dp（禁用=primary 20%） | 比 DSH 大 14dp／10dp；同一控件两种禁用色 | S1 x1020…1120；`AgentHomeComposer.kt:296-314`、`AgentQuestionCard.kt:523-547` |
| 模型名 | `#666`、≈15sp、偏右 | HOME labelMedium 12sp `onSurface` `#202124`；DETAIL labelLarge 14sp | 层级反转：DSH 弱化，MCA 用近黑小字（Medium 字重）强调 | S1 x538…961；`AgentHomeComposer.kt:275-294` |
| 占位符 | ≈`#AFAFAF` | `onSurfaceVariant #5F6368` | 占位过深，像已输入内容 | S1；`AgentHomeComposer.kt:230-237` |
| plus 面板 | 浮层 15dp 边距、高 ≈272dp、行距 ≈33dp、无把手 | 全宽 sheet、拖拽把手、56dp 行、scrim | 形态与节奏完全不同；选择时看不到草稿 | S3；`AgentHomePlusSheet.kt:38-71` |
| plus 行 | 20dp 图标 + 16sp 中文 + 13sp token + 13sp 灰说明 | 16sp 标签 + 12sp 副标题 + 右 chevron | 无图标、无命令 token、无右侧说明；行更高但信息更少 | S3；`AgentHomePlusSheet.kt:86-118` |
| 上下文统计 | 轮/步 · tok/缓存 · 上下文 % | 运行次数/活动组/产物数 | 信息类型不同；DSH 的是成本/进度，MCA 的是内部结构 | S1 y2483…2521；`AgentQuestionCard.kt:549-587` |
| 空态/错误色 | 空态 `#868789` | 空态 `#5F6368`；错误 `#FCE8E6` 全宽块 | MCA 的空态更黑、错误态更“重”（有底色块） | S2；`AgentWorkspaceComponents.kt:112-151` |

**颜色结论**：DSH 的强调色是偏紫的 periwinkle（选中 tab ≈`#4C72D5`），MCA 是 Google 蓝 `#1A73E8`；DSH 的次级文字 ≈`#858585`，MCA 是 `#5F6368`。**这属于品牌/令牌层差异，不应在“对齐 DSH”名义下直接改**（`MCA_PROJECT_HARD_LOCK.md` §9.1/§9.2）。可以对齐的是“用法”：会话标题/状态/说明用更轻的灰、气泡内文字回归近黑、占位符不要等于正文色。

## 5. 需要新增或变更的契约 API

> 原则：能在现有投影里算出来的，不新增 API；必须新增的，给出最小形态与它解掉的矩阵行。签名是提案，不是已实现代码。

| ID | 变更 | 最小形态 | 解掉的缺口 | 优先级 |
| --- | --- | --- | --- | --- |
| API-1 | **会话 turn 持久化与观察**（P0 根因） | `AgentTurn(turnId, taskId, runId, role, text, createdAtMillis, terminalStatus?)`；`AgentWorkspaceQueries.observeTurns(taskId, beforeCursor, limit)`；终态时把 `AgentLoopResult.transcript` 的最终 assistant 文本写入；如要流式再加 `observeAssistantText(runId)`。`RUN_TERMINAL.summary` 保持状态用途，不得再当正文。 | Detail 回答、消息时间/复制/反馈、多轮历史、分享会话、Home 会话化 | P0 |
| API-2 | **任务显示名** | `AgentTask.displayTitle`（Room 已有）+ `RenameAgentTask(commandId, taskId, expectedTaskRevision, displayTitle)` + `AgentWorkspaceCommands.renameTask` | 会话标题（左对齐短名）、重命名、抽屉行标题 | P1 |
| API-3 | **运行用量** | `AgentRunUsage(promptTokens?, cachedPromptTokens?, completionTokens?, contextWindowTokens?)`，来源 `AgentModelAttempt.usageJson`；`observeRunUsage(runId)` 或并入 `AgentRunSnapshot` | Composer 统计“tok · 缓存 · 上下文 %” | P1 |
| API-4 | **任务查询过滤/工作区** | `AgentTaskQuery` 增 `text: String?`、`displayStatuses: Set<AgentDisplayStatus>?`、`workspaceId: String?`；`observeWorkspaces(): Flow<List<AgentWorkspaceSummary>>`（`AgentTask.workspaceId` 已存在但 UI 完全忽略） | 抽屉搜索/筛选/文件夹；分页过滤不能只靠客户端过滤已分页结果 | P1 |
| API-5 | **权限预设** | `AgentApprovalPolicy{ASK_EVERY_TOOL, ASK_WRITES_ONLY, SESSION_ALLOW}`、`AgentSandboxMode{READ_ONLY, WORKSPACE_WRITE}` 进 `AgentRunPolicy`；`AgentApprovalDecision` 增 `ALLOW_FOR_SESSION`（涉及持久化枚举，需迁移/兼容） | plus 的“权限 permission”、审批的“始终允许”、会话级策略 | P1 |
| API-6 | **上下文压缩** | `CompactAgentTask(commandId, taskId, expectedTaskRevision)`：生成摘要 turn + 新 run + 收窄上下文；context assembler 需支持摘要边界 | plus 的“压缩 compact”；也解 `CONTEXT_LIMIT` 只能暂停不能压缩 | P2 |
| API-7 | **计划模式** | `AgentRunPolicy.planMode` + `plan.proposed/plan.updated` 事件 + 只读工具门控 | plus 的“计划 plan” | P2 |
| API-8 | **反馈** | `AgentFeedbackPort.submit(taskId, runId?, turnId?, rating, comment?)` + 持久化 | 动作行点赞/点踩、plus 的“反馈 feedback” | P2 |
| API-9 | **产物打开** | `AgentArtifactContentPort.open(artifactId): Uri`（或让 `AgentDocumentLauncher` 支持 artifact）；当前点产物只弹元数据对话框 | 产物“打开/预览”，而不是只能看 SHA/大小 | P2 |
| API-10 | **自动化任务** | 定时/触发任务模型 + 调度 owner；**不在 V1**。在此之前必须把抽屉“自动化任务”改名（纯 UI） | 抽屉标签语义说谎 | P3 |
| API-11 | **会话分享** | 依赖 API-1，把 turns 拼成文本后 `ACTION_SEND`；不需要新契约 | 动作行“分享会话” | P2 |

## 6. 诚实纠偏（当前实现与声明不一致的地方）

### 6.1 P0：真实运行中“最终回答”不是模型输出

- 生产唯一写 `RUN_TERMINAL` 的位置：`AgentOwnerExecution.kt:395-414`，`summary = display.name` → 字面值 `SUCCEEDED`／`FAILED`／`CANCELLED`。
- UI 读取位置：`AgentTimelineProjection.kt:67-75`（取最后一条 `RUN_TERMINAL.summary` 当回答）→ `AgentWorkspaceProjection.kt:457-461`（仅 SUCCEEDED 时作为 `finalAnswer`）→ `AgentTaskDetailScreen.kt:328-352`（`Text(bodyLarge)` 渲染）。
- 后果：一个成功任务的“对话”最终显示 **`SUCCEEDED`**，还带复制/分享/导出按钮；长回答没有任何展示通道（`AgentStep` 无文本字段，`AgentRunSnapshot.steps` 在 UI 中从未使用，`AgentModelAttempt.usageJson` 也不暴露）。
- 为什么测试没发现：`AgentWorkspacePreviews.kt:139` 和 `AgentWorkspaceProjectionTest.kt:269-287` 都伪造了 `RUN_TERMINAL.summary = "已整理 notes.md 的要点并写入 summary.md。"`；这是“预览/单测绿 ≠ 功能可用”的典型样本。
- 额外影响：成功时 `conversationItems` 走 `finalAnswer != null` 分支，**不再渲染运行期间显示过的执行活动**（`AgentTaskDetailScreen.kt:328-352, 353-381`）；列表在终态瞬间变短，且“轨迹”页又把同一批活动重复一遍。

### 6.2 “DSH 1:1 / 按 DSH 模型复刻”不成立

`docs/adr/0002-agent-v1-runtime-and-delivery.md:160` 与 `MCA_AGENT_V1_HANDOFF.md:21` 的表述覆盖了 composer 外形与抽屉分区，但截图证明至少 9 处结构性差异：会话页 chrome、标题位置与字重、tab 度量、内容边距、plus 菜单形态与命令集、抽屉宽度/行高/子行、底栏、消息级操作、输入卡高度。建议在文档中改为“借鉴 DSH 的输入卡与抽屉分区”，不要再用 1:1。

### 6.3 其他已确认的实现级缺陷

1. **“新任务”会静默清空草稿**：抽屉按钮 `closeThen { actions.onDraftChange("") }`（`AgentHomeDrawer.kt:88`）。抽屉只能在 HOME 打开，所以这个主按钮既不能新建会话，又会丢失用户已输入的目标，没有确认/撤销。
2. **DETAIL composer 的 `+` 是“离开会话”**：`Icons.Filled.Add` 绑定 `actions.onBackToHome`（`AgentQuestionCard.kt:364-368`）。DSH 的 `+` 打开添加/命令面板；MCA 里点加号会被踢回落地页。
3. **同一个模型选择器有两套布局**：HOME 用 `TextButton(weight(1f))`（`AgentHomeComposer.kt:275-294`），DETAIL 用 `.align(Center).padding(horizontal = 84.dp)`（`AgentQuestionCard.kt:436-459`）。后者在 ≈330dp 以下的窄屏（可用宽度 ≈W−24−168）且模型名较长时会截断；两处字号也不同（12sp vs 14sp）。
4. **产物点开只有元数据**：`AgentArtifactRow` 的可点区域打开 `AgentArtifactInfoDialog`（`AgentArtifactsSection.kt:39-113, 116-184`），`documentLauncher` 只用于任务包/Skill 导入（`AgentWorkspacePackageCoordinator.kt:50-54`、`AgentSkillsCoordinator.kt:52`）。docstring 称其为“DSH conversation attachment card”，但没有打开文件的能力。
5. **a11y 文案与实际目标不符**：DETAIL 返回箭头 `contentDescription = "返回任务列表"`，实际回 HOME 落地页（`AgentTaskDetailScreen.kt:186-192`）；Skills/MCP 的返回同样写“返回任务列表”，实际回上一 surface（`AgentSkillsScreen.kt:69-74`、`AgentMcpScreen.kt:67-72`）。
6. **禁用发送色不一致**：HOME 的 `FilledIconButton` 只覆盖启用色，禁用回落 Material 灰；DETAIL 覆盖为 `primary.copy(alpha=0.20f)`（`AgentHomeComposer.kt:296-314`、`AgentQuestionCard.kt:523-547`）。
7. **HOME 不用 `home` 的加载/空/错误状态**：`AgentHomeUiState` 有 `loading/empty/errorMessage`（`AgentWorkspaceModels.kt:137-148`），HOME 只渲染 models/error banner（`AgentWorkspaceHomeScreen.kt:149-198`）；首次进入无“正在载入/暂无任务”反馈。
8. **抽屉每次导出/导入/删除后倒退到第 1 页**：`onTasksChanged = ::retryTasks` → `restartTaskFeed` 取消所有页任务并只观察 cursor=null（`AgentWorkspaceViewModel.kt:465-471`）；已加载的 50–200 条会消失。
9. **HOME 的滚动锚点是无意义写状态**：HOME 只有 3 个固定 item（品牌/选择器/输入卡）加少量条件 banner，但 `snapshotFlow { firstVisibleItemIndex }` 每次滚动都写 `savedStateHandle`（`AgentWorkspaceHomeScreen.kt:59-66`）；`initialScrollIndex` 又被强制归零。属于残留管线，建议删除而不是保留。

## 7. 未验证与风险

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 真机两轮渲染（浅/深色） | `NOT_RUN` | 本文件全部为静态源码 + 截图推断 |
| 暗色对比 | `NOT_RUN` | DSH 只有浅色截图；MCA 暗色由 `McaTheme` 角色决定，未渲染 |
| 大字体/字号缩放 | `NOT_RUN` | MCA 多处 `maxLines=1` + 固定 48dp 行，放大字号有截断风险（尤其 tab、模型名、抽屉状态行） |
| IME/键盘 | `NOT_RUN` | HOME 输入卡在滚动列表内，键盘弹起时是否可见未验证；DETAIL 已用 `imePadding + navigationBarsPadding` |
| TalkBack | `NOT_RUN` | 上述 a11y 文案不符已确认；顺序/合并语义未验证 |
| 平板/横屏 | `NOT_RUN` | 抽屉 `widthIn(max=340)` 与 `fillMaxWidth(0.76f)` 的顺序在平板上的实际宽度未验证 |
| DSH 审批/问题/任务菜单/插件页/错误态 | `NO_EVIDENCE` | 截图未覆盖，不能判定 parity |
| DSH 分页/滚动行为 | `NO_EVIDENCE` | 截图工作区为空，无法确认是滚动加载还是按钮加载 |
| 真实模型长回答 | `NOT_RUN` | 与 §6.1 相关：即使修好 summary，也需验证 4 KiB/32 KiB 边界与截断提示 |

## 8. 建议落地顺序（仅建议，本文件不实施）

1. **P0 内容正确性**：API-1 + 停止用 `RUN_TERMINAL.summary` 当正文 + markdown 渲染 + 消息级时间/复制；用一次真实运行验证“回答 = 模型文本”。验收：成功任务显示真实回答；长回答有明确截断提示；对话在运行中→成功时不丢行。
2. **P0/P1 会话 chrome**：DETAIL 改为“菜单/标题/面板”同构顶栏，标题用 `displayTitle` 左对齐，DETAIL 恢复抽屉；tab 度量按 §4；动作行补齐 5 个动作（无 API 的先不画）。
3. **P1 输入卡**：统一 HOME/DETAIL 的 composer 主体（目标高 ≈96–112dp、14–20dp 边距、单行占位、去除附件按钮、发送 40–44dp、禁用色统一）；统计行改用“轮/步 · tok·缓存 · 上下文 %”（API-3，缺失时不画假数字）。
4. **P1 plus 菜单**：改为锚定浮层卡片，按 DSH 的两组命令重排；缺 API 的计划/反馈/压缩**先不放条目**（保持“无死控件”），等 API-6/7/8。
5. **P1 抽屉**：宽度 69–70%、行高 40–44dp、去掉子行（Skills/MCP 改为插件页内入口）、“自动化任务”改名、补搜索/筛选/新建（API-4 前可先禁用并说明）。
6. **P2 一致性**：空态/错误态共用组件、图标 pitch 与 glyph 尺寸、占位符灰度、气泡文字色、HOME 的 loading/empty 状态、分页滚动加载、去掉 HOME 残留滚动锚点。

## 9. 结论

MCA 的 Agent 工作区已经具备 DSH 的**外形骨架**（对话/轨迹、气泡、输入卡、抽屉、plus、审批/问题），但当前存在一个内容级 P0（回答=状态枚举）、一个结构级缺口（逐轮消息与消息级操作不存在）、以及一组高可见的度量差异（输入卡 2 倍高、plus 形态、抽屉宽度/行高、标题/返回 affordance）。在这些修完并有真机渲染证据之前，不能把现状描述为“DSH 1:1”，也不应把本矩阵中的任何一行标为 `PASS`。

---

### 附：证据文件清单

- 截图：见 §1.1 三个 attachment object（只读）。
- 源码：`feature/agent-workspace/**`、`core/agent-contract/**`、`core/agent-runtime/**`、`app/.../agent/**`、`core/designsystem/**`，按 §1.2 行号引用。
- 既有声明：`docs/adr/0002-agent-v1-runtime-and-delivery.md:160`、`docs/proposals/agent-v1/MCA_AGENT_V1_HANDOFF.md:21,62`。
- 本文件为唯一写入；未运行 Gradle，未修改代码，未生成 APK。

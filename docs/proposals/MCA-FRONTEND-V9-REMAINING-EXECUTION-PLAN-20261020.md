# MCA 前端 V9 剩余工作详细执行方案

日期：2026-10-20。范围：保留当前色系、设计语言与信息密度，收口尚未完成的质感、动效、性能和验收工作。

本文是当前源码的剩余工作清单与执行合同，不是新的换肤方案，也不是完成证明。本次仅编写方案，没有修改生产代码、运行构建或操作设备。

## 1. 当前结论与适用基线

当前不能认定已经达到 Gemini / ChatGPT App 的整体完成度，也不能认定 V9 已全部完成。项目已经具备较好的主题、控件、反馈与性能基础；剩余差距集中在异步内容稳定性、动效中断、返回与触控边界、长内容正确性，以及能够对应当前 APK 的完整验收。

执行时以 [项目硬锁](../architecture/MCA_PROJECT_HARD_LOCK.md) 为规范入口；原 [V9](MCA-GEMINI-GRADE-PATCH-V9.md) 提供目标和历史任务背景，本文提供当前剩余切片。历史报告中的命令和 PASS 必须结合原始运行窗口解释，不能自动继承。

本次只读核对的源码基线：

| 项目 | 事实 |
| --- | --- |
| 仓库 / 分支 | `E:/MAC` / `codex/enterprise-refactor` |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9` |
| 工作区 | 大量 tracked / untracked 修改，包含前端、Agent、DSH、停止与资源结算等并行工作 |
| llama.cpp | `4ceb1719101f32637b841206c172f3f058ffc182` |
| stable-diffusion.cpp | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` |
| tokenizers-cpp | `acbdc5a27ae01ba74cda756f94da698d40f11dfe`，内部另有 5 个文件未提交修改，约 478 行新增 / 117 行删除 |
| 本次视觉依据 | 实际查看 `artifacts/ui-quality/r30/chat-shadows.png`、`r30/composer-menu-2x.png`、`r31/state.png`；它们是历史截图，不能代表之后的工作区补丁 |
| 本次证据边界 | 源码、diff、测试源码、已有日志与截图；没有新增真机、编译或性能结论 |

下面的行号用于初始定位，执行前通过函数名重新核对。若 HEAD 或文件变化，先重新建立差异，不能按旧行号机械覆盖。

### 1.1 已有成果：保留并验证，不从头重写

- `McaIconButton` 已有默认 48dp 目标、有界 ripple、共享按压、禁用颜色和必填无障碍名称。
- 助手操作行已有按压、48dp 目标和真实复制成功后的短时 Check 反馈。
- 回答尾部已有完整字段换行、等宽数字特性及统计 → 来源 → 操作的分组；token 数、速度、耗时、“上下文来源”和操作入口继续保留。
- 图片缓存已有按字节限容、版本隔离、请求去重、最多两个并行解码及订阅取消；多个展示入口已有三态和重试。
- 主输入菜单已持续组合，`open` 驱动入退场，已允许大字体增长与纵向滚动。
- 公共 `SmoothPage` 已有新的一位置动效 owner 和行为测试源码；当前属于待整合验证。
- 本轮未提交的检索旋转图标修改已将动画读取移到 `graphicsLayer`；需要验证，不能重复改成另一套动画。
- `:benchmark`、benchmark build type、profileable manifest、三个基本宏场景已经存在。剩余工作是修正、扩展和真实执行，不是再建第二套设施。
- `scripts/release.ps1` 已有签名 Release 构建与来源记录原型；`scripts/build.ps1` 实际产 debug。必须准确区分。
- `b26806e` 已补主菜单退场、大图与图库三态、悬浮球默认位置、部分对比度及阴影；不要重新安排相同改动。

### 1.2 当前尚未闭合的事实

| 编号 | 当前事实 | 分类 |
| --- | --- | --- |
| B01 | `ChatBubbleMessageBlocks.kt:188/192/200` 传入 `contentIdentity`，但 `ChatMarkdownBlockRenderers.kt:62` 声明尚无该参数 | 源码调用契约未闭合；优先阻断整合，未重跑编译 |
| B02 | Markdown 仍在 `remember` 内同步解析；分页后仍扫描完整源文取得完整代码复制载荷 | 待实现性能收口 |
| B03 | 表格拆列删除空单元格，并未正确区分转义 / 行内代码中的管道 | 待修正确性 |
| B04 | 聊天专用页面返回仍有独立的双 channel 与可被迟到 progress 反转的退出路径 | 待修动效终态 |
| B05 | 主菜单退场行仍可执行；研究子菜单条件移除；菜单返回所有权尚不完整 | 待修交互边界 |
| B06 | 图片 `produceState` 换 key 的首帧可能投影旧图，`runCatching` 吞掉取消；还有 nullable 图片展示入口 | 待修异步身份与终态 |
| B07 | 图片预览关闭开始后释放 BackHandler，底部与下拉操作未统一关闭保护 | 待修退出闭环 |
| B08 | 用户消息固定按钮缺外层名称；若干小目标和同类复制反馈尚不一致 | 待修控件完成度 |
| B09 | 新公共动效代码没有匹配的本轮运行 XML；菜单完整退出录像、真实长表格渲染仍缺 | 待验证 |
| B10 | 历史帧样本未完整绑定当前 APK / 输入；120Hz、交互反馈、长期内存未闭环 | 待测量 |
| B11 | benchmark 旧 selector、无效滚动、随机输出、variant fallback 可使测量范围失真 | 待修工具 |
| B12 | 新 FrameEvidence 尚不支持现有 Release record 的字段结构；Release 锁与来源边界也需加固 | 待修构建与证据链 |

最新可见 `artifacts/final-verify-tests-rerun.log` 为 app 3412 tests、2 failures、11 skipped，失败类为 `KnowledgeBaseRoomContractTest` 与 `AgentToolApprovalClosureTest`。这是当前整合风险，不能继续写“全仓全绿”。由相应领域 owner 归因；前端执行者不能为通过而削弱它们，也不能未经比较就认定与前端有关或无关。

## 2. 冻结合同与质感目标

### 2.1 本轮必须保持的产品内容

保持当前品牌标记、色板、字体度量、默认排版、默认间距、圆角、页面几何、气泡、图标体系、导航与入口。长字体 / 短屏的换行和可滚动补偿按现有适配规则处理，不能借机改变 1× 默认密度。

保持 token 数、tok/s、耗时、上下文来源、所有动作和技术信息。禁止通过隐藏统计、缩减历史、合并掉诊断、截短复制内容或移走操作行制造“简洁”。已有诊断的主次关系可以精修，但现有可达性和默认可见合同不能缩水。

保持草稿、选择与输入法行为、用户滚动意图、已展开页数、消息身份、旧会话数据、下载 / 生图 / 推理任务生命周期。前端动画退出不得成为业务任务的额外取消条件。

### 2.2 Gemini / ChatGPT 级完成度的具体含义

| 体验 | 本项目应达到的可观察结果 |
| --- | --- |
| 内容稳定 | 流式更新没有旧会话闪入、代码复制过期、图片错闪、无意义整体跳动 |
| 动效连续 | 进入、拖动、取消、返回、退出从当前位置衔接；打断不反跳，终态只提交一次 |
| 控件精确 | 图标光学重心、同类线条、底座、目标、按下、禁用与成功反馈一致 |
| 信息有秩序 | 正文仍主导，统计完整且安静，来源可读，操作稳定；不靠减信息达成 |
| 平台行为可靠 | 键盘、返回、TalkBack、大字体、短屏和恢复都能完成原任务 |
| 性能可复核 | 输入反馈、帧截止时间、启动、内存各有原始证据；数据能追溯到真实二进制 |

禁止默认增加渐变、光晕、模糊、卡片、阴影、弹跳、图标底座或新强调色。图标“高级”主要来自一致、对齐、状态和反馈；不能靠给全部按钮加背景完成。

本文不预先给“10 分”。方案可执行、代码正确、运行顺滑、视觉精细是不同判断，最终分别验收。

## 3. 执行组织、依赖与优先级

### 3.1 单一整合者与文件所有权

| 角色 | 独占责任 | 并行限制 |
| --- | --- | --- |
| 整合负责人 | 候选快照、公共契约、构建 / 测试 / 安装、最终报告 | 唯一 Gradle 发起者，统一构建锁；负责跨写集审查 |
| A：富文本 | Markdown 入口、投影、parser、表格、相关测试 | `ChatMarkdownBlockRenderers.kt` 的复制按钮也由 A 修改，控件审查者提交要求，不能抢写 |
| B：动效 | 公共 SmoothPage、聊天专用页面动效、测试 | 不触碰推理 / 持久化 owner；保留两套已有曲线差异 |
| C：菜单与控件 | composer / action menu / research capsule、控件局部状态 | `ChatComposerScreens.kt` 单一 writer；菜单与控件切片顺序实施 |
| D：图片 | loader、图片剩余入口、预览终态 | `ChatMessageBubbleScreens.kt` 中图片和控件先后交接，不并发写 |
| E：证据设施 | build / release / FrameEvidence / 宏测试 / runner | 根构建文件、manifest 与其他 owner 协调；不自行并发构建 |

实际并行度受可用槽位和验证吞吐限制。每个执行者必须知道他人正在工作，不恢复或覆盖未知差异。相同文件任务先后交接；不同文件也必须对齐参数契约后才能并行。

### 3.2 剩余任务总表

| ID | 优先级 | 任务 | 前置 | 完成输出 |
| --- | --- | --- | --- | --- |
| R00 | P0 | 当前快照与 Markdown 调用契约闭合 | 无 | 候选清单、匹配声明与调用 |
| R01 | P1 | 长 Markdown 有界后台投影 | R00 | 无旧结果提交、无无界积压、完整复制 |
| R02 | P1 | 表格语义与真实表格渲染 | R01 稳定后 | 正确空列 / 管道 / 横滚与截图 |
| R03 | P1 | 公共 + 聊天专用动效终态收口 | R00 | 中断行为测试与实际调用面 |
| R04 | P1 | 菜单家族退场与返回栈 | R00 | 无退场动作、无返回穿透 |
| R05 | P1 | 菜单 2× 字体和可用高度 | R04 | 重要状态完整、所有项可达 |
| R06 | P1 | 图片请求身份与取消隔离 | R00 | 首帧无旧图、取消不变失败 |
| R07 | P1 | 图片剩余三态与预览退场 | R06 | 全部相关入口终态完整 |
| R08 | P1/P2 | 控件名称、目标、成功反馈、光学校准 | R02/R04/R07 对应写集交接 | 逐控件状态表与实测证据 |
| R09 | P0 | 构建来源与 FrameEvidence 收口 | 可与 UI 独立准备 | Release / benchmark / 安装包可追溯 |
| R10 | P0/P1 | 测量定位、场景有效性、固定重放 | R09；对应 UI 契约稳定 | 测到真实场景，失败不假绿 |
| R11 | P1 | 当前候选测试与生产行为回归 | R01–R10 | 新 XML、日志、真实路径结果 |
| R12 | P1 | 真机两轮质感与可访问性复核 | R11 + 可用设备 | 固定矩阵、两轮问题与复查 |
| R13 | P1 | 帧、反馈、内存、启动与按需优化 | R09/R10/R11 | 每场景分布与 trace、无回归 |
| R14 | P2 | Compose reports 与 Baseline Profile | R10/R13 | 实际报告审阅、profile 收益或明确未检出 |
| R15 | P1 | 全前端集成边界与最终证据报告 | 所有适用任务 | 区分实现 / 测试 / 视觉 / 性能结论 |

P0 表示阻止可信整合或验收，不表示这些源码风险都已在设备上复现。P1 为达到本轮质感目标的主路径。P2 是在真实发现和测量支持下收口，不能拿它延迟更直接的用户缺陷。

推荐顺序：先 R00；A/B/C/D 做各自小切片，E 并行准备证据工具；每个写集稳定后由整合者验证；最后在一个固定候选上做 R11–R15。不要先跑全量长测，再继续修改所有文件。

## 4. R00：锁定候选，闭合当前调用契约

**Owner：整合者协调 A。**

先读取当前 diff 与子模块；记录本任务开始时已存在的修改。区分已提交成果、未提交前端补丁、并行功能工作和生成产物。验证窗口开始时所有生产 writer 暂停写入对应候选；这属于协调步骤，不需要额外产品决定。

精确写集：

- `feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownBlockRenderers.kt`
- `feature/chat/src/main/java/com/muyuchat/feature/chat/ChatBubbleMessageBlocks.kt`
- 必需的局部身份 / 投影文件与测试。

实施：

1. 保留 `PagedAssistantRichText(content, modifier)` 兼容入口。
2. 增加显式 `contentIdentity` 的入口并共用实际实现，接住当前三个 named-argument 调用；禁止只把调用参数删掉以绕开身份隔离。
3. 页数、已完成结果、待解析请求绑定当前显示 owner。切会话 / 换消息时重置该 owner 的分页与异步结果；同 owner 追加文本保留用户已展开页数。
4. 当前局部身份为 sessionId + messageId；父消息行还有完整 key / duplicateOrdinal。当前分行局部状态可以依赖父 key 隔离；如引入跨行共享缓存，必须使用完整 row identity，禁止仅用 messageId。
5. 当前 16,384 UTF-16 分页、安全前缀、“显示更多”、完整代码复制保持。

验收：所有调用有匹配声明；旧入口可用；不同会话相同 messageId 不借旧状态；同会话编辑替换不显示旧正文；明确测试页数保持与重置。未跑编译前只记“源码契约已闭合，编译待验”。

回退：身份调用、重载、owner 必须作为整体回退，不能留下一半契约。

## 5. R01：长 Markdown 有界后台化

**Owner：A。主写集：** `ChatMarkdownBlockRenderers.kt`、`ChatMarkdownBlockParser.kt`，以及必要的小型 `ChatMarkdownProjection` / request state 文件与行为测试。名称可调整，不能建立通用万能渲染框架。

源码入口：

- renderer `:83–84` 目前在 `remember` 同步调用 `parseMarkdownBlocks`。
- parser `:55–60` 会扫描 `fullContent` 为代码块构造完整 `copyCode`。
- `inlineMarkdownText` 还会创建行内 AnnotatedString，必须通过 trace 确认剩余成本。

### 5.1 调度和状态合同

1. 普通短回复使用有明确上限的同步快路径，不让每几个字都闪一次加载占位。起始可用约 2,048 UTF-16 units 作为实验预算，但必须同时约束显示文本与完整源文，并以测量决定保留值。该值是调度阈值，绝不是消息长度上限。
2. 超过快路径预算，将块解析及完整复制载荷扫描一起移到 `Dispatchers.Default`。
3. 每个局部 owner 最多保留当前已完成结果和一个最新待处理请求；不可每个 token 建一个独立长期任务或无界队列。
4. 请求至少包含 owner identity、显示页范围、显示内容 / revision、完整源文 / revision。结果提交同时核对身份和版本。
5. 同 owner、同页范围、严格追加时，可以暂留上一完成结果；换 owner、非前缀编辑、换页范围不得展示不兼容旧结果。
6. 取消旧协程不能替代版本提交检查。CPU 扫描要有适度协作取消点或真正串行的有界最新任务调度；单纯 `withContext(Default)` 不证明旧扫描停止。
7. 不变的完成块可以复用；最后未完成块、变化的代码复制载荷必须刷新。禁止看到显示前缀不变就缓存旧完整代码。
8. 沿用原 parser，先证明等价；本轮不直接重写为复杂增量语法树。
9. 解析异常保留可读的源内容与工程诊断；取消不得映射成用户错误。不得因解析慢删掉数学、链接、表格或正文。

### 5.2 必要行为用例

| 场景 | 必须证明 |
| --- | --- |
| A 慢、B 快、A 晚返回 | 最终只提交 B |
| 会话 A 解析中切 B | A 永不污染 B；同 messageId 也隔离 |
| 追加 / 替换 / 展开更多 | 各自按正确 identity 和页数工作 |
| 显示前缀不变、代码继续增长 | 复制结果含完整最新代码 |
| 高频 burst / owner 销毁 | pending 数量有界，销毁后不提交 |
| emoji / ZWJ / CRLF / 未闭合 fence | 结果与旧解析器等价，无字符破坏 |
| 多代码块 / 列表 / 表格组合 | 原始顺序、内容与链接保留 |
| 已完成块未变 | 可复用实例；发生实质变化时不会复用旧载荷 |

复用 `CodeFormattingTest`、`LongTextRenderPolicyTest`、`InlineMarkdownStabilityTest`、`MarkdownListFormattingTest`；新增测试必须驱动真实 owner / 调度和提交逻辑，不能只匹配源码包含 `Dispatchers.Default`。

设备验收采用同一 100k 字符混合 fixture，比较流式时滚动、输入、开菜单和复制；保留主线程 trace。若 AnnotatedString、布局或列表测量仍成为热点，另做可归因小切片，不能宣布“全部 Markdown 已不占主线程”。

## 6. R02：表格正确性与真实渲染

**Owner：A，独立于异步性能切片。**

写集：`ChatMarkdownBlockParser.kt`，新增表格 parsing 行为测试；有真实渲染问题时才改 renderer；保留 `ChatMarkdownTableWidthTest`。

当前 `toTableCells` 使用 `split('|').filter { it.isNotBlank() }`，会把真实空单元格删除并令后续列左移。实现要区分外侧表格边界和真实空列，并识别转义 `\|`、行内代码 span 中的 `|`。不要用多条互相冲突的正则补丁扩大误拆。

固定 fixture 必须包含：首 / 中 / 尾空列、转义管道、不同行内反引号跨度、ragged rows、长中英文、未完成最后一行、表格与代码 / 列表紧接。separator 行识别和列数补齐策略必须一致；不能静默丢单元格。

显示层沿用现有字体、列宽、表面与横向滚动。通过标准：共享列宽、空列占位正确；360dp 与 2× 字体可横滚到末列；没有被菜单 / 提示层挡住的关键内容；原文与完整复制不丢失。

必须创建确实含表格的原生聊天状态并截图；可先用内部测试 fixture 稳定复现，再检查真实生产聊天路径。旧文件名为 markdown-table、画面却是首页或模型未就绪错误的图片不得作为证据。

## 7. R03：返回动效、打断和统一位置 owner

**Owner：B。**

### 7.1 公共 SmoothPage：审现有补丁后验证

写集：

- `core/designsystem/src/main/java/com/muyuchat/core/designsystem/SmoothPage.kt`
- `SmoothPageMotionState.kt`
- `core/designsystem/src/test/java/com/muyuchat/core/designsystem/SmoothPageMotionStateTest.kt`
- `SmoothPageStructureContractTest.kt`

保留现有一位置 Animatable、单消费者、退出请求同步 terminal gate、最新回调 / 宽度 / 曲线与 layer-phase 读取。不要再叠加第二个水平 slide。

实际运行已有四组行为测试，并补风险对应场景：连续 start/cancel/progress burst、自定义 Float tween / spring 的 offset 转换、更新回调、窗口宽度变化、进入中返回、取消后再次返回、重复 dismiss、销毁取消。只用“出现 Channel.CONFLATED”的结构断言不能证明行为。

在真实调用面验证 ModelHub cloud editor、Agent info，以及实际复用该 wrapper 的页面。IME 可见时按既有规则先收键盘，不能与页面退出同时发生；动画 0× 必须能完成，不能挂在等待帧上。

### 7.2 聊天专用 wrapper：补独立缺口

`feature/chat/src/main/java/com/muyuchat/feature/chat/AppMenuTransitions.kt:267` 的聊天专用 `SmoothRightToLeftPage` 没有自动获得公共修改。它在约 `:293–306` 有两个独立 channel，progress 可取消 settle 并重置 dismissing；约 `:343–350` 的退出可能被迟到 progress 反转。

改动只覆盖该局部 wrapper / motion owner 与对应行为测试。建立统一事件顺序和 dismissal terminal gate。若复用公共 owner，必须证明可配置曲线等价；保留聊天进入 240ms、取消 160ms、退出 180ms tween 和 140ms fade，禁止直接换成公共默认 spring。

必须证明：提交退出后迟到 progress/cancel 不反转；callback 一次；进入中返回从当前位置衔接；拖动取消无预跳；快速连续返回无悬停。

### 7.3 旋转图标等已有 layer 优化

核对当前 `ChatWebSearchMessageViews.kt` 补丁可编译且视觉不变；用重组 / trace 工具确认仅旋转图层更新，不带整张 trace 卡逐帧重组。保留正文、技术信息与状态字段的真实更新。没有测到重组下降时不得报具体百分比。

动效录像必须覆盖进入前、完整动作、退出结束后。10fps 像素差只能辅助确认画面变化；曲线和卡顿需要原始录像 / trace，不能凭几张帧图评“旗舰”。

## 8. R04：菜单家族退出和系统返回

**Owner：C。写集：** `ChatComposerActionMenuViews.kt`、`ChatComposerScreens.kt`、`ChatComposerResearchModeViews.kt`，必要的局部状态与行为 / UI 测试。

当前主菜单已经有 100ms 退场，但菜单行的执行权限未全部与 open 联动；research capsule 仍被宿主条件直接移除，内部 target 一直为 true，声明 exit 也不会产生关闭帧。

实现合同：

1. 明确 Closed / Opening / Open / Closing 的生命周期，视觉存在与可执行动作分开处理。
2. Closing 开始就拒绝新业务动作，且幂等消费重复关闭。退场视觉维持当前色值，不因为禁用而突然整块变灰。
3. 主菜单和研究子菜单持续组合直到退场结束；系统动画 0× 时也必须完成移除。
4. 关闭时仍保留所需的输入阻挡和返回消费，不能让可见退场层把点击穿给底下消息。背景遮罩的可见效果沿用现有设计，不新增装饰。
5. 返回优先级按真实栈核对：顶层 Dialog → 研究子菜单 → 主菜单 → 页面；同时遵循现有 IME 返回规则。返回只关闭当前最上层，不一起退出多个层级。
6. 退场中的第二次返回只消费。打开 / 关闭 / 重开必须使用同一 owner，不能因旧动画完成回调覆盖新打开状态。
7. 选中项必要业务回调执行一次；不能为等待动画而延后关键用户动作，也不能在 exit 完成后再重复执行。

验收操作：

- 打开主菜单 → 返回：只关菜单，草稿与页面保留。
- 打开研究子菜单 → 返回 → 返回：先子菜单再主菜单。
- 关闭后的 0 / 30 / 70ms 点击原菜单项位置：对应业务调用计数不增加，底层动作也不触发。
- 连续开关 20 次、进入中关闭、退出中重开：没有残留 scrim、不可见点击层、旧回调关掉新菜单。
- 0× / 1× 动画均验证；无障碍焦点退出后回到合理触发控件。

## 9. R05：菜单大字体与短屏

**Owner：C，R04 后实施。**

当前标题与副标题在大字体仍为两行 Ellipsis。已有两行标题修好了一个样例，不代表长授权状态和全部不可用原因已经完整。

以 `角色生图：本对话自动授权`、最长可用权限 / 后端说明、长联网源名为固定压力数据：

- 1× 默认菜单宽度、位置、行距与图标尺寸等值。
- 1.5× / 2× 允许重要标题和状态完整换行；不缩字号、不删状态、不把语义可读冒充视觉可读。
- `heightIn(min=48.dp)` 等已有自适应基础保留。菜单最大高度按实际 viewport、composer、IME 与安全区域计算约束，不能仅凭固定 520dp 声称所有短屏适配。
- 纵向可滚到最后一项；增长只由真实内容需要驱动。
- 非关键长解释若已有详情入口可沿用，但不能把原本可见的必要授权词、错误原因藏起来。

验收组合：360dp 窄屏、较大手机、现有平板支持面；fontScale 1 / 1.5 / 2；键盘开 / 关；最长状态；浅 / 深 / 自定义背景。每组合记录实际 viewport、截图和可到达最后项。

## 10. R06：图片请求身份与取消安全

**Owner：D。写集：** `ImageAssetIoSupport.kt`、必要局部状态 / request projection 与测试。复用已有缓存 owner，避免再建一套缓存。

当前 `rememberChatImageLoadState:98–115` 的 produceState 换 key 后旧 value 可能留到新 producer 执行；`runCatching` 又会将取消映射 Failed。`rememberChatImageBitmap:119–131` 有相似首帧窗口。

实现：

1. 每个展示结果带请求身份：URI、目标尺寸、revision，以及适用的任务 / asset identity。当前请求与结果不匹配时同步投影 Loading/null，不能等 effect 才清旧图。
2. 重抛 `CancellationException`；后台解码完成后确认协程仍有效和请求仍匹配再提交。
3. 取消不显示失败、不启动 fallback decode；离开展示或删除资产后旧结果不得重新写回。
4. 保留现有字节上限、并发上限、去重、源版本与最后订阅者取消规则；不额外加无测量收益的预加载。
5. 同一生图任务允许保留上一张有效实时 preview 时，必须明确按 task identity 区分；跨任务不能因同 URI 而保留旧图。

任务身份的接线路径也要落地：从 `ImageGenerationResultScreens.kt` 的 `ImageCreatingPlaceholder` 调用处传入现有 `job.id`，贯穿 preview 展示 owner。没有 job 的待创建态使用局部稳定占位身份，job 创建后切换；不要用开始时间、previewStep 或 URI 代替 job ID。补“新任务复用相同 URI、相同 previewRevision”用例，仍不能显示上一任务图片。

行为测试：A Ready→B 首帧无 A；A 慢 B 快；同 URI 新 revision；尺寸切换；重试；最后订阅者取消；owner 销毁；取消后无 Failed / fallback。复用 `ChatImageCacheContractTest`，不重复测试已经证明的缓存实现细节。

设备使用明显标记的脱敏 A/B 图、慢解码与失败输入，快速切换 / 关闭 / 重开，逐帧确认没有跨图闪现；trace 确认主线程无解码。

## 11. R07：剩余图片三态与预览退出

**Owner：D。**

### 11.1 nullable 展示入口迁移

独占写集：`ChatMessageBubbleScreens.kt`、`ImageGenerationResultScreens.kt`。

初始入口：用户附件约 `:272`；生图 preview 约 `:282`；最终主图约 `:473`；结果缩略图约 `:489`。它们不能继续把不可读、尚未产生与加载中都视为同一个 null。

迁入已有 Loading / Ready / Failed 和重试，保留 104dp / 72dp 等原有尺寸、比例、裁剪及保存 / 编辑 / 分享入口。原图保存质量保持；缩略图采样只服务展示。

实时预览区分“尚无预览”“已有预览读取失败”“任务失败”。预览失败不取消后台生图，第一张尚未产生也不能显示任务错误。循环中的每项使用稳定 asset / attachment identity，插入和重排不借邻项状态。

检查正常、损坏、文件删除、权限失效及恢复重试；失败恢复后状态真实更新，所有原动作仍可执行。

### 11.2 图片预览关闭闭环

写集：`ImageAssetPreviewOverlay.kt`；仅在必要时协调 `ImageWorkspaceScreens.kt` 的父返回处理。

当前 overlay 约 `:166` 的 BackHandler 在 previewClosing 开始就禁用，父页面仍可在第二次返回中直接清 preview ID，截断约 150ms 的退出。顶部按钮已有部分保护；底部重试、再生成和下拉动作尚未统一。

要求：预览从组合到移除持续持有返回，Closing 时幂等消费；关闭时收起子菜单；所有新业务动作统一拒绝 Closing；已开始保存由原 owner 结算，不因视觉退出重复保存或误取消。

检查“按原参数再生成”等按钮禁用时文本颜色和可执行性，不能外层 disabled、内层白字仍呈现强可执行感。只沿用既有语义色，不换色系。

验收：退出中重复返回，点击收藏 / 重试 / 更多 / 再生成，退出完整且只回父工作区；保存中退出不重复提交；参数查看、复用、放大、保存、删除入口保持。

## 12. R08：图标、按钮、反馈与回答尾部精修

**Owner：C 负责控件审查；实际修改遵守文件 owner。**

### 12.1 先修确定缺陷

| 控件 | 当前证据 | 最小完成项 |
| --- | --- | --- |
| 用户消息固定 | `ChatMessageBubbleScreens.kt:322–335` 内图标名称 null，外层未接名称 | 外层动态“固定到上下文 / 取消固定上下文”，真实固定和禁用状态，避免重复播报 |
| 输入更多 | `ChatComposerScreens.kt:472–480` 可见 40dp 直接 clickable | 保持可见圆形与 glyph，实测目标，补一致按压与适用语义 |
| 研究胶囊关闭 | `ChatComposerResearchModeViews.kt:159–165` 16dp 图标直接 clickable | 可见图标不变，目标与名称可靠，返回 / 关闭幂等 |
| 代码复制 | `ChatMarkdownBlockRenderers.kt:238–248` 约 30dp 显式高度 | 由 A 核对真实触控与 2× 字体，完整复制和成功反馈保持 |
| 用户复制 | 用户使用 Toast，助手已有原位 Check | 同类成功反馈一致；真实剪贴板成功后反馈，按消息身份隔离 |
| 图片编辑返回 | `ImageEditorChrome.kt:65` | 核对已有 Material 反馈，只有可见不一致时复用共享按压 |

目标按实际 touch bounds / semantics 测量。可见 40dp / 16dp 与触控范围分开处理；扩展范围不能抢占邻近按钮和正文。若保持现有位置无法获得无重叠目标，先给出准确边界与最小例外，由整合者按冻结合同处理，禁止偷偷挤大布局。

`McaIconButton.selected` 当前主要控制绘制。仅真实选择型调用补 selected/stateDescription；普通按钮不要全部附带 selected=false 播报。已具备相同反馈的控件不做机械替换。

### 12.2 光学与材质审查表

为历史、新建、菜单、添加、发送 / 停止、复制、重试、固定、展开、关闭、保存建立小型清单，逐项记录：glyph 家族、可见大小、目标、线条重量、重心、颜色 role、按下、禁用、成功、语义。先实际截图再决定是否改。

- 保留现有图标体系和 outline / filled 的状态意义，不引入另一套 glyph。
- 使用同一设备原始分辨率检查对齐；放大 crop 辅助观察，最终按正常大小判断。
- 同类底座和 glyph 的视觉重心一致；已有发送 +2dp 光学校准等先验证，不能统一归零破坏已解决问题。
- 边界、ripple 不越过圆角；按压只影响绘制，不挤动 hit region 或邻项。
- 不新增无意义的加载圈、Toast、震动或点击音；加载 / 成功反馈必须对应真实异步结果。

### 12.3 保留全部技术信息的回答尾部

核对 `ChatGenerationMetricsUi.kt`、`ChatBubbleMessageBlocks.kt`、`ChatAssistantActionViews.kt` 与上下文详情入口：

1. token / 速度 / 耗时字段完整，数字和单位不分离，不产生孤立分隔点。
2. 复用已有 tnum 和字段级 FlowRow；不对每次统计变化做位移、缩放或整区 crossfade。
3. 正文、统计、来源、动作的现有层级与默认间距保持。先验证动态稳定性和颜色 role，不再凭“感觉厚”调阴影。
4. 流式转完成时值不回零、字段不抖动、动作不瞬间误可用；Context 来源可访问且完整。
5. 长数值、小时耗时、极低速、缺字段、错误、停止和只读会话分别检查。
6. 浅 / 深 / 自定义背景检查真实合成对比度；普通小字目标 4.5:1，必要图形目标 3:1，合理例外分列。优先使用现有语义 role，不能暗改主色。

本任务通过标准是同类控件一致、信息完整、反馈真实和布局稳定，不是共享组件调用次数增加。

## 13. R09：构建来源与帧证据链

**Owner：E，整合者独占执行。写集：** `scripts/release.ps1`、`scripts/build.ps1` 的必要边界、`scripts/BuildProvenance.ps1`、`scripts/FrameEvidence.ps1`、`scripts/measure-frames.ps1`、工具行为测试。禁止为了前端任务改 native 算法或降低打包合同。

### 13.1 复用现有入口，修正边界

当前 debug record 与 Release record 结构不同：

| 入口 | variant / schema | APK hash | 源码来源 |
| --- | --- | --- | --- |
| `scripts/build.ps1` | debug | `apkSha256` | `sourceProvenance.*` |
| `scripts/release.ps1` | release / `mca.release-build-record/1` | `artifacts.apk.sha256` | `source.*` |

新 `Get-McaFrameBuildBinding` 只读第一种字段，直接传现有 Release record 会被拒绝。应按明确 schema 做兼容适配，归一为只读绑定对象；不要随意猜字段，也不要写一份手工“passed”记录掩盖缺失。

现有 Release 仍有待收口边界：

- `Get-McaSourceProvenance` 在获取锁之前执行，排队期间输入可能已变。
- `gradlew --stop` 在锁之前执行，可能干扰另一个合法构建。
- 末尾仅记录 finalFingerprint，没有用同一种完整来源算法比较构建前后。
- `-SkipBuild` 可以对已有 APK 关联当前源码观察，不能被当作“该源码构建了这个 APK”。

实施要求：先获取同一锁，再采输入、执行构建 / 打包、采相同范围的构建后输入并比较；停止 daemon 等共享操作也在受控边界内，或删除无必要操作。构建中输入变化必须非零失败，记录不能标 passed。

`SkipBuild` 只能复核已有二进制和既有可信 record，保留原 source binding；缺 record 时来源为未验证，不得重新背书。保留签名、原生依赖、ABI、16KB page / 现有打包检查等适用合同。

构建审计产物不能反过来污染输入比较。当前 Release 会改写仓库内 `tools/release/16kb_page_size_report.json`、`tools/sbom/mca-sbom.spdx.json`；这些文件进入完整来源范围。应将本次审计报告写入 run 专属 artifacts 目录：SBOM 复用已有 `--output`，16KB 工具增加精确输出参数。不能忽略整个 tools 或放宽来源检查。补“正常构建与审计不改源码且来源比较通过；真实源码变化仍失败”的工具 fixture。

debug、Release、benchmark 的字段归一要验证 schema、variant、HEAD 格式、64hex 指纹、子模块内容、APK hash 与实际任务参数。split APK 当前不支持时明确 BLOCKED；以后若支持，需完整 split manifest，不能只匹配 base.apk。

benchmark 还需明确产物：宏 runner 在受控锁内采完整前后输入，记录实际 target APK 与 test APK 各自 SHA、包名、variant、任务参数、签名和 profileable 状态，生成可信 benchmark record；安装后核对设备 target APK 才开始测量。`.benchmark` target 是独立二进制，不能使用 Release record 或运行期间工作区不变替代来源绑定。

### 13.2 FrameEvidence 与 measure-frames 加固

1. 每个子模块 git 命令检查退出码，不能将失败读成空 revision 或空 diff hash。
2. source fingerprint 验证 64hex，不仅检查非空；记录文件也保存 hash。
3. 当前工作区观察与安装包来源分列。二者相近、版本号相同、运行期间无变化，都不能证明安装包来自这份源码。
4. 测前 / 测后安装 APK hash 保持一致；测中升级即该样本无效。
5. 原始 framestats 保存 bytes、SHA、采样边界、退出码和支持范围；缺帧不可宣称逐帧验证。
6. summary.json 写入失败必须非零退出；不能只打印错误后仍 PASS。
7. 完善电量 / 供电、主题、导航模式、fixture / 模型 / 参数、冷热缓存、编译 / profile 与热状态。部分平台字段取不到可保留诊断样本，但旗舰验收不得假定条件一致。
8. 保持 PowerShell 兼容编码；`measure-frames.ps1` 当前 UTF-8 BOM 应保留。AST 通过只代表语法，不代表行为。

离线工具 fixture 覆盖：正确 / 不匹配 APK、无 record、旧 / 未知 schema、非法 hash、子模块 git 失败、split、测中升级、summary 无法写入、0 帧、缺百分位、旧 / 新 gfxinfo 文本。预期失败返回非零和明确原因；不得依赖真机才能测错误分支。

`tools/_build_locked.ps1`、`_gate_locked.ps1` 当前仅打印 Gradle exit，不可靠传播失败。整合不要继续据其父进程 exit0 判断成功；复用正式入口或局部修正退出码，并保留原始日志。

## 14. R10：测量场景真实性与受控重放

**Owner：E。写集：** `benchmark/.../BenchmarkUi.kt`、三个场景、`scripts/verify-ui-quality.ps1`、`scripts/measure-frames.ps1`，以及内部 fixture / instrumentation 适配。生产语义文件仅在必要时由原 owner 增加稳定测试标识，不能损害 TalkBack。

### 14.1 修过时 selector 与无效手势

- `BenchmarkUi.kt:55` 仍查 `By.desc("选择模型")`，但模型胶囊已经移除该重复 contentDescription。适配当前按钮角色 / 合并语义 / 稳定 resource-id，不能为测试恢复重复播报。
- measure-frames 的文本 fallback 同样要覆盖已选本地 / 云端模型、未选、加载中和失败，不能只找“选择模型”。
- 图库 setup 验证足够的真实条目与可滚动内容；fling 检查执行结果和前后锚点。空列表 / 短列表 / 没滚动是无效样本，不能当作快速 PASS。
- UIAutomator 命令成功不等于界面动作成功；drawer、model、settings、scroll 各记录目标界面和终态观察。
- runner 找不到指定 variant 的 task 应失败 / 明确未运行，禁止退回另一个 connected task。

### 14.2 将三个结论分开

每次宏测分别记录：`execution`（任务是否运行）、`sampleValidity`（是否测到预定场景）、`performanceAcceptance`（指标是否达标）。任务 exit0 只够第一个。

校验每场景有效迭代 ≥10、有效帧 >0、动作数量 / 范围、完成终态、fixture hash 与内容校验。skip 只能标本场景 NOT_RUN / BLOCKED，不能由其他场景的 PASS 抵消。

### 14.3 补真实链路的固定重放

现在 `StreamingTokenBenchmark` 驱动随机真实模型输出，不能校验实际 500 token，也不是原 V9 的固定 60s 事件流。保留它用于真实生产路径；另建内部受控重放场景：

1. fixture 是脱敏固定事件序列，含正文 / reasoning / stats / 工具 / 图片 / terminal，保存 SHA。
2. 进入真实 publisher → state → mapping → ChatRoute → renderer，不能只给独立 Text 一段字符串代表全链路。
3. 宏测按真实单调时钟和真实帧播放；纯行为测试可用虚拟时间。不能把 60s 事件 `advanceUntilIdle` 到一帧后测流畅。
4. 记录计划 / 实际消费事件数、最终内容 hash、终态、可见字数、完整复制结果与滚动操作。
5. 包含正常小块、2,048 字符 burst、很快结束、无正文等待、reasoning-only、末尾 flush、取消后新 run、重复 terminal、terminal 后迟到 chunk。
6. 包含中文、surrogate / ZWJ、代码 / 表格 / 数学 / 链接和 100k 富文本。100k 是压力 fixture，不限制生产消息长度。
7. 注入入口仅内部 source set / 测试进程，Release manifest 不增加可外部注入会话的入口；benchmark 独立数据不能替代生产升级验收。

先检查现有内部测试 seam；能复用就复用。不要为 fixture 创建第二套生产推理 owner 或全局可变状态写口。

## 15. R11：当前候选验证与生产回归

**Owner：整合者。** 所有命令在实际工具链路径验证后执行；JDK 21、真实 QNN SDK 和项目共享锁必须有效。文档任务不执行以下命令，它们是后续实施步骤。

### 15.1 源码与单测

先检查 diff、未跟踪新源码、架构脚本适用范围，再用相应模块测试编译当前候选。`verify-code -FullTests` 不是全模块，不能自动代替 designsystem 的显式任务。

可采用以下串行模式，产生日志时额外保存前后输入指纹；任务选项需按当前 Gradle 核对：

```powershell
$env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
$env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force

$mcaTasks = @(
  ':core:designsystem:testDebugUnitTest',
  ':feature:chat:testDebugUnitTest',
  ':feature:modelhub:testDebugUnitTest',
  ':feature:settings:testDebugUnitTest'
)
foreach ($mcaTask in $mcaTasks) {
  $mcaLock = Enter-McaBuildLock -Kind test -RetrySeconds 1800 `
    -RetryIntervalSeconds 2 -CommandLine ('frontend candidate ' + $mcaTask)
  try {
    & .\gradlew.bat $mcaTask --rerun --console=plain --max-workers=1
    $mcaExit = $LASTEXITCODE
  } finally {
    Exit-McaBuildLock -Lock $mcaLock
  }
  if ($mcaExit -ne 0) { throw ($mcaTask + ' failed: ' + $mcaExit) }
}
```

检查本次 XML 时间、tests>0、failure/error、skip 原因，以及新增身份 / 取消 / 动效 testcase 真正出现。不要使用全 invocation `--rerun-tasks` 无意义重编所有 native 依赖。

重要现有测试包括：`ChatScrollCoordinatorTest`、`MessageStableIdentityTest`、`MessageIntentIdentityTest`、`CodeFormattingTest`、`LongTextRenderPolicyTest`、`InlineMarkdownStabilityTest`、`ChatImageCacheContractTest`、反馈与动效 contract tests。纯结构断言只做补充，不能替代调度 / 终态行为测试。

app 两项已知失败由对应 owner 处理和复核，保留归因记录；修复前全仓门禁保持 FAIL，不能把前端模块通过扩写为整合通过。若新修改触及其边界，增加该精确行为检查。

### 15.2 构建与安装

R09 完成后，正确性诊断可用 `scripts/build.ps1` 的 debug record；性能与最终质感候选用已加固的 Release 或 release-like benchmark。

```powershell
# Release 入口本身获取共享锁，不在外层再次持有同一锁。
.\scripts\release.ps1 -OutRoot artifacts/releases/frontend-v9-candidate
```

不得在新候选上用 `-SkipBuild` 给旧 APK 背书。记录实际输出 APK、record 路径和 hash；在安装前后核验。生产升级使用兼容签名与原 applicationId，保留既有数据。安装失败记录原始原因，不清数据或改包名假装完成升级。

### 15.3 生产行为回归清单

| 路径 | 最低检查 |
| --- | --- |
| 本地 / 云端聊天 | 发送、流式、结束、停止、错误、重试；可用代表路径分别记录 |
| 身份 | 生成 / 解析 / 复制反馈中切会话；相同消息 ID、重试、编辑、迟到回调 |
| 滚动 | 跟随底部；用户上滑不拉回；显式到底；巨大助手 item 和末尾浏览卡；展开代码 / 来源 |
| 输入 | 中文组合输入、草稿、选择、多行、附件增删、IME 开关；覆盖页回来焦点合理 |
| 技术信息 | token / 速度 / 耗时 / 上下文来源与操作行完整；统计更新不引发全页抖动 |
| 图片 | 加载 / 失败 / 恢复 / 重试；preview 与任务独立；保存分享与原图质量 |
| 页面 | 模型中心、设置、文件库、助手 / 角色、图片编辑与预览、现有 Agent / DSH 入口 |
| 恢复 | 覆盖页、后台回来、适用 Activity 重建；未完成任务仍由原 owner 管理 |

保持当前单一滚动执行者。若滚动 / IME 检查失败，只按现有 `ChatScrollCoordinator` 修最小问题，不重写整套聊天状态。无法运行的真实模型路径逐项 NOT_RUN / BLOCKED，UI fixture 不能替代它。

## 16. R12：两轮真机质感和可访问性验收

**Owner：整合者执行，另一个未写该切片的审查者复核。**

### 16.1 固定矩阵

| 表面 | 必须检查的状态 |
| --- | --- |
| 聊天 | 空态、普通回复、长流式、停止、错误、恢复、只读 |
| 富文本 | 代码 / 复制、真实表格 / 横滚、链接、数学、分页、未完成 fence |
| 尾部 | 普通 / 极长数字、缺字段、流式转终态、来源详情、所有动作 |
| 输入与菜单 | 无内容、多行、附件、IME、主菜单、研究子菜单、退场和返回 |
| 图片 | 等待、Ready、损坏、恢复重试、快速切图、预览退出中操作 |
| 全局页面 | 模型中心、设置、下载 / 导入、文件、助手 / 角色、图片编辑 |
| 新工作区 | 对应 owner 交付后的 Agent / DSH 原生壳与 WebView 页面，分开记录 |

至少覆盖 compact 约 360dp 的真实适用环境、较大手机；本产品已有平板支持，应覆盖一个实际平板窗口。1× 全量代表面；1.5× / 2× 聚焦长标题、菜单、统计、表单、按钮；浅 / 深 / 自定义背景均覆盖实际受影响表面。系统 / API / 导航模式缺口单列，不能只做一台平板就说手机完成。

### 16.2 两轮流程

1. 固定内容、状态、viewport、density、fontScale、主题、键盘和候选 APK；先截图 / 录像。
2. 第一轮从整屏到微观列最多五个最高影响问题；没有五项时如实少列，不编造。
3. 只修这些可定位问题，保留前后图与变更理由。
4. 第二轮同条件重拍，复核修复、邻近控件与状态，不只看 crop。
5. 动效单独录完整开始 / 取消 / 结束；录制干扰性能时，视觉与性能分开跑。

每轮内部固定 APK；修复代码后生成新的受控候选。before / after 分别记录各自 SHA、源码补丁和构建记录，其余内容、设备、viewport、字体、主题、键盘条件一致；不能把两轮写成同一 APK，也不能用旧截图证明新候选。

颜色、字号、换行、间距、尺寸与阴影差异均要解释。缺可信 before 时注明，只能做当前品质审查，不声称严格像素等值。不可整体刷新 golden、遮整块聊天区或复用其他 APK 截图。

### 16.3 无障碍和触控

运行实际 TalkBack / merged semantics，核对名称、角色、状态、焦点顺序、弹层关闭焦点恢复和 disabled。目标按实际可见可触区域核查，不只看 XML bounds。

历史 probe 有 clipped row、未命名 action parent、nested clickable findings。每条要分类为真实缺陷 / 合并语义重复节点 / 视口裁剪并附节点和人工复核；不能统称工具限制后直接归零。

```powershell
.\scripts\verify-a11y.ps1 -DeviceSerial <serial> -NoLaunch
.\scripts\verify-ui-motion.ps1 -DeviceSerial <serial> `
  -Actions drawer,menu,model -EnableAnimationScale
```

以上 motion 脚本仅覆盖其已有动作；输入菜单、研究胶囊和图片预览需要独立动作 / 录像，不能靠 app-menu 的 PASS 代替。临时系统设置结束必须恢复并读回，记录失败。

## 17. R13：旗舰性能闭环与依据 trace 的优化

**Owner：整合者与 E。前置：固定当前候选与有效场景，不在测量中改源码。**

### 17.1 场景与最小覆盖

| 场景 | 主要观察 | 防止误判 |
| --- | --- | --- |
| 普通流式 60s + 输入 | stats 隔离、输入反馈、映射 / 解析 / GC | 真实时钟重放，消费完事件 |
| burst + 100k 富文本 | 解析、排版、完整复制、菜单与 IME | 不把压力内容改短；UI 无 >100ms 可归因阻塞 |
| 阅读脱离底部 / 回到底部 | 用户意图、唯一滚动 owner、巨大 item | 不以关闭自动跟随换取流畅 |
| 设置打开短窗口 | 首次 composition / 切换尖峰 | 与稳态滚动分开，不被长窗口稀释 |
| 设置长滚动 | 稳态帧与分配 | 不能代替打开瞬间 |
| 菜单 / 返回 / 预览退出 | 动画 deadline、打断、事件反馈 | 真正执行目标动作 |
| 20 张图片往返 10 轮 | 解码、去重、Java / native / graphics / PSS | 有内容、实际移动、版本变化与失败恢复 |
| 覆盖 / 后台 / 恢复 | 不可见 UI 工作、状态球采样、后台任务 | 不暂停真实推理 / 下载 owner |
| 冷 / 暖启动 | TTID、有效 TTFD、主题和可交互 | 不把空壳首帧当整体就绪 |

保留此前“延迟设置内容一帧导致更差”的实验记录；不要盲目重试。若仍有尖峰，用 trace 找实际成本，先等值减少工作，不能直接用一帧延迟把负担移进动画中段。

有效 TTFD 必须补真实信号：当前 `ColdStartupBenchmark` 的聊天可见等待是有效性 guard，`ready` 日志也不是“已绘制且可操作”的时间。定义启动内容呈现、关键入口可操作和绘制提交边界，接线平台 fully-drawn / Compose ReportDrawn 或单独受控 trace 标记；测试 ready 后绘制延迟时不会提前结束。没有该信号时，TTFD 列保持 NOT_RUN，不能填入 ready 日志值。

### 17.2 指标与目标

| 项目 | 目标 / 报告要求 |
| --- | --- |
| 刷新率 | 60 / 90 / 120Hz 分别记录，理论预算 16.67 / 11.11 / 8.33ms |
| 截止时间 | 固定 UI 场景 missed deadline 比例 ≤1%，p95 frameOverrun ≤0ms 为旗舰目标 |
| CPU / GPU | CPU frame duration、overrun、适用 GPU / FrameTimeline 分开，不互相替代 |
| 可见交互反馈 | 事件到首次可见反馈 p95 ≤100ms；按压 / 输入追求下个可用帧 |
| 长帧 | 不产生可归因于前端的持续卡顿或 >100ms 主线程阻塞 |
| 内存 | 固定往返循环后活跃集进入平台期，无持续增长趋势；模型权重另归因 |
| 启动 | TTID 与有效 TTFD 不回退，冷暖、缓存、profile 条件明确 |
| 回归 | 可重复 >5% 回退触发归因 / 回退；任何可见故障均需处理，不能以低于5%豁免 |

API31+ 使用真实 `frameDurationCpuMs`、`frameOverrunMs` 与 Perfetto FrameTimeline；低版本说明支持范围。不能用 CPU>8.33ms 推算 missed deadline，也不能凭 peak_refresh_rate=120 或一段60fps录像证明120Hz。

每稳定场景至少10次有效迭代，逐轮与汇总分开；报告 p50 / p90 / p95 / p99、有效帧数、实际事件数、离散程度、刷新率分布与热状态。尾部样本不足明确说明。不得跨场景平均掩盖菜单、IME、设置打开的失败。

同设备、模型 / 参数、fixture、variant / profile、供电、温度、缓存条件比较。热节流或环境不匹配作废但保留原因；只选最好一次无效。差异在噪声内写“未检出显著变化”。

### 17.3 使用现有诊断入口

R09 / R10 修复后，用匹配安装包的受控记录执行：

```powershell
.\scripts\measure-frames.ps1 -DeviceSerial <serial> -NoLaunch `
  -Actions drawer,scroll,model -BuildRecord <matching-record.json>
.\scripts\measure-frames.ps1 -DeviceSerial <serial> -NoLaunch `
  -Actions settings -ScrollCount 6 -BuildRecord <matching-record.json>
.\scripts\measure-frames.ps1 -DeviceSerial <serial> -NoLaunch `
  -Actions settings -ScrollCount 20 -BuildRecord <matching-record.json>
```

gfxinfo PASS 只是诊断阈值，不自动等于V9、120Hz或视觉完成。screenrecord 像素变化 PASS 只证明有运动，不证明曲线和帧截止时间。

R10 修复后分别跑已有宏场景，不能混为一次总体 PASS：

```powershell
.\scripts\verify-ui-quality.ps1 -Lane macro -DeviceSerial <serial> `
  -Variant benchmark -Iterations 10 -CompilationMode default `
  -TestFilters com.muyuchat.mca.benchmark.ColdStartupBenchmark
.\scripts\verify-ui-quality.ps1 -Lane macro -DeviceSerial <serial> `
  -Variant benchmark -Iterations 10 -CompilationMode default `
  -TestFilters com.muyuchat.mca.benchmark.StreamingTokenBenchmark
.\scripts\verify-ui-quality.ps1 -Lane macro -DeviceSerial <serial> `
  -Variant benchmark -Iterations 10 -CompilationMode default `
  -TestFilters com.muyuchat.mca.benchmark.ImageGalleryScrollBenchmark
```

新增固定重放的精确 class / 命令由 R10 注册后写入场景表，不预造尚不存在的可运行命令。

OEM 首装限制或设备不可用时，该项 BLOCKED / NOT_RUN。继续完成独立的源码、工具与行为工作；不要更改正式包身份、清数据或把模拟器指标冒充真机结果。

## 18. R14：Compose reports 与 Baseline Profile

**Owner：E，需实际收益证明。**

Compose 报告设施已经存在。对本轮变更模块生成新 metrics/reports，记录时间与源码快照，检查不稳定参数、无效缓存、长列表重组和 layer 读取是否符合预期。不能仅看某个 skip 百分比就给质感等级；发现问题后以实际 trace 为依据修复。

当前未发现完整 Baseline Profile 生成与收益验证闭环。执行顺序：

1. 先复核已有 AGP / benchmark / profileinstaller 与正式打包合同，复用现有模块，必要时增加最小 profile generation 配置。
2. 路径只覆盖真实高频启动、打开聊天、抽屉、输入、菜单、设置、模型中心与图片浏览；不把下载模型 / 网络等待计入 profile 收益。
3. 生成 profile 后检查目标包、可达路径和 APK 中的 profile 资产，禁止空 profile 宣称接入完成。
4. 同候选源码增加明确的 `CompilationMode.Partial` + `BaselineProfileMode.Require` 与 `Disable` 配对入口，固定 warmup 等其他参数；Require 缺 profile 必须失败。当前 `BenchmarkTarget.kt` 的 `partial` 实际是 Disable，`default` 也不能证明 profile 已安装，不能将二者误称有 / 无 profile 实验。除明确作为实验变量的 profile 策略外，其他编译、预热和缓存条件保持一致；不能把 Full 与 None 的差异归因于 Baseline Profile。记录冷启动和首次交互。
5. 无显著改善则如实记录，不为让“旗舰 checklist”完整强留复杂配置；有回退则归因 / 回退。

此项不能替代 R01 的真实CPU工作减少，也不能替代高刷新率deadline验收。

## 19. R15：全前端范围与整合报告

### 19.1 Agent / DSH 与跨域状态

当前 Agent / DSH 在并行实现，不能沿用旧 DEFERRED 就宣称“整个前端完成”。由其 owner 提交候选与测试 / 渲染结果，再纳入同一主题、控件、输入、返回、大字体、恢复与性能矩阵。

原生壳和 WebView 内容分别记录框架与测量方法；DSH 的截图不能证明原生聊天表格通过，原生聊天帧数也不能证明 WebView 内动画通过。

若用户可见状态仍依赖中文关键词判断等级，作为独立跨壳任务登记所有生产者、真实结构化事件源与兼容字符串展示边界。先与现有 owner 对齐，再做窄类型投影；禁止为美化一个状态条机械改整个 MainUiState 或新建全局 writer。保留旧消息、诊断和 API 兼容。该项影响的路径未验证时单列，不阻止无关切片交付。

### 19.2 不混入本轮的项目

edge-to-edge 全面变更涉及窗口几何；高风险权威会话持久化降频、引擎重构、模型精度 / 上下文调整也不属于本轮质感收口。它们可以独立规划，不能作为当前色系内精修的前置，更不能在本文下顺手实施。

### 19.3 更新现有报告的方式

保留历史证据和日期，追加当前候选结论，不把旧 PASS 改日期。修正文档中已经失效的事实，例如 profileable manifest 已存在；“依赖尚未构建”应区分历史曾构建与当前候选待重验；菜单2×已有样例，剩余是长状态 / 设备组合和退出录像。

最终至少分以下栏：实现、当前单测、候选构建、生产行为、真机视觉、动效打断、可访问性、60/90/120Hz、来源绑定。任何一栏未完成，不能被总分或另一栏 PASS 抵消。

## 20. 统一证据包和交付格式

建议每个候选在 `artifacts/ui-quality/v9-closeout/<runId>/` 保存本地证据，分 implementation / tests / visual-pass1 / visual-pass2 / motion / frames / memory / startup / a11y。不要把真实用户内容、凭据、模型资产或生成 APK 随文档提交。

每项记录：

```text
任务 ID / 负责人 / 复核人：
候选 HEAD / tracked diff hash / untracked 输入 / 子模块内容指纹：
APK SHA / variant / 受控 record 路径与 SHA：
改动文件和实际行为：
功能、信息、数据、视觉冻结项的前后核对：
命令、真实退出码、测试数量、XML / 日志：
设备 / API / viewport / density / fontScale / 主题 / 导航：
fixture / 模型与参数 / 缓存 / profile / 电量 / 热状态：
原始截图 / 录像 / trace / framestats / SHA：
PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE 及原因：
剩余问题 / 下一切片 / 回退边界：
```

回退以本任务 hunks 或有明确边界的切片为单位；共享脏文件不得整体 restore/reset，不删除未知产物、不覆盖子模块补丁。前端任务一般不改数据格式；一旦发现需迁移，单独立项与验证，不能混入显示优化。

## 21. 最终完成定义与等级口径

### 21.1 主路径阻断项

以下任一失败，不得称本轮已达到旗舰完成度：

- 当前源码契约不匹配、关键测试失败或候选构建不可追溯。
- 丢内容、旧会话 / 旧图闪入、完整复制过期、表格内容错列。
- 返回 / 退出多次回调、点击穿透、动画停留、IME 或浮层挡住主任务。
- 控件无名称、必要操作不可达、关键大字体内容截断且无等价可达方式。
- 关键场景未执行却标 PASS，帧 / fixture / variant 归属无效。
- 为收益删功能、技术信息、上下文或降低输出质量。

### 21.2 分层结论

| 等级 | 可说的结论 | 必要证据 |
| --- | --- | --- |
| 实现收口 | 剩余代码项已实现 | diff 审查、真实行为测试、当前编译 |
| 产品精修 | 当前声明范围已达到稳定一致的精修质量 | 兼容构建、两轮真机、完整关键状态、无硬失败 |
| 旗舰质感候选 | 可观察细节与交互达到高完成度 | 前项 + 独立视觉评审，评分至少95/100且证据完整 |
| 旗舰性能验收 | 声明设备 / 刷新率 / 场景满足V9指标 | 有效固定场景、当前APK绑定、deadline / 反馈 / 内存 / 启动原始证据 |
| Gemini / ChatGPT 同级判断 | 只在明示比较范围内给结论 | 记录参照App版本、平台、相同任务条件与逐项比较；无参考条件时不做全面同级声明 |

视觉评分沿用 composition18、hierarchy/type14、spacing14、surface10、interaction14、platform10、a11y8、render/iteration12 的100分权重。95分是本计划对旗舰候选的工作目标，不是自动授分；缺真机证据就不能靠代码审查补足。性能另报，不揉进审美总分。

“10分S级”只能作为目标表述。最终交付应说清覆盖范围、结果和局限，不用满分代替原始证据。计划再详细也不能保证任何执行者零错误，因此每个切片都有行为反例、独立复核和可回退边界。

## 22. 可直接交给执行 Agent 的启动指令

> 任务：执行 MCA 前端 V9 剩余方案中的 `<任务ID>`。先完整读取适用本地指令与 `docs/architecture/MCA_PROJECT_HARD_LOCK.md`，再读本方案对应任务卡、实际源码、测试与当前 diff。当前色系、设计语言、默认排版、信息密度、所有技术信息和功能入口必须保留。先报告本任务写集、owner、当前基线和首个可验证切片，然后在已授权范围内推进。你不是唯一参与者，不能覆盖他人改动；同文件任务先协调交接。只实现该任务，不顺带换肤或重构业务。新增测试覆盖真实身份、取消、终态、打断或恢复风险，不用源码字符串断言冒充行为验证。Gradle与最终构建交由整合者持共享锁串行执行。交付列实际文件、行为变化、验证命令 / 日志 / 数量、未运行项和回退边界。未获得当前真机 / trace 证据的内容，保持 NOT_RUN / BLOCKED；不要自报10分或全部完成。

第一批只分配 R00、R03、R04、R06、R09 的独立写集。R00 的声明与调用闭合后立即验证，不等全部任务结束；随后按依赖表推进。最终由整合者用同一个固定候选完成整套验收。

## 23. 执行状态（2026-10-21，候选 20261020-v9closeout-r1）

本节只追加当前候选结论，不改写上文任何历史事实。完整证据与未完成项见
`artifacts/ui-quality/v9-closeout/20261020-v9closeout-r1/CLOSEOUT-REPORT.md`（本地证据，不提交）。

| 项 | 当前状态 | 依据 |
| --- | --- | --- |
| R00 | 已完成并编译验证 | `PagedAssistantRichText` 三参声明接住 3 处调用；`LongTextRenderPolicyTest` 同步 |
| R01 | 已完成 + 15 例行为测试 | 2048 快路径 / `Dispatchers.Default` / 单槽提交门槛 / 完整 copyCode |
| R02 | 已完成 + 10 例行为测试 | 单一表格扫描器：空列、`\|`、反引号 span、ragged 补齐 |
| R03 | 已完成 + 8 例真帧测试 | `SmoothPageMotionState` 单 owner；聊天曲线 240/160/180/140 显式保留 |
| R04/R05 | 已完成 + 21 例测试；**2× 滚动可达末项已在含五路改动的构建上复现** | `ChatComposerMenuLifecycle` + 真实高度预算替代 520dp；新构建截图 `r05-2x-newbuild/2x-menu-after-scroll.png`（1×/1.5×/2× 取样见 `font-scale/20261021-175804-064Z`） |
| R06/R07 | 已完成 + 21 例测试 | 请求身份同步投影、取消重抛；预览关闭闸门 |
| R08 | 实现完成；**a11y 命名已真机复验**；EX-01/02/03 以测试锁定现值 | 菜单遮挡层新增 `contentDescription=关闭菜单` + `onClickLabel=关闭`，真机 unnamed 1→0；`ChatComposerMenuScrimA11yContractTest` / `ChatTouchTargetContractTest` 锁定 |
| R09 | **PASS：一次 passed、源绑定的签名 Release 已取得** | record `release-20261021-042506-694Z`：`status=passed`、`bindingStatus=bound`、HEAD `68b6eef`、worktree `c924066d…`；APK `MCA-v0.2.4-arm64-v8a.apk` sha `94af094d…`（427 MB）；16 KB 审计 81/81 compliant、SBOM 写 run 目录；此前 5 次尝试均被守卫准确拒绝，说明门槛真实 |…ailed / apk=null；共享树外部编辑频率 2–5 分钟/次 |
| R10 | selector/变体严格性/三结论分离完成；重放链路非设备部分已验证；真机宏测 BLOCKED | BenchmarkUi 改用生产副标题（已就绪/云端 API）+ 8 个不可用标记；measure-frames 7 个 model 指针；`ControlledReplayFixtureTest` 用真实 `ChatStreamAccumulator` 驱动 12 个用例（长度+SHA 全匹配）；ChatRoute→renderer 与真实帧仍 NOT_RUN |
| R14 | 配对完成并编译 PASS；Compose 指标已按模块级取得并审阅 | `-e mca.compilationMode partial-baseline｜partial-no-baseline`；`compileDebugKotlin --rerun` 后 chat 模块 1380 composables / 937 skippable（67.9%）/54 不稳定类 / 445 不稳定参数；据此把 `ComposerMenuLayer`+`ComposerMenuLifecycle` 从 Unstable 修为 Stable（isRendered 改为快照状态）；profile 收益仍 NOT_RUN（无设备） |
| R11 | 模块测试 PASS 且源码未变；本候选的 app 编译与 app 单测均已通过 | chat **456/0**、designsystem **57/0**（`feature/chat/src`、`core/designsystem/src` 自 17:42 零改动）；`:app:compileDebugKotlin` 与两个 app 测试在 2026-10-21 20:57 与 2026-10-22 01:2x 均 BUILD SUCCESSFUL；外部在途重构仍可能让 `:app` 暂时不可编，自动重试脚本可捕捉窗口 |
| R11 app 单测 | **PASS（Gradle 任务，2026-10-21 20:57）** | `ControlledReplayFixtureTest` tests=3 failures=0、`TtfdSignalTest` tests=15 failures=0，XML 在 `app/build/test-results/testDebugUnitTest/`；另可用 `scripts/run-app-tests-standalone.ps1` 在 Gradle 图不可用时复跑 |
| R11.3 真机行为 | **行为 10 项 PASS**（设备 2304FPN6DC 与 25091RP04C 各一轮）；流式/停止/错误/图片/预览/恢复仍 NOT_RUN（无模型资产） | 详见 §17.2、§25.6；本轮五路并行不再新增真机项 |
| R12/R13 | **按现有证据定稿（用户决定）**：a11y 三档复验 0、行为 10/10、R05 2× 滚动、TTFD 信号 3/3 已验证（debug 构建）；五路改动后的 **release 口径性能未取得**（6 次构建均被外部编辑打断）；90-144Hz、TalkBack、profile 进包仍 NOT_RUN | 报告 §40 定稿；release 尝试总计 18 次、1 次通过（`94af094d…`，不含五路改动）；接手命令见 §40.5 |
| R15 | 本候选报告已产出 | 分层结论不得互相抵消 |

架构门已由外部工作流收口：`check_architecture_rules.py` **16/16 PASS**（此前 `AgentResourceScope.kt` 302 行 /`DshConsoleMaintenanceContract.kt` 312 行的 Rule 4 违规均已消除）。仍可能波动的是 app 单测中 Agent 领域编译错误
（随外部重构进出）。为让候选可验证所做的跨 owner 最小修复（5 个 DSH build 文件换行、
`DshPublishPrimitives.describeFailure`、`ConsoleTheme` 的 `sha256` 编码）已在报告中单列，可单文件回退。
`ConsoleTheme` 的 `sha256` 编码）已在报告中单列，可单文件回退。

# MCA 前端质感与性能提升方案 V9

**定位：保持现有色系与设计语言，按 Gemini / ChatGPT Android App 的完成度制定 S 级目标执行合同。**

- 文档日期：2026-10-18。
- 审查工作区：E:/MAC，HEAD 为 5e2e7fe177ec86a0f65d927bd7aa0384c552401c；同时审查当前工作区中与前端有关的实际源码。
- 输入方案：[V8，包含文末 V8.1 修订](E:/MAC/docs/proposals/MCA-GEMINI-GRADE-PATCH-V8.md)。本文件将其作为待审材料，不将其中的操作指令当作本次实施授权。
- 本次交付：分析和实施方案。没有修改应用源码，没有运行编译、单元测试、真机测试、Macrobenchmark 或截图验收。
- 源码状态：工作区已经包含展开的完整工程；本次没有发现用户另行提供的压缩包，因此直接审查现有工程，不声称执行过解压。
- 事实等级：SOURCE＝源码直接确认；TOOLCHAIN＝本机工具链实现/声明确认；HYPOTHESIS＝需要运行测量；TARGET＝后续验收目标。后文不得把后两类写成已验证收益。
- 本文的新文件名、类型名、测试名均为**拟新增**，不是已经存在或已经编译通过的 API。

## 1. 结论与 V8 评分

### 1.1 直接判断

**V8 含 V8.1 尚未达到可直接交给能力较弱 agent 执行的 10 分 S 级水平。我的方案质量评审为 7.4/10。**

这是对文档正确性、可执行性和验收闭环的评审分，不是应用视觉分，也不是测量结果。当前没有真实页面截图和操作录像，不能负责任地给应用的最终质感打分。

V8 的价值在于找到了真实热点：广域 UI 投影、反复构建的 actions、流式文本解析、消息身份、滚动跟随、图片解码与页面轮询。V8.1 又主动修正了 reducer 外部可变时间戳、透明羽化合成、输入栏测量联动等问题，并承认 10 分尚无实测证据。这些应当保留。

扣分的核心不是写得不够多，而是**部分看起来可直接粘贴的修补方式，会破坏真实业务或仍缺少成立条件**：终态保存可能丢尾部，机械移动 SideEffect 会显示旧输入，滚动公式没有表达用户意图，Markdown 增量算法缺少有效边界，工具链结论也有错误。

| 评审维度 | 权重 | V8 得分 | 判断依据 |
|---|---:|---:|---|
| 问题定位与源码关联 | 20 | 18 | 主要热点真实，已有文件/符号定位 |
| 技术与行为正确性 | 25 | 16 | 会话终态、Route、Markdown、滚动及编译器断言仍有问题 |
| 保持现有视觉的约束 | 15 | 12 | V8.1 收紧了范围，但正文仍有字号、边距、阴影等越界项 |
| 弱 agent 可执行性 | 20 | 14 | 有任务编号，但缺依赖完整性、终态矩阵和失败处置 |
| 测量与证据闭环 | 15 | 10 | 承认未测，但部分绝对指标互相矛盾或不能代表体验 |
| 范围和优先级 | 5 | 4 | 有阶段划分，仍应把装饰性效果从主路径移出 |
| 合计 | 100 | 74 | 主观工程评审，不能冒充认证 |

### 1.2 V9 的“10 分”含义

V9 提供的是**以 10 分为验收目标的详细执行合同**，不是“写完就已经 10 分”的证明。只有完成真实生产路径、截图、交互、性能和恢复能力验收，才能给出最终等级。

任何方案都无法保证任意执行者完美实施。能提高成功率的方法是：把任务拆成有明确输入输出的小切片；给出真实业务不变量；对最容易误改的地方设反例；用自动化与独立复核阻止错误进入下一阶段。

最终允许的结论只有：PASS、有证据的 FAIL、BLOCKED、NOT_RUN。不能把 NOT_RUN 改成“预计通过”，也不能用聚合分数掩盖丢消息、错误取消或不可访问等阻断缺陷。

## 2. 当前前端水平：优势、差距与真正的提升方向

### 2.1 已有基础值得保留

工程使用原生 Kotlin / Jetpack Compose，包含独立的设计系统和聊天、模型中心、Agent、设置模块；release 已开启混淆和资源收缩。它不是需要推倒重来的静态 Demo。

现有聊天有明确辨识度：蓝色主调、浅色输入区、胶囊和圆形操作区、非对称用户气泡、透明全宽助手内容、定制品牌标记和自定义背景。对标旗舰产品时，不应把这些替换成竞品的页面。

工程也已具备生成所有权、取消、持久化、恢复、生产 UI 测试、Markdown 与图片行为测试等基础。这些复杂性是真实产品能力，不能为了降低函数参数数或重组计数把它们删掉。

### 2.2 目前的差距更接近“连续使用的完成度”

| 用户感受到的差距 | 源码中对应的原因或风险 | 这次正确的提升方式 |
|---|---|---|
| 输出时页面忙、局部更新牵动整页 | 全量 MainUiState 到 ChatUiState 的广域映射；部分目录、JSON、格式化混入高频路径 | 按真实依赖缓存投影，复用未变条目，缩小读取范围 |
| 看旧消息时被新内容拉走 | 当前按末条内容的 512 字符桶触发 scrollToItem | 独立表达跟随意图，用户手势优先，单一滚动执行者 |
| 多行输入、键盘、图片加载后底部位置不稳 | 列表固定 118dp 与实测输入栏高度分离；多层 inset 需核对 | 建立一个底部占位来源和明确坐标系 |
| 长文越长越费力、代码块边界不稳 | 全量/显示片段重复解析、内联扫描与分页边界 | 先优化等值解析，再有条件做真实增量 |
| 次要页面和悬浮球持续花资源 | 挂载期间持续轮询，折叠和覆盖状态未完全隔离 | 可见性与生命周期驱动，保留折叠态必需数据 |
| 点了没有明确结果、部分入口像未完成 | 抽屉搜索按钮存在空点击；部分品牌按钮缺可访问名称 | 接通既有功能、补状态语义和操作反馈 |
| 暗色/系统栏/启动衔接可能不够完整 | 部分主题角色使用默认值；启动主题和运行主题需实测 | 先记录有效值，再修真实闪烁和裁切问题 |

以上是静态可定位的问题或风险，不能推导出“现在一定掉帧”“现在肯定闪屏”。掉帧程度、GPU 成本、启动闪烁必须实际复现。

### 2.3 对标 Gemini / ChatGPT，学习的是可感知结果

本方案不声称知道两款产品的内部 Compose、渲染器或缓存实现。对标维度应是用户能稳定感受到的品质：

1. 手势立即得到响应；输出再快，读旧消息的主动权仍在用户手中。
2. 输入、键盘、附件、发送/停止切换连续，焦点与光标不意外丢失。
3. 长文、代码、链接、图片在生成中和生成后保持一致，复制得到完整原文。
4. 页面切换没有突然的颜色闪变、裁切、层级错误和旧状态回闪。
5. 加载、空、错误、取消、恢复、权限不足均有真实且可恢复的状态。
6. 字体、轮廓、图标、间距保持现有秩序，交互细节在全产品中可靠一致。

这里最有价值的质感来自**稳定、准确、及时和克制**，不是统一增加阴影、弹簧、渐变或触觉。

## 3. 视觉与业务边界：执行前必须冻结

### 3.1 本次固定不变的内容

执行者首先读取当前仓库约束和 [项目硬锁](E:/MAC/docs/architecture/MCA_PROJECT_HARD_LOCK.md)。本方案不能覆盖更严格的现有约束。

- 不改现有色系、不启用新的动态取色策略、不替换品牌色。
- 不改页面信息架构、入口位置、默认信息密度和现有功能可达性。
- 不改字体族、字号、字重、行高、字距、默认换行、截断行数。
- 不统一用户与助手字号；它们可以是有意的层级差异。
- 不改间距、可见尺寸、圆角语言、图标系统、气泡形态、输入栏外轮廓。
- 不把现有三行信息头改为两行，不把 18dp 全局换成 16dp。
- 不把输入框阴影从某个值机械换成另一个“更高级”的值。
- 不移除自定义背景、模型选择、角色、知识、世界书、工具、生图、搜索和 API 能力。
- 不增加基于芯片、机型、已验证名单或认证状态的下载、导入、加载、运行限制。
- 不修改推理参数、输出内容、原始消息、导出协议、持久化格式来换取 UI 分数。

### 3.2 已确认的颜色来源

| 来源 | 现有数值或规则 | V9 处理 |
|---|---|---|
| McaTheme 浅色 primary | #1A73E8 | 原值保留 |
| 浅色 background / surface | #F8F9FA / #FFFFFF | 原值保留 |
| 浅色 onSurface / muted / outline | #202124 / #5F6368 / #DADCE0 | 原值保留 |
| 浅色 surfaceVariant | #F1F3F4 | 原值保留 |
| 深色 primary | #8AB4F8 | 原值保留 |
| 深色 background / surface | #121212 / #202124 | 原值保留 |
| 深色 onSurface / muted / outline | #E8EAED / #9AA0A6 / #5F6368 | 原值保留 |
| 深色 surfaceVariant | #303134 | 原值保留 |
| Chat McaPrimaryBlue | #3F7DE8 | 不与主题 primary 强行合并 |
| McaStatusAmber | #D98324 | 保留现有警告语义 |
| 输入 shell / field / icon surface | #FEFEFF / #F3F7FF / #EAF1FF | 原值保留 |
| 输入 text / placeholder | #1F2937 / #8A93A3 | 原值保留，先核查暗色/背景下实际可读性 |

上表不是全部主题角色清单。McaTheme 未显式指定的 Material3 角色仍有当前依赖版本下的有效默认值。提取 token 时必须先捕获它们，不能凭感觉补一批新色。

### 3.3 可以直接做的质感改进

允许修复：布局遮挡、重复 inset、视觉状态回闪、裁切、错误层级、未工作的按钮、旧回调、焦点丢失、图片闪动、复制内容不完整，以及按现有设计规范缺失的按压/焦点/禁用/忙碌状态。

这些修复仍要以 before/after 证明具体问题。对现有正确页面，token 提取和架构优化应保持像素/文本布局等值。

48dp 触控目标可优先通过不改变图标可见尺寸的 hit area 实现；若会产生点击区域重叠或改变布局，必须单独设计和验证，不能直接放大所有控件。

### 3.4 默认不进入 V9 主实施路径

透明羽化、模糊/玻璃、全局阴影重调、统一字号、全局弹簧替换、逐帧字符显露、自动新增 haptic、重新布局头部，均不是本轮默认任务。

若以后独立提出视觉改动，必须有明确问题、实机对照、用户认可和性能证据。本轮不因这些可选项未做而阻塞主方案。

## 4. 核验入口与关键事实

### 4.1 真实工具链

| 项目 | 当前值 | 对执行的影响 |
|---|---|---|
| Kotlin | 2.3.21 | Compose 编译器插件随 Kotlin；不能套用旧版 stability 假设 |
| AGP | 8.13.2 | benchmark/profile 变体按此版本验证 |
| Compose BOM | 2026.05.01 | UI 依赖不能为了文档示例随意降级/升级 |
| activity / lifecycle | 1.13.0 / 2.10.0 | 使用当前 lifecycle 包名和 API |
| min / compile / target SDK | 26 / 36 / 36 | 必须覆盖旧系统和 target36 系统栏行为 |
| 构建 JDK | 21 | 使用仓库脚本检查，避免环境导致假失败 |
| release | minify + shrink 已启用 | release-like 测量，不用 debug 数据代表出货表现 |

### 4.2 源码定位表

以下行号是本次审查时的导航锚点，后续以符号和当前内容为准。文件移动前先查调用者和测试，不以行号字符串作为实现契约。

| 编号 | 文件与符号 | 已确认事实 |
|---|---|---|
| A01 | [MainActivity](E:/MAC/app/src/main/java/com/muyuchat/mca/MainActivity.kt:126) | app 层整体 state 参与组合；聊天挂载在其他覆盖页面之下 |
| A02 | [buildChatUiState](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt:175) | 单次映射覆盖很多业务域，需完整依赖表再拆 |
| A03 | [history/assistant 等映射](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatUiStateHistoryMapping.kt:13) | 每次逐会话格式化；assistant 解析参数并序列化 exportJson |
| A04 | [模型目录映射](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatUiStateModelCatalogMapping.kt:15) | 目录含真实 runtime/backend 能力，不可删成简化列表 |
| A05 | [图片运行态映射](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatUiStateImageRuntimeMapping.kt:13) | 备份、LoRA、TI、upscale、文件、任务均需保留 |
| A06 | [ChatStreamAccumulator](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatStreamAccumulator.kt:10) | 文本发布阈值是 75ms 或 2048 字符；空缓冲 flush 不 commit |
| A07 | [生成循环](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt:363) | append 文本之外还单独发布 Chunk stats |
| A08 | [ChatGenerationUiPublisher](E:/MAC/app/src/main/java/com/muyuchat/mca/presentation/ChatGenerationUiPublisher.kt:112) | 高频 stats/progress 是独立更新来源 |
| A09 | [McaAppContainer](E:/MAC/app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:322) | 当前流式提交同时更新完整消息与会话 |
| A10 | [会话 upsert](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceState.kt:31) | 保存消息、角色归属、模型、标题、置顶、外观、摘要等；内部读当前时间 |
| A11 | [runChatSessionPersistence](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt:49) | flush 后从 chatSessions 取完整快照并保存，含 sequence/mutex/rollback |
| A12 | [stopActiveGeneration](E:/MAC/app/src/main/java/com/muyuchat/mca/ChatStopGenerationOperations.kt:78) | 显式 Stop 有独立 epoch/flush/保存路径 |
| A13 | [ChatRoute](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatRoute.kt:59) | composition 内 update，随后读取 holder.inputs |
| A14 | [ChatViewModel](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt:76) | capability identity 变化会重建 actions；不应再复制业务 authority |
| A15 | [mainChatScreenActions](E:/MAC/app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt:33) | 闭包含最新状态/宿主回调问题，LoRA 校验需保留 |
| A16 | [ChatMessageListSection](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMessageListSection.kt:64) | 固定底部 118dp；key 含 role/time/index；逐行执行部分关联查询 |
| A17 | [ChatScreen](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt:244) | 512 字符桶强制滚动；有实测 composer 高度和 MIUI IME 兼容注释 |
| A18 | [PagedAssistantRichText](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownBlockRenderers.kt:62) | 16384 字符分页；需要兼顾完整代码复制 |
| A19 | [parseMarkdownBlocks](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownBlockParser.kt:55) | fullContent 与显示 content 重复走源解析路径 |
| A20 | [Markdown 源解析](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownSource.kt) | 已有围栏/原文语义，不能用几条正则替换 |
| A21 | [HTML 消息](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatBubbleMessageBlocks.kt:97) | produceState/Default 做解析，仍需过时结果隔离和有界调度 |
| A22 | [图片解码](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ImageAssetIoSupport.kt:28) | 已两阶段采样；remember 带 URI/尺寸/revision，不能说完全没降采样 |
| A23 | [设备状态球](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatDeviceStatusScreens.kt:81) | 折叠态也读内存、ring、warning、active，不能一折叠就停全部数据 |
| A24 | [RuntimeScreen](E:/MAC/feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsRuntimeScreen.kt:47) | 轮询和 advancedJson 派生需按可见性与精确 key 管理 |
| A25 | [抽屉](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatHistoryDrawerScreens.kt:60) | 已有 query/filter，但搜索图标空点击；品牌按钮语义待补 |
| A26 | [McaTheme](E:/MAC/core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaTheme.kt) / [ChatColorTokens](E:/MAC/feature/chat/src/main/java/com/muyuchat/feature/chat/ChatColorTokens.kt) | 现有配色权威来源，先等值整理 |
| A27 | [SmoothRightToLeftPage](E:/MAC/core/designsystem/src/main/java/com/muyuchat/core/designsystem/SmoothPage.kt) | 有独立关闭 Animatable；visible=true 不能证明关闭动画缺失 |
| A28 | [AppStartupViewModel](E:/MAC/app/src/main/java/com/muyuchat/mca/AppStartupViewModel.kt:74) | 初始化/迁移已有 IO 分段，不能污名化成主线程 runBlocking |
| A29 | [启动样式](E:/MAC/app/src/main/res/values/styles.xml) | 需核查暗色启动/系统 splash；当前只能提出验证项 |
| A30 | [verify-code](E:/MAC/scripts/verify-code.ps1) / [共享构建锁](E:/MAC/tools/build-guards/McaBuildLock.psm1) | 现有 Gradle 入口共享锁；新增 runner 必须接同一把锁 |

## 5. 对 V8 / V8.1 的逐项裁决

| V8 主张/实现 | 裁决 | V9 替代 |
|---|---|---|
| 流式性能应从状态传播和派生计算入手 | 保留 | 先分离真实依赖，测各段 CPU/分配 |
| 每 token 都重建 UI/都重组 | 不成立的泛化 | 75ms/2048 字符文本门限、stats 发布、StateFlow 合流、组合跳过分开统计 |
| 只在新会话或 terminal batch 更新 chatSessions | 撤销默认实施 | 保留完整会话同步，优化轻量投影；终态物化如需改造独立审查 |
| V8.1 已彻底修好纯 reducer | 不成立 | helper 仍读时钟；输入捕获在 reducer 外，重试需确定性 |
| V8.1 terminal 覆盖四个 flush 点即可 | 不完整 | Stop 和持久化入口也有 flush；Done 后还可修改消息；空 flush 无 batch |
| 稳定性配置不支持通配符 | 错误 | 本机 Compose 编译器支持 * 与 **；但不鼓励粗暴稳定性白名单 |
| Kotlin 不允许表达式 @OptIn | 错误 | kotlin.OptIn 的目标包含 EXPRESSION；具体写法由本工具链编译验证 |
| List/data class 不稳定所以旧消息必重组 | 错误推断 | strong skipping 默认开启，先看报告与 trace，禁止伪造稳定承诺 |
| 直接把 update 搬进 SideEffect | 不足 | 同帧 UI 用当前参数；回调读取最新已提交能力；保持 nullable capability |
| 六个切片可直接组装完整 ChatUiState | 尚不完整 | 为所有字段建立来源/依赖清单，再做新旧投影等值测试 |
| catalog 包含 stats 却要求整轮只执行一次 | 验收自相矛盾 | 仅依赖变化时执行；精简实际需要的 stats 子字段 |
| 末项几何/底边差值代表总内容增长 | 不成立 | 意图状态机 + 独立几何纠偏；内容增长不能自动解除跟随 |
| 输入栏高度与 contentPadding 联动 | 保留问题方向 | 先统一占位/IME/Scaffold 坐标，不能简单再多加一次 |
| 增量 Markdown prefix/tail 示例 | 不可直接照抄 | 缺安全边界函数/状态；先等值单次解析，后可选增量 |
| 正则补 ``` 和 ** 即可稳定 Markdown | 撤销 | 不修改原文，保留真实围栏语义，保守回退 |
| 折叠状态球停止所有采样 | 会破坏现有 compact 内容 | compact 保留必要采样，expanded 才计算额外详情 |
| 透明羽化用 Offscreen | 合成方向修正有价值，默认不做 | Offscreen 有额外 GPU 成本；本次排除装饰项 |
| V8.1 羽化示例已经可编译 | 静态签名仍有问题 | verticalGradient 的 vararg 后应使用命名 startY/endY；本次不引入此效果 |
| 改字号/间距/阴影/头部信息层级提升高级感 | 超出本次授权 | 记录为独立设计提案，本轮不执行 |
| lifecycle 依赖完全缺失 | V8.1 已纠正 | 使用 androidx.lifecycle.compose.LocalLifecycleOwner；直接使用宜显式声明依赖 |
| 最后承认缺少性能/截图证据 | 保留并加强 | 所有收益必须绑定当前构建指纹和原始结果 |

## 6. 不可破坏的状态、所有权与持久化契约

### 6.1 先理解数据的角色

MainUiState.messages 是当前页面消息，chatSessions 的 messages 是完整会话内容的一部分，durableChatSessions 是最后实际提交的持久化锚点。它们不是三个可以随意降频的等价缓存。

chatSessions 同时服务历史恢复、导出、会话切换、知识绑定、摘要、角色归属、工具续轮、图片流程与失败回滚。**不能按抽屉是否可见决定它的新鲜度。**

页面可缓存 history 行、格式化时间、展示名称、目录、渲染结果；业务 authority 继续由现有业务 owner 管理。新增页面 holder 不得成为另一个写完整会话的地方。

### 6.2 reducer 规则

CAS/update 中只能根据显式旧状态和不可变输入生成新状态。时间、随机数、UUID、计数副作用、IO、协程启动、日志采样、外部变量赋值都放在 reducer 外面。

当前 upsertSessionFor 内部调用时钟是一个已存在的边界问题。若触碰该链路，拟新增不可变 mutation 输入携带 occurredAt；可保留旧签名兼容适配器，但不能让高频 CAS 仍隐式读时钟，也不能借这个任务重写整个保存架构。

不要在 reducer 内“取结果到外部 var”，然后声称 exactly-once。必要结果应由明确 owner 串行操作或有测试的状态事务边界产出。

### 6.3 正确的终态不是“最后一个 batch”

排空缓冲和保存完整终态是两个不同动作：

1. 核对 run/session/epoch 身份，停止接收旧 run 的新发布。
2. 排空仍属于该 run 的 pending 文本/思考内容；空缓冲也是合法情况。
3. 完成 citations、空回复回退、工具结果、错误说明等实际终态修改。
4. 再取得与该身份匹配的完整会话快照。
5. 交给现有 persistence sequence、mutex 和 rollback 语义保存。
6. 晚到的旧保存可以更新“实际提交了什么”的 durable 锚点，但不得覆盖更新页面或清掉较新 pending 状态；遵循当前保存链路的细粒度检查。

以上描述的是逻辑责任，不是要求所有终态机械执行同一线性顺序。封闭新 delta 不得自行提前推进 epoch 或撤销 lease，导致合法 pending flush 被拒绝。保留 Done 的先释放生成 UI、后慢收尾，以及 Stop 的 cancel→推进 epoch→无 runId flush 等现有专属语义；是否允许终态写入由真实 owner 判断。

首先保留已正确的生产顺序，通过测试保护；优化若不需要触碰这些步骤，就不要触碰，也不把所有逻辑集中到一个大函数。

### 6.4 必测终态矩阵

| 编号 | 输入/竞争情形 | 必须成立的结果 |
|---|---|---|
| T01 | 最后 chunk 已经发布，Done 到来时 buffer 为空 | 未超存储边界 fixture 的正文/思考及合法持久字段完整；对应会话与Room canonical结果等值 |
| T02 | 最后 chunk 未到 75ms/2048 字符门限 | Done 后不丢尾部、不重复追加 |
| T03 | 零 chunk、reasoning-only、只有 duration/stats | 现有空回复/思考和终态语义保留 |
| T04 | Done 后补 citations、工具结果、空回复回退 | 保存的是全部修改完成后的结果 |
| T05 | Error、外层异常、没有标准 terminal 的结束 | 已接受内容保留；错误状态和保存按原契约完成 |
| T06 | Stop 推进 epoch，flush 无 runId | 保存合法部分回答；重复 Stop 幂等 |
| T07 | 旧 chunk / Done / save 晚到新 run 或新 session | 不能污染新消息、状态、工具/图片归属 |
| T08 | Done 解除生成状态后用户立即切会话 | 慢收尾不得把旧会话重新设为当前 |
| T09 | 生成中重命名/置顶/导出/绑定知识/更改外观 | 不丢 metadata、消息尾部或角色快照 |
| T10 | Room 保存失败 | 正确回滚或保留错误恢复路径；durable 锚点只表示真实提交 |
| T11 | clear/discard/delete 之后旧任务结束 | 被清除消息不能复活；孤儿清理遵循实际提交 |
| T12 | UI 与 authenticated Local API 同时工作 | UI 取消只取消自己的 owner；API 运行和 stop target 不串线 |

Room 比较基于现有 canonical mapping，而不是整个内存对象直接相等。现有保留规则包括最多100会话、整个保存集合总计最多2000消息和16MiB级负载边界；2000不是每会话额度。inline image bytes 也不按原内存对象保存；以 ChatSessionPersistenceMapping 当前实现为准。另设超界与非持久字段 fixture，验证旧裁剪/映射行为等值，不能为通过 T01 删除存储保护。

## 7. 观测与验收：先给“流畅”一个可靠定义

### 7.1 三条测量通道

**A. 确定性 UI 重放。** 在内部 benchmark 构建中把固定、脱敏事件序列送入真实 publisher → state → mapping → ChatRoute → renderer 链路，测前端成本。不能只直接给一个静态 Text 然后代表完整产品。

**B. 真实生产流程。** 通过生产 MainActivity 和真实本地/云端模型路径验证发送、停止、工具、图片、持久化与 API 隔离。固定模型/参数/输入；记录运行环境。UI 重放不能代替这一条。

**C. 微基准。** 单独测 mapping、Markdown、搜索、格式化、图片去重等纯热点。微基准用于归因，不能替代页面帧指标。

### 7.2 构建与重放隔离

- 拟新增独立 UI macrobenchmark 模块和 release-like target variant，名称在 G00 发现阶段确认后固定。
- target 非 debuggable，可 profile，尽量继承 release 的优化、资源压缩和实际代码路径。
- benchmark 签名、applicationIdSuffix、安装数据与正式用户数据隔离；不能为了性能结果修改正式签名或推理包。
- 脱敏 fixture 和事件注入仅存在于内部 source set；正式 release 不得暴露额外 exported Activity、未认证接口或任意会话注入入口。
- 内部 runner 的入口必须可由测试进程安全调用；启动方案和 manifests 必须经过显式审核，不能把测试 seam 做成产品权限后门。
- 本仓库 core:benchmark 用于模型调优，不是 UI Macrobenchmark；不复用其名字冒充已有测量设施。
- debug 计数器/trace marker 单独开关；最终性能采样必须确认其开销受控，不能在主线程逐 token 写日志。

### 7.3 统一记录条件

每次 before/after 保存：commit、完整工作区输入指纹、APK SHA-256、variant、依赖版本、设备/API、分辨率/density/fontScale、主题、刷新率、导航模式、温度/节流状态、电量/供电、模型/参数、fixture hash、编译/profile 状态、缓存冷热状态和原始 trace。

同一对比中这些条件保持一致。性能测试所选设备只是证据样本，不构成产品能力准入名单。兼容性覆盖与功能可用性分开管理。

### 7.4 帧指标与目标

| 刷新率 | 单帧预算 | 解释 |
|---|---:|---|
| 60Hz | 16.67ms | 不能以此验收 120Hz 设备 |
| 90Hz | 11.11ms | 按实际固定/观测刷新率记录 |
| 120Hz | 8.33ms | CPU 帧耗时和 GPU/呈现截止时间一起看 |

FrameTimingMetric 的 CPU duration 与 frame overrun 分开报告；overrun≤0 才表示该帧满足其时限。API 不支持相同 trace 指标时，报告支持范围和替代测量，不伪造统一列。

| 体验/工程项 | 初始目标 | 验收方式 |
|---|---|---|
| 固定 UI 场景帧时限 | missed deadline 比例≤1%，p95 overrun≤0ms 为旗舰目标 | 每场景单独给分布；未达标则归因，不能平均掩盖 |
| 长文突发输出 | 不产生可归因于前端的持续卡顿或 >100ms 主线程阻塞 | trace 定位 mapping/parse/layout/GC；native 竞争也单列 |
| 输入/点击可见反馈 | p95≤100ms；输入和按压更优目标为下个可用帧 | 固定事件到首次反馈的测量定义，不能用回调开始代替 |
| Stop | 用户反馈与原有 native 停止超时分开 | 不把 native 完成时间伪装成 UI 延迟，也不谎报已停止 |
| TTID/TTFD | TTID、有效可交互 TTFD 均不回退；逐项解释冷/暖启动 | 首帧空壳不能被当成完整就绪 |
| 内存 | UI 有界缓存；固定循环后活跃集进入平台期，无持续斜率 | Java/native/graphics/PSS 区分；模型权重和 UI 分开 |
| 电量/后台工作 | 不可见 UI 无无意义的定时采样/解析 | trace/job 计数；保留真实后台推理/下载任务 |

这些是待测目标，不是对当前产品的事实声明。G00 应登记哪些设备/场景可以可靠达到该目标；不得为了通过把长文改短、关闭图片、降低刷新率或修改模型参数。

### 7.5 采样与回归判定

- 每个稳定场景先做至少 10 次有效迭代；冷启动、长时间内存、热节流另设协议。
- 报 p50/p90/p95/p99、样本量、离散程度与原始结果。尾部样本不足时直接说明，不能过度解释 p99。
- 前后差异落入噪声区间时写“未检出显著变化”，不写“提升 1%”。
- 默认 CPU/分配等热点希望有可重复下降；不预先承诺 50%/80% 之类收益。
- 任一关键场景出现可重复的 >5% 回退，或新增长帧/泄漏/行为缺陷，必须解释或回退。5% 只是启动调查阈值，不是允许低于它的用户可见回归。
- 设备温度或后台负载不匹配时该组作废，并保存作废原因；不得只挑最好一次。
- 真实用户路径不得因测试失败而被新增“未经验证设备”拦截。

每次还须记录有效应用帧数、实际消费事件数、目标动作起止标记与最终内容校验。零帧、fixture未消费完、手势未执行、采样窗口遗漏目标交互均为无效运行。每轮统计与跨轮汇总分开；missed deadline 用实际受支持的 overrun/deadline 数据计算，不能直接用 CPU duration 超过固定预算近似替代。

### 7.6 质感截图和录像

每个视觉相关切片至少两轮：第一轮逐帧/截图找问题，第二轮修后复查。无视觉差异的性能切片也要检查代表性页面的几何与文字等值。

固定 viewport、density、fontScale、主题、语言、时间和内容；对光标、进度、时钟等不可确定区域只允许窄范围 mask，保留 mask 清单。禁止把整个消息区 mask 掉制造零差异。

静态图检查轮廓/颜色/换行；录像检查输入/键盘/滚动/切换/打断。自动像素差不能替代人工观察，人工“感觉更顺”不能替代帧数据。

## 8. 全字段投影合同：防止拆完以后功能丢失

### 8.1 拆分方式

拟新增投影输入类型在 app 层，不让 feature 依赖 MainUiState。允许先保留 buildChatUiState 的兼容入口，内部逐个委托已验证的投影器；不要一次替换全部调用链。

每个投影必须满足：相同显式输入→相同输出；输入未变化可复用；输入变化及时反映。Locale、时区、权限、文件 revision、函数提供的参数结果也可能是依赖，不能漏掉。

输入类型不持有整个 MainUiState 冒充切片。对可变集合，先建立真实不可变快照/版本边界；不能加 @Immutable 后继续原地修改。也不强制为了指标改成某个集合库。

### 8.2 必须覆盖的 ChatUiState 输出域

下面列出当前字段全集的分组合同。执行者仍须生成逐字段 dependency manifest；分组不是授权省略字段。

| 拟投影域 | 当前输出字段 | 必须查清的依赖/失效条件 |
|---|---|---|
| Message / Conversation | messages, activeConversationId, conversationReadOnly, input | 当前会话身份、完整消息、输入、角色快照/混合历史只读规则 |
| History | history | 标题、presentation、pin、选中、消息数、updatedAt、locale/timezone、mixed 标志 |
| Chat Catalog | localModels, selectedModelId, selectedModelName, selectedModelIsCloud, selectedModelRuntimeLabel | 本地/云目录、当前 backend、loaded、真实 readiness/transport、目录使用的 stats 子字段 |
| Image Catalog | imageModels, imageModelsLoading, existingImageModelIds, selectedImageModelId, selectedImageModelName, selectedImageModelIsCloud | 本地/云生图目录、能力准备、选中与运行时，不允许以未知机型禁用 |
| Assistant | assistants, selectedAssistantId | assistants、params fallback、本地/云模型名称、选中、角色 exportJson |
| World Book | worldBooks | 世界书内容/entry、global/assistant/session scope、当前 assistant/session |
| Knowledge | knowledgeBases | 基础信息、selected IDs、document counts、importing IDs、indexState |
| Model Availability | modelLoadMessage, modelReadinessLabel, statusMessage | 加载/错误/运行状态；不能把所有 stats 放进 key 图省事 |
| Image Library | images, generationHistoryInputUris, imageLibraryBackup | 原图/缩略图 revision、历史元数据、授权、备份运行/失败/计数 |
| Image Extensions | imageLoras, imageLoraImporting, imageLoraMessage, imageTextualInversions, imageTextualInversionImporting, imageTextualInversionMessage | LoRA/TI 文件、能力匹配、实际导入和错误 |
| Upscale | imageUpscalers, selectedImageUpscalerId, imageUpscalerImporting, imageUpscalerMessage, imageUpscaleJob | 目录/选择/导入/真实 job |
| Image Jobs / Files | files, imageJobs, deferGenerationImageGrantRelease | 文件信息、生成任务、revision、权限释放时机 |
| Generation | isGenerating, generationPhase, generationTokenProgress, generationPersistProgress, generationStats, promptContextUsage | run identity、phase、真实进度与终态，不能被低频 stats gate 挡住 |
| Context Summary | contextCompressionThresholdPercent, contextCompressionPending, contextSummaryModelSelection, contextSummaryModels, contextSummaryHistory, canUndoContextSummary | 当前 session/context summaries、云模型 configured、阈值/选择/undo 条件 |
| Drafts / Browser | pendingImageTranslationDraft, pendingImageActionDraft, browserTask | 草稿 identity、浏览器任务状态机、归属 |
| Image Tool | assistantImageToolAvailable, assistantImageToolUnavailableReason, assistantImageToolAutoApproval, assistantImageToolCanChange | assistant 权限、云格式/配置、当前模型；所有原因字符串保留 |
| Runtime / API | stats, apiEnabled, restEnabled | compact/expanded 所需真实 runtime 数据、API 配置/运行态 |
| Generation Parameters | generationParams, reasoningMode | 选中模型有效参数、角色/模型覆盖、云 reasoning 约束 |
| Web Search | webSearchEnabled, webSearchConfigured, webSearchEnabledForTurn, webSearchStatusMessage, webSearchTurnModeLabel, webSearchResearchMode, webSearchResearchModeLabel, webSearchResearchOverridden, webSearchProviderLabel | provider/turn/override/configured、状态信息，不允许缓存过期 |
| Vision | visionCapabilityLabel, visionCapabilityDetail, visionCapabilityReady | 当前模型能力、mmproj/实际加载、云多模态状态及设备兼容通用路径 |

ChatRouteInputs 的 13 个字段也逐项保留：offlineTranslationStatus / Installed / Installing / Downloading / DownloadProgress、assistantMemories、appMenuOpen、chatBackground、globalChatBackground、assistantChatBackground、sessionChatBackground、hasActiveChatSession、chatBackgroundImporting。

generationParamsForChatModel 的函数身份并不能代表其返回值永不变化。投影应接收真正的参数值或受 owner 管理的版本输入，避免 remember 一个旧闭包导致模型参数不刷新。

### 8.3 缓存细节

- history 第一步缓存展示行，key 包含该行真正展示的值；单个消息尾部增长不应重新解析所有角色 JSON。
- updatedAtMillis 是行值之一，当前每次流式更新可能变化；不得为了让 history key 恒定而冻结业务时间或擅改排序。
- DateFormat 不跨线程共享可变实例。可在单一投影调用内复用，或改用经等值测试的线程安全格式化方案；必须处理 locale/timezone 改变。
- sessionPresentations 也需要真实依赖，不只是 remember 完整 chatSessions。优先逐 session 复用角色 presentation，而不是重算全表。
- assistants 的 paramsJson 依赖 fallback state.params；即使 JSON 没变，fallback 变化仍可能改变结果。
- worldbook 依赖当前 assistant/session scope；knowledge 依赖 count/importing/selected；不能只用列表身份。
- JSON 导出应按实体真实版本缓存，或只在用户导出时生成；若字段是公开 UI 合同，先保留其可用性，再单独迁移调用者。
- 映射缓存上限和生命周期明确，不把每次 streaming version 全部留在 Map 里。

### 8.4 等值与失效测试

新增参考测试保留原实现为测试 oracle，或先采集经过审核的完整输出 fixture。测试不得从新实现再次生成 expected。

对每个 dependency manifest 条目：选择能触发其真实语义影响的 fixture，仅改变该输入，与旧参考输出比较并验证必要失效。格式舍入、等义JSON或排序不变可能导致重算后输出仍相等，这是合法情况。仅改变无关高频输入时，断言无关投影不重新执行或复用同一已验证结果。功能值与执行次数分别断言。

不把“catalog 一整轮只执行一次”“历史一整轮最多两次”作为无条件门槛。真实输入改变时必须重新计算。

## 9. 执行顺序、文件所有权与切片大小

### 9.1 依赖图

    G00 基线/依赖表/测量设施
      ├─ P01 高频 stats 展示边界
      ├─ P02 等值投影与行复用 ─ P03 actions / Route
      ├─ P04 消息身份 ─ P05 滚动/输入栏/inset
      ├─ P06a Markdown 等值快赢 ─ P06b 可选真实增量
      ├─ P07 图片/HTML 有界异步
      ├─ P08 页面可见性与采样
      └─ V01 视觉契约提取 ─ V02 状态/可访问性 ─ V03 启动/系统栏
    X01 跨模块检查 + Q01 性能/profile/CI贯穿上述阶段
    Q02 独立总验收，所有必选门禁满足后结项

P02、P03、P05 均会触碰聊天装配或页面，不得让多个 agent 同时修改相同文件。G00 的读和纯 fixture 可以并行；Gradle 必须串行。

### 9.2 所有权表

| 责任人/agent 类型 | 文件范围 | 约束 |
|---|---|---|
| 集成 owner | MainActivity、MainChatScreenActions、ChatUiStateMapping、构建/runner | 所有跨域装配由一个人串行集成 |
| Projection worker | History/ModelCatalog/ImageRuntimeMapping 和新增纯投影/测试 | 不改 authority/持久化/取消 |
| Chat interaction worker | ChatRoute/ViewModel、MessageList、ChatScreen、composer | 每次仅一个子切片，避免 Route 与 scroll 同时改 |
| Rich content worker | Markdown、HTML、ImageAssetIoSupport 与相应测试 | 不改原文/导出协议，不另写平行解析器 |
| Visual QA worker | token 清单、fixture、截图与语义测试 | 不自作主张改色、字体和布局 |
| Perf/QA worker | benchmark/profile 测试、证据归档 | 不暴露正式注入入口，不伪造统计 |

所有 worker 接单时必须知道：你不是唯一修改者；保留他人改动，发现冲突交集成 owner 协调，不用 reset/checkout 覆盖整个文件。

### 9.3 每个切片的完成定义

有界生产改动 → 相关真实调用者已接通 → 编译/针对性测试 → 非空测试报告 → 必要实机/截图 → before/after 数据 → 独立审查 → 清楚的回退点。

只创建未调用的组件、给测试造一个不经过生产链路的替身、更新 golden 让它变绿、把测试数量减少，都不算完成。

以下每个工作包的“新增”只是实施指令。**本次只写文档，不创建这些生产文件或测试。**

### 9.4 排程必须使用的完整前置表

上方图是概要，真正派单以此表与工作包正文约束的并集为准；不能只看图就同时开改共享文件。

| 切片 | 完整前置 | 可并行条件 |
|---|---|---|
| G00 | 无 | 只读/fixture可拆；构建/真机采样串行 |
| P01 | G00数据流与全部stats消费者清单 | 不与P02同时修改mapping装配 |
| P02a/b/c | G00依赖manifest、参考fixture | 纯文件不重叠可拆；MainActivity由集成owner接 |
| P02d/e/f/g | 前一域等值通过，相关依赖manifest完成 | 不把部分等值推断为全字段等值 |
| P03 | P02的装配接口固定、G00 action清单 | 与P05不能同时改ChatScreen/Route装配 |
| P04 | G00消息ID/关联/intent消费者清单 | 与Markdown可并行只读；列表接线串行 |
| P05 | P04、G00几何/系统基线 | 单一owner；先意图再inset两片 |
| P06a | G00 parser corpus、P04身份 | 不与P07同时改同一Bubble文件 |
| P06b | P06a通过且仍有实测瓶颈 | 独立高风险，非必选 |
| P07 | G00 decode/HTML账本、P04身份 | 与P06重叠文件由owner串行合并 |
| P08 | G00页面可见性模型 | MainActivity与其他集成改动串行 |
| V01 | G00有效主题值和截图 | 先只抽token；不与行为改动混片 |
| V02 | P03、P04、V01；涉及输入还需P05 | 小入口/语义可独立派单 |
| V03 | G00、P05 viewport合同、V01 | 系统栏/启动/动画拆片 |
| X01 | G00、P08可见性合同、V01 | 不同feature可并行，构建串行 |
| Q01 | G00可运行benchmark入口 | 测量贯穿，不提前替所有片签PASS |
| Q02 | 所有必选片关闭，未通过可选项明确排除 | 独立总验收，保留未测矩阵 |
| H01 | 上层优化完成后仍有权威写瓶颈证据 | 独立架构项目，非必选 |

## 10. G00：建立基线、依赖清单与真实测量入口

**优先级 P0；依赖：无；所有权：集成 owner + QA；风险：低，但测试注入边界需审查。**

**生产入口：** MainActivity、buildChatUiState、ChatGenerationUiPublisher、ChatStreamAccumulator、ChatRoute、ChatMessageListSection。构建入口为 app/build.gradle.kts、settings.gradle.kts、gradle/libs.versions.toml。

**拟新增产物：** docs/proposals/audit/v9/BASELINE.md、PROJECTION-DEPENDENCIES.md、VISUAL-CONTRACT.md、SCENARIOS.md；内部 UI benchmark 模块与串行 runner 的具体路径在发现阶段登记，默认建议 benchmarks/ui-macro、scripts/verify-ui-quality.ps1。

实施步骤：

1. 保存 git status、HEAD、已有 dirty/untracked 列表，不改动它们。提取真实构建环境和当前源输入指纹，后续每份证据都绑定该指纹。
2. 逐字段读取 ChatScreenHeadTypes.ChatUiState 和 ChatRouteInputs，填满第 8 节 dependency manifest。每行包含输出字段、源符号、所有读取、fallback、失效键、调用者、对应测试。
3. 梳理 state 写入和消费者：文本、stats、phase、输入、目录、session、Room、API。记录发布频率，不把 StateFlow emission 当成实际 composition 数。
4. 创建固定的纯文本、富文本、历史、图片、工具场景。动态时间、随机 UUID、网络内容使用测试受控输入，fixture 自身带 hash。
5. 在独立诊断开关下加入 trace section/计数，范围包括 stream commit、mapping 子域、Markdown 源解析/块解析/inline、HTML、decode、scroll request。计数器线程安全且低开销。
6. 诊断符号要让 debug、benchmark、release 都能解析：可采用相同接口的 no-op 实现或构建期常量控制。main 引用仅存在于 debug 的类会导致 release 编译失败，禁止这种接法。
7. 增加受 property 控制的 Compose compiler reports/metrics。覆盖 app、feature/chat、feature/settings、feature/modelhub、feature/agent、core/designsystem 等实际包含 composable 的模块；生成 release 报告并记录 strong skipping 状态。
8. 稳定性报告只用于寻找热点，不先新增 stability config。如果确需配置，使用当前插件支持的格式和 DSL 接线；注释用独立的 // 行，不用 # 或行尾注释。
9. 实现内部 benchmark variant、测试进程和完整 runner；检查 manifests 和 release APK，确认 fixture 注入没有进入正式可导出表面。
10. 定义 TTFD：用户所需最小会话/输入状态恢复、当前入口可操作时上报，而不是等模型下载或大型模型加载。使用当前 Activity/Compose 的 reportFullyDrawn/ReportDrawn 机制时先审计“一次上报”与超时/失败就绪条件。
11. 跑 baseline 并保存原始 trace、截图、录像、测试 XML；在 BASELINE.md 中分开写 observed、not-run 和 proposed target。

**验收：** dependency manifest 无漏字段；至少一条确定性场景经真实 production UI 链路跑通；冷/暖启动和输出/手势场景可重复；正式变体无测试后门；构建锁生效。

**禁止：** 宣称 compiler reports 证明 UI 已快、为测试删生产功能、用空页面降低 TTFD、在报告中放用户对话/密钥/完整私有 URI。

**回退：** 独立移除诊断/benchmark 接线，不碰业务链路；保留采集证据。未完成 G00 可以做纯静态整理，但不得开始宣称收益。

## 11. P01：收窄高频统计对 UI 的影响

**优先级 P1；依赖：G00 数据流；所有权：publisher/集成 owner；风险：中。**

**真实文件：** ChatGenerationUiPublisher、ChatExecutionOperations、ChatUiStateMapping、ChatDeviceStatusScreens、ChatComposerStatusViews。

目标是让数值刷新不牵动无关目录和富内容。文本已存在 75ms/2048 字符发布机制，不重复增加一层文本延迟。

实施步骤：

1. 先枚举 RuntimeStats 的所有消费字段。把用于业务路由/能力判断的 backend、readiness 等与仅显示的耗时、速率、内存数值分开。
2. 第一切片只在 UI 消费边界建立拟新增 ChatRuntimeTelemetryUi 投影；业务统计、ReplyGenerationMetrics 和 terminal 数据保持完整。
3. 仅对连续数值显示尝试 leading + latest-trailing 合流，初始评估窗口 100ms。phase、error、取消、readiness、API 状态和持久化进度变化立即送达；不能全部 sample 一个大 state。
4. run/session 变化清理旧 pending；trailing 任务必须携带身份并在提交时复核。Done/Stop/Error 提交最终可见数值并取消延迟任务；空 pending 也不能跳过终态语义。
5. 文本不通过本 gate，copy/export/保存不读取降频的展示统计或正文。关键无障碍状态变化不被 gate 隐藏。
6. 只有确认全量统计写入本身是瓶颈，才另做 publisher 层合并；先检查 API、性能报告、调优和错误诊断消费者，保留完整 metrics 的独立权威通道。
7. 对比纯 stats 流、文本+stats、backend切换、零文本完成、快速 Stop/新 run，核实没有旧尾值回闪。

**拟新增测试：** ChatTelemetryPublicationTest，使用虚拟时间验证 leading、latest trailing、terminal、取消、identity；不使用真实 sleep 形成脆弱测试。

**验收：** 无关目录在仅展示数值变化时不重算；最终指标正确；输入/停止反馈不变慢；对外 API 和最终 metrics 等值。报告前后更新数、CPU/分配与帧表现，不要求 emission 数等于重组数。

**回退：** 移除展示合流适配器即可恢复原显示频率；不涉及消息保存格式。

## 12. P02：投影拆分、轻量历史与未变条目复用

**优先级 P1；依赖：G00 manifest；所有权：Projection worker；风险：中高，主要是漏依赖。**

**真实文件：** MainActivity、ChatUiStateMapping、ChatUiStateHistoryMapping、ChatUiStateModelCatalogMapping、ChatUiStateImageRuntimeMapping、ChatExportNaming、ChatSessionPersistenceMapping。

**拟新增：** app 同包下 ChatProjectionInputs.kt、ChatUiProjector.kt、ChatHistoryRowProjector.kt；按真实需要分文件，不创建只有转发的多层工厂。

子切片顺序：

1. **P02a 低风险快赢。** 将 ChatExportNaming 中重复构建的相同正则提升为不可变预编译常量；保留匹配顺序、Unicode、文件命名清理和长度规则。DateFormat 在一次 history 映射内复用，不跨线程共享。
2. **P02b history。** 输入仅含展示行需要的值和 presentation；缓存未变行。若 updatedAt 仍变化，允许该行更新；不能冻结时间戳伪造缓存命中。
3. **P02c presentation。** 按 session/assistant 实际快照复用名称、summary、归属，而不是每次对完整消息作全表解析。先测内容依赖，不能仅按 sessionId 永久缓存。
4. **P02d assistants/worldbooks/knowledge/context。** 每域完成新旧等值后再接下一个。覆盖 fallback params、scope、count、importing、active summaries、undo。
5. **P02e model/image catalog。** 提取实际读取的 stats 子字段、backend、loaded、云配置和能力输入；生图 loading/empty/unsupported 分开，继续允许通用兼容路径。
6. **P02f image runtime/library/jobs/files。** revision、preparedImageUi、元数据、权限/备份状态逐项接通，不把图库更新和消息输出绑在一起。
7. **P02g 组装。** 高频 message/input/generation 用当前值；低频目录复用已验证结果。buildChatUiState 可先作为兼容门面，待所有调用者迁移后才清理。
8. 每个域接入时执行“只变一个依赖”的测试；错误时只回退该域，不一口气重写 MainActivity。

**标题与排序边界：** 自动标题可缓存，但首条 user 内容/附件/编辑/导入变化必须失效；manualTitle 永远保留。sortedForHistory 以当前比较器为准，若优化为局部插入，必须对 pinned、updatedAt、相等值和所有 tie-breaker 做等价验证。不用“活跃会话永远第一”替代原规则。

**拟新增测试：** ChatProjectionEquivalenceTest、ChatProjectionDependencyInvalidationTest、ChatHistoryRowReuseTest、ChatTitleCacheInvalidationTest。保留现有 ChatExportNamingTest、ChatHistoryPersistenceBoundsTest 等。

**验收：** 第 8 节全部字段/RouteInputs 有证据；无关高频值变化不会触发不依赖它们的昂贵工作；历史选择/重命名/置顶/参数/角色导入即时正确；T01–T12 不受损。缓存总量有界，session 删除可释放缓存。

**回退：** 原门面按域恢复委托；不迁移 Room schema、不改变消息权威数据。

## 13. P03：稳定动作与同帧 Route 输入

**优先级 P1；依赖：P02 装配边界；所有权：集成 owner；风险：高，旧闭包会悄悄执行错误操作。**

**真实文件：** MainChatScreenActions、MainActivity、ChatRoute、ChatViewModel、ChatScreenActions。

实施步骤：

1. 分类 actions：业务 owner 方法、宿主 launcher/菜单 setter、需要当前状态校验的 intent、nullable capability。生成清单，保留每个公开动作和可空性。
2. mainChatScreenActions 中 onGenerateImagePrompt 的 localImageLoras 校验改为 intent 执行时由真实 owner 取得当前快照；完整保留选择、格式、模型兼容性和事务校验。不得固定初始 state，也不得把校验搬到纯 UI 后遗漏 API 路径。
3. 稳定容器只记忆真正稳定的 owner。宿主回调使用正确 remember key 或 rememberUpdatedState；不能 remember 一个捕获初始 sink/launcher 的 lambda。
4. 当前 ChatRoute 同帧显示直接使用 state 与 inputs 参数。不要通过在 SideEffect 后才更新的普通 holder.inputs 反读当前画面。
5. 若保留 ChatViewModel 作为事件 holder：在成功 composition 后发布最新 capabilities；动作 wrapper 在触发时读取最新已提交能力。constructor 初始值合法，后续提交不在 composition 执行业务动作。
6. nullable capability 的可用/不可用是 UI 语义。当帧 action availability shape 必须直接由当前 actions/capabilities 参数计算，并作为必要的 remember key；不能从 SideEffect 才更新的 holder.screenActions 普通属性反读当前可用性。稳定 wrapper 只负责在事件触发时读取最新已提交 delegate。availability shape 改变时允许必要的 container 更新；禁止用永久非空 wrapper 伪装原本不存在的能力。
7. 不使用 remember(state) 重建 holder，也不再 collect 一份相同 StateFlow 给页面制造额外延迟。StateFlow 发布与 Compose Snapshot 不是同一个概念，但 composition 的外部副作用仍应消除。
8. 检查 lifecycle/recreation：当前背景、菜单、翻译进度、回调替换都正确；holder 不再复制业务 authority。

**拟新增测试：** ChatRouteCurrentInputTest、ChatActionLatestCapabilityTest、ChatActionAvailabilityTest。测试仅改变一个输入并完成一次 composition，不靠下一次 messages 更新“帮忙刷新”。

最小回归场景：只改背景、只打开菜单、只改翻译安装进度、只替换宿主回调、LoRA 导入/删除后点击、nullable capability 从有到无及从无到有、切会话后执行旧菜单动作。一次 composition 测试同时断言按钮显示/启用状态和点击分发对象，不能只测 holder 赋值。

**验收：** 旧回调不再收到新操作；当前输入一次提交即显示；稳定输入下 actions 不无意义重建；业务校验/请求身份完整。不能用“恰好三个参数”或反射编译后参数个数验收。

**回退：** 将 Route 与 action 稳定化作为可独立回退的两个提交/补丁；若当前输入正确性无法证明，保留原路径，不用延迟显示换取计数好看。

## 14. P04：消息身份、行输入和延迟动作安全

**优先级 P1；依赖：G00；所有权：Chat interaction worker；风险：中高。**

**真实文件：** ChatMessageListSection、ChatMessageBubbleScreens、ChatBubbleMessageBlocks、ChatScreenActions、业务删除/编辑/重生成 intent 接收方。ChatMessage 领域定义实际位于 core/inference-contract，package 仍为 com.muyuchat.core.engine。

实施步骤：

1. 用现有稳定 message.id 及 conversation identity 构成行 key；key 需要满足 Lazy/saveable 约束且无拼接碰撞。不能把 index、正文 hash、时间/角色组合当作恒定身份。
2. 先审计导入/旧数据的空或重复 ID。不能在 composition 内随机生成/补写 ID；如需规范化，放在明确的导入/存储边界，另有兼容和迁移测试。
3. 建立拟新增 ChatMessageRowUi 与独立稳定 intents，含该行实际需要的 message、关联 image/job、角色标志、选择/可见语义。不要为了稳定性把整个 ChatUiState 塞进去。
4. image request/job 的关联 map、characterCard presence 等按真实版本在列表外构建。只改变一个 job 时对应行及时更新；避免每行每次扫描全表。
5. 删除、复制、菜单、重生成等延迟动作捕获 conversationId + messageId；在权威边界重新定位，检查当前会话与权限。已删除/已切会话时安全拒绝，绝不对“同一 index 的新消息”执行。
6. 如既有外部接口要求 index，保留内部适配边界，在执行时解析 ID；不要求一次破坏所有 API 签名。
7. 本地展开、代码横向滚动、选择状态和图片 remember 绑定稳定身份；会话切换不串行状态。

**拟新增测试：** MessageStableIdentityTest、MessageIntentIdentityTest、MessageRowAssociationTest。

**验收：** prepend/delete/reorder、相同 timestamp、相同正文、多角色、会话切换、旧弹出菜单均正确。固定“只变末条正文”场景中未变行工作量显著收窄；主题/字体/选中等合法变化仍允许更新。

**回退：** 行模型/关联缓存可以独立回退；ID 语义修复保留，除非证据表明它与旧数据不兼容。

## 15. P05：滚动意图、输入栏和 Insets 的统一合同

**优先级 P0 体验；依赖：P04、G00 几何基线；所有权：单一 Chat interaction worker；风险：高。**

**真实文件：** ChatScreen、ChatMessageListSection、ChatComposerScreens、MainActivity、McaTheme、AndroidManifest。拟新增 ChatScrollCoordinator.kt、ChatViewportInsets.kt；两者不持有业务消息 authority。

### 15.1 明确意图状态

拟新增页面局部 FollowMode：Following、DetachedByUser、Restoring。另用有身份的一次性 JumpToLatest 请求表示显式跳转，不把它伪装成永久业务状态。

| 事件 | 当前状态 | 行为/下一状态 |
|---|---|---|
| 首次进入新空会话或用户发送新消息 | 非恢复状态 | 按当前产品行为到最新；Following |
| 用户拖动/滚轮等主动浏览 | Following | 立即取消程序滚动；DetachedByUser |
| 正文增长、图片重测、IME 变化 | Following | 保持 Following，合并几何纠偏；不因瞬时离底变 Detached |
| 正文增长、图片重测、IME 变化 | DetachedByUser | 保持阅读锚点，不自动到底 |
| 用户主动回到末尾附近 | DetachedByUser | 手势结束且满足明确阈值时可重新 Following |
| 点击既有“最新消息”入口 | 任意可显示状态 | 取消旧任务，发一次有身份的到底请求，完成后 Following |
| 恢复历史会话滚动位置 | 任意 | Restoring；按 message key + offset 恢复后再决定模式 |
| 切会话/删除当前锚点/屏幕销毁 | 任意 | 取消旧任务；不能让旧 scroll coroutine 操作新列表 |

几何只回答“需要移动多少”，用户事件回答“应该跟随吗”。不能用同一个瞬时底边条件同时做这两件事。

### 15.2 只有一个程序滚动执行者

1. 删除旧 512 字符桶的强制 scrollToItem 路径前，先建立行为测试，确认唯一新 owner 接管。
2. scroll coordinator 收到带 session/run/message identity 的请求；同一显示帧/布局周期合并为最新需求，不为每个 chunk 排队一段动画。
3. 用户手势具有更高优先级；按当前 Compose scroll mutation 协调机制取消程序滚动，不能与 animateScroll 同时争夺。isScrollInProgress 不能区分程序和用户来源；只将列表实际消费的纵向手势、滚轮、可访问滚动识别为用户浏览。任意 pointerDown、代码块横滚、文字选择不应误解除跟随，用户 fling 仍有优先权。
4. 新布局后重新读取有效 geometry；已经完成、已经到底、旧 identity、屏幕不可见时直接结束。避免 snapshotFlow → scroll → layout → snapshotFlow 的无效自激循环。
5. 末条比视口高时 scrollToItem(lastIndex) 只把末项顶部对齐，并不保证看到尾部。实现必须处理巨大单项和附加 browser card，使用真实末端 sentinel 或经过边界测试的 tail 定位策略。
6. sentinel 不改变正常视觉留白；key 和 item index 规则明确，empty list/browser-only 时合法。
7. 用户 detached 时以首个可见消息 key + offset 为阅读锚点，优先利用 Lazy 稳定 key 的保持机制；只有真实非用户结构/尺寸变化才做一次低优先级、可取消纠偏。不得每帧反复恢复旧 offset；用户继续浏览立即取消纠偏并更新锚点。prepend、删除、异步图片测量时不能随意跳到 index0。
8. 不强制每个字符都做平滑动画。最终策略以不打断、无抖动、可取消和帧预算为准，现有曲线不因“高级”而统一换 spring。
9. 用户打断或旧identity造成的 scroll 子任务取消，不得终止整个 coordinator 的后续请求处理；遵守结构化取消，不吞页面销毁的 CancellationException，也不在 finally 重新到底。测试“打断→继续阅读→点击最新→恢复跟随”的完整序列。

### 15.3 一个底部占位来源

当前列表 bottom padding 固定118dp；另一处 bottomChromeHeight 将实测底部区域高度换算为dp后 coerceAtLeast(120.dp)。这是计算下界，不是输入框真实最小高度，只读会话提示也会写入该测量值。二者职责、是否含padding/IME及只读模式须一起核对。新 contract 先定义以下量，不能直接拼几个 padding：

| 量 | 单位/坐标 | 唯一 owner |
|---|---|---|
| Scaffold innerPadding | dp，父布局已经应用的系统/内容边界 | app shell |
| status/navigation safe area | 逻辑 inset，应用后应消费 | shell 或明确页面，二选一 |
| IME inset | 由实际窗口 resize/edge-to-edge 策略决定 | 选定的 composer/page owner |
| composer overlap | 实际盖在消息视口上的可见高度，不含已由父应用的部分 | composer 测量/viewport contract |
| list contentPadding.bottom | 为 overlay 预留且只预留一次 | message viewport |
| jump/overlay bottom offset | 与上述同一有效占位，不能独立魔数 | viewport contract |

在 baseline 中画出每层布局矩形并记录是否 consume。先证明重复/遗漏再修改。Scaffold safeDrawing、header.statusBarsPadding、父 padding 如果同时应用又不消费可能重复；这不是随意删除某层的依据。

adjustResize 与 imePadding 在一些版本/厂商组合下承担不同职责；当前 MIUI 兼容注释必须保留，除非有覆盖等价证据。runtime 和 Manifest 都有策略时需要一起审计。imeNestedScroll 的平台行为 API30+，低 API 保留可用降级。

### 15.4 输入栏连续性

- 保留输入 shell 的 RoundedCornerShape(36.dp) 圆角参数、文字指标、附件布局和可见尺寸；36dp不是高度、按钮直径或新增间距。
- input 从一行到多行、附件加入/移除、状态提示出现时，仅影响必要的 composer 高度；不重建文本编辑状态，不丢 selection/composing region。
- 输入事件不经过低频 telemetry gate；IME 中文组合输入、emoji/ZWJ、粘贴、撤销、硬件键盘各有实机用例。
- send/stop 形态仍使用当前设计，状态切换以真实 owner 为准；重复点击不重复发送/取消，无虚假“完成”。
- 读旧消息时弹键盘仍保留阅读意图；新消息发送时按既有产品语义重新跟随。

### 15.5 测试与验收

**拟新增：** ChatFollowIntentStateTest、ChatScrollGeometryTest、ChatViewportInsetsTest、ChatComposerContinuityTest；生产仪器测试补入现有 ProductionImeBackTest/ProductionUiSessionTest 或独立生产路径测试。

必须覆盖：短会话、末条超过2屏、300条历史、附加浏览器卡、流式图片/异步图片重测、键盘开关/切换、输入1→多行、字体1.0/1.3/2.0、横屏、小屏、split-screen、prepend/delete、恢复历史锚点、手势中终态到达。

系统组合至少登记 API26/29/30/35/36、手势/三键导航和实际厂商设备；做不到的格子写 NOT_RUN，不声称全兼容。

**门禁：** 用户 detach 后连续输出不拉回；Following 下内容增长不自行解除；输入栏不遮最后内容；不存在重复底部空白；手势能立即打断；切会话无旧任务跳转；无可重复振荡/无效滚动循环。

**回退：** coordinator 与 inset 变更分两个切片保存；不得为快速回退恢复“任何更新都强制到底”。出现几何问题先回退对应 inset 切片，保留用户意图保护。

## 16. P06：富文本保持原语义，先消除重复工作

**优先级 P1；依赖：G00、P04 身份；所有权：Rich content worker；风险：P06a 中，P06b 高。**

**真实文件：** ChatMarkdownSource、ChatMarkdownBlockParser、ChatMarkdownBlockRenderers、ChatMarkdownRenderSupport、ChatBubbleMessageBlocks；相关复制、导出调用方。

### 16.1 P06a 必选：等值快赢

1. 建立旧 parser 的终态和逐前缀输出 corpus，覆盖当前实际支持的语法。先验证现状，不把这次性能工作变成切换 Markdown 标准或换整个渲染库。
2. 当 content 与 fullContent 相同，不重复解析同一字符串；将源解析结果在一次转换内复用。
3. fullContent 与显示片段不同时，拟新增 ParsedChatDocument 保存完整源块及 source range；显示块根据真实范围关联完整代码复制内容。不能依赖“第几个代码块”的脆弱错位，也不能丢掉现有 copyCode 行为。
4. source range 使用明确 UTF-16 offset 与原始字符串版本；显示分页在安全边界处理 surrogate/grapheme，不能切半 emoji。范围用于定位原文，不能用规范化字符串的 offset 去截原始字符串。
5. buildInlineMarkdown 对 URL_PATTERN.find 的重复调用先缓存单次结果。再以基准证明是否存在随字符推进反复扫描剩余后缀的超线性路径；若存在，改为单次 match iterator/单调游标，同时保留 code/link/escape 优先级。
6. 正则常量可预编译，不能在每个字符分支构造。不要仅把 Regex 放 remember 后就宣布整体 O(n)。
7. 解析大输入可在受控 dispatcher 后台执行；UI 保留上一次合法结果，结果必须带 messageId + sourceVersion 校验。不能把同步解析简单包在 withContext 后允许过时结果覆盖新文本。
8. 最终正文、复制、选中、导出、持久化均取真实原文/完整数据。渲染用的临时容错不能写回消息。
9. 保留 16384 字符分页的产品行为，优化时明确“初始可见范围、加载更多、完整复制”的合同；不为了减少工作默认截掉正文或悄悄减少页大小。
10. 以独立解析结果和按块稳定身份复用未变块；不要在外层 LazyColumn 中嵌套一个无界同方向滚动列表。

### 16.2 P06b 有条件：真正增量解析

只有 P06a 完成后，长文场景仍有可定位 parser 瓶颈，才开始本切片。不能把它设为前面优化的必需依赖。

拟新增 IncrementalChatMarkdownParser / IncrementalDocumentState，至少包含：message identity、sourceVersion、原文长度/版本、已确认 source blocks、可回退的 safe checkpoint、语法 continuation、未提交尾部、完整代码范围、缓存预算。

必须先书面定义的算法条件：

- 确认 append-only；发生替换、缩短、编辑、重新生成、messageId 改变时取消旧任务并全量回退。
- checkpoint 不能靠“最后空行”或数 ```/**。列表、表格、围栏长度、波浪线围栏、缩进、转义、未闭合片段都可能跨越边界。
- 能证明不受新后缀影响的块才进入 committed prefix；不能证明就把 checkpoint 前移或对该范围重解析。
- 仅 tail 变更时复用 committed blocks；prefix String 每次增长后重新 remember 不是增量。
- 解析 continuation 与当前 parser 语义一致；当前不支持的语法不借机半实现。
- pathological 长单块没有安全断点时允许全量回退；不承诺所有输入固定 O(delta)。对大输入保持有界排队与版本取消。
- terminal 必须进行与全量 parser 的等价复核，或采用已经通过差分证明的 finalization；不能留下伪闭合语法。
- 单消息只保留必要的当前/上一版，不缓存每个 token 的文档；所有消息缓存按字节/块数/LRU 有上限。

**禁止：** 引用未实现的 findSafeParagraphBoundary 当成算法完成；用人工补结束符改变原文；新建简化 regex parser 取代现有围栏解析；逐帧 substring 再重新 Markdown 全量解析。

### 16.3 测试与验收

沿用 CodeFormattingTest、MarkdownListFormattingTest、MarkdownMathTextTest、CharacterHtmlPreviewTest、LongTextRenderPolicyTest。拟新增 MarkdownParseEquivalenceTest、MarkdownIncrementalDifferentialTest、MarkdownSourceRangeTest、InlineMarkdownComplexityBenchmark。

corpus 至少包括：中文/英文混排、CRLF/LF、emoji/ZWJ/组合字符、反引号与波浪线围栏、长围栏、围栏内伪围栏、未闭合代码、文件名/语言标记、缩进代码、行内反引号、转义、链接、长无空格串、列表、表格、引用、数学、HTML、跨16384边界代码。

差分方式：每个 corpus 用多种 chunk 切法喂入；同一原文前缀的渲染结构与参考实现等价；终态块、copyCode、复制文本、导出文本完全满足原合同。使用生成式随机分块时固定 seed 并保存失败样本。

**门禁：** terminal 等价；无闪回旧版本；复制完整代码；复杂输入无新增不可取消长任务；parse/分配明显改善且帧数据不退化。若 P06b 未证明正确，交付 P06a，P06b 标记未通过，不能以“已增量”结项。

**回退：** incremental path 在内部可独立关闭并回到已优化的全量 parser；不回滚已证明正确的重复解析消除。

## 17. P07：图片与 HTML 的有界异步、去重和失效

**优先级 P1；依赖：P04、G00；所有权：Rich content worker；风险：中高。**

**真实文件：** ImageAssetIoSupport、ChatBubbleMessageBlocks、ChatBackgroundScreens、图集/图片工作区的实际 rememberChatImageBitmap 调用者。

### 17.1 图片缓存合同

当前已经使用两阶段 decode + inSampleSize，不能再把“新增采样”当作从零提升。真正要评估的是重复解码、离屏释放、URI/revision 失效和峰值内存。

拟新增 ChatImageRepository / ImageDecodeKey / DecodeBudget，放在合适的 feature/基础设施边界；不要为了一个页面建立贯穿全 app 的通用资源框架。

实施步骤：

1. 清点所有 decode 调用者、请求尺寸和 revision 来源；先记录一次滚动/返回的实际 decode 数与峰值。
2. key 至少包括资源身份、revision、目标像素桶、必要的旋转/变换和解码配置。相同 URI 被覆盖而 revision 改变时不能返回旧图。
3. 目标尺寸按 density/实际 viewport 计算；保留足够清晰度，不降低分享、导出、保存原图质量。大图预览与原图 IO 是不同路径。
4. in-flight 相同 key 去重；某个消费者离开只释放自己的订阅，不能取消仍有消费者的 decode。全部离开可取消等待/丢弃过时结果。
5. decode 队列有并发上限，初始从1–2个后台任务测起；图片预取低于可见图片优先级，不能抢占文本和输入主线程。
6. 解码本身若不可协作取消，完成后仍核对版本和订阅，不发布旧结果；不要无限启动下一批任务。
7. 成功项进入按 allocationByteCount 计费的 LRU；失败项是否短期缓存必须有过期/手动重试机制，权限恢复后不能永久显示失败。
8. 活跃显示引用与可驱逐缓存分开。不能对仍在 Compose/Canvas/GPU 使用的 Bitmap 调 recycle；优先安全引用释放，由平台管理回收。
9. 响应 trim memory、切后台、会话/图库删除；清 LRU 不等于回收所有 GPU/native 内存，报告中分开说明。
10. URI 授权沿用现有生命周期，deferGenerationImageGrantRelease 语义不能被缓存层提前释放；日志只记不敏感资源标识。

初始预算策略必须在 G00 的内存账本中确定。例如将 decoded UI cache 控制在 min(可用应用堆预算的约1/16，32MiB) 内作为评估起点，同时统计 active 与 in-flight 额外峰值；**这个数字是待测配置，不是所有设备硬编码真理，也不是准入条件。**模型权重共享内存紧张时减缓存，不能关掉功能。

### 17.2 HTML 解析合同

1. 以 messageId + sourceVersion + 当前解析策略版本为 key；只对需要 HTML 预览的内容解析，不在每个普通文本前缀都运行 Jsoup。
2. 当前 Jsoup 同步调用可能不响应协程取消；使用有界 worker，并在结果提交时丢弃旧版本。collectLatest 只能取消协程等待，不能自动证明 CPU 工作已停止。
3. 保留现有字符卡、HTML/链接/脚本处理与预览行为，不借性能重构更改内容权限或注入策略。
4. 一个长文/多条 HTML 不得创建无界 Default 任务；缓存按字符/文档大小收费，不只按条数。
5. 加载中沿用稳定容器和上一份可显示结果；尺寸未知时不使用伪固定比例造成永久裁切，实际 remeasure 交给 P05 的意图合同。

### 17.3 测试与验收

**拟新增：** ImageDecodeDeduplicationTest、ImageRevisionInvalidationTest、ImageCacheLeaseTest、ImageDecodeBackpressureTest、HtmlLatestVersionTest。

实机场景：20张缩略图来回滚动10轮；同 URI 覆盖；大图预览/保存；权限撤销再恢复；切会话；后台/前台；trim memory；连续两版 HTML 快慢完成顺序反转。

**门禁：** 同 key 并发只解码一次；revision 更新必刷新；无显示中 recycle；无旧 HTML 覆盖新内容；峰值和稳定内存有账本；原图导出字节/质量不变。缓存没有命中收益时不因“旗舰一定要 cache”而保留复杂层。

**回退：** repository 缓存层可绕回原两阶段采样；版本校验和后台有界调度可独立保留。

## 18. P08：页面覆盖、生命周期与状态球采样

**优先级 P1；依赖：G00 可见性模型；所有权：跨页面集成 owner；风险：中。**

**真实文件：** MainActivity、ChatDeviceStatusScreens、SettingsRuntimeScreen、AgentScreen，以及实际日志/诊断轮询调用者。

实施步骤：

1. 明确 app foreground、页面可见、被覆盖、悬浮球折叠/展开四种不同信号。Activity RESUMED 不代表下层聊天可见。
2. host 提供页面可见性；页面只控制自己的采样/派生任务，不取消真实推理、下载、Local API 或后台保存。
3. 使用 androidx.lifecycle.compose.LocalLifecycleOwner 和 repeatOnLifecycle 管理前后台；直接用 lifecycle API 的模块显式声明对应依赖，避免长期依赖偶然的传递依赖。
4. compact 状态球仍需要内存标签、ring、警告和 active；保留与当前体验相当的必要采样。expanded 才增加温度/细项/时钟等额外成本。
5. expanded→compact 时取消额外 job，compact→expanded 立即刷新一份再进入轮询；app 回前台同理，避免先显示过期数值很久。
6. Settings RuntimeScreen 隐藏后停止页面私有 SystemLoadReader 轮询；高级 JSON 按完整 advancedJson 值缓存解析，不能只按 loaded model ID 缓存。
7. Agent benchmark history JSON/格式化按真实行值缓存；只更新某行时不序列化全量历史。
8. 明确 sampler scope/关闭点，确保重进页面不叠加第二个定时器。采样失败展示现有可恢复状态，不默默置零伪装正常。

**拟新增：** UiVisibilitySamplingTest、DeviceStatusCompactProjectionTest、RuntimeJsonInvalidationTest。

**验收：** 折叠球仍准确；覆盖/后台无页面无意义采样；展开/回前台及时刷新；无重复 jobs；真实后台任务和 API 不受影响。至少结合 ProductionFloatingStatusTest 走生产入口。

**回退：** 逐采样源恢复，避免一次性停掉所有 lifecycle 优化掩盖具体错误。

## 19. V01：等值 token 和组件契约，保持现有设计语言

**优先级 P1 质感基础；依赖：G00 截图/有效值；所有权：Visual QA + 集成；风险：低到中。**

**真实文件：** McaTheme、ChatColorTokens、ChatComposerScreens、ChatMessageBubbleScreens、ChatStatusBarHeader、现有 Markdown 和共享组件。

**拟新增：** core/designsystem 中适量 McaSpacing / McaRadii / McaMotion 等纯 token，或保留 feature 专属 token；名称根据实际复用决定，不创建一套无人使用的大型系统。

实施步骤：

1. 记录浅/深色下有效 ColorScheme 所有被使用角色、Typography、Shapes、disabled alpha、ripple/focus、elevation 与系统栏设置。把“显式值”和“库默认值”分开。
2. 记录输入 shell 的36dp圆角参数、用户气泡20/20/20/6dp圆角参数、助手透明全宽、当前18dp边距、头部各行文字指标等真实值。不要把圆角参数当组件高度；相关值以当前源码与实际渲染共同确认。
3. 只抽取重复、含义一致的值。同为14dp的两个组件不一定是同一语义 token；primary 的两种蓝也不要求合并。
4. 替换后逐页截图对照：尺寸、换行、对齐、颜色、阴影完全等值。若 token 类型或默认角色导致结果改变，先恢复旧有效值。
5. 共享组件只收口轮廓、状态、语义与交互，不把所有页面强行放进同一种卡片；保留当前产品密度。
6. 若发现暗色代码块/输入文字因错误 role 映射不可读，先确认具体复现与当前有效前景/背景。优先修正错误映射至既有 palette；涉及可见色变化单列 before/after，不夹在“等值抽取”里。
7. palette 在所有约束下无法同时满足某个可访问性目标时，明确记录未解决冲突，不偷偷加新色，也不谎报全部达标。

**验收：** 代表性页面 token 前后几何/排版一致；没有新 hex；自定义壁纸的 scrim/blur/scale 行为一致；不引入 Compose 依赖到平台中立领域 contract 只为加注解。

**回退：** 每个 token 簇可原值内联恢复；不修改存储主题偏好格式。

## 20. V02：旗舰质感的状态、反馈和可访问性闭环

**优先级 P0 质感；依赖：P03、P04、V01；所有权：Chat interaction + Visual QA；风险：中。**

### 20.1 视觉精度检查，均以现有设计为准

| 检查项 | 可执行修复 | 禁止的替代 |
|---|---|---|
| 文本基线/图标光学对齐 | 查实际 bounds/font metrics，修真实裁切或错误 modifier | 全局调字号/行高/图标尺寸 |
| 轮廓与阴影 | 消除意外二次裁切、错误层叠或异色缝 | 把所有阴影统一“轻一点” |
| 圆角连续性 | 内容 clip 与现有容器 shape 一致 | 全部统一成某个大圆角 |
| 分隔线/边框 | 正确设备像素对齐，保持当前颜色/厚度语义 | 到处新增描边或卡片 |
| 图片加载 | 稳定 key、消除旧图回闪、合适尺寸 | 用假图或降低最终图片质量 |
| 选中/禁用/按压 | 使用现有设计系统的明确状态和真实可用性 | 为了可点保留永远非空动作 |
| 键盘/焦点 | 返回优先收 IME；关闭弹层恢复合理焦点 | 一次返回把页面和键盘都关掉 |
| 生成中与完成 | 不闪空，不丢布局；最终内容立即完整 | 人工拖慢输出制造“丝滑” |

若改变 modifier 次序会改变合法既有像素，必须解释具体缺陷，不能把任意差异都叫修复。

### 20.2 搜索按钮和真实入口

ChatHistoryDrawer 已有 query 与筛选逻辑，搜索图标当前空点击。接入 FocusRequester 到既有输入，必要时滚动到该输入并显示键盘；不新造第二套搜索页面。

空 query、无结果、清除、中文组合输入、快速输入和关闭/重开抽屉都要验证。搜索大量历史时先采用按 query/数据版本计算并有界取消；只有实测卡顿才添加经过体验验证的输入 debounce，不能机械延迟所有输入。

品牌 IconButton 保留原标记，补准确的操作名称/role；装饰性图标保留空描述，避免 TalkBack 重复读。名称来自实际操作，例如打开应用菜单/会话，而不是笼统的“logo”。

### 20.3 动作状态合同

- 单击后下一个可用帧出现现有 pressed/busy 状态；异步任务的实际开始/失败/完成分别有真实反馈。
- 导入/下载/加载/生图不假造进度；未知总量用现有不定进度。100% 只在真正完成时显示。
- 发送/停止/重试/删除/导出有幂等或并发保护；不以全局禁用整页阻止合法浏览。
- 可取消任务能取消；不可立即完成的 native stop 显示准确状态，不把 UI 释放等同 native 已结束。
- 错误保留用户输入和可恢复内容；重试沿用正确 request identity，防止重复生成和错会话。
- 列表移除/菜单关闭后恢复合理焦点；不跳到无关的顶部控件。

### 20.4 无障碍和大字体

1. 48dp 触控范围审计，尤其圆形图标、copy、更多、关闭、搜索、附件、最新消息；避免重叠。
2. TalkBack 顺序按视觉和操作逻辑；状态按钮说明选中/展开/禁用；生成中不要逐 token live region 播报。
3. 标题/代码/链接/消息语义保持可选中、可复制；整条消息 merge semantics 不能吞掉代码复制等独立操作。
4. 1.0/1.3/2.0 fontScale 下检查裁切与功能可达性。维持默认字号；大字体适配确需不同排布时单列变更，不以关闭 font scaling 解决。
5. 系统动画缩放为0或辅助设置下，交互仍完整，无依赖动画结束才可取消的死锁。
6. 现有 ProductionImageSubmitAccessibilityTest 等继续跑；不要声称仓库原本完全没有无障碍。

**拟新增：** ChatControlSemanticsTest、DrawerSearchFocusTest、ChatFocusRestorationTest、ChatAsyncActionStateTest；优先行为测试，不为一个常量镜像写无意义单测。

**验收：** 无空点击入口；无缺名的重要按钮；焦点/忙碌/错误/取消状态齐全；截图保留现有设计；TalkBack 不重复/刷屏；触控命中与可见操作一致。

**回退：** 每个入口/状态独立回退，已修好的 accessible name 不随布局补丁一起撤销。

## 21. V03：启动、系统栏和页面转场衔接

**优先级 P1；依赖：G00、P05 viewport、V01；所有权：集成 owner；风险：中高。**

**真实文件：** AppStartupViewModel、MainActivity、McaTheme、styles.xml、Manifest、SmoothPage，以及各覆盖页面实际调用点。

实施步骤：

1. 先录冷启动、暖启动、浅/深切换、进后台恢复的高帧率视频；确认是否真实出现白闪/旧主题/首帧位置变化。
2. 检查 API31+ splash 和 API26–30 windowBackground；如需夜间资源，仅使用现有深色背景/品牌资产，保持启动语言。
3. 迁移和兼容修复当前已在 IO，不为压 TTID 直接后移到用户写入之后。区分必须在读取前完成的 migration 与可延后准备，给出依赖证明。
4. target36 edge-to-edge 按平台实际行为审计；没有显式 enableEdgeToEdge 调用不能直接判断无效。系统栏颜色设置与 icon appearance 设唯一 owner，避免 Activity 和 McaTheme 互相覆盖。
5. 页面挂载、overlay z-order、进入/退出、Back、IME 返回、predictive back 路径逐一追踪。SmoothRightToLeftPage 有自有关闭动画，不能因 visible=true 就删掉/重做共享原语。
6. 先保留当前 240/140/180ms 等实际曲线/时长；若存在打断后跳变、关不掉或点击穿透，修状态与完成回调，只在必要处调整具体行为。
7. 高频纯位移/透明度可在适用场景迁移 graphicsLayer/draw/offset lambda，尺寸、文字排版和 padding 仍可合法走 layout。不能要求“100% 动画不触发布局”。
8. 取消/反向手势不执行两次 onDismiss；覆盖页关闭后旧 UI 仍可操作且状态不丢失。动画缩放0、旋转、快速连续开关要可恢复。

**拟新增：** StartupThemeContinuityTest、PageDismissInterruptionTest、SystemBarOwnershipTest；优先生产仪器/录像，源代码字符串断言不能证明没有闪屏。

**验收：** 被确认的闪烁/遮挡消失；原本正确的启动图/转场风格保留；TTID/TTFD均有定义；迁移/恢复没有为了性能而失效；系统栏/IME矩阵无回归。

**回退：** 启动资源、系统栏 owner、动画中断修复分开；不把它们揉成难回退的大型 theme 重构。

## 22. X01：模型中心、设置和 Agent 的全前端收口

**优先级 P1/P2；依赖：G00、P08、V01；所有权：跨模块 worker；风险：中。**

主要入口：[ModelHubScreen](E:/MAC/feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/ModelHubScreen.kt)、[MarketAndDownloads](E:/MAC/feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/MarketAndDownloads.kt)、[SettingsScreens](E:/MAC/feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt)、[SettingsSearchScreen](E:/MAC/feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchScreen.kt)、[SettingsLogsPrivacyScreens](E:/MAC/feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt)、[LocalApiToolScreen](E:/MAC/feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiToolScreen.kt)、[AgentScreen](E:/MAC/feature/agent/src/main/java/com/muyuchat/feature/agent/AgentScreen.kt)。

### 22.1 模型中心/下载

1. 目录筛选、分类、当前选择和下载进度分开投影；一个下载更新不应重新构建全部不相关分类/格式化结果。
2. 长目录使用稳定 key、contentType 和必要的延迟布局；现有已经采用的能力先确认，不机械重写。
3. 下载行以真实 bytes/total/status 渲染，保留速度/剩余时间格式化语义和取消/恢复/校验。数字稳定更新不能通过虚假进度或提前100%实现。
4. loading、empty、filtered-empty、network-error、partial-success、resuming、verifying、failed 可区分；保留现有文案和按钮位置。
5. 未知设备/缺少 profile 只能提示或推荐通用包，不隐藏真实可尝试操作；失败来自具体格式、完整性、native load 或真实执行。
6. formatter 移动前检查 DownloadFormattingTest 通过 MarketAndDownloadsKt file facade/private 符号反射的合同。可保留兼容 wrapper，或显式迁移测试覆盖，不盲目改名造成测试失效。

### 22.2 设置/搜索/日志

1. 设置仍用现有分组、标题、图标、行高和间距；性能工作不趁机重排选项。
2. 搜索索引按设置结构/语言版本缓存；query 变化只搜索必要数据。结果点击能到真实目标，关闭后焦点与滚动恢复。
3. 运行页按 P08 处理。日志批量追加、展示裁剪与导出完整性分开；只按既有保留策略截展示，不丢诊断原始数据。
4. 大日志字符串拼接、JSON pretty print、隐私扫描先用 trace 确认成本；后台有界执行并校验版本。禁止在组合中同步扫描大文件。
5. 开关操作失败要回到真实值并显示既有错误反馈；不要先永久改 UI 再忽略保存失败。
6. 本地 API 配置、认证、自检、启动/停止真实状态保留；不为了测试增加无认证可控接口。

### 22.3 Agent/调优/诊断

1. 历史 JSON、benchmark 行格式化与实时运行态解耦；对相同实体/版本复用。
2. 轮询与页面可见性挂钩，但调优/推理真实 job 由业务 owner 控制，不随着离屏销毁。
3. 运行、排队、取消、恢复、失败、结果落盘状态可恢复且真实。不要把前端“取消按钮已返回”等同 native 任务已经结束。
4. 图表/摘要优先保留现有样式；数据点多时减少不可见绘制工作，不能擅自删除异常值或美化结果曲线。

**验收：** 各模块有同样的输入、反馈、键盘、返回、焦点质量；高频进度不牵动静态目录；未知设备功能可用性不被限制；原有黄金文案和格式化行为保留。

**回退：** 按模块和投影域回退，不能因一个下载行问题回滚整个主题系统。

## 23. Q01：Compiler reports、Macrobenchmark、Baseline Profiles 与 CI

**优先级 P1；依赖：G00；所有权：Perf/QA；风险：中，测错比没有数据更危险。**

### 23.1 Compose 稳定性审计

- 保存真实 release reports，不从 data class/List 推断每行必重组。
- 检查 skipped、restartable/skippable 与实际 runtime composition/layout/draw；发现热点再改数据/读取边界。
- stable config 每一条都要列：类型真实路径、嵌套可变性、集合是否原地修改、写入时复制规则、是否跨线程；只含 val 不是足够证明。
- 配置支持 * 和 **，但广域声明风险高。不能把整个 engine package 设稳定来“全绿”。
- 优先 UI 投影模型或经审计配置；不要给 core/inference-contract 引入 Compose 依赖只为添加 @Immutable。
- 参数数量、skippable 比例和“旧消息零重组”均不是最终质量门禁。

### 23.2 UI 宏基准

1. 在 G00 已隔离的模块中使用与 AGP 兼容的 AndroidX Benchmark/Baseline Profile 版本；先核对依赖和 task 列表，不把本文拟定 task 名当作已存在。
2. 目标非 debug，profileable；记录 R8/资源压缩和正式包差异。测试 APK 与目标包使用明确 variant/applicationId，避免操作真实用户数据。
3. Macrobenchmark 通过 UI Automator 跨进程操作目标 app，使用已验证的稳定 selector，而不是屏幕绝对坐标；不能直接用 Compose `onNodeWithTag` 控制另一个进程的目标。若将 Compose testTag 用作 `By.res`，必须在正确祖先启用 `testTagsAsResourceId`，并验证真实目标 UI 树可读取；也可使用已有稳定资源 ID 或准确的可访问名称。semantics 不只在测试 mock 屏幕存在。
4. 测量窗口包含真实输出、滚动、键盘与导航；fixture setup、安装和冷却不混进帧采样窗口。
5. API31+ 采 frameOverrunMs；更低 API 按受支持指标/trace 报告，不编造 overrun。
6. Perfetto 原始结果存储，报告指出主线程、RenderThread、GPU/合成、GC、binder/native 竞争来源。
7. 所有长时间重放都能超时退出并清理 fixture job；超时是失败，不能吞掉并写完成。

### 23.3 Baseline Profile

1. 优先覆盖冷启动到可交互、进入聊天、历史抽屉、输入/发送/停止、富文本滚动、模型中心和设置常用路径。
2. 使用当前 Baseline Profile 插件支持的生成变体及正式 release 合并处理链路，核对目标 app 包名和变体；不强行手写与 release 完全相同的 minified 生成变体，也不手工猜测混淆后的类名。生成与打包分别检查，最终确认经 R8 处理的正式 APK 包含有效 profile，且覆盖真实生产路径；不能只把文件放进目录就宣布生效。
3. 对比无 profile/有 profile 的受控编译模式，记录安装/CompilationMode；测试不同改动时不混用旧编译状态。
4. profile 不能代替减少重复解析、无界缓存或主线程阻塞；也不能用无关大段覆盖扩大文件冒充优化。
5. 真实模型冷加载、native 权重 IO 与前端启动分开归因，不能把 native 耗时都记为 Compose 问题。

before/after 必须使用同一 CompilationMode 和 profile 安装条件。无profile归因实验、有指定profile的出货代表实验、Full编译实验分别记录；不能用before无profile对after Full，然后把收益全部归给UI改动。内部harness专属路径不能代表正式profile覆盖，必须核查正式release合并产物和真实生产入口。

### 23.4 CI 分层

| 层级 | 内容 | 触发/限制 |
|---|---|---|
| 每切片 | compile + 有关行为单测 + dependency/格式合同 | 依据实际改动，不每次全仓所有测试 |
| 前端集成 | app/chat/settings/modelhub/agent/designsystem 的受影响测试，生产 smoke | 含 transitive 影响；新文件不能靠 Changed 自动漏选 |
| 性能改动 | 固定真机 UI benchmark + hotspot microbench | 同环境串行，原始 trace 可追溯 |
| 视觉/输入改动 | 截图/录像、IME/返回/无障碍/字体 | 至少两轮检查 |
| release 候选 | 严格构建、生产关键路径、profile 打包与恢复 | 沿用项目 release 门禁，不弱化 native 检查 |

“每 PR 3 分钟”“全量25分钟”不是本项目已证实 SLA。先测 CI 再定时限；设备不足可以排队，不能把 NOT_RUN 改为 PASS。

## 24. 确定性场景与 fixture 清单

### 24.1 场景定义

| ID | 数据/操作 | 必测重点 |
|---|---|---|
| S00 | 干净内部测试数据冷启动/暖启动，各浅深主题 | TTID/有效TTFD、首帧主题、入口可操作 |
| S01 | 30条已有消息，末条追加普通中英文；持续60秒 | 常规流式、输入并行、stats隔离 |
| S02 | 突发 chunk：小块/2048字符块交替，含75ms门限前后 | 文本发布边界、合流、末尾flush |
| S03 | 100k UTF-16 units 富文本，包含代码/表格/链接/数学 | parse、分页、完整copy、GC、帧 |
| S04 | 300条历史与多角色，抽屉搜索/置顶/重命名/切会话 | identity、history复用、排序、焦点 |
| S05 | 输出中上滑读旧消息，再新内容增长，最后显式到底 | 用户意图、无拉回、单一scroll owner |
| S06 | 一条助手消息大于两屏，末尾还有browser card | 巨大item定位、真正尾部、overlay留白 |
| S07 | 输入1→多行、中文组合输入、附件增删、IME切换 | composer连续性、inset、selection |
| S08 | 20张图片往返滚动10轮，同URI revision更新 | 去重、内存平台期、无旧图/闪图 |
| S09 | HTML/字符卡连续改版，故意让旧解析晚结束 | 有界后台、过时结果隔离 |
| S10 | 生成中切模型中心/设置/Agent覆盖页，再后台恢复 | 可见性采样、真实job持续、旧状态不闪 |
| S11 | Done/Stop/Error/异常、空buffer，立即切会话/重试 | T01–T12终态、Room、身份、API隔离 |
| S12 | 下载/导入/生图/备份/权限错误和恢复 | 真实进度、错误可恢复、metadata不丢 |
| S13 | TalkBack、fontScale 1.3/2.0、动画缩放0 | 触控、语义、裁切、返回 |
| S14 | API26/29/30/35/36相关系统组合 | 导航栏、edge-to-edge、IME兼容 |

S03 的 100k 是压力 fixture，不是用户消息上限。可另设 1Mi 字符超长耐受场景，必须保留分页/内存边界，不能把每个 PR 都跑到设备 OOM；生产现有可接收范围不得擅自缩小。

### 24.2 流式时间轴

fixture 定义固定事件时间轴：run开始、phase、text/reasoning delta、stats、工具/图片结果、terminal。保存完整事件序列与 hash。纯行为单测使用虚拟时间；Macrobenchmark 使用真实单调时钟与真实帧驱动的受控重放，两者共享事件语义，但不混用执行时钟。禁止用 `advanceUntilIdle` 将“60秒输出”挤到一帧后评价帧表现。

至少提供：正常小块、突发大块、很快完成、长时间无文本、reasoning-only、最后字节已发、最后字节未发、重复terminal、terminal后旧chunk、取消后新run。文本包含 surrogate/ZWJ/grapheme 跨 chunk 片段，确保源聚合和显示都不破坏字符。

“60秒”等宏场景应拆成可独立采样阶段。测试不能通过固定 sleep 猜测 UI 已稳定；用真实语义/状态/帧条件同步，并设合理 timeout。

### 24.3 页面状态矩阵

| 表面 | 必测状态 | 预期品质 |
|---|---|---|
| 聊天列表 | empty/loading/streaming/done/stopped/error/restoring/readOnly | 无内容丢失、无回闪、滚动意图正确 |
| 输入栏 | empty/composing/multiline/attachment/sending/stopping/blocked-by-real-condition | 焦点连续、真实可用性、无重复提交 |
| 历史抽屉 | loading/normal/search/no-results/rename/pin/delete/mixed-owner | 现有操作可达、排序/焦点/metadata正确 |
| Markdown | partial/complete/page-boundary/code/table/link/math | 排版稳定、原文和copy完整 |
| 图片/HTML | pending/ready/revision-changed/failure/permission-restored | 无过时结果、重试有效、缓存有界 |
| 模型/下载 | preparing/empty/filter-empty/downloading/verifying/ready/error | 无假进度、未知设备不被封锁 |
| 状态球/运行页 | compact/expanded/covered/background/resumed/read-failure | 内容真实、采样适度、不影响后台owner |
| 设置/API | normal/search/saving/save-failed/selftest/running/stopping | 操作反馈与真实服务一致 |
| 跨页/系统 | enter/exit/interrupt/back/IME/predictive-back/recreate | 不重复dismiss、不穿透、位置和状态可恢复 |

矩阵中的“blocked”只限具体运行条件，如损坏包、缺二进制、格式错误、真实native失败；不能泛化成“未认证机型”。

## 25. 测试迁移与命令合同

### 25.1 已存在的入口，可以直接使用

以下命令供后续实施者在 E:/MAC 执行。本次没有运行。按切片选择，不需要每个小改动全部跑一遍。

编译现有默认范围：

    .\scripts\verify-code.ps1 -SkipTests

app 范围的流式/所有权/保存相关针对性测试示例：

    .\scripts\verify-code.ps1 -Tests @('*ChatStreamAccumulator*','*ChatRunOwnership*','*ChatRunCancellation*','*ChatStopGenerationOperationsTest','*ChatSessionPersistenceOperationsTest')

app 投影/标题/导出切片可按实际新增类补筛选：

    .\scripts\verify-code.ps1 -Tests @('*ChatExportNamingTest','*ChatExportMarkdownTest','*ChatHistoryPersistenceBoundsTest','*ChatProjection*')

chat 模块测试使用独立调用：

    .\scripts\verify-code.ps1 -FeatureTests -Tests @('*LongTextRenderPolicyTest','*CodeFormattingTest','*Markdown*Test','*CharacterHtmlPreviewTest','*ImeBackBehaviorTest')

集成阶段：

    .\scripts\verify-code.ps1 -FullTests

架构检查可独立执行：

    python tools/architecture/check_architecture_rules.py

release 候选按仓库文档使用 scripts/build.ps1 的正确参数与环境；不在本文猜测签名、SDK路径或全构建开关。

### 25.2 不要误解现有脚本覆盖范围

- verify-code 默认编译 app/core:engine/api:local，不是自动枚举所有 Android 模块。
- FeatureTests 专指 feature:chat，不代表所有 feature。
- FullTests 是脚本列出的 app/core/api 子集，不是整个仓库所有模块。
- --tests 筛选对一次 Gradle invocation 是全局的；app 和 chat 专属筛选分开调用，避免 No tests found。
- Changed 选择依赖 git diff，新建未跟踪文件可能不被选中；新增测试/文件必须显式选择模块/类，不靠 Changed 保证覆盖。
- 架构脚本有其有限规则，PASS 不能替代实际编译、生产路径和 UI 验证。
- 最终分级/报告脚本可能修改报告；没有明确审阅输入和作用前不能运行后再宣称前端 S 级。本次文档审查不运行它们。

### 25.3 拟新增统一 UI 验证 runner

建议 scripts/verify-ui-quality.ps1，**当前尚不存在，不得将下列示例当作现成命令执行。**

拟定接口：Lane 为 unit / instrumented / macro / profiles / screenshot；GradleTasks 为明确 string[]；TestFilters 为该任务范围内筛选；DeviceSerial、Variant、OutputDirectory 为显式输入。

runner 必须：

1. 复用 McaBuildLock.psm1 的 Enter-McaBuildLock / Exit-McaBuildLock，同一共享锁、try/finally释放。新增 lane 选现有合法 kind，不发明不被 ValidateSet 接受的字符串。
2. 复用 McaWorktreeFingerprint.ps1，在开始和结束检查输入一致；变更中途发生则结果 FAIL(input-changed-during-run)。指纹覆盖源码、资源、构建脚本等实际构建输入，排除本次预期生成的日志、trace、截图和报告输出；优先沿用现有工具定义的排除规则，不能因 runner 正常生成证据而误判输入改变。
3. 独立唯一日志路径；检查 exit code、BUILD SUCCESSFUL、FAILED task、Kotlin codegen crash。不能仅 grep 某一种 e:file 错误。
4. 实际执行测试的任务必须产生本次运行的非空测试结果，并核对 XML 中目标类/数量；compile、compiler reports、profile merge 等非测试任务验证对应任务结果与产物，不能统一要求测试 XML。预期执行测试/生成必要产物却未执行的 NO-SOURCE、空报告、错变体、旧报告不得算通过；无关依赖任务正常 NO-SOURCE 不单独判失败。
5. 检查设备 serial/目标包/构建 hash；真机缺失明确 BLOCKED/NOT_RUN，不自动换模拟器代表性能。
6. 不嵌套调用另一已持锁的 runner，避免同进程重入锁失败；顶层 runner 与叶子 Gradle helper 职责分清。
7. 采集操作可以在一个有边界的 lane 内串行完成；不同 agent 不允许直接 gradlew 绕锁“加速”。
8. benchmark/profile/screenshot 具体 task 从当前 Gradle task 列表验证后登记，不能把文档中拟名当真实 task。
9. 编译报告/原始 trace/截图/录像与测试摘要写入同一 run ID；分享前脱敏，不收集密钥和私人正文。

### 25.4 必须保留并加强的现有测试

| 测试/集合 | 保护的业务 | 迁移规则 |
|---|---|---|
| ChatStreamAccumulatorPublicationBoundaryTest / ContractTest | 发布门限、flush、旧run排除 | 补空buffer terminal，不降低原断言 |
| ChatRunOwnershipCancellationClosureTest / CancellationBufferAndExclusionTest / OwnershipContractTest | 取消/epoch/末尾/所有权 | 整条生产链路保持；不能只测新helper |
| ChatStopGenerationOperationsTest / ChatSessionPersistenceOperationsTest | Stop、保存序列/失败 | 加T01–T12竞态，不删慢路径保护 |
| UiGenerationOwnership* / UiStateStoreTest | UI/API身份、状态更新 | 保留真实请求归属和并发语义 |
| ChatGenerationUiPublisherTest / GenerationProgressProjectionTest | phase/stats/progress | 不让展示降频吞终态 |
| ChatHistoryPersistenceBoundsTest / ChatExport* | 持久化边界、原文导出 | 缓存不能改变内容和限制 |
| LongTextRenderPolicyTest | 分页/源码策略 | 512桶断言在P05有更强行为覆盖后替换，不能只换字符串 |
| CodeFormatting / MarkdownList / MarkdownMath / CharacterHtmlPreview | 富内容语义 | 作为oracle一部分，不盲目刷新expected |
| ImeBackBehaviorTest / ProductionImeBackTest | 返回先收键盘 | 生产入口验证，不只检查BackHandler存在 |
| ProductionFloatingStatusTest / ProductionImageSubmitAccessibilityTest | 状态球、图片可达性 | 采样/语义修改后仍走原生产路径 |
| ProductionUiSession / Browser / Gallery / CharacterCardImport / OfflineTranslation | 跨业务工作流 | 按影响运行，保留真实授权/归属 |
| SettingsSurfaceGoldenTest | 8个文件中的429个CJK字面量基线 | 它是文字合同不是截图；移动文字需扩扫描覆盖，不能盲刷golden |
| DownloadFormattingTest | 下载数字/状态格式 | 注意file facade/私有反射适配，保留行为 |

表中简写测试族以源码当前真实类名展开。新增名称不在旧脚本过滤器里时必须显式加入。

### 25.5 源码断言测试的处理原则

源码断言可以保护局部契约，但不能证明真实滚动、没有掉帧、没有闪屏。替换它们需要更强的行为覆盖：新测试先能在旧缺陷下失败，再在正确实现下通过，最后移除过时实现字符串断言。

不要同时重写实现和 expected 后只报告绿色。黄金文案、协议、持久化和设备兼容规则的变化必须被独立审阅。

## 26. H01：权威会话写入降频，列为独立高风险后续项目

本轮默认不实施 V8 的 chatSessions 降频。P02/P06/P08 等无需依赖这项就能获得实质优化。

只有 trace 证明保留同步语义后的权威会话写入仍是主要瓶颈，且上层优化无法解决时，才单独提出：

1. 完整列出 chatSessions 的所有业务消费者，区分 durable content、当前工作副本与 UI index。
2. 确定唯一消息 authority、会话快照 materialization 和可恢复版本，不建立两个可分歧的权威。
3. 将 drain pending output 与 materialize full session snapshot 分开；后者在空buffer也能完成。
4. 终态、导出、切会话、保存、工具续轮、摘要、编辑/重命名、API读取等所有边界要有 freshness 合同。
5. 定义 clock/metadata/ownership/rollback 事务，不让 reducer 读时钟或捕获外部结果。
6. 对 T01–T12 做完整生产级竞争测试，并验证 save失败/旧save/clear/discard/进程恢复。
7. 新旧实现并行对照时只读比较，不双写Room；功能开关不能让同一会话同时走两个owner。
8. 提供独立可回退补丁、迁移/恢复和性能证据，再决定是否合入。

这是架构级变更，不应作为“15行快赢”交给弱 agent。未实施 H01 不影响 V9 必选目标验收；若没有可重复证据，应删除这个待办而不是为了清单完整去重写。

## 27. 弱 agent 执行合同

### 27.1 可以直接复制的任务模板

    任务ID：例如 P02b，不得同时承接多个高风险切片。
    目标：一个可观察结果，不是“整体高级化”。
    基线：HEAD、工作区指纹、相关文件当前diff、已知失败。
    阅读：本方案相关章节、当前仓库约束、真实生产调用链、已有测试。
    文件所有权：明确生产文件和测试文件；其他文件只读，需扩范围先说明原因。
    输入/输出：列真实字段、nullable、identity和fallback。
    不变量：颜色/布局冻结、单一authority、原文/持久化/取消/通用兼容路径。
    步骤：先加能捕获真实错误的测试，再实现最小生产改动，再接真实调用者。
    验证：具体runner/模块/类、真机场景、截图或trace；不得并行Gradle。
    交付：diff、非空测试报告、before/after、风险、回退命令/补丁边界。
    失败：报告真实状态；不改测试阈值、不删断言、不伪造设备或测量。
    协作：其他人也在工作，保留他们的改动；只处理自己负责的行。

### 27.2 每次修改的顺序

1. 读取实际文件与调用者，确认本文基线是否仍适用。发现源码已变化，先更新本切片的依赖表。
2. 搜索每个要改符号的全部读写点；确认 main/debug/benchmark/release 都能解析。
3. 明确该改动属于纯等值优化、行为修复或可见视觉变更；不混合提交。
4. 写能揭示缺陷或漏依赖的测试。可逆、低影响常量提取不需要镜像单测，用编译/截图等值验证即可。
5. 实现最小闭环；不只新增一个无人调用的新类。
6. 先针对性编译/测试，再按实际影响做生产场景；结果通过后不无理由反复跑同一大套测试。
7. 检查工作区diff，只归属本切片；保留既有dirty、不自动提交、不扩大发布范围。
8. 独立 reviewer 按不变量与反例检查，不以作者“自评10分”验收。
9. 更新证据索引并交给集成owner；下一片只基于已验收基线启动。

### 27.3 必须停下本切片的情况

- 无法确定权威消息/请求归属或保存顺序。
- 发现修复需要改变既有颜色、字体、布局或产品入口。
- 测试失败原因不明，却需要删测试/刷新golden才能继续。
- 源码/依赖版本已偏离基线，导致符号或API不适用。
- 两个agent同时修改同一装配文件，或者构建输入中途变化。
- 没有真机却准备写性能PASS，或没有截图却准备写视觉PASS。

停下的是依赖不清的切片，其他独立工作可以继续。报告最小阻碍与已经完成的可审阅产物，不要整仓reset，也不要反复请求用户授权已经允许的普通修复。

### 27.4 不接受的“优化完成”方式

- 用 @Immutable / stability通配把所有红字变绿，但集合仍原地修改。
- 给 composable 砍参数/挪文件后没有实际CPU/行为改善。
- 只测新helper，生产MainActivity仍走旧路径。
- 只在debug有符号，却写运行时if分支以为release不会解析。
- 通过降低刷新率、缩短文本、禁图片、加设备名单让benchmark过关。
- 把未保存尾部、取消竞态、漏字段称作“可接受缓存延迟”。
- 新增看起来高级的效果，掩盖输入/滚动/可恢复状态缺陷。

## 28. 证据包、复核和最终门禁

### 28.1 每个切片的证据记录

    Slice ID / owner / reviewer：
    HEAD / input fingerprint / APK SHA-256：
    问题与生产复现步骤：
    本次改动的真实调用链：
    改动文件和明确未覆盖范围：
    输入依赖与业务不变量检查：
    编译命令、退出码、日志：
    测试任务、类、case数量、XML路径：
    真机/API/刷新率/温度/fixture hash：
    截图与录像 before/after、允许mask：
    性能原始文件与统计、噪声/异常解释：
    PASS / FAIL / BLOCKED / NOT_RUN：
    已知风险、回退边界、后续责任人：

trace/截图等大文件不必全部进git，但必须有可复查位置和hash。任何标记为本地保留的约束/文件都不进入提交或外部分发；证据包也需要遵守当前仓库规定。

### 28.2 阻断项：任一失败不得称 S 级

1. T01–T12 中消息、保存、取消、归属或恢复缺陷。
2. 改变用户明确冻结的色系、排版、布局、气泡/输入语言。
3. 用户读旧消息仍被输出拉走；键盘/输入栏遮挡、焦点丢失或复制缺内容。
4. 重要入口空点击、不可访问、进度/完成状态与真实任务不一致。
5. 新泄漏、无界任务/缓存、过时解析覆盖或可重复关键场景性能回退。
6. 新增硬件/机型认证准入限制或测试注入进入正式未认证表面。
7. 缺构建指纹/原始测量/非空测试报告，却宣称全部通过。

### 28.3 最终验收表

| 维度 | 必须提供的证据 | 通过条件 |
|---|---|---|
| 数据与业务正确性 | 终态/竞争/Room/API所有权测试 | T01–T12及原关键合同通过 |
| 视觉保留 | 固定截图/几何/文字对照 | 无未授权变化，真实缺陷修复有解释 |
| 交互质感 | 输出/读旧消息/IME/输入/返回录像 | 稳定、可打断、无跳变/遮挡/旧回调 |
| 富内容 | 差分corpus、复制导出、分页用例 | 原语义完整，终态等值 |
| 性能 | release-like真机原始trace/统计 | 达登记目标；无未解释回退 |
| 内存与后台 | 固定循环/trim/lifecycle数据 | 缓存任务有界，无持续增长/隐藏轮询 |
| 状态与无障碍 | 页面矩阵、TalkBack、大字体 | 重要入口/状态/焦点可用且准确 |
| 工程交付 | 真实生产接线、profile打包、日志/测试报告 | 可复现、可审查、可回退 |

S 级是上述全部维度同时成立，不使用加权平均抵消阻断项。若只有静态分析或部分场景通过，写明“方案完成/局部验证完成”，不能写“产品10分”。

### 28.4 建议交付批次与工作量

| 批次 | 内容 | 退出条件 | 参考量级 |
|---|---|---|---|
| B0 | G00、依赖表、fixture、基线 | 能可靠复现/测量，明确范围 | 2–4工程日 |
| B1 | P02a/b、P03、P04、P08 | 高频无关工作下降，身份/回调正确 | 3–6工程日 |
| B2 | P05、P06a、P07 | 核心阅读/输入/富内容完成度达标 | 5–10工程日 |
| B3 | V01–V03、X01、P01按证据实施 | 全产品状态/主题/可访问性收口 | 4–8工程日 |
| B4 | Q01/Q02、profiles、独立回归 | 证据表全部明确，阻断项为0 | 3–5工程日 |
| 可选 | P06b或H01 | 单独证明必要性和正确性 | 另估，不混入默认承诺 |

这是基于复杂度的排期参考，不是对弱agent的完美执行时限。部分只读/纯测试可并行，交叠文件和Gradle串行；真实耗时取决于设备/既有失败和基线质量。

## 29. 最终执行优先级

**先把现有设计做得稳定、准确、连续，再讨论额外视觉效果。**

1. 建立可复现基线和全字段依赖表。
2. 保住消息、终态、取消、持久化和UI/API所有权。
3. 收窄投影/动作/行输入，让输出主要影响真正变化的区域。
4. 完成滚动意图、输入栏/键盘、长文/图片的连续体验。
5. 补齐真实按钮反馈、状态矩阵、焦点和可访问性。
6. 等值整理设计契约，修实测存在的系统栏、启动与转场问题。
7. 用release-like真机、截图/录像和独立复核证明结果。

本次文档交付的已完成状态是：V8/V8.1静态审查与V9执行方案。应用代码实施、编译、性能收益、视觉提升和最终S级认证均为后续工作，状态为NOT_RUN。

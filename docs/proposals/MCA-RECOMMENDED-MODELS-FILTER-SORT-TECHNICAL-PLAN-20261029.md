# 推荐模型页筛选、排序与视图模式技术方案

日期：2026-10-29
状态：实现记录。推荐页查询、轻量控制行、紧凑视图和筛选/排序共用右侧面板已接入当前工作树；modelhub 单元测试与架构检查通过。设备截图、TalkBack/大字交互和真机视觉验收尚未完成，不能据此宣称视觉验收通过。
基线：仓库 E:/MAC，分支 codex/enterprise-refactor，HEAD 68b6eef（提案形成时）。当时 feature/modelhub 已有未提交修改；本轮实现基于同一基线审阅并保留这些工作树改动。三个已初始化 Git 子模块为干净状态。

## 1. 目标与决策摘要

在推荐模型列表上方增加一行轻量控制：全部、筛选、排序、详细。用户可以先按任务、运行路线和资源档缩小当前目录，再按可核对的模型属性排序；筛选状态不会改变下载、打开、加载或诊断的业务处理。

默认进入时继续显示现有全部推荐目录、分组顺序、组内推荐顺序及详细卡片。点击“全部”清空筛选、恢复目录排序和现有详细列表。筛选与排序共用一个属性面板；“详细”独立切换详细/紧凑模型项。

筛选和排序使用同一个右侧面板，面板内分为“筛选 / 排序”两个页签。面板覆盖整个 ModelHub 窗口，底层导航由模态窗口拦截；手机上按约 68% 窗宽显示并保留左侧列表作为定位背景，宽窗口限制最大宽度为 420dp。

页面布局属于项目硬锁中的视觉合同。当前工作树已实现该页面切片；固定状态前后截图、设备交互与用户视觉验收仍是独立未完成项。

## 2. 当前实现与约束

### 2.1 现有页面

- ModelHub 外层仍有本地、云端、推荐、广场、文件五个区段；本方案只影响推荐区内部。
- 推荐内容由 RecommendationCatalog 按既有任务区段组织，当前覆盖 CPU 聊天的内存组、NPU 聊天、LiteRT-LM 的 CPU/GPU/NPU、CPU/GPU/NPU 生图。部分组默认仅显示第一项，展开后显示其余项目。
- 当前列表为单一 LazyColumn；RecommendedModelCard 集中展示能力、格式、运行路线、设备建议、大小、安装/诊断状态和主操作。
- 目录排序使用模型区段、设备适配提示、目录优先级和稳定 ID；现有目录没有可证明 CPU、GPU、NPU 之间统一质量的全局榜单。
- ModelScopeRecommendedModel 已提供任务/区段、计算后端、最小 RAM、优先级、文件/组件大小等元数据。过滤排序应使用这些类型化字段，不从标题、说明文案或芯片名称反推分类。

### 2.2 主要体验问题

用户首先要理解运行时/芯片分类，才能判断应该看哪个分组；多个组连续排列增加了滚动和比较成本。模型卡当前将决策信息与工程细节连续铺开，下载大小和建议内存不容易在扫描时比较。

### 2.3 不可破坏的合同

- 不更改 ModelHub 外层导航、下载/续传/校验/加载流程、模型资产、已安装状态、模型卡现有主操作或目录来源。
- 不通过设备型号、芯片识别、验证历史或本地 profile 自动隐藏模型或限制用户入口。设备匹配只用于推荐顺序和解释性提示；用户主动选择的运行路线筛选只控制当前列表显示。
- 设备信息未知时仍显示完整用户可见目录。未知 RAM 或缺少大小元数据的模型留在“全部”中；对应属性无数据时不伪造筛选值。
- 不重排模型级业务优先级，不声称跨引擎的统一最佳，不新建平行推荐服务、全局状态 writer 或网络 API。
- 保留颜色、字体度量、既有卡片视觉语言和 48dp 交互热区。除显式的紧凑视图外，不借筛选改版整体换肤或全页增大留白。

## 3. 信息层级与默认排版

推荐页继续沿用现有 ModelHub 页面壳层。控制行放在当前设备摘要之后、推荐列表之前；设备摘要保持单行/自然换行，不创建新的大标题或推荐 Hero。

```text
模型中心
[本地] [云端] [推荐] [广场] [文件]        现有外层导航
当前设备：芯片 · RAM · 推荐提示             现有信息
[全部]    [筛选 · 2]    [排序]    [详细]     新增轻量控制行

CPU 图文聊天
  轻量档 · 4–8GB
  现有详细模型卡
  主力档 · 8–16GB
  现有详细模型卡
NPU 图文聊天
  ...
CPU / GPU / NPU 生图
  ...                                      一个纵向列表滚动区
```

默认状态不增加推荐模型卡，不替换现有分组或组内折叠逻辑。“筛选 · 2”中的数字表示当前已应用的筛选条件数；筛选未生效时只显示“筛选”。“排序”标签保持短，当前值在面板内明确展示为单选项。

## 4. 筛选与排序面板

### 4.1 手机与宽窗口呈现

- 所有宽度使用对齐到逻辑 End 的覆盖式侧栏；不挤压或重排推荐列表，列表滚动位置保持不变。
- 面板在窄屏约占全屏宽度的 68%，最大 420dp，留出列表背景作为定位上下文，同时为中文选项保留可读宽度。
- 面板使用覆盖 ModelHub 页面并拦截底层操作的全屏模态层，背景 scrim 可点关闭；系统返回、显式关闭、scrim 关闭都会放弃草稿。首期不加从系统右边缘开始的拖动关闭手势，避免与 Android 返回手势争抢。
- 关闭不代表丢失已应用设置；重新打开时从已应用状态重新建立草稿。筛选与排序页签切换不会重置另一页的草稿。
- 复用 McaMotion 与设计系统色形，不添加新主题、渐变或高对比装饰边框。

右侧面板结构：

```text
筛选与排序                                      关闭
筛选                 排序
任务      不限 | 聊天 | 生图
运行路线  不限 | CPU | GPU | NPU
资源档    不限 | 由已知模型元数据生成；未知值单列

[重置]                         [显示 18 个模型]
```

点击“排序”页签后只展示推荐、下载体积、最低内存三个单选项。底部操作固定，筛选选项或排序解释过长时只滚动中间内容。

### 4.2 筛选维度与匹配语义

| 维度 | 数据来源 | 第一版行为 |
| --- | --- | --- |
| 任务 | RecommendedModelSection、ModelScopeRecommendedKind、模型 bundle 任务 | 全部 / 聊天 / 生图；多模态聊天归聊天，不因带视觉能力而误归为生图 |
| 运行路线 | RecommendedComputeBackend、chatRuntime、image/vision bundle runtime | CPU / GPU / NPU；使用 OR 语义，选择路线只筛当前列表，不改变卡片动作或下载准入 |
| 资源档 | minRamGb 和当前目录已有的组/等级 | 只展示数据实际支持的档位；未知档可选为“未标注”，不能把估算值显示成硬件保证 |
| 格式/量化 | 已有模型格式、parameterScale、quant | 首期不默认占据面板；只有确认有稳定字段且用户选择研究用途时再加入“更多筛选” |

匹配逻辑：同一维度内多个选项为 OR，不同维度之间为 AND。未选择某维度等于该维度不限。所有可见模型始终可从“全部”找到；面板不根据当前设备自动勾选任务、后端或 RAM 档。

### 4.3 排序选项

| 排序 | 字段与结果呈现 | 缺失值与稳定性 |
| --- | --- | --- |
| 推荐（默认） | 沿用现有目录分组顺序和推荐 Comparator，不生成跨执行路线总排名 | 与当前 `RecommendationCatalog` 顺序一致；以稳定 ID 保持相同输入顺序 |
| 下载体积 | 按默认下载计划总字节升序；显式选择后转为平铺比较列表，每项保留任务/运行路线标记 | 大小未知或 bundle 元数据不完整的项目排在末尾；相同时用既有 priority、ID 稳定决胜 |
| 最低内存 | 按模型提供的 minRamGb 升序；显式选择后转为平铺比较列表 | 未声明值排在末尾并标出未标注；相同时用 priority、ID 稳定决胜 |

第一版不提供“最新”或“最快”等排序，除非目录已有可验证、跨条目一致的数据字段。推荐顺序中的 device-fit 是提示/排序信息，绝不能变成筛除条件。选择“推荐”返回原分组；选择其他全局比较排序才使用平铺结果，并为每项保留用途和后端标签。

### 4.4 应用、重置和关闭

1. 点击“筛选”或“排序”打开同一个侧栏，并直接选中对应页签；页签可来回切换且保留同一份草稿。
2. 打开时从 appliedQuery 复制 draftQuery。勾选项只更新草稿，并实时计算结果数，不立即改变背景列表。
3. 点击“显示 N 个模型”提交草稿到 appliedQuery，关闭面板并更新列表。若结果数为零，按钮显示“显示 0 个模型”，可应用并进入专用无匹配状态。
4. 点击“重置”只清除草稿筛选并恢复推荐排序；详细/紧凑模式是独立视图偏好，不被面板重置影响。
5. 系统返回、遮罩关闭或显式关闭放弃草稿，保留最近一次已应用状态。重新打开时从 appliedQuery 恢复。
6. 点击“全部”关闭面板、清空所有已应用筛选、恢复推荐排序与旧分组结构，并返回详细视图。滚动定位到目录起始位置。这样它是清楚的兼容/恢复入口。

面板首期不加关键词输入框，避免重复模型广场搜索并减少键盘/返回复杂度。后续若数据量证明需要搜索，另立切片并明确搜索范围及 IME 返回顺序。

## 5. 详细 / 紧凑列表模式

详细为默认值，保留现有 RecommendedModelCard 的内容、完整介绍展开和按钮。紧凑模式仅压缩非决策信息：

- 首行：模型名称、安装/实验/诊断等已有状态。
- 次行：用途能力、参数规模/量化，以及重要的运行提示。
- 比较行：minRamGb、默认下载体积；未知数据如实显示“未知/见文件列表”。
- 动作：保留现有主操作和次级入口；诊断状态只影响次级诊断入口，不替代下载、打开或加载主操作。
- 点“详情”或现有卡片详情操作可展开原始介绍和技术字段；展开内容不设硬行数上限。

紧凑视图不得隐藏关键兼容警告、下载状态、可访问的主操作、文件查看入口或诊断入口。布局仍为单列，不用网格卡片，不通过缩小字号实现紧凑。切换模式不改模型排序或已展开推荐组。

## 6. Compose 状态与数据处理

### 6.1 状态归属

已应用的筛选/排序和详细/紧凑视图是推荐页呈现状态，以窄类型和 `Saver` 在 `rememberSaveable` 中保存；面板打开状态、当前页签和草稿仅用 `remember` 保持。每次打开都从已应用条件重建草稿，关闭时不提交，避免旋转/进程恢复意外应用半成品条件。不要写入 MainUiState、下载 owner、数据库或全局偏好。

建议的逻辑模型（具体命名按实现时现有类型核验）：

```kotlin
internal data class RecommendationQuery(
    val tasks: Set<RecommendationTask> = emptySet(),
    val backends: Set<RecommendationBackend> = emptySet(),
    val minimumRamTiers: Set<Int> = emptySet(),
    val sort: RecommendationSort = RecommendationSort.CATALOG
)

internal enum class RecommendationSort {
    CATALOG,
    DOWNLOAD_SIZE_ASC,
    MINIMUM_RAM_ASC
}
```

`Set<Enum>` 不一定直接适用于 Bundle 保存；采用 `Saver` 或保存稳定字符串 key，再解析为窄类型。若设备配置/进程恢复恢复 appliedQuery，临时 draft 不覆盖已应用值。过滤函数保持纯函数并单测，Composable 只负责状态和事件。

### 6.2 目录投影

- 先从现有 RecommendationCatalog 收集当前用户可见条目及其原分组、稳定 ID、任务、后端、minRamGb、priority 和可选下载大小。
- 使用 `remember(catalog, appliedQuery)` 或等价有明确输入键的派生路径计算结果；不得在每次卡片重组时扫描全部目录。
- 推荐排序模式直接复用已排序组，不复制或二次推断 device-fit 规则。
- 平铺排序模式用同一个 filtered result 集合执行 comparator；缺失数据始终末位，tie-break 保持确定性。保留 LazyColumn 的 item key，使用 model ID 加展示模式/必要分组 key，不用可变列表索引作为 key。
- 不新增嵌套 LazyColumn。筛选面板的选项区可以独立滚动，但推荐结果页面保持一个主滚动 owner。
- 原有组展开状态继续由现有状态持有；“全部”恢复旧默认详细/推荐顺序，不重新定义模型卡安装和下载状态。

### 6.3 操作路径与兼容

RecommendedModelCard 的下载、文件、网页、打开本地模型、加载本地聊天模型、验证/诊断回调全部保留。筛选只是对当前 Compose 列表投影做选择。不能通过缺少设备档案、跨芯片适配或未验证状态让筛选结果的主操作消失。

## 7. 建议实现文件与职责

| 文件 | 计划职责 |
| --- | --- |
| `feature/modelhub/.../RecommendedModelsSection.kt` | 持有 applied/draft query、视图模式和面板状态；向单一列表传入投影结果；保留现有组和卡片回调 |
| `feature/modelhub/.../RecommendationQuery.kt`（新增） | 纯类型、facet 匹配、计数、稳定排序和结果形态选择；不依赖 Compose/Android UI |
| `feature/modelhub/.../RecommendationFilterSheet.kt`（新增） | 轻量控制行、End 对齐侧栏、筛选/排序页签与草稿操作；不访问全局 store |
| `feature/modelhub/.../RecommendedModelCard.kt` | 增加明确的 presentation mode 参数；紧凑模式使用同一模型和事件，不复制业务逻辑 |
| `feature/modelhub/.../RecommendationCatalog.kt` / `RecommendationPresentation.kt` | 仅在类型映射/格式化确需复用时小幅扩展；设备排序和可见性合同保持单一来源 |
| `feature/modelhub/src/test/...` | 新增纯查询/排序测试；更新适用的屏幕契约测试，不删除已有下载准入、主操作和大字测试 |

`RecommendedModelsSection.kt` 当前已接近 500 行。实施时优先把纯查询和面板 UI 放入上述单一职责文件；如果接线会把页面文件推近 600 行以上，先评估职责边界，不得压行或仅为计数机械拆文件。`feature/modelhub` 目前已有未提交改动及 `*.kt.new` 文件；执行者必须先审阅并吸收这些现状，不得覆盖、清理或假设它们已提交。

## 8. 测试与验收计划

### 8.1 纯逻辑测试

- 无筛选的模型 ID 序列、分组顺序和组内顺序与现有 RecommendationCatalog 输出完全一致。
- 任务分类包含对应聊天/图文/生图模型，不能把视觉聊天误筛到生图；CPU/GPU/NPU 根据类型化路线字段匹配。
- 同维度 OR、跨维度 AND、生效筛选计数、无匹配结果及重置逻辑正确。
- 下载大小由默认下载计划计算；缺失/部分 bundle 大小排末位，不溢出；相同值使用稳定 tie-break。
- minRamGb 升序和未知值末位规则正确；未知数据仍在 All 中可见。
- 未知芯片、跨 vendor、匹配芯片的 catalog ID 集保持完整；排序差异不能改变用户可用的下载/打开/加载入口。

优先扩展 `RecommendationCatalogTest` 与 `RecommendationPresentationTest`，新建 `RecommendationQueryTest` 覆盖纯过滤/排序。保留 `RecommendedCardPrimaryActionTest` 中关于验证状态不能替代主操作的测试。

### 8.2 UI 状态与可访问性

- 默认全部/推荐/详细状态与原列表等值；已安装、下载中、下载失败、待校验、运行库提示均正确显示。
- 面板打开、修改草稿、应用、重置、取消、遮罩关闭和系统返回的状态迁移明确；取消不得偷偷应用草稿。
- 过滤为零时显示“没有匹配模型”及直接清除筛选的动作；与真实空目录、目录加载中分开。
- 面板打开时系统返回先关闭面板，不能穿透至聊天/模型中心前一页；无键盘内容时也须实测 BACK。
- TalkBack/Compose semantics 能读出筛选项名称、选中状态、已选数量、排序单选状态和结果数；图标按钮有名称；触控目标不小于 48dp。
- 1×、1.5×、2×字体下，长中文选项换行可滚动、不被固定高度裁切，底部应用按钮仍可达；深浅色对比达到当前 token 约束。

复用/扩展 `ModelHubLargeFontContractTest` 与 `ModelHubStatusAccessibilityContractTest`。如果当前模块没有可真实交互的 Compose UI test 基础，新增最小可验证的 UI test 配置，而不是只靠源码字符串断言冒充交互通过。

### 8.3 固定视觉矩阵

实施前先为当前工作树记录 before 截图，至少包括：推荐页全部详细、一个代表性筛选结果、已安装模型卡、深/浅色和 2× 字体。实施后对同样的状态复拍并进行两轮宏观/微观检查。

- 手机：360dp 宽紧凑屏、390/412dp 常见宽度；右侧面板宽度、左侧定位区、两页签、滚动/应用/关闭。
- 宽窗口：验证 420dp 最大面板宽度、右侧定位和后方列表可读区域；窗口大小变化时不重置已应用条件。
- 列表：全部旧分组、任务筛选、路线多选、零结果、三种排序、紧凑/详细、长标题、未知下载大小。
- 主题和字号：浅色/深色，1×/2×；确认不改品牌色、字体度量、卡片圆角或全局主题。
- 交互：筛选面板 Back/遮罩关闭、应用后恢复列表、列表滚动锚点、快速连续开关，不丢卡片状态。

不要把 Compose Preview 或编译成功称作真机验收；需要真实设备/模拟器渲染并逐项检查。帧性能无退化主张需在相同设备与列表负载下测量；至少确认查询不在重组热路径反复全量排序。

### 8.4 构建命令建议

文档阶段不运行 Gradle。生产代码实施时，由一个整合者串行运行并确认匹配到真实测试：

```powershell
.\gradlew.bat :feature:modelhub:compileDebugKotlin -PmcaQnnTypedBindingsRequired=false --no-daemon
.\gradlew.bat :feature:modelhub:testDebugUnitTest -PmcaQnnTypedBindingsRequired=false --no-daemon
python tools/architecture/check_architecture_rules.py
```

如果验证脚本/锁脚本已更新，优先使用其正式入口；记录 JDK 21、当前 worktree 补丁、运行任务数、失败/跳过和截图/设备范围。不同 Agent 不得并行争用同一 Gradle/build 目录。

## 9. 分阶段实施与回退

| 阶段 | 内容 | 状态与可独立回退的边界 |
| --- | --- | --- |
| P0 | 审阅硬锁、既有 modelhub 改动与查询/列表调用链 | 代码审阅完成；未生成固定状态截图 |
| P1 | 纯 `RecommendationQuery`、过滤/计数/排序逻辑和单测 | 代码与 modelhub 单元测试通过；纯查询文件与测试可独立回退 |
| P2 | toolbar、draft/applied 状态、右侧筛选/排序页签面板与结果状态 | 代码已接入，模块编译通过；面板、状态与列表接线可单独回退 |
| P3 | 紧凑模式与详细/紧凑切换 | 代码已接入；尚未完成设备渲染验收 |
| P4 | 固定截图、TalkBack/大字/BACK、设备验收并按证据迭代 | NOT_RUN；缺少当前工作树绑定的截图和实际交互证据 |

查询和筛选偏好是纯 UI 状态，不改变数据库或用户模型资产；回退页面时不需要数据迁移。实施若发现筛选需要新增源数据或改变模型目录协议，应停止在该切片，另提数据/协议方案，不把它混入纯前端实现。

## 10. 风险与完成定义

| 风险 | 处理 |
| --- | --- |
| 右侧面板与系统返回手势相邻或窄屏内容拥挤 | 不使用系统边缘拖动关闭；保留系统 Back、显式关闭与 scrim 关闭；窄屏宽度约 68% 且最大 420dp，并实测 360dp/大字体 |
| 误把设备适配提示变成隐式过滤 | 目录全集 ID 测试；设备字段不得写入默认筛选 draft |
| 排序与旧分组产生矛盾 | 推荐模式严格等值旧分组；显式全局字段排序才使用带用途/路线标签的平铺比较 |
| 缺失元数据使模型消失 | 缺值排后或归到未标注项；All 不依赖这些属性 |
| 新控件挤压既有信息密度 | 一行控制、既有列表密度优先；不添加重复说明和新搜索条 |
| 筛选状态污染全局状态/任务行为 | 状态留在 feature 页面；下载与本地动作仍调用既有回调 |
| 当前工作树中存在并行修改 | 实施前核对分支、HEAD、modelhub diff 和未跟踪 `.kt.new`；禁止覆盖未知修改 |

完成必须同时满足：All 视图的可见模型、分组、顺序和主操作与基线一致；用户筛选/排序结果符合字段合同；未知设备仍能浏览完整目录；应用/取消/重置/返回状态可靠；小屏、大字和无障碍通过；截图与构建测试绑定当前工作树证据。未渲染的视图保持 NOT_RUN，不宣称视觉完成。

## 11. 关联源码与测试

- [MCA 项目硬锁](../architecture/MCA_PROJECT_HARD_LOCK.md)
- [ModelHubScreen.kt](../../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/ModelHubScreen.kt)
- [RecommendedModelsSection.kt](../../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/RecommendedModelsSection.kt)
- [RecommendationCatalog.kt](../../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/RecommendationCatalog.kt)
- [RecommendationPresentation.kt](../../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/RecommendationPresentation.kt)
- [RecommendedModelCard.kt](../../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/RecommendedModelCard.kt)
- [RecommendationCatalogTest.kt](../../feature/modelhub/src/test/java/com/muyuchat/feature/modelhub/RecommendationCatalogTest.kt)
- [RecommendedCardPrimaryActionTest.kt](../../feature/modelhub/src/test/java/com/muyuchat/feature/modelhub/RecommendedCardPrimaryActionTest.kt)
- [ModelHubLargeFontContractTest.kt](../../feature/modelhub/src/test/java/com/muyuchat/feature/modelhub/ModelHubLargeFontContractTest.kt)
- [AgentModelPickerSheet.kt](../../feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentModelPickerSheet.kt)
- [ChatScreen.kt](../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt) — 当前 modal drawer 与返回手势说明。

# MCA 生成预设管理 交接记录

日期：2026-11-03 · 分支：`enterprise-refactor` · 状态：功能与自动化门禁完成，真机与发布链路未验证

> 2026-11-03 整合更新：本记录描述的功能已按切片提交（`feat(presets)`）；§6 的检查行由整合者按
> 提交时的工作区重跑，§7 的 N1/N2/N3 仍为未修复项，§9 的回退点改为对应提交。

## 1. 任务 / 范围

把酒馆（SillyTavern）文本生成预设变成可管理的资产：导入、命名、绑定本地/云端对话模型、删除，并把绑定结果落到模型级生成参数。

用户能力变化：

- 应用菜单新增「预设管理」入口，进入独立全屏页面。
- 模型管理页新增「预设」分节，与独立页面读取同一批记录。
- 预设只保存采样与输出参数；提示词、模板和宏仍由角色卡导入，行为与改动前一致。

本切片为跨层变更（UI 路由 + 状态写入 + 持久化），按硬锁 §12.1「跨层契约/状态」「持久化/格式」两档选择验证。

## 2. 起点基线

- HEAD：`bb3f25da`（release 0.3.1 证据记录），工作树开始时即不干净。
- 子模块 revision：`llama.cpp 4ceb17191`、`stable-diffusion.cpp b98234bd4`、`tokenizers-cpp acbdc5a27`；`git submodule summary` 无内部差异。
- 起点既有未提交改动（非本任务产生，见 §3.2）：`feature/settings/*`、`GitHubReleaseUpdate.kt`、`AppUpdateOwner.kt`、`SettingsUiStateMapping.kt`、`MainViewModel.kt` 中的持久化前缀缓存分支。
- 构建工具链：JDK 21、Gradle 8.14.3、Kotlin 2.0.21。本机未配置 QAIRT/QNN SDK（见 §6）。

## 3. 改动文件与职责

### 3.1 本切片

| 文件 | 职责 |
| --- | --- |
| [GenerationPresetManagerSection.kt](../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/GenerationPresetManagerSection.kt) 新增 348 行 | 预设管理 UI：`PresetManagerScreen`（独立页）、`GenerationPresetManagerSection`（模型枢纽分节）、列表、删除确认、绑定弹窗。只渲染与发 intent，不访问存储 |
| [GenerationPresetStore.kt](../app/src/main/java/com/muyuchat/mca/GenerationPresetStore.kt) 新增 93 行 | `GenerationPresetRecord` + SharedPreferences 持久化，命名空间 `mca_generation_presets_v1`，`schema=mca.generation.presets.v1`，坏记录逐条丢弃 |
| [GenerationPresetOperations.kt](../app/src/main/java/com/muyuchat/mca/GenerationPresetOperations.kt) 新增 162 行 | 导入/删除/绑定保存的业务用例与 UI 投影；导入在 `Dispatchers.IO` 解析文件并写 store |
| [MainViewModelGenerationPresetDelegates.kt](../app/src/main/java/com/muyuchat/mca/MainViewModelGenerationPresetDelegates.kt) 新增 36 行 | 主 VM 的三个预设入口，只做参数装配，不加新 host 能力 |
| [CloudModelUiMapping.kt](../app/src/main/java/com/muyuchat/mca/CloudModelUiMapping.kt) 新增 30 行 | 云端模型 UI 投影单一来源；`cloudChatModelUiItems()` 只暴露 CHAT 类型给绑定弹窗 |
| [GenerationPresetFields.kt](../app/src/main/java/com/muyuchat/mca/GenerationPresetFields.kt) 新增 44 行 | 由 `generate_state_fields.py` 生成的字段限定 writer |
| [AppTab.kt:37](../app/src/main/java/com/muyuchat/mca/AppTab.kt#L37) | 追加 `PRESETS("预设管理")`，末位追加，未重排既有 ordinal |
| [MainActivity.kt:628](../app/src/main/java/com/muyuchat/mca/MainActivity.kt#L628) | 第 8 个 `CrossfadeTabPage` 宿主 + `PresetManagerScreen` 接线；模型枢纽投影改走共享映射 |
| [MainViewModel.kt:220](../app/src/main/java/com/muyuchat/mca/MainViewModel.kt#L220) | 注入 store、创建 `generationPresetWriter`（本切片仅 +4 行，其余 diff 属 §3.2） |
| [MainUiState.kt](../app/src/main/java/com/muyuchat/mca/MainUiState.kt) / UiStateFields.kt / MainUiBootstrapFactory.kt:155 / McaAppContainer.kt:175 / UiStateDomainWriters.kt | 全局状态新增 `generationPresets` 字段（+1）、启动时加载、composition root 创建 store、注册 writer |
| [ModelHubScreen.kt:69,330](../feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/ModelHubScreen.kt#L69) · ModelHubUiTypes.kt | 新增 `PRESETS` 分节、三个回调参数（带默认值，旧调用点不破坏）、`GenerationPresetUiItem` 与状态字段 |
| feature/chat：AppMenuScreens.kt / ChatAuxiliaryPages.kt / ChatScreen.kt / ChatScreenActions.kt / ChatViewModel.kt | `onOpenPresets` 从菜单行透传到宿主 `host.onTab(AppTab.PRESETS)`，各 +2 行 |
| [state_write_authorities.json:532](../tools/architecture/state_write_authorities.json#L532) | `GenerationPresetFields` → `generationPresets` / `params` / `statusMessage` |
| [shared-preferences-files.golden.txt:23](../app/src/test/resources/shared-preferences-files.golden.txt#L23) | 登记新增私有文件名 |

门禁测试是**加强**而非放宽：`MainShellMotionContractTest` 的 CrossfadeTabPage 计数 7→8，并把 `PresetManagerScreen(` 加入必须存在的宿主清单；`AppForegroundRecoveryContractTest` 把 `CloudModelUiMapping.kt` 纳入 displayName 回退断言的读取范围；`SillyTavernPresetCodecTest` 只把挤在一行的语句拆成两行，断言未变。

### 3.2 同一工作树内的其他未提交改动（不属于本切片，勿混入提交）

`AppUpdateOwner.kt`、`GitHubReleaseUpdate.kt`（+140）、新增 `AppUpdateParallelDownloader.kt` 及其测试、`feature/settings/build.gradle.kts`、`SettingsScreens.kt`（+1047）、`SettingsAboutScreen.kt`、`SettingsLogsPrivacyScreens.kt`、`SettingsUiStateMapping.kt`、`SettingsAsyncOperationContractTest.kt`、`SettingsAsyncStateContractTest.kt`、`GitHubReleaseUpdateTest.kt`，以及 `MainViewModel.kt` 中 `setPersistentPrefixCacheEnabled` 的单飞提示分支。整合者提交本切片时必须逐文件挑选，`MainViewModel.kt` 与 `MainActivity.kt` 属混合 diff，需按 hunk 拆分。

## 4. 功能 / 数据 / 协议 / 视觉影响

- 功能：只新增入口与页面；模型管理、下载、聊天、Local API 的既有行为未改。旧「预设只读展示」没有缩水——模型枢纽原分节仍在，独立页共享同一批记录。
- 数据：新增独立命名空间 `mca_generation_presets_v1`，不改写任何既有文件。带 `schema` 版本字段，读侧 schema 不匹配即视为空，不猜测旧格式。既有模型级参数仍是运行权威：应用预设是把语义字段复制进 `ModelGenerationProfileStore`，非迁移。删除预设不回退已应用到模型的参数，弹窗与状态栏均已写明。
- 协议：HTTP/SSE、导入导出格式、角色卡覆盖优先级均未变化；`SillyTavernPresetCodec` 输入输出不变。
- 视觉：沿用现有 `McaDialog`/`McaButton`/`McaChip`/`FlowRow` 与既有页面留白、字号；菜单行插在「模型管理」与「智能调优」之间，其余顺序不动；页面进入仍走既有 `CrossfadeTabPage` + `McaMotion.fade`。属冻结合同内的新增页面，无重排无换肤。

## 5. owner、状态与资源边界

- `generationPresets` 唯一业务写入者是 `GenerationPresetFields` writer；UI 侧 `GenerationPresetUiItem` 与 `ModelHubUiState` 是纯投影，不回写。
- `params` / `statusMessage` 的写入沿用既有域内约定；`params` 现有 8 个字段限定 writer（本切片前 7 个），属既有 D02 基线扩张，非新型违规，边界脚本 0 违规通过。
- 文件读取只在导入用例的 `Dispatchers.IO` 协程内，作用域是 `viewModelScope`，取消随 VM 生命周期；store 的 load/save 由实例内 `lock` 保护。
- 未新增 `Any?` 注入、未给 `MainCoordinatorHost` 加能力、未新增第二套运行时或全局可变流。

## 6. 检查命令、结果、证据位置

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 状态写边界 | `python tools/architecture/check_state_write_boundaries.py` | PASS：D01/D02/D03 源边界 0 违规（exit 0） |
| 架构规则 | `python tools/architecture/check_architecture_rules.py` | PASS：ALL 19 CHECKED ARCHITECTURE RULES PASSED（exit 0）；作用域仅脚本声明目录 |
| 编译 | `./gradlew :app:compileDebugKotlin :feature:modelhub:compileDebugKotlin :feature:chat:compileDebugKotlin -PmcaQnnTypedBindingsRequired=false` | PASS：BUILD SUCCESSFUL，无新增 warning |
| app 单测 | `./gradlew :app:testDebugUnitTest -PmcaQnnTypedBindingsRequired=false --rerun` | PASS：4698 tests / 0 failures / 0 errors / 11 skipped，6m20s 强制执行 |
| feature:chat 单测 | 同上 `:feature:chat:testDebugUnitTest --rerun` | PASS：492 tests / 0 failures / 0 skipped |
| feature:modelhub 单测 | 同上 `:feature:modelhub:testDebugUnitTest --rerun` | PASS：91 tests / 0 failures / 0 skipped |
| 跳过项核对 | `app/build/test-results/testDebugUnitTest/*.xml` | 11 项均为既有环境门控用例（WebSearch live key ×6、符号链接守卫 ×4、导入清理 ×1），与本切片无关 |
| 完整 APK / 发布构建 | `.\scripts\build.ps1` | NOT_RUN（未获发布授权） |
| 升级兼容（真实签名 + 既有数据） | — | NOT_RUN |
| 真机 UI（预设页导入/绑定/删除、返回手势、键盘、大字体、深浅色） | — | NOT_RUN：本机 `adb` 不可用，无连接设备 |
| 真实 QNN/native 链路 | — | BLOCKED：本机未配置 QAIRT/QNN SDK，缺完整 SDK headers |

原始证据：`app/build/test-results/testDebugUnitTest/`（4698 用例，最新 15:44）、`feature/chat/build/test-results/testDebugUnitTest/`（15:38）、`feature/modelhub/build/test-results/testDebugUnitTest/`（15:45）。本轮数字来自 `--rerun` 强制执行，不依赖缓存或 UP-TO-DATE。

## 7. 未验证 / 阻断 / 新增问题

- **BLOCKED**：`-PmcaQnnTypedBindingsRequired=false` 只是隔离 Debug 构建的显式 opt-in，产品构建必须带 typed graph runner；上述编译与单测结论不能写成 QNN/native 通过（硬锁 §12.2）。
- **NOT_RUN**：预设页从未在真实设备上渲染，冻结视觉等值、返回手势、`rememberSaveable` 跨 fade 保态、OpenDocument 选择器行为均只有源码级门禁证据。
- **新增问题 N1（线程）**：`deleteGenerationPreset` / `saveGenerationPresetBindings` 由点击回调直接同步执行，内部含 SharedPreferences 读写和 `GenerationPreferences.persistGenerationParams` 磁盘写，落在主线程，违反 §6「磁盘处理不进主线程」。下一切片应像导入一样走 `viewModelScope + Dispatchers.IO`。
- **新增问题 N2（竞态）**：三个用例都是 `load() → 内存改 → save()`，store 锁只保护单次读写，不保护整个读改写序列；并发导入/绑定可丢记录。建议把序列化移进 store 或加单飞。
- **新增问题 N3（覆盖）**：预设 store 与绑定用例没有专门行为测试；现有证据来自编译、路由/宿主计数断言和私有文件 golden。建议补 store schema 与绑定应用优先级（角色卡覆盖 > 预设 > 模型默认）用例。
- **既有问题**：`MainViewModel.kt` 959 行、`MainActivity.kt` 760 行，均低于硬锁上限但已接近；`MainUiState` 字段数继续 +1，D02 收窄仍是未做项。

## 8. 临时路径与例外

无临时 adapter、无未接线入口、无待清理旧路径；`AppTab.PRESETS` 与 `GenerationPresetManagerSection` 均有生产调用者。未申请第 15 节例外——所有文件预算内，测试只做强。

## 9. 回退点与下一切片

回退：本切片全部改动仍未提交，删除 6 个未跟踪新文件 + 还原 `git diff` 中 §3.1 列出的文件即可回到 `bb3f25da`，不涉及既有数据迁移。已产生的用户数据只在 `mca_generation_presets_v1`，删除该命名空间即完全回退，其他文件不受影响。

建议顺序：① N1/N2 线程与读改写修复 + N3 用例；② 单文件拆分提交，与 §3.2 的设置/更新改动隔离；③ 真机跑一遍预设页三动作并记录视觉基线；④ 配好 QAIRT/QNN 后跑产品构建与发布链路。提交、推送、发布需用户明确授权。

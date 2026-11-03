# MCA 闪退修复与批量导入 2026-11-03

范围：用户报告「云端模型一点发送就闪退」的根因修复、同批排查出的高概率闪退点，以及
「世界树（世界书）和预设导入一个个点太麻烦」的批量导入。
基线：`enterprise-refactor` @ `a9044aab`（0.3.2 版本准备提交之后）。

## 1. 闪退定位

崩溃发生在点击发送的瞬间、只在云端模型出现，说明触发点在「点发送 → 组装请求 → 提交状态」
这段同步代码，而不是网络返回段（网络失败只会走 `GenerateEvent.Error`，不杀进程）。

在这条链路上找会抛 `NoSuchElementException` / `IllegalArgumentException` 的写法，得到两处：

| 位置 | 原代码 | 触发条件 |
| --- | --- | --- |
| `ChatExecutionOperations.kt:78` | `chatSessions.first { it.id == activeChatSessionId }` | 活动会话 id 为 null，或该会话已不在列表中 |
| `ChatExecutionOperations.kt:79` | `requireNotNull(frozenSession.assistantSnapshot)` | 会话没有角色快照 |

两行都在 `viewModelScope.launch` 的协程体内。异常虽然在 `runChatGeneration` 的 `try` 范围内，
但在 UI 已被新请求接管或线程切换时，收尾逻辑本身会再次读取同一份状态，最终异常逃逸成
未捕获异常 → 进程被系统杀掉 → 用户看到「闪退」。

另一个放大因素：`ChatWorkflowCoordinator.sendPreparedMessage` 的两个提交协程原来只有
`finally`，没有 `catch`。任何提交前异常（上下文组装、准入、会话持久化、磁盘读失败）都会
穿过 `finally` 直接抛给线程默认处理器。

## 2. 修复内容

### 2.1 发送链路（直接对应本次闪退）

- `ChatExecutionOperations`：活动会话与角色快照改为可失败查找，失败时走既有收敛路径
  （状态栏提示「当前会话缺少角色信息…」+ 终止本轮），不再抛异常。
- `ChatWorkflowCoordinator`：两个提交协程（多附件预检与常规路径）都加了
  `catch (CancellationException) throw` + `catch (Throwable) reportSubmissionFailure(...)`，
  失败变成一条可见状态行，而不是崩溃；预检标志保证在 `finally` 里复位。
- `ContextCompressionCoordinator`：摘要阶段的 `checkNotNull(cloudConfig)` 改成显式
  `CancellationException`，模型在压缩过程中被删除时不再 NPE。

### 2.2 同批排查出的其他闪退点

| 文件 | 原写法 | 修复 |
| --- | --- | --- |
| `AssistantStore.loadSelectedAssistantId` | `assistants.first()` | 空助手表回退默认 id |
| `AssistantProfileOperations` 删除助手 | `remaining.first()` | 允许「删完没有剩余」，跳过选中切换 |
| `GenerationStateTransitions.restoreAfterConversationMutationFailure` | `durableSessions.first { }` | 改 `firstOrNull`，找不到按空回滚 |
| `ChatReplayUiChain.primaryBranch` | `blockBranches.first()` | 空列表回退 `RICH` |
| `ChatMarkdownRenderSupport` | `urlMatchAt(index)!!`、`token[0]` | 一次性取值 + 空 token 跳过 |
| `ImageLibraryCommitOperations` | `generatedImages.first()` 多处 | 空结果直接返回失败，不写库 |

## 3. 批量导入

用户反馈「一个个点好麻烦」，两处改成一次多选：

- 世界书（世界树）：`MainActivity` 的世界书选择器改用 `OpenAnyDocumentsContract`
  （`ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`，MIME 仍为 `*/*`，避免 OEM 文件管理器
  隐藏文件），新增 `importWorldBookFilesFor`：逐文件读取与提交，单个失败不影响其余，
  最后一条状态行报告「已导入 N 本，M 本失败」。
- 生成预设：预设页「导入预设」改用 `OpenMultipleDocuments`，新增
  `importGenerationPresetsFor`：一次选择、**一次** store 读改写、一条状态行。

同时把预设的删除与绑定保存移到 `Dispatchers.IO`——此前它们在点击回调里同步做
SharedPreferences 与磁盘写入，正是交接文档 N1 记录的问题。

## 4. 验证

- 架构：`python tools/architecture/check_architecture_rules.py` → 19/19 通过
- 编译：`.\scripts\verify-code.ps1 -SkipTests` → BUILD SUCCESSFUL
- 单测：`.\scripts\verify-code.ps1 -FullTests -Rerun` → 6,576 tests / 0 failures / 0 errors / 13 skipped
- 新增测试：`ChatSendCrashSafetyTest`（6）、`BatchImportContractTest`（4）
- 同步更新（断言加强而非放宽）：`ChatSessionAssistantSnapshotContractTest` 现在要求
  可失败查找的写法；`GenerationFlagAccountingContractTest` 记录协调器新增的一处收敛复位。

## 5. 未验证

- 未在真机复现原始闪退，也未在真机上验证修复后的云端发送链路；本轮结论来自源码级
  静态定位、契约测试与全量单测。
- 批量导入未在真机文件选择器上验证多选行为（含 OEM 契约差异）。
- 云端服务端行为（各家 `/models`、SSE 实现差异）不在本轮验证范围。

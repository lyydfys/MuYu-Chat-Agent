# V9 G00 全字段投影依赖清单（PROJECTION-DEPENDENCIES）

- 文档日期：2026-10-18
- 对应方案：`docs/proposals/MCA-GEMINI-GRADE-PATCH-V9.md` G00 / P02 / 8.2 / 8.3 / Q01
- 基线：仓库 `E:\MAC`，HEAD `bbad9747a4d32c1d2bcc19be8f9a0079aef3175c`，分支 `codex/enterprise-refactor`
- 事实等级：SOURCE＝本次直接读取源码确认；HYPOTHESIS＝需要运行时测量；NOT_RUN＝本次未执行
- 本文件是依赖清单，不是性能收益证明，也不是对任何优化的验收结论。本次未编译、未运行测试、未运行 Macrobenchmark。
- 阅读快照说明：本清单按读取时的 `ChatUiStateMapping.kt` / `ChatUiStateHistoryMapping.kt` / `ChatUiStateModelCatalogMapping.kt` / `ChatUiStateImageRuntimeMapping.kt` 内容编写。同一工作区还有其他 agent 的未提交改动；若这些映射文件随后被拆分或加缓存，必须按符号重新核对本清单，不能把本文件当作实现已完成。

## 0. 结论摘要

| 项目 | 实际状态 |
|---|---|
| `ChatUiState` 字段总数 | 76（`feature/chat/.../ChatScreenHeadTypes.kt`，本次逐字段清点） |
| 已登记依赖的字段 | 76 / 76（本文件第 3 节；无遗漏字段） |
| `ChatRouteInputs` 字段 | 13，已登记 13 / 13（第 4 节） |
| 生产调用点 | `app/.../MainActivity.kt` 的聊天 `ChatRoute(...)` 调用：`state = buildChatUiState(...)`、`inputs = ChatRouteInputs(...)` |
| 投影函数当前位置 | `app/src/main/java/com/muyuchat/mca/ChatUiStateMapping.kt`（主装配），加上 History / ModelCatalog / ImageRuntime 三个同包子映射 |
| 本次改动生产 UI | 无。本文件是审计产物；未改 Kotlin 生产源码 |
| 已验证项 | 依赖静态阅读、字段赋值覆盖（76 个字段在 `buildChatUiState` 中均有显式赋值） |
| 未验证项 | 编译、单测、等价性回归、Profile 报告、帧数据 → 全部 NOT_RUN（禁止写成 PASS） |

字段赋值覆盖的核对方式：解析 `ChatScreenHeadTypes.kt` 的 `ChatUiState` 构造参数得到 76 个名字，再对照 `buildChatUiState` 的具名实参；缺失字段 0。这只能证明“字段都被赋值”，不能证明依赖无遗漏；漏依赖的判定以第 3 节的人工读取为准，后续需要 P02 的等价/失效测试来证明。

## 1. 调用链与每次组合实际执行的内容

```text
MainUiState（UiStateStore 唯一权威）
  ├─ remember(state.chatSessions, state.assistants) → assistantSessionPresentations(...)
  ├─ remember(state.images) → imageGenerationHistoryById（逐图解析 generationMetadataJson）
  ├─ produceState(localImageModels, qnnImageVerificationCurrentByModelId) → preparedImageUi（IO）
  ├─ remember(9 个 key) → viewModel.chatImageToolPermissionUiState()
  └─ buildChatUiState(state, sessionPresentations, timestampLocale,
                      imageGenerationHistoryById, assistantImageToolPermission,
                      preparedImageUi, generationParamsForChatModel)
        ↓ 每次 MainActivity 组合都会调用（没有 remember 包裹整个结果）
     ChatRoute(state = ChatUiState, inputs = ChatRouteInputs, actions = mainChatScreenActions(...))
        ↓
     ChatViewModel（页面 state/inputs holder）→ ChatScreen → 各子页面
```

`buildChatUiState` 是普通函数（非 `@Composable`），但由组合直接调用，因此**当前实现下 MainActivity 每发生一次组合，下列工作都会重跑**：

- 整个 `ChatUiState` 的构造（76 个字段全部重新求值）；
- `buildChatHistoryItems`：遍历全部 `chatSessions`，逐行计算 `SimpleDateFormat("MM-dd HH:mm")` 文本（行级缓存已存在，见 5.1）；
- `buildLocalChatModelChoices`：本地全部模型的 backend/NPU/参数解析 + 云端全部聊天模型的排序；
- `buildAssistantUiItems`：每个角色 `GenerationParams.fromJson` + `assistant.toJson().toString(2)` 导出 JSON；
- 图片/生图/任务/LoRA/TI/upscaler/file/job 的全量映射；
- 每次调用都新建 `SimpleDateFormat` / `imageCreatedAtFormatter` / 读取 `TimeZone.getDefault()`。

这些是“依赖清单”描述的事实，不是已经测得的耗时。哪一段是热点必须由 Q01 的 trace/compiler reports + 微基准确认。

## 2. 投影函数的显式输入与环境输入

| 输入 | 类型 | 真实来源 | 失效语义 |
|---|---|---|---|
| `state` | `MainUiState` | `UiStateStore` 只读发布 | 任意被读取字段变化都必须反映；不能按“消息是否变化”粗粒度复用整表 |
| `sessionPresentations` | `Map<String, AssistantSessionPresentation>` | `MainActivity` 的 `remember(state.chatSessions, state.assistants)` → `assistantSessionPresentations(state.assistants)` | 依赖会话标题/归属/快照/消息末尾内容与角色名称；会话或角色列表变化即重建 |
| `timestampLocale` | `java.util.Locale` | `LocalConfiguration`/`LocalLocale` 环境 | 语言或系统 Locale 变化必须重算所有时间文本 |
| `imageGenerationHistoryById` | `Map<String, ImageGenerationHistoryMetadata?>` | `remember(state.images) { images.associate { id to fromJsonOrNull(generationMetadataJson) } }` | 任一图片 id 或 `generationMetadataJson` 变化；不要把它缓存成跨 deleted-image 的永久表 |
| `assistantImageToolPermission` | `ChatImageToolPermissionUiState` | `remember(activeChatSessionId, selectedChatBackend, selectedCloudChatModelId, selectedImageBackend, selectedLocalImageModelId, selectedCloudImageModelId, cloudModels, localImageModels, statusMessage) { viewModel.chatImageToolPermissionUiState() }` | 上述 key 变化才刷新；`chatImageToolPermissionUiState()` 内部若读取 key 之外的字段，当前 remember 会给出旧值（既有风险，P02/P03 需复核，见第 7 节） |
| `preparedImageUi` | `Map<String, PreparedLocalImageUi>` | `produceState(localImageModels, qnnImageVerificationCurrentByModelId)`，IO 侧 `prepareLocalImageUi` | 模型列表、QNN 校验状态变化；异步完成本身也要让 UI 重新读取该 map |
| `generationParamsForChatModel` | `(String) -> GenerationParams` | `viewModel::generationParamsForChatModel` | **函数身份不能代表返回值不变**：角色 override/全局参数/模型切换都可能改变返回值。P02 必须把它收窄为值或 owner 管理的版本输入 |
| 系统时区 | `TimeZone.getDefault()` | `buildChatHistoryItems` 内部每次调用读取 | 时区变化必须改变历史时间文本；跨时区/夏令时切换需要重算 |
| 进程级行缓存 | `sharedChatHistoryRowProjector` | `ChatUiStateHistoryMapping.kt` | 有界（每次投影移除已删除会话）；所有行输入参与缓存键；`@Synchronized` |

## 3. ChatUiState 全字段依赖清单（76 / 76）

图例：`→` 表示“由……计算/派生”；“失效键”列出**任一变化都必须重算该字段**的输入；只列出真实读取，不列未读字段的猜测。

### 3.1 模型状态、消息与输入

| # | 字段 | 真实来源（读取） | 失效键 / 说明 |
|---:|---|---|---|
| 1 | `modelLoadMessage` | `state.modelLoadStage.takeIf { state.busy && state.engineLifecycle == AgentEngineLifecycle.LOADING }` | `modelLoadStage`、`busy`、`engineLifecycle` |
| 2 | `modelReadinessLabel` | 云端时 `null`；否则 `state.localChatReadinessLabel()` → `engineLifecycle`、`stats.loaded`、`loadedModelId`、`isGenerating`、`stats.lastError` | `selectedChatBackend`、`engineLifecycle`、`stats.loaded`、`loadedModelId`、`isGenerating`、`stats.lastError`；文案分支属于显示合同，不能随手改字符串 |
| 3 | `messages` | `state.messages`（直接引用） | 消息列表身份/内容；本字段不复制、不解析 |
| 4 | `history` | `buildChatHistoryItems(state, sessionPresentations, timestampLocale)` | `chatSessions` 的 `id/title/updatedAt/messages.size/pinned/manualTitle/assistantId/assistantSnapshot/mixedAssistantHistory/messages 末尾非空内容`、`activeChatSessionId`、`sessionPresentations`、`timestampLocale`、`TimeZone.getDefault()`；行级键见 5.1 |
| 5 | `localModels` | `buildLocalChatModelChoices(state, generationParamsForChatModel)` | `cloudModels`（`kind=CHAT`/`configured`/`updatedAt`/`displayName`/`modelName`/`apiFormat.label`/`id`/`selectedCloudChatModelId`）、`selectedChatBackend`、`models`（`id/displayName/quant/sizeBytes/runtime.storageValue/runtime.label/lastLoadedAt/createdAt`）、`loadedModelId`、`isLocalChatModelLoaded`（`selectedChatBackend/loadedModelId/stats.loaded/engineLifecycle`）、`deviceProfile?.accelerationProfile`（`chipsetCode`、`qnnRuntime.usableForSmoke`）、`LiteRtQualcommRuntimeStager.variantForSocModel(chipsetCode)`、`stats.gpuOffloadSupported`、`params`/`generationParamsForChatModel(model.id)`（`advancedJson` → `nGpuLayers`）、`stats` |
| 6 | `imageModels` | 本地行：`state.localImageModels`（`id/path/displayName/sizeBytes/family.label/runtime.label/selectedLocalImageModelId`）+ `preparedImageUi[model.path]`（`capabilities`、`readiness`、`label`；`capabilities == null` 时整行被过滤）+ `imageCapabilities`（`supportedTaskModes`、`supportsNegativePrompt`、`supportsClipSkip`、`supportsVaeTiling`、`supportsTextualInversion`、`supportedTextualInversionFormats`、`supportsUltraFix`、`supportsLora`、`isQwenImage21Gguf`、`supportsLivePreview`、`previewMode`、`defaultPreviewInterval`、`maxBatchCount`、`executionDefaults.*`）+ `selectedImageBackend`、`selectedLocalImageModelId`。云端行：`cloudModels`（`kind IMAGE`/`configured`/`updatedAt`/`displayName`/`modelName`/`protocolLabel`/`id`/`selectedCloudImageModelId`）、`selectedImageBackend` | 上述全部；`preparedImageUi` 的异步完成也是失效点；`ChatModelChoice.init` 约束要求 `supportsImageUltraFix == false` 时不得传非 0 的 `imageUltraFixRequiredTileSize`（组合期约束，见 5.4） |
| 7 | `imageModelsLoading` | `state.localImageModels.any { preparedImageUi[it.path] == null }` | `localImageModels`（id/path 集合）、`preparedImageUi` 键集合；不能只用 `imageModels.isEmpty()` 判断 |
| 8 | `existingImageModelIds` | `existingImageModelIdsFor(state)` → `existingImageModelChoiceIds(localImageModels.id, cloudModels[kind=IMAGE].id)`，带 `MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX` / `CLOUD_IMAGE_MODEL_CHOICE_PREFIX` | 本地/云端生图模型 id 集合；前缀常量变化也要重算 |
| 9 | `assistants` | `buildAssistantUiItems(state)`：`state.assistants` 的 `id/name/avatar/tag/systemPrompt/defaultModelMode/defaultModelId/paramsJson/memoryEnabled/memorySummaryInterval/characterCardJson/webSearchEnabled/fileContextEnabled/toJson()` + `state.params`（`GenerationParams.fromJson` 的 fallback）+ `state.models`（默认本地模型 displayName）+ `state.cloudModels`（默认云端模型 modelName）+ `state.selectedAssistantId` | 上述全部；`paramsJson` 不变但 `state.params` 变化也会改变结果（fallback）；导出 JSON 是 UI 合同，不能为缓存省略 |
| 10 | `selectedAssistantId` | `state.selectedAssistantId` | 直接读取 |
| 11 | `worldBooks` | `buildWorldBookUiItems(state)`：`state.worldBooks` 的 `id/name/entries.size/entries[enabled,constant]/scope/assistantId/chatSessionId/enabled` + `state.selectedAssistantId` + `state.activeChatSessionId` | 世界书内容/scope/归属、当前角色、当前会话；scope 过滤是真实语义 |
| 12 | `knowledgeBases` | `buildKnowledgeBaseUiItems(state)`：`state.knowledgeBases`（`id/name/description/indexState`）+ `selectedKnowledgeBaseIds` + `knowledgeDocumentCounts[id]` + `knowledgeBaseImportingIds` | 上述全部；计数与导入集合不能只看知识库列表身份 |
| 13 | `statusMessage` | `state.statusMessage` | 直接读取 |
| 31 | `activeConversationId` | `state.activeChatSessionId` | 直接读取 |
| 32 | `conversationReadOnly` | `state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }` 的 `mixedAssistantHistory`、`assistantId`、`assistantSnapshot?.assistantId`，以及 `state.assistants.none { it.id == session.assistantId }`；无会话时 `false` | `chatSessions`（当前会话归属/快照/混合标志）、`activeChatSessionId`、`assistants`（id 集合） |
| 33 | `input` | `state.input` | 直接读取（高频） |
| 34 | `isGenerating` | `state.isGenerating` | 直接读取 |
| 35 | `generationPhase` | `state.generationPhase` | 直接读取（终态/phase 必须立即送达，不允许展示层降频吞掉） |
| 36 | `generationTokenProgress` | `state.generationTokenProgress` | 直接读取 |
| 37 | `generationPersistProgress` | `state.generationPersistProgress` | 直接读取 |
| 38 | `generationStats` | `state.generationStats` | 直接读取；与 `stats` 是不同通道，不能合并 |
| 39 | `promptContextUsage` | `state.promptContextUsage` | 直接读取 |

### 3.2 图片目录、图库、扩展与任务

| # | 字段 | 真实来源（读取） | 失效键 / 说明 |
|---:|---|---|---|
| 14 | `images` | `state.images` 每个字段 `id/name/uriString/source/prompt/createdAt/sizeBytes/width/height/favorite` + `imageGenerationHistoryById[image.id]`（`sourceGenerationAvailable`、`executionFacets()`、`upscaleHistory.lastOrNull().targetScale`、`displayDetails()`、`requestPrompt`、`modelId`、`modelName`、`inputDraft.taskMode.wireName`、`operationFacet().wireName`、`options.sampleMethod`、`toShareJson()`、`inputDraft.strength/controlStrength`、`options.*`、`loras`、`textualInversionIds`、`ultraFix`、`canRecreate()`）+ `imageCreatedAtFormatter`（`timestampLocale`）+ `formatAssetBytes(sizeBytes)` | 图片字段、`generationMetadataJson` 解析结果、`timestampLocale`；`imageGenerationHistoryById` 由调用方 `remember(state.images)` 生成，因此图片列表变化（含结构相等变化）会重建该 map |
| 15 | `generationHistoryInputUris` | `retainedGenerationImageContentReferences(historyReferences = imageGenerationHistoryById.values.filterNotNull().flatMap { requiredContentInputReferences() }, jobInputDrafts = state.imageJobs.mapNotNull { it.spec?.inputDraft })`，仅保留 `content://` | `imageGenerationHistoryById`、`state.imageJobs[*].spec.inputDraft` 的 `inputImageReference/maskImageReference/controlImageReference`；job 列表变化必须重算 |
| 16 | `imageLibraryBackup` | `imageLibraryBackupUiState(state)`：`state.imageLibraryBackup.running/importing/done/total/message/failed` | 备份状态的每个公开字段 |
| 17 | `imageLoras` | `buildImageLoraUiItems(state)`：`state.localImageLoras`（`id/name/sizeBytes/sha256`）、`activeLocalImageLoraIds`、`QwenImage21TurboSchedule.isOfficialV021Sha256(sha256)` | LoRA 列表内容、激活集合、sha256；`formatAssetBytes` 只读 `sizeBytes` |
| 18 | `imageLoraImporting` | `state.localImageLoraImporting` | 直接读取 |
| 19 | `imageLoraMessage` | `state.localImageLoraMessage` | 直接读取 |
| 20 | `imageTextualInversions` | `buildImageTextualInversionUiItems(state)`：`state.localImageTextualInversions`（`id/name/trigger/format.wireName/sizeBytes/sha256`）、`activeLocalImageTextualInversionIds`、`deletingLocalImageTextualInversionIds` | 列表内容、激活/删除集合 |
| 21 | `imageTextualInversionImporting` | `state.localImageTextualInversionLoading || state.localImageTextualInversionImporting` | 两个布尔量 |
| 22 | `imageTextualInversionMessage` | `state.localImageTextualInversionMessage` | 直接读取 |
| 23 | `imageUpscalers` | `buildImageUpscalerUiItems(state)`：`state.localImageUpscalers`（`id/name/sizeBytes/sha256`）、`selectedLocalImageUpscalerId`、`activeLocalImageUpscalerId`、`localImageUpscalerDeletingId` | 列表内容、选择/激活/删除 id |
| 24 | `selectedImageUpscalerId` | `state.selectedLocalImageUpscalerId` | 直接读取 |
| 25 | `imageUpscalerImporting` | `state.localImageUpscalerImporting` | 直接读取 |
| 26 | `imageUpscalerMessage` | `state.localImageUpscalerMessage` | 直接读取 |
| 27 | `imageUpscaleJob` | `state.imageUpscaleJob` 的 `id/spec.sourceImageSnapshot.id/spec.upscalerSnapshot.id/spec.upscalerSnapshot.name/spec.targetScale/status.label/status.terminal/status.failed/resultImageAssetId/message` | upscale job 的每个读取字段；`status.terminal` 由 `running` 取反派生 |
| 28 | `deferGenerationImageGrantRelease` | `state.deferGenerationImageGrantRelease` | 直接读取（授权生命周期语义，不能丢弃） |
| 29 | `files` | `buildFileAssetUiItems(state, timestampLocale)`：`state.files`（`id/name/mimeType/preview/createdAt/sizeBytes/truncated`）+ `SimpleDateFormat("MM-dd HH:mm", timestampLocale)` | 文件列表字段、`timestampLocale` |
| 30 | `imageJobs` | `buildImageGenerationUiJobs(state)`：`state.imageJobs`（`id/prompt/status.label/status.failed/status.terminal/modelId/modelName/backend/spec?.chatSessionId/spec?.chatMessageId/imageAssetId/imageAssetIds/completedProgressCount/spec?.options?.batchCount/previewUriString/previewMode/previewStep/previewRevision/previewWidth/previewHeight/message/spec?.promptExecution{method,originalPrompt,effectivePrompt,effectiveNegativePrompt}/startedAtMillis/startedAtElapsedMs`） | 上述全部；`translationPreview` 只在两种本地翻译方法下产生，属于真实显示语义 |

### 3.3 模型/生图选择与运行态

| # | 字段 | 真实来源（读取） | 失效键 / 说明 |
|---:|---|---|---|
| 40 | `contextCompressionThresholdPercent` | `state.contextCompressionThresholdPercent` | 直接读取 |
| 41 | `contextCompressionPending` | `state.contextCompressionPending` | 直接读取 |
| 42 | `contextSummaryModelSelection` | `state.contextSummaryModelSelection` | 直接读取 |
| 43 | `contextSummaryModels` | `buildContextSummaryModelOptions(state)`：固定 `current`/`deterministic`/`local` 三个选项 + `state.cloudModels.filter { kind == CHAT && configured }` 的 `id/displayName` | 云端聊天模型集合的 `id/displayName/kind/configured` |
| 44 | `contextSummaryHistory` | `buildContextSummaryHistoryItems(state)`：`chatSessions.firstOrNull { id == activeChatSessionId }?.contextSummaries` 的 `version/source/text/sourceMessageIds/active/createdAt/structuredSummary.coverageLimited/evidence.size/omittedExcerptCount/omittedCharacterCount/summaryModelIdentity/summaryDiagnostic` | 当前会话 `contextSummaries` 内容与 `activeChatSessionId`；会话切换必须重算 |
| 45 | `canUndoContextSummary` | `canUndoContextSummaryFor(state)`：当前会话 `contextSummaries.any { it.active }` | 当前会话 id、summary active 标志 |
| 46 | `pendingImageTranslationDraft` | `state.pendingChatImageTranslationDraft` | 直接读取 |
| 47 | `pendingImageActionDraft` | `state.pendingChatImageActionDraft` | 直接读取 |
| 48 | `browserTask` | `state.browserTask` | 直接读取 |
| 49 | `selectedModelId` | 云端：`selectedCloudChatModelId` 加 `MainViewModel.CLOUD_MODEL_CHOICE_PREFIX`；本地：`loadedModelId` | `selectedChatBackend`、`selectedCloudChatModelId`、`loadedModelId` |
| 50 | `selectedModelName` | 云端：`cloudModels.firstOrNull { id == selectedCloudChatModelId }?.modelName`；本地：`loadedModelName` | 上述字段 + `cloudModels` 的 `id/modelName` |
| 51 | `selectedModelIsCloud` | `selectedChatBackend == ChatBackend.CLOUD` | 直接读取 |
| 52 | `selectedModelRuntimeLabel` | 云端：匹配的 `cloudModel.apiFormat.label`；本地：`models.firstOrNull { id == loadedModelId }?.runtime?.label` | `selectedChatBackend`、`selectedCloudChatModelId`、`cloudModels.id/apiFormat.label`、`loadedModelId`、`models.id/runtime.label` |
| 53 | `selectedImageModelId` | 云端：`selectedCloudImageModelId` 加云端前缀；本地：`selectedLocalImageModelId` 加本地前缀 | `selectedImageBackend`、两个已选 id |
| 54 | `selectedImageModelName` | 云端：`cloudModels.firstOrNull { id == selectedCloudImageModelId && kind == IMAGE }?.modelName`；本地：`localImageModels.firstOrNull { id == selectedLocalImageModelId }?.displayName` | 上述字段 + 两个模型列表的 id/name |
| 55 | `selectedImageModelIsCloud` | `selectedImageBackend == ImageBackend.CLOUD` | 直接读取 |
| 56 | `assistantImageToolAvailable` | `assistantImageToolPermission.available` | 见第 2 节 caller remember key；值由 `viewModel.chatImageToolPermissionUiState()` 产生 |
| 57 | `assistantImageToolUnavailableReason` | `assistantImageToolPermission.unavailableReason` | 同上；所有原因字符串都是 UI 合同 |
| 58 | `assistantImageToolAutoApproval` | `assistantImageToolPermission.autoApproval` | 同上 |
| 59 | `assistantImageToolCanChange` | `assistantImageToolPermission.canChange` | 同上 |
| 60 | `stats` | `state.stats` | 直接读取；`RuntimeStats` 内部字段驱动多个派生态（见 #2/#5/#63） |
| 61 | `apiEnabled` | `state.apiEnabled` | 直接读取 |
| 62 | `restEnabled` | `state.restEnabled` | 直接读取 |
| 63 | `generationParams` | `state.params` | 直接读取（UI 参数编辑合同） |
| 64 | `reasoningMode` | `state.params.reasoningMode` | `params.reasoningMode` |

### 3.4 联网搜索、视觉能力与生成参数

| # | 字段 | 真实来源（读取） | 失效键 / 说明 |
|---:|---|---|---|
| 65 | `webSearchEnabled` | `state.webSearchConfig.enabled` | 直接读取 |
| 66 | `webSearchConfigured` | `state.webSearchConfig.realSearchConfigured` →（`primaryConfigured && !isPublicCheckSource`）或任一 configured backup 的 `realSearchConfigured`；`primaryConfigured` 读 `enabled/endpointForRequest()/provider.requiresApiKey/apiKey` | `webSearchConfig` 的 `enabled/provider/endpoint/apiKey/backupProviders`（configured 派生） |
| 67 | `webSearchEnabledForTurn` | `webSearchTurnMode == ON`，或 `FOLLOW && assistants.firstOrNull { id == selectedAssistantId }?.webSearchEnabled == true` | `webSearchTurnMode`、`selectedAssistantId`、当前角色的 `webSearchEnabled` |
| 68 | `webSearchStatusMessage` | `state.webSearchStatusMessage` | 直接读取（不得缓存过期文案） |
| 69 | `webSearchTurnModeLabel` | `state.toWebSearchTurnModeLabel()`：`webSearchConfig.enabled`、`assistants[selectedAssistantId].webSearchEnabled`、`webSearchTurnMode`、`webSearchConfig.triggerMode` | 上述字段；文案是全应用冻结字符串之一 |
| 70 | `webSearchResearchMode` | `(state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).name` | `webSearchResearchModeOverride`、`webSearchConfig.researchMode` |
| 71 | `webSearchResearchModeLabel` | `(state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).label` | 同上 |
| 72 | `webSearchResearchOverridden` | `state.webSearchResearchModeOverride != null` | 直接读取 |
| 73 | `webSearchProviderLabel` | `buildString`，读取 `webSearchConfig.realSearchConfigured/realSearchProviderLabel/providerLabel/triggerMode.label/isPublicCheckSource/enabled` | 上述字段；`realSearchProviderLabel` 由 configured providers 派生 |
| 74 | `visionCapabilityLabel` | `state.chatVisionCapability()` → `VisionCapabilityUi.label`（云端/本地分支很多，见下） | 见 #74–#76 合并依赖 |
| 75 | `visionCapabilityDetail` | 同上 `.detail` | 同上 |
| 76 | `visionCapabilityReady` | 同上 `.ready` | `selectedChatBackend`；云端：`cloudModels`（`id/kind=CHAT/configured/supportsVision`）与 `selectedCloudChatModelId`；本地：`models.firstOrNull { id == loadedModelId }` 的 `runtime/visionProjectorPath/acceptsImageInput(...)`、`nativeStatsJson` 解析出的 `visionReady/visionModelKnownTextOnly`、`liteRtVisionInputAvailable`；**不含任何芯片/机型准入判断**（视觉可用性来自模型与真实加载状态） |

## 4. ChatRouteInputs 全字段依赖（13 / 13）

`ChatRouteInputs` 定义在 `feature/chat/.../ChatRoute.kt`；生产值在 `MainActivity.kt` 的 `ChatRoute(inputs = ChatRouteInputs(...))` 构造。

| # | 字段 | 真实来源（读取） | 失效键 / 说明 |
|---:|---|---|---|
| 1 | `offlineTranslationStatus` | `state.offlineTranslationStatus` | 直接读取 |
| 2 | `offlineTranslationInstalled` | `state.offlineTranslationInstalled` | 直接读取 |
| 3 | `offlineTranslationInstalling` | `state.offlineTranslationInstalling` | 直接读取 |
| 4 | `offlineTranslationDownloading` | `state.offlineTranslationDownloading` | 直接读取 |
| 5 | `offlineTranslationDownloadProgress` | `state.offlineTranslationDownloadProgress` | 直接读取 |
| 6 | `assistantMemories` | `state.assistantMemories.map { AssistantMemoryUiItem(m.id, m.assistantId, m.scope, m.content, m.source, m.createdAt) }` | 记忆列表的 `id/assistantId/scope/content/source/createdAt` |
| 7 | `appMenuOpen` | `MainActivity` 的 `rememberSaveable { mutableStateOf(false) }`（组合局部状态） | 页面局部状态；不属于 `MainUiState`，不能塞进全局投影 |
| 8 | `chatBackground` | `effectiveChatAppearance.toUiState()` ← `resolveChatAppearance(sessionAppearance, assistantAppearance, state.globalChatAppearance)`；`sessionAppearance = activeChatSession?.appearanceOverride`；`assistantAppearance = selectedAssistant?.appearance?.takeUnless { it.isDefault }`；`selectedAssistant = state.assistants.firstOrNull { id == selectedAssistantId }` | `chatSessions[activeChatSessionId].appearanceOverride`、`assistants[selectedAssistantId].appearance`、`globalChatAppearance`、`activeChatSessionId`、`selectedAssistantId` |
| 9 | `globalChatBackground` | `state.globalChatAppearance.toUiState()` | 全局外观每个公开字段 |
| 10 | `assistantChatBackground` | `assistantAppearance?.toUiState()` | 当前角色 appearance（非默认时才有值） |
| 11 | `sessionChatBackground` | `sessionAppearance?.toUiState()` | 当前会话 appearanceOverride |
| 12 | `hasActiveChatSession` | `activeChatSession != null`（`chatSessions.firstOrNull { id == activeChatSessionId }`） | `chatSessions`、`activeChatSessionId` |
| 13 | `chatBackgroundImporting` | `state.chatBackgroundImporting` | 直接读取 |

## 5. 领域级失效、缓存现状与注意事项

### 5.1 History（已有行级缓存，不是本次新增）

`ChatHistoryRowProjector`（进程级 `sharedChatHistoryRowProjector`）以 `ChatHistoryRowDependencies` 为键缓存 `ChatHistoryItem`，键包含：

`title/displayName/summary/assistantId/updatedAt/timestampLocale/时区快照(id, rawOffset, dstSavings, usesDaylightTime, offsetAtUpdatedAt)/messageCount/pinned/manualTitle/selected/mixedAssistantHistory`。

- 缓存键包含**原始 `updatedAt`**，即使格式化文本没变也参与比较；这避免了用冻结时间戳伪造命中。
- 每次投影 `rowsBySessionId.keys.retainAll(currentSessionIds)`，缓存不超过当前会话数；删除会话释放对应缓存。
- `summary` 来自 `sessionPresentations`，其生成函数读取会话消息末尾非空内容；`sessionPresentations` 由 `MainActivity` 按 `state.chatSessions + state.assistants` remember，故这两类变化会重建 presentation，再驱动行缓存命中/失效。
- `history` 的未验证点：`TimeZone.getDefault()` 在函数内读取，若同一进程内时区改变而 `timestampLocale` 不变，现有行键中的 `timestampTimeZone` 快照会变化（`TimeZone.getDefault()` 每次返回新实例？`getDefault()` 通常返回缓存 clone），实际失效行为需要专门测试（P02b/P02c）。

### 5.2 Assistants / WorldBook / Knowledge / ContextSummary

- `assistants` 每次全量 `GenerationParams.fromJson(assistant.paramsJson, state.params)` + `assistant.toJson().toString(2)`；`exportJson` 是公开 UI 合同（导出/导入），不能为了复用省略。
- `worldBooks` 的 scope 过滤依赖 `selectedAssistantId` 与 `activeChatSessionId`，不能只按 `worldBooks` 列表身份缓存。
- `knowledgeBases` 的 `documentCount`/`importing` 来自独立集合 `knowledgeDocumentCounts`/`knowledgeBaseImportingIds`；知识库对象本身没有这些字段。
- `contextSummaryHistory`/`canUndoContextSummary` 只读**当前会话**的 `contextSummaries`，会话切换即失效。

### 5.3 模型/图片目录

- `localModels` 读 `RuntimeStats.gpuOffloadSupported`、`ModelManifest.runtime`、`GenerationParams.advancedJson`、`deviceProfile.accelerationProfile`；这些跨领域读取意味着“目录”不是静态列表。
- `imageModels` 读 `preparedImageUi`（异步 IO 结果）与 `LocalImageUiCapabilitiesSnapshot.executionDefaults`；`preparedImageUi` 未就绪时本地行被过滤、`imageModelsLoading=true`。**不能**把“空列表”当作空态缓存。
- `existingImageModelIds` 只依赖模型 id 与前缀；与 `images`/任务无关。
- `imageModelsLoading` 只依赖 `localImageModels.path` 与 `preparedImageUi` 的键，不依赖 capability 内容。

### 5.4 组合期约束与风险（SOURCE）

- `ChatModelChoice.init` 在组合期运行多项 `require`；`imageModels` 映射已显式处理 `supportsUltraFix=false` 时 `imageUltraFixRequiredTileSize=0`，避免整个页面因单行参数自相矛盾而中止（既有注释记录了该事故风险）。
- `buildChatUiState` 的 `images` 分支对每个图片调用 `SimpleDateFormat.format`、`formatAssetBytes`、`generationMetadataJson` 的多层派生；这是 P02f 的候选热点，但必须先用 Q01 trace 证明，而不是从代码形状推断。
- `state.imageJobs` 的 `spec` 是可空嵌套；`generationHistoryInputUris` 与 `imageJobs` 都读取 `job.spec?.inputDraft`，拆分时二者应共享同一份 job 快照，避免一个域读到新列表、另一个域读到旧列表。
- `generationParamsForChatModel` 是函数而非值：`localModels` 的 `selectedBackendId`/`customGpuLayers` 会因模型参数变化而变化，但函数身份不变。P02e 若按函数身份缓存会得到旧选择。

## 6. P02 拆分验收：本清单如何转成测试

针对每个域，最低测试要求（本次未创建、未运行 → NOT_RUN）：

| 域 | 等值测试 | 失效测试 | 现有可复用保护 |
|---|---|---|---|
| Message/Conversation/Input/Generation | `buildChatUiState` 新旧逐字段等值（当前无完整 oracle；`ChatProjectionFormattingEquivalenceTest` 只覆盖时间/图片格式化） | 任一高频字段变化必须反映 | `ChatProjectionFormattingEquivalenceTest`（仅局部） |
| History | 行输出与旧映射逐字段等值（含 pinned/manualTitle/mixed/selected/时区） | 只改 `messages` 末尾不应重算所有角色 JSON；`updatedAt` 变化必须允许更新 | `ChatHistoryRowProjector` 既有缓存键 |
| Model/Image Catalog | 目录行逐字段等值（含 backendOptions/selectedBackendId/customGpuLayers） | 仅 `generationStats` 变化不得重算目录；`preparedImageUi` 完成必须使本地行出现 | 无独立测试（NOT_RUN） |
| Image runtime/library/jobs/files | 图片/任务/LoRA/TI/upscaler/file 行逐字段等值（含 revision/preview/translation/authorization） | 单张图更新不得重算全部图片；删除会话/图片必须释放缓存 | 无独立测试（NOT_RUN） |
| Assistants/WorldBook/Knowledge/Context | 逐字段等值（含 `exportJson`、fallback params、scope、count、importing、active summaries） | fallback `state.params` 变化必须使角色行失效；scope 变化必须重算 | 无独立测试（NOT_RUN） |
| ChatRouteInputs | 13 字段等值（含三层背景解析） | 只改背景/菜单/翻译进度不能丢失其它输入 | 无独立测试（NOT_RUN） |

测试方法（V9 8.4）：保留旧实现作为 oracle 或采集经审核的完整输出 fixture；不得从新实现生成 expected；每个依赖条目只改变一个输入并验证真实语义；无关高频输入变化时断言“未重算或复用同一已验证结果”；功能值与执行次数分别断言。

## 7. 已知风险与未验证项

| 编号 | 事实/风险 | 状态 |
|---|---|---|
| R1 | 投影函数由组合直接调用，未被 `remember` 包裹；任何触发 MainActivity 重组的字段都会重跑全部 76 个字段的求值 | SOURCE；Q01 需用 compiler reports + trace 确认实际重组频率与耗时 |
| R2 | `assistantImageToolPermission` 的 `remember` key 集合可能与 `chatImageToolPermissionUiState()` 内部读取不完全一致 | SOURCE（静态）；需 P03/P02 复核，当前未改 |
| R3 | `generationParamsForChatModel` 是方法引用，返回值可变而身份不变 | SOURCE；P02e 若按函数身份缓存会陈旧 |
| R4 | `imageGenerationHistoryById` 由 `remember(state.images)` 生成；`state.images` 列表结构相等时不会重建，但单张图片的 `generationMetadataJson` 变化会通过列表相等性传播（取决于记录 equals） | HYPOTHESIS；需要专门的失效测试 |
| R5 | `history` 行缓存是进程级 `@Synchronized` 单例；跨线程调用只有序列化成本，没有并发正确性问题，但会成为高频投影的锁竞争点 | SOURCE；需要 trace 测量锁等待，不能仅凭代码推断 |
| R6 | 本次未运行任何测试/编译/Macrobenchmark；`ChatUiState` 与 `ChatRouteInputs` 的“全字段覆盖”是静态赋值核对 | NOT_RUN |
| R7 | 本清单不含 token 级性能结论；未测帧、未测 CPU、未测分配 | NOT_RUN |

## 8. 回退点

本文件单独新增，不改变任何生产行为；回退方式为删除/还原该文档。若后续 P02 依据本清单拆分投影，回退边界应按域恢复 `buildChatUiState` 的原委托（见 V9 P02 回退条款），不得用文档删除掩盖行为变化。

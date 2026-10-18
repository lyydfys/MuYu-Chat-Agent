package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.AssistantUiItem
import com.muyuchat.feature.chat.ChatModelChoice
import com.muyuchat.feature.chat.ContextSummaryModelOption
import com.muyuchat.feature.chat.ContextSummaryUiItem
import com.muyuchat.feature.chat.FileAssetUiItem
import com.muyuchat.feature.chat.ImageAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiJob
import com.muyuchat.feature.chat.ImageLoraUiItem
import com.muyuchat.feature.chat.ImageTextualInversionUiItem
import com.muyuchat.feature.chat.ImageUpscalerUiItem
import com.muyuchat.feature.chat.KnowledgeBaseUiItem
import com.muyuchat.feature.chat.WorldBookUiItem
import java.util.Locale
import java.util.TimeZone

/**
 * P02d/e/f/g：`buildChatUiState` 各低频目录投影的进程内单条复用缓存。
 *
 * 设计边界：
 *
 * - **只缓存纯映射结果**。每个方法在输入未变时返回上一次的同一个输出实例；输入变化时调用
 *   调用方传入的 compute（仍是原来的 `ChatUiState*Mapping.kt` 映射函数）。缓存不复制业务
 *   状态、不写入 `MainUiState`、不持有 Activity/Composable 作用域。
 * - **容量固定**：每个域只保留一条 `(key, value)`。旧输入和旧输出在下次 miss 时被替换，
 *   因此长流式会话不会把每个 streaming 版本留在 Map 里。
 * - **线程安全**：单条缓存在自己的监视器上串行化；同一输入并发 miss 最多重复计算一次，
 *   不会返回半更新的一对 key/value。
 * - **依赖契约**：每个 Key 只包含被缓存映射函数**实际读取**的字段。新增读取字段时必须同步
 *   加入对应 Key（以及 `ChatUiProjectionDependencyInvalidationTest`），否则会返回过期结果。
 *   集合按内容等值判断（同引用直接命中），与历史行缓存相同的假设：`MainUiState` 中的列表是
 *   不可变快照，不会原地修改。
 * - **隐式环境输入显式化**：时间文本依赖 `Locale` 与默认 `TimeZone`，`formatAssetBytes`/
 *   `displayDetails` 的 `String.format` 还依赖**默认 Locale**；这些进程级默认值都进入对应
 *   Key，而不是假设它们不会变化。
 *
 * 这里没有新增业务写入路径，也没有新的执行 owner：缓存的生命周期就是进程，失效由输入决定。
 */
internal class ChatUiProjectionCache {
    private val assistantsMemo =
        SingleEntryProjectionMemo<AssistantProjectionKey, List<AssistantUiItem>>()
    private val localModelsMemo =
        SingleEntryProjectionMemo<LocalChatModelsProjectionKey, List<ChatModelChoice>>()
    private val imagesMemo =
        SingleEntryProjectionMemo<ImageAssetsProjectionKey, List<ImageAssetUiItem>>()
    private val filesMemo =
        SingleEntryProjectionMemo<FileAssetsProjectionKey, List<FileAssetUiItem>>()
    private val imageJobsMemo =
        SingleEntryProjectionMemo<ImageJobsProjectionKey, List<ImageGenerationUiJob>>()
    private val worldBooksMemo =
        SingleEntryProjectionMemo<WorldBooksProjectionKey, List<WorldBookUiItem>>()
    private val knowledgeBasesMemo =
        SingleEntryProjectionMemo<KnowledgeBasesProjectionKey, List<KnowledgeBaseUiItem>>()
    private val imageLorasMemo =
        SingleEntryProjectionMemo<ImageLorasProjectionKey, List<ImageLoraUiItem>>()
    private val textualInversionsMemo =
        SingleEntryProjectionMemo<TextualInversionsProjectionKey, List<ImageTextualInversionUiItem>>()
    private val upscalersMemo =
        SingleEntryProjectionMemo<UpscalersProjectionKey, List<ImageUpscalerUiItem>>()
    private val contextSummaryMemo =
        SingleEntryProjectionMemo<ContextSummaryProjectionKey, ChatContextSummaryProjection>()

    /**
     * `buildAssistantUiItems` 的输入面（见 [AssistantProjectionKey]）。
     */
    fun assistants(state: MainUiState, compute: () -> List<AssistantUiItem>): List<AssistantUiItem> =
        assistantsMemo.getOrCompute(
            AssistantProjectionKey(
                assistants = state.assistants,
                fallbackParams = state.params,
                localModels = state.models,
                cloudModels = state.cloudModels,
                selectedAssistantId = state.selectedAssistantId
            ),
            compute
        )

    /**
     * `buildLocalChatModelChoices` 的输入面（见 [LocalChatModelsProjectionKey]）。
     *
     * `generationParamsForChatModel` 的返回值本身是依赖：函数身份相同不代表返回的参数没变
     * （每个模型的 profile 可以独立持久化）。因此这里按模型把**当前返回值**放进 key，并把同
     * 一份快照交给 compute —— 旧实现同一模型最多读取两次，这里一次；命中和失配都使用同一份
     * 参数值，key 与输出不会再出现两次读取之间的差异。
     */
    fun localModels(
        state: MainUiState,
        generationParamsForChatModel: (String) -> GenerationParams,
        compute: (Map<String, GenerationParams>) -> List<ChatModelChoice>
    ): List<ChatModelChoice> {
        val modelParams = LinkedHashMap<String, GenerationParams>(state.models.size)
        state.models.forEach { model ->
            modelParams[model.id] = if (state.isLocalChatModelLoaded(model.id)) {
                state.params
            } else {
                generationParamsForChatModel(model.id)
            }
        }
        return localModelsMemo.getOrCompute(
            LocalChatModelsProjectionKey(
                cloudModels = state.cloudModels,
                selectedChatBackend = state.selectedChatBackend,
                selectedCloudChatModelId = state.selectedCloudChatModelId,
                models = state.models,
                loadedModelId = state.loadedModelId,
                runtimeStats = LocalModelRuntimeStatsDependency.from(state.stats),
                acceleration = LocalModelAccelerationDependency.from(state.deviceProfile),
                engineLifecycle = state.engineLifecycle,
                isGenerating = state.isGenerating,
                params = state.params,
                modelParams = modelParams
            )
        ) { compute(modelParams) }
    }

    /**
     * `state.images.map { ImageAssetUiItem(...) }` 的输入面（见 [ImageAssetsProjectionKey]）。
     */
    fun images(
        state: MainUiState,
        generationHistoryById: Map<String, ImageGenerationHistoryMetadata?>,
        timestampLocale: Locale,
        compute: () -> List<ImageAssetUiItem>
    ): List<ImageAssetUiItem> =
        imagesMemo.getOrCompute(
            ImageAssetsProjectionKey(
                images = state.images,
                generationHistoryById = generationHistoryById,
                timestampLocale = timestampLocale,
                timestampTimeZone = ChatProjectionTimeZoneSnapshot.from(TimeZone.getDefault()),
                defaultFormatLocale = Locale.getDefault()
            ),
            compute
        )

    /**
     * `buildFileAssetUiItems` 的输入面（见 [FileAssetsProjectionKey]）。
     */
    fun files(
        state: MainUiState,
        timestampLocale: Locale,
        compute: () -> List<FileAssetUiItem>
    ): List<FileAssetUiItem> =
        filesMemo.getOrCompute(
            FileAssetsProjectionKey(
                files = state.files,
                timestampLocale = timestampLocale,
                timestampTimeZone = ChatProjectionTimeZoneSnapshot.from(TimeZone.getDefault()),
                defaultFormatLocale = Locale.getDefault()
            ),
            compute
        )

    /**
     * `buildImageGenerationUiJobs` 的输入面（见 [ImageJobsProjectionKey]）。
     */
    fun imageJobs(
        state: MainUiState,
        compute: () -> List<ImageGenerationUiJob>
    ): List<ImageGenerationUiJob> =
        imageJobsMemo.getOrCompute(ImageJobsProjectionKey(state.imageJobs), compute)

    /**
     * `buildWorldBookUiItems` 的输入面（见 [WorldBooksProjectionKey]）。
     */
    fun worldBooks(
        state: MainUiState,
        compute: () -> List<WorldBookUiItem>
    ): List<WorldBookUiItem> =
        worldBooksMemo.getOrCompute(
            WorldBooksProjectionKey(
                worldBooks = state.worldBooks,
                selectedAssistantId = state.selectedAssistantId,
                activeChatSessionId = state.activeChatSessionId
            ),
            compute
        )

    /**
     * `buildKnowledgeBaseUiItems` 的输入面（见 [KnowledgeBasesProjectionKey]）。
     */
    fun knowledgeBases(
        state: MainUiState,
        compute: () -> List<KnowledgeBaseUiItem>
    ): List<KnowledgeBaseUiItem> =
        knowledgeBasesMemo.getOrCompute(
            KnowledgeBasesProjectionKey(
                knowledgeBases = state.knowledgeBases,
                selectedKnowledgeBaseIds = state.selectedKnowledgeBaseIds,
                knowledgeDocumentCounts = state.knowledgeDocumentCounts,
                knowledgeBaseImportingIds = state.knowledgeBaseImportingIds
            ),
            compute
        )

    /**
     * `buildImageLoraUiItems` 的输入面（见 [ImageLorasProjectionKey]）。
     */
    fun imageLoras(
        state: MainUiState,
        compute: () -> List<ImageLoraUiItem>
    ): List<ImageLoraUiItem> =
        imageLorasMemo.getOrCompute(
            ImageLorasProjectionKey(
                localImageLoras = state.localImageLoras,
                activeLocalImageLoraIds = state.activeLocalImageLoraIds,
                defaultFormatLocale = Locale.getDefault()
            ),
            compute
        )

    /**
     * `buildImageTextualInversionUiItems` 的输入面（见 [TextualInversionsProjectionKey]）。
     */
    fun textualInversions(
        state: MainUiState,
        compute: () -> List<ImageTextualInversionUiItem>
    ): List<ImageTextualInversionUiItem> =
        textualInversionsMemo.getOrCompute(
            TextualInversionsProjectionKey(
                localImageTextualInversions = state.localImageTextualInversions,
                activeLocalImageTextualInversionIds = state.activeLocalImageTextualInversionIds,
                deletingLocalImageTextualInversionIds = state.deletingLocalImageTextualInversionIds,
                defaultFormatLocale = Locale.getDefault()
            ),
            compute
        )

    /**
     * `buildImageUpscalerUiItems` 的输入面（见 [UpscalersProjectionKey]）。
     */
    fun upscalers(
        state: MainUiState,
        compute: () -> List<ImageUpscalerUiItem>
    ): List<ImageUpscalerUiItem> =
        upscalersMemo.getOrCompute(
            UpscalersProjectionKey(
                localImageUpscalers = state.localImageUpscalers,
                selectedLocalImageUpscalerId = state.selectedLocalImageUpscalerId,
                activeLocalImageUpscalerId = state.activeLocalImageUpscalerId,
                localImageUpscalerDeletingId = state.localImageUpscalerDeletingId,
                defaultFormatLocale = Locale.getDefault()
            ),
            compute
        )

    /**
     * `buildContextSummaryModelOptions` + `buildContextSummaryHistoryItems` +
     * `canUndoContextSummaryFor` 的输入面（见 [ContextSummaryProjectionKey]）。
     *
     * [activeChatSession] 必须是 `state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }`；
     * 由 [buildChatUiState] 传入，避免在热点路径重复扫描会话列表。
     */
    fun contextSummary(
        state: MainUiState,
        activeChatSession: ChatSessionRecord?,
        compute: () -> ChatContextSummaryProjection
    ): ChatContextSummaryProjection =
        contextSummaryMemo.getOrCompute(
            ContextSummaryProjectionKey(
                cloudModels = state.cloudModels,
                activeChatSessionId = state.activeChatSessionId,
                activeContextSummaries = activeChatSession?.contextSummaries.orEmpty()
            ),
            compute
        )
}

/** 进程内共享缓存；[buildChatUiState] 的默认参数，测试可注入独立实例断言复用/失效。 */
internal val sharedChatUiProjectionCache = ChatUiProjectionCache()

/**
 * 单条 `(key, value)` 记忆器。
 *
 * 命中条件只有 key 等值；输出实例原样返回，所以 Compose 侧能继续拿到同一个稳定引用。
 * 计算在锁内完成：该缓存只服务纯映射，compute 不会回调本记忆器，也不会做 IO 等待。
 */
internal class SingleEntryProjectionMemo<K : Any, V : Any> {
    private var cachedKey: K? = null
    private var cachedValue: V? = null

    @Synchronized
    fun getOrCompute(key: K, compute: () -> V): V {
        val cached = cachedValue
        if (cached != null && cachedKey == key) return cached
        val computed = compute()
        cachedKey = key
        cachedValue = computed
        return computed
    }
}

/**
 * `buildAssistantUiItems` 的完整输入：
 *
 * - `assistants`：`id/name/avatar/tag/systemPrompt/defaultModelMode/defaultModelId/paramsJson/
 *   characterCardJson/memoryEnabled/memorySummaryInterval/webSearchEnabled/fileContextEnabled/
 *   appearance/createdAt/updatedAt`（`exportJson = assistant.toJson().toString(2)` 读取全部字段）。
 * - `fallbackParams`：`GenerationParams.fromJson(assistant.paramsJson, state.params)` 的缺省值。
 * - `localModels`：`defaultModelMode == "local"` 时按 `defaultModelId` 查 `displayName`。
 * - `cloudModels`：`defaultModelMode == "cloud"` 时按 `defaultModelId + kind == CHAT` 查 `modelName`。
 * - `selectedAssistantId`：每行的 `selected`。
 */
internal data class AssistantProjectionKey(
    val assistants: List<AssistantRecord>,
    val fallbackParams: GenerationParams,
    val localModels: List<ModelManifest>,
    val cloudModels: List<CloudModelRecord>,
    val selectedAssistantId: String
)

/**
 * `buildLocalChatModelChoices` 的完整输入：
 *
 * - `cloudModels`：云端聊天行的 `id/displayName/modelName/apiFormat.label/updatedAt/configured`。
 * - `selectedChatBackend`、`selectedCloudChatModelId`：云端行 `loaded`。
 * - `models`：本地行的 `id/displayName/quant/sizeBytes/runtime/lastLoadedAt/createdAt`。
 * - `loadedModelId`：排序权重、`isLocalChatModelLoaded`、`selectedBackendId` 的 stats 选择条件。
 * - `runtimeStats`：`isLocalChatModelLoaded`、`selectedChatBackendId`、`localChatReadinessLabel`、
 *   `gpuOffloadSupported` 实际读取的 stats 子字段（不含 decoder 速率等无关数值）。
 * - `acceleration`：NPU 可用性判定读取的 `chipsetCode / qnnRuntime.usableForSmoke /
 *   packagedLiteRtTransportAvailable`。
 * - `engineLifecycle`、`isGenerating`：`localChatReadinessLabel`。
 * - `params`：已加载模型的 `selectedBackendId/customGpuLayers` 输入。
 * - `modelParams`：每个模型实际使用的参数（已加载模型为 `state.params`，未加载模型为
 *   `generationParamsForChatModel(model.id)` 的当前返回值）。
 */
internal data class LocalChatModelsProjectionKey(
    val cloudModels: List<CloudModelRecord>,
    val selectedChatBackend: ChatBackend,
    val selectedCloudChatModelId: String?,
    val models: List<ModelManifest>,
    val loadedModelId: String?,
    val runtimeStats: LocalModelRuntimeStatsDependency,
    val acceleration: LocalModelAccelerationDependency,
    val engineLifecycle: AgentEngineLifecycle,
    val isGenerating: Boolean,
    val params: GenerationParams,
    val modelParams: Map<String, GenerationParams>
)

/** `RuntimeStats` 中真正被模型目录投影读取的字段快照。 */
internal data class LocalModelRuntimeStatsDependency(
    val loaded: Boolean,
    val backend: String,
    val backendDevices: String,
    val gpuOffloadSupported: Boolean?,
    val gpuOffloadActive: Boolean,
    val gpuOffloadAllocationObserved: Boolean,
    val gpuOffloadExecutionObserved: Boolean,
    val gpuAutoFallbackApplied: Boolean,
    val lastError: String?
) {
    companion object {
        fun from(stats: RuntimeStats): LocalModelRuntimeStatsDependency =
            LocalModelRuntimeStatsDependency(
                loaded = stats.loaded,
                backend = stats.backend,
                backendDevices = stats.backendDevices,
                gpuOffloadSupported = stats.gpuOffloadSupported,
                gpuOffloadActive = stats.gpuOffloadActive,
                gpuOffloadAllocationObserved = stats.gpuOffloadAllocationObserved,
                gpuOffloadExecutionObserved = stats.gpuOffloadExecutionObserved,
                gpuAutoFallbackApplied = stats.gpuAutoFallbackApplied,
                lastError = stats.lastError
            )
    }
}

/** 设备加速段中真正被模型目录投影读取的字段快照。 */
internal data class LocalModelAccelerationDependency(
    val chipsetCode: String?,
    val qnnRuntimeUsableForSmoke: Boolean?,
    val packagedLiteRtTransportAvailable: Boolean
) {
    companion object {
        fun from(profile: DeviceProfile?): LocalModelAccelerationDependency {
            val acceleration = profile?.accelerationProfile
            val chipsetCode = acceleration?.chipsetCode
            return LocalModelAccelerationDependency(
                chipsetCode = chipsetCode,
                qnnRuntimeUsableForSmoke = acceleration?.qnnRuntime?.usableForSmoke,
                packagedLiteRtTransportAvailable =
                    LiteRtQualcommRuntimeStager.variantForSocModel(chipsetCode) != null
            )
        }
    }
}

/**
 * `state.images.map { ... }` 的完整输入：
 *
 * - `images`：`id/name/uriString/source/prompt/createdAt/sizeBytes/width/height/favorite`。
 * - `generationHistoryById`：每张图按 id 取 `ImageGenerationHistoryMetadata`，行里的
 *   `upscaleTargetScale/generationDetails/generationPrompt/generationModelId/generationModelName/
 *   generationTaskMode/generationOperation/generationSampler/generationRuntime/generationDevice/
 *   parameterShareJson/generationPreset/generationCanRecreate` 全部由它派生。
 * - `timestampLocale` 与 `timestampTimeZone`：`createdAtText` 的 `SimpleDateFormat` 输入。
 * - `defaultFormatLocale`：`formatAssetBytes` 与 `displayDetails()` 里 `"%.1f".format(...)`/
 *   `String.format` 读取的是 **默认 Locale**，不是 `timestampLocale`。
 */
internal data class ImageAssetsProjectionKey(
    val images: List<ImageAssetRecord>,
    val generationHistoryById: Map<String, ImageGenerationHistoryMetadata?>,
    val timestampLocale: Locale,
    val timestampTimeZone: ChatProjectionTimeZoneSnapshot,
    val defaultFormatLocale: Locale
)

/**
 * `buildFileAssetUiItems` 的完整输入：
 *
 * - `files`：`id/name/mimeType/preview/createdAt/sizeBytes/truncated`。
 * - `timestampLocale` 与 `timestampTimeZone`：`createdAtText` 的 `SimpleDateFormat` 输入。
 * - `defaultFormatLocale`：`formatAssetBytes` 的 `String.format` 默认 Locale。
 */
internal data class FileAssetsProjectionKey(
    val files: List<FileAssetRecord>,
    val timestampLocale: Locale,
    val timestampTimeZone: ChatProjectionTimeZoneSnapshot,
    val defaultFormatLocale: Locale
)

/**
 * `buildImageGenerationUiJobs` 的完整输入：整个 `imageJobs` 列表。
 *
 * 输出读取 `prompt/status.label/modelId/modelName/backend/spec.chatSessionId/spec.chatMessageId/
 * spec.options.batchCount/spec.promptExecution/preview* / imageAssetIds/message/startedAt*`，
 * 全部位于列表元素内，因此列表内容即依赖。
 */
internal data class ImageJobsProjectionKey(
    val imageJobs: List<ImageGenerationJobRecord>
)

/**
 * `buildWorldBookUiItems` 的完整输入：
 *
 * - `worldBooks`：过滤条件 `scope/assistantId/chatSessionId` 与输出
 *   `id/name/entries.size/enabled/entries(enabled, constant)`。
 * - `selectedAssistantId`：`ASSISTANT` scope 过滤。
 * - `activeChatSessionId`：`CHAT` scope 过滤。
 */
internal data class WorldBooksProjectionKey(
    val worldBooks: List<WorldBookRecord>,
    val selectedAssistantId: String,
    val activeChatSessionId: String?
)

/**
 * `buildKnowledgeBaseUiItems` 的完整输入：
 *
 * - `knowledgeBases`：`id/name/description/indexState`。
 * - `selectedKnowledgeBaseIds`：`selected`。
 * - `knowledgeDocumentCounts`：`documentCount`。
 * - `knowledgeBaseImportingIds`：`importing`。
 */
internal data class KnowledgeBasesProjectionKey(
    val knowledgeBases: List<KnowledgeBaseRecord>,
    val selectedKnowledgeBaseIds: Set<String>,
    val knowledgeDocumentCounts: Map<String, Int>,
    val knowledgeBaseImportingIds: Set<String>
)

/**
 * `buildImageLoraUiItems` 的完整输入：
 *
 * - `localImageLoras`：`id/name/sizeBytes/sha256`。
 * - `activeLocalImageLoraIds`：`inUse`（`isQwenImage21ViggleV021` 只由 `sha256` 派生）。
 * - `defaultFormatLocale`：`formatAssetBytes` 的 `String.format` 默认 Locale。
 */
internal data class ImageLorasProjectionKey(
    val localImageLoras: List<LocalImageLoraRecord>,
    val activeLocalImageLoraIds: Set<String>,
    val defaultFormatLocale: Locale
)

/**
 * `buildImageTextualInversionUiItems` 的完整输入：
 *
 * - `localImageTextualInversions`：`id/name/trigger/format/sizeBytes/sha256`。
 * - `activeLocalImageTextualInversionIds` 与 `deletingLocalImageTextualInversionIds`：`inUse`。
 * - `defaultFormatLocale`：`formatAssetBytes` 的 `String.format` 默认 Locale。
 */
internal data class TextualInversionsProjectionKey(
    val localImageTextualInversions: List<TextualInversionArtifact>,
    val activeLocalImageTextualInversionIds: Set<String>,
    val deletingLocalImageTextualInversionIds: Set<String>,
    val defaultFormatLocale: Locale
)

/**
 * `buildImageUpscalerUiItems` 的完整输入：
 *
 * - `localImageUpscalers`：`id/name/sizeBytes/sha256`。
 * - `selectedLocalImageUpscalerId`：`selected`。
 * - `activeLocalImageUpscalerId`：`inUse`。
 * - `localImageUpscalerDeletingId`：`deleting`。
 * - `defaultFormatLocale`：`formatAssetBytes` 的 `String.format` 默认 Locale。
 */
internal data class UpscalersProjectionKey(
    val localImageUpscalers: List<LocalImageUpscalerRecord>,
    val selectedLocalImageUpscalerId: String?,
    val activeLocalImageUpscalerId: String?,
    val localImageUpscalerDeletingId: String?,
    val defaultFormatLocale: Locale
)

/**
 * 上下文摘要三个输出（模型选项、历史、可撤销）的完整输入：
 *
 * - `cloudModels`：模型选项里的 `kind == CHAT && configured` 行与 `displayName`。
 * - `activeChatSessionId` + `activeContextSummaries`：历史项与 `canUndo` 只读当前会话的
 *   `contextSummaries`（active session 由调用方按 `activeChatSessionId` 解析后传入）。
 */
internal data class ContextSummaryProjectionKey(
    val cloudModels: List<CloudModelRecord>,
    val activeChatSessionId: String?,
    val activeContextSummaries: List<ContextSummaryRecord>
)

/** 上下文摘要的一体化投影结果，保证三个字段来自同一次计算。 */
internal data class ChatContextSummaryProjection(
    val models: List<ContextSummaryModelOption>,
    val history: List<ContextSummaryUiItem>,
    val canUndo: Boolean
)

/**
 * 时区快照：`SimpleDateFormat` 的格式化结果由"区域规则"决定，ID/偏移/DST 属性是它的稳定
 * 显式输入。与历史行缓存使用同一套快照字段，避免把可变 `TimeZone` 实例放进 key。
 */
internal data class ChatProjectionTimeZoneSnapshot(
    val id: String,
    val rawOffset: Int,
    val dstSavings: Int,
    val usesDaylightTime: Boolean
) {
    companion object {
        fun from(timeZone: TimeZone): ChatProjectionTimeZoneSnapshot =
            ChatProjectionTimeZoneSnapshot(
                id = timeZone.id,
                rawOffset = timeZone.rawOffset,
                dstSavings = timeZone.dstSavings,
                usesDaylightTime = timeZone.useDaylightTime()
            )
    }
}

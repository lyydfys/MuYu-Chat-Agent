package com.muyuchat.mca

import android.app.Application
import android.content.SharedPreferences
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatRuntime

internal data class MainUiBootstrapResult(
    val initialDeviceProfile: DeviceProfile,
    val initialChatSessions: List<ChatSessionRecord>,
    val initialKnowledgeBases: List<KnowledgeBaseRecord>,
    val uiState: MainUiState
)

internal object MainUiBootstrapFactory {
    fun create(
        application: Application,
        globalChatAppearanceStore: GlobalChatAppearanceStore,
        localImageUpscalerStore: LocalImageUpscalerStore,
        webSearchStore: WebSearchStore,
        webSearchDiagnosticStore: WebSearchDiagnosticStore,
        isRuntimeAvailable: (LocalChatRuntime) -> Boolean,
        modelScopeClient: ModelScopeClient,
        agentLogger: AgentDecisionLogger,
        benchmarkHistoryLogger: BenchmarkHistoryLogger,
        deviceProfileReader: DeviceProfileReader,
        chatSessionStore: ChatSessionStore,
        worldBookStore: WorldBookStore,
        knowledgeBaseStore: KnowledgeBaseStore,
        cloudApiStore: CloudApiStore,
        localImageModelStore: LocalImageModelStore,
        localImageLoraStore: LocalImageLoraStore,
        imageUpscalerPreferences: SharedPreferences,
        modelGenerationProfileStore: ModelGenerationProfileStore,
        contextCompressionPreferences: SharedPreferences,
        apiKey: String,
        currentOpenApiAddress: String,
        apiUrl: (String) -> String,
        skipInitialModelDiscovery: Boolean,
        initialAppUpdateState: AppUpdateState,
        initialParams: GenerationParams,
        initialAssistants: List<AssistantRecord>,
        initialStoredSelectedAssistantId: String,
        initialChatSessions: List<ChatSessionRecord>
    ): MainUiBootstrapResult {
        val initialDeviceProfile = deviceProfileReader.read()
        val initialPersistentPrefixCacheEnabled = GenerationPreferences.loadPersistentPrefixCacheEnabled(application)
        val initialAssistantMemories = initialAssistants.flatMap { assistant ->
            chatSessionStore.loadMemories(assistant.id)
        }
        val initialGlobalChatAppearance = globalChatAppearanceStore.load()
        val initialWorldBooks = worldBookStore.load()
        val initialKnowledgeBases = knowledgeBaseStore.loadBases()
        val initialStartupSession = initialChatSessions
            .latestSessionForAssistant(initialStoredSelectedAssistantId)
            ?: initialChatSessions.firstOrNull()
        val initialKnowledgeDocumentCounts: Map<String, Int> = emptyMap()
        val initialKnowledgeBaseIds = initialStartupSession
            ?.id
            ?.let(knowledgeBaseStore::selectedKnowledgeBaseIds)
            .orEmpty()
        val initialImages: List<ImageAssetRecord> = emptyList()
        val initialFiles: List<FileAssetRecord> = emptyList()
        val initialSelectedAssistantId = initialStartupSession
            ?.assistantSnapshot
            ?.assistantId
            ?.takeIf { assistantId -> initialAssistants.any { it.id == assistantId } }
            ?: initialStartupSession
            ?.assistantId
            ?.takeIf { assistantId -> initialAssistants.any { it.id == assistantId } }
            ?: initialStoredSelectedAssistantId
        val initialSelectedAssistant = initialAssistants.firstOrNull { it.id == initialSelectedAssistantId }
        val initialLocalImageModels = if (skipInitialModelDiscovery) emptyList() else localImageModelStore.loadModels(discover = false)
        val initialLocalImageLoras = localImageLoraStore.load()
        val initialLocalImageUpscalers = localImageUpscalerStore.load()
        val initialSelectedLocalImageUpscalerId = imageUpscalerPreferences
            .getString(IMAGE_UPSCALER_SELECTED_ID, null)
            ?.takeIf { id -> initialLocalImageUpscalers.any { it.id == id } }
            ?: initialLocalImageUpscalers.firstOrNull()?.id
        val initialSelectedLocalImageModelId = localImageModelStore.loadSelectedModelId()
            ?.takeIf { id ->
                initialLocalImageModels.any {
                    it.id == id &&
                        it.isReadyForLocalImageGeneration()
                }
            }
            ?: initialLocalImageModels.firstOrNull {
                it.isReadyForLocalImageGeneration()
            }?.id
        val initialCloudModels = cloudApiStore.loadModels()
        val initialSessionCloudChatModelId = initialStartupSession
            ?.takeIf { it.modelMode.equals("cloud", ignoreCase = true) }
            ?.modelId
            ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.CHAT } }
        val initialSelectedCloudChatModelId = initialSessionCloudChatModelId
            ?: cloudApiStore.loadSelectedCloudChatModelId()
            ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.CHAT } }
            ?: initialCloudModels.firstOrNull { it.kind == CloudModelKind.CHAT }?.id
        val initialSelectedCloudImageModelId = cloudApiStore.loadSelectedCloudImageModelId()
            ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.IMAGE } }
            ?: initialCloudModels.firstOrNull { it.kind == CloudModelKind.IMAGE }?.id
        val initialCloudConfig = initialCloudModels
            .firstOrNull { it.id == initialSelectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
            ?.toChatConfig()
            ?: cloudApiStore.load()
        val initialWebSearchConfig = webSearchStore.load()
        val initialWebSearchDiagnostics = webSearchDiagnosticStore.load()
        val initialSelectedBackend = initialStartupSession
            ?.modelMode
            ?.toChatBackendOrNull()
            ?: cloudApiStore.loadSelectedBackend()
        val initialSelectedImageBackend = localImageModelStore.loadSelectedBackend()

        val uiState = MainUiState(
            messages = initialStartupSession?.messages.orEmpty(),
            chatSessions = initialChatSessions,
            activeChatSessionId = initialStartupSession?.id,
            images = initialImages,
            files = initialFiles,
            localImageModels = initialLocalImageModels,
            localImageLoras = initialLocalImageLoras,
            localImageTextualInversionLoading = true,
            localImageTextualInversionMessage = "正在加载 Textual Inversion 库…",
            localImageUpscalers = initialLocalImageUpscalers,
            selectedLocalImageUpscalerId = initialSelectedLocalImageUpscalerId,
            selectedLocalImageModelId = initialSelectedLocalImageModelId,
            selectedImageBackend = when (initialSelectedImageBackend) {
                ImageBackend.LOCAL -> if (initialSelectedLocalImageModelId != null) ImageBackend.LOCAL else ImageBackend.CLOUD
                ImageBackend.CLOUD -> ImageBackend.CLOUD
            },
            cloudApiConfig = initialCloudConfig,
            cloudModels = initialCloudModels,
            selectedCloudChatModelId = initialSelectedCloudChatModelId,
            selectedCloudImageModelId = initialSelectedCloudImageModelId,
            assistants = initialAssistants,
            assistantMemories = initialAssistantMemories,
            selectedAssistantId = initialSelectedAssistantId,
            globalChatAppearance = initialGlobalChatAppearance,
            worldBooks = initialWorldBooks,
            knowledgeBases = initialKnowledgeBases,
            knowledgeDocumentCounts = initialKnowledgeDocumentCounts,
            selectedKnowledgeBaseIds = initialKnowledgeBaseIds,
            selectedChatBackend = if (initialSelectedBackend == ChatBackend.CLOUD && initialCloudConfig.configured) {
                ChatBackend.CLOUD
            } else {
                ChatBackend.LOCAL
            },
            models = emptyList(),
            mnnRuntimeAvailable = isRuntimeAvailable(LocalChatRuntime.MNN_CPU),
            recommendedRemoteModels = sortRecommendedModels(
                modelScopeClient.userFacingRecommendedModels(),
                initialDeviceProfile
            ),
            params = loadModelGenerationParamsFor(
                profileStore = modelGenerationProfileStore,
                backend = if (initialSelectedBackend == ChatBackend.CLOUD) {
                    ChatBackend.CLOUD
                } else {
                    ChatBackend.LOCAL
                },
                modelId = if (initialSelectedBackend == ChatBackend.CLOUD) {
                    initialSelectedCloudChatModelId
                } else {
                    initialStartupSession?.modelId
                },
                defaults = initialParams
            ).let { modelPreset ->
                initialSelectedAssistant?.toGenerationParams(modelPreset) ?: modelPreset
            },
            agentLogs = agentLogger.recent(),
            benchmarkHistory = benchmarkHistoryLogger.recent(),
            deviceProfile = initialDeviceProfile,
            apiEnabled = false,
            restEnabled = false,
            apiKey = apiKey,
            localApiAddress = apiUrl("127.0.0.1"),
            openApiAddress = currentOpenApiAddress,
            webSearchConfig = initialWebSearchConfig,
            webSearchDiagnostics = initialWebSearchDiagnostics,
            persistentPrefixCacheEnabled = initialPersistentPrefixCacheEnabled,
            contextCompressionThresholdPercent = ContextCompressionCoordinator.loadThreshold(contextCompressionPreferences).percent,
            contextSummaryModelSelection = ContextCompressionCoordinator.loadSummaryModelSelection(contextCompressionPreferences),
            appUpdate = initialAppUpdateState
        )

        return MainUiBootstrapResult(
            initialDeviceProfile = initialDeviceProfile,
            initialChatSessions = initialChatSessions,
            initialKnowledgeBases = initialKnowledgeBases,
            uiState = uiState
        )
    }
}

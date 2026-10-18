package com.muyuchat.mca

import com.muyuchat.core.advisor.AgentDecisionLog
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.benchmark.BenchmarkHistoryRecord
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentCandidateProgress

// The single UI state object the app renders from.
//
// Moved here verbatim from MainViewModel during P2, which is a move and not a split: this is
// still one data class holding one union of state, exactly as before. The task warns against
// splitting a union state into several Flows that could tear into inconsistent snapshots, and
// nothing here does that -- the class is unchanged field for field and default for default.
//
// It is worth having in its own file because 153 fields is the shape of the whole app in one
// place, and reading it should not require opening a 20000-line ViewModel. Its transitions live
// next door in presentation/GenerationStateTransitions.kt, which imports this type.
//
// Kept in com.muyuchat.mca, so every referencing file -- MainViewModel, MainActivity,
// LocalChatReadiness, ReplyGenerationMetrics and the four tests -- reaches it exactly as before.
//
// No field, type, default or ordering was edited.

data class MainUiState(
    val offlineTranslationInstalling: Boolean = false,
    val offlineTranslationDownloading: Boolean = false,
    val offlineTranslationDownloadBytes: Long = 0L,
    val offlineTranslationDownloadTotalBytes: Long = 0L,
    val offlineTranslationDownloadProgress: Float? = null,
    val offlineTranslationStatus: String = "未安装",
    val offlineTranslationInstalled: Boolean = false,
    val pendingChatImageTranslationDraft: com.muyuchat.feature.chat.ChatImageTranslationDraftUi? = null,
    val pendingChatImageActionDraft: com.muyuchat.feature.chat.ChatImageActionDraftUi? = null,
    val contentImportPreview: ContentImportPreview? = null,
    val contentImportCommitting: Boolean = false,
    val modelLoadStage: String? = null,
    val tab: AppTab = AppTab.CHAT,
    val messages: List<ChatMessage> = emptyList(),
    val chatSessions: List<ChatSessionRecord> = emptyList(),
    val activeChatSessionId: String? = null,
    val browserTask: com.muyuchat.feature.chat.BrowserTask? = null,
    val images: List<ImageAssetRecord> = emptyList(),
    val imageLibraryBackup: ImageLibraryBackupState = ImageLibraryBackupState(),
    val files: List<FileAssetRecord> = emptyList(),
    val imageJobs: List<ImageGenerationJobRecord> = emptyList(),
    val localImageModels: List<LocalImageModelRecord> = emptyList(),
    val localImageLoras: List<LocalImageLoraRecord> = emptyList(),
    val chatImageLoraSelections: List<Pair<String, Double>>? = emptyList(),
    val activeLocalImageLoraIds: Set<String> = emptySet(),
    val localImageLoraImporting: Boolean = false,
    val localImageLoraMessage: String = "",
    val localImageTextualInversions: List<TextualInversionArtifact> = emptyList(),
    val activeLocalImageTextualInversionIds: Set<String> = emptySet(),
    val deletingLocalImageTextualInversionIds: Set<String> = emptySet(),
    val localImageTextualInversionLoading: Boolean = false,
    val localImageTextualInversionImporting: Boolean = false,
    val localImageTextualInversionMessage: String = "",
    val localImageUpscalers: List<LocalImageUpscalerRecord> = emptyList(),
    val selectedLocalImageUpscalerId: String? = null,
    val activeLocalImageUpscalerId: String? = null,
    val localImageUpscalerImporting: Boolean = false,
    val localImageUpscalerDeletingId: String? = null,
    val localImageUpscalerMessage: String = "",
    val imageUpscaleJob: ImageUpscaleJobRecord? = null,
    val deferGenerationImageGrantRelease: Boolean = false,
    /**
     * Calculated on Dispatchers.IO from each exact QNN stamp.  An absent entry
     * is deliberately treated as not current by UI callers.
     */
    val qnnImageVerificationCurrentByModelId: Map<String, Boolean> = emptyMap(),
    /** Optional exact bundle/chipset/runtime diagnostic evidence; never an admission list. */
    val qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    val qairtVerifiedRecommendationIds: Set<String> = emptySet(),
    val selectedLocalImageModelId: String? = null,
    val selectedImageBackend: ImageBackend = ImageBackend.CLOUD,
    val cloudApiConfig: CloudApiConfig = CloudApiConfig(),
    val cloudModels: List<CloudModelRecord> = emptyList(),
    val selectedCloudChatModelId: String? = null,
    val selectedCloudImageModelId: String? = null,
    val editingCloudModelId: String? = null,
    val selectedChatBackend: ChatBackend = ChatBackend.LOCAL,
    val assistants: List<AssistantRecord> = emptyList(),
    val assistantMemories: List<MemoryRecord> = emptyList(),
    val selectedAssistantId: String = AssistantRecord.DEFAULT_ID,
    val globalChatAppearance: ChatAppearance = ChatAppearance(),
    val chatBackgroundImporting: Boolean = false,
    val worldBooks: List<WorldBookRecord> = emptyList(),
    val knowledgeBases: List<KnowledgeBaseRecord> = emptyList(),
    val knowledgeDocumentCounts: Map<String, Int> = emptyMap(),
    val knowledgeBaseImportingIds: Set<String> = emptySet(),
    val selectedKnowledgeBaseIds: Set<String> = emptySet(),
    val input: String = "",
    val isGenerating: Boolean = false,
    /** Exact runtime stage; a missing token progress remains intentionally indeterminate. */
    val generationPhase: GenerationPhase? = null,
    val generationTokenProgress: TokenProgress? = null,
    /** Byte-level KV serialization progress while native persists a state file. */
    val generationPersistProgress: PersistProgress? = null,
    /** Statistics emitted by the active request only; never reuse a prior turn's rates. */
    val generationStats: RuntimeStats? = null,
    val promptContextUsage: PromptContextUsage? = null,
    /** User-selected automatic context compression threshold. */
    val contextCompressionThresholdPercent: Int = 80,
    val contextSummaryModelSelection: String = "current",
    /** A manual compression request is consumed by the next generation. */
    val contextCompressionPending: Boolean = false,
    val settingsOperations: SettingsOperationState = SettingsOperationState(),
    val persistentPrefixCacheEnabled: Boolean = true,
    val persistentPrefixCacheEntryCount: Int = 0,
    val persistentPrefixCacheBytes: Long = 0L,
    val models: List<ModelManifest> = emptyList(),
    val mnnRuntimeAvailable: Boolean = false,
    val remoteFiles: List<RemoteModelFile> = emptyList(),
    val recommendedRemoteModels: List<ModelScopeRecommendedModel> = emptyList(),
    val hubModels: List<ModelScopeHubModel> = emptyList(),
    val hubQuery: String = "Qwen3.5 MNN",
    val hubPage: Int = 1,
    val hubTotalCount: Int = 0,
    val repoInput: String = "",
    val downloadFileName: String? = null,
    val downloadTaskId: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus? = null,
    val downloadPhase: com.muyuchat.feature.modelhub.ModelHubDownloadPhase? = null,
    val downloadFailureSource: com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource? = null,
    val downloadIntegrityStatus: String = "UNKNOWN",
    val downloadIntegrityMessage: String? = null,
    val downloadExecutionStatus: String = "UNKNOWN",
    val downloadExecutionMessage: String? = null,
    val importTasks: List<com.muyuchat.feature.modelhub.ModelImportTaskUi> = emptyList(),
    val busy: Boolean = false,
    val loadedModelId: String? = null,
    val loadedModelName: String? = null,
    val statusMessage: String? = null,
    val params: GenerationParams = GenerationParams(),
    val stats: RuntimeStats = RuntimeStats(),
    val logs: List<com.muyuchat.core.telemetry.RuntimeMetrics> = emptyList(),
    val agentLogs: List<AgentDecisionLog> = emptyList(),
    val deviceProfile: DeviceProfile? = null,
    val agentRecommendation: AgentRecommendation? = null,
    val benchmark: BenchmarkResult? = null,
    val benchmarkHistory: List<BenchmarkHistoryRecord> = emptyList(),
    val autoTuningInProgress: Boolean = false,
    val lastAutoTuningSummary: String? = null,
    val localStabilitySmokeSummary: String? = null,
    val rollbackParams: GenerationParams? = null,
    val profileId: String? = null,
    val revision: Long? = null,
    val profileRecordState: AgentProfileRecordState = AgentProfileRecordState.NONE,
    val verification: AgentProfileVerification = AgentProfileVerification.UNKNOWN,
    val engineLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.UNLOADED,
    val tuningJobState: AgentTuningJobState = AgentTuningJobState.IDLE,
    val reloadRequired: Boolean = false,
    val pendingProfile: AgentPendingProfile? = null,
    val rollbackProfile: AgentRollbackProfile? = null,
    val tuningEtaSeconds: Long? = null,
    val tuningPhase: String? = null,
    val tuningCandidateProgress: AgentCandidateProgress = AgentCandidateProgress(),
    val diagnosticReport: String = "",
    val preference: UserPreference = UserPreference(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    val apiKey: String = "",
    val localApiAddress: String = "",
    val openApiAddress: String = "",
    val nativeStatsJson: String = "{}",
    val webSearchConfig: WebSearchConfig = WebSearchConfig(),
    val webSearchTurnMode: WebSearchTurnMode = WebSearchTurnMode.FOLLOW,
    val webSearchResearchModeOverride: WebSearchResearchMode? = null,
    val webSearchOneShotEnabled: Boolean = false,
    val webSearchStatusMessage: String? = null,
    val webSearchDiagnostics: List<WebSearchDiagnosticRecord> = emptyList(),
    val appUpdate: AppUpdateState = AppUpdateState()
)

enum class SettingsCleanupOperation {
    CHAT_HISTORY,
    IMAGE_LIBRARY,
    FILE_LIBRARY,
    PERSISTENT_PREFIX_CACHE
}

enum class WebSearchOperation {
    PREFLIGHT,
    CONFIG_TEST,
    TURN_TEST
}

data class SettingsOperationState(
    val cleanup: SettingsCleanupOperation? = null,
    val webSearch: WebSearchOperation? = null
)

/**
 * The chat-image LoRA selection that should actually condition a chat-triggered image, or null when
 * no LoRA conditioning applies.
 *
 * Two writers store `null` on purpose to mean "the stored selection is no longer usable, ask the user
 * to re-select": [MainViewModel.reportMissingLocalImageLoraSelection] (the selected LoRA was deleted)
 * and [MainViewModel.updateChatImageLoraSelection] (`frozen.takeIf { valid }` yields null when the
 * resolved selection is invalid). `null` is therefore a legitimate persisted state, not an
 * impossible one, and callers must degrade instead of asserting.
 *
 * Returns null when the local backend is not selected, so that callers keep their existing early-exit
 * behaviour for cloud-backed images.
 */
internal fun MainUiState.usableChatImageLoraSelections(): List<Pair<String, Double>>? {
    if (selectedImageBackend != ImageBackend.LOCAL) return null
    return chatImageLoraSelections
}

// R3/C3-S14：三个会话写入门 guard 自 MainViewModel 顶层化（体逐字；VM 内调用点零改动）。

internal fun MainUiState.activeAssistantSnapshot(): AssistantConversationSnapshot? =
    activeChatSessionId
        ?.let { sessionId -> chatSessions.firstOrNull { it.id == sessionId } }
        ?.assistantSnapshot

internal fun MainUiState.canWriteToActiveAssistantSession(): Boolean {
    val sessionId = activeChatSessionId ?: return false
    val session = chatSessions.firstOrNull { it.id == sessionId } ?: return false
    val owner = session.assistantId ?: return false
    return !session.mixedAssistantHistory && owner == selectedAssistantId &&
        session.assistantSnapshot?.assistantId == owner &&
        assistants.any { it.id == owner }
}

internal fun MainUiState.selectedAssistant(): AssistantRecord? =
    assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()

package com.muyuchat.mca.composition

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.*
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * [MainCoordinatorHost] defines host capabilities required by [MainCoordinatorAssembly]
 * to assemble and wire all application coordinators without polluting MainViewModel with verbose wiring.
 */
internal interface MainCoordinatorHost {
    val store: UiStateStore
    val scope: CoroutineScope
    val application: Application
    val container: McaAppContainer
    val engine: McaInferenceService

    val initialChatSessions: List<ChatSessionRecord>
    val chatContextComposer: ChatContextComposer
    val tuningOwner: TuningOwner
    val agentLogger: AgentDecisionLogger
    val benchmarkRunner: BenchmarkRunner
    val diagnosticsOperations: DiagnosticsOperations
    val localApiRuntimeOwner: LocalApiRuntimeOwner
    val modelScopeClient: ModelScopeClient
    val installationScopeId: String
    val localApiIdempotencyJournalLock: Any
    val characterMemorySummaryCoordinator: CharacterMemorySummaryCoordinator
    val knowledgeLibraryPort: KnowledgeLibraryPort
    val contextCompressionPreferences: SharedPreferences
    val imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation
    val persistentPrefixCacheOperationSequence: AtomicLong
    val modelIdentityReferenceMigration: ModelIdentityReferenceMigration
    val skipInitialModelDiscovery: Boolean
    val imagePromptTokenMeasurementHost: ImagePromptTokenMeasurementHost
    val visionAttachmentPort: VisionAttachmentPort
    val chatTerminalSettlementPort: ChatTerminalSettlementPort
    val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator
    val chatAppearanceCoordinator: ChatAppearanceCoordinator
    val imageUpscaleOwner: ImageUpscaleOwner
    val chatImageRequestPort: ChatImageRequestPort
    val imageLibraryCommitPort: ImageLibraryCommitPort
    val imageGenerationOwner: ImageGenerationOwner
    val offlinePromptTranslationService: OfflinePromptTranslationService

    var activeWebSearchTaskId: String?
    var foregroundRecoveryJob: Job?

    fun hostShowStatus(message: String?)
    fun hostFail(message: String)
    fun hostBusy(message: String)
    fun hostFailDownload(message: String)
    fun hostLoadModel(requestedModel: ModelManifest)
    fun hostLaunchModelOperation(message: String, operation: suspend CoroutineScope.() -> Unit): Job
    fun hostTryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease?
    fun hostReleaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean
    fun hostPersistFiles(files: List<FileAssetRecord>): Unit
    fun hostPersistChatSessions(
        sessions: List<ChatSessionRecord>? = null,
        binding: Pair<String, Set<String>>? = null,
        generationRunId: Long? = null
    ): Job?
    fun hostBuildChatImageRetrySnapshot(request: ChatGeneratedImageRequest, sessionId: String): ImageGenerationJobSpec?
    fun hostChatImageNegativePromptSupported(state: MainUiState): Boolean
    fun hostCancelImageGenerationForJob(expectedJobId: String?)
    fun hostReserveUiGenerationStart(): UiGenerationReservation?
    fun hostStartGeneration(
        requestMessages: List<ChatMessage>,
        reservation: UiGenerationReservation? = null,
        toolExchanges: List<ChatToolExchange> = emptyList(),
        paramsOverride: GenerationParams? = null,
        offerAssistantImageTool: Boolean = true,
        chatModelIdOverride: String? = null,
        continuationRequestId: String? = null,
        preflightCompression: ContextCompressionResult? = null
    ): Boolean
    fun hostSendChatImageRequestWithPromptBridge(
        visibleUserText: String,
        prompt: String,
        requestedOutputCount: Int? = null,
        allowCloudTranslationFallback: Boolean = false
    )
    fun hostSendPreparedMessage(state: MainUiState, preparedInput: PreparedChatInput)
    fun hostPersistModelGenerationParams(state: MainUiState, params: GenerationParams)
    fun hostPersistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams)
    fun hostUpdateParams(params: GenerationParams)
    fun hostCancelGenerationJob(): UiGenerationCancellation
    fun hostPublishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot
    fun hostLoadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams): GenerationParams
    fun hostCurrentDeviceProfile(): DeviceProfile
    fun hostEnqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean)
    fun hostMarkLocalConversationContextInvalid()
    fun hostSelectChatSession(sessionId: String)
    fun hostNewChatForAssistant(assistant: AssistantRecord, bindDefaultModel: Boolean): Boolean
    fun hostCaptureContentImportOwner(): ContentImportOwner
    suspend fun hostStageContentImportPreview(preview: ContentImportPreview)
    fun hostReadAttachmentText(uri: Uri, maxBytes: Int): Pair<String, Boolean>
    fun hostObserveManagedDownloads()
    fun hostSchedulePersistentPrefixCacheClear(operation: Long, successMessage: String, failureMessage: String)
    fun hostAppendAssistant(
        delta: String,
        reasoningDelta: String = "",
        reasoningDurationMs: Long = 0L,
        forcePublish: Boolean = false,
        generationRunId: Long? = null
    )
    fun hostFlushPendingAssistantOutput(generationRunId: Long? = null): Boolean
    suspend fun hostEnsureLocalConversationContextInvalidated(): Boolean
    fun hostPrepareChatInput(state: MainUiState): PreparedChatInput
}

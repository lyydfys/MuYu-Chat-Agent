package com.muyuchat.mca.composition

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.viewModelScope
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
 * Concrete adapter implementing [MainCoordinatorHost] by delegating to [MainViewModel].
 * Extracted from MainViewModel inline anonymous implementation to isolate host wiring.
 */
internal class MainCoordinatorHostAdapter(
    private val vm: MainViewModel
) : MainCoordinatorHost {
    override val store get() = vm.uiStateStore
    override val scope get() = vm.viewModelScope
    override val application get() = vm.getApplication<Application>()
    override val container get() = vm.container
    override val engine get() = vm.runtimeAssembly.engine
    override val initialChatSessions get() = vm.initialChatSessions
    override val chatContextComposer get() = vm.chatContextComposer
    override val tuningOwner get() = vm.tuningOwner
    override val agentLogger get() = vm.agentLogger
    override val benchmarkRunner get() = vm.benchmarkRunner
    override val diagnosticsOperations get() = vm.diagnosticsOperations
    override val localApiRuntimeOwner get() = vm.localApiRuntimeOwner
    override val modelScopeClient get() = vm.modelScopeClient
    override val installationScopeId get() = vm.installationScopeId
    override val localApiIdempotencyJournalLock get() = vm.localApiIdempotencyJournalLock
    override val characterMemorySummaryCoordinator get() = vm.characterMemorySummaryCoordinator
    override val knowledgeLibraryPort get() = vm.knowledgeLibraryPort
    override val contextCompressionPreferences get() = vm.contextCompressionPreferences
    override val imageLibraryStartupReconciliation get() = vm.imageLibraryStartupReconciliation
    override val persistentPrefixCacheOperationSequence get() = vm.persistentPrefixCacheOperationSequence
    override val modelIdentityReferenceMigration get() = vm.modelIdentityReferenceMigration
    override val skipInitialModelDiscovery get() = vm.skipInitialModelDiscovery
    override val imagePromptTokenMeasurementHost get() = vm.imagePromptTokenMeasurementHost
    override val visionAttachmentPort get() = vm.visionAttachmentPort
    override val chatTerminalSettlementPort get() = vm.chatTerminalSettlementPort
    override val webSearchWorkflowCoordinator get() = vm.webSearchWorkflowCoordinator
    override val chatAppearanceCoordinator get() = vm.chatAppearanceCoordinator
    override val imageUpscaleOwner get() = vm.imageUpscaleOwner
    override val chatImageRequestPort get() = vm.chatImageRequestPort
    override val imageLibraryCommitPort get() = vm.imageLibraryCommitPort
    override val imageGenerationOwner get() = vm.imageGenerationOwner
    override val offlinePromptTranslationService get() = vm.offlinePromptTranslationService

    override var activeWebSearchTaskId: String?
        get() = vm.activeWebSearchTaskId
        set(value) { vm.activeWebSearchTaskId = value }
    override var foregroundRecoveryJob: Job?
        get() = vm.foregroundRecoveryJob
        set(value) { vm.foregroundRecoveryJob = value }

    override fun hostShowStatus(message: String?) = vm.showStatus(message)
    override fun hostFail(message: String) = vm.fail(message)
    override fun hostBusy(message: String) = vm.busy(message)
    override fun hostFailDownload(message: String) = vm.failDownload(message)
    override fun hostLoadModel(requestedModel: ModelManifest) = vm.loadModel(requestedModel)
    override fun hostLaunchModelOperation(message: String, operation: suspend CoroutineScope.() -> Unit): Job =
        vm.launchModelOperation(message, operation)
    override fun hostTryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease? =
        vm.tryAcquireObservedImageGenerationLease(requestId)
    override fun hostReleaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean =
        vm.releaseObservedImageGenerationLease(lease)
    override fun hostPersistFiles(files: List<FileAssetRecord>) {
        vm.persistFiles(files)
    }
    override fun hostPersistChatSessions(
        sessions: List<ChatSessionRecord>?,
        binding: Pair<String, Set<String>>?,
        generationRunId: Long?
    ): Job? = vm.persistChatSessions(sessions, binding, generationRunId)
    override fun hostBuildChatImageRetrySnapshot(request: ChatGeneratedImageRequest, sessionId: String): ImageGenerationJobSpec? =
        vm.buildChatImageRetrySnapshot(request, sessionId)
    override fun hostChatImageNegativePromptSupported(state: MainUiState): Boolean =
        vm.chatImageNegativePromptSupported(state)
    override fun hostCancelImageGenerationForJob(expectedJobId: String?) =
        vm.cancelImageGenerationForJob(expectedJobId)
    override fun hostReserveUiGenerationStart(): UiGenerationReservation? = vm.reserveUiGenerationStart()
    override fun hostStartGeneration(
        requestMessages: List<ChatMessage>,
        reservation: UiGenerationReservation?,
        toolExchanges: List<ChatToolExchange>,
        paramsOverride: GenerationParams?,
        offerAssistantImageTool: Boolean,
        chatModelIdOverride: String?,
        continuationRequestId: String?,
        preflightCompression: ContextCompressionResult?
    ): Boolean = vm.startGeneration(
        requestMessages, reservation, toolExchanges, paramsOverride,
        offerAssistantImageTool, chatModelIdOverride, continuationRequestId, preflightCompression
    )
    override fun hostSendChatImageRequestWithPromptBridge(
        visibleUserText: String,
        prompt: String,
        requestedOutputCount: Int?,
        allowCloudTranslationFallback: Boolean
    ) = vm.sendChatImageRequestWithPromptBridge(
        visibleUserText, prompt, requestedOutputCount, allowCloudTranslationFallback
    )
    override fun hostSendPreparedMessage(state: MainUiState, preparedInput: PreparedChatInput) =
        vm.sendPreparedMessage(state, preparedInput)
    override fun hostPersistModelGenerationParams(state: MainUiState, params: GenerationParams) =
        vm.persistModelGenerationParams(state, params)
    override fun hostPersistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams) =
        vm.persistModelGenerationParams(backend, modelId, params)
    override fun hostUpdateParams(params: GenerationParams) = vm.updateParams(params)
    override fun hostCancelGenerationJob(): UiGenerationCancellation = vm.cancelGenerationJob()
    override fun hostPublishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot =
        vm.publishManagedChatCatalogAfterValidation()
    override fun hostLoadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams): GenerationParams =
        vm.loadModelGenerationParams(backend, modelId, defaults)
    override fun hostCurrentDeviceProfile(): DeviceProfile = vm.currentDeviceProfile()
    override fun hostMarkLocalConversationContextInvalid() = vm.markLocalConversationContextInvalid()
    override fun hostEnqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
        vm.enqueueManagedDownload(request, useAfterDownload)
    override fun hostSelectChatSession(sessionId: String) = vm.selectChatSession(sessionId)
    override fun hostNewChatForAssistant(assistant: AssistantRecord, bindDefaultModel: Boolean): Boolean =
        vm.newChatForAssistant(assistant, bindDefaultModel)
    override fun hostCaptureContentImportOwner(): ContentImportOwner = vm.captureContentImportOwner()
    override suspend fun hostStageContentImportPreview(preview: ContentImportPreview) =
        vm.stageContentImportPreview(preview)
    override fun hostReadAttachmentText(uri: Uri, maxBytes: Int): Pair<String, Boolean> =
        readAttachmentText(vm.getApplication<Application>().contentResolver, uri, maxBytes)
    override fun hostObserveManagedDownloads() = vm.observeManagedDownloads()
    override fun hostSchedulePersistentPrefixCacheClear(
        operation: Long,
        successMessage: String,
        failureMessage: String
    ) = vm.schedulePersistentPrefixCacheClear(operation, successMessage, failureMessage)
    override fun hostAppendAssistant(
        delta: String,
        reasoningDelta: String,
        reasoningDurationMs: Long,
        forcePublish: Boolean,
        generationRunId: Long?
    ) = vm.appendAssistant(delta, reasoningDelta, reasoningDurationMs, forcePublish, generationRunId)
    override fun hostFlushPendingAssistantOutput(generationRunId: Long?): Boolean =
        vm.flushPendingAssistantOutput(generationRunId)
    override suspend fun hostEnsureLocalConversationContextInvalidated(): Boolean =
        vm.ensureLocalConversationContextInvalidated()
    override fun hostPrepareChatInput(state: MainUiState): PreparedChatInput =
        vm.prepareChatInput(state)
}

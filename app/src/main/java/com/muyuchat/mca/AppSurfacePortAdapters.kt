package com.muyuchat.mca

import android.app.Application
import kotlinx.coroutines.Job
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository

/**
 * R3-S35：聊天生图审批端口具名适配器（A 族第 4 端口）。
 */
internal class ChatImageRequestApprovalPortAdapter(
    private val store: UiStateWriter<ChatImageApprovalFields>,
    private val admission: RuntimeAdmissionState,
    private val imageGenerationOwnerProvider: () -> ImageGenerationOwner,
    private val chatImageRequestPortProvider: () -> ChatImageRequestPort,
    private val buildChatImageRetrySnapshotRef: (
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ) -> ImageGenerationJobSpec?,
    private val continueAssistantImageToolTurnRef: (requestId: String, outputJson: String) -> Unit
) : ChatImageRequestApprovalPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (ChatImageApprovalFields) -> ChatImageApprovalFields) {
        store.update(transform)
    }

    override val imageGenerationJob: Job?
        get() = admission.imageGenerationJob

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
        state.canWriteToActiveAssistantSession()

    override fun findChatGeneratedImageRequest(
        requestId: String
    ): Pair<String, ChatGeneratedImageRequest>? =
        findChatGeneratedImageRequestIn(store.current, requestId)

    override fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? = buildChatImageRetrySnapshotRef(request, chatSessionId)

    override fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        requestedJobId: String?,
        requestedChatSessionId: String?,
        requestedChatMessageId: String?
    ): Boolean = imageGenerationOwnerProvider().submit(
        prompt = prompt,
        inputDraft = inputDraft,
        options = options,
        jobSnapshot = jobSnapshot,
        requestedJobId = requestedJobId,
        requestedChatSessionId = requestedChatSessionId,
        requestedChatMessageId = requestedChatMessageId
    )

    override fun updateChatGeneratedImageRequest(
        chatSessionId: String,
        requestId: String,
        request: ChatGeneratedImageRequest
    ) {
        applyChatGeneratedImageRequest(
            chatImageRequestPortProvider(),
            chatSessionId,
            requestId,
            request
        )
    }

    override fun updateChatImageRequestFromJob(
        chatSessionId: String,
        requestId: String,
        jobId: String
    ) {
        applyChatImageRequestFromJob(
            chatImageRequestPortProvider(),
            chatSessionId,
            requestId,
            jobId
        )
    }

    override fun continueAssistantImageToolTurn(requestId: String, outputJson: String) {
        continueAssistantImageToolTurnRef(requestId, outputJson)
    }
}

/**
 * R3-S36：运行时就绪刷新端口具名适配器（B 族第 1 端口，破万里程碑）。
 */
internal class ManagedRuntimeReadinessPortAdapter(
    override val context: Application,
    private val store: UiStateWriter<ModelCatalogFields>,
    private val modelStore: ModelStoreRepository,
    private val localImageModelStore: LocalImageModelStore,
    private val refreshGate: ManagedRuntimeReadinessRefreshGate,
    private val admission: RuntimeAdmissionState,
    private val coordinator: LocalImageGenerationCoordinator,
    private val imageLibraryBackupJobActiveProvider: () -> Boolean,
    private val hasActiveImageUpscaleProvider: () -> Boolean
) : ManagedRuntimeReadinessPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (ModelCatalogFields) -> ModelCatalogFields): ModelCatalogFields =
        store.updateAndGet(transform)

    override fun listLocalModels(): List<ModelManifest> = modelStore.listModels()

    override fun loadLocalImageModels(): List<LocalImageModelRecord> =
        localImageModelStore.loadModels()

    override fun loadPersistedSelectedImageModelId(): String? =
        localImageModelStore.loadSelectedModelId()

    override fun saveSelectedImageModelId(id: String?) {
        localImageModelStore.saveSelectedModelId(id)
    }

    override fun beginReadinessRefresh(): Long = refreshGate.begin()

    override fun invalidateReadinessRefresh() {
        refreshGate.invalidate()
    }

    override fun isReadinessRefreshCurrent(token: Long): Boolean = refreshGate.isCurrent(token)

    override fun imageLibraryBackupJobActive(): Boolean =
        imageLibraryBackupJobActiveProvider()

    override fun hasActiveImageGeneration(): Boolean =
        admission.activeImageGenerationJobId != null

    override fun hasActiveImageUpscale(): Boolean = hasActiveImageUpscaleProvider()

    override fun hasActiveLocalApiImageGeneration(): Boolean =
        admission.activeLocalApiImageModelId != null

    override fun hasActiveImageGenerationRequest(): Boolean =
        coordinator.activeRequestId() != null
}

/**
 * R3-S37：应用更新端口具名适配器（B 族第 2 端口）。
 */
internal class AppUpdatePortAdapter(
    override val context: Application,
    private val storeProvider: () -> UiStateWriter<AppUpdateFields>
) : AppUpdatePort {
    override fun uiState(): MainUiState = storeProvider().current

    override fun updateUiState(transform: (AppUpdateFields) -> AppUpdateFields) {
        storeProvider().update(transform)
    }
}

/**
 * R3-S38：云模型配置端口具名适配器（B 族第 3 端口）。
 */
internal class CloudModelConfigPortAdapter(
    private val application: Application,
    private val store: UiStateWriter<CloudModelConfigFields>,
    override val cloudApiStore: CloudApiStore,
    override val cloudChatProvider: OpenAiCompatibleChatProvider,
    override val localImageModelStore: LocalImageModelStore,
    private val loadModelGenerationParamsRef: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    private val persistChatSessionsRef: (List<ChatSessionRecord>?, Pair<String, Set<String>>?) -> Unit
) : CloudModelConfigPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (CloudModelConfigFields) -> CloudModelConfigFields): CloudModelConfigFields =
        store.updateAndGet(transform)

    override fun loadModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams = loadModelGenerationParamsRef(backend, modelId, defaults)

    override fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>?
    ) {
        persistChatSessionsRef(sessions, knowledgeBinding)
    }

    override fun persistGenerationParams(params: GenerationParams) {
        GenerationPreferences.persistGenerationParams(application, params)
    }

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override fun busy(message: String) {
        store.update { it.copy(busy = true, statusMessage = message) }
    }

    override fun fail(message: String) {
        store.update { it.copy(busy = false, statusMessage = message) }
    }
}

/**
 * R3-S39：选择聊天会话端口具名适配器（B 族第 4 端口）。
 */
internal class SelectChatSessionPortAdapter(
    private val application: Application,
    private val store: UiStateWriter<ConversationSelectionFields>,
    private val knowledgeBaseStore: KnowledgeBaseStore,
    private val assistantStore: AssistantStore,
    private val cloudApiStore: CloudApiStore,
    private val manualContextCompressionRequests: ContextCompressionRequestStore,
    private val rejectWhileConversationMutationInProgressRef: () -> Boolean,
    private val hasActiveAssistantImageExecutionRef: (String?) -> Boolean,
    private val invalidatePendingChatImagePreparationRef: () -> Unit,
    private val cancelActiveWebSearchRef: () -> Unit,
    private val bumpContentImportSelectionRevisionRef: () -> Unit,
    private val loadModelGenerationParamsRef: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    private val markLocalConversationContextInvalidRef: () -> Unit,
    private val continueAssistantImageTurnRef: (String) -> Unit
) : SelectChatSessionPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (ConversationSelectionFields) -> ConversationSelectionFields) {
        store.update(transform)
    }

    override fun rejectWhileConversationMutationInProgress(): Boolean =
        rejectWhileConversationMutationInProgressRef()

    override fun hasActiveAssistantImageExecution(sessionId: String?): Boolean =
        hasActiveAssistantImageExecutionRef(sessionId)

    override fun showStatus(message: String) {
        store.showStatus(message)
    }

    override fun invalidatePendingChatImagePreparation() {
        invalidatePendingChatImagePreparationRef()
    }

    override fun cancelActiveWebSearch() {
        cancelActiveWebSearchRef()
    }

    override fun bumpContentImportSelectionRevision() {
        bumpContentImportSelectionRevisionRef()
    }

    override fun selectedKnowledgeBaseIdsFor(sessionId: String): Set<String> =
        knowledgeBaseStore.selectedKnowledgeBaseIds(sessionId)

    override fun saveSelectedAssistantId(assistantId: String) {
        assistantStore.saveSelectedAssistantId(assistantId)
    }

    override fun loadModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams = loadModelGenerationParamsRef(backend, modelId, defaults)

    override fun persistGenerationParams(params: GenerationParams) {
        GenerationPreferences.persistGenerationParams(application, params)
    }

    override fun saveSelectedBackend(backend: ChatBackend) {
        cloudApiStore.saveSelectedBackend(backend)
    }

    override fun saveSelectedCloudChatModelId(modelId: String) {
        cloudApiStore.saveSelectedCloudChatModelId(modelId)
    }

    override fun normalizedCloudChatConfig(config: CloudApiConfig): CloudApiConfig =
        config.normalized()

    override fun saveCloudChatConfig(config: CloudApiConfig) {
        cloudApiStore.save(config.normalized())
    }

    override fun toGenerationParams(
        assistant: AssistantRecord,
        defaults: GenerationParams
    ): GenerationParams = assistantGenerationParamsResolved(
        assistant.paramsJson,
        defaults,
        assistant.systemPrompt
    )

    override fun isContextCompressionPending(sessionId: String): Boolean =
        manualContextCompressionRequests.isPending(sessionId)

    override fun markLocalConversationContextInvalid() {
        markLocalConversationContextInvalidRef()
    }

    override fun continueAssistantImageTurn(requestId: String) {
        continueAssistantImageTurnRef(requestId)
    }
}

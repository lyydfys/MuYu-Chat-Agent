package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest

internal class ChatTerminalSettlementPortAdapter(
    private val store: UiStateStore,
    private val chatRunsRef: ChatRunOwnership,
    private val diagnosticStoreRef: WebSearchDiagnosticStore,
    private val accumulatorRef: ChatStreamAccumulator
) : ChatTerminalSettlementPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun currentChatModelId(state: MainUiState): String? = state.currentChatModelId()

    override fun upsertSession(
        sessions: List<ChatSessionRecord>,
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String?,
        modelMode: String?,
        modelId: String?
    ): List<ChatSessionRecord> = sessions.upsertSessionFor(
        state = store.current,
        sessionId = sessionId,
        messages = messages,
        assistantId = assistantId,
        modelMode = modelMode,
        modelId = modelId
    )

    override val webSearchDiagnosticStore: WebSearchDiagnosticStore
        get() = diagnosticStoreRef

    override fun isRunCurrent(runId: Long?): Boolean = chatRunsRef.isCurrent(runId)

    override fun discardPendingOutput(runId: Long?) {
        accumulatorRef.discard(runId)
    }
}

internal class ChatSessionPersistencePortAdapter(
    private val store: UiStateStore,
    private val scopeRef: CoroutineScope,
    private val engineRef: McaInferenceService,
    private val chatRunsRef: ChatRunOwnership,
    private val chatSessionStoreRef: ChatSessionStore,
    private val persistenceState: ChatSessionPersistenceState,
    private val accumulatorRef: ChatStreamAccumulator,
    private val worldStoreRef: WorldBookStore,
    private val admissionRef: RuntimeAdmissionState
) : ChatSessionPersistencePort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val scope: CoroutineScope
        get() = scopeRef

    override val chatRuns: ChatRunOwnership
        get() = chatRunsRef

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override val chatSessionPersistenceMutex get() = persistenceState.persistenceMutex

    override val chatSessionPersistenceSequence get() = persistenceState.persistenceSequence

    override val chatSessionPersistenceStateLock get() = persistenceState.stateLock

    override val pendingKnowledgeBindings: MutableMap<String, Set<String>>
        get() = persistenceState.pendingKnowledgeBindings

    override var durableChatSessions: List<ChatSessionRecord>
        get() = persistenceState.durableChatSessions
        set(value) {
            persistenceState.durableChatSessions = value
        }

    override fun flushPendingAssistantOutput(generationRunId: Long?): Boolean =
        accumulatorRef.flush(generationRunId)

    override fun reconciledChatSessionsAfterSave(snapshot: List<ChatSessionRecord>): List<ChatSessionRecord> =
        reconciledChatSessionsAfterSaveFor(persistenceState, chatSessionStoreRef, snapshot)

    override fun publishReconciledChatSessions(requested: List<ChatSessionRecord>, persisted: List<ChatSessionRecord>) =
        publishReconciledChatSessionsInto(store, chatRunsRef, requested, persisted)

    override fun cleanupPendingWorldBooksAfterOwnerCommit(scope: WorldBookScope, retainedOwnerIds: Set<String>) =
        cleanupPendingWorldBooksAfterOwnerCommitFor(persistenceState, worldStoreRef, store, scope, retainedOwnerIds)

    override fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) =
        cancelPendingWorldBookCleanupIn(persistenceState, scope, ownerIds)

    override val engine: McaInferenceService
        get() = engineRef

    override var localConversationContextNeedsInvalidation: Boolean
        get() = admissionRef.localConversationContextNeedsInvalidation
        set(value) {
            admissionRef.localConversationContextNeedsInvalidation = value
        }

    override val localConversationContextInvalidationSequence get() =
        admissionRef.localConversationContextInvalidationSequence

    override var conversationMutationBarrier: Job?
        get() = admissionRef.conversationMutationBarrier
        set(value) {
            admissionRef.conversationMutationBarrier = value
        }

    override var localConversationContextInvalidationJob: Job?
        get() = admissionRef.localConversationContextInvalidationJob
        set(value) {
            admissionRef.localConversationContextInvalidationJob = value
        }
}

/** R3/C3-S12：图片请求簿记端口的具名适配器（真实依赖：store + 已容器化的持久化端口）。 */
internal class ChatImageRequestPortAdapter(
    private val store: UiStateStore,
    private val persistencePort: ChatSessionPersistencePort
) : ChatImageRequestPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>?
    ) {
        runChatSessionPersistence(
            port = persistencePort,
            sessions = sessions,
            knowledgeBinding = knowledgeBinding,
            generationRunId = null
        )
    }
}

/**
 * R3/C3-S12：提交恢复窄端口的具名适配器（拆 retry -> owner -> recovery 构造环）。
 *
 * The journal is taken as a provider because the owning lazy keeps its original construction
 * timing: forcing it here would move the `mkdirs`/`require` checks from first journal use to
 * ViewModel construction.
 */
internal class ChatImageCommitRecoveryPortAdapter(
    private val journalProvider: () -> ChatImageCommitJournal,
    private val sessionStoreRef: ChatSessionStore
) : ChatImageCommitRecoveryPort {
    override val chatImageCommitJournal: ChatImageCommitJournal
        get() = journalProvider()

    override val chatSessionStore: ChatSessionStore
        get() = sessionStoreRef
}

/** R3/C3-S14：图片发送端口具名化（真实依赖：store/holder/持久化端口/请求端口/owner/协调器）。 */
internal class SendChatImageRequestPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val persistencePort: ChatSessionPersistencePort,
    private val requestPort: ChatImageRequestPort,
    private val imageGenerationOwner: ImageGenerationOwner,
    private val coordinator: LocalImageGenerationCoordinator
) : SendChatImageRequestPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun showStatus(message: String) {
        store.showStatus(message)
    }

    override fun rejectWhileConversationMutationInProgress(): Boolean =
        rejectWhileConversationMutationInProgressInto(store, admission)

    override fun chatImageSubmissionBlockMessage(state: MainUiState): String? =
        chatImageSubmissionBlockMessageFor(state, admission, coordinator)

    override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
        state.canWriteToActiveAssistantSession()

    override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
        state.activeAssistantSnapshot()

    override fun selectedAssistant(state: MainUiState): AssistantRecord? =
        state.selectedAssistant()

    override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? =
        state.selectedLocalImageModel()

    override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? =
        state.selectedImageCloudConfig()

    override fun currentChatModelId(state: MainUiState): String? =
        state.currentChatModelId()

    override fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
        chatImageOptionsForCurrentSelectionFor(state)

    override fun upsertSession(
        sessions: List<ChatSessionRecord>,
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String?,
        assistantSnapshot: AssistantConversationSnapshot?,
        modelMode: String?,
        modelId: String?
    ): List<ChatSessionRecord> = sessions.upsertSessionFor(
        state = store.current,
        sessionId = sessionId,
        messages = messages,
        assistantId = assistantId,
        assistantSnapshot = assistantSnapshot,
        modelMode = modelMode,
        modelId = modelId
    )

    override fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>?
    ) {
        runChatSessionPersistence(
            port = persistencePort,
            sessions = sessions,
            knowledgeBinding = knowledgeBinding,
            generationRunId = null
        )
    }

    override fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        requestedJobId: String?,
        requestedChatSessionId: String?,
        requestedChatMessageId: String?
    ): Boolean = imageGenerationOwner.submit(
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
        applyChatGeneratedImageRequest(requestPort, chatSessionId, requestId, request)
    }

    override fun updateChatImageRequestFromJob(chatSessionId: String, requestId: String, jobId: String) {
        applyChatImageRequestFromJob(requestPort, chatSessionId, requestId, jobId)
    }
}

/** R3/C3-S14：图片重试/清理端口具名化（journal 以 provider 传入，保持原 lazy 时机）。 */
internal class ChatImageRetryPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val cloudApiStoreRef: CloudApiStore,
    private val localImageModelStoreRef: LocalImageModelStore,
    private val journalProvider: () -> ChatImageCommitJournal,
    private val chatSessionStoreRef: ChatSessionStore,
    private val sendPortRef: SendChatImageRequestPort
) : ChatImageRetryPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val cloudApiStore: CloudApiStore
        get() = cloudApiStoreRef

    override val localImageModelStore: LocalImageModelStore
        get() = localImageModelStoreRef

    override val chatImageCommitJournal: ChatImageCommitJournal
        get() = journalProvider()

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override fun invalidatePendingChatImagePreparation() {
        invalidatePendingChatImagePreparationInto(store, admission)
    }

    override fun sendChatImageRequest(
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions?,
        promptEnvelope: PromptEnvelope?,
        contextTraceJson: String?
    ) {
        SendChatImageRequestOperations.sendChatImageRequest(
            port = sendPortRef,
            visibleUserText = visibleUserText,
            prompt = prompt,
            optionsOverride = optionsOverride,
            promptEnvelope = promptEnvelope,
            contextTraceJson = contextTraceJson
        )
    }

    override fun pendingImageTranslation(): PendingImageTranslation? =
        admission.pendingImageTranslation

    override fun setPendingImageTranslation(value: PendingImageTranslation?) {
        admission.pendingImageTranslation = value
    }
}

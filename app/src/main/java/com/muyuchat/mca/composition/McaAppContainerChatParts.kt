package com.muyuchat.mca.composition

import com.muyuchat.mca.ChatSessionPersistencePortAdapter
import com.muyuchat.mca.ChatSessionPersistencePort
import com.muyuchat.mca.ChatImageCommitJournal
import com.muyuchat.mca.ChatImageCommitRecoveryPort
import com.muyuchat.mca.ChatImageCommitRecoveryPortAdapter
import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ChatImageRequestPortAdapter
import com.muyuchat.mca.ChatImageRetryPort
import com.muyuchat.mca.ChatImageRetryPortAdapter
import com.muyuchat.mca.SendChatImageRequestPort
import com.muyuchat.mca.SendChatImageRequestPortAdapter
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.ChatTerminalSettlementPortAdapter
import com.muyuchat.mca.ChatTerminalSettlementPort
import com.muyuchat.mca.ChatLocalApiProviderPort
import com.muyuchat.mca.ChatLocalApiProviderPortAdapter
import com.muyuchat.mca.ChatStopGenerationPort
import com.muyuchat.mca.ChatStopGenerationPortAdapter
import com.muyuchat.mca.ReplyGenerationMetrics
import com.muyuchat.mca.UiGenerationCancellation
import com.muyuchat.mca.ConversationMutationRollbackState
import java.util.concurrent.atomic.AtomicBoolean
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope
import com.muyuchat.mca.ChatSessionStore
import com.muyuchat.mca.KnowledgeBaseRecord

internal fun McaAppContainer.createChatTerminalSettlementPortImpl(store: UiStateStore): ChatTerminalSettlementPort =
        ChatTerminalSettlementPortAdapter(
            store = store,
            chatRunsRef = chatRuns,
            diagnosticStoreRef = webSearchDiagnosticStore,
            accumulatorRef = chatStreamAccumulator
        )

    /** R3/C3-S11：会话持久化端口具名适配器工厂。 */
internal fun McaAppContainer.createChatSessionPersistencePort(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService
    ): ChatSessionPersistencePort =
        ChatSessionPersistencePortAdapter(
            store = store,
            scopeRef = scope,
            engineRef = engine,
            chatRunsRef = chatRuns,
            chatSessionStoreRef = chatSessionStore,
            persistenceState = chatSessionPersistenceState,
            accumulatorRef = chatStreamAccumulator,
            worldStoreRef = worldBookStore,
            admissionRef = runtimeAdmission
        )

    /** R3/C3-S12：图片请求簿记端口的具名适配器工厂。 */
internal fun McaAppContainer.createChatImageRequestPort(
        store: UiStateStore,
        persistencePort: ChatSessionPersistencePort
    ): ChatImageRequestPort =
        ChatImageRequestPortAdapter(store = store, persistencePort = persistencePort)

internal fun McaAppContainer.createChatImageCommitRecoveryPortImpl(
        journalProvider: () -> ChatImageCommitJournal,
        sessionStore: ChatSessionStore
    ): ChatImageCommitRecoveryPort =
        ChatImageCommitRecoveryPortAdapter(journalProvider = journalProvider, sessionStoreRef = sessionStore)

internal fun McaAppContainer.createImageLibraryStartupReconciliationImpl(
        store: UiStateStore,
        scope: CoroutineScope,
        recoveryPort: ChatImageCommitRecoveryPort,
        initialKnowledgeBases: List<KnowledgeBaseRecord>
    ): ImageLibraryStartupReconciliation =
        ImageLibraryStartupReconciliation(
            scope = scope,
            store = store,
            chatSessionStore = chatSessionStore,
            recoveryPort = recoveryPort,
            persistenceState = chatSessionPersistenceState,
            imageLibraryMutationMutex = imageLibraryMutationMutex,
            imageLibraryBackup = imageLibraryBackup,
            imageAssetDirectory = imageAssetDirectory,
            knowledgeBaseStore = knowledgeBaseStore,
            initialKnowledgeBases = initialKnowledgeBases
        )

    /** R3/C3-S14：图片发送端口工厂（owner 以真实对象注入；admission/coordinator 容器自持）。 */
internal fun McaAppContainer.createSendChatImageRequestPort(
        store: UiStateStore,
        persistencePort: ChatSessionPersistencePort,
        requestPort: ChatImageRequestPort,
        imageGenerationOwner: ImageGenerationOwner
    ): SendChatImageRequestPort =
        SendChatImageRequestPortAdapter(
            store = store,
            admission = runtimeAdmission,
            persistencePort = persistencePort,
            requestPort = requestPort,
            imageGenerationOwner = imageGenerationOwner,
            coordinator = localImageGenerationCoordinator
        )

    /** R3/C3-S14：图片重试/清理端口工厂（journal 以 provider 传入，保持原 lazy 时机）。 */
internal fun McaAppContainer.createChatImageRetryPort(
        store: UiStateStore,
        sendPort: SendChatImageRequestPort,
        journalProvider: () -> ChatImageCommitJournal
    ): ChatImageRetryPort =
        ChatImageRetryPortAdapter(
            store = store,
            admission = runtimeAdmission,
            cloudApiStoreRef = cloudApiStore,
            localImageModelStoreRef = localImageModelStore,
            journalProvider = journalProvider,
            chatSessionStoreRef = chatSessionStore,
            sendPortRef = sendPort
        )

internal fun McaAppContainer.createChatLocalApiProviderPort(
        engine: McaInferenceService,
        store: UiStateStore,
        cancelActiveLocalApiImageGenerationAction: (String) -> Boolean,
        localApiPayloadPortProvider: () -> LocalApiPayloadPort,
    ): ChatLocalApiProviderPort =
        ChatLocalApiProviderPortAdapter(
            engine = engine,
            chatRuns = chatRuns,
            store = store,
            cancelActiveLocalApiImageGenerationAction = cancelActiveLocalApiImageGenerationAction,
            localApiPayloadPortProvider = localApiPayloadPortProvider,
            chatExecutionFacade = chatExecutionFacadeFor(engine),
        )

internal fun McaAppContainer.createChatStopGenerationPort(
        store: UiStateStore,
        engine: McaInferenceService,
        stopScopeRef: () -> CoroutineScope,
        rejectWhileConversationMutationInProgressRef: () -> Boolean,
        stopGenerationInProgressRef: AtomicBoolean,
        currentReplyMetricsRef: () -> ReplyGenerationMetrics?,
        cancelGenerationJobRef: () -> UiGenerationCancellation,
        flushPendingAssistantOutputRef: () -> Unit,
        persistConversationMutationRef: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Unit,
    ): ChatStopGenerationPort =
        ChatStopGenerationPortAdapter(
            store = store,
            engine = engine,
            chatRuns = chatRuns,
            stopScopeRef = stopScopeRef,
            rejectWhileConversationMutationInProgressRef = rejectWhileConversationMutationInProgressRef,
            stopGenerationInProgressRef = stopGenerationInProgressRef,
            currentReplyMetricsRef = currentReplyMetricsRef,
            cancelGenerationJobRef = cancelGenerationJobRef,
            flushPendingAssistantOutputRef = flushPendingAssistantOutputRef,
            persistConversationMutationRef = persistConversationMutationRef,
            chatExecutionFacade = chatExecutionFacadeFor(engine),
        )

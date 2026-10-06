package com.muyuchat.mca

import android.app.Application
import android.content.Context
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ModelRuntimeIdentity

/**
 * R3/C3-S1（插队裁定 2）：自 MainViewModel 具名化的端口适配器——只持有真实依赖
 * （store / 容器对象 / 锁实例 / 运行期准入 holder），不再反向捕获 ViewModel。
 */

internal class LocalImageAdapterPortAdapter(
    private val store: UiStateStore,
    private val loraStoreRef: LocalImageLoraStore,
    private val textualInversionStoreRef: TextualInversionStore,
    private val loraLifecycleLock: Any,
    private val textualInversionLifecycleLock: Any
) : LocalImageAdapterPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val loraStore: LocalImageLoraStore
        get() = loraStoreRef

    override val textualInversionStore: TextualInversionStore
        get() = textualInversionStoreRef

    override fun <T> withLoraLifecycleLock(block: () -> T): T =
        synchronized(loraLifecycleLock) { block() }

    override fun <T> withTextualInversionLifecycleLock(block: () -> T): T =
        synchronized(textualInversionLifecycleLock) { block() }
}

internal class WebSearchConfigPortAdapter(
    private val store: UiStateStore,
    private val appContext: Application,
    private val webSearchStoreRef: WebSearchStore,
    private val diagnosticStoreRef: WebSearchDiagnosticStore,
    private val providerRef: WebSearchProvider
) : WebSearchConfigPort {
    override val context: Application
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun saveConfig(config: WebSearchConfig) {
        webSearchStoreRef.save(config)
    }

    override fun appendDiagnostic(
        record: WebSearchDiagnosticRecord
    ): List<WebSearchDiagnosticRecord> = diagnosticStoreRef.add(record)

    override val provider: WebSearchProvider
        get() = providerRef
}

internal class VisionAttachmentPortAdapter(
    private val store: UiStateStore,
    private val appContext: Application,
    private val engineRef: McaInferenceService,
    private val admissionRef: RuntimeAdmissionState
) : VisionAttachmentPort {
    override val context: Context
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun nativeStatsJson(): String = engineRef.nativeStatsJson()

    override val activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = admissionRef.activeRuntimeIdentity

    override val activeVisionProjectorStamp: LoadedVisionProjectorStamp?
        get() = admissionRef.activeVisionProjectorStamp
}

internal class LocalApiPayloadPortAdapter(
    private val store: UiStateStore,
    private val engineRef: McaInferenceService,
    private val runtimeProfileStoreRef: ModelRuntimeProfileStore,
    private val tuningRef: TuningOwner,
    private val admissionRef: RuntimeAdmissionState
) : LocalApiPayloadPort {
    override fun uiState(): MainUiState = store.current

    override val activeTuningJobId: String?
        get() = tuningRef.activeTuningJobId

    override fun tuningCancelRequested(): Boolean =
        tuningRef.adaptiveTuningCancelRequested.get()

    override fun tuningPauseRequested(): Boolean =
        tuningRef.adaptiveTuningPauseRequested.get()

    override fun runtimeProfileJson(): String =
        runtimeProfileJsonFor(store.current, engineRef, admissionRef.activeRuntimeIdentity)

    override fun nativeStatsJson(): String = engineRef.nativeStatsJson()

    override fun runtimeLoaded(): Boolean = engineRef.stats.value.loaded

    override suspend fun modelRuntimeSummaries(
        modelIds: Collection<String>
    ): List<PersistedModelRuntimeSummary> =
        runtimeProfileStoreRef.modelRuntimeSummaries(modelIds)
}

internal class ImagePromptTokenMeasurementHostAdapter(
    private val store: UiStateStore,
    private val measurementCacheRef: ImagePromptTokenMeasurementCache,
    private val tokenizerDescriptorsRef: java.util.concurrent.ConcurrentHashMap<String, LocalImagePromptTokenizerDescriptor>
) : ImagePromptTokenMeasurementHost {
    override val models: List<LocalImageModelRecord>
        get() = store.current.localImageModels

    override fun cachedMeasurement(cacheKey: String): ImagePromptTokenMeasurement? =
        measurementCacheRef.get(cacheKey)

    override fun cacheMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement) {
        measurementCacheRef.put(cacheKey, measurement)
    }

    override fun cachedDescriptor(modelKey: String): LocalImagePromptTokenizerDescriptor? =
        tokenizerDescriptorsRef[modelKey]

    override fun cacheDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor) {
        tokenizerDescriptorsRef.putIfAbsent(modelKey, descriptor)
    }
}

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

/** R3/C3-S15：资产生成端口具名化（owner 真实对象注入；注释中的 requestedJobId 丢弃为现存行为）。 */
internal class ImageAssetCreationPortAdapter(
    private val store: UiStateStore,
    private val application: Application,
    private val admission: RuntimeAdmissionState,
    private val imageGenerationOwner: ImageGenerationOwner,
    private val assetWriteMutex: Mutex,
    private val assetDirectory: File,
    private val localImageModelStoreRef: LocalImageModelStore
) : ImageAssetCreationPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val context: Application
        get() = application

    override suspend fun <T> withImageAssetWriteLock(block: suspend () -> T): T =
        assetWriteMutex.withLock { block() }

    override val imageAssetDirectory: File
        get() = assetDirectory

    override val localImageModelStore: LocalImageModelStore
        get() = localImageModelStoreRef

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override fun invalidatePendingChatImagePreparation() {
        invalidatePendingChatImagePreparationInto(store, admission)
    }

    override fun canReadGenerationHistoryInput(reference: String): Boolean =
        canReadGenerationHistoryInputFor(application, reference)

    override fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean =
        rejectImageModelSwitchWhileGenerationIsActiveFor(store, admission)

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
        requestedChatSessionId = requestedChatSessionId,
        requestedChatMessageId = requestedChatMessageId
    )

    override fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora> = resolveCurrentLocalImageLorasFor(selections, records)

    override fun activeImageGenerationJobId(): String? =
        admission.activeImageGenerationJobId

    override fun activeImageGenerationModelId(): String? =
        admission.activeImageGenerationModelId
}

/** R3/C3-S15：图像放大端口具名化（对账 owner 经 S13 注入；createUpscaledAsset 走资产生成端口）。 */
internal class ImageUpscalePortAdapter(
    private val store: UiStateStore,
    private val application: Application,
    private val coordinator: LocalImageGenerationCoordinator,
    private val observationLock: Any,
    private val assetCreationPort: ImageAssetCreationPort,
    private val assetWriteMutex: Mutex,
    private val startupReconciliation: ImageLibraryStartupReconciliation,
    private val workerClientRef: LocalImageWorkerClient,
    private val chatSessionStoreRef: ChatSessionStore,
    private val upscalerStoreRef: LocalImageUpscalerStore,
    private val assetDirectory: File,
    private val mutationMutex: Mutex
) : ImageUpscalePort {
    override val context: Application
        get() = application

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val imageAssetDirectory: File
        get() = assetDirectory

    override val localImageWorkerClient: LocalImageWorkerClient
        get() = workerClientRef

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override val localImageUpscalerStore: LocalImageUpscalerStore
        get() = upscalerStoreRef

    override suspend fun <T> withImageLibraryMutationLock(block: suspend () -> T): T =
        mutationMutex.withLock { block() }

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }

    override suspend fun awaitImageLibraryStartupReconciliation() {
        startupReconciliation.await()
    }

    override suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord = assetWriteMutex.withLock {
        createUpscaledAsset(port = assetCreationPort, spec = spec, result = result, requestId = requestId)
    }

    override fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(store, coordinator, observationLock, requestId)

    override fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean =
        releaseObservedImageGenerationLeaseFor(store, coordinator, observationLock, lease)
}

/** R3/C3-S16：图片库提交端口具名化（journal 以 provider 传入，保持原 lazy 时机）。 */
internal class ImageLibraryCommitPortAdapter(
    private val store: UiStateStore,
    private val persistenceState: ChatSessionPersistenceState,
    private val chatSessionStoreRef: ChatSessionStore,
    private val journalProvider: () -> ChatImageCommitJournal,
    private val admissionRef: RuntimeAdmissionState,
    private val requestPortRef: ChatImageRequestPort,
    private val imageLibraryMutationMutexRef: Mutex
) : ImageLibraryCommitPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val imageLibraryMutationMutex: Mutex
        get() = imageLibraryMutationMutexRef

    override val chatSessionPersistenceMutex: Mutex
        get() = persistenceState.persistenceMutex

    override val chatSessionPersistenceSequence: AtomicLong
        get() = persistenceState.persistenceSequence

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override var durableChatSessions: List<ChatSessionRecord>
        get() = persistenceState.durableChatSessions
        set(value) {
            persistenceState.durableChatSessions = value
        }

    override val activeImageGenerationJobId: String?
        get() = admissionRef.activeImageGenerationJobId

    override val chatImageCommitJournal: ChatImageCommitJournal
        get() = journalProvider()

    override fun buildChatImageCommitSnapshot(
        job: ImageGenerationJobRecord,
        images: List<ImageAssetRecord>
    ): ChatImageCommitSnapshot? = chatImageCommitSnapshot(requestPortRef, job, images)

    override fun chatImageCommitJournalJobId(jobId: String, imageId: String): String =
        chatImageCommitJournalJobId(jobId, imageId)

    override fun updateImageJob(
        jobs: List<ImageGenerationJobRecord>,
        jobId: String,
        status: ImageGenerationStatusRecord,
        message: String,
        imageAssetId: String?,
        preview: PublishedLocalImagePreview?,
        imageAssetIds: List<String>
    ): List<ImageGenerationJobRecord> = jobs.updateImageJob(
        jobId = jobId,
        status = status,
        message = message,
        imageAssetId = imageAssetId,
        preview = preview,
        imageAssetIds = imageAssetIds
    )
}

/** R3/C3-S16：图片删除端口具名化（放大 owner 与 S13 对账 owner 均真实对象注入）。 */
internal class ImageAssetDeletionPortAdapter(
    private val store: UiStateStore,
    private val scopeRef: CoroutineScope,
    private val admissionRef: RuntimeAdmissionState,
    private val imageUpscaleOwnerRef: ImageUpscaleOwner,
    private val chatSessionStoreRef: ChatSessionStore,
    private val mutationMutex: Mutex,
    private val assetDirectory: File,
    private val startupReconciliation: ImageLibraryStartupReconciliation
) : ImageAssetDeletionPort {
    override val uiState: StateFlow<MainUiState>
        get() = store.state

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val scope: CoroutineScope
        get() = scopeRef

    override val imageLibraryMutationMutex: Mutex
        get() = mutationMutex

    override val imageUpscaleOwner: ImageUpscaleOwner
        get() = imageUpscaleOwnerRef

    override val chatSessionStore: ChatSessionStore
        get() = chatSessionStoreRef

    override val latestImageFavoriteMutations: MutableMap<String, Pair<Long, Boolean>>
        get() = admissionRef.latestImageFavoriteMutations

    override val imageFavoriteMutationSequence: AtomicLong
        get() = admissionRef.imageFavoriteMutationSequence

    override val imageAssetDirectory: File
        get() = assetDirectory

    override suspend fun awaitImageLibraryStartupReconciliation() {
        startupReconciliation.await()
    }

    override fun showStatus(message: String?) {
        store.showStatus(message)
    }
}

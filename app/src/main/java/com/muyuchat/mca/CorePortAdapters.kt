package com.muyuchat.mca

import android.app.Application
import android.content.Context
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.EngineLifecycleLease
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
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.TuningExecutionProfile

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

/** R3/C3-S27/S31：图片提示词翻译端口具名化（service 经 provider 保 lazy；verifiedBundle 直调 operations，删除过渡 lambda）。 */
internal class ImagePromptTranslationPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val downloader: OfflinePromptTranslationBundleDownloader,
    private val serviceProvider: () -> OfflinePromptTranslationService
) : ImagePromptTranslationPort {
    override suspend fun verifiedBundle(): VerifiedOfflinePromptTranslationBundle? =
        verifiedOfflineTranslationBundleFor(store, admission, downloader)

    override suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle?,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationResolution = serviceProvider().translate(bundle, request)

    override fun publishTranslationStatus(status: String, taskMessage: String) {
        store.update { state ->
            val activeJobId = admission.activeImageGenerationJobId
            state.copy(
                offlineTranslationStatus = status,
                imageJobs = if (activeJobId.isNullOrBlank()) state.imageJobs else {
                    state.imageJobs.map { job ->
                        if (job.id == activeJobId &&
                            job.status == ImageGenerationStatusRecord.GENERATING
                        ) job.copy(message = taskMessage) else job
                    }
                },
                statusMessage = taskMessage
            )
        }
    }
}

/** R3-S30：本地生图 worker 网关具名化（真实客户端经命名参数纯转发）。 */
internal class LocalGeneratedImageWorkerGatewayAdapter(
    private val client: LocalImageWorkerClient
) : LocalGeneratedImageWorkerGateway {
    override suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        inputDraft: LocalImageInputDraft,
        requestId: String,
        batchLineage: ImageGenerationBatchLineage?,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult = client.generate(
        model = model,
        prompt = prompt,
        options = options,
        inputDraft = inputDraft,
        onProgress = onProgress,
        requestId = requestId,
        batchLineage = batchLineage
    )
}

/** R3-S30：本地生图资产生成端口具名化（worker 代际经 holder；两助手经顶层 For）。 */
internal class CreateLocalGeneratedImageAssetPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val gateway: LocalGeneratedImageWorkerGateway,
    private val localImageModelStoreRef: LocalImageModelStore,
    private val readinessGate: ManagedRuntimeReadinessRefreshGate,
    private val assetWriteMutex: Mutex,
    private val assetDirectory: File,
    private val application: Application
) : CreateLocalGeneratedImageAssetPort {
    override var activeLocalImageWorkerRequestId: String?
        get() = admission.activeLocalImageWorkerRequestId
        set(value) {
            admission.activeLocalImageWorkerRequestId = value
        }

    override val localImageWorkerClient: LocalGeneratedImageWorkerGateway
        get() = gateway

    override fun recordLocalImageExecutionOutcome(
        model: LocalImageModelRecord,
        failureMessage: String?
    ) = recordLocalImageExecutionOutcomeFor(
        localImageModelStoreRef, readinessGate, store, model, failureMessage
    )

    override fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String = executionWithQnnVerificationEvidenceFor(
        application, model, result, options, requestId, attemptId, entryPoint
    )

    override val imageAssetWriteMutex: Mutex
        get() = assetWriteMutex

    override val imageAssetDirectory: File
        get() = assetDirectory
}

/**
 * C2-S3：ImageGenerationHost 具名适配器——持有真实容器依赖与 VM 作用域，
 * 彻底消除 MainViewModel 内 97 行匿名 object 与 33 处 this@MainViewModel 捕获。
 */
internal class ImageGenerationHostAdapter(
    private val store: UiStateStore,
    private val scopeRef: CoroutineScope,
    private val applicationRef: Application,
    private val runtimeAdmission: RuntimeAdmissionState,
    private val coordinator: LocalImageGenerationCoordinator,
    private val coordinatorObservationLock: Any,
    private val localImageLoraLifecycleLockRef: Any,
    private val localImageTextualInversionLifecycleLockRef: Any,
    private val localImageLoraStoreRef: LocalImageLoraStore,
    private val localImageWorkerClientRef: LocalImageWorkerClient,
    private val imageAssetDirectoryRef: File,
    private val imageAssetWriteMutex: Mutex,
    private val cloudImageProviderRef: CloudImageProvider,
    private val chatImageCommitJournalRef: ChatImageCommitJournal,
    private val runtimeProfileStoreRef: ModelRuntimeProfileStore,
    private val engineRef: McaInferenceService,
    private val createLocalGeneratedImageAssetPort: CreateLocalGeneratedImageAssetPort,
    private val imageLibraryStartupReconciliationProvider: () -> ImageLibraryStartupReconciliation,
    private val imageLibraryCommitPortProvider: () -> ImageLibraryCommitPort,
    private val chatImageRequestPortProvider: () -> ChatImageRequestPort,
    private val imagePromptTranslationPortProvider: () -> ImagePromptTranslationPort,
    private val completeAssistantImageToolTurnForJobRef: (String) -> Unit
) : ImageGenerationHost {
    override val scope: CoroutineScope get() = scopeRef
    override val application: Application get() = applicationRef

    override fun currentState(): MainUiState = store.current
    override fun updateUiState(transform: (MainUiState) -> MainUiState) = store.update(transform)
    override fun showStatus(message: String?) = store.showStatus(message)

    override val imageGenerationJob: Job? get() = runtimeAdmission.imageGenerationJob
    override fun setImageGenerationJob(job: Job?) {
        runtimeAdmission.imageGenerationJob = job
    }

    override var activeImageGenerationJobId: String?
        get() = runtimeAdmission.activeImageGenerationJobId
        set(value) {
            runtimeAdmission.activeImageGenerationJobId = value
        }

    override var activeImageGenerationBackend: ImageBackend?
        get() = runtimeAdmission.activeImageGenerationBackend
        set(value) {
            runtimeAdmission.activeImageGenerationBackend = value
        }

    override var activeImageGenerationModelId: String?
        get() = runtimeAdmission.activeImageGenerationModelId
        set(value) {
            runtimeAdmission.activeImageGenerationModelId = value
        }

    override val localImageTextualInversionLifecycleLock: Any
        get() = localImageTextualInversionLifecycleLockRef

    override val localImageLoraLifecycleLock: Any
        get() = localImageLoraLifecycleLockRef

    override val localImageLoraStore: LocalImageLoraStore
        get() = localImageLoraStoreRef

    override val localImageWorkerClient: LocalImageWorkerClient
        get() = localImageWorkerClientRef

    override val imageAssetDirectory: File
        get() = imageAssetDirectoryRef

    override val imageLibraryCommitPort: ImageLibraryCommitPort
        get() = imageLibraryCommitPortProvider()

    override val chatImageRequestPort: ChatImageRequestPort
        get() = chatImageRequestPortProvider()

    override val chatImageCommitJournal: ChatImageCommitJournal
        get() = chatImageCommitJournalRef

    override suspend fun awaitImageLibraryStartupReconciliation() {
        imageLibraryStartupReconciliationProvider().await()
    }

    override fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(
            store = store,
            coordinator = coordinator,
            observationLock = coordinatorObservationLock,
            requestId = requestId
        )

    override fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean = releaseObservedImageGenerationLeaseFor(
        store = store,
        coordinator = coordinator,
        observationLock = coordinatorObservationLock,
        lease = lease
    )

    override fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora> = resolveCurrentLocalImageLorasFor(selections, records)

    override fun resolveCurrentLocalImageTextualInversionIds(
        ids: List<String>,
        records: List<TextualInversionArtifact>
    ): List<String> = resolveCurrentLocalImageTextualInversionIdsFor(ids, records)

    override suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution = prepareLocalImagePromptExecution(
        port = imagePromptTranslationPortProvider(),
        model = model,
        prompt = prompt,
        options = options,
        captured = captured
    )

    override suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease =
        prepareLocalImageRuntimeMemoryFor(
            store, runtimeAdmission, runtimeProfileStoreRef, engineRef
        )

    override fun startLocalImageGenerationWatchdog(jobId: String) =
        startLocalImageGenerationWatchdogFor(store, scopeRef, jobId)

    override fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? =
        publishLocalImagePreviewTo(
            store,
            runtimeAdmission,
            applicationRef,
            imageAssetWriteMutex,
            jobId,
            progress
        )

    override fun cleanupLocalImagePreviews(jobId: String) =
        cleanupLocalImagePreviewsFor(applicationRef, jobId)

    override suspend fun createCloudGeneratedImageAssets(
        request: CloudImageRequest,
        config: CloudApiConfig,
        chatSessionId: String?,
        generationMetadata: ImageGenerationHistoryMetadata
    ): List<ImageAssetRecord> = createCloudGeneratedImageAssetsFor(
        cloudImageProviderRef,
        imageAssetWriteMutex,
        imageAssetDirectoryRef,
        request,
        config,
        chatSessionId,
        generationMetadata
    )

    override suspend fun createLocalGeneratedImageAsset(
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions,
        inputDraft: LocalImageInputDraft,
        chatSessionId: String?,
        generationMetadata: ImageGenerationHistoryMetadata,
        batchLineage: ImageGenerationBatchLineage?,
        outputLineages: List<ImageGenerationBatchLineage>,
        onProgress: (LocalImageProgress) -> Unit
    ): List<ImageAssetRecord> = CreateLocalGeneratedImageAssetOperations.createLocalGeneratedImageAsset(
        port = createLocalGeneratedImageAssetPort,
        requestId = requestId,
        prompt = prompt,
        model = model,
        options = options,
        inputDraft = inputDraft,
        chatSessionId = chatSessionId,
        generationMetadata = generationMetadata,
        batchLineage = batchLineage,
        outputLineages = outputLineages,
        onProgress = onProgress
    )

    override fun completeAssistantImageToolTurnForJob(jobId: String) =
        completeAssistantImageToolTurnForJobRef(jobId)
}

/**
 * R3-S32：图片放大模型目录端口具名适配器（store / preferences / ownerLockProvider 真实依赖注入）。
 */
internal class LocalImageUpscalerPortAdapter(
    private val store: UiStateStore,
    private val upscalerStoreRef: LocalImageUpscalerStore,
    private val preferences: android.content.SharedPreferences,
    private val imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
) : LocalImageUpscalerPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val upscalerStore: LocalImageUpscalerStore
        get() = upscalerStoreRef

    override fun persistSelectedUpscalerId(id: String?): Boolean =
        preferences.edit()
            .putString(IMAGE_UPSCALER_SELECTED_ID, id)
            .commit()

    override fun <T> withUpscaleLifecycleLock(block: (upscaleRunning: Boolean) -> T): T =
        imageUpscaleOwnerProvider().withUpscaleLifecycleLock(block)
}

/**
 * R3-S33：提示词翻译预览端口具名适配器（直调 operations；service 以 provider 保 lazy 时机）。
 */
internal class ImagePromptTranslationPreviewPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val downloader: OfflinePromptTranslationBundleDownloader,
    private val serviceProvider: () -> OfflinePromptTranslationService
) : ImagePromptTranslationPreviewPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val offlinePromptTranslationService: OfflinePromptTranslationService
        get() = serviceProvider()

    override suspend fun verifiedOfflineTranslationBundle(): VerifiedOfflinePromptTranslationBundle? =
        verifiedOfflineTranslationBundleFor(store, admission, downloader)
}

/**
 * R3-S34：助手生图工具轮次端口具名适配器（运行态注册直调 holder；授权直查 store；聊天操作由 lambda 委托）。
 */
internal class AssistantImageToolTurnPortAdapter(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val authorizationStore: ChatImageToolAuthorizationStore,
    private val chatImageNegativePromptSupportedRef: (MainUiState) -> Boolean,
    private val prepareAssistantImagePromptRef: suspend (
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>
    ) -> ChatImagePromptBridge.Result,
    private val prepareAssistantImageNegativePromptRef: suspend (
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>,
        preserveAuthoredNegative: Boolean
    ) -> ChatImagePromptBridge.Result,
    private val setPendingAssistantToolFailureRef: (String) -> Unit,
    private val buildChatImageRetrySnapshotRef: (
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ) -> ImageGenerationJobSpec?,
    private val attachAssistantImageRequestToTimelineRef: (
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ) -> Unit,
    private val approveChatImageRequestRef: (String) -> Unit
) : AssistantImageToolTurnPort {
    override fun activeChatSessionId(): String? = store.current.activeChatSessionId

    override fun chatImageNegativePromptSupported(): Boolean =
        chatImageNegativePromptSupportedRef(store.current)

    override suspend fun prepareAssistantImagePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>
    ): ChatImagePromptBridge.Result = prepareAssistantImagePromptRef(
        prompt, chatConfig, params, conversationContext
    )

    override suspend fun prepareAssistantImageNegativePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>,
        preserveAuthoredNegative: Boolean
    ): ChatImagePromptBridge.Result = prepareAssistantImageNegativePromptRef(
        prompt, chatConfig, params, conversationContext, preserveAuthoredNegative
    )

    override fun setPendingAssistantToolFailure(message: String) {
        setPendingAssistantToolFailureRef(message)
    }

    override fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? = buildChatImageRetrySnapshotRef(request, chatSessionId)

    override fun registerPendingAssistantImageToolTurn(turn: AssistantImageToolPendingTurn) {
        admission.pendingAssistantImageToolTurns[turn.requestId] = PendingAssistantImageToolTurn(
            requestId = turn.requestId,
            chatSessionId = turn.chatSessionId,
            originalRequestMessages = turn.originalRequestMessages,
            params = turn.params,
            cloudModelId = turn.cloudModelId,
            toolCalls = turn.toolCalls,
            primaryToolCallId = turn.primaryToolCallId,
            initialToolOutputs = turn.initialToolOutputs
        )
    }

    override fun attachAssistantImageRequestToTimeline(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ) {
        attachAssistantImageRequestToTimelineRef(chatSessionId, request)
    }

    override fun currentTurnSelectionSnapshot(): AssistantImageToolTurnSelection {
        val state = store.current
        return AssistantImageToolTurnSelection(
            activeChatSessionId = state.activeChatSessionId,
            selectedCloudChatModelId = state.selectedCloudChatModelId,
            chatModelIdentity = chatImageToolChatModelIdentity(
                state.selectedCloudChatModelId,
                state.selectedChatCloudConfig()?.normalized()
            ),
            imageAuthorizationIdentity = state.chatImageModelBinding()?.authorizationIdentity()
        )
    }

    override fun chatImageToolAuthorizationMode(
        chatSessionId: String?,
        chatModelIdentity: String?,
        imageModelIdentity: String?
    ): ChatImageToolAuthorizationMode = authorizationStore.getMode(
        chatSessionId,
        chatModelIdentity,
        imageModelIdentity
    )

    override fun approveChatImageRequest(requestId: String) {
        approveChatImageRequestRef(requestId)
    }
}

/**
 * R3-S35：聊天生图审批端口具名适配器（A 族第 4 端口）。
 */
internal class ChatImageRequestApprovalPortAdapter(
    private val store: UiStateStore,
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

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
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
    private val store: UiStateStore,
    private val modelStore: ModelStoreRepository,
    private val localImageModelStore: LocalImageModelStore,
    private val refreshGate: ManagedRuntimeReadinessRefreshGate,
    private val admission: RuntimeAdmissionState,
    private val coordinator: LocalImageGenerationCoordinator,
    private val imageLibraryBackupJobActiveProvider: () -> Boolean,
    private val hasActiveImageUpscaleProvider: () -> Boolean
) : ManagedRuntimeReadinessPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

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
    private val storeProvider: () -> UiStateStore
) : AppUpdatePort {
    override fun uiState(): MainUiState = storeProvider().current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        storeProvider().update(transform)
    }
}

/**
 * R3-S38：云模型配置端口具名适配器（B 族第 3 端口）。
 */
internal class CloudModelConfigPortAdapter(
    private val application: Application,
    private val store: UiStateStore,
    override val cloudApiStore: CloudApiStore,
    override val cloudChatProvider: OpenAiCompatibleChatProvider,
    override val localImageModelStore: LocalImageModelStore,
    private val loadModelGenerationParamsRef: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    private val persistChatSessionsRef: (List<ChatSessionRecord>?, Pair<String, Set<String>>?) -> Unit
) : CloudModelConfigPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

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
    private val store: UiStateStore,
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

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
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
    ): GenerationParams = assistantGenerationParamsFromJson(
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

internal class RuntimeProfileTransactionPortAdapter(
    private val store: UiStateStore,
    override val runtimeProfileStore: ModelRuntimeProfileStore,
    override val engine: McaInferenceService,
    private val runtimeAdmission: RuntimeAdmissionState,
    private val tuningOwner: TuningOwner,
    private val showStatusAction: (String) -> Unit,
) : RuntimeProfileTransactionPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val activeModelForRuntimeProfile: ModelManifest?
        get() = runtimeAdmission.activeModelForRuntimeProfile

    override var pendingProfileTransactionId: String?
        get() = runtimeAdmission.pendingProfileTransactionId
        set(value) {
            runtimeAdmission.pendingProfileTransactionId = value
        }

    override fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity =
        tuningOwner.candidateMeasurement(profileId, jobId, phase, result)

    override fun tuningCandidateScore(result: CandidateCanaryResult): CandidateScore =
        canaryScore(port = tuningOwner.canaryPort, result)

    override fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams = mergedExecutionProfile(generation, profile)

    override suspend fun restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean = tuningOwner.restoreExactRuntimeProfile(model, profile)

    override fun showStatus(message: String) {
        showStatusAction(message)
    }

    override fun asTuningExecutionProfile(
        profile: ModelExecutionProfile,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile =
        tuningOwner.asTuningExecutionProfile(profile, kind = kind, verificationLevel = verificationLevel, reason = reason)

    override suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: CanaryEvaluationParams,
        authorization: LoadAuthorization,
        disposition: PendingProfileDisposition,
        lifecycleLease: EngineLifecycleLease?
    ): CandidateCanaryResult = tuningOwner.runCandidateCanary(
        candidate = candidate,
        canaryParams = canaryParams,
        authorization = authorization,
        disposition = disposition,
        lifecycleLease = lifecycleLease
    )
}




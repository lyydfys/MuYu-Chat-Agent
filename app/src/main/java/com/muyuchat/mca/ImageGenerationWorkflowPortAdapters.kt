package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.EngineLifecycleLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import java.io.File
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationParams

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

package com.muyuchat.mca

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.feature.chat.ChatMessageIntent
import com.muyuchat.mca.composition.MainCoordinatorAssembly
import com.muyuchat.mca.composition.MainCoordinatorHost
import com.muyuchat.mca.composition.MainCoordinatorHostAdapter
import com.muyuchat.mca.composition.MainViewModelPortFactory
import com.muyuchat.mca.composition.MainRuntimeAssembly
import com.muyuchat.mca.composition.McaAppContainer
import com.muyuchat.mca.presentation.StoreKnowledgeLibraryPort
import com.muyuchat.mca.presentation.afterClearChatGenerationStopped
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

enum class AppTab(val title: String) {
    CHAT("聊天"), AGENT("智能调参"), MODELS("模型管理"), API("本地 API"), SETTINGS("系统设置")
}

internal data class MainViewModelStartupContext(
    val container: McaAppContainer,
    val installationScopeId: String,
    val modelIdentityReferencesMigrated: Boolean
)

class MainViewModel @JvmOverloads internal constructor(
    application: Application,
    private val deferStartup: Boolean = false,
    internal val skipInitialModelDiscovery: Boolean = false,
    injectedEngine: Any? = null,
    injectedLocalApiRuntimeOwner: Any? = null,
    injectedChatAppearanceCoordinator: Any? = null,
    injectedMainPostStartupCoordinator: Any? = null,
    injectedChatSessionManagementCoordinator: Any? = null,
    injectedChatInputAndAttachmentCoordinator: Any? = null,
    injectedChatImageWorkflowCoordinator: Any? = null,
    injectedChatWorkflowCoordinator: Any? = null,
    injectedDocumentImportCoordinator: Any? = null,
    injectedAppLifecycleCoordinator: Any? = null,
    injectedCacheManagementCoordinator: Any? = null,
    injectedStartupContext: MainViewModelStartupContext? = null
) : AndroidViewModel(application) {
    companion object {
        private val localApiProcessLifecycleLock = Any()
        private var localApiProcessOwnerToken: Any? = null
        private const val BACKGROUND_TASK_STATUS = "已转入后台运行，返回 MCA 后查看进度。"
        private const val CONTEXT_COMPRESSION_PREFERENCES = "mca_context_compression_v1"
        internal const val MAX_DIRECT_COMPOSER_UTF8_BYTES = 256 * 1024
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    internal val container = injectedStartupContext?.container ?: error("MainViewModel requires precomputed startup context")
    internal val modelIdentityReferenceMigration = ModelIdentityReferenceMigration(application)
    internal val modelGenerationProfileStore = container.modelGenerationProfileStore
    internal val installationScopeId =
        injectedStartupContext?.installationScopeId
            ?: if (injectedEngine != null) "" else error("MainViewModel requires precomputed startup context")
    internal val runtimeAssembly = MainRuntimeAssembly(
        application = application,
        container = container,
        scope = viewModelScope,
        installationScopeId = installationScopeId,
        injectedEngine = injectedEngine
    )
    internal val modelScopeClient = container.modelScopeClient
    private val appUpdatePort: AppUpdatePort = container.createAppUpdatePort { uiStateStore }
    internal val appUpdateOwner = AppUpdateOwner(appUpdatePort, viewModelScope)
    private val initialAppUpdateState = appUpdateOwner.loadInitialAppUpdateState()
    private val apiKey = GenerationPreferences.loadOrCreateApiKey(application)
    internal val localApiRuntimeOwner: LocalApiRuntimeOwner = (injectedLocalApiRuntimeOwner as? LocalApiRuntimeOwner) ?: container.createLocalApiRuntimeOwner()
    private val initialApiPreferences = loadApiPreferences(application)
    internal var foregroundRecoveryJob: Job? = null
    private val foregroundRecoverySequence = AtomicLong(0L)
    internal val chatSessionStore = container.chatSessionStore
    internal val characterMemorySummaryCoordinator = CharacterMemorySummaryCoordinator()
    internal val knowledgeLibraryPort = StoreKnowledgeLibraryPort(container.worldBookStore, container.knowledgeBaseStore)
    internal val chatContextComposer = ChatContextComposer(StoreChatContextSources(container.worldBookStore, container.knowledgeBaseStore))
    private val chatImageCommitRecoveryPort: ChatImageCommitRecoveryPort = container.createChatImageCommitRecoveryPort({ container.chatImageCommitJournal }, chatSessionStore)
    internal val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application)) }
    internal var activeWebSearchDeferred: Deferred<WebSearchResult>? get() = container.runtimeAdmission.activeWebSearchDeferred; set(v) { container.runtimeAdmission.activeWebSearchDeferred = v }
    @Volatile internal var activeWebSearchTaskId: String? = null
    internal val contextCompressionPreferences = application.getSharedPreferences(CONTEXT_COMPRESSION_PREFERENCES, Context.MODE_PRIVATE)
    internal val benchmarkRunner = runtimeAssembly.benchmarkRunner
    internal val agentLogger = AgentDecisionLogger(application)
    internal val diagnosticsOperations by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runtimeAssembly.createDiagnosticsOperations(
            store = uiStateStore,
            agentLogger = agentLogger,
            currentDeviceProfile = { currentDeviceProfile() }
        )
    }
    private val initialParams = loadGenerationParams(application)
    private val initialAssistants = container.assistantStore.loadAssistants(initialParams)
    private val loadedChatSessions = chatSessionStore.load()
    private val initialStoredSelectedAssistantId = container.assistantStore.loadSelectedAssistantId(initialAssistants)
    internal val initialChatSessions = loadedChatSessions.withInterruptedChatImageRequests().withBackfilledAssistantSnapshots(initialAssistants).let { sessions ->
        val selected = initialAssistants.firstOrNull { it.id == initialStoredSelectedAssistantId }
        if (selected != null && sessions.latestSessionForAssistant(selected.id) == null) listOf(selected.newConversationSession(null, null)) + sessions else sessions
    }.also { snapshots -> if (snapshots != loadedChatSessions) { runCatching { chatSessionStore.save(snapshots) } } }
    private val bootstrapResult = MainUiBootstrapFactory.create(
        application = application, container = container, isRuntimeAvailable = runtimeAssembly::isRuntimeAvailable, modelScopeClient = modelScopeClient, agentLogger = agentLogger,
        benchmarkHistoryLogger = container.benchmarkHistoryLogger, deviceProfileReader = container.deviceProfileReader, chatSessionStore = chatSessionStore,
        worldBookStore = container.worldBookStore, knowledgeBaseStore = container.knowledgeBaseStore, cloudApiStore = container.cloudApiStore, localImageModelStore = container.localImageModelStore,
        localImageLoraStore = container.localImageLoraStore, imageUpscalerPreferences = container.imageUpscalerPreferences, modelGenerationProfileStore = modelGenerationProfileStore,
        contextCompressionPreferences = contextCompressionPreferences, apiKey = apiKey, currentOpenApiAddress = currentOpenApiAddress(), apiUrl = ::apiUrl,
        skipInitialModelDiscovery = skipInitialModelDiscovery, initialAppUpdateState = initialAppUpdateState, initialParams = initialParams, initialAssistants = initialAssistants,
        initialStoredSelectedAssistantId = initialStoredSelectedAssistantId, initialChatSessions = initialChatSessions
    )
    private val chatSessionPersistenceStateInit = container.initializeChatSessionPersistenceState(initialChatSessions)
    @Volatile private var replyGenerationMetrics: ReplyGenerationMetrics? = null
    private val stopGenerationInProgress = AtomicBoolean(false)
    internal val chatRuns: ChatRunOwnership get() = container.chatRuns
    internal val localApiIdempotencyJournalLock = Any()
    internal val uiStateStore = UiStateStore(bootstrapResult.uiState)
    private val settingsCleanupLock = Any()
    private var activeSettingsCleanupJob: Job? = null
    private val chatStreamAccumulatorInit = container.initializeChatStreamAccumulator(uiStateStore, viewModelScope)
    internal val chatAppearanceCoordinator: ChatAppearanceCoordinator = (injectedChatAppearanceCoordinator as? ChatAppearanceCoordinator) ?: container.createChatAppearanceCoordinator(scope = viewModelScope, uiStateStore = uiStateStore, showStatus = ::showStatus)
    internal val imageLibraryStartupReconciliation = container.createImageLibraryStartupReconciliation(uiStateStore, viewModelScope, chatImageCommitRecoveryPort, bootstrapResult.initialKnowledgeBases)
    internal val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    internal val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    internal val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator = container.createWebSearchWorkflowCoordinator(uiStateStore, chatTerminalSettlementPort, viewModelScope)
    internal val cacheManagementCoordinator: CacheManagementCoordinator = (injectedCacheManagementCoordinator as? CacheManagementCoordinator) ?: runtimeAssembly.createCacheManagementCoordinator(store = uiStateStore, chatRuns = chatRuns, refreshPersistentPrefixCacheSummary = { refreshPersistentPrefixCacheSummary() }, markLocalConversationContextInvalid = { markLocalConversationContextInvalid() })
    internal val visionAttachmentPort = runtimeAssembly.createVisionAttachmentPort(uiStateStore)
    internal val tuningOwner = runtimeAssembly.createTuningOwner(uiStateStore)
    init {
        runtimeAssembly.initializeImageGenerationOwner(
            store = uiStateStore,
            imageLibraryStartupReconciliationProvider = { imageLibraryStartupReconciliation },
            imageLibraryCommitPortProvider = { imageLibraryCommitPort },
            chatImageRequestPortProvider = { chatImageRequestPort },
            imagePromptTranslationPortProvider = { imagePromptTranslationPort },
            completeAssistantImageToolTurnForJob = { assistantImageContinuationCoordinator.completeAssistantImageToolTurnForJob(it) }
        )
    }
    internal val imageGenerationOwner get() = runtimeAssembly.imageGenerationOwner
    val uiState = uiStateStore.state
    private val _uiState: MutableStateFlow<MainUiState> get() = uiStateStore.ownerFlow

    internal fun showStatus(message: String?) { uiStateStore.showStatus(message) }

    internal fun launchSettingsCleanup(
        operation: SettingsCleanupOperation,
        start: () -> Job?
    ): Job? = synchronized(settingsCleanupLock) {
        if (activeSettingsCleanupJob?.isActive == true) return null
        uiStateStore.update { state ->
            state.copy(
                settingsOperations = state.settingsOperations.copy(cleanup = operation)
            )
        }
        val job = start()
        if (job == null) {
            activeSettingsCleanupJob = null
            uiStateStore.update { state ->
                if (state.settingsOperations.cleanup == operation) {
                    state.copy(settingsOperations = state.settingsOperations.copy(cleanup = null))
                } else {
                    state
                }
            }
            return null
        }
        activeSettingsCleanupJob = job
        job.invokeOnCompletion {
            uiStateStore.update { state ->
                if (state.settingsOperations.cleanup == operation) {
                    state.copy(settingsOperations = state.settingsOperations.copy(cleanup = null))
                } else {
                    state
                }
            }
            synchronized(settingsCleanupLock) {
                if (activeSettingsCleanupJob === job) activeSettingsCleanupJob = null
            }
        }
        job
    }

    internal fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(store = uiStateStore, coordinator = localImageGenerationCoordinator, observationLock = localImageGenerationCoordinatorObservationLock, requestId = requestId)

    internal fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean =
        releaseObservedImageGenerationLeaseFor(store = uiStateStore, coordinator = localImageGenerationCoordinator, observationLock = localImageGenerationCoordinatorObservationLock, lease = lease)

    fun releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock: () -> Unit): Boolean = chatImageWorkflowCoordinator.releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock)

    private fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
        imageGenerationOwner.cancelActiveLocalApiImageGeneration(reason)

    internal val localApiServerCoordinator = LocalApiServerCoordinator(
        application = application, scope = viewModelScope, store = uiStateStore, localApiRuntimeOwner = localApiRuntimeOwner, apiKey = apiKey,
        cancelActiveLocalApiImageGeneration = ::cancelActiveLocalApiImageGeneration, persistApiPreferences = ::persistApiPreferences, apiUrl = ::apiUrl,
        currentOpenApiAddress = ::currentOpenApiAddress, onInterruptForegroundRecovery = { foregroundRecoverySequence.incrementAndGet(); foregroundRecoveryJob?.cancel() }
    )

    private fun updateGenerationImageGrantReleaseDefer(defer: Boolean) = chatImageWorkflowCoordinator.updateGenerationImageGrantReleaseDefer(defer)

    internal fun observeManagedDownloads() {
        modelImportAndDownloadCoordinator.observeManagedDownloads { image ->
            val selection = settleLocalImageSelection(image)
            uiStateStore.update { it.copy(localImageModels = selection.models, qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId, selectedLocalImageModelId = selection.selectedId, selectedImageBackend = selection.selectedBackend) }
        }
    }

    private val coordinatorHost: MainCoordinatorHost = MainCoordinatorHostAdapter(this)
    internal val coordinatorAssembly: MainCoordinatorAssembly = MainCoordinatorAssembly.create(host = coordinatorHost, injectedMainPostStartupCoordinator = injectedMainPostStartupCoordinator, injectedChatSessionManagementCoordinator = injectedChatSessionManagementCoordinator, injectedChatInputAndAttachmentCoordinator = injectedChatInputAndAttachmentCoordinator)
    internal val documentImportCoordinator: DocumentImportCoordinator = (injectedDocumentImportCoordinator as? DocumentImportCoordinator) ?: DocumentImportCoordinator(application = application, uiStateStore = uiStateStore, scope = viewModelScope, knowledgeBaseStore = container.knowledgeBaseStore, chatSessionStore = chatSessionStore, chatSessionPersistenceState = container.chatSessionPersistenceState, contentImportSelectionRevision = container.runtimeAdmission.contentImportSelectionRevision, stageContentImportPreview = { contentImportPreviewCoordinator.stageContentImportPreview(it) }, markLocalConversationContextInvalid = { markLocalConversationContextInvalid() }, showStatus = { showStatus(it) })
    private val appLifecycleCoordinator: AppLifecycleCoordinator = (injectedAppLifecycleCoordinator as? AppLifecycleCoordinator) ?: runtimeAssembly.createAppLifecycleCoordinator(store = uiStateStore, chatRuns = chatRuns, localApiServerCoordinator = localApiServerCoordinator, offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator, assistantImageContinuationCoordinator = assistantImageContinuationCoordinator, maybeCheckForAppUpdate = { maybeCheckForAppUpdate() }, foregroundRecoverySequence = foregroundRecoverySequence, getForegroundRecoveryJob = { foregroundRecoveryJob }, setForegroundRecoveryJob = { foregroundRecoveryJob = it })
    internal val _uiEffects = kotlinx.coroutines.flow.MutableSharedFlow<com.muyuchat.mca.presentation.MainUiEffect>(extraBufferCapacity = 64)
    val uiEffects: kotlinx.coroutines.flow.SharedFlow<com.muyuchat.mca.presentation.MainUiEffect> = _uiEffects

    init { if (!deferStartup) startAfterConstruction() }

    internal fun startAfterConstruction() {
        mainPostStartupCoordinator.runPostStartupWork()
        viewModelScope.launch {
            ProcessUiLifecycleEvents.events.collect { event ->
                when (event) {
                    ProcessUiLifecycleEvent.FOREGROUNDED -> onAppForegrounded()
                    ProcessUiLifecycleEvent.BACKGROUNDED -> onAppBackgrounded()
                }
            }
        }
        claimLocalApiProcessOwnership()
        LocalApiRuntime.claimOwner(localApiRuntimeOwner) {
            retireLocalApiListener(stopForegroundService = false, forceServerStop = false)
        }
        installChatLocalApiProviders(chatLocalApiProviderPort)
        localApiRuntimeOwner.publishBindings(auxProviderPort = localApiAuxProviderPort, controlPlane = localApiControlPlaneCoordinator.createControlPlane())
        if (initialApiPreferences.apiEnabled) {
            requestLocalApiState(enabled = true, restEnabled = initialApiPreferences.restEnabled, failurePrefix = "本机 API 自动恢复失败")
        }
    }

    internal fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot = publishChatCatalogSnapshot(managedRuntimeReadinessPort)
    fun retryImageGeneration(jobId: String) = chatImageWorkflowCoordinator.retryImageGeneration(jobId)
    private val imageAssetCreationPort: ImageAssetCreationPort get() = chatImageWorkflowCoordinator.imageAssetCreationPort
    internal val imageUpscaleOwner: ImageUpscaleOwner get() = chatImageWorkflowCoordinator.imageUpscaleOwner
    private val imagePromptTranslationPort: ImagePromptTranslationPort get() = chatImageWorkflowCoordinator.imagePromptTranslationPort
    private fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? = chatImageWorkflowCoordinator.publishLocalImagePreview(jobId, progress)
    private fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean = rejectImageModelSwitchWhileGenerationIsActiveFor(uiStateStore, container.runtimeAdmission)
    private fun settleLocalImageSelection(preferred: LocalImageModelRecord): LocalImageSelection = localImageLibraryAndModelCoordinator.settleLocalImageSelection(preferred)

    fun deleteLocalImageModel(modelId: String) {
        launchModelOperation("模型操作未完成") {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                showStatus("当前图片任务正在使用该模型，请停止或等待任务完成后再删除")
                return@launchModelOperation
            }
            val removed = uiStateStore.current.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            val success = runCatching { container.localImageModelStore.deleteModel(modelId) }.getOrDefault(false)
            settleAndApplyLocalImageModelCatalogUpdate(store = uiStateStore, localImageModelStore = container.localImageModelStore, context = getApplication(), managedRuntimeReadinessRefreshGate = container.readinessRefreshGate, statusMessage = if (success) "已删除本地图像生成引擎：${removed?.displayName ?: "模型"}" else "未找到本地图像生成引擎")
        }
    }

    fun selectImageGenerationModel(choiceId: String) = chatImageWorkflowCoordinator.selectImageGenerationModel(choiceId)

    fun selectAssistant(assistantId: String) {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val previousSession = state.chatSessions.latestSessionForAssistant(assistantId)
        if (previousSession != null) {
            if (previousSession.id != state.activeChatSessionId || state.selectedAssistantId != assistant.id) selectChatSession(previousSession.id)
            if (uiStateStore.current.activeChatSessionId == previousSession.id && previousSession.modelMode.toChatBackendOrNull() == ChatBackend.LOCAL) {
                previousSession.modelId?.let { modelId ->
                    if (uiStateStore.current.loadedModelId != modelId) uiStateStore.current.models.firstOrNull { it.id == modelId }?.let(::loadModel)
                }
            }
            return
        }
        if (!newChatForAssistant(assistant, bindDefaultModel = true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun updateAgentPreference(preference: UserPreference) = agentTuningCoordinator.updateAgentPreference(preference)
    fun sendMessage() { chatWorkflowCoordinator.sendMessage(this) }

    internal fun sendChatImageRequestWithPromptBridge(
        visibleUserText: String, prompt: String, requestedOutputCount: Int? = null, allowCloudTranslationFallback: Boolean = false
    ) = runChatImagePromptBridge(port = chatImagePromptBridgePort, visibleUserText = visibleUserText, prompt = prompt, requestedOutputCount = requestedOutputCount, allowCloudTranslationFallback = allowCloudTranslationFallback)

    internal fun chatImageNegativePromptSupported(state: MainUiState): Boolean = chatImageNegativePromptSupportedFor(state)

    fun regenerateLastResponse() = regenerateLastResponseFor(null)

    private fun regenerateLastResponseFor(target: ChatMessageIdentity?) {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) return
        val lastAssistant = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (lastAssistant < 0) return
        if (target != null) {
            val targetIndex = target.resolveIndex(state.messages, state.activeChatSessionId)
            if (targetIndex == null || targetIndex != lastAssistant) return
        }
        val priorUser = state.messages.take(lastAssistant).indexOfLast { it.role == Role.USER }
        if (priorUser < 0) return
        val rollback = state.conversationMutationRollbackState()
        discardPendingAssistantOutput()
        val kept = state.messages.take(lastAssistant) + ChatMessage(Role.ASSISTANT, "")
        val generationReservation = reserveUiGenerationStart() ?: return
        val preparationLease = McaGenerationForegroundService.acquire(getApplication<Application>(), McaGenerationForegroundService.KIND_CHAT)
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        uiStateStore.update {
            val sessionId = it.activeChatSessionId ?: UUID.randomUUID().toString()
            sessionsToPersist = it.chatSessions.upsertSessionFor(state = it, sessionId = sessionId, messages = kept, assistantId = it.selectedAssistantId, modelMode = it.selectedChatBackend.bindingValue(), modelId = it.currentChatModelId())
            it.copy(messages = kept, activeChatSessionId = sessionId, chatSessions = sessionsToPersist, isGenerating = true, generationPhase = null, generationTokenProgress = null, generationStats = null, promptContextUsage = null, statusMessage = "正在重新生成上一条回答...")
        }
        val persistenceJob = persistConversationMutation(
            sessions = sessionsToPersist, rollback = rollback,
            onCommitted = { startGeneration(kept.dropLast(1), generationReservation) },
            onCommitFailed = { chatRuns.cancelPending(generationReservation) }
        )
        persistenceJob.invokeOnCompletion {
            chatRuns.cancelPending(generationReservation)
            McaGenerationForegroundService.release(getApplication<Application>(), preparationLease)
        }
    }

    fun deleteMessageAt(index: Int) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteMessageAtFor(store = uiStateStore, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) }, index = index)
    }

    fun deleteMessage(target: ChatMessageIdentity) {
        deleteMessageFor(store = uiStateStore, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) }, target = target)
    }

    fun handleChatMessageIntent(intent: ChatMessageIntent) {
        when (intent) {
            is ChatMessageIntent.Delete -> deleteMessage(intent.target)
            is ChatMessageIntent.Regenerate -> regenerateLastResponseFor(intent.target)
            is ChatMessageIntent.DeleteLastTurn ->
                chatWorkflowCoordinator.deleteLastConversationTurn(currentSessionId, intent.target)
        }
    }

    val currentSessionId: String? get() = uiStateStore.current.activeChatSessionId
    fun deleteLastConversationTurn() = chatWorkflowCoordinator.deleteLastConversationTurn(currentSessionId) // preserveReusableNativePrefix = true
    internal fun reserveUiGenerationStart(): UiGenerationReservation? = chatRuns.reserveStartAndCancelSuperseded()
    internal fun cancelGenerationJob(): UiGenerationCancellation = chatRuns.cancelCurrentAndCancelOwner()

    internal fun startGeneration(
        requestMessages: List<ChatMessage>, reservation: UiGenerationReservation? = null, toolExchanges: List<ChatToolExchange> = emptyList(),
        paramsOverride: GenerationParams? = null, offerAssistantImageTool: Boolean = true, chatModelIdOverride: String? = null,
        continuationRequestId: String? = null, preflightCompression: com.muyuchat.core.engine.ContextCompressionResult? = null
    ): Boolean {
        val admittedReservation = reservation ?: reserveUiGenerationStart() ?: return false
        val generationRunId = admittedReservation.runId
        val replyMetrics = ReplyGenerationMetrics(generationRunId)
        val ownedGenerationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            chatExecutionPort.runChatGeneration(
                requestMessages = requestMessages, reservation = admittedReservation, replyMetrics = replyMetrics, toolExchanges = toolExchanges,
                paramsOverride = paramsOverride, offerAssistantImageTool = offerAssistantImageTool, chatModelIdOverride = chatModelIdOverride,
                continuationRequestId = continuationRequestId, preflightCompression = preflightCompression
            )
        }
        if (!chatRuns.activate(admittedReservation, ownedGenerationJob)) {
            ownedGenerationJob.cancel(); chatRuns.cancelPending(admittedReservation); return false
        }
        val foregroundLease = McaGenerationForegroundService.acquire(getApplication<Application>(), McaGenerationForegroundService.KIND_CHAT)
        chatRuns.publishJob(ownedGenerationJob)
        replyGenerationMetrics = replyMetrics
        ownedGenerationJob.invokeOnCompletion {
            try {
                chatRuns.finish(generationRunId, ownedGenerationJob)
                chatRuns.retirePublishedJob(ownedGenerationJob)
            } finally {
                McaGenerationForegroundService.release(getApplication<Application>(), foregroundLease)
            }
        }
        ownedGenerationJob.start()
        return true
    }

    fun stopGeneration() { stopActiveGeneration(chatStopGenerationPort) }
    fun selectChatSession(sessionId: String) { chatSessionManagementCoordinator.selectChatSession(sessionId) }
    fun newChat() { if (!rejectWhileConversationMutationInProgress()) newChatForAssistant(uiStateStore.current.selectedAssistant()) }

    internal fun newChatForAssistant(assistant: AssistantRecord?, bindDefaultModel: Boolean = false): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        return chatSessionManagementCoordinator.newChatForAssistant(assistant, bindDefaultModel)
    }

    fun deleteChatSession(sessionId: String) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteChatSessionFor(store = uiStateStore, scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) }, manualContextCompressionRequests = manualContextCompressionRequests, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup, persistConversationMutation = { remaining, rollback -> persistConversationMutation(remaining, rollback) }, chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore, cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.mutex.withLock { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() } }, sessionId = sessionId)
    }

    fun clearChatHistory() {
        if (rejectWhileConversationMutationInProgress()) return
        launchSettingsCleanup(SettingsCleanupOperation.CHAT_HISTORY) {
            clearChatHistoryFor(store = uiStateStore, scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) }, manualContextCompressionRequests = manualContextCompressionRequests, queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback, onCommitted, onCommitFailed -> persistConversationMutation(sessions, rollback, onCommitted = onCommitted, onCommitFailed = onCommitFailed) }, chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore)
        }
    }

    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        if (enabled) {
            cacheManagementCoordinator.setPersistentPrefixCacheEnabled(true)
        } else {
            launchSettingsCleanup(SettingsCleanupOperation.PERSISTENT_PREFIX_CACHE) {
                cacheManagementCoordinator.setPersistentPrefixCacheEnabled(false)
            }
        }
    }

    fun clearPersistentPrefixCache() {
        launchSettingsCleanup(SettingsCleanupOperation.PERSISTENT_PREFIX_CACHE) {
            cacheManagementCoordinator.clearPersistentPrefixCache()
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            manualContextCompressionRequests.clear(uiStateStore.current.activeChatSessionId)
            cancelGenerationJob()
            val stoppedStats = runtimeAssembly.stopGenerationAndReadStats()
            discardPendingAssistantOutput()
            markLocalConversationContextInvalid()
            uiStateStore.update { it.afterClearChatGenerationStopped(stoppedStats).copy(contextCompressionPending = false) }
            persistChatSessions()
        }
    }

    fun onAppBackgrounded() {
        chatRuns.background()
        uiStateStore.update { state ->
            val imageActive = state.imageJobs.any { job -> !job.status.terminal }
            if ((state.isGenerating || imageActive) && state.statusMessage != BACKGROUND_TASK_STATUS) {
                state.copy(statusMessage = BACKGROUND_TASK_STATUS)
            } else state
        }
        if (agentTuningCoordinator.adaptiveTuningJob?.isActive == true) pauseAgentTuning()
    }

    fun onAppForegrounded() = appLifecycleCoordinator.onAppForegrounded()

    override fun onCleared() {
        container.runtimeAdmission.chatImagePromptBridgeJob?.cancel()
        LocalApiRuntime.releaseOwner(localApiRuntimeOwner)
        retireLocalApiListener(stopForegroundService = false, forceServerStop = false)
        localApiRuntimeOwner.release()
        localImageWorkerClient.close()
        container.isolatedLocalChatRunners.close()
        super.onCleared()
    }

    private fun retireLocalApiListener(stopForegroundService: Boolean, forceServerStop: Boolean) = localApiServerCoordinator.retireLocalApiListener(stopForegroundService, forceServerStop)

    private fun claimLocalApiProcessOwnership() {
        localApiServerCoordinator.claimLocalApiProcessOwnership()
    }

    private val ports = runtimeAssembly.createPorts(
        store = uiStateStore, imageGenerationOwner = imageGenerationOwner,
        chatImageCommitJournalProvider = { container.chatImageCommitJournal }, localApiPayloadPortProvider = { localApiControlPlaneCoordinator.localApiPayloadPort },
        benchmarkRunner = benchmarkRunner, buildRecommendationAction = ::buildRecommendation, generateLocalApiImageAction = ::generateLocalApiImage,
        cancelActiveLocalApiImageGenerationAction = ::cancelActiveLocalApiImageGeneration, chatContextComposer = chatContextComposer, showStatus = ::showStatus,
        chatImageOptionsForCurrentSelection = ::chatImageOptionsForCurrentSelection, offlinePromptTranslationServiceProvider = { offlinePromptTranslationService },
        verifiedOfflineTranslationBundleRef = { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() },
        chatImageNegativePromptSupported = ::chatImageNegativePromptSupported, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        stopGenerationInProgress = stopGenerationInProgress, currentReplyMetricsRef = { replyGenerationMetrics }, cancelGenerationJobRef = { cancelGenerationJob() },
        flushPendingAssistantOutputRef = { flushPendingAssistantOutput() }, persistConversationMutationRef = { sessions, rollback -> persistConversationMutation(sessions, rollback) }
    )

    private val chatSessionPersistencePort: ChatSessionPersistencePort = ports.chatSessionPersistencePort
    internal val chatImageRequestPort: ChatImageRequestPort = ports.chatImageRequestPort
    private val sendChatImageRequestPort: SendChatImageRequestPort = ports.sendChatImageRequestPort
    private val chatImageRetryPort: ChatImageRetryPort = ports.chatImageRetryPort
    internal val imageLibraryCommitPort: ImageLibraryCommitPort = ports.imageLibraryCommitPort
    internal val imageAssetDeletionPort: ImageAssetDeletionPort get() = chatImageWorkflowCoordinator.imageAssetDeletionPort

    internal val chatImageWorkflowCoordinator: ChatImageWorkflowCoordinator = (injectedChatImageWorkflowCoordinator as? ChatImageWorkflowCoordinator) ?: runtimeAssembly.createChatImageWorkflowCoordinator(
        store = uiStateStore, imageAssetWriteMutex = container.imageAssetWriteMutex, imageLibraryMutationMutex = container.imageLibraryMutationMutex,
        imageLibraryBackup = imageLibraryBackup, chatSessionStore = chatSessionStore, imageGenerationOwner = imageGenerationOwner, imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
        chatImageRetryPort = chatImageRetryPort, showStatus = ::showStatus,
        offlinePromptTranslationService = { offlinePromptTranslationService }, runtimeProfileStore = container.runtimeProfileStore,
        selectCloudImageModel = ::selectCloudImageModel
    )

    internal val chatWorkflowCoordinator: ChatWorkflowCoordinator = (injectedChatWorkflowCoordinator as? ChatWorkflowCoordinator) ?: container.createChatWorkflowCoordinator(
        store = uiStateStore, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus,
        isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
        persistConversationMutation = { sessions, rollback, preserveNativePrefix -> persistConversationMutation(sessions = sessions, rollback = rollback, preserveReusableNativePrefix = preserveNativePrefix) }
    )

    fun deleteImageAsset(imageId: String) = deleteImageAssets(listOf(imageId))
    fun deleteImageAssets(imageIds: List<String>) = deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)
    fun setImageAssetFavorite(imageId: String, favorite: Boolean) = setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)
    fun clearImageLibrary() {
        launchSettingsCleanup(SettingsCleanupOperation.IMAGE_LIBRARY) {
            clearImageLibraryOperation(imageAssetDeletionPort)
        }
    }

    fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?) {
        val frozen = selections?.toList()
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLorasFor(it, uiStateStore.current.localImageLoras) }.isSuccess } == true
        uiStateStore.update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }
    }

    fun reportMissingLocalImageLoraSelection() {
        val state = uiStateStore.current
        val currentSelections = state.chatImageLoraSelections
        if (!currentSelections.isNullOrEmpty()) {
            val valid = runCatching { resolveCurrentLocalImageLorasFor(currentSelections, state.localImageLoras) }.isSuccess
            if (!valid) {
                uiStateStore.update { it.copy(chatImageLoraSelections = null) }
                showStatus("所选 LoRA 已删除，请重新选择。")
            }
        }
    }

    private val localApiAuxProviderPort: LocalApiAuxProviderPort = ports.localApiAuxProviderPort
    private val chatLocalApiProviderPort: ChatLocalApiProviderPort = ports.chatLocalApiProviderPort
    private val chatExecutionPort: ChatGenerationExecutionPort get() = coordinatorAssembly.chatExecutionPort
    private val chatImagePromptBridgePort: ChatImagePromptBridgePort = ports.chatImagePromptBridgePort
    private val chatStopGenerationPort: ChatStopGenerationPort = ports.chatStopGenerationPort

    internal fun markLocalConversationContextInvalid() = runtimeAssembly.markLocalConversationContextInvalid(container.runtimeAdmission, uiStateStore)
    internal fun rejectWhileConversationMutationInProgress(): Boolean = rejectWhileConversationMutationInProgressInto(uiStateStore, container.runtimeAdmission)

    internal suspend fun ensureLocalConversationContextInvalidated(): Boolean {
        conversationMutationBarrier?.join()
        localConversationContextInvalidationJob?.join()
        if (!localConversationContextNeedsInvalidation) return true
        val sequence = localConversationContextInvalidationSequence.get()
        return runtimeAssembly.invalidateConversationContext()
            .onSuccess { if (localConversationContextInvalidationSequence.get() == sequence) localConversationContextNeedsInvalidation = false }
            .onFailure { error ->
                if (localConversationContextInvalidationSequence.get() == sequence) {
                    uiStateStore.update { it.copy(isGenerating = false, generationPhase = null, generationTokenProgress = null, generationPersistProgress = null, statusMessage = "无法重置本地上下文，已取消本轮生成：${error.message ?: "native runtime error"}") }
                }
            }.isSuccess
    }

    internal fun persistChatSessions(
        sessions: List<ChatSessionRecord>? = null, knowledgeBinding: Pair<String, Set<String>>? = null, generationRunId: Long? = null
    ): Job? = runChatSessionPersistence(chatSessionPersistencePort, sessions, knowledgeBinding, generationRunId)

    private fun persistConversationMutation(
        sessions: List<ChatSessionRecord>, rollback: ConversationMutationRollbackState, preserveReusableNativePrefix: Boolean = false,
        onCommitted: (() -> Unit)? = null, onCommitFailed: (() -> Unit)? = null
    ): Job = runPersistConversationMutation(chatSessionPersistencePort, sessions, rollback, preserveReusableNativePrefix, onCommitted, onCommitFailed)

    private suspend fun generateLocalApiImage(requestId: String, body: String): String = chatImageWorkflowCoordinator.generateLocalApiImage(requestId, body)
    private fun loadApiPreferences(application: Application): LocalApiPreferences = GenerationPreferences.loadApiPreferences(application)
    internal fun persistApiPreferences(apiEnabled: Boolean, restEnabled: Boolean) = GenerationPreferences.persistApiPreferences(context = getApplication(), apiEnabled = apiEnabled, restEnabled = restEnabled)
    private fun loadPersistentPrefixCacheEnabled(application: Application): Boolean = GenerationPreferences.loadPersistentPrefixCacheEnabled(application)
    private fun persistPersistentPrefixCacheEnabled(application: Application, enabled: Boolean) = GenerationPreferences.persistPersistentPrefixCacheEnabled(application, enabled)
    private fun apiUrl(host: String): String = localApiRuntimeOwner.apiUrl(host)
    private fun currentOpenApiAddress(): String = localApiRuntimeOwner.currentOpenApiAddress(detectLanIpAddress())
}

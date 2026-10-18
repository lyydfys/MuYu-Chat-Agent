package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.mca.LocalApiRuntimeOwner
import com.muyuchat.mca.ChatAppearanceCoordinator
import com.muyuchat.mca.ChatImageWorkflowCoordinator
import com.muyuchat.mca.ChatWorkflowCoordinator
import com.muyuchat.mca.PendingAssistantImageToolTurn
import com.muyuchat.mca.LocalImageWorkerClient
import com.muyuchat.mca.LocalImageGenerationCoordinator
import com.muyuchat.mca.ContextCompressionRequestStore
import com.muyuchat.mca.ImageLibraryBackup
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.mca.IsolatedLocalChatRunners
import com.muyuchat.mca.canonicalImageAssetDirectory
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.ChatImageCommitJournal
import com.muyuchat.mca.ChatImageCommitRecoveryPort
import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ChatImageRetryPort
import com.muyuchat.mca.ImageAssetCreationPort
import com.muyuchat.mca.ImageAssetDeletionPort
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ImageUpscaleOwner
import com.muyuchat.mca.ImageUpscalePort
import com.muyuchat.mca.LocalImageUpscalerPort
import com.muyuchat.mca.ImagePromptTranslationPort
import com.muyuchat.mca.ImagePromptTranslationPreviewPort
import com.muyuchat.mca.AssistantImageToolTurnPort
import com.muyuchat.mca.ChatImageRequestApprovalPort
import com.muyuchat.mca.AppUpdatePort
import com.muyuchat.mca.SelectChatSessionPort
import com.muyuchat.mca.ChatBackend
import com.muyuchat.mca.OpenAiCompatibleChatProvider
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.ChatImagePromptBridge
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.ImageGenerationJobSpec
import com.muyuchat.mca.MainUiState
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.CloudImageProvider
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.ContentImportPreviewStore
import kotlinx.coroutines.Job
import java.io.File
import com.muyuchat.mca.upsertSessionFor
import com.muyuchat.mca.bindingValue
import com.muyuchat.mca.currentChatModelId
import java.util.UUID
import com.muyuchat.mca.ChatTerminalSettlementPort
import com.muyuchat.mca.ChatStreamAccumulator
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.LocalImagePreparedLora
import com.muyuchat.mca.LocalImagePromptExecution
import com.muyuchat.mca.LocalApiImageGenerationOwnership
import com.muyuchat.mca.QnnImageEvidenceEntryPoint
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImageResult
import com.muyuchat.mca.LocalImageLoraRecord
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.LocalApiTuningJobCreateRequest
import com.muyuchat.api.local.LocalApiIdempotencyContext
import com.muyuchat.mca.ConversationMutationRollbackState
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.ChatSessionPersistenceState
import com.muyuchat.mca.LocalImagePromptTokenizerDescriptor
import com.muyuchat.mca.ImagePromptTokenMeasurementHost
import com.muyuchat.mca.ImagePromptTokenMeasurementCache
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.mca.WebSearchProvider
import com.muyuchat.mca.WebSearchWorkflowCoordinator
import com.muyuchat.mca.LocalImageAdapterPort
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.mca.AssistantStore
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.mca.ManagedRuntimeReadinessRefreshGate
import com.muyuchat.mca.RuntimeAdmissionState
import com.muyuchat.mca.GenerationPreferences
import com.muyuchat.mca.BackgroundImageStore
import com.muyuchat.mca.ChatImageToolAuthorizationStore
import com.muyuchat.mca.ChatSessionStore
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.GlobalChatAppearanceStore
import com.muyuchat.mca.KnowledgeBaseRecord
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.LocalImageLoraStore
import com.muyuchat.mca.LocalImageModelStore
import com.muyuchat.mca.LocalImageUpscalerStore
import com.muyuchat.mca.ModelGenerationProfileStore
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.mca.OfflinePromptTranslationBundleDownloader
import com.muyuchat.mca.OfflinePromptTranslationBundleInstaller
import com.muyuchat.mca.TextualInversionStore
import com.muyuchat.mca.WebSearchDiagnosticStore
import com.muyuchat.mca.WebSearchStore
import com.muyuchat.mca.WorldBookStore
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.mca.LocalApiTuningControlPort
import com.muyuchat.mca.TuningJobEntity
import com.muyuchat.mca.PendingRuntimeProfileTransaction
import com.muyuchat.api.local.LocalApiControlResult
import org.json.JSONObject

/**
 * R2：组合根骨架（方案 §9 / §14.4）。
 *
 * 职责边界：只装配“仅依赖 Application（或零依赖）”的稳定叶子对象；不承载状态机、数据库逻辑或 UI 分支。
 * ViewModel/UI/Worker/Local API 通过接口或本容器读取装配结果，而不是各自 new 出平行实例。
 *
 * 逐域扩张（构造参数 1:1 保留，零行为变化）：
 * - S12：runtime/model 配置存储、会话与授权存储、世界书/知识库/助手存储（7 个）；
 * - S13：外观/背景/云 API/离线翻译/webSearch/图像模型资产族（+10 个）；
 * - 后续切片：带 port/scope 的 owner（如 AppUpdateOwner）待其 port 依赖先行解耦后再迁入。
 */
private const val IMAGE_UPSCALER_PREFERENCES = "image_upscaler_product_selection_v1"

internal class McaAppContainer(internal val application: Application) {

    /**
     * E06: the single chat execution facade for this process, built once on first use. The
     * supervisor is the shared admission/terminal owner for every chat channel; the execution body
     * is the engine itself (see chatExecutionBodyFor).
     */
    private val chatExecutionFacadeRef =
        java.util.concurrent.atomic.AtomicReference<com.muyuchat.mca.facade.ChatExecutionFacade?>(null)

    fun chatExecutionFacadeFor(engine: McaInferenceService): com.muyuchat.mca.facade.ChatExecutionFacade =
        chatExecutionFacadeRef.updateAndGet { existing ->
            existing ?: com.muyuchat.mca.facade.ChatExecutionFacade(
                supervisor = sharedRuntimeSupervisor,
                executionBody = com.muyuchat.mca.chatExecutionBodyFor(engine),
            )
        }!!

    val runtimeProfileStore = ModelRuntimeProfileStore(application)

    val modelGenerationProfileStore = ModelGenerationProfileStore(application)

    val chatSessionStore = ChatSessionStore(application)

    val chatImageToolAuthorizationStore = ChatImageToolAuthorizationStore(application)

    val worldBookStore = WorldBookStore(application)

    val knowledgeBaseStore = KnowledgeBaseStore(application)

    val assistantStore = AssistantStore(application)

    val globalChatAppearanceStore = GlobalChatAppearanceStore(application)

    val backgroundImageStore = BackgroundImageStore(application)

    val cloudApiStore = CloudApiStore(application)

    val cloudChatProvider = OpenAiCompatibleChatProvider()

    val offlineTranslationInstaller = OfflinePromptTranslationBundleInstaller(application)

    val offlineTranslationDownloader = OfflinePromptTranslationBundleDownloader(application)

    val webSearchStore = WebSearchStore(application)

    val webSearchDiagnosticStore = WebSearchDiagnosticStore(application)

    val localImageModelStore = LocalImageModelStore(application)

    val localImageLoraStore = LocalImageLoraStore(application)

    // R3/C3-S1（插队裁定 2/3）：自 VM 迁出的共享锁实例与 webSearch provider。
    val localImageLoraLifecycleLock = Any()

    val localImageTextualInversionLifecycleLock = Any()

    val webSearchProvider = WebSearchProvider()

    // R3/C3-S3：随端口迁入的 token 测量缓存（仅该端口使用，生命周期与容器一致）。
    val imagePromptTokenMeasurementCache = ImagePromptTokenMeasurementCache()

    val imagePromptTokenizerDescriptors = java.util.concurrent.ConcurrentHashMap<String, LocalImagePromptTokenizerDescriptor>()

    val localImageTextualInversionStore = TextualInversionStore(application)

    val localImageUpscalerStore = LocalImageUpscalerStore(application)

    // R3/C2（插队裁定 2/3）：自 MainViewModel 迁入的 application-only 装配与运行期准入态 holder。
    val deviceProfileReader = DeviceProfileReader(application)

    val modelStore = ModelStoreRepository(application)

    val modelScopeClient = com.muyuchat.core.download.ModelScopeClient()

    val benchmarkHistoryLogger = BenchmarkHistoryLogger(application)

    val readinessRefreshGate = ManagedRuntimeReadinessRefreshGate()

    val advisor = AgentAdvisor()

    val imageAssetDirectory = canonicalImageAssetDirectory(application.filesDir)

    // R3/C3-S12：提交日志自 VM 迁出；lazy 模式与构造参数逐字保留（构造含 mkdirs/require 副作用，
    // 不得因迁移改为急切求值）。
    val chatImageCommitJournal: ChatImageCommitJournal by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatImageCommitJournal(
            ownedRoot = File(application.filesDir, "pending-chat-image-commits"),
            assetDirectory = imageAssetDirectory
        )
    }

    // R3/C3-S7：图像/内容/搜索家族小对象批（自 VM 迁出）。
    val imageLibraryBackup = ImageLibraryBackup(application, chatSessionStore)

    val manualContextCompressionRequests = ContextCompressionRequestStore()

    val localImageGenerationCoordinator = LocalImageGenerationCoordinator()

    val localImageGenerationCoordinatorObservationLock = Any()

    val imageUpscalerPreferences = application.getSharedPreferences(
        IMAGE_UPSCALER_PREFERENCES,
        Context.MODE_PRIVATE
    )

    val imageLibraryMutationMutex = Mutex()

    val imageAssetWriteMutex = Mutex()

    val cloudImageProvider = CloudImageProvider()

    /**
     * E07: one process-wide runtime supervisor shared by the chat facade and the image execution
     * gate, so all channels of this process admit against one identity/terminal authority.
     */
    private val runtimeSupervisorRef =
        java.util.concurrent.atomic.AtomicReference<com.muyuchat.core.engine.runtime.RuntimeSupervisor?>(null)

    val sharedRuntimeSupervisor: com.muyuchat.core.engine.runtime.RuntimeSupervisor
        get() = runtimeSupervisorRef.updateAndGet { existing ->
            existing ?: com.muyuchat.core.engine.runtime.RuntimeSupervisor()
        }!!

    private val imageExecutionGateRef =
        java.util.concurrent.atomic.AtomicReference<com.muyuchat.mca.facade.ImageExecutionGate?>(null)

    /** E07: the single image execution gate every worker generate/upscale passes through. */
    val imageExecutionGate: com.muyuchat.mca.facade.ImageExecutionGate
        get() = imageExecutionGateRef.updateAndGet { existing ->
            existing ?: com.muyuchat.mca.facade.ImageExecutionOwner(sharedRuntimeSupervisor)
        }!!

    val localImageWorkerClient = LocalImageWorkerClient(application, imageExecutionGate)

    /** R3/C2（插队裁定 2）：调参 owner 工厂——VM 只传它自有的运行时对象（store/scope/engine），
     *  其余依赖由容器装配。 */
    fun createTuningOwner(store: UiStateStore, scope: CoroutineScope, engine: McaInferenceService): TuningOwner =
        createTuningOwnerImpl(store, scope, engine)

    /** R3/C3-S1：三个小端口的具名适配器工厂（真实依赖注入）。 */
    fun createLocalImageAdapterPort(store: UiStateStore): LocalImageAdapterPort =
        createLocalImageAdapterPortImpl(store)

    fun createWebSearchWorkflowCoordinator(
        store: UiStateStore,
        terminalPort: ChatTerminalSettlementPort,
        scope: CoroutineScope
    ): WebSearchWorkflowCoordinator =
        createWebSearchWorkflowCoordinatorImpl(store, terminalPort, scope)

    fun createVisionAttachmentPort(store: UiStateStore, engine: McaInferenceService): VisionAttachmentPort =
        createVisionAttachmentPortImpl(store, engine)

    /** R3/C3-S2：Local API payload 端口的具名适配器工厂。 */
    fun createLocalApiPayloadPort(store: UiStateStore, engine: McaInferenceService, tuningOwner: TuningOwner): LocalApiPayloadPort =
        createLocalApiPayloadPortImpl(store, engine, tuningOwner)

    /** R3/C3-S3：token 测量 host 的具名适配器工厂。 */
    fun createImagePromptTokenMeasurementHost(store: UiStateStore): ImagePromptTokenMeasurementHost =
        createImagePromptTokenMeasurementHostImpl(store)

    // R3/C3-S4：会话持久化状态（两段式初始化——初值来自 VM 首段装配的 initialChatSessions）。
    lateinit var chatSessionPersistenceState: ChatSessionPersistenceState
        private set

    fun initializeChatSessionPersistenceState(initialDurableSessions: List<ChatSessionRecord>): ChatSessionPersistenceState =
        ChatSessionPersistenceState(initialDurableSessions).also { chatSessionPersistenceState = it }


    // R3/C3-S5：chatRuns 与流缓冲累加器（捕获面=真实对象：store/scope/chatRuns；两段式初始化等待 store）。
    val chatRuns = ChatRunOwnership()

    lateinit var chatStreamAccumulator: ChatStreamAccumulator
        private set

    fun initializeChatStreamAccumulator(store: UiStateStore, scope: CoroutineScope): ChatStreamAccumulator =
        ChatStreamAccumulator(
            scope = scope,
            currentRunId = { chatRuns.currentRunId() },
            activeSessionId = { store.current.activeChatSessionId },
            hasAssistantMessage = { store.current.messages.any { it.role == Role.ASSISTANT } },
            commit = { batch, isStillCurrent ->
            // 在进入 CAS reducer 之前取一次时间：reducer 可能因竞争重试，
            // 但同一次 commit 必须使用同一个 occurredAt（硬锁 §6 纯 reducer）。
            val occurredAt = System.currentTimeMillis()
            store.update { state ->
                if (!isStillCurrent()) {
                    state
                } else {
                    val updated = state.messages.toMutableList()
                    val index = updated.indexOfLast { it.role == Role.ASSISTANT }
                    if (index >= 0) {
                        val current = updated[index]
                        updated[index] = current.copy(
                            content = current.content + batch.content,
                            reasoningContent = current.reasoningContent + batch.reasoning,
                            reasoningDurationMs = maxOf(
                                current.reasoningDurationMs,
                                batch.reasoningDurationMs
                            )
                        )
                    }
                    val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
                    state.copy(
                        messages = updated,
                        activeChatSessionId = sessionId,
                        chatSessions = state.chatSessions.upsertSessionFor(
                            state = state,
                            sessionId = sessionId,
                            messages = updated,
                            assistantId = state.selectedAssistantId,
                            modelMode = state.selectedChatBackend.bindingValue(),
                            modelId = state.currentChatModelId(),
                            occurredAt = occurredAt
                        )
                    )
                }
            }
            }
        ).also { chatStreamAccumulator = it }

    /** R3/C3-S5：terminalSettlement 端口具名适配器工厂。 */
    fun createChatTerminalSettlementPort(store: UiStateStore): ChatTerminalSettlementPort =
        createChatTerminalSettlementPortImpl(store)

    /** R3/C3-S12：提交恢复窄端口的具名适配器工厂（journal 以 provider 传入，保持原 lazy 时机）。 */
    fun createChatImageCommitRecoveryPort(
        journalProvider: () -> ChatImageCommitJournal,
        sessionStore: ChatSessionStore
    ): ChatImageCommitRecoveryPort =
        createChatImageCommitRecoveryPortImpl(journalProvider, sessionStore)

    /** R3/C3-S13：启动对账 owner 工厂（真实依赖全对象化；lazy async 与 start/await 契约不变）。 */
    fun createImageLibraryStartupReconciliation(
        store: UiStateStore,
        scope: CoroutineScope,
        recoveryPort: ChatImageCommitRecoveryPort,
        initialKnowledgeBases: List<KnowledgeBaseRecord>
    ): ImageLibraryStartupReconciliation =
        createImageLibraryStartupReconciliationImpl(store, scope, recoveryPort, initialKnowledgeBases)

    /** R3/C3-S15：资产生成/放大端口工厂（owner 与 S13 对账 owner 均真实对象注入）。 */
    fun createImageAssetCreationPort(
        store: UiStateStore,
        application: Application,
        imageGenerationOwner: ImageGenerationOwner
    ): ImageAssetCreationPort =
        createImageAssetCreationPortImpl(store, application, imageGenerationOwner)

    fun createImageUpscalePort(
        store: UiStateStore,
        application: Application,
        assetCreationPort: ImageAssetCreationPort,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageUpscalePort =
        createImageUpscalePortImpl(store, application, assetCreationPort, startupReconciliation)

    fun createImageAssetDeletionPort(
        store: UiStateStore,
        scope: CoroutineScope,
        imageUpscaleOwner: ImageUpscaleOwner,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageAssetDeletionPort =
        createImageAssetDeletionPortImpl(store, scope, imageUpscaleOwner, startupReconciliation)

    /** R3/C3-S27/S31：图片提示词翻译端口工厂（verifiedBundle 直调 operations，删除过渡 lambda）。 */
    fun createImagePromptTranslationPort(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPort =
        createImagePromptTranslationPortImpl(store, serviceProvider)

    /** R3/C2-S3：生图准入/执行唯一 owner 工厂（装配具名 ImageGenerationHostAdapter，真实依赖注入）。 */
    fun createImageGenerationOwner(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        imageLibraryStartupReconciliationProvider: () -> ImageLibraryStartupReconciliation,
        imageLibraryCommitPortProvider: () -> ImageLibraryCommitPort,
        chatImageRequestPortProvider: () -> ChatImageRequestPort,
        imagePromptTranslationPortProvider: () -> ImagePromptTranslationPort,
        completeAssistantImageToolTurnForJob: (String) -> Unit
    ): ImageGenerationOwner =
        createImageGenerationOwnerImpl(store, scope, engine, imageLibraryStartupReconciliationProvider, imageLibraryCommitPortProvider, chatImageRequestPortProvider, imagePromptTranslationPortProvider, completeAssistantImageToolTurnForJob)

    /** R3-S32：图片放大模型目录端口工厂（store / preferences / ownerLockProvider 真实依赖注入）。 */
    fun createLocalImageUpscalerPort(
        store: UiStateStore,
        imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
    ): LocalImageUpscalerPort =
        createLocalImageUpscalerPortImpl(store, imageUpscaleOwnerProvider)

    /** R3-S33：提示词翻译预览端口工厂（直调 operations，service 延迟注入）。 */
    fun createImagePromptTranslationPreviewPort(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPreviewPort =
        createImagePromptTranslationPreviewPortImpl(store, serviceProvider)

    /** R3-S34：助手生图工具轮次端口工厂（真实依赖注入，聊天操作由 lambda 委托）。 */
    fun createAssistantImageToolTurnPort(
        store: UiStateStore,
        chatImageNegativePromptSupported: (MainUiState) -> Boolean,
        prepareAssistantImagePrompt: suspend (
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>
        ) -> ChatImagePromptBridge.Result,
        prepareAssistantImageNegativePrompt: suspend (
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>,
            preserveAuthoredNegative: Boolean
        ) -> ChatImagePromptBridge.Result,
        setPendingAssistantToolFailure: (String) -> Unit,
        buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
        attachAssistantImageRequestToTimeline: (String, ChatGeneratedImageRequest) -> Unit,
        approveChatImageRequest: (String) -> Unit
    ): AssistantImageToolTurnPort =
        createAssistantImageToolTurnPortImpl(store, chatImageNegativePromptSupported, prepareAssistantImagePrompt, prepareAssistantImageNegativePrompt, setPendingAssistantToolFailure, buildChatImageRetrySnapshot, attachAssistantImageRequestToTimeline, approveChatImageRequest)

    /** R3-S35：聊天生图审批端口工厂（A 族第 4 端口）。 */
    fun createChatImageRequestApprovalPort(
        store: UiStateStore,
        imageGenerationOwnerProvider: () -> ImageGenerationOwner,
        chatImageRequestPortProvider: () -> ChatImageRequestPort,
        buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
        continueAssistantImageToolTurn: (String, String) -> Unit
    ): ChatImageRequestApprovalPort =
        createChatImageRequestApprovalPortImpl(store, imageGenerationOwnerProvider, chatImageRequestPortProvider, buildChatImageRetrySnapshot, continueAssistantImageToolTurn)

    /** R3-S37：应用更新端口工厂（B 族第 2 端口）。 */
    fun createAppUpdatePort(storeProvider: () -> UiStateStore): AppUpdatePort =
        createAppUpdatePortImpl(storeProvider)

    /** R3-S39：选择聊天会话端口工厂（B 族第 4 端口）。 */
    fun createSelectChatSessionPort(
        store: UiStateStore,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        hasActiveAssistantImageExecution: (String?) -> Boolean,
        invalidatePendingChatImagePreparation: () -> Unit,
        cancelActiveWebSearch: () -> Unit,
        bumpContentImportSelectionRevision: () -> Unit,
        loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
        markLocalConversationContextInvalid: () -> Unit,
        continueAssistantImageTurn: (String) -> Unit
    ): SelectChatSessionPort =
        createSelectChatSessionPortImpl(store, rejectWhileConversationMutationInProgress, hasActiveAssistantImageExecution, invalidatePendingChatImagePreparation, cancelActiveWebSearch, bumpContentImportSelectionRevision, loadModelGenerationParams, markLocalConversationContextInvalid, continueAssistantImageTurn)

    fun createLocalApiTuningControlPort(
        store: UiStateStore,
        tuningOwner: TuningOwner,
        localApiModelMatchesRef: (String, MainUiState, ModelManifest) -> Boolean,
        localApiPerformancePreferenceRef: (String?) -> UserPreference?,
        localApiTuningRejectedRef: (Int, String, String, JSONObject) -> LocalApiControlResult.Rejected,
        localApiTuningConflictRef: (String, String?, String) -> LocalApiControlResult.Rejected,
        localApiExclusiveLifecycleConflictRef: (String, String?) -> LocalApiControlResult.Rejected?,
        assignActiveTuningJobIdRef: (String?) -> Unit,
        tuningJobPublicJsonRef: (TuningJobEntity) -> JSONObject,
        setTuningCancelRequestedRef: (Boolean) -> Unit,
        setTuningPauseRequestedRef: (Boolean) -> Unit,
        launchTuningApplyRef: (PendingRuntimeProfileTransaction) -> Unit,
        launchTuningRollbackRef: (ModelRuntimeIdentity, ModelManifest) -> Unit,
    ): LocalApiTuningControlPort =
        createLocalApiTuningControlPortImpl(
            store,
            tuningOwner,
            localApiModelMatchesRef,
            localApiPerformancePreferenceRef,
            localApiTuningRejectedRef,
            localApiTuningConflictRef,
            localApiExclusiveLifecycleConflictRef,
            assignActiveTuningJobIdRef,
            tuningJobPublicJsonRef,
            setTuningCancelRequestedRef,
            setTuningPauseRequestedRef,
            launchTuningApplyRef,
            launchTuningRollbackRef,
        )

    fun createLocalApiImageGenerationPort(
        store: UiStateStore,
        getActiveLocalApiImageModelIdRef: () -> String?,
        setActiveLocalApiImageModelIdRef: (String?) -> Unit,
        tryAcquireObservedImageGenerationLeaseRef: (String) -> LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLeaseRef: (LocalImageGenerationCoordinator.Lease) -> Boolean,
        registerLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorkerRef: (LocalApiImageGenerationOwnership) -> Boolean,
        resolveCurrentLocalImageLorasRef1: (List<Pair<String, Double>>) -> List<LocalImagePreparedLora>,
        resolveCurrentLocalImageLorasRef2: (List<Pair<String, Double>>, List<LocalImageLoraRecord>) -> List<LocalImagePreparedLora>,
        resolveCurrentLocalImageTextualInversionIdsRef: (List<String>) -> List<String>,
        prepareLocalImagePromptExecutionRef: suspend (LocalImageModelRecord, String, LocalImageGenerationOptions, LocalImagePromptExecution?) -> LocalImagePromptExecution,
        prepareLocalImageRuntimeMemoryRef: suspend () -> EngineLifecycleLease?,
        executionWithQnnVerificationEvidenceRef: (LocalImageModelRecord, LocalImageResult, LocalImageGenerationOptions, String, String, QnnImageEvidenceEntryPoint) -> String,
    ): LocalApiImageGenerationPort =
        createLocalApiImageGenerationPortImpl(
            store,
            getActiveLocalApiImageModelIdRef,
            setActiveLocalApiImageModelIdRef,
            tryAcquireObservedImageGenerationLeaseRef,
            releaseObservedImageGenerationLeaseRef,
            registerLocalApiImageGenerationOwnershipRef,
            unregisterLocalApiImageGenerationOwnershipRef,
            cancelOwnedLocalApiImageWorkerRef,
            resolveCurrentLocalImageLorasRef1,
            resolveCurrentLocalImageLorasRef2,
            resolveCurrentLocalImageTextualInversionIdsRef,
            prepareLocalImagePromptExecutionRef,
            prepareLocalImageRuntimeMemoryRef,
            executionWithQnnVerificationEvidenceRef,
        )

    fun createLocalApiControlPlane(
        busyStateProvider: () -> LocalApiBusyState,
        preflightProvider: (LocalApiPreflightRequest) -> LocalApiPreflightResult,
        profileJsonProvider: () -> String,
        tuningJsonProvider: () -> String,
        tuningJobProvider: (String?) -> LocalApiControlResult,
        createTuningJobDelegate: (LocalApiTuningJobCreateRequest, String) -> LocalApiControlResult,
        pauseTuningJobDelegate: (String, String) -> LocalApiControlResult,
        resumeTuningJobDelegate: (String, String) -> LocalApiControlResult,
        cancelTuningJobDelegate: (String, String) -> LocalApiControlResult,
        applyTuningJobDelegate: (String, String) -> LocalApiControlResult,
        rollbackTuningDelegate: (String) -> LocalApiControlResult,
        runDurableLocalApiControl: (LocalApiIdempotencyContext, () -> LocalApiControlResult) -> LocalApiControlResult,
    ): LocalApiControlPlane =
        createLocalApiControlPlaneImpl(busyStateProvider, preflightProvider, profileJsonProvider, tuningJsonProvider, tuningJobProvider, createTuningJobDelegate, pauseTuningJobDelegate, resumeTuningJobDelegate, cancelTuningJobDelegate, applyTuningJobDelegate, rollbackTuningDelegate, runDurableLocalApiControl)

    val isolatedLocalChatRunners: IsolatedLocalChatRunners by lazy {
        IsolatedLocalChatRunners(application)
    }

    fun createInferenceService(
        runners: Map<LocalChatRuntime, LocalChatRunner> = isolatedLocalChatRunners.runners,
        installationScopeId: String = runBlocking(Dispatchers.IO) { runtimeProfileStore.installationScopeId() }
    ): McaInferenceService =
        createInferenceServiceImpl(runners, installationScopeId)

    fun createLocalApiRuntimeOwner(): LocalApiRuntimeOwner =
        createLocalApiRuntimeOwnerImpl()

    fun createChatAppearanceCoordinator(
        scope: CoroutineScope,
        uiStateStore: UiStateStore,
        showStatus: (String?) -> Unit
    ): ChatAppearanceCoordinator =
        createChatAppearanceCoordinatorImpl(scope, uiStateStore, showStatus)

    val contentImportPreviewStore = ContentImportPreviewStore(application)

    fun createChatImageWorkflowCoordinator(
        scope: CoroutineScope,
        store: UiStateStore,
        imageAssetWriteMutex: Mutex,
        imageLibraryMutationMutex: Mutex,
        imageLibraryBackup: ImageLibraryBackup,
        chatSessionStore: ChatSessionStore,
        imageGenerationOwner: ImageGenerationOwner,
        imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
        chatImageRetryPort: ChatImageRetryPort,
        showStatus: (String?) -> Unit,
        tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
        offlinePromptTranslationService: () -> OfflinePromptTranslationService,
        runtimeProfileStore: ModelRuntimeProfileStore,
        engine: McaInferenceService,
        selectCloudImageModel: (String) -> Unit,
        registerLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorker: (LocalApiImageGenerationOwnership) -> Boolean
    ): ChatImageWorkflowCoordinator =
        createChatImageWorkflowCoordinatorImpl(
            scope,
            store,
            imageAssetWriteMutex,
            imageLibraryMutationMutex,
            imageLibraryBackup,
            chatSessionStore,
            imageGenerationOwner,
            imageLibraryStartupReconciliation,
            chatImageRetryPort,
            showStatus,
            tryAcquireObservedImageGenerationLease,
            releaseObservedImageGenerationLease,
            offlinePromptTranslationService,
            runtimeProfileStore,
            engine,
            selectCloudImageModel,
            registerLocalApiImageGenerationOwnership,
            unregisterLocalApiImageGenerationOwnership,
            cancelOwnedLocalApiImageWorker,
        )

    fun createChatWorkflowCoordinator(
        store: UiStateStore,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        showStatus: (String) -> Unit,
        isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean,
        pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
        persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, Boolean) -> kotlinx.coroutines.Job?
    ): ChatWorkflowCoordinator =
        createChatWorkflowCoordinatorImpl(store, rejectWhileConversationMutationInProgress, showStatus, isChatImageRequestActive, pendingAssistantImageToolTurns, persistConversationMutation)

    val runtimeAdmission = RuntimeAdmissionState(
        GenerationPreferences.loadRuntimeUserOverrideFields(application).toMutableSet()
    )
}

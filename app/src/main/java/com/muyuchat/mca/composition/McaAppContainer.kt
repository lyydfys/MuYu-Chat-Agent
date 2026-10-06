package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.mca.ChatSessionPersistencePortAdapter
import com.muyuchat.mca.ChatSessionPersistencePort
import com.muyuchat.mca.LocalImageWorkerClient
import com.muyuchat.mca.LocalImageGenerationCoordinator
import com.muyuchat.mca.ContextCompressionRequestStore
import com.muyuchat.mca.ImageLibraryBackup
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import com.muyuchat.mca.canonicalImageAssetDirectory
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.ChatImageCommitJournal
import com.muyuchat.mca.ChatImageCommitRecoveryPort
import com.muyuchat.mca.ChatImageCommitRecoveryPortAdapter
import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ChatImageRequestPortAdapter
import com.muyuchat.mca.ChatImageRetryPort
import com.muyuchat.mca.ChatImageRetryPortAdapter
import com.muyuchat.mca.SendChatImageRequestPort
import com.muyuchat.mca.SendChatImageRequestPortAdapter
import com.muyuchat.mca.ImageAssetCreationPort
import com.muyuchat.mca.ImageAssetCreationPortAdapter
import com.muyuchat.mca.ImageAssetDeletionPort
import com.muyuchat.mca.ImageAssetDeletionPortAdapter
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ImageLibraryCommitPortAdapter
import com.muyuchat.mca.ImageUpscaleOwner
import com.muyuchat.mca.ImageUpscalePort
import com.muyuchat.mca.ImageUpscalePortAdapter
import com.muyuchat.mca.LocalImageUpscalerPort
import com.muyuchat.mca.LocalImageUpscalerPortAdapter
import com.muyuchat.mca.CreateLocalGeneratedImageAssetPort
import com.muyuchat.mca.CreateLocalGeneratedImageAssetPortAdapter
import com.muyuchat.mca.LocalGeneratedImageWorkerGatewayAdapter
import com.muyuchat.mca.ImagePromptTranslationPort
import com.muyuchat.mca.ImagePromptTranslationPortAdapter
import com.muyuchat.mca.ImagePromptTranslationPreviewPort
import com.muyuchat.mca.ImagePromptTranslationPreviewPortAdapter
import com.muyuchat.mca.AssistantImageToolTurnPort
import com.muyuchat.mca.AssistantImageToolTurnPortAdapter
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.ChatImagePromptBridge
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.ImageGenerationJobSpec
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.VerifiedOfflinePromptTranslationBundle
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageGenerationHostAdapter
import com.muyuchat.mca.CloudImageProvider
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.KnowledgeBaseRecord
import java.io.File
import com.muyuchat.mca.ChatTerminalSettlementPortAdapter
import com.muyuchat.mca.upsertSessionFor
import com.muyuchat.mca.bindingValue
import com.muyuchat.mca.currentChatModelId
import java.util.UUID
import com.muyuchat.mca.ChatTerminalSettlementPort
import com.muyuchat.mca.ChatStreamAccumulator
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.ChatSessionPersistenceState
import com.muyuchat.mca.LocalImagePromptTokenizerDescriptor
import com.muyuchat.mca.ImagePromptTokenMeasurementHostAdapter
import com.muyuchat.mca.ImagePromptTokenMeasurementHost
import com.muyuchat.mca.ImagePromptTokenMeasurementCache
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.LocalApiPayloadPortAdapter
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.mca.WebSearchProvider
import com.muyuchat.mca.LocalImageAdapterPortAdapter
import com.muyuchat.mca.WebSearchConfigPortAdapter
import com.muyuchat.mca.VisionAttachmentPortAdapter
import com.muyuchat.mca.WebSearchConfigPort
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

internal class McaAppContainer(private val application: Application) {

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

    val localImageWorkerClient = LocalImageWorkerClient(application)

    /** R3/C2（插队裁定 2）：调参 owner 工厂——VM 只传它自有的运行时对象（store/scope/engine），
     *  其余依赖由容器装配。 */
    fun createTuningOwner(store: UiStateStore, scope: CoroutineScope, engine: McaInferenceService): TuningOwner =
        TuningOwner(
            store = store,
            scope = scope,
            engine = engine,
            runtimeProfileStore = runtimeProfileStore,
            modelStore = modelStore,
            benchmarkHistoryLogger = benchmarkHistoryLogger,
            readinessGate = readinessRefreshGate,
            deviceProfileReader = deviceProfileReader,
            application = application,
            advisor = advisor,
            admission = runtimeAdmission
        )

    /** R3/C3-S1：三个小端口的具名适配器工厂（真实依赖注入）。 */
    fun createLocalImageAdapterPort(store: UiStateStore): LocalImageAdapterPort =
        LocalImageAdapterPortAdapter(
            store = store,
            loraStoreRef = localImageLoraStore,
            textualInversionStoreRef = localImageTextualInversionStore,
            loraLifecycleLock = localImageLoraLifecycleLock,
            textualInversionLifecycleLock = localImageTextualInversionLifecycleLock
        )

    fun createWebSearchConfigPort(store: UiStateStore): WebSearchConfigPort =
        WebSearchConfigPortAdapter(
            store = store,
            appContext = application,
            webSearchStoreRef = webSearchStore,
            diagnosticStoreRef = webSearchDiagnosticStore,
            providerRef = webSearchProvider
        )

    fun createVisionAttachmentPort(store: UiStateStore, engine: McaInferenceService): VisionAttachmentPort =
        VisionAttachmentPortAdapter(
            store = store,
            appContext = application,
            engineRef = engine,
            admissionRef = runtimeAdmission
        )

    /** R3/C3-S2：Local API payload 端口的具名适配器工厂。 */
    fun createLocalApiPayloadPort(store: UiStateStore, engine: McaInferenceService, tuningOwner: TuningOwner): LocalApiPayloadPort =
        LocalApiPayloadPortAdapter(
            store = store,
            engineRef = engine,
            runtimeProfileStoreRef = runtimeProfileStore,
            tuningRef = tuningOwner,
            admissionRef = runtimeAdmission
        )

    /** R3/C3-S3：token 测量 host 的具名适配器工厂。 */
    fun createImagePromptTokenMeasurementHost(store: UiStateStore): ImagePromptTokenMeasurementHost =
        ImagePromptTokenMeasurementHostAdapter(
            store = store,
            measurementCacheRef = imagePromptTokenMeasurementCache,
            tokenizerDescriptorsRef = imagePromptTokenizerDescriptors
        )

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
                            modelId = state.currentChatModelId()
                        )
                    )
                }
            }
            }
        ).also { chatStreamAccumulator = it }

    /** R3/C3-S5：terminalSettlement 端口具名适配器工厂。 */
    fun createChatTerminalSettlementPort(store: UiStateStore): ChatTerminalSettlementPort =
        ChatTerminalSettlementPortAdapter(
            store = store,
            chatRunsRef = chatRuns,
            diagnosticStoreRef = webSearchDiagnosticStore,
            accumulatorRef = chatStreamAccumulator
        )

    /** R3/C3-S11：会话持久化端口具名适配器工厂。 */
    fun createChatSessionPersistencePort(
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
    fun createChatImageRequestPort(
        store: UiStateStore,
        persistencePort: ChatSessionPersistencePort
    ): ChatImageRequestPort =
        ChatImageRequestPortAdapter(store = store, persistencePort = persistencePort)

    /** R3/C3-S12：提交恢复窄端口的具名适配器工厂（journal 以 provider 传入，保持原 lazy 时机）。 */
    fun createChatImageCommitRecoveryPort(
        journalProvider: () -> ChatImageCommitJournal,
        sessionStore: ChatSessionStore
    ): ChatImageCommitRecoveryPort =
        ChatImageCommitRecoveryPortAdapter(journalProvider = journalProvider, sessionStoreRef = sessionStore)

    /** R3/C3-S13：启动对账 owner 工厂（真实依赖全对象化；lazy async 与 start/await 契约不变）。 */
    fun createImageLibraryStartupReconciliation(
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
    fun createSendChatImageRequestPort(
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
    fun createChatImageRetryPort(
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

    /** R3/C3-S15：资产生成/放大端口工厂（owner 与 S13 对账 owner 均真实对象注入）。 */
    fun createImageAssetCreationPort(
        store: UiStateStore,
        application: Application,
        imageGenerationOwner: ImageGenerationOwner
    ): ImageAssetCreationPort =
        ImageAssetCreationPortAdapter(
            store = store,
            application = application,
            admission = runtimeAdmission,
            imageGenerationOwner = imageGenerationOwner,
            assetWriteMutex = imageAssetWriteMutex,
            assetDirectory = imageAssetDirectory,
            localImageModelStoreRef = localImageModelStore
        )

    fun createImageUpscalePort(
        store: UiStateStore,
        application: Application,
        assetCreationPort: ImageAssetCreationPort,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageUpscalePort =
        ImageUpscalePortAdapter(
            store = store,
            application = application,
            coordinator = localImageGenerationCoordinator,
            observationLock = localImageGenerationCoordinatorObservationLock,
            assetCreationPort = assetCreationPort,
            assetWriteMutex = imageAssetWriteMutex,
            startupReconciliation = startupReconciliation,
            workerClientRef = localImageWorkerClient,
            chatSessionStoreRef = chatSessionStore,
            upscalerStoreRef = localImageUpscalerStore,
            assetDirectory = imageAssetDirectory,
            mutationMutex = imageLibraryMutationMutex
        )

    /** R3/C3-S16：图片库提交/删除端口工厂。 */
    fun createImageLibraryCommitPort(
        store: UiStateStore,
        requestPort: ChatImageRequestPort
    ): ImageLibraryCommitPort =
        ImageLibraryCommitPortAdapter(
            store = store,
            persistenceState = chatSessionPersistenceState,
            chatSessionStoreRef = chatSessionStore,
            journalProvider = { chatImageCommitJournal },
            admissionRef = runtimeAdmission,
            requestPortRef = requestPort,
            imageLibraryMutationMutexRef = imageLibraryMutationMutex
        )

    fun createImageAssetDeletionPort(
        store: UiStateStore,
        scope: CoroutineScope,
        imageUpscaleOwner: ImageUpscaleOwner,
        startupReconciliation: ImageLibraryStartupReconciliation
    ): ImageAssetDeletionPort =
        ImageAssetDeletionPortAdapter(
            store = store,
            scopeRef = scope,
            admissionRef = runtimeAdmission,
            imageUpscaleOwnerRef = imageUpscaleOwner,
            chatSessionStoreRef = chatSessionStore,
            mutationMutex = imageLibraryMutationMutex,
            assetDirectory = imageAssetDirectory,
            startupReconciliation = startupReconciliation
        )

    /** R3/C3-S27/S31：图片提示词翻译端口工厂（verifiedBundle 直调 operations，删除过渡 lambda）。 */
    fun createImagePromptTranslationPort(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPort =
        ImagePromptTranslationPortAdapter(
            store = store,
            admission = runtimeAdmission,
            downloader = offlineTranslationDownloader,
            serviceProvider = serviceProvider
        )

    /** R3-S30：本地生图资产生成端口工厂（worker 代际经 holder；网关包真实客户端）。 */
    fun createCreateLocalGeneratedImageAssetPort(
        store: UiStateStore,
        application: Application
    ): CreateLocalGeneratedImageAssetPort =
        CreateLocalGeneratedImageAssetPortAdapter(
            store = store,
            admission = runtimeAdmission,
            gateway = LocalGeneratedImageWorkerGatewayAdapter(localImageWorkerClient),
            localImageModelStoreRef = localImageModelStore,
            readinessGate = readinessRefreshGate,
            assetWriteMutex = imageAssetWriteMutex,
            assetDirectory = imageAssetDirectory,
            application = application
        )

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
    ): ImageGenerationOwner = ImageGenerationOwner(
        host = ImageGenerationHostAdapter(
            store = store,
            scopeRef = scope,
            applicationRef = application,
            runtimeAdmission = runtimeAdmission,
            coordinator = localImageGenerationCoordinator,
            coordinatorObservationLock = localImageGenerationCoordinatorObservationLock,
            localImageLoraLifecycleLockRef = localImageLoraLifecycleLock,
            localImageTextualInversionLifecycleLockRef = localImageTextualInversionLifecycleLock,
            localImageLoraStoreRef = localImageLoraStore,
            localImageWorkerClientRef = localImageWorkerClient,
            imageAssetDirectoryRef = imageAssetDirectory,
            imageAssetWriteMutex = imageAssetWriteMutex,
            cloudImageProviderRef = cloudImageProvider,
            chatImageCommitJournalRef = chatImageCommitJournal,
            runtimeProfileStoreRef = runtimeProfileStore,
            engineRef = engine,
            createLocalGeneratedImageAssetPort = createCreateLocalGeneratedImageAssetPort(store, application),
            imageLibraryStartupReconciliationProvider = imageLibraryStartupReconciliationProvider,
            imageLibraryCommitPortProvider = imageLibraryCommitPortProvider,
            chatImageRequestPortProvider = chatImageRequestPortProvider,
            imagePromptTranslationPortProvider = imagePromptTranslationPortProvider,
            completeAssistantImageToolTurnForJobRef = completeAssistantImageToolTurnForJob
        )
    )

    /** R3-S32：图片放大模型目录端口工厂（store / preferences / ownerLockProvider 真实依赖注入）。 */
    fun createLocalImageUpscalerPort(
        store: UiStateStore,
        imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
    ): LocalImageUpscalerPort =
        LocalImageUpscalerPortAdapter(
            store = store,
            upscalerStoreRef = localImageUpscalerStore,
            preferences = imageUpscalerPreferences,
            imageUpscaleOwnerProvider = imageUpscaleOwnerProvider
        )

    /** R3-S33：提示词翻译预览端口工厂（直调 operations，service 延迟注入）。 */
    fun createImagePromptTranslationPreviewPort(
        store: UiStateStore,
        serviceProvider: () -> OfflinePromptTranslationService
    ): ImagePromptTranslationPreviewPort =
        ImagePromptTranslationPreviewPortAdapter(
            store = store,
            admission = runtimeAdmission,
            downloader = offlineTranslationDownloader,
            serviceProvider = serviceProvider
        )

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
        AssistantImageToolTurnPortAdapter(
            store = store,
            admission = runtimeAdmission,
            authorizationStore = chatImageToolAuthorizationStore,
            chatImageNegativePromptSupportedRef = chatImageNegativePromptSupported,
            prepareAssistantImagePromptRef = prepareAssistantImagePrompt,
            prepareAssistantImageNegativePromptRef = prepareAssistantImageNegativePrompt,
            setPendingAssistantToolFailureRef = setPendingAssistantToolFailure,
            buildChatImageRetrySnapshotRef = buildChatImageRetrySnapshot,
            attachAssistantImageRequestToTimelineRef = attachAssistantImageRequestToTimeline,
            approveChatImageRequestRef = approveChatImageRequest
        )

    val runtimeAdmission = RuntimeAdmissionState(
        GenerationPreferences.loadRuntimeUserOverrideFields(application).toMutableSet()
    )
}

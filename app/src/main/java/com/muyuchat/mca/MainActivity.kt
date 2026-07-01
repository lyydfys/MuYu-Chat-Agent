package com.muyuchat.mca

import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.feature.agent.AgentScreen
import com.muyuchat.feature.agent.AgentDecisionItem
import com.muyuchat.feature.agent.BenchmarkHistoryItem
import com.muyuchat.feature.agent.AgentUiState
import com.muyuchat.feature.agent.TuningTrialItem
import com.muyuchat.feature.chat.ChatScreen
import com.muyuchat.feature.chat.ChatHistoryItem
import com.muyuchat.feature.chat.ImageAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiJob
import com.muyuchat.feature.chat.ChatModelChoice
import com.muyuchat.feature.chat.ChatUiState
import com.muyuchat.feature.modelhub.ModelHubScreen
import com.muyuchat.feature.modelhub.CloudApiUiState
import com.muyuchat.feature.modelhub.CloudModelUiItem
import com.muyuchat.feature.modelhub.CloudProviderPresetUi
import com.muyuchat.feature.modelhub.LocalImageModelUiItem
import com.muyuchat.feature.modelhub.ModelHubUiState
import com.muyuchat.feature.settings.LocalApiToolScreen
import com.muyuchat.feature.settings.SettingsHubScreen
import com.muyuchat.feature.settings.SettingsUiState
import com.muyuchat.mca.ui.McaTheme
import com.muyuchat.core.benchmark.BenchmarkResult
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingChatExportSessionId: String? = null
    private var pendingVisionProjectorModelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    viewModel.onAppBackgrounded()
                }
            }
        )
        val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importModel(uri)
        }
        val localImageModelImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importLocalImageModel(uri)
        }
        val visionProjectorImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val modelId = pendingVisionProjectorModelId
            pendingVisionProjectorModelId = null
            if (uri != null && modelId != null) viewModel.attachVisionProjector(modelId, uri)
        }
        val diagnosticExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) viewModel.exportDiagnosticReport(uri)
        }
        val chatExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            val sessionId = pendingChatExportSessionId
            pendingChatExportSessionId = null
            if (uri != null && sessionId != null) {
                viewModel.exportChatSession(sessionId, uri)
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            McaTheme {
                McaApp(
                    state = state,
                    onTab = viewModel::selectTab,
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/zip",
                                "application/x-zip-compressed",
                                "*/*"
                            )
                        )
                    },
                    onImportLocalImageModel = {
                        localImageModelImportLauncher.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/zip",
                                "application/x-zip-compressed",
                                "*/*"
                            )
                        )
                    },
                    onAttachVisionProjector = { modelId ->
                        pendingVisionProjectorModelId = modelId
                        visionProjectorImportLauncher.launch(
                            arrayOf(
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onExportDiagnostics = {
                        diagnosticExportLauncher.launch("mca-diagnostic-${System.currentTimeMillis()}.json")
                    },
                    onExportChatSession = { sessionId ->
                        pendingChatExportSessionId = sessionId
                        chatExportLauncher.launch(viewModel.chatSessionExportFileName(sessionId))
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}
@Composable
private fun McaApp(
    state: MainUiState,
    onTab: (AppTab) -> Unit,
    onImport: () -> Unit,
    onImportLocalImageModel: () -> Unit,
    onAttachVisionProjector: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportChatSession: (String) -> Unit,
    viewModel: MainViewModel
) {
    var appMenuOpen by rememberSaveable { mutableStateOf(false) }
    fun preparePageReturn() {
        if (appMenuOpen) {
            appMenuOpen = true
        }
    }
    fun finishAppMenuReturn() {
        onTab(AppTab.CHAT)
    }

    Scaffold { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()

        Box(modifier = modifier) {
            ChatScreen(
                state = ChatUiState(
                    messages = state.messages,
                    history = state.chatSessions.map { session ->
                        ChatHistoryItem(
                            id = session.id,
                            title = session.title,
                            updatedAtText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(session.updatedAt)),
                            updatedAtMillis = session.updatedAt,
                            messageCount = session.messages.size,
                            pinned = session.pinned,
                            selected = session.id == state.activeChatSessionId
                        )
                    },
                    localModels = buildList {
                        addAll(
                            state.cloudModels
                                .filter { it.kind == CloudModelKind.CHAT && it.configured }
                                .sortedByDescending { it.updatedAt }
                                .map { model ->
                                ChatModelChoice(
                                    id = MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + model.id,
                                    displayName = model.modelName,
                                    quant = "云端推理",
                                    loaded = state.selectedChatBackend == ChatBackend.CLOUD && model.id == state.selectedCloudChatModelId,
                                    subtitle = model.apiFormat.label,
                                    cloud = true
                                )
                            }
                        )
                        addAll(
                            state.models
                                .sortedWith(
                                    compareByDescending<com.muyuchat.core.modelstore.ModelManifest> {
                                        if (it.id == state.loadedModelId) 1 else 0
                                    }
                                        .thenByDescending { it.lastLoadedAt ?: it.createdAt }
                                )
                                .take(3)
                                .map { model ->
                                    ChatModelChoice(
                                        id = model.id,
                                        displayName = model.displayName,
                                        quant = model.quant,
                                        sizeBytes = model.sizeBytes,
                                        loaded = state.selectedChatBackend == ChatBackend.LOCAL && model.id == state.loadedModelId,
                                        subtitle = model.runtime.label
                                    )
                                }
                        )
                    },
                    imageModels = buildList {
                        addAll(
                            state.localImageModels
                                .sortedByDescending { if (it.id == state.selectedLocalImageModelId) 1 else 0 }
                                .take(3)
                                .map { model ->
                                    val readiness = model.localImageReadinessMessage()
                                    ChatModelChoice(
                                        id = MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.displayName,
                                        quant = if (readiness == null) "本地生图" else "缺少组件",
                                        sizeBytes = model.sizeBytes,
                                        loaded = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId,
                                        subtitle = if (readiness == null) {
                                            "${model.family.label} · ${model.runtime.label}"
                                        } else {
                                            "${model.family.label} · 需导入组件包"
                                        },
                                        cloud = false
                                    )
                                }
                        )
                        addAll(
                            state.cloudModels
                                .filter { it.kind == CloudModelKind.IMAGE && it.configured }
                                .sortedByDescending { it.updatedAt }
                                .take(3)
                                .map { model ->
                                    ChatModelChoice(
                                        id = MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.modelName,
                                        quant = "云端生图",
                                        loaded = state.selectedImageBackend == ImageBackend.CLOUD && model.id == state.selectedCloudImageModelId,
                                        subtitle = model.protocolLabel,
                                        cloud = true
                                    )
                                }
                        )
                    },
                    images = state.images.map { image ->
                        ImageAssetUiItem(
                            id = image.id,
                            name = image.name,
                            uriString = image.uriString,
                            source = image.source,
                            prompt = image.prompt,
                            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(image.createdAt)),
                            sizeText = formatAssetBytes(image.sizeBytes),
                            width = image.width,
                            height = image.height
                        )
                    },
                    imageJobs = state.imageJobs.map { job ->
                        ImageGenerationUiJob(
                            id = job.id,
                            prompt = job.prompt,
                            statusLabel = job.status.label,
                            imageAssetId = job.imageAssetId,
                            failed = job.status.failed,
                            message = job.message,
                            startedAtMillis = job.startedAtMillis
                        )
                    },
                    activeConversationId = state.activeChatSessionId,
                    input = state.input,
                    isGenerating = state.isGenerating,
                    selectedModelId = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.selectedCloudChatModelId?.let { MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + it }
                    } else {
                        state.loadedModelId
                    },
                    selectedModelName = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudChatModelId }?.modelName
                    } else {
                        state.loadedModelName
                    },
                    selectedModelIsCloud = state.selectedChatBackend == ChatBackend.CLOUD,
                    selectedImageModelId = if (state.selectedImageBackend == ImageBackend.CLOUD) {
                        state.selectedCloudImageModelId?.let { MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + it }
                    } else {
                        state.selectedLocalImageModelId?.let { MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + it }
                    },
                    selectedImageModelName = if (state.selectedImageBackend == ImageBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }?.modelName
                    } else {
                        state.localImageModels.firstOrNull { it.id == state.selectedLocalImageModelId }?.displayName
                    },
                    selectedImageModelIsCloud = state.selectedImageBackend == ImageBackend.CLOUD,
                    stats = state.stats,
                    apiEnabled = state.apiEnabled,
                    restEnabled = state.restEnabled,
                    reasoningMode = state.params.reasoningMode
                ),
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                onNewConversation = viewModel::newChat,
                onSelectConversation = viewModel::selectChatSession,
                onDeleteConversation = viewModel::deleteChatSession,
                onClearHistory = viewModel::clearChatHistory,
                onRenameConversation = viewModel::renameChatSession,
                onTogglePinConversation = viewModel::toggleChatSessionPinned,
                onExportConversation = onExportChatSession,
                onRegenerate = viewModel::regenerateLastResponse,
                onDeleteMessage = viewModel::deleteMessageAt,
                onUploadFile = viewModel::attachFile,
                onUseImageAsset = viewModel::useImageAsset,
                onDeleteImageAsset = viewModel::deleteImageAsset,
                onGenerateImagePrompt = viewModel::generateImageAsset,
                onCancelImageGeneration = viewModel::cancelImageGeneration,
                onSelectImageModel = viewModel::selectImageGenerationModel,
                onReasoningModeChange = viewModel::updateReasoningMode,
                onCloudReasoningModeLocked = viewModel::showCloudReasoningModeLocked,
                onLoadModel = viewModel::selectChatModel,
                onOpenAgent = { onTab(AppTab.AGENT) },
                onOpenModels = { onTab(AppTab.MODELS) },
                onOpenApi = { onTab(AppTab.API) },
                onOpenSettings = { onTab(AppTab.SETTINGS) },
                appMenuOpen = appMenuOpen,
                onAppMenuOpenChange = { appMenuOpen = it },
                modifier = Modifier.fillMaxSize()
            )

            SwipeBackPage(
                visible = state.tab == AppTab.AGENT,
                onDismissStart = { preparePageReturn() },
                onDismiss = { finishAppMenuReturn() }
            ) { pageModifier, closePage ->
                AgentScreen(
                state = AgentUiState(
                    deviceProfile = state.deviceProfile,
                    recommendation = state.agentRecommendation,
                    benchmark = state.benchmark,
                    tuningTrials = state.benchmark?.toTuningTrialItems().orEmpty(),
                    benchmarkHistory = state.benchmarkHistory.map { record ->
                        val params = runCatching { org.json.JSONObject(record.paramsJson) }.getOrNull()
                        BenchmarkHistoryItem(
                            timeText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(record.time)),
                            modelName = record.modelName ?: "unknown",
                            decodeTps = record.result.decodeTps,
                            ttftMs = record.result.ttftMs,
                            nCtx = params?.optInt("n_ctx") ?: 0,
                            nThreads = params?.optInt("n_threads") ?: 0,
                            stable = record.result.stable
                        )
                    },
                    preference = state.preference,
                    isBusy = state.busy,
                    statusMessage = state.statusMessage,
                    loadedModelName = state.loadedModelName,
                    lastAutoTuningSummary = state.lastAutoTuningSummary,
                    params = state.params,
                    agentDecisionHistory = state.agentLogs.take(10).map { log ->
                        val recommendation = runCatching { org.json.JSONObject(log.recommendationJson) }.getOrNull()
                        val name = recommendation
                            ?.optJSONObject("recommended")
                            ?.optJSONObject("model")
                            ?.optString("displayName")
                            .orEmpty()
                        val risk = recommendation?.optString("risk").orEmpty()
                        AgentDecisionItem(
                            timeText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.time)),
                            title = if (log.userConfirmed) "已应用参数" else "自动建议",
                            detail = "${name.ifBlank { "无推荐模型" }} · 风险 ${risk.ifBlank { "unknown" }}"
                        )
                    }
                ),
                onScan = viewModel::scanAgent,
                onPreferenceChange = viewModel::updateAgentPreference,
                onApplyRecommendation = viewModel::applyAgentRecommendation,
                onBenchmark = viewModel::runAgentBenchmark,
                onQuickDebug = viewModel::runAgentQuickDebug,
                onDeepDebug = viewModel::runAgentDeepDebug,
                onPowerDebug = viewModel::runAgentPowerDebug,
                onAgentInfo = viewModel::showAgentDebugExplanation,
                onParamsChange = viewModel::updateParams,
                onBack = closePage,
                modifier = pageModifier
            )
            }

            SwipeBackPage(
                visible = state.tab == AppTab.MODELS,
                onDismissStart = { preparePageReturn() },
                onDismiss = { finishAppMenuReturn() }
            ) { pageModifier, closePage ->
                ModelHubScreen(
                state = ModelHubUiState(
                    localModels = state.models,
                    localImageModels = state.localImageModels.map { model ->
                        val readiness = model.localImageReadinessMessage()
                        LocalImageModelUiItem(
                            id = model.id,
                            displayName = model.displayName,
                            runtimeLabel = model.runtime.label,
                            familyLabel = model.family.label,
                            fileName = model.fileName,
                            sizeBytes = model.sizeBytes,
                            imageSize = model.imageSize,
                            componentCount = model.componentCount,
                            readyForGeneration = readiness == null,
                            readinessMessage = readiness,
                            selected = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId
                        )
                    },
                    remoteFiles = state.remoteFiles,
                    recommendedRemoteModels = state.recommendedRemoteModels,
                    hubModels = state.hubModels,
                    hubQuery = state.hubQuery,
                    hubPage = state.hubPage,
                    hubTotalCount = state.hubTotalCount,
                    repoInput = state.repoInput,
                    downloadFileName = state.downloadFileName,
                    downloadedBytes = state.downloadedBytes,
                    downloadTotalBytes = state.downloadTotalBytes,
                    downloadSpeedBytesPerSecond = state.downloadSpeedBytesPerSecond,
                    downloadRemainingSeconds = state.downloadRemainingSeconds,
                    downloadStatus = state.downloadStatus,
                    deviceTotalRamBytes = state.deviceProfile?.displayTotalRamBytes ?: 0L,
                    deviceAvailableRamBytes = state.deviceProfile?.availableRamBytes ?: 0L,
                    deviceAccelerationSummary = "本地推理固定使用 CPU；云端接口用于更高性能模型。",
                    deviceImagePolicy = "本地生图固定使用 CPU stable-diffusion.cpp；需要速度优先时建议切换云端生图。",
                    deviceImageTier = "CPU",
                    cloudApi = CloudApiUiState(
                        enabled = state.cloudApiConfig.enabled,
                        apiFormat = state.cloudApiConfig.apiFormat.name,
                        availableFormats = cloudApiFormats().map { it.name to it.label },
                        providerName = state.cloudApiConfig.providerName,
                        displayName = state.cloudApiConfig.displayName,
                        baseUrl = state.cloudApiConfig.baseUrl,
                        apiKey = state.cloudApiConfig.apiKey,
                        chatModel = state.cloudApiConfig.chatModel,
                        imageApiFormat = state.cloudApiConfig.imageApiFormat.name,
                        availableImageFormats = cloudImageApiFormats().map { it.name to it.label },
                        imageModel = state.cloudApiConfig.imageModel,
                        imageSize = state.cloudApiConfig.imageSize,
                        imageEndpointPath = state.cloudApiConfig.imageEndpointPath,
                        imageModelPresets = imageModelPresetsFor(state.cloudApiConfig.imageApiFormat),
                        imageSizePresets = imageSizePresetsFor(state.cloudApiConfig.imageApiFormat),
                        providerPresets = cloudProviderPresets(),
                        connectedModels = state.cloudModels.map { model ->
                            CloudModelUiItem(
                                id = model.id,
                                kind = model.kind.name,
                                displayName = model.displayName,
                                providerName = model.providerName,
                                protocolLabel = model.protocolLabel,
                                modelName = model.modelName,
                                baseUrl = model.baseUrl,
                                imageSize = if (model.kind == CloudModelKind.IMAGE) model.imageSize else "",
                                selected = when (model.kind) {
                                    CloudModelKind.CHAT -> state.selectedChatBackend == ChatBackend.CLOUD && model.id == state.selectedCloudChatModelId
                                    CloudModelKind.IMAGE -> state.selectedImageBackend == ImageBackend.CLOUD && model.id == state.selectedCloudImageModelId
                                }
                            )
                        },
                        selected = state.selectedChatBackend == ChatBackend.CLOUD,
                        configured = state.cloudApiConfig.configured,
                        imageConfigured = state.cloudApiConfig.imageConfigured,
                        imageSupported = true
                    ),
                    isBusy = state.busy,
                    loadedModelId = state.loadedModelId,
                    statusMessage = state.statusMessage
                ),
                onImportClick = onImport,
                onRepoInputChange = viewModel::onRepoInputChange,
                onFetchRemoteFiles = viewModel::fetchRemoteFiles,
                onHubQueryChange = viewModel::onHubQueryChange,
                onSearchHubModels = viewModel::searchHubModels,
                onFetchHubModelFiles = viewModel::fetchHubModelFiles,
                onShowRecommendedFiles = viewModel::fetchRecommendedFiles,
                onDownloadRecommended = viewModel::downloadRecommended,
                onOpenModelPage = viewModel::openModelScopePage,
                onDownload = viewModel::download,
                onLoad = viewModel::loadModel,
                onVerify = viewModel::verifyModel,
                onDelete = viewModel::deleteModel,
                onAttachVisionProjector = { model -> onAttachVisionProjector(model.id) },
                onImportLocalImageModel = onImportLocalImageModel,
                onSelectLocalImageModel = viewModel::selectLocalImageModel,
                onVerifyLocalImageModel = viewModel::verifyLocalImageModel,
                onDeleteLocalImageModel = viewModel::deleteLocalImageModel,
                onCloudEnabledChange = viewModel::updateCloudApiEnabled,
                onBeginAddCloudModel = viewModel::beginAddCloudModel,
                onEditCloudModel = viewModel::editCloudModel,
                onCloudProviderPreset = viewModel::applyCloudProviderPreset,
                onCloudFormatChange = viewModel::updateCloudApiFormat,
                onCloudBaseUrlChange = viewModel::updateCloudBaseUrl,
                onCloudApiKeyChange = viewModel::updateCloudApiKey,
                onCloudChatModelChange = viewModel::updateCloudChatModel,
                onCloudImageFormatChange = viewModel::updateCloudImageApiFormat,
                onCloudImageModelChange = viewModel::updateCloudImageModel,
                onCloudImageSizeChange = viewModel::updateCloudImageSize,
                onCloudImageEndpointPathChange = viewModel::updateCloudImageEndpointPath,
                onCloudDisplayNameChange = viewModel::updateCloudDisplayName,
                onSaveCloudChatModel = viewModel::saveCloudChatModel,
                onSaveCloudImageModel = viewModel::saveCloudImageModel,
                onTestCloudApi = viewModel::testCloudApiConfig,
                onSelectCloudChat = { modelId -> viewModel.selectChatModel(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + modelId) },
                onSelectCloudImage = viewModel::selectCloudImageModel,
                onDeleteCloudModel = viewModel::deleteCloudModel,
                onRefreshLocal = viewModel::refreshLocalModels,
                onBack = closePage,
                modifier = pageModifier
            )
            }

            SwipeBackPage(
                visible = state.tab == AppTab.API,
                onDismissStart = { preparePageReturn() },
                onDismiss = { finishAppMenuReturn() }
            ) { pageModifier, closePage ->
                LocalApiToolScreen(
                    state = state.settingsUiState(),
                    onApiToggle = viewModel::toggleApi,
                    onRestToggle = viewModel::toggleRest,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            SwipeBackPage(
                visible = state.tab == AppTab.SETTINGS,
                onDismissStart = { preparePageReturn() },
                onDismiss = { finishAppMenuReturn() }
            ) { pageModifier, closePage ->
                SettingsHubScreen(
                state = state.settingsUiState(),
                onRefreshLogs = viewModel::refreshLogs,
                onRefreshDiagnostics = viewModel::refreshDiagnostics,
                onExportDiagnostics = onExportDiagnostics,
                onBack = closePage,
                modifier = pageModifier
            )
            }
        }
    }
}

@Composable
private fun SwipeBackPage(
    visible: Boolean,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = drawerPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            suspend fun closeAnimated() {
                onDismissStart()
                offsetX.animateTo(
                    targetValue = -widthPx,
                    animationSpec = tween(durationMillis = 180)
                )
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch {
                    closeAnimated()
                }
            }

            SystemBackMotionHandler(
                enabled = visible,
                onProgress = { progress ->
                    scope.launch {
                        offsetX.snapTo(-widthPx * progress.coerceIn(0f, 1f))
                    }
                },
                onCancel = {
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 160)
                        )
                    }
                },
                onBack = {
                    scope.launch {
                        if (offsetX.value > -widthPx * 0.08f) {
                            offsetX.snapTo(-widthPx * 0.08f)
                        }
                        closeAnimated()
                    }
                }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

@Composable
private fun SystemBackMotionHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnBack by rememberUpdatedState(onBack)

    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                currentOnProgress(0f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                currentOnProgress(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                currentOnCancel()
            }

            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
    }

    LaunchedEffect(enabled) {
        callback.isEnabled = enabled
    }

    androidx.compose.runtime.DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher?.addCallback(lifecycleOwner, callback)
        onDispose {
            callback.remove()
        }
    }
}

private fun drawerPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 260),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 180))

private fun drawerPageExit() = slideOutHorizontally(
    animationSpec = tween(durationMillis = 260),
    targetOffsetX = { -it }
) + fadeOut(animationSpec = tween(durationMillis = 180))

@Composable
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { 144.dp.toPx() }
    val triggerPx = with(density) { 48.dp.toPx() }
    return pointerInput(onBack, edgeWidthPx, triggerPx) {
        var startedAtEdge = false
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x >= size.width - edgeWidthPx
                totalDrag = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                if (startedAtEdge) {
                    totalDrag += dragAmount
                    if (dragAmount < 0f) change.consume()
                }
            },
            onDragEnd = {
                if (startedAtEdge && totalDrag < -triggerPx) onBack()
                startedAtEdge = false
                totalDrag = 0f
            },
            onDragCancel = {
                startedAtEdge = false
                totalDrag = 0f
            }
        )
    }
}

private fun MainUiState.settingsUiState(): SettingsUiState = SettingsUiState(
    params = params,
    stats = stats,
    logs = logs,
    agentLogs = agentLogs.map { log ->
        val recommendation = runCatching { org.json.JSONObject(log.recommendationJson) }.getOrNull()
        val name = recommendation
            ?.optJSONObject("recommended")
            ?.optJSONObject("model")
            ?.optString("displayName")
            .orEmpty()
        val risk = recommendation?.optString("risk").orEmpty()
        "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.time))} · ${name.ifBlank { "无推荐" }} · risk=$risk · confirmed=${log.userConfirmed}"
    },
    apiEnabled = apiEnabled,
    restEnabled = restEnabled,
    apiKey = apiKey,
    localApiAddress = localApiAddress,
    openApiAddress = openApiAddress,
    nativeStatsJson = nativeStatsJson,
    diagnosticReport = diagnosticReport
)

private fun formatAssetBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}

private fun imageModelPresetsFor(format: CloudImageApiFormat): List<String> =
    when (format) {
        CloudImageApiFormat.OPENAI_IMAGES -> listOf("gpt-image-1.5", "gpt-image-1", "dall-e-3")
        CloudImageApiFormat.DASHSCOPE_IMAGE -> listOf("qwen-image-2.0-pro", "qwen-image-2.0", "qwen-image-plus", "qwen-image")
        CloudImageApiFormat.CUSTOM_PATH -> emptyList()
    }

private fun cloudApiFormats(): List<CloudApiFormat> =
    listOf(CloudApiFormat.OPENAI_COMPATIBLE, CloudApiFormat.ANTHROPIC)

private fun cloudImageApiFormats(): List<CloudImageApiFormat> =
    listOf(CloudImageApiFormat.OPENAI_IMAGES, CloudImageApiFormat.DASHSCOPE_IMAGE, CloudImageApiFormat.CUSTOM_PATH)

private fun cloudProviderPresets(): List<CloudProviderPresetUi> = listOf(
    CloudProviderPresetUi("openai", "OpenAI 协议", "自定义 OpenAI-compatible 接口"),
    CloudProviderPresetUi("anthropic", "Anthropic 协议", "自定义 Anthropic Messages 接口")
)

private fun imageSizePresetsFor(format: CloudImageApiFormat): List<String> =
    when (format) {
        CloudImageApiFormat.OPENAI_IMAGES -> listOf("1024x1024", "1024x1536", "1536x1024", "1024x1792", "1792x1024")
        CloudImageApiFormat.DASHSCOPE_IMAGE -> listOf("1024x1024", "1024x1536", "1536x1024", "1328x1328", "1664x928", "928x1664")
        CloudImageApiFormat.CUSTOM_PATH -> listOf("1024x1024", "1024x1536", "1536x1024", "16:9", "9:16", "1:1")
    }

private fun BenchmarkResult.toTuningTrialItems(): List<TuningTrialItem> {
    val trials = runCatching { org.json.JSONArray(threadResultsJson) }.getOrNull() ?: return emptyList()
    return (0 until trials.length()).mapNotNull { index ->
        val item = trials.optJSONObject(index) ?: return@mapNotNull null
        val threads = item.optInt("threads")
        if (threads <= 0) return@mapNotNull null
        TuningTrialItem(
            threads = threads,
            decodeTps = item.optDouble("decodeTps"),
            ttftMs = item.optLong("ttftMs"),
            genTokens = item.optInt("genTokens"),
            stable = item.optBoolean("stable", true) && item.optString("error").isBlank(),
            selected = threads == bestThreadCount
        )
    }
}

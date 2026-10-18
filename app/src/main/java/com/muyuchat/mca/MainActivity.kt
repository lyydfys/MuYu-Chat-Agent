@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.mca

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.feature.agent.*
import com.muyuchat.feature.chat.*
import com.muyuchat.feature.modelhub.*
import com.muyuchat.feature.settings.*
import com.muyuchat.mca.presentation.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PENDING_WORLD_BOOK_SCOPE_KEY = "pending_world_book_scope"

class MainActivity : ComponentActivity() {
    private val startup: AppStartupViewModel by viewModels()
    private val viewModel: MainViewModel get() = requireNotNull(startup.state.value.model)
    private var pendingChatExportSessionId: String? = null
    private var pendingVisionProjectorModelId: String? = null
    private var pendingKnowledgeBaseId: String? = null
    private var pendingKnowledgeDocumentOwner: ContentImportOwner? = null
    private var pendingWorldBookImportScope: WorldBookScope = WorldBookScope.GLOBAL
    private var pendingAssistantCardOwner: ContentImportOwner? = null
    private var pendingWorldBookOwner: ContentImportOwner? = null
    private var pendingModelImportTextOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
        pendingWorldBookImportScope = WorldBookScope.fromWireName(savedInstanceState?.getString(PENDING_WORLD_BOOK_SCOPE_KEY))
        pendingModelImportTextOnly = savedInstanceState?.getBoolean("pending_model_import_text_only") ?: false
        pendingAssistantCardOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_assistant_card_owner"))
        pendingWorldBookOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_world_book_owner"))
        pendingKnowledgeDocumentOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_knowledge_document_owner"))

        val importLauncher = registerForActivityResult(OpenModelDocumentsContract()) { uris ->
            if (uris.isNotEmpty()) startup.whenReady { it.importModel(uris, pendingModelImportTextOnly, persistModelImportAccess(uris)) }
        }
        val modelDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) startup.whenReady { it.importModelDirectory(uri, pendingModelImportTextOnly, persistModelImportAccess(listOf(uri))) }
        }
        val offlineTranslationImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) { persistModelImportAccess(listOf(uri)); startup.whenReady { it.importOfflinePromptTranslationBundle(uri) } }
        }
        val localImageModelImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startup.whenReady { it.importLocalImageModel(uri) }
        }
        val assistantCardImportLauncher = registerForActivityResult(OpenAnyDocumentContract()) { uri ->
            val owner = pendingAssistantCardOwner
            pendingAssistantCardOwner = null
            if (uri != null) startup.whenReady { it.importAssistantCardFile(uri.toString(), owner) }
        }
        val worldBookImportLauncher = registerForActivityResult(OpenAnyDocumentContract()) { uri ->
            val scope = pendingWorldBookImportScope
            val owner = pendingWorldBookOwner
            pendingWorldBookImportScope = WorldBookScope.GLOBAL
            pendingWorldBookOwner = null
            if (uri != null) startup.whenReady { it.importWorldBookFile(uri.toString(), scope, owner) }
        }
        val knowledgeDocumentImportLauncher = registerForActivityResult(OpenAnyDocumentContract()) { uri ->
            val baseId = pendingKnowledgeBaseId
            val owner = pendingKnowledgeDocumentOwner
            pendingKnowledgeBaseId = null
            pendingKnowledgeDocumentOwner = null
            if (uri != null && baseId != null) startup.whenReady { it.importKnowledgeDocument(baseId, uri.toString(), owner) }
        }
        val visionProjectorImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val modelId = pendingVisionProjectorModelId
            pendingVisionProjectorModelId = null
            if (uri != null && modelId != null) startup.whenReady { it.attachVisionProjector(modelId, uri) }
        }
        val diagnosticExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) startup.whenReady { it.exportDiagnosticReport(uri) }
        }
        val chatExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            val sId = pendingChatExportSessionId
            pendingChatExportSessionId = null
            if (uri != null && sId != null) startup.whenReady { it.exportChatSession(sId, uri) }
        }

        setContent {
            val startupState by startup.state.collectAsStateWithLifecycle()
            val readyModel = startupState.model
            if (readyModel == null) {
                McaTheme {
                    AppStartupScreen(startupState, onRetry = { startup.start() }, onSkipScan = { startup.skipModelDiscovery() })
                }
                return@setContent
            }
            val state by readyModel.uiState.collectAsStateWithLifecycle()
            McaTheme {
                McaApp(
                    state = state,
                    onTab = viewModel::selectTab,
                    onImport = { pendingModelImportTextOnly = it; importLauncher.launch(Unit) },
                    onImportModelDirectory = { pendingModelImportTextOnly = it; modelDirectoryLauncher.launch(null) },
                    onImportLocalImageModel = { localImageModelImportLauncher.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*")) },
                    onImportOfflineTranslation = { offlineTranslationImportLauncher.launch(null) },
                    onImportAssistantCardFile = { pendingAssistantCardOwner = viewModel.captureContentImportOwner(); assistantCardImportLauncher.launch(Unit) },
                    onImportWorldBookFile = { scope ->
                        pendingWorldBookOwner = viewModel.captureContentImportOwner()
                        pendingWorldBookImportScope = if (scope == WorldBookImportScope.CHAT) WorldBookScope.CHAT else if (scope == WorldBookImportScope.ASSISTANT) WorldBookScope.ASSISTANT else WorldBookScope.GLOBAL
                        worldBookImportLauncher.launch(Unit)
                    },
                    onImportKnowledgeDocument = { id -> pendingKnowledgeBaseId = id; pendingKnowledgeDocumentOwner = viewModel.captureContentImportOwner(); knowledgeDocumentImportLauncher.launch(Unit) },
                    onAttachVisionProjector = { id -> pendingVisionProjectorModelId = id; visionProjectorImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    onExportDiagnostics = { diagnosticExportLauncher.launch("mca-diagnostic-${System.currentTimeMillis()}.json") },
                    onExportChatSession = { id -> pendingChatExportSessionId = id; chatExportLauncher.launch(viewModel.chatSessionExportFileName(id)) },
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_WORLD_BOOK_SCOPE_KEY, pendingWorldBookImportScope.wireName)
        outState.putString("pending_assistant_card_owner", pendingAssistantCardOwner?.toJsonString())
        outState.putString("pending_world_book_owner", pendingWorldBookOwner?.toJsonString())
        outState.putString("pending_knowledge_document_owner", pendingKnowledgeDocumentOwner?.toJsonString())
        outState.putBoolean("pending_model_import_text_only", pendingModelImportTextOnly)
        super.onSaveInstanceState(outState)
    }

    private fun persistModelImportAccess(uris: List<android.net.Uri>): Boolean {
        return uris.map { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)
        }.all { it }
    }
}

@Composable
private fun McaApp(
    state: MainUiState, onTab: (AppTab) -> Unit, onImport: (Boolean) -> Unit,
    onImportModelDirectory: (Boolean) -> Unit, onImportLocalImageModel: () -> Unit,
    onImportOfflineTranslation: () -> Unit, onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit, onImportKnowledgeDocument: (String) -> Unit,
    onAttachVisionProjector: (String) -> Unit, onExportDiagnostics: () -> Unit,
    onExportChatSession: (String) -> Unit, viewModel: MainViewModel
) {
    val timestampLocale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    state.contentImportPreview?.let { preview ->
        ContentImportPreviewDialog(
            preview = preview,
            committing = state.contentImportCommitting,
            onConfirm = { viewModel.confirmContentImportPreview(preview.id) },
            onCancel = { viewModel.cancelContentImportPreview(preview.id) }
        )
    }

    var modelImportDialogOpen by rememberSaveable { mutableStateOf(false) }
    ModelImportDialog(
        visible = modelImportDialogOpen,
        onDismissRequest = { modelImportDialogOpen = false },
        onImportDirectory = onImportModelDirectory,
        onImportFiles = onImport
    )

    var appMenuOpen by rememberSaveable { mutableStateOf(false) }
    var internalBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var startModelsInRecommended by rememberSaveable { mutableStateOf(false) }
    var startSettingsInWebSearch by rememberSaveable { mutableStateOf(false) }
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val availableAppUpdate = state.appUpdate.takeIf { it.status == AppUpdateStatus.AVAILABLE && it.canDownload }

    AppUpdatePromptDialog(
        versionName = availableAppUpdate?.latestVersionName?.takeIf { state.tab != AppTab.SETTINGS && dismissedUpdateVersion != it },
        onConfirm = {
            dismissedUpdateVersion = availableAppUpdate?.latestVersionName
            onTab(AppTab.SETTINGS)
        },
        onDismiss = {
            dismissedUpdateVersion = availableAppUpdate?.latestVersionName
        }
    )

    val preparedImageUi by produceState<Map<String, PreparedLocalImageUi>>(
        initialValue = emptyMap(), state.localImageModels, state.qnnImageVerificationCurrentByModelId
    ) {
        value = withContext(Dispatchers.IO) {
            state.localImageModels.associate { model ->
                model.path to prepareLocalImageUi(model, state.qnnImageVerificationCurrentByModelId[model.id])
            }
        }
    }
    val imageGenerationHistoryById = remember(state.images) {
        state.images.associate { it.id to ImageGenerationHistoryMetadata.fromJsonOrNull(it.generationMetadataJson) }
    }
    val activeChatSession = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
    val selectedAssistant = state.assistants.firstOrNull { it.id == state.selectedAssistantId }
    val assistantAppearance = selectedAssistant?.appearance?.takeUnless { it.isDefault }
    val sessionAppearance = activeChatSession?.appearanceOverride
    val effectiveChatAppearance = resolveChatAppearance(sessionAppearance, assistantAppearance, state.globalChatAppearance)
    val agentTuningTrials = remember(state.benchmark) {
        state.benchmark?.toTuningTrialItems().orEmpty()
    }
    val agentBenchmarkHistory = remember(state.benchmarkHistory, timestampLocale) {
        state.benchmarkHistory.map { record ->
            val params = runCatching { org.json.JSONObject(record.paramsJson) }.getOrNull()
            BenchmarkHistoryItem(
                timeText = java.text.SimpleDateFormat("HH:mm:ss", timestampLocale)
                    .format(java.util.Date(record.time)),
                modelName = record.modelName ?: "unknown",
                decodeTps = record.result.decodeTps,
                ttftMs = record.result.ttftMs,
                nCtx = params?.optInt("n_ctx") ?: 0,
                nThreads = params?.optInt("n_threads") ?: 0,
                stable = record.result.stable
            )
        }
    }
    val agentDecisionHistory = remember(state.agentLogs, timestampLocale) {
        state.agentLogs.take(10).map { log ->
            val rec = runCatching { org.json.JSONObject(log.recommendationJson) }.getOrNull()
            val name = rec?.optJSONObject("recommended")?.optJSONObject("model")
                ?.optString("displayName").orEmpty()
            val risk = rec?.optString("risk").orEmpty()
            AgentDecisionItem(
                timeText = java.text.SimpleDateFormat("HH:mm:ss", timestampLocale)
                    .format(java.util.Date(log.time)),
                title = if (log.userConfirmed) "已应用参数" else "自动建议",
                detail = "${name.ifBlank { "无推荐模型" }} · 风险 ${risk.ifBlank { "unknown" }}"
            )
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val assistantImageToolPermission = remember(
                state.activeChatSessionId, state.selectedChatBackend, state.selectedCloudChatModelId,
                state.selectedImageBackend, state.selectedLocalImageModelId, state.selectedCloudImageModelId,
                state.cloudModels, state.localImageModels, state.statusMessage
            ) { viewModel.chatImageToolPermissionUiState() }
            val sessionPresentations = remember(state.chatSessions, state.assistants) {
                state.chatSessions.assistantSessionPresentations(state.assistants)
            }
            ChatRoute(
                state = buildChatUiState(
                    state = state, sessionPresentations = sessionPresentations, timestampLocale = timestampLocale,
                    imageGenerationHistoryById = imageGenerationHistoryById, assistantImageToolPermission = assistantImageToolPermission,
                    preparedImageUi = preparedImageUi, generationParamsForChatModel = viewModel::generationParamsForChatModel
                ),
                inputs = ChatRouteInputs(
                    chatBackground = effectiveChatAppearance.toUiState(), globalChatBackground = state.globalChatAppearance.toUiState(),
                    assistantChatBackground = assistantAppearance?.toUiState(), sessionChatBackground = sessionAppearance?.toUiState(),
                    hasActiveChatSession = activeChatSession != null, chatBackgroundImporting = state.chatBackgroundImporting,
                    assistantMemories = state.assistantMemories.map { m -> AssistantMemoryUiItem(m.id, m.assistantId, m.scope, m.content, m.source, m.createdAt) },
                    offlineTranslationStatus = state.offlineTranslationStatus, offlineTranslationInstalled = state.offlineTranslationInstalled,
                    offlineTranslationInstalling = state.offlineTranslationInstalling, offlineTranslationDownloading = state.offlineTranslationDownloading,
                    offlineTranslationDownloadProgress = state.offlineTranslationDownloadProgress, appMenuOpen = appMenuOpen,
                ),
                modifier = Modifier.fillMaxSize(),
                actions = rememberMainChatScreenActions(
                    viewModel = viewModel,
                    onExportChatSession = onExportChatSession,
                    onImportAssistantCardFile = onImportAssistantCardFile,
                    onImportKnowledgeDocument = onImportKnowledgeDocument,
                    onImportLocalImageModel = onImportLocalImageModel,
                    onImportWorldBookFile = onImportWorldBookFile,
                    requestModelImport = { modelImportDialogOpen = true },
                    onTab = onTab,
                    setAppMenuOpen = { appMenuOpen = it },
                    setStartModelsInRecommended = { startModelsInRecommended = it },
                    setStartSettingsInWebSearch = { startSettingsInWebSearch = it },
                ),
            )

            SwipeBackPage(
                visible = state.tab == AppTab.AGENT,
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                AgentScreen(
                    state = AgentUiState(
                        deviceProfile = state.deviceProfile,
                        recommendation = state.agentRecommendation,
                        benchmark = state.benchmark,
                        tuningTrials = agentTuningTrials,
                        benchmarkHistory = agentBenchmarkHistory,
                        preference = state.preference, isBusy = state.busy, statusMessage = state.statusMessage,
                        loadedModelName = state.loadedModelName, lastAutoTuningSummary = state.lastAutoTuningSummary,
                        localStabilitySmokeSummary = state.localStabilitySmokeSummary, params = state.params,
                        profileId = state.profileId, revision = state.revision, profileRecordState = state.profileRecordState,
                        verification = state.verification, engineLifecycle = state.engineLifecycle, tuningJobState = state.tuningJobState,
                        reloadRequired = state.reloadRequired, pending = state.pendingProfile, rollback = state.rollbackProfile,
                        etaSeconds = state.tuningEtaSeconds, phase = state.tuningPhase, candidateProgress = state.tuningCandidateProgress,
                        agentDecisionHistory = agentDecisionHistory
                    ),
                    onScan = viewModel::scanAgent, onPreferenceChange = viewModel::updateAgentPreference,
                    onApplyRecommendation = viewModel::applyAgentRecommendation, onBenchmark = viewModel::runAgentBenchmark,
                    onQuickDebug = viewModel::runAgentQuickDebug, onStandardDebug = viewModel::runAgentStandardDebug,
                    onDeepDebug = viewModel::runAgentDeepDebug, onPowerDebug = viewModel::runAgentPowerDebug,
                    onStabilitySmoke = viewModel::runAgentStabilitySmoke, onStartTuning = viewModel::startAgentTuning,
                    onPauseTuning = viewModel::pauseAgentTuning, onResumeTuning = viewModel::resumeAgentTuning,
                    onCancelTuning = viewModel::cancelAgentTuning, onQueryTuningJob = viewModel::queryAgentTuningJob,
                    onApplyPendingProfile = viewModel::applyPendingAgentProfile, onDiscardPendingProfile = viewModel::discardPendingAgentProfile,
                    onRollbackProfile = viewModel::rollbackAgentProfile, onAgentInfo = viewModel::showAgentDebugExplanation,
                    onParamsChange = viewModel::updateParams, onBack = closePage, modifier = pageModifier
                )
            }

            SwipeBackPage(
                visible = state.tab == AppTab.MODELS,
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                ModelHubScreen(
                    startInRecommended = startModelsInRecommended,
                    state = ModelHubUiState(
                        localModels = state.models,
                        mnnRuntimeAvailable = state.mnnRuntimeAvailable,
                        localImageModels = state.localImageModels.map { model ->
                            val prepared = preparedImageUi[model.path]
                            val readinessMessage = prepared?.readiness ?: "正在后台读取模型配置…"
                            val readyForGeneration = prepared != null && prepared.readiness == null
                            val recommendationId = model.recommendationId ?: model.bundleRoot
                                ?.let { root -> runCatching { localImageBundleManifestFromRoot(File(root)) }.getOrNull()?.recommendationId }
                            LocalImageModelUiItem(
                                id = model.id, displayName = model.displayName, runtimeLabel = model.runtime.label,
                                familyLabel = model.family.label, fileName = model.fileName,
                                storagePath = model.bundleRoot ?: model.path, sizeBytes = model.sizeBytes,
                                imageSize = model.imageSize, componentCount = model.componentCount,
                                readyForGeneration = readyForGeneration, readinessMessage = readinessMessage,
                                readinessLabel = prepared?.label ?: "正在检查",
                                selected = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId,
                                recommendationId = recommendationId, verificationStatus = model.verificationStatus.name,
                                verificationMessage = model.verificationMessage
                            )
                        },
                        remoteFiles = state.remoteFiles,
                        recommendedRemoteModels = state.recommendedRemoteModels,
                        hubModels = state.hubModels, hubQuery = state.hubQuery, hubPage = state.hubPage, hubTotalCount = state.hubTotalCount,
                        repoInput = state.repoInput, downloadFileName = state.downloadFileName, downloadTaskId = state.downloadTaskId,
                        downloadedBytes = state.downloadedBytes, downloadTotalBytes = state.downloadTotalBytes,
                        downloadSpeedBytesPerSecond = state.downloadSpeedBytesPerSecond, downloadRemainingSeconds = state.downloadRemainingSeconds,
                        downloadStatus = state.downloadStatus, downloadPhase = state.downloadPhase, downloadFailureSource = state.downloadFailureSource,
                        downloadIntegrityStatus = state.downloadIntegrityStatus, downloadIntegrityMessage = state.downloadIntegrityMessage,
                        downloadExecutionStatus = state.downloadExecutionStatus, downloadExecutionMessage = state.downloadExecutionMessage,
                        importTasks = state.importTasks, deviceTotalRamBytes = state.deviceProfile?.displayTotalRamBytes ?: 0L,
                        deviceAvailableRamBytes = state.deviceProfile?.availableRamBytes ?: 0L,
                        deviceAccelerationSummary = state.deviceProfile?.deviceAccelerationSummary().orEmpty(),
                        deviceImagePolicy = state.deviceProfile?.deviceImagePolicy().orEmpty(),
                        deviceImageTier = state.deviceProfile?.deviceImageTierKey().orEmpty(),
                        deviceChipsetCode = state.deviceProfile?.accelerationProfile?.chipsetCode.orEmpty(),
                        deviceSupportedAbis = state.deviceProfile?.supportedAbis.orEmpty(),
                        deviceIsSnapdragon = state.deviceProfile?.socFamily == SocFamily.Snapdragon,
                        qairtVerifiedLocalModelIds = state.qairtVerifiedLocalModelIds,
                        qairtVerifiedRecommendationIds = state.qairtVerifiedRecommendationIds,
                        cloudApi = CloudApiUiState(
                            enabled = state.cloudApiConfig.enabled, apiFormat = state.cloudApiConfig.apiFormat.name,
                            availableFormats = cloudApiFormats().map { it.name to it.label }, providerName = state.cloudApiConfig.providerName,
                            displayName = state.cloudApiConfig.displayName, baseUrl = state.cloudApiConfig.baseUrl, apiKey = state.cloudApiConfig.apiKey,
                            chatModel = state.cloudApiConfig.chatModel, supportsVision = state.cloudApiConfig.supportsVision,
                            supportsTools = state.cloudApiConfig.supportsTools, responsesReasoningEnabled = state.cloudApiConfig.responsesReasoningEnabled,
                            imageApiFormat = state.cloudApiConfig.imageApiFormat.name, availableImageFormats = cloudImageApiFormats().map { it.name to it.label },
                            imageModel = state.cloudApiConfig.imageModel, imageSize = state.cloudApiConfig.imageSize,
                            imageEndpointPath = state.cloudApiConfig.imageEndpointPath, imageModelPresets = imageModelPresetsFor(state.cloudApiConfig.imageApiFormat),
                            imageSizePresets = imageSizePresetsFor(state.cloudApiConfig.imageApiFormat), providerPresets = cloudProviderPresets(),
                            connectedModels = state.cloudModels.map { model ->
                                CloudModelUiItem(
                                    id = model.id, kind = model.kind.name,
                                    displayName = model.displayName.ifBlank { model.modelName },
                                    providerName = model.providerName, protocolLabel = model.protocolLabel,
                                    modelName = model.modelName, baseUrl = model.baseUrl,
                                    supportsVision = model.supportsVision, supportsTools = model.supportsTools,
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
                        localChatEngineReady = state.isLocalChatEngineReady(),
                        statusMessage = if (state.busy && state.engineLifecycle == com.muyuchat.feature.agent.AgentEngineLifecycle.LOADING) {
                            state.modelLoadStage ?: state.statusMessage
                        } else state.statusMessage
                    ),
                    onImportClick = { modelImportDialogOpen = true }, onRepoInputChange = viewModel::onRepoInputChange,
                    onFetchRemoteFiles = viewModel::fetchRemoteFiles, onHubQueryChange = viewModel::onHubQueryChange,
                    onSearchHubModels = viewModel::searchHubModels, onFetchHubModelFiles = viewModel::fetchHubModelFiles,
                    onShowRecommendedFiles = viewModel::fetchRecommendedFiles,
                    onDownloadRecommended = { model -> viewModel.downloadRecommended(model, useAfterDownload = startModelsInRecommended) },
                    onOpenModelPage = viewModel::openModelScopePage, onOpenLocalModel = viewModel::selectLocalImageModel,
                    onVerifyLocalModel = viewModel::verifyLocalImageModel, onDownload = viewModel::download,
                    onLoad = viewModel::loadModel, onUnload = viewModel::unloadModel, onVerify = viewModel::verifyModel,
                    onDelete = viewModel::deleteModel, onAttachVisionProjector = { model -> onAttachVisionProjector(model.id) },
                    onImportLocalImageModel = onImportLocalImageModel, onSelectLocalImageModel = viewModel::selectLocalImageModel,
                    onVerifyLocalImageModel = viewModel::verifyLocalImageModel, onDeleteLocalImageModel = viewModel::deleteLocalImageModel,
                    onRemoveLocalImageModelRecord = viewModel::removeLocalImageModelRecord, onCloudEnabledChange = viewModel::updateCloudApiEnabled,
                    onBeginAddCloudModel = viewModel::beginAddCloudModel, onEditCloudModel = viewModel::editCloudModel,
                    onCloudProviderPreset = viewModel::applyCloudProviderPreset, onCloudFormatChange = viewModel::updateCloudApiFormat,
                    onCloudBaseUrlChange = viewModel::updateCloudBaseUrl, onCloudApiKeyChange = viewModel::updateCloudApiKey,
                    onCloudChatModelChange = viewModel::updateCloudChatModel, onCloudSupportsVisionChange = viewModel::updateCloudSupportsVision,
                    onCloudSupportsToolsChange = viewModel::updateCloudSupportsTools, onCloudResponsesReasoningChange = viewModel::updateCloudResponsesReasoning,
                    onCloudImageFormatChange = viewModel::updateCloudImageApiFormat, onCloudImageModelChange = viewModel::updateCloudImageModel,
                    onCloudImageSizeChange = viewModel::updateCloudImageSize, onCloudImageEndpointPathChange = viewModel::updateCloudImageEndpointPath,
                    onCloudDisplayNameChange = viewModel::updateCloudDisplayName, onSaveCloudChatModel = viewModel::saveCloudChatModel,
                    onSaveCloudImageModel = viewModel::saveCloudImageModel, onTestCloudApi = viewModel::testCloudApiConfig,
                    onSelectCloudChat = { modelId -> viewModel.selectChatModel(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + modelId) },
                    onSelectCloudImage = viewModel::selectCloudImageModel, onDeleteCloudModel = viewModel::deleteCloudModel,
                    onRefreshLocal = viewModel::refreshLocalModels, onPauseDownloads = viewModel::pauseManagedDownloads,
                    onResumeDownloads = viewModel::resumeManagedDownloads, onPauseImport = viewModel::pauseManagedImport,
                    onResumeImport = viewModel::resumeManagedImport, onBack = closePage, modifier = pageModifier
                )
            }

            SwipeBackPage(
                visible = state.tab == AppTab.API,
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
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
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                SettingsHubScreen(
                    state = state.settingsUiState(),
                    onRefreshLogs = viewModel::refreshLogs, onRefreshDiagnostics = viewModel::refreshDiagnostics,
                    onExportDiagnostics = onExportDiagnostics, onClearChatHistory = viewModel::clearChatHistory,
                    onClearImageLibrary = viewModel::clearImageLibrary, onClearFileLibrary = viewModel::clearFileLibrary,
                    onPersistentPrefixCacheEnabledChanged = viewModel::setPersistentPrefixCacheEnabled,
                    onClearPersistentPrefixCache = viewModel::clearPersistentPrefixCache,
                    offlineTranslationStatus = state.offlineTranslationStatus,
                    offlineTranslationInstalled = state.offlineTranslationInstalled,
                    offlineTranslationInstalling = state.offlineTranslationInstalling,
                    offlineTranslationDownloading = state.offlineTranslationDownloading,
                    offlineTranslationDownloadProgress = state.offlineTranslationDownloadProgress,
                    onDownloadOfflineTranslation = viewModel::downloadOfflinePromptTranslationBundle,
                    onCancelOfflineTranslationDownload = viewModel::cancelOfflinePromptTranslationDownload,
                    onImportOfflineTranslation = onImportOfflineTranslation,
                    onCancelOfflineTranslationImport = viewModel::cancelOfflinePromptTranslationInstall,
                    onSaveWebSearchSettings = { draft: WebSearchSettingsDraft ->
                        viewModel.saveWebSearchConfig(draft.enabled, draft.provider, draft.endpoint, draft.apiKey, draft.maxResults, draft.fetchPageContent, draft.triggerMode, draft.researchMode, draft.backupProviders.toWebSearchBackupConfigs())
                    },
                    onPreflightWebSearch = { draft: WebSearchSettingsDraft ->
                        viewModel.preflightWebSearchConfig(draft.enabled, draft.provider, draft.endpoint, draft.apiKey, draft.maxResults, draft.fetchPageContent, draft.triggerMode, draft.researchMode, draft.backupProviders.toWebSearchBackupConfigs())
                    },
                    onTestWebSearch = { query: String, draft: WebSearchSettingsDraft ->
                        viewModel.testWebSearchConfig(query, draft.enabled, draft.provider, draft.endpoint, draft.apiKey, draft.maxResults, draft.fetchPageContent, draft.triggerMode, draft.researchMode, draft.backupProviders.toWebSearchBackupConfigs())
                    },
                    onTestWebSearchTurn = { query: String, draft: WebSearchSettingsDraft, allowPublicCheckSourceForProtocolTest: Boolean ->
                        viewModel.testWebSearchTurn(query, draft.enabled, draft.provider, draft.endpoint, draft.apiKey, draft.maxResults, draft.fetchPageContent, draft.triggerMode, draft.researchMode, draft.backupProviders.toWebSearchBackupConfigs(), allowPublicCheckSourceForProtocolTest)
                    },
                    onClearWebSearchDiagnostics = viewModel::clearWebSearchDiagnostics, onCheckUpdate = viewModel::checkForAppUpdate,
                    onDownloadUpdate = viewModel::downloadAppUpdate, onInstallUpdate = viewModel::installAppUpdate,
                    onOpenRelease = viewModel::openAppUpdateRelease, onAutoCheckChanged = viewModel::setAppUpdateAutoCheckEnabled,
                    onOpenWebPage = { url -> internalBrowserUrl = url }, onBack = closePage,
                    startInWebSearch = startSettingsInWebSearch, modifier = pageModifier
                )
            }

            ChatBrowserDialogs(
                internalBrowserUrl = internalBrowserUrl,
                onDismissInternalBrowser = { internalBrowserUrl = null },
                browserTask = state.browserTask,
                onBrowserTaskEvent = viewModel::onBrowserTaskEvent
            )
        }
    }

    ConsumeImeBackHandler()
}
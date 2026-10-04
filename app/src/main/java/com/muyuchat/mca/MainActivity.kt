@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.mca

import android.os.Bundle
import android.content.Context
import android.view.WindowManager
import java.io.File
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.feature.agent.AgentScreen
import com.muyuchat.feature.agent.AgentDecisionItem
import com.muyuchat.feature.agent.BenchmarkHistoryItem
import com.muyuchat.feature.agent.AgentUiState
import com.muyuchat.feature.agent.TuningTrialItem
import com.muyuchat.feature.chat.ChatScreen
import com.muyuchat.feature.chat.ConsumeImeBackHandler
import com.muyuchat.feature.chat.InternalBrowserDialog
import com.muyuchat.feature.chat.AssistantEditorDraft
import com.muyuchat.feature.chat.AssistantMemoryUiItem
import com.muyuchat.feature.chat.AssistantUiItem
import com.muyuchat.feature.chat.ChatHistoryItem
import com.muyuchat.feature.chat.ChatBackgroundScope
import com.muyuchat.feature.chat.ChatBackgroundState
import com.muyuchat.feature.chat.ChatBackgroundScaleMode as UiChatBackgroundScaleMode
import com.muyuchat.feature.chat.FileAssetUiItem
import com.muyuchat.feature.chat.ImageAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiJob
import com.muyuchat.feature.chat.ImageGenerationUiLoraSelection
import com.muyuchat.feature.chat.ImageGenerationUiOptions
import com.muyuchat.feature.chat.ImageGenerationUiPreset
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import com.muyuchat.feature.chat.ImageLibraryBackupUiState
import com.muyuchat.feature.chat.ImageLoraUiItem
import com.muyuchat.feature.chat.ImageTextualInversionUiItem
import com.muyuchat.feature.chat.ImageUpscalerUiItem
import com.muyuchat.feature.chat.ImageUpscaleUiJob
import com.muyuchat.feature.chat.ChatModelChoice
import com.muyuchat.feature.chat.ChatBackendFamily
import com.muyuchat.feature.chat.chatBackendFamilyForRuntime
import com.muyuchat.feature.chat.chatBackendOptionsFor
import com.muyuchat.feature.chat.npuAvailabilityForChatBackend
import com.muyuchat.feature.chat.selectedChatBackendId
import com.muyuchat.feature.chat.withChatBackend
import com.muyuchat.feature.chat.ChatUiState
import com.muyuchat.feature.chat.ChatScreenActions
import com.muyuchat.feature.chat.ChatConversationActions
import com.muyuchat.feature.chat.ChatGenerationActions
import com.muyuchat.feature.chat.ChatContentActions
import com.muyuchat.feature.chat.ChatImageActions
import com.muyuchat.feature.chat.ChatShellActions
import com.muyuchat.feature.chat.ContextSummaryUiItem
import com.muyuchat.feature.chat.ContextSummaryModelOption
import com.muyuchat.feature.chat.KnowledgeBaseUiItem
import com.muyuchat.feature.chat.WorldBookImportScope
import com.muyuchat.feature.chat.WorldBookUiItem
import com.muyuchat.feature.modelhub.ModelHubScreen
import com.muyuchat.feature.modelhub.CloudApiUiState
import com.muyuchat.feature.modelhub.CloudModelUiItem
import com.muyuchat.feature.modelhub.CloudProviderPresetUi
import com.muyuchat.feature.modelhub.LocalImageModelUiItem
import com.muyuchat.feature.modelhub.ModelHubUiState
import com.muyuchat.feature.settings.LocalApiToolScreen
import com.muyuchat.feature.settings.SettingsHubScreen
import com.muyuchat.feature.settings.AppUpdateSettingsUiState
import com.muyuchat.feature.settings.SettingsUiState
import com.muyuchat.feature.settings.WebSearchBackupProviderDraft
import com.muyuchat.feature.settings.WebSearchBackupProviderUiState
import com.muyuchat.feature.settings.WebSearchDiagnosticSourceUiItem
import com.muyuchat.feature.settings.WebSearchDiagnosticUiItem
import com.muyuchat.feature.settings.WebSearchSettingsDraft
import com.muyuchat.feature.settings.WebSearchSettingsUiState
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.AccelerationCapabilityStatus
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.telemetry.SocFamily
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.mca.presentation.deviceAccelerationSummary
import com.muyuchat.mca.presentation.deviceImagePolicy
import com.muyuchat.mca.presentation.deviceImageTierKey
import com.muyuchat.mca.presentation.imageModelPresetsFor
import com.muyuchat.mca.presentation.cloudApiFormats
import com.muyuchat.mca.presentation.cloudImageApiFormats
import com.muyuchat.mca.presentation.cloudProviderPresets
import com.muyuchat.mca.presentation.imageSizePresetsFor
import com.muyuchat.mca.presentation.toUiState
import com.muyuchat.mca.presentation.toAppearance
import com.muyuchat.mca.presentation.toAppearanceScope
import com.muyuchat.mca.presentation.toWebSearchBackupConfigs
import com.muyuchat.mca.presentation.toWebSearchTurnModeLabel
import com.muyuchat.mca.presentation.formatAppUpdateBytes
import com.muyuchat.mca.presentation.formatAssetBytes
import com.muyuchat.mca.presentation.toTuningTrialItems
import com.muyuchat.mca.presentation.settingsUiState
import com.muyuchat.mca.presentation.existingImageModelChoiceIds
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions
import com.muyuchat.mca.presentation.VisionCapabilityUi
import com.muyuchat.mca.presentation.liteRtVisionInputReadyForUi
import com.muyuchat.mca.presentation.liteRtVisionVerifiedReadyForUi
import com.muyuchat.mca.presentation.chatVisionCapability
import com.muyuchat.feature.chat.ChatRoute
import com.muyuchat.feature.chat.ChatRouteInputs

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
        // Keep the chat header in the resized viewport while the IME is open.
        // Some MIUI builds otherwise pan the whole activity upward, which hides
        // the model selector and makes the top bar impossible to operate.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
        pendingWorldBookImportScope = WorldBookScope.fromWireName(
            savedInstanceState?.getString(PENDING_WORLD_BOOK_SCOPE_KEY)
        )
        pendingModelImportTextOnly = savedInstanceState?.getBoolean("pending_model_import_text_only") ?: false
        pendingAssistantCardOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_assistant_card_owner"))
        pendingWorldBookOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_world_book_owner"))
        pendingKnowledgeDocumentOwner = ContentImportOwner.fromJsonOrNull(savedInstanceState?.getString("pending_knowledge_document_owner"))
        val importLauncher = registerForActivityResult(OpenModelDocumentsContract()) { uris ->
            val textOnly = pendingModelImportTextOnly
            val persistent = persistModelImportAccess(uris)
            if (uris.isNotEmpty()) startup.whenReady { it.importModel(uris, textOnly, persistent) }
        }
        val modelDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val textOnly = pendingModelImportTextOnly
            if (uri != null) {
                val persistent = persistModelImportAccess(listOf(uri))
                startup.whenReady { it.importModelDirectory(uri, textOnly, persistent) }
            }
        }
        val offlineTranslationImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                persistModelImportAccess(listOf(uri))
                startup.whenReady { it.importOfflinePromptTranslationBundle(uri) }
            }
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
            val knowledgeBaseId = pendingKnowledgeBaseId
            val owner = pendingKnowledgeDocumentOwner
            pendingKnowledgeBaseId = null
            pendingKnowledgeDocumentOwner = null
            if (uri != null && knowledgeBaseId != null) {
                startup.whenReady { it.importKnowledgeDocument(knowledgeBaseId, uri.toString(), owner) }
            }
        }
        val visionProjectorImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val modelId = pendingVisionProjectorModelId
            pendingVisionProjectorModelId = null
            if (uri != null && modelId != null) startup.whenReady { it.attachVisionProjector(modelId, uri) }
        }
        val diagnosticExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) startup.whenReady { it.exportDiagnosticReport(uri) }
        }
        val chatExportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown")
        ) { uri ->
            val sessionId = pendingChatExportSessionId
            pendingChatExportSessionId = null
            if (uri != null && sessionId != null) {
                startup.whenReady { it.exportChatSession(sessionId, uri) }
            }
        }

        setContent {
            val startupState by startup.state.collectAsStateWithLifecycle()
            val readyModel = startupState.model
            if (readyModel == null) {
                McaTheme {
                    AppStartupScreen(startupState,
                        onRetry = { startup.start() },
                        onSkipScan = { startup.skipModelDiscovery() })
                }
                return@setContent
            }
            val state by readyModel.uiState.collectAsStateWithLifecycle()
            McaTheme {
                McaApp(
                    state = state,
                    onTab = viewModel::selectTab,
                    onImport = { textOnly ->
                        pendingModelImportTextOnly = textOnly
                        importLauncher.launch(Unit)
                    },
                    onImportModelDirectory = { textOnly ->
                        pendingModelImportTextOnly = textOnly
                        modelDirectoryLauncher.launch(null)
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
                    onImportOfflineTranslation = { offlineTranslationImportLauncher.launch(null) },
                    onImportAssistantCardFile = {
                        pendingAssistantCardOwner = viewModel.captureContentImportOwner()
                        assistantCardImportLauncher.launch(Unit)
                    },
                    onImportWorldBookFile = { scope ->
                        pendingWorldBookOwner = viewModel.captureContentImportOwner()
                        pendingWorldBookImportScope = when (scope) {
                            WorldBookImportScope.GLOBAL -> WorldBookScope.GLOBAL
                            WorldBookImportScope.ASSISTANT -> WorldBookScope.ASSISTANT
                            WorldBookImportScope.CHAT -> WorldBookScope.CHAT
                        }
                        worldBookImportLauncher.launch(Unit)
                    },
                    onImportKnowledgeDocument = { knowledgeBaseId ->
                        pendingKnowledgeBaseId = knowledgeBaseId
                        pendingKnowledgeDocumentOwner = viewModel.captureContentImportOwner()
                        knowledgeDocumentImportLauncher.launch(Unit)
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_WORLD_BOOK_SCOPE_KEY, pendingWorldBookImportScope.wireName)
        outState.putString("pending_assistant_card_owner", pendingAssistantCardOwner?.toJsonString())
        outState.putString("pending_world_book_owner", pendingWorldBookOwner?.toJsonString())
        outState.putString("pending_knowledge_document_owner", pendingKnowledgeDocumentOwner?.toJsonString())
        outState.putBoolean("pending_model_import_text_only", pendingModelImportTextOnly)
        super.onSaveInstanceState(outState)
    }

    private fun persistModelImportAccess(uris: List<android.net.Uri>): Boolean {
        // Some document providers grant only temporary access; import still works while it is valid.
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
    state: MainUiState,
    onTab: (AppTab) -> Unit,
    onImport: (Boolean) -> Unit,
    onImportModelDirectory: (Boolean) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onImportOfflineTranslation: () -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onAttachVisionProjector: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportChatSession: (String) -> Unit,
    viewModel: MainViewModel
) {
    // The timestamp formatters in this composable used Locale.getDefault(), which does
    // not read observable state: changing the system language did not recompose these rows and
    // the dates kept the previous locale's formatting. LocalLocale.current is observable, and
    // the lint check NonObservableLocale names this exact replacement. It is read once here in
    // the composable scope because the five uses sit inside map lambdas, where a CompositionLocal
    // cannot be read.
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
    var modelImportTextOnly by rememberSaveable { mutableStateOf(false) }
    val requestModelImport = { modelImportDialogOpen = true }
    if (modelImportDialogOpen) {
        AlertDialog(
            onDismissRequest = { modelImportDialogOpen = false },
            title = { Text("导入本地聊天模型") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("MNN 建议选择完整模型目录或 ZIP，以保留子目录、分词器和权重。GGUF、LiteRT-LM 可直接选择模型文件。导入进度在本地模型页查看。")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = modelImportTextOnly, onCheckedChange = { modelImportTextOnly = it })
                        Text("仅导入 MNN 聊天组件（不启用视觉、音频）")
                    }
                    Text("默认完整导入。纯聊天模式需要完整的聊天权重和分词器，不会补齐缺失文件。")
                    TextButton(onClick = {
                        modelImportDialogOpen = false
                        onImportModelDirectory(modelImportTextOnly)
                    }) { Text("选择模型目录") }
                    TextButton(onClick = {
                        modelImportDialogOpen = false
                        onImport(modelImportTextOnly)
                    }) { Text("选择文件 / ZIP") }
                }
            },
            confirmButton = { TextButton(onClick = { modelImportDialogOpen = false }) { Text("取消") } }
        )
    }
    var appMenuOpen by rememberSaveable { mutableStateOf(false) }
    var internalBrowserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var startModelsInRecommended by rememberSaveable { mutableStateOf(false) }
    var startSettingsInWebSearch by rememberSaveable { mutableStateOf(false) }
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val availableAppUpdate = state.appUpdate.takeIf { update ->
        update.status == AppUpdateStatus.AVAILABLE && update.canDownload
    }
    fun preparePageReturn() {
        if (appMenuOpen) {
            appMenuOpen = true
        }
    }
    fun finishAppMenuReturn() {
        onTab(AppTab.CHAT)
    }
    val preparedImageUi by produceState<Map<String, PreparedLocalImageUi>>(
        initialValue = emptyMap(), state.localImageModels, state.qnnImageVerificationCurrentByModelId
    ) {
        value = withContext(Dispatchers.IO) {
            state.localImageModels.associate { model ->
                model.path to prepareLocalImageUi(model, state.qnnImageVerificationCurrentByModelId[model.id])
            }
        }
    }
    val imageGenerationHistoryById = remember(state.images) { state.images.associate { image ->
        image.id to ImageGenerationHistoryMetadata.fromJsonOrNull(image.generationMetadataJson)
    } }
    // 注：chatVisionCapability 与 generationHistoryInputUris 的派生已在 P3-a 随映射移入
    // ChatUiStateMapping.buildChatUiState（它们只被该映射使用），此处不再重复计算。
    val activeChatSession = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
    val selectedAssistant = state.assistants.firstOrNull { it.id == state.selectedAssistantId }
    val assistantAppearance = selectedAssistant?.appearance?.takeUnless { it.isDefault }
    val sessionAppearance = activeChatSession?.appearanceOverride
    val effectiveChatAppearance = resolveChatAppearance(
        sessionOverride = sessionAppearance,
        assistantAppearance = assistantAppearance,
        globalAppearance = state.globalChatAppearance
    )

    if (availableAppUpdate != null &&
        state.tab != AppTab.SETTINGS &&
        dismissedUpdateVersion != availableAppUpdate.latestVersionName
    ) {
        AlertDialog(
            onDismissRequest = {
                dismissedUpdateVersion = availableAppUpdate.latestVersionName
            },
            title = { Text("发现新版本") },
            text = {
                Text(
                    "MCA ${availableAppUpdate.latestVersionName} 已在官方 GitHub Release 发布。" +
                        "下载后会校验 SHA-256、包名和版本号，再交由系统安装。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissedUpdateVersion = availableAppUpdate.latestVersionName
                    onTab(AppTab.SETTINGS)
                }) {
                    Text("查看更新")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissedUpdateVersion = availableAppUpdate.latestVersionName
                }) {
                    Text("稍后")
                }
            }
        )
    }

    Scaffold { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()

        Box(modifier = modifier) {
            val assistantImageToolPermission = remember(
                state.activeChatSessionId,
                state.selectedChatBackend,
                state.selectedCloudChatModelId,
                state.selectedImageBackend,
                state.selectedLocalImageModelId,
                state.selectedCloudImageModelId,
                state.cloudModels,
                state.localImageModels,
                state.statusMessage
            ) {
                viewModel.chatImageToolPermissionUiState()
            }
            val sessionPresentations = remember(state.chatSessions, state.assistants) {
                state.chatSessions.assistantSessionPresentations(state.assistants)
            }
            ChatRoute(
state = buildChatUiState(
                    state = state,
                    sessionPresentations = sessionPresentations,
                    timestampLocale = timestampLocale,
                    imageGenerationHistoryById = imageGenerationHistoryById,
                    assistantImageToolPermission = assistantImageToolPermission,
                    preparedImageUi = preparedImageUi,
                    generationParamsForChatModel = viewModel::generationParamsForChatModel
                ),
                inputs = ChatRouteInputs(
chatBackground = effectiveChatAppearance.toUiState(),
globalChatBackground = state.globalChatAppearance.toUiState(),
assistantChatBackground = assistantAppearance?.toUiState(),
sessionChatBackground = sessionAppearance?.toUiState(),
hasActiveChatSession = activeChatSession != null,
chatBackgroundImporting = state.chatBackgroundImporting,
assistantMemories = state.assistantMemories.map { memory ->
                    AssistantMemoryUiItem(
                        id = memory.id,
                        assistantId = memory.assistantId,
                        scope = memory.scope,
                        content = memory.content,
                        source = memory.source,
                        createdAt = memory.createdAt
                    )
                },
offlineTranslationStatus = state.offlineTranslationStatus,
offlineTranslationInstalled = state.offlineTranslationInstalled,
offlineTranslationInstalling = state.offlineTranslationInstalling,
offlineTranslationDownloading = state.offlineTranslationDownloading,
offlineTranslationDownloadProgress = state.offlineTranslationDownloadProgress,
appMenuOpen = appMenuOpen,
                ),
                modifier = Modifier.fillMaxSize(),
    actions = mainChatScreenActions(
        viewModel = viewModel,
        state = state,
        onTab = onTab,
        setAppMenuOpen = { appMenuOpen = it },
        setStartModelsInRecommended = { startModelsInRecommended = it },
        setStartSettingsInWebSearch = { startSettingsInWebSearch = it },
        onExportChatSession = onExportChatSession,
        onImportAssistantCardFile = onImportAssistantCardFile,
        onImportKnowledgeDocument = onImportKnowledgeDocument,
        onImportLocalImageModel = onImportLocalImageModel,
        onImportWorldBookFile = onImportWorldBookFile,
        requestModelImport = requestModelImport,
    ),
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
                            timeText = java.text.SimpleDateFormat("HH:mm:ss", timestampLocale).format(java.util.Date(record.time)),
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
                    localStabilitySmokeSummary = state.localStabilitySmokeSummary,
                    params = state.params,
                    profileId = state.profileId,
                    revision = state.revision,
                    profileRecordState = state.profileRecordState,
                    verification = state.verification,
                    engineLifecycle = state.engineLifecycle,
                    tuningJobState = state.tuningJobState,
                    reloadRequired = state.reloadRequired,
                    pending = state.pendingProfile,
                    rollback = state.rollbackProfile,
                    etaSeconds = state.tuningEtaSeconds,
                    phase = state.tuningPhase,
                    candidateProgress = state.tuningCandidateProgress,
                    agentDecisionHistory = state.agentLogs.take(10).map { log ->
                        val recommendation = runCatching { org.json.JSONObject(log.recommendationJson) }.getOrNull()
                        val name = recommendation
                            ?.optJSONObject("recommended")
                            ?.optJSONObject("model")
                            ?.optString("displayName")
                            .orEmpty()
                        val risk = recommendation?.optString("risk").orEmpty()
                        AgentDecisionItem(
                            timeText = java.text.SimpleDateFormat("HH:mm:ss", timestampLocale).format(java.util.Date(log.time)),
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
                onStandardDebug = viewModel::runAgentStandardDebug,
                onDeepDebug = viewModel::runAgentDeepDebug,
                onPowerDebug = viewModel::runAgentPowerDebug,
                onStabilitySmoke = viewModel::runAgentStabilitySmoke,
                onStartTuning = viewModel::startAgentTuning,
                onPauseTuning = viewModel::pauseAgentTuning,
                onResumeTuning = viewModel::resumeAgentTuning,
                onCancelTuning = viewModel::cancelAgentTuning,
                onQueryTuningJob = viewModel::queryAgentTuningJob,
                onApplyPendingProfile = viewModel::applyPendingAgentProfile,
                onDiscardPendingProfile = viewModel::discardPendingAgentProfile,
                onRollbackProfile = viewModel::rollbackAgentProfile,
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
                startInRecommended = startModelsInRecommended,
                state = ModelHubUiState(
                    localModels = state.models,
                    mnnRuntimeAvailable = state.mnnRuntimeAvailable,
                    localImageModels = state.localImageModels.map { model ->
                        val prepared = preparedImageUi[model.path]
                        // Keep the user-facing loading message separate from
                        // the nullable readiness result.  The previous code
                        // replaced null with a non-null message and then used
                        // that value as `readyForGeneration`, making every
                        // image model appear unready even after preparation
                        // succeeded.
                        val readinessMessage = prepared?.readiness ?: "正在后台读取模型配置…"
                        val readyForGeneration = prepared != null && prepared.readiness == null
                        val recommendationId = model.recommendationId ?: model.bundleRoot
                            ?.let { root -> runCatching { localImageBundleManifestFromRoot(File(root)) }.getOrNull()?.recommendationId }
                        LocalImageModelUiItem(
                            id = model.id,
                            displayName = model.displayName,
                            runtimeLabel = model.runtime.label,
                            familyLabel = model.family.label,
                            fileName = model.fileName,
                            storagePath = model.bundleRoot ?: model.path,
                            sizeBytes = model.sizeBytes,
                            imageSize = model.imageSize,
                            componentCount = model.componentCount,
                            readyForGeneration = readyForGeneration,
                            readinessMessage = readinessMessage,
                            readinessLabel = prepared?.label ?: "正在检查",
                            selected = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId,
                            recommendationId = recommendationId,
                            verificationStatus = model.verificationStatus.name,
                            verificationMessage = model.verificationMessage
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
                    downloadTaskId = state.downloadTaskId,
                    downloadedBytes = state.downloadedBytes,
                    downloadTotalBytes = state.downloadTotalBytes,
                    downloadSpeedBytesPerSecond = state.downloadSpeedBytesPerSecond,
                    downloadRemainingSeconds = state.downloadRemainingSeconds,
                    downloadStatus = state.downloadStatus,
                    downloadPhase = state.downloadPhase,
                    downloadFailureSource = state.downloadFailureSource,
                    downloadIntegrityStatus = state.downloadIntegrityStatus,
                    downloadIntegrityMessage = state.downloadIntegrityMessage,
                    downloadExecutionStatus = state.downloadExecutionStatus,
                    downloadExecutionMessage = state.downloadExecutionMessage,
                    importTasks = state.importTasks,
                    deviceTotalRamBytes = state.deviceProfile?.displayTotalRamBytes ?: 0L,
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
                        enabled = state.cloudApiConfig.enabled,
                        apiFormat = state.cloudApiConfig.apiFormat.name,
                        availableFormats = cloudApiFormats().map { it.name to it.label },
                        providerName = state.cloudApiConfig.providerName,
                        displayName = state.cloudApiConfig.displayName,
                        baseUrl = state.cloudApiConfig.baseUrl,
                        apiKey = state.cloudApiConfig.apiKey,
                        chatModel = state.cloudApiConfig.chatModel,
                        supportsVision = state.cloudApiConfig.supportsVision,
                        supportsTools = state.cloudApiConfig.supportsTools,
                        responsesReasoningEnabled = state.cloudApiConfig.responsesReasoningEnabled,
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
                                supportsVision = model.supportsVision,
                                supportsTools = model.supportsTools,
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
                onImportClick = requestModelImport,
                onRepoInputChange = viewModel::onRepoInputChange,
                onFetchRemoteFiles = viewModel::fetchRemoteFiles,
                onHubQueryChange = viewModel::onHubQueryChange,
                onSearchHubModels = viewModel::searchHubModels,
                onFetchHubModelFiles = viewModel::fetchHubModelFiles,
                onShowRecommendedFiles = viewModel::fetchRecommendedFiles,
                onDownloadRecommended = { model ->
                    viewModel.downloadRecommended(model, useAfterDownload = startModelsInRecommended)
                },
                onOpenModelPage = viewModel::openModelScopePage,
                onOpenLocalModel = viewModel::selectLocalImageModel,
                onVerifyLocalModel = viewModel::verifyLocalImageModel,
                onDownload = viewModel::download,
                onLoad = viewModel::loadModel,
                onUnload = viewModel::unloadModel,
                onVerify = viewModel::verifyModel,
                onDelete = viewModel::deleteModel,
                onAttachVisionProjector = { model -> onAttachVisionProjector(model.id) },
                onImportLocalImageModel = onImportLocalImageModel,
                onSelectLocalImageModel = viewModel::selectLocalImageModel,
                onVerifyLocalImageModel = viewModel::verifyLocalImageModel,
                onDeleteLocalImageModel = viewModel::deleteLocalImageModel,
                onRemoveLocalImageModelRecord = viewModel::removeLocalImageModelRecord,
                onCloudEnabledChange = viewModel::updateCloudApiEnabled,
                onBeginAddCloudModel = viewModel::beginAddCloudModel,
                onEditCloudModel = viewModel::editCloudModel,
                onCloudProviderPreset = viewModel::applyCloudProviderPreset,
                onCloudFormatChange = viewModel::updateCloudApiFormat,
                onCloudBaseUrlChange = viewModel::updateCloudBaseUrl,
                onCloudApiKeyChange = viewModel::updateCloudApiKey,
                onCloudChatModelChange = viewModel::updateCloudChatModel,
                onCloudSupportsVisionChange = viewModel::updateCloudSupportsVision,
                onCloudSupportsToolsChange = viewModel::updateCloudSupportsTools,
                onCloudResponsesReasoningChange = viewModel::updateCloudResponsesReasoning,
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
                onPauseDownloads = viewModel::pauseManagedDownloads,
                onResumeDownloads = viewModel::resumeManagedDownloads,
                onPauseImport = viewModel::pauseManagedImport,
                onResumeImport = viewModel::resumeManagedImport,
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
                onClearChatHistory = viewModel::clearChatHistory,
                onClearImageLibrary = viewModel::clearImageLibrary,
                onClearFileLibrary = viewModel::clearFileLibrary,
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
                    viewModel.saveWebSearchConfig(
                        enabled = draft.enabled,
                        provider = draft.provider,
                        endpoint = draft.endpoint,
                        apiKey = draft.apiKey,
                        maxResults = draft.maxResults,
                        fetchPageContent = draft.fetchPageContent,
                        triggerMode = draft.triggerMode,
                        researchMode = draft.researchMode,
                        backupProviders = draft.backupProviders.toWebSearchBackupConfigs()
                    )
                },
                onPreflightWebSearch = { draft: WebSearchSettingsDraft ->
                    viewModel.preflightWebSearchConfig(
                        enabled = draft.enabled,
                        provider = draft.provider,
                        endpoint = draft.endpoint,
                        apiKey = draft.apiKey,
                        maxResults = draft.maxResults,
                        fetchPageContent = draft.fetchPageContent,
                        triggerMode = draft.triggerMode,
                        researchMode = draft.researchMode,
                        backupProviders = draft.backupProviders.toWebSearchBackupConfigs()
                    )
                },
                onTestWebSearch = { query: String, draft: WebSearchSettingsDraft ->
                    viewModel.testWebSearchConfig(
                        query = query,
                        enabled = draft.enabled,
                        provider = draft.provider,
                        endpoint = draft.endpoint,
                        apiKey = draft.apiKey,
                        maxResults = draft.maxResults,
                        fetchPageContent = draft.fetchPageContent,
                        triggerMode = draft.triggerMode,
                        researchMode = draft.researchMode,
                        backupProviders = draft.backupProviders.toWebSearchBackupConfigs()
                    )
                },
                onTestWebSearchTurn = { query: String, draft: WebSearchSettingsDraft, allowPublicCheckSourceForProtocolTest: Boolean ->
                    viewModel.testWebSearchTurn(
                        query = query,
                        enabled = draft.enabled,
                        provider = draft.provider,
                        endpoint = draft.endpoint,
                        apiKey = draft.apiKey,
                        maxResults = draft.maxResults,
                        fetchPageContent = draft.fetchPageContent,
                        triggerMode = draft.triggerMode,
                        researchMode = draft.researchMode,
                        backupProviders = draft.backupProviders.toWebSearchBackupConfigs(),
                        allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
                    )
                },
                onClearWebSearchDiagnostics = viewModel::clearWebSearchDiagnostics,
                onCheckUpdate = viewModel::checkForAppUpdate,
                onDownloadUpdate = viewModel::downloadAppUpdate,
                onInstallUpdate = viewModel::installAppUpdate,
                onOpenRelease = viewModel::openAppUpdateRelease,
                onAutoCheckChanged = viewModel::setAppUpdateAutoCheckEnabled,
                onOpenWebPage = { url -> internalBrowserUrl = url },
                onBack = closePage,
                startInWebSearch = startSettingsInWebSearch,
                modifier = pageModifier
            )
            }

            internalBrowserUrl?.let { url ->
                InternalBrowserDialog(
                    initialUrl = url,
                    onDismiss = { internalBrowserUrl = null }
                )
            }
            state.browserTask?.takeIf { task ->
                task.phase == com.muyuchat.feature.chat.BrowserTaskPhase.QUEUED || task.windowVisible
            }?.let { task ->
                InternalBrowserDialog(
                    task = task,
                    onTaskEvent = viewModel::onBrowserTaskEvent,
                    onDismiss = {
                        viewModel.onBrowserTaskEvent(com.muyuchat.feature.chat.BrowserTaskEvent(
                            task.taskId, task.navigationId, com.muyuchat.feature.chat.BrowserTaskAction.CANCEL
                        ))
                    }
                )
            }
        }
    }

    // Register after page handlers so the IME owns the first back gesture.
    ConsumeImeBackHandler()
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
                    targetValue = widthPx,
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
                        offsetX.snapTo(widthPx * progress.coerceIn(0f, 1f))
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
                        if (offsetX.value < widthPx * 0.08f) {
                            offsetX.snapTo(widthPx * 0.08f)
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

private fun drawerPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 260),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 180))

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
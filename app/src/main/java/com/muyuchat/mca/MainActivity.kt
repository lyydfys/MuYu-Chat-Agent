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
import com.muyuchat.mca.ui.McaTheme
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

private const val PENDING_WORLD_BOOK_SCOPE_KEY = "pending_world_book_scope"

private fun ChatAppearance.toUiState(): ChatBackgroundState = ChatBackgroundState(
    imageUri = backgroundImagePath,
    scrimAlpha = backgroundAlpha,
    blurRadius = backgroundBlur,
    scaleMode = when (backgroundScaleMode) {
        ChatBackgroundScaleMode.CROP -> UiChatBackgroundScaleMode.CROP
        ChatBackgroundScaleMode.FIT -> UiChatBackgroundScaleMode.FIT
        ChatBackgroundScaleMode.CENTER -> UiChatBackgroundScaleMode.CENTER
    }
)

private fun ChatBackgroundState.toAppearance(): ChatAppearance = ChatAppearance(
    backgroundImagePath = imageUri,
    backgroundAlpha = scrimAlpha.coerceIn(0f, 1f),
    backgroundBlur = blurRadius.coerceIn(0f, ChatAppearance.MAX_BLUR),
    backgroundScaleMode = when (scaleMode) {
        UiChatBackgroundScaleMode.CROP -> ChatBackgroundScaleMode.CROP
        UiChatBackgroundScaleMode.FIT -> ChatBackgroundScaleMode.FIT
        UiChatBackgroundScaleMode.CENTER -> ChatBackgroundScaleMode.CENTER
    }
)

private fun ChatBackgroundScope.toAppearanceScope(): ChatAppearanceScope = when (this) {
    ChatBackgroundScope.GLOBAL -> ChatAppearanceScope.GLOBAL
    ChatBackgroundScope.ASSISTANT -> ChatAppearanceScope.ASSISTANT
    ChatBackgroundScope.SESSION -> ChatAppearanceScope.SESSION
}

internal fun existingImageModelChoiceIds(
    localModelIds: Iterable<String>,
    cloudImageModelIds: Iterable<String>
): Set<String> = buildSet {
    localModelIds.forEach { modelId ->
        if (modelId.isNotBlank()) add(MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + modelId)
    }
    cloudImageModelIds.forEach { modelId ->
        if (modelId.isNotBlank()) add(MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + modelId)
    }
}

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
    val chatVisionCapability = state.chatVisionCapability()
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
    val generationHistoryInputUris = retainedGenerationImageContentReferences(
        historyReferences = imageGenerationHistoryById.values
        .filterNotNull()
            .flatMapTo(mutableSetOf()) { history -> history.requiredContentInputReferences() },
        jobInputDrafts = state.imageJobs.mapNotNull { job -> job.spec?.inputDraft }
    )
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
            ChatScreen(
                state = ChatUiState(
                    pendingImageTranslationDraft = state.pendingChatImageTranslationDraft,
                    pendingImageActionDraft = state.pendingChatImageActionDraft,
                    browserTask = state.browserTask,
                    modelLoadMessage = state.modelLoadStage.takeIf { state.busy && state.engineLifecycle == com.muyuchat.feature.agent.AgentEngineLifecycle.LOADING },
                    modelReadinessLabel = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        null
                    } else {
                        state.localChatReadinessLabel()
                    },
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
                                    displayName = model.displayName.ifBlank { model.modelName },
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
                                .map { model ->
                                    val backendFamily = chatBackendFamilyForRuntime(model.runtime.storageValue)
                                    val modelIsLoaded = state.isLocalChatModelLoaded(model.id)
                                    val acceleration = state.deviceProfile?.accelerationProfile
                                    val npuAvailable: Boolean? = when (backendFamily) {
                                        // LiteRT Qualcomm assets are staged on
                                        // first NPU load, so the generic QNN
                                        // inspector may still be looking at
                                        // GenieX's APK runtime here.  Treat an
                                        // exact packaged V73/V75/V79/V81
                                        // transport as actionable and let the
                                        // LiteRT native load perform the final
                                        // graph compatibility check.
                                        ChatBackendFamily.LITERT_LM,
                                        ChatBackendFamily.QAIRT -> npuAvailabilityForChatBackend(
                                            family = backendFamily,
                                            chipsetCode = acceleration?.chipsetCode,
                                            qnnRuntimeUsableForSmoke = acceleration?.qnnRuntime?.usableForSmoke,
                                            packagedLiteRtTransportAvailable =
                                                backendFamily == ChatBackendFamily.LITERT_LM &&
                                                    LiteRtQualcommRuntimeStager.variantForSocModel(
                                                        acceleration?.chipsetCode
                                                    ) != null
                                        )
                                        else -> null
                                    }
                                    val backendOptions = chatBackendOptionsFor(
                                        family = backendFamily,
                                        npuAvailable = npuAvailable,
                                        gpuAvailable = if (
                                            backendFamily == ChatBackendFamily.LLAMA_CPP && modelIsLoaded
                                        ) {
                                            state.stats.gpuOffloadSupported
                                        } else {
                                            null
                                        }
                                    )
                                    val selectedBackend = selectedChatBackendId(
                                        family = backendFamily,
                                        params = if (modelIsLoaded) {
                                            state.params
                                        } else {
                                            viewModel.generationParamsForChatModel(model.id)
                                        },
                                        stats = if (model.id == state.loadedModelId) state.stats else com.muyuchat.core.engine.RuntimeStats()
                                    )
                                    val customGpuLayers = if (backendFamily == ChatBackendFamily.LLAMA_CPP) {
                                        LlamaAdvancedParams.parse(
                                            if (modelIsLoaded) state.params.advancedJson
                                            else viewModel.generationParamsForChatModel(model.id).advancedJson
                                        ).params?.nGpuLayers?.takeIf { it >= 0 }
                                    } else {
                                        null
                                    }
                                    ChatModelChoice(
                                        id = model.id,
                                        displayName = model.displayName,
                                        quant = model.quant,
                                        sizeBytes = model.sizeBytes,
                                        loaded = modelIsLoaded,
                                        subtitle = listOfNotNull(
                                            model.runtime.label,
                                            state.localChatReadinessLabel().takeIf {
                                                state.selectedChatBackend == ChatBackend.LOCAL &&
                                                    model.id == state.loadedModelId
                                            }
                                        ).joinToString(" · "),
                                        backendOptions = backendOptions,
                                        selectedBackendId = selectedBackend,
                                        customGpuLayers = customGpuLayers
                                    )
                                }
                        )
                    },
                    imageModels = buildList {
                        addAll(
                            state.localImageModels
                                .sortedByDescending { if (it.id == state.selectedLocalImageModelId) 1 else 0 }
                                .mapNotNull { model ->
                                    val prepared = preparedImageUi[model.path] ?: return@mapNotNull null
                                    val imageCapabilities = prepared.capabilities ?: return@mapNotNull null
                                    val readiness = prepared.readiness
                                    val readinessLabel = prepared.label
                                    val imageDefaults = imageCapabilities.executionDefaults
                                    ChatModelChoice(
                                        id = MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.displayName,
                                        quant = if (readiness == null) "本地生图" else readinessLabel,
                                        sizeBytes = model.sizeBytes,
                                        loaded = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId,
                                        subtitle = if (readiness == null) {
                                            "${model.family.label} · ${model.runtime.label}"
                                        } else {
                                            "${model.family.label} · $readinessLabel"
                                        },
                                        cloud = false,
                                        supportedImageTaskModes = imageCapabilities.supportedTaskModes,
                                        supportsImageNegativePrompt = imageCapabilities.supportsNegativePrompt,
                                        supportsImageClipSkip = imageCapabilities.supportsClipSkip,
                                        supportsImageVaeTiling = imageCapabilities.supportsVaeTiling,
                                        supportsImageTextualInversion = imageCapabilities.supportsTextualInversion,
                                        supportedImageTextualInversionFormats =
                                            imageCapabilities.supportedTextualInversionFormats,
                                        supportsImageUltraFix = imageCapabilities.supportsUltraFix,
                                        supportsImageLora = imageCapabilities.supportsLora,
                                        supportsImageLivePreview = imageCapabilities.supportsLivePreview,
                                        imagePreviewMode = imageCapabilities.previewMode,
                                        imageDefaultPreviewInterval = imageCapabilities.defaultPreviewInterval,
                                        maxImageBatchCount = imageCapabilities.maxBatchCount,
                                        imageDefaultWidth = imageDefaults.width,
                                        imageDefaultHeight = imageDefaults.height,
                                        imageDefaultSteps = imageDefaults.steps,
                                        imageMinSteps = imageDefaults.minSteps,
                                        imageMaxSteps = imageDefaults.maxSteps,
                                        imageDefaultCfgScale = imageDefaults.cfgScale,
                                        imageDefaultSeed = imageDefaults.seed,
                                        imageDefaultSampler = imageDefaults.sampler,
                                        imageMinWidth = imageDefaults.minWidth,
                                        imageMaxWidth = imageDefaults.maxWidth,
                                        imageMinHeight = imageDefaults.minHeight,
                                        imageMaxHeight = imageDefaults.maxHeight,
                                        imageWidthMultiple = imageDefaults.widthMultiple,
                                        imageHeightMultiple = imageDefaults.heightMultiple,
                                        imageUltraFixMinWidth = imageDefaults.ultraFixMinWidth,
                                        imageUltraFixMaxWidth = imageDefaults.ultraFixMaxWidth,
                                        imageUltraFixMinHeight = imageDefaults.ultraFixMinHeight,
                                        imageUltraFixMaxHeight = imageDefaults.ultraFixMaxHeight,
                                        imageUltraFixWidthMultiple = imageDefaults.ultraFixWidthMultiple,
                                        imageUltraFixHeightMultiple = imageDefaults.ultraFixHeightMultiple,
                                        imageUltraFixRequiredTileSize =
                                            imageDefaults.ultraFixRequiredTileSize,
                                        imageSupportedSamplers = imageDefaults.supportedSamplers,
                                        imageImg2ImgSupportedSamplers =
                                            imageDefaults.img2ImgSupportedSamplers
                                    )
                                }
                        )
                        addAll(
                            state.cloudModels
                                .filter { it.kind == CloudModelKind.IMAGE && it.configured }
                                .sortedByDescending { it.updatedAt }
                                .map { model ->
                                    ChatModelChoice(
                                        id = MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.displayName.ifBlank { model.modelName },
                                        quant = "云端生图",
                                        loaded = state.selectedImageBackend == ImageBackend.CLOUD && model.id == state.selectedCloudImageModelId,
                                        subtitle = model.protocolLabel,
                                        cloud = true
                                    )
                            }
                        )
                    },
                    existingImageModelIds = existingImageModelChoiceIds(
                        localModelIds = state.localImageModels.map { model -> model.id },
                        cloudImageModelIds = state.cloudModels
                            .asSequence()
                            .filter { model -> model.kind == CloudModelKind.IMAGE }
                            .map { model -> model.id }
                            .asIterable()
                    ),
                    assistants = state.assistants.map { assistant ->
                        val assistantParams = GenerationParams.fromJson(assistant.paramsJson, state.params)
                        AssistantUiItem(
                            id = assistant.id,
                            name = assistant.name,
                            avatar = assistant.avatar,
                            tag = assistant.tag,
                            systemPrompt = assistant.systemPrompt,
                            modelSummary = when (assistant.defaultModelMode) {
                                "local" -> state.models.firstOrNull { it.id == assistant.defaultModelId }?.displayName ?: "指定本地模型"
                                "cloud" -> state.cloudModels.firstOrNull {
                                    it.id == assistant.defaultModelId && it.kind == CloudModelKind.CHAT
                                }?.modelName ?: "指定云端模型"
                                else -> "跟随当前模型"
                            },
                            defaultModelMode = assistant.defaultModelMode,
                            defaultModelId = assistant.defaultModelId,
                            temperature = assistantParams.temperature,
                            topP = assistantParams.topP,
                            nPredict = assistantParams.nPredict,
                            reasoningMode = assistantParams.reasoningMode,
                            memoryEnabled = assistant.memoryEnabled,
                            memorySummaryInterval = assistant.memorySummaryInterval,
                            characterCardImported = !assistant.characterCardJson.isNullOrBlank(),
                            webSearchEnabled = assistant.webSearchEnabled,
                            fileContextEnabled = assistant.fileContextEnabled,
                            selected = assistant.id == state.selectedAssistantId,
                            exportJson = assistant.toJson().toString(2),
                            topK = assistantParams.topK,
                            minP = assistantParams.minP,
                            repeatPenalty = assistantParams.repeatPenalty,
                            presencePenalty = assistantParams.presencePenalty,
                            frequencyPenalty = assistantParams.frequencyPenalty,
                            stopWords = assistantParams.stopWords
                        )
                    },
                    worldBooks = state.worldBooks
                        .filter { book ->
                            when (book.scope) {
                                WorldBookScope.GLOBAL -> true
                                WorldBookScope.ASSISTANT -> book.assistantId == state.selectedAssistantId
                                WorldBookScope.CHAT -> book.chatSessionId == state.activeChatSessionId
                            }
                        }
                        .map { book ->
                            WorldBookUiItem(
                                id = book.id,
                                name = book.name,
                                entryCount = book.entries.size,
                                scopeLabel = when (book.scope) {
                                    WorldBookScope.GLOBAL -> "全局"
                                    WorldBookScope.ASSISTANT -> "当前角色"
                                    WorldBookScope.CHAT -> "当前对话"
                                },
                                enabled = book.enabled,
                                constantEntryCount = book.entries.count { it.enabled && it.constant },
                                keywordEntryCount = book.entries.count { it.enabled && !it.constant }
                            )
                        },
                    knowledgeBases = state.knowledgeBases.map { knowledgeBase ->
                        KnowledgeBaseUiItem(
                            id = knowledgeBase.id,
                            name = knowledgeBase.name,
                            description = knowledgeBase.description,
                            selected = knowledgeBase.id in state.selectedKnowledgeBaseIds,
                            indexStateLabel = when (knowledgeBase.indexState) {
                                KnowledgeIndexState.LEXICAL_READY -> "本地检索已就绪"
                                KnowledgeIndexState.EMBEDDING_PENDING -> "等待向量索引"
                                KnowledgeIndexState.EMBEDDING_READY -> "向量索引已就绪"
                                KnowledgeIndexState.REINDEX_REQUIRED -> "需要重建索引"
                                KnowledgeIndexState.FAILED -> "索引失败"
                            },
                            documentCount = state.knowledgeDocumentCounts[knowledgeBase.id] ?: 0,
                            importing = knowledgeBase.id in state.knowledgeBaseImportingIds
                        )
                    },
                    statusMessage = state.statusMessage,
                    selectedAssistantId = state.selectedAssistantId,
                    generationHistoryInputUris = generationHistoryInputUris,
                    images = state.images.map { image ->
                        val generation = imageGenerationHistoryById[image.id]
                        val sourceGeneration = generation?.takeIf { it.sourceGenerationAvailable }
                        val executionFacets = sourceGeneration?.executionFacets()
                        ImageAssetUiItem(
                            id = image.id,
                            name = image.name,
                            uriString = image.uriString,
                            source = image.source,
                            prompt = image.prompt,
                            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(image.createdAt)),
                            createdAtMillis = image.createdAt,
                            sizeText = formatAssetBytes(image.sizeBytes),
                            width = image.width,
                            height = image.height,
                            sizeBytes = image.sizeBytes,
                            upscaleTargetScale = generation?.upscaleHistory?.lastOrNull()?.targetScale,
                            generationDetails = generation?.displayDetails().orEmpty(),
                            generationPrompt = sourceGeneration?.requestPrompt.orEmpty(),
                            generationModelId = sourceGeneration?.modelId.orEmpty(),
                            generationModelName = sourceGeneration?.modelName.orEmpty(),
                            generationTaskMode = sourceGeneration?.inputDraft?.taskMode?.wireName.orEmpty(),
                            generationOperation = sourceGeneration?.operationFacet()?.wireName.orEmpty(),
                            generationSampler = sourceGeneration?.options?.sampleMethod.orEmpty(),
                            generationRuntime = executionFacets?.runtimeLabel.orEmpty(),
                            generationDevice = executionFacets?.deviceLabel.orEmpty(),
                            parameterShareJson = sourceGeneration?.toShareJson().orEmpty(),
                            generationPreset = sourceGeneration?.let { metadata ->
                                ImageGenerationUiPreset(
                                    prompt = metadata.requestPrompt,
                                    taskMode = ImageGenerationUiTaskMode.entries.firstOrNull { mode ->
                                        mode.wireName == metadata.inputDraft.taskMode.wireName
                                    },
                                    negativePrompt = metadata.options.negativePrompt,
                                    width = metadata.options.width,
                                    height = metadata.options.height,
                                    steps = metadata.options.steps,
                                    cfgScale = metadata.options.cfgScale,
                                    seed = metadata.options.seed,
                                    sampleMethod = metadata.options.sampleMethod,
                                    clipSkip = metadata.options.clipSkip,
                                    batchCount = metadata.options.batchCount,
                                    vaeTileSize = metadata.options.vaeTiling?.tileSize,
                                    vaeTileOverlap = metadata.options.vaeTiling?.overlap,
                                    loras = metadata.loras.map { selection ->
                                        ImageGenerationUiLoraSelection(
                                            id = selection.id,
                                            multiplier = selection.multiplier
                                        )
                                    },
                                    textualInversionIds = metadata.options.textualInversionIds,
                                    strength = metadata.inputDraft.strength,
                                    controlStrength = metadata.inputDraft.controlStrength,
                                    ultraFix = metadata.options.ultraFix?.let { ultraFix ->
                                        com.muyuchat.feature.chat.ImageGenerationUiUltraFixOptions(
                                            targetWidth = ultraFix.targetWidth,
                                            targetHeight = ultraFix.targetHeight,
                                            strength = ultraFix.strength,
                                            inversionSteps = ultraFix.inversionSteps,
                                            refinementSteps = ultraFix.refinementSteps,
                                            tileSize = ultraFix.tileSize,
                                            overlap = ultraFix.overlap
                                        )
                                    }
                                )
                            },
                            favorite = image.favorite,
                            canRecreate = sourceGeneration?.canRecreate() == true
                        )
                    },
                    imageLibraryBackup = ImageLibraryBackupUiState(
                        running = state.imageLibraryBackup.running,
                        importing = state.imageLibraryBackup.importing,
                        done = state.imageLibraryBackup.done,
                        total = state.imageLibraryBackup.total,
                        message = state.imageLibraryBackup.message,
                        failed = state.imageLibraryBackup.failed
                    ),
                    imageLoras = state.localImageLoras.map { adapter ->
                        ImageLoraUiItem(
                            id = adapter.id,
                            name = adapter.name,
                            sizeText = formatAssetBytes(adapter.sizeBytes),
                            sha256 = adapter.sha256,
                            inUse = adapter.id in state.activeLocalImageLoraIds
                        )
                    },
                    imageLoraImporting = state.localImageLoraImporting,
                    imageLoraMessage = state.localImageLoraMessage,
                    imageTextualInversions = state.localImageTextualInversions.map { artifact ->
                        ImageTextualInversionUiItem(
                            id = artifact.id,
                            name = artifact.name,
                            trigger = artifact.trigger,
                            format = artifact.format.wireName,
                            sizeText = formatAssetBytes(artifact.sizeBytes),
                            sha256 = artifact.sha256,
                            inUse = artifact.id in state.activeLocalImageTextualInversionIds ||
                                artifact.id in state.deletingLocalImageTextualInversionIds
                        )
                    },
                    imageTextualInversionImporting = state.localImageTextualInversionLoading ||
                        state.localImageTextualInversionImporting,
                    imageTextualInversionMessage = state.localImageTextualInversionMessage,
                    imageUpscalers = state.localImageUpscalers.map { upscaler ->
                        ImageUpscalerUiItem(
                            id = upscaler.id,
                            name = upscaler.name,
                            sizeText = formatAssetBytes(upscaler.sizeBytes),
                            sha256 = upscaler.sha256,
                            selected = upscaler.id == state.selectedLocalImageUpscalerId,
                            inUse = upscaler.id == state.activeLocalImageUpscalerId,
                            deleting = upscaler.id == state.localImageUpscalerDeletingId
                        )
                    },
                    selectedImageUpscalerId = state.selectedLocalImageUpscalerId,
                    imageUpscalerImporting = state.localImageUpscalerImporting,
                    imageUpscalerMessage = state.localImageUpscalerMessage,
                    imageUpscaleJob = state.imageUpscaleJob?.let { job ->
                        ImageUpscaleUiJob(
                            id = job.id,
                            sourceImageId = job.spec.sourceImageSnapshot.id,
                            upscalerId = job.spec.upscalerSnapshot.id,
                            upscalerName = job.spec.upscalerSnapshot.name,
                            targetScale = job.spec.targetScale,
                            statusLabel = job.status.label,
                            running = !job.status.terminal,
                            failed = job.status.failed,
                            terminal = job.status.terminal,
                            resultImageAssetId = job.resultImageAssetId,
                            message = job.message
                        )
                    },
                    deferGenerationImageGrantRelease = state.deferGenerationImageGrantRelease,
                    files = state.files.map { file ->
                        FileAssetUiItem(
                            id = file.id,
                            name = file.name,
                            mimeType = file.mimeType,
                            preview = file.preview,
                            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(file.createdAt)),
                            sizeText = formatAssetBytes(file.sizeBytes),
                            truncated = file.truncated
                        )
                    },
                    imageJobs = state.imageJobs.map { job ->
                        ImageGenerationUiJob(
                            id = job.id,
                            prompt = job.prompt,
                            statusLabel = job.status.label,
                            modelId = job.modelId,
                            modelName = job.modelName,
                            modelIsCloud = job.backend == ImageBackend.CLOUD,
                            chatSessionId = job.spec?.chatSessionId,
                            chatMessageId = job.spec?.chatMessageId,
                            imageAssetId = job.imageAssetId,
                            imageAssetIds = job.imageAssetIds,
                            completedProgressCount = job.completedProgressCount,
                            expectedOutputCount = job.spec?.options?.batchCount ?: 1,
                            previewUriString = job.previewUriString,
                            previewMode = job.previewMode,
                            previewStep = job.previewStep,
                            previewRevision = job.previewRevision,
                            previewWidth = job.previewWidth,
                            previewHeight = job.previewHeight,
                            failed = job.status.failed,
                            terminal = job.status.terminal,
                            message = job.message,
                            startedAtMillis = job.startedAtMillis,
                            startedAtElapsedMs = job.startedAtElapsedMs
                        )
                    },
                    activeConversationId = state.activeChatSessionId,
                    input = state.input,
                    isGenerating = state.isGenerating,
                    generationPhase = state.generationPhase,
                    generationTokenProgress = state.generationTokenProgress,
                    generationPersistProgress = state.generationPersistProgress,
                    generationStats = state.generationStats,
                    promptContextUsage = state.promptContextUsage,
                    contextCompressionThresholdPercent = state.contextCompressionThresholdPercent,
                    contextCompressionPending = state.contextCompressionPending,
                    contextSummaryModelSelection = state.contextSummaryModelSelection,
                    contextSummaryModels = buildList {
                        add(ContextSummaryModelOption("current", "当前聊天模型"))
                        add(ContextSummaryModelOption("deterministic", "规则整理"))
                        add(ContextSummaryModelOption("local", "已加载的本地模型"))
                        state.cloudModels.filter { it.kind == CloudModelKind.CHAT && it.configured }.forEach { model ->
                            add(ContextSummaryModelOption("cloud:${model.id}", "云端 · ${model.displayName}"))
                        }
                    },
                    contextSummaryHistory = state.chatSessions
                        .firstOrNull { it.id == state.activeChatSessionId }
                        ?.contextSummaries.orEmpty().map { summary ->
                            ContextSummaryUiItem(
                                version = summary.version,
                                source = summary.source,
                                text = summary.text,
                                sourceMessageIds = summary.sourceMessageIds,
                                active = summary.active,
                                createdAt = summary.createdAt,
                                coverageLimited = summary.structuredSummary.coverageLimited,
                                evidenceCount = summary.structuredSummary.evidence.size,
                                omittedExcerptCount = summary.structuredSummary.omittedExcerptCount,
                                omittedCharacterCount = summary.structuredSummary.omittedCharacterCount,
                                modelIdentity = summary.summaryModelIdentity,
                                diagnostic = summary.summaryDiagnostic
                            )
                        },
                    canUndoContextSummary = state.chatSessions
                        .firstOrNull { it.id == state.activeChatSessionId }
                        ?.contextSummaries?.any { it.active } == true,
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
                    selectedModelRuntimeLabel = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudChatModelId }?.apiFormat?.label
                    } else {
                        state.models.firstOrNull { it.id == state.loadedModelId }?.runtime?.label
                    },
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
                    assistantImageToolAvailable = assistantImageToolPermission.available,
                    assistantImageToolUnavailableReason = assistantImageToolPermission.unavailableReason,
                    assistantImageToolAutoApproval = assistantImageToolPermission.autoApproval,
                    assistantImageToolCanChange = assistantImageToolPermission.canChange,
                    stats = state.stats,
                    apiEnabled = state.apiEnabled,
                    restEnabled = state.restEnabled,
                    generationParams = state.params,
                    reasoningMode = state.params.reasoningMode,
                    webSearchEnabled = state.webSearchConfig.enabled,
                    webSearchConfigured = state.webSearchConfig.realSearchConfigured,
                    webSearchEnabledForTurn = state.webSearchTurnMode == WebSearchTurnMode.ON ||
                        (state.webSearchTurnMode == WebSearchTurnMode.FOLLOW &&
                            state.assistants.firstOrNull { it.id == state.selectedAssistantId }?.webSearchEnabled == true),
                    webSearchStatusMessage = state.webSearchStatusMessage,
                    webSearchTurnModeLabel = state.toWebSearchTurnModeLabel(),
                    webSearchResearchMode = (state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).name,
                    webSearchResearchModeLabel = (state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).label,
                    webSearchResearchOverridden = state.webSearchResearchModeOverride != null,
                    webSearchProviderLabel = buildString {
                        if (state.webSearchConfig.realSearchConfigured) {
                            append(state.webSearchConfig.realSearchProviderLabel.ifBlank { state.webSearchConfig.providerLabel })
                            append(" · ")
                            append(state.webSearchConfig.triggerMode.label)
                        } else if (state.webSearchConfig.isPublicCheckSource) {
                            append("协议自检源 · 网页直读")
                        } else if (state.webSearchConfig.enabled) {
                            append("网页直读 · ")
                            append(state.webSearchConfig.triggerMode.label)
                        } else {
                            append(state.webSearchConfig.providerLabel)
                        }
                    },
                    visionCapabilityLabel = chatVisionCapability.label,
                    visionCapabilityDetail = chatVisionCapability.detail,
                    visionCapabilityReady = chatVisionCapability.ready
                ),
                chatBackground = effectiveChatAppearance.toUiState(),
                globalChatBackground = state.globalChatAppearance.toUiState(),
                assistantChatBackground = assistantAppearance?.toUiState(),
                sessionChatBackground = sessionAppearance?.toUiState(),
                hasActiveChatSession = activeChatSession != null,
                chatBackgroundImporting = state.chatBackgroundImporting,
                onChatBackgroundChange = { scope, appearance ->
                    viewModel.setChatAppearance(scope.toAppearanceScope(), appearance?.toAppearance())
                },
                onChatBackgroundImageSelected = { scope, uri ->
                    viewModel.importChatBackground(scope.toAppearanceScope(), uri)
                },
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
                onInputChange = viewModel::onInputChange,
                onDismissStatusMessage = viewModel::clearStatusMessage,
                onSend = viewModel::sendMessage,
                onSendImagePrompt = viewModel::sendChatImagePrompt,
                onResolveImageActionDraft = viewModel::resolveChatImageActionDraft,
                onSearchContextSummarySources = viewModel::contextSummaryEvidenceForQuery,
                onBrowserTaskEvent = viewModel::onBrowserTaskEvent,
                onSetAssistantImageToolAutoApproval = viewModel::setAssistantImageToolAutoApproval,
                onApproveChatImageRequest = viewModel::approveChatImageRequest,
                onRejectChatImageRequest = viewModel::rejectChatImageRequest,
                onCancelChatImageGeneration = viewModel::cancelChatImageGeneration,
                onContinueAssistantImageTurn = viewModel::continueAssistantImageTurn,
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
                onTogglePinMessage = viewModel::toggleMessagePinned,
                onDeleteLastTurn = viewModel::deleteLastConversationTurn,
                onUploadFile = viewModel::attachFile,
                onUseImageAsset = viewModel::useImageAsset,
                onDeleteImageAsset = viewModel::deleteImageAsset,
                onDeleteImageAssets = viewModel::deleteImageAssets,
                onSetImageAssetFavorite = viewModel::setImageAssetFavorite,
                onExportImageLibraryBackup = viewModel::exportImageLibraryBackup,
                onImportImageLibraryBackup = viewModel::importImageLibraryBackup,
                onCancelImageLibraryBackup = viewModel::cancelImageLibraryBackup,
                onImportImageLora = viewModel::importLocalImageLora,
                onDeleteImageLora = viewModel::deleteLocalImageLora,
                onImportImageTextualInversion = viewModel::importLocalImageTextualInversion,
                onDeleteImageTextualInversion = viewModel::deleteLocalImageTextualInversion,
                onImportImageUpscaler = viewModel::importLocalImageUpscaler,
                onDeleteImageUpscaler = viewModel::deleteLocalImageUpscaler,
                onSelectImageUpscaler = viewModel::selectLocalImageUpscaler,
                onUpscaleImageAsset = viewModel::upscaleImageAsset,
                onCancelImageUpscale = viewModel::cancelImageUpscale,
                onUseFileAsset = viewModel::useFileAsset,
                onDeleteFileAsset = viewModel::deleteFileAsset,
                onGenerateImagePrompt = generateImage@{ prompt, uiOptions ->
                    val loras = uiOptions.loras.mapNotNull { selection ->
                        state.localImageLoras
                            .firstOrNull { adapter -> adapter.id == selection.id }
                            ?.toPrepared(selection.multiplier)
                    }
                    if (loras.size != uiOptions.loras.size) {
                        viewModel.reportMissingLocalImageLoraSelection()
                        return@generateImage false
                    }
                    viewModel.generateImageAsset(
                        prompt = prompt,
                        inputDraft = LocalImageInputDraft(
                            taskMode = LocalImageTaskMode.fromWireName(uiOptions.taskMode.wireName),
                            inputImageReference = uiOptions.inputImageUri,
                            maskImageReference = uiOptions.maskImageUri,
                            controlImageReference = uiOptions.controlImageUri,
                            strength = uiOptions.strength,
                            controlStrength = uiOptions.controlStrength
                        ),
                        options = LocalImageGenerationOptions(
                            negativePrompt = uiOptions.negativePrompt,
                            width = uiOptions.width,
                            height = uiOptions.height,
                            steps = uiOptions.steps,
                            seed = uiOptions.seed,
                            cfgScale = uiOptions.cfgScale,
                            sampleMethod = uiOptions.sampleMethod,
                            clipSkip = uiOptions.clipSkip,
                            batchCount = uiOptions.batchCount,
                            loras = loras,
                            vaeTiling = uiOptions.vaeTileSize?.let { tileSize ->
                                LocalImageVaeTilingOptions(
                                    tileSize = tileSize,
                                    overlap = uiOptions.vaeTileOverlap ?: 0.5
                                )
                            },
                            textualInversionIds = uiOptions.textualInversionIds,
                            ultraFix = uiOptions.ultraFix?.let { request ->
                                LocalImageUltraFixOptions(
                                    targetWidth = request.targetWidth,
                                    targetHeight = request.targetHeight,
                                    strength = request.strength,
                                    inversionSteps = request.inversionSteps,
                                    refinementSteps = request.refinementSteps,
                                    tileSize = request.tileSize,
                                    overlap = request.overlap,
                                )
                            },
                            preview = uiOptions.toLocalImagePreviewOptions()
                        )
                    )
                },
                onMeasureImagePromptTokens = viewModel::measureImagePromptTokens,
                onRetryImageGeneration = viewModel::retryImageGeneration,
                onRetryChatImageRequest = viewModel::retryChatImageGeneration,
                onRecreateImageAsset = viewModel::recreateImageAsset,
                onCancelImageGeneration = viewModel::cancelImageGeneration,
                releaseGenerationImageGrantsIfCoordinatorIdle =
                    viewModel::releaseGenerationImageGrantsIfCoordinatorIdle,
                onSelectImageModel = viewModel::selectImageGenerationModel,
                onChatImageLoraSelectionChange = { selections ->
                    viewModel.updateChatImageLoraSelection(
                        selections?.map { selection -> selection.id to selection.multiplier }
                    )
                },
                onConfirmImageTranslationDraft = viewModel::confirmChatImageTranslationDraft,
                onCancelImageTranslationDraft = viewModel::cancelChatImageTranslationDraft,
                onModelBackendChange = { modelId, backendId ->
                    viewModel.updateModelBackendPreference(modelId, backendId)
                },
                onGenerationParamsChange = viewModel::updateParams,
                onReasoningModeChange = viewModel::updateReasoningMode,
                onCloudReasoningModeLocked = viewModel::showCloudReasoningModeLocked,
                onToggleWebSearchForTurn = viewModel::toggleWebSearchForNextTurn,
                onSelectWebSearchResearchMode = viewModel::selectWebSearchResearchModeForNextTurn,
                onOpenBrowserTask = viewModel::startBrowserTask,
                onLoadModel = viewModel::selectChatModel,
                onOpenAgent = { onTab(AppTab.AGENT) },
                onOpenModels = { startModelsInRecommended = false; onTab(AppTab.MODELS) },
                onOpenRecommendedModels = {
                    startModelsInRecommended = true
                    onTab(AppTab.MODELS)
                },
                onImportChatModel = requestModelImport,
                onImportImageModel = onImportLocalImageModel,
                offlineTranslationStatus = state.offlineTranslationStatus,
                offlineTranslationInstalling = state.offlineTranslationInstalling,
                offlineTranslationDownloading = state.offlineTranslationDownloading,
                offlineTranslationDownloadProgress = state.offlineTranslationDownloadProgress,
                onDownloadOfflineTranslation = viewModel::downloadOfflinePromptTranslationBundle,
                onCancelOfflineTranslationDownload = viewModel::cancelOfflinePromptTranslationDownload,
                onOpenApi = { onTab(AppTab.API) },
                onOpenSettings = {
                    startSettingsInWebSearch = false
                    onTab(AppTab.SETTINGS)
                },
                onRequestContextCompression = viewModel::requestContextCompression,
                onUndoContextSummary = viewModel::undoContextSummary,
                onSetContextCompressionThreshold = viewModel::setContextCompressionThreshold,
                onSetContextSummaryModel = viewModel::setContextSummaryModel,
                onOpenWebSearchSettings = {
                    startSettingsInWebSearch = true
                    onTab(AppTab.SETTINGS)
                },
                onSaveAssistant = { draft: AssistantEditorDraft ->
                    viewModel.saveAssistantProfile(
                        id = draft.id,
                        name = draft.name,
                        avatar = draft.avatar,
                        tag = draft.tag,
                        systemPrompt = draft.systemPrompt,
                        defaultModelMode = draft.defaultModelMode,
                        defaultModelId = draft.defaultModelId?.removePrefix(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX),
                        temperature = draft.temperature,
                        topP = draft.topP,
                        nPredict = draft.nPredict,
                        topK = draft.topK,
                        minP = draft.minP,
                        repeatPenalty = draft.repeatPenalty,
                        presencePenalty = draft.presencePenalty,
                        frequencyPenalty = draft.frequencyPenalty,
                        stopWords = draft.stopWords,
                        reasoningMode = draft.reasoningMode,
                        memoryEnabled = draft.memoryEnabled,
                        memorySummaryInterval = draft.memorySummaryInterval,
                        webSearchEnabled = draft.webSearchEnabled,
                        fileContextEnabled = draft.fileContextEnabled
                    )
                },
                onUpsertAssistantMemory = viewModel::upsertAssistantMemory,
                onDeleteAssistantMemory = viewModel::deleteAssistantMemory,
                onSelectAssistant = viewModel::selectAssistant,
                onDeleteAssistant = viewModel::deleteAssistant,
                onImportAssistantCard = viewModel::importAssistantCard,
                onImportAssistantCardFile = onImportAssistantCardFile,
                onImportWorldBookFile = onImportWorldBookFile,
                onDeleteWorldBook = viewModel::deleteWorldBook,
                onCreateKnowledgeBase = { name -> viewModel.createKnowledgeBase(name) },
                onImportKnowledgeDocument = onImportKnowledgeDocument,
                onSetKnowledgeBaseSelected = viewModel::setKnowledgeBaseSelected,
                onDeleteKnowledgeBase = viewModel::deleteKnowledgeBase,
                appMenuOpen = appMenuOpen,
                onAppMenuOpenChange = { appMenuOpen = it },
                modifier = Modifier.fillMaxSize()
            )

            // Keep the status indicator compact and out of the app drawer. It samples only
            // while a model/image job is active; the composable also pauses when the activity
            // is backgrounded via repeatOnLifecycle.  It is positioned below the chat header
            // so the overlay cannot intercept the model selector or send controls.
            SystemLoadCompactCard(
                visible = state.tab == AppTab.CHAT && !appMenuOpen &&
                    (state.busy || state.isGenerating || state.imageJobs.any { !it.status.terminal }),
                nativeStatsJson = state.nativeStatsJson,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 158.dp, start = 12.dp, end = 12.dp)
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
    targetOffsetX = { it }
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
    loadedModelId = loadedModelId,
    nativeStatsJson = nativeStatsJson,
    diagnosticReport = diagnosticReport,
    chatSessionCount = chatSessions.size,
    imageAssetCount = images.size,
    imageAssetBytes = images.sumOf { it.sizeBytes },
    fileAssetCount = files.size,
    fileAssetBytes = files.sumOf { it.sizeBytes },
    persistentPrefixCacheEnabled = persistentPrefixCacheEnabled,
    persistentPrefixCacheEntryCount = persistentPrefixCacheEntryCount,
    persistentPrefixCacheBytes = persistentPrefixCacheBytes,
    statusMessage = statusMessage,
    appUpdate = AppUpdateSettingsUiState(
        autoCheckEnabled = appUpdate.autoCheckEnabled,
        status = appUpdate.status.name,
        currentVersionName = appUpdate.currentVersionName,
        latestVersionName = appUpdate.latestVersionName,
        latestTitle = appUpdate.latestTitle,
        releaseNotes = appUpdate.releaseNotes,
        releaseUrl = appUpdate.releaseUrl,
        apkSizeText = appUpdate.apkSizeBytes.takeIf { it > 0L }?.let(::formatAppUpdateBytes).orEmpty(),
        canDownload = appUpdate.canDownload,
        downloadedBytes = appUpdate.downloadedBytes,
        downloadTotalBytes = appUpdate.downloadTotalBytes,
        lastCheckedText = appUpdate.lastCheckedAtMillis.takeIf { it > 0L }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        }.orEmpty(),
        message = appUpdate.message
    ),
    webSearch = WebSearchSettingsUiState(
        enabled = webSearchConfig.enabled,
        provider = webSearchConfig.provider.name,
        providerLabel = webSearchConfig.providerLabel,
        endpoint = webSearchConfig.endpoint,
        apiKey = webSearchConfig.apiKey,
        maxResults = webSearchConfig.maxResults,
        fetchPageContent = webSearchConfig.fetchPageContent,
        triggerMode = webSearchConfig.triggerMode.name,
        triggerModeLabel = webSearchConfig.triggerMode.label,
        researchMode = webSearchConfig.researchMode.name,
        researchModeLabel = webSearchConfig.researchMode.label,
        configured = webSearchConfig.configured,
        realSearchConfigured = webSearchConfig.realSearchConfigured,
        realSearchProviderLabel = webSearchConfig.realSearchProviderLabel,
        backupProviders = webSearchConfig.backupProviders.take(3).map { backup ->
            WebSearchBackupProviderUiState(
                enabled = backup.enabled,
                provider = backup.provider.name,
                providerLabel = backup.providerLabel,
                endpoint = backup.endpoint,
                apiKey = backup.apiKey,
                configured = backup.configured
            )
        },
        statusMessage = webSearchStatusMessage ?: statusMessage,
        diagnostics = webSearchDiagnostics.map { record ->
            WebSearchDiagnosticUiItem(
                createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(record.createdAt)),
                providerLabel = record.providerLabel,
                triggerModeLabel = record.triggerModeLabel,
                query = record.query,
                success = record.success,
                message = record.message,
                sourceCount = record.sourceCount,
                elapsedMs = record.elapsedMs,
                searchedQueries = record.searchedQueries,
                directUrls = record.directUrls,
                healthScore = record.healthScore,
                healthLabel = record.healthLabel,
                healthReasons = record.healthReasons,
                qualityScore = record.qualityScore,
                qualityLabel = record.qualityLabel,
                qualityReasons = record.qualityReasons,
                sourceTrustSummary = record.sourceTrustSummary,
                researchConfidenceScore = record.researchConfidenceScore,
                researchConfidenceLabel = record.researchConfidenceLabel,
                researchEvidenceGroups = record.researchEvidenceGroups,
                researchConflictWarnings = record.researchConflictWarnings,
                researchSynthesisGuidance = record.researchSynthesisGuidance,
                triggerReasons = record.triggerReasons,
                warnings = record.warnings,
                cacheStatus = record.cacheStatus,
                closedLoopChecks = record.closedLoopChecks,
                topSources = record.topSources.map { source ->
                    val trustClass = source.webSearchSourceTrustClass()
                    WebSearchDiagnosticSourceUiItem(
                        title = source.title.ifBlank { source.url },
                        url = source.url,
                        snippet = source.snippet,
                        provider = source.provider,
                        trustLabel = trustClass.label,
                        hostLabel = source.webSearchHostLabel()
                    )
                }
            )
        }
    )
)

private fun formatAppUpdateBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${value.toLong()} ${units[index]}" else "%.1f %s".format(value, units[index])
}

private fun List<WebSearchBackupProviderDraft>.toWebSearchBackupConfigs(): List<WebSearchBackupProviderConfig> =
    take(3).map { draft ->
        WebSearchBackupProviderConfig(
            enabled = draft.enabled,
            provider = WebSearchProviderType.from(draft.provider),
            endpoint = draft.endpoint.trim(),
            apiKey = draft.apiKey.trim()
        )
    }

private fun MainUiState.toWebSearchTurnModeLabel(): String {
    if (!webSearchConfig.enabled) return "未启用"
    val assistantDefault = assistants.firstOrNull { it.id == selectedAssistantId }?.webSearchEnabled == true
    return when (webSearchTurnMode) {
        WebSearchTurnMode.ON -> "本轮开启"
        WebSearchTurnMode.OFF -> "本轮关闭"
        WebSearchTurnMode.FOLLOW -> when {
            assistantDefault -> "助手默认"
            webSearchConfig.triggerMode == WebSearchTriggerMode.ALWAYS -> "始终"
            webSearchConfig.triggerMode == WebSearchTriggerMode.SMART -> "智能"
            else -> "手动"
        }
    }

}

internal fun ImageGenerationUiOptions.toLocalImagePreviewOptions(): LocalImagePreviewOptions? {
    val interval = previewInterval ?: return null
    val uiMode = requireNotNull(previewMode) {
        "Image live preview requires an explicit mode when an interval is requested."
    }
    return LocalImagePreviewOptions(
        interval = interval,
        mode = LocalImagePreviewMode.fromWireName(uiMode.wireName)
    )
}

private fun DeviceProfile.deviceAccelerationSummary(): String {
    val acceleration = accelerationProfile
    val runtime = if (acceleration.qnnRuntime.usableForSmoke) {
        "QNN runtime 已就绪"
    } else if (acceleration.qnnRuntime.transportDependencyBlocked) {
        "NPU 运行环境受限"
    } else if (acceleration.qnnRuntime.ready) {
        "QNN runtime 探测失败"
    } else {
        "QNN runtime 待打包"
    }
    return "${acceleration.snapdragonTier.label} · ${acceleration.localChat.label} · $runtime"
}

private fun DeviceProfile.deviceImagePolicy(): String {
    val acceleration = accelerationProfile
    val image = acceleration.localImage
    val runtimeText = when {
        acceleration.qnnRuntime.ready && !acceleration.qnnRuntime.exactArchMatch ->
            "已发现 HTP V${acceleration.qnnRuntime.htpArchVersion} runtime，但设备需要 HTP V${acceleration.qnnRuntime.preferredHtpArchVersion}；不会把它标记为可用 NPU。"
        else -> when (image.status) {
            AccelerationCapabilityStatus.EXPERIMENTAL_READY ->
                "QNN 生图运行包已就绪；是否成功以当前模型的实际执行结果为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING ->
                "QNN 生图需要匹配的 runtime 与模型文件；下载后可直接加载验证。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED ->
                "已找到 QNN runtime 文件；可直接尝试，原生加载与 graph smoke 结果会如实显示。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED ->
                "QNN runtime 原生加载探测失败；入口不封禁，修复包后可再次直接尝试。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED ->
                "设备通信依赖当前受限；QNN 生图入口保持可见并报告真实 graph 执行结果。"
            AccelerationCapabilityStatus.READY ->
                "当前稳定路径为 stable-diffusion.cpp / MNN CPU，NPU 生图不会被宣传为已启用。"
            AccelerationCapabilityStatus.UNSUPPORTED ->
                "未识别到已知 QNN 能力档案；入口仍开放，默认也保留 CPU 兼容生图。"
        }
    }
    val visionText = when {
        acceleration.qnnRuntime.ready && !acceleration.qnnRuntime.exactArchMatch ->
            "识别到的 QNN HTP V${acceleration.qnnRuntime.htpArchVersion} 与设备要求的 HTP V${acceleration.qnnRuntime.preferredHtpArchVersion} 不匹配；请安装对应 runtime。"
        else -> when (acceleration.localVision.status) {
            AccelerationCapabilityStatus.EXPERIMENTAL_READY ->
                "本地识图可使用 LiteRT-LM / QNN NPU 包；结果以实际模型执行为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING ->
                "本地识图需要匹配的 LiteRT-LM / QNN runtime 和模型文件；下载后可直接加载验证。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED ->
                "本地识图已找到 QNN runtime 文件；入口开放并以真实 NPU smoke 为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED ->
                "本地识图 QNN runtime 原生加载探测失败；入口不封禁，可在修复包后重试。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED ->
                "本地识图设备通信依赖受限；入口保持开放并报告真实 NPU 执行结果。"
            AccelerationCapabilityStatus.READY ->
                "本地识图使用 GGUF mmproj / MNN 兼容路径。"
            AccelerationCapabilityStatus.UNSUPPORTED ->
                "未识别到已知 NPU 档案；本地识图入口仍开放。"
        }
    }
    return "$visionText $runtimeText"
}

private fun DeviceProfile.deviceImageTierKey(): String {
    val acceleration = accelerationProfile
    return when {
        acceleration.qnnRuntime.usableForSmoke && acceleration.sdxlNpuCandidate -> "qnn_sdxl_ready"
        acceleration.qnnRuntime.usableForSmoke && acceleration.stableDiffusion15NpuCandidate -> "qnn_sd15_ready"
        acceleration.sdxlNpuCandidate -> "qnn_sdxl_candidate"
        acceleration.stableDiffusion15NpuCandidate -> "qnn_sd15_candidate"
        else -> "cpu"
    }
}

private data class VisionCapabilityUi(
    val label: String,
    val detail: String,
    val ready: Boolean
)

/** Keep LiteRT's chat affordance in lockstep with the actual image-send gate. */
internal fun liteRtVisionInputReadyForUi(nativeStats: org.json.JSONObject?): Boolean =
    nativeStats?.let { liteRtVisionInputAvailable(it.toString()) } == true

internal fun liteRtVisionVerifiedReadyForUi(nativeStats: org.json.JSONObject?): Boolean =
    liteRtVisionInputReadyForUi(nativeStats) &&
        nativeStats?.optBoolean("visionReady", false) == true

private fun MainUiState.chatVisionCapability(): VisionCapabilityUi {
    if (selectedChatBackend == ChatBackend.CLOUD) {
        val cloudModel = cloudModels.firstOrNull {
            it.id == selectedCloudChatModelId && it.kind == CloudModelKind.CHAT && it.configured
        }
        return if (cloudModel != null) {
            if (cloudModel.supportsVision) {
                VisionCapabilityUi(
                    label = "云端多模态",
                    detail = "图片将发送给当前云端模型，识图能力由服务商和模型决定。",
                    ready = true
                )
            } else {
                VisionCapabilityUi(
                    label = "云端识图未启用",
                    detail = "请编辑云端推理引擎，开启支持图片输入。",
                    ready = false
                )
            }
        } else {
            VisionCapabilityUi(
                label = "未加载云端模型",
                detail = "请在模型管理加载支持图片输入的云端推理引擎。",
                ready = false
            )
        }
    }

    val loadedModel = models.firstOrNull { it.id == loadedModelId }
    val nativeStats = runCatching { org.json.JSONObject(nativeStatsJson) }.getOrNull()
    val nativeVisionReady = nativeStats?.optBoolean("visionReady", false) == true
    // Use the same admission predicate as the actual send path. Transport
    // readiness alone only proves that the SDK can serialize ImageFile; a
    // LiteRT package whose metadata explicitly says it has no visual encoder
    // must not appear ready in the chat UI.
    val liteRtImageTransportReady = liteRtVisionInputReadyForUi(nativeStats)
    val visionReadyForUi = if (loadedModel?.runtime == ChatModelRuntime.LITERT_LM) {
        liteRtVisionVerifiedReadyForUi(nativeStats)
    } else {
        nativeVisionReady
    }
    val liteRtKnownTextOnly = nativeStats?.optBoolean("visionModelKnownTextOnly", false) == true
    return when {
        loadedModel == null -> VisionCapabilityUi(
            label = "未加载本地模型",
            detail = "请加载 MNN 多模态包，或加载多模态 GGUF 并绑定匹配 mmproj。",
            ready = false
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM && liteRtKnownTextOnly -> VisionCapabilityUi(
            label = "当前模型不支持识图",
            detail = "这是纯文本 LiteRT-LM 包，不含图像视觉权重。请更换视觉版模型，或改用完整 MNN/GGUF 多模态模型。",
            ready = false
        )
        loadedModel.acceptsImageInput(visionReadyForUi) -> VisionCapabilityUi(
            label = "本地识图已就绪",
            detail = if (loadedModel.runtime == ChatModelRuntime.MNN) {
                "MNN 视觉组件已加载；所有兼容 ARM64 机型默认开放图片输入。"
            } else {
                "当前本地模型已启用视觉模块。"
            },
            ready = true
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM && liteRtImageTransportReady -> VisionCapabilityUi(
            label = "LiteRT 图像传输可用",
            detail = "图片输入已接通；当前模型尚未通过真实图片验证，首次请求会检查模型视觉能力。",
            ready = true
        )
        loadedModel.runtime == ChatModelRuntime.LITERT_LM -> VisionCapabilityUi(
            label = "LiteRT 图像通道未就绪",
            detail = "请重新加载模型后重试图片输入；若仍失败，请切换到支持视觉的 GGUF/MNN/QNN。",
            ready = false
        )
        loadedModel.runtime == ChatModelRuntime.MNN -> VisionCapabilityUi(
            label = "MNN 视觉组件未就绪",
            detail = "请加载包含可读 visual.mnn 的完整多模态包；就绪后即可发送图片。",
            ready = false
        )
        !loadedModel.visionProjectorPath.isNullOrBlank() -> VisionCapabilityUi(
            label = "视觉投影器待启用",
            detail = "已绑定 mmproj，请重新加载模型后再发图。",
            ready = false
        )
        else -> VisionCapabilityUi(
            label = "本地识图未启用",
            detail = "纯文本模型不能识图，请使用 MNN 多模态包或绑定匹配 mmproj。",
            ready = false
        )
    }
}

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
    CloudApiFormat.entries.toList()

private fun cloudImageApiFormats(): List<CloudImageApiFormat> =
    listOf(CloudImageApiFormat.OPENAI_IMAGES, CloudImageApiFormat.DASHSCOPE_IMAGE, CloudImageApiFormat.CUSTOM_PATH)

private fun cloudProviderPresets(): List<CloudProviderPresetUi> = listOf(
    CloudProviderPresetUi("openai", "OpenAI 协议", "自定义 OpenAI-compatible 接口"),
    CloudProviderPresetUi("responses", "OpenAI Responses", "自定义 Responses 接口"),
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

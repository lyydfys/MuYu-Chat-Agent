@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.mca

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentCodes
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.mca.dsh.overlay.DshOverlayHost
import com.muyuchat.mca.dsh.overlay.DshOverlayIntents
import com.muyuchat.mca.onboarding.onboardingModelStep
import com.muyuchat.feature.agent.*
import com.muyuchat.feature.agent.workspace.AgentWorkspaceRoute
import com.muyuchat.feature.chat.*
import com.muyuchat.feature.dsh.workspace.DshWorkspaceRoute
import com.muyuchat.feature.dsh.workspace.ui.DshPipHost
import com.muyuchat.feature.dsh.workspace.ui.DshPipPort
import com.muyuchat.feature.video.workspace.VideoWorkspaceRoute
import com.muyuchat.feature.modelhub.*
import com.muyuchat.feature.settings.*
import com.muyuchat.mca.presentation.*
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.settings.VideoDraftFileStore
import com.muyuchat.mca.video.settings.VideoEnginePreferenceStore
import com.muyuchat.mca.presentation.startup.TtfdProbe
import com.muyuchat.core.designsystem.appearance.McaAppearanceThemeChoice
import com.muyuchat.mca.presentation.startup.TtfdProbeHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PENDING_WORLD_BOOK_SCOPE_KEY = "pending_world_book_scope"

/** Tab-overlay crossfade length. Inside the V9 motion window (180-220 ms), shared by every tab. */
private const val TAB_SWITCH_FADE_MILLIS = 200

/** Splash -> app handoff crossfade. Same window; the app takes over while the splash fades out. */
private const val STARTUP_HANDOFF_FADE_MILLIS = 220

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

    /**
     * Shrinks the DSH page into a system picture-in-picture window.
     *
     * Published through [DshPipHost] instead of a route parameter: the workspace module has no
     * Activity, and threading a new argument through the whole route tree would collide with slices
     * in flight. The console row only renders inside the DSH workspace, so the page that gets
     * shrunk is always the DSH one.
     */
    private val pictureInPicturePort = object : DshPipPort {
        override fun isAvailable(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        override fun enterShrunken(): String? {
            if (!isAvailable()) return "当前系统不支持画中画"
            if (isInPictureInPictureMode) return null
            return runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(3, 4))
                    .build()
                if (enterPictureInPictureMode(params)) null else "系统拒绝了画中画请求"
            }.getOrElse { error ->
                "画中画启动失败：${error.javaClass.simpleName}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DshPipHost.port = pictureInPicturePort
        TtfdProbe.install(window.decorView)
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
        val worldBookImportLauncher = registerForActivityResult(OpenAnyDocumentsContract()) { uris ->
            val scope = pendingWorldBookImportScope
            val owner = pendingWorldBookOwner
            pendingWorldBookImportScope = WorldBookScope.GLOBAL
            pendingWorldBookOwner = null
            if (uris.isNotEmpty()) {
                startup.whenReady { it.importWorldBookFiles(uris.map(Uri::toString), scope, owner) }
            }
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
        // User-initiated backup manifest (settings -> logs/privacy). The file is a versioned,
        // verifiable envelope built by `McaBackupCoordinator`; it is never a database dump.
        val backupExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) startup.whenReady { it.exportBackup(uri) }
        }
        // Restore of a picked backup. The settings card shows a destructive confirmation before this
        // launcher opens; the restore itself runs inside one database transaction and rolls back on
        // any failure, so binding it to the Activity needs no extra state here.
        val backupPreviewLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startup.whenReady { it.restoreBackup(uri) }
        }
        val chatExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            val sId = pendingChatExportSessionId
            pendingChatExportSessionId = null
            if (uri != null && sId != null) startup.whenReady { it.exportChatSession(sId, uri) }
        }
        // D15 virtual screen: the system `MediaProjection` consent dialog. The launcher stays
        // registered for the Activity lifetime so Android can deliver a result after recreation;
        // the result itself is applied by the app consent owner, never by this Activity.
        val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            startup.whenReady { model -> model.onDshScreenCaptureResult(result.resultCode, result.data) }
        }
        // Prepares the capture foreground service, then launches the one system dialog. The id is
        // claimed exactly once by the ViewModel, so recomposition or recreation cannot open two
        // dialogs for the same request.
        val launchDshScreenCapture: (Long) -> Unit = { requestId ->
            if (viewModel.beginDshScreenCaptureConsent(requestId)) {
                val consentIntent = runCatching {
                    getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()
                }.getOrNull()
                if (consentIntent == null) {
                    viewModel.rejectDshScreenCaptureConsent(
                        requestId,
                        DshScreenCaptureConsentCodes.MEDIA_PROJECTION_UNAVAILABLE
                    )
                } else if (runCatching { screenCaptureLauncher.launch(consentIntent) }.isFailure) {
                    viewModel.rejectDshScreenCaptureConsent(
                        requestId,
                        DshScreenCaptureConsentCodes.CONSENT_DIALOG_UNAVAILABLE
                    )
                }
            }
        }

        setContent {
            // One TTFD observation point; renders nothing and never changes the startup flow.
            TtfdProbeHost()
            val startupState by startup.state.collectAsStateWithLifecycle()
            // Model-download facts for the first-run page, reduced to plain values so the guide never
            // sees MainUiState; no ready model or no recommendation degrades it to 「稍后下载模型」.
            val guideModel = startupState.model
            val guideState = if (guideModel != null) {
                guideModel.uiState.collectAsStateWithLifecycle().value
            } else {
                null
            }
            val guideRecommended = guideState?.recommendedRemoteModels?.firstOrNull()
            val guideModelStep = onboardingModelStep(
                modelLabel = guideRecommended?.title,
                downloadedBytes = guideState?.downloadedBytes ?: 0L,
                totalBytes = guideState?.downloadTotalBytes ?: 0L,
                running = guideState?.downloadStatus == DownloadStatus.RUNNING ||
                    guideState?.downloadStatus == DownloadStatus.QUEUED,
                failed = guideState?.downloadStatus == DownloadStatus.FAILED,
                failureReason = guideState?.downloadIntegrityMessage ?: guideState?.downloadExecutionMessage,
                blockReason = guideRecommended?.let(::recommendedDownloadBlockReason)
            )
            // The colour mode, the first-run gate and the app-wide consent dialog all live in
            // FirstRunContent; the shell only supplies the app body, which is composed once the guide is
            // done and is skipped entirely while it runs.
            FirstRunContent(
                modelStep = guideModelStep,
                onDownloadModel = { guideRecommended?.let { viewModel.downloadRecommended(it) } }
            ) { appearanceChoice, onAppearanceChange, onReplay ->
                // V03: splash -> app handoff inside the one McaTheme node FirstRunContent provides, so the
                // startup surface crossfades out and the app crossfades in without a second theme owner.
                Crossfade(
                    targetState = startupState.model,
                    animationSpec = McaMotion.fade(STARTUP_HANDOFF_FADE_MILLIS),
                    modifier = Modifier.fillMaxSize(),
                    label = "startup-to-app",
                ) { readyModel ->
                    if (readyModel == null) {
                        AppStartupScreen(startupState, onRetry = { startup.start() }, onSkipScan = { startup.skipModelDiscovery() })
                    } else {
                        val state by readyModel.uiState.collectAsStateWithLifecycle()
                        // Observes the app-owned consent request and asks this Activity to launch
                        // the system dialog. It renders nothing and owns no capture state.
                        DshScreenCaptureConsentHost(
                            consent = readyModel.dshScreenCaptureConsent,
                            onLaunchConsent = launchDshScreenCapture
                        )
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
                            onExportBackup = { backupExportLauncher.launch(viewModel.suggestedBackupFileName()) },
                            onPreviewBackupRestore = { backupPreviewLauncher.launch(arrayOf("application/json", "text/json")) },
                            onExportChatSession = { id -> pendingChatExportSessionId = id; chatExportLauncher.launch(viewModel.chatSessionExportFileName(id)) },
                            viewModel = viewModel,
                            appearanceRememberedChoice = appearanceChoice,
                            onAppearanceRememberedChange = onAppearanceChange,
                            onReplayOnboarding = onReplay
                        )
                    }
                }
            }
        }
        // App 外显示: a mark/bubble tap starts this Activity on the DSH tab. The extra is consumed
        // once, so a later recreation does not force the tab again.
        openDshWorkspaceIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The overlay's Intent carries FLAG_ACTIVITY_SINGLE_TOP|CLEAR_TOP, so an already running app
        // receives it here instead of starting a second Activity.
        setIntent(intent)
        openDshWorkspaceIfRequested(intent)
    }

    /**
     * Switches to the DSH workspace when the overlay asked for it; the WebView itself restores the
     * session path it persisted, so this is "back to the conversation", not a new navigation stack.
     */
    private fun openDshWorkspaceIfRequested(intent: Intent?) {
        if (intent == null) return
        if (!intent.getBooleanExtra(DshOverlayIntents.EXTRA_OPEN_WORKSPACE, false)) return
        intent.removeExtra(DshOverlayIntents.EXTRA_OPEN_WORKSPACE)
        startup.whenReady { model -> model.selectTab(AppTab.DSH_WORKSPACE) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_WORLD_BOOK_SCOPE_KEY, pendingWorldBookImportScope.wireName)
        outState.putString("pending_assistant_card_owner", pendingAssistantCardOwner?.toJsonString())
        outState.putString("pending_world_book_owner", pendingWorldBookOwner?.toJsonString())
        outState.putString("pending_knowledge_document_owner", pendingKnowledgeDocumentOwner?.toJsonString())
        outState.putBoolean("pending_model_import_text_only", pendingModelImportTextOnly)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Drop the port only while this instance still owns it: a configuration change can create
        // the replacement Activity before the old one is destroyed.
        if (DshPipHost.port === pictureInPicturePort) DshPipHost.port = null
        super.onDestroy()
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
    onExportBackup: () -> Unit, onPreviewBackupRestore: () -> Unit,
    onExportChatSession: (String) -> Unit, viewModel: MainViewModel,
    /**
     * Appearance and first-run callbacks the settings surface forwards. Passed in rather than read
     * inside the settings page because both owners live at the composition root: the colour mode has
     * to reach [McaTheme] above this node, and replaying onboarding replaces this whole subtree.
     */
    appearanceRememberedChoice: McaAppearanceThemeChoice,
    onAppearanceRememberedChange: (McaAppearanceThemeChoice) -> Unit,
    onReplayOnboarding: () -> Unit
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
    // Experimental video-engine switch: persisted app-wide, default OFF. The page only forwards
    // the value into the submit command; execution ownership stays with the process video owner.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val videoEnginePreference = remember(appContext) { VideoEnginePreferenceStore(appContext) }
    val videoDraftStore = remember(appContext) {
        VideoDraftFileStore(File(appContext.filesDir, "video/draft.txt"))
    }
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

    // V03: one saveable holder for the four overlay tabs. The chat page is the always-composed base
    // layer below them and is deliberately not disposed, so it needs no holder; each overlay tab
    // keeps its rememberSaveable state across the crossfade disposal.
    val tabStateHolder = rememberSaveableStateHolder()

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
                    preparedImageUi = preparedImageUi, generationParamsForChatModel = viewModel::generationParamsForChatModel,
                    modelGenerationPreset = viewModel::currentModelGenerationPreset
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

            CrossfadeTabPage(
                visible = state.tab == AppTab.AGENT,
                stateHolder = tabStateHolder,
                tabKey = AppTab.AGENT.name,
                label = "tab-agent",
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

            // Independent Agent workspace (architecture 13.1). The chat page stays composed below
            // every tab; this overlay only observes the Agent owner and never writes chat state.
            CrossfadeTabPage(
                visible = state.tab == AppTab.AGENT_WORKSPACE,
                stateHolder = tabStateHolder,
                tabKey = AppTab.AGENT_WORKSPACE.name,
                label = "tab-agent-workspace",
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                AgentWorkspaceRoute(
                    queries = viewModel.agentWorkspacePorts.runtime.queries,
                    commands = viewModel.agentWorkspacePorts.runtime,
                    modelCatalog = viewModel.agentWorkspacePorts.modelCatalog,
                    onBack = closePage,
                    onLoadLocalModel = viewModel::selectChatModel,
                    modifier = pageModifier,
                    packageActions = viewModel.agentWorkspacePorts.packageActions,
                    skillAdmin = viewModel.agentWorkspacePorts.skillAdmin,
                    mcpAdmin = viewModel.agentWorkspacePorts.mcpAdmin,
                    toolPackageAdmin = viewModel.agentWorkspacePorts.toolPackageAdmin,
                    memoryAdmin = viewModel.agentWorkspacePorts.memoryAdmin,
                    workflowAdmin = viewModel.agentWorkspacePorts.workflowAdmin,
                    workflowLaunch = viewModel.agentWorkspacePorts.workflowAdmin,
                    workflowSchedule = viewModel.agentWorkspacePorts.workflowSchedule,
                    documentLauncher = rememberAgentDocumentLauncher()
                )
            }

            // Independent official DSH mode. The page hosts the official DSH Web UI in its own
            // WebView container; MCA owns only host start/stop/failure and Android interactions.
            // It never renders Kotlin Agent state and never starts a second runtime per entry.
            CrossfadeTabPage(
                visible = state.tab == AppTab.DSH_WORKSPACE,
                stateHolder = tabStateHolder,
                tabKey = AppTab.DSH_WORKSPACE.name,
                label = "tab-dsh-workspace",
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                // The overlay host is a process singleton keyed by application context.
                val overlayContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
                DshWorkspaceRoute(
                    client = viewModel.dshRuntimeClient,
                    firstFrameReporter = viewModel.dshFirstFrameReporter,
                    consolePort = viewModel.dshConsolePort,
                    pluginAdminPort = viewModel.dshPluginAdminPort,
                    terminalPort = viewModel.dshTerminalUiPort,
                    emergencyRecovery = viewModel.dshEmergencyRecovery,
                    maintenanceConsolePort = viewModel.dshMaintenanceConsolePort,
                    externalBrowserPort = viewModel.dshExternalBrowserPort,
                    accountAuthUrlMatcher = viewModel.dshAccountAuthUrlMatcher,
                    accountLogin = viewModel.dshAccountLoginPort,
                    modelSwitch = viewModel.dshModelSwitch,
                    overlayPort = viewModel.dshOverlayPort,
                    // Account-direct conversations never pass through the MCA gateway, so the page
                    // text observer is the only signal that a reply finished. The gateway path keeps
                    // priority: a DOM hit within one second of a gateway reply is dropped by the
                    // de-duplicator inside the route.
                    onAccountReply = { text ->
                        DshOverlayHost.of(overlayContext).publishAccountReply(text)
                    },
                    accountReplyProbeEnabled = true,
                    lastGatewayReplyAtMillis = { DshOverlayHost.of(overlayContext).lastGatewayReplyAtMillis },
                    modifier = pageModifier,
                    onExit = closePage
                )
            }

            // Native video workflow workspace (proposal mca-native-video-workflow-v1). The page
            // is pure Compose UI; execution belongs to the process-level video owner injected here
            // through the contract port (V1-02a). The :local_video worker adapter is wired;
            // real device/native execution remains pending validation — no fake runtime or progress.
            // Leaving never cancels a video run; re-entering reuses the same owner.
            CrossfadeTabPage(
                visible = state.tab == AppTab.VIDEO_WORKSPACE,
                stateHolder = tabStateHolder,
                tabKey = AppTab.VIDEO_WORKSPACE.name,
                label = "tab-video-workspace",
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                VideoWorkspaceRoute(
                    port = viewModel.videoWorkflowPort,
                    importPort = viewModel.videoImportPort,
                    remoteProbePort = viewModel.videoRemoteProbePort,
                    modelAssetPort = viewModel.videoModelAssetPort,
                    enginePreference = videoEnginePreference,
                    turboLoraPathProvider = {
                        File(
                            appContext.filesDir,
                            "video_models/${Wan22Ti2v5bProfiles.MODEL_ID}/loras/turbo.safetensors",
                        ).takeIf { it.isFile }?.absolutePath
                    },
                    draftStore = videoDraftStore,
                    onExit = closePage,
                    modifier = pageModifier,
                )
            }

            CrossfadeTabPage(
                visible = state.tab == AppTab.MODELS,
                stateHolder = tabStateHolder,
                tabKey = AppTab.MODELS.name,
                label = "tab-models",
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
                            streaming = state.cloudApiConfig.streaming,
                            imageApiFormat = state.cloudApiConfig.imageApiFormat.name, availableImageFormats = cloudImageApiFormats().map { it.name to it.label },
                            imageModel = state.cloudApiConfig.imageModel, imageSize = state.cloudApiConfig.imageSize,
                            imageEndpointPath = state.cloudApiConfig.imageEndpointPath, imageModelPresets = imageModelPresetsFor(state.cloudApiConfig.imageApiFormat),
                            imageSizePresets = imageSizePresetsFor(state.cloudApiConfig.imageApiFormat), providerPresets = cloudProviderPresets(),
                            connectedModels = state.cloudModelUiItems(),
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
                    onDownloadRecommendedVariant = { model, variantId ->
                        viewModel.downloadRecommended(
                            model,
                            useAfterDownload = startModelsInRecommended,
                            imageBundleVariantId = variantId
                        )
                    },
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
                    onCloudStreamingChange = viewModel::updateCloudStreaming,
                    onCloudImageFormatChange = viewModel::updateCloudImageApiFormat, onCloudImageModelChange = viewModel::updateCloudImageModel,
                    onCloudImageSizeChange = viewModel::updateCloudImageSize, onCloudImageEndpointPathChange = viewModel::updateCloudImageEndpointPath,
                    onCloudDisplayNameChange = viewModel::updateCloudDisplayName, onSaveCloudChatModel = viewModel::saveCloudChatModel,
                    onSaveCloudImageModel = viewModel::saveCloudImageModel, onTestCloudApi = viewModel::testCloudApiConfig,
                    onFetchCloudModels = { viewModel.fetchCloudModelOptions() },
                    onFetchCloudImageModels = { viewModel.fetchCloudImageModelOptions() },
                    onSelectCloudChat = { modelId -> viewModel.selectChatModel(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + modelId) },
                    onSelectCloudImage = viewModel::selectCloudImageModel, onDeleteCloudModel = viewModel::deleteCloudModel,
                    onRefreshLocal = viewModel::refreshLocalModels, onPauseDownloads = viewModel::pauseManagedDownloads,
                    onResumeDownloads = viewModel::resumeManagedDownloads, onPauseImport = viewModel::pauseManagedImport,
                    onResumeImport = viewModel::resumeManagedImport, onBack = closePage, modifier = pageModifier
                )
            }

            CrossfadeTabPage(
                visible = state.tab == AppTab.PRESETS,
                stateHolder = tabStateHolder,
                tabKey = AppTab.PRESETS.name,
                label = "tab-presets",
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                PresetManagerScreen(
                    presets = generationPresetUiItems(state.generationPresets),
                    localModels = state.models,
                    cloudChatModels = state.cloudChatModelUiItems(),
                    onImportPreset = viewModel::importGenerationPreset,
                    onImportPresets = viewModel::importGenerationPresets,
                    onDeletePreset = viewModel::deleteGenerationPreset,
                    onSaveBindings = viewModel::saveGenerationPresetBindings,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            CrossfadeTabPage(
                visible = state.tab == AppTab.API,
                stateHolder = tabStateHolder,
                tabKey = AppTab.API.name,
                label = "tab-api",
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

            CrossfadeTabPage(
                visible = state.tab == AppTab.SETTINGS,
                stateHolder = tabStateHolder,
                tabKey = AppTab.SETTINGS.name,
                label = "tab-settings",
                onDismissStart = { if (appMenuOpen) appMenuOpen = true },
                onDismiss = { onTab(AppTab.CHAT) }
            ) { pageModifier, closePage ->
                SettingsHubScreen(
                    state = state.settingsUiState(),
                    onRefreshLogs = viewModel::refreshLogs, onRefreshDiagnostics = viewModel::refreshDiagnostics,
                    onExportDiagnostics = onExportDiagnostics, onClearChatHistory = viewModel::clearChatHistory,
                    onExportBackup = onExportBackup, onPreviewBackupRestore = onPreviewBackupRestore,
                    onClearImageLibrary = viewModel::clearImageLibrary, onClearFileLibrary = viewModel::clearFileLibrary,
                    onPersistentPrefixCacheEnabledChanged = viewModel::setPersistentPrefixCacheEnabled,
                    onClearPersistentPrefixCache = viewModel::clearPersistentPrefixCache,
                    onChatLocalToolsEnabledChanged = viewModel::setChatLocalToolsEnabled,
                    onRichBlocksEnabledChanged = { enabled -> viewModel.setRichBlocksEnabled(enabled) },
                    agentApprovalPreset = viewModel.agentApprovalPreset(),
                    onAgentApprovalPresetChanged = viewModel::setAgentApprovalPreset,
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
                    startInWebSearch = startSettingsInWebSearch, modifier = pageModifier,
                    appearanceThemeChoice = appearanceRememberedChoice,
                    onAppearanceThemeChoiceChange = onAppearanceRememberedChange,
                    onReplayOnboarding = onReplayOnboarding
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

/**
 * One top-level tab overlay with the shared tab crossfade.
 *
 * Layout/visibility contract: the overlay still fills the same Box slot it always did and still
 * hosts the same [SwipeBackPage] + screen call; only the appearance transition changed from "outgoing
 * page disappears, incoming page slides in" to a [Crossfade] driven by [McaMotion.fade]. The chat page
 * is intentionally **not** wrapped: it is the always-composed base layer below these overlays, so its
 * `ChatViewModel`, draft, scroll and focus state never leave composition on a tab switch.
 *
 * `SaveableStateHolder` keeps each overlay tab's `rememberSaveable` state across the disposal that
 * ends the fade. Keeping hidden tabs permanently composed would preserve plain `remember` too, but
 * would run their background effects and polling on every other tab -- not acceptable here.
 */
@Composable
private fun CrossfadeTabPage(
    visible: Boolean,
    stateHolder: SaveableStateHolder,
    tabKey: String,
    label: String,
    onDismissStart: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit,
) {
    Crossfade(
        targetState = visible,
        animationSpec = McaMotion.fade(TAB_SWITCH_FADE_MILLIS),
        modifier = Modifier.fillMaxSize(),
        label = label,
    ) { shown ->
        if (shown) {
            stateHolder.SaveableStateProvider(tabKey) {
                SwipeBackPage(
                    visible = true,
                    onDismissStart = onDismissStart,
                    onDismiss = onDismiss,
                    content = content,
                )
            }
        }
    }
}

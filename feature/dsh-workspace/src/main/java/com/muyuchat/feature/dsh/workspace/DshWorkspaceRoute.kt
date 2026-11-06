package com.muyuchat.feature.dsh.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshPluginAdminPort
import com.muyuchat.core.dsh.contract.console.DshTerminalUiPort
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable
import com.muyuchat.feature.dsh.workspace.ui.DshConsoleRoute
import com.muyuchat.feature.dsh.workspace.ui.DshFloatingBall
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayPanel
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiPort
import com.muyuchat.feature.dsh.workspace.ui.DshWorkspaceActions
import com.muyuchat.feature.dsh.workspace.ui.DshWorkspaceScreen
import com.muyuchat.feature.dsh.workspace.web.DshAccountReplyDeduper
import com.muyuchat.feature.dsh.workspace.web.DshWebStorage
import com.muyuchat.feature.dsh.workspace.web.DshWebViewController
import com.muyuchat.feature.dsh.workspace.terminal.DshTerminalPage
import com.muyuchat.feature.dsh.workspace.web.DshGeckoViewHost
import com.muyuchat.feature.dsh.workspace.web.DshWebKernel
import com.muyuchat.feature.dsh.workspace.web.DshWebKernelPolicy
import com.muyuchat.feature.dsh.workspace.web.DshWebKernelStore
import com.muyuchat.feature.dsh.workspace.web.DshWebViewHost
import kotlinx.coroutines.launch

/**
 * Public entry point of the independent DSH workspace tab.
 *
 * The app composition root supplies the frozen [DshRuntimeClient] of the single DSH runtime
 * owner, the leave-tab callback and, optionally, [firstFrameReporter] for the runtime's startup
 * timeline. Everything else (Shell, WebView, SAF, downloads, back hierarchy, navigation memory)
 * is self-contained so `MainActivity` can render this route for `AppTab.DSH_WORKSPACE` without
 * reaching into DSH runtime or DSH session state.
 *
 * DSH-only additions, all optional so an unwired host behaves exactly as before:
 * - [consolePort] exposes the read-only D20 console as an overlay of this same tab. The console
 *   never writes runtime/model/session/plugin state; it only submits typed intents to the port.
 * - [externalBrowserPort] receives typed D04 popup requests (`window.open` / `target="_blank"`),
 *   including the account authorize popup. Without it, normal external links keep the existing
 *   validated Android-browser path and account popups are refused with a notice (never loaded in
 *   the DSH WebView).
 * - [accountAuthUrlMatcher] is the account owner's exact authorize-URL classifier. Defaults to
 *   "no account popups", which degrades them to normal external links.
 * - [accountPopupHoldMillis] bounds how long a hidden `about:blank` popup may wait for the
 *   authorize URL; it replaces the old one-second window that truncated official sign-in.
 * - [accountLogin] is the account lane's correlation seam: it receives the trusted pre-open /
 *   abandon facts and the launch-cookie 401, and it is the same object behind
 *   [externalBrowserPort] / [accountAuthUrlMatcher]. Without it the popup path behaves as before
 *   (account URLs degrade to ordinary external links) and no sign-in attempt is correlated.
 *
 * The route never starts a second runtime: the ViewModel only calls `ensureStarted()` / `restart()`
 * on the injected client. Leaving the tab does not stop the runtime or cancel DSH work.
 */
@Composable
fun DshWorkspaceRoute(
    client: DshRuntimeClient,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onOpenExternal: ((android.net.Uri) -> Unit)? = null,
    firstFrameReporter: DshFirstFrameReporter = DshFirstFrameReporter.None,
    consolePort: DshConsolePort? = null,
    /** In-app plugin administration; null keeps the console's plugin block read-only. */
    pluginAdminPort: DshPluginAdminPort? = null,
    /** Native terminal page; null hides the entry and keeps the tab byte-for-byte as before. */
    terminalPort: DshTerminalUiPort? = null,
    /** Emergency recovery seam; null hides the block. */
    emergencyRecovery: com.muyuchat.core.dsh.contract.console.DshEmergencyRecoveryPort? = null,
    maintenanceConsolePort: DshMaintenanceConsolePort? = null,
    externalBrowserPort: DshExternalBrowserPort? = null,
    accountAuthUrlMatcher: DshAccountAuthUrlMatcher = DshAccountAuthUrlMatcher { false },
    accountPopupHoldMillis: Long = DEFAULT_ACCOUNT_POPUP_HOLD_MILLIS,
    accountLogin: DshAccountLoginPort? = null,
    /** Optional MCA model panel exposed from the floating ball. Null keeps the ball two-action. */
    modelSwitch: DshModelSwitchPort? = null,
    /**
     * Optional app-outside display owner. The workspace only renders the switch, the appearance panel
     * and the notices; the window service, the background keep-alive and the persistence stay in the
     * app module. Null keeps the ball without the two overlay entries.
     */
    overlayPort: DshOverlayUiPort? = null,
    /**
     * Account-path reply sink.
     *
     * Non-null turns on the read-only DOM observer in the page (subject to
     * [accountReplyProbeEnabled]) so a reply that the official account path renders directly — the MCA
     * gateway never sees it — can raise the same overlay bubble. The app wires this to the same
     * `onReply` entry it already uses for the gateway path; null keeps the probe completely off.
     */
    onAccountReply: ((String) -> Unit)? = null,
    /** Persisted user switch for [onAccountReply]; false means no injection at all. */
    accountReplyProbeEnabled: Boolean = true,
    /**
     * Timestamp of the last MCA-gateway reply, supplied by the app.
     *
     * A DOM report within [DshReplyDomLimits.GATEWAY_DEDUP_MILLIS] of that moment is dropped, so the two
     * paths share one bubble instead of racing.
     */
    lastGatewayReplyAtMillis: (() -> Long?)? = null
) {
    val context = LocalContext.current
    // Rendering kernel for the official page. The store holds the user's choice; the policy
    // keeps the system WebView unless GeckoView can actually run on this build and device.
    val webKernel = remember(context) {
        DshWebKernelPolicy.effectiveKernel(
            requested = DshWebKernelStore(context).read(),
            supportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            geckoViewPresent = true
        )
    }
    val applicationContext = context.applicationContext
    val navigationStore = remember(applicationContext) {
        FileDshNavigationStore.forContext(applicationContext)
    }
    val viewModel: DshWorkspaceViewModel = viewModel(
        key = "dsh-workspace",
        factory = DshWorkspaceViewModel.factory(client, navigationStore, firstFrameReporter)
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val controller = remember { DshWebViewController() }
    val popupCoordinator = remember { DshPopupCoordinator() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Shared with the account-path reply probe: one bubble per reply, never a gateway+DOM double.
    val accountReplyDeduper = remember { DshAccountReplyDeduper() }
    val latestOnExit = rememberUpdatedState(onExit)
    val latestOnOpenExternal = rememberUpdatedState(onOpenExternal)
    val latestExternalBrowserPort = rememberUpdatedState(externalBrowserPort)
    val latestAccountAuthMatcher = rememberUpdatedState(accountAuthUrlMatcher)
    val latestAccountLogin = rememberUpdatedState(accountLogin)
    var destination by rememberSaveable { mutableStateOf(DshWorkspaceDestination.WEB) }
    var overlayPanelOpen by rememberSaveable { mutableStateOf(false) }
    var overlayEnabled by rememberSaveable {
        mutableStateOf(overlayPort?.snapshot()?.enabled == true)
    }

    fun handlePopupRequest(request: DshPopupRequest) {
        val browserPort = latestExternalBrowserPort.value
        if (browserPort == null) {
            if (request.kind == com.muyuchat.core.dsh.contract.console.DshPopupKind.ACCOUNT_AUTH) {
                viewModel.postNotice("未接入账号登录浏览器，已阻止该登录窗口。")
            } else if (!openExternalLink(context, request.url, latestOnOpenExternal.value)) {
                viewModel.postNotice("无法打开外部链接。")
            }
            return
        }
        scope.launch {
            val outcome = runCatching { browserPort.openPopup(request) }.getOrNull()
                ?: DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER)
            when (val mapped = DshPopupHandoffPolicy.outcomeFor(request.kind, request.url, outcome)) {
                is DshPopupOutcome.Notify -> viewModel.postNotice(mapped.text)
                is DshPopupOutcome.FallbackExternal -> {
                    if (!openExternalLink(context, mapped.url, latestOnOpenExternal.value)) {
                        viewModel.postNotice("无法打开外部链接。")
                    }
                }

                DshPopupOutcome.Silent -> Unit
            }
        }
    }

    val callbacks = remember(viewModel) {
        object : DshWebHostCallbacks {
            override fun onExternalLink(url: String) {
                if (!openExternalLink(context, url, latestOnOpenExternal.value)) {
                    viewModel.postNotice("无法打开外部链接。")
                }
            }

            override fun onBlockedNavigation(reason: DshBlockReason) {
                viewModel.postNotice(reason.messageFor())
            }

            override fun onPageStarted() = viewModel.onPageStarted()
            override fun onPageCommitVisible() = viewModel.onPageCommitVisible()
            override fun onProgress(progress: Int) = viewModel.onProgress(progress)
            override fun onPageFinished(
                cleanUrl: String?,
                sameOrigin: Boolean,
                containsLaunchToken: Boolean
            ) = viewModel.onPageFinished(cleanUrl, sameOrigin, containsLaunchToken)
            override fun onMainFrameError(kind: DshWebErrorKind) = viewModel.onMainFrameError(kind)
            override fun onHttpUnauthorized() {
                viewModel.onHttpUnauthorized()
                // The DSH document's own 401 is the launch-cookie session, never the account
                // grant: the account lane classifies it and must not clear the account.
                latestAccountLogin.value?.onWebSessionUnauthorized()
            }
            override fun onHttpError(statusCode: Int) = viewModel.onHttpError(statusCode)
            override fun onRendererGone() = viewModel.onRendererGone()
            override fun onNavigationState(canGoBack: Boolean) =
                viewModel.onNavigationState(canGoBack)
            override fun onNotice(text: String) = viewModel.postNotice(text)
            override fun onRestoreRequestConsumed(id: Long) =
                viewModel.consumeRestoreRequest(id)
            override fun onWebHostDisposed(webGeneration: Long) =
                viewModel.onWebHostDisposed(webGeneration)
            override fun onPopupRequest(request: DshPopupRequest) = handlePopupRequest(request)
            override fun onPopupPreopened(requesterOrigin: String) {
                // The official account frontend pre-opens a blank popup before `auth_init` answers.
                // The lane binds that one window to a bounded attempt; nothing is opened here.
                latestAccountLogin.value?.onPopupPreopened(requesterOrigin)
            }
            override fun onPopupAbandoned(reason: DshPopupAbandonReason) {
                // A pre-opened window that never got its authorize URL can never be handed off.
                latestAccountLogin.value?.onPopupAbandoned()
                when (reason) {
                    DshPopupAbandonReason.TIMEOUT ->
                        viewModel.postNotice("登录窗口等待超时，请重新发起登录。")

                    DshPopupAbandonReason.REJECTED ->
                        viewModel.postNotice("已阻止该弹窗链接。")

                    DshPopupAbandonReason.PAGE_CLOSED -> Unit
                }
            }
        }
    }

    val actions = remember(viewModel) {
        DshWorkspaceActions(
            onRetry = viewModel::retry,
            onReloadPage = viewModel::requestPageReload,
            onDismissPageError = viewModel::dismissPageError,
            onExit = { latestOnExit.value() }
        )
    }

    LaunchedEffect(viewModel) { viewModel.start() }

    // System background/foreground: never cancels DSH work; only an interrupted page load is
    // reloaded after a long enough absence (D25). Tab switching is composition disposal, handled
    // separately by the WebView host lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onHostBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onHostForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Runtime generation changed: expire the old loopback origin's cookies before a new page loads.
    val previousOrigin = remember { mutableStateOf<DshOrigin?>(null) }
    LaunchedEffect(state) {
        val web = state as? DshWorkspaceUiState.Web ?: return@LaunchedEffect
        val previous = previousOrigin.value
        if (previous != null && previous != web.origin) {
            DshWebStorage.expireOriginCookies(previous)
        }
        previousOrigin.value = web.origin
    }

    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text, duration = SnackbarDuration.Short)
        viewModel.consumeNotice(current.id)
    }

    // Compose's BackHandler owns back while this tab is visible: console overlay, fullscreen
    // video, then WebView history, then the host's page-exit motion.
    androidx.activity.compose.BackHandler {
        if (DshWorkspaceDestinationPolicy.consumesBack(destination)) {
            destination = DshWorkspaceDestinationPolicy.backDestination(destination)
        } else if (!controller.handleBack()) {
            latestOnExit.value()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        DshWorkspaceScreen(
            state = state,
            page = page,
            snackbarHostState = snackbarHostState,
            actions = actions,
            modifier = Modifier.fillMaxSize(),
            onOpenMaintenanceConsole = if (consolePort != null) {
                { destination = DshWorkspaceDestination.CONSOLE }
            } else {
                null
            },
            webContent = { web ->
                key(web.epoch, web.webGeneration) {
                    if (webKernel == DshWebKernel.GECKOVIEW) {
                        DshGeckoViewHost(
                            state = web,
                            callbacks = callbacks,
                            controller = controller,
                            onFirstFrameRendered = viewModel::onFirstFrameRendered,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        DshWebViewHost(
                        state = web,
                        callbacks = callbacks,
                        controller = controller,
                        onFirstFrameRendered = viewModel::onFirstFrameRendered,
                        popupCoordinator = popupCoordinator,
                        accountAuthMatcher = latestAccountAuthMatcher.value,
                        accountPopupHoldMillis = accountPopupHoldMillis,
                        accountLogin = latestAccountLogin.value,
                        // Account-path replies pass the same dedup rule as the gateway path: a DOM
                        // report within one second of a gateway reply is dropped.
                        onAccountReply = onAccountReply?.let { sink ->
                            { text: String ->
                                if (accountReplyDeduper.accept(
                                        text,
                                        lastGatewayReplyAtMillis?.invoke()
                                    )
                                ) {
                                    sink(text)
                                }
                                Unit
                            }
                        },
                        accountReplyProbeEnabled = accountReplyProbeEnabled,
                        modifier = Modifier.fillMaxSize()
                    )
                    }
                }
            }
        )
        if (destination == DshWorkspaceDestination.WEB && state is DshWorkspaceUiState.Web) {
            // One movable whale ball carries both host actions (返回 MCA / DSH 控制台) as a small
            // expanded panel. A fixed corner button covered the official left/right sidebar
            // toggles (device R35C), and even the center cluster kept two targets on the page; the
            // ball is draggable, remembers its position and intercepts only its own area.
            DshFloatingBall(
                showConsole = consolePort != null,
                onExit = { actions.onExit() },
                onOpenConsole = { destination = DshWorkspaceDestination.CONSOLE },
                modelSwitch = modelSwitch,
                overlayEnabled = overlayEnabled,
                onToggleOverlay = overlayPort?.let { port ->
                    { enabled: Boolean ->
                        port.setEnabled(enabled)?.let(viewModel::postNotice)
                        overlayEnabled = port.snapshot().enabled
                    }
                },
                onOpenOverlayPanel = overlayPort?.let { { overlayPanelOpen = true } }
            )
        }
        if (overlayPort != null && overlayPanelOpen) {
            DshOverlayPanel(
                port = overlayPort,
                onDismiss = {
                    overlayPanelOpen = false
                    overlayEnabled = overlayPort.snapshot().enabled
                },
                onNotice = viewModel::postNotice
            )
        }
        if (consolePort != null && destination == DshWorkspaceDestination.CONSOLE) {
            DshConsoleRoute(
                port = consolePort,
                modifier = Modifier.fillMaxSize(),
                maintenancePort = maintenanceConsolePort,
                pluginAdminPort = pluginAdminPort,
                // Only offered when the runtime actually injected a terminal owner.
                onOpenTerminal = terminalPort?.let { { destination = DshWorkspaceDestination.TERMINAL } },
                emergencyRecovery = emergencyRecovery,
                onBack = {
                    destination = DshWorkspaceDestinationPolicy.backDestination(destination)
                }
            )
        }
        if (terminalPort != null && destination == DshWorkspaceDestination.TERMINAL) {
            DshTerminalPage(port = terminalPort, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Opens a validated external http(s) URL through Android, never inside the DSH WebView.
 * The callback form is used when the host has its own browser surface.
 */
private fun openExternalLink(
    context: android.content.Context,
    url: String,
    onOpenExternal: ((android.net.Uri) -> Unit)?
): Boolean {
    if (!DshNavigationPolicy.isOpenableExternalUrl(url)) return false
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    if (onOpenExternal != null) {
        return runCatching { onOpenExternal(uri) }.isSuccess
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

private const val DEFAULT_ACCOUNT_POPUP_HOLD_MILLIS = 15_000L

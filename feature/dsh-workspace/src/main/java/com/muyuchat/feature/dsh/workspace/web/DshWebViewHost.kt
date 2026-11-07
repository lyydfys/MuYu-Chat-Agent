package com.muyuchat.feature.dsh.workspace.web

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaTextField

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.feature.dsh.workspace.DshBlockReason
import com.muyuchat.feature.dsh.workspace.DshDownloadInspection
import com.muyuchat.feature.dsh.workspace.DshDownloadPolicy
import com.muyuchat.feature.dsh.workspace.DshDownloadRequest
import com.muyuchat.feature.dsh.workspace.DshFirstFrameGate
import com.muyuchat.feature.dsh.workspace.DshNavigationPolicy
import com.muyuchat.feature.dsh.workspace.DshPopupAbandonReason
import com.muyuchat.feature.dsh.workspace.DshPopupCoordinator
import com.muyuchat.feature.dsh.workspace.DshWebErrorKind
import com.muyuchat.feature.dsh.workspace.DshWorkspaceUiState
import com.muyuchat.feature.dsh.workspace.DshWebHostCallbacks
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.FrameLayout

/** Bounded `data:` download waiting for the SAF create-document picker. */
internal data class DshSaveFileRequest(
    val bytes: ByteArray,
    val mimeType: String,
    val suggestedName: String
)

internal class DshSaveFileContract : ActivityResultContract<DshSaveFileRequest, Uri?>() {
    override fun createIntent(context: Context, input: DshSaveFileRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * Compose host for the official DSH page.
 *
 * This composable owns exactly one Android WebView per [DshWorkspaceUiState.Web] generation and
 * never persists it: destroying/recreating the view is safe because the HttpOnly cookie (not the
 * URL) carries the session. It is the only place Android WebView platform objects are created.
 */
@Composable
internal fun DshWebViewHost(
    state: DshWorkspaceUiState.Web,
    callbacks: DshWebHostCallbacks,
    controller: DshWebViewController,
    onFirstFrameRendered: (Long) -> Unit,
    popupCoordinator: DshPopupCoordinator,
    accountAuthMatcher: DshAccountAuthUrlMatcher,
    accountPopupHoldMillis: Long,
    accountLogin: DshAccountLoginPort? = null,
    /**
     * Account-path reply probe sink.
     *
     * Non-null enables the read-only DOM observer (also gated by [accountReplyProbeEnabled]); the
     * feature calls it with a finished burst of new page text so the app can raise the same overlay
     * bubble it raises for the MCA-gateway path. Null keeps the observer completely off.
     */
    onAccountReply: ((String) -> Unit)? = null,
    /** User-facing switch for the probe; false means the page is never observed or injected. */
    accountReplyProbeEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestState = rememberUpdatedState(state)
    val latestCallbacks = rememberUpdatedState(callbacks)
    // The composed host survives a runtime restart, while the generation it displays changes.
    // Report the current generation (not a frozen creation-time epoch) and reset the one-shot
    // latch below when the generation advances, so the new page can be observed as rendered.
    val latestEpoch = rememberUpdatedState(state.epoch)
    val latestOnFirstFrameRendered = rememberUpdatedState(onFirstFrameRendered)
    // Exactly one visibility observation per host generation. The gate filters error pages and the
    // token URL; the ViewModel adds the epoch + READY gate; the runtime owner records the stage
    // only after the readiness gate has published PLUGINS_LOADED.
    val firstFrameGate = remember { DshFirstFrameGate() }

    // Diagnostic builds (android:debuggable) get WebView remote debugging, JS error capture and a
    // DOM probe. Release builds run the exact same code paths with every hook disabled.
    val diagnosticsEnabled = remember(context) {
        DshWebDiagnostics.isDebuggable(context.applicationContext)
    }
    val diagnostics = remember(diagnosticsEnabled) { DshWebDiagnostics(diagnosticsEnabled) }
    val dialogs = remember { DshJsDialogController() }
    val fileChooser = remember { DshFileChooserCoordinator() }
    val fullscreen = controller.fullscreen
    val downloader = remember(context.applicationContext) { DshDownloader(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<DshSaveFileRequest?>(null) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    // Account-path reply probe: the page is only observed when the caller wired a sink and the switch
    // is on. The gate owns every policy decision; the injected script only batches new text.
    val latestOnAccountReply = rememberUpdatedState(onAccountReply)
    val accountReplyGate = remember { DshReplyDomGate() }
    val accountReplySilenceJob = remember { mutableStateOf<Job?>(null) }
    val accountReplyActive = accountReplyProbeEnabled && onAccountReply != null

    fun clearAccountReplySilence() {
        accountReplySilenceJob.value?.cancel()
        accountReplySilenceJob.value = null
    }

    fun handleAccountReplyProbe(text: String, control: String) {
        if (!accountReplyActive) return
        val sink = latestOnAccountReply.value ?: return
        val parsed = dshParseReplyProbeControl(control)
        val decision = accountReplyGate.observe(
            DshReplyDomTick(
                text = text,
                nowMillis = System.currentTimeMillis(),
                inputFocused = parsed.inputFocused,
                pageReset = parsed.pageReset
            )
        )
        if (decision is DshReplyDomDecision.Report) {
            clearAccountReplySilence()
            runCatching { sink(decision.text) }
            return
        }
        if (text.isEmpty()) return
        // Schedule exactly one silence resolution per growth burst; a later growth replaces it.
        clearAccountReplySilence()
        accountReplySilenceJob.value = scope.launch {
            delay(DshReplyDomLimits.SILENCE_MILLIS)
            val settled = accountReplyGate.observe(
                DshReplyDomTick(nowMillis = System.currentTimeMillis())
            )
            if (settled is DshReplyDomDecision.Report) {
                runCatching { sink(settled.text) }
            }
        }
    }

    fun reportFirstFrameIfVisible(visible: Boolean) {
        if (visible) latestOnFirstFrameRendered.value(latestEpoch.value)
    }

    // onPageCommitVisible carries no URL. Only report when the displayed document is this
    // generation's clean DSH page, so the initial/replacement `about:blank` and a token URL can
    // never be mistaken for the official first frame. A transient mismatch is not consumed by
    // the gate; the clean onPageFinished fallback below still reports the generation.
    fun displayedPageIsOfficial(): Boolean {
        val url = webViewHolder.value?.url ?: return false
        return DshNavigationPolicy.isCleanAuthenticatedUrl(url, latestState.value.origin)
    }

    // Forwards every WebView callback to the Route and adds debug-only page diagnostics. The
    // wrapper reads latestCallbacks/webViewHolder dynamically so it stays correct across
    // recompositions; in release builds DshWebDiagnostics is a no-op.
    val diagnosticCallbacks = remember(diagnostics) {
        object : DshWebHostCallbacks {
            override fun onExternalLink(url: String) {
                latestCallbacks.value.onExternalLink(url)
            }

            override fun onBlockedNavigation(reason: DshBlockReason) {
                latestCallbacks.value.onBlockedNavigation(reason)
            }

            override fun onPageStarted() {
                // Each committed load starts a fresh observation window. The ViewModel still
                // dedupes reports per generation, so in-page navigations cannot double-report.
                firstFrameGate.reset()
                firstFrameGate.onPageStarted()
                latestCallbacks.value.onPageStarted()
                // A new document cannot carry the previous burst: drop the reply-probe accumulator.
                clearAccountReplySilence()
                accountReplyGate.reset()
                // The WebView can report a real innerHeight while resolving viewport units
                // against a zero-height initial containing block; see DshPageLayoutShim.
                DshPageLayoutShim.apply(webViewHolder.value)
                diagnostics.installErrorCapture(webViewHolder.value)
            }

            override fun onPageCommitVisible() {
                latestCallbacks.value.onPageCommitVisible()
                if (displayedPageIsOfficial()) {
                    DshPageLayoutShim.apply(webViewHolder.value)
                    reportFirstFrameIfVisible(firstFrameGate.onPageCommitVisible())
                }
            }

            override fun onProgress(progress: Int) {
                latestCallbacks.value.onProgress(progress)
            }

            override fun onPageFinished(
                cleanUrl: String?,
                sameOrigin: Boolean,
                containsLaunchToken: Boolean
            ) {
                latestCallbacks.value.onPageFinished(cleanUrl, sameOrigin, containsLaunchToken)
                reportFirstFrameIfVisible(
                    firstFrameGate.onPageFinished(sameOrigin, containsLaunchToken)
                )
                if (sameOrigin && !containsLaunchToken) {
                    // Install before probing so a boot-time error is captured where possible, then
                    // log the DOM snapshot. The host only reaches here for a token-free page.
                    DshPageLayoutShim.apply(webViewHolder.value)
                    diagnostics.installErrorCapture(webViewHolder.value)
                    diagnostics.logProbe(webViewHolder.value)
                    // Release-visible account-path reply probe: independent of the debug flag above.
                    if (accountReplyActive) {
                        accountReplyGate.reset()
                        DshReplyDomObserver.inject(webViewHolder.value)
                    }
                }
            }

            override fun onMainFrameError(kind: DshWebErrorKind) {
                firstFrameGate.onMainFrameFailed()
                latestCallbacks.value.onMainFrameError(kind)
            }

            override fun onHttpUnauthorized() {
                firstFrameGate.onMainFrameFailed()
                latestCallbacks.value.onHttpUnauthorized()
            }

            override fun onHttpError(statusCode: Int) {
                firstFrameGate.onMainFrameFailed()
                latestCallbacks.value.onHttpError(statusCode)
            }

            override fun onRendererGone() {
                latestCallbacks.value.onRendererGone()
            }

            override fun onNavigationState(canGoBack: Boolean) {
                latestCallbacks.value.onNavigationState(canGoBack)
            }

            override fun onNotice(text: String) {
                latestCallbacks.value.onNotice(text)
            }

            override fun onRestoreRequestConsumed(id: Long) {
                latestCallbacks.value.onRestoreRequestConsumed(id)
            }

            override fun onWebHostDisposed(webGeneration: Long) {
                latestCallbacks.value.onWebHostDisposed(webGeneration)
            }

            // The chrome client only ever sees this wrapper, so every popup edge must be forwarded
            // here; without it a classified popup would be claimed and then silently dropped.
            override fun onPopupRequest(request: DshPopupRequest) {
                latestCallbacks.value.onPopupRequest(request)
            }

            override fun onPopupPreopened(requesterOrigin: String) {
                latestCallbacks.value.onPopupPreopened(requesterOrigin)
            }

            override fun onPopupAbandoned(reason: DshPopupAbandonReason) {
                latestCallbacks.value.onPopupAbandoned(reason)
            }
        }
    }
    val disposedViews = remember {
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    }
    val fullscreenFrame = remember { FrameLayout(context) }
    val chromeClientHolder = remember { mutableStateOf<DshWebChromeClient?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    // Fixed at creation: onDispose must identify the generation it actually hosted, not whatever
    // newer generation the ViewModel may already have installed.
    val hostWebGeneration = remember { state.webGeneration }

    fun dispose(view: WebView?) {
        // The hidden account popup (if one is being held for the official cancel observation) is
        // not part of this WebView; release it so a disposed host never leaks it.
        chromeClientHolder.value?.releaseRetainedAccountPopup()
        if (view == null || !disposedViews.add(view)) return
        clearAccountReplySilence()
        accountReplyGate.reset()
        // Remove the reply-probe bridge before the view is destroyed: a live interface on a destroyed
        // WebView is both a leak and a callback into post-destroy UI state.
        DshReplyDomObserver.detach(view)
        controller.detach(view)
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.webChromeClient = null }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
        if (webViewHolder.value === view) webViewHolder.value = null
    }

    val fileLauncher = rememberLauncherForActivityResult(DshFileChooserContract()) { uris ->
        fileChooser.deliver(uris)
    }
    LaunchedEffect(fileChooser.request?.id) {
        val request = fileChooser.request ?: return@LaunchedEffect
        fileLauncher.launch(request.intent)
    }

    val saveLauncher = rememberLauncherForActivityResult(DshSaveFileContract()) { uri ->
        val pending = pendingSave
        pendingSave = null
        when {
            pending == null -> Unit
            uri == null -> latestCallbacks.value.onNotice("已取消保存。")
            else -> scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                            stream.write(pending.bytes)
                        } != null
                    }.getOrDefault(false)
                }
                latestCallbacks.value.onNotice(
                    if (saved) {
                        // One-shot export: it writes this file only and never grants the folder.
                        "已保存（单次导出，不授予工作区目录）：${pending.suggestedName}"
                    } else {
                        "保存失败，请重试。"
                    }
                )
            }
        }
    }
    LaunchedEffect(pendingSave) {
        pendingSave?.let(saveLauncher::launch)
    }

    DisposableEffect(Unit) {
        onDispose {
            dispose(webViewHolder.value)
            dialogs.dismissAll()
            fileChooser.cancelPending()
            fullscreen.release()
            latestCallbacks.value.onWebHostDisposed(hostWebGeneration)
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            val origin = latestState.value.origin
            val entryUrl = latestState.value.loadUrl.takeIf { latestState.value.authenticatedEntry }
            val webViewClient = DshWebViewClient(origin, entryUrl) { diagnosticCallbacks }
            val chromeClient = DshWebChromeClient(
                origin = origin,
                callbacks = { diagnosticCallbacks },
                dialogs = dialogs,
                fileChooser = fileChooser,
                fullscreen = fullscreen,
                popupCoordinator = popupCoordinator,
                accountAuthMatcher = accountAuthMatcher,
                accountPopupHoldMillis = accountPopupHoldMillis,
                diagnosticsEnabled = diagnosticsEnabled,
                accountLogin = accountLogin
            )
            chromeClientHolder.value = chromeClient
            val downloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                val request = DshDownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength)
                when (val inspection = DshDownloadPolicy.classify(request)) {
                    is DshDownloadInspection.Http -> latestCallbacks.value.onNotice(
                        downloader.enqueue(inspection, origin).message
                    )
                    is DshDownloadInspection.Data -> pendingSave = DshSaveFileRequest(
                        bytes = inspection.bytes,
                        mimeType = inspection.mimeType,
                        suggestedName = inspection.suggestedFileName
                    )
                    is DshDownloadInspection.Rejected -> latestCallbacks.value.onNotice(
                        DshDownloadPolicy.messageFor(inspection.reason)
                    )
                }
            }
            DshWebViewFactory.create(
                context = viewContext,
                backgroundColor = backgroundColor,
                webViewClient = webViewClient,
                webChromeClient = chromeClient,
                downloadListener = downloadListener
            ).also { view ->
                webViewHolder.value = view
                controller.attach(view)
                if (accountReplyActive) {
                    // One bridge, two string parameters, no other capability. The callback arrives on
                    // the WebView bridge thread, so hop to the view thread before touching gate state.
                    DshReplyDomObserver.install(view) { text, control ->
                        view.post { handleAccountReplyProbe(text, control) }
                        Unit
                    }
                }
            }
        },
        update = { view -> view.setBackgroundColor(backgroundColor) },
        onRelease = { view -> dispose(view) }
    )

    LaunchedEffect(webViewHolder.value, state.loadId) {
        val view = webViewHolder.value ?: return@LaunchedEffect
        view.loadUrl(state.loadUrl)
    }

    LaunchedEffect(webViewHolder.value, state.restoreRequest?.id) {
        val view = webViewHolder.value ?: return@LaunchedEffect
        val request = state.restoreRequest ?: return@LaunchedEffect
        view.loadUrl(request.url)
        latestCallbacks.value.onRestoreRequestConsumed(request.id)
    }

    if (fullscreen.isVisible) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                fullscreenFrame.also(fullscreen::attachContainer)
            },
            onRelease = { fullscreen.attachContainer(null) }
        )
    }

    DshJsDialogHost(dialogs)
}

@Composable
private fun DshJsDialogHost(dialogs: DshJsDialogController) {
    val request = dialogs.request ?: return
    var text by remember(request.id) { mutableStateOf(request.defaultValue.orEmpty()) }
    McaDialog(
        onDismissRequest = { dialogs.cancel() },
        title = {
            Text(
                when (request.kind) {
                    DshJsDialogKind.ALERT -> "DSH 提示"
                    DshJsDialogKind.CONFIRM -> "DSH 确认"
                    DshJsDialogKind.PROMPT -> "DSH 输入"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                if (request.kind == DshJsDialogKind.PROMPT) {
                    McaTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("输入内容") },
                        singleLine = false
                    )
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text, onClick = { dialogs.confirm(text) }) { Text("确定") }
        },
        dismissButton = if (request.kind == DshJsDialogKind.ALERT) {
            null
        } else {
            { McaButton(style = McaButtonStyle.Text, onClick = { dialogs.cancel() }) { Text("取消") } }
        }
    )
}

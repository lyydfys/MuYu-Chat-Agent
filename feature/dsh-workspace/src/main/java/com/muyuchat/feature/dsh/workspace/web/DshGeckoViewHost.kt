package com.muyuchat.feature.dsh.workspace.web

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.muyuchat.feature.dsh.workspace.DshWebErrorKind
import com.muyuchat.feature.dsh.workspace.DshWebHostCallbacks
import com.muyuchat.feature.dsh.workspace.DshWorkspaceUiState
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

/**
 * GeckoView counterpart of [DshWebViewHost]: the second kernel of the DSH workspace.
 *
 * Scope: it renders exactly the URL the ViewModel already published (the same authenticated entry
 * the WebView path uses), reports the same first-frame and navigation signals, and classifies load
 * failures with the shared [DshWebErrorClassifier]. It deliberately does **not** reproduce the
 * WebView-only extras — JS dialogs, the file chooser, platform download handling, the account reply
 * DOM probe and popup pre-opening all stay on the WebView path. With GeckoView selected those
 * affordances degrade (external links still open in the system browser) which is exactly why the
 * system WebView remains the default kernel.
 *
 * Nothing here creates a second DSH runtime: it is a rendering engine over the same URL.
 */
@Composable
internal fun DshGeckoViewHost(
    state: DshWorkspaceUiState.Web,
    callbacks: DshWebHostCallbacks,
    controller: DshWebViewController,
    onFirstFrameRendered: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val latestCallbacks = rememberUpdatedState(callbacks)
    val latestEpoch = rememberUpdatedState(state.epoch)
    val latestGeneration = rememberUpdatedState(state.webGeneration)
    val latestOnFirstFrame = rememberUpdatedState(onFirstFrameRendered)

    val runtime = remember(context) { DshGeckoRuntimeHolder.getOrNull(context) }
    if (runtime == null) {
        LaunchedEffect(Unit) {
            latestCallbacks.value.onNotice("GeckoView 初始化失败，已回退系统 WebView。")
        }
        return
    }

    val session = remember(state.epoch, state.webGeneration) {
        createSession(
            runtime = runtime,
            callbacks = { latestCallbacks.value },
            onFirstFrame = { latestOnFirstFrame.value(latestEpoch.value) },
            controller = controller
        )
    }

    DisposableEffect(session) {
        onDispose {
            runCatching { session.session.close() }
            controller.detachEngine(session.handle)
            latestCallbacks.value.onWebHostDisposed(latestGeneration.value)
        }
    }

    LaunchedEffect(session, state.loadUrl) {
        // Exactly the URL the ViewModel published; the token travels inside it and is never logged.
        runCatching { session.session.loadUri(state.loadUrl) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> GeckoView(ctx).apply { setSession(session.session) } }
    )
}

/** One GeckoRuntime per process; a second `GeckoRuntime.create` throws. */
private object DshGeckoRuntimeHolder {
    private var runtime: GeckoRuntime? = null
    private var failed = false

    fun getOrNull(context: Context): GeckoRuntime? {
        runtime?.let { return it }
        if (failed) return null
        return runCatching { GeckoRuntime.create(context) }
            .onSuccess { runtime = it }
            .onFailure { failed = true }
            .getOrNull()
    }
}

/** Session plus the back-history handle the shared controller drives. */
private class GeckoSessionBinding(
    val session: GeckoSession,
    val handle: GeckoBackHandle
)

private class GeckoBackHandle(private val session: GeckoSession) : DshWebEngineHandle {
    var canGoBack: Boolean = false
        private set

    fun update(canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    override fun canGoBack(): Boolean = canGoBack

    override fun goBack() {
        runCatching { session.goBack() }
    }
}

private fun createSession(
    runtime: GeckoRuntime,
    callbacks: () -> DshWebHostCallbacks,
    onFirstFrame: () -> Unit,
    controller: DshWebViewController
): GeckoSessionBinding {
    val session = GeckoSession()
    val handle = GeckoBackHandle(session)
    val binding = GeckoSessionBinding(session, handle)

    session.progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            callbacks().onPageStarted()
            callbacks().onProgress(0)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!success) return
            callbacks().onProgress(100)
            callbacks().onPageCommitVisible()
            callbacks().onPageFinished(cleanUrl = null, sameOrigin = true, containsLaunchToken = false)
            onFirstFrame()
        }
    }

    session.navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            handle.update(canGoBack)
            controller.updateHistory(canGoBack)
            callbacks().onNavigationState(canGoBack)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            // The origin fence and the token rule already ran in the ViewModel; a failure here is a
            // transport problem, so it is classified with the same table the WebView path uses.
            callbacks().onMainFrameError(geckoErrorKind(error))
            // Returning null keeps GeckoView's own error page instead of retrying the URL.
            return null
        }
    }

    session.open(runtime)
    controller.attachEngine(handle)
    return binding
}

/**
 * Maps a GeckoView `WebRequestError` onto the shared coarse classification.
 *
 * These are the GeckoView equivalents of the WebView `ERROR_*` values the classifier already
 * understands, so the user sees the identical banner whichever kernel renders the page.
 */
internal fun geckoErrorKind(error: WebRequestError): DshWebErrorKind = when (error.code) {
    WebRequestError.ERROR_CONNECTION_REFUSED -> DshWebErrorKind.CONNECTION_REFUSED
    WebRequestError.ERROR_NET_TIMEOUT -> DshWebErrorKind.TIMEOUT
    WebRequestError.ERROR_OFFLINE,
    WebRequestError.ERROR_UNKNOWN_HOST -> DshWebErrorKind.NETWORK_UNAVAILABLE
    else -> DshWebErrorKind.OTHER
}

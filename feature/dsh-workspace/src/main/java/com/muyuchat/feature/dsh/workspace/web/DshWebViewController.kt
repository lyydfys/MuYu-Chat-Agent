package com.muyuchat.feature.dsh.workspace.web

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Fullscreen HTML video/audio support.
 *
 * WebChromeClient hands the page a native custom view; it is attached to a FrameLayout that the
 * Compose host always keeps available while fullscreen is active. The back button exits fullscreen
 * before it is allowed to reach the WebView history or the MCA tab host.
 */
internal class DshFullscreenVideoController {

    private var container: FrameLayout? = null
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var pending: Pair<View, WebChromeClient.CustomViewCallback>? = null

    var isVisible by mutableStateOf(false)
        private set

    fun attachContainer(frame: FrameLayout?) {
        container = frame
        if (frame == null) {
            pending = null
            return
        }
        mountPending(frame)
    }

    fun show(view: View, viewCallback: WebChromeClient.CustomViewCallback) {
        if (pending != null || customView != null) hideNow(notifyPage = true)
        pending = view to viewCallback
        isVisible = true
        container?.let(::mountPending)
    }

    /** WebView told the page it left fullscreen. */
    fun hideFromPage() = hideNow(notifyPage = true)

    /** Back-press path. Returns true when fullscreen consumed the press. */
    fun exitFromBack(): Boolean {
        if (!isVisible) return false
        hideNow(notifyPage = true)
        return true
    }

    fun release() {
        pending = null
        hideNow(notifyPage = false)
    }

    private fun mountPending(frame: FrameLayout) {
        val (view, viewCallback) = pending ?: return
        pending = null
        customView = view
        callback = viewCallback
        frame.removeAllViews()
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun hideNow(notifyPage: Boolean) {
        val view = customView
        customView = null
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        container?.removeAllViews()
        val viewCallback = callback
        callback = null
        isVisible = false
        if (notifyPage) runCatching { viewCallback?.onCustomViewHidden() }
    }
}

/**
 * Stable handle the Route uses for back/reload while the AndroidView factory owns the actual
 * WebView instance.
 */
/**
 * Back/stop surface of a non-WebView kernel.
 *
 * The route only needs "can the engine go back" and "go back" to keep the system back hierarchy
 * identical across kernels; everything else stays kernel-specific.
 */
internal interface DshWebEngineHandle {
    fun canGoBack(): Boolean
    fun goBack()
}

class DshWebViewController {

    private var webView: WebView? = null
    private var engine: DshWebEngineHandle? = null

    internal val fullscreen = DshFullscreenVideoController()

    var canGoBack by mutableStateOf(false)
        private set

    internal fun attach(view: WebView) {
        webView = view
        canGoBack = view.canGoBack()
    }

    internal fun detach(view: WebView) {
        if (webView === view) webView = null
        canGoBack = false
        fullscreen.release()
    }

    internal fun updateHistory(canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    /** A second kernel (GeckoView) took over rendering; it owns back from now on. */
    internal fun attachEngine(handle: DshWebEngineHandle) {
        engine = handle
        canGoBack = handle.canGoBack()
    }

    internal fun detachEngine(handle: DshWebEngineHandle) {
        if (engine === handle) engine = null
        canGoBack = false
        fullscreen.release()
    }

    /**
     * System back hierarchy: fullscreen video, then WebView history, then the caller's onExit.
     * Called from the Route's BackHandler on the main thread.
     */
    fun handleBack(): Boolean {
        if (fullscreen.exitFromBack()) return true
        val engineHandle = engine
        if (engineHandle != null && engineHandle.canGoBack()) {
            engineHandle.goBack()
            return true
        }
        val view = webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }
}

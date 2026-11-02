package com.muyuchat.feature.dsh.workspace.web

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import com.muyuchat.feature.dsh.workspace.BuildConfig
import com.muyuchat.feature.dsh.workspace.DshPopupAbandonReason
import com.muyuchat.feature.dsh.workspace.DshPopupBegin
import com.muyuchat.feature.dsh.workspace.DshPopupClaim
import com.muyuchat.feature.dsh.workspace.DshPopupClassification
import com.muyuchat.feature.dsh.workspace.DshPopupCoordinator
import com.muyuchat.feature.dsh.workspace.DshPopupPolicy
import com.muyuchat.feature.dsh.workspace.DshPopupWindowRelease
import com.muyuchat.feature.dsh.workspace.DshOrigin
import com.muyuchat.feature.dsh.workspace.DshUrlRedaction
import com.muyuchat.feature.dsh.workspace.DshWebHostCallbacks

/**
 * Chrome-client edges of the official DSH renderer.
 *
 * Permission policy: no dangerous Android permission is declared by this module, so camera and
 * microphone requests from the page are denied. The reference Android project grants none either;
 * enabling voice capture is an explicit app-level decision documented in README.md. Geolocation is
 * denied. Downloads are handled by DownloadListener in [DshWebViewHost]; fullscreen video and JS
 * dialogs are supported; console output is only read in debug builds and is always redacted.
 *
 * The official page opens external links with `window.open(url, '_blank', ...)` and
 * `<a target="_blank">` (verified in the pinned frontend: `ui-chat/apply.ts`, `WebBlock.tsx`,
 * account/plugin-market links). Android WebView blocks new windows unless multiple-window support
 * is enabled and `onCreateWindow` is implemented, so that flag is on and every popup is reduced to
 * one URL through the same origin/external/blocked policy as normal navigation. The popup WebView
 * never renders and is destroyed immediately; no popup content persists.
 */
internal class DshWebChromeClient(
    private val origin: DshOrigin,
    private val callbacks: () -> DshWebHostCallbacks,
    private val dialogs: DshJsDialogController,
    private val fileChooser: DshFileChooserCoordinator,
    private val fullscreen: DshFullscreenVideoController,
    /** Ticket table for popup handoffs; owned by the Route and shared with the browser port gate. */
    private val popupCoordinator: DshPopupCoordinator,
    /** Account owner's exact authorize-URL matcher; defaults to "not an account popup". */
    private val accountAuthMatcher: DshAccountAuthUrlMatcher,
    /**
     * Bounded lifetime of a hidden popup waiting for the authorize URL. The official frontend
     * pre-opens `about:blank` and replaces its location after an RPC, so this must be far longer
     * than one second; it is still bounded well below the official attempt lifetime.
     */
    private val accountPopupHoldMillis: Long,
    /** True only when the hosting app is `android:debuggable`; release builds stay silent. */
    private val diagnosticsEnabled: Boolean,
    /**
     * Account lane correlation seam. The chrome client only reports the trusted pre-open fact; the
     * lane owns the attempt, the deadline and the decision whether an authorize URL may be opened.
     */
    private val accountLogin: DshAccountLoginPort? = null
) : WebChromeClient() {

    init {
        require(accountPopupHoldMillis in 1L..DshPopupLimits.MAX_LIFETIME_MILLIS) {
            "popup hold must be a bounded positive duration"
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The one pre-opened account window kept alive after its handoff, so the official page closing
     * it can still reach this client. Never rendered and never holding an authorize URL.
     */
    private var retainedAccountPopup: KeptAccountPopup? = null

    /**
     * One pre-opened account window that is deliberately held after the authorize URL was handed to
     * the external browser.
     *
     * The official frontend closes exactly this window when the attempt it follows ends without
     * success (`cancelled`/`failed`/`expired`) and never after a success, which makes the close the
     * only host-observable cancel fact. [superseded] marks a window that a newer popup replaced: its
     * late close says nothing about the live attempt and must not be reported.
     */
    private class KeptAccountPopup(val webView: WebView) {
        var superseded = false
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks().onProgress(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = fileChooser.begin(fileChooserParams, filePathCallback)

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        callback.invoke(origin, false, false)
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        dialogs.showAlert(message, result)
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        dialogs.showConfirm(message, result)
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        dialogs.showPrompt(message, defaultValue, result)
        return true
    }

    override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
        fullscreen.show(view, callback)
    }

    override fun onHideCustomView() {
        fullscreen.hideFromPage()
    }

    /**
     * One-shot new-window interception (D04/D25).
     *
     * The official page opens external/account links via `window.open`/`target="_blank"`. The
     * temporary WebView exists only to receive the pending URL; it never renders and is destroyed
     * as soon as the URL is classified. The URL is never loaded in the DSH WebView and never handed
     * to an arbitrary Intent from this layer: same-origin popups navigate the official page, and
     * external popups become a typed ticket for the injected browser port.
     *
     * The account frontend pre-opens `about:blank` first and replaces its location after an RPC,
     * so a blank popup is held for [hiddenWindowHoldMillis] instead of the old one-second window.
     * When the deadline elapses nothing is opened and the host is told it was abandoned.
     *
     * One exception keeps the window alive instead of destroying it at the handoff: the account
     * popup. Closing that window is the only host-observable consequence of the official cancel
     * (the official frontend closes exactly this window when its attempt ends without success), so
     * it is held — bounded by the same attempt budget — until the page releases it, the budget
     * elapses or the host is disposed. It never renders and never loads the authorize URL.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        // At most one hidden window may outlive a handoff: a newer popup replaces it.
        destroySupersededAccountPopup()
        // The official account frontend opens `window.open('about:blank')` before `auth_init`
        // answers, then replaces the popup location with the authorize URL. Android exposes no
        // requester origin for a new window, so the pre-open is reported for every window of the
        // trusted page and the account lane decides: an attempt is bound to exactly one window,
        // and a pre-open that resolves to an ordinary external link releases it again.
        accountLogin?.onPopupPreopened(origin.value)
        val popup = WebView(view.context)
        popup.settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
        }
        var routed = false
        var hold: Runnable? = null
        // Ticket of the account handoff whose blank window is deliberately kept alive. The window
        // is kept only so the official page can release it (the one host-observable cancel fact).
        var accountHandoffTicketId: Long? = null
        var accountWindowKept = false
        val keptAccountWindow = KeptAccountPopup(popup)

        fun cancelHold() {
            hold?.let(mainHandler::removeCallbacks)
            hold = null
        }

        fun destroyPopup() {
            cancelHold()
            runCatching { popup.stopLoading() }
            mainHandler.post { runCatching { popup.destroy() } }
        }

        fun abandon(reason: DshPopupAbandonReason) {
            if (routed) return
            routed = true
            callbacks().onPopupAbandoned(reason)
            destroyPopup()
        }

        fun route(url: String?) {
            if (routed) return
            val candidate = url?.trim().orEmpty()
            // The pre-opened account popup has no target yet; the hold timer bounds the wait.
            if (candidate.isEmpty() || candidate.equals("about:blank", ignoreCase = true)) return
            when (val classification = DshPopupPolicy.classify(candidate, origin, accountAuthMatcher)) {
                DshPopupClassification.InWebView -> {
                    routed = true
                    view.loadUrl(candidate)
                    destroyPopup()
                }

                is DshPopupClassification.Handoff -> {
                    routed = true
                    when (val begin = popupCoordinator.begin(candidate, classification.kind)) {
                        is DshPopupBegin.Created -> {
                            when (val claim = popupCoordinator.claim(begin.request.ticketId)) {
                                is DshPopupClaim.HandedOff -> {
                                    if (classification.kind == DshPopupKind.ACCOUNT_AUTH) {
                                        // Keep this one window until the official page releases it:
                                        // that release is the cancel fact the host can observe.
                                        accountHandoffTicketId = claim.request.ticketId
                                        accountWindowKept = true
                                        retainedAccountPopup = keptAccountWindow
                                    }
                                    callbacks().onPopupRequest(claim.request)
                                }

                                else -> callbacks().onPopupAbandoned(DshPopupAbandonReason.REJECTED)
                            }
                        }

                        is DshPopupBegin.Rejected ->
                            callbacks().onPopupAbandoned(DshPopupAbandonReason.REJECTED)
                    }
                    if (!accountWindowKept) destroyPopup()
                }

                is DshPopupClassification.Blocked -> {
                    routed = true
                    callbacks().onBlockedNavigation(classification.reason)
                    destroyPopup()
                }
            }
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                popupView: WebView,
                request: WebResourceRequest
            ): Boolean {
                route(request.url?.toString())
                return true
            }

            override fun onPageStarted(popupView: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                route(url)
            }

            override fun onPageFinished(popupView: WebView, url: String?) {
                if (!url.isNullOrBlank() && !url.equals("about:blank", ignoreCase = true)) {
                    route(url)
                }
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                val ticketId = accountHandoffTicketId
                if (accountWindowKept && !keptAccountWindow.superseded && ticketId != null &&
                    popupCoordinator.releaseHandedOffWindow(ticketId) ==
                    DshPopupWindowRelease.RECORDED
                ) {
                    // The official page released the pre-opened window after the handoff: the
                    // official attempt ended without a success the host can observe (the official
                    // frontend closes it on cancelled/failed/expired and never after success).
                    keptAccountWindow.superseded = true
                    retainedAccountPopup = null
                    accountLogin?.onOfficialPopupReleased()
                    destroyPopup()
                } else {
                    abandon(DshPopupAbandonReason.PAGE_CLOSED)
                }
                mainHandler.post { runCatching { window.destroy() } }
            }
        }
        // A silent popup (no navigation, no close event) must not live forever.
        val lifetime = Runnable {
            hold = null
            if (accountWindowKept) {
                // The authorize URL already went to the external browser; the attempt has its own
                // bounded deadline, so releasing the hidden window neither cancels nor completes it.
                destroyPopup()
            } else {
                abandon(DshPopupAbandonReason.TIMEOUT)
            }
        }
        hold = lifetime
        mainHandler.postDelayed(lifetime, hiddenWindowHoldMillis())
        transport.webView = popup
        return runCatching {
            resultMsg.sendToTarget()
            true
        }.getOrElse {
            destroyPopup()
            false
        }
    }

    /**
     * Releases the previous handed-off account popup when a newer window is created.
     *
     * The window is only kept so the page can release it; a superseded one has no remaining fact to
     * report, so it is destroyed without notifying the account lane (a late close of it must never
     * cancel the attempt that replaced it).
     */
    private fun destroySupersededAccountPopup() {
        val previous = retainedAccountPopup ?: return
        retainedAccountPopup = null
        previous.superseded = true
        runCatching { previous.webView.stopLoading() }
        mainHandler.post { runCatching { previous.webView.destroy() } }
    }

    /**
     * Releases the hidden account popup when the WebView host is disposed.
     *
     * The sign-in attempt itself is app-scoped and must survive a tab switch, so it is deliberately
     * not cancelled here; only the hidden window is released so a disposed host cannot leak it. The
     * account lane keeps its own bounded deadline for the attempt either way.
     */
    fun releaseRetainedAccountPopup() {
        destroySupersededAccountPopup()
    }

    /**
     * Bounded lifetime of the hidden pre-opened window.
     *
     * A slow `auth_init` must not truncate the official sign-in, so while an account attempt is
     * live the hold follows the attempt's remaining budget instead of the fixed default. Both values
     * stay inside [DshPopupLimits.MAX_LIFETIME_MILLIS].
     */
    private fun hiddenWindowHoldMillis(): Long {
        val attemptBudget = accountLogin?.handoffBudgetMillis() ?: 0L
        val hold = maxOf(accountPopupHoldMillis, attemptBudget)
        return hold.coerceIn(1L, DshPopupLimits.MAX_LIFETIME_MILLIS)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (diagnosticsEnabled) {
            // Diagnostic APKs: surface every page console line at WARN (warnings/errors included)
            // with source and level so a blank-pane/JS failure is visible without adb filtering.
            // Source and message are redacted first; token-bearing URLs never reach the log.
            val source = DshUrlRedaction.redactText(consoleMessage.sourceId())
            val message = DshUrlRedaction.redactText(consoleMessage.message())
            Log.w(
                TAG,
                "console ${consoleMessage.messageLevel()} ${source}:${consoleMessage.lineNumber()} $message"
            )
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, DshUrlRedaction.redactText(consoleMessage.message()))
        }
        return true
    }

    private companion object {
        const val TAG = "DshWebView"
    }
}

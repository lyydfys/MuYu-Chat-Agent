package com.muyuchat.feature.dsh.workspace.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.muyuchat.feature.dsh.workspace.DshNavigationDecision
import com.muyuchat.feature.dsh.workspace.DshNavigationPolicy
import com.muyuchat.feature.dsh.workspace.DshOrigin
import com.muyuchat.feature.dsh.workspace.DshWebErrorClassifier
import com.muyuchat.feature.dsh.workspace.DshWebHostCallbacks

/**
 * The origin fence of the DSH WebView.
 *
 * - `http` same-origin main-frame navigations stay inside the WebView;
 * - validated external `http(s)` links are handed to the host, which opens them through an Android
 *   Intent (never in this WebView, never with the DSH cookie);
 * - every other scheme/URL is refused (javascript:, intent:, file:, content:, blob:, data:, ...);
 * - subresource and iframe requests are not rerouted; only top-level navigations cross this fence;
 * - the one-shot `?token=...` entry URL is never logged and is removed from history as soon as the
 *   official 303 cookie exchange lands on the clean URL;
 * - TLS errors are always cancelled; no handler ever calls proceed().
 */
internal class DshWebViewClient(
    private val origin: DshOrigin,
    /** The token URL this WebView generation was created with, when it was created with one. */
    private val authenticatedEntryUrl: String?,
    private val callbacks: () -> DshWebHostCallbacks
) : WebViewClient() {

    private var authenticatedEntryConsumed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return when (val decision = DshNavigationPolicy.classify(request.url?.toString(), origin)) {
            DshNavigationDecision.KeepInsideWebView -> false
            is DshNavigationDecision.OpenExternally -> {
                callbacks().onExternalLink(decision.url)
                true
            }
            is DshNavigationDecision.Blocked -> {
                callbacks().onBlockedNavigation(decision.reason)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        callbacks().onPageStarted()
        callbacks().onNavigationState(view.canGoBack())
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        callbacks().onPageCommitVisible()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        val sameOrigin = origin.matches(url)
        val containsLaunchToken = DshNavigationPolicy.hasLaunchTokenQuery(url)
        // Once the token entry has redirected to the clean cookie-authenticated URL, drop the
        // token URL from WebView history so back/diagnostics can never replay it.
        if (!authenticatedEntryConsumed && authenticatedEntryUrl != null &&
            sameOrigin && !containsLaunchToken && url != authenticatedEntryUrl
        ) {
            authenticatedEntryConsumed = true
            runCatching { view.clearHistory() }
        }
        callbacks().onPageFinished(
            // The raw clean URL is needed to derive the restore path; a URL that still carries the
            // launch token is never handed on at all.
            cleanUrl = if (sameOrigin && !containsLaunchToken) url else null,
            sameOrigin = sameOrigin,
            containsLaunchToken = containsLaunchToken
        )
        callbacks().onNavigationState(view.canGoBack())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        callbacks().onNavigationState(view.canGoBack())
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true && error != null) {
            callbacks().onMainFrameError(DshWebErrorClassifier.classify(error.errorCode))
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame != true) return
        val status = errorResponse?.statusCode ?: return
        if (status == 401 || status == 403) {
            callbacks().onHttpUnauthorized()
        } else {
            callbacks().onHttpError(status)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler?, error: SslError?) {
        // Loopback DSH is plain http; any TLS error is a real failure. Never proceed().
        handler?.cancel()
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest?) {
        request?.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        callbacks().onRendererGone()
        // Returning true prevents the app from being killed; the Compose host recreates the
        // WebView under a new generation key and disposes this instance in onRelease.
        return true
    }

    /**
     * No `shouldInterceptRequest`: the official page and the runtime are same-origin and there is
     * no native proxy/rewrite requirement. Keeping it unset means the page is rendered unchanged.
     */
}

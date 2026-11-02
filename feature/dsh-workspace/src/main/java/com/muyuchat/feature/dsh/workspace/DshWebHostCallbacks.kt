package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshPopupRequest

/**
 * Coarse, user-meaningful classification of main-frame WebView load failures.
 *
 * The numbers are the stable `WebViewClient.ERROR_*` values (documented by Android). They are
 * duplicated as constants here so the pure classifier stays free of android.* classes and can be
 * unit-tested on the JVM.
 */
enum class DshWebErrorKind {
    NETWORK_UNAVAILABLE,
    CONNECTION_REFUSED,
    TIMEOUT,
    OTHER
}

object DshWebErrorClassifier {
    // android.webkit.WebViewClient.ERROR_HOST_LOOKUP
    private const val ERROR_HOST_LOOKUP = -2

    // android.webkit.WebViewClient.ERROR_CONNECT
    private const val ERROR_CONNECT = -6

    // android.webkit.WebViewClient.ERROR_IO
    private const val ERROR_IO = -7

    // android.webkit.WebViewClient.ERROR_TIMEOUT
    private const val ERROR_TIMEOUT = -8

    fun classify(errorCode: Int): DshWebErrorKind = when (errorCode) {
        ERROR_CONNECT -> DshWebErrorKind.CONNECTION_REFUSED
        ERROR_TIMEOUT -> DshWebErrorKind.TIMEOUT
        ERROR_HOST_LOOKUP, ERROR_IO -> DshWebErrorKind.NETWORK_UNAVAILABLE
        else -> DshWebErrorKind.OTHER
    }

    fun messageFor(kind: DshWebErrorKind): String = when (kind) {
        DshWebErrorKind.NETWORK_UNAVAILABLE -> "网络连接不可用，无法连接本机 DSH 页面。"
        DshWebErrorKind.CONNECTION_REFUSED -> "DSH 页面连接被拒绝，运行时可能尚未就绪。"
        DshWebErrorKind.TIMEOUT -> "加载 DSH 页面超时。"
        DshWebErrorKind.OTHER -> "DSH 页面加载失败。"
    }
}

/** A recoverable page-level error shown as a banner above the still-composed WebView. */
sealed interface DshPageError {
    data class Network(val kind: DshWebErrorKind) : DshPageError
    data class Http(val statusCode: Int) : DshPageError
}

/** WebView page chrome; deliberately not part of the persisted navigation state. */
data class DshPageUiState(
    val loading: Boolean = true,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val error: DshPageError? = null
)

/** One-shot snackbar message with a monotonic identity so repeats are not swallowed. */
data class DshNotice(val id: Long, val text: String)

/** One-shot instruction for the live WebView to load a same-origin restore URL. */
data class DshRestoreRequest(val id: Long, val url: String)

/** Why a pending popup ended without being handed to a browser port. */
enum class DshPopupAbandonReason {
    /** The bounded wait for the authorize URL elapsed. */
    TIMEOUT,

    /** The page or the system closed the popup before a URL was handed off. */
    PAGE_CLOSED,

    /** The URL was refused by the origin fence or the ticket table. */
    REJECTED
}

/**
 * All callback edges from the WebView host into the workspace owner.
 *
 * The host (web package) never talks to the runtime client and never holds app state; the Route
 * translates these events into ViewModel calls and Android Intents. Keeping a single typed surface
 * (instead of a bag of nullable lambdas) is what makes the origin fence auditable.
 */
interface DshWebHostCallbacks {
    fun onExternalLink(url: String)
    fun onBlockedNavigation(reason: DshBlockReason)
    fun onPageStarted()
    fun onPageCommitVisible()
    fun onProgress(progress: Int)
    fun onPageFinished(cleanUrl: String?, sameOrigin: Boolean, containsLaunchToken: Boolean)
    fun onMainFrameError(kind: DshWebErrorKind)
    fun onHttpUnauthorized()
    fun onHttpError(statusCode: Int)
    fun onRendererGone()
    fun onNavigationState(canGoBack: Boolean)
    fun onNotice(text: String)

    /** The WebView loaded a one-shot restore URL; the request must not be replayed. */
    fun onRestoreRequestConsumed(id: Long)

    /** The whole WebView composable left composition (tab switch, failure shell, recreation). */
    fun onWebHostDisposed(webGeneration: Long)

    /**
     * A popup navigation was classified as external. The Route must hand the typed request to the
     * injected browser port; it must never load the URL in the DSH WebView or open it blindly.
     */
    fun onPopupRequest(request: DshPopupRequest) {}

    /**
     * The trusted DSH page pre-opened a blank popup on a user gesture
     * (`window.open('about:blank')`), which is how the official account frontend starts a sign-in
     * before `auth_init` answers.
     *
     * The Route forwards this fact to the account lane; the container never starts an attempt on
     * its own, never opens the blank page and never treats it as a login.
     */
    fun onPopupPreopened(requesterOrigin: String) {}

    /** A pending popup ended without a handoff; nothing was opened. */
    fun onPopupAbandoned(reason: DshPopupAbandonReason) {}
}

/** Human text for a refused navigation. The blocked URL itself is never echoed. */
fun DshBlockReason.messageFor(): String = when (this) {
    DshBlockReason.NON_HTTP_SCHEME -> "已阻止非 http(s) 链接。"
    DshBlockReason.CREDENTIALS_IN_URL -> "已阻止包含用户名或密码的链接。"
    DshBlockReason.URL_TOO_LONG -> "已阻止过长链接。"
    DshBlockReason.INVALID_URL -> "已阻止无效链接。"
}

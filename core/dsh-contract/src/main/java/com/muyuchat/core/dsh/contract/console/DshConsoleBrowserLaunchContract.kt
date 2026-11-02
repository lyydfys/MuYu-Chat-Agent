package com.muyuchat.core.dsh.contract.console

import java.net.URI

/**
 * D04 browser-popup seam of the DSH WebView.
 *
 * The official account frontend opens a popup (`window.open`) on `about:blank` first and replaces
 * its location with the authorize URL after the RPC answers. The official tool packages and the
 * account module stay unmodified; the App only owns the transport decision. That decision is typed
 * here: a validated [DshPopupRequest] is handed to the host implementation of
 * [DshExternalBrowserPort], never to an arbitrary Android Intent built inside the WebView layer.
 *
 * The request deliberately carries no token and no cookie. [toString] prints only the host, so an
 * accidental log cannot dump an authorize URL with `code`/`state` parameters.
 */
data class DshPopupRequest(
    val ticketId: Long,
    val url: String,
    val kind: DshPopupKind,
    val createdAtMillis: Long,
    val deadlineAtMillis: Long
) {
    init {
        require(ticketId >= 1L) { "popup ticket id must be positive" }
        require(url.isNotEmpty() && url.length <= DshPopupLimits.MAX_URL_CHARS) {
            "popup URL must be a bounded absolute URL"
        }
        require(url.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "popup URL must not carry control characters"
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw IllegalArgumentException("popup URL is malformed")
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "popup URL must be http(s)"
        }
        require(!uri.host.isNullOrBlank()) { "popup URL must carry a host" }
        require(uri.rawUserInfo == null) { "popup URL must not carry credentials" }
        require(createdAtMillis >= 0L) { "popup creation time must be non-negative" }
        require(deadlineAtMillis > createdAtMillis) { "popup deadline must follow its creation" }
        require(deadlineAtMillis - createdAtMillis <= DshPopupLimits.MAX_LIFETIME_MILLIS) {
            "popup lifetime exceeds the contract limit"
        }
    }

    /** Host only; the path/query (authorize code, state) is never part of a log line. */
    val host: String = requireNotNull(runCatching { URI(url).host }.getOrNull())

    fun isExpiredAt(nowMillis: Long): Boolean = nowMillis >= deadlineAtMillis

    override fun toString(): String =
        "DshPopupRequest(id=$ticketId, kind=$kind, host=$host, deadline=$deadlineAtMillis)"
}

/** The two external-link classes the container must keep separate. */
enum class DshPopupKind {
    /**
     * An official account authorization popup. Only the trusted account browser port may open it;
     * the DSH WebView must never load it, because it carries third-party cookies and callback
     * parameters of the official sign-in attempt.
     */
    ACCOUNT_AUTH,

    /**
     * An ordinary external link. The host may open it in its own browser surface, but it must not
     * receive the DSH cookie (a separate WebView/process, or the system browser).
     */
    NORMAL_EXTERNAL
}

/**
 * Classifies a popup URL as an account authorize URL. Implemented by the account owner, which knows
 * the exact authorize URL of the current official attempt (the URL is issued by DSH, not invented
 * here). A default implementation must return false: an unclassified popup degrades to a normal
 * external link, never to an account handoff.
 */
fun interface DshAccountAuthUrlMatcher {
    fun isAccountAuthUrl(url: String): Boolean
}

/** Why the host could not take a valid popup request. Nothing was opened in any of these cases. */
enum class DshPopupUnavailable {
    NO_BROWSER_HANDLER,
    USER_PRESENCE_REQUIRED,
    ACCOUNT_ATTEMPT_UNAVAILABLE,
    FEATURE_DISABLED
}

/** Why a popup request was refused rather than opened. */
enum class DshPopupRejection {
    /** The account attempt this popup belonged to has ended, expired or rotated. */
    STALE_REQUEST,

    /** The URL does not belong to the attempt the host is willing to hand off. */
    URL_MISMATCH,

    /** The popup was already handed off, cancelled or expired (single-use ticket). */
    REPLAYED
}

/** Outcome of handing a [DshPopupRequest] to the trusted browser port. */
sealed interface DshPopupHandoffResult {
    /** The host accepted the URL and owns the subsequent browser/attempt lifecycle. */
    data object HandedOff : DshPopupHandoffResult

    /** No browser surface was available; the popup must not fall back to the DSH WebView. */
    data class Unavailable(val reason: DshPopupUnavailable) : DshPopupHandoffResult

    /** The ticket was already handed off/cancelled, or the attempt rotated. */
    data class Rejected(val reason: DshPopupRejection) : DshPopupHandoffResult

    /** The popup deadline elapsed before the host could open it. */
    data object Expired : DshPopupHandoffResult

    /** The user or the page cancelled the popup before it was handed off. */
    data object Cancelled : DshPopupHandoffResult
}

/**
 * Trusted host seam for external browser launches.
 *
 * The implementation (app composition root / account lane) decides between Custom Tabs, the system
 * browser and any host browser surface, correlates the popup with the official account attempt and
 * enforces its own deadline. The WebView only supplies the validated request.
 */
interface DshExternalBrowserPort {
    suspend fun openPopup(request: DshPopupRequest): DshPopupHandoffResult
}

/** Bounds shared by the popup seam and the container. */
object DshPopupLimits {
    const val MAX_URL_CHARS = 4_096

    /** A hidden native popup is a transport detail; it must never live for an hour-long attempt. */
    const val MAX_LIFETIME_MILLIS = 120_000L

    /** Bounded pending tickets; a page cannot accumulate hidden windows. */
    const val MAX_PENDING_TICKETS = 4
}

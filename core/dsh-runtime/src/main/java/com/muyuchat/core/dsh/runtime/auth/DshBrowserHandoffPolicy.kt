package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult

/**
 * Typed Android external-browser seam.
 *
 * The launcher only ever receives an authorize URL that was validated against the official attempt.
 * It never receives a token, a verifier, a cookie or an `about:blank` placeholder, and it must not
 * return a fake success: a device with no browser activity answers [DshBrowserHandoffResult.NoHandler].
 */
fun interface DshExternalBrowserLauncher {
    fun open(url: String): DshBrowserHandoffResult
}

enum class DshPendingWindowState {
    /** Created by a trusted pre-open (`about:blank`); no URL may be opened from this state. */
    PENDING,
    /** One validated authorize URL is attached; handoff may now happen. */
    URL_READY,
    /** The URL was handed to the external browser; the WebView handle is spent. */
    HANDED_OFF,
    /** Closed by cancel, failure, teardown, expiry or host stop. Late URLs are rejected. */
    CLOSED
}

enum class DshWindowCloseReason {
    HANDED_OFF,
    CANCELLED,
    FAILED,
    TEARDOWN,
    EXPIRED,
    HOST_STOP
}

data class DshPendingAuthWindowView(
    val handleId: String,
    val attemptId: String,
    val requesterOrigin: String,
    val state: DshPendingWindowState,
    val hasAuthorizeUrl: Boolean,
    val deadlineEpochMillis: Long
) {
    override fun toString(): String =
        "DshPendingAuthWindowView(state=$state, hasUrl=$hasAuthorizeUrl)"
}

sealed interface DshWindowReservation {
    data class Reserved(val handleId: String, val reused: Boolean) : DshWindowReservation

    data class Rejected(val reason: DshAuthFailureCode, val closeWindow: Boolean) : DshWindowReservation
}

sealed interface DshAuthorizeAttachResult {
    data object Attached : DshAuthorizeAttachResult

    data class Rejected(
        val reason: DshAuthFailureCode,
        val closeWindow: Boolean,
        /** True when the window was already closed: a late URL must never reopen a browser. */
        val lateAfterClose: Boolean = false
    ) : DshAuthorizeAttachResult
}

data class DshHandoffOutcome(
    val result: DshBrowserHandoffResult,
    val openedUrl: String?,
    val closed: Boolean
)

/**
 * Owns the trusted pending auth window and the browser handoff decision.
 *
 * This is the D04a/D04b seam: the official Web client calls `window.open('about:blank')`, waits for
 * `auth_init`, then `popup.location.replace(authorizeUrl)`. The host therefore must keep a pending
 * handle alive for the whole official attempt (bounded by the attempt deadline), not for a magic
 * 1-second blank-page life or a 5-second popup lifetime. A blank handle is never handed to the
 * system browser: only [attachAuthorizeUrl] promotes it to [DshPendingWindowState.URL_READY], and
 * only [handOff] opens exactly that validated URL.
 *
 * Every transition is pure JVM state; the Android side only supplies the trusted Web origin, the
 * handle id from the native window creation callback and the real launcher.
 */
class DshBrowserHandoffPolicy(
    private val bounds: DshAuthAttemptBounds = DshAuthAttemptBounds(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val windows = LinkedHashMap<String, Window>()

    private class Window(
        val handleId: String,
        val attemptId: String,
        val requesterOrigin: String,
        val deadlineEpochMillis: Long
    ) {
        var state: DshPendingWindowState = DshPendingWindowState.PENDING
        var officialAuthorizeUrl: String? = null
        var openedUrl: String? = null
    }

    /**
     * Reserves the pending handle created by a trusted pre-open.
     *
     * Only the exact trusted DSH Web origin is accepted. A duplicate reservation for the same
     * attempt returns the existing handle instead of creating a second window (the official module
     * also returns the current attempt rather than starting another one).
     */
    fun reserve(
        handleId: String,
        attemptId: String,
        requesterOrigin: String,
        trustedWebOrigin: String,
        deadlineEpochMillis: Long
    ): DshWindowReservation {
        synchronized(lock) {
            if (requesterOrigin != trustedWebOrigin) {
                return DshWindowReservation.Rejected(
                    DshAuthFailureCode.POPUP_ORIGIN_UNTRUSTED,
                    true
                )
            }
            val existing = windows[handleId]
            if (existing != null) {
                if (existing.state == DshPendingWindowState.CLOSED ||
                    existing.state == DshPendingWindowState.HANDED_OFF
                ) {
                    return DshWindowReservation.Rejected(DshAuthFailureCode.PROTOCOL_ERROR, false)
                }
                return DshWindowReservation.Reserved(existing.handleId, reused = true)
            }
            val live = windows.values.firstOrNull {
                it.attemptId == attemptId &&
                    (it.state == DshPendingWindowState.PENDING ||
                        it.state == DshPendingWindowState.URL_READY)
            }
            if (live != null) return DshWindowReservation.Reserved(live.handleId, reused = true)
            if (deadlineEpochMillis <= clock()) {
                return DshWindowReservation.Rejected(DshAuthFailureCode.ATTEMPT_TIMEOUT, true)
            }
            windows[handleId] = Window(handleId, attemptId, requesterOrigin, deadlineEpochMillis)
            return DshWindowReservation.Reserved(handleId, reused = false)
        }
    }

    /**
     * Attaches the authorize URL the official `auth_init` produced. [candidateUrl] is what the
     * client asked to open; it must equal the official URL except for the official theme parameter.
     * A URL that arrives after the window closed is rejected and never opened (late-URL guard).
     */
    fun attachAuthorizeUrl(
        handleId: String,
        officialAuthorizeUrl: String,
        candidateUrl: String = officialAuthorizeUrl
    ): DshAuthorizeAttachResult {
        synchronized(lock) {
            val window = windows[handleId]
                ?: return DshAuthorizeAttachResult.Rejected(
                    DshAuthFailureCode.PROTOCOL_ERROR,
                    closeWindow = false,
                    lateAfterClose = true
                )
            if (window.state == DshPendingWindowState.CLOSED ||
                window.state == DshPendingWindowState.HANDED_OFF
            ) {
                return DshAuthorizeAttachResult.Rejected(
                    DshAuthFailureCode.PROTOCOL_ERROR,
                    closeWindow = false,
                    lateAfterClose = true
                )
            }
            if (clock() >= window.deadlineEpochMillis) {
                closeLocked(handleId, DshWindowCloseReason.EXPIRED)
                return DshAuthorizeAttachResult.Rejected(DshAuthFailureCode.ATTEMPT_TIMEOUT, true)
            }
            if (!DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(
                    officialAuthorizeUrl,
                    candidateUrl,
                    bounds
                )
            ) {
                closeLocked(handleId, DshWindowCloseReason.FAILED)
                return DshAuthorizeAttachResult.Rejected(
                    DshAuthFailureCode.AUTHORIZE_URL_INVALID,
                    true
                )
            }
            val previous = window.officialAuthorizeUrl
            if (previous != null && previous != officialAuthorizeUrl) {
                closeLocked(handleId, DshWindowCloseReason.FAILED)
                return DshAuthorizeAttachResult.Rejected(DshAuthFailureCode.PROTOCOL_ERROR, true)
            }
            window.officialAuthorizeUrl = officialAuthorizeUrl
            window.openedUrl = candidateUrl
            window.state = DshPendingWindowState.URL_READY
            return DshAuthorizeAttachResult.Attached
        }
    }

    /**
     * Hands the attached URL to the external browser exactly once.
     *
     * A window that is still [DshPendingWindowState.PENDING] (blank only) is never opened, so a
     * slow `auth_init` cannot leak a blank page or an unrelated site into the system browser.
     */
    fun handOff(handleId: String, launcher: DshExternalBrowserLauncher): DshHandoffOutcome {
        synchronized(lock) {
            val window = windows[handleId]
                ?: return DshHandoffOutcome(
                    DshBrowserHandoffResult.Rejected("unknown_handle"),
                    openedUrl = null,
                    closed = false
                )
            if (window.state != DshPendingWindowState.URL_READY) {
                val reason = when (window.state) {
                    DshPendingWindowState.PENDING -> "blank_window_never_opened"
                    DshPendingWindowState.HANDED_OFF -> "already_handed_off"
                    DshPendingWindowState.CLOSED -> "window_closed"
                    DshPendingWindowState.URL_READY -> "unreachable"
                }
                return DshHandoffOutcome(
                    DshBrowserHandoffResult.Rejected(reason),
                    openedUrl = null,
                    closed = window.state == DshPendingWindowState.CLOSED
                )
            }
            val url = requireNotNull(window.openedUrl)
            val result = launcher.open(url)
            return when (result) {
                DshBrowserHandoffResult.Opened -> {
                    window.state = DshPendingWindowState.HANDED_OFF
                    closeLocked(handleId, DshWindowCloseReason.HANDED_OFF)
                    DshHandoffOutcome(result, openedUrl = url, closed = true)
                }
                DshBrowserHandoffResult.NoHandler,
                is DshBrowserHandoffResult.Rejected -> {
                    closeLocked(handleId, DshWindowCloseReason.FAILED)
                    DshHandoffOutcome(result, openedUrl = null, closed = true)
                }
            }
        }
    }

    /** Closes one window. Idempotent; a closed window can never be reopened by a late URL. */
    fun close(handleId: String, reason: DshWindowCloseReason): Boolean {
        synchronized(lock) {
            return closeLocked(handleId, reason)
        }
    }

    /** Closes every window whose attempt deadline elapsed; returns the closed handle ids. */
    fun expire(now: Long = clock()): List<String> {
        synchronized(lock) {
            val expired = windows.values
                .filter {
                    now >= it.deadlineEpochMillis &&
                        (it.state == DshPendingWindowState.PENDING ||
                            it.state == DshPendingWindowState.URL_READY)
                }
                .map { it.handleId }
            expired.forEach { closeLocked(it, DshWindowCloseReason.EXPIRED) }
            return expired
        }
    }

    fun windowView(handleId: String): DshPendingAuthWindowView? {
        synchronized(lock) {
            val window = windows[handleId] ?: return null
            return DshPendingAuthWindowView(
                handleId = window.handleId,
                attemptId = window.attemptId,
                requesterOrigin = window.requesterOrigin,
                state = window.state,
                hasAuthorizeUrl = window.officialAuthorizeUrl != null,
                deadlineEpochMillis = window.deadlineEpochMillis
            )
        }
    }

    fun openWindowCount(): Int {
        synchronized(lock) {
            return windows.values.count {
                it.state == DshPendingWindowState.PENDING || it.state == DshPendingWindowState.URL_READY
            }
        }
    }

    private fun closeLocked(handleId: String, reason: DshWindowCloseReason): Boolean {
        val window = windows[handleId] ?: return false
        if (window.state == DshPendingWindowState.CLOSED) return false
        window.state = DshPendingWindowState.CLOSED
        check(reason != DshWindowCloseReason.HANDED_OFF || window.openedUrl != null) {
            "a handed-off window must carry the validated authorize URL"
        }
        return true
    }
}


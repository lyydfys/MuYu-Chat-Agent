package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView
import com.muyuchat.core.dsh.contract.auth.DshAuthCallbackRequest
import com.muyuchat.core.dsh.contract.auth.DshAuthCallbackVerdict
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshAuthRejectionCode
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.contract.auth.DshLoginSource
import java.util.UUID

/** Host-side phase of the account sign-in orchestration. The official module remains authoritative. */
enum class DshHostAuthPhase {
    IDLE,
    OPENING_POPUP,
    WAITING_CALLBACK,
    EXCHANGING,
    COMMITTING,
    AUTHENTICATED,
    CANCELLED,
    FAILED,
    EXPIRED,
    UNKNOWN;

    val isActive: Boolean
        get() = this == OPENING_POPUP || this == WAITING_CALLBACK || this == EXCHANGING || this == COMMITTING
}

/**
 * One side effect the hosting adapter must execute after a transition.
 *
 * The machine never talks to Android, the official module or the credential store directly: the
 * adapter owns the trusted popup handle, the external browser intent and the official RPCs. That is
 * what keeps this file pure JVM-testable and keeps account secrets out of the host.
 */
sealed interface DshAuthEffect {
    /** Close exactly one trusted pending popup handle (never a user-visible window). */
    data class ClosePendingWindow(val handleId: String) : DshAuthEffect

    /** Ask the official module to cancel/release the attempt (official `cancelSignIn`). */
    data class CancelOfficialAttempt(val attemptId: String) : DshAuthEffect

    /** Hand the raw callback back to the official module (it owns code/verifier). */
    data class SubmitCallback(val attemptId: String) : DshAuthEffect

    /** Open the validated authorize URL in the system browser / Custom Tab. */
    data class OpenExternalBrowser(val attemptId: String, val authorizeUrl: String) : DshAuthEffect
}

data class DshAuthTransition(
    val phase: DshHostAuthPhase,
    val effects: List<DshAuthEffect> = emptyList(),
    val failure: DshAuthFailureCode? = null,
    /** Official cancel during `committing` is deferred until the official `done` settles. */
    val cancelDeferred: Boolean = false
)

/** Callback handling result: the HTTP status the fixed official route must answer plus effects. */
data class DshCallbackOutcome(
    val verdict: DshAuthCallbackVerdict,
    val transition: DshAuthTransition
)

data class DshAuthSnapshot(
    val phase: DshHostAuthPhase,
    val attemptId: String?,
    val authorizeUrl: String?,
    val expiresAtEpochMillis: Long?,
    val lastFailure: DshAuthFailureCode?
)

/**
 * Host-side state machine for one DeepSeek account sign-in attempt.
 *
 * Scope split (do not blur it):
 * - the official `dsh-deepseek-account-platform` module owns state/verifier/PKCE, `auth_init`,
 *   `auth_exchange`, the grant and the credential store; its attempt phases are
 *   `initializing -> waiting-browser -> exchanging -> committing -> succeeded|cancelled|failed|expired`;
 * - this machine owns only what the Android host must add: a bounded attempt deadline, a trusted
 *   popup handle bound to the one attempt, the external-browser handoff decision, callback
 *   admission (400/410 with the official statuses), the one host-observable official cancel fact
 *   (the page releasing the pre-opened window, see [onOfficialPopupReleased]) and a typed terminal
 *   state that survives a process restart as `UNKNOWN` instead of a fake success.
 *
 * The official attempt deadline defaults to 600_000 ms (max 3_600_000 ms) and starts before
 * `auth_init`; `auth_init.expires_in` may only shorten it. `committing` clears the timer because the
 * official module already stopped racing the browser there.
 */
class DshAccountSignInMachine(
    private val bounds: DshAuthAttemptBounds = DshAuthAttemptBounds(),
    /** Exact trusted DSH Web origin allowed to create the pending popup handle. */
    private val trustedWebOrigin: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val attemptIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private var phase: DshHostAuthPhase = DshHostAuthPhase.IDLE
    private var attemptId: String? = null
    private var deadlineEpochMillis: Long = 0L
    private var startedAtEpochMillis: Long = 0L
    private var officialAuthorizeUrl: String? = null
    private var pendingHandleId: String? = null
    private var callbackConsumed: Boolean = false
    private var lastFailure: DshAuthFailureCode? = null

    fun snapshot(): DshAuthSnapshot = synchronized(lock) auth@{
        DshAuthSnapshot(
            phase = phase,
            attemptId = attemptId,
            authorizeUrl = officialAuthorizeUrl,
            expiresAtEpochMillis = if (phase.isActive) deadlineEpochMillis else null,
            lastFailure = lastFailure
        )
    }

    /**
     * Starts one attempt. Rejected with [DshAuthFailureCode.ATTEMPT_BUSY] while an official attempt
     * is active (the official module also returns the current state instead of starting a second
     * attempt) or while a prior attempt's outcome is still [DshHostAuthPhase.UNKNOWN].
     *
     * The official Web client has already called `window.open('about:blank')`; the next trusted
     * step must be [onPopupWindowCreated]. No URL is opened here.
     */
    fun requestSignIn(callbackOrigin: String, loginSource: DshLoginSource): DshAuthTransition =
        synchronized(lock) auth@{
            if (phase.isActive || phase == DshHostAuthPhase.UNKNOWN) {
                return@auth busy(DshAuthFailureCode.ATTEMPT_BUSY)
            }
            if (!DshAuthUrlPolicy.isValidCallbackOrigin(callbackOrigin)) {
                lastFailure = DshAuthFailureCode.CALLBACK_ORIGIN_INVALID
                return@auth DshAuthTransition(phase, failure = lastFailure)
            }
            val now = clock()
            startedAtEpochMillis = now
            deadlineEpochMillis = now + bounds.defaultAttemptMillis.coerceIn(1L, bounds.maxAttemptMillis)
            attemptId = attemptIdFactory()
            officialAuthorizeUrl = null
            pendingHandleId = null
            callbackConsumed = false
            lastFailure = null
            phase = DshHostAuthPhase.OPENING_POPUP
            // loginSource is validated by the contract enum; the official module receives it.
            DshAuthTransition(phase)
        }

    /**
     * The trusted native seam observed a pre-opened `about:blank` window.
     *
     * Only the exact trusted DSH Web origin may create that handle; every other origin is closed
     * and reported. A handle that arrives outside [DshHostAuthPhase.OPENING_POPUP] is a duplicate or
     * stale handle: it is closed and never promoted to an attempt.
     */
    fun onPopupWindowCreated(handleId: String, requesterOrigin: String): DshAuthTransition =
        synchronized(lock) auth@{
            if (requesterOrigin != trustedWebOrigin) {
                lastFailure = DshAuthFailureCode.POPUP_ORIGIN_UNTRUSTED
                return@auth DshAuthTransition(
                    phase,
                    effects = listOf(DshAuthEffect.ClosePendingWindow(handleId)),
                    failure = lastFailure
                )
            }
            if (phase != DshHostAuthPhase.OPENING_POPUP) {
                return@auth DshAuthTransition(
                    phase,
                    effects = listOf(DshAuthEffect.ClosePendingWindow(handleId))
                )
            }
            pendingHandleId = handleId
            DshAuthTransition(phase)
        }

    /**
     * The official `auth_init` returned the authorize URL.
     *
     * [openUrl] is what the client may actually open; it must equal [officialAuthorizeUrl] except
     * for the official `theme` conversion, otherwise nothing is opened. `expiresInMillis` (the
     * official `expires_in`) can only shorten the current deadline, exactly like the official
     * module's `Math.min(expiresAt, now + expires_in)`.
     */
    fun onAuthorizeUrl(
        handleId: String,
        officialAuthorizeUrl: String,
        expiresInMillis: Long? = null,
        openUrl: String = officialAuthorizeUrl
    ): DshAuthTransition = synchronized(lock) auth@{
        if (phase != DshHostAuthPhase.OPENING_POPUP && phase != DshHostAuthPhase.WAITING_CALLBACK) {
            lastFailure = DshAuthFailureCode.PROTOCOL_ERROR
            return@auth DshAuthTransition(
                phase,
                effects = listOf(DshAuthEffect.ClosePendingWindow(handleId)),
                failure = lastFailure
            )
        }
        if (pendingHandleId != handleId) {
            lastFailure = DshAuthFailureCode.AUTHORIZE_URL_INVALID
            return@auth DshAuthTransition(
                phase,
                effects = listOf(DshAuthEffect.ClosePendingWindow(handleId)),
                failure = lastFailure
            )
        }
        if (!DshAuthUrlPolicy.isValidAuthorizeUrl(officialAuthorizeUrl, bounds)) {
            lastFailure = DshAuthFailureCode.AUTHORIZE_URL_INVALID
            return@auth failAndRelease(DshAuthFailureCode.AUTHORIZE_URL_INVALID)
        }
        if (!DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(officialAuthorizeUrl, openUrl, bounds)) {
            return@auth failAndRelease(DshAuthFailureCode.AUTHORIZE_URL_INVALID)
        }
        // A second auth_init URL for the same attempt must be identical; anything else is a
        // protocol violation and is never opened.
        val previous = this.officialAuthorizeUrl
        if (previous != null && previous != officialAuthorizeUrl) {
            return@auth failAndRelease(DshAuthFailureCode.PROTOCOL_ERROR)
        }
        val now = clock()
        if (now >= deadlineEpochMillis) {
            return@auth expire()
        }
        if (expiresInMillis != null && expiresInMillis > 0) {
            val shortened = now + expiresInMillis * 1000L
            if (shortened < deadlineEpochMillis) deadlineEpochMillis = shortened
            // The absolute maximum still applies even if the server reports a longer window.
            val hardLimit = startedAtEpochMillis + bounds.maxAttemptMillis
            if (deadlineEpochMillis > hardLimit) deadlineEpochMillis = hardLimit
        }
        this.officialAuthorizeUrl = officialAuthorizeUrl
        phase = DshHostAuthPhase.WAITING_CALLBACK
        val id = requireNotNull(attemptId)
        DshAuthTransition(phase, effects = listOf(DshAuthEffect.OpenExternalBrowser(id, openUrl)))
    }

    /** The host finished trying to hand [handleId] to the external browser. */
    fun onBrowserHandoffFinished(
        handleId: String,
        result: DshBrowserHandoffResult
    ): DshAuthTransition = synchronized(lock) auth@{
        if (pendingHandleId != handleId || phase != DshHostAuthPhase.WAITING_CALLBACK) {
            return@auth DshAuthTransition(phase)
        }
        when (result) {
            DshBrowserHandoffResult.Opened -> DshAuthTransition(phase)
            DshBrowserHandoffResult.NoHandler ->
                failAndRelease(DshAuthFailureCode.BROWSER_HANDLER_MISSING)
            is DshBrowserHandoffResult.Rejected ->
                failAndRelease(DshAuthFailureCode.BROWSER_HANDLER_MISSING)
        }
    }

    /**
     * Admits one callback for the fixed official route.
     *
     * [officialStateMatches] must come from the official module's timing-safe comparison; the host
     * never holds the state value. Rejections mirror the official route exactly: 400 for method,
     * path, parameter shape or state; 410 for a callback that is not for a waiting attempt
     * (cancelled, expired, replayed, already exchanging/committing).
     */
    fun onCallback(
        request: DshAuthCallbackRequest,
        officialStateMatches: Boolean
    ): DshCallbackOutcome = synchronized(lock) auth@{
        fun reject(status: Int, code: DshAuthRejectionCode) = DshCallbackOutcome(
            DshAuthCallbackVerdict.Rejected(status, code),
            DshAuthTransition(phase)
        )
        if (!request.method.equals("GET", ignoreCase = true)) {
            return@auth reject(400, DshAuthRejectionCode.WRONG_METHOD)
        }
        if (request.path != bounds.callbackPath) {
            return@auth reject(400, DshAuthRejectionCode.WRONG_PATH)
        }
        if (request.query.containsKey("error")) {
            return@auth reject(400, DshAuthRejectionCode.CALLBACK_ERROR_PARAM)
        }
        when (request.valueCount("code")) {
            0 -> return@auth reject(400, DshAuthRejectionCode.MISSING_CODE)
            1 -> Unit
            else -> return@auth reject(400, DshAuthRejectionCode.DUPLICATE_CODE)
        }
        when (request.valueCount("state")) {
            0 -> return@auth reject(400, DshAuthRejectionCode.MISSING_STATE)
            1 -> Unit
            else -> return@auth reject(400, DshAuthRejectionCode.DUPLICATE_STATE)
        }
        if (!officialStateMatches) {
            return@auth reject(400, DshAuthRejectionCode.STATE_MISMATCH)
        }
        if (callbackConsumed) {
            return@auth reject(410, DshAuthRejectionCode.REPLAYED_CALLBACK)
        }
        if (phase != DshHostAuthPhase.WAITING_CALLBACK) {
            val code = when (phase) {
                DshHostAuthPhase.EXPIRED -> DshAuthRejectionCode.ATTEMPT_EXPIRED
                DshHostAuthPhase.IDLE,
                DshHostAuthPhase.CANCELLED,
                DshHostAuthPhase.FAILED,
                DshHostAuthPhase.UNKNOWN,
                DshHostAuthPhase.AUTHENTICATED,
                DshHostAuthPhase.EXCHANGING,
                DshHostAuthPhase.COMMITTING -> DshAuthRejectionCode.ATTEMPT_NOT_WAITING
                else -> DshAuthRejectionCode.ATTEMPT_NOT_WAITING
            }
            return@auth reject(410, code)
        }
        if (clock() >= deadlineEpochMillis) {
            val expired = expire()
            return@auth DshCallbackOutcome(
                DshAuthCallbackVerdict.Rejected(410, DshAuthRejectionCode.ATTEMPT_EXPIRED),
                expired
            )
        }
        callbackConsumed = true
        phase = DshHostAuthPhase.EXCHANGING
        val id = requireNotNull(attemptId)
        DshCallbackOutcome(
            DshAuthCallbackVerdict.Accepted,
            DshAuthTransition(phase, effects = listOf(DshAuthEffect.SubmitCallback(id)))
        )
    }

    /** The official `auth_exchange` failed (official `protocol` class or a transport failure). */
    fun onExchangeFailed(failure: DshAuthFailureCode = DshAuthFailureCode.PROTOCOL_ERROR): DshAuthTransition =
        synchronized(lock) auth@{
            if (phase != DshHostAuthPhase.EXCHANGING) {
                return@auth DshAuthTransition(phase, failure = lastFailure)
            }
            failAndRelease(failure)
        }

    /**
     * The official commit finished. Success is only published when the official credential store
     * confirmed the write; a store failure stays a typed failure, never a signed-in state.
     */
    fun onCommitFinished(
        success: Boolean,
        failure: DshAuthFailureCode = DshAuthFailureCode.CREDENTIAL_STORE_FAILED
    ): DshAuthTransition = synchronized(lock) auth@{
        if (phase != DshHostAuthPhase.EXCHANGING && phase != DshHostAuthPhase.COMMITTING) {
            return@auth DshAuthTransition(phase, failure = lastFailure)
        }
        if (success) {
            phase = DshHostAuthPhase.AUTHENTICATED
            lastFailure = null
            callbackConsumed = true
            DshAuthTransition(phase)
        } else {
            failAndRelease(failure)
        }
    }

    /**
     * The official credential store changed on disk after this attempt started.
     *
     * This is the only host-observable success fact: the official module writes its credential store
     * exactly when a grant is committed, and one host attempt is active at a time, so a store change
     * during WAITING_CALLBACK/EXCHANGING/COMMITTING is attributable to this attempt. It proves
     * presence only (never identity, token or account), and it is not accepted while the attempt is
     * still OPENING_POPUP (no handoff has happened yet).
     */
    fun onCredentialStoreObserved(): DshAuthTransition = synchronized(lock) auth@{
        if (!phase.isActive) return@auth DshAuthTransition(phase, failure = lastFailure)
        if (phase != DshHostAuthPhase.WAITING_CALLBACK &&
            phase != DshHostAuthPhase.EXCHANGING &&
            phase != DshHostAuthPhase.COMMITTING
        ) {
            return@auth DshAuthTransition(phase, failure = lastFailure)
        }
        phase = DshHostAuthPhase.AUTHENTICATED
        lastFailure = null
        callbackConsumed = true
        pendingHandleId = null
        DshAuthTransition(phase)
    }

    /** The official module entered `committing`; mirrors the official timer being cleared. */
    fun onCommitting(): DshAuthTransition = synchronized(lock) auth@{
        if (phase != DshHostAuthPhase.EXCHANGING) return@auth DshAuthTransition(phase)
        phase = DshHostAuthPhase.COMMITTING
        DshAuthTransition(phase)
    }

    /**
     * User cancel. Matches the official `cancelSignIn`: during `committing` the cancel is deferred
     * (the official module waits for `done` instead of aborting the commit) and reported as such.
     */
    fun cancel(): DshAuthTransition = synchronized(lock) auth@{
        when (phase) {
            DshHostAuthPhase.COMMITTING ->
                return@auth DshAuthTransition(phase, cancelDeferred = true)
            DshHostAuthPhase.OPENING_POPUP,
            DshHostAuthPhase.WAITING_CALLBACK,
            DshHostAuthPhase.EXCHANGING -> Unit
            else -> return@auth DshAuthTransition(phase)
        }
        val effects = buildList {
            pendingHandleId?.let { add(DshAuthEffect.ClosePendingWindow(it)) }
            attemptId?.let { add(DshAuthEffect.CancelOfficialAttempt(it)) }
        }
        phase = DshHostAuthPhase.CANCELLED
        lastFailure = DshAuthFailureCode.ATTEMPT_CANCELLED
        pendingHandleId = null
        DshAuthTransition(phase, effects = effects, failure = lastFailure)
    }

    /**
     * The official account UI released the pre-opened window that carried this attempt's authorize
     * URL (see `DshAccountLoginPort.onOfficialPopupReleased`).
     *
     * Grounding (pinned `dsh-client-ui-settings-account` + `dsh-deepseek-account-platform`): the
     * official `BrowserLogin` closes that window when the attempt it follows becomes `cancelled`,
     * `failed` or `expired`, and never on `succeeded` (the window was already navigated then). The
     * host therefore knows "no success is coming that it can see", but it cannot re-derive which
     * official phase ended the attempt and must not invent one.
     *
     * Emits no effect on purpose: nothing is left to close (the page closed the window) and nothing
     * may be cancelled (the official attempt is already terminal). `committing` is exempt: the
     * official credential store is writing the grant there, so a closed window proves nothing and
     * the attempt keeps its state until the official outcome or the host deadline settles it.
     */
    fun onOfficialPopupReleased(): DshAuthTransition = synchronized(lock) auth@{
        if (!phase.isActive) {
            // A late release of a window that belonged to an already-terminal attempt is not a new
            // fact: a finished attempt must never be re-labelled, least of all as a success.
            return@auth DshAuthTransition(phase, failure = lastFailure)
        }
        if (phase == DshHostAuthPhase.COMMITTING) {
            return@auth DshAuthTransition(phase, failure = lastFailure)
        }
        phase = DshHostAuthPhase.CANCELLED
        lastFailure = DshAuthFailureCode.OFFICIAL_POPUP_RELEASED
        pendingHandleId = null
        DshAuthTransition(phase, failure = lastFailure)
    }

    /** Deadline enforcement. `committing` never expires: the official module cleared its timer. */
    fun tick(now: Long = clock()): DshAuthTransition = synchronized(lock) auth@{
        if (phase == DshHostAuthPhase.OPENING_POPUP ||
            phase == DshHostAuthPhase.WAITING_CALLBACK ||
            phase == DshHostAuthPhase.EXCHANGING
        ) {
            if (now >= deadlineEpochMillis) return@auth expire()
        }
        DshAuthTransition(phase)
    }

    /**
     * The hosting process died while an attempt was in flight. The host cannot prove whether the
     * official commit landed, so the attempt becomes [DshHostAuthPhase.UNKNOWN] and must be
     * resolved by [resolveAfterRestart] from the official credential state; a new sign-in is
     * refused until then instead of starting over a possibly committed grant.
     */
    fun onProcessRestart(): DshAuthTransition = synchronized(lock) auth@{
        if (!phase.isActive) return@auth DshAuthTransition(phase)
        val effects = listOfNotNull(pendingHandleId?.let { DshAuthEffect.ClosePendingWindow(it) })
        phase = DshHostAuthPhase.UNKNOWN
        lastFailure = DshAuthFailureCode.PROCESS_RESTARTED
        pendingHandleId = null
        DshAuthTransition(phase, effects = effects, failure = lastFailure)
    }

    /**
     * Resolves an [DshHostAuthPhase.UNKNOWN] attempt from the official credential store (the only
     * authority). A present credential is signed-in; absence allows a clean new attempt.
     */
    fun resolveAfterRestart(credentialPresent: Boolean): DshAuthTransition = synchronized(lock) auth@{
        if (phase != DshHostAuthPhase.UNKNOWN) return@auth DshAuthTransition(phase)
        phase = if (credentialPresent) DshHostAuthPhase.AUTHENTICATED else DshHostAuthPhase.IDLE
        callbackConsumed = credentialPresent
        DshAuthTransition(phase, failure = lastFailure)
    }

    /**
     * Restores the [DshHostAuthPhase.UNKNOWN] host phase from the durable attempt marker when the
     * *app* process was killed while an attempt was in flight.
     *
     * The official attempt may still be alive in the separate DSH node process, so nothing is
     * claimed here: no success, no sign-out and no new attempt. The host refuses a second sign-in
     * until the outcome is resolved (see [supersedeUnknownAttempt] for the user-initiated case).
     * The marker is a host attempt id only; it never contains a state, code or token.
     */
    fun restoreAfterProcessRestart(markerAttemptId: String): DshAuthTransition =
        synchronized(lock) auth@{
            if (phase != DshHostAuthPhase.IDLE) {
                return@auth DshAuthTransition(phase, failure = lastFailure)
            }
            require(markerAttemptId.isNotBlank()) { "a restored attempt needs a host attempt id" }
            attemptId = markerAttemptId
            phase = DshHostAuthPhase.UNKNOWN
            lastFailure = DshAuthFailureCode.PROCESS_RESTARTED
            pendingHandleId = null
            DshAuthTransition(phase, failure = lastFailure)
        }

    /**
     * A new, user-initiated sign-in supersedes an [DshHostAuthPhase.UNKNOWN] outcome that the host
     * cannot prove either way. The previous outcome stays unclaimed (no success, no sign-out) and
     * only a fresh attempt may start from here.
     */
    fun supersedeUnknownAttempt(): DshAuthTransition = synchronized(lock) auth@{
        if (phase != DshHostAuthPhase.UNKNOWN) {
            return@auth DshAuthTransition(phase, failure = lastFailure)
        }
        phase = DshHostAuthPhase.IDLE
        callbackConsumed = false
        lastFailure = DshAuthFailureCode.PROCESS_RESTARTED
        DshAuthTransition(phase, failure = lastFailure)
    }

    /**
     * The hosting DSH runtime generation that owned the official attempt is gone (host stopped,
     * restarted or crashed). Its attempt cannot be correlated any more, so the host phase becomes
     * [DshHostAuthPhase.FAILED] with [DshAuthFailureCode.PROCESS_RESTARTED].
     *
     * No `cancelSignIn` effect is emitted: the process that owned the attempt no longer exists, and
     * a dead host cannot cancel it (same rule as [onProcessRestart]). No success is claimed either.
     */
    fun onRuntimeGenerationLost(): DshAuthTransition = synchronized(lock) auth@{
        if (!phase.isActive) return@auth DshAuthTransition(phase, failure = lastFailure)
        phase = DshHostAuthPhase.FAILED
        lastFailure = DshAuthFailureCode.PROCESS_RESTARTED
        pendingHandleId = null
        DshAuthTransition(phase, failure = lastFailure)
    }

    private fun busy(code: DshAuthFailureCode): DshAuthTransition {
        lastFailure = code
        return DshAuthTransition(phase, failure = code)
    }

    private fun expire(): DshAuthTransition {
        val effects = buildList {
            pendingHandleId?.let { add(DshAuthEffect.ClosePendingWindow(it)) }
            attemptId?.let { add(DshAuthEffect.CancelOfficialAttempt(it)) }
        }
        phase = DshHostAuthPhase.EXPIRED
        lastFailure = DshAuthFailureCode.ATTEMPT_TIMEOUT
        pendingHandleId = null
        return DshAuthTransition(phase, effects = effects, failure = lastFailure)
    }

    private fun failAndRelease(code: DshAuthFailureCode): DshAuthTransition {
        val effects = buildList {
            pendingHandleId?.let { add(DshAuthEffect.ClosePendingWindow(it)) }
            attemptId?.let { add(DshAuthEffect.CancelOfficialAttempt(it)) }
        }
        phase = DshHostAuthPhase.FAILED
        lastFailure = code
        pendingHandleId = null
        return DshAuthTransition(phase, effects = effects, failure = code)
    }

    fun attemptView(): DshAuthAttemptView = synchronized(lock) auth@{
        val id = attemptId ?: return DshAuthAttemptView(
            attemptId = "",
            phase = DshAuthAttemptPhase.IDLE,
            authorizeUrl = null,
            expiresAtEpochMillis = null
        )
        return DshAuthAttemptView(
            attemptId = id,
            phase = when (phase) {
                DshHostAuthPhase.IDLE -> DshAuthAttemptPhase.IDLE
                DshHostAuthPhase.OPENING_POPUP -> DshAuthAttemptPhase.INITIALIZING
                DshHostAuthPhase.WAITING_CALLBACK -> DshAuthAttemptPhase.WAITING_BROWSER
                DshHostAuthPhase.EXCHANGING -> DshAuthAttemptPhase.EXCHANGING
                DshHostAuthPhase.COMMITTING -> DshAuthAttemptPhase.COMMITTING
                DshHostAuthPhase.AUTHENTICATED -> DshAuthAttemptPhase.SUCCEEDED
                DshHostAuthPhase.CANCELLED -> DshAuthAttemptPhase.CANCELLED
                DshHostAuthPhase.FAILED -> DshAuthAttemptPhase.FAILED
                DshHostAuthPhase.EXPIRED -> DshAuthAttemptPhase.EXPIRED
                DshHostAuthPhase.UNKNOWN -> DshAuthAttemptPhase.OUTCOME_UNKNOWN
            },
            authorizeUrl = officialAuthorizeUrl,
            expiresAtEpochMillis = if (phase.isActive) deadlineEpochMillis else null,
            errorCode = lastFailure?.name
        )
    }
}



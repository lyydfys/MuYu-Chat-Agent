package com.muyuchat.core.dsh.contract.console

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView

/**
 * Bounded, credential-free projection of the host-side DeepSeek account attempt (D20 console).
 *
 * Ownership: the official `dsh-deepseek-account-platform` module owns the attempt, the authorize
 * URL, the PKCE verifier, the fixed `/oauth/callback` exchange, the grant and the credential
 * store. The app-side account lane owns only the host correlation (bounded deadline, trusted popup
 * binding, external-browser decision, terminal states) and exposes it as `DshAuthAttemptView`.
 * This projection is exactly that observation, reduced to display-safe facts:
 *
 * - no URL, origin, path, query, authorization code, PKCE verifier, cookie or token field;
 * - success is projected only from a host-observable fact: the official credential store file
 *   appeared/changed after this attempt started. That is presence, not identity, and the app never
 *   reads the file's contents; an unobserved success stays [DshConsoleAccountState.UNKNOWN];
 * - `attemptAgeMillis` is the age observed by this console projection since the account lane first
 *   reported the live attempt, never an invented official start timestamp;
 * - reading this projection must be cheap: it is produced by the app composition adapter from the
 *   account lane's in-memory attempt view.
 */
enum class DshConsoleAccountState {
    /** No attempt exists in this runtime generation; nothing has been started. */
    NOT_STARTED,

    /** An attempt exists and the host is preparing or waiting for the official browser handoff. */
    WAITING_BROWSER,

    /** The browser handoff is done and the host can only wait for the official callback result. */
    WAITING_CALLBACK,

    /** The bounded attempt deadline elapsed; this is not an account-token expiry claim. */
    TIMED_OUT,

    /** The host attempt was cancelled by the user or the pre-opened window was abandoned. */
    CANCELLED,

    /** The outcome cannot be proven app-side (process/host restart, or an unobservable success). */
    UNKNOWN,

    /** The host attempt reached a typed failure before any callback result. */
    FAILED,

    /**
     * The official credential store was written after this attempt started. This is a *presence*
     * observation (the official module only writes the store on a committed grant), never a claim
     * about which account or which token, and the file contents are never read.
     */
    SIGNED_IN
}

/** Short typed reason of the projected state. Fixed classes only; never owner text. */
enum class DshConsoleAccountReason {
    /** No reason is needed for the current state. */
    NONE,

    /** The host is waiting for the official `auth_init` authorize URL. */
    AUTHORIZE_URL_PENDING,

    /** The authorize URL was opened externally; the host is waiting for the official callback. */
    CALLBACK_PENDING,

    /** The official module admitted the callback and is exchanging/committing its grant. */
    OFFICIAL_EXCHANGE_PENDING,

    ATTEMPT_TIMEOUT,
    ATTEMPT_CANCELLED,

    /** Another attempt was active when sign-in was requested. */
    ATTEMPT_BUSY,

    /** No Android browser activity could accept the official authorize URL. */
    BROWSER_HANDLER_MISSING,

    /** The configured callback origin is not an accepted loopback origin. */
    CALLBACK_ORIGIN_INVALID,

    /** The candidate URL is not the official authorize URL shape. */
    AUTHORIZE_URL_INVALID,

    /** A popup was requested by an origin other than the trusted DSH Web origin. */
    POPUP_ORIGIN_UNTRUSTED,

    /** The pinned payload does not expose the official account module. */
    OFFICIAL_MODULE_MISSING,

    /** The official state comparison rejected the callback. */
    CALLBACK_STATE_MISMATCH,

    /** The official credential store refused or failed its write. */
    CREDENTIAL_STORE_FAILED,

    /** The official payload/protocol shape was violated. */
    PROTOCOL_ERROR,

    /** The hosting runtime generation was stopped, restarted or lost mid-attempt. */
    RUNTIME_GENERATION_LOST,

    /** The official attempt reported success, but the app cannot verify the credential. */
    OFFICIAL_OUTCOME_UNOBSERVED,

    /** A dispatched external step produced no verifiable result. */
    OUTCOME_UNVERIFIED,

    /** No account attempt source was injected into this console composition. */
    SOURCE_UNAVAILABLE,

    /** A typed failure not covered by the reasons above. */
    OTHER_FAILURE,

    /** The official credential store appeared/changed after this attempt started. */
    CREDENTIAL_STORE_PRESENT
}

/**
 * Immutable account row of the console snapshot. All fields are bounded enums or a bounded age;
 * there is deliberately no string field that could carry owner content.
 */
data class DshConsoleAccountProjection(
    val state: DshConsoleAccountState = DshConsoleAccountState.UNKNOWN,
    /**
     * Observed age of the correlated attempt in milliseconds, in
     * `0..`[MAX_ATTEMPT_AGE_MILLIS]. Zero when nothing was observed live (no attempt, a restored
     * unknown attempt, or an attempt that never became visible to this console process).
     */
    val attemptAgeMillis: Long = 0L,
    val reason: DshConsoleAccountReason = DshConsoleAccountReason.SOURCE_UNAVAILABLE
) {
    init {
        require(attemptAgeMillis in 0L..MAX_ATTEMPT_AGE_MILLIS) {
            "account attempt age must stay within the bounded attempt window"
        }
        require(state != DshConsoleAccountState.NOT_STARTED || attemptAgeMillis == 0L) {
            "a not-started account attempt cannot carry an age"
        }
        require((state == DshConsoleAccountState.NOT_STARTED) == (reason == DshConsoleAccountReason.NONE)) {
            "a not-started state needs no reason; every other state needs one"
        }
        require(reason != DshConsoleAccountReason.SOURCE_UNAVAILABLE ||
            state == DshConsoleAccountState.UNKNOWN
        ) {
            "a missing source is always an unknown outcome, never a started/waiting state"
        }
    }

    override fun toString(): String =
        "DshConsoleAccountProjection(state=$state, reason=$reason)"

    companion object {
        /** The official maximum attempt bound; a projection can never exceed it. */
        const val MAX_ATTEMPT_AGE_MILLIS: Long = 3_600_000L

        /**
         * Pure mapping of the account lane's attempt view. `view == null` means no source was
         * injected and stays [DshConsoleAccountReason.SOURCE_UNAVAILABLE].
         *
         * Official `succeeded` is projected as [DshConsoleAccountState.SIGNED_IN] because the
         * host-side owner only publishes it after the official credential store changed; the row
         * still says "presence observed", never which account or token.
         */
        fun from(
            view: DshAuthAttemptView?,
            attemptAgeMillis: Long
        ): DshConsoleAccountProjection {
            if (view == null) return DshConsoleAccountProjection()
            val age = attemptAgeMillis.coerceIn(0L, MAX_ATTEMPT_AGE_MILLIS)
            return when (view.phase) {
                DshAuthAttemptPhase.IDLE -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.NOT_STARTED,
                    attemptAgeMillis = 0L,
                    reason = DshConsoleAccountReason.NONE
                )

                // Host `OPENING_POPUP`: `auth_init` is in flight, no authorize URL exists yet.
                DshAuthAttemptPhase.INITIALIZING -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.WAITING_BROWSER,
                    attemptAgeMillis = age,
                    reason = DshConsoleAccountReason.AUTHORIZE_URL_PENDING
                )

                // Host `WAITING_CALLBACK`: the authorize URL was handed to the external browser.
                DshAuthAttemptPhase.WAITING_BROWSER -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.WAITING_CALLBACK,
                    attemptAgeMillis = age,
                    reason = DshConsoleAccountReason.CALLBACK_PENDING
                )

                DshAuthAttemptPhase.EXCHANGING,
                DshAuthAttemptPhase.COMMITTING -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.WAITING_CALLBACK,
                    attemptAgeMillis = age,
                    reason = DshConsoleAccountReason.OFFICIAL_EXCHANGE_PENDING
                )

                // The owner only reaches this phase after the official store changed on disk.
                DshAuthAttemptPhase.SUCCEEDED -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.SIGNED_IN,
                    attemptAgeMillis = 0L,
                    reason = DshConsoleAccountReason.CREDENTIAL_STORE_PRESENT
                )

                DshAuthAttemptPhase.CANCELLED -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.CANCELLED,
                    attemptAgeMillis = age,
                    reason = failureReason(view.errorCode)
                        ?: DshConsoleAccountReason.ATTEMPT_CANCELLED
                )

                DshAuthAttemptPhase.FAILED -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.FAILED,
                    attemptAgeMillis = age,
                    reason = failureReason(view.errorCode)
                        ?: DshConsoleAccountReason.OTHER_FAILURE
                )

                DshAuthAttemptPhase.EXPIRED -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.TIMED_OUT,
                    attemptAgeMillis = age,
                    reason = DshConsoleAccountReason.ATTEMPT_TIMEOUT
                )

                DshAuthAttemptPhase.OUTCOME_UNKNOWN -> DshConsoleAccountProjection(
                    state = DshConsoleAccountState.UNKNOWN,
                    attemptAgeMillis = age,
                    reason = failureReason(view.errorCode)
                        ?: DshConsoleAccountReason.OFFICIAL_OUTCOME_UNOBSERVED
                )
            }
        }

        /** Maps the owner's stable `DshAuthFailureCode` name to a display-safe reason class. */
        private fun failureReason(code: String?): DshConsoleAccountReason? = when (code) {
            "ATTEMPT_TIMEOUT" -> DshConsoleAccountReason.ATTEMPT_TIMEOUT
            "ATTEMPT_CANCELLED" -> DshConsoleAccountReason.ATTEMPT_CANCELLED
            "ATTEMPT_BUSY" -> DshConsoleAccountReason.ATTEMPT_BUSY
            "BROWSER_HANDLER_MISSING" -> DshConsoleAccountReason.BROWSER_HANDLER_MISSING
            "CALLBACK_ORIGIN_INVALID" -> DshConsoleAccountReason.CALLBACK_ORIGIN_INVALID
            "AUTHORIZE_URL_INVALID" -> DshConsoleAccountReason.AUTHORIZE_URL_INVALID
            "POPUP_ORIGIN_UNTRUSTED" -> DshConsoleAccountReason.POPUP_ORIGIN_UNTRUSTED
            "OFFICIAL_MODULE_MISSING" -> DshConsoleAccountReason.OFFICIAL_MODULE_MISSING
            "CALLBACK_STATE_MISMATCH" -> DshConsoleAccountReason.CALLBACK_STATE_MISMATCH
            "CREDENTIAL_STORE_FAILED" -> DshConsoleAccountReason.CREDENTIAL_STORE_FAILED
            "PROTOCOL_ERROR" -> DshConsoleAccountReason.PROTOCOL_ERROR
            "PROCESS_RESTARTED" -> DshConsoleAccountReason.RUNTIME_GENERATION_LOST
            "OUTCOME_UNKNOWN" -> DshConsoleAccountReason.OUTCOME_UNVERIFIED
            else -> null
        }
    }
}

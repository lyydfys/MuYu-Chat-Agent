package com.muyuchat.core.dsh.contract.auth

/**
 * Pure, token-free projections of the official DeepSeek account sign-in protocol.
 *
 * Grounding (pinned payload, read-only): `dsh-deepseek-account-platform/lib/index.js`
 * `startSignIn(client, callbackOrigin, loginSource)` registers the fixed `GET /oauth/callback`
 * route, creates a 32-byte state/verifier pair (S256 challenge), asks `auth_init` for
 * `authorize_url`/`authorize_id`/`expires_in`, exposes the attempt as
 * `{id, phase, authorizeUrl, expiresAt, errorCode}` and only then commits the grant through the
 * official credential store. The official module owns every secret; this contract only carries
 * what a host projection may safely observe.
 *
 * Nothing here may hold a token, an authorization code, a PKCE verifier or a cookie value.
 */

/** The attempt phases the official account module actually publishes. */
enum class DshAuthAttemptPhase {
    /** Host projection of "no attempt right now"; the official view is `null` in that case. */
    IDLE,
    /** Official `initializing`: `auth_init` is in flight, no authorize URL yet. */
    INITIALIZING,
    /** Official `waiting-browser`: authorize URL exists, browser handoff is expected. */
    WAITING_BROWSER,
    /** Official `exchanging`: the callback code is being exchanged for a grant. */
    EXCHANGING,
    /** Official `committing`: the grant is being written by the official credential store. */
    COMMITTING,
    /** Official `succeeded`; only the official credential state may confirm this. */
    SUCCEEDED,
    /** Official `cancelled` (user cancel or authorization cancellation). */
    CANCELLED,
    /** Official `failed`, carrying an official `errorCode` class. */
    FAILED,
    /** Official `expired`: the attempt deadline elapsed. */
    EXPIRED,
    /** Host-only: the hosting process died while the attempt was in flight. */
    OUTCOME_UNKNOWN
}

/** The only login sources the pinned official module accepts. No `android` value is invented. */
enum class DshLoginSource(val wireValue: String) {
    WEB("web"),
    DESKTOP("desktop")
}

/**
 * Typed failure classes of the host account adapter. Every value is a known official condition or
 * an explicit host degradation; none of them is reported as a successful login.
 */
enum class DshAuthFailureCode {
    /** The payload does not carry the official account module/service in this generation. */
    OFFICIAL_MODULE_MISSING,
    /** `callbackOrigin` is not an accepted loopback origin with an explicit non-zero port. */
    CALLBACK_ORIGIN_INVALID,
    /** The authorize URL is not the official origin + `/dsh/authorize` shape. */
    AUTHORIZE_URL_INVALID,
    /** No Android browser activity can handle the authorize URL (typed degradation). */
    BROWSER_HANDLER_MISSING,
    /** A popup handle was requested by an origin other than the trusted DSH Web origin. */
    POPUP_ORIGIN_UNTRUSTED,
    /** A second sign-in was requested while an official attempt is still active. */
    ATTEMPT_BUSY,
    /** The official attempt deadline elapsed (init/exchange/browser all share it). */
    ATTEMPT_TIMEOUT,
    /** The user cancelled the attempt. */
    ATTEMPT_CANCELLED,
    /**
     * The official account UI released the popup window that carried the authorize URL.
     *
     * Grounding (pinned payload): the official client's `BrowserLogin.update` closes exactly that
     * window when the watched attempt reaches `cancelled`, `failed` or `expired`, and never on
     * `succeeded` (it already navigated the window then). The host therefore learns "the official
     * attempt ended without a success the host can see", but it cannot re-derive which of the three
     * official phases it was, and it must not invent one: the host attempt ends as
     * [DshAuthAttemptPhase.CANCELLED] and this code, never as a success.
     */
    OFFICIAL_POPUP_RELEASED,
    /** The hosting process restarted while the attempt was in flight. */
    PROCESS_RESTARTED,
    /** The official state comparison rejected the callback (wrong/old attempt). */
    CALLBACK_STATE_MISMATCH,
    /** The official credential store refused or failed the committing write. */
    CREDENTIAL_STORE_FAILED,
    /** The official credential store refused the local delete during sign-out. */
    CREDENTIAL_DELETE_FAILED,
    /** Official payload/shape violation (the official `protocol` error class). */
    PROTOCOL_ERROR,
    /** A dispatched external step produced no verifiable result. */
    OUTCOME_UNKNOWN
}

/** One safe attempt view. [authorizeUrl] already exists at the official `waiting-browser` phase. */
data class DshAuthAttemptView(
    val attemptId: String,
    val phase: DshAuthAttemptPhase,
    val authorizeUrl: String?,
    val expiresAtEpochMillis: Long?,
    /** Official `errorCode` class (never a token or a message with user content). */
    val errorCode: String? = null
) {
    override fun toString(): String = "DshAuthAttemptView(phase=$phase, hasUrl=${authorizeUrl != null})"
}

/** A callback as delivered by the hosting Web server. Query values stay out of `toString`. */
data class DshAuthCallbackRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>
) {
    fun single(name: String): String? = query[name]?.singleOrNull()?.takeIf { it.isNotEmpty() }

    fun valueCount(name: String): Int = query[name]?.size ?: 0

    override fun toString(): String = "DshAuthCallbackRequest(method=$method, path=$path)"
}

/** Why a callback was rejected, with the HTTP status the official callback route would send. */
enum class DshAuthRejectionCode {
    WRONG_METHOD,
    WRONG_PATH,
    MALFORMED_QUERY,
    MISSING_CODE,
    DUPLICATE_CODE,
    MISSING_STATE,
    DUPLICATE_STATE,
    CALLBACK_ERROR_PARAM,
    STATE_MISMATCH,
    ATTEMPT_EXPIRED,
    ATTEMPT_NOT_WAITING,
    REPLAYED_CALLBACK,
    PKCE_MISSING
}

sealed interface DshAuthCallbackVerdict {
    /**
     * The callback is for the current attempt and may be submitted to the official exchange.
     * No authorization code is copied into this verdict: the hosting adapter forwards the raw
     * callback to the official module, which owns code/verifier handling.
     */
    data object Accepted : DshAuthCallbackVerdict

    /** Rejected with the exact official status (400 for bad input, 410 for a non-waiting attempt). */
    data class Rejected(val status: Int, val code: DshAuthRejectionCode) : DshAuthCallbackVerdict
}

/** The pin-down of the official attempt bounds (values read from the pinned module defaults). */
data class DshAuthAttemptBounds(
    val defaultAttemptMillis: Long = 600_000L,
    val maxAttemptMillis: Long = 3_600_000L,
    /** Bounded handoff deadline for one popup/attempt; never a magic 1s/5s window lifetime. */
    val handoffDeadlineMillis: Long = 600_000L,
    /** Official callback path; it is never randomized per attempt. */
    val callbackPath: String = "/oauth/callback",
    /** Official authorize path, validated by the official `browserUrl` helper. */
    val authorizePath: String = "/dsh/authorize",
    /** Default official platform origin. */
    val platformOrigin: String = "https://platform.deepseek.com"
)

/** Result of handing one validated authorize URL to the external browser. */
sealed interface DshBrowserHandoffResult {
    data object Opened : DshBrowserHandoffResult
    data object NoHandler : DshBrowserHandoffResult
    data class Rejected(val reason: String) : DshBrowserHandoffResult
}

/** Credential lifecycle as projected from the official store; never a token container. */
enum class DshCredentialLifecycleState {
    ABSENT,
    PRESENT,
    /** Present but the official conditional invalidation matched a 401/40003. */
    STALE,
    /** Explicitly invalidated after a matched unauthorized response. */
    INVALIDATED,
    /** Removed by the official sign-out. */
    REMOVED
}

/** Which caller produced a 401; only the account-scoped ones may touch the account credential. */
enum class DshUnauthorizedScope {
    MODEL_LISTING,
    INFERENCE,
    PROFILE,
    BALANCE,
    WEB_COOKIE,
    MCA_GATEWAY_BEARER
}

/** Ordered official sign-out steps; local delete must succeed before the remote revoke starts. */
enum class DshLogoutStep {
    CANCEL_ATTEMPT,
    AWAIT_COMMITTING_DONE,
    VERIFY_GRANT_ISSUER,
    LOCAL_DELETE,
    BACKGROUND_REVOKE,
    PUBLISH_SIGNED_OUT
}

/** The official DSH credential store is the only persistence owner. There is no Kotlin twin. */
enum class DshCredentialPersistenceOwner {
    OFFICIAL_DSH
}

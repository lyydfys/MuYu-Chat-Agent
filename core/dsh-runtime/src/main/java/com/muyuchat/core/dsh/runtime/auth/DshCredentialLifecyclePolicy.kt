package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshCredentialLifecycleState
import com.muyuchat.core.dsh.contract.auth.DshCredentialPersistenceOwner
import com.muyuchat.core.dsh.contract.auth.DshLogoutStep
import com.muyuchat.core.dsh.contract.auth.DshUnauthorizedScope

/**
 * The official credential record kind (`grant` in the pinned store schema).
 *
 * A credential is represented here by a **fingerprint**, never by the token itself: the host policy
 * only ever needs to answer "is this the record the official module would delete?". The real token
 * stays inside the official `credentials-local` store and is never read by MCA.
 */
internal enum class DshCredentialKind {
    GRANT,
    OTHER
}

internal data class DshCredentialRef(
    val kind: DshCredentialKind,
    val tokenFingerprint: String,
    val issuer: String,
    /** Official `detailsLifetime` generation captured by the caller that saw the 401. */
    val lifetimeGeneration: Long
)

internal data class DshUnauthorizedEvent(
    val scope: DshUnauthorizedScope,
    val httpStatus: Int,
    /** Official business code (for example `40003`); null when the response carried none. */
    val officialCode: Int?,
    val tokenFingerprint: String?,
    val issuer: String?,
    val observedLifetimeGeneration: Long
)

/** What the caller must do after one unauthorized response. */
internal enum class DshCredentialAction {
    /** Delete exactly the matching grant through the official store, then emit signed-out. */
    DELETE_EXACT_GRANT,

    /** A model-listing 401 never signs the account out (official behavior). */
    IGNORE_MODEL_LISTING,

    /** A 401 from a flow that does not own the account credential (Web cookie / MCA bearer). */
    NOT_ACCOUNT_SCOPE,

    /** The response was not an account credential rejection (other status/code). */
    NOT_UNAUTHORIZED,

    /** The event does not match the current record (old 401, other kind, other issuer/lifetime). */
    IGNORE_STALE,

    /** No stored credential to delete; the account is already signed out. */
    IGNORE_NO_CREDENTIAL
}

internal data class DshCredentialDecision(
    val action: DshCredentialAction,
    val state: DshCredentialLifecycleState,
    val reason: String
)

internal sealed interface DshLogoutStatus {
    data object Ready : DshLogoutStatus

    /** Official cancel is deferred until the in-flight `committing` attempt settles. */
    data object DeferredForCommitting : DshLogoutStatus

    data class Blocked(val failure: DshAuthFailureCode) : DshLogoutStatus
}

internal data class DshLogoutPlan(
    val steps: List<DshLogoutStep>,
    val status: DshLogoutStatus,
    /** True only when the local delete step is proven and the captured grant may be revoked. */
    val remoteRevokeAllowed: Boolean
)

internal data class DshLogoutTicket(val plan: DshLogoutPlan, val coalesced: Boolean)

internal enum class DshTokenStoreVerdict {
    REFUSED_OFFICIAL_STORE_IS_THE_ONLY_AUTHORITY
}

/**
 * Credential lifecycle policy for the official DeepSeek account.
 *
 * This file deliberately contains **no** token store, cookie jar, SharedPreferences handle or file
 * writer: [persistenceOwner] is always [DshCredentialPersistenceOwner.OFFICIAL_DSH]. The policy only
 * decides what the host must ask the official module to do.
 *
 * Grounding (pinned `dsh-deepseek-account-platform`):
 * - a Platform 401 carrying `x-dsh-auth-token`, or a response whose business code is `40003`, is an
 *   account-authorization rejection (`AccountUnauthorizedError`);
 * - invalidation is conditional: it re-reads the record, requires `kind === "grant"`, the same
 *   captured `detailsLifetime`, the same `token` and the configured `issuer`, and only then deletes;
 * - a model-listing 401 is not treated as identity loss;
 * - sign-out cancels the attempt (or waits for `committing`), verifies the issuer, deletes locally
 *   and only then starts a bounded, memory-only background revoke; a local delete failure never
 *   restores the credential and never starts the remote revoke first.
 */
internal class DshCredentialLifecyclePolicy(
    private val currentIssuer: String
) {
    private val lock = Any()
    private var logoutInFlight: Boolean = false

    /** The only credential persistence owner; encoded so no Kotlin twin can be introduced. */
    val persistenceOwner: DshCredentialPersistenceOwner =
        DshCredentialPersistenceOwner.OFFICIAL_DSH

    /**
     * Every request to create a Kotlin-side token/cookie mirror is refused; the official store is
     * the authority and a second writer would create a dual-authority credential.
     */
    fun reviewTokenStoreProposal(proposedStoreName: String): DshTokenStoreVerdict {
        require(proposedStoreName.isNotBlank()) { "a token store proposal must name its store" }
        return DshTokenStoreVerdict.REFUSED_OFFICIAL_STORE_IS_THE_ONLY_AUTHORITY
    }

    /**
     * Maps one unauthorized response onto the official conditional invalidation.
     *
     * @param current the record currently held by the official store, or null when signed out.
     */
    fun onUnauthorized(
        event: DshUnauthorizedEvent,
        current: DshCredentialRef?
    ): DshCredentialDecision = synchronized(lock) {
        when (event.scope) {
            DshUnauthorizedScope.MODEL_LISTING -> return DshCredentialDecision(
                DshCredentialAction.IGNORE_MODEL_LISTING,
                DshCredentialLifecycleState.PRESENT,
                "a model-listing 401 does not clear the account login"
            )
            DshUnauthorizedScope.WEB_COOKIE,
            DshUnauthorizedScope.MCA_GATEWAY_BEARER -> return DshCredentialDecision(
                DshCredentialAction.NOT_ACCOUNT_SCOPE,
                DshCredentialLifecycleState.PRESENT,
                "only the provider that owns the credential may invalidate it"
            )
            DshUnauthorizedScope.INFERENCE,
            DshUnauthorizedScope.PROFILE,
            DshUnauthorizedScope.BALANCE -> Unit
        }
        val unauthorized = event.httpStatus == 401 || event.officialCode == OFFICIAL_UNAUTHORIZED_CODE
        if (!unauthorized) {
            return DshCredentialDecision(
                DshCredentialAction.NOT_UNAUTHORIZED,
                DshCredentialLifecycleState.PRESENT,
                "neither HTTP 401 nor the official 40003 code"
            )
        }
        if (current == null) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_NO_CREDENTIAL,
                DshCredentialLifecycleState.ABSENT,
                "no stored account credential"
            )
        }
        if (current.kind != DshCredentialKind.GRANT) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_STALE,
                DshCredentialLifecycleState.PRESENT,
                "the stored record is not an account grant"
            )
        }
        if (event.observedLifetimeGeneration != current.lifetimeGeneration) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_STALE,
                DshCredentialLifecycleState.PRESENT,
                "the 401 belongs to a superseded credential lifetime"
            )
        }
        if (event.tokenFingerprint != current.tokenFingerprint) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_STALE,
                DshCredentialLifecycleState.PRESENT,
                "the 401 does not match the stored grant token"
            )
        }
        if (event.issuer != null && event.issuer != current.issuer) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_STALE,
                DshCredentialLifecycleState.PRESENT,
                "the 401 issuer does not match the stored grant issuer"
            )
        }
        if (current.issuer != currentIssuer) {
            return DshCredentialDecision(
                DshCredentialAction.IGNORE_STALE,
                DshCredentialLifecycleState.PRESENT,
                "the stored grant was issued by another platform origin"
            )
        }
        DshCredentialDecision(
            DshCredentialAction.DELETE_EXACT_GRANT,
            DshCredentialLifecycleState.STALE,
            "the current grant was rejected by the official platform"
        )
    }

    /**
     * Plans the official sign-out. A `committing` attempt is awaited, never aborted, and the remote
     * revoke is only reachable after a proven local delete.
     */
    fun planLogout(
        attemptActive: Boolean,
        attemptCommitting: Boolean,
        credential: DshCredentialRef?
    ): DshLogoutTicket = synchronized(lock) {
        val coalesced = logoutInFlight
        logoutInFlight = true
        if (credential != null && credential.kind != DshCredentialKind.GRANT) {
            logoutInFlight = false
            return DshLogoutTicket(
                DshLogoutPlan(
                    steps = listOf(DshLogoutStep.PUBLISH_SIGNED_OUT),
                    status = DshLogoutStatus.Blocked(DshAuthFailureCode.PROTOCOL_ERROR),
                    remoteRevokeAllowed = false
                ),
                coalesced = coalesced
            )
        }
        if (credential != null && credential.issuer != currentIssuer) {
            logoutInFlight = false
            return DshLogoutTicket(
                DshLogoutPlan(
                    steps = listOf(DshLogoutStep.PUBLISH_SIGNED_OUT),
                    status = DshLogoutStatus.Blocked(DshAuthFailureCode.PROTOCOL_ERROR),
                    remoteRevokeAllowed = false
                ),
                coalesced = coalesced
            )
        }
        val steps = buildList {
            if (attemptActive) {
                add(DshLogoutStep.CANCEL_ATTEMPT)
                if (attemptCommitting) add(DshLogoutStep.AWAIT_COMMITTING_DONE)
            }
            if (credential != null) {
                add(DshLogoutStep.VERIFY_GRANT_ISSUER)
                add(DshLogoutStep.LOCAL_DELETE)
                add(DshLogoutStep.BACKGROUND_REVOKE)
            }
            add(DshLogoutStep.PUBLISH_SIGNED_OUT)
        }
        DshLogoutTicket(
            DshLogoutPlan(
                steps = steps,
                status = if (attemptCommitting) {
                    DshLogoutStatus.DeferredForCommitting
                } else {
                    DshLogoutStatus.Ready
                },
                // The plan may start the revoke step, but the adapter must only reach it after
                // afterLocalDelete(..., succeeded = true).
                remoteRevokeAllowed = credential != null
            ),
            coalesced = coalesced
        )
    }

    /**
     * The local delete result. A failure keeps the account local-only: no remote revoke may start
     * (the official module also leaves the token unrecovered rather than revoking a record that may
     * still be authoritative), and the caller publishes a typed failure instead of signed-out.
     */
    fun afterLocalDelete(plan: DshLogoutPlan, succeeded: Boolean): DshLogoutPlan {
        if (succeeded) {
            logoutInFlight = false
            return plan
        }
        logoutInFlight = false
        return plan.copy(
            steps = listOf(DshLogoutStep.CANCEL_ATTEMPT, DshLogoutStep.PUBLISH_SIGNED_OUT),
            status = DshLogoutStatus.Blocked(DshAuthFailureCode.CREDENTIAL_DELETE_FAILED),
            remoteRevokeAllowed = false
        )
    }

    /**
     * Scope of the official `signed-out` Agent cancellation: only a running Agent whose latest
     * durable request used the `deepseek-account` provider. MCA local/cloud work is untouched.
     */
    fun shouldCancelAgentOnSignOut(provider: String?, status: String): Boolean =
        status == "running" && provider == ACCOUNT_PROVIDER

    private companion object {
        const val OFFICIAL_UNAUTHORIZED_CODE = 40_003
        const val ACCOUNT_PROVIDER = "deepseek-account"
    }
}

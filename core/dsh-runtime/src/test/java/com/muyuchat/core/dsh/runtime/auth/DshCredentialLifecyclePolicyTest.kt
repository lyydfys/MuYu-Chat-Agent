package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshCredentialLifecycleState
import com.muyuchat.core.dsh.contract.auth.DshCredentialPersistenceOwner
import com.muyuchat.core.dsh.contract.auth.DshLogoutStep
import com.muyuchat.core.dsh.contract.auth.DshUnauthorizedScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05: credential lifecycle, 401 matching, sign-out ordering and the no-Kotlin-token-store rule.
 *
 * Grounding: pinned `dsh-deepseek-account-platform.expireCredential` (`kind === "grant"`, same
 * captured lifetime, same token, same issuer) and `signOut` (cancel attempt, await `committing`,
 * verify issuer, delete locally, only then background-revoke). The host keeps no token: it only
 * decides which official call to make.
 */
class DshCredentialLifecyclePolicyTest {

    private val policy = DshCredentialLifecyclePolicy(currentIssuer = ISSUER)
    private val current = DshCredentialRef(
        kind = DshCredentialKind.GRANT,
        tokenFingerprint = "fp-current",
        issuer = ISSUER,
        lifetimeGeneration = 7
    )

    private fun event(
        scope: DshUnauthorizedScope = DshUnauthorizedScope.INFERENCE,
        httpStatus: Int = 401,
        code: Int? = null,
        fingerprint: String? = "fp-current",
        issuer: String? = ISSUER,
        lifetime: Long = 7
    ) = DshUnauthorizedEvent(scope, httpStatus, code, fingerprint, issuer, lifetime)

    // ------------------------------------------------------------------ 401 matching

    @Test
    fun aModelListing401NeverClearsTheLogin() {
        val decision = policy.onUnauthorized(event(scope = DshUnauthorizedScope.MODEL_LISTING), current)
        assertEquals(DshCredentialAction.IGNORE_MODEL_LISTING, decision.action)
        assertEquals(DshCredentialLifecycleState.PRESENT, decision.state)
    }

    @Test
    fun onlyTheAccountOwningFlowsMayInvalidateTheCredential() {
        for (scope in listOf(DshUnauthorizedScope.WEB_COOKIE, DshUnauthorizedScope.MCA_GATEWAY_BEARER)) {
            val decision = policy.onUnauthorized(event(scope = scope), current)
            assertEquals(DshCredentialAction.NOT_ACCOUNT_SCOPE, decision.action)
        }
    }

    @Test
    fun inferenceProfileAndBalance401With40003DeleteExactlyTheCurrentGrant() {
        for (scope in listOf(
            DshUnauthorizedScope.INFERENCE,
            DshUnauthorizedScope.PROFILE,
            DshUnauthorizedScope.BALANCE
        )) {
            assertEquals(
                DshCredentialAction.DELETE_EXACT_GRANT,
                policy.onUnauthorized(event(scope = scope), current).action
            )
            assertEquals(
                DshCredentialAction.DELETE_EXACT_GRANT,
                policy.onUnauthorized(event(scope = scope, httpStatus = 200, code = 40003), current).action
            )
        }
    }

    @Test
    fun aNonAuthorizationErrorIsNotAConstraintFailure() {
        val decision = policy.onUnauthorized(event(httpStatus = 500, code = 50001), current)
        assertEquals(DshCredentialAction.NOT_UNAUTHORIZED, decision.action)
    }

    @Test
    fun anOld401CannotDeleteANewerLogin() {
        val afterRelogin = current.copy(tokenFingerprint = "fp-new", lifetimeGeneration = 8)
        val oldFingerprint = policy.onUnauthorized(event(fingerprint = "fp-current", lifetime = 7), afterRelogin)
        assertEquals(DshCredentialAction.IGNORE_STALE, oldFingerprint.action)
        val oldLifetime = policy.onUnauthorized(event(fingerprint = "fp-new", lifetime = 7), afterRelogin)
        assertEquals(DshCredentialAction.IGNORE_STALE, oldLifetime.action)
        val otherIssuer = policy.onUnauthorized(event(issuer = "https://other.example"), current)
        assertEquals(DshCredentialAction.IGNORE_STALE, otherIssuer.action)
    }

    @Test
    fun aNonGrantRecordOrAMissingCredentialIsNeverDeletedByA401() {
        assertEquals(
            DshCredentialAction.IGNORE_STALE,
            policy.onUnauthorized(event(), current.copy(kind = DshCredentialKind.OTHER)).action
        )
        val absent = policy.onUnauthorized(event(), null)
        assertEquals(DshCredentialAction.IGNORE_NO_CREDENTIAL, absent.action)
        assertEquals(DshCredentialLifecycleState.ABSENT, absent.state)
        val foreignGrant = policy.onUnauthorized(event(), current.copy(issuer = "https://other.example"))
        assertEquals(DshCredentialAction.IGNORE_STALE, foreignGrant.action)
    }

    // ------------------------------------------------------------------ sign-out

    @Test
    fun signOutOrdersCancelAwaitVerifyDeleteThenBackgroundRevoke() {
        val ticket = policy.planLogout(attemptActive = true, attemptCommitting = true, credential = current)
        assertEquals(
            listOf(
                DshLogoutStep.CANCEL_ATTEMPT,
                DshLogoutStep.AWAIT_COMMITTING_DONE,
                DshLogoutStep.VERIFY_GRANT_ISSUER,
                DshLogoutStep.LOCAL_DELETE,
                DshLogoutStep.BACKGROUND_REVOKE,
                DshLogoutStep.PUBLISH_SIGNED_OUT
            ),
            ticket.plan.steps
        )
        assertEquals(DshLogoutStatus.DeferredForCommitting, ticket.plan.status)
        assertTrue(ticket.plan.remoteRevokeAllowed)
        assertFalse(ticket.coalesced)
    }

    @Test
    fun aLocalDeleteFailureStopsTheRemoteRevokeAndPublishesATypedFailure() {
        val ticket = policy.planLogout(attemptActive = false, attemptCommitting = false, credential = current)
        val blocked = policy.afterLocalDelete(ticket.plan, succeeded = false)
        assertEquals(
            DshLogoutStatus.Blocked(DshAuthFailureCode.CREDENTIAL_DELETE_FAILED),
            blocked.status
        )
        assertFalse("no remote revoke may start after a failed local delete", blocked.remoteRevokeAllowed)
        assertFalse(blocked.steps.contains(DshLogoutStep.BACKGROUND_REVOKE))
    }

    @Test
    fun aSuccessfulLocalDeleteKeepsThePlanIntact() {
        val ticket = policy.planLogout(attemptActive = true, attemptCommitting = false, credential = current)
        val done = policy.afterLocalDelete(ticket.plan, succeeded = true)
        assertTrue(done.remoteRevokeAllowed)
        assertEquals(ticket.plan.steps, done.steps)
    }

    @Test
    fun aForeignOrNonGrantRecordBlocksSignOutBeforeDeletingAnything() {
        val foreign = policy.planLogout(false, false, current.copy(issuer = "https://other.example"))
        assertEquals(
            DshLogoutStatus.Blocked(DshAuthFailureCode.PROTOCOL_ERROR),
            foreign.plan.status
        )
        assertFalse(foreign.plan.remoteRevokeAllowed)
        assertEquals(
            DshLogoutStatus.Blocked(DshAuthFailureCode.PROTOCOL_ERROR),
            policy.planLogout(false, false, current.copy(kind = DshCredentialKind.OTHER)).plan.status
        )
    }

    @Test
    fun signedOutWithNoCredentialSkipsDeleteAndRevokeButStillPublishes() {
        val ticket = policy.planLogout(attemptActive = false, attemptCommitting = false, credential = null)
        assertEquals(listOf(DshLogoutStep.PUBLISH_SIGNED_OUT), ticket.plan.steps)
        assertFalse(ticket.plan.remoteRevokeAllowed)
    }

    @Test
    fun concurrentSignOutRequestsAreCoalesced() {
        val first = policy.planLogout(false, false, current)
        val second = policy.planLogout(false, false, current)
        assertFalse(first.coalesced)
        assertTrue("a second sign-out must join the in-flight one", second.coalesced)
        policy.afterLocalDelete(first.plan, succeeded = true)
        assertFalse(policy.planLogout(false, false, current).coalesced)
    }

    // ------------------------------------------------------------------ no Kotlin token store

    @Test
    fun theOfficialStoreIsTheOnlyCredentialPersistenceOwner() {
        assertEquals(
            DshCredentialPersistenceOwner.OFFICIAL_DSH,
            policy.persistenceOwner
        )
        assertEquals(
            DshTokenStoreVerdict.REFUSED_OFFICIAL_STORE_IS_THE_ONLY_AUTHORITY,
            policy.reviewTokenStoreProposal("kotlin-account-token-store")
        )
    }

    @Test
    fun signedOutCancelsOnlyTheAccountAgents() {
        assertTrue(policy.shouldCancelAgentOnSignOut("deepseek-account", "running"))
        assertFalse(policy.shouldCancelAgentOnSignOut("mca-local", "running"))
        assertFalse(policy.shouldCancelAgentOnSignOut("mca-cloud", "running"))
        assertFalse(policy.shouldCancelAgentOnSignOut("deepseek-account", "completed"))
        assertFalse(policy.shouldCancelAgentOnSignOut(null, "running"))
    }

    private companion object {
        const val ISSUER = "https://platform.deepseek.com"
    }
}

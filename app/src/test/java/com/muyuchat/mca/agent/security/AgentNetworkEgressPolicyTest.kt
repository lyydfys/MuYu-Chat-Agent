package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K07 egress tests (architecture 11.2/11.3): LOCAL_ONLY transport denial, per-run origin/class/
 * source grants, final payload source coverage, and policyVersion-incrementing revocation with an
 * own-run cancellation handle only.
 */
class AgentNetworkEgressPolicyTest {
    private val policy = AgentNetworkEgressPolicy()

    @Test
    fun localOnlyDeniesCloudRemoteMcpAndBrowserBeforeConnection() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.LOCAL_ONLY, 3L)
        listOf(
            AgentEgressTransport.CLOUD_PROVIDER,
            AgentEgressTransport.REMOTE_MCP,
            AgentEgressTransport.BROWSER_SEARCH
        ).forEach { transport ->
            val decision = policy.checkTransport(scope, transport, "https://api.example.com", emptyList())
            assertTrue("$transport must be denied", decision is AgentEgressDecision.Denied)
            val denied = decision as AgentEgressDecision.Denied
            assertEquals("POLICY_OFFLINE", denied.error.code)
            assertEquals(AgentErrorCategory.POLICY, denied.error.category)
        }
    }

    @Test
    fun localOnlyAllowsOnlyAnExplicitlyGrantedLocalServer() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.LOCAL_ONLY, 3L)
        val origin = "http://127.0.0.1:8080"
        val withoutGrant = policy.checkTransport(scope, AgentEgressTransport.LOCAL_SERVER, origin, emptyList())
        assertEquals("POLICY_EGRESS_DENIED", (withoutGrant as AgentEgressDecision.Denied).error.code)

        val created = policy.createGrant(
            scope,
            AgentEgressGrantRequest(
                grantId = "grant-1",
                transport = AgentEgressTransport.LOCAL_SERVER,
                origin = origin,
                dataClasses = setOf(AgentDataClass.PRIVATE),
                sourceIds = setOf("source-1")
            )
        )
        assertTrue(created is AgentEgressGrantOutcome.Created)
        val grant = (created as AgentEgressGrantOutcome.Created).grant
        assertEquals("http://127.0.0.1:8080", grant.origin)

        val transport = policy.checkTransport(scope, AgentEgressTransport.LOCAL_SERVER, origin, listOf(grant))
        assertTrue(transport is AgentEgressDecision.Allowed)

        val covered = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.LOCAL_SERVER,
                origin,
                modelId = "local-model",
                sources = listOf(AgentSecurityFixtures.source("source-1"))
            ),
            listOf(grant)
        )
        assertTrue(covered is AgentEgressDecision.Allowed)
        assertEquals(listOf("source-1"), (covered as AgentEgressDecision.Allowed).grantedSourceIds)

        val newSource = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.LOCAL_SERVER,
                origin,
                modelId = "local-model",
                sources = listOf(AgentSecurityFixtures.source("source-1"), AgentSecurityFixtures.source("source-2"))
            ),
            listOf(grant)
        )
        assertTrue(newSource is AgentEgressDecision.AuthorizationRequired)
        assertEquals(
            listOf("source-2"),
            (newSource as AgentEgressDecision.AuthorizationRequired).missingSourceIds
        )
    }

    @Test
    fun networkEnabledWithUnknownOriginRequiresAuthorization() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 2L)
        val decision = policy.checkTransport(
            scope, AgentEgressTransport.CLOUD_PROVIDER, "HTTPS://API.Example.COM:443", emptyList()
        )
        assertTrue(decision is AgentEgressDecision.AuthorizationRequired)
        val required = decision as AgentEgressDecision.AuthorizationRequired
        assertEquals("https://api.example.com", required.origin)
        assertEquals(AgentEgressAuthorizationReason.UNKNOWN_ORIGIN, required.reason)
        assertTrue(required.missingSourceIds.isEmpty())
    }

    @Test
    fun payloadIsCoveredOnlyWhenOriginClassAndExactSourceMatch() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 2L)
        val grant = createGrant(
            scope,
            "https://api.example.com",
            setOf(AgentDataClass.PRIVATE, AgentDataClass.PUBLIC),
            setOf("s1", "s2")
        )

        val otherOrigin = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.CLOUD_PROVIDER,
                "https://other.example.com",
                modelId = "m",
                sources = listOf(AgentSecurityFixtures.source("s1"))
            ),
            listOf(grant)
        )
        assertTrue(otherOrigin is AgentEgressDecision.AuthorizationRequired)

        val fullyCovered = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.CLOUD_PROVIDER,
                "https://api.example.com",
                modelId = "m",
                sources = listOf(
                    AgentSecurityFixtures.source("s2"),
                    AgentSecurityFixtures.source("s1", dataClass = AgentDataClass.PUBLIC)
                )
            ),
            listOf(grant)
        )
        assertTrue(fullyCovered is AgentEgressDecision.Allowed)
        assertEquals(
            listOf("s1", "s2"),
            (fullyCovered as AgentEgressDecision.Allowed).grantedSourceIds
        )

        val missing = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.CLOUD_PROVIDER,
                "https://api.example.com",
                modelId = "m",
                sources = listOf(AgentSecurityFixtures.source("s3"))
            ),
            listOf(grant)
        )
        assertTrue(missing is AgentEgressDecision.AuthorizationRequired)
        assertEquals(listOf("s3"), (missing as AgentEgressDecision.AuthorizationRequired).missingSourceIds)

        val wrongClass = policy.checkPayload(
            scope,
            AgentEgressPayload(
                AgentEgressTransport.CLOUD_PROVIDER,
                "https://api.example.com",
                modelId = "m",
                sources = listOf(AgentSecurityFixtures.source("s1", dataClass = AgentDataClass.SECRET))
            ),
            listOf(grant)
        )
        assertTrue(wrongClass is AgentEgressDecision.AuthorizationRequired)
        assertEquals(listOf("s1"), (wrongClass as AgentEgressDecision.AuthorizationRequired).missingSourceIds)
    }

    @Test
    fun revokedOrStaleGrantIsATypedEgressDenial() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 3L)
        val stale = AgentEgressGrant(
            grantId = "grant-1",
            runId = "run-1",
            transport = AgentEgressTransport.CLOUD_PROVIDER,
            origin = "https://api.example.com",
            allowedDataClasses = setOf(AgentDataClass.PRIVATE),
            allowedSourceIds = setOf("s1"),
            policyVersion = 2L
        )
        val staleDecision = policy.checkTransport(
            scope, AgentEgressTransport.CLOUD_PROVIDER, "https://api.example.com", listOf(stale)
        )
        assertEquals("POLICY_EGRESS_DENIED", (staleDecision as AgentEgressDecision.Denied).error.code)

        val revoked = stale.copy(policyVersion = 3L, revoked = true)
        val revokedDecision = policy.checkTransport(
            scope, AgentEgressTransport.CLOUD_PROVIDER, "https://api.example.com", listOf(revoked)
        )
        assertEquals("POLICY_EGRESS_DENIED", (revokedDecision as AgentEgressDecision.Denied).error.code)
    }

    @Test
    fun revocationIncrementsPolicyVersionAndOnlyCancelsOwnRun() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 5L)
        val grant = createGrant(scope, "https://api.example.com", setOf(AgentDataClass.PRIVATE), setOf("s1"))
        val cancellations = mutableListOf<Pair<String, AgentEgressCancelReason>>()
        val ownCalls = AgentInFlightEgressCalls { runId, reason -> cancellations += runId to reason }

        val revocation = policy.revoke(scope, grant, ownCalls, AgentEgressCancelReason.GRANT_REVOKED)
        assertEquals("run-1", revocation.runId)
        assertEquals("grant-1", revocation.revokedGrantId)
        assertEquals(5L, revocation.previousPolicyVersion)
        assertEquals(6L, revocation.newPolicyVersion)

        revocation.cancellation.cancelOwnCalls("run-1", AgentEgressCancelReason.GRANT_REVOKED)
        assertEquals(listOf("run-1" to AgentEgressCancelReason.GRANT_REVOKED), cancellations)
        var rejectedOtherRun = false
        try {
            revocation.cancellation.cancelOwnCalls("run-2", AgentEgressCancelReason.GRANT_REVOKED)
        } catch (_: IllegalArgumentException) {
            rejectedOtherRun = true
        }
        assertTrue("the handle must never cancel another run", rejectedOtherRun)
        assertEquals(1, cancellations.size)
    }

    @Test
    fun originCanonicalizationRulesAreStrict() {
        assertEquals(
            "https://example.com",
            (policy.canonicalOrigin("https://Example.COM:443", AgentEgressTransport.CLOUD_PROVIDER)
                as AgentOriginResult.Valid).origin
        )
        assertEquals(
            "https://api.example.com:8443",
            (policy.canonicalOrigin("https://api.example.com:8443", AgentEgressTransport.CLOUD_PROVIDER)
                as AgentOriginResult.Valid).origin
        )
        assertEquals(
            "http://127.0.0.1:8080",
            (policy.canonicalOrigin("http://127.0.0.1:8080", AgentEgressTransport.LOCAL_SERVER)
                as AgentOriginResult.Valid).origin
        )
        assertEquals(
            "http://[::1]:8080",
            (policy.canonicalOrigin("http://[::1]:8080", AgentEgressTransport.LOCAL_SERVER)
                as AgentOriginResult.Valid).origin
        )
        listOf(
            "http://api.example.com",
            "https://user:pass@example.com",
            "https://example.com/path",
            "https://example.com?query=1",
            "example.com",
            "https://example.com:0"
        ).forEach { raw ->
            val result = policy.canonicalOrigin(raw, AgentEgressTransport.CLOUD_PROVIDER)
            assertTrue("$raw must be rejected", result is AgentOriginResult.Invalid)
        }
    }

    @Test
    fun localServerCannotTargetAPublicHost() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.LOCAL_ONLY, 1L)
        val decision = policy.checkTransport(
            scope, AgentEgressTransport.LOCAL_SERVER, "https://example.com", emptyList()
        )
        assertEquals("POLICY_EGRESS_DENIED", (decision as AgentEgressDecision.Denied).error.code)

        val grant = policy.createGrant(
            scope,
            AgentEgressGrantRequest(
                grantId = "grant-1",
                transport = AgentEgressTransport.LOCAL_SERVER,
                origin = "https://example.com",
                dataClasses = setOf(AgentDataClass.PRIVATE),
                sourceIds = setOf("s1")
            )
        )
        assertEquals("POLICY_EGRESS_DENIED", (grant as AgentEgressGrantOutcome.Rejected).error.code)
    }

    @Test
    fun remoteMcpCannotTargetLoopbackOrPrivateAddresses() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 1L)
        listOf("https://127.0.0.1:8080", "https://[::1]:8080", "https://192.168.1.10").forEach { origin ->
            val decision = policy.checkTransport(scope, AgentEgressTransport.REMOTE_MCP, origin, emptyList())
            assertEquals("POLICY_EGRESS_DENIED", (decision as AgentEgressDecision.Denied).error.code)
        }
    }

    @Test
    fun localOnlyScopeCannotCreateACloudGrant() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.LOCAL_ONLY, 1L)
        val outcome = policy.createGrant(
            scope,
            AgentEgressGrantRequest(
                grantId = "grant-1",
                transport = AgentEgressTransport.CLOUD_PROVIDER,
                origin = "https://api.example.com",
                dataClasses = setOf(AgentDataClass.PRIVATE),
                sourceIds = setOf("s1")
            )
        )
        assertEquals("POLICY_OFFLINE", (outcome as AgentEgressGrantOutcome.Rejected).error.code)
    }

    @Test
    fun inconsistentDuplicateSourceProjectionIsDenied() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 1L)
        val grant = createGrant(scope, "https://api.example.com", setOf(AgentDataClass.PRIVATE), setOf("s1"))
        val payload = AgentEgressPayload(
            AgentEgressTransport.CLOUD_PROVIDER,
            "https://api.example.com",
            modelId = "m",
            sources = listOf(
                AgentSecurityFixtures.source("s1", sha256 = AgentSecurityFixtures.SCHEMA_HASH),
                AgentSecurityFixtures.source("s1", sha256 = AgentSecurityFixtures.OTHER_SCHEMA_HASH)
            )
        )
        val decision = policy.checkPayload(scope, payload, listOf(grant))
        assertEquals("POLICY_EGRESS_DENIED", (decision as AgentEgressDecision.Denied).error.code)
    }

    @Test
    fun grantsAreScopedToTheirRunAndEveryDenialIsTypedAndContentFree() {
        val scope = AgentEgressScope("run-1", AgentNetworkPolicy.NETWORK_ENABLED, 1L)
        val foreignGrant = AgentEgressGrant(
            grantId = "grant-9",
            runId = "run-2",
            transport = AgentEgressTransport.CLOUD_PROVIDER,
            origin = "https://api.example.com",
            allowedDataClasses = setOf(AgentDataClass.PRIVATE),
            allowedSourceIds = setOf("s1"),
            policyVersion = 1L
        )
        val decision = policy.checkTransport(
            scope, AgentEgressTransport.CLOUD_PROVIDER, "https://api.example.com", listOf(foreignGrant)
        )
        assertTrue(decision is AgentEgressDecision.AuthorizationRequired)

        val denied = policy.checkTransport(
            scope, AgentEgressTransport.CLOUD_PROVIDER, "ftp://example.com", listOf(foreignGrant)
        ) as AgentEgressDecision.Denied
        assertEquals("POLICY_EGRESS_DENIED", denied.error.code)
        assertEquals("AgentSecurityError(POLICY_EGRESS_DENIED)", denied.error.toString())
        assertFalse(denied.error.safeMessage.contains("ftp://"))
    }

    private fun createGrant(
        scope: AgentEgressScope,
        origin: String,
        dataClasses: Set<AgentDataClass>,
        sourceIds: Set<String>
    ): AgentEgressGrant {
        val outcome = policy.createGrant(
            scope,
            AgentEgressGrantRequest("grant-1", AgentEgressTransport.CLOUD_PROVIDER, origin, dataClasses, sourceIds)
        )
        assertTrue("expected created grant, got $outcome", outcome is AgentEgressGrantOutcome.Created)
        return (outcome as AgentEgressGrantOutcome.Created).grant
    }
}

package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * K07 broker tests (architecture 7.4 / 8): decision order, one-shot approval binding, TTL,
 * invalidation, typed denials and the two-denial pause. Concurrency lives in
 * [AgentApprovalConcurrencyTest].
 */
class AgentApprovalPermissionBrokerTest {
    private lateinit var store: InMemoryAgentApprovalStore
    private lateinit var broker: AgentPermissionBroker
    private var nextApprovalId = 0
    private val writeDescriptor = AgentSecurityFixtures.descriptor(
        toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
        sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
    )

    @Before
    fun setUp() {
        store = InMemoryAgentApprovalStore()
        nextApprovalId = 0
        broker = AgentPermissionBroker(
            approvalStore = store,
            approvalIdFactory = { "approval-${++nextApprovalId}" }
        )
    }

    @Test
    fun workspaceReadInsideTaskGrantIsAllowForTask() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(),
            """{"path":"inputs/brief.txt"}"""
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, AgentSecurityFixtures.descriptor())
        val decision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs")),
            NOW
        )
        assertTrue("expected ALLOW_FOR_TASK, got $decision", decision is AgentPermissionDecision.AllowForTask)
        assertEquals(AgentPermissionBasis.WORKSPACE_READ_GRANT, (decision as AgentPermissionDecision.AllowForTask).basis)
        assertEquals(0, store.size())

        val searchFrozen = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.SEARCH_TEXT),
            """{"query":"brief"}"""
        )
        val searchDecision = broker.decide(
            AgentSecurityFixtures.candidate(
                searchFrozen,
                AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.SEARCH_TEXT)
            ),
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs")),
            NOW
        )
        assertTrue(searchDecision is AgentPermissionDecision.AllowForTask)
    }

    @Test
    fun workspaceReadOutsideTaskGrantNeedsApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(),
            """{"path":"artifacts/report.md"}"""
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, AgentSecurityFixtures.descriptor())
        val decision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs")),
            NOW
        )
        assertTrue(decision is AgentPermissionDecision.NeedsApproval)
        assertEquals(AgentApprovalStatus.PENDING, (decision as AgentPermissionDecision.NeedsApproval).approval.status)
    }

    @Test
    fun writeAlwaysNeedsApprovalEvenWhenGrantCoversArtifacts() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val decision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs", "artifacts")),
            NOW
        )
        assertTrue("writes must always ASK, got $decision", decision is AgentPermissionDecision.NeedsApproval)
    }

    @Test
    fun readOnlyToolOutsideWorkspaceStillNeedsApproval() = runBlocking {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = "artifact.inspect",
            sideEffect = AgentToolSideEffect.READ_ONLY
        )
        val frozen = AgentSecurityFixtures.freezePassthrough(descriptor)
        val candidate = AgentSecurityFixtures.candidate(frozen, descriptor)
        val decision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs", "artifacts")),
            NOW
        )
        assertTrue(decision is AgentPermissionDecision.NeedsApproval)
    }

    @Test
    fun approvalBindsExactFrozenIdentityAndFiveMinuteTtl() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        val approval = (decision as AgentPermissionDecision.NeedsApproval).approval

        assertEquals(candidate.command.invocationId, approval.invocationId)
        assertEquals(candidate.command.argumentsHash, approval.argumentsHash)
        assertEquals(candidate.command.schemaHash, approval.toolSchemaHash)
        assertEquals(candidate.command.resourceFingerprint, approval.resourceFingerprint)
        assertEquals(candidate.command.runEpoch, approval.runEpoch)
        assertEquals(candidate.command.policyVersion, approval.policyVersion)
        assertEquals(AgentPermissionBroker.LOCAL_PRINCIPAL, approval.principal)
        assertEquals(AgentApprovalStatus.PENDING, approval.status)
        assertEquals(NOW + 300_000L, approval.expiresAtMillis)
        assertTrue(AgentApprovalBinding.of(candidate.command).matches(approval))
    }

    @Test
    fun replayingApprovalCreationDoesNotDuplicateThePendingRow() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val first = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        val second = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW + 1_000L)
        val firstId = (first as AgentPermissionDecision.NeedsApproval).approval.approvalId
        val secondId = (second as AgentPermissionDecision.NeedsApproval).approval.approvalId
        assertEquals(firstId, secondId)
        assertEquals(1, store.insertCount)
    }

    @Test
    fun approvalExpiryIsBoundedByFrozenCommandExpiry() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW + 250_000L)
        val approval = (decision as AgentPermissionDecision.NeedsApproval).approval
        assertEquals(1_300_000L, approval.expiresAtMillis)
    }

    @Test
    fun denialProducesDeniedAndTwoIdenticalProposalsPause() = runBlocking {
        val first = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-1")
        val firstCandidate = AgentSecurityFixtures.candidate(first, writeDescriptor)
        val firstApproval = approveAndGetId(firstCandidate)
        val firstResult = broker.resolveApproval(
            firstCandidate, firstApproval, AgentApprovalDecision.DENY,
            AgentSecurityFixtures.policy(), null, NOW
        )
        assertTrue(firstResult is AgentApprovalResolutionResult.Denied)
        assertNull((firstResult as AgentApprovalResolutionResult.Denied).pauseRunWith)

        val second = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-2")
        val secondCandidate = AgentSecurityFixtures.candidate(second, writeDescriptor)
        val secondApproval = approveAndGetId(secondCandidate)
        val secondResult = broker.resolveApproval(
            secondCandidate, secondApproval, AgentApprovalDecision.DENY,
            AgentSecurityFixtures.policy(), null, NOW
        )
        assertTrue(secondResult is AgentApprovalResolutionResult.Denied)
        assertEquals(
            AgentPauseReason.PERMISSION_DENIED_LOOP,
            (secondResult as AgentApprovalResolutionResult.Denied).pauseRunWith
        )
        assertEquals(AgentApprovalStatus.DENIED, store.statusOf(secondApproval))
    }

    @Test
    fun aDifferentProposalBreaksTheDenialStreak() = runBlocking {
        val first = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs("alpha"), invocationId = "inv-1")
        val firstCandidate = AgentSecurityFixtures.candidate(first, writeDescriptor)
        deny(firstCandidate, approveAndGetId(firstCandidate))

        val other = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs("beta"), invocationId = "inv-2")
        val otherCandidate = AgentSecurityFixtures.candidate(other, writeDescriptor)
        deny(otherCandidate, approveAndGetId(otherCandidate))

        val again = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs("alpha"), invocationId = "inv-3")
        val againCandidate = AgentSecurityFixtures.candidate(again, writeDescriptor)
        val result = deny(againCandidate, approveAndGetId(againCandidate))
        assertNull(result.pauseRunWith)

        val fourth = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs("alpha"), invocationId = "inv-4")
        val fourthCandidate = AgentSecurityFixtures.candidate(fourth, writeDescriptor)
        val loop = deny(fourthCandidate, approveAndGetId(fourthCandidate))
        assertEquals(AgentPauseReason.PERMISSION_DENIED_LOOP, loop.pauseRunWith)
    }

    @Test
    fun doubleClickingDenyDoesNotCountAsASecondProposal() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-1")
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)
        val first = deny(candidate, approvalId)
        assertNull(first.pauseRunWith)
        val replay = broker.resolveApproval(
            candidate, approvalId, AgentApprovalDecision.DENY,
            AgentSecurityFixtures.policy(), null, NOW
        )
        assertTrue(replay is AgentApprovalResolutionResult.Denied)
        assertTrue((replay as AgentApprovalResolutionResult.Denied).replay)
        assertNull(replay.pauseRunWith)

        val second = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-2")
        val secondCandidate = AgentSecurityFixtures.candidate(second, writeDescriptor)
        val secondResult = deny(secondCandidate, approveAndGetId(secondCandidate))
        assertEquals(AgentPauseReason.PERMISSION_DENIED_LOOP, secondResult.pauseRunWith)
    }

    @Test
    fun authorizeConsumesExactlyOneOneShotApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)
        assertEquals(AgentApprovalStatus.PENDING, store.statusOf(approvalId))

        val first = broker.authorize(candidate, approvalId, AgentSecurityFixtures.policy(), null, NOW + 6_000L)
        assertTrue("expected authorization, got $first", first is AgentExecutionAuthorization.Authorized)
        val second = broker.authorize(candidate, approvalId, AgentSecurityFixtures.policy(), null, NOW + 7_000L)
        assertTrue(second is AgentExecutionAuthorization.Denied)
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, (second as AgentExecutionAuthorization.Denied).toolStatus)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
    }

    @Test
    fun expiredApprovalNeverExecutes() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)

        val expiredAt = NOW + 300_001L
        val resolved = broker.resolveApproval(
            candidate, approvalId, AgentApprovalDecision.ALLOW_ONCE,
            AgentSecurityFixtures.policy(), null, expiredAt
        )
        assertTrue(resolved is AgentApprovalResolutionResult.NotExecuted)
        assertEquals("PERMISSION_EXPIRED", (resolved as AgentApprovalResolutionResult.NotExecuted).error.code)

        val authorization = broker.authorize(candidate, approvalId, AgentSecurityFixtures.policy(), null, expiredAt)
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        val denied = authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, denied.toolStatus)
        assertEquals("PERMISSION_EXPIRED", denied.error.code)
    }

    @Test
    fun policyVersionChangeInvalidatesPendingApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)

        val authorization = broker.authorize(
            candidate, approvalId,
            AgentSecurityFixtures.policy(policyVersion = 8L), null, NOW
        )
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        val denied = authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, denied.toolStatus)
        assertEquals("PERMISSION_SCOPE_REVOKED", denied.error.code)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun runEpochChangeInvalidatesPendingApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)

        val authorization = broker.authorize(
            candidate, approvalId,
            AgentSecurityFixtures.policy(runEpoch = 4L), null, NOW
        )
        assertEquals(
            "PERMISSION_SCOPE_REVOKED",
            (authorization as AgentExecutionAuthorization.Denied).error.code
        )
    }

    @Test
    fun offlinePolicyChangeInvalidatesNetworkApproval() = runBlocking {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = "mcp.echo",
            sideEffect = AgentToolSideEffect.NETWORK
        )
        val frozen = AgentSecurityFixtures.freezePassthrough(
            descriptor,
            networkDestinations = listOf("https://api.example.com")
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, descriptor)

        val offlineDecision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY),
            null,
            NOW
        )
        assertTrue(offlineDecision is AgentPermissionDecision.Denied)
        assertEquals(
            "POLICY_OFFLINE",
            (offlineDecision as AgentPermissionDecision.Denied).error.code
        )

        val onlineApproval = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED),
            null,
            NOW
        )
        val approvalId = (onlineApproval as AgentPermissionDecision.NeedsApproval).approval.approvalId
        val switchedOffline = broker.authorize(
            candidate,
            approvalId,
            AgentSecurityFixtures.policy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY),
            null,
            NOW
        )
        assertTrue(switchedOffline is AgentExecutionAuthorization.Denied)
        assertEquals(
            "POLICY_OFFLINE",
            (switchedOffline as AgentExecutionAuthorization.Denied).error.code
        )
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, switchedOffline.toolStatus)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun revokedWorkspaceGrantInvalidatesPendingApprovalAndRemovesAllowForTask() = runBlocking {
        val writeFrozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val writeCandidate = AgentSecurityFixtures.candidate(writeFrozen, writeDescriptor)
        val approvalId = approveAndGetId(writeCandidate)
        val authorization = broker.authorize(
            writeCandidate, approvalId, AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(revoked = true), NOW
        )
        assertEquals(
            "PERMISSION_SCOPE_REVOKED",
            (authorization as AgentExecutionAuthorization.Denied).error.code
        )
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))

        val readFrozen = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(),
            """{"path":"inputs/brief.txt"}"""
        )
        val readCandidate = AgentSecurityFixtures.candidate(readFrozen, AgentSecurityFixtures.descriptor())
        val readDecision = broker.decide(
            readCandidate, AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(revoked = true), NOW
        )
        assertTrue(readDecision is AgentPermissionDecision.Denied)
        assertEquals(
            "PERMISSION_SCOPE_REVOKED",
            (readDecision as AgentPermissionDecision.Denied).error.code
        )
    }

    @Test
    fun toolUpdateCannotReuseAnOldApprovalAndExplicitInvalidationApplies() = runBlocking {
        val descriptorV1 = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            schemaHash = AgentSecurityFixtures.SCHEMA_HASH,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val descriptorV2 = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            schemaHash = AgentSecurityFixtures.OTHER_SCHEMA_HASH,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozenV1 = AgentSecurityFixtures.freeze(descriptorV1, writeArgs())
        val candidateV1 = AgentSecurityFixtures.candidate(frozenV1, descriptorV1)
        val approvalId = approveAndGetId(candidateV1)

        val frozenV2 = AgentSecurityFixtures.freeze(descriptorV2, writeArgs())
        val candidateV2 = AgentSecurityFixtures.candidate(frozenV2, descriptorV2)
        val mismatch = broker.authorize(candidateV2, approvalId, AgentSecurityFixtures.policy(), null, NOW)
        assertEquals(
            "PERMISSION_ARGUMENTS_CHANGED",
            (mismatch as AgentExecutionAuthorization.Denied).error.code
        )
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, mismatch.toolStatus)
        // A binding mismatch burns the pending approval: a stale prompt can never be retried.
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
        assertFalse(broker.invalidateApproval(approvalId, NOW))

        val afterInvalidation = broker.authorize(candidateV1, approvalId, AgentSecurityFixtures.policy(), null, NOW)
        assertEquals(
            "PERMISSION_SCOPE_REVOKED",
            (afterInvalidation as AgentExecutionAuthorization.Denied).error.code
        )
    }

    @Test
    fun userDenialMapsToDeniedToolStatusOnAuthorize() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)
        deny(candidate, approvalId)
        val authorization = broker.authorize(candidate, approvalId, AgentSecurityFixtures.policy(), null, NOW)
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        val denied = authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.DENIED, denied.toolStatus)
        assertEquals("PERMISSION_DENIED", denied.error.code)
    }

    @Test
    fun tamperedFrozenCommandIsRejectedBeforeAnyApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val tampered = frozen.command.copy(argumentsHash = "0".repeat(64))
        val candidate = AgentPermissionCandidate(tampered, writeDescriptor, frozen.scope)
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        assertTrue(decision is AgentPermissionDecision.Denied)
        val denied = decision as AgentPermissionDecision.Denied
        assertEquals("INTERNAL_INVARIANT", denied.error.code)
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, denied.toolStatus)
        assertEquals(0, store.size())
    }

    @Test
    fun toStringsNeverLeakArgumentContent() = runBlocking {
        val secret = "PRIVATE-CONTENT-xyz"
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(content = secret))
        val candidate = AgentPermissionCandidate(frozen.command, writeDescriptor, frozen.scope)
        assertFalse(frozen.toString().contains(secret))
        assertFalse(candidate.toString().contains(secret))
        val request = AgentSecurityFixtures.request(writeDescriptor, writeArgs(content = secret))
        assertFalse(request.toString().contains(secret))
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        assertFalse(decision.toString().contains(secret))
        assertFalse(decision.toString().contains("artifacts/report.md"))
    }

    @Test
    fun policyChangeBeforeApprovalResolutionInvalidatesIt() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val approvalId = approveAndGetId(candidate)
        val result = broker.resolveApproval(
            candidate, approvalId, AgentApprovalDecision.ALLOW_ONCE,
            AgentSecurityFixtures.policy(policyVersion = 8L), null, NOW
        )
        assertTrue(result is AgentApprovalResolutionResult.NotExecuted)
        assertEquals("PERMISSION_SCOPE_REVOKED", (result as AgentApprovalResolutionResult.NotExecuted).error.code)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun expiredFrozenCommandCannotCreateAnApproval() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW + 300_000L)
        assertTrue(decision is AgentPermissionDecision.Denied)
        assertEquals("PERMISSION_EXPIRED", (decision as AgentPermissionDecision.Denied).error.code)
        assertEquals(0, store.size())
    }

    @Test
    fun expiredPendingApprovalIsReplacedWhenFrozenCommandOutlivesIt() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(
            writeDescriptor,
            writeArgs(),
            approvalTtlMillis = 600_000L
        )
        val candidate = AgentSecurityFixtures.candidate(frozen, writeDescriptor)
        val first = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        val firstApproval = (first as AgentPermissionDecision.NeedsApproval).approval
        assertEquals(NOW + 300_000L, firstApproval.expiresAtMillis)

        val second = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW + 350_000L)
        assertTrue("expected a replacement approval, got $second", second is AgentPermissionDecision.NeedsApproval)
        val replacement = (second as AgentPermissionDecision.NeedsApproval).approval
        assertNotEquals(firstApproval.approvalId, replacement.approvalId)
        assertEquals(AgentApprovalStatus.PENDING, replacement.status)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(firstApproval.approvalId))
    }

    @Test
    fun sameArgumentsInADifferentInvocationCannotReuseTheApproval() = runBlocking {
        val first = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-1")
        val firstCandidate = AgentSecurityFixtures.candidate(first, writeDescriptor)
        val approvalId = approveAndGetId(firstCandidate)

        val second = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs(), invocationId = "inv-2")
        val secondCandidate = AgentSecurityFixtures.candidate(second, writeDescriptor)
        val authorization = broker.authorize(secondCandidate, approvalId, AgentSecurityFixtures.policy(), null, NOW)
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        val denied = authorization as AgentExecutionAuthorization.Denied
        assertEquals("PERMISSION_ARGUMENTS_CHANGED", denied.error.code)
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, denied.toolStatus)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun aTamperedResolvedScopeIsRejectedAsInternalInvariant() = runBlocking {
        val frozen = AgentSecurityFixtures.freeze(writeDescriptor, writeArgs())
        val tamperedScope = frozen.scope.copy(
            expectedOldHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        val candidate = AgentPermissionCandidate(frozen.command, writeDescriptor, tamperedScope)
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        assertTrue(decision is AgentPermissionDecision.Denied)
        assertEquals("INTERNAL_INVARIANT", (decision as AgentPermissionDecision.Denied).error.code)
        assertEquals(0, store.size())
    }

    @Test
    fun externalSideEffectToolsAlwaysNeedApproval() = runBlocking {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = "shell.run",
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT
        )
        val frozen = AgentSecurityFixtures.freezePassthrough(descriptor)
        val candidate = AgentSecurityFixtures.candidate(frozen, descriptor)
        val decision = broker.decide(
            candidate,
            AgentSecurityFixtures.policy(),
            AgentSecurityFixtures.grant(listOf("inputs", "artifacts")),
            NOW
        )
        assertTrue("external effects must always ASK, got $decision", decision is AgentPermissionDecision.NeedsApproval)
    }

    private suspend fun approveAndGetId(candidate: AgentPermissionCandidate): String {
        val decision = broker.decide(candidate, AgentSecurityFixtures.policy(), null, NOW)
        assertTrue("expected approval, got $decision", decision is AgentPermissionDecision.NeedsApproval)
        return (decision as AgentPermissionDecision.NeedsApproval).approval.approvalId
    }

    private suspend fun deny(
        candidate: AgentPermissionCandidate,
        approvalId: String
    ): AgentApprovalResolutionResult.Denied {
        val result = broker.resolveApproval(
            candidate, approvalId, AgentApprovalDecision.DENY,
            AgentSecurityFixtures.policy(), null, NOW
        )
        assertTrue("expected denied, got $result", result is AgentApprovalResolutionResult.Denied)
        return result as AgentApprovalResolutionResult.Denied
    }

    private fun writeArgs(
        content: String = "hello",
        expectedOldHash: String? = null
    ): String {
        val hashJson = expectedOldHash?.let { "\"$it\"" } ?: "null"
        return """{"path":"artifacts/report.md","content":"$content","expectedOldHash":$hashJson}"""
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}

package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 approval closure matrix, independent of the existing K07 broker/concurrency tests.
 *
 * Covers AGENT_TOOLS_MEMORY_WORKFLOW_PLAN §4.1 with the post-P0 one-shot gate
 * [AgentApprovalStorePort.consumePending]: NeedsApproval creates exactly one PENDING row, the first
 * authorize consumes it exactly once, and double click/replay/expiry/policy/epoch/binding mismatch
 * can never reach an executor.
 *
 * The store is [P0ClosureApprovalStore], a strict JVM CAS model, so the matrix also fails if the
 * fixture drifts from the documented port contract. The room adapter's conditional UPDATE is
 * covered by `AgentInvocationApprovalSchemaClosureTest` only at the SQL-index level; its DAO
 * behavior remains an instrumentation item.
 */
class AgentApprovalConsumeClosureTest {
    private lateinit var store: P0ClosureApprovalStore
    private lateinit var broker: AgentPermissionBroker
    private var nextApprovalId = 0
    private var executions = 0

    private val writeDescriptor = AgentSecurityFixtures.descriptor(
        toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
        sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
    )

    @Before
    fun setUp() {
        store = P0ClosureApprovalStore()
        nextApprovalId = 0
        executions = 0
        broker = AgentPermissionBroker(
            approvalStore = store,
            approvalIdFactory = { "p0-approval-${++nextApprovalId}" }
        )
    }

    @Test
    fun needsApprovalCreatesExactlyOnePendingApprovalForTheInvocation() = runBlocking {
        val candidate = freezeCandidate()
        val first = broker.decide(candidate, policy(), null, NOW)
        val second = broker.decide(candidate, policy(), null, NOW + 1L)

        assertTrue(first is AgentPermissionDecision.NeedsApproval)
        assertTrue(second is AgentPermissionDecision.NeedsApproval)
        val firstApproval = (first as AgentPermissionDecision.NeedsApproval).approval
        val secondApproval = (second as AgentPermissionDecision.NeedsApproval).approval
        assertEquals(firstApproval.approvalId, secondApproval.approvalId)
        assertEquals(1, store.pendingCountFor(candidate.command.invocationId))
        assertEquals(1, store.insertCount)
        assertEquals(AgentApprovalStatus.PENDING, store.statusOf(firstApproval.approvalId))
    }

    @Test
    fun firstAuthorizeConsumesThePendingApprovalExactlyOnce() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)

        val authorized = authorize(candidate, approvalId, policy(), NOW + 1L)
        assertTrue("first authorize must win, got $authorized", authorized is AgentExecutionAuthorization.Authorized)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
        assertEquals(1, executions)

        val replay = authorize(candidate, approvalId, policy(), NOW + 2L)
        assertTrue(replay is AgentExecutionAuthorization.Denied)
        assertEquals(
            AgentToolResultStatus.NOT_EXECUTED,
            (replay as AgentExecutionAuthorization.Denied).toolStatus
        )
        assertEquals(1, executions)
    }

    @Test
    fun approvalAtItsAbsoluteExpiryNeverExecutes() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)

        val expiry = candidate.command.expiresAtMillis
        val expired = authorize(candidate, approvalId, policy(), expiry)
        assertTrue(expired is AgentExecutionAuthorization.Denied)
        assertEquals(
            "PERMISSION_EXPIRED",
            (expired as AgentExecutionAuthorization.Denied).error.code
        )
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, expired.toolStatus)
        assertNotEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
        assertEquals(0, executions)
    }

    @Test
    fun deniedApprovalMapsToDeniedAndNeverConsumes() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)
        val denied = broker.denyPending(
            approvalId = approvalId,
            binding = AgentApprovalBinding.of(candidate.command),
            nowMillis = NOW
        )
        assertTrue(denied is AgentApprovalResolveOutcome.Resolved)
        assertEquals(
            AgentApprovalStatus.DENIED,
            (denied as AgentApprovalResolveOutcome.Resolved).approval.status
        )

        val authorization = authorize(candidate, approvalId, policy(), NOW + 1L)
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.DENIED, authorization.toolStatus)
        assertEquals("PERMISSION_DENIED", authorization.error.code)
        assertEquals(AgentApprovalStatus.DENIED, store.statusOf(approvalId))
        assertEquals(0, executions)
    }

    @Test
    fun explicitlyInvalidatedApprovalNeverExecutes() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)
        assertTrue(broker.invalidateApproval(approvalId, NOW + 1L))

        val authorization = authorize(candidate, approvalId, policy(), NOW + 2L)
        assertTrue(authorization is AgentExecutionAuthorization.Denied)
        authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, authorization.toolStatus)
        assertEquals("PERMISSION_SCOPE_REVOKED", authorization.error.code)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
        assertEquals(0, executions)
    }

    @Test
    fun unknownApprovalNeverExecutes() = runBlocking {
        val candidate = freezeCandidate()
        val authorization = authorize(candidate, "p0-unknown-approval", policy(), NOW)
        assertNotExecuted(authorization, "PERMISSION_EXPIRED")
    }

    @Test
    fun changedArgumentsCannotConsumeTheApprovalAndBurnsIt() = runBlocking {
        val approvalId = decideToApprovalId(freezeCandidate())
        val changed = freezeCandidate(argumentsJson = writeArgs(content = "beta"))
        val authorization = authorize(changed, approvalId, policy(), NOW)
        assertNotExecuted(authorization, "PERMISSION_ARGUMENTS_CHANGED")
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun differentInvocationCannotConsumeTheApprovalAndBurnsIt() = runBlocking {
        val approvalId = decideToApprovalId(freezeCandidate(invocationId = "p0-inv-1"))
        val otherInvocation = freezeCandidate(invocationId = "p0-inv-2")
        val authorization = authorize(otherInvocation, approvalId, policy(), NOW)
        assertNotExecuted(authorization, "PERMISSION_ARGUMENTS_CHANGED")
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun changedToolSchemaVersionCannotConsumeTheApprovalAndBurnsIt() = runBlocking {
        val approvalId = decideToApprovalId(freezeCandidate())
        val upgradedDescriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            schemaHash = AgentSecurityFixtures.OTHER_SCHEMA_HASH,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val upgraded = AgentSecurityFixtures.candidate(
            AgentSecurityFixtures.freeze(upgradedDescriptor, writeArgs(), invocationId = "p0-inv-1"),
            upgradedDescriptor
        )
        val authorization = authorize(upgraded, approvalId, policy(), NOW)
        assertNotExecuted(authorization, "PERMISSION_ARGUMENTS_CHANGED")
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun changedResourceFingerprintCannotConsumeTheApprovalAndBurnsIt() = runBlocking {
        val approvalId = decideToApprovalId(freezeCandidate())
        val otherPath = freezeCandidate(
            argumentsJson = writeArgs(content = "hello", path = "artifacts/other.md")
        )
        val authorization = authorize(otherPath, approvalId, policy(), NOW)
        assertNotExecuted(authorization, "PERMISSION_ARGUMENTS_CHANGED")
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approvalId))
    }

    @Test
    fun policyVersionChangeIsRejectedBeforeConsume() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)
        val authorization = authorize(
            candidate, approvalId, policy(policyVersion = 8L), NOW + 1L
        )
        assertNotExecuted(authorization, "PERMISSION_SCOPE_REVOKED")
        assertNotEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
    }

    @Test
    fun runEpochChangeIsRejectedBeforeConsume() = runBlocking {
        val candidate = freezeCandidate()
        val approvalId = decideToApprovalId(candidate)
        val authorization = authorize(
            candidate, approvalId, policy(runEpoch = 4L), NOW + 1L
        )
        assertNotExecuted(authorization, "PERMISSION_SCOPE_REVOKED")
        assertNotEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approvalId))
    }

    @Test
    fun concurrentAuthorizeCallsConsumeExactlyOnce() {
        val resolutionCount = AtomicInteger()
        val executionCount = AtomicInteger()
        val authorized = AtomicInteger()
        val notExecuted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val candidate = freezeCandidate()
            val approvalId = runBlocking { decideToApprovalId(candidate) }
            val start = CountDownLatch(1)
            val futures = (1..64).map {
                pool.submit(
                    Callable {
                        start.await()
                        runBlocking {
                            resolutionCount.incrementAndGet()
                            when (
                                val result = broker.authorize(
                                    candidate, approvalId, policy(), null, NOW + 2L
                                )
                            ) {
                                is AgentExecutionAuthorization.Authorized -> {
                                    authorized.incrementAndGet()
                                    executionCount.incrementAndGet()
                                }
                                is AgentExecutionAuthorization.Denied -> {
                                    assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.toolStatus)
                                    notExecuted.incrementAndGet()
                                }
                            }
                        }
                    }
                )
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(64, resolutionCount.get())
        assertEquals("exactly one one-shot approval may be authorized", 1, authorized.get())
        assertEquals("executor must run exactly once", 1, executionCount.get())
        assertEquals("all other clicks are NOT_EXECUTED", 63, notExecuted.get())
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(store.snapshot().single().approvalId))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    private suspend fun decideToApprovalId(candidate: AgentPermissionCandidate): String {
        val decision = broker.decide(candidate, policy(), null, NOW)
        assertTrue("expected NeedsApproval, got $decision", decision is AgentPermissionDecision.NeedsApproval)
        return (decision as AgentPermissionDecision.NeedsApproval).approval.approvalId
    }

    /** Only [AgentExecutionAuthorization.Authorized] may reach an executor; count that side effect. */
    private suspend fun authorize(
        candidate: AgentPermissionCandidate,
        approvalId: String,
        policy: AgentPermissionPolicy,
        nowMillis: Long
    ): AgentExecutionAuthorization {
        val result = broker.authorize(candidate, approvalId, policy, null, nowMillis)
        if (result is AgentExecutionAuthorization.Authorized) {
            executions += 1
        }
        return result
    }

    private fun freezeCandidate(
        invocationId: String = "p0-inv-1",
        argumentsJson: String = writeArgs()
    ): AgentPermissionCandidate {
        val frozen = AgentSecurityFixtures.freeze(
            writeDescriptor,
            argumentsJson,
            invocationId = invocationId
        )
        return AgentSecurityFixtures.candidate(frozen, writeDescriptor)
    }

    private fun assertNotExecuted(
        authorization: AgentExecutionAuthorization,
        expectedCode: String
    ) {
        assertTrue("expected Denied, got $authorization", authorization is AgentExecutionAuthorization.Denied)
        authorization as AgentExecutionAuthorization.Denied
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, authorization.toolStatus)
        assertEquals(expectedCode, authorization.error.code)
        assertNotNull(authorization.error.safeMessage)
        assertEquals(0, executions)
    }

    private fun policy(
        runEpoch: Long = 3L,
        policyVersion: Long = 7L
    ): AgentPermissionPolicy = AgentSecurityFixtures.policy(
        runEpoch = runEpoch,
        policyVersion = policyVersion
    )

    private fun writeArgs(
        content: String = "hello",
        path: String = "artifacts/report.md"
    ): String = """{"path":"$path","content":"$content","expectedOldHash":null}"""

    private companion object {
        const val NOW = 1_000_000L
    }
}

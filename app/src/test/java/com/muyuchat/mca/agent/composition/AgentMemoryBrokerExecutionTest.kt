package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.mca.agent.data.AgentTestFixtures
import com.muyuchat.mca.agent.memory.AgentMemoryResourceScopes
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.AgentMemoryTestFixtures
import com.muyuchat.mca.agent.memory.AgentMemoryToolArgumentNormalizer
import com.muyuchat.mca.agent.memory.AgentMemoryToolDescriptors
import com.muyuchat.mca.agent.memory.InMemoryAgentMemoryPersistence
import com.muyuchat.mca.agent.memory.MemorySearchTool
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.security.AgentPermissionBroker
import com.muyuchat.mca.agent.security.AgentPermissionCandidate
import com.muyuchat.mca.agent.security.AgentPermissionDecision
import com.muyuchat.mca.agent.security.AgentPermissionPolicy
import com.muyuchat.mca.agent.security.AgentExecutionAuthorization
import com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant
import com.muyuchat.mca.agent.security.InMemoryAgentApprovalStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (JVM) proof that a run can invoke `memory.search` exactly once through the existing
 * freeze → broker → authorize → executor path.
 *
 * The frozen selection is a real [AgentMemorySelectionService] over an in-memory persistence port;
 * the broker is the real [AgentPermissionBroker] over the in-memory approval CAS fake. The memory
 * resource label is not a workspace path, so V1 requires one explicit approval per call; the test
 * therefore consumes the approval once and proves the replay is rejected before execution.
 */
class AgentMemoryBrokerExecutionTest {
    private val persistence = InMemoryAgentMemoryPersistence().apply {
        seedSpace("space-a")
        seedItem("memory-1", "space-a", "The launch plan is approved for October.")
    }
    private val store = AgentMemoryTestFixtures.store(
        persistence,
        AgentMemoryTestFixtures.sequentialIds()
    )
    private val selections = AgentMemorySelectionService(
        persistence,
        clock = { AgentMemoryTestFixtures.NOW }
    )
    private val task = AgentTestFixtures.task()
    private val run = AgentTestFixtures.run()

    @Test
    fun `memory search is frozen authorized executed once and then rejected`() = runBlocking {
        selections.freezeForTask(task.taskId, listOf("space-a"))
        val descriptor = AgentMemoryToolDescriptors.MEMORY_SEARCH
        val typedScope = requireNotNull(AgentMemoryResourceScopes.precondition(setOf("space-a")))
        val frozen = AgentFrozenCommandFactory(normalizer = AgentMemoryToolArgumentNormalizer()).freeze(
            AgentFreezeRequest(
                descriptor = descriptor,
                rawArgumentsJson = """{"query":"launch plan"}""",
                invocationId = "invocation-memory-1",
                runId = run.runId,
                stepId = "step-1",
                callOrdinal = 0,
                workspaceId = task.workspaceId,
                taskId = task.taskId,
                runEpoch = run.epoch,
                policyVersion = run.policy.policyVersion,
                nowMillis = AgentMemoryTestFixtures.NOW,
                typedScope = typedScope
            )
        )
        assertTrue("expected frozen memory.search, got $frozen", frozen is AgentFreezeResult.Frozen)
        val frozenCommand = frozen as AgentFreezeResult.Frozen
        val candidate = AgentPermissionCandidate(
            command = frozenCommand.command,
            descriptor = descriptor,
            scope = frozenCommand.scope
        )
        val approvalStore = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(approvalStore)
        val policy = AgentPermissionPolicy(
            runEpoch = run.epoch,
            policyVersion = run.policy.policyVersion,
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY
        )
        val grant = AgentWorkspaceReadGrant.v1(task)

        val decision = broker.decide(candidate, policy, grant, AgentMemoryTestFixtures.NOW)
        assertTrue("memory.search must pass through the approval broker, got $decision", decision is AgentPermissionDecision.NeedsApproval)
        val approvalId = (decision as AgentPermissionDecision.NeedsApproval).approval.approvalId

        val authorization = broker.authorize(
            candidate = candidate,
            approvalId = approvalId,
            policy = policy,
            grant = grant,
            nowMillis = AgentMemoryTestFixtures.NOW + 1L
        )
        assertTrue("expected one authorization, got $authorization", authorization is AgentExecutionAuthorization.Authorized)
        assertEquals(AgentApprovalStatus.CONSUMED, approvalStore.statusOf(approvalId))

        val result = MemorySearchTool(store, selections).execute(
            (authorization as AgentExecutionAuthorization.Authorized).command,
            AgentMemoryTestFixtures.context(task.taskId)
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(1, JSONObject(requireNotNull(result.dataJson)).getJSONObject("data").getInt("citationCount"))

        val replay = broker.authorize(
            candidate = candidate,
            approvalId = approvalId,
            policy = policy,
            grant = grant,
            nowMillis = AgentMemoryTestFixtures.NOW + 2L
        )
        assertTrue("a consumed approval must never authorize again, got $replay", replay is AgentExecutionAuthorization.Denied)
        assertEquals(
            AgentErrorCodes.PERMISSION_EXPIRED,
            (replay as AgentExecutionAuthorization.Denied).error.code
        )
    }
}

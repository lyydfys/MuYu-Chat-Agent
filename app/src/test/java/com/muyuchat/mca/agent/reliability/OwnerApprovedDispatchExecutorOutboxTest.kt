package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production executor adapter is a strict delegate: it never converts a refusal into success. */
class OwnerApprovedDispatchExecutorOutboxTest {

    @Test
    fun aHandedOffReceiptIsPassedThroughUnchanged() = runBlocking {
        val seen = mutableListOf<String>()
        val executor = OwnerApprovedDispatchExecutor { entry ->
            seen += entry.invocationId
            AgentDispatchExecution.HandedOff
        }
        val entry = entryFor("invocation-executor")

        assertEquals(AgentDispatchExecution.HandedOff, executor.dispatch(entry))
        assertEquals(listOf("invocation-executor"), seen)
    }

    @Test
    fun aRefusalIsNotRewrittenAsSettlement() = runBlocking {
        val executor = OwnerApprovedDispatchExecutor {
            AgentDispatchExecution.Refused("RESOURCE_LEASE_LOST", "no authorization")
        }

        val outcome = executor.dispatch(entryFor("invocation-refused"))

        assertTrue(outcome is AgentDispatchExecution.Refused)
        assertEquals("RESOURCE_LEASE_LOST", (outcome as AgentDispatchExecution.Refused).errorCode)
    }

    @Test
    fun aTerminalReceiptIsPassedThroughWithItsStatus() = runBlocking {
        val executor = OwnerApprovedDispatchExecutor {
            AgentDispatchExecution.Settled(ToolInvocationStatus.NOT_EXECUTED)
        }

        assertEquals(
            AgentDispatchExecution.Settled(ToolInvocationStatus.NOT_EXECUTED),
            executor.dispatch(entryFor("invocation-settled"))
        )
    }

    private fun entryFor(invocationId: String): AgentDispatchOutboxEntry = AgentDispatchOutboxEntry(
        idempotencyKey = AgentDispatchOutboxEntry.keyFor(invocationId),
        invocationId = invocationId,
        runId = "run-1",
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = "workspace.read_text",
        toolVersion = "1.0.0",
        approvedAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )
}

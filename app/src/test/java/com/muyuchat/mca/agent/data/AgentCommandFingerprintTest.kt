package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentCommandFingerprintTest {
    @Test
    fun `content hash ignores commandId and expectedRevision`() {
        val first = CreateTaskTransition(
            commandId = "command-1",
            task = AgentTestFixtures.task()
        )
        val retry = first.copy(commandId = "command-2")
        val revisionChanged = UpdateRunTransition(
            commandId = "command-1",
            run = AgentTestFixtures.run(revision = 3L),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 3L
        )
        val sameContentOtherRevision = revisionChanged.copy(expectedRevision = 9L)
        assertEquals(
            AgentCommandFingerprint.fingerprint(first),
            AgentCommandFingerprint.fingerprint(retry)
        )
        assertEquals(
            AgentCommandFingerprint.fingerprint(revisionChanged),
            AgentCommandFingerprint.fingerprint(sameContentOtherRevision)
        )
    }

    @Test
    fun `content changes produce a different hash`() {
        val base = UpdateRunTransition(
            commandId = "command-1",
            run = AgentTestFixtures.run(state = AgentRunState.RUNNING),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 3L
        )
        val terminal = base.copy(
            run = base.run.copy(state = AgentRunState.SUCCEEDED),
            taskDisplay = AgentDisplayStatus.SUCCEEDED
        )
        assertNotEquals(
            AgentCommandFingerprint.fingerprint(base),
            AgentCommandFingerprint.fingerprint(terminal)
        )
        val creation = CreateAgentTask(
            commandId = "command-1",
            goal = "goal a",
            displayTitle = "title",
            modelBinding = AgentTestFixtures.binding(),
            policy = AgentTestFixtures.policy(),
            budget = AgentTestFixtures.budget()
        )
        val changed = creation.copy(goal = "goal b")
        assertNotEquals(
            AgentCommandFingerprint.createTaskFingerprint(creation),
            AgentCommandFingerprint.createTaskFingerprint(changed)
        )
    }

    @Test
    fun `create task fingerprint includes the frozen memory selection`() {
        val base = CreateAgentTask(
            commandId = "command-1",
            goal = "goal a",
            displayTitle = "title",
            modelBinding = AgentTestFixtures.binding(),
            policy = AgentTestFixtures.policy(),
            budget = AgentTestFixtures.budget()
        )
        val selected = base.copy(memorySpaceIds = setOf("space-a"))
        assertNotEquals(
            AgentCommandFingerprint.createTaskFingerprint(base),
            AgentCommandFingerprint.createTaskFingerprint(selected)
        )
        // Whitespace/order variants of the same selection are one logical command.
        assertEquals(
            AgentCommandFingerprint.createTaskFingerprint(selected),
            AgentCommandFingerprint.createTaskFingerprint(
                base.copy(memorySpaceIds = setOf(" space-a ", "space-a"))
            )
        )
        // A policy-only selection is also part of the frozen content.
        assertNotEquals(
            AgentCommandFingerprint.createTaskFingerprint(base),
            AgentCommandFingerprint.createTaskFingerprint(
                base.copy(policy = base.policy.copy(memorySpaceIds = setOf("space-a")))
            )
        )
    }

    @Test
    fun `invocation hash includes ordinal status and idempotency key`() {
        val base = UpsertInvocationTransition(
            commandId = "command-1",
            invocation = AgentTestFixtures.invocation(),
            expectedRevision = 3L
        )
        val otherStatus = base.copy(
            invocation = base.invocation.copy(status = ToolInvocationStatus.RUNNING)
        )
        val otherKey = base.copy(
            invocation = base.invocation.copy(idempotencyKey = "idem-2")
        )
        val otherOrdinal = base.copy(
            invocation = base.invocation.copy(callOrdinal = 1)
        )
        val hashes = setOf(
            AgentCommandFingerprint.fingerprint(base),
            AgentCommandFingerprint.fingerprint(otherStatus),
            AgentCommandFingerprint.fingerprint(otherKey),
            AgentCommandFingerprint.fingerprint(otherOrdinal)
        )
        assertEquals(4, hashes.size)
    }

    @Test
    fun `command type and scope are stable`() {
        val create = CreateTaskTransition("command-1", AgentTestFixtures.task())
        assertEquals("CREATE_TASK", AgentCommandFingerprint.commandType(create))
        assertEquals(AgentTestFixtures.TASK_ID, AgentCommandFingerprint.scopeId(create))
        val createRun = CreateRunTransition(
            commandId = "command-2",
            run = AgentTestFixtures.run(state = AgentRunState.QUEUED),
            taskRevision = 2L,
            taskDisplay = AgentDisplayStatus.QUEUED
        )
        assertEquals("CREATE_RUN", AgentCommandFingerprint.commandType(createRun))
        assertEquals(AgentTestFixtures.TASK_ID, AgentCommandFingerprint.scopeId(createRun))
    }

    @Test
    fun `approval consumption hash includes the decision`() {
        val allow = ConsumeApprovalTransition(
            commandId = "command-1",
            approvalId = "approval-1",
            consumedAtMillis = 1_000L,
            nextRun = null,
            taskDisplay = null,
            expectedRevision = 3L,
            decision = AgentApprovalDecision.ALLOW_ONCE
        )
        val deny = allow.copy(decision = AgentApprovalDecision.DENY)
        assertNotEquals(
            AgentCommandFingerprint.fingerprint(allow),
            AgentCommandFingerprint.fingerprint(deny)
        )
    }

    @Test
    fun `hash is a lowercase sha256`() {
        val fingerprint = AgentCommandFingerprint.fingerprint(
            CreateTaskTransition("command-1", AgentTestFixtures.task())
        )
        assertEquals(64, fingerprint.length)
        assertEquals(true, fingerprint.matches(Regex("^[0-9a-f]{64}$")))
    }
}

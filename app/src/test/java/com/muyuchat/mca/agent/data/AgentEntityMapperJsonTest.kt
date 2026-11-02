package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentEntityMapperJsonTest {
    @Test
    fun `model binding policy budget and spent budget round trip`() {
        val binding = AgentTestFixtures.binding()
        assertEquals(binding, AgentRecordJson.decodeBinding(AgentRecordJson.encodeBinding(binding)))
        val policy = AgentTestFixtures.policy(version = 5L)
        assertEquals(policy, AgentRecordJson.decodePolicy(AgentRecordJson.encodePolicy(policy)))
        val unlimitedTools = policy.copy(enabledToolKeys = null)
        assertEquals(
            unlimitedTools,
            AgentRecordJson.decodePolicy(AgentRecordJson.encodePolicy(unlimitedTools))
        )
        val budget = AgentTestFixtures.budget()
        assertEquals(budget, AgentRecordJson.decodeBudget(AgentRecordJson.encodeBudget(budget)))
        val spent = AgentTestFixtures.spent()
        assertEquals(spent, AgentRecordJson.decodeSpent(AgentRecordJson.encodeSpent(spent)))
    }

    @Test
    fun `memory selection policy field is additive and old rows decode to empty`() {
        val selected = AgentTestFixtures.policy(version = 5L).copy(
            memorySpaceIds = setOf("space-b", "space-a")
        )
        assertEquals(selected, AgentRecordJson.decodePolicy(AgentRecordJson.encodePolicy(selected)))

        // Empty stays byte-identical to the pre-memory encoding, preserving stored hashes.
        assertFalse(AgentRecordJson.encodePolicy(AgentTestFixtures.policy()).contains("memorySpaceIds"))

        val legacy = "{\"networkPolicy\":\"LOCAL_ONLY\",\"policyVersion\":1," +
            "\"enabledToolKeys\":null,\"skillIds\":[],\"offlineForced\":true}"
        assertEquals(emptySet<String>(), AgentRecordJson.decodePolicy(legacy).memorySpaceIds)
    }

    @Test
    fun `workflow pin is additive and old rows decode to no workflow`() {
        val pinned = AgentTestFixtures.policy(version = 5L).copy(
            workflow = AgentWorkflowRunSpec("wf-definition", 3)
        )
        assertEquals(pinned, AgentRecordJson.decodePolicy(AgentRecordJson.encodePolicy(pinned)))

        // A normal run keeps byte-identical policy JSON, so stored idempotency hashes do not move.
        assertFalse(AgentRecordJson.encodePolicy(AgentTestFixtures.policy()).contains("workflow"))

        val legacy = "{\"networkPolicy\":\"LOCAL_ONLY\",\"policyVersion\":1," +
            "\"enabledToolKeys\":null,\"skillIds\":[],\"offlineForced\":true}"
        assertNull(AgentRecordJson.decodePolicy(legacy).workflow)
    }

    @Test
    fun `task entity mapping preserves contract fields and display title`() {
        val task = AgentTestFixtures.task()
        val entity = AgentEntityMappers.toEntity(
            task,
            displayTitle = "Title",
            modelBindingJson = null,
            policyJson = null,
            budgetJson = null
        )
        assertEquals("Title", entity.displayTitle)
        assertEquals(task, AgentEntityMappers.toContract(entity))
    }

    @Test
    fun `run entity mapping round trips every persisted field`() {
        val run = AgentTestFixtures.run(
            state = AgentRunState.WAITING_APPROVAL,
            revision = 9L,
            lastSequence = 4L
        )
        val entity = AgentEntityMappers.toEntity(run)
        assertNull(entity.successorRunId)
        assertEquals(run, AgentEntityMappers.toContract(entity))
    }

    @Test
    fun `attempt step and invocation mapping round trip`() {
        val attempt = AgentTestFixtures.attempt()
        val attemptEntity = AgentEntityMappers.toEntity(attempt, stepId = "step-1", stopRequestedAt = 42L)
        assertEquals("step-1", attemptEntity.stepId)
        assertEquals(42L, attemptEntity.stopRequestedAt)
        assertEquals(attempt, AgentEntityMappers.toContract(attemptEntity))
        val step = AgentTestFixtures.step(status = AgentStepStatus.WAITING)
        assertEquals(step, AgentEntityMappers.toContract(AgentEntityMappers.toEntity(step)))
        val invocation = AgentTestFixtures.invocation(status = ToolInvocationStatus.RUNNING)
        val invocationEntity = AgentEntityMappers.toEntity(invocation)
        assertEquals("step-1", invocationEntity.runningStepKey)
        assertEquals(invocation, AgentEntityMappers.toContract(invocationEntity))
        val terminal = AgentTestFixtures.invocation(status = ToolInvocationStatus.SUCCEEDED)
        assertNull(AgentEntityMappers.toEntity(terminal).runningStepKey)
    }

    @Test
    fun `approval entity carries pending key only while pending`() {
        val pending = AgentTestFixtures.approval(status = AgentApprovalStatus.PENDING)
        assertEquals("invocation-1", AgentEntityMappers.toEntity(pending, "run-1").pendingInvocationKey)
        val consumed = pending.copy(status = AgentApprovalStatus.CONSUMED)
        assertNull(AgentEntityMappers.toEntity(consumed, "run-1").pendingInvocationKey)
        assertEquals(consumed, AgentEntityMappers.toContract(AgentEntityMappers.toEntity(consumed, "run-1")))
    }

    @Test
    fun `event and question mapping round trip`() {
        val event = AgentTestFixtures.event(sequence = 3L)
        assertEquals(event, AgentEntityMappers.toContract(AgentEntityMappers.toEntity(event)))
        val question = AgentTestFixtures.question(status = AgentQuestionStatus.ANSWERED)
        assertEquals(question, AgentEntityMappers.toContract(AgentEntityMappers.toEntity(question)))
    }

    @Test
    fun `artifact and commit mapping round trip`() {
        val stored = AgentTestFixtures.storedArtifact().copy(isLatest = true)
        val entity = AgentEntityMappers.toEntity(stored)
        assertEquals("artifacts/report.md", entity.latestKey)
        assertEquals(1, entity.isLatest)
        val roundTripped = AgentEntityMappers.toStoredArtifact(entity)
        assertEquals(stored.storageRef, roundTripped.storageRef)
        assertEquals(stored.version, roundTripped.version)
        assertEquals(stored.artifact, roundTripped.artifact)
        val committed = stored.copy(
            artifact = stored.artifact.copy(commitState = AgentArtifactCommitState.COMMITTED)
        )
        assertEquals(
            committed.artifact,
            AgentEntityMappers.toStoredArtifact(AgentEntityMappers.toEntity(committed)).artifact
        )
        val commit = AgentTestFixtures.commit(phase = AgentArtifactCommitPhase.RENAMED)
        assertEquals(
            commit,
            AgentEntityMappers.toContract(AgentEntityMappers.toEntity(commit, createdAtMillis = 1L))
        )
    }

    @Test
    fun `unknown persisted enum string is a typed corruption error`() {
        val entity = AgentEntityMappers.toEntity(AgentTestFixtures.run())
        try {
            AgentEntityMappers.toContract(entity.copy(state = "RUNNING_FAST"))
            fail("expected corruption error")
        } catch (error: AgentDataIntegrityException) {
            assertEquals(AgentDataErrorCodes.STORAGE_CORRUPTION, error.code)
            assertTrue(error.message!!.contains("RUNNING_FAST"))
        }
    }

    @Test
    fun `malformed json is a typed corruption error`() {
        try {
            AgentRecordJson.decodeBinding("{not-json")
            fail("expected corruption error")
        } catch (error: AgentDataIntegrityException) {
            assertEquals(AgentDataErrorCodes.STORAGE_CORRUPTION, error.code)
        }
        try {
            AgentRecordJson.decodePolicy("{\"networkPolicy\":\"LOCAL_ONLY\"}")
            fail("expected corruption error")
        } catch (error: AgentDataIntegrityException) {
            assertEquals(AgentDataErrorCodes.STORAGE_CORRUPTION, error.code)
        }
    }

    @Test
    fun `enum codec stores names and rejects ordinals`() {
        assertEquals("STOPPING", AgentRunState.STOPPING.encode())
        assertEquals("WAITING_APPROVAL", ToolInvocationStatus.WAITING_APPROVAL.encode())
        assertEquals(AgentDisplayStatus.PAUSING, AgentEnumCodec.decodeDisplayStatus("PAUSING"))
        try {
            AgentEnumCodec.decodeDisplayStatus("10")
            fail("ordinals must not decode")
        } catch (_: AgentDataIntegrityException) {
            // expected
        }
    }
}

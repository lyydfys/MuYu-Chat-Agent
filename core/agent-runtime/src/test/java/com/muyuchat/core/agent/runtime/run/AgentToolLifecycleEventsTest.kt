package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.PromptedV1Decoder
import com.muyuchat.core.agent.runtime.protocol.PromptedV1TurnDecoder
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import com.muyuchat.core.agent.runtime.testing.FakeArtifactVerifier
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.QueueModelPort
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 tool-lifecycle events at the runtime/journal boundary.
 *
 * AGENT_TOOLS_MEMORY_WORKFLOW_PLAN §4.1 requires bounded, sanitized
 * TOOL_PROPOSED / TOOL_APPROVAL_REQUESTED / TOOL_STARTED / TOOL_RESULT events so the approval sheet
 * and timeline can render real tool detail without leaking argument content.
 *
 * A deterministic hook records the frozen invocation before deciding, mirroring the app
 * AgentToolPermissionHook; the coordinator/journal are the production runtime.
 */
class AgentToolLifecycleEventsTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("p0-events")
    private val writeDescriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT, validator)

    @Test
    fun allowedToolCallEmitsProposedStartedAndResultInOrder() = runTest {
        val harness = harness { request, command ->
            AgentFrozenCommandDecision.ReadyToExecute(command)
        }
        val result = harness.loop.execute(
            harness.run,
            harness.task,
            null,
            AgentModelProgressSink { }
        )
        assertTrue(result is AgentLoopResult.Completed)

        val events = harness.journal.allEvents(harness.run.runId)
        val toolTypes = events.map { it.type }.filter { it.startsWith("tool.") && it != AgentEventTypes.TOOL_CATALOG_RESOLVED }
        assertEquals(
            listOf(
                AgentEventTypes.TOOL_PROPOSED,
                AgentEventTypes.TOOL_STARTED,
                AgentEventTypes.TOOL_RESULT
            ),
            toolTypes
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED.name, statusOf(events.single {
            it.type == AgentEventTypes.TOOL_RESULT
        }))
        assertEquals(1, harness.executor.executeCount)
        assertTrue("a single tool call emits a bounded number of events", toolTypes.size <= 5)
        assertBoundedAndRedacted(events)
    }

    @Test
    fun proposalRowExistsBeforeFreezeAndEndsSucceeded() = runTest {
        var statusAtFreeze: ToolInvocationStatus? = null
        val harness = harness(onFreeze = { statusAtFreeze = it }) { _, command ->
            AgentFrozenCommandDecision.ReadyToExecute(command)
        }
        harness.loop.execute(harness.run, harness.task, null, AgentModelProgressSink { })

        assertEquals(ToolInvocationStatus.PROPOSED, statusAtFreeze)
        val step = harness.journal.readRun(harness.run.runId)!!.steps.single()
        val invocation = harness.journal.readInvocation(step.invocationId!!)!!
        assertEquals(ToolInvocationStatus.SUCCEEDED, invocation.status)
    }

    @Test
    fun waitingApprovalEmitsProposedAndApprovalRequestedWithoutStartedOrResult() = runTest {
        val harness = harness { request, command ->
            AgentFrozenCommandDecision.WaitingApproval(
                command = command,
                approval = approval(command)
            )
        }
        val result = harness.loop.execute(
            harness.run,
            harness.task,
            null,
            AgentModelProgressSink { }
        )
        assertTrue(result is AgentLoopResult.WaitingApproval)
        assertEquals(0, harness.executor.executeCount)

        val events = harness.journal.allEvents(harness.run.runId)
        val toolTypes = events.map { it.type }.filter { it.startsWith("tool.") && it != AgentEventTypes.TOOL_CATALOG_RESOLVED }
        assertEquals(
            listOf(
                AgentEventTypes.TOOL_PROPOSED,
                AgentEventTypes.TOOL_APPROVAL_REQUESTED
            ),
            toolTypes
        )
        assertBoundedAndRedacted(events)
    }

    @Test
    fun deniedToolCallEmitsProposedAndDeniedResultButNeverStarted() = runTest {
        val harness = harness { _, _ ->
            AgentFrozenCommandDecision.Denied(
                errorCode = "PERMISSION_DENIED",
                safeMessage = "The tool invocation was denied by policy."
            )
        }
        harness.loop.execute(harness.run, harness.task, null, AgentModelProgressSink { })

        val events = harness.journal.allEvents(harness.run.runId)
        val toolTypes = events.map { it.type }.filter { it.startsWith("tool.") && it != AgentEventTypes.TOOL_CATALOG_RESOLVED }
        assertEquals(
            listOf(AgentEventTypes.TOOL_PROPOSED, AgentEventTypes.TOOL_RESULT),
            toolTypes
        )
        assertEquals(AgentToolResultStatus.DENIED.name, statusOf(events.single {
            it.type == AgentEventTypes.TOOL_RESULT
        }))
        assertEquals(0, harness.executor.executeCount)
        assertBoundedAndRedacted(events)
    }

    // ---------------------------------------------------------------------------------------
    // Harness.
    // ---------------------------------------------------------------------------------------

    private data class Harness(
        val journal: InMemoryAgentJournal,
        val task: AgentTask,
        val run: AgentRun,
        val executor: RecordingToolExecutor,
        val loop: AgentPromptedLoopCoordinator
    )

    private suspend fun harness(
        onFreeze: (ToolInvocationStatus?) -> Unit = {},
        decision: suspend (AgentFrozenCommandRequest, FrozenToolCommand) -> AgentFrozenCommandDecision
    ): Harness {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("p0-events-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING)
        journal.commit(
            CreateRunTransition(
                commandId = "p0-events-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        val executor = RecordingToolExecutor(writeDescriptor)
        val hook = RecordingFreezeHook(journal, task, onFreeze, decision)
        val modelPort = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(
                    AgentTestFixtures.toolEnvelope(
                        name = AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT,
                        argumentsJson = WRITE_ARGUMENTS
                    )
                ),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("done"))
            )
        )
        val loop = AgentPromptedLoopCoordinator(
            decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
            serializer = AgentPromptedTranscriptSerializer(codec),
            systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
            catalogProvider = AgentRunToolCatalogProvider { _, _ ->
                FakeToolCatalog(mapOf(writeDescriptor.toolKey to executor))
            },
            schemaValidator = validator,
            journal = journal,
            toolLedger = journal,
            modelPort = modelPort,
            budgetPolicy = AgentBudgetPolicy(clock),
            contextAssembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec),
            commandHook = hook,
            artifactVerifier = FakeArtifactVerifier(),
            ids = ids,
            clock = clock
        )
        return Harness(journal, task, run, executor, loop)
    }

    /** Mirrors the app hook order: freeze material is recorded before the decision is returned. */
    private class RecordingFreezeHook(
        private val ledger: AgentToolInvocationLedger,
        private val task: AgentTask,
        private val onFreeze: (ToolInvocationStatus?) -> Unit,
        private val decision: suspend (AgentFrozenCommandRequest, FrozenToolCommand) -> AgentFrozenCommandDecision
    ) : AgentFrozenCommandHook {
        override suspend fun freeze(request: AgentFrozenCommandRequest): AgentFrozenCommandDecision {
            onFreeze(ledger.readInvocation(request.invocationId)?.status)
            val resourcePath = if (request.descriptor.toolKey.startsWith("artifact.")) {
                "artifacts/report.md"
            } else {
                "inputs/brief.txt"
            }
            val command = FrozenToolCommand(
                invocationId = request.invocationId,
                runId = request.run.runId,
                stepId = request.stepId,
                callOrdinal = request.callOrdinal,
                toolKey = request.descriptor.toolKey,
                toolVersion = request.descriptor.version,
                schemaHash = request.descriptor.schemaHash,
                normalizedArgumentsJson = request.normalizedArgumentsJson,
                argumentsHash = request.argumentsHash,
                resourceFingerprint = RESOURCE_FINGERPRINT,
                runEpoch = request.run.epoch,
                policyVersion = request.run.policy.policyVersion,
                expiresAtMillis = 9_000_000L
            )
            val recorded = ledger.recordFrozen(
                AgentFrozenInvocation(
                    command = command,
                    descriptor = request.descriptor,
                    scope = AgentToolScope(
                        taskRoot = "${task.workspaceId}/tasks/${task.taskId}",
                        logicalPaths = listOf(resourcePath),
                        expectedOldHash = null,
                        networkDestinations = emptyList()
                    ),
                    recordedAtMillis = 1_100L
                )
            )
            if (recorded !is AgentToolInvocationRecord.Recorded) {
                return AgentFrozenCommandDecision.Failed(
                    errorCode = "INTERNAL_INVARIANT",
                    safeMessage = "The frozen tool command could not be recorded."
                )
            }
            return decision(request, command)
        }
    }

    private fun approval(command: FrozenToolCommand): AgentApproval = AgentApproval(
        approvalId = "approval-1",
        invocationId = command.invocationId,
        principal = "local-user",
        argumentsHash = command.argumentsHash,
        toolSchemaHash = command.schemaHash,
        resourceFingerprint = command.resourceFingerprint,
        policyVersion = command.policyVersion,
        runEpoch = command.runEpoch,
        expiresAtMillis = 5_000_000L,
        status = AgentApprovalStatus.PENDING,
        createdAtMillis = 1_000_000L
    )

    private fun statusOf(event: AgentEvent): String? {
        val summary = event.summary ?: return null
        val match = Regex("\"status\":\"([A-Z_]+)\"").find(summary) ?: return null
        return match.groupValues[1]
    }

    private fun assertBoundedAndRedacted(events: List<AgentEvent>) {
        events.forEach { event ->
            val summary = event.summary ?: return@forEach
            assertTrue(
                "event ${event.type} summary exceeds the 4 KiB contract bound",
                summary.toByteArray(Charsets.UTF_8).size <= 4 * 1024
            )
            assertTrue(
                "event ${event.type} leaked the tool argument content",
                !summary.contains(SECRET)
            )
        }
    }

    private companion object {
        const val RESOURCE_FINGERPRINT = "p0-events-resource"
        const val SECRET = "P0-SECRET-CONTENT-must-never-appear"
        val WRITE_ARGUMENTS =
            """{"path":"artifacts/report.md","content":"$SECRET","expectedOldHash":null}"""
    }
}

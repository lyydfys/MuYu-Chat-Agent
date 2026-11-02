package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDisplayStatus
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
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
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
import com.muyuchat.core.agent.runtime.testing.RecordingFrozenCommandHook
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 resume authorization closure at the loop boundary.
 *
 * AGENT_TOOLS_MEMORY_WORKFLOW_PLAN §4.1 requires the resume branch to execute only an
 * [AuthorizedToolCommand] whose command matches the durable invocation row, the current run
 * identity and the frozen catalog. These tests cover the post-P0 seam:
 * - an authorized command with a matching VALIDATED invocation executes exactly once;
 * - a forged arguments hash, foreign run, stale epoch or missing invocation is refused as
 *   NOT_EXECUTED and never reaches the executor;
 * - a typed NotExecuted decision pairs the observation and never executes.
 */
class AgentApprovalResumeClosureTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("p0-resume")
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    @Test
    fun authorizedResumeExecutesTheFrozenCommandExactlyOnce() = runTest {
        val harness = harness()
        val command = command(harness)
        recordValidatedInvocation(harness, command)
        val authorization = AuthorizedToolCommand(
            command = command,
            approvalId = "approval-1",
            basis = AgentToolAuthorizationBasis.APPROVED_ONCE
        )

        val result = harness.loop.execute(
            harness.run,
            harness.task,
            resume(AgentResumeToolDecision.Authorized(authorization, CALL_HASH)),
            AgentModelProgressSink { }
        )

        assertEquals(1, harness.executor.executeCount)
        assertEquals(command.invocationId, harness.executor.commands.single().invocationId)
        assertEquals(
            ToolInvocationStatus.SUCCEEDED,
            harness.journal.readInvocation(command.invocationId)!!.status
        )
        val observation = result.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .last()
        assertEquals(AgentToolResultStatus.SUCCEEDED.name, stringField(observation.result, "status"))
        assertTrue(
            harness.journal.allEvents(harness.run.runId).any {
                it.type == com.muyuchat.core.agent.contract.AgentEventTypes.TOOL_STARTED
            }
        )
    }

    @Test
    fun forgedArgumentsHashIsRefusedAsNotExecuted() = runTest {
        val harness = harness()
        val command = command(harness)
        recordValidatedInvocation(harness, command)
        val forged = command.copy(argumentsHash = "0".repeat(64))

        val result = executeAuthorized(harness, forged)

        assertEquals(0, harness.executor.executeCount)
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            harness.journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(
            AgentToolResultStatus.NOT_EXECUTED.name,
            stringField(lastToolResult(result).result, "status")
        )
    }

    @Test
    fun foreignRunIsRefusedAsNotExecuted() = runTest {
        val harness = harness()
        val command = command(harness)
        recordValidatedInvocation(harness, command)
        val forged = command.copy(runId = "foreign-run")

        val result = executeAuthorized(harness, forged)

        assertEquals(0, harness.executor.executeCount)
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            harness.journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(
            AgentToolResultStatus.NOT_EXECUTED.name,
            stringField(lastToolResult(result).result, "status")
        )
    }

    @Test
    fun staleRunEpochIsRefusedAsNotExecuted() = runTest {
        val harness = harness()
        val command = command(harness)
        recordValidatedInvocation(harness, command)
        val forged = command.copy(runEpoch = 99L)

        val result = executeAuthorized(harness, forged)

        assertEquals(0, harness.executor.executeCount)
        assertEquals(
            AgentToolResultStatus.NOT_EXECUTED.name,
            stringField(lastToolResult(result).result, "status")
        )
    }

    @Test
    fun missingInvocationRowIsRefusedAsNotExecuted() = runTest {
        val harness = harness()
        val command = command(harness)

        val result = executeAuthorized(harness, command)

        assertEquals(0, harness.executor.executeCount)
        assertEquals(
            AgentToolResultStatus.NOT_EXECUTED.name,
            stringField(lastToolResult(result).result, "status")
        )
        assertNull(harness.journal.readInvocation(command.invocationId))
    }

    @Test
    fun typedNotExecutedDecisionNeverCallsTheExecutor() = runTest {
        val harness = harness()
        val result = harness.loop.execute(
            harness.run,
            harness.task,
            resume(
                AgentResumeToolDecision.NotExecuted(
                    invocationId = "invocation-1",
                    callHash = CALL_HASH,
                    toolKey = descriptor.toolKey,
                    status = AgentToolResultStatus.DENIED,
                    safeMessage = "The user denied this tool invocation.",
                    errorCode = "PERMISSION_DENIED"
                )
            ),
            AgentModelProgressSink { }
        )

        assertEquals(0, harness.executor.executeCount)
        assertEquals(
            AgentToolResultStatus.DENIED.name,
            stringField(lastToolResult(result).result, "status")
        )
    }

    // ---------------------------------------------------------------------------------------
    // Harness and durable invocation setup.
    // ---------------------------------------------------------------------------------------

    private data class Harness(
        val journal: InMemoryAgentJournal,
        val task: AgentTask,
        val run: AgentRun,
        val executor: RecordingToolExecutor,
        val loop: AgentPromptedLoopCoordinator
    )

    private suspend fun harness(): Harness {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("p0-resume-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING)
        journal.commit(
            CreateRunTransition(
                commandId = "p0-resume-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val catalog = FakeToolCatalog(mapOf(descriptor.toolKey to executor))
        val modelPort = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("after resume")))
        )
        val loop = AgentPromptedLoopCoordinator(
            decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
            serializer = AgentPromptedTranscriptSerializer(codec),
            systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
            catalogProvider = AgentRunToolCatalogProvider { _, _ -> catalog },
            schemaValidator = validator,
            journal = journal,
            toolLedger = journal,
            modelPort = modelPort,
            budgetPolicy = AgentBudgetPolicy(clock),
            contextAssembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec),
            commandHook = RecordingFrozenCommandHook(),
            artifactVerifier = FakeArtifactVerifier(),
            ids = ids,
            clock = clock
        )
        return Harness(journal, task, run, executor, loop)
    }

    private fun command(
        harness: Harness,
        invocationId: String = "invocation-1"
    ): FrozenToolCommand = FrozenToolCommand(
        invocationId = invocationId,
        runId = harness.run.runId,
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
        argumentsHash = "a".repeat(64),
        resourceFingerprint = RESOURCE_FINGERPRINT,
        runEpoch = harness.run.epoch,
        policyVersion = harness.run.policy.policyVersion,
        expiresAtMillis = 9_000_000L
    )

    private suspend fun recordValidatedInvocation(harness: Harness, command: FrozenToolCommand) {
        val proposal = AgentToolProposal(
            invocationId = command.invocationId,
            runId = harness.run.runId,
            taskId = harness.task.taskId,
            stepId = command.stepId,
            stepIndex = 0,
            callOrdinal = command.callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = command.argumentsHash,
            proposedAtMillis = 1_000L
        )
        val recorded = harness.journal.recordProposed(proposal)
        assertTrue("proposal must be recorded, got $recorded", recorded is AgentToolInvocationRecord.Recorded)
        val frozen = harness.journal.recordFrozen(
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = AgentToolScope(
                    taskRoot = "${harness.task.workspaceId}/tasks/${harness.task.taskId}",
                    logicalPaths = listOf("inputs/brief.txt"),
                    expectedOldHash = null,
                    networkDestinations = emptyList()
                ),
                recordedAtMillis = 1_100L
            )
        )
        assertTrue("frozen material must be recorded, got $frozen", frozen is AgentToolInvocationRecord.Recorded)
    }

    private suspend fun executeAuthorized(
        harness: Harness,
        command: FrozenToolCommand
    ): AgentLoopResult {
        val authorization = AuthorizedToolCommand(
            command = command,
            approvalId = "approval-1",
            basis = AgentToolAuthorizationBasis.APPROVED_ONCE
        )
        return harness.loop.execute(
            harness.run,
            harness.task,
            resume(AgentResumeToolDecision.Authorized(authorization, CALL_HASH)),
            AgentModelProgressSink { }
        )
    }

    private fun resume(decision: AgentResumeToolDecision): AgentLoopResume = AgentLoopResume(
        transcript = AgentLoopTranscript(
            entries = listOf(AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope())),
            nextStepIndex = 1,
            repairsUsed = 0
        ),
        toolDecision = decision
    )

    private fun lastToolResult(result: AgentLoopResult): AgentTranscriptEntry.ToolResult =
        result.transcript.entries.filterIsInstance<AgentTranscriptEntry.ToolResult>().last()

    private fun stringField(value: AgentJsonValue, field: String): String? =
        ((value as? AgentJsonValue.Obj)?.fields?.toMap()?.get(field) as? AgentJsonValue.Str)?.value

    private companion object {
        const val CALL_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val RESOURCE_FINGERPRINT = "p0-resource-fingerprint"
    }
}

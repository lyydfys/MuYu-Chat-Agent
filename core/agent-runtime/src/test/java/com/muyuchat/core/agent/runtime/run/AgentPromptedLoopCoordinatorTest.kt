package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.protocol.AgentNativeTranscriptSerializer
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
import com.muyuchat.core.agent.runtime.testing.SuspendingModelPort
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** K03 loop-coordinator tests (T01/T05/T07/T09/P09/P10/P12 at memory level). */
class AgentPromptedLoopCoordinatorTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("loop")
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    @Test
    fun validFinalCompletesWithTwoMessageTranscriptAndOneTerminallessAttempt() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("完成 ✅"))))
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals("完成 ✅", result.finalText)
        assertEquals(1, result.transcript.entries.size)
        val request = port.requests.single()
        assertEquals(
            listOf(AgentTranscriptRole.SYSTEM, AgentTranscriptRole.USER),
            request.messages.map { it.role }
        )
        assertEquals(2_048, request.maxOutputTokens)
        assertEquals(0, executor.executeCount)
        val events = journal.allEvents(run.runId)
        assertEquals(
            listOf(
                AgentEventTypes.TOOL_CATALOG_RESOLVED,
                AgentEventTypes.MODEL_ATTEMPT_STARTED,
                AgentEventTypes.MODEL_ATTEMPT_SETTLED
            ),
            events.map { it.type }
        )
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(4L, result.run.revision)
    }

    @Test
    fun promptOnlyFrameHasNoToolsAndLeavesTheParentCatalogUntouched() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("frame answer")))
        )
        val executor = RecordingToolExecutor(descriptor)
        val catalog = FakeToolCatalog(mapOf(descriptor.toolKey to executor))
        val provider = object : AgentRunToolCatalogProvider {
            var releaseCount = 0
            override suspend fun catalogFor(
                run: AgentRun,
                task: AgentTask
            ): com.muyuchat.core.agent.contract.AgentToolCatalog = catalog

            override suspend fun release(run: AgentRun) {
                releaseCount += 1
            }
        }
        val result = coordinator(journal, port, catalog, provider = provider)
            .executePromptOnlyFrame(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals("frame answer", (result as AgentLoopResult.Completed).finalText)
        assertTrue("a prompt-only frame must declare no tools", port.requests.single().tools.isEmpty())
        assertEquals(0, executor.executeCount)
        assertEquals("the frame must not release the parent's catalog", 0, provider.releaseCount)
        val events = journal.allEvents(run.runId).map { it.type }
        assertTrue(AgentEventTypes.TOOL_CATALOG_RESOLVED !in events)
        assertTrue(AgentEventTypes.TOOL_CATALOG_RESTORED !in events)
    }

    @Test
    fun toolCallExecutesOnceThenFinalAndPairsTheResult() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("已整理完成。"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals(1, executor.executeCount)
        assertEquals(3, result.transcript.entries.size)
        assertTrue(result.transcript.entries[0] is AgentTranscriptEntry.AssistantEnvelope)
        val toolResult = result.transcript.entries[1] as AgentTranscriptEntry.ToolResult
        assertEquals(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, toolResult.toolKey)
        assertEquals(AgentToolResultStatus.SUCCEEDED.name, stringField(toolResult.result, "status"))
        assertEquals(64, toolResult.callHash.length)
        val command = executor.commands.single()
        assertTrue(command.normalizedArgumentsJson.contains("\"offset\":0"))
        assertEquals(64, command.argumentsHash.length)
        assertEquals(2, port.inferCount)
        assertEquals(
            listOf(
                AgentTranscriptRole.SYSTEM,
                AgentTranscriptRole.USER,
                AgentTranscriptRole.ASSISTANT,
                AgentTranscriptRole.USER
            ),
            port.requests[1].messages.map { it.role }
        )
        assertTrue(port.requests[1].messages.last().content.contains("\"record\":\"tool_result\""))
    }

    @Test
    fun aNativeTurnIsExecutedWithoutPromptedDecoding() = runTest {
        val (journal, task, run) = setup()
        val nativeTurn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(
                AgentTurnToolCall(
                    callId = "call-1",
                    name = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
                    argumentsJson = """{"path":"inputs/brief.txt"}"""
                )
            ),
            terminalProof = "responses:completed"
        )
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = nativeTurn),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("完成"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val nativeRun = run.copy(
            modelBinding = run.modelBinding.copy(
                protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
            )
        )
        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(descriptor.toolKey to executor)),
            hook = hook
        ).execute(nativeRun, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(1, executor.executeCount)
        assertEquals("call-1", hook.requests.single().providerCallId)
        val entries = (result as AgentLoopResult.Completed).transcript.entries
        assertEquals(3, entries.size)
        val toolResult = entries[1] as AgentTranscriptEntry.ToolResult
        // N05d: a native result is recorded as the reversible transcript v2 output record.
        assertEquals(AgentToolResultStatus.SUCCEEDED.name, nativePayloadField(toolResult, "status"))
        assertEquals(2, port.inferCount)
        // N05c send side: the next request carries the provider call and its paired output.
        val secondRequest = port.requests[1].messages
        val call = secondRequest.single {
            it.kind == com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_CALL
        }
        assertEquals("call-1", call.callId)
        assertEquals(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, call.toolName)
        val output = secondRequest.single {
            it.kind == com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_OUTPUT
        }
        assertEquals("call-1", output.callId)
        assertTrue(output.content.contains("SUCCEEDED"))
    }

    @Test
    fun aNativeMalformedOrUnknownCallNeverExecutesAndNeverReportsSuccess() = runTest {
        val cases = listOf(
            Triple("call-bad-args", AgentV1ToolSpecs.WORKSPACE_READ_TEXT, "{oops"),
            Triple("call-unknown", "missing.tool", "{}")
        )
        for ((callId, name, arguments) in cases) {
            val (journal, task, run) = setup()
            val nativeTurn = AgentModelTurn(
                protocol = AgentTurnProtocol.NATIVE,
                finish = AgentTurnFinish.TOOL_CALL,
                calls = listOf(AgentTurnToolCall(callId = callId, name = name, argumentsJson = arguments)),
                terminalProof = "responses:completed"
            )
            val port = QueueModelPort(
                mutableListOf(
                    AgentModelResult.Success(visibleText = "", turn = nativeTurn),
                    AgentModelResult.Success(AgentTestFixtures.finalEnvelope("done"))
                )
            )
            val executor = RecordingToolExecutor(descriptor)
            val nativeRun = run.copy(
                modelBinding = run.modelBinding.copy(
                    protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
                )
            )
            val result = coordinator(
                journal,
                port,
                FakeToolCatalog(mapOf(descriptor.toolKey to executor))
            ).execute(nativeRun, task, null, AgentModelProgressSink { })

            assertEquals("$name must never execute", 0, executor.executeCount)
            if (result is AgentLoopResult.Completed) {
                val output = port.requests[1].messages.single {
                    it.kind == com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_OUTPUT &&
                        it.callId == callId
                }
                assertTrue(
                    "$name must not fabricate a successful paired output",
                    !output.content.contains(AgentToolResultStatus.SUCCEEDED.name)
                )
            } else {
                assertTrue(
                    "a rejected native call must end in a typed non-success, got $result",
                    result is AgentLoopResult.NeedsReview ||
                        result is AgentLoopResult.Failed ||
                        result is AgentLoopResult.Paused
                )
            }
        }
    }

    @Test
    fun aNativeBatchOfReadOnlyCallsExecutesEachCallWithItsOwnPairing() = runTest {
        val (journal, task, run) = setup()
        val nativeRun = run.copy(
            modelBinding = run.modelBinding.copy(
                protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
            )
        )
        val batchTurn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(
                AgentTurnToolCall("call-1", AgentV1ToolSpecs.WORKSPACE_READ_TEXT, """{"path":"inputs/a"}"""),
                AgentTurnToolCall("call-2", AgentV1ToolSpecs.WORKSPACE_READ_TEXT, """{"path":"inputs/b"}""")
            )
        )
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("批次完成"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(nativeRun, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals("both read-only calls must execute", 2, executor.executeCount)
        val entries = (result as AgentLoopResult.Completed).transcript.entries
        assertEquals(5, entries.size)
        assertEquals("call-1", (entries[0] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-1", (entries[1] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals("call-2", (entries[2] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-2", (entries[3] as AgentTranscriptEntry.ToolResult).providerCallId)
        val second = port.requests[1].messages
        assertEquals(
            setOf("call-1", "call-2"),
            second.filter {
                it.kind == com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_CALL
            }.map { it.callId }.toSet()
        )
        assertEquals(
            setOf("call-1", "call-2"),
            second.filter {
                it.kind == com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_OUTPUT
            }.map { it.callId }.toSet()
        )
    }

    @Test
    fun aNativeBatchWithAWriteCallIsRefusedWithoutExecutingAnything() = runTest {
        val (journal, task, run) = setup()
        val nativeRun = run.copy(
            modelBinding = run.modelBinding.copy(
                protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
            )
        )
        val writeDescriptor = AgentV1ToolSpecs.descriptor(
            AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT,
            validator
        )
        val batchTurn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(
                AgentTurnToolCall("call-1", AgentV1ToolSpecs.WORKSPACE_READ_TEXT, """{"path":"inputs/a"}"""),
                AgentTurnToolCall(
                    "call-2",
                    AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT,
                    """{"path":"artifacts/out.txt","content":"x"}"""
                )
            )
        )
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("批次被拒绝"))
            )
        )
        val readExecutor = RecordingToolExecutor(descriptor)
        val writeExecutor = RecordingToolExecutor(writeDescriptor)
        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(
                mapOf(
                    descriptor.toolKey to readExecutor,
                    writeDescriptor.toolKey to writeExecutor
                )
            )
        ).execute(nativeRun, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals("a write call must prevent the whole batch", 0, readExecutor.executeCount)
        assertEquals(0, writeExecutor.executeCount)
        assertTrue(
            journal.allEvents(run.runId).any { it.type == AgentEventTypes.PROTOCOL_ERROR }
        )
    }

    @Test
    fun anOversizedNativeBatchIsRefusedWithoutExecutingAnything() = runTest {
        val (journal, task, run) = setup()
        val nativeRun = run.copy(
            modelBinding = run.modelBinding.copy(
                protocolMode = com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
            )
        )
        val calls = (1..5).map { index ->
            AgentTurnToolCall(
                "call-$index",
                AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
                """{"path":"inputs/$index"}"""
            )
        }
        val batchTurn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = calls
        )
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("批次过大"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(nativeRun, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(0, executor.executeCount)
        assertTrue(journal.allEvents(run.runId).any { it.type == AgentEventTypes.PROTOCOL_ERROR })
    }

    @Test
    fun askUserStopsWithoutExecutingAnything() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(mutableListOf(AgentModelResult.Success(AgentTestFixtures.askUserEnvelope())))
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.WaitingUser)
        result as AgentLoopResult.WaitingUser
        assertEquals("报告需要面向哪类读者？", result.questionText)
        assertEquals(listOf("开发者", "管理者"), result.options)
        assertEquals(1, result.transcript.entries.size)
        assertEquals(0, executor.executeCount)
    }

    @Test
    fun oneProtocolRepairIsUsedBeforePausing() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success("not json at all"),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("repaired"))
            )
        )
        val result = coordinator(journal, port, FakeToolCatalog(emptyMap()))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals("repaired", result.finalText)
        assertEquals(1, result.transcript.repairsUsed)
        assertTrue(result.transcript.entries.first() is AgentTranscriptEntry.ProtocolError)
        assertEquals(2, port.inferCount)
        assertTrue(journal.allEvents(run.runId).any { it.type == AgentEventTypes.PROTOCOL_ERROR })
    }

    @Test
    fun secondProtocolFailureFinishesWithVisibleTextWithoutExecutingTools() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success("first bad"),
                AgentModelResult.Success("second bad")
            )
        )
        val result = coordinator(journal, port, FakeToolCatalog(emptyMap()))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals("second bad", result.finalText)
        assertEquals(1, result.transcript.repairsUsed)
        assertEquals(2, port.inferCount)
        assertTrue(result.transcript.entries.first() is AgentTranscriptEntry.ProtocolError)
    }

    @Test
    fun deniedFrozenCommandProducesPairedDeniedResultAndNoExecution() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("denied path"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val hook = RecordingFrozenCommandHook {
            AgentFrozenCommandDecision.Denied(
                AgentErrorCodes.PERMISSION_DENIED,
                "The user denied this tool invocation."
            )
        }
        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(descriptor.toolKey to executor)),
            hook = hook
        ).execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals(0, executor.executeCount)
        val toolResult = result.transcript.entries[1] as AgentTranscriptEntry.ToolResult
        assertEquals(AgentToolResultStatus.DENIED.name, stringField(toolResult.result, "status"))
        assertEquals(AgentErrorCodes.PERMISSION_DENIED, stringField(toolResult.result, "errorCode"))
    }

    @Test
    fun multiCallEnvelopeIsRejectedAndNeverExecutesAnyCall() = runTest {
        val (journal, task, run) = setup()
        val multiCall = AgentTestFixtures.toolEnvelope()
            .replace(",\"call\":", ",\"calls\":[{}],\"call\":")
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(multiCall),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("no execution"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals(0, executor.executeCount)
        assertTrue(result.transcript.entries.first() is AgentTranscriptEntry.ProtocolError)
        assertEquals(1, result.transcript.repairsUsed)
    }

    @Test
    fun finalArtifactIdsMustBeRegisteredBeforeCompletion() = runTest {
        val (journal, task, run) = setup()
        val verifier = FakeArtifactVerifier(registered = setOf("artifact-1"))
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(
                    AgentTestFixtures.finalEnvelope("done", listOf("artifact-1"))
                )
            )
        )
        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(emptyMap()),
            verifier = verifier
        ).execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(listOf("artifact-1"), (result as AgentLoopResult.Completed).artifactIds)
    }

    @Test
    fun unknownFinalArtifactPausesAfterTheSingleRepair() = runTest {
        val (journal, task, run) = setup()
        val verifier = FakeArtifactVerifier(registered = emptySet())
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("x", listOf("artifact-1"))),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("x", listOf("artifact-1")))
            )
        )
        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(emptyMap()),
            verifier = verifier
        ).execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Paused)
        result as AgentLoopResult.Paused
        assertEquals(AgentPauseReason.MODEL_PROTOCOL_ERROR, result.reason)
        assertEquals(2, port.inferCount)
        assertEquals(1, result.transcript.repairsUsed)
    }

    @Test
    fun contextOverflowPausesBeforeAnyModelCall() = runTest {
        val (journal, task, run) = setup(contextWindowTokens = 30)
        val port = QueueModelPort(mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope())))
        val result = coordinator(journal, port, FakeToolCatalog(emptyMap()))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Paused)
        result as AgentLoopResult.Paused
        assertEquals(AgentPauseReason.CONTEXT_LIMIT, result.reason)
        assertEquals(AgentErrorCodes.MODEL_CONTEXT_LIMIT, result.error?.code)
        assertEquals(0, port.inferCount)
    }

    @Test
    fun attemptBudgetExhaustionPausesWithBudgetLimitReason() = runTest {
        val (journal, task, run) = setup(budget = AgentBudget(modelAttempts = 1))
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success("bad"),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope())
            )
        )
        val result = coordinator(journal, port, FakeToolCatalog(emptyMap()))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Paused)
        result as AgentLoopResult.Paused
        assertEquals(AgentPauseReason.BUDGET_LIMIT, result.reason)
        assertEquals(AgentErrorCodes.BUDGET_STEP_LIMIT, result.error?.code)
        assertEquals(1, port.inferCount)
    }

    @Test
    fun invalidToolArgumentsArePairedAsNotExecutedWithoutCallingTheExecutor() = runTest {
        val (journal, task, run) = setup()
        val badArguments = AgentTestFixtures.toolEnvelope(
            argumentsJson = """{"limit":10}"""
        )
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(badArguments),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("after invalid args"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals(0, executor.executeCount)
        val toolResult = result.transcript.entries[1] as AgentTranscriptEntry.ToolResult
        assertEquals(AgentToolResultStatus.NOT_EXECUTED.name, stringField(toolResult.result, "status"))
        assertEquals(AgentErrorCodes.TOOL_INPUT_INVALID, stringField(toolResult.result, "errorCode"))
    }

    @Test
    fun anUnknownToolResultParksForReviewWithoutAnotherModelAttempt() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("must not be requested"))
            )
        )
        val crashing = object : AgentToolExecutor {
            override val descriptor = this@AgentPromptedLoopCoordinatorTest.descriptor

            override suspend fun execute(
                command: FrozenToolCommand,
                context: AgentToolContext
            ): AgentToolResult = error("simulated executor crash")
        }

        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to crashing)))
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue("expected NEEDS_REVIEW, got $result", result is AgentLoopResult.NeedsReview)
        result as AgentLoopResult.NeedsReview
        assertEquals("the model must not get a retry turn", 1, port.requests.size)
        assertEquals(AgentErrorCodes.TOOL_RESULT_UNKNOWN, result.error.code)
        assertTrue("uncertain effects must stay visible", result.error.possibleSideEffects)
        val invocationId = requireNotNull(journal.readRun(run.runId)!!.steps.last().invocationId)
        assertEquals(
            ToolInvocationStatus.UNKNOWN,
            journal.readInvocation(invocationId)!!.status
        )
        val toolResult = result.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .single()
        assertEquals(AgentToolResultStatus.UNKNOWN.name, stringField(toolResult.result, "status"))
    }

    @Test
    fun modelFailureAndCancellationMapToDistinctLoopOutcomes() = runTest {
        val (journal, task, run) = setup()
        val failure = coordinator(
            journal,
            QueueModelPort(mutableListOf(AgentRuntimeFixtures.modelFailure())),
            FakeToolCatalog(emptyMap())
        ).execute(run, task, null, AgentModelProgressSink { })
        assertTrue(failure is AgentLoopResult.Paused)
        assertEquals(AgentPauseReason.MODEL_PROTOCOL_ERROR, (failure as AgentLoopResult.Paused).reason)

        val (journal2, task2, run2) = setup()
        val cancelled = coordinator(
            journal2,
            QueueModelPort(mutableListOf(AgentModelResult.Cancelled())),
            FakeToolCatalog(emptyMap())
        ).execute(run2, task2, null, AgentModelProgressSink { })
        assertTrue(cancelled is AgentLoopResult.Cancelled)
    }

    @Test
    fun cancellationPropagatesAndClearsTheAttemptRegistry() = runTest {
        val (journal, task, run) = setup()
        val port = SuspendingModelPort()
        val registry = AgentActiveAttemptRegistry()
        val loop = coordinator(
            journal,
            port,
            FakeToolCatalog(emptyMap()),
            attemptRegistry = registry
        )
        val deferred = async { loop.execute(run, task, null, AgentModelProgressSink { }) }
        port.started.await()
        deferred.cancel()
        var cancellation: Throwable? = null
        try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }
        assertTrue(cancellation is CancellationException)
        assertEquals(1, port.cancellationCount)
        assertNull(registry.active(run.runId))
    }

    @Test
    fun aResumedExecutionWithAChangedCatalogFailsClosed() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("first")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("second"))
            )
        )
        val first = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(descriptor.toolKey to RecordingToolExecutor(descriptor)))
        ).execute(run, task, null, AgentModelProgressSink { })
        assertTrue(first is AgentLoopResult.Completed)
        first as AgentLoopResult.Completed
        assertEquals(1, port.requests.size)
        assertTrue(
            journal.allEvents(run.runId).any { it.type == AgentEventTypes.TOOL_CATALOG_RESOLVED }
        )

        val versionBumped = descriptor.copy(version = "1.0.1")
        val changed = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(versionBumped.toolKey to RecordingToolExecutor(versionBumped)))
        ).execute(first.run, task, null, AgentModelProgressSink { })

        assertTrue(changed is AgentLoopResult.Failed)
        changed as AgentLoopResult.Failed
        assertEquals(AgentRunToolCatalogErrorCodes.TOOL_CATALOG_DRIFT, changed.error.code)
        assertEquals("a drifted catalog must not reach the model", 1, port.requests.size)
    }

    @Test
    fun aDriftedCatalogIsRestoredFromTheRecordedSurface() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("first")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("second"))
            )
        )
        val original = FakeToolCatalog(
            mapOf(descriptor.toolKey to RecordingToolExecutor(descriptor))
        )
        val first = coordinator(journal, port, original)
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(first is AgentLoopResult.Completed)
        first as AgentLoopResult.Completed

        val recorded = AgentRecordedCatalog(
            catalogHash = AgentToolCatalogHashing.hash(original),
            tools = original.snapshot().map {
                RecordedToolIdentity(it.toolKey, it.version, it.schemaHash)
            }.sortedBy { it.toolKey }
        )
        val driftedDescriptor = descriptor.copy(version = "9.9.9")
        val drifted = FakeToolCatalog(
            mapOf(driftedDescriptor.toolKey to RecordingToolExecutor(driftedDescriptor))
        )
        val provider = object : AgentRunToolCatalogProvider {
            override suspend fun catalogFor(
                run: AgentRun,
                task: AgentTask
            ): AgentToolCatalog = drifted

            override suspend fun catalogForRecorded(
                run: AgentRun,
                task: AgentTask,
                recorded: AgentRecordedCatalog
            ): AgentToolCatalog? = AgentToolCatalogFiltering.filter(original, recorded)
        }

        val second = coordinator(journal, port, original, provider = provider)
            .execute(first.run, task, null, AgentModelProgressSink { })

        assertTrue("expected a restored continuation, got $second", second is AgentLoopResult.Completed)
        assertTrue(
            journal.allEvents(run.runId).any { it.type == AgentEventTypes.TOOL_CATALOG_RESTORED }
        )
    }

    @Test
    fun aResumedExecutionWithTheSameCatalogContinues() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("first")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("second"))
            )
        )
        val catalog = FakeToolCatalog(mapOf(descriptor.toolKey to RecordingToolExecutor(descriptor)))
        val first = coordinator(journal, port, catalog)
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(first is AgentLoopResult.Completed)
        first as AgentLoopResult.Completed

        val second = coordinator(journal, port, catalog)
            .execute(first.run, task, null, AgentModelProgressSink { })

        assertTrue(second is AgentLoopResult.Completed)
        assertEquals(2, port.requests.size)
        assertEquals(
            1,
            journal.allEvents(run.runId).count { it.type == AgentEventTypes.TOOL_CATALOG_RESOLVED }
        )
    }

    private suspend fun setup(
        contextWindowTokens: Int = 8_192,
        budget: AgentBudget = AgentBudget()
    ): Triple<InMemoryAgentJournal, AgentTask, AgentRun> {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("setup-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING, budget = budget)
            .copy(modelBinding = AgentRuntimeFixtures.binding(contextWindowTokens))
        journal.commit(
            CreateRunTransition(
                "setup-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return Triple(journal, task, run)
    }

    @Test
    fun sameRootFrameRejectsAWriteCatalogBeforeAnyModelRequest() = runTest {
        val (journal, task, run) = setup()
        val writeDescriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT, validator)
        val executor = RecordingToolExecutor(writeDescriptor)
        val catalog = FakeToolCatalog(mapOf(writeDescriptor.toolKey to executor))
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("must not run")))
        )
        val provider = object : AgentRunToolCatalogProvider {
            var releaseCount = 0

            override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog = catalog

            override suspend fun release(run: AgentRun) {
                releaseCount += 1
            }
        }

        val result = coordinator(journal, port, catalog, provider = provider)
            .executeSameRootFrame(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Failed)
        assertEquals(
            "WF_FRAME_WRITE_TOOL_UNSUPPORTED",
            (result as AgentLoopResult.Failed).error.code
        )
        assertTrue("no model request may be sent", port.requests.isEmpty())
        assertEquals("no tool may execute", 0, executor.executeCount)
        assertEquals("the parent catalog is not released here", 0, provider.releaseCount)
        assertTrue(
            "no model attempt may be journaled",
            journal.allEvents(run.runId).none { it.type == AgentEventTypes.MODEL_ATTEMPT_STARTED }
        )
    }

    @Test
    fun sameRootFrameWithAnEmptyCatalogCompletesWithoutReleasingTheParentCatalog() = runTest {
        val (journal, task, run) = setup()
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("frame answer")))
        )
        val catalog = FakeToolCatalog(emptyMap())
        val provider = object : AgentRunToolCatalogProvider {
            var releaseCount = 0

            override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog = catalog

            override suspend fun release(run: AgentRun) {
                releaseCount += 1
            }
        }

        val result = coordinator(journal, port, catalog, provider = provider)
            .executeSameRootFrame(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals("frame answer", (result as AgentLoopResult.Completed).finalText)
        assertEquals("the frame still runs the one model loop", 1, port.requests.size)
        assertEquals("the parent catalog is not released here", 0, provider.releaseCount)
    }

    private fun coordinator(
        journal: InMemoryAgentJournal,
        modelPort: com.muyuchat.core.agent.contract.AgentModelPort,
        catalog: FakeToolCatalog,
        hook: RecordingFrozenCommandHook = RecordingFrozenCommandHook(ledger = journal),
        verifier: FakeArtifactVerifier = FakeArtifactVerifier(),
        attemptRegistry: AgentActiveAttemptRegistry = AgentActiveAttemptRegistry(),
        provider: AgentRunToolCatalogProvider? = null
    ): AgentPromptedLoopCoordinator {
        return AgentPromptedLoopCoordinator(
            decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
            serializer = AgentPromptedTranscriptSerializer(codec),
            systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
            catalogProvider = provider ?: AgentRunToolCatalogProvider { _, _ -> catalog },
            schemaValidator = validator,
            journal = journal,
            toolLedger = journal,
            modelPort = modelPort,
            budgetPolicy = AgentBudgetPolicy(clock),
            contextAssembler = AgentContextAssembler(
                AgentPromptedTranscriptSerializer(codec),
                nativeSerializer = AgentNativeTranscriptSerializer(codec),
                codec = codec
            ),
            commandHook = hook,
            artifactVerifier = verifier,
            ids = ids,
            clock = clock,
            attemptRegistry = attemptRegistry
        )
    }

    /** Reads a field of the tool observation stored inside a transcript v2 output record. */
    private fun nativePayloadField(entry: AgentTranscriptEntry.ToolResult, field: String): String? {
        val record = entry.result as? AgentJsonValue.Obj ?: return null
        val payload = (record.fields.toMap()["result"] as? AgentJsonValue.Obj) ?: return null
        return (payload.fields.toMap()[field] as? AgentJsonValue.Str)?.value
    }

    private fun stringField(value: AgentJsonValue, field: String): String? =
        ((value as? AgentJsonValue.Obj)?.fields?.toMap()?.get(field) as? AgentJsonValue.Str)?.value

    @Test
    fun aSafeRetryTerminalFailureIsRetriedWithinBudget() = runTest {
        val (journal, task, run) = setup()
        val failure = AgentModelResult.Failure(
            com.muyuchat.core.agent.contract.AgentError(
                code = AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                category = com.muyuchat.core.agent.contract.AgentErrorCategory.MODEL,
                retryability = com.muyuchat.core.agent.contract.AgentRetryability.SAFE_RETRY,
                safeMessage = "provider reset before terminal",
                correlationId = "test"
            )
        )
        val port = QueueModelPort(
            mutableListOf(
                failure,
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("重试后完成"))
            )
        )
        val executor = RecordingToolExecutor(descriptor)
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue("unexpected: $result", result is AgentLoopResult.Completed)
        assertEquals("重试后完成", (result as AgentLoopResult.Completed).finalText)
        assertEquals(2, port.inferCount)
        assertEquals(0, executor.executeCount)
        val events = journal.allEvents(run.runId)
        assertEquals(
            2,
            events.count { it.type == AgentEventTypes.MODEL_ATTEMPT_STARTED }
        )
        assertEquals(
            2,
            events.count { it.type == AgentEventTypes.MODEL_ATTEMPT_SETTLED }
        )
    }

}

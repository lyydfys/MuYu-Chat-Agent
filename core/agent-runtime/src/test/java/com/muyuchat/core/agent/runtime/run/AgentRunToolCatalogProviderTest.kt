package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
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
import com.muyuchat.core.agent.runtime.testing.SuspendingModelPort
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-run tool catalog + decoder provider tests.
 *
 * The coordinator must resolve the catalog exactly once per execute, build the strict decoder from
 * that snapshot, and use the same snapshot for model prompts, tool lookup and frozen-command
 * execution. Provider failures become a typed Failed result with zero model/tool work.
 */
class AgentRunToolCatalogProviderTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val readDescriptor = AgentV1ToolSpecs.descriptor(
        AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        validator
    )
    private val searchDescriptor = AgentV1ToolSpecs.descriptor(
        AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
        validator
    )

    @Test
    fun eachRunUsesItsOwnCatalogForDecoderAndFrozenCommand() = runTest {
        val journal = InMemoryAgentJournal()
        val (taskA, runA) = createTaskAndRun(journal, "a")
        val (taskB, runB) = createTaskAndRun(journal, "b")
        val readExecutor = RecordingToolExecutor(readDescriptor)
        val searchExecutor = RecordingToolExecutor(searchDescriptor)
        val catalogA = FakeToolCatalog(mapOf(readDescriptor.toolKey to readExecutor))
        val catalogB = FakeToolCatalog(mapOf(searchDescriptor.toolKey to searchExecutor))
        val provider = MutableCatalogProvider(catalogA)
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("A done")),
                AgentModelResult.Success(
                    AgentTestFixtures.toolEnvelope(
                        name = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
                        argumentsJson = """{"query":"report"}"""
                    )
                ),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("B done"))
            )
        )
        val coordinator = coordinator(journal, port, provider, hook)

        val first = coordinator.execute(runA, taskA, null, AgentModelProgressSink { })
        assertTrue(first is AgentLoopResult.Completed)
        assertEquals("A done", (first as AgentLoopResult.Completed).finalText)
        assertEquals(1, readExecutor.executeCount)
        assertEquals(0, searchExecutor.executeCount)
        assertEquals(readDescriptor.toolKey, readExecutor.commands.single().toolKey)
        assertEquals(readDescriptor.toolKey, hook.requests.last().descriptor.toolKey)
        assertEquals(1, provider.calls)

        provider.catalog = catalogB
        val second = coordinator.execute(runB, taskB, null, AgentModelProgressSink { })
        assertTrue(second is AgentLoopResult.Completed)
        assertEquals("B done", (second as AgentLoopResult.Completed).finalText)
        assertEquals(1, readExecutor.executeCount)
        assertEquals(1, searchExecutor.executeCount)
        assertEquals(searchDescriptor.toolKey, searchExecutor.commands.single().toolKey)
        assertEquals(searchDescriptor.toolKey, hook.requests.last().descriptor.toolKey)
        assertEquals(2, provider.calls)
    }

    @Test
    fun decoderRejectsToolKeyFromAnotherCatalog() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "isolation")
        val readExecutor = RecordingToolExecutor(readDescriptor)
        val otherExecutor = RecordingToolExecutor(searchDescriptor)
        val catalogA = FakeToolCatalog(mapOf(readDescriptor.toolKey to readExecutor))
        val provider = MutableCatalogProvider(catalogA)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(
                    AgentTestFixtures.toolEnvelope(
                        name = AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT,
                        argumentsJson = """{"query":"report"}"""
                    )
                ),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("recovered"))
            )
        )
        val coordinator = coordinator(journal, port, provider)

        val result = coordinator.execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals(1, result.transcript.repairsUsed)
        assertTrue(result.transcript.entries.first() is AgentTranscriptEntry.ProtocolError)
        assertEquals(0, readExecutor.executeCount)
        assertEquals(0, otherExecutor.executeCount)
        assertEquals(2, port.inferCount)
        assertEquals(1, provider.calls)
    }

    @Test
    fun providerIsResolvedOncePerExecuteEvenWithMultipleModelTurns() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "once")
        val readExecutor = RecordingToolExecutor(readDescriptor)
        val catalog = FakeToolCatalog(mapOf(readDescriptor.toolKey to readExecutor))
        val provider = MutableCatalogProvider(catalog)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("finished"))
            )
        )
        val result = coordinator(journal, port, provider)
            .execute(run, task, null, AgentModelProgressSink { })
        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(2, port.inferCount)
        assertEquals(1, provider.calls)
        assertEquals(1, readExecutor.executeCount)
    }

    @Test
    fun providerFailureIsTypedFailedWithZeroModelAndToolExecution() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "failure")
        val executor = RecordingToolExecutor(readDescriptor)
        val provider = ExplodingCatalogProvider(IllegalStateException("mcp discovery failed"))
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("must not run")))
        )
        val result = coordinator(journal, port, provider)
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue("provider failure must be a typed Failed result, got $result", result is AgentLoopResult.Failed)
        result as AgentLoopResult.Failed
        assertEquals(
            AgentRunToolCatalogErrorCodes.TOOL_CATALOG_UNAVAILABLE,
            result.error.code
        )
        assertEquals(AgentErrorCategory.TOOL, result.error.category)
        assertTrue(result.transcript.entries.isEmpty())
        assertEquals(0, result.transcript.nextStepIndex)
        assertEquals(0, port.inferCount)
        assertEquals(0, executor.executeCount)
        assertEquals(1, provider.calls)
        assertEquals(1, provider.releaseCalls)
        assertEquals(listOf("catalog", "release"), provider.events)
        assertTrue(journal.allEvents(run.runId).isEmpty())
        assertSame(run, result.run)
    }

    @Test
    fun providerCancellationIsRethrownUntyped() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "cancelled")
        val provider = ExplodingCatalogProvider(CancellationException("catalog cancelled"))
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("must not run")))
        )
        val coordinator = coordinator(journal, port, provider)
        var cancellation: Throwable? = null
        try {
            coordinator.execute(run, task, null, AgentModelProgressSink { })
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }
        assertNotNull("provider cancellation must propagate", cancellation)
        assertTrue(cancellation is CancellationException)
        assertEquals(0, port.inferCount)
        assertEquals(1, provider.calls)
        assertEquals(1, provider.releaseCalls)
        assertEquals(listOf("catalog", "release"), provider.events)
        assertTrue(journal.allEvents(run.runId).isEmpty())
    }

    @Test
    fun releaseRunsExactlyOnceAfterModelAndToolActivity() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "release-success")
        val order = mutableListOf<String>()
        val executor = OrderRecordingToolExecutor(readDescriptor, order)
        val catalog = FakeToolCatalog(mapOf(readDescriptor.toolKey to executor))
        val provider = MutableCatalogProvider(catalog, order)
        val port = OrderRecordingModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("done"))
            ),
            order
        )
        val result = coordinator(journal, port, provider)
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(
            listOf("catalog", "infer", "execute", "infer", "release"),
            order
        )
        assertEquals(1, provider.releaseCalls)
        assertEquals(1, executor.executeCount)
    }

    @Test
    fun releaseRunsExactlyOnceOnModelFailure() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "release-failure")
        val order = mutableListOf<String>()
        val provider = MutableCatalogProvider(FakeToolCatalog(emptyMap()), order)
        val port = OrderRecordingModelPort(
            mutableListOf(AgentRuntimeFixtures.modelFailure()),
            order
        )
        val result = coordinator(journal, port, provider)
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Paused)
        assertEquals(listOf("catalog", "infer", "release"), order)
        assertEquals(1, provider.releaseCalls)
    }

    @Test
    fun releaseRunsExactlyOnceWhenExecutionIsCancelled() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "release-cancel")
        val order = mutableListOf<String>()
        val provider = MutableCatalogProvider(FakeToolCatalog(emptyMap()), order)
        val port = SuspendingModelPort()
        val coordinator = coordinator(journal, port, provider)
        val deferred = async {
            coordinator.execute(run, task, null, AgentModelProgressSink { })
        }
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
        assertEquals(1, provider.releaseCalls)
        assertEquals(listOf("catalog", "release"), order)
    }

    @Test
    fun releaseFailureDoesNotChangeTheCompletedResult() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "release-throws")
        val provider = MutableCatalogProvider(
            catalog = FakeToolCatalog(emptyMap()),
            releaseFailure = IllegalStateException("mcp close failed")
        )
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("still done")))
        )
        val result = coordinator(journal, port, provider)
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals("still done", (result as AgentLoopResult.Completed).finalText)
        assertEquals(1, provider.releaseCalls)
    }

    @Test
    fun releaseCancellationPropagatesWhileResultWasSuccessful() = runTest {
        val journal = InMemoryAgentJournal()
        val (task, run) = createTaskAndRun(journal, "release-cancel-ex")
        val provider = MutableCatalogProvider(
            catalog = FakeToolCatalog(emptyMap()),
            releaseFailure = CancellationException("release cancelled")
        )
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("done")))
        )
        var cancellation: Throwable? = null
        try {
            coordinator(journal, port, provider)
                .execute(run, task, null, AgentModelProgressSink { })
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        }
        assertTrue(cancellation is CancellationException)
        assertEquals(1, provider.releaseCalls)
    }

    // ---------------------------------------------------------------------------------------

    private class MutableCatalogProvider(
        var catalog: AgentToolCatalog,
        private val order: MutableList<String> = mutableListOf(),
        private val releaseFailure: Throwable? = null
    ) : AgentRunToolCatalogProvider {
        var calls: Int = 0
        var releaseCalls: Int = 0
        val events: List<String> get() = order

        override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
            calls += 1
            order += "catalog"
            return catalog
        }

        override suspend fun release(run: AgentRun) {
            releaseCalls += 1
            order += "release"
            releaseFailure?.let { throw it }
        }
    }

    private class ExplodingCatalogProvider(
        private val failure: Throwable,
        private val order: MutableList<String> = mutableListOf()
    ) : AgentRunToolCatalogProvider {
        var calls: Int = 0
        var releaseCalls: Int = 0
        val events: List<String> get() = order

        override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
            calls += 1
            order += "catalog"
            throw failure
        }

        override suspend fun release(run: AgentRun) {
            releaseCalls += 1
            order += "release"
        }
    }

    private class OrderRecordingModelPort(
        private val results: MutableList<AgentModelResult>,
        private val order: MutableList<String>
    ) : AgentModelPort {
        var inferCount: Int = 0

        override suspend fun infer(
            request: AgentModelRequest,
            progress: AgentModelProgressSink
        ): AgentModelResult {
            inferCount += 1
            order += "infer"
            return results.removeFirstOrNull()
                ?: throw IllegalStateException("no scripted model result")
        }

        override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch =
            AgentStopDispatch(true)
    }

    private class OrderRecordingToolExecutor(
        override val descriptor: AgentToolDescriptor,
        private val order: MutableList<String>
    ) : AgentToolExecutor {
        var executeCount: Int = 0

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            executeCount += 1
            order += "execute"
            return AgentRuntimeFixtures.successResult()
        }
    }

    private suspend fun createTaskAndRun(
        journal: InMemoryAgentJournal,
        suffix: String
    ): Pair<AgentTask, AgentRun> {
        val task = AgentRuntimeFixtures.task(taskId = "catalog-task-$suffix")
        journal.commit(CreateTaskTransition("catalog-task-cmd-$suffix", task))
        val run = AgentRuntimeFixtures.run(
            runId = "catalog-run-$suffix",
            taskId = task.taskId,
            state = AgentRunState.RUNNING
        )
        journal.commit(
            CreateRunTransition(
                "catalog-run-cmd-$suffix",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return task to run
    }

    private fun coordinator(
        journal: InMemoryAgentJournal,
        modelPort: com.muyuchat.core.agent.contract.AgentModelPort,
        provider: AgentRunToolCatalogProvider,
        hook: RecordingFrozenCommandHook = RecordingFrozenCommandHook(ledger = journal)
    ): AgentPromptedLoopCoordinator = AgentPromptedLoopCoordinator(
        decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
        serializer = AgentPromptedTranscriptSerializer(codec),
        systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
        catalogProvider = provider,
        schemaValidator = validator,
        journal = journal,
        toolLedger = journal,
        modelPort = modelPort,
        budgetPolicy = AgentBudgetPolicy(clock),
        contextAssembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec),
        commandHook = hook,
        artifactVerifier = FakeArtifactVerifier(),
        ids = SequentialAgentIdGenerator("catalog-loop"),
        clock = clock,
        attemptRegistry = AgentActiveAttemptRegistry()
    )
}

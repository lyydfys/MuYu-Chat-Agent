package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PersistStage
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.ReplyGenerationMetrics
import com.muyuchat.mca.WebSearchDiagnosticRecord
import com.muyuchat.mca.WebSearchResearchMode
import com.muyuchat.mca.WebSearchTurnMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the guard semantics and the exact field set of ChatGenerationUiPublisher's publications.
 *
 * The publisher is a verbatim move of eight `_uiState.update { … }` sites in `startGeneration`,
 * so the contract under test is exactly the one those sites carried: the same fields change by
 * the same values, and a superseded run cannot change anything.
 */
class ChatGenerationUiPublisherTest {

    private fun ownershipWithFreshRun(chatRuns: ChatRunOwnership): Long {
        val reservation = requireNotNull(chatRuns.reserveStart()) { "reserveStart must admit a foreground run" }
        require(chatRuns.activate(reservation, owner = "test-owner")) { "activate must accept its own reservation" }
        return reservation.runId
    }

    private fun usage(): PromptContextUsage = PromptContextUsage(
        retainedMessageCount = 1,
        trimmedMessageCount = 0,
        roleTokens = 3,
        worldBookTokens = 0,
        knowledgeTokens = 0,
        totalEstimatedTokens = 3L
    )

    @Test
    fun currentRunPublishesStatusAndSearchLines() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        publisher.publishContextCompressedStatus(7)
        assertEquals("已压缩 7 条较早对话，保留角色设定和最近消息", uiState.value.statusMessage)

        publisher.publishContextStatusMessage("已接入 2 个知识库")
        assertEquals("已接入 2 个知识库", uiState.value.statusMessage)

        publisher.publishWebSearchStartStatus("q".repeat(60))
        assertEquals("正在联网检索：${"q".repeat(42)}", uiState.value.webSearchStatusMessage)
        assertEquals("正在联网检索...", uiState.value.statusMessage)
    }

    @Test
    fun promptContextUsageIsPublishedByInstance() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        val usage = usage()
        publisher.publishPromptContextUsage(usage)
        assertSame(usage, uiState.value.promptContextUsage)
    }

    @Test
    fun streamPhaseWritesTheQuartetExceptPersistProgress() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val inFlightPersist = PersistProgress(PersistStage.WRITING, writtenBytes = 1, totalBytes = 2)
        val uiState = MutableStateFlow(MainUiState(generationPersistProgress = inFlightPersist))
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        val stats = RuntimeStats(loaded = true, completionTokens = 3)
        publisher.publishStreamPhase(stats, GenerationPhase.PREFILL, TokenProgress(1, 4))

        assertEquals(stats, uiState.value.stats)
        assertEquals(GenerationPhase.PREFILL, uiState.value.generationPhase)
        assertEquals(TokenProgress(1, 4), uiState.value.generationTokenProgress)
        assertEquals(stats, uiState.value.generationStats)
        // copy() omits the field on purpose: a phase event does not cancel a persist in flight.
        assertSame(inFlightPersist, uiState.value.generationPersistProgress)
    }

    @Test
    fun streamChunkEntersDecodeAndReplacesTheWholeProgressQuartet() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(
            MainUiState(
                generationPhase = GenerationPhase.PREFILL,
                generationTokenProgress = TokenProgress(2, 4),
                generationPersistProgress = PersistProgress(PersistStage.ENCODING, 1, 2)
            )
        )
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        val stats = RuntimeStats(loaded = true, completionTokens = 9)
        publisher.publishStreamChunkStats(stats)

        assertEquals(stats, uiState.value.stats)
        assertEquals(GenerationPhase.DECODE, uiState.value.generationPhase)
        assertNull(uiState.value.generationTokenProgress)
        assertNull(uiState.value.generationPersistProgress)
        assertEquals(stats, uiState.value.generationStats)
    }

    @Test
    fun persistProgressAndLogsPublishTheirSingleField() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        val progress = PersistProgress(PersistStage.WRITING, writtenBytes = 5, totalBytes = 9)
        publisher.publishPersistProgress(progress)
        assertSame(progress, uiState.value.generationPersistProgress)
        assertEquals(RuntimeStats(), uiState.value.stats)

        publisher.publishRuntimeLogs(emptyList())
        assertEquals(emptyList<com.muyuchat.core.telemetry.RuntimeMetrics>(), uiState.value.logs)
    }

    @Test
    fun refusalReleasesTheLockAndCarriesTheExactMessage() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(
            MainUiState(
                isGenerating = true,
                generationPhase = GenerationPhase.TOKENIZE,
                generationTokenProgress = TokenProgress(1, 2),
                generationPersistProgress = PersistProgress(PersistStage.WRITING, 1, 2),
                engineLifecycle = AgentEngineLifecycle.GENERATING
            )
        )
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        publisher.refuseGenerationBeforeStream("未加载云端推理模型")

        val state = uiState.value
        assertEquals(false, state.isGenerating)
        assertNull(state.generationPhase)
        assertNull(state.generationTokenProgress)
        assertNull(state.generationPersistProgress)
        assertEquals("未加载云端推理模型", state.statusMessage)
        // The refusal predates the stream entirely, so the lifecycle projection stays untouched.
        assertEquals(AgentEngineLifecycle.GENERATING, state.engineLifecycle)
    }

    @Test
    fun fallbackTerminationSettlesOnlyAGeneratingStateAndFinishesMetricsLazily() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val stats = RuntimeStats(loaded = true, completionTokens = 3)
        val assistant = ChatMessage(Role.ASSISTANT, "answer")
        val uiState = MutableStateFlow(MainUiState(messages = listOf(assistant)))
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)
        var metricsCalls = 0

        // An idle state is left untouched, and the metrics are never finished.
        publisher.publishFallbackTermination(stats, "生成流已结束", { metricsCalls += 1; null })
        assertEquals(0, metricsCalls)
        assertNull(uiState.value.statusMessage)

        // A running state is settled, with the lazily finished metrics attached.
        val replyMetrics = ReplyGenerationMetrics(7L).apply { record(stats) }
        uiState.value = MainUiState(
            isGenerating = true,
            generationPhase = GenerationPhase.DECODE,
            messages = listOf(assistant),
            engineLifecycle = AgentEngineLifecycle.GENERATING
        )
        publisher.publishFallbackTermination(stats, "生成流已结束", { replyMetrics.finish() })

        val after = uiState.value
        assertEquals(false, after.isGenerating)
        assertEquals("生成流已结束", after.statusMessage)
        assertNull(after.generationPhase)
        assertEquals(AgentEngineLifecycle.READY, after.engineLifecycle)
        assertNotNull(after.messages.last().generationMetrics)
    }

    @Test
    fun supersededRunCannotPublishAnything() {
        val chatRuns = ChatRunOwnership()
        val firstRunId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, firstRunId, uiState)

        // A replacement run reserves -- the epoch advances and the first run is superseded.
        val secondRunId = ownershipWithFreshRun(chatRuns)

        stalePublisher.publishContextCompressedStatus(7)
        stalePublisher.publishContextStatusMessage("stale")
        stalePublisher.publishWebSearchStartStatus("stale query")
        stalePublisher.publishPromptContextUsage(usage())
        stalePublisher.publishStreamPhase(RuntimeStats(loaded = true), GenerationPhase.DECODE, TokenProgress(1, 3))
        stalePublisher.publishStreamChunkStats(RuntimeStats(loaded = true))
        stalePublisher.publishPersistProgress(PersistProgress(PersistStage.WRITING, 1, 2))
        stalePublisher.publishRuntimeLogs(emptyList())
        stalePublisher.refuseGenerationBeforeStream("stale refusal")
        stalePublisher.publishFallbackTermination(RuntimeStats(loaded = true), "stale", { null })

        assertEquals(MainUiState(), uiState.value)

        // Positive control: the replacement owner publishes through the same surface.
        val freshPublisher = ChatGenerationUiPublisher(chatRuns, secondRunId, uiState)
        freshPublisher.publishContextStatusMessage("fresh")
        assertEquals("fresh", uiState.value.statusMessage)
    }

    @Test
    fun preStreamTurnNormalizationWritesItsExactFields() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(
            MainUiState(
                contextCompressionPending = true,
                webSearchTurnMode = WebSearchTurnMode.ON,
                webSearchResearchModeOverride = WebSearchResearchMode.DEEP,
                webSearchOneShotEnabled = true
            )
        )
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        val normalized = uiState.value.params.copy(nPredict = 321)
        publisher.publishNormalizedTurnParams(normalized)
        assertEquals(321, uiState.value.params.nPredict)

        publisher.publishManualCompressionConsumed()
        assertEquals(false, uiState.value.contextCompressionPending)

        publisher.publishWebSearchTurnReset()
        assertEquals(WebSearchTurnMode.FOLLOW, uiState.value.webSearchTurnMode)
        assertNull(uiState.value.webSearchResearchModeOverride)
        assertEquals(false, uiState.value.webSearchOneShotEnabled)
    }

    @Test
    fun preStreamNormalizationIsDeliberatelyNotRunGated() {
        // These four sites were NOT run-gated inline: they run before/around the stream with the
        // values already frozen into the run, so the publication writes exactly like the old site.
        val chatRuns = ChatRunOwnership()
        val firstRunId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState(webSearchTurnMode = WebSearchTurnMode.ON))
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, firstRunId, uiState)
        ownershipWithFreshRun(chatRuns)

        val normalized = uiState.value.params.copy(nPredict = 321)
        stalePublisher.publishNormalizedTurnParams(normalized)
        stalePublisher.publishManualCompressionConsumed()
        stalePublisher.publishWebSearchTurnReset()
        stalePublisher.publishWebSearchOutcome("stale search", emptyList(), "stale status")

        assertEquals(321, uiState.value.params.nPredict)
        assertEquals(false, uiState.value.contextCompressionPending)
        assertEquals(WebSearchTurnMode.FOLLOW, uiState.value.webSearchTurnMode)
        assertEquals("stale search", uiState.value.webSearchStatusMessage)
        assertEquals("stale status", uiState.value.statusMessage)
    }

    @Test
    fun webSearchOutcomeKeepsExistingValuesForNullInputs() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val record = WebSearchDiagnosticRecord(
            providerLabel = "test-provider",
            triggerModeLabel = "手动",
            query = "q",
            success = true,
            message = "ok"
        )
        val uiState = MutableStateFlow(
            MainUiState(
                webSearchStatusMessage = "previous search",
                webSearchDiagnostics = listOf(record),
                statusMessage = "previous status"
            )
        )
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState)

        publisher.publishWebSearchOutcome(
            webSearchStatusMessage = null,
            diagnostics = null,
            statusMessage = null
        )
        assertEquals("previous search", uiState.value.webSearchStatusMessage)
        assertEquals(1, uiState.value.webSearchDiagnostics.size)
        assertEquals("previous status", uiState.value.statusMessage)

        publisher.publishWebSearchOutcome(
            webSearchStatusMessage = "fresh search",
            diagnostics = emptyList(),
            statusMessage = "fresh status"
        )
        assertEquals("fresh search", uiState.value.webSearchStatusMessage)
        assertEquals(0, uiState.value.webSearchDiagnostics.size)
        assertEquals("fresh status", uiState.value.statusMessage)
    }
}

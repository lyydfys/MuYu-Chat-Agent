package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.mca.AssistantConversationSnapshot
import com.muyuchat.mca.contextSummaryCandidate
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
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private fun publisherWithClock(
        chatRuns: ChatRunOwnership,
        runId: Long,
        uiState: MutableStateFlow<MainUiState>,
        clock: () -> Long
    ): ChatGenerationUiPublisher = ChatGenerationUiPublisher(
        chatRuns,
        runId,
        uiState,
        { transform -> uiState.update(transform) },
        clock
    )

    @Test
    fun currentRunPublishesStatusAndSearchLines() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

        val stats = RuntimeStats(loaded = true, completionTokens = 9)
        publisher.publishStreamChunkStats(stats)

        assertEquals(stats, uiState.value.stats)
        assertEquals(GenerationPhase.DECODE, uiState.value.generationPhase)
        assertNull(uiState.value.generationTokenProgress)
        assertNull(uiState.value.generationPersistProgress)
        assertEquals(stats, uiState.value.generationStats)
    }

    @Test
    fun streamChunkStatsPublishesImmediatelyThenThrottlesInsideTheWindow() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        var nowNanos = 0L
        var stateWrites = 0
        val publisher = ChatGenerationUiPublisher(
            chatRuns,
            runId,
            uiState,
            { transform ->
                stateWrites += 1
                uiState.update(transform)
            },
            { nowNanos }
        )
        val firstStats = RuntimeStats(loaded = true, completionTokens = 1, decodeTps = 1.0)
        val skippedStats = firstStats.copy(completionTokens = 2, decodeTps = 2.0)
        val latestStats = firstStats.copy(completionTokens = 3, decodeTps = 3.0)

        publisher.publishStreamChunkStats(firstStats)
        assertEquals(firstStats, uiState.value.stats)
        assertEquals(firstStats, uiState.value.generationStats)
        assertEquals(GenerationPhase.DECODE, uiState.value.generationPhase)
        assertEquals(1, stateWrites)

        nowNanos += 99_999_999L
        publisher.publishStreamChunkStats(skippedStats)
        assertEquals(firstStats, uiState.value.stats)
        assertEquals(firstStats, uiState.value.generationStats)
        assertEquals(1, stateWrites)

        nowNanos += 1L
        publisher.publishStreamChunkStats(latestStats)
        assertEquals(latestStats, uiState.value.stats)
        assertEquals(latestStats, uiState.value.generationStats)
        assertEquals(2, stateWrites)
    }

    @Test
    fun streamPhaseIgnoresTheChunkStatsThrottleWindow() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        var nowNanos = 0L
        val publisher = publisherWithClock(chatRuns, runId, uiState) { nowNanos }

        publisher.publishStreamChunkStats(RuntimeStats(loaded = true, completionTokens = 1))
        val inFlightPersist = PersistProgress(PersistStage.WRITING, writtenBytes = 1, totalBytes = 2)
        uiState.value = uiState.value.copy(generationPersistProgress = inFlightPersist)
        nowNanos += 1L

        val phaseStats = RuntimeStats(loaded = true, completionTokens = 2)
        publisher.publishStreamPhase(phaseStats, GenerationPhase.PREFILL, TokenProgress(1, 4))

        assertEquals(phaseStats, uiState.value.stats)
        assertEquals(phaseStats, uiState.value.generationStats)
        assertEquals(GenerationPhase.PREFILL, uiState.value.generationPhase)
        assertEquals(TokenProgress(1, 4), uiState.value.generationTokenProgress)
        assertSame(inFlightPersist, uiState.value.generationPersistProgress)
    }

    @Test
    fun persistProgressAndRefusalIgnoreTheChunkStatsThrottleWindow() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState(isGenerating = true))
        var nowNanos = 0L
        val publisher = publisherWithClock(chatRuns, runId, uiState) { nowNanos }

        publisher.publishStreamChunkStats(RuntimeStats(loaded = true, completionTokens = 1))
        nowNanos += 50_000_000L
        val progress = PersistProgress(PersistStage.WRITING, writtenBytes = 5, totalBytes = 9)
        publisher.publishPersistProgress(progress)
        assertSame(progress, uiState.value.generationPersistProgress)

        publisher.refuseGenerationBeforeStream("generation refused")
        val afterRefusal = uiState.value
        assertFalse(afterRefusal.isGenerating)
        assertNull(afterRefusal.generationPersistProgress)
        assertEquals("generation refused", afterRefusal.statusMessage)
    }

    @Test
    fun supersededRunRemainsGatedAfterTheStatsWindowElapses() {
        val chatRuns = ChatRunOwnership()
        val firstRunId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        var nowNanos = 0L
        val stalePublisher = publisherWithClock(chatRuns, firstRunId, uiState) { nowNanos }

        ownershipWithFreshRun(chatRuns)
        nowNanos += 200_000_000L
        val before = uiState.value
        stalePublisher.publishStreamChunkStats(RuntimeStats(loaded = true, completionTokens = 9))

        assertSame(before, uiState.value)
    }

    @Test
    fun terminalSettlementKeepsFinalStatsInsideTheChunkStatsWindow() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState(isGenerating = true))
        var nowNanos = 0L
        val publisher = publisherWithClock(chatRuns, runId, uiState) { nowNanos }

        publisher.publishStreamChunkStats(RuntimeStats(loaded = true, completionTokens = 1))
        nowNanos += 50_000_000L
        publisher.publishStreamChunkStats(RuntimeStats(loaded = true, completionTokens = 2))

        val finalStats = RuntimeStats(loaded = true, completionTokens = 9, decodeTps = 12.5)
        var finishMetricsCalls = 0
        publisher.publishFallbackTermination(finalStats, "generation complete") {
            finishMetricsCalls += 1
            null
        }

        assertEquals(1, finishMetricsCalls)
        assertEquals(finalStats, uiState.value.stats)
        assertNull(uiState.value.generationStats)
        assertFalse(uiState.value.isGenerating)
        assertEquals("generation complete", uiState.value.statusMessage)
    }

    @Test
    fun persistProgressAndLogsPublishTheirSingleField() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })
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
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, firstRunId, uiState, { transform -> uiState.update(transform) })

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
        val freshPublisher = ChatGenerationUiPublisher(chatRuns, secondRunId, uiState, { transform -> uiState.update(transform) })
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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, firstRunId, uiState, { transform -> uiState.update(transform) })
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
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

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

    @Test
    fun browserSearchTaskStartIsGatedAndReportsPublication() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })
        val task = BrowserTaskStateMachine.beginBackgroundSearch(
            owner = "test", sessionId = null, messageId = null, query = "q"
        )

        assertTrue(publisher.publishBrowserSearchTaskStart(task))
        assertEquals(task, uiState.value.browserTask)

        // A visible search window is never replaced (verbatim inline guard).
        val visible = task.copy(windowVisible = true)
        uiState.value = uiState.value.copy(browserTask = visible)
        assertFalse(publisher.publishBrowserSearchTaskStart(task))
        assertEquals(visible, uiState.value.browserTask)
    }

    @Test
    fun browserSearchTaskStartIsNotPublishedByASupersededRun() {
        val chatRuns = ChatRunOwnership()
        val firstRunId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState())
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, firstRunId, uiState, { transform -> uiState.update(transform) })
        ownershipWithFreshRun(chatRuns)

        val task = BrowserTaskStateMachine.beginBackgroundSearch(
            owner = "test", sessionId = null, messageId = null, query = "q"
        )
        assertFalse(stalePublisher.publishBrowserSearchTaskStart(task))
        assertNull(uiState.value.browserTask)
    }

    @Test
    fun browserSearchResultsTransitionOnlyTheOwnedSearchingTask() {
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val task = BrowserTaskStateMachine.beginBackgroundSearch(
            owner = "test", sessionId = null, messageId = null, query = "q"
        )
        val uiState = MutableStateFlow(MainUiState(browserTask = task))
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

        // A task id the UI no longer owns is left untouched.
        publisher.publishBrowserSearchResults("browser-other", listOf("https://a.example/x"), "done")
        assertEquals(task, uiState.value.browserTask)

        publisher.publishBrowserSearchResults(task.taskId, listOf("https://a.example/x"), "done")
        val after = requireNotNull(uiState.value.browserTask)
        assertEquals(BrowserTaskPhase.AWAITING_CONFIRMATION, after.phase)
        assertEquals(listOf("https://a.example/x"), after.candidateUrls)
    }

    @Test
    fun contextSummaryActivationLeavesForeignSessionsAndSupersededRunsUntouched() {
        val messages = listOf(
            ChatMessage(Role.USER, "I prefer tea.", id = "first", createdAt = 1L),
            ChatMessage(Role.ASSISTANT, "Meeting is on 2026-09-27.", id = "second", createdAt = 2L),
            ChatMessage(Role.USER, "latest question", id = "latest", createdAt = 3L)
        )
        val summaryResult = compressChatRequestContext(
            ChatRequest(messages, GenerationParams(nCtx = 4_096)),
            ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1),
            ContextCompressionTrigger.MANUAL
        )
        val candidate = requireNotNull(
            contextSummaryCandidate(messages, summaryResult, emptyList(), "persona", 100L)
        )
        val snapshot = AssistantConversationSnapshot(
            assistantId = "assistant-1",
            name = "Assistant",
            systemPrompt = "persona",
            memoryEnabled = false,
            webSearchEnabled = false,
            fileContextEnabled = false,
            capturedAt = 1L
        )
        val chatRuns = ChatRunOwnership()
        val runId = ownershipWithFreshRun(chatRuns)
        val uiState = MutableStateFlow(MainUiState(messages = messages, activeChatSessionId = "session-1"))
        val publisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })

        // Foreign frozen session: the run owns the UI but the session identity does not match, so
        // nothing is activated and the (empty) session list is left unchanged.
        assertFalse(
            publisher.publishContextSummaryActivation(
                candidate = candidate,
                scope = "persona",
                expectedSessionId = "session-1",
                frozenSessionId = "session-other",
                frozenAssistantId = "assistant-1",
                frozenAssistantSnapshot = snapshot
            )
        )

        // Session drift in live state: left untouched even when the frozen ids match.
        uiState.value = uiState.value.copy(activeChatSessionId = "session-2")
        assertFalse(
            publisher.publishContextSummaryActivation(
                candidate = candidate,
                scope = "persona",
                expectedSessionId = "session-1",
                frozenSessionId = "session-1",
                frozenAssistantId = "assistant-1",
                frozenAssistantSnapshot = snapshot
            )
        )

        // Superseded run: the gate refuses before the transform runs.
        val stalePublisher = ChatGenerationUiPublisher(chatRuns, runId, uiState, { transform -> uiState.update(transform) })
        ownershipWithFreshRun(chatRuns)
        val before = uiState.value
        assertFalse(
            stalePublisher.publishContextSummaryActivation(
                candidate = candidate,
                scope = "persona",
                expectedSessionId = before.activeChatSessionId,
                frozenSessionId = "session-1",
                frozenAssistantId = "assistant-1",
                frozenAssistantSnapshot = snapshot
            )
        )
        assertSame(before, uiState.value)
    }
}

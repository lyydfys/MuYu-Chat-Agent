package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.BrowserTask
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the orchestration of the user-facing stop path, moved verbatim out of
 * `MainViewModel.stopGeneration` during R1 (item 2). The port is a pure interface, so the whole
 * path runs on a fake:
 *
 *  - the conversation-mutation barrier is checked first and refuses the stop;
 *  - the one-at-a-time latch admits exactly one stop and is always released;
 *  - a stop on an idle UI touches no state;
 *  - the full path cancels the run, cancels a SEARCHING browser task, requests the runtime stop,
 *    joins, re-checks the epoch, settles the UI (isGenerating=false, progress cleared, lifecycle
 *    from the port) and persists the settled conversation with the port's rollback snapshot;
 *  - an epoch drift after the join settles nothing but still releases the latch;
 *  - a cloud stop (stopLocalRuntime=false without a cancelled ui- request) skips the runtime stop.
 *
 * The fake's stop scope is Unconfined, so the launched body runs synchronously to its first real
 * suspension point; with no stopped Job to join, a stop completes inside the call.
 */
class ChatStopGenerationOperationsTest {

    private class FakeStopPort(
        initial: MainUiState,
        private val rejectMutation: Boolean = false,
        private val enterStop: Boolean = true
    ) : ChatStopGenerationPort {

        val stateFlow = MutableStateFlow(initial)
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        var tryEnterCalls = 0
        var exitStopCalls = 0
        var rejectCalls = 0
        var updateCalls = 0
        var cancelCalls = 0
        var runtimeStopCalls = 0
        var flushCalls = 0
        var persistCalls = 0
        var persistSessions: List<ChatSessionRecord> = emptyList()
        var persistRollback: ConversationMutationRollbackState? = null
        var metrics: ReplyGenerationMetrics? = null
        var cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = true,
            invalidatedRunId = 1L
        )
        var runIsCurrent: (Long?) -> Boolean = { true }
        var stopRequestId: String? = null
        var lifecycle: AgentEngineLifecycle = AgentEngineLifecycle.READY
        val rollback = ConversationMutationRollbackState(
            messages = emptyList(),
            activeChatSessionId = null,
            chatSessions = emptyList(),
            selectedKnowledgeBaseIds = emptySet(),
            generationPhase = null,
            generationTokenProgress = null,
            promptContextUsage = null
        )

        override fun currentState(): MainUiState = stateFlow.value

        override fun updateUiState(transform: (MainUiState) -> MainUiState) {
            updateCalls += 1
            stateFlow.update(transform)
        }

        override fun stopScope(): CoroutineScope = scope

        override fun rejectWhileConversationMutationInProgress(): Boolean {
            rejectCalls += 1
            return rejectMutation
        }

        override fun tryEnterStop(): Boolean {
            tryEnterCalls += 1
            return enterStop
        }

        override fun exitStop() {
            exitStopCalls += 1
        }

        override fun currentReplyMetrics(): ReplyGenerationMetrics? = metrics

        override fun isRunCurrent(runId: Long?): Boolean = runIsCurrent(runId)

        override fun currentRunId(): Long = 1L

        override fun cancelCurrentGeneration(): UiGenerationCancellation {
            cancelCalls += 1
            return cancellation
        }

        override fun activeUiStopRequestId(): String? = stopRequestId

        override suspend fun requestRuntimeStop() {
            runtimeStopCalls += 1
        }

        override fun runtimeLifecycle(): AgentEngineLifecycle = lifecycle

        override fun rollbackState(): ConversationMutationRollbackState = rollback

        override fun flushPendingAssistantOutput() {
            flushCalls += 1
        }

        override fun persistConversationMutation(
            sessions: List<ChatSessionRecord>,
            rollback: ConversationMutationRollbackState
        ) {
            persistCalls += 1
            persistSessions = sessions
            persistRollback = rollback
        }
    }

    private fun generatingState(browserTask: BrowserTask? = null): MainUiState = MainUiState(
        isGenerating = true,
        generationPhase = GenerationPhase.DECODE,
        browserTask = browserTask,
        activeChatSessionId = "session-1"
    )

    @Test
    fun mutationBarrierRefusesTheStopBeforeTheLatch() {
        val port = FakeStopPort(generatingState(), rejectMutation = true)

        stopActiveGeneration(port)

        assertEquals(1, port.rejectCalls)
        assertEquals(0, port.tryEnterCalls)
        assertEquals(0, port.exitStopCalls)
        assertEquals(0, port.cancelCalls)
    }

    @Test
    fun theLatchAdmitsOnlyOneStop() {
        val port = FakeStopPort(generatingState(), enterStop = false)

        stopActiveGeneration(port)

        assertEquals(1, port.tryEnterCalls)
        assertEquals(0, port.exitStopCalls)
        assertEquals(0, port.cancelCalls)
        assertEquals(0, port.updateCalls)
    }

    @Test
    fun idleUiReleasesTheLatchWithoutSettling() {
        val port = FakeStopPort(MainUiState(isGenerating = false))

        stopActiveGeneration(port)

        assertEquals(1, port.tryEnterCalls)
        assertEquals(1, port.exitStopCalls)
        assertEquals(0, port.updateCalls)
        assertEquals(0, port.cancelCalls)
        assertEquals(0, port.flushCalls)
        assertEquals(0, port.persistCalls)
    }

    @Test
    fun fullStopPathSettlesAndPersists() {
        val searching = BrowserTaskStateMachine.beginBackgroundSearch(
            owner = "test", sessionId = "session-1", messageId = null, query = "query"
        )
        val port = FakeStopPort(generatingState(browserTask = searching))
        port.metrics = ReplyGenerationMetrics(7L)

        stopActiveGeneration(port)

        assertEquals(1, port.cancelCalls)
        assertEquals(1, port.runtimeStopCalls)
        assertEquals(1, port.flushCalls)
        assertEquals(1, port.persistCalls)
        assertEquals(1, port.exitStopCalls)
        // Browser-task cancel, then the settlement.
        assertEquals(2, port.updateCalls)

        val after = port.stateFlow.value
        assertEquals(false, after.isGenerating)
        assertEquals("已停止生成", after.statusMessage)
        assertNull(after.generationPhase)
        assertEquals(AgentEngineLifecycle.READY, after.engineLifecycle)
        assertEquals(BrowserTaskPhase.CANCELLED, after.browserTask?.phase)
        assertSame(port.rollback, port.persistRollback)
        assertEquals(after.chatSessions, port.persistSessions)
    }

    @Test
    fun epochDriftAfterTheJoinSettlesNothingButReleasesTheLatch() {
        val port = FakeStopPort(generatingState())
        port.runIsCurrent = { false }

        stopActiveGeneration(port)

        assertEquals(1, port.cancelCalls)
        assertEquals(1, port.exitStopCalls)
        // The browser-task cancel precedes the epoch check; nothing after it runs.
        assertEquals(1, port.updateCalls)
        assertEquals(0, port.flushCalls)
        assertEquals(0, port.persistCalls)
    }

    @Test
    fun cloudStopWithoutAUiStopRequestSkipsTheRuntimeStop() {
        val port = FakeStopPort(generatingState())
        port.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = false,
            invalidatedRunId = 1L
        )

        stopActiveGeneration(port)

        assertEquals(0, port.runtimeStopCalls)
        assertEquals("已停止生成", port.stateFlow.value.statusMessage)
    }

    @Test
    fun pendingCancelledUiRequestStillStopsTheLocalRuntime() {
        val uiPort = FakeStopPort(generatingState())
        uiPort.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = true,
            stopLocalRuntime = false,
            invalidatedRunId = 1L
        )
        uiPort.stopRequestId = "ui-123-abc"

        stopActiveGeneration(uiPort)
        assertEquals(1, uiPort.runtimeStopCalls)

        val apiPort = FakeStopPort(generatingState())
        apiPort.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = true,
            stopLocalRuntime = false,
            invalidatedRunId = 1L
        )
        apiPort.stopRequestId = "api-123-abc"

        stopActiveGeneration(apiPort)
        assertEquals("only a ui- request may target the local runtime", 0, apiPort.runtimeStopCalls)
    }
}

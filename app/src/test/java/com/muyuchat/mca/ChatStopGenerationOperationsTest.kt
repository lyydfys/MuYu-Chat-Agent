package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.BrowserTask
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 *  - the full path cancels the run, cancels a SEARCHING browser task, requests the runtime stop for
 *    the exact frozen attempt id, joins, re-checks the epoch, settles the UI (isGenerating=false,
 *    progress cleared, lifecycle from the port) and persists the settled conversation with the
 *    port's rollback snapshot;
 *  - a late stop only targets its frozen attempt: an Agent/API/image owner that took the runtime
 *    afterwards is untouched, while a stale stop writes nothing to the newer UI;
 *  - a run with no dispatched attempt (pending/cloud) never touches the engine;
 *  - repeated stops are idempotent and an epoch drift still releases the latch;
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
        var afterCommit: (updateCall: Int) -> Unit = {}
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
        var lifecycle: AgentEngineLifecycle = AgentEngineLifecycle.READY
        /** The exact request the runtime would currently stop, if any (simulates the engine owner). */
        var runtimeCurrentRequestId: String? = null
        val runtimeStopTargets = mutableListOf<String>()
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

        override fun updateUiState(transform: (GenerationStopFields) -> GenerationStopFields): GenerationStopFields {
            updateCalls += 1
            while (true) {
                val snapshot = stateFlow.value
                val fields = transform(GenerationStopFields(snapshot))
                val updated = fields.mergeInto(snapshot)
                if (stateFlow.compareAndSet(snapshot, updated)) {
                    afterCommit(updateCalls)
                    return GenerationStopFields(updated)
                }
            }
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

        override fun cancelCurrentGeneration(): UiGenerationCancellation {
            cancelCalls += 1
            return cancellation
        }

        override fun frozenLocalAttempt(cancellation: UiGenerationCancellation): ChatAttemptIdentity? =
            cancellation.localAttempt

        override suspend fun requestRuntimeStop(attempt: ChatAttemptIdentity) {
            runtimeStopCalls += 1
            runtimeStopTargets += attempt.requestId
            // Exact-attempt model: only the frozen id can be stopped; if any other owner (Agent,
            // Local API, image) holds the runtime now, its request is left untouched.
            if (runtimeCurrentRequestId == attempt.requestId) {
                runtimeCurrentRequestId = null
            }
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
        val frozenAttempt = ChatAttemptIdentity(1L, "ui-1-test")
        port.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = true,
            invalidatedRunId = 1L,
            localAttempt = frozenAttempt
        )
        port.runtimeCurrentRequestId = frozenAttempt.requestId

        stopActiveGeneration(port)

        assertEquals(1, port.cancelCalls)
        assertEquals(1, port.runtimeStopCalls)
        assertEquals("the frozen attempt id is the only engine target",
            listOf(frozenAttempt.requestId), port.runtimeStopTargets)
        assertNull("the exact attempt was stopped", port.runtimeCurrentRequestId)
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
    fun stopPersistsItsCommittedSessionsEvenWhenAnotherWriteImmediatelyReplacesThem() {
        val settledSessions = listOf(ChatSessionRecord("session-1", "Settled", emptyList()))
        val replacement = listOf(ChatSessionRecord("session-2", "Replacement", emptyList()))
        val port = FakeStopPort(generatingState().copy(chatSessions = settledSessions))
        port.afterCommit = { updateCall ->
            if (updateCall == 2) port.stateFlow.value = port.stateFlow.value.copy(chatSessions = replacement)
        }

        stopActiveGeneration(port)

        assertSame(settledSessions, port.persistSessions)
        assertSame(replacement, port.stateFlow.value.chatSessions)
        assertEquals(1, port.persistCalls)
        assertEquals(1, port.exitStopCalls)
    }

    @Test
    fun epochDriftAfterTheJoinSettlesNothingButReleasesTheLatch() {
        val port = FakeStopPort(generatingState())
        port.runIsCurrent = { false }

        stopActiveGeneration(port)

        assertEquals(1, port.cancelCalls)
        assertEquals(1, port.exitStopCalls)
        // The stale run owns neither the browser task nor the settlement.
        assertEquals(0, port.updateCalls)
        assertEquals(0, port.flushCalls)
        assertEquals(0, port.persistCalls)
    }

    @Test
    fun aStaleStopStillStopsItsFrozenAttemptButWritesNothingToTheNewerUi() {
        val port = FakeStopPort(generatingState())
        val frozenAttempt = ChatAttemptIdentity(1L, "ui-1-stale")
        port.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = true,
            invalidatedRunId = 2L,
            localAttempt = frozenAttempt
        )
        port.runtimeCurrentRequestId = frozenAttempt.requestId
        port.runIsCurrent = { false }

        stopActiveGeneration(port)

        assertEquals("the frozen attempt still receives its exact stop",
            listOf(frozenAttempt.requestId), port.runtimeStopTargets)
        assertNull(port.runtimeCurrentRequestId)
        assertEquals("but a stale stop must not cancel the newer browser task", 0, port.updateCalls)
        assertEquals(0, port.flushCalls)
        assertEquals(0, port.persistCalls)
        assertEquals(true, port.stateFlow.value.isGenerating)
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
    fun aPendingReservationWithNoDispatchedAttemptNeverTouchesTheEngine() {
        // Regeneration reserves the UI run before its persistence commit. That reservation has no
        // engine attempt yet, so the stop must settle the UI without stopping whatever request
        // currently owns the runtime (that could be an Agent, the Local API or an image job).
        val uiPort = FakeStopPort(generatingState())
        uiPort.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = true,
            stopLocalRuntime = false,
            invalidatedRunId = 1L
        )
        uiPort.runtimeCurrentRequestId = "agent:run-1:0:0"

        stopActiveGeneration(uiPort)

        assertEquals(0, uiPort.runtimeStopCalls)
        assertEquals("agent:run-1:0:0", uiPort.runtimeCurrentRequestId)
        assertEquals(false, uiPort.stateFlow.value.isGenerating)
        assertEquals("已停止生成", uiPort.stateFlow.value.statusMessage)
    }

    @Test
    fun aLateStopForTheFrozenAttemptCannotStopTheRequestThatOwnsTheEngineNow() {
        // The classic P03 race: the user stops chat A, but by the time the stop is dispatched the
        // engine belongs to Agent B. The frozen A identity must be the only target.
        val port = FakeStopPort(generatingState())
        val frozenAttempt = ChatAttemptIdentity(1L, "ui-1-late")
        port.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = true,
            invalidatedRunId = 1L,
            localAttempt = frozenAttempt
        )
        port.runtimeCurrentRequestId = "agent:run-9:1:0"

        stopActiveGeneration(port)

        assertEquals(listOf(frozenAttempt.requestId), port.runtimeStopTargets)
        assertEquals("the Agent attempt must keep the runtime",
            "agent:run-9:1:0", port.runtimeCurrentRequestId)
        assertEquals(false, port.stateFlow.value.isGenerating)
    }

    @Test
    fun aChatStopLeavesLocalApiAndImageOwnersAlone() {
        // API and image requests never match the frozen `ui-` attempt id, and there is no global
        // fallback stop in the port contract. Neither owner is cancelled by a chat stop.
        for (currentOwner in listOf("chatcmpl-api-1", "image-job-7")) {
            val port = FakeStopPort(generatingState())
            port.cancellation = UiGenerationCancellation(
                owner = null,
                pendingCancelled = false,
                stopLocalRuntime = true,
                invalidatedRunId = 1L,
                localAttempt = ChatAttemptIdentity(1L, "ui-1-chat")
            )
            port.runtimeCurrentRequestId = currentOwner

            stopActiveGeneration(port)

            assertEquals(listOf("ui-1-chat"), port.runtimeStopTargets)
            assertEquals("$currentOwner must not be stopped", currentOwner, port.runtimeCurrentRequestId)
        }
    }

    @Test
    fun repeatedStopIsIdempotent() {
        val port = FakeStopPort(generatingState())
        port.cancellation = UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = true,
            invalidatedRunId = 1L,
            localAttempt = ChatAttemptIdentity(1L, "ui-1-repeat")
        )
        port.runtimeCurrentRequestId = "ui-1-repeat"

        stopActiveGeneration(port)
        val persistedAfterFirst = port.persistCalls
        stopActiveGeneration(port)

        assertEquals("the second tap must not cancel again", 1, port.cancelCalls)
        assertEquals("nor request a second engine stop", 1, port.runtimeStopCalls)
        assertEquals(persistedAfterFirst, port.persistCalls)
        assertEquals(2, port.exitStopCalls)
    }

    @Test
    fun anIdleStopDoesNotReadOrTouchAnyRuntimeTarget() {
        val port = FakeStopPort(MainUiState(isGenerating = false))
        port.runtimeCurrentRequestId = "agent:run-1:0:0"

        stopActiveGeneration(port)

        assertEquals(0, port.cancelCalls)
        assertEquals(0, port.runtimeStopCalls)
        assertEquals(0, port.updateCalls)
        assertEquals("agent:run-1:0:0", port.runtimeCurrentRequestId)
    }
}

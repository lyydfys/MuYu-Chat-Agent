package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PersistStage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.composition.conversationResetWriter
import com.muyuchat.mca.composition.hubInputWriter
import com.muyuchat.mca.presentation.afterClearChatGenerationStopped
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReplayOperationsTest {
    private val running = MainUiState(
        input = "draft",
        messages = listOf(ChatMessage(Role.USER, "keep until stopped")),
        activeChatSessionId = "session-a",
        isGenerating = true,
        generationPhase = GenerationPhase.DECODE,
        generationTokenProgress = TokenProgress(3, 12),
        generationPersistProgress = PersistProgress(PersistStage.WRITING, 3L, 12L),
        generationStats = RuntimeStats(loaded = true),
        engineLifecycle = AgentEngineLifecycle.GENERATING,
        contextCompressionPending = true,
        hubPage = 7,
        apiEnabled = true,
        statusMessage = "running",
    )

    @Test
    fun `clear cancels and stops before publishing then persists the committed reset`() = runBlocking {
        val store = UiStateStore(running)
        val events = mutableListOf<String>()
        val stoppedStats = RuntimeStats(loaded = true)

        clearConversationFor(
            store = store.conversationResetWriter(),
            clearManualCompressionRequest = { sessionId ->
                assertEquals("session-a", sessionId)
                events += "clear-request"
            },
            cancelGeneration = {
                assertSame(running, store.current)
                events += "cancel"
            },
            stopGenerationAndReadStats = {
                assertTrue(store.current.isGenerating)
                // A different domain may publish while the native stop is suspended.
                store.hubInputWriter().update { it.copy(repoInput = "concurrent catalog input") }
                events += "stop"
                stoppedStats
            },
            discardPendingAssistantOutput = {
                assertTrue(store.current.isGenerating)
                events += "discard"
            },
            invalidateConversationContext = {
                assertTrue(store.current.isGenerating)
                events += "invalidate"
            },
            persistChatSessions = {
                assertFalse(store.current.isGenerating)
                assertTrue(store.current.messages.isEmpty())
                assertEquals("concurrent catalog input", store.current.repoInput)
                events += "persist"
            },
        )

        assertEquals(listOf("clear-request", "cancel", "stop", "discard", "invalidate", "persist"), events)
        assertEquals(
            running.afterClearChatGenerationStopped(stoppedStats).copy(
                contextCompressionPending = false,
                repoInput = "concurrent catalog input",
            ),
            store.current,
        )
    }

    @Test
    fun `native stop failure propagates without clearing or persisting the conversation`() {
        val store = UiStateStore(running)
        val events = mutableListOf<String>()
        val failure = IllegalStateException("native stop failed")

        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                clearConversationFor(
                    store = store.conversationResetWriter(),
                    clearManualCompressionRequest = { events += "clear-request" },
                    cancelGeneration = { events += "cancel" },
                    stopGenerationAndReadStats = { throw failure },
                    discardPendingAssistantOutput = { events += "discard" },
                    invalidateConversationContext = { events += "invalidate" },
                    persistChatSessions = { events += "persist" },
                )
            }
        }

        assertSame(failure, actual)
        assertEquals(listOf("clear-request", "cancel"), events)
        assertSame(running, store.current)
    }

    @Test
    fun `the reset preserves legacy loaded unloaded and error lifecycle semantics`() {
        listOf(
            RuntimeStats(loaded = true),
            RuntimeStats(loaded = false),
            RuntimeStats(loaded = false, lastError = "runtime unavailable"),
        ).forEach { stats ->
            val store = UiStateStore(running)
            store.conversationResetWriter().update { it.afterConversationGenerationStopped(stats) }
            assertEquals(
                running.afterClearChatGenerationStopped(stats).copy(contextCompressionPending = false),
                store.current,
            )
        }
    }
}

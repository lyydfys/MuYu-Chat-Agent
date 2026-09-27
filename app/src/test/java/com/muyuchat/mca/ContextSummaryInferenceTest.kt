package com.muyuchat.mca

import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ContextSummaryInferenceTest {
    private val stats = RuntimeStats()

    @Test
    fun preservesRawWhitespaceAndDrainsPastTheFirstDoneBeforeReturning() = runBlocking {
        var reachedEnd = false
        var released = false
        val raw = collectContextSummary(flow {
            try {
                emit(GenerateEvent.Chunk(" \n", stats, reasoning = "separate reasoning"))
                emit(GenerateEvent.Chunk("{\"schemaVersion\":1}", stats))
                emit(GenerateEvent.Chunk("\n ", stats))
                emit(GenerateEvent.Done(stats))
                emit(GenerateEvent.Chunk("late duplicate", stats))
                emit(GenerateEvent.Error("late error", stats))
                reachedEnd = true
            } finally {
                released = true
            }
        }, isOwnerCurrent = { true })

        assertEquals(" \n{\"schemaVersion\":1}\n ", raw)
        assertTrue(reachedEnd)
        assertTrue(released)
    }

    @Test
    fun firstErrorCannotBeOverwrittenByALaterDoneAndStillReleasesTheFlow() = runBlocking {
        var released = false
        val result = runCatching {
            collectContextSummary(flow {
                try {
                    emit(GenerateEvent.Chunk("partial", stats))
                    emit(GenerateEvent.Error("native graph failed", stats, code = "NATIVE_ERROR"))
                    emit(GenerateEvent.Done(stats))
                } finally {
                    released = true
                }
            }, isOwnerCurrent = { true })
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("NATIVE_ERROR"))
        assertTrue(released)
    }

    @Test
    fun toolCallsAndMissingTerminalCannotBecomeSummaryText() = runBlocking {
        val toolCall = ChatToolCall("call-id", "unexpected_tool", "{}")
        val withTool = runCatching {
            collectContextSummary(
                flowOf(GenerateEvent.Chunk("{}", stats), GenerateEvent.Done(stats, listOf(toolCall))),
                isOwnerCurrent = { true }
            )
        }
        val unfinished = runCatching {
            collectContextSummary(flowOf(GenerateEvent.Chunk("{}", stats)), isOwnerCurrent = { true })
        }
        assertTrue(withTool.exceptionOrNull() is IllegalStateException)
        assertTrue(unfinished.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun exactBodyLimitIsAllowedAndOverflowDrainsWithoutReturningPartialText() = runBlocking {
        val body = "x".repeat(65_536)
        assertEquals(body, collectContextSummary(
            flowOf(GenerateEvent.Chunk(body, stats), GenerateEvent.Done(stats)), { true }
        ))
        var reachedEnd = false
        val overflow = runCatching {
            collectContextSummary(flow {
                emit(GenerateEvent.Chunk(body, stats))
                emit(GenerateEvent.Chunk("overflow", stats))
                emit(GenerateEvent.Done(stats))
                reachedEnd = true
            }, { true })
        }
        assertTrue(overflow.exceptionOrNull() is IllegalStateException)
        assertTrue(reachedEnd)
    }

    @Test
    fun cancellationAndStaleOwnerPropagateAndReleaseTheProducer() = runBlocking {
        var released = false
        var current = true
        val stale = runCatching {
            collectContextSummary(flow {
                try {
                    emit(GenerateEvent.Chunk("partial", stats))
                    current = false
                    emit(GenerateEvent.Done(stats))
                } finally {
                    released = true
                }
            }, { current })
        }
        assertTrue(stale.exceptionOrNull() is CancellationException)
        assertTrue(released)
        val cancelled = runCatching {
            collectContextSummary(flow {
                emit(GenerateEvent.Chunk("partial", stats))
                throw CancellationException("user cancelled")
            }, { true })
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }

    @Test
    fun typedOwnerCancellationErrorDoesNotBecomeAnOrdinaryModelFailure() = runBlocking {
        val result = runCatching {
            collectContextSummary(flowOf(
                GenerateEvent.Error("request owner stopped", stats, code = "REQUEST_CANCELLED")
            ), { true })
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}

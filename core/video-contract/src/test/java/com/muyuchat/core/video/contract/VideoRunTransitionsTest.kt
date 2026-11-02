package com.muyuchat.core.video.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive check of the frozen transition table against the §4.3.1 matrix. The expected sets
 * below are transcribed from the plan so any accidental table edit fails here.
 */
class VideoRunTransitionsTest {

    private val expected: Map<VideoRunState, Set<VideoRunState>> = mapOf(
        VideoRunState.DRAFT to setOf(VideoRunState.QUEUED),
        VideoRunState.QUEUED to setOf(
            VideoRunState.PREPARING, VideoRunState.CANCELLED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.PREPARING to setOf(
            VideoRunState.CONDITIONING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.CONDITIONING to setOf(
            VideoRunState.SAMPLING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.SAMPLING to setOf(
            VideoRunState.DECODING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.DECODING to setOf(
            VideoRunState.ENCODING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.ENCODING to setOf(
            VideoRunState.PUBLISHING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
        ),
        VideoRunState.PUBLISHING to setOf(
            VideoRunState.COMPLETED, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.RECONCILING,
        ),
        VideoRunState.CANCEL_REQUESTED to setOf(
            VideoRunState.CANCELLED, VideoRunState.INTERRUPTED, VideoRunState.RECONCILING,
        ),
    )

    private val runningPhases = setOf(
        VideoRunState.PREPARING, VideoRunState.CONDITIONING, VideoRunState.SAMPLING,
        VideoRunState.DECODING, VideoRunState.ENCODING, VideoRunState.PUBLISHING,
    )

    private val terminals = setOf(
        VideoRunState.COMPLETED, VideoRunState.CANCELLED, VideoRunState.FAILED, VideoRunState.INTERRUPTED,
    )

    @Test
    fun `every documented row matches exactly`() {
        for ((from, allowed) in expected) {
            assertEquals("row $from", allowed, VideoRunTransitions.allowed(from))
        }
    }

    @Test
    fun `unknown and reconciling recover only into running phases or terminals`() {
        val recoveryTargets = runningPhases + terminals
        for (state in listOf(VideoRunState.UNKNOWN, VideoRunState.RECONCILING)) {
            assertEquals(recoveryTargets, VideoRunTransitions.allowed(state))
        }
        // Silent requeue is forbidden: recovery must not resurrect a QUEUED job by itself.
        assertFalse(VideoRunTransitions.allows(VideoRunState.UNKNOWN, VideoRunState.QUEUED))
        assertFalse(VideoRunTransitions.allows(VideoRunState.RECONCILING, VideoRunState.DRAFT))
    }

    @Test
    fun `terminal states have no outgoing transitions`() {
        for (state in terminals) {
            assertTrue("terminal $state", VideoRunTransitions.isTerminal(state))
            assertTrue("terminal $state must be frozen", VideoRunTransitions.allowed(state).isEmpty())
        }
    }

    @Test
    fun `non-terminal states are correctly classified`() {
        val nonTerminals = VideoRunState.entries.toSet() - terminals
        for (state in nonTerminals) {
            assertFalse("state $state must not be terminal", VideoRunTransitions.isTerminal(state))
        }
    }

    @Test
    fun `happy path chain is fully legal`() {
        val path = listOf(
            VideoRunState.QUEUED,
            VideoRunState.PREPARING,
            VideoRunState.CONDITIONING,
            VideoRunState.SAMPLING,
            VideoRunState.DECODING,
            VideoRunState.ENCODING,
            VideoRunState.PUBLISHING,
            VideoRunState.COMPLETED,
        )
        for (i in 0 until path.size - 1) {
            assertTrue(
                "${path[i]} -> ${path[i + 1]} must be legal",
                VideoRunTransitions.allows(path[i], path[i + 1]),
            )
        }
    }

    @Test
    fun `cancel during sampling then interrupted is legal but sampling to cancelled is not`() {
        assertTrue(VideoRunTransitions.allows(VideoRunState.SAMPLING, VideoRunState.CANCEL_REQUESTED))
        assertTrue(VideoRunTransitions.allows(VideoRunState.CANCEL_REQUESTED, VideoRunState.CANCELLED))
        assertTrue(VideoRunTransitions.allows(VideoRunState.CANCEL_REQUESTED, VideoRunState.INTERRUPTED))
        // Cancel confirmation is a two-step contract: a running phase may not jump straight to
        // CANCELLED without CANCEL_REQUESTED being recorded first.
        assertFalse(VideoRunTransitions.allows(VideoRunState.SAMPLING, VideoRunState.CANCELLED))
    }

    @Test
    fun `progress never regresses within an attempt`() {
        // Coarse ordering of the pipeline; earlier phases must not be reachable from later ones.
        val order = listOf(
            VideoRunState.QUEUED,
            VideoRunState.PREPARING,
            VideoRunState.CONDITIONING,
            VideoRunState.SAMPLING,
            VideoRunState.DECODING,
            VideoRunState.ENCODING,
            VideoRunState.PUBLISHING,
        )
        for (laterIndex in order.indices) {
            for (earlierIndex in 0 until laterIndex) {
                assertFalse(
                    "regression ${order[laterIndex]} -> ${order[earlierIndex]} must be illegal",
                    VideoRunTransitions.allows(order[laterIndex], order[earlierIndex]),
                )
            }
        }
    }
}

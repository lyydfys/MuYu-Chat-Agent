package com.muyuchat.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningLoopGuardTest {
    @Test
    fun stopsHighlySimilarReasoningLoops() {
        val guard = ReasoningLoopGuard()
        var stopped = false
        repeat(8) {
            stopped = stopped || guard.shouldStop(
                "Wait, the system instruction says I am a large language model developed by Alibaba Cloud. " +
                    "The user prompt says I am a large language model developed by Alibaba Cloud. "
            )
        }

        assertTrue(stopped)
    }

    @Test
    fun doesNotStopShortNonRepeatingReasoning() {
        val guard = ReasoningLoopGuard()

        assertFalse(guard.shouldStop("First I identify the user's question. "))
        assertFalse(guard.shouldStop("Then I check the local assistant persona. "))
        assertFalse(guard.shouldStop("Finally I prepare a concise answer. "))
    }
}

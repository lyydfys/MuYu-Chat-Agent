package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowBudgetTest {
    @Test
    fun compactContextsUseTheirExactNativeWindow() {
        val context128 = localContextWindowBudget(128)
        assertEquals(128, context128.contextLength)
        assertEquals(16, context128.reservedOutputTokens)
        assertEquals(8, context128.headroomTokens)
        assertEquals(104, context128.promptBudgetTokens)

        val context256 = localContextWindowBudget(256)
        assertEquals(256, context256.contextLength)
        assertEquals(208, context256.promptBudgetTokens)
    }

    @Test
    fun standardContextsPreserveEstablishedSafetyMargins() {
        val context512 = localContextWindowBudget(512)
        assertEquals(512, context512.contextLength)
        assertEquals(64, context512.reservedOutputTokens)
        assertEquals(96, context512.headroomTokens)
        assertEquals(352, context512.promptBudgetTokens)
        assertEquals(256, context512.minimumPromptBudgetTokens)
    }

    @Test
    fun promptBudgetNeverFallsAtTheCompactToStandardBoundary() {
        val context511 = localContextWindowBudget(511)
        val context512 = localContextWindowBudget(512)

        assertEquals(352, context511.promptBudgetTokens)
        assertEquals(352, context512.promptBudgetTokens)
        var previous = localContextWindowBudget(1).promptBudgetTokens
        for (nCtx in 2..1_024) {
            val current = localContextWindowBudget(nCtx).promptBudgetTokens
            assertTrue("prompt budget fell at n_ctx=$nCtx: $previous -> $current", current >= previous)
            previous = current
        }
    }

    @Test
    fun arbitraryCustomContextIsNeverRoundedOrRaised() {
        assertEquals(8_190, localContextWindowBudget(8_190).contextLength)
    }

    @Test
    fun maximumRepresentableContextDoesNotOverflowItsBudget() {
        val budget = localContextWindowBudget(Int.MAX_VALUE)

        assertEquals(Int.MAX_VALUE, budget.contextLength)
        assertEquals(1_024, budget.reservedOutputTokens)
        assertEquals(96, budget.headroomTokens)
        assertEquals(Int.MAX_VALUE - 1_024 - 96, budget.promptBudgetTokens)
        assertTrue(budget.promptBudgetTokens > 0)
    }
}

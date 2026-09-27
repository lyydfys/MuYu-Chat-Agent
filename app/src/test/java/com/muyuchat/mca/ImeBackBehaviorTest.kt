package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBackBehaviorTest {
    @Test
    fun consumesBackWhenComposeReportsImeVisible() {
        assertTrue(
            shouldConsumeImeBack(
                composeImeVisible = true,
                layoutImeVisible = false,
                inputMethodActive = false
            )
        )
    }

    @Test
    fun consumesBackWhenMiuiDisplayFrameReportsImeVisible() {
        assertTrue(
            shouldConsumeImeBack(
                composeImeVisible = false,
                layoutImeVisible = true,
                inputMethodActive = false
            )
        )
    }

    @Test
    fun consumesBackWhenInsetsLagButInputMethodIsStillActive() {
        assertTrue(
            shouldConsumeImeBack(
                composeImeVisible = false,
                layoutImeVisible = false,
                inputMethodActive = true
            )
        )
    }

    @Test
    fun letsNormalBackNavigationContinueWhenImeIsInactive() {
        assertFalse(
            shouldConsumeImeBack(
                composeImeVisible = false,
                layoutImeVisible = false,
                inputMethodActive = false
            )
        )
    }

    @Test
    fun consumesBackForShortMiuiInsetsRaceWindow() {
        assertTrue(
            shouldConsumeImeBack(
                composeImeVisible = false,
                layoutImeVisible = false,
                inputMethodActive = false,
                imeVisibleRecently = true
            )
        )
    }
}

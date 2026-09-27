package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBackBehaviorTest {
    @Test
    fun firstBackHidesImeAndSecondBackReachesNavigation() {
        val state = ImeBackState()
        state.observe(true)
        assertTrue(state.consume())
        state.observe(false)
        assertFalse(state.consume())
    }

    @Test
    fun staleVisibleInsetsCannotConsumeTheSameHideTwice() {
        val state = ImeBackState()
        state.observe(true)
        assertTrue(state.consume())
        state.observe(true)
        assertFalse(state.consume())
    }

    @Test
    fun systemImeDismissalDoesNotSwallowTheNextBack() {
        val state = ImeBackState()
        state.observe(true)
        state.observe(false)
        assertFalse(state.consume())
    }

    @Test
    fun gestureThatStartsWithImeStillHidesItWhenInsetsCloseBeforeCommit() {
        val state = ImeBackState()
        state.observe(true)
        state.beginGesture()
        state.observe(false)
        assertTrue(state.consume())
        assertFalse(state.consume())
    }

    @Test
    fun cancelledGestureDoesNotConsumeLaterNavigation() {
        val state = ImeBackState()
        state.observe(true)
        state.beginGesture()
        state.cancelGesture()
        state.observe(false)
        assertFalse(state.consume())
    }

    @Test
    fun reopeningTheKeyboardCreatesANewHideOpportunity() {
        val state = ImeBackState()
        state.observe(true)
        assertTrue(state.consume())
        state.observe(false)
        state.observe(true)
        assertTrue(state.consume())
    }
}

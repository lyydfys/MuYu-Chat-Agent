package com.muyuchat.core.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McaSliderDetentsTest {
    @Test
    fun `continuous movement stays silent`() {
        val detents = McaSliderDetents(0f, 0f..1f, steps = 0)
        assertFalse(detents.onInput(0.5f))
        assertFalse(detents.onInput(1f))
        assertFalse(detents.onInput(0f))
    }

    @Test
    fun `one input event jumping over several detents requests only one tick`() {
        val detents = McaSliderDetents(0f, 0f..100f, steps = 99)
        assertTrue(detents.onInput(90f))
        assertFalse(detents.onInput(90f))
        assertTrue(detents.onInput(10f))
    }

    @Test
    fun `movement within the same detent stays silent`() {
        val detents = McaSliderDetents(20f, 0f..100f, steps = 9)
        assertFalse(detents.onInput(21f))
        assertFalse(detents.onInput(24f))
        assertTrue(detents.onInput(26f))
        assertFalse(detents.onInput(29f))
    }

    @Test
    fun `restoring an external value silently resets the detent baseline`() {
        val detents = McaSliderDetents(0f, 0f..100f, steps = 9)
        detents.syncValue(80f)
        assertFalse(detents.onInput(80f))
        assertTrue(detents.onInput(70f))
        detents.syncValue(70f)
        assertFalse(detents.onInput(70f))
    }

    @Test
    fun `unrelated recomposition cannot reset an input not yet acknowledged by its owner`() {
        val detents = McaSliderDetents(0f, 0f..100f, steps = 9)
        assertTrue(detents.onInput(80f))
        detents.syncValue(0f)
        assertFalse(detents.onInput(80f))
    }

    @Test
    fun `range edges are clamped and a collapsed range stays silent`() {
        val detents = McaSliderDetents(0f, 0f..100f, steps = 9)
        assertFalse(detents.onInput(-10f))
        assertTrue(detents.onInput(110f))
        assertFalse(detents.onInput(120f))

        val collapsed = McaSliderDetents(5f, 5f..5f, steps = 9)
        assertFalse(collapsed.onInput(5f))
    }
}

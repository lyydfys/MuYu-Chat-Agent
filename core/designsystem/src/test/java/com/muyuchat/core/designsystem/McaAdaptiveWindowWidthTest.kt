package com.muyuchat.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app-wide foldable/tablet width ladder.
 *
 * A breakpoint that silently shifts turns one page "wide" and another "narrow" on the very same
 * device, which is the exact inconsistency this ladder exists to remove. The thresholds match the
 * documented compact/medium/expanded window classes, so changing one is a product decision and
 * has to fail here first.
 */
class McaAdaptiveWindowWidthTest {

    @Test
    fun `phone widths stay compact`() {
        listOf(320, 360, 411, 480, 599).forEach { width ->
            assertEquals("width=$width", McaWindowWidth.COMPACT, mcaWindowWidthFor(width))
        }
    }

    @Test
    fun `six hundred dp is the medium breakpoint`() {
        assertEquals(McaWindowWidth.COMPACT, mcaWindowWidthFor(599))
        assertEquals(McaWindowWidth.MEDIUM, mcaWindowWidthFor(600))
        assertEquals(McaWindowWidth.MEDIUM, mcaWindowWidthFor(720))
        assertEquals(McaWindowWidth.MEDIUM, mcaWindowWidthFor(839))
    }

    @Test
    fun `eight hundred forty dp is the expanded breakpoint`() {
        assertEquals(McaWindowWidth.MEDIUM, mcaWindowWidthFor(839))
        assertEquals(McaWindowWidth.EXPANDED, mcaWindowWidthFor(840))
        assertEquals(McaWindowWidth.EXPANDED, mcaWindowWidthFor(1280))
    }

    @Test
    fun `only compact refuses a second pane`() {
        assertFalse(McaWindowWidth.COMPACT.supportsTwoPane())
        assertTrue(McaWindowWidth.MEDIUM.supportsTwoPane())
        assertTrue(McaWindowWidth.EXPANDED.supportsTwoPane())
    }

    @Test
    fun `content ceiling grows with the window but stays readable`() {
        val compact = mcaContentMaxWidthFor(McaWindowWidth.COMPACT)
        val medium = mcaContentMaxWidthFor(McaWindowWidth.MEDIUM)
        val expanded = mcaContentMaxWidthFor(McaWindowWidth.EXPANDED)

        assertTrue("compact ceiling must stay readable: $compact", compact.value <= 760f)
        assertTrue("medium must not be narrower than compact", medium >= compact)
        assertTrue("expanded must not be narrower than medium", expanded >= medium)
        // Even on a fully unfolded device the reading column must not span the whole window.
        assertTrue("expanded ceiling must cap the measure: $expanded", expanded.value <= 1_000f)
    }
}

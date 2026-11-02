package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R05：the menu height budget and the frozen 1x geometry.
 *
 * The budget function is the only place that turns "how much room the host really has" into a cap,
 * so a short screen / open keyboard / 2x font can be checked without a device. The rendered result
 * (screenshots for 360dp, large phone, tablet x font scale 1/1.5/2 x keyboard on/off) is NOT_RUN:
 * this round has no device attached.
 */
class ChatComposerMenuMetricsTest {

    @Test
    fun `1x geometry keeps the frozen baseline`() {
        assertEquals(238, ComposerMenuMetrics.SHEET_WIDTH_DP)
        assertEquals(86, ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP)
        assertEquals(10, ComposerMenuMetrics.SHEET_HORIZONTAL_PADDING_DP)
        assertEquals(12, ComposerMenuMetrics.SHEET_VERTICAL_PADDING_DP)
        assertEquals(4, ComposerMenuMetrics.SHEET_ROW_SPACING_DP)
        assertEquals(48, ComposerMenuMetrics.ROW_MIN_HEIGHT_DP)
        assertEquals(34, ComposerMenuMetrics.ROW_ICON_PLATE_DP)
        assertEquals(20, ComposerMenuMetrics.ROW_ICON_GLYPH_DP)
        assertEquals(1.5f, ComposerMenuMetrics.LARGE_FONT_SCALE, 0f)

        assertEquals(128, ComposerMenuMetrics.CAPSULE_WIDTH_DP)
        assertEquals(16, ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP)

        // Composer "more" circle: unchanged visible geometry.
        assertEquals(40, ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP)
        assertEquals(24, ComposerMenuMetrics.COMPOSER_MORE_GLYPH_DP)
    }

    @Test
    fun `a normal phone shows every row without scrolling`() {
        // 800dp viewport above the composer, 8 rows * 48dp + spacing + padding = 488dp of content.
        val budget = composerMenuHeightBudget(hostAvailableHeightDp = 800, contentHeightDp = 488)

        assertEquals(800 - ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP, budget.maxHeightDp)
        assertFalse(budget.scrollable)
        // The removed fixed 520dp cap would have been below the room the host really has: at 1x the
        // sheet is not capped at all now, which is why the default rendering stays identical.
        assertTrue(budget.maxHeightDp > 520)
    }

    @Test
    fun `a short screen with an open keyboard scrolls instead of clipping`() {
        // 640dp device with a 300dp IME: the IME-adjusted host leaves 340dp above the composer.
        val budget = composerMenuHeightBudget(hostAvailableHeightDp = 340, contentHeightDp = 488)

        assertEquals(340 - ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP, budget.maxHeightDp)
        assertTrue("the last row must be reachable by scrolling", budget.scrollable)
        assertTrue(
            "the fixed 520dp cap would not even fit this viewport",
            budget.maxHeightDp < 340
        )
    }

    @Test
    fun `two-x font content grows the sheet until the real viewport is the limit`() {
        val contentAtTwoX = 980
        val budget = composerMenuHeightBudget(hostAvailableHeightDp = 900, contentHeightDp = contentAtTwoX)

        assertEquals(900 - ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP, budget.maxHeightDp)
        assertTrue("content taller than the viewport must scroll", budget.scrollable)
        assertTrue("the cap follows the viewport, not a fixed 520dp", budget.maxHeightDp != 520)
    }

    @Test
    fun `a content that fits never claims to be scrollable`() {
        val budget = composerMenuHeightBudget(hostAvailableHeightDp = 600, contentHeightDp = 300)
        assertEquals(514, budget.maxHeightDp)
        assertFalse(budget.scrollable)
    }

    @Test
    fun `a tiny host still keeps one row visible`() {
        val budget = composerMenuHeightBudget(hostAvailableHeightDp = 60, contentHeightDp = 400)
        assertEquals(ComposerMenuMetrics.ROW_MIN_HEIGHT_DP, budget.maxHeightDp)
        assertTrue(budget.scrollable)

        val collapsed = composerMenuHeightBudget(hostAvailableHeightDp = 0, contentHeightDp = 0)
        assertEquals(ComposerMenuMetrics.ROW_MIN_HEIGHT_DP, collapsed.maxHeightDp)
    }

    @Test
    fun `an unbounded host leaves the content unconstrained`() {
        val budget = composerMenuHeightBudget(
            hostAvailableHeightDp = Int.MAX_VALUE,
            contentHeightDp = 488
        )
        assertEquals(Int.MAX_VALUE, budget.maxHeightDp)
        assertFalse("an unbounded host must not pretend to overflow", budget.scrollable)
    }

    @Test
    fun `research close target fits inside the panel and stays clear of the first pill`() {
        // Panel top padding + header padding * 2 + glyph + item spacing = where the first
        // selectable pill starts.
        val firstPillTopDp = ComposerMenuMetrics.CAPSULE_PADDING_DP +
            ComposerMenuMetrics.CAPSULE_HEADER_VERTICAL_PADDING_DP * 2 +
            ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP +
            ComposerMenuMetrics.CAPSULE_ITEM_SPACING_DP
        assertEquals(39, firstPillTopDp)

        val target = ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP
        assertTrue("the target must cover the visible 16dp glyph", target >= ComposerMenuMetrics.CAPSULE_CLOSE_GLYPH_DP)
        assertTrue("the target must stay clear of the first pill", target <= firstPillTopDp)
        assertTrue("the target must stay inside the panel width", target <= ComposerMenuMetrics.CAPSULE_WIDTH_DP)
    }

    @Test
    fun `research close target grows with the header at large font scales`() {
        // Measured header heights: 16dp glyph + 2dp * 2 padding at 1x, and the text-driven header
        // at 1.5x/2x. The target must always contain the whole header and never reach the pill.
        for (headerHeightDp in listOf(20, 28, 36, 44)) {
            val target = researchCloseTargetHeightDp(headerHeightDp)
            val headerBottomDp = ComposerMenuMetrics.CAPSULE_PADDING_DP + headerHeightDp
            val firstPillTopDp = headerBottomDp + ComposerMenuMetrics.CAPSULE_ITEM_SPACING_DP

            assertTrue(
                "header $headerHeightDp: the target must cover the header and the glyph",
                target >= headerBottomDp
            )
            assertTrue(
                "header $headerHeightDp: the target must stay clear of the first pill",
                target <= firstPillTopDp
            )
        }
        // 1x keeps the value the visual baseline was measured against.
        assertEquals(36, researchCloseTargetHeightDp(20))
        // At 2x the header is taller than 36dp, so the target follows it instead of leaving the
        // bottom of the glyph outside the hit area.
        assertTrue(researchCloseTargetHeightDp(36) > 36)
    }
}

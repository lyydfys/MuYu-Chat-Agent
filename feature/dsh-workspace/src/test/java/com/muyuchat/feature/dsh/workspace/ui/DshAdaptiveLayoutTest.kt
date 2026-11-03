package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import com.muyuchat.core.designsystem.mcaWindowWidthFor
import com.muyuchat.core.designsystem.supportsTwoPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure foldable/large-screen policy of the DSH workspace.
 *
 * The composables need a device (and a real unfolded foldable for the visual check), but the
 * breakpoints, the rail width, the reading ceiling, the floating-ball metrics and the WebView
 * viewport rewrite are all arithmetic and are pinned here. The 599/600/839/840 boundary cases are
 * the ones that decide whether a foldable gets the single-column phone layout or the two-pane one.
 */
class DshAdaptiveLayoutTest {

    // region breakpoints

    @Test
    fun `the dsh breakpoints are the app-wide 600 and 840 boundaries`() {
        assertEquals(McaWindowWidth.COMPACT, dshWindowWidthFor(599))
        assertEquals(McaWindowWidth.MEDIUM, dshWindowWidthFor(600))
        assertEquals(McaWindowWidth.MEDIUM, dshWindowWidthFor(839))
        assertEquals(McaWindowWidth.EXPANDED, dshWindowWidthFor(840))
    }

    @Test
    fun `common phone and foldable widths fall into the expected class`() {
        assertEquals(McaWindowWidth.COMPACT, dshWindowWidthFor(320))
        assertEquals(McaWindowWidth.COMPACT, dshWindowWidthFor(360))
        assertEquals(McaWindowWidth.COMPACT, dshWindowWidthFor(411))
        assertEquals(McaWindowWidth.EXPANDED, dshWindowWidthFor(1000))
        assertEquals(McaWindowWidth.EXPANDED, dshWindowWidthFor(1600))
    }

    @Test
    fun `the dsh wrapper never disagrees with the app-wide ladder`() {
        val widths = listOf(-1, 0, 240, 320, 411, 480, 599, 600, 601, 720, 838, 839, 840, 841, 1000, 2560)
        widths.forEach { width ->
            assertEquals(
                "dshWindowWidthFor($width) must follow mcaWindowWidthFor",
                mcaWindowWidthFor(width),
                dshWindowWidthFor(width)
            )
        }
    }

    @Test
    fun `only compact keeps the single column`() {
        assertFalse(dshSupportsRail(McaWindowWidth.COMPACT))
        assertTrue(dshSupportsRail(McaWindowWidth.MEDIUM))
        assertTrue(dshSupportsRail(McaWindowWidth.EXPANDED))
        assertFalse(McaWindowWidth.COMPACT.supportsTwoPane())
    }

    @Test
    fun `the wide viewport threshold is derived from the ladder`() {
        assertEquals(600, dshWideWindowMinDp)
        assertEquals(
            McaWindowWidth.MEDIUM,
            dshWindowWidthFor(dshWideWindowMinDp)
        )
    }

    // endregion

    // region rail

    @Test
    fun `no rail on a phone`() {
        assertEquals(0, dshWorkspaceRailWidthFor(360, twoPane = false))
        assertEquals(0, dshWorkspaceRailWidthFor(411, twoPane = false))
        assertEquals(0, dshWorkspaceRailWidthFor(1600, twoPane = false))
    }

    @Test
    fun `the rail never starves the web pane`() {
        // 600 - 420 = 180dp spare, capped at the app-wide 360dp rail ceiling.
        assertEquals(180, dshWorkspaceRailWidthFor(600, twoPane = true))
        assertEquals(360, dshWorkspaceRailWidthFor(840, twoPane = true))
        assertEquals(360, dshWorkspaceRailWidthFor(1600, twoPane = true))
    }

    @Test
    fun `a window without spare width gets no rail`() {
        assertEquals(0, dshWorkspaceRailWidthFor(420, twoPane = true))
        assertEquals(0, dshWorkspaceRailWidthFor(300, twoPane = true))
    }

    @Test
    fun `the rail keeps at least the minimum web pane width`() {
        val rail = dshWorkspaceRailWidthFor(599, twoPane = true)
        assertTrue(rail in 0..DSH_RAIL_MAX_WIDTH_DP)
        assertTrue(599 - rail >= DSH_MIN_WEB_PANE_WIDTH_DP)
    }

    // endregion

    // region content ceiling

    @Test
    fun `the console reading ceiling follows the app-wide content ceiling`() {
        listOf(McaWindowWidth.COMPACT, McaWindowWidth.MEDIUM, McaWindowWidth.EXPANDED).forEach { width ->
            assertEquals(
                mcaContentMaxWidthFor(width).value.toInt(),
                dshContentMaxWidthFor(width)
            )
        }
        assertEquals(720, dshContentMaxWidthFor(McaWindowWidth.COMPACT))
        assertEquals(780, dshContentMaxWidthFor(McaWindowWidth.MEDIUM))
        assertEquals(940, dshContentMaxWidthFor(McaWindowWidth.EXPANDED))
    }

    @Test
    fun `phone shells and dialogs keep their current width`() {
        assertNull(dshShellMaxWidthFor(McaWindowWidth.COMPACT))
        assertNull(dshDialogMaxWidthFor(McaWindowWidth.COMPACT))
    }

    @Test
    fun `wide shells and dialogs are capped instead of full bleed`() {
        assertEquals(560, dshShellMaxWidthFor(McaWindowWidth.MEDIUM))
        assertEquals(640, dshShellMaxWidthFor(McaWindowWidth.EXPANDED))
        assertEquals(560, dshDialogMaxWidthFor(McaWindowWidth.MEDIUM))
        assertEquals(640, dshDialogMaxWidthFor(McaWindowWidth.EXPANDED))
    }

    // endregion

    // region floating ball

    @Test
    fun `the phone ball keeps the existing metrics`() {
        assertEquals(
            DshBallMetrics(edgeMarginDp = 12, ballSizeDp = 48, maxHorizontalTravelDp = 0),
            dshBallMetricsFor(McaWindowWidth.COMPACT)
        )
    }

    @Test
    fun `wide balls get a bigger safe margin, a bigger target and a bounded drag band`() {
        val medium = dshBallMetricsFor(McaWindowWidth.MEDIUM)
        val expanded = dshBallMetricsFor(McaWindowWidth.EXPANDED)
        assertTrue(medium.edgeMarginDp > 12)
        assertTrue(expanded.edgeMarginDp > medium.edgeMarginDp)
        assertTrue(medium.ballSizeDp >= 48)
        assertTrue(expanded.ballSizeDp >= medium.ballSizeDp)
        assertTrue(medium.maxHorizontalTravelDp in 1..1000)
        assertTrue(expanded.maxHorizontalTravelDp > medium.maxHorizontalTravelDp)
    }

    @Test
    fun `an uncapped phone ball travels the whole pane`() {
        assertEquals(Float.POSITIVE_INFINITY, dshBallTravelCapPx(0, 3f), 0f)
        assertEquals(900f, dshBallCappedTravelPx(900f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(900f, dshBallCappedTravelPx(900f, 0f), 0f)
    }

    @Test
    fun `the wide cap bounds the drag range`() {
        assertEquals(960f, dshBallTravelCapPx(320, 3f), 0.0001f)
        assertEquals(960f, dshBallCappedTravelPx(2000f, 960f), 0f)
        assertEquals(400f, dshBallCappedTravelPx(400f, 960f), 0f)
    }

    @Test
    fun `a broken measure pins the ball instead of throwing it off the page`() {
        assertEquals(0f, dshBallCappedTravelPx(Float.NaN, 960f), 0f)
        assertEquals(0f, dshBallCappedTravelPx(-10f, 960f), 0f)
        assertEquals(0f, dshBallXOffsetPx(0.5f, Float.NaN, 132f, 33f, 33f, 900f), 0f)
        assertEquals(0f, dshBallXOffsetPx(0.5f, 1000f, 132f, 33f, 33f, Float.NaN), 0f)
        assertEquals(0f, dshBallXOffsetPx(0.5f, 1000f, 132f, Float.NaN, 33f, 900f), 0f)
    }

    @Test
    fun `the ball offset is arithmetically identical to the previous phone formula`() {
        // Phone arithmetic: 1000px pane, 132px ball (48dp @ 2.75), 33px inset (12dp @ 2.75).
        val paneWidthPx = 1000f
        val ballSizePx = 132f
        val edgePx = 33f
        val travelPx = paneWidthPx - ballSizePx - edgePx * 2
        listOf(0f, 0.15f, 0.25f, 0.5f, 0.9f, 1f).forEach { fraction ->
            assertEquals(
                "fraction $fraction must keep the phone placement",
                edgePx + travelPx * fraction,
                dshBallXOffsetPx(fraction, paneWidthPx, ballSizePx, edgePx, edgePx, travelPx),
                0.01f
            )
        }
    }

    @Test
    fun `the phone ball starts after the plain safe margin`() {
        assertEquals(12, dshBallStartInsetDp(railWidthDp = 0, edgeMarginDp = 12))
        assertEquals(200, dshBallStartInsetDp(railWidthDp = 180, edgeMarginDp = 20))
        assertEquals(384, dshBallStartInsetDp(railWidthDp = 360, edgeMarginDp = 24))
        assertEquals(0, dshBallStartInsetDp(railWidthDp = -40, edgeMarginDp = 0))
    }

    @Test
    fun `a capped travel keeps the wide ball off the rail and inside the page`() {
        // EXPANDED 1600dp at density 1: 360dp rail + 24dp inset, 56dp ball, 420dp drag cap.
        val paneWidthPx = 1600f
        val ballSizePx = 56f
        val startInsetPx = 384f
        val endInsetPx = 24f
        val capPx = 420f
        val fullTravelPx = paneWidthPx - ballSizePx - startInsetPx - endInsetPx
        val travelPx = dshBallCappedTravelPx(fullTravelPx, capPx)
        assertEquals(capPx, travelPx, 0f)
        val left = dshBallXOffsetPx(0f, paneWidthPx, ballSizePx, startInsetPx, endInsetPx, travelPx)
        val right = dshBallXOffsetPx(1f, paneWidthPx, ballSizePx, startInsetPx, endInsetPx, travelPx)
        val centre = dshBallXOffsetPx(0.5f, paneWidthPx, ballSizePx, startInsetPx, endInsetPx, travelPx)
        assertTrue("the ball may not park on the rail", left >= startInsetPx)
        assertTrue("the ball may not leave the page", right + ballSizePx <= paneWidthPx - endInsetPx)
        assertEquals(startInsetPx + fullTravelPx / 2f - capPx / 2f, left, 0.01f)
        assertEquals(startInsetPx + fullTravelPx / 2f, centre, 0.01f)
        assertEquals(left + capPx / 2f, centre, 0.01f)
    }

    @Test
    fun `a travel larger than the usable band is clamped to it`() {
        val paneWidthPx = 600f
        val ballSizePx = 132f
        val startInsetPx = 33f
        val endInsetPx = 33f
        val fullTravelPx = paneWidthPx - ballSizePx - startInsetPx - endInsetPx
        val tooLargePx = fullTravelPx * 4f
        assertEquals(
            startInsetPx + fullTravelPx,
            dshBallXOffsetPx(1f, paneWidthPx, ballSizePx, startInsetPx, endInsetPx, tooLargePx),
            0.01f
        )
    }

    @Test
    fun `out of range fractions are clamped to the page`() {
        val paneWidthPx = 1000f
        val ballSizePx = 132f
        val edgePx = 33f
        val travelPx = paneWidthPx - ballSizePx - edgePx * 2
        // fraction <= 0 parks the ball at the left inset, fraction >= 1 at the right one.
        assertEquals(edgePx, dshBallXOffsetPx(-3f, paneWidthPx, ballSizePx, edgePx, edgePx, travelPx), 0.01f)
        assertEquals(
            paneWidthPx - ballSizePx - edgePx,
            dshBallXOffsetPx(4f, paneWidthPx, ballSizePx, edgePx, edgePx, travelPx),
            0.01f
        )
        // A non-finite fraction falls back to the middle of the usable band.
        assertEquals(
            edgePx + travelPx / 2f,
            dshBallXOffsetPx(Float.NaN, paneWidthPx, ballSizePx, edgePx, edgePx, travelPx),
            0.01f
        )
    }

    // endregion

    // region webview viewport

    @Test
    fun `a phone never has its viewport rewritten`() {
        assertNull(dshViewportContentFor("width=390, initial-scale=1", McaWindowWidth.COMPACT))
        assertNull(dshViewportContentFor("width=device-width", McaWindowWidth.COMPACT))
        assertNull(dshViewportContentFor("", McaWindowWidth.COMPACT))
        assertNull(dshViewportContentFor(null, McaWindowWidth.COMPACT))
        assertFalse(dshNormalizesViewportWidth(McaWindowWidth.COMPACT))
        assertTrue(dshNormalizesViewportWidth(McaWindowWidth.MEDIUM))
        assertTrue(dshNormalizesViewportWidth(McaWindowWidth.EXPANDED))
    }

    @Test
    fun `an official device-width declaration is left untouched on a wide window`() {
        assertNull(dshViewportContentFor("width=device-width, initial-scale=1", McaWindowWidth.MEDIUM))
        assertNull(dshViewportContentFor("width=device-width", McaWindowWidth.EXPANDED))
    }

    @Test
    fun `a pinned phone width becomes device-width on a wide window`() {
        assertEquals(
            "width=device-width, initial-scale=1",
            dshViewportContentFor("width=390, initial-scale=1", McaWindowWidth.MEDIUM)
        )
        assertEquals(
            "initial-scale=1, width=device-width",
            dshViewportContentFor("initial-scale=1, width=412", McaWindowWidth.EXPANDED)
        )
        // Other declarations, their order and the absence of user-scalable stay as served.
        assertEquals(
            "width=device-width, initial-scale=1, maximum-scale=5, viewport-fit=cover",
            dshViewportContentFor(
                "width=430, initial-scale=1, maximum-scale=5, viewport-fit=cover",
                McaWindowWidth.EXPANDED
            )
        )
    }

    @Test
    fun `a declaration without a width token gets device-width prepended`() {
        assertEquals(
            "width=device-width, initial-scale=1",
            dshViewportContentFor("initial-scale=1", McaWindowWidth.MEDIUM)
        )
        assertEquals(
            "width=device-width",
            dshViewportContentFor("", McaWindowWidth.EXPANDED)
        )
        assertEquals(
            "width=device-width",
            dshViewportContentFor(null, McaWindowWidth.MEDIUM)
        )
    }

    @Test
    fun `the rewrite is whitespace and case tolerant`() {
        assertEquals(
            "width=device-width, user-scalable=yes",
            dshViewportContentFor("WIDTH = 390 , user-scalable=yes", McaWindowWidth.MEDIUM)
        )
        assertEquals(
            "width=device-width, user-scalable=yes",
            dshViewportContentFor("width=390,user-scalable=yes", McaWindowWidth.MEDIUM)
        )
        assertNull(dshViewportContentFor("width=DEVICE-WIDTH", McaWindowWidth.EXPANDED))
        assertNull(dshViewportContentFor("  width=device-width  ", McaWindowWidth.EXPANDED))
    }

    // endregion
}

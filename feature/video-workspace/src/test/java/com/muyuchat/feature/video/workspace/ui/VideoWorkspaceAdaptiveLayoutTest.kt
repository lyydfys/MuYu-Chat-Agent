package com.muyuchat.feature.video.workspace.ui

import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the foldable/tablet pass of the video workspace.
 *
 * The page is a long settings form followed by the workflow surface (canvas or simplified chain)
 * and the output preview. On MEDIUM it must stay one column with a readable form measure; on
 * EXPANDED the settings half moves beside the workflow half and the canvas keeps every dp the rail
 * does not take. COMPACT — the normal phone — must stay byte-identical: same single scrolling
 * column, same order, same collapsed onboarding cards, no new container.
 *
 * The breakpoints and the reading ceilings come from `core:designsystem` (`McaWindowWidth`,
 * `mcaContentMaxWidthFor`, `McaAdaptiveTwoPane`); this test only pins that the module reuses them.
 * Pure JVM: no Compose, no Android, no device.
 */
class VideoWorkspaceAdaptiveLayoutTest {

    @Test
    fun `only expanded windows get two panes`() {
        assertFalse(
            "a phone keeps the single column",
            videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.COMPACT).twoPane
        )
        assertFalse(
            "an unfolded foldable stays vertical and only gains a readable measure",
            videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.MEDIUM).twoPane
        )
        assertTrue(
            "a tablet/desktop window puts settings beside the workflow surface",
            videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.EXPANDED).twoPane
        )
    }

    @Test
    fun `compact answers no adaptation at all`() {
        val compact = videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.COMPACT)

        assertFalse("the phone must not gain a second pane", compact.twoPane)
        assertFalse(
            "the phone form column must not be wrapped in a new width container",
            compact.limitsFormWidth
        )
        assertFalse(
            "the phone must keep the collapsed tutorial/examples default",
            compact.expandUsageCardsByDefault
        )
        assertEquals("no rail width is spent on a phone", 0f, compact.settingsRailMaxWidth.value, 0.01f)
        assertEquals("no canvas floor is needed on a phone", 0f, compact.canvasMinWidth.value, 0.01f)
    }

    @Test
    fun `medium stays vertical and only caps the form column`() {
        val medium = videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.MEDIUM)

        assertFalse("medium is still one scrolling column", medium.twoPane)
        assertTrue("medium caps the settings/form cards", medium.limitsFormWidth)
        assertFalse(
            "medium keeps the collapsed tutorial/examples default",
            medium.expandUsageCardsByDefault
        )
        assertEquals("no rail on a vertical page", 0f, medium.settingsRailMaxWidth.value, 0.01f)
    }

    @Test
    fun `the content ceiling comes from the shared reading ladder`() {
        McaWindowWidth.entries.forEach { width ->
            assertEquals(
                "width=$width must reuse mcaContentMaxWidthFor instead of inventing a breakpoint",
                mcaContentMaxWidthFor(width).value,
                videoWorkspaceAdaptiveLayoutFor(width).formMaxWidth.value,
                0.01f
            )
        }
    }

    @Test
    fun `the canvas keeps the width the settings column does not need`() {
        val expanded = videoWorkspaceAdaptiveLayoutFor(McaWindowWidth.EXPANDED)

        assertTrue(
            "the canvas/output pane must never fall below the component's 420dp floor, was " +
                "${expanded.canvasMinWidth}",
            expanded.canvasMinWidth.value >= 420f
        )
        assertTrue(
            "the settings column must stay a column, was ${expanded.settingsRailMaxWidth}",
            expanded.settingsRailMaxWidth.value <= 400f
        )
        // McaAdaptiveTwoPane computes `rail = (window - minContentWidth).coerceIn(0, railMaxWidth)`,
        // so at the 840dp breakpoint the canvas floor is reserved first (840 - 520 = 320dp rail) and
        // the 360dp ceiling only applies to genuinely wide windows.
        val railWidthTheBreakpointAffords = 840f - expanded.canvasMinWidth.value
        assertTrue(
            "an 840dp window must afford the canvas floor inside the settings ceiling, was " +
                "rail=$railWidthTheBreakpointAffords for ${expanded.settingsRailMaxWidth}",
            railWidthTheBreakpointAffords <= expanded.settingsRailMaxWidth.value
        )
        assertTrue(
            "the expanded window starts with the onboarding cards open",
            expanded.expandUsageCardsByDefault
        )
        assertFalse(
            "the two-pane layout bounds the forms with the rail, not with a second ceiling",
            expanded.limitsFormWidth
        )
    }

    @Test
    fun `the screen routes the split through the shared adaptive components`() {
        val screen = source("ui/VideoWorkspaceScreen.kt")
        val panes = source("ui/VideoWorkspacePanes.kt")

        assertTrue(
            "the two-pane split must reuse McaAdaptiveTwoPane",
            panes.contains("McaAdaptiveTwoPane(")
        )
        assertTrue(
            "the window class must come from the shared ladder",
            screen.contains("videoWorkspaceAdaptiveLayoutFor(mcaWindowWidth())")
        )
        assertTrue(
            "the page must delegate its outer layout to the pane host",
            screen.contains("VideoWorkspacePanes(")
        )
        assertTrue(
            "the settings ceiling must be the shared component's rail bound",
            panes.contains("railMaxWidth = layout.settingsRailMaxWidth")
        )
        assertTrue(
            "the canvas floor must be the shared component's content bound",
            panes.contains("minContentWidth = layout.canvasMinWidth")
        )
        assertEquals(
            "one settings body definition serves the phone, MEDIUM and the rail",
            1,
            countMatches(screen, "val settingsColumn:")
        )
        assertEquals(
            "one workflow body definition serves every width class",
            1,
            countMatches(screen, "val workflowColumn:")
        )
    }

    @Test
    fun `the workflow pane is never capped and the cap only wraps the forms`() {
        val panes = source("ui/VideoWorkspacePanes.kt")

        assertEquals(
            "the only width cap on the page is the MEDIUM settings column",
            1,
            countMatches(panes, "widthIn(max =")
        )
        assertFalse(
            "the canvas/output pane must not be squeezed into a reading column",
            panes.contains("McaContentWidth")
        )
        val mediumBlock = panes
            .substringAfter("if (layout.limitsFormWidth)")
            .substringBefore("} else {")
        assertTrue(
            "the MEDIUM cap belongs to the form/settings half",
            mediumBlock.contains("widthIn(max = layout.formMaxWidth)")
        )
        assertTrue(mediumBlock.contains("settings()"))
        assertFalse(
            "the workflow half must stay full width on MEDIUM",
            mediumBlock.contains("workflow()")
        )
        val canvasSlot = panes
            .substringAfter("content = {")
            .substringBefore("workflow")
        assertFalse(
            "the canvas slot must carry no width ceiling",
            canvasSlot.contains("widthIn") || canvasSlot.contains("maxWidth")
        )
    }

    @Test
    fun `existing page anchors survive the split`() {
        val screen = source("ui/VideoWorkspaceScreen.kt")
        val panes = source("ui/VideoWorkspacePanes.kt")

        // Same tags the accessibility/engine/toggle contract tests address.
        listOf(
            "video.engine_settings",
            "video.chain_title",
            "video.prompt_fill_notice",
            "video.full_vae_toggle",
            "video.external_engine_toggle",
        ).forEach { tag ->
            assertTrue("testTag '$tag' must survive", screen.contains("testTag(\"$tag\")"))
        }
        assertTrue(
            "the page-level tag must stay on the outer container",
            panes.contains("testTag(\"video.workspace\")")
        )
        assertTrue(
            "the phone page must still be the single scrolling column",
            panes.contains(".verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "the onboarding cards must still receive the default (collapsed) value on phones",
            countMatches(screen, "initiallyExpanded = adaptiveLayout.expandUsageCardsByDefault") == 2
        )
        val usage = source("ui/VideoUsageSection.kt")
        assertEquals(
            "both onboarding cards must default to collapsed",
            2,
            countMatches(usage, "initiallyExpanded: Boolean = false")
        )
        assertEquals(
            "both onboarding cards must seed their saved state from the default",
            2,
            countMatches(usage, "mutableStateOf(initiallyExpanded)")
        )
    }

    private fun countMatches(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)
        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/video/workspace/$relative"),
            File("feature/video-workspace/src/main/java/com/muyuchat/feature/video/workspace/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) {
            "source file not found: $relative (cwd=${File(".").canonicalPath})"
        }
        return file.readText()
    }
}

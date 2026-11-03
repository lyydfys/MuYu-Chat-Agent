package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the foldable/tablet pass of the model hub.
 *
 * The requirement was "adapt the whole project to foldables, without touching the normal phone".
 * So two things must stay provable:
 *  - the width decision (when do the five sections leave the strip and become a rail, how wide may
 *    that rail get, and what reading ceiling does the section column get);
 *  - that COMPACT composes the previous phone layout and nothing else — no extra centring
 *    container, no rail, no second copy of the five destinations.
 *
 * The breakpoints themselves belong to `core:designsystem` (`McaWindowWidth`,
 * `mcaContentMaxWidthFor`) and are never re-derived here: these checks only pin that this module
 * reuses them. Pure JVM: no Compose, no Android, no device.
 */
class ModelHubAdaptiveLayoutTest {

    @Test
    fun `only medium and expanded windows get the two-pane rail`() {
        assertFalse(
            "a phone must keep the segmented strip",
            modelHubAdaptiveLayoutFor(McaWindowWidth.COMPACT).twoPane
        )
        assertTrue(
            "an unfolded foldable has room for a rail",
            modelHubAdaptiveLayoutFor(McaWindowWidth.MEDIUM).twoPane
        )
        assertTrue(
            "a tablet/desktop window has room for a rail",
            modelHubAdaptiveLayoutFor(McaWindowWidth.EXPANDED).twoPane
        )
    }

    @Test
    fun `compact answers no adaptation at all`() {
        val compact = modelHubAdaptiveLayoutFor(McaWindowWidth.COMPACT)

        assertFalse("the phone must not gain a rail", compact.twoPane)
        assertFalse(
            "the phone list must not be wrapped in an extra centring container",
            compact.limitsContentWidth
        )
        assertEquals("no rail width is spent on a phone", 0f, compact.railMaxWidth.value, 0.01f)
    }

    @Test
    fun `the content ceiling comes from the shared reading ladder`() {
        McaWindowWidth.entries.forEach { width ->
            assertEquals(
                "width=$width must reuse mcaContentMaxWidthFor instead of inventing a breakpoint",
                mcaContentMaxWidthFor(width).value,
                modelHubAdaptiveLayoutFor(width).contentMaxWidth.value,
                0.01f
            )
        }
    }

    @Test
    fun `the rail stays a rail and leaves a usable content column`() {
        val medium = modelHubAdaptiveLayoutFor(McaWindowWidth.MEDIUM)
        val expanded = modelHubAdaptiveLayoutFor(McaWindowWidth.EXPANDED)

        // Five 2-character labels: below 160dp they start ellipsising at 2.0x font, above 280dp the
        // rail stops being navigation and starts being a second content column.
        listOf("medium" to medium, "expanded" to expanded).forEach { (label, layout) ->
            assertTrue(
                "$label rail ceiling must fit the section labels, was ${layout.railMaxWidth}",
                layout.railMaxWidth.value >= 160f
            )
            assertTrue(
                "$label rail ceiling must stay navigation-sized, was ${layout.railMaxWidth}",
                layout.railMaxWidth.value <= 280f
            )
        }
        assertTrue(
            "a wider window may give the rail more room",
            expanded.railMaxWidth >= medium.railMaxWidth
        )
        // McaAdaptiveTwoPane reserves its own content minimum before spending width on the rail, so
        // at the expanded breakpoint (840dp) both curated values fit side by side.
        assertTrue(
            "rail + the component's 420dp content floor must fit the 840dp breakpoint, was " +
                "${expanded.railMaxWidth}",
            expanded.railMaxWidth.value + 420f <= 840f
        )
        assertEquals(
            "a phone spends no rail width",
            0f,
            modelHubAdaptiveLayoutFor(McaWindowWidth.COMPACT).railMaxWidth.value,
            0.01f
        )
    }

    @Test
    fun `the screen routes the rail through the shared adaptive components`() {
        val screen = sourceFile("ModelHubScreen.kt")

        assertTrue(
            "the two-pane split must reuse McaAdaptiveTwoPane",
            screen.contains("McaAdaptiveTwoPane(")
        )
        assertTrue(
            "the wide content column must reuse McaContentWidth",
            screen.contains("McaContentWidth(maxWidth = layout.contentMaxWidth)")
        )
        assertTrue(
            "the window class must come from the shared ladder",
            screen.contains("modelHubAdaptiveLayoutFor(mcaWindowWidth())")
        )
        assertTrue(
            "the rail must be addressable for UI tests",
            screen.contains("testTag(\"modelhub.section_rail\")")
        )
        assertEquals(
            "the rail and both phone strip layouts must be driven by the same five sections",
            3,
            countMatches(screen, "ModelHubSection.entries.forEach")
        )
    }

    @Test
    fun `the phone path returns before any adaptive container is composed`() {
        val paned = sourceFile("ModelHubScreen.kt")
            .substringAfter("private fun ModelHubPanedBody")
            .substringBefore("private fun ModelHubSectionRail")
        val phoneGuard = paned.indexOf("if (!layout.twoPane)")
        val twoPaneCall = paned.indexOf("McaAdaptiveTwoPane(")

        assertTrue("the phone guard must exist", phoneGuard >= 0)
        assertTrue("the two-pane call must exist", twoPaneCall >= 0)
        assertTrue(
            "COMPACT must hand the body straight through, before McaAdaptiveTwoPane is reached",
            phoneGuard < twoPaneCall
        )
        assertTrue(
            "COMPACT must keep the page column's own weight instead of a new container",
            paned.contains("body(modifier)")
        )
        val screen = sourceFile("ModelHubScreen.kt")
        assertTrue(
            "COMPACT must keep the weight(1f) body slot",
            screen.contains("body = sectionBody") &&
                screen.contains("modifier = Modifier.weight(1f)")
        )
        assertTrue(
            "the strip must be composed only when the rail is not",
            screen.contains("showSectionStrip = !adaptiveLayout.twoPane")
        )
    }

    @Test
    fun `the five section names and the phone strip are unchanged`() {
        val screen = sourceFile("ModelHubScreen.kt")

        listOf("本地", "云端", "推荐", "广场", "文件").forEach { title ->
            assertTrue("the section '$title' must keep its label", screen.contains("(\"$title\")"))
        }
        // The phone strip keeps its wrap-at-large-font behaviour; the rail reuses the same tab cell.
        assertTrue("phone strip must still wrap at 2.0x font", screen.contains("fontScale >= 1.5f"))
        assertTrue("phone strip must still wrap at 2.0x font", screen.contains("FlowRow("))
        assertTrue("phone strip must keep single-line labels", screen.contains("softWrap = false"))
        assertTrue(
            "the rail must reuse the phone tab cell instead of a second visual language",
            screen.substringAfter("private fun ModelHubSectionRail").contains("ModelHubTab(")
        )
    }

    @Test
    fun `section switching keeps the phone haptic and startInRecommended behaviour`() {
        val screen = sourceFile("ModelHubScreen.kt")

        assertTrue(
            "one selection path must serve both the strip and the rail",
            screen.contains("if (section != target) haptics.tick()")
        )
        assertTrue(
            "the deep-link/start-in-recommended entry must stay",
            screen.contains("mutableStateOf(if (startInRecommended) ModelHubSection.RECOMMENDED else ModelHubSection.LOCAL)")
        )
        assertTrue(
            "the start-in-recommended notice must stay",
            screen.contains("下载完成后自动加入本地模型")
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

    private fun sourceFile(fileName: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/$fileName"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate modelhub source file: $fileName")
    }
}

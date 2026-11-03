package com.muyuchat.feature.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import com.muyuchat.core.designsystem.mcaWindowWidthFor
import com.muyuchat.core.designsystem.supportsTwoPane
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chat shell's foldable/tablet decision.
 *
 * The important half is the COMPACT answer: a phone must come out of this function with no rail and
 * no reading ceiling, so nothing downstream can start centring or insetting an unchanged phone
 * layout. The wide answers only say "rail + the shared ceiling", never a number of their own.
 */
class ChatAdaptiveLayoutTest {

    @Test
    fun `phone stays single column with no rail and no reading ceiling`() {
        listOf(320, 395, 599).forEach { widthDp ->
            val layout = chatAdaptiveLayoutFor(mcaWindowWidthFor(widthDp))
            assertFalse("$widthDp dp must stay single-column", layout.twoPane)
            assertEquals("$widthDp dp must not reserve rail width", 0.dp, layout.railMaxWidth)
            assertNull("$widthDp dp must not add a reading ceiling", layout.readingMaxWidth)
        }
    }

    @Test
    fun `unfolded and expanded windows host the history rail`() {
        listOf(600, 720, 839, 840, 1024, 1280).forEach { widthDp ->
            val width = mcaWindowWidthFor(widthDp)
            val layout = chatAdaptiveLayoutFor(width)
            assertTrue("$widthDp dp must use two panes", layout.twoPane)
            assertTrue("$widthDp dp must reserve rail width", layout.railMaxWidth > 0.dp)
            assertEquals(
                "$widthDp dp must use the shared ladder's reading ceiling",
                mcaContentMaxWidthFor(width),
                layout.readingMaxWidth
            )
        }
    }

    @Test
    fun `the two-pane answer is the shared width class answer`() {
        listOf(320, 599, 600, 839, 840, 1600).forEach { widthDp ->
            val width = mcaWindowWidthFor(widthDp)
            assertEquals("$widthDp dp", width.supportsTwoPane(), chatAdaptiveLayoutFor(width).twoPane)
        }
    }

    @Test
    fun `reading ceiling modifier inserts nothing on a phone`() {
        val compact = chatAdaptiveLayoutFor(McaWindowWidth.COMPACT)
        assertSame(
            "COMPACT must hand back the receiver, not a width constraint",
            Modifier,
            Modifier.chatReadingCeiling(compact)
        )
        val expanded = chatAdaptiveLayoutFor(McaWindowWidth.EXPANDED)
        assertNotSame(
            "MEDIUM/EXPANDED must cap the reading width",
            Modifier,
            Modifier.chatReadingCeiling(expanded)
        )
    }

    @Test
    fun `chat does not redefine the shared breakpoints`() {
        val source = sourceFile("ChatAdaptiveLayout.kt")
        // The ladder itself is design-system owned: the chat decision consumes it, never re-reads
        // the raw window width and never compares against a literal dp threshold.
        assertTrue(source.contains("mcaContentMaxWidthFor("))
        assertTrue(source.contains("mcaWindowWidth()"))
        assertTrue(source.contains("McaWindowWidth.COMPACT"))
        assertFalse("chat must not read screenWidthDp itself", source.contains("screenWidthDp"))
        assertFalse("chat must not compare raw dp thresholds", Regex(">=\\s*\\d+").containsMatchIn(source))
    }

    private fun sourceFile(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}

/**
 * Source contract for the wiring the pure test cannot see: the two hosts are selected from the one
 * decision, the phone keeps its modal drawer and its existing full-width layout, and the wide-only
 * containers are the shared design-system ones.
 */
class ChatAdaptiveWiringContractTest {

    @Test
    fun `chat screen selects the host from the shared decision and keeps both containers`() {
        val screen = sourceFile("ChatScreen.kt")
        assertTrue(screen.contains("val adaptiveLayout = chatAdaptiveLayout()"))
        assertTrue(screen.contains("if (adaptiveLayout.twoPane)"))
        assertTrue(screen.contains("ChatWideRailHost("))
        // COMPACT keeps the exact modal drawer it had before the adaptation.
        assertTrue(screen.contains("ModalNavigationDrawer("))
        assertTrue(screen.contains("rail = { historyPane(true) }"))
        assertTrue(screen.contains("historyPane(false)"))
        // One history definition for both hosts: a session switch cannot open a second pane.
        assertEquals(1, screen.countOccurrences("ChatHistoryDrawer("))
        // The message column and the composer shell read the ceiling from the same decision.
        assertTrue(screen.contains("readingMaxWidth = adaptiveLayout.readingMaxWidth"))
        assertTrue(screen.contains("shellMaxWidth = adaptiveLayout.readingMaxWidth"))
        assertFalse("the screen must not grow its own width class", screen.contains("screenWidthDp"))
    }

    @Test
    fun `message column applies the ceiling only when it is present`() {
        val list = sourceFile("ChatMessageListSection.kt")
        assertTrue(list.contains("readingMaxWidth: Dp? = null"))
        assertTrue(list.contains("Modifier.widthIn(max = readingMaxWidth)"))
        assertTrue(list.contains("Alignment.TopStart else Alignment.TopCenter"))
        // The price of a centred wide column must not be the phone's scroll wiring.
        assertTrue(list.contains(".nestedScroll(nestedScrollConnection)"))
    }

    @Test
    fun `composer shell is capped but its window anchored layers are not`() {
        val composer = sourceFile("ChatComposerScreens.kt")
        assertTrue(composer.contains("shellMaxWidth: Dp? = null"))
        assertTrue(composer.contains("Modifier.widthIn(max = maxWidth)"))
        // The action menu is anchored to the window edge and must keep full width for its scrim.
        assertTrue(composer.contains("CompactInputActionMenu("))
        assertTrue(composer.contains("ResearchModeFloatingCapsule("))
    }

    @Test
    fun `one history panel serves the drawer and the permanent rail`() {
        val drawer = sourceFile("ChatHistoryDrawerScreens.kt")
        val adaptive = sourceFile("ChatAdaptiveLayout.kt")
        assertTrue(drawer.contains("rail: Boolean = false"))
        assertTrue(drawer.contains("ChatHistorySurface("))
        assertTrue(drawer.contains("Modifier.edgeSwipeBack {"))
        assertTrue(adaptive.contains("ModalDrawerSheet("))
        assertTrue(adaptive.contains("if (rail)"))
        assertTrue(adaptive.contains("McaAdaptiveTwoPane("))
    }

    @Test
    fun `image prompt bar keeps its internals and only gains an outer ceiling`() {
        val workspace = sourceFile("ImageWorkspaceScreens.kt")
        assertTrue(workspace.contains("ChatReadingWidth(adaptiveLayout)"))
        assertTrue(workspace.contains(".chatReadingCeiling(adaptiveLayout)"))
        // The translation panel and the prompt bar itself are pinned elsewhere; they must not learn
        // about the chat ceiling, so the only change is the container around them.
        assertFalse(sourceFile("ImagePromptBarScreens.kt").contains("chatReadingCeiling"))
        assertFalse(sourceFile("ImagePromptBarScreens.kt").contains("ChatReadingWidth"))
        assertFalse(sourceFile("ImagePromptTranslationPanel.kt").contains("chatReadingCeiling"))
        assertFalse(sourceFile("ImagePromptTranslationPanel.kt").contains("ChatReadingWidth"))
    }

    private fun sourceFile(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val index = indexOf(needle, from)
            if (index < 0) return count
            count += 1
            from = index + needle.length
        }
    }
}

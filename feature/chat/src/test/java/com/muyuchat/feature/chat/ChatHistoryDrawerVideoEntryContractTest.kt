package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the frozen drawer entry order of the feature rail (proposal
 * mca-native-video-workflow-v1 §9.1): 图片 → Agent → DSH → 视频 → 最近. The video entry sits
 * directly below DSH with its own test tag and accessibility label; nothing else moves.
 */
class ChatHistoryDrawerVideoEntryContractTest {

    @Test
    fun `feature rail order is image agent dsh video recent`() {
        val source = source("ChatHistoryDrawerScreens.kt")
        val railStart = entryBlockStart(source)

        val imageEntry = source.indexOf("\"图片\"", railStart)
        val agentEntry = source.indexOf("\"Agent\"", railStart)
        val dshEntry = source.indexOf("\"DSH\"", railStart)
        val videoEntry = source.indexOf("\"视频\"", railStart)
        val recentHeading = source.indexOf("\"最近\"", railStart)

        assertTrue("all five rail anchors must exist", listOf(imageEntry, agentEntry, dshEntry, videoEntry, recentHeading).all { it >= 0 })
        assertTrue("image must precede agent", imageEntry < agentEntry)
        assertTrue("agent must precede dsh", agentEntry < dshEntry)
        assertTrue("dsh must precede video", dshEntry < videoEntry)
        assertTrue("video must precede the recent heading", videoEntry < recentHeading)
    }

    @Test
    fun `video entry carries its test tag and accessibility label`() {
        val source = source("ChatHistoryDrawerScreens.kt")
        val videoEntry = source.indexOf("\"视频\"", entryBlockStart(source))
        assertTrue("video entry must exist", videoEntry >= 0)

        val block = source.substring(videoEntry, source.indexOf("Spacer", videoEntry).let { if (it < 0) source.length else it })
        assertTrue("video entry needs the drawer.video_workflow test tag", block.contains("testTag = \"drawer.video_workflow\""))
        assertTrue("video entry needs its accessibility label", block.contains("accessibilityLabel = \"打开视频工作区\""))
        assertEquals("video opens via the dedicated callback", 1, Regex("onClick = onOpenVideoWorkspace").findAll(block).count())
    }

    /**
     * The rail lives after the app-menu header and before the LazyColumn of recent sessions.
     * The anchor is the image entry's own label, walked back to its `AppFeatureButton(` call:
     * a one-line literal match broke as soon as the entry was formatted across lines, which is a
     * formatting change, not a rail-order change. Every ordering assertion below is unchanged.
     */
    private fun entryBlockStart(source: String): Int {
        val imageText = source.indexOf("text = \"图片\"")
        check(imageText >= 0) { "rail head entry not found" }
        val anchor = source.lastIndexOf("AppFeatureButton(", imageText)
        check(anchor >= 0) { "rail head entry not found" }
        return anchor
    }

    private fun source(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/chat/$fileName"),
            File("feature/chat/src/main/java/com/muyuchat/feature/chat/$fileName"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "source file not found: $fileName" }
        return file.readText()
    }
}

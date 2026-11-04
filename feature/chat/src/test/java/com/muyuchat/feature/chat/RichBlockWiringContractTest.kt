package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本块的接线回归：安全解析器必须真的用于助手消息，且默认关闭时既有 Markdown 路径不变。
 *
 * 仅源码锚点（Compose 无法在纯 JVM 组合）；解析规则本身由 [RichBlockSanitizerTest] 覆盖。
 */
class RichBlockWiringContractTest {

    @Test
    fun `rich blocks are gated by the state flag and default to off`() {
        val state = source("ChatScreenHeadTypes.kt")
        assertTrue(state.contains("val richBlocksEnabled: Boolean = false"))
        val list = source("ChatMessageListSection.kt")
        assertTrue(list.contains("richBlocksEnabled = state.richBlocksEnabled"))
        val bubble = source("ChatMessageBubbleScreens.kt")
        assertTrue(bubble.contains("richBlocksEnabled = richBlocksEnabled"))
    }

    @Test
    fun `assistant rich path runs sanitizer and renders only the safe block model`() {
        val blocks = source("ChatBubbleMessageBlocks.kt")
        assertTrue(blocks.contains("sanitizeRichBlocks(message.content)"))
        assertTrue(blocks.contains("richBlocksActive && richResult != null && richResult.detected"))
        assertTrue(blocks.contains("RichBlockView("))
        assertTrue(blocks.contains("该消息只包含不可显示的 HTML 内容"))
        // 关闭或空结果时仍走原 Markdown / 角色卡路径。
        assertTrue(blocks.contains("else if (visibleHtmlPreview != null)"))
        assertTrue(blocks.contains("PagedAssistantRichText("))
    }

    @Test
    fun `the rich view never hosts a webview or executes html`() {
        val view = source("RichBlockView.kt")
        assertFalse(view.contains("WebView("))
        assertFalse(view.contains("android.webkit"))
        assertFalse(view.contains("jsoup"))
        assertTrue(view.contains("RichBlock.Section"))
        assertTrue(view.contains("RichBlock.Table"))
        assertTrue(view.contains("未识别块"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/chat/$name"),
            File("feature/chat/src/main/java/com/muyuchat/feature/chat/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "source not found: $name (cwd=${File(".").canonicalPath})" }
        return file.readText()
    }
}

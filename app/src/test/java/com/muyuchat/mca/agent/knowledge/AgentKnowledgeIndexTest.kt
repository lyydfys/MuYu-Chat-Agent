package com.muyuchat.mca.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval contract for the bundled operation knowledge.
 *
 * The index must be boring in exactly two ways: a query about a covered app returns *that*
 * document (never an unrelated one), and whatever it hands to a prompt stays inside the size
 * budget. Both are asserted here through the reader-lambda seam, so no assets, no Context and no
 * Robolectric are involved.
 */
class AgentKnowledgeIndexTest {

    @Test
    fun `a wechat query returns the wechat document only`() {
        val index = fixtureIndex()

        val hits = index.search("帮我在微信里给张三发条消息")

        assertEquals(1, hits.size)
        assertEquals("wechat", hits.single().id)
        assertTrue(hits.single().excerpt.contains("找联系人并发消息"))
        assertTrue(hits.single().score > 0)
    }

    @Test
    fun `an android settings query returns the settings document`() {
        val index = fixtureIndex()

        val hits = index.search("怎么打开开发者选项和无线调试")

        assertEquals("settings", hits.first().id)
    }

    @Test
    fun `a browser query returns the browser document`() {
        val index = fixtureIndex()

        val hits = index.search("网页一直加载不出来，弹窗关不掉")

        assertEquals("browser", hits.first().id)
    }

    @Test
    fun `a query never returns more than two documents`() {
        val index = fixtureIndex()

        // Mentions every covered area at once; the limit still wins.
        val hits = index.search("微信 设置 浏览器 开发者 弹窗 聊天记录 无障碍 验证码 群")

        assertTrue("expected at most 2 hits, got ${hits.size}", hits.size <= 2)
        assertEquals(hits.map { it.id }.distinct().size, hits.size)
    }

    @Test
    fun `an unmatched query returns nothing instead of the closest document`() {
        val index = fixtureIndex()

        assertTrue(index.search("帮我算一下 17 乘 23").isEmpty())
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `limit zero or negative returns nothing`() {
        val index = fixtureIndex()

        assertTrue(index.search("微信", limit = 0).isEmpty())
        assertTrue(index.search("微信", limit = -3).isEmpty())
    }

    @Test
    fun `every excerpt stays within the 2000 character budget`() {
        val longBody = buildString {
            appendLine("# 微信（WeChat）常用操作")
            repeat(400) { appendLine("第 ${it + 1} 行：这是一段用于撑大文档的中文占位内容。") }
        }
        val index = AgentKnowledgeIndex.load { name ->
            if (name == "wechat") longBody else null
        }

        val hit = index.search("微信").single()

        assertTrue("excerpt is ${hit.excerpt.length} chars", hit.excerpt.length <= 2_000)
        assertTrue(hit.excerpt.endsWith("…"))
        // The stored body stays complete; only the projection is bounded.
        assertTrue(index.promptText("微信").length <= AgentKnowledgeIndex.MAX_PROMPT_CHARS)
    }

    @Test
    fun `prompt text is empty when nothing matches and bounded when it does`() {
        val index = fixtureIndex()

        assertEquals("", index.promptText("完全无关的问题"))
        val text = index.promptText("微信 发消息")
        assertTrue(text.isNotBlank())
        assertTrue(text.length <= AgentKnowledgeIndex.MAX_PROMPT_CHARS)
    }

    @Test
    fun `a missing or unreadable document is skipped without failing the index`() {
        val index = AgentKnowledgeIndex.load { name ->
            when (name) {
                "wechat" -> "# 微信\n找联系人"
                "settings" -> throw IllegalStateException("asset unreadable")
                else -> null
            }
        }

        assertEquals(listOf("wechat"), index.documentIds())
        assertEquals("wechat", index.search("微信").single().id)
        assertTrue(index.search("开发者选项").isEmpty())
    }

    @Test
    fun `the title comes from the first heading and falls back to the document id`() {
        val withHeading = AgentKnowledgeIndex.load { "# 自定义标题\n正文" }
        assertEquals("自定义标题", withHeading.search("微信").single().title)

        val withoutHeading = AgentKnowledgeIndex.load { "正文没有标题" }
        assertEquals("wechat", withoutHeading.search("微信").single().title)
    }

    /** Index whose reader returns a representative body per document name. */
    private fun fixtureIndex(): AgentKnowledgeIndex = AgentKnowledgeIndex.load { name ->
        when (name) {
            "wechat" -> """
                # 微信（WeChat）常用操作

                ## 找联系人并发消息

                目标：给某个指定的人发一条消息。
                步骤：点顶部搜索框，输入昵称，点中头像进入聊天页，输入内容后点发送。
                注意：备注名和微信名可能不一致。

                ## 翻聊天记录

                目标：找到较早的消息。步骤：在会话里向上滑动，或用「查找聊天记录」。
            """.trimIndent()

            "settings" -> """
                # Android 设置

                ## 开启开发者选项

                目标：让「开发者选项」出现。步骤：「关于手机」里连续点击「版本号」7 次。

                ## 开启无线调试

                目标：通过 Wi-Fi 调试。步骤：开发者选项里打开「无线调试」，用配对码配对。

                ## 电池优化白名单

                目标：避免后台被杀。步骤：应用信息里的电池改为「不限制」。
            """.trimIndent()

            "browser" -> """
                # 网页操作通用要点

                ## 等待页面加载完成

                目标：避免点空。步骤：看进度条消失、占位块消失。

                ## 处理弹窗与浮层

                目标：先清掉遮挡。步骤：找「同意」「关闭」或右上角 ×。

                ## 处理反爬与验证提示

                目标：及时停手。步骤：看到滑块、验证码就停止重复尝试。
            """.trimIndent()

            else -> null
        }
    }
}

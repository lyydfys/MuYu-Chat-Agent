package com.muyuchat.mca.agent.knowledge

import com.muyuchat.core.agent.contract.AgentKnowledgePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the production [AgentKnowledgePort] adapter.
 *
 * The index decides relevance; this adapter only has to be a well-behaved port: text or `""`, never
 * a throw, cheap on the second call, and safe when the assets are missing. The cases use the same
 * reader-lambda seam as the index tests, so no assets and no Android runtime are involved.
 */
class BundledAgentKnowledgePortTest {

    @Test
    fun `a covered goal returns that document's excerpt`() {
        val port = BundledAgentKnowledgePort { fixtureIndex() }

        val knowledge = port.relevantKnowledge("帮我在微信里给张三发条消息")

        assertTrue("微信目标要命中微信知识", knowledge.contains("找联系人并发消息"))
        assertTrue(knowledge.isNotBlank())
    }

    @Test
    fun `an uncovered goal returns an empty string`() {
        val port = BundledAgentKnowledgePort { fixtureIndex() }

        assertEquals("", port.relevantKnowledge("帮我算一下 2 加 2 等于几"))
        assertEquals("", port.relevantKnowledge(""))
        assertEquals("", port.relevantKnowledge("   "))
    }

    @Test
    fun `a failing or empty index degrades to no knowledge instead of throwing`() {
        val throwing = BundledAgentKnowledgePort { error("assets missing") }
        assertEquals("", throwing.relevantKnowledge("打开微信"))

        val empty = BundledAgentKnowledgePort { AgentKnowledgeIndex.load { null } }
        assertEquals("", empty.relevantKnowledge("打开微信"))
    }

    @Test
    fun `the index is loaded lazily and only once`() {
        var loads = 0
        val port = BundledAgentKnowledgePort {
            loads += 1
            fixtureIndex()
        }

        assertEquals("构造 port 不能读资产", 0, loads)
        port.relevantKnowledge("打开微信")
        assertEquals(1, loads)
        port.relevantKnowledge("网页打不开")
        assertEquals("第二次查询复用同一个 index", 1, loads)
    }

    @Test
    fun `repeated queries for the same goal are answered from the cache`() {
        // The index is loaded once; a mutable body proves a second query does not re-search it.
        var currentBody = "# 微信常用操作\n\n## 找联系人并发消息\n\n第一次的内容。"
        val port = BundledAgentKnowledgePort {
            AgentKnowledgeIndex.load { name -> if (name == "wechat") currentBody else null }
        }

        val first = port.relevantKnowledge("帮我在微信里发消息")
        assertTrue(first.contains("第一次的内容"))

        currentBody = "# 微信常用操作\n\n## 找联系人并发消息\n\n第二次的内容。"
        val second = port.relevantKnowledge("帮我在微信里发消息")

        assertEquals("同一个 goal 必须命中缓存，不能重新检索", first, second)
        assertTrue(second.contains("第一次的内容"))
    }

    @Test
    fun `different goals may return different documents`() {
        val port = BundledAgentKnowledgePort { fixtureIndex() }

        val wechat = port.relevantKnowledge("帮我在微信里发消息")
        val browser = port.relevantKnowledge("网页一直加载不出来")

        assertTrue(wechat.contains("找联系人并发消息"))
        assertTrue(browser.contains("等待页面加载完成"))
        assertNotEquals(wechat, browser)
    }

    @Test
    fun `the default port is the silent one`() {
        assertEquals("", AgentKnowledgePort.NONE.relevantKnowledge("打开微信"))
    }

    private fun fixtureIndex(): AgentKnowledgeIndex = AgentKnowledgeIndex.load { name ->
        when (name) {
            "wechat" -> """
                # 微信常用操作

                ## 找联系人并发消息

                目标：给指定的人发一条消息。步骤：点搜索框，输入昵称，点头像进入聊天页，输入内容后点发送。
            """.trimIndent()

            "browser" -> """
                # 网页操作通用要点

                ## 等待页面加载完成

                目标：避免点空。步骤：看进度条消失、占位块消失。
            """.trimIndent()

            else -> null
        }
    }
}

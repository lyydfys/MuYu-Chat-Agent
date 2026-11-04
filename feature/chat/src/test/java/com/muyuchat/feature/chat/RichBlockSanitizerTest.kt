package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全富文本块解析合同。
 *
 * 现实约束：酒馆预设常输出 HTML（details/status/table/div），但执行模型生成的 JS 绝不允许运行。
 * 这里固定「结构保留、样式丢弃、脚本绝不执行/不出现在结果里」的边界，以及各种上限与降级行为。
 */
class RichBlockSanitizerTest {

    @Test
    fun `whitelisted structure keeps its text and drops tags`() {
        val result = sanitizeRichBlocks(
            "<div><b>粗体</b> 与 <i>斜体</i> 正文</div>"
        )
        assertTrue(result.detected)
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("粗体"))
        assertTrue(text.contains("斜体"))
        assertTrue(text.contains("正文"))
        assertFalse(text.contains("<div"))
        assertFalse(text.contains("<b>"))
    }

    @Test
    fun `script blocks are removed with their code`() {
        val result = sanitizeRichBlocks("<p>前</p><script>alert(1)</script><p>后</p>")
        val text = richBlocksPlainText(result.blocks)
        assertTrue(result.detected)
        assertTrue(text.contains("前"))
        assertTrue(text.contains("后"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("script"))
        assertTrue(result.droppedTags >= 1)
    }

    @Test
    fun `a message containing only script has no readable blocks`() {
        val result = sanitizeRichBlocks("<script>alert(1)</script>")
        assertTrue(result.detected)
        assertTrue(result.blocks.isEmpty())
        assertTrue(result.emptyButDetected)
    }

    @Test
    fun `style iframe object embed form input and button are removed`() {
        val html = """
            <style>body{color:red}</style>
            <iframe src="https://evil.example/x"></iframe>
            <object data="x.swf"></object>
            <embed src="x.swf">
            <form action="/steal"><input value="x"><button>提交</button></form>
            <p>唯一正文</p>
        """.trimIndent()
        val result = sanitizeRichBlocks(html)
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("唯一正文"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("提交"))
        assertFalse(text.contains("evil.example"))
    }

    @Test
    fun `event attributes are dropped and cannot produce interaction`() {
        val result = sanitizeRichBlocks(
            """<div onclick="alert(1)" onmouseover="alert(2)">安全文字</div>"""
        )
        val text = richBlocksPlainText(result.blocks)
        assertEquals("安全文字", text)
        assertFalse(text.contains("onclick"))
        assertFalse(text.contains("alert"))
        // 结果模型里没有属性/链接字段，控制器不会给任何可交互标签。
        assertTrue(
            result.blocks.all { block ->
                block !is RichBlock.Section || block.body.none { child -> child is RichBlock.Unknown }
            }
        )
    }

    @Test
    fun `javascript and data urls are stripped from links and images`() {
        val result = sanitizeRichBlocks(
            """
                <a href="javascript:alert(1)">点我</a>
                <img src="data:text/html;base64,PHNjcmlwdD4=" alt="图">
                <a href="https://example.com/ok">允许文本</a>
            """.trimIndent()
        )
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("点我"))
        assertTrue(text.contains("允许文本"))
        assertFalse(text.lowercase().contains("javascript:"))
        assertFalse(text.contains("data:text/html"))
        assertFalse(text.contains("https://")) // 属性不进入结果；只有可读文本
    }

    @Test
    fun `details and summary become a collapsible section`() {
        val result = sanitizeRichBlocks(
            "<details><summary>角色状态</summary><status>体力：80</status>心情：愉快</details>"
        )
        assertTrue(result.detected)
        val section = result.blocks.filterIsInstance<RichBlock.Section>().firstOrNull()
        assertNotNull(section)
        assertEquals("角色状态", section!!.title)
        val text = richBlocksPlainText(listOf(section))
        assertTrue(text.contains("体力"))
        assertTrue(text.contains("心情"))
    }

    @Test
    fun `nested status inside details is extracted as a nested section`() {
        val result = sanitizeRichBlocks(
            "<details><summary>详情</summary><status title=\"状态\">HP: 10</status></details>"
        )
        val outer = result.blocks.filterIsInstance<RichBlock.Section>().first()
        val inner = outer.body.filterIsInstance<RichBlock.Section>().firstOrNull()
        assertNotNull(inner)
        assertEquals("状态", inner!!.title)
        assertTrue(richBlocksPlainText(listOf(inner)).contains("HP"))
    }

    @Test
    fun `table rows are parsed without keeping markup`() {
        val result = sanitizeRichBlocks(
            """
                <table><thead><tr><th>项目</th><th>数值</th></tr></thead>
                <tbody><tr><td>体力</td><td>80</td></tr><tr><td>心情</td><td>好</td></tr></tbody></table>
            """.trimIndent()
        )
        val table = result.blocks.filterIsInstance<RichBlock.Table>().firstOrNull()
        assertNotNull(table)
        assertEquals(listOf("项目", "数值"), table!!.rows[0])
        assertEquals(listOf("体力", "80"), table.rows[1])
        assertEquals(3, table.rows.size)
    }

    @Test
    fun `ordered and unordered lists become readable lines`() {
        val result = sanitizeRichBlocks("<ul><li>苹果</li><li>梨</li></ul><ol><li>第一步</li></ol>")
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("• 苹果"))
        assertTrue(text.contains("• 梨"))
        assertTrue(text.contains("1. 第一步"))
    }

    @Test
    fun `headings and br survive as plain text lines`() {
        val result = sanitizeRichBlocks("<h1>标题</h1><div>第一行<br>第二行</div>")
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("第一行\n第二行"))
    }

    @Test
    fun `key value lines are recognized for two column rendering`() {
        val result = sanitizeRichBlocks("<status>体力：80\n心情：愉快</status>")
        val section = result.blocks.filterIsInstance<RichBlock.Section>().first()
        val pairs = section.body.filterIsInstance<RichBlock.KeyValue>()
        assertEquals(listOf("体力" to "80", "心情" to "愉快"), pairs.map { it.key to it.value })
    }

    @Test
    fun `unknown tags degrade to an expandable raw text block instead of disappearing`() {
        val result = sanitizeRichBlocks("<customblock data-x=\"1\">当前状态：平静</customblock>")
        val unknown = result.blocks.filterIsInstance<RichBlock.Unknown>().firstOrNull()
        assertNotNull(unknown)
        assertEquals("customblock", unknown!!.tagName)
        assertTrue(unknown.rawText.contains("当前状态"))
        assertFalse(unknown.rawText.contains("data-x"))
    }

    @Test
    fun `StatusBlock style custom status tags render as a status section`() {
        val result = sanitizeRichBlocks("<StatusBlock>体力：80\n心情：愉快</StatusBlock>")
        val section = result.blocks.filterIsInstance<RichBlock.Section>().firstOrNull()
        assertNotNull(section)
        assertEquals("状态", section!!.title)
        assertTrue(richBlocksPlainText(listOf(section)).contains("体力"))
    }

    @Test
    fun `deep nesting is truncated without crashing`() {
        val html = buildString {
            repeat(40) { append("<div>") }
            append("深层文字")
            repeat(40) { append("</div>") }
        }
        val result = sanitizeRichBlocks(html)
        assertTrue(result.detected)
        assertTrue(result.truncated)
        assertTrue(richBlocksPlainText(result.blocks).contains("深层文字"))
    }

    @Test
    fun `tag count limit stops parsing without throwing`() {
        val html = buildString {
            append("<div>")
            repeat(600) { append("<br>") }
            append("结尾")
            append("</div>")
        }
        val result = sanitizeRichBlocks(html)
        assertTrue(result.detected)
        assertTrue(result.truncated)
        assertTrue(richBlocksPlainText(result.blocks).contains("结尾"))
    }

    @Test
    fun `input over the limit is truncated and marked`() {
        val html = "<div>" + "字".repeat(200_100) + "</div>"
        val result = sanitizeRichBlocks(html)
        assertTrue(result.truncated)
        assertTrue(result.blocks.isNotEmpty())
    }

    @Test
    fun `plain markdown is not mistaken for html`() {
        val markdown = """
            # 标题
            **加粗** 和 `行内代码`
            - 列表项
            <https://example.com/autolink>
        """.trimIndent()
        val result = sanitizeRichBlocks(markdown)
        assertFalse(result.detected)
        assertTrue(result.blocks.isEmpty())
    }

    @Test
    fun `blank and empty content are not detected`() {
        assertFalse(sanitizeRichBlocks("").detected)
        assertFalse(sanitizeRichBlocks("   \n\t ").detected)
        assertFalse(containsRichHtml(""))
    }

    @Test
    fun `sanitizing the same source twice is idempotent`() {
        val html = "<details><summary>状态</summary><status>体力：80</status></details>"
        val first = sanitizeRichBlocks(html)
        val second = sanitizeRichBlocks(html)
        assertEquals(first.blocks, second.blocks)
        assertEquals(first.truncated, second.truncated)
        // 投影后的纯文本不再含标签，再次解析不会再被识别为富文本。
        val projection = richBlocksPlainText(first.blocks)
        assertFalse(sanitizeRichBlocks(projection).detected)
    }

    @Test
    fun `entities are decoded once and never re-trigger markup`() {
        val result = sanitizeRichBlocks("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>")
        val text = richBlocksPlainText(result.blocks)
        assertTrue(text.contains("<script>alert(1)</script>"))
        assertEquals(1, Regex("alert").findAll(text).count())
    }

    @Test
    fun `containsRichHtml recognizes tags but not comparisons or autolinks`() {
        assertTrue(containsRichHtml("<details><summary>x</summary></details>"))
        assertTrue(containsRichHtml("<StatusBlock>y</StatusBlock>"))
        assertTrue(containsRichHtml("<script>z</script>"))
        assertFalse(containsRichHtml("1 < 2 and 3 > 2"))
        assertFalse(containsRichHtml("<https://example.com>"))
    }
}

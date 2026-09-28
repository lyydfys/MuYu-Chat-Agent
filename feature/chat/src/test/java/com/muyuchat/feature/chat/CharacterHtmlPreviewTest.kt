package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterHtmlPreviewTest {
    @Test
    fun fullHtmlDocumentShowsStatusAndSceneWithoutCssOrTags() {
        val source = """
            ```html
            <!DOCTYPE html><html><head><style>.status { color: #fff; }</style></head>
            <body><details open><summary style="cursor:pointer">[状态栏] 点击折叠/展开</summary>
            <pre>时间：2025年6月5日 18:32
            地点：江阳社区</pre></details><p>她走进客厅。</p></body></html>
            ```
        """.trimIndent()

        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.contains("时间：2025年6月5日 18:32"))
        assertTrue(preview.contains("地点：江阳社区"))
        assertTrue(preview.contains("她走进客厅。"))
        assertFalse(preview.contains("color:"))
        assertFalse(preview.contains("<summary"))
        assertFalse(preview.contains("```"))
    }

    @Test
    fun mixedNarrativeAndStatusFragmentRemainsReadable() {
        val source = "她去洗把脸。\n<details open><summary>状态栏</summary><div><pre>亲密度：16/200<br>地点：客厅</pre></div></details>"
        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.startsWith("她去洗把脸。"))
        assertTrue(preview.contains("状态栏"))
        assertTrue(preview.contains("亲密度：16/200"))
        assertTrue(preview.contains("地点：客厅"))
        assertFalse(preview.contains("<details"))
    }

    @Test
    fun accidentalFenceInsideStatusMarkupDoesNotExposeTags() {
        val source = """
            她去洗把脸。
            <details open><summary style="color:red">状态栏
            ```html
            </summary>
            ```
            <div>时间：18:32</div></details>
        """.trimIndent()

        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.contains("状态栏"))
        assertTrue(preview.contains("时间：18:32"))
        assertFalse(preview.contains("<summary"))
        assertFalse(preview.contains("```"))
    }

    @Test
    fun multilineStyledStatusWithFencedClosingTagReadsLikeAStatus() {
        val source = """
            <details open>
            <summary
            style="cursor:pointer;margin-bottom:10px;padding:8px 12px;
            background:#e91e63;color:#fff;border-radius:8px;text-align:center;">
            【状态栏】 点击折叠/展开
            ```html
            </summary>
            ```
            <div style="padding:18px;background:rgba(20,0,40,0.72)"><pre>时间：18:32
            地点：客厅</pre></div></details>
            她关上了门。
        """.trimIndent()

        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.contains("【状态栏】 点击折叠/展开"))
        assertTrue(preview.contains("时间：18:32"))
        assertTrue(preview.contains("地点：客厅"))
        assertTrue(preview.contains("她关上了门。"))
        assertFalse(preview.contains("<summary"))
        assertFalse(preview.contains("background:"))
        assertFalse(preview.contains("```html"))

        val blocks = requireNotNull(characterHtmlBlocks(source))
        val status = blocks.filterIsInstance<CharacterHtmlBlock.Details>().single()
        assertTrue(status.initiallyOpen)
        assertEquals("【状态栏】 点击折叠/展开", status.title)
        assertTrue(status.content.contains("时间：18:32"))
        assertTrue(status.content.contains("地点：客厅"))
        assertEquals("她关上了门。", (blocks.last() as CharacterHtmlBlock.Text).content)
    }

    @Test
    fun multipleDetailsKeepNarrativeAndIndependentOpenState() {
        val source = "开场。\n<details open><summary>状态</summary><p>体力 50</p></details>\n中场。\n<details><summary>背包</summary><p>钥匙</p></details>\n结束。"
        val blocks = requireNotNull(characterHtmlBlocks(source))

        assertEquals(listOf("开场。", "中场。", "结束。"), blocks.filterIsInstance<CharacterHtmlBlock.Text>().map { it.content })
        val details = blocks.filterIsInstance<CharacterHtmlBlock.Details>()
        assertEquals(listOf("状态", "背包"), details.map { it.title })
        assertEquals(listOf(true, false), details.map { it.initiallyOpen })
        assertTrue(details[0].content.contains("体力 50"))
        assertTrue(details[1].content.contains("钥匙"))
    }

    @Test
    fun codeExampleAfterStatusRemainsACodeExample() {
        val source = """
            <details open><summary>状态栏</summary><p>时间：18:32</p></details>
            她说：这是一个示例。
            ```kotlin
            val message = "<details>keep this literal</details>"
            ```
            ```html
            <div class="example">example markup</div>
            ```
            ```css
            .example { color: red; }
            ```
        """.trimIndent()

        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.contains("状态栏"))
        assertTrue(preview.contains("时间：18:32"))
        assertTrue(preview.contains("```kotlin\nval message = \"<details>keep this literal</details>\"\n```"))
        assertTrue(preview.contains("```html\n<div class=\"example\">example markup</div>\n```"))
        assertTrue(preview.contains("```css\n.example { color: red; }\n```"))
    }

    @Test
    fun unfinishedStreamingHtmlHidesPartialTagsAndCss() {
        assertEquals(
            "",
            characterHtmlPreview("```html\n<!DOCTYPE html><html><head><style>.status { color: red")
        )
        assertEquals(
            "她进屋。",
            characterHtmlPreview("她进屋。\n<details open><summary style=\"color:red\"")
        )
        assertEquals(
            "她进屋。",
            characterHtmlPreview("她进屋。\n<details open>\n<summary\n style=\"cursor:pointer;\n background:#e91e63")
        )
    }

    @Test
    fun structuralHtmlFragmentsPreviewWithoutClosingTags() {
        val preview = requireNotNull(characterHtmlPreview("<section><p>对白</p><span>她点头"))

        assertTrue(preview.contains("对白"))
        assertTrue(preview.contains("她点头"))
        assertFalse(preview.contains("<section"))
    }

    @Test
    fun separateCssFenceDoesNotBlockHtmlPreview() {
        val source = """
            ```html
            <!DOCTYPE html><html><body><div class="status">状态栏</div></body></html>
            ```
            ```css
            .status { color: red; }
            ```
        """.trimIndent()

        val preview = requireNotNull(characterHtmlPreview(source))

        assertTrue(preview.contains("状态栏"))
        assertFalse(preview.contains("color:"))
        assertFalse(preview.contains("```"))
    }

    @Test
    fun executableOrRemoteHtmlContentIsNeverIncludedInPreview() {
        val preview = requireNotNull(characterHtmlPreview(
            "<div>对话</div><script>alert('bad')</script><iframe src='https://example.com'>secret</iframe><img src='https://example.com/a' alt='插图'>"
        ))

        assertTrue(preview.contains("对话"))
        assertTrue(preview.contains("插图"))
        assertFalse(preview.contains("alert"))
        assertFalse(preview.contains("secret"))
        assertFalse(preview.contains("https://"))
    }

    @Test
    fun ordinaryMarkdownAndOtherCodeStayOnTheirExistingPath() {
        assertTrue(characterHtmlHasStatus("<details><summary>状态栏</summary><p>体力 50</p></details>"))
        assertFalse(characterHtmlHasStatus("```html\n<details><summary>example</summary></details>\n```"))
        assertFalse(characterHtmlHasStatus("```html\n<!DOCTYPE html><html><body><details><summary>example</summary></details></body></html>\n```"))
        assertNull(characterHtmlPreview("Use <div> for layout."))
        assertNull(characterHtmlPreview("    <p>This is an indented HTML example.</p>"))
        assertNull(characterHtmlPreview("    <details>Indented markup example</details>"))
        assertNull(characterHtmlPreview("```html\n<div>example</div>\n```"))
        assertNull(characterHtmlPreview("Here is an HTML example:\n```html\n<p>Hello</p>\n```"))
        assertNull(characterHtmlPreview("Here is a full HTML example:\n```html\n<!DOCTYPE html><html><body><p>Hello</p></body></html>\n```"))
        assertNull(characterHtmlPreview("```kotlin\nval markup = \"<div>x</div>\"\n```"))
    }
}

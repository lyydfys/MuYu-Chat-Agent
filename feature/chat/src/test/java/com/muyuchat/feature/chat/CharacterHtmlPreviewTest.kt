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
    fun unfinishedStreamingHtmlHidesPartialTagsAndCss() {
        assertEquals(
            "",
            characterHtmlPreview("```html\n<!DOCTYPE html><html><head><style>.status { color: red")
        )
        assertEquals(
            "她进屋。",
            characterHtmlPreview("她进屋。\n<details open><summary style=\"color:red\"")
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
        assertNull(characterHtmlPreview("Use <div> for layout."))
        assertNull(characterHtmlPreview("    <p>This is an indented HTML example.</p>"))
        assertNull(characterHtmlPreview("    <details>Indented markup example</details>"))
        assertNull(characterHtmlPreview("```html\n<div>example</div>\n```"))
        assertNull(characterHtmlPreview("Here is an HTML example:\n```html\n<p>Hello</p>\n```"))
        assertNull(characterHtmlPreview("```kotlin\nval markup = \"<div>x</div>\"\n```"))
    }
}

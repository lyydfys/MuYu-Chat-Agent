package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "上下文来源" panel naming rules.
 *
 * The user reported the panel printing `958c34f-…` where a world book name belongs ("之前让你改了一
 * 次，你没有改彻底"), so these cases pin every branch of the id → name resolution: recorded name,
 * live lookup, a book that is no longer in the library, and a trace that predates recorded names.
 * A raw id must never be the title.
 */
class ContextTraceOwnerLabelTest {

    private val liveWorldBook = mapOf("958c34f0-1111-2222-3333-444444444444" to "雨夜世界书")
    private val liveKnowledgeBase = mapOf("kh-1" to "项目资料库")

    @Test
    fun `the name recorded in the trace wins over the live lookup`() {
        val source = worldSource(ownerId = "958c34f0-1111-2222-3333-444444444444", ownerName = "旧名字")

        val label = contextTraceOwnerLabel(source, isKnowledge = false, names = liveNames())

        assertEquals("旧名字", label.title)
        assertTrue(label.detail.startsWith("世界书 · 范围：全局"))
    }

    @Test
    fun `a deleted book still shows its recorded name instead of its uuid`() {
        val source = worldSource(ownerId = "deleted-book-id", ownerName = "雨夜世界书")

        val label = contextTraceOwnerLabel(source, isKnowledge = false, names = liveNames())

        assertEquals("雨夜世界书（已不在当前资料库）", label.title)
        assertFalse(label.title.contains("deleted-book-id"))
    }

    @Test
    fun `an old trace without a recorded name falls back to the live library`() {
        val source = worldSource(ownerId = "958c34f0-1111-2222-3333-444444444444", ownerName = null)

        val label = contextTraceOwnerLabel(source, isKnowledge = false, names = liveNames())

        assertEquals("雨夜世界书", label.title)
        assertFalse("a live hit is not 'gone'", label.title.contains("已不在"))
    }

    @Test
    fun `an unknown book is named readably and never by its raw id`() {
        val source = worldSource(ownerId = "958c34f0-9999-8888-7777-666666666666", ownerName = null)

        val label = contextTraceOwnerLabel(source, isKnowledge = false, names = liveNames())

        assertEquals("未命名世界书（记录中无名称）", label.title)
        assertFalse(label.title.contains("958c34f0"))
        // The ids survive only as short breadcrumbs in the detail line.
        assertTrue(label.detail, label.detail.contains("#000001"))
        assertFalse(label.detail.contains("entry-958c34f0-0000"))
    }

    @Test
    fun `without any library at all the panel does not claim the book is gone`() {
        val source = worldSource(ownerId = "unknown-id", ownerName = "只存在于记录里的书")

        val label = contextTraceOwnerLabel(
            source,
            isKnowledge = false,
            names = ContextTraceLibraryNames()
        )

        assertEquals("只存在于记录里的书", label.title)
        assertEquals("未命名世界书", contextTraceOwnerLabel(
            worldSource(ownerId = "unknown-id", ownerName = null),
            isKnowledge = false,
            names = ContextTraceLibraryNames()
        ).title)
    }

    @Test
    fun `knowledge sources show the base name and cite document and chunk only as short ids`() {
        val source = knowledgeSource(
            ownerId = "kh-1",
            documentId = "doc-1234567890",
            chunkId = "chunk-abcdef123456"
        )

        val label = contextTraceOwnerLabel(source, isKnowledge = true, names = liveNames())

        assertEquals("项目资料库", label.title)
        assertTrue(label.detail, label.detail.startsWith("知识库 · 文档 #"))
        assertTrue(label.detail, label.detail.contains("知识块 #"))
        assertFalse(label.detail.contains("doc-1234567890"))
    }

    @Test
    fun `scope markers stay readable in the detail line`() {
        assertEquals("全局", contextTraceScopeLabel("global"))
        assertEquals("角色", contextTraceScopeLabel("assistant"))
        assertEquals("对话", contextTraceScopeLabel("chat"))
        assertEquals("未知范围", contextTraceScopeLabel(null))
        assertEquals("未知范围", contextTraceScopeLabel("   "))
        assertEquals("其他范围", contextTraceScopeLabel("imported"))
    }

    @Test
    fun `ids are shortened in detail lines and never printed blank`() {
        assertEquals("#444444", shortTraceId("958c34f0-1111-2222-3333-444444444444"))
        assertEquals("#short", shortTraceId("short"))
        assertEquals("未知", shortTraceId(null))
        assertEquals("未知", shortTraceId("  "))
    }

    @Test
    fun `the parser reads the recorded book name and tolerates traces without one`() {
        val withName = parseContextAssemblyTrace(traceJson(bookName = "雨夜世界书"))
        val withoutName = parseContextAssemblyTrace(traceJson(bookName = null))

        assertEquals("雨夜世界书", withName?.selectedWorldBooks?.single()?.ownerName)
        assertNull(withoutName?.selectedWorldBooks?.single()?.ownerName)
        // Both traces still parse; only the optional name differs.
        assertEquals("entry-1", withoutName?.selectedWorldBooks?.single()?.sourceId)
    }

    private fun liveNames() = ContextTraceLibraryNames(
        worldBookNames = liveWorldBook,
        knowledgeBaseNames = liveKnowledgeBase
    )

    private fun worldSource(ownerId: String, ownerName: String?) = ContextTraceSource(
        sourceId = "entry-958c34f0-0000-0000-0000-000000000001",
        ownerId = ownerId,
        scope = "global",
        excerpt = "雨夜的旧城街道，霓虹招牌在积水里晃动。",
        estimatedTokens = 24,
        reason = "keyword",
        ownerName = ownerName
    )

    private fun knowledgeSource(ownerId: String, documentId: String, chunkId: String) = ContextTraceSource(
        sourceId = chunkId,
        ownerId = ownerId,
        scope = documentId,
        excerpt = "第三章 部署说明",
        estimatedTokens = 12,
        reason = "lexical_match",
        lexicalScore = 3
    )

    private fun traceJson(bookName: String?): String {
        val nameField = if (bookName == null) "" else ",\"bookName\":\"$bookName\""
        return "{" +
            "\"schemaVersion\":1,\"retrievalType\":\"lexical\"," +
            "\"rolePromptHash\":\"hash-role\",\"assembledHash\":\"hash-context\",\"tokenBudget\":1024," +
            "\"selectedWorldBookSources\":[{\"bookId\":\"958c34f0-1111-2222-3333-444444444444\"," +
            "\"entryId\":\"entry-1\",\"scope\":\"global\",\"excerpt\":\"雨夜\",\"estimatedTokens\":24," +
            "\"reason\":\"keyword\"" + nameField + "}]," +
            "\"skippedWorldBookSources\":[],\"selectedKnowledgeSources\":[],\"skippedKnowledgeSources\":[]," +
            "\"omittedSourceCount\":0}"
    }
}

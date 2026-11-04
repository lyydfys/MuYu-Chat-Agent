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

    @Test
    fun `a knowledge base uses its recorded name and the document title in the detail`() {
        val source = knowledgeSource(
            ownerId = "kh-1",
            documentId = "doc-1234567890",
            chunkId = "chunk-abcdef123456",
            baseName = "项目资料库",
            documentName = "部署说明.md"
        )

        val label = contextTraceOwnerLabel(source, isKnowledge = true, names = liveNames())

        assertEquals("项目资料库", label.title)
        assertTrue(label.detail, label.detail.contains("文档 部署说明.md"))
        assertFalse(
            "the document id must not be printed when a title exists",
            label.detail.contains("doc-1234567890")
        )
        assertTrue(label.detail, label.detail.contains("知识块 #"))
    }

    @Test
    fun `a deleted knowledge base still shows its recorded name instead of its uuid`() {
        val source = knowledgeSource(
            ownerId = "deleted-base-id",
            documentId = "doc-1",
            chunkId = "chunk-1",
            baseName = "旧资料库",
            documentName = "旧文档.md"
        )

        val label = contextTraceOwnerLabel(source, isKnowledge = true, names = liveNames())

        assertEquals("旧资料库（已不在当前资料库）", label.title)
        assertFalse(label.title.contains("deleted-base-id"))
        assertTrue(label.detail, label.detail.contains("旧文档.md"))
    }

    @Test
    fun `an old knowledge trace without names falls back to the live base`() {
        val source = knowledgeSource(
            ownerId = "kh-1",
            documentId = "doc-1234567890",
            chunkId = "chunk-abcdef123456",
            baseName = null,
            documentName = null
        )

        val label = contextTraceOwnerLabel(source, isKnowledge = true, names = liveNames())

        assertEquals("项目资料库", label.title)
        assertFalse("a live hit is not 'gone'", label.title.contains("已不在"))
        assertTrue("no document title recorded, so a short id stands in", label.detail.contains("文档 #"))
    }

    @Test
    fun `without any library a knowledge base is not marked gone`() {
        val withRecordedName = knowledgeSource(
            ownerId = "unknown-base",
            documentId = "doc-9",
            chunkId = "chunk-9",
            baseName = "只存在于记录里的资料库",
            documentName = null
        )
        val withoutName = knowledgeSource(
            ownerId = "unknown-base",
            documentId = "doc-9",
            chunkId = "chunk-9",
            baseName = null,
            documentName = null
        )

        assertEquals(
            "只存在于记录里的资料库",
            contextTraceOwnerLabel(withRecordedName, isKnowledge = true, names = ContextTraceLibraryNames()).title
        )
        assertEquals(
            "未命名知识库",
            contextTraceOwnerLabel(withoutName, isKnowledge = true, names = ContextTraceLibraryNames()).title
        )
    }

    @Test
    fun `the parser reads recorded knowledge names and tolerates traces without them`() {
        val withNames = parseContextAssemblyTrace(knowledgeTraceJson("项目资料库", "部署说明.md"))
        val withoutNames = parseContextAssemblyTrace(knowledgeTraceJson(null, null))

        val parsedWith = withNames?.selectedKnowledge?.single()
        val parsedWithout = withoutNames?.selectedKnowledge?.single()

        assertEquals("项目资料库", parsedWith?.ownerName)
        assertEquals("部署说明.md", parsedWith?.documentName)
        assertNull(parsedWithout?.ownerName)
        assertNull(parsedWithout?.documentName)
        // Both traces still parse; only the optional names differ.
        assertEquals("chunk-1", parsedWithout?.sourceId)
    }

    private fun liveNames() = ContextTraceLibraryNames(
        worldBookNames = liveWorldBook,
        knowledgeBaseNames = liveKnowledgeBase
    )

    private fun knowledgeTraceJson(baseName: String?, documentName: String?): String {
        val nameFields = buildString {
            if (baseName != null) append(",\"knowledgeBaseName\":\"").append(baseName).append('"')
            if (documentName != null) append(",\"documentName\":\"").append(documentName).append('"')
        }
        return "{" +
            "\"schemaVersion\":1,\"retrievalType\":\"lexical\"," +
            "\"rolePromptHash\":\"hash-role\",\"assembledHash\":\"hash-context\",\"tokenBudget\":1024," +
            "\"selectedWorldBookSources\":[],\"skippedWorldBookSources\":[]," +
            "\"selectedKnowledgeSources\":[{\"knowledgeBaseId\":\"kh-1\",\"documentId\":\"doc-1\"," +
            "\"chunkId\":\"chunk-1\",\"excerpt\":\"部署说明\",\"lexicalScore\":3,\"estimatedTokens\":12," +
            "\"reason\":\"lexical_match\"" + nameFields + "}]," +
            "\"skippedKnowledgeSources\":[],\"omittedSourceCount\":0}"
    }

    private fun worldSource(ownerId: String, ownerName: String?) = ContextTraceSource(
        sourceId = "entry-958c34f0-0000-0000-0000-000000000001",
        ownerId = ownerId,
        scope = "global",
        excerpt = "雨夜的旧城街道，霓虹招牌在积水里晃动。",
        estimatedTokens = 24,
        reason = "keyword",
        ownerName = ownerName
    )

    private fun knowledgeSource(
        ownerId: String,
        documentId: String,
        chunkId: String,
        baseName: String? = null,
        documentName: String? = null
    ) = ContextTraceSource(
        sourceId = chunkId,
        ownerId = ownerId,
        scope = documentId,
        excerpt = "第三章 部署说明",
        estimatedTokens = 12,
        reason = "lexical_match",
        lexicalScore = 3,
        ownerName = baseName,
        documentName = documentName
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

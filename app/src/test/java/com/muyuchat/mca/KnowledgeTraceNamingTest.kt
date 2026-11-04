package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the knowledge-base names persisted inside the context trace.
 *
 * The world book got the same treatment first; a knowledge source is the same problem (a UUID where
 * a name belongs), so both names travel with the trace and survive the base/document being deleted.
 */
class KnowledgeTraceNamingTest {

    @Test
    fun `trace names are trimmed, single line and capped`() {
        assertEquals("部署说明.md", boundedTraceName("  部署说明.md  "))
        assertEquals("第一行 第二行", boundedTraceName("第一行\n第二行"))
        assertEquals("甲 乙", boundedTraceName("甲 \r\n  乙"))
        assertEquals(MCA_TRACE_NAME_MAX_CHARS, boundedTraceName("名".repeat(500))?.length)
        assertNull(boundedTraceName(null))
        assertNull(boundedTraceName("   \n  "))
    }

    @Test
    fun `the trace stores the knowledge base and document names`() {
        val trace = traceWith(
            KnowledgeSource(
                knowledgeBaseId = "kh-1",
                documentId = "doc-1",
                chunkId = "chunk-1",
                excerpt = "第三章 部署说明",
                lexicalScore = 3,
                estimatedTokens = 12,
                reason = "lexical_match",
                baseName = "项目资料库",
                documentName = "部署说明.md"
            )
        )

        val stored = JSONObject(trace.toJsonString())
            .getJSONArray("selectedKnowledgeSources")
            .getJSONObject(0)

        assertEquals("项目资料库", stored.getString("knowledgeBaseName"))
        assertEquals("部署说明.md", stored.getString("documentName"))
        // The ids stay, so the panel can still resolve a name and the diagnostics stay precise.
        assertEquals("kh-1", stored.getString("knowledgeBaseId"))
        assertEquals("doc-1", stored.getString("documentId"))
    }

    @Test
    fun `a long or multi-line name is stored bounded, not raw`() {
        val trace = traceWith(
            KnowledgeSource(
                knowledgeBaseId = "kh-1",
                documentId = "doc-1",
                chunkId = "chunk-1",
                excerpt = "正文",
                lexicalScore = 1,
                estimatedTokens = 4,
                reason = "budget_or_limit",
                baseName = "资料库\n".repeat(200),
                documentName = null
            )
        )

        val stored = JSONObject(trace.toJsonString())
            .getJSONArray("selectedKnowledgeSources")
            .getJSONObject(0)
        val name = stored.getString("knowledgeBaseName")

        assertEquals(MCA_TRACE_NAME_MAX_CHARS, name.length)
        assertFalse("a stored name must not contain a newline", name.contains('\n'))
    }

    @Test
    fun `a source without names omits the keys instead of writing json null`() {
        val trace = traceWith(
            KnowledgeSource(
                knowledgeBaseId = "kh-2",
                documentId = "doc-2",
                chunkId = "chunk-2",
                excerpt = "正文",
                lexicalScore = 1,
                estimatedTokens = 4,
                reason = "budget_or_limit"
            )
        )

        val stored = JSONObject(trace.toJsonString())
            .getJSONArray("selectedKnowledgeSources")
            .getJSONObject(0)

        assertFalse(stored.has("knowledgeBaseName"))
        assertFalse(stored.has("documentName"))
    }

    @Test
    fun `knowledge source names are optional for existing callers`() {
        val legacy = KnowledgeSource(
            knowledgeBaseId = "kh",
            documentId = "doc",
            chunkId = "chunk",
            excerpt = "x",
            lexicalScore = 0,
            estimatedTokens = 1,
            reason = "budget_or_limit"
        )

        assertNull(legacy.baseName)
        assertNull(legacy.documentName)
    }

    private fun traceWith(source: KnowledgeSource): ContextAssemblyTrace = ContextAssemblyTrace(
        rolePromptHash = "a".repeat(64),
        selectedWorldBookSources = emptyList(),
        skippedWorldBookSources = emptyList(),
        selectedKnowledgeSources = listOf(source),
        skippedKnowledgeSources = emptyList(),
        tokenBudget = 1024,
        assembledHash = "b".repeat(64)
    )
}

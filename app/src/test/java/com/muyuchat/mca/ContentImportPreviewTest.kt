package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentImportPreviewTest {
    private val owner = ContentImportOwner(
        sessionId = "session-a",
        assistantId = "assistant-a",
        selectionRevision = 7L
    )

    @Test
    fun knowledgePreviewUsesTheSameBoundedProjectionAsCommit() {
        val raw = "\uFEFF杭州西湖位于浙江。\r\n\r\n保留原始换行。"

        val preview = knowledgeDocumentPreview(
            rawSource = raw,
            title = "西湖.md",
            knowledgeBaseId = "base-a",
            source = "content://document/1",
            owner = owner
        )

        assertEquals("knowledge_document", preview.kind)
        assertEquals(owner, preview.owner)
        assertEquals("base-a", preview.knowledgeBaseId)
        assertEquals("content://document/1", preview.source)
        assertEquals(contentImportSha256(raw), preview.sourceHash)
        assertTrue(preview.fields.any { it.first == "chunks" && it.second == "1" })
        assertTrue(preview.fields.single { it.first == "excerpt" }.second.contains("杭州西湖"))
    }

    @Test
    fun malformedEmbeddedCharacterBookAppearsInPreviewBeforeCommit() {
        val raw = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "带坏世界书",
                "description": "保留未知字段",
                "vendor_extension": {"opaque": true},
                "character_book": {"entries": "not-an-array"}
              }
            }
        """.trimIndent()

        val success = CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success
        val preview = characterCardPreview(success, owner)

        assertEquals(raw, preview.rawSource)
        assertTrue(preview.warnings.any { it.contains("世界书") || it.contains("character_book") })
        assertTrue(preview.warnings.any { it.contains("Unknown fields") })
        assertEquals(owner, preview.owner)
    }

    @Test
    fun worldBookPreviewRetainsRawSourceAndReportsSkippedRegexEntries() {
        val raw = """
            {"name":"规则","entries":[
              {"uid":"regex","key":"^secret$","use_regex":true,"content":"不要导入"},
              {"uid":"normal","key":["杭州"],"content":"西湖在杭州。"}
            ]}
        """.trimIndent()
        val result = WorldBookCodec.parse(
            rawJson = raw,
            scope = WorldBookScope.GLOBAL
        )
        val preview = worldBookPreview(raw, result, owner, WorldBookScope.GLOBAL)

        assertEquals(raw, preview.rawSource)
        assertEquals(1, preview.fields.single { it.first == "entries" }.second.toInt())
        assertTrue(preview.warnings.any { it.contains("正则") })
        assertEquals(owner, preview.owner)
    }
}

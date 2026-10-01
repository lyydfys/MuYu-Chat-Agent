package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentImportPreviewTest {
    private val owner = ContentImportOwner(
        sessionId = "session-a",
        assistantId = "assistant-a",
        selectionRevision = 7L
    )

    @Test
    fun characterPreviewExplainsDerivedAndUserAuthoredPromptOrigins() {
        val derivedRaw = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"来源预览","description":"保持角色身份。"}}"""
        val derived = characterCardPreview(CharacterCardCodec.parseJson(derivedRaw) as CharacterCardParseResult.Success, owner)
        val authoredRaw = AssistantRecord(id = "authored", name = "用户角色", systemPrompt = "用户自己编写。")
            .withSystemPromptProvenance(AssistantPromptProvenance.USER_AUTHORED).toJson().toString()
        val authored = characterCardPreview(CharacterCardCodec.parseJson(authoredRaw) as CharacterCardParseResult.Success, owner)

        assertEquals("根据导入角色卡生成", derived.fields.single { it.first == "提示词来源" }.second)
        assertEquals("用户编写的提示词", authored.fields.single { it.first == "提示词来源" }.second)
        assertEquals(derivedRaw, derived.rawSource)
        assertEquals(authoredRaw, authored.rawSource)
    }

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
        assertFalse(preview.warnings.any { it.contains("Unknown fields") })
        assertTrue(preview.fields.any { it.first == "对话提示词" && it.second.contains("保留未知字段") })
        assertEquals(owner, preview.owner)
    }

    @Test
    fun worldBookPreviewRetainsRawSourceAndRegexEntries() {
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
        assertEquals(2, preview.fields.single { it.first == "entries" }.second.toInt())
        assertEquals(owner, preview.owner)
    }

    @Test
    fun tavernV3PreviewKeepsEmbeddedRegexEntries() {
        val raw = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{
              "name":"同提示词角色","system_prompt":"shared prompt",
              "character_book":{"entries":[
                {"uid":1,"keys":["^v[0-9]+$"],"content":"版本","use_regex":true},
                {"uid":2,"keys":["foo.*"],"content":"Foo","use_regex":true},
                {"uid":3,"keys":["bar|baz"],"content":"Bar","use_regex":true},
                {"uid":4,"keys":["[a-z]+"],"content":"Letters","use_regex":true}
              ]}
            }}
        """.trimIndent()

        val card = CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success
        val preview = characterCardPreview(card, owner)

        assertEquals("4", preview.fields.single { it.first == "character_book" }.second)
        assertFalse(preview.warnings.any { it.contains("未导入") })
        assertEquals(raw, preview.rawSource)
    }

    @Test
    fun tavernCardWithGreetingAndLoreButNoSystemPromptShowsBothSources() {
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"夜航员","first_mes":"欢迎登船。","character_book":{"entries":[{"id":1,"constant":true,"content":"航线资料"},{"id":2,"constant":false,"keys":[],"content":"暂未触发的资料"}]}}}"""
        val card = CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success

        val preview = characterCardPreview(card, owner)

        assertEquals("欢迎登船。", preview.fields.single { it.first == "开场白" }.second)
        assertEquals("2", preview.fields.single { it.first == "character_book" }.second)
        assertEquals("1", preview.fields.single { it.first == "可自动触发的世界书条目" }.second)
        assertEquals("开场白：\n欢迎登船。", preview.fields.single { it.first == "对话提示词" }.second)
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun mcaBoilerplatePreviewSeparatesRawPromptFromEffectiveCharacterPrompt() {
        val sourcePrompt = com.muyuchat.core.engine.GenerationParams().systemPrompt
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"高贵丝袜舞蹈勇妈的堕落","system_prompt":"$sourcePrompt","character_book":{"entries":[{"id":1,"constant":true,"content":"角色设定"}]}}}"""
        val card = CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success

        val preview = characterCardPreview(card, owner)

        assertEquals(sourcePrompt, preview.fields.single { it.first == "原卡 system_prompt" }.second)
        assertEquals(
            "你是高贵丝袜舞蹈勇妈的堕落。请保持角色身份，自然回应。",
            preview.fields.single { it.first == "对话提示词" }.second
        )
        assertTrue(preview.warnings.any { it.contains("MCA 通用默认提示词") })
        assertEquals(raw, preview.rawSource)
    }
    @Test
    fun oversizedGreetingIsIncludedInPromptTruncationWarning() {
        val greeting = "迎".repeat(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS + 1)
        val raw = org.json.JSONObject()
            .put("spec", "chara_card_v3")
            .put("spec_version", "3.0")
            .put("data", org.json.JSONObject().put("name", "夜航员").put("first_mes", greeting))
            .toString()
        val success = CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success

        val preview = characterCardPreview(success, owner)

        assertEquals(greeting, preview.fields.single { it.first == "开场白" }.second)
        assertEquals(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS,
            preview.fields.single { it.first == "对话提示词" }.second.length)
        assertTrue(preview.warnings.any { it.contains("最多保留") })
        assertEquals(raw, preview.rawSource)
    }

}

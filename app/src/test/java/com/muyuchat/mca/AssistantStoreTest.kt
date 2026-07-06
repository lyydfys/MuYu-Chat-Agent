package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStoreTest {
    @Test
    fun normalizeAssistantRecordsAddsDefaultAssistantWhenMissing() {
        val fallback = AssistantRecord.default(systemPrompt = "default prompt")
        val custom = AssistantRecord(id = "custom", name = "代码助手", systemPrompt = "code prompt")

        val normalized = normalizeAssistantRecords(listOf(custom), fallback)

        assertEquals(2, normalized.size)
        assertEquals(AssistantRecord.DEFAULT_ID, normalized.first().id)
        assertEquals("default prompt", normalized.first().systemPrompt)
        assertEquals("custom", normalized[1].id)
    }

    @Test
    fun normalizeAssistantRecordsKeepsExistingDefaultAndDeduplicates() {
        val fallback = AssistantRecord.default(systemPrompt = "fallback")
        val existingDefault = AssistantRecord.default(systemPrompt = "existing")
        val custom = AssistantRecord(id = "custom", name = "写作助手")
        val duplicate = custom.copy(name = "重复助手")

        val normalized = normalizeAssistantRecords(
            listOf(existingDefault, custom, duplicate),
            fallback
        )

        assertEquals(2, normalized.size)
        assertEquals("existing", normalized.first { it.id == AssistantRecord.DEFAULT_ID }.systemPrompt)
        assertEquals("写作助手", normalized.first { it.id == "custom" }.name)
    }

    @Test
    fun normalizeAssistantRecordsReturnsFallbackForEmptyInput() {
        val fallback = AssistantRecord.default(systemPrompt = "fallback prompt")

        val normalized = normalizeAssistantRecords(emptyList(), fallback)

        assertEquals(listOf(fallback), normalized)
        assertTrue(normalized.single().id == AssistantRecord.DEFAULT_ID)
    }

    @Test
    fun assistantRecordJsonPreservesDefaultModelBinding() {
        val params = GenerationParams(
            temperature = 0.42f,
            topP = 0.8f,
            nCtx = 4096,
            nPredict = 2048,
            reasoningMode = ReasoningMode.STANDARD
        )
        val assistant = AssistantRecord(
            id = "code",
            name = "代码助手",
            avatar = "码",
            tag = "代码 / 云端",
            defaultModelMode = "cloud",
            defaultModelId = "cloud-123",
            systemPrompt = "你是代码助手",
            paramsJson = params.toJson()
        )

        val restored = AssistantRecord.fromJson(assistant.toJson())
        val restoredParams = GenerationParams.fromJson(restored.paramsJson)

        assertEquals("cloud", restored.defaultModelMode)
        assertEquals("cloud-123", restored.defaultModelId)
        assertEquals("码", restored.avatar)
        assertEquals("代码 / 云端", restored.tag)
        assertEquals("你是代码助手", restored.systemPrompt)
        assertEquals(0.42f, restoredParams.temperature, 0.001f)
        assertEquals(0.8f, restoredParams.topP, 0.001f)
        assertEquals(4096, restoredParams.nCtx)
        assertEquals(2048, restoredParams.nPredict)
        assertEquals(ReasoningMode.STANDARD, restoredParams.reasoningMode)
    }

    @Test
    fun assistantRecordImportsNestedCharacterCardData() {
        val raw = """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "旅途记录员",
                "creator": "community",
                "description": "擅长把旅行照片整理成手帐式中文记录。",
                "personality": "温柔、克制、会追问缺失信息。",
                "scenario": "用户正在手机上整理一次山海旅行。",
                "first_mes": "把照片和地点发给我，我来帮你整理。",
                "mes_example": "<START>\n用户：这张图是什么风格？\n助手：我会先描述画面，再给出可复用提示词。"
              }
            }
        """.trimIndent()

        val assistant = AssistantRecord.fromJson(JSONObject(raw))

        assertEquals("旅途记录员", assistant.name)
        assertEquals("community", assistant.tag)
        assertTrue(assistant.systemPrompt.contains("角色描述"))
        assertTrue(assistant.systemPrompt.contains("旅行照片"))
        assertTrue(assistant.systemPrompt.contains("示例对话"))
        assertFalse(assistant.systemPrompt.contains("null"))
    }

    @Test
    fun assistantRecordExportIncludesSchemaMetadata() {
        val json = AssistantRecord(id = "writer", name = "写作助手").toJson()

        assertEquals("mca.assistant.card", json.getString("schema"))
        assertEquals(1, json.getInt("version"))
    }
}

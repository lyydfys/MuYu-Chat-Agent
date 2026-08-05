package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun systemPromptLimitAppliesToImportedAndManualValues() {
        val overLimit = "x".repeat(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS + 37)
        val imported = AssistantRecord.fromJson(JSONObject().put("systemPrompt", overLimit))

        assertEquals(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS, imported.systemPrompt.length)
        assertEquals(
            AssistantRecord.MAX_SYSTEM_PROMPT_CHARS,
            GenerationParams.fromJson(imported.paramsJson).systemPrompt.length
        )
        assertEquals(
            AssistantRecord.MAX_SYSTEM_PROMPT_CHARS,
            boundedManualAssistantSystemPrompt(overLimit).length
        )
    }

    @Test
    fun conversationSnapshotFreezesPersonaButKeepsGenerationControlsLive() {
        val assistant = AssistantRecord(
            id = "writer",
            name = "Writer",
            systemPrompt = "You are the original writing persona.",
            memoryEnabled = true,
            webSearchEnabled = true,
            fileContextEnabled = false
        )

        val snapshot = assistant.toConversationSnapshot(capturedAt = 42L)
        val requestParams = snapshot.applyTo(
            GenerationParams(
                systemPrompt = "An edited persona must not replace the snapshot.",
                temperature = 1.15f,
                nPredict = 1536
            )
        )
        val restored = AssistantConversationSnapshot.fromJsonOrNull(snapshot.toJsonString())

        assertEquals("writer", snapshot.assistantId)
        assertEquals("You are the original writing persona.", requestParams.systemPrompt)
        assertEquals(1.15f, requestParams.temperature, 0.001f)
        assertEquals(1536, requestParams.nPredict)
        assertEquals(snapshot, restored)
        assertTrue(requireNotNull(restored).webSearchEnabled)
        assertTrue(restored.memoryEnabled)
        assertFalse(restored.fileContextEnabled)
    }

    @Test
    fun legacySessionsBackfillSnapshotsWithoutReplacingExistingPersona() {
        val default = AssistantRecord.default(systemPrompt = "default persona")
        val writer = AssistantRecord(
            id = "writer",
            name = "Writer",
            systemPrompt = "writer persona"
        )
        val existingSnapshot = AssistantConversationSnapshot.fromAssistant(
            assistant = writer.copy(systemPrompt = "historical persona"),
            capturedAt = 7L
        )
        val legacy = ChatSessionRecord(
            id = "legacy",
            title = "Legacy",
            messages = emptyList(),
            assistantId = "writer",
            updatedAt = 9L
        )
        val retained = ChatSessionRecord(
            id = "retained",
            title = "Retained",
            messages = emptyList(),
            assistantId = "writer",
            assistantSnapshot = existingSnapshot,
            updatedAt = 10L
        )
        val orphan = ChatSessionRecord(
            id = "orphan",
            title = "Orphan",
            messages = emptyList(),
            assistantId = "deleted",
            updatedAt = 11L
        )

        val snapshots = listOf(legacy, retained, orphan)
            .withBackfilledAssistantSnapshots(listOf(default, writer))

        assertEquals("writer persona", requireNotNull(snapshots[0].assistantSnapshot).systemPrompt)
        assertEquals(9L, snapshots[0].assistantSnapshot?.capturedAt)
        assertEquals(existingSnapshot, snapshots[1].assistantSnapshot)
        assertEquals("default persona", snapshots[2].assistantSnapshot?.systemPrompt)
        assertEquals("deleted", snapshots[2].assistantId)
    }

    @Test
    fun malformedConversationSnapshotIsIgnored() {
        assertEquals(null, AssistantConversationSnapshot.fromJsonOrNull("not json"))
        assertEquals(
            null,
            AssistantConversationSnapshot.fromJsonOrNull(
                JSONObject().put("assistantId", "writer").toString()
            )
        )
        assertEquals(
            null,
            AssistantConversationSnapshot.fromJsonOrNull(
                JSONObject()
                    .put("schema", AssistantConversationSnapshot.SCHEMA)
                    .put("version", AssistantConversationSnapshot.VERSION + 1)
                    .put("assistantId", "writer")
                    .put("systemPrompt", "persona")
                    .toString()
            )
        )
    }

    @Test
    fun canonicalRoomWritePrecedesLegacyAssistantMirrorAndFailureIsNotSuppressed() {
        val source = sourceFile("AssistantStore.kt")
        val body = functionBody(source, "saveAssistants")

        assertCallsInOrder(
            body,
            "database.chatSessionDao().replaceAssistants(normalized)",
            "saveLegacyAssistants(normalized)"
        )
        assertFalse(body.contains("runCatching"))
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Unable to locate $name")

    private fun functionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("fun $functionName")
        require(declaration >= 0) { "Missing function $functionName" }
        val start = source.indexOf('{', declaration)
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        error("Unterminated function $functionName")
    }

    private fun assertCallsInOrder(source: String, vararg needles: String) {
        var cursor = 0
        needles.forEach { needle ->
            val index = source.indexOf(needle, cursor)
            assertTrue("Missing or out of order: $needle", index >= cursor)
            cursor = index + needle.length
        }
    }
}

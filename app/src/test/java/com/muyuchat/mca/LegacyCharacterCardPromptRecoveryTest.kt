package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.PrefixCacheKey
import com.muyuchat.core.engine.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCharacterCardPromptRecoveryTest {
    private val boilerplate = GenerationParams().systemPrompt
    private val sourceCard = JSONObject()
        .put("spec", "chara_card_v2")
        .put("spec_version", "2.0")
        .put("data", JSONObject()
            .put("name", "旅人林")
            .put("system_prompt", boilerplate)
            .put("description", "你是旅人林，记得三年前一起走过的海边。")
            .put("personality", "温柔，回答时保持角色身份。")
            .put("scenario", "傍晚的海边小屋。")
            .put("first_mes", "你终于回来了，茶还温着。")
            .put("post_history_instructions", "不要把自己称作 MCA 离线助手。")
            .put("extensions", JSONObject().put("keep", "{{inert_extension}}")))
        .toString()

    @Test
    fun roomNormalizationRestoresOnlyPersonaAndMatchingGenerationPrompt() {
        val params = JSONObject(GenerationParams(temperature = 0.31f, seed = 42)
            .toAssistantGenerationJson())
            .put("n_ctx", 4096)
            .put("n_threads", 3)
            .toString()
        val broken = importedRecord().copy(
            avatar = "林", tag = "旅行", paramsJson = params,
            defaultModelMode = "local", defaultModelId = "user-model",
            memoryEnabled = true, memorySummaryInterval = 27,
            webSearchEnabled = true, fileContextEnabled = false,
            createdAt = 100L, updatedAt = 101L
        )

        val recovered = normalize(broken)
        val prompt = recovered.systemPrompt
        assertTrue(prompt.contains("你是旅人林"))
        assertTrue(prompt.contains("傍晚的海边小屋"))
        assertTrue(prompt.contains("你终于回来了，茶还温着。"))
        assertTrue(prompt.contains("不要把自己称作 MCA 离线助手。"))
        assertFalse(prompt.contains(boilerplate))
        assertEquals(prompt, GenerationParams.fromJson(recovered.paramsJson).systemPrompt)
        assertEquals(0.31f, GenerationParams.fromJson(recovered.paramsJson).temperature, 0.00001f)
        assertEquals(42, GenerationParams.fromJson(recovered.paramsJson).seed)
        assertEquals(4096, JSONObject(recovered.paramsJson).getInt("n_ctx"))
        assertEquals(3, JSONObject(recovered.paramsJson).getInt("n_threads"))
        assertEquals(broken.copy(systemPrompt = prompt, paramsJson = recovered.paramsJson), recovered)
        assertEquals(sourceCard, recovered.characterCardJson)
        assertEquals(recovered, normalize(recovered))
    }

    @Test
    fun legacyJsonMirrorUsesTheSameRecoveryAfterDeserialization() {
        val broken = importedRecord().copy(createdAt = 200L, updatedAt = 200L)
        val readFromLegacyMirror = AssistantRecord.fromJson(broken.toJson())

        val recovered = normalize(readFromLegacyMirror)

        assertEquals(normalize(broken), recovered)
        assertEquals(sourceCard, recovered.characterCardJson)
        assertEquals(recovered.systemPrompt, GenerationParams.fromJson(recovered.paramsJson).systemPrompt)
    }

    @Test
    fun authoredSuffixMissingSourceAndMcaSelfExportNeverTriggerRecovery() {
        val authored = importedRecord().copy(systemPrompt = "$boilerplate\n用户明确要求保留这个句子。")
        val noSource = importedRecord().copy(characterCardJson = null)
        val corruptSource = importedRecord().copy(characterCardJson = "not json")
        val mcaSelfExport = importedRecord().copy(characterCardJson = JSONObject()
            .put("schema", "mca.assistant.card")
            .put("version", 1)
            .put("name", "用户保留默认助手")
            .put("systemPrompt", boilerplate)
            .toString())
        val actualDefault = importedRecord().copy(id = AssistantRecord.DEFAULT_ID)

        for (record in listOf(authored, noSource, corruptSource, mcaSelfExport, actualDefault)) {
            assertEquals(record.systemPrompt, normalize(record).systemPrompt)
            assertEquals(record.characterCardJson, normalize(record).characterCardJson)
        }
    }

    @Test
    fun authoredSystemPromptInTheRawCardIsNotReinterpretedAsTheKnownBoilerplateRegression() {
        val card = JSONObject(sourceCard).apply {
            getJSONObject("data").put("system_prompt", "请作为作者指定的角色回答。")
        }.toString()
        val record = importedRecord().copy(characterCardJson = card)

        assertEquals(boilerplate, normalize(record).systemPrompt)
        assertEquals(card, normalize(record).characterCardJson)
    }

    @Test
    fun exactOldSnapshotOfTheRecoveredCardIsRestoredWithoutChangingMessagesOrOwnership() {
        val broken = importedRecord()
        val recovered = normalize(broken)
        val session = session(broken)

        val restored = listOf(session).withBackfilledAssistantSnapshots(listOf(recovered)).single()

        assertEquals(recovered.systemPrompt, restored.assistantSnapshot?.systemPrompt)
        assertEquals(session.copy(assistantSnapshot = requireNotNull(session.assistantSnapshot)
            .copy(systemPrompt = recovered.systemPrompt)), restored)
        assertEquals(77L, restored.assistantSnapshot?.capturedAt)
        assertEquals(session.messages, restored.messages)
        assertEquals(restored, listOf(restored).withBackfilledAssistantSnapshots(listOf(recovered)).single())
    }

    @Test
    fun editedHistoricalMixedAndOtherOwnerSnapshotsStayUntouched() {
        val broken = importedRecord()
        val recovered = normalize(broken)
        val original = session(broken)
        val snapshot = requireNotNull(original.assistantSnapshot)
        val custom = original.copy(id = "custom", assistantSnapshot = snapshot.copy(systemPrompt = "历史角色提示词"))
        val suffixed = original.copy(id = "suffix", assistantSnapshot = snapshot.copy(systemPrompt = "$boilerplate\n有效历史指令"))
        val edited = original.copy(id = "edited", assistantSnapshot = snapshot.copy(
            priorSystemPromptHashes = listOf(PrefixCacheKey.sha256Utf8("previous authored prompt"))))
        val mixed = original.copy(id = "mixed", mixedAssistantHistory = true)
        val differentOwner = original.copy(id = "other", assistantSnapshot = snapshot.copy(assistantId = "other"))
        val sessions = listOf(custom, suffixed, edited, mixed, differentOwner)

        assertEquals(sessions, sessions.withBackfilledAssistantSnapshots(listOf(recovered)))
        val explicitlyEditedProfile = recovered.copy(systemPrompt = "现在用户编写的角色提示词")
        assertEquals(original, listOf(original)
            .withBackfilledAssistantSnapshots(listOf(explicitlyEditedProfile)).single())
    }

    private fun importedRecord() = AssistantRecord(
        id = "imported-external-card", name = "旅人林", systemPrompt = boilerplate,
        characterCardJson = sourceCard, createdAt = 10L, updatedAt = 10L
    )

    private fun normalize(record: AssistantRecord): AssistantRecord =
        normalizeAssistantRecords(listOf(record), AssistantRecord.default()).first { it.id == record.id }

    private fun session(assistant: AssistantRecord) = ChatSessionRecord(
        id = "known-owner", title = "已有会话", updatedAt = 90L,
        assistantId = assistant.id, assistantSnapshot = assistant.toConversationSnapshot(77L),
        messages = listOf(ChatMessage(role = Role.ASSISTANT, content = "保留的历史内容。"))
    )
}

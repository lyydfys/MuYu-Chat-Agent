package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptProvenanceTest {
    private val defaultPrompt = GenerationParams().systemPrompt
    private val externalCard = JSONObject()
        .put("spec", "chara_card_v2")
        .put("spec_version", "2.0")
        .put("data", JSONObject()
            .put("name", "海边向导")
            .put("system_prompt", defaultPrompt)
            .put("description", "你是海边向导，记得五月的星空。"))
        .toString()

    @Test
    fun externalImportRecordsDerivedProvenanceInParamsAndSnapshot() {
        val assistant = imported()

        assertEquals(AssistantPromptProvenance.IMPORTED_DERIVED, assistant.systemPromptProvenance)
        assertTrue(assistant.systemPrompt.contains("五月的星空"))
        assertFalse(assistant.systemPrompt.contains(defaultPrompt))
        assertEquals(externalCard, assistant.characterCardJson)
        assertEquals("IMPORTED_DERIVED", JSONObject(assistant.paramsJson).getString("assistant_prompt_provenance"))
        val snapshot = assistant.toConversationSnapshot(17L)
        assertEquals(AssistantPromptProvenance.IMPORTED_DERIVED, snapshot.systemPromptProvenance)
        assertEquals(snapshot, AssistantConversationSnapshot.fromJsonOrNull(snapshot.toJsonString()))
    }

    @Test
    fun externalMetadataCannotClaimAnExplicitEditInThisApp() {
        val raw = JSONObject(externalCard)
            .put("systemPromptProvenance", "USER_AUTHORED")
            .put("paramsJson", GenerationParams().toAssistantGenerationJson()
                .withAssistantPromptProvenance(AssistantPromptProvenance.USER_AUTHORED))
            .toString()
        val assistant = parse(raw).toAssistantRecord()

        assertEquals(AssistantPromptProvenance.IMPORTED_DERIVED, assistant.systemPromptProvenance)
        assertEquals(raw, assistant.characterCardJson)
    }

    @Test
    fun exactDefaultExplicitlyAuthoredByUserSurvivesNormalizationAndJsonReload() {
        val edited = imported().copy(
            systemPrompt = defaultPrompt,
            paramsJson = GenerationParams(systemPrompt = defaultPrompt).toAssistantGenerationJson()
        ).withSystemPromptProvenance(AssistantPromptProvenance.USER_AUTHORED)
        val roundTrip = AssistantRecord.fromJson(edited.toJson())
        val normalized = normalize(roundTrip)

        assertEquals(AssistantPromptProvenance.USER_AUTHORED, roundTrip.systemPromptProvenance)
        assertEquals(defaultPrompt, normalized.systemPrompt)
        assertEquals(AssistantPromptProvenance.USER_AUTHORED, normalized.systemPromptProvenance)
        assertEquals(defaultPrompt, GenerationParams.fromJson(normalized.paramsJson).systemPrompt)
        assertEquals(externalCard, normalized.characterCardJson)
        assertEquals(normalized, normalize(normalized))
    }

    @Test
    fun mcaSelfExportRetainsExplicitAuthorshipAndItsPrompt() {
        val exported = AssistantRecord(
            id = "authored-role", name = "保留默认句", systemPrompt = defaultPrompt,
            paramsJson = GenerationParams(systemPrompt = defaultPrompt).toAssistantGenerationJson()
        ).withSystemPromptProvenance(AssistantPromptProvenance.USER_AUTHORED).toJson().toString()

        val assistant = parse(exported).toAssistantRecord()

        assertEquals(defaultPrompt, assistant.systemPrompt)
        assertEquals(AssistantPromptProvenance.USER_AUTHORED, assistant.systemPromptProvenance)
        assertEquals(exported, assistant.characterCardJson)
        assertEquals(defaultPrompt, normalize(assistant).systemPrompt)
    }

    @Test
    fun missingHistoricalMetadataDoesNotInheritAuthorshipFromGenerationDefaults() {
        val authoredDefaults = AssistantRecord.default()
            .withSystemPromptProvenance(AssistantPromptProvenance.USER_AUTHORED)
        val legacy = AssistantRecord.fromJson(JSONObject().put("id", "old-role"), authoredDefaults)
        val withInvalidMetadata = AssistantRecord.fromJson(JSONObject()
            .put("id", "old-role")
            .put("systemPromptProvenance", "future_unknown_value"))

        assertEquals(AssistantPromptProvenance.LEGACY_UNKNOWN, legacy.systemPromptProvenance)
        assertEquals(AssistantPromptProvenance.LEGACY_UNKNOWN, withInvalidMetadata.systemPromptProvenance)
    }

    @Test
    fun narrowLegacyRecoveryKeepsTheOriginUnknownInsteadOfGuessingEarlierEdits() {
        val legacy = AssistantRecord(
            id = "legacy-role", name = "任何名称", systemPrompt = defaultPrompt,
            characterCardJson = externalCard, createdAt = 20L, updatedAt = 30L
        )
        val recovered = normalize(legacy)

        assertTrue(recovered.systemPrompt.contains("五月的星空"))
        assertEquals(AssistantPromptProvenance.LEGACY_UNKNOWN, recovered.systemPromptProvenance)
        assertEquals(20L, recovered.createdAt)
        assertEquals(30L, recovered.updatedAt)
        assertEquals(externalCard, recovered.characterCardJson)
        assertEquals(recovered, normalize(recovered))
    }

    @Test
    fun userAuthoredSnapshotCannotBeReplacedByAnImportDerivedProfile() {
        val assistant = imported()
        val editedSnapshot = assistant.toConversationSnapshot(18L).copy(
            systemPrompt = defaultPrompt,
            systemPromptProvenance = AssistantPromptProvenance.USER_AUTHORED
        )
        val session = ChatSessionRecord(
            id = "owned-session", assistantId = assistant.id, messages = emptyList(),
            assistantSnapshot = editedSnapshot, title = "保留用户设置", updatedAt = 18L
        )

        assertEquals(listOf(session), listOf(session).withBackfilledAssistantSnapshots(listOf(assistant)))
    }

    @Test
    fun missingSnapshotProvenanceRemainsUnknownAfterRead() {
        val snapshot = imported().toConversationSnapshot(19L)
        val oldJson = JSONObject(snapshot.toJsonString()).apply {
            remove("systemPromptProvenance")
        }
        val restored = requireNotNull(AssistantConversationSnapshot.fromJsonOrNull(oldJson.toString()))

        assertEquals(snapshot.systemPrompt, restored.systemPrompt)
        assertEquals(AssistantPromptProvenance.LEGACY_UNKNOWN, restored.systemPromptProvenance)
    }

    @Test
    fun sanitizingGenerationParametersRetainsSourceWithoutChangingRuntimeDefaults() {
        val params = JSONObject(GenerationParams(temperature = 0.23f, seed = 42).toAssistantGenerationJson())
            .put("n_ctx", 4096)
            .put("n_threads", 3)
            .toString().withAssistantPromptProvenance(AssistantPromptProvenance.USER_AUTHORED)
        val sanitized = sanitizeAssistantParamsJsonPreservingLegacyExecution(
            params, GenerationParams(nCtx = 8192, nThreads = 5), defaultPrompt
        )
        val assistant = AssistantRecord(paramsJson = sanitized)
        val effective = assistantGenerationParamsFromJson(sanitized,
            GenerationParams(nCtx = 8192, nThreads = 5), defaultPrompt)

        assertEquals(AssistantPromptProvenance.USER_AUTHORED, assistant.systemPromptProvenance)
        assertEquals(0.23f, effective.temperature, 0.00001f)
        assertEquals(42, effective.seed)
        assertEquals(8192, effective.nCtx)
        assertEquals(5, effective.nThreads)
        assertEquals(4096, JSONObject(sanitized).getInt("n_ctx"))
        assertEquals(3, JSONObject(sanitized).getInt("n_threads"))
    }

    private fun imported(): AssistantRecord = parse(externalCard).toAssistantRecord()

    private fun parse(raw: String): CharacterCard =
        (CharacterCardCodec.parseJson(raw) as CharacterCardParseResult.Success).card

    private fun normalize(assistant: AssistantRecord): AssistantRecord =
        normalizeAssistantRecords(listOf(assistant), AssistantRecord.default()).first { it.id == assistant.id }
}

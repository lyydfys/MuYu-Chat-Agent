package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.core.engine.PrefixCacheKey
import java.util.UUID


data class AssistantRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "默认助手",
    val avatar: String = "",
    val tag: String = "",
    val systemPrompt: String = GenerationParams().systemPrompt,
    val defaultModelMode: String = "follow_current",
    val defaultModelId: String? = null,
    val paramsJson: String = GenerationParams().toAssistantGenerationJsonWithOverrides(emptySet()),
    /** Original imported character-card JSON, retained as inert data for lossless re-export. */
    val characterCardJson: String? = null,
    val memoryEnabled: Boolean = false,
    val memorySummaryInterval: Int = DEFAULT_MEMORY_SUMMARY_INTERVAL,
    val webSearchEnabled: Boolean = false,
    val fileContextEnabled: Boolean = true,
    /** Optional role-card background; copied into app-private storage by the UI. */
    val appearance: ChatAppearance = ChatAppearance(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** Derived from the canonical params JSON; generation parameter consumers ignore this metadata. */
    val systemPromptProvenance: AssistantPromptProvenance
        get() = assistantPromptProvenanceFromParamsJson(paramsJson)

    fun toJson(): JSONObject = JSONObject()
        .put("schema", "mca.assistant.card")
        .put("version", 1)
        .put("id", id)
        .put("name", name)
        .put("avatar", avatar)
        .put("tag", tag)
        .put("systemPrompt", systemPrompt)
        .put("systemPromptProvenance", systemPromptProvenance.name)
        .put("defaultModelMode", defaultModelMode)
        .put("defaultModelId", defaultModelId)
        .put("paramsJson", paramsJson)
        .apply { characterCardJson?.let { put("characterCardJson", it) } }
        .put("memoryEnabled", memoryEnabled)
        .put("memorySummaryInterval", memorySummaryInterval.coerceIn(MIN_MEMORY_SUMMARY_INTERVAL, MAX_MEMORY_SUMMARY_INTERVAL))
        .put("webSearchEnabled", webSearchEnabled)
        .put("fileContextEnabled", fileContextEnabled)
        .put("appearance", appearance.toJson())
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        const val DEFAULT_ID = "default"
        /** Maximum persisted/manual system-prompt length shared by import and editor paths. */
        const val MAX_SYSTEM_PROMPT_CHARS = 12_000
        const val MAX_ASSISTANT_PROMPT_CHARS = MAX_SYSTEM_PROMPT_CHARS
        const val DEFAULT_MEMORY_SUMMARY_INTERVAL = 12
        const val MIN_MEMORY_SUMMARY_INTERVAL = 2
        const val MAX_MEMORY_SUMMARY_INTERVAL = 100

        fun default(systemPrompt: String = GenerationParams().systemPrompt, params: GenerationParams = GenerationParams()): AssistantRecord =
            AssistantRecord(
                id = DEFAULT_ID,
                name = "默认助手",
                systemPrompt = systemPrompt.ifBlank { GenerationParams().systemPrompt }
                    .take(MAX_SYSTEM_PROMPT_CHARS),
                paramsJson = params.copy(
                    systemPrompt = systemPrompt.ifBlank { GenerationParams().systemPrompt }
                        .take(MAX_SYSTEM_PROMPT_CHARS)
                ).toAssistantGenerationJsonWithOverrides(emptySet()),
                createdAt = 0L,
                updatedAt = 0L
            )

        fun fromJson(json: JSONObject, defaults: AssistantRecord = default()): AssistantRecord {
            val data = json.optJSONObject("data")
            val source = data ?: json
            val systemPrompt = json.cleanAssistantString("systemPrompt", "system_prompt", "prompt", "instructions")
                .ifBlank { source.cleanAssistantString("systemPrompt", "system_prompt", "prompt", "instructions") }
                .ifBlank { source.toCharacterCardPrompt() }
                .ifBlank { defaults.systemPrompt }
                .take(MAX_SYSTEM_PROMPT_CHARS)
            val persistedParams = json.cleanAssistantString("paramsJson", "params_json")
                .ifBlank { source.cleanAssistantString("paramsJson", "params_json") }
            val rawParams = persistedParams.ifBlank { defaults.paramsJson }
            val defaultParams = assistantGenerationParamsFromJson(
                defaults.paramsJson,
                GenerationParams(),
                defaults.systemPrompt
            )
            val provenance = when {
                json.has("systemPromptProvenance") ->
                    AssistantPromptProvenance.fromWireValue(json.optString("systemPromptProvenance"))
                source.has("systemPromptProvenance") ->
                    AssistantPromptProvenance.fromWireValue(source.optString("systemPromptProvenance"))
                else -> assistantPromptProvenanceFromParamsJson(persistedParams)
            }
            val sanitizedParams = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                rawParams, defaultParams, systemPrompt
            ).let { sanitized ->
                // Fallback generation defaults are not evidence of this record's authorship.
                if (persistedParams.isBlank()) JSONObject(sanitized).apply {
                    remove(ASSISTANT_PROMPT_PROVENANCE_KEY)
                }.toString() else sanitized
            }
            return AssistantRecord(
                id = json.cleanAssistantString("id")
                    .ifBlank { source.cleanAssistantString("id", "character_id") }
                    .ifBlank { UUID.randomUUID().toString() },
                name = json.cleanAssistantString("name", "char_name", "title")
                    .ifBlank { source.cleanAssistantString("name", "char_name", "title") }
                    .ifBlank { defaults.name },
                avatar = json.cleanAssistantString("avatar", "emoji")
                    .ifBlank { source.cleanAssistantString("avatar", "emoji") }
                    .ifBlank { defaults.avatar },
                tag = json.cleanAssistantString("tag", "category")
                    .ifBlank { source.cleanAssistantString("tag", "category", "creator", "creator_notes") }
                    .ifBlank { defaults.tag },
                systemPrompt = systemPrompt,
                defaultModelMode = json.cleanAssistantString("defaultModelMode", "default_model_mode")
                    .ifBlank { defaults.defaultModelMode },
                defaultModelId = json.cleanAssistantString("defaultModelId", "default_model_id")
                    .takeIf { it.isNotBlank() && it != "null" },
                paramsJson = if (provenance != AssistantPromptProvenance.LEGACY_UNKNOWN ||
                    runCatching { JSONObject(persistedParams).has(ASSISTANT_PROMPT_PROVENANCE_KEY) }.getOrDefault(false)) {
                    sanitizedParams.withAssistantPromptProvenance(provenance)
                } else sanitizedParams,
                characterCardJson = json.rawAssistantString("characterCardJson", "character_card_json"),
                memoryEnabled = json.optBoolean("memoryEnabled", defaults.memoryEnabled),
                memorySummaryInterval = json.optInt("memorySummaryInterval", defaults.memorySummaryInterval)
                    .coerceIn(MIN_MEMORY_SUMMARY_INTERVAL, MAX_MEMORY_SUMMARY_INTERVAL),
                webSearchEnabled = json.optBoolean("webSearchEnabled", defaults.webSearchEnabled),
                fileContextEnabled = json.optBoolean("fileContextEnabled", defaults.fileContextEnabled),
                appearance = ChatAppearance.fromJsonOrNull(
                    json.optJSONObject("appearance")?.toString()
                        ?: json.optString("appearanceJson").takeIf { it.isNotBlank() }
                        ?: source.optJSONObject("appearance")?.toString()
                        ?: source.optString("appearanceJson").takeIf { it.isNotBlank() }
                ) ?: defaults.appearance,
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        /** Bridges the bounded card codec back to the established assistant persistence format. */
        fun fromCharacterCard(
            card: CharacterCard,
            defaults: AssistantRecord = default()
        ): AssistantRecord {
            val imported = fromJson(card.toJson(), defaults)
            val cardRoot = card.toJson()
            val cardData = cardRoot.optJSONObject("data") ?: cardRoot
            val provenance = if (card.format == CharacterCardFormat.LEGACY_JSON &&
                cardRoot.optString("schema") == "mca.assistant.card") {
                // Self-exports preserve explicit authorship; old exports remain unknown.
                imported.systemPromptProvenance
            } else {
                // External fields do not claim an explicit edit in this app.
                AssistantPromptProvenance.IMPORTED_DERIVED
            }
            val cardUsesMcaBoilerplatePrompt = isMcaDefaultCharacterCardPrompt(card)
            val sourcePrompt = listOf(
                card.systemPrompt.takeUnless { cardUsesMcaBoilerplatePrompt }.orEmpty(),
                cardData.toCharacterCardPrompt(),
                card.postHistoryInstructions
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n\n")
            val systemPrompt = sourcePrompt
                .ifBlank { characterCardFallbackSystemPrompt(card.name) }
                .take(MAX_SYSTEM_PROMPT_CHARS)
            val defaultParams = assistantGenerationParamsFromJson(
                defaults.paramsJson,
                GenerationParams(),
                defaults.systemPrompt
            )
            return imported.copy(
                systemPrompt = systemPrompt,
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    imported.paramsJson,
                    defaultParams,
                    systemPrompt
                ).withAssistantPromptProvenance(provenance),
                characterCardJson = card.toJsonString()
            )
        }

        private fun JSONObject.cleanAssistantString(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                optString(key).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty().trim()

        private fun JSONObject.rawAssistantString(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                (opt(key) as? String)?.takeIf { it.isNotEmpty() }
            }

        private fun JSONObject.toCharacterCardPrompt(): String {
            val sections = listOf(
                "角色描述" to cleanAssistantString("description", "desc", "char_persona"),
                "性格" to cleanAssistantString("personality"),
                "场景" to cleanAssistantString("scenario", "world_scenario"),
                "开场白" to cleanAssistantString("first_mes", "firstMessage", "greeting", "char_greeting"),
                "示例对话" to cleanAssistantString("mes_example", "example_dialogue")
            ).filter { (_, value) -> value.isNotBlank() }
            if (sections.isEmpty()) return ""
            return sections.joinToString("\n\n") { (label, value) -> "$label：\n$value" }
        }

    }
}

internal fun isMcaDefaultAssistantPrompt(prompt: String): Boolean {
    // Normalize only presentation differences in the exact MCA boilerplate.
    // A default followed by authored instructions must remain untouched.
    fun compact(value: String): String = value
        .filterNot(Char::isWhitespace)
        .replace('（', '(')
        .replace('）', ')')
    return compact(prompt) == compact(GenerationParams().systemPrompt)
}

internal fun isMcaDefaultCharacterCardPrompt(card: CharacterCard): Boolean {
    val isMcaAssistantExport = card.format == CharacterCardFormat.LEGACY_JSON &&
        card.toJson().optString("schema") == "mca.assistant.card"
    return !isMcaAssistantExport && isMcaDefaultAssistantPrompt(card.systemPrompt)
}

internal fun characterCardFallbackSystemPrompt(name: String): String =
    "你是${name.trim().ifBlank { "当前角色" }}。请保持角色身份，自然回应。"

internal fun AssistantRecord.initialGreetingMessage(): ChatMessage? {
    val rawCard = characterCardJson ?: return null
    val card = (CharacterCardCodec.parseJson(rawCard) as? CharacterCardParseResult.Success)?.card
        ?: return null
    return card.firstMessage.takeIf(String::isNotBlank)?.let { greeting ->
        ChatMessage(role = Role.ASSISTANT, content = greeting)
    }
}

internal fun AssistantRecord.initialGreetingSession(
    modelMode: String?,
    modelId: String?,
    sessionId: String = UUID.randomUUID().toString(),
    capturedAt: Long = System.currentTimeMillis()
): ChatSessionRecord? = initialGreetingMessage()?.let { greeting ->
    ChatSessionRecord(
        id = sessionId,
        title = "新对话",
        messages = listOf(greeting),
        updatedAt = capturedAt,
        assistantId = id,
        assistantSnapshot = toConversationSnapshot(capturedAt),
        modelMode = modelMode,
        modelId = modelId
    )
}


class AssistantStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_assistants", Context.MODE_PRIVATE)
    private val database = McaRoomDatabase.get(appContext)

    fun loadAssistants(defaultParams: GenerationParams): List<AssistantRecord> = runBlocking(Dispatchers.IO) {
        val fallback = AssistantRecord.default(defaultParams.systemPrompt, defaultParams)
        val roomRecords = runCatching {
            database.chatSessionDao().assistantRecords()
        }.getOrElse {
            emptyList()
        }
        if (roomRecords.isNotEmpty()) {
            val normalized = normalizeAssistantRecords(roomRecords, fallback)
            if (normalized != roomRecords) {
                saveLegacyAssistants(normalized)
                runCatching {
                    database.chatSessionDao().replaceAssistants(normalized)
                }
            }
            return@runBlocking normalized
        }

        val legacyRecords = loadLegacyAssistants(fallback)
        val normalized = normalizeAssistantRecords(legacyRecords, fallback)
        saveLegacyAssistants(normalized)
        runCatching {
            database.chatSessionDao().replaceAssistants(normalized)
        }
        normalized
    }

    fun saveAssistants(assistants: List<AssistantRecord>) = runBlocking(Dispatchers.IO) {
        val normalized = assistants.distinctBy { it.id }
        // Room is the canonical store.  Do not advance the legacy mirror when
        // the canonical transaction fails, and never suppress that failure.
        database.chatSessionDao().replaceAssistants(normalized)
        saveLegacyAssistants(normalized)
    }

    /** Persists one role's default appearance while keeping the legacy JSON mirror in sync. */
    fun updateAppearance(assistantId: String, appearance: ChatAppearance): AssistantRecord? =
        runBlocking(Dispatchers.IO) {
            val current = database.chatSessionDao().assistantRecords()
            val existing = current.firstOrNull { it.id == assistantId } ?: return@runBlocking null
            val updatedRecord = existing.copy(
                appearance = appearance,
                updatedAt = System.currentTimeMillis()
            )
            val updated = current.map { if (it.id == assistantId) updatedRecord else it }
            database.chatSessionDao().replaceAssistants(updated)
            saveLegacyAssistants(updated)
            updatedRecord
        }

    private fun loadLegacyAssistants(fallback: AssistantRecord): List<AssistantRecord> {
        val raw = prefs.getString("assistants_json", null) ?: return listOf(fallback)
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                AssistantRecord.fromJson(array.getJSONObject(index), fallback)
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun saveLegacyAssistants(assistants: List<AssistantRecord>) {
        val array = JSONArray()
        assistants.forEach { array.put(it.toJson()) }
        prefs.edit().putString("assistants_json", array.toString()).apply()
    }

    fun loadSelectedAssistantId(assistants: List<AssistantRecord>): String {
        val selected = prefs.getString("selected_assistant_id", null)
        return selected?.takeIf { id -> assistants.any { it.id == id } }
            ?: AssistantRecord.DEFAULT_ID.takeIf { id -> assistants.any { it.id == id } }
            // An empty store is a damaged or first-run state, not a crash: fall back to the
            // well-known default id and let the caller recreate the assistant.
            ?: assistants.firstOrNull()?.id
            ?: AssistantRecord.DEFAULT_ID
    }

    fun saveSelectedAssistantId(id: String) {
        prefs.edit().putString("selected_assistant_id", id).apply()
    }
}

internal fun AssistantRecord.newConversationSession(
    modelMode: String?,
    modelId: String?,
    sessionId: String = UUID.randomUUID().toString(),
    capturedAt: Long = System.currentTimeMillis()
): ChatSessionRecord = initialGreetingSession(modelMode, modelId, sessionId, capturedAt)
    ?: ChatSessionRecord(
        id = sessionId,
        title = "新对话",
        messages = emptyList(),
        updatedAt = capturedAt,
        assistantId = id,
        assistantSnapshot = toConversationSnapshot(capturedAt),
        modelMode = modelMode,
        modelId = modelId
    )

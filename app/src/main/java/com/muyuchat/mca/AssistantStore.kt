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

/** Origin of the persisted prompt; missing historical metadata must never imply an authored edit. */
enum class AssistantPromptProvenance {
    IMPORTED_DERIVED,
    USER_AUTHORED,
    LEGACY_UNKNOWN;

    companion object {
        internal fun fromWireValue(value: String?): AssistantPromptProvenance =
            values().firstOrNull { it.name == value?.trim() } ?: LEGACY_UNKNOWN
    }
}

private const val ASSISTANT_PROMPT_PROVENANCE_KEY = "assistant_prompt_provenance"

/** Inert assistant metadata stored in the existing params JSON so Room needs no schema migration. */
internal fun String.withAssistantPromptProvenance(provenance: AssistantPromptProvenance): String =
    JSONObject(this).put(ASSISTANT_PROMPT_PROVENANCE_KEY, provenance.name).toString()

private fun assistantPromptProvenanceFromParamsJson(rawJson: String): AssistantPromptProvenance =
    runCatching {
        AssistantPromptProvenance.fromWireValue(JSONObject(rawJson).optString(ASSISTANT_PROMPT_PROVENANCE_KEY))
    }.getOrDefault(AssistantPromptProvenance.LEGACY_UNKNOWN)

internal fun AssistantRecord.withSystemPromptProvenance(provenance: AssistantPromptProvenance): AssistantRecord =
    copy(paramsJson = paramsJson.withAssistantPromptProvenance(provenance))

data class AssistantRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "默认助手",
    val avatar: String = "",
    val tag: String = "",
    val systemPrompt: String = GenerationParams().systemPrompt,
    val defaultModelMode: String = "follow_current",
    val defaultModelId: String? = null,
    val paramsJson: String = GenerationParams().toAssistantGenerationJson(),
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
                ).toAssistantGenerationJson(),
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

/**
 * The persona contract captured by a conversation when the user chooses an
 * assistant.  Generation controls remain live, but the system prompt and
 * assistant-scoped capabilities stay stable for the life of that conversation.
 * This is important both for conversational continuity and for a reusable
 * llama.cpp prefix cache.
 */
data class AssistantConversationSnapshot(
    val assistantId: String,
    val name: String,
    val systemPrompt: String,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val capturedAt: Long,
    /** Earlier prompt versions explicitly applied to this same role and conversation. */
    val priorSystemPromptHashes: List<String> = emptyList(),
    /** Captured independently from later profile edits; absent legacy metadata remains unknown. */
    val systemPromptProvenance: AssistantPromptProvenance = AssistantPromptProvenance.LEGACY_UNKNOWN
) {
    init {
        require(assistantId.isNotBlank()) { "Assistant snapshot requires an assistant id." }
        require(systemPrompt.isNotBlank()) { "Assistant snapshot requires a system prompt." }
        require(capturedAt >= 0L) { "Assistant snapshot capture time must not be negative." }
        require(priorSystemPromptHashes.size <= 32 && priorSystemPromptHashes.all(PrefixCacheKey::isSha256Hex)) {
            "Invalid earlier prompt fingerprints."
        }
    }

    fun applyTo(params: GenerationParams): GenerationParams =
        params.copy(systemPrompt = systemPrompt)

    fun toJsonString(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("assistantId", assistantId)
        .put("name", name)
        .put("systemPrompt", systemPrompt)
        .put("memoryEnabled", memoryEnabled)
        .put("webSearchEnabled", webSearchEnabled)
        .put("fileContextEnabled", fileContextEnabled)
        .put("capturedAt", capturedAt)
        .put("priorSystemPromptHashes", JSONArray(priorSystemPromptHashes))
        .put("systemPromptProvenance", systemPromptProvenance.name)
        .toString()

    companion object {
        const val SCHEMA = "mca.assistant.conversation_snapshot"
        const val VERSION = 1

        fun fromAssistant(
            assistant: AssistantRecord,
            capturedAt: Long = System.currentTimeMillis()
        ): AssistantConversationSnapshot {
            val prompt = assistant.systemPrompt
                .trim()
                .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
                .ifBlank { GenerationParams().systemPrompt }
            return AssistantConversationSnapshot(
                assistantId = assistant.id.trim().take(MAX_ASSISTANT_ID_CHARS)
                    .ifBlank { AssistantRecord.DEFAULT_ID },
                name = assistant.name.trim().take(MAX_ASSISTANT_NAME_CHARS).ifBlank { "Assistant" },
                systemPrompt = prompt,
                memoryEnabled = assistant.memoryEnabled,
                webSearchEnabled = assistant.webSearchEnabled,
                fileContextEnabled = assistant.fileContextEnabled,
                capturedAt = capturedAt.coerceAtLeast(0L),
                systemPromptProvenance = assistant.systemPromptProvenance
            )
        }

        fun fromJsonOrNull(raw: String?): AssistantConversationSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val schema = json.optString("schema").trim()
                require(schema.isBlank() || schema == SCHEMA) {
                    "Unsupported assistant snapshot schema."
                }
                val version = if (json.has("version")) json.optInt("version", -1) else VERSION
                require(version == VERSION) {
                    "Unsupported assistant snapshot version."
                }
                val assistantId = json.optString("assistantId")
                    .trim()
                    .take(MAX_ASSISTANT_ID_CHARS)
                val systemPrompt = json.optString("systemPrompt")
                    .trim()
                    .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
                AssistantConversationSnapshot(
                    assistantId = assistantId,
                    name = json.optString("name")
                        .trim()
                        .take(MAX_ASSISTANT_NAME_CHARS)
                        .ifBlank { "Assistant" },
                    systemPrompt = systemPrompt,
                    memoryEnabled = json.optBoolean("memoryEnabled", false),
                    webSearchEnabled = json.optBoolean("webSearchEnabled", false),
                    fileContextEnabled = !json.has("fileContextEnabled") ||
                        json.optBoolean("fileContextEnabled", true),
                    capturedAt = json.optLong("capturedAt", 0L).coerceAtLeast(0L),
                    priorSystemPromptHashes = json.optJSONArray("priorSystemPromptHashes")?.let { values ->
                        (0 until values.length().coerceAtMost(32)).mapNotNull { index ->
                            values.optString(index).takeIf(PrefixCacheKey::isSha256Hex)
                        }.distinct()
                    }.orEmpty(),
                    systemPromptProvenance = AssistantPromptProvenance.fromWireValue(
                        json.optString("systemPromptProvenance")
                    )
                )
            }.getOrNull()
        }

        private const val MAX_ASSISTANT_ID_CHARS = 128
        private const val MAX_ASSISTANT_NAME_CHARS = 96
    }
}

internal fun AssistantRecord.toConversationSnapshot(
    capturedAt: Long = System.currentTimeMillis()
): AssistantConversationSnapshot = AssistantConversationSnapshot.fromAssistant(this, capturedAt)

/**
 * Backfills a legacy conversation only when its recorded assistant still exists.
 * Existing authored snapshots stay immutable. The one exception is an untouched, exact MCA
 * boilerplate snapshot of the same external card whose persisted persona has just been recovered.
 * Unknown owners stay mixed and viewable.
 */
internal fun List<ChatSessionRecord>.withBackfilledAssistantSnapshots(
    assistants: List<AssistantRecord>
): List<ChatSessionRecord> {
    return map { session ->
        val assistant = session.assistantId
            ?.let { assistantId -> assistants.firstOrNull { it.id == assistantId } }
        val existing = session.assistantSnapshot
        if (existing != null) {
            if (!session.mixedAssistantHistory && assistant != null &&
                existing.assistantId == assistant.id && existing.priorSystemPromptHashes.isEmpty() &&
                existing.systemPromptProvenance != AssistantPromptProvenance.USER_AUTHORED &&
                isMcaDefaultAssistantPrompt(existing.systemPrompt)) {
                val recovered = restoreLegacyImportedCharacterCardPrompt(
                    assistant.copy(systemPrompt = existing.systemPrompt), GenerationParams()
                )
                if (recovered.systemPrompt != existing.systemPrompt &&
                    recovered.systemPrompt == assistant.systemPrompt) {
                    session.copy(assistantSnapshot = existing.copy(systemPrompt = recovered.systemPrompt))
                } else session
            } else session
        } else if (assistant == null) {
            session.copy(mixedAssistantHistory = true)
        } else {
            session.copy(
                assistantSnapshot = assistant.toConversationSnapshot(
                    capturedAt = session.updatedAt.coerceAtLeast(0L)
                )
            )
        }
    }
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
            ?: assistants.first().id
    }

    fun saveSelectedAssistantId(id: String) {
        prefs.edit().putString("selected_assistant_id", id).apply()
    }
}

internal fun normalizeAssistantRecords(
    records: List<AssistantRecord>,
    fallback: AssistantRecord
): List<AssistantRecord> {
    val withDefault = if (records.any { it.id == AssistantRecord.DEFAULT_ID }) {
        records
    } else {
        listOf(fallback) + records
    }
    val defaults = assistantGenerationParamsFromJson(
        fallback.paramsJson,
        GenerationParams(),
        fallback.systemPrompt
    )
    return withDefault
        .distinctBy { it.id }
        .map { assistant ->
            val restored = restoreLegacyImportedCharacterCardPrompt(assistant, defaults)
            restored.copy(
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    restored.paramsJson,
                    defaults,
                    restored.systemPrompt
                )
            )
        }
        .ifEmpty { listOf(fallback) }
}

/**
 * A narrow lazy migration for old external character cards that were persisted with the exact
 * MCA boilerplate instead of the persona prompt derived from the retained source card.
 *
 * The raw card is kept byte-for-byte. Only an exact boilerplate prompt is eligible, and explicit
 * MCA assistant-card exports are excluded because their boilerplate is intentional. An explicit
 * USER_AUTHORED record always stays untouched, even when its prompt equals the default exactly.
 * Records without metadata remain LEGACY_UNKNOWN; this migration never guesses earlier edits.
 * A prompt with any authored suffix, or a record without a retained source card, stays untouched.
 */
internal fun restoreLegacyImportedCharacterCardPrompt(
    assistant: AssistantRecord,
    defaults: GenerationParams
): AssistantRecord {
    if (assistant.systemPromptProvenance == AssistantPromptProvenance.USER_AUTHORED ||
        assistant.id == AssistantRecord.DEFAULT_ID || !isMcaDefaultAssistantPrompt(assistant.systemPrompt)) {
        return assistant
    }
    val rawCard = assistant.characterCardJson?.takeIf(String::isNotBlank) ?: return assistant
    val card = (CharacterCardCodec.parseJson(rawCard) as? CharacterCardParseResult.Success)?.card
        ?: return assistant
    if (!isMcaDefaultCharacterCardPrompt(card)) return assistant
    val restored = card.toAssistantRecord(AssistantRecord.default(defaults.systemPrompt, defaults))
    val restoredPrompt = restored.systemPrompt
        .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
        .takeIf(String::isNotBlank)
        ?: return assistant
    if (isMcaDefaultAssistantPrompt(restoredPrompt)) return assistant
    return assistant.copy(
        systemPrompt = restoredPrompt,
        paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
            assistant.paramsJson,
            defaults,
            restoredPrompt
        )
    )
}

/**
 * Migrates an old assistant card without losing the two execution values that historically lived
 * in its params JSON. They are retained only for lossless export/import compatibility; all
 * runtime application paths use [assistantGenerationParamsFromJson], which deliberately copies
 * generation fields onto the caller's model execution defaults and therefore ignores these keys.
 */
internal fun sanitizeAssistantParamsJsonPreservingLegacyExecution(
    rawJson: String,
    defaults: GenerationParams,
    systemPrompt: String
): String {
    val sanitized = runCatching {
        JSONObject(sanitizeAssistantParamsJson(rawJson, defaults, systemPrompt))
    }.getOrElse {
        return sanitizeAssistantParamsJson(rawJson, defaults, systemPrompt)
    }
    val raw = runCatching { JSONObject(rawJson) }.getOrNull() ?: return sanitized.toString()
    if (raw.has(ASSISTANT_PROMPT_PROVENANCE_KEY)) {
        sanitized.put(
            ASSISTANT_PROMPT_PROVENANCE_KEY,
            assistantPromptProvenanceFromParamsJson(rawJson).name
        )
    }
    LEGACY_EXECUTION_INT_FIELDS.forEach { (canonical, aliases) ->
        val value = aliases.firstNotNullOfOrNull { key ->
            if (raw.has(key) && !raw.isNull(key)) {
                raw.optInt(key, Int.MIN_VALUE).takeIf { it > 0 }
            } else {
                null
            }
        }
        value?.let { sanitized.put(canonical, it) }
    }
    return sanitized.toString()
}

private val LEGACY_EXECUTION_INT_FIELDS: List<Pair<String, List<String>>> = listOf(
    "n_ctx" to listOf("n_ctx", "nCtx"),
    "n_threads" to listOf("n_threads", "nThreads")
)

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

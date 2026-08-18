package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.GenerationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    val webSearchEnabled: Boolean = false,
    val fileContextEnabled: Boolean = true,
    /** Optional role-card background; copied into app-private storage by the UI. */
    val appearance: ChatAppearance = ChatAppearance(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", "mca.assistant.card")
        .put("version", 1)
        .put("id", id)
        .put("name", name)
        .put("avatar", avatar)
        .put("tag", tag)
        .put("systemPrompt", systemPrompt)
        .put("defaultModelMode", defaultModelMode)
        .put("defaultModelId", defaultModelId)
        .put("paramsJson", paramsJson)
        .apply { characterCardJson?.let { put("characterCardJson", it) } }
        .put("memoryEnabled", memoryEnabled)
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
            val rawParams = json.cleanAssistantString("paramsJson", "params_json")
                .ifBlank { source.cleanAssistantString("paramsJson", "params_json") }
                .ifBlank { defaults.paramsJson }
            val defaultParams = assistantGenerationParamsFromJson(
                defaults.paramsJson,
                GenerationParams(),
                defaults.systemPrompt
            )
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
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    rawParams,
                    defaultParams,
                    systemPrompt
                ),
                characterCardJson = json.rawAssistantString("characterCardJson", "character_card_json"),
                memoryEnabled = json.optBoolean("memoryEnabled", defaults.memoryEnabled),
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
            val systemPrompt = listOf(
                card.systemPrompt,
                cardData.toCharacterCardPrompt(),
                card.postHistoryInstructions
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n\n")
                .ifBlank { imported.systemPrompt }
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
                ),
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
    val capturedAt: Long
) {
    init {
        require(assistantId.isNotBlank()) { "Assistant snapshot requires an assistant id." }
        require(systemPrompt.isNotBlank()) { "Assistant snapshot requires a system prompt." }
        require(capturedAt >= 0L) { "Assistant snapshot capture time must not be negative." }
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
                capturedAt = capturedAt.coerceAtLeast(0L)
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
                    capturedAt = json.optLong("capturedAt", 0L).coerceAtLeast(0L)
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
 * Backfills durable persona snapshots for conversations created before the
 * snapshot schema.  A missing or removed assistant falls back to the default
 * assistant only for that legacy migration; existing snapshots are untouched.
 */
internal fun List<ChatSessionRecord>.withBackfilledAssistantSnapshots(
    assistants: List<AssistantRecord>
): List<ChatSessionRecord> {
    val fallback = assistants.firstOrNull { it.id == AssistantRecord.DEFAULT_ID }
        ?: assistants.firstOrNull()
        ?: return this
    return map { session ->
        if (session.assistantSnapshot != null) {
            session
        } else {
            val assistant = session.assistantId
                ?.let { assistantId -> assistants.firstOrNull { it.id == assistantId } }
                ?: fallback
            session.copy(
                assistantId = session.assistantId ?: assistant.id,
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
            assistant.copy(
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    assistant.paramsJson,
                    defaults,
                    assistant.systemPrompt
                )
            )
        }
        .ifEmpty { listOf(fallback) }
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

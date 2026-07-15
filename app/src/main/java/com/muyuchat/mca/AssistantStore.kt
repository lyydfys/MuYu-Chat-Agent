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
    val memoryEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val fileContextEnabled: Boolean = true,
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
        .put("memoryEnabled", memoryEnabled)
        .put("webSearchEnabled", webSearchEnabled)
        .put("fileContextEnabled", fileContextEnabled)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        const val DEFAULT_ID = "default"

        fun default(systemPrompt: String = GenerationParams().systemPrompt, params: GenerationParams = GenerationParams()): AssistantRecord =
            AssistantRecord(
                id = DEFAULT_ID,
                name = "默认助手",
                systemPrompt = systemPrompt.ifBlank { GenerationParams().systemPrompt },
                paramsJson = params.copy(systemPrompt = systemPrompt.ifBlank { GenerationParams().systemPrompt }).toAssistantGenerationJson(),
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
                name = json.cleanAssistantString("name", "title")
                    .ifBlank { source.cleanAssistantString("name", "title") }
                    .ifBlank { defaults.name },
                avatar = json.cleanAssistantString("avatar", "emoji")
                    .ifBlank { source.cleanAssistantString("avatar", "emoji") }
                    .ifBlank { defaults.avatar },
                tag = json.cleanAssistantString("tag", "category")
                    .ifBlank { source.cleanAssistantString("tag", "category", "creator", "creator_notes") }
                    .ifBlank { defaults.tag },
                systemPrompt = systemPrompt.take(MAX_ASSISTANT_PROMPT_CHARS),
                defaultModelMode = json.cleanAssistantString("defaultModelMode", "default_model_mode")
                    .ifBlank { defaults.defaultModelMode },
                defaultModelId = json.cleanAssistantString("defaultModelId", "default_model_id")
                    .takeIf { it.isNotBlank() && it != "null" },
                paramsJson = sanitizeAssistantParamsJsonPreservingLegacyExecution(
                    rawParams,
                    defaultParams,
                    systemPrompt
                ),
                memoryEnabled = json.optBoolean("memoryEnabled", defaults.memoryEnabled),
                webSearchEnabled = json.optBoolean("webSearchEnabled", defaults.webSearchEnabled),
                fileContextEnabled = json.optBoolean("fileContextEnabled", defaults.fileContextEnabled),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        private fun JSONObject.cleanAssistantString(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                optString(key).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty().trim()

        private fun JSONObject.toCharacterCardPrompt(): String {
            val sections = listOf(
                "角色描述" to cleanAssistantString("description", "desc"),
                "性格" to cleanAssistantString("personality"),
                "场景" to cleanAssistantString("scenario"),
                "开场白" to cleanAssistantString("first_mes", "firstMessage", "greeting"),
                "示例对话" to cleanAssistantString("mes_example", "example_dialogue")
            ).filter { (_, value) -> value.isNotBlank() }
            if (sections.isEmpty()) return ""
            return sections.joinToString("\n\n") { (label, value) -> "$label：\n$value" }
        }

        private const val MAX_ASSISTANT_PROMPT_CHARS = 12_000
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
        saveLegacyAssistants(normalized)
        runCatching {
            database.chatSessionDao().replaceAssistants(normalized)
        }
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

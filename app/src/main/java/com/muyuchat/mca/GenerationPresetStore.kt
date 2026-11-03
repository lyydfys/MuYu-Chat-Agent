package com.muyuchat.mca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A named model preset plus the local/cloud chat models it is bound to. */
data class GenerationPresetRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val paramsJson: String,
    val boundLocalModelIds: Set<String> = emptySet(),
    val boundCloudModelIds: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("paramsJson", paramsJson)
        .put("boundLocalModelIds", JSONArray(boundLocalModelIds.sorted()))
        .put("boundCloudModelIds", JSONArray(boundCloudModelIds.sorted()))
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): GenerationPresetRecord? {
            val id = json.optString("id").trim().takeIf(String::isNotBlank) ?: return null
            val name = json.optString("name").trim().takeIf(String::isNotBlank) ?: return null
            val paramsJson = json.optString("paramsJson").trim().takeIf(String::isNotBlank) ?: return null
            if (runCatching { JSONObject(paramsJson) }.isFailure) return null
            return GenerationPresetRecord(
                id = id,
                name = name.take(64),
                paramsJson = paramsJson,
                boundLocalModelIds = json.optJSONArray("boundLocalModelIds").toStringSet(),
                boundCloudModelIds = json.optJSONArray("boundCloudModelIds").toStringSet(),
                createdAt = json.optLong("createdAt", 0L).coerceAtLeast(0L),
                updatedAt = json.optLong("updatedAt", 0L).coerceAtLeast(0L)
            )
        }

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return buildSet {
                for (index in 0 until length()) {
                    optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}

/**
 * SharedPreferences-backed named model presets.  Model profiles remain the runtime authority;
 * applying a preset copies its semantic fields into the selected models' profiles.
 */
internal class GenerationPresetStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): List<GenerationPresetRecord> = synchronized(lock) {
        val raw = preferences.getString(PRESETS_KEY, null) ?: return@synchronized emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@synchronized emptyList()
        if (root.optString("schema", SCHEMA) != SCHEMA) return@synchronized emptyList()
        val values = root.optJSONArray("presets") ?: return@synchronized emptyList()
        buildList {
            for (index in 0 until values.length()) {
                values.optJSONObject(index)
                    ?.let(GenerationPresetRecord::fromJson)
                    ?.let(::add)
            }
        }
    }

    fun save(records: List<GenerationPresetRecord>) = synchronized(lock) {
        val values = JSONArray()
        records.forEach { values.put(it.toJson()) }
        preferences.edit()
            .putString(
                PRESETS_KEY,
                JSONObject().put("schema", SCHEMA).put("presets", values).toString()
            )
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "mca_generation_presets_v1"
        private const val PRESETS_KEY = "presets_json"
        private const val SCHEMA = "mca.generation.presets.v1"
    }
}

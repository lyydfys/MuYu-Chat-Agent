package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject

/**
 * Stable identity used for chat generation settings.
 *
 * Runtime/execution profiles already isolate load and device parameters.  This
 * store deliberately covers only user-facing generation semantics (sampling,
 * output budget and reasoning), so changing a chat model does not overwrite a
 * different model's preferred settings or native load profile.
 */
internal object ModelGenerationProfileKey {
    private const val LOCAL_PREFIX = "local:"
    private const val CLOUD_PREFIX = "cloud:"

    fun local(modelId: String): String = prefixed(LOCAL_PREFIX, modelId)

    fun cloud(modelId: String): String = prefixed(CLOUD_PREFIX, modelId)

    private fun prefixed(prefix: String, modelId: String): String {
        val clean = modelId.trim()
        require(clean.isNotEmpty()) { "Model id must not be blank." }
        return prefix + clean
    }
}

/** Semantic fields persisted per model; persona/system text remains assistant-scoped. */
internal fun GenerationParams.toModelGenerationProfileJson(): JSONObject = JSONObject()
    .put("n_predict", nPredict.coerceAtLeast(1))
    .put("temperature", temperature)
    .put("top_k", topK)
    .put("top_p", topP)
    .put("min_p", minP)
    .put("repeat_penalty", repeatPenalty)
    .put("presence_penalty", presencePenalty)
    .put("frequency_penalty", frequencyPenalty)
    .apply {
        // Keep an explicit null so clearing a seed on one model does not
        // accidentally inherit a different model's seed on the next switch.
        put("seed", seed ?: JSONObject.NULL)
        put("stop_words", org.json.JSONArray(stopWords))
        put("reasoning_mode", reasoningMode.name.lowercase())
        put("hide_reasoning", hideReasoning)
        // Native execution choices are model-scoped as well.  Keep the advanced
        // object here so a GGUF model's CPU/GPU/custom layer policy survives a
        // switch to another model and back.
        put(
            "advanced_json",
            runCatching { JSONObject(advancedJson.ifBlank { "{}" }) }
                .getOrElse { JSONObject() }
        )
    }

/**
 * Restores a model profile on top of [defaults].  Load-bound fields and the
 * current assistant's system prompt are intentionally retained from defaults.
 */
internal fun modelGenerationParamsFromJson(
    json: JSONObject,
    defaults: GenerationParams
): GenerationParams {
    val parsed = GenerationParams.fromJson(json, defaults)
    val profileSeed = when {
        !json.has("seed") -> defaults.seed
        json.isNull("seed") -> null
        else -> json.optInt("seed")
    }
    return defaults.copy(
        nPredict = parsed.nPredict,
        temperature = parsed.temperature,
        topK = parsed.topK,
        topP = parsed.topP,
        minP = parsed.minP,
        repeatPenalty = parsed.repeatPenalty,
        presencePenalty = parsed.presencePenalty,
        frequencyPenalty = parsed.frequencyPenalty,
        seed = profileSeed,
        stopWords = parsed.stopWords,
        reasoningMode = parsed.reasoningMode,
        hideReasoning = parsed.hideReasoning,
        advancedJson = if (json.has("advanced_json")) parsed.advancedJson else defaults.advancedJson
    )
}

internal data class ModelGenerationProfileDocument(
    val schema: String = SCHEMA,
    val updatedAt: Long,
    val paramsJson: JSONObject
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("updatedAt", updatedAt)
        .put("params", paramsJson)

    companion object {
        const val SCHEMA = "mca.model.generation.profile.v1"

        fun fromJson(json: JSONObject): ModelGenerationProfileDocument? {
            if (json.optString("schema", SCHEMA) != SCHEMA) return null
            val params = json.optJSONObject("params") ?: return null
            return ModelGenerationProfileDocument(
                updatedAt = json.optLong("updatedAt", 0L).coerceAtLeast(0L),
                paramsJson = JSONObject(params.toString())
            )
        }
    }
}

/**
 * Small SharedPreferences-backed store.  It is intentionally independent from
 * [ModelRuntimeProfileStore], whose identity is tied to native execution and
 * device capability rather than user sampling preferences.
 */
internal class ModelGenerationProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(modelKey: String, defaults: GenerationParams): GenerationParams = synchronized(lock) {
        val document = readProfiles()[modelKey] ?: return@synchronized defaults
        runCatching { modelGenerationParamsFromJson(document.paramsJson, defaults) }
            .getOrDefault(defaults)
    }

    /** Loads an existing profile or creates a compatible first profile from legacy global values. */
    fun loadOrCreate(modelKey: String, defaults: GenerationParams): GenerationParams = synchronized(lock) {
        val profiles = readProfiles()
        val existing = profiles[modelKey]
        if (existing != null) {
            return@synchronized runCatching {
                modelGenerationParamsFromJson(existing.paramsJson, defaults)
            }.getOrDefault(defaults)
        }
        profiles[modelKey] = ModelGenerationProfileDocument(
            updatedAt = System.currentTimeMillis(),
            paramsJson = defaults.toModelGenerationProfileJson()
        )
        writeProfiles(profiles)
        defaults
    }

    fun save(modelKey: String, params: GenerationParams) = synchronized(lock) {
        val profiles = readProfiles()
        profiles[modelKey] = ModelGenerationProfileDocument(
            updatedAt = System.currentTimeMillis(),
            paramsJson = params.toModelGenerationProfileJson()
        )
        writeProfiles(profiles)
    }

    fun contains(modelKey: String): Boolean = synchronized(lock) {
        readProfiles().containsKey(modelKey)
    }

    /** Copy an old local-model profile without overwriting an existing target choice. */
    fun migrateLocalModelIds(idMapping: Map<String, String>) = synchronized(lock) {
        val profiles = readProfiles()
        var changed = false
        idMapping.forEach { (oldId, newId) ->
            if (oldId == newId) return@forEach
            val oldKey = ModelGenerationProfileKey.local(oldId)
            val newKey = ModelGenerationProfileKey.local(newId)
            val old = profiles[oldKey] ?: return@forEach
            if (newKey !in profiles) {
                profiles[newKey] = old
                changed = true
            }
        }
        if (changed) writeProfiles(profiles, durable = true)
    }

    fun remove(modelKey: String) = synchronized(lock) {
        val profiles = readProfiles()
        if (profiles.remove(modelKey) != null) writeProfiles(profiles)
    }

    fun clear() = synchronized(lock) {
        preferences.edit().remove(PROFILES_KEY).apply()
    }

    private fun readProfiles(): MutableMap<String, ModelGenerationProfileDocument> {
        val raw = preferences.getString(PROFILES_KEY, null) ?: return linkedMapOf()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        if (root.optString("schema", SCHEMA) != SCHEMA) return linkedMapOf()
        val values = root.optJSONObject("profiles") ?: return linkedMapOf()
        return linkedMapOf<String, ModelGenerationProfileDocument>().apply {
            values.keys().forEach { key ->
                ModelGenerationProfileDocument.fromJson(values.optJSONObject(key) ?: return@forEach)
                    ?.let { put(key, it) }
            }
        }
    }

    private fun writeProfiles(profiles: Map<String, ModelGenerationProfileDocument>, durable: Boolean = false) {
        val values = JSONObject()
        profiles.forEach { (key, document) -> values.put(key, document.toJson()) }
        val edit = preferences.edit()
            .putString(
                PROFILES_KEY,
                JSONObject().put("schema", SCHEMA).put("profiles", values).toString()
            )
        if (durable) check(edit.commit()) { "Model generation profile migration was not persisted." }
        else edit.apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "mca_model_generation_profiles_v1"
        private const val PROFILES_KEY = "profiles_json"
        private const val SCHEMA = "mca.model.generation.profiles.v1"
    }
}

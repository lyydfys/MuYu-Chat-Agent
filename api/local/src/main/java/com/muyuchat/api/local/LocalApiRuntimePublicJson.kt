package com.muyuchat.api.local

import org.json.JSONObject

internal fun LocalApiRuntime.modelsJsonImpl(): String {
        val supplied = runCatching { modelsJsonProvider() }.getOrDefault("{}")
        val root = supplied.toJsonObjectOrNull()
        val source = root?.optJSONArray("data")
            ?: runCatching { org.json.JSONArray(supplied) }.getOrNull()
            ?: org.json.JSONArray()
        val loaded = runCatching { loadedModelJsonProvider().toJsonObjectOrNull() }.getOrNull()
        val profile = runCatching { profileJson().toJsonObjectOrNull() }.getOrNull()
        val tuning = runCatching { tuningJson().toJsonObjectOrNull() }.getOrNull()
        val perModelStates = runCatching { modelRuntimeStatesJsonProvider() }
            .getOrDefault("{}")
            .toJsonObjectOrNull()
        val activeModelId = profile?.firstString("modelId")
            .orEmpty()
            .ifBlank { loaded?.firstString("id", "modelId").orEmpty() }
        val activeContextLength = activeContextLength(loaded)
        val result = org.json.JSONArray()

        for (index in 0 until source.length()) {
            val raw = source.optJSONObject(index) ?: continue
            val item = raw
                .publicCopy(stripExecutionState = true)
                .withPublicModelNameAliases()
            val modelId = item.firstString("id", "modelId").orEmpty()
            val displayName = item.firstString("displayName", "display_name", "name")
            val publicModelId = localApiPublicModelId(displayName, modelId)
            if (publicModelId.isNotBlank()) item.put("id", publicModelId)
            if (modelId.isNotBlank()) item.put("internal_id", modelId)
            item.put(
                "aliases",
                org.json.JSONArray().apply {
                    listOf(modelId, displayName.orEmpty(), publicModelId)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .forEach(::put)
                }
            )
            if (item.isImageGenerationCatalogEntry()) {
                // Image packages have their own worker lifecycle and execution evidence. Do not
                // synthesize chat profile/tuning/loaded fields onto them; retain only the producer's
                // already-sanitized discovery contract.
                result.put(item)
                continue
            }
            val active = modelId.isNotBlank() && modelId == activeModelId
            val persisted = perModelStates.stateForModel(modelId)
            val activeProfile = if (active) profile else null
            val activeTuning = if (
                active || tuning?.firstString("modelId").orEmpty() == modelId
            ) tuning else null

            item.putNullable(
                "profileId",
                (activeProfile?.firstValue("activeProfileId", "profileId")
                    ?: persisted?.firstValue("profileId")).publicScalar()
            )
            item.put(
                "profileRecordState",
                activeProfile?.firstString("profileRecordState", "recordState")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("profileRecordState", "recordState")
                            .orEmpty()
                    }
                    .publicStateOr("none")
            )
            item.put(
                "profileVerificationLevel",
                activeProfile?.firstString("profileVerificationLevel", "verification")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("profileVerificationLevel", "verification")
                            .orEmpty()
                    }
                    .publicStateOr("unknown")
            )
            item.put(
                "reloadRequired",
                activeProfile?.firstBoolean("reloadRequired")
                    ?: persisted?.firstBoolean("reloadRequired")
                    ?: false
            )
            item.put(
                "engineLifecycle",
                activeProfile?.firstString("engineLifecycle", "lifecycle")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("engineLifecycle", "lifecycle").orEmpty()
                    }
                    .publicStateOr(if (active) "unknown" else "unloaded")
            )
            item.put(
                "tuningJobState",
                activeTuning?.firstString("tuningJobState", "state")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("tuningJobState").orEmpty()
                    }
                    .publicStateOr("idle")
            )
            // Short aliases keep the catalog self-describing for clients that consume the
            // coordinator vocabulary, while the profile-prefixed names remain explicit.
            item.put("recordState", item.optString("profileRecordState"))
            item.put("verification", item.optString("profileVerificationLevel"))
            item.put("lifecycle", item.optString("engineLifecycle"))
            val lifecycle = item.optString("engineLifecycle").lowercase()
            val isLoaded = active && lifecycle !in NON_LOADED_LIFECYCLES
            item.put("loaded", isLoaded)
            if (isLoaded && activeContextLength != null) {
                // These aliases intentionally expose the user's logical context window. llama.cpp
                // may pad the native allocation (for example 8190 -> 8192), but replacing the
                // configured value with that transport detail would silently rewrite custom n_ctx.
                item.put("n_ctx", activeContextLength)
                item.put("context_length", activeContextLength)
                item.put("max_context_length", activeContextLength)
            }
            result.put(item)
        }
        return JSONObject()
            .put("object", root?.optString("object").orEmpty().ifBlank { "list" })
            .put("data", result)
            .toString()
    }
internal fun LocalApiRuntime.imageTextualInversionsJsonImpl(): String {
        val supplied = runCatching { imageTextualInversionsJsonProvider() }.getOrDefault("[]")
        val root = supplied.toJsonObjectOrNull()
        val source = root?.optJSONArray("data")
            ?: runCatching { org.json.JSONArray(supplied) }.getOrNull()
            ?: org.json.JSONArray()
        val result = org.json.JSONArray()
        val uuidPattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
        val triggerPattern = Regex("^[A-Za-z0-9_:#<>|.-]{1,64}$")
        val sha256Pattern = Regex("^[0-9a-f]{64}$")
        val formats = setOf("safetensors", "pytorch", "checkpoint", "binary")

        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val id = item.optString("id").trim().lowercase()
            val name = item.optString("name").trim()
            val trigger = item.optString("trigger").trim()
            val format = item.optString("format").trim().lowercase()
            val sha256 = item.optString("sha256").trim().lowercase()
            val sizeBytes = item.optLong("sizeBytes", -1L)
            val importedAt = item.optLong("importedAt", 0L)
            if (!uuidPattern.matches(id) || name.isEmpty() || name.length > 128 ||
                !triggerPattern.matches(trigger) || format !in formats ||
                !sha256Pattern.matches(sha256) || sizeBytes !in 1L..100L * 1024L * 1024L ||
                importedAt < 0L
            ) {
                continue
            }
            result.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "textual_inversion")
                    .put("name", name)
                    .put("trigger", trigger)
                    .put("format", format)
                    .put("sha256", sha256)
                    .put("size_bytes", sizeBytes)
                    .put("created", importedAt / 1_000L)
            )
        }
        return JSONObject()
            .put("object", root?.optString("object").orEmpty().ifBlank { "list" })
            .put("data", result)
            .toString()
    }
private fun LocalApiRuntime.activeContextLength(loaded: JSONObject?): Int? {
        val configured = runCatching { generationParamsProvider().nCtx }
            .getOrNull()
            ?.takeIf { it > 0 }
        if (configured != null) return configured

        return loaded?.firstPositiveInt("n_ctx", "context_length", "max_context_length")
            ?: loaded?.optJSONObject("stats")
                ?.firstPositiveInt("nCtx", "n_ctx", "maxAllTokens", "max_all_tokens")
            ?: nativeStatsObject()
                ?.firstPositiveInt("nCtx", "n_ctx", "maxAllTokens", "max_all_tokens")
    }

    private fun JSONObject?.stateForModel(modelId: String): JSONObject? {
        if (this == null || modelId.isBlank()) return null
        optJSONObject(modelId)?.let { return it }
        val data = optJSONArray("data") ?: return null
        for (index in 0 until data.length()) {
            val state = data.optJSONObject(index) ?: continue
            if (state.firstString("modelId", "id") == modelId) return state
        }
        return null
    }

    internal fun JSONObject.publicCopy(stripExecutionState: Boolean = false): JSONObject {
        val copy = JSONObject()
        keys().asSequence().forEach { key ->
            val normalized = key.lowercase().replace("_", "").replace("-", "")
            if (normalized in PRIVATE_JSON_KEYS) return@forEach
            if (normalized.isSensitiveJsonKey()) return@forEach
            if (stripExecutionState && normalized in CATALOG_EXECUTION_KEYS) return@forEach
            when (val value = opt(key)) {
                is JSONObject -> copy.put(key, value.publicCopy(stripExecutionState))
                is org.json.JSONArray -> copy.put(key, value.publicCopy(stripExecutionState))
                is String -> copy.put(key, if (value.looksLikeAbsolutePath()) REDACTED else value)
                else -> copy.put(key, value)
            }
        }
        return copy
    }

    internal fun org.json.JSONArray.publicCopy(stripExecutionState: Boolean): org.json.JSONArray {
        val copy = org.json.JSONArray()
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is JSONObject -> copy.put(value.publicCopy(stripExecutionState))
                is org.json.JSONArray -> copy.put(value.publicCopy(stripExecutionState))
                is String -> copy.put(if (value.looksLikeAbsolutePath()) REDACTED else value)
                else -> copy.put(value)
            }
        }
        return copy
    }

    internal fun JSONObject.firstValue(vararg keys: String): Any? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return opt(key)
        }
        return null
    }

    private fun JSONObject.isImageGenerationCatalogEntry(): Boolean =
        optString("type").equals("image_generation", ignoreCase = true) ||
            optJSONObject("capabilities")?.optBoolean("image_generation", false) == true

    private fun JSONObject.withPublicModelNameAliases(): JSONObject {
        val displayName = firstString("displayName", "display_name", "name") ?: return this
        put("name", displayName)
        put("display_name", displayName)
        put("displayName", displayName)
        return this
    }

    internal fun JSONObject.firstString(vararg keys: String): String? =
        keys.asSequence()
            .filter { has(it) && !isNull(it) }
            .map { optString(it).trim() }
            .firstOrNull { it.isNotBlank() }

    private fun JSONObject.firstPositiveInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = when (val raw = opt(key)) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            if (value != null && value > 0) return value
        }
        return null
    }

    internal fun JSONObject.putIfMissing(key: String, value: Any?) {
        if (!has(key) || isNull(key)) putNullable(key, value)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    internal fun Any?.publicScalar(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is String -> takeIf(PUBLIC_TOKEN_PATTERN::matches)
        is Number, is Boolean -> this
        else -> null
    }

    private fun String.publicStateOr(fallback: String): String =
        trim().takeIf(PUBLIC_TOKEN_PATTERN::matches) ?: fallback

    private fun String.isSensitiveJsonKey(): Boolean =
        contains("rawoutput") ||
            contains("configjson") ||
            (contains("prompt") && !endsWith("tokens"))

    internal fun String.toJsonObjectOrNull(): JSONObject? =
        runCatching { JSONObject(this) }.getOrNull()

    internal fun String.looksLikeAbsolutePath(): Boolean =
        WINDOWS_ABSOLUTE_PATH.containsMatchIn(this) ||
            contains("\\\\") ||
            ANDROID_OR_UNIX_ABSOLUTE_PATH.containsMatchIn(this)

    private val PRIVATE_JSON_KEYS = setOf(
        "path",
        "absolutepath",
        "modelpath",
        "localpath",
        "bundlepath",
        "filepath",
        "apikey",
        "authorization",
        "bearer",
        "token",
        "authtoken",
        "accesstoken",
        "refreshtoken",
        "secret",
        "password",
        "credential",
        "credentials",
        "prompt",
        "rawprompt",
        "systemprompt",
        "userprompt",
        "messages",
        "chatmessages",
        "chatrequest",
        "requestbody",
        "responsebody",
        "session",
        "sessionid",
        "usercontent",
        "identityhash",
        "installationscopeid",
        "fingerprint",
        "androidid",
        "deviceid",
        "androidserial",
        "serialnumber",
        "imei",
        "meid"
    )
    private val CATALOG_EXECUTION_KEYS = setOf(
        "contextlength",
        "maxcontextlength",
        "nctx",
        "maxoutputtokens",
        "profileid",
        "profilerecordstate",
        "profileverificationlevel",
        "verification",
        "reloadrequired",
        "enginelifecycle",
        "tuningjobstate"
    )
    private val NON_LOADED_LIFECYCLES = setOf("unloaded", "loading", "stopping", "error")
    private const val REDACTED = "[redacted]"
    internal val PUBLIC_TOKEN_PATTERN = Regex("[A-Za-z0-9._:-]{1,256}")
    private val WINDOWS_ABSOLUTE_PATH = Regex("(?i)(?:^|[^A-Za-z0-9])[A-Z]:[\\\\/]")
    private val ANDROID_OR_UNIX_ABSOLUTE_PATH =
        Regex("(?i)(?:^|[\\s=:\\[])/(?!v\\d+(?:/|$))[A-Za-z0-9._-]+/")

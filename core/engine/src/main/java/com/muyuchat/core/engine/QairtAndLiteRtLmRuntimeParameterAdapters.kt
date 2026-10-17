package com.muyuchat.core.engine

import org.json.JSONObject

class QairtRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.GENIEX_QAIRT, registry) {
    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf("backend" to "qairt") to emptyMap()

    override fun activeValuesFromStats(root: JSONObject): JSONObject? =
        root.optJSONObject("effectiveConfig")

    override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        val backend = root.optString("backend")
        val devices = root.opt("backendDevices")?.toString().orEmpty()
        if (!root.optBoolean("loaded", false) ||
            !backend.contains("qairt", ignoreCase = true) ||
            !(devices.contains("qairt", ignoreCase = true) &&
                (devices.contains("htp", ignoreCase = true) || devices.contains("npu", ignoreCase = true)))
        ) return null
        return ActiveLoadedSignature.of(identity, expected.values)
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("signature", ignoreCase = true)
}

/**
 * Parameter adapter for the Kotlin LiteRT-LM Engine API.
 *
 * LiteRT-LM does not expose the llama.cpp/MNN style native config readback on
 * every release. We still validate the concrete load state that is available
 * (the session is loaded and reports the requested backend), and use the
 * immutable, coordinator-resolved profile for the remaining fields when the
 * runner cannot echo them. This keeps profile authorization useful without
 * making a device allowlist a prerequisite for loading a model.
 */
class LiteRtLmRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.LITERT_LM, registry) {
    override val aliases: Map<String, String> = mapOf(
        "n_ctx" to "max_num_tokens",
        "maxNumTokens" to "max_num_tokens",
        "backend_type" to "backend",
        "backendType" to "backend",
        "cacheDir" to "cache_dir",
        "cache_directory" to "cache_dir",
        "thread_count" to "n_threads",
        "threadCount" to "n_threads"
    )

    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf(
            "backend" to "cpu",
            "max_num_tokens" to 4096,
            "n_threads" to 4
        ) to emptyMap()

    override fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) {
        val rawBackend = requestedLoad["backend"]
        val normalizedBackend = canonicalBackend(rawBackend?.toString()) ?: "cpu"
        if (rawBackend != null &&
            !canonicalBackend(rawBackend.toString()).equals(normalizedBackend, ignoreCase = true)
        ) {
            warnings += "backend normalized to $normalizedBackend because LiteRT-LM supports cpu, gpu, npu, or google_tensor"
        } else if (rawBackend != null && rawBackend.toString() != normalizedBackend) {
            warnings += "backend normalized to $normalizedBackend"
        }
        requestedLoad["backend"] = normalizedBackend
        sourceByField["backend"] = if (rawBackend == null) "runtime-default" else "runtime-safety"

        val rawMaxTokens = requestedLoad["max_num_tokens"]
        val maxTokens = integerValue(rawMaxTokens)
        if (maxTokens == null || maxTokens < 1) {
            requestedLoad["max_num_tokens"] = 4096
            sourceByField["max_num_tokens"] = "runtime-safety"
            if (rawMaxTokens != null) {
                warnings += "max_num_tokens normalized to 4096 because it must be a positive integer"
            }
        } else {
            requestedLoad["max_num_tokens"] = maxTokens
        }

        val rawThreads = requestedLoad["n_threads"]
        val threads = integerValue(rawThreads)
        if (threads == null || threads < 1) {
            requestedLoad["n_threads"] = 4
            sourceByField["n_threads"] = "runtime-safety"
            if (rawThreads != null) {
                warnings += "n_threads normalized to 4 because it must be a positive integer"
            }
        } else {
            requestedLoad["n_threads"] = threads
        }

        val rawCacheDir = requestedLoad["cache_dir"]
        if (rawCacheDir == null || rawCacheDir == JSONObject.NULL || rawCacheDir.toString().trim().isEmpty()) {
            requestedLoad.remove("cache_dir")
            sourceByField.remove("cache_dir")
        } else if (rawCacheDir !is String) {
            requestedLoad["cache_dir"] = rawCacheDir.toString()
        }
    }

    override fun activeValuesFromStats(root: JSONObject): JSONObject? {
        val config = configObject(root) ?: JSONObject()
        val values = linkedMapOf<String, Any?>()

        fun copyKnown(source: JSONObject, onlyIfAbsent: Boolean = false) {
            source.keys().forEach { key ->
                val target = aliases[key] ?: key
                if (!onlyIfAbsent || !values.containsKey(target)) {
                    values[target] = source.opt(key)
                }
            }
        }

        copyKnown(config)
        // Some LiteRT-LM releases put backend/effective limits at the stats
        // root instead of inside effectiveConfig. Preserve either shape.
        copyKnown(root, onlyIfAbsent = true)

        if (values.isEmpty()) return null
        return JSONObject().apply {
            values.forEach { (field, value) ->
                when (field) {
                    "backend" -> canonicalBackend(value?.toString())?.let { put(field, it) }
                    "max_num_tokens", "n_threads" -> integerValue(value)?.let { put(field, it) }
                    else -> if (value != null && value != JSONObject.NULL) put(field, value)
                }
            }
        }
    }

    override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        if (expected.identityHash != identity.identityHash) return null
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        if (!root.optBoolean("loaded", false)) return null

        val observed = activeValuesFromStats(root)
        val observedBackend = observed?.optString("backend")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("backend").takeIf { it.isNotBlank() }
        val expectedBackend = expected.values.value("backend")?.toString() ?: "cpu"
        if (observedBackend == null ||
            canonicalBackend(observedBackend) != canonicalBackend(expectedBackend)
        ) return null

        // Prefer exact readback when the runner supplies effectiveConfig. A
        // valid loaded/backend witness is sufficient for LiteRT-LM builds that
        // do not expose the other EngineConfig fields in their stats payload.
        super.activeLoadedSignature(identity, nativeStatsJson, expected)
            ?.let { return it }
        return ActiveLoadedSignature.of(identity, expected.values)
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("load signature", ignoreCase = true)

    private fun configObject(root: JSONObject): JSONObject? {
        val raw = root.opt("effectiveConfig")
            ?: root.opt("loadedConfigJson")
            ?: root.opt("lastConfigJson")
            ?: root.opt("config")
        return when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }

    private fun canonicalBackend(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
        return when (value) {
            "cpu", "host" -> "cpu"
            "gpu", "opencl", "open_cl" -> "gpu"
            "npu", "qnn", "qualcomm" -> "npu"
            "google_tensor", "googletensor", "tpu", "google_tensor_processor" -> "google_tensor"
            else -> null
        }
    }
}

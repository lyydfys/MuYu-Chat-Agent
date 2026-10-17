package com.muyuchat.core.engine

import org.json.JSONObject

data class LlamaAdvancedValidationIssue(
    val field: String,
    val message: String
) {
    override fun toString(): String = if (field.isBlank()) message else "$field: $message"
}

data class LlamaAdvancedParseResult(
    val params: LlamaAdvancedParams?,
    val rawJson: String,
    val issues: List<LlamaAdvancedValidationIssue>
) {
    val isJsonObject: Boolean
        get() = params != null

    val errorMessages: List<String>
        get() = issues.map(LlamaAdvancedValidationIssue::toString)

    fun advancedJsonString(): String = params?.toJsonString() ?: rawJson

    fun advancedJsonValue(): Any = params?.toJsonObject() ?: rawJson

    fun putCanonicalFields(target: JSONObject) {
        params?.putCanonicalFields(target)
    }
}

data class LlamaAdvancedMergeResult(
    val json: String,
    val issues: List<LlamaAdvancedValidationIssue>
) {
    val errorMessages: List<String>
        get() = issues.map(LlamaAdvancedValidationIssue::toString)
}

/**
 * Versioned product contract for llama.cpp options that do not belong to the
 * common chat/sampling fields in [GenerationParams]. Unknown keys are retained
 * verbatim in [preservedJson], so newer native options survive an older UI.
 */
data class LlamaAdvancedParams(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nThreadsBatch: Int? = null,
    val nBatch: Int? = null,
    val nUbatch: Int? = null,
    val nGpuLayers: Int? = null,
    val mainGpu: Int? = null,
    val splitMode: String? = null,
    val nCpuMoe: Int? = null,
    val cacheTypeK: String? = null,
    val cacheTypeV: String? = null,
    val flashAttn: String? = null,
    val cacheReuse: Int? = null,
    val specType: String? = null,
    val specDraftNMax: Int? = null,
    val nParallel: Int? = null,
    val perf: Boolean? = null,
    val useJinja: Boolean? = null,
    val mmap: Boolean? = null,
    val mlock: Boolean? = null,
    val preservedJson: String = "{}"
) {
    fun toJsonObject(): JSONObject {
        val root = runCatching { JSONObject(preservedJson) }.getOrElse { JSONObject() }
        root.remove(SCHEMA_VERSION_KEY)
        CANONICAL_FIELDS.forEach { root.remove(it) }
        return root.apply {
            put(
                SCHEMA_VERSION_KEY,
                schemaVersion.takeIf { it == CURRENT_SCHEMA_VERSION } ?: CURRENT_SCHEMA_VERSION
            )
            putIfNotNull(KEY_N_THREADS_BATCH, nThreadsBatch)
            putIfNotNull(KEY_N_BATCH, nBatch)
            putIfNotNull(KEY_N_UBATCH, nUbatch)
            putIfNotNull(KEY_N_GPU_LAYERS, nGpuLayers)
            putIfNotNull(KEY_MAIN_GPU, mainGpu)
            putIfNotNull(KEY_SPLIT_MODE, splitMode)
            putIfNotNull(KEY_N_CPU_MOE, nCpuMoe)
            putIfNotNull(KEY_CACHE_TYPE_K, cacheTypeK)
            putIfNotNull(KEY_CACHE_TYPE_V, cacheTypeV)
            putIfNotNull(KEY_FLASH_ATTN, flashAttn)
            putIfNotNull(KEY_CACHE_REUSE, cacheReuse)
            putIfNotNull(KEY_SPEC_TYPE, specType)
            putIfNotNull(KEY_SPEC_DRAFT_N_MAX, specDraftNMax)
            putIfNotNull(KEY_N_PARALLEL, nParallel)
            putIfNotNull(KEY_PERF, perf)
            putIfNotNull(KEY_USE_JINJA, useJinja)
            putIfNotNull(KEY_MMAP, mmap)
            putIfNotNull(KEY_MLOCK, mlock)
        }
    }

    fun toJsonString(): String = toJsonObject().toString()

    fun putCanonicalFields(target: JSONObject) {
        CANONICAL_FIELDS.forEach { key ->
            canonicalValue(key)?.let { target.put(key, it) }
        }
    }

    private fun canonicalValue(key: String): Any? = when (key) {
        KEY_N_THREADS_BATCH -> nThreadsBatch
        KEY_N_BATCH -> nBatch
        KEY_N_UBATCH -> nUbatch
        KEY_N_GPU_LAYERS -> nGpuLayers
        KEY_MAIN_GPU -> mainGpu
        KEY_SPLIT_MODE -> splitMode
        KEY_N_CPU_MOE -> nCpuMoe
        KEY_CACHE_TYPE_K -> cacheTypeK
        KEY_CACHE_TYPE_V -> cacheTypeV
        KEY_FLASH_ATTN -> flashAttn
        KEY_CACHE_REUSE -> cacheReuse
        KEY_SPEC_TYPE -> specType
        KEY_SPEC_DRAFT_N_MAX -> specDraftNMax
        KEY_N_PARALLEL -> nParallel
        KEY_PERF -> perf
        KEY_USE_JINJA -> useJinja
        KEY_MMAP -> mmap
        KEY_MLOCK -> mlock
        else -> null
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val SCHEMA_VERSION_KEY = "schema_version"

        const val KEY_N_THREADS_BATCH = "n_threads_batch"
        const val KEY_N_BATCH = "n_batch"
        const val KEY_N_UBATCH = "n_ubatch"
        const val KEY_N_GPU_LAYERS = "n_gpu_layers"
        const val KEY_MAIN_GPU = "main_gpu"
        const val KEY_SPLIT_MODE = "split_mode"
        const val KEY_N_CPU_MOE = "n_cpu_moe"
        const val KEY_CACHE_TYPE_K = "cache_type_k"
        const val KEY_CACHE_TYPE_V = "cache_type_v"
        const val KEY_FLASH_ATTN = "flash_attn"
        const val KEY_CACHE_REUSE = "cache_reuse"
        const val KEY_SPEC_TYPE = "spec_type"
        const val KEY_SPEC_DRAFT_N_MAX = "spec_draft_n_max"
        const val KEY_N_PARALLEL = "n_parallel"
        const val KEY_PERF = "perf"
        const val KEY_USE_JINJA = "use_jinja"
        const val KEY_MMAP = "mmap"
        const val KEY_MLOCK = "mlock"

        val CANONICAL_FIELDS: Set<String> = linkedSetOf(
            KEY_N_THREADS_BATCH,
            KEY_N_BATCH,
            KEY_N_UBATCH,
            KEY_N_GPU_LAYERS,
            KEY_MAIN_GPU,
            KEY_SPLIT_MODE,
            KEY_N_CPU_MOE,
            KEY_CACHE_TYPE_K,
            KEY_CACHE_TYPE_V,
            KEY_FLASH_ATTN,
            KEY_CACHE_REUSE,
            KEY_SPEC_TYPE,
            KEY_SPEC_DRAFT_N_MAX,
            KEY_N_PARALLEL,
            KEY_PERF,
            KEY_USE_JINJA,
            KEY_MMAP,
            KEY_MLOCK
        )

        private val CACHE_TYPES = setOf(
            "f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1"
        )
        private val QUANTIZED_CACHE_TYPES = CACHE_TYPES - setOf("f32", "f16", "bf16")
        private val SPLIT_MODES = setOf("none", "layer", "row", "tensor")
        private val FLASH_ATTN_MODES = setOf("auto", "on", "off")
        private val SPEC_TYPES = setOf(
            "none",
            "draft-simple",
            "draft-eagle3",
            "draft-mtp",
            "ngram-simple",
            "ngram-map-k",
            "ngram-map-k4v",
            "ngram-mod",
            "ngram-cache"
        )

        fun parse(rawJson: String?): LlamaAdvancedParseResult {
            val source = rawJson?.takeIf { it.isNotBlank() } ?: "{}"
            val root = runCatching { JSONObject(source) }.getOrElse { error ->
                return LlamaAdvancedParseResult(
                    params = null,
                    rawJson = source,
                    issues = listOf(
                        LlamaAdvancedValidationIssue(
                            field = "advanced_json",
                            message = "must be a JSON object: ${error.message ?: "invalid format"}"
                        )
                    )
                )
            }
            val issues = mutableListOf<LlamaAdvancedValidationIssue>()
            val schemaVersion = root.readInt(
                SCHEMA_VERSION_KEY,
                CURRENT_SCHEMA_VERSION,
                CURRENT_SCHEMA_VERSION,
                issues
            ) ?: CURRENT_SCHEMA_VERSION
            val nThreadsBatch = root.readInt(KEY_N_THREADS_BATCH, 1, 256, issues)
            val nBatch = root.readInt(KEY_N_BATCH, 32, 8192, issues)
            var nUbatch = root.readInt(KEY_N_UBATCH, 32, 8192, issues)
            val nGpuLayers = root.readGpuLayers(issues)
            val mainGpu = root.readInt(KEY_MAIN_GPU, 0, 127, issues)
            val splitMode = root.readEnum(KEY_SPLIT_MODE, SPLIT_MODES, issues)
            val nCpuMoe = root.readInt(KEY_N_CPU_MOE, 0, 4096, issues)
            var cacheTypeK = root.readEnum(KEY_CACHE_TYPE_K, CACHE_TYPES, issues)
            var cacheTypeV = root.readEnum(KEY_CACHE_TYPE_V, CACHE_TYPES, issues)
            val flashAttn = root.readFlashAttn(issues)
            val cacheReuse = root.readInt(KEY_CACHE_REUSE, 0, 262_144, issues)
            val specType = root.readEnum(KEY_SPEC_TYPE, SPEC_TYPES, issues)
            val specDraftNMax = root.readInt(KEY_SPEC_DRAFT_N_MAX, 0, 8, issues)
            val nParallel = root.readInt(KEY_N_PARALLEL, 1, 1, issues)
            val perf = root.readBoolean(KEY_PERF, issues)
            val useJinja = root.readBoolean(KEY_USE_JINJA, issues)
            val mmap = root.readBoolean(KEY_MMAP, issues)
            val mlock = root.readBoolean(KEY_MLOCK, issues)

            if (nBatch != null && nUbatch != null &&
                (nUbatch > nBatch || nBatch % nUbatch != 0)
            ) {
                issues += LlamaAdvancedValidationIssue(
                    KEY_N_UBATCH,
                    "must not exceed n_batch, and n_batch must be divisible by n_ubatch"
                )
                nUbatch = null
            }
            if (cacheTypeV in QUANTIZED_CACHE_TYPES && flashAttn == "off") {
                issues += LlamaAdvancedValidationIssue(
                    KEY_CACHE_TYPE_V,
                    "quantized V cache requires flash_attn=auto or on"
                )
                cacheTypeV = null
            }
            if (splitMode == "tensor" &&
                (cacheTypeK in QUANTIZED_CACHE_TYPES || cacheTypeV in QUANTIZED_CACHE_TYPES)
            ) {
                issues += LlamaAdvancedValidationIssue(
                    KEY_SPLIT_MODE,
                    "tensor split cannot currently be combined with quantized KV cache"
                )
                cacheTypeK = null
                cacheTypeV = null
            }

            val params = LlamaAdvancedParams(
                schemaVersion = schemaVersion,
                nThreadsBatch = nThreadsBatch,
                nBatch = nBatch,
                nUbatch = nUbatch,
                nGpuLayers = nGpuLayers,
                mainGpu = mainGpu,
                splitMode = splitMode,
                nCpuMoe = nCpuMoe,
                cacheTypeK = cacheTypeK,
                cacheTypeV = cacheTypeV,
                flashAttn = flashAttn,
                cacheReuse = cacheReuse,
                specType = specType,
                specDraftNMax = specDraftNMax,
                nParallel = nParallel,
                perf = perf,
                useJinja = useJinja,
                mmap = mmap,
                mlock = mlock,
                preservedJson = root.toString()
            )
            return LlamaAdvancedParseResult(
                params = params.copy(preservedJson = params.toJsonString()),
                rawJson = source,
                issues = issues
            )
        }

        /** Collect flattened native-root fields back into advanced_json. */
        fun collectFromRoot(root: JSONObject, defaultAdvancedJson: String = "{}"): String {
            val rawAdvanced = when (val value = root.opt("advanced_json")) {
                is JSONObject -> value.toString()
                is String -> value.ifBlank { defaultAdvancedJson }
                else -> defaultAdvancedJson
            }
            val parsed = parse(rawAdvanced)
            val base = parsed.params?.toJsonObject() ?: return parsed.rawJson
            CANONICAL_FIELDS.forEach { key ->
                if (root.has(key) && !root.isNull(key)) {
                    base.put(key, root.opt(key))
                }
            }
            return parse(base.toString()).advancedJsonString()
        }

        fun merge(baseJson: String, patchJson: String): LlamaAdvancedMergeResult {
            val base = parse(baseJson)
            if (base.params == null) return LlamaAdvancedMergeResult(base.rawJson, base.issues)
            val patch = parse(patchJson)
            if (patch.params == null) {
                return LlamaAdvancedMergeResult(base.advancedJsonString(), base.issues + patch.issues)
            }
            val merged = deepMerge(base.params.toJsonObject(), patch.params.toJsonObject())
            val normalized = parse(merged.toString())
            return LlamaAdvancedMergeResult(
                json = normalized.advancedJsonString(),
                issues = base.issues + patch.issues + normalized.issues
            )
        }

        private fun JSONObject.readInt(
            key: String,
            min: Int,
            max: Int,
            issues: MutableList<LlamaAdvancedValidationIssue>
        ): Int? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key).toExactIntOrNull()
            if (value == null) {
                issues += LlamaAdvancedValidationIssue(key, "must be an integer")
                return null
            }
            if (value !in min..max) {
                issues += LlamaAdvancedValidationIssue(key, "must be in range $min..$max")
                return null
            }
            return value
        }

        private fun JSONObject.readGpuLayers(
            issues: MutableList<LlamaAdvancedValidationIssue>
        ): Int? {
            if (!has(KEY_N_GPU_LAYERS) || isNull(KEY_N_GPU_LAYERS)) return null
            val raw = opt(KEY_N_GPU_LAYERS)
            val value = when (raw) {
                is String -> when (raw.trim().lowercase()) {
                    "auto" -> -1
                    "all" -> -2
                    else -> raw.trim().toIntOrNull()
                }
                else -> raw.toExactIntOrNull()
            }
            if (value == null || value !in -2..4096) {
                issues += LlamaAdvancedValidationIssue(
                    KEY_N_GPU_LAYERS,
                    "must be auto(-1), all(-2), or 0..4096"
                )
                return null
            }
            return value
        }

        private fun JSONObject.readEnum(
            key: String,
            allowed: Set<String>,
            issues: MutableList<LlamaAdvancedValidationIssue>
        ): String? {
            if (!has(key) || isNull(key)) return null
            val value = (opt(key) as? String)?.trim()?.lowercase()
            if (value == null || value !in allowed) {
                issues += LlamaAdvancedValidationIssue(key, "allowed values: ${allowed.joinToString()}")
                return null
            }
            return value
        }

        private fun JSONObject.readFlashAttn(
            issues: MutableList<LlamaAdvancedValidationIssue>
        ): String? {
            if (!has(KEY_FLASH_ATTN) || isNull(KEY_FLASH_ATTN)) return null
            val value = when (val raw = opt(KEY_FLASH_ATTN)) {
                is Boolean -> if (raw) "on" else "off"
                is String -> when (raw.trim().lowercase()) {
                    "true", "enabled", "enable", "on" -> "on"
                    "false", "disabled", "disable", "off" -> "off"
                    "auto" -> "auto"
                    else -> null
                }
                else -> null
            }
            if (value == null || value !in FLASH_ATTN_MODES) {
                issues += LlamaAdvancedValidationIssue(KEY_FLASH_ATTN, "allowed values: auto, on, off")
                return null
            }
            return value
        }

        private fun JSONObject.readBoolean(
            key: String,
            issues: MutableList<LlamaAdvancedValidationIssue>
        ): Boolean? {
            if (!has(key) || isNull(key)) return null
            val value = when (val raw = opt(key)) {
                is Boolean -> raw
                is Number -> when (raw.toExactIntOrNull()) {
                    0 -> false
                    1 -> true
                    else -> null
                }
                is String -> when (raw.trim().lowercase()) {
                    "true", "1", "on", "enabled", "enable" -> true
                    "false", "0", "off", "disabled", "disable" -> false
                    else -> null
                }
                else -> null
            }
            if (value == null) {
                issues += LlamaAdvancedValidationIssue(key, "must be a boolean")
            }
            return value
        }

        private fun Any?.toExactIntOrNull(): Int? = when (this) {
            is Byte, is Short, is Int -> (this as Number).toInt()
            is Long -> takeIf {
                it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong()
            }?.toInt()
            is Float, is Double -> (this as Number).toDouble().takeIf {
                it.isFinite() &&
                    it % 1.0 == 0.0 &&
                    it >= Int.MIN_VALUE.toDouble() &&
                    it <= Int.MAX_VALUE.toDouble()
            }?.toInt()
            is String -> trim().toIntOrNull()
            else -> null
        }

        private fun deepMerge(base: JSONObject, patch: JSONObject): JSONObject {
            val result = JSONObject(base.toString())
            val keys = patch.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val patchValue = patch.opt(key)
                val baseValue = result.opt(key)
                if (patchValue is JSONObject && baseValue is JSONObject) {
                    result.put(key, deepMerge(baseValue, patchValue))
                } else {
                    result.put(key, patchValue)
                }
            }
            return result
        }

        private fun JSONObject.putIfNotNull(key: String, value: Any?) {
            if (value != null) put(key, value)
        }
    }
}

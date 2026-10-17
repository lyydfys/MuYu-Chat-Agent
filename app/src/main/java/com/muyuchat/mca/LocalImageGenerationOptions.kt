package com.muyuchat.mca

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit generation controls carried across the main-process/worker IPC
 * boundary.  Normal product calls leave every field null and retain the
 * model-family defaults; smoke and benchmark calls can require an exact,
 * auditable configuration instead of silently falling back to those defaults.
 */
data class LocalImageGenerationOptions(
    /** null = use profile default; empty string = explicitly disable it. */
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val threads: Int? = null,
    val seed: Int? = null,
    val cfgScale: Double? = null,
    val distilledGuidance: Double? = null,
    val flowShift: Double? = null,
    val sampleMethod: String? = null,
    val backendMode: String? = null,
    val tokenEmbeddingMode: String? = null,
    val memoryMode: Int? = null,
    val runner: String? = null,
    val useCfg: Boolean? = null,
    val taskMode: LocalImageTaskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
    val inputImage: LocalImagePreparedInput? = null,
    val maskImage: LocalImagePreparedInput? = null,
    val controlImage: LocalImagePreparedInput? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
    val clipSkip: Int? = null,
    val batchCount: Int = 1,
    val loras: List<LocalImagePreparedLora> = emptyList(),
    val vaeTiling: LocalImageVaeTilingOptions? = null,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: LocalImageUltraFixOptions? = null,
    val preview: LocalImagePreviewOptions? = null,
    /** Debug-only marker for the semantic smoke harness; never accepted from public JSON. */
    internal val allowLowStepSmoke: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        // Preserve null here so normal product requests can still resolve the
        // model's default negative prompt. Concrete native execution params are
        // canonicalised to a string immediately before crossing the JNI boundary.
        negativePrompt?.let { put("negativePrompt", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        steps?.let { put("steps", it) }
        threads?.let { put("threads", it) }
        seed?.let { put("seed", it) }
        cfgScale?.let { put("cfgScale", it) }
        distilledGuidance?.let { put("distilledGuidance", it) }
        flowShift?.let { put("flowShift", it) }
        sampleMethod?.let { put("sampleMethod", it) }
        backendMode?.let { put("backendMode", it) }
        tokenEmbeddingMode?.let { put("tokenEmbeddingMode", it) }
        memoryMode?.let { put("memoryMode", it) }
        runner?.let { put("runner", it) }
        useCfg?.let { put("useCfg", it) }
        put("taskMode", taskMode.wireName)
        inputImage?.let { put("inputImage", it.toJson()) }
        maskImage?.let { put("maskImage", it.toJson()) }
        controlImage?.let { put("controlImage", it.toJson()) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        put("batchCount", batchCount)
        if (loras.isNotEmpty()) {
            put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        }
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        if (textualInversionIds.isNotEmpty()) put("textualInversionIds", JSONArray(textualInversionIds))
        ultraFix?.let { put("ultraFix", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = true)

        /** History intentionally omits prepared paths; execution validation happens after restaging. */
        internal fun fromHistoryJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = false)

        private fun parseJson(
            json: JSONObject?,
            validateForExecution: Boolean
        ): LocalImageGenerationOptions {
            if (json == null) return LocalImageGenerationOptions()
            fun optionalInt(key: String): Int? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be an integer." }
                    val value = raw.toDouble()
                    require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                        "$key must be a finite 32-bit integer."
                    }
                    value.toInt()
                } else null
            fun optionalDouble(key: String): Double? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be numeric." }
                    raw.toDouble().also { value -> require(value.isFinite()) { "$key must be finite." } }
                } else null
            fun optionalString(key: String, preserveBlank: Boolean = false): String? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is String) { "$key must be a string." }
                    raw.trim().let { value ->
                        if (preserveBlank || value.isNotBlank()) value else null
                    }
                } else {
                    null
                }
            fun optionalBoolean(key: String): Boolean? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Boolean) { "$key must be a boolean." }
                    raw
                } else null
            fun optionalObject(key: String): JSONObject? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an object when specified." }
                    val raw = json.get(key)
                    require(raw is JSONObject) { "$key must be an object when specified." }
                    raw
                } else {
                    null
                }
            fun optionalArray(key: String): JSONArray? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an array when specified." }
                    val raw = json.get(key)
                    require(raw is JSONArray) { "$key must be an array when specified." }
                    raw
                } else {
                    null
                }

            val parsed = LocalImageGenerationOptions(
                negativePrompt = optionalString("negativePrompt", preserveBlank = true)
                    ?: optionalString("negative_prompt", preserveBlank = true),
                width = optionalInt("width"),
                height = optionalInt("height"),
                steps = optionalInt("steps"),
                threads = optionalInt("threads"),
                seed = optionalInt("seed") ?: optionalInt("randomSeed"),
                cfgScale = optionalDouble("cfgScale"),
                distilledGuidance = optionalDouble("distilledGuidance"),
                flowShift = optionalDouble("flowShift"),
                sampleMethod = optionalString("sampleMethod", preserveBlank = true),
                // A present-but-empty execution control is invalid and must reach
                // the MNN resolver rather than becoming an implicit fallback.
                backendMode = optionalString("backendMode", preserveBlank = true),
                tokenEmbeddingMode = optionalString("tokenEmbeddingMode", preserveBlank = true),
                memoryMode = optionalInt("memoryMode"),
                runner = optionalString("runner", preserveBlank = true),
                useCfg = optionalBoolean("useCfg"),
                taskMode = if (json.has("taskMode")) {
                    require(!json.isNull("taskMode")) { "taskMode must be a string when specified." }
                    LocalImageTaskMode.fromWireName(optionalString("taskMode", preserveBlank = true))
                } else {
                    LocalImageTaskMode.TEXT_TO_IMAGE
                },
                inputImage = optionalObject("inputImage")?.let(LocalImagePreparedInput::fromJson),
                maskImage = optionalObject("maskImage")?.let(LocalImagePreparedInput::fromJson),
                controlImage = optionalObject("controlImage")?.let(LocalImagePreparedInput::fromJson),
                strength = optionalDouble("strength"),
                controlStrength = optionalDouble("controlStrength"),
                clipSkip = optionalInt("clipSkip"),
                batchCount = optionalInt("batchCount") ?: 1,
                loras = optionalArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT) {
                        "Too many LoRA adapters."
                    }
                    buildList {
                        for (index in 0 until array.length()) {
                            add(LocalImagePreparedLora.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                vaeTiling = optionalObject("vaeTiling")?.let(LocalImageVaeTilingOptions::fromJson),
                textualInversionIds = optionalArray("textualInversionIds")?.let { values ->
                    require(values.length() <= 8) { "Too many textual inversion identifiers." }
                    buildList {
                        for (index in 0 until values.length()) {
                            val value = values.get(index) as? String
                                ?: error("textualInversionIds[$index] must be a UUID.")
                            add(UUID.fromString(value.trim()).toString())
                        }
                    }.also { ids -> require(ids.distinct().size == ids.size) { "Textual inversion ids must be unique." } }
                }.orEmpty(),
                ultraFix = optionalObject("ultraFix")?.let(LocalImageUltraFixOptions::fromJson),
                preview = optionalObject("preview")?.let(LocalImagePreviewOptions::fromJson)
            )
            if (!validateForExecution) {
                require(parsed.inputImage == null && parsed.maskImage == null &&
                    parsed.controlImage == null && parsed.loras.isEmpty() && parsed.preview == null
                ) { "Image history options must not contain transient execution artifacts." }
                return parsed
            }
            return parsed.withCanonicalUltraFixControls().also { options ->
                options.validateProductInputContract()
            }
        }
    }
}

data class LocalImageUltraFixOptions(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(targetWidth in 64..8192 && targetWidth % 8 == 0)
        require(targetHeight in 64..8192 && targetHeight % 8 == 0)
        require(targetWidth.toLong() * targetHeight.toLong() <= 64L * 1024L * 1024L)
        require(strength > 0.0 && strength <= 1.0)
        require(inversionSteps in 1..100 && refinementSteps in 1..100)
        require(inversionSteps == localImageDenoisingTailStepCount(refinementSteps, strength))
        require(tileSize in 128..2048 && tileSize % 8 == 0 &&
            tileSize <= minOf(targetWidth, targetHeight))
        require(overlap in 0.0..0.5)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("targetWidth", targetWidth)
        .put("targetHeight", targetHeight)
        .put("strength", strength)
        .put("inversionSteps", inversionSteps)
        .put("refinementSteps", refinementSteps)
        .put("tileSize", tileSize)
        .put("overlap", overlap)

    companion object {
        fun fromJson(json: JSONObject): LocalImageUltraFixOptions {
            val fields = setOf("targetWidth", "targetHeight", "strength", "inversionSteps", "refinementSteps", "tileSize", "overlap")
            require(json.keys().asSequence().toSet() == fields) { "UltraFix options must contain only request fields." }
            fun int(name: String): Int = (json.get(name) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                ?: error("UltraFix $name must be an integer.")
            fun double(name: String): Double = (json.get(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)
                ?: error("UltraFix $name must be finite.")
            return LocalImageUltraFixOptions(
                int("targetWidth"), int("targetHeight"), double("strength"), int("inversionSteps"),
                int("refinementSteps"), int("tileSize"), double("overlap")
            )
        }
    }
}

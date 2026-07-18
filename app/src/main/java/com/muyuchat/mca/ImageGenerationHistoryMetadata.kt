package com.muyuchat.mca

import org.json.JSONObject

/** Reproducible, secret-free generation parameters persisted with a generated image. */
data class ImageGenerationHistoryMetadata(
    val backend: ImageBackend,
    val modelId: String,
    val modelName: String,
    val requestPrompt: String,
    val options: LocalImageGenerationOptions,
    val inputDraft: LocalImageInputDraft,
    val nativeExecutionJson: String = ""
) {
    init {
        require(modelId.isNotBlank()) { "Image history modelId must not be blank." }
        require(modelName.isNotBlank()) { "Image history modelName must not be blank." }
        require(requestPrompt.isNotBlank()) { "Image history requestPrompt must not be blank." }
        if (backend == ImageBackend.LOCAL) {
            require(options.taskMode == inputDraft.taskMode) {
                "Image history options and input draft must use the same task mode."
            }
        }
    }

    fun toJsonString(): String = JSONObject()
        .put("version", VERSION)
        .put("backend", backend.name)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("requestPrompt", requestPrompt)
        .put(
            "options",
            options.copy(
                inputImage = null,
                maskImage = null,
                controlImage = null,
                taskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
                strength = null,
                controlStrength = null,
                preview = null
            ).toJson()
        )
        .put("inputDraft", inputDraft.toHistoryJson())
        .apply {
            nativeExecutionJson.trim().takeIf(String::isNotEmpty)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { put("nativeExecution", it) }
            }
        }
        .toString()

    fun withNativeExecution(nativeExecutionJson: String): ImageGenerationHistoryMetadata {
        val nativeEffective = runCatching {
            JSONObject(nativeExecutionJson).getJSONObject("nativeEffective")
        }.getOrNull() ?: return copy(nativeExecutionJson = nativeExecutionJson)
        return copy(
            options = options.copy(
                width = nativeEffective.historyExactIntOrNull("width") ?: options.width,
                height = nativeEffective.historyExactIntOrNull("height") ?: options.height,
                steps = nativeEffective.historyExactIntOrNull("steps") ?: options.steps,
                seed = nativeEffective.historyExactIntOrNull("seed") ?: options.seed,
                cfgScale = nativeEffective.historyFiniteDoubleOrNull("cfgScale") ?: options.cfgScale,
                sampleMethod = nativeEffective.historyNonBlankStringOrNull("sampleMethod")
                    ?: options.sampleMethod,
                clipSkip = nativeEffective.historyExactIntOrNull("clipSkip") ?: options.clipSkip,
                batchCount = nativeEffective.historyExactIntOrNull("batchCount")
                    ?.takeIf { it > 0 }
                    ?: options.batchCount
            ),
            nativeExecutionJson = nativeExecutionJson
        )
    }

    fun displayDetails(): String = buildList {
        add("$modelName · ${inputDraft.taskMode.displayName()}")
        val elapsedMs = runCatching {
            JSONObject(nativeExecutionJson).optLong("elapsedMs", 0L)
        }.getOrDefault(0L)
        val parameters = buildList {
            if (options.width != null && options.height != null) add("${options.width}×${options.height}")
            options.steps?.let { add("$it steps") }
            options.cfgScale?.let { add("CFG ${it.toCompactText()}") }
            add(options.seed?.let { "seed $it" } ?: "随机 seed")
            options.sampleMethod?.takeIf(String::isNotBlank)?.let { add(it) }
            if (options.batchCount > 1) add("${options.batchCount} 张")
            if (elapsedMs > 0L) add("用时 ${"%.1f".format(elapsedMs / 1000.0)} 秒")
        }
        if (parameters.isNotEmpty()) add(parameters.joinToString(" · "))
        val controls = buildList {
            options.clipSkip?.let { add("CLIP skip $it") }
            options.vaeTiling?.let { add("VAE 分块 ${it.tileSize}/${it.overlap.toCompactText()}") }
            if (!inputDraft.inputImageReference.isNullOrBlank()) add("原图")
            if (!inputDraft.maskImageReference.isNullOrBlank()) add("蒙版")
            if (!inputDraft.controlImageReference.isNullOrBlank()) add("控制图")
            inputDraft.strength?.let { add("重绘强度 ${it.toCompactText()}") }
            inputDraft.controlStrength?.let { add("控制强度 ${it.toCompactText()}") }
            when (val negative = options.negativePrompt) {
                null -> add("模型默认负向提示词")
                "" -> add("无负向提示词")
                else -> add("负向：$negative")
            }
        }
        if (controls.isNotEmpty()) add(controls.joinToString(" · "))
    }.joinToString("\n")

    companion object {
        private const val VERSION = 1

        fun fromJsonOrNull(raw: String?): ImageGenerationHistoryMetadata? = runCatching {
            val json = JSONObject(raw?.trim().orEmpty())
            require(json.getInt("version") == VERSION) { "Unsupported image history version." }
            val input = json.getJSONObject("inputDraft")
            val inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.fromWireName(input.getString("taskMode")),
                inputImageReference = input.optionalHistoryString("inputImageReference"),
                maskImageReference = input.optionalHistoryString("maskImageReference"),
                controlImageReference = input.optionalHistoryString("controlImageReference"),
                strength = input.optionalHistoryDouble("strength"),
                controlStrength = input.optionalHistoryDouble("controlStrength")
            ).also(LocalImageInputDraft::validate)
            val persistedOptions = LocalImageGenerationOptions.fromJson(json.getJSONObject("options"))
            ImageGenerationHistoryMetadata(
                backend = ImageBackend.valueOf(json.getString("backend")),
                modelId = json.getString("modelId"),
                modelName = json.getString("modelName"),
                requestPrompt = json.getString("requestPrompt"),
                options = persistedOptions.copy(
                    taskMode = inputDraft.taskMode,
                    strength = inputDraft.strength,
                    controlStrength = inputDraft.controlStrength
                ),
                inputDraft = inputDraft,
                nativeExecutionJson = json.optJSONObject("nativeExecution")?.toString().orEmpty()
            )
        }.getOrNull()
    }
}

private fun LocalImageInputDraft.toHistoryJson(): JSONObject = JSONObject()
    .put("taskMode", taskMode.wireName)
    .apply {
        inputImageReference?.let { put("inputImageReference", it) }
        maskImageReference?.let { put("maskImageReference", it) }
        controlImageReference?.let { put("controlImageReference", it) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
    }

private fun JSONObject.optionalHistoryString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key).takeIf(String::isNotBlank) else null

private fun JSONObject.optionalHistoryDouble(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key).also { require(it.isFinite()) } else null

private fun JSONObject.historyExactIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) as? Number ?: return null
    val number = value.toDouble()
    return number.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    }?.toInt()
}

private fun JSONObject.historyFiniteDoubleOrNull(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun JSONObject.historyNonBlankStringOrNull(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)

private fun LocalImageTaskMode.displayName(): String = when (this) {
    LocalImageTaskMode.TEXT_TO_IMAGE -> "文生图"
    LocalImageTaskMode.IMG2IMG -> "图生图"
    LocalImageTaskMode.INPAINT -> "局部重绘"
    LocalImageTaskMode.CONTROL -> "Control"
    LocalImageTaskMode.EDIT -> "编辑"
}

private fun Double.toCompactText(): String = toString().trimEnd('0').trimEnd('.')

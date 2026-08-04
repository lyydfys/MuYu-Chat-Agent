package com.muyuchat.feature.chat

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal enum class ImageGenerationParameterImportSource {
    MCA,
    LOCAL_DREAM
}

internal enum class ImageGenerationParameterImportField(
    val label: String,
    val presetField: ImageGenerationPresetField? = null
) {
    PROMPT("提示词", ImageGenerationPresetField.PROMPT),
    NEGATIVE_PROMPT("负向提示词", ImageGenerationPresetField.NEGATIVE_PROMPT),
    SIZE("尺寸", ImageGenerationPresetField.SIZE),
    STEPS("步数", ImageGenerationPresetField.STEPS),
    CFG("CFG", ImageGenerationPresetField.CFG),
    SEED("Seed", ImageGenerationPresetField.SEED),
    SAMPLER("采样器", ImageGenerationPresetField.SAMPLER),
    CLIP_SKIP("CLIP skip", ImageGenerationPresetField.CLIP_SKIP),
    LORA("LoRA", ImageGenerationPresetField.LORA),
    TEXTUAL_INVERSION("Textual Inversion", ImageGenerationPresetField.TEXTUAL_INVERSION),
    ULTRAFIX("UltraFix", ImageGenerationPresetField.ULTRAFIX),
    BATCH("批次数量", ImageGenerationPresetField.BATCH),
    VAE_TILING("VAE 分块", ImageGenerationPresetField.VAE_TILING),
    STRENGTH("重绘强度"),
    CONTROL_STRENGTH("Control 强度")
}

internal data class ImageGenerationParameterImport(
    val preset: ImageGenerationUiPreset,
    val fields: Set<ImageGenerationParameterImportField>,
    val source: ImageGenerationParameterImportSource,
    val sourceModelId: String? = null,
    val taskMode: String? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null
)

/**
 * MCA exports UltraFix as a composite while retaining canonical outer fields for wire
 * compatibility. Those outer fields must not be applied as ordinary IMG2IMG controls.
 */
internal fun ImageGenerationParameterImport.hasStructuredUltraFixPayload(): Boolean =
    preset.ultraFix != null ||
        (source == ImageGenerationParameterImportSource.LOCAL_DREAM &&
            taskMode.equals("ULTRAFIX", ignoreCase = true) &&
            ImageGenerationParameterImportField.ULTRAFIX in fields)

internal fun ImageGenerationParameterImportField.isUltraFixShadow(): Boolean = when (this) {
    ImageGenerationParameterImportField.SIZE,
    ImageGenerationParameterImportField.STEPS,
    ImageGenerationParameterImportField.STRENGTH,
    ImageGenerationParameterImportField.BATCH,
    ImageGenerationParameterImportField.VAE_TILING -> true
    else -> false
}

internal data class ImageGenerationParameterImportSelection(
    val imported: ImageGenerationParameterImport,
    val compatibleFields: Set<ImageGenerationParameterImportField>,
    val selectedFields: Set<ImageGenerationParameterImportField> = compatibleFields
) {
    init {
        require(compatibleFields.all(imported.fields::contains)) {
            "Compatible import fields must exist in the decoded payload."
        }
        require(selectedFields.all(compatibleFields::contains)) {
            "Selected import fields must be compatible."
        }
    }

    val hiddenFieldCount: Int = imported.fields.size - compatibleFields.size

    fun toggle(field: ImageGenerationParameterImportField): ImageGenerationParameterImportSelection {
        if (field !in compatibleFields) return this
        return copy(
            selectedFields = if (field in selectedFields) {
                selectedFields - field
            } else {
                selectedFields + field
            }
        )
    }

    /** Cancel and dismiss resolve to null, so no panel callback can run before explicit confirm. */
    fun applicationOrNull(confirm: Boolean): ImageGenerationParameterImportApplication? =
        if (confirm && selectedFields.isNotEmpty()) {
            ImageGenerationParameterImportApplication(imported, selectedFields)
        } else {
            null
        }
}

internal data class ImageGenerationParameterImportApplication(
    val imported: ImageGenerationParameterImport,
    val fields: Set<ImageGenerationParameterImportField>
)

/**
 * Parses path-free image-generation parameters copied by MCA or Local Dream.
 *
 * The parser deliberately accepts only an identified schema and bounded scalar values. It never
 * imports file paths, model files, source images, masks, control images, or credentials. Model and
 * capability compatibility is checked again by [compatibleImageGenerationParameterImportFields]
 * before the caller applies any field to the current model and task mode.
 */
internal object ImageGenerationParameterImportCodec {
    private const val MCA_SCHEMA = "mca.image.generation.parameters"
    private const val MCA_VERSION = 1
    private const val MCA_BASE64_PREFIX = "MCAPARAMS:"
    private const val LOCAL_DREAM_BASE64_PREFIX = "LDPARAMS:"
    private const val MAX_CLIPBOARD_CHARS = 64 * 1024
    private const val MAX_PROMPT_CHARS = 16_384
    private const val MAX_MODEL_ID_CHARS = 256
    private const val MAX_LORA_COUNT = 8
    private const val MAX_TEXTUAL_INVERSION_COUNT = 8

    fun decode(raw: String?): Result<ImageGenerationParameterImport> = try {
        val trimmed = raw?.trim().orEmpty()
        require(trimmed.isNotEmpty()) { "剪贴板中没有可导入的图片参数。" }
        require(trimmed.length <= MAX_CLIPBOARD_CHARS) { "图片参数内容过大。" }
        val json = JSONObject(decodeEnvelope(trimmed))
        Result.success(
            when {
                json.optString("schema") == MCA_SCHEMA -> decodeMca(json)
                json.optBoolean("_localdream_params", false) -> decodeLocalDream(json)
                else -> error("未识别的图片参数格式。")
            }
        )
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun encodeMcaBase64(json: String): String {
        require(json.length <= MAX_CLIPBOARD_CHARS) { "图片参数内容过大。" }
        val parsed = JSONObject(json)
        require(parsed.optString("schema") == MCA_SCHEMA) { "只能编码 MCA 图片参数。" }
        return MCA_BASE64_PREFIX + Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun decodeEnvelope(raw: String): String = when {
        raw.startsWith(MCA_BASE64_PREFIX) -> decodeBase64(raw.removePrefix(MCA_BASE64_PREFIX))
        raw.startsWith(LOCAL_DREAM_BASE64_PREFIX) ->
            decodeBase64(raw.removePrefix(LOCAL_DREAM_BASE64_PREFIX))
        raw.startsWith("{") -> raw
        else -> error("未识别的图片参数格式。")
    }

    private fun decodeBase64(payload: String): String {
        require(payload.isNotBlank() && payload.length <= MAX_CLIPBOARD_CHARS) {
            "图片参数 Base64 内容无效。"
        }
        val decoded = Base64.getDecoder().decode(payload.trim())
        require(decoded.size <= MAX_CLIPBOARD_CHARS) { "解码后的图片参数内容过大。" }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun decodeMca(json: JSONObject): ImageGenerationParameterImport {
        require(json.optInt("version", -1) == MCA_VERSION) { "不支持的 MCA 图片参数版本。" }
        val fields = linkedSetOf<ImageGenerationParameterImportField>()
        val prompt = boundedOptionalString(json, "prompt")?.also {
            fields += ImageGenerationParameterImportField.PROMPT
        }.orEmpty()
        val negativePrompt = when (json.optString("negativePromptMode")) {
            "model_default", "" -> null.also {
                fields += ImageGenerationParameterImportField.NEGATIVE_PROMPT
            }
            "explicit" -> boundedOptionalString(json, "negativePrompt").orEmpty().also {
                fields += ImageGenerationParameterImportField.NEGATIVE_PROMPT
            }
            else -> error("负向提示词模式无效。")
        }
        val ultraFix = json.optJSONObject("ultraFix")?.let(::parseMcaUltraFix)
            ?.also { fields += ImageGenerationParameterImportField.ULTRAFIX }
        val dimensionRange = if (ultraFix == null) 64..4_096 else 64..8_192
        val width = boundedOptionalInt(json, "width", dimensionRange)
        val height = boundedOptionalInt(json, "height", dimensionRange)
        require((width == null) == (height == null)) { "图片宽高必须同时提供。" }
        if (width != null) fields += ImageGenerationParameterImportField.SIZE
        val steps = boundedOptionalInt(json, "steps", 1..1_000)
            ?.also { fields += ImageGenerationParameterImportField.STEPS }
        val cfg = boundedOptionalDouble(json, "cfgScale", 0.0..30.0)
            ?.also { fields += ImageGenerationParameterImportField.CFG }
        val seed = boundedOptionalInt(json, "seed", 0..Int.MAX_VALUE)
            ?.also { fields += ImageGenerationParameterImportField.SEED }
        val sampler = boundedOptionalString(json, "sampleMethod", 128)
            ?.also { fields += ImageGenerationParameterImportField.SAMPLER }
        val clipSkip = boundedOptionalInt(json, "clipSkip", -1..32)
            ?.also { fields += ImageGenerationParameterImportField.CLIP_SKIP }
        val batch = boundedOptionalInt(json, "batchCount", 1..8)
            ?.also { fields += ImageGenerationParameterImportField.BATCH }
        val loras = parseLoras(json.optJSONArray("loras"))
            .also { if (it.isNotEmpty()) fields += ImageGenerationParameterImportField.LORA }
        val textualInversionIds = parseTextualInversionIds(
            json.optJSONArray("textualInversionIds")
        ).also { ids ->
            if (ids.isNotEmpty()) fields += ImageGenerationParameterImportField.TEXTUAL_INVERSION
        }
        val tiling = json.optJSONObject("vaeTiling")
        val vaeTileSize = tiling?.let { objectJson ->
            boundedRequiredInt(objectJson, "tileSize", 64..4_096)
        }?.also {
            require(it % 8 == 0) { "VAE tileSize 无效。" }
            fields += ImageGenerationParameterImportField.VAE_TILING
        }
        val vaeOverlap = tiling?.getDouble("overlap")?.also {
            require(it.isFinite() && it in 0.0..0.5) { "VAE overlap 无效。" }
        }
        require(tiling == null || vaeOverlap != null) { "VAE tiling 参数不完整。" }
        val strength = strictOptionalDouble(json, "strength")?.also {
            require(it >= 0.0 && it <= 1.0) { "strength 超出允许范围。" }
            fields += ImageGenerationParameterImportField.STRENGTH
        }
        val controlStrength = strictOptionalDouble(json, "controlStrength")?.also {
            require(it in 0.0..2.0) { "controlStrength 超出允许范围。" }
            fields += ImageGenerationParameterImportField.CONTROL_STRENGTH
        }
        if (ultraFix != null) {
            require(json.optString("taskMode") == "img2img") {
                "UltraFix parameters require taskMode=img2img."
            }
            require(width == ultraFix.targetWidth && height == ultraFix.targetHeight &&
                steps == ultraFix.refinementSteps &&
                strength != null && kotlin.math.abs(strength - ultraFix.strength) <= 1.0e-12 &&
                vaeTileSize == ultraFix.tileSize && vaeOverlap != null &&
                kotlin.math.abs(vaeOverlap - ultraFix.overlap) <= 1.0e-12 &&
                batch == 1
            ) { "UltraFix structured controls conflict with their canonical outer values." }
        }
        return ImageGenerationParameterImport(
            preset = ImageGenerationUiPreset(
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfgScale = cfg,
                seed = seed,
                sampleMethod = sampler,
                clipSkip = clipSkip,
                batchCount = batch,
                vaeTileSize = vaeTileSize,
                vaeTileOverlap = vaeOverlap,
                loras = loras,
                textualInversionIds = textualInversionIds,
                ultraFix = ultraFix
            ),
            fields = fields,
            source = ImageGenerationParameterImportSource.MCA,
            sourceModelId = boundedPortableContextString(json, "modelId", MAX_MODEL_ID_CHARS),
            taskMode = boundedPortableContextString(json, "taskMode", 64),
            strength = strength,
            controlStrength = controlStrength
        )
    }

    private fun decodeLocalDream(json: JSONObject): ImageGenerationParameterImport {
        require(json.optInt("v", -1) == 1) { "不支持的 Local Dream 参数版本。" }
        val fields = linkedSetOf<ImageGenerationParameterImportField>()
        val taskMode = boundedPortableContextString(json, "mode", 64)
        val isUltraFix = taskMode.equals("ULTRAFIX", ignoreCase = true)
        val prompt = boundedOptionalString(json, "prompt")?.also {
            fields += ImageGenerationParameterImportField.PROMPT
        }.orEmpty()
        val negative = boundedOptionalString(json, "negative_prompt")?.also {
            fields += ImageGenerationParameterImportField.NEGATIVE_PROMPT
        }
        val steps = boundedOptionalInt(json, "steps", 1..1_000)
            ?.also {
                if (!isUltraFix) fields += ImageGenerationParameterImportField.STEPS
            }
        val cfg = boundedOptionalDouble(json, "cfg", 0.0..30.0)
            ?.also { fields += ImageGenerationParameterImportField.CFG }
        val seedLong = optionalLong(json, "seed")
        val seed = seedLong?.also { require(it in 0..Int.MAX_VALUE.toLong()) { "Seed 无效。" } }
            ?.toInt()
            ?.also { fields += ImageGenerationParameterImportField.SEED }
        val scheduler = boundedOptionalString(json, "scheduler", 128)
            ?.also { fields += ImageGenerationParameterImportField.SAMPLER }
        val denoiseStrength = strictOptionalDouble(json, "denoise_strength")?.also {
            require(it >= 0.0 && it <= 1.0) { "denoise_strength 超出允许范围。" }
            if (!isUltraFix) fields += ImageGenerationParameterImportField.STRENGTH
        }
        if (isUltraFix && steps != null && denoiseStrength != null) {
            // These two scalar fields form one UltraFix control in MCA. Keeping them atomic
            // prevents Local Dream refinement values from becoming ordinary IMG2IMG values.
            fields += ImageGenerationParameterImportField.ULTRAFIX
        }
        require(fields.isNotEmpty()) { "Local Dream 参数中没有可导入字段。" }
        return ImageGenerationParameterImport(
            preset = ImageGenerationUiPreset(
                prompt = prompt,
                negativePrompt = negative,
                steps = steps,
                cfgScale = cfg,
                seed = seed,
                sampleMethod = scheduler
            ),
            fields = fields,
            source = ImageGenerationParameterImportSource.LOCAL_DREAM,
            sourceModelId = boundedPortableContextString(json, "model_id", MAX_MODEL_ID_CHARS),
            taskMode = taskMode,
            strength = denoiseStrength
        )
    }

    private fun parseLoras(array: JSONArray?): List<ImageGenerationUiLoraSelection> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_LORA_COUNT) { "LoRA 数量超过上限。" }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id").trim().also {
                    require(it.isNotEmpty() && it.length <= MAX_MODEL_ID_CHARS) { "LoRA ID 无效。" }
                }
                val multiplier = item.getDouble("multiplier").also {
                    require(it.isFinite() && it in -4.0..4.0 && kotlin.math.abs(it) >= 0.01) {
                        "LoRA 权重无效。"
                    }
                }
                add(ImageGenerationUiLoraSelection(id, multiplier))
            }
        }.also { selections ->
            require(selections.map(ImageGenerationUiLoraSelection::id).distinct().size == selections.size) {
                "LoRA ID 不能重复。"
            }
        }
    }

    private fun parseTextualInversionIds(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_TEXTUAL_INVERSION_COUNT) {
            "Textual inversion selection exceeds the supported limit."
        }
        return buildList {
            for (index in 0 until array.length()) {
                val raw = array.get(index)
                require(raw is String) { "Textual inversion ids must be strings." }
                add(java.util.UUID.fromString(raw.trim()).toString())
            }
        }.also { ids -> require(ids.distinct().size == ids.size) }
    }

    private fun parseMcaUltraFix(json: JSONObject): ImageGenerationUiUltraFixOptions {
        val allowed = setOf(
            "targetWidth",
            "targetHeight",
            "strength",
            "inversionSteps",
            "refinementSteps",
            "tileSize",
            "overlap"
        )
        require(json.keys().asSequence().toSet() == allowed) {
            "UltraFix parameters contain unknown or missing fields."
        }
        val targetWidth = boundedRequiredInt(json, "targetWidth", 64..8_192)
        val targetHeight = boundedRequiredInt(json, "targetHeight", 64..8_192)
        val strength = strictOptionalDouble(json, "strength")
            ?: error("UltraFix strength is required.")
        val inversionSteps = boundedRequiredInt(json, "inversionSteps", 1..100)
        val refinementSteps = boundedRequiredInt(json, "refinementSteps", 1..100)
        val tileSize = boundedRequiredInt(json, "tileSize", 128..2_048)
        val overlap = strictOptionalDouble(json, "overlap")
            ?: error("UltraFix overlap is required.")
        require(targetWidth % 8 == 0 && targetHeight % 8 == 0 &&
            targetWidth.toLong() * targetHeight.toLong() <= 64L * 1024L * 1024L &&
            strength > 0.0 && strength <= 1.0 &&
            inversionSteps == imageGenerationUltraFixDenoisingTailStepCount(
                refinementSteps,
                strength
            ) &&
            tileSize % 8 == 0 && tileSize <= minOf(targetWidth, targetHeight) &&
            overlap in 0.0..0.5
        ) { "UltraFix parameters violate the bounded execution contract." }
        return ImageGenerationUiUltraFixOptions(
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            strength = strength,
            inversionSteps = inversionSteps,
            refinementSteps = refinementSteps,
            tileSize = tileSize,
            overlap = overlap
        )
    }

    private fun boundedOptionalString(
        json: JSONObject,
        name: String,
        maxChars: Int = MAX_PROMPT_CHARS
    ): String? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.getString(name)
        require(value.length <= maxChars) { "$name 过长。" }
        return value
    }

    private fun boundedOptionalInt(json: JSONObject, name: String, range: IntRange): Int? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = exactLong(json.get(name), name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "$name 不是有效整数。"
        }
        val intValue = value.toInt()
        require(intValue in range) { "$name 超出允许范围。" }
        return intValue
    }

    private fun boundedRequiredInt(json: JSONObject, name: String, range: IntRange): Int =
        boundedOptionalInt(json, name, range)
            ?: error("$name 不能为空。")

    private fun boundedOptionalDouble(
        json: JSONObject,
        name: String,
        range: ClosedFloatingPointRange<Double>
    ): Double? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.getDouble(name)
        require(value.isFinite() && value in range) { "$name 超出允许范围。" }
        return value
    }

    private fun strictOptionalDouble(json: JSONObject, name: String): Double? {
        if (!json.has(name) || json.isNull(name)) return null
        val raw = json.get(name)
        require(raw is Number) { "$name 必须是数值。" }
        return raw.toDouble().also { require(it.isFinite()) { "$name 必须是有限数值。" } }
    }

    private fun boundedPortableContextString(
        json: JSONObject,
        name: String,
        maxChars: Int
    ): String? = boundedOptionalString(json, name, maxChars)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.takeUnless(String::looksLikePrivateImportReference)

    private fun optionalLong(json: JSONObject, name: String): Long? {
        if (!json.has(name) || json.isNull(name)) return null
        return exactLong(json.get(name), name)
    }

    private fun exactLong(value: Any, name: String): Long = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> {
            val number = (value as Number).toDouble()
            require(number.isFinite() && number >= Long.MIN_VALUE.toDouble() &&
                number <= Long.MAX_VALUE.toDouble() && number % 1.0 == 0.0
            ) { "$name 不是整数。" }
            number.toLong()
        }
        is String -> value.toLongOrNull() ?: error("$name 不是整数。")
        else -> error("$name 不是整数。")
    }
}

internal fun compatibleImageGenerationParameterImportFields(
    imported: ImageGenerationParameterImport,
    currentTaskMode: ImageGenerationUiTaskMode,
    selectedModel: ChatModelChoice?,
    selectedModelIsCloud: Boolean,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsLora: Boolean,
    availableLoraIds: Set<String>,
    maxBatchCount: Int,
    supportsTextualInversion: Boolean = false,
    supportsUltraFix: Boolean = false,
    ultraFixEnabled: Boolean = false,
    availableTextualInversionIds: Set<String> = emptySet(),
    hasImageInput: Boolean = true,
    imageInputWidth: Int? = null,
    imageInputHeight: Int? = null
): Set<ImageGenerationParameterImportField> {
    val compatiblePresetFields = compatibleImageGenerationPresetFields(
        preset = imported.preset,
        selectedModel = selectedModel,
        selectedModelIsCloud = selectedModelIsCloud,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsClipSkip = supportsClipSkip,
        supportsVaeTiling = supportsVaeTiling,
        supportsLora = supportsLora,
        availableLoraIds = availableLoraIds,
        maxBatchCount = maxBatchCount,
        currentTaskMode = currentTaskMode,
        supportsTextualInversion = supportsTextualInversion,
        supportsUltraFix = supportsUltraFix,
        availableTextualInversionIds = availableTextualInversionIds
    )
    val structuredUltraFix = imported.preset.ultraFix
    val hasStructuredUltraFixPayload = imported.hasStructuredUltraFixPayload()
    return imported.fields.filterTo(linkedSetOf()) { field ->
        if (hasStructuredUltraFixPayload && field.isUltraFixShadow()) {
            return@filterTo false
        }
        when (field) {
            ImageGenerationParameterImportField.ULTRAFIX -> {
                val localDreamUltraFix =
                    imported.source == ImageGenerationParameterImportSource.LOCAL_DREAM &&
                        imported.taskMode.equals("ULTRAFIX", ignoreCase = true)
                val sourceTarget = if (imageInputWidth != null && imageInputHeight != null) {
                    selectedModel?.ultraFixTargetSizeForSourceOrNull(
                        imageInputWidth,
                        imageInputHeight
                    )
                } else {
                    null
                }
                if (selectedModelIsCloud || selectedModel == null || !supportsUltraFix ||
                    currentTaskMode != ImageGenerationUiTaskMode.IMG2IMG || !hasImageInput
                ) {
                    false
                } else if (localDreamUltraFix) {
                    sourceTarget != null &&
                    imported.preset.steps?.let { steps ->
                        steps in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS &&
                            imported.strength?.let { strength ->
                                strength.isFinite() && strength >= 0.0 && strength <= 1.0 &&
                                    imageGenerationUltraFixDenoisingTailStepCount(
                                        steps,
                                        strength
                                    ) in 1..minOf(
                                        IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                        steps
                                    )
                            } == true
                    } == true
                } else {
                    structuredUltraFix != null &&
                        sourceTarget != null &&
                        structuredUltraFix.targetWidth >= sourceTarget.first &&
                        structuredUltraFix.targetHeight >= sourceTarget.second &&
                        ImageGenerationPresetField.ULTRAFIX in compatiblePresetFields
                }
            }
            ImageGenerationParameterImportField.STEPS ->
                if (imported.source == ImageGenerationParameterImportSource.LOCAL_DREAM &&
                    imported.taskMode.equals("ULTRAFIX", ignoreCase = true) &&
                    supportsUltraFix && ultraFixEnabled &&
                    currentTaskMode == ImageGenerationUiTaskMode.IMG2IMG
                ) {
                    imported.preset.steps?.let {
                        it in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS
                    } == true
                } else {
                    field.presetField in compatiblePresetFields
                }
            ImageGenerationParameterImportField.STRENGTH ->
                !selectedModelIsCloud &&
                    selectedModel != null &&
                    currentTaskMode in setOf(
                        ImageGenerationUiTaskMode.IMG2IMG,
                        ImageGenerationUiTaskMode.INPAINT
                    ) &&
                    imported.strength?.let { it.isFinite() && it >= 0.0 && it <= 1.0 } == true
            ImageGenerationParameterImportField.CONTROL_STRENGTH ->
                !selectedModelIsCloud &&
                    selectedModel != null &&
                    currentTaskMode == ImageGenerationUiTaskMode.CONTROL &&
                    imported.controlStrength?.let { it.isFinite() && it in 0.0..2.0 } == true
            else -> field.presetField in compatiblePresetFields
        }
    }
}

private fun String.looksLikePrivateImportReference(): Boolean {
    val value = trim()
    if (value.startsWith("/") ||
        value.startsWith("\\\\") ||
        value.contains("file:", ignoreCase = true) ||
        value.contains("content:", ignoreCase = true) ||
        value.contains("android.resource:", ignoreCase = true) ||
        Regex("[A-Za-z]:[\\\\/]").containsMatchIn(value)
    ) return true
    val lowered = value.lowercase()
    return listOf("/data/", "/storage/", "/sdcard/", "/mnt/", "/cache/", "/tmp/")
        .any(lowered::contains)
}

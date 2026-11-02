package com.muyuchat.mca

import org.json.JSONObject
import org.json.JSONArray
import com.muyuchat.api.local.looksLikePrivatePath

/**
 * P5/S49: the generated-image history tail cluster (operation enum, JSON field readers,
 * native-execution sanitizers and compact text formatting), moved verbatim out of
 * ImageGenerationHistoryMetadata.kt; the 13 private helpers read by the retained data-class
 * codecs are now internal. Behavior unchanged.
 */
/** Stable, display-oriented operation facet for generated-image history. */
internal enum class ImageGenerationHistoryOperation(
    val wireName: String,
    val displayName: String
) {
    TEXT_TO_IMAGE("text_to_image", "Text to image"),
    IMG2IMG("img2img", "Image to image"),
    INPAINT("inpaint", "Inpaint"),
    CONTROL("control", "Control"),
    EDIT("edit", "Edit"),
    ULTRAFIX("ultrafix", "UltraFix");

    companion object {
        fun from(taskMode: LocalImageTaskMode, ultraFix: Boolean): ImageGenerationHistoryOperation {
            if (ultraFix) return ULTRAFIX
            return entries.first { it.wireName == taskMode.wireName }
        }

        fun fromWireNameOrNull(value: String?): ImageGenerationHistoryOperation? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/** Canonicalizes UltraFix's duplicated scalar controls without requiring staged worker paths. */
// P5/S49: read by the retained history-codec members (pure helper).
internal fun LocalImageGenerationOptions.withCanonicalPersistedUltraFixControls():
    LocalImageGenerationOptions {
    val request = ultraFix ?: return this
    require(taskMode == LocalImageTaskMode.IMG2IMG && batchCount == 1 && preview == null) {
        "Persisted UltraFix controls require one img2img output."
    }
    require(width == null || width == request.targetWidth)
    require(height == null || height == request.targetHeight)
    require(steps == null || steps == request.refinementSteps)
    require(strength == null || kotlin.math.abs(strength - request.strength) <= 1.0e-12)
    require(vaeTiling == null || (
        vaeTiling.tileSize == request.tileSize &&
            kotlin.math.abs(vaeTiling.overlap - request.overlap) <= 1.0e-12
        )) {
        "Persisted UltraFix scalar controls conflict with the structured request."
    }
    return copy(
        width = request.targetWidth,
        height = request.targetHeight,
        steps = request.refinementSteps,
        strength = request.strength,
        vaeTiling = LocalImageVaeTilingOptions(request.tileSize, request.overlap)
    )
}

/**
 * Persisted translation receipts are portable audit metadata, not an install-authenticated
 * attestation that a historical translator ran. Recreate therefore starts again under the current
 * prompt-language policy. Only the two current execution methods are safe to reuse because their effective
 * positive prompt is identical to the retained request text. The former UTF-8-pass-through
 * marker is retained solely so old history remains readable; it cannot authorize a new run.
 */
internal fun LocalImagePromptTransformationMethod.isReusableFromImageHistory(): Boolean =
    this == LocalImagePromptTransformationMethod.DIRECT ||
        this == LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL

/**
 * V1-v3 translated prompt evidence predates the current two-phase semantic contract. Preserve the
 * surrounding history, but deliberately discard that weak evidence so recreate performs a fresh
 * v4 translation. Current-schema corruption must still fail closed instead of silently downgrading.
 */
// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.isDiscardableLegacyTranslatedPromptExecution(
    requestPrompt: String,
    originalNegativePrompt: String?
): Boolean {
    if (opt("method") != LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN.name) return false
    if (opt("originalPrompt") != requestPrompt) return false
    val persistedOriginalNegativePrompt = when {
        !has("originalNegativePrompt") || isNull("originalNegativePrompt") -> null
        opt("originalNegativePrompt") is String -> getString("originalNegativePrompt")
        else -> return false
    }
    if (persistedOriginalNegativePrompt != originalNegativePrompt) return false
    val schemaVersion = historyExactIntOrNull("version") ?: return false
    if (schemaVersion in 1..LAST_LEGACY_TRANSLATED_PROMPT_EXECUTION_VERSION) return true
    val contractVersion = historyExactIntOrNull("translationContractVersion") ?: return false
    return schemaVersion == CURRENT_TRANSLATED_PROMPT_EXECUTION_SCHEMA_VERSION &&
        contractVersion in 1 until CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION
}

private const val LAST_LEGACY_TRANSLATED_PROMPT_EXECUTION_VERSION = 3
private const val CURRENT_TRANSLATED_PROMPT_EXECUTION_SCHEMA_VERSION = 4

internal data class ImageHistoryExecutionFacets(
    val runtimeLabel: String = "",
    val deviceLabel: String = ""
)

/**
 * Structured, display-safe execution facets for image-library filtering. Values are accepted only
 * from the persisted, path-sanitized native execution echo. Unknown and legacy evidence stays
 * empty instead of being inferred from the current model or device.
 */
internal fun ImageGenerationHistoryMetadata.executionFacets(): ImageHistoryExecutionFacets {
    if (backend == ImageBackend.CLOUD) {
        return ImageHistoryExecutionFacets(runtimeLabel = "云端 API")
    }
    val root = runCatching { JSONObject(nativeExecutionJson) }.getOrNull()
        ?: return ImageHistoryExecutionFacets()
    val nativeEffective = root.optJSONObject("nativeEffective")
    fun exactString(key: String): String? = sequenceOf(nativeEffective, root)
        .filterNotNull()
        .mapNotNull { json -> json.opt(key) as? String }
        .map(String::trim)
        .firstOrNull(String::isNotBlank)

    val runtime = exactString("runtime")?.let { wireName ->
        LocalImageRuntime.entries.firstOrNull { it.name == wireName }
    }
    val runtimeLabel = when (runtime) {
        LocalImageRuntime.QNN_HTP -> "QNN HTP"
        LocalImageRuntime.MEDIATEK_NEURON -> "MediaTek Neuron NPU"
        LocalImageRuntime.MNN_DIFFUSION -> "MNN Diffusion"
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
        LocalImageRuntime.ONNX_RUNTIME -> "ONNX Runtime"
        LocalImageRuntime.CUSTOM -> "自定义本地引擎"
        null -> ""
    }

    val transportHtpArch = sequenceOf(root, nativeEffective)
        .filterNotNull()
        .mapNotNull { json -> json.historyExactIntOrNull("transportHtpArch") }
        .firstOrNull { it in 1..999 }
    val backendMode = exactString("backendMode")?.lowercase()
    val deviceLabel = when {
        transportHtpArch != null -> "HTP V$transportHtpArch"
        backendMode == "opencl" -> "OpenCL GPU"
        backendMode == "cpu" -> "CPU"
        runtime == LocalImageRuntime.QNN_HTP && root.optBoolean("npuActive", false) -> "NPU"
        else -> ""
    }
    return ImageHistoryExecutionFacets(
        runtimeLabel = runtimeLabel,
        deviceLabel = deviceLabel
    )
}

// P5/S49: read by the retained history-codec members (pure helper).
internal fun LocalImageInputDraft.toHistoryJson(): JSONObject = JSONObject()
    .put("taskMode", taskMode.wireName)
    .apply {
        inputImageReference.safeHistoryInputReferenceOrNull()?.let { put("inputImageReference", it) }
        maskImageReference.safeHistoryInputReferenceOrNull()?.let { put("maskImageReference", it) }
        controlImageReference.safeHistoryInputReferenceOrNull()?.let { put("controlImageReference", it) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
    }

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.optionalHistoryString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key).takeIf(String::isNotBlank) else null

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.optionalHistoryDouble(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key).also { require(it.isFinite()) } else null

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.historyExactIntOrNull(key: String): Int? {
    val value = historyExactLongOrNull(key) ?: return null
    return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.historyExactLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) as? Number ?: return null
    return when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong()
        is java.math.BigInteger -> value.toString().toLongOrNull()
        is java.math.BigDecimal -> value.stripTrailingZeros()
            .takeIf { it.scale() <= 0 }
            ?.toPlainString()
            ?.toLongOrNull()
        is Float, is Double -> value.toDouble().takeIf { number ->
            number.isFinite() &&
                number % 1.0 == 0.0 &&
                kotlin.math.abs(number) <= MAX_EXACT_JSON_DOUBLE_INTEGER
        }?.toLong()
        else -> runCatching { java.math.BigDecimal(value.toString()).stripTrailingZeros() }
            .getOrNull()
            ?.takeIf { it.scale() <= 0 }
            ?.toPlainString()
            ?.toLongOrNull()
    }
}

private const val MAX_EXACT_JSON_DOUBLE_INTEGER = 9_007_199_254_740_991.0

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.historyFiniteDoubleOrNull(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.historyBooleanOrNull(key: String): Boolean? = opt(key) as? Boolean

// P5/S49: read by the retained history-codec members (pure helper).
internal fun JSONObject.historyNonBlankStringOrNull(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)

// P5/S49: read by the retained history-codec members (pure helper).
internal fun String?.safeHistoryInputReferenceOrNull(): String? = this
    ?.trim()
    ?.takeIf { it.startsWith("content://", ignoreCase = true) }

internal fun sanitizeNativeExecutionJson(
    raw: String,
    allowLegacyQnnDetailStageMaskMigration: Boolean = false
): String {
    val source = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return ""
    val runtime = sequenceOf(source.optJSONObject("nativeEffective"), source)
        .filterNotNull()
        .mapNotNull { json -> json.opt("runtime") as? String }
        .firstOrNull()
    if (runtime == LocalImageRuntime.QNN_HTP.name) {
        val accepted = runCatching {
            if (allowLegacyQnnDetailStageMaskMigration) {
                migrateLegacyQnnDetailStageMasks(source)
            }
            validatePersistedQnnDetailStageMasks(source)
        }.isSuccess
        if (!accepted) return ""
    }
    return sanitizeNativeObject(source).toString()
}

private fun migrateLegacyQnnDetailStageMasks(root: JSONObject) {
    fun migrate(container: JSONObject, hexField: String, legacyField: String) {
        if (!container.has(legacyField)) return
        require(!container.has(hexField)) { "QNN detail stage mask has two wire encodings." }
        val signed = requireNotNull(container.historyExactLongOrNull(legacyField))
        require(signed >= 0L) { "Legacy QNN detail stage mask is outside its accepted range." }
        container.put(hexField, signed.toULong().toFixedUInt64Hex())
        container.remove(legacyField)
    }

    val containers = buildList {
        add(root)
        root.optJSONObject("nativeEffective")?.let(::add)
        root.optJSONObject("sdxlPhaseProof")?.let(::add)
        root.optJSONObject("nativeEffective")
            ?.optJSONObject("sdxlPhaseProof")
            ?.let(::add)
    }
    containers.forEach { container ->
        migrate(
            container,
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            "nativeDetailStageMask"
        )
        listOf("encoder", "unet", "vae").forEach { phase ->
            migrate(
                container,
                "${phase}NativeDetailStageMaskHex",
                "${phase}NativeDetailStageMask"
            )
        }
    }
}

private fun validatePersistedQnnDetailStageMasks(root: JSONObject) {
    val aggregate = root.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    root.optJSONObject("nativeEffective")?.let { nativeEffective ->
        if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
            nativeEffective.has("nativeDetailStageMask")
        ) {
            require(
                nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                    aggregate
            ) { "Persisted QNN detail stage mask conflicts with nativeEffective." }
        }
    }
    listOf("encoder", "unet", "vae").forEach { phase ->
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        if (root.has(hexField) || root.has(legacyField)) {
            val phaseMask = root.strictUInt64Hex(hexField, legacyField)
            root.optJSONObject("sdxlPhaseProof")?.let { proof ->
                if (proof.has(hexField) || proof.has(legacyField)) {
                    require(proof.strictUInt64Hex(hexField, legacyField) == phaseMask) {
                        "Persisted QNN $phase detail stage mask conflicts with its phase proof."
                    }
                }
            }
        }
    }
}

private fun sanitizeNativeObject(source: JSONObject): JSONObject = JSONObject().apply {
    source.keys().asSequence().forEach { key ->
        if (key.isPrivatePathEvidenceKey()) return@forEach
        sanitizeNativeValue(source.opt(key))?.let { value -> put(key, value) }
    }
}

private fun sanitizeNativeArray(source: JSONArray): JSONArray = JSONArray().apply {
    for (index in 0 until source.length()) {
        sanitizeNativeValue(source.opt(index))?.let(::put)
    }
}

private fun sanitizeNativeValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> sanitizeNativeObject(value)
    is JSONArray -> sanitizeNativeArray(value)
    is String -> value.takeUnless(String::looksLikePrivatePath)
    is Number, is Boolean -> value
    else -> null
}

private fun String.isPrivatePathEvidenceKey(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return "path" in normalized || normalized in setOf(
        "bundleroot",
        "modelroot",
        "upscalerroot",
        "loraroot",
        "cachedir",
        "filesdir",
        "tempdir",
        "temporarydirectory",
        "workingdirectory",
        "outputdirectory"
    )
}

// P5/S49: read by the retained history-codec members (pure helper).
internal fun LocalImageTaskMode.displayName(): String = when (this) {
    LocalImageTaskMode.TEXT_TO_IMAGE -> "文生图"
    LocalImageTaskMode.IMG2IMG -> "图生图"
    LocalImageTaskMode.INPAINT -> "局部重绘"
    LocalImageTaskMode.CONTROL -> "Control"
    LocalImageTaskMode.EDIT -> "编辑"
}

// P5/S49: read by the retained history-codec members (pure helper).
internal fun Double.toCompactText(): String = toString().trimEnd('0').trimEnd('.')

package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val NATIVE_FLOAT_RELATIVE_TOLERANCE = 1e-6
private const val SDCPP_STAGE_INPUT_IMAGE_DECODED = 128L
private const val SDCPP_STAGE_MASK_IMAGE_DECODED = 256L
private const val SDCPP_STAGE_CONTROL_IMAGE_DECODED = 512L
private const val SDCPP_STAGE_LORA_VALIDATED = 1_024L
private const val SDCPP_REQUIRED_SUCCESS_STAGES = 127L
private val QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE = 1uL shl 63
private val LOWERCASE_SHA256 = Regex("^[a-f0-9]{64}$")
internal val LOWERCASE_UINT64_HEX = Regex("^[a-f0-9]{16}$")
private val SHARED_QNN_ENCODER_INPUT_SHAPE = listOf(1, 3, 512, 512)
private val SHARED_QNN_ENCODER_OUTPUT_SHAPE = listOf(1, 4, 64, 64)
private const val SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT = 1L * 4L * 64L * 64L
private const val SHARED_QNN_ENCODER_RUNTIME_MODE = "standalone_encoder_then_shared_unet_vae"

private fun nativeFloatMatches(first: Double, second: Double): Boolean {
    if (!first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    return kotlin.math.abs(first - second) <= NATIVE_FLOAT_RELATIVE_TOLERANCE * scale
}

/** Product-visible image operation. This is independent of runtime or device discovery. */
enum class LocalImageTaskMode(val wireName: String) {
    TEXT_TO_IMAGE("text_to_image"),
    IMG2IMG("img2img"),
    INPAINT("inpaint"),
    CONTROL("control"),
    EDIT("edit");

    companion object {
        fun fromWireName(value: String?): LocalImageTaskMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image taskMode: ${value.orEmpty()}")
        }
    }
}

enum class LocalImagePreviewMode(val wireName: String) {
    NONE("none"),
    PROJECTION("projection"),
    TAE("tae"),
    VAE("vae");

    companion object {
        fun fromWireName(value: String?): LocalImagePreviewMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image preview mode: ${value.orEmpty()}")
        }
    }
}

internal fun validateLocalImageRuntimeProductOptions(
    runtime: LocalImageRuntime,
    options: LocalImageGenerationOptions
) {
    options.validateProductInputContract()
    if (options.ultraFix != null && runtime !in setOf(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageRuntime.QNN_HTP
        )
    ) {
        localImageInputReject(
            "unsupported_ultrafix",
            "Runtime ${runtime.name} does not expose a complete native UltraFix pipeline."
        )
    }
    if (options.batchCount != 1 && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_batch_count",
            "Runtime ${runtime.name} currently supports one output per request; native execution was not started."
        )
    }
    if (options.preview != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        runtime != LocalImageRuntime.QNN_HTP
    ) {
        localImageInputReject(
            "unsupported_preview",
            "Runtime ${runtime.name} does not expose a native preview callback."
        )
    }
    if (options.clipSkip != null && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_clip_skip",
            "Runtime ${runtime.name} does not consume clipSkip; native execution was not started."
        )
    }
    if (options.vaeTiling != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        !(runtime == LocalImageRuntime.QNN_HTP && options.ultraFix != null)
    ) {
        localImageInputReject(
            "unsupported_vae_tiling",
            "Runtime ${runtime.name} does not expose native VAE tiling controls."
        )
    }
    if (options.loras.isNotEmpty() && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_lora",
            "Runtime ${runtime.name} does not expose a native LoRA execution path."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        options.distilledGuidance != null
    ) {
        localImageInputReject(
            "unsupported_distilled_guidance",
            "Runtime ${runtime.name} has no distilled-guidance graph input; native execution was not started."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP && options.flowShift != null) {
        localImageInputReject(
            "unsupported_flow_shift",
            "Runtime ${runtime.name} does not expose a writable native flow-shift control."
        )
    }
    if (runtime == LocalImageRuntime.MNN_DIFFUSION &&
        options.taskMode == LocalImageTaskMode.EDIT
    ) {
        if (options.strength != null) {
            localImageInputReject(
                "unsupported_edit_strength",
                "This MNN edit execution path does not consume strength; native execution was not started."
            )
        }
    }
    // Runtime/profile capability checks belong to the resolved execution path. This generic
    // boundary only enforces product-wide lifecycle limits; it must not pre-emptively block an
    // edit or control request merely because a runtime is new or was not previously validated.
}

/**
 * Enforces the selected package's executable capability contract after profile resolution.
 * Runtime-wide checks above remain the early safety boundary; this check prevents a runtime
 * feature from being exposed to a model family whose native conditioner cannot consume it.
 */
internal fun validateLocalImageProfileProductOptions(
    profile: ImageExecutionProfile,
    options: LocalImageGenerationOptions
) {
    val capabilities = profile.capabilities
    val qwen21TextOnly = profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        profile.variant == ImageModelVariant.QWEN_IMAGE_21
    if (qwen21TextOnly && options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE) {
        localImageInputReject("task_mode_execution_unsupported", "Qwen-Image-2.1 GGUF 当前原生引擎仅接入文生图，未包含图像编辑编码器执行链。")
    }
    val sharedQnnVaePreview = profile.hasSharedQnnVaePreviewTopology()
    val runtimeHasNativePreview =
        (profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP && !qwen21TextOnly) ||
            sharedQnnVaePreview
    if (profile.runtime == LocalImageRuntime.QNN_HTP &&
        options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE
    ) {
        val hasExecutableTaskTopology = when (options.taskMode) {
            LocalImageTaskMode.IMG2IMG -> profile.hasExecutableQnnImg2ImgTopology()
            LocalImageTaskMode.INPAINT -> profile.hasExecutableQnnInpaintTopology()
            LocalImageTaskMode.CONTROL -> profile.graph.controlNet != null
            LocalImageTaskMode.TEXT_TO_IMAGE -> true
            LocalImageTaskMode.EDIT -> false
        }
        if (!hasExecutableTaskTopology) {
            localImageInputReject(
                "task_mode_execution_unsupported",
                "The resolved QNN graph topology cannot execute ${options.taskMode.wireName}; " +
                    "a concrete compatible input graph is required before native execution starts."
            )
        }
    }
    if (options.textualInversionIds.isNotEmpty() &&
        (!profile.capabilities.supportsTextualInversion ||
            !profile.tokenizer.supportsTextualInversion)
    ) {
        localImageInputReject(
            "textual_inversion_execution_unsupported",
            "The resolved tokenizer and conditioning graph do not support textual inversion."
        )
    }
    if (options.ultraFix != null && !profile.capabilities.supportsUltraFix) {
        localImageInputReject(
            "ultrafix_execution_unsupported",
            "The resolved graph does not expose the complete UltraFix pipeline."
        )
    }
    options.ultraFix?.let { request ->
        require(request.targetWidth in
            capabilities.ultraFixMinWidth..capabilities.ultraFixMaxWidth &&
            request.targetHeight in
            capabilities.ultraFixMinHeight..capabilities.ultraFixMaxHeight &&
            request.targetWidth % capabilities.ultraFixWidthMultiple == 0 &&
            request.targetHeight % capabilities.ultraFixHeightMultiple == 0 &&
            request.tileSize % capabilities.ultraFixWidthMultiple == 0 &&
            request.tileSize % capabilities.ultraFixHeightMultiple == 0 &&
            (capabilities.ultraFixRequiredTileSize == 0 ||
                request.tileSize == capabilities.ultraFixRequiredTileSize)
        ) {
            "UltraFix target and tile dimensions do not match the resolved graph topology."
        }
    }
    validateLocalImageTaskSamplerCapability(
        profile = profile,
        taskMode = options.taskMode,
        requestedScheduler = profile.scheduler.algorithm
    )
    if (options.batchCount > capabilities.maxBatchCount) {
        localImageInputReject(
            "unsupported_batch_count",
            "Profile ${profile.profileId} supports at most ${capabilities.maxBatchCount} output(s) per request."
        )
    }
    if (options.clipSkip != null && !capabilities.supportsClipSkip) {
        localImageInputReject(
            "unsupported_clip_skip",
            "Profile ${profile.profileId} has no CLIP layer-skip execution path."
        )
    }
    if (options.vaeTiling != null && options.ultraFix == null &&
        !capabilities.supportsVaeTiling
    ) {
        localImageInputReject(
            "unsupported_vae_tiling",
            "Profile ${profile.profileId} does not consume native VAE tiling controls."
        )
    }
    if (options.preview != null && !runtimeHasNativePreview) {
        localImageInputReject(
            "unsupported_preview",
            "Profile ${profile.profileId} does not expose a native live-preview callback."
        )
    }
    if (options.preview != null && profile.runtime == LocalImageRuntime.QNN_HTP) {
        if (options.preview.interval !in 1..10) {
            localImageInputReject(
                "invalid_preview_interval",
                "QNN live preview interval must be between 1 and 10 steps."
            )
        }
        val supportedPreview =
            sharedQnnVaePreview && options.preview.mode == LocalImagePreviewMode.VAE
        if (!supportedPreview) {
            localImageInputReject(
                "unsupported_preview",
                "QNN preview requires a resolved shared-QNN VAE topology."
            )
        }
    }
    if (options.loras.isNotEmpty() && !capabilities.supportsLora) {
        localImageInputReject(
            "unsupported_lora",
            "Profile ${profile.profileId} does not expose LoRA adapter execution."
        )
    }
}

/**
 * Verifies native echo before converting worker-private paths into non-sensitive content hashes.
 * Kotlin-synthesized request metadata alone is never accepted as execution proof.
 */
internal fun verifyAndSanitizeStableDiffusionProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "stable-diffusion.cpp did not report nativeEffective image input evidence."
        )
    fun requireExactString(field: String, expected: String) {
        require(result.optString(field) == expected && nativeEffective.optString(field) == expected) {
            "stable-diffusion.cpp did not execute the requested $field."
        }
    }
    fun requireExactInt(field: String, expected: Int) {
        require(result.optInt(field, Int.MIN_VALUE) == expected &&
            nativeEffective.optInt(field, Int.MIN_VALUE) == expected
        ) { "stable-diffusion.cpp did not execute the requested $field." }
    }
    fun requireExactDouble(field: String, expected: Double) {
        val outer = result.optDouble(field, Double.NaN)
        val inner = nativeEffective.optDouble(field, Double.NaN)
        require(
            nativeFloatMatches(outer, expected) &&
                nativeFloatMatches(inner, expected)
        ) { "stable-diffusion.cpp did not execute the requested $field." }
    }
    fun requirePathAndCount(
        pathField: String,
        countField: String,
        expected: LocalImagePreparedInput?
    ) {
        val hashField = pathField.removeSuffix("Path") + "Sha256"
        val expectedCount = if (expected == null) 0 else 1
        requireExactInt(countField, expectedCount)
        require(result.has(pathField) && !result.isNull(pathField) &&
            nativeEffective.has(pathField) && !nativeEffective.isNull(pathField)
        ) { "stable-diffusion.cpp did not report $pathField execution evidence." }
        require(result.has(hashField) && !result.isNull(hashField) &&
            nativeEffective.has(hashField) && !nativeEffective.isNull(hashField)
        ) { "stable-diffusion.cpp did not report $hashField execution evidence." }
        val outerPath = result.optString(pathField)
        val innerPath = nativeEffective.optString(pathField)
        val outerSha256 = result.optString(hashField).lowercase()
        val innerSha256 = nativeEffective.optString(hashField).lowercase()
        if (expected == null) {
            require(outerPath.isBlank() && innerPath.isBlank() &&
                outerSha256.isBlank() && innerSha256.isBlank()
            ) {
                "stable-diffusion.cpp reported evidence for an unused $pathField."
            }
        } else {
            val expectedPath = File(expected.path).canonicalPath
            require(outerPath.isNotBlank() && innerPath.isNotBlank() &&
                File(outerPath).canonicalPath == expectedPath &&
                File(innerPath).canonicalPath == expectedPath
            ) { "stable-diffusion.cpp did not execute the prepared $pathField." }
            require(outerSha256 == expected.sha256 && innerSha256 == expected.sha256) {
                "stable-diffusion.cpp did not prove the decoded bytes for $pathField."
            }
        }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }

    requireExactString("taskMode", options.taskMode.wireName)
    requirePathAndCount("inputImagePath", "inputImageExecutionCount", options.inputImage)
    requirePathAndCount("maskImagePath", "maskImageExecutionCount", options.maskImage)
    requirePathAndCount("controlImagePath", "controlImageExecutionCount", options.controlImage)
    requireExactDouble("strength", options.strength ?: 1.0)
    requireExactDouble("controlStrength", options.controlStrength ?: 1.0)
    requireExactInt("clipSkip", options.clipSkip ?: -1)
    requireExactInt("batchCount", options.batchCount)

    val timetableCount = result.optInt("timetableCount", -1)
    require(timetableCount > 0 &&
        nativeEffective.optInt("timetableCount", -1) == timetableCount
    ) { "stable-diffusion.cpp timetableCount evidence is missing or inconsistent." }
    require(result.has("useCfg") && !result.isNull("useCfg") &&
        nativeEffective.has("useCfg") && !nativeEffective.isNull("useCfg")
    ) { "stable-diffusion.cpp did not report its actual CFG mode." }
    val useCfg = result.getBoolean("useCfg")
    require(nativeEffective.getBoolean("useCfg") == useCfg) {
        "stable-diffusion.cpp CFG evidence differs between the result and nativeEffective."
    }
    val expectedUnetExecutionCountLong =
        timetableCount.toLong() * (if (useCfg) 2L else 1L)
    require(expectedUnetExecutionCountLong <= Int.MAX_VALUE.toLong()) {
        "stable-diffusion.cpp CFG execution count exceeds the supported range."
    }
    val expectedUnetExecutionCount = expectedUnetExecutionCountLong.toInt()
    require(result.optInt("unetExecutionCount", -1) == expectedUnetExecutionCount &&
        nativeEffective.optInt("unetExecutionCount", -1) == expectedUnetExecutionCount
    ) {
        "stable-diffusion.cpp physical diffusion count does not match timetableCount and CFG."
    }

    val outerControlNetEvidence = result.optJSONObject("controlNetEvidence")
        ?: error("stable-diffusion.cpp did not report outer ControlNet execution evidence.")
    val nativeControlNetEvidence = nativeEffective.optJSONObject("controlNetEvidence")
        ?: error("stable-diffusion.cpp did not report nativeEffective ControlNet execution evidence.")
    val controlEvidenceFields = listOf(
        "computeAttemptCount",
        "computeSuccessCount",
        "positiveComputeAttemptCount",
        "positiveComputeSuccessCount",
        "negativeComputeAttemptCount",
        "negativeComputeSuccessCount",
        "residualConsumptionCount",
        "positiveResidualConsumptionCount",
        "negativeResidualConsumptionCount",
        "auxiliaryResidualConsumptionCount"
    )
    val controlCounts = controlEvidenceFields.associateWith { field ->
        val outer = outerControlNetEvidence.optLong(field, -1L)
        val native = nativeControlNetEvidence.optLong(field, -1L)
        require(outer >= 0L && native == outer) {
            "stable-diffusion.cpp ControlNet $field evidence is missing or inconsistent."
        }
        outer
    }
    val controlAttemptCount = controlCounts.getValue("computeAttemptCount")
    val controlSuccessCount = controlCounts.getValue("computeSuccessCount")
    val positiveControlAttemptCount = controlCounts.getValue("positiveComputeAttemptCount")
    val positiveControlSuccessCount = controlCounts.getValue("positiveComputeSuccessCount")
    val negativeControlAttemptCount = controlCounts.getValue("negativeComputeAttemptCount")
    val negativeControlSuccessCount = controlCounts.getValue("negativeComputeSuccessCount")
    val residualConsumptionCount = controlCounts.getValue("residualConsumptionCount")
    val positiveResidualConsumptionCount =
        controlCounts.getValue("positiveResidualConsumptionCount")
    val negativeResidualConsumptionCount =
        controlCounts.getValue("negativeResidualConsumptionCount")
    val auxiliaryResidualConsumptionCount =
        controlCounts.getValue("auxiliaryResidualConsumptionCount")
    require(controlAttemptCount == positiveControlAttemptCount + negativeControlAttemptCount &&
        controlSuccessCount == positiveControlSuccessCount + negativeControlSuccessCount &&
        residualConsumptionCount == positiveResidualConsumptionCount +
        negativeResidualConsumptionCount + auxiliaryResidualConsumptionCount
    ) { "stable-diffusion.cpp ControlNet evidence is not completely classified by branch." }
    val expectedControl = options.controlImage != null
    val outerControlStrengthApplied = result.optBoolean("controlStrengthApplied", false)
    val nativeControlStrengthApplied =
        nativeEffective.optBoolean("controlStrengthApplied", false)
    val expectedPositiveControlCount = if (expectedControl) timetableCount.toLong() else 0L
    val expectedNegativeControlCount =
        if (expectedControl && useCfg) timetableCount.toLong() else 0L
    val expectedTotalControlCount =
        expectedPositiveControlCount + expectedNegativeControlCount
    val outerImageInputConsumption = result.optJSONObject("imageInputConsumption")
        ?: error("stable-diffusion.cpp did not report outer imageInputConsumption evidence.")
    val nativeImageInputConsumption = nativeEffective.optJSONObject("imageInputConsumption")
        ?: error("stable-diffusion.cpp did not report nativeEffective imageInputConsumption evidence.")
    val expectedControlConsumption = if (expectedControl) "controlnet_residual" else "none"
    require(outerImageInputConsumption.optString("control") == expectedControlConsumption &&
        nativeImageInputConsumption.optString("control") == expectedControlConsumption
    ) { "stable-diffusion.cpp ControlNet residual-consumption evidence is inconsistent." }
    if (expectedControl) {
        require(controlAttemptCount == expectedTotalControlCount &&
            controlSuccessCount == expectedTotalControlCount &&
            positiveControlAttemptCount == expectedPositiveControlCount &&
            positiveControlSuccessCount == expectedPositiveControlCount &&
            negativeControlAttemptCount == expectedNegativeControlCount &&
            negativeControlSuccessCount == expectedNegativeControlCount &&
            residualConsumptionCount == expectedTotalControlCount &&
            positiveResidualConsumptionCount == expectedPositiveControlCount &&
            negativeResidualConsumptionCount == expectedNegativeControlCount &&
            auxiliaryResidualConsumptionCount == 0L &&
            outerControlStrengthApplied && nativeControlStrengthApplied
        ) {
            "stable-diffusion.cpp did not prove successful ControlNet compute and residual consumption."
        }
    } else {
        require(controlCounts.values.all { it == 0L } &&
            !outerControlStrengthApplied && !nativeControlStrengthApplied
        ) { "stable-diffusion.cpp reported unrequested ControlNet execution." }
    }

    val nativeTiling = nativeEffective.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report vaeTiling evidence.")
    val outerTiling = result.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report outer vaeTiling evidence.")
    val expectedTiling = options.vaeTiling
    val expectedEnabled = expectedTiling != null
    require(nativeTiling.optBoolean("enabled") == expectedEnabled &&
        outerTiling.optBoolean("enabled") == expectedEnabled
    ) { "stable-diffusion.cpp VAE tiling enablement did not match the request." }
    val expectedRequestedTileSize = expectedTiling?.tileSize ?: 0
    val expectedRequestedOverlap = expectedTiling?.overlap ?: 0.0
    listOf(nativeTiling, outerTiling).forEach { tiling ->
        require(tiling.optInt("requestedTileSize", -1) == expectedRequestedTileSize &&
            nativeFloatMatches(
                tiling.optDouble("requestedOverlap", Double.NaN),
                expectedRequestedOverlap
            )
        ) { "stable-diffusion.cpp VAE tiling request evidence is inconsistent." }
    }
    fun verifyVaeTilingPhase(
        phase: String,
        requireExecution: Boolean,
        exactInvocationCount: Long? = null
    ) {
        val outerPhase = outerTiling.optJSONObject(phase)
            ?: error("stable-diffusion.cpp did not report outer VAE $phase evidence.")
        val nativePhase = nativeTiling.optJSONObject(phase)
            ?: error("stable-diffusion.cpp did not report nativeEffective VAE $phase evidence.")
        val integerFields = listOf(
            "invocationCount",
            "successCount",
            "plannedTileCount",
            "tileComputeAttemptCount",
            "tileComputeSuccessCount",
            "tileSizeX",
            "tileSizeY"
        )
        val values = integerFields.associateWith { field ->
            val outer = outerPhase.optLong(field, -1L)
            val native = nativePhase.optLong(field, -1L)
            require(outer >= 0L && native == outer) {
                "stable-diffusion.cpp VAE $phase $field evidence is missing or inconsistent."
            }
            outer
        }
        val overlapX = outerPhase.optDouble("overlapX", Double.NaN)
        val overlapY = outerPhase.optDouble("overlapY", Double.NaN)
        require(nativeFloatMatches(nativePhase.optDouble("overlapX", Double.NaN), overlapX) &&
            nativeFloatMatches(nativePhase.optDouble("overlapY", Double.NaN), overlapY)
        ) { "stable-diffusion.cpp VAE $phase overlap evidence is inconsistent." }
        val invocationCount = values.getValue("invocationCount")
        if (invocationCount == 0L) {
            require(!requireExecution && values.values.all { it == 0L } &&
                overlapX == 0.0 && overlapY == 0.0
            ) { "stable-diffusion.cpp did not execute required VAE $phase tiling." }
            return
        }
        exactInvocationCount?.let { expectedCount ->
            require(invocationCount == expectedCount) {
                "stable-diffusion.cpp VAE $phase invocation count differs from the physical output plan."
            }
        }
        require(expectedEnabled &&
            values.getValue("successCount") == invocationCount &&
            values.getValue("plannedTileCount") > 0L &&
            values.getValue("tileComputeAttemptCount") ==
                values.getValue("plannedTileCount") &&
            values.getValue("tileComputeSuccessCount") ==
                values.getValue("tileComputeAttemptCount") &&
            values.getValue("tileSizeX") > 0L && values.getValue("tileSizeY") > 0L &&
            overlapX.isFinite() && overlapX in 0.0..0.5 &&
            overlapY.isFinite() && overlapY in 0.0..0.5
        ) { "stable-diffusion.cpp VAE $phase tiling did not complete its physical tile plan." }
    }
    verifyVaeTilingPhase(
        "encode",
        requireExecution = expectedEnabled && options.inputImage != null
    )
    verifyVaeTilingPhase(
        "decode",
        requireExecution = expectedEnabled,
        exactInvocationCount = if (expectedEnabled) options.batchCount.toLong() else null
    )

    val expectedPreview = options.preview
    val previewRequested = result.optBoolean("previewRequested", false)
    val previewMode = result.optString("previewMode")
    val previewInterval = result.optInt("previewInterval", -1)
    val previewPublicationCount = result.optInt("previewPublicationCount", -1)
    val previewLastStep = result.optInt("previewLastStep", -1)
    val previewLastRevision = result.optLong("previewLastRevision", -1L)
    if (expectedPreview == null) {
        require(
            !previewRequested && previewMode == "none" && previewInterval == 0 &&
                previewPublicationCount == 0 && previewLastStep == 0 && previewLastRevision == 0L
        ) { "stable-diffusion.cpp reported an unrequested preview callback." }
    } else {
        val totalSteps = result.optInt("steps", -1)
        require(
            previewRequested && previewMode == expectedPreview.mode.wireName &&
                previewInterval == expectedPreview.interval
        ) { "stable-diffusion.cpp preview mode or interval did not match the request." }
        require(
            previewPublicationCount > 0 && previewLastStep in 1..totalSteps &&
                previewLastRevision > 0L
        ) { "stable-diffusion.cpp did not prove publication of a real preview frame." }
    }

    val outerLoras = result.optJSONArray("loras")
        ?: error("stable-diffusion.cpp did not report outer LoRA execution evidence.")
    val nativeLoras = nativeEffective.optJSONArray("loras")
        ?: error("stable-diffusion.cpp did not report nativeEffective LoRA execution evidence.")
    require(outerLoras.length() == options.loras.size &&
        nativeLoras.length() == options.loras.size
    ) { "stable-diffusion.cpp LoRA evidence count differs from the request." }
    options.loras.forEachIndexed { index, expected ->
        listOf(outerLoras, nativeLoras).forEach { array ->
            val item = array.optJSONObject(index)
                ?: error("stable-diffusion.cpp LoRA evidence item must be an object.")
            require(!item.has("path")) {
                "stable-diffusion.cpp exposed a worker-private LoRA path."
            }
            require(item.optString("id") == expected.id &&
                item.optString("sha256").lowercase() == expected.sha256 &&
                nativeFloatMatches(
                    item.optDouble("multiplier", Double.NaN),
                    expected.multiplier
                )
            ) { "stable-diffusion.cpp LoRA identity or multiplier differs from the request." }
        }
    }
    val outerLoraEvidence = result.optJSONObject("loraEvidence")
        ?: error("stable-diffusion.cpp did not report outer LoRA count evidence.")
    val nativeLoraEvidence = nativeEffective.optJSONObject("loraEvidence")
        ?: error("stable-diffusion.cpp did not report nativeEffective LoRA count evidence.")
    listOf(outerLoraEvidence, nativeLoraEvidence).forEach { evidence ->
        val expectedCount = options.loras.size
        require(evidence.optInt("requestedCount", -1) == expectedCount &&
            evidence.optInt("loadedCount", -1) == expectedCount &&
            evidence.optInt("appliedCount", -1) == expectedCount
        ) { "stable-diffusion.cpp did not load and apply the complete LoRA set." }
        val appliedTensorCount = evidence.optLong("appliedTensorCount", -1L)
        require(
            if (expectedCount == 0) appliedTensorCount == 0L else appliedTensorCount > 0L
        ) { "stable-diffusion.cpp LoRA tensor execution evidence is invalid." }
    }

    var requiredStageMask = SDCPP_REQUIRED_SUCCESS_STAGES
    if (options.inputImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_INPUT_IMAGE_DECODED
    if (options.maskImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_MASK_IMAGE_DECODED
    if (options.controlImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_CONTROL_IMAGE_DECODED
    if (options.loras.isNotEmpty()) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_LORA_VALIDATED
    listOf("nativeStageMask", "nativeDetailStageMask").forEach { field ->
        val stageMask = result.optLong(field, -1L)
        require(stageMask >= 0L && (stageMask and requiredStageMask) == requiredStageMask) {
            "stable-diffusion.cpp $field is missing required execution stages."
        }
    }

    val audit = options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
        .put("controlNetEvidence", JSONObject(nativeControlNetEvidence.toString()))
        .put("vaeTiling", JSONObject(nativeTiling.toString()))
        .put("loras", JSONArray(nativeLoras.toString()))
        .put("loraEvidence", JSONObject(nativeLoraEvidence.toString()))
    return audit
}

/** MNN must echo every product-input field and prove the same count with concrete outputs. */
internal fun verifyAndSanitizeMnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report nativeEffective image input evidence."
        )
    require(result.has("taskMode") && !result.isNull("taskMode") &&
        nativeEffective.has("taskMode") && !nativeEffective.isNull("taskMode")
    ) {
        "MNN-Diffusion is missing required taskMode execution evidence."
    }
    require(result.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion did not execute the requested taskMode."
    }
    require(nativeEffective.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion nativeEffective taskMode differs from the request."
    }

    val outputs = result.optJSONArray("outputs")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report its concrete outputs array."
        )
    require(outputs.length() == options.batchCount) {
        "MNN-Diffusion output count differs from batchCount."
    }
    val outputPath = result.optString("outputPath")
    val nativeOutputPath = nativeEffective.optString("outputPath")
    val outputBytes = result.optLong("outputBytes", -1L)
    val nativeOutputBytes = nativeEffective.optLong("outputBytes", -1L)
    val outputSha256 = result.optString("outputSha256")
    val nativeOutputSha256 = nativeEffective.optString("outputSha256")
    require(outputPath.isNotBlank() && outputPath == nativeOutputPath &&
        outputBytes > 0L && outputBytes == nativeOutputBytes &&
        outputSha256.matches(Regex("^[0-9a-f]{64}$")) &&
        outputSha256 == nativeOutputSha256
    ) { "MNN-Diffusion output publication proof is missing or inconsistent." }
    for (index in 0 until outputs.length()) {
        val output = outputs.optJSONObject(index)
            ?: error("MNN-Diffusion output item must be an object.")
        require(output.optInt("index", -1) == index) {
            "MNN-Diffusion output indices are not contiguous."
        }
        require(output.optString("path") == outputPath &&
            output.optLong("outputBytes", -1L) == outputBytes &&
            output.optString("outputSha256") == outputSha256
        ) {
            "MNN-Diffusion output item differs from the committed publication proof."
        }
        require(output.optString("mimeType") == "image/png") {
            "MNN-Diffusion output item must be a PNG."
        }
        require(output.has("seed") && output.getLong("seed") == nativeEffective.getLong("seed")) {
            "MNN-Diffusion output seed differs from nativeEffective."
        }
    }
    require(result.has("batchCount") && !result.isNull("batchCount") &&
        nativeEffective.has("batchCount") && !nativeEffective.isNull("batchCount") &&
        result.getInt("batchCount") == options.batchCount &&
        nativeEffective.getInt("batchCount") == options.batchCount
    ) {
        "MNN-Diffusion batchCount execution evidence differs from the request."
    }

    fun verifyRole(
        pathField: String,
        countField: String,
        hashField: String,
        expected: LocalImagePreparedInput?
    ) {
        val expectedCount = if (expected == null) 0 else 1
        require(result.has(countField) && !result.isNull(countField) &&
            nativeEffective.has(countField) && !nativeEffective.isNull(countField)
        ) {
            "MNN-Diffusion is missing required $countField execution evidence."
        }
        require(result.getInt(countField) == expectedCount) {
            "MNN-Diffusion $countField evidence differs from the request."
        }
        require(nativeEffective.getInt(countField) == expectedCount) {
            "MNN-Diffusion nativeEffective $countField differs from the request."
        }
        require(result.has(pathField) && !result.isNull(pathField) &&
            nativeEffective.has(pathField) && !nativeEffective.isNull(pathField)
        ) {
            "MNN-Diffusion is missing required $pathField execution evidence."
        }
        val outerPath = result.getString(pathField)
        val innerPath = nativeEffective.getString(pathField)
        if (expected == null) {
            require(outerPath.isBlank() && innerPath.isBlank()) {
                "MNN-Diffusion reported an unused $pathField."
            }
            require(!result.has(hashField) && !nativeEffective.has(hashField)) {
                "MNN-Diffusion reported unexpected $hashField evidence."
            }
        } else {
            val expectedPath = File(expected.path).canonicalPath
            require(outerPath.isNotBlank() && File(outerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion did not execute the prepared $pathField."
            }
            require(innerPath.isNotBlank() && File(innerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion nativeEffective did not bind the prepared $pathField."
            }
            require(result.has(hashField) && !result.isNull(hashField) &&
                nativeEffective.has(hashField) && !nativeEffective.isNull(hashField) &&
                result.getString(hashField) == expected.sha256 &&
                nativeEffective.getString(hashField) == expected.sha256
            ) {
                "MNN-Diffusion did not report the native-consumed $hashField."
            }
        }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }

    verifyRole("inputImagePath", "inputImageExecutionCount", "inputImageSha256", options.inputImage)
    verifyRole("maskImagePath", "maskImageExecutionCount", "maskImageSha256", options.maskImage)
    verifyRole(
        "controlImagePath",
        "controlImageExecutionCount",
        "controlImageSha256",
        options.controlImage
    )
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
}

/**
 * QNN currently exposes product image inputs only for the native ControlNet path.  Verify that the
 * prepared worker file was actually consumed on every scheduler step, then remove its private path
 * before the execution object can cross back into the main process or Local API response.
 */
private fun validateQnnNativeDetailStageMask(result: JSONObject): ULong {
    val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    result.optJSONObject("nativeEffective")?.let { nativeEffective ->
        if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
            nativeEffective.has("nativeDetailStageMask")
        ) {
            require(
                nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                    detailStageMask
            ) { "QNN detail stage mask conflicts with nativeEffective." }
        }
    }
    return detailStageMask
}

internal fun verifyAndSanitizeQnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.CONTROL &&
        options.controlImage != null && options.inputImage == null && options.maskImage == null
    ) {
        "QNN image-input evidence is currently defined only for a control request."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN ControlNet did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName
    ) { "QNN ControlNet did not execute the requested taskMode." }
    require(result.optInt("batchCount", -1) == 1 &&
        nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN ControlNet must report its single concrete output." }

    fun requireExactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN ControlNet $field evidence differs from the request." }
    }
    requireExactCount("inputImageExecutionCount", 0)
    requireExactCount("maskImageExecutionCount", 0)
    requireExactCount("controlImageExecutionCount", 1)

    listOf("inputImagePath", "maskImagePath").forEach { field ->
        require(result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()) {
            "QNN ControlNet reported an unused $field."
        }
        result.remove(field)
        nativeEffective.remove(field)
    }
    val expectedInput = requireNotNull(options.controlImage)
    val expectedPath = File(expectedInput.path).canonicalPath
    val outerPath = result.optString("controlImagePath")
    val innerPath = nativeEffective.optString("controlImagePath")
    require(outerPath.isNotBlank() && innerPath.isNotBlank() &&
        File(outerPath).canonicalPath == expectedPath &&
        File(innerPath).canonicalPath == expectedPath
    ) { "QNN ControlNet did not consume the prepared control image." }
    result.remove("controlImagePath")
    nativeEffective.remove("controlImagePath")

    require(result.optString("controlImageSha256") == expectedInput.sha256 &&
        nativeEffective.optString("controlImageSha256") == expectedInput.sha256
    ) { "QNN ControlNet control-image digest differs from the prepared input." }
    val expectedStrength = options.controlStrength ?: 1.0
    require(nativeFloatMatches(result.optDouble("controlStrength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(
            nativeEffective.optDouble("controlStrength", Double.NaN),
            expectedStrength
        )
    ) { "QNN ControlNet did not execute the requested controlStrength." }
    require(result.optBoolean("controlStrengthApplied", false) &&
        nativeEffective.optBoolean("controlStrengthApplied", false)
    ) { "QNN ControlNet did not apply controlStrength." }

    val timetableCount = result.optInt("timetableCount", -1)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "QNN ControlNet CFG evidence differs between the result and nativeEffective."
    }
    val expectedExecutions = timetableCount
    require(timetableCount > 0 &&
        result.optInt("controlNetExecutionCount", -1) == expectedExecutions &&
        nativeEffective.optInt("controlNetExecutionCount", -1) == expectedExecutions
    ) { "QNN ControlNet did not execute once per scheduler step." }
    val residualTensorCount = result.optInt("controlNetResidualTensorCount", -1)
    require(residualTensorCount > 0 &&
        nativeEffective.optInt("controlNetResidualTensorCount", -1) == residualTensorCount
    ) { "QNN ControlNet residual tensor evidence is invalid." }
    val expectedResidualWrites = Math.multiplyExact(expectedExecutions, residualTensorCount)
    require(result.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites &&
        nativeEffective.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites
    ) { "QNN ControlNet did not inject every residual on every scheduler step." }
    val expectedResidualReuseCount = if (useCfg) timetableCount else 0
    require(result.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount &&
        nativeEffective.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount
    ) { "QNN ControlNet residual reuse evidence does not match the CFG execution." }
    val expectedConditioningBranch = "positive"
    require(result.optString("controlNetConditioningBranch") == expectedConditioningBranch &&
        nativeEffective.optString("controlNetConditioningBranch") == expectedConditioningBranch
    ) { "QNN ControlNet did not execute on the required positive conditioning branch." }
    require(result.optBoolean("controlNetInputConsumed", false) &&
        nativeEffective.optBoolean("controlNetInputConsumed", false)
    ) { "QNN ControlNet did not report consuming the prepared control tensor." }

    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 0)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 1)
        .put("controlNetExecutionCount", expectedExecutions)
        .put("controlNetResidualTensorCount", residualTensorCount)
        .put("controlNetResidualWriteCount", expectedResidualWrites)
        .put("controlNetResidualUnetReuseCount", expectedResidualReuseCount)
        .put("controlNetConditioningBranch", expectedConditioningBranch)
}

/** Verifies the three-process SDXL encoder -> UNet -> decoder img2img chain. */
internal fun verifyAndSanitizeQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: SdxlPreparedInputTensor,
    expectedVaeEncoderContextSha256: String
): JSONObject {
    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.IMG2IMG &&
        options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "QNN SDXL img2img verification requires exactly one prepared input image." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN SDXL img2img did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        result.optInt("batchCount", -1) == 1 && nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN SDXL img2img task or batch evidence differs from the request." }
    fun exactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN SDXL img2img $field evidence differs from the request." }
    }
    exactCount("inputImageExecutionCount", 1)
    exactCount("maskImageExecutionCount", 0)
    exactCount("controlImageExecutionCount", 0)
    val expectedInput = requireNotNull(options.inputImage)
    require(File(result.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        File(nativeEffective.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        result.getString("inputImageSha256").lowercase() == expectedInput.sha256 &&
        nativeEffective.getString("inputImageSha256").lowercase() == expectedInput.sha256
    ) { "QNN SDXL source-image preprocessing provenance differs from the worker input." }
    require(!result.optBoolean("inputImageSourceReadByNative", true) &&
        result.optString("inputImageSourceValidation") == "android_preprocess_provenance"
    ) { "QNN SDXL encoder must consume the committed tensor without reopening the source image." }
    require(File(result.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        File(nativeEffective.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        result.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256 &&
        nativeEffective.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256
    ) { "QNN SDXL encoder did not consume the exact Android preprocessing tensor." }
    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    require(result.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        nativeEffective.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        result.optString("inputImageTensorDtype") == SDXL_INPUT_TENSOR_DTYPE &&
        result.optString("inputImageTensorLayout") == SDXL_INPUT_TENSOR_LAYOUT &&
        result.optString("inputImageTensorRange") == SDXL_INPUT_TENSOR_RANGE &&
        result.optLong("inputImageTensorBytes", -1L) == prepared.tensorBytes
    ) { "QNN SDXL img2img preprocessing evidence is incomplete." }
    val expectedStrength = options.strength ?: 1.0
    require(nativeFloatMatches(result.optDouble("strength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(nativeEffective.optDouble("strength", Double.NaN), expectedStrength)
    ) { "QNN SDXL UNet did not execute the requested img2img strength." }
    val schedule = resolveSdxlImg2ImgSchedule(
        steps = result.getInt("steps"),
        fullTimetableCount = result.getInt("fullTimetableCount"),
        strength = expectedStrength
    )
    require(result.getInt("timetableCount") == schedule.effectiveSteps &&
        nativeEffective.getInt("timetableCount") == schedule.effectiveSteps &&
        result.getInt("effectiveDenoiseSteps") == schedule.effectiveSteps &&
        result.getInt("img2imgBeginIndex") == schedule.beginIndex &&
        nativeEffective.getInt("img2imgBeginIndex") == schedule.beginIndex
    ) { "QNN SDXL img2img did not execute the exact strength-derived tail timetable." }
    val encoderPid = result.optInt("encoderWorkerPid", -1)
    val unetPid = result.optInt("unetWorkerPid", -1)
    val vaePid = result.optInt("vaeWorkerPid", -1)
    val phaseSequences = listOf(
        result.optLong("encoderNativeGenerationSequence", -1L),
        result.optLong("unetNativeGenerationSequence", -1L),
        result.optLong("vaeNativeGenerationSequence", -1L)
    )
    val phaseProof = result.getJSONObject("sdxlPhaseProof")
    val nativePhaseProof = nativeEffective.getJSONObject("sdxlPhaseProof")
    val expectedEncoderExecutionCount = options.ultraFix?.let { request ->
        val expectedTileCount = Math.toIntExact(localQnnUltraFixTilePlanEvidence(request).tileCount)
        require(
            phaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                phaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount
        ) { "QNN SDXL UltraFix encoder evidence differs from its deterministic tile plan." }
        expectedTileCount
    } ?: 1
    require(encoderPid > 0 && unetPid > 0 && vaePid > 0 &&
        phaseSequences.all { it > 0L } && phaseSequences.distinct().size == 3 &&
        result.optBoolean("encoderProcessDeathConfirmed") &&
        result.optBoolean("unetProcessDeathConfirmed") &&
        result.optBoolean("vaeProcessDeathConfirmed") &&
        result.optInt("encoderContextLoadCount", -1) == 1 &&
        result.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        phaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        nativePhaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount
    ) { "QNN SDXL img2img did not prove three ordered disposable graph processes." }
    val phaseDetailStageMasks = listOf("encoder", "unet", "vae").map { phase ->
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        val outer = result.strictUInt64Hex(hexField, legacyField)
        require(phaseProof.strictUInt64Hex(hexField, legacyField) == outer) {
            "QNN SDXL $phase detail stage mask conflicts with the phase proof."
        }
        outer
    }
    require(phaseDetailStageMasks.fold(0uL, ULong::or) == detailStageMask) {
        "QNN SDXL aggregate detail stage mask differs from its three phase proofs."
    }
    val encoderLatentSha256 = result.optString("encoderLatentSha256").lowercase()
    val encoderContextSha256 = result.optString("encoderContextSha256").lowercase()
    require(encoderLatentSha256.matches(Regex("[a-f0-9]{64}")) &&
        nativeEffective.optString("encoderLatentSha256").lowercase() == encoderLatentSha256
    ) { "QNN SDXL UNet encoder-latent provenance is invalid." }
    require(expectedVaeEncoderContextSha256.matches(Regex("[a-f0-9]{64}")) &&
        encoderContextSha256 == expectedVaeEncoderContextSha256 &&
        nativeEffective.optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        result.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        nativeEffective.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256
    ) { "QNN SDXL VAE encoder context provenance is invalid." }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", encoderContextSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
}

/**
 * Verifies one standalone encoder followed by one coherent shared-QNN UNet/VAE session. This is
 * intentionally separate from the split-SDXL verifier, whose three disposable-process proof must
 * remain intact.
 */
internal fun verifyAndSanitizeQnnUltraFixProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: Any,
    usesSplitWorkers: Boolean,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(options.ultraFix != null) {
        "QNN UltraFix input verification requires a structured UltraFix request."
    }
    return if (usesSplitWorkers) {
        verifyAndSanitizeQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? SdxlPreparedInputTensor
                ?: error("QNN UltraFix split input tensor has the wrong topology."),
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256
        )
    } else {
        verifyAndSanitizeSharedQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? QnnPreparedInputTensor
                ?: error("QNN UltraFix shared input tensor has the wrong topology."),
            profile = profile,
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256,
            expectedVaeEncoderGraphName = expectedVaeEncoderGraphName
        )
    }
}

internal fun verifyAndSanitizeSharedQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: QnnPreparedInputTensor,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(profile.hasSharedQnnImg2ImgTopology()) {
        "Shared-QNN img2img verification requires a coherent QNN UNet/VAE topology with a VAE encoder."
    }
    val ultraFixRequest = options.ultraFix
    require(if (ultraFixRequest == null) {
        prepared.tensorWidth == 512 && prepared.tensorHeight == 512
    } else {
        prepared.tensorWidth == ultraFixRequest.targetWidth &&
            prepared.tensorHeight == ultraFixRequest.targetHeight
    }) {
        "Shared-QNN img2img verification requires the committed tensor dimensions."
    }
    require(expectedVaeEncoderContextSha256.matches(LOWERCASE_SHA256)) {
        "Resolved shared-QNN VAE encoder context SHA-256 is malformed."
    }
    require(expectedVaeEncoderGraphName.isNotBlank()) {
        "Resolved shared-QNN VAE encoder graph name is missing."
    }
    require(
        options.taskMode == LocalImageTaskMode.IMG2IMG &&
            options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "Shared-QNN img2img verification requires exactly one prepared input image." }

    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(detailStageMask and QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE != 0uL) {
        "Shared-QNN img2img did not prove a real VAE encoder graphExecute call."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "Shared-QNN img2img did not report nativeEffective image input evidence."
        )

    fun exactString(field: String, expected: String) {
        require(result.optString(field) == expected && nativeEffective.optString(field) == expected) {
            "Shared-QNN img2img $field evidence differs from the executed request."
        }
    }
    fun exactInt(field: String, expected: Int) {
        require(result.optInt(field, Int.MIN_VALUE) == expected &&
            nativeEffective.optInt(field, Int.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactLong(field: String, expected: Long) {
        require(result.optLong(field, Long.MIN_VALUE) == expected &&
            nativeEffective.optLong(field, Long.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactBoolean(field: String, expected: Boolean) {
        require(result.has(field) && nativeEffective.has(field) &&
            result.optBoolean(field, !expected) == expected &&
            nativeEffective.optBoolean(field, !expected) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactDouble(field: String, expected: Double) {
        require(nativeFloatMatches(result.optDouble(field, Double.NaN), expected) &&
            nativeFloatMatches(nativeEffective.optDouble(field, Double.NaN), expected)
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactSha256(field: String, expected: String? = null): String {
        val outer = result.optString(field)
        val inner = nativeEffective.optString(field)
        require(outer.matches(LOWERCASE_SHA256) && inner == outer &&
            (expected == null || outer == expected)
        ) { "Shared-QNN img2img $field evidence is missing, malformed, or inconsistent." }
        return outer
    }
    fun exactShape(field: String, expected: List<Int>) {
        fun JSONObject.shapeOrNull(): List<Int>? {
            val shape = optJSONArray(field) ?: return null
            if (shape.length() != expected.size) return null
            return (0 until shape.length()).map { index -> shape.optInt(index, Int.MIN_VALUE) }
        }
        require(result.shapeOrNull() == expected && nativeEffective.shapeOrNull() == expected) {
            "Shared-QNN img2img $field differs from the physical encoder tensor contract."
        }
    }

    exactString("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
    exactInt("batchCount", 1)
    exactInt("inputImageExecutionCount", 1)
    exactInt("maskImageExecutionCount", 0)
    exactInt("controlImageExecutionCount", 0)
    listOf("maskImagePath", "controlImagePath").forEach { field ->
        require(result.has(field) && nativeEffective.has(field) &&
            result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()
        ) { "Shared-QNN img2img reported an unexpected private $field slot." }
    }

    val expectedInput = requireNotNull(options.inputImage)
    val ultraFixPlan = ultraFixRequest?.let(::localQnnUltraFixTilePlanEvidence)
    val expectedInputTensorShape = ultraFixRequest?.let { request ->
        listOf(1, 3, request.targetHeight, request.targetWidth)
    } ?: SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphInputShape = SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphOutputShape = SHARED_QNN_ENCODER_OUTPUT_SHAPE
    val expectedEncoderExecutionCount = ultraFixPlan?.tileCount ?: 1L
    val expectedPosteriorSampleCount = ultraFixRequest?.let { request ->
        Math.multiplyExact(
            Math.multiplyExact(request.targetWidth.toLong() / 8L, request.targetHeight.toLong() / 8L),
            4L
        )
    } ?: SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT
    val expectedEncoderRuntimeMode = if (ultraFixRequest != null) {
        "standalone_tiled_encoder_then_shared_tiled_unet_vae"
    } else {
        SHARED_QNN_ENCODER_RUNTIME_MODE
    }
    val expectedSource = File(expectedInput.path).canonicalFile
    require(
        File(result.optString("inputImagePath")).canonicalFile == expectedSource &&
            File(nativeEffective.optString("inputImagePath")).canonicalFile == expectedSource
    ) { "Shared-QNN source-image preprocessing provenance differs from the worker input." }
    exactSha256("inputImageSha256", expectedInput.sha256)
    exactBoolean("inputImageSourceReadByNative", false)
    exactString("inputImageSourceValidation", "android_preprocess_provenance")

    val expectedTensor = File(prepared.tensorPath).canonicalFile
    require(
        File(result.optString("inputImageTensorPath")).canonicalFile == expectedTensor &&
            File(nativeEffective.optString("inputImageTensorPath")).canonicalFile == expectedTensor
    ) { "Shared-QNN encoder did not consume the exact committed Android preprocessing tensor." }
    exactSha256("inputImageTensorSha256", prepared.tensorSha256)
    exactString("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
    exactInt("inputImageSourceWidth", prepared.sourceWidth)
    exactInt("inputImageSourceHeight", prepared.sourceHeight)
    exactInt("inputImageOrientedWidth", prepared.orientedWidth)
    exactInt("inputImageOrientedHeight", prepared.orientedHeight)
    exactInt("inputImageExifOrientation", prepared.exifOrientation)
    exactInt("inputImageTensorWidth", prepared.tensorWidth)
    exactInt("inputImageTensorHeight", prepared.tensorHeight)
    exactInt("inputImageTensorChannels", 3)
    exactLong("inputImageTensorBytes", prepared.tensorBytes)
    exactShape("inputImageTensorShape", expectedInputTensorShape)
    exactString("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
    exactString("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
    exactString("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)

    exactSha256("encoderContextSha256", expectedVaeEncoderContextSha256)
    exactInt("encoderContextLoadCount", 1)
    exactInt("encoderExecutionCount", expectedEncoderExecutionCount.toInt())
    exactString("encoderGraphName", expectedVaeEncoderGraphName)
    exactString("encoderInputName", "input")
    exactString("encoderMeanOutputName", "mean")
    exactString("encoderStdOutputName", "std")
    exactString("encoderInputDtype", "uint16")
    exactString("encoderMeanDtype", "uint16")
    exactString("encoderStdDtype", "uint16")
    exactShape("encoderInputShape", expectedEncoderGraphInputShape)
    exactShape("encoderMeanShape", expectedEncoderGraphOutputShape)
    exactShape("encoderStdShape", expectedEncoderGraphOutputShape)
    val encoderInputBufferSha256 = exactSha256("encoderInputBufferSha256")
    val encoderMeanBufferSha256 = exactSha256("encoderMeanBufferSha256")
    val encoderStdBufferSha256 = exactSha256("encoderStdBufferSha256")
    val encoderLatentSha256 = exactSha256("encoderLatentSha256")
    exactString("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
    exactLong("posteriorSampleCount", expectedPosteriorSampleCount)
    exactDouble("encoderLatentScalingFactor", profile.vae.scalingFactor)
    exactBoolean("encoderContextReleasedBeforeSharedSession", true)
    exactString("encoderRuntimeMode", expectedEncoderRuntimeMode)
    exactString(
        "runtimeSessionMode",
        if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE) {
            "shared_text_unet_vae"
        } else {
            "shared_unet_vae"
        }
    )

    val steps = result.optInt("steps", -1)
    require(steps > 0 && nativeEffective.optInt("steps", -1) == steps) {
        "Shared-QNN img2img did not report the full scheduler step count."
    }
    val expectedStrength = options.strength ?: 1.0
    exactDouble("strength", expectedStrength)
    val fullTimetableCount = result.optInt("fullTimetableCount", -1)
    require(fullTimetableCount == steps &&
        nativeEffective.optInt("fullTimetableCount", -1) == fullTimetableCount
    ) { "Shared-QNN img2img full timetable evidence is invalid." }
    val schedule = resolveQnnImg2ImgSchedule(
        steps = steps,
        fullTimetableCount = fullTimetableCount,
        strength = expectedStrength
    )
    exactInt("timetableCount", schedule.effectiveSteps)
    exactInt("effectiveDenoiseSteps", schedule.effectiveSteps)
    exactInt("img2imgBeginIndex", schedule.beginIndex)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.has("useCfg") && nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "Shared-QNN img2img CFG evidence is inconsistent."
    }
    exactInt(
        "unetExecutionCount",
        Math.multiplyExact(schedule.effectiveSteps, if (useCfg) 2 else 1)
    )

    val tailTimesteps = result.optJSONArray("timesteps")
    require(tailTimesteps != null && tailTimesteps.length() == schedule.effectiveSteps) {
        "Shared-QNN img2img did not report the exact executed scheduler tail."
    }
    val addNoiseTimestep = tailTimesteps.optDouble(0, Double.NaN)
    require(addNoiseTimestep.isFinite()) {
        "Shared-QNN img2img first executed timestep is missing or non-finite."
    }
    val ultraFix = options.ultraFix != null
    if (ultraFix) {
        exactBoolean("img2imgAddNoiseApplied", false)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", 0.0)
    } else {
        exactBoolean("img2imgAddNoiseApplied", true)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", addNoiseTimestep)
    }
    val noiseChecksum = result.optString("img2imgNoiseChecksum")
    if (ultraFix) {
        require(noiseChecksum == "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) { "Shared-QNN UltraFix did not prove addNoise=false." }
    } else {
        require(noiseChecksum.matches(LOWERCASE_UINT64_HEX) &&
            noiseChecksum != "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) {
            "Shared-QNN img2img did not prove non-empty scheduler add-noise output."
        }
    }

    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", expectedVaeEncoderContextSha256)
        .put("encoderGraphName", expectedVaeEncoderGraphName)
        .put("encoderInputBufferSha256", encoderInputBufferSha256)
        .put("encoderMeanBufferSha256", encoderMeanBufferSha256)
        .put("encoderStdBufferSha256", encoderStdBufferSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("encoderRuntimeMode", expectedEncoderRuntimeMode)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
        .put("img2imgAddNoiseTimestep", if (ultraFix) 0.0 else addNoiseTimestep)
        .put("img2imgNoiseChecksum", noiseChecksum)
}

/**
 * QNN text-to-image reports three empty worker path slots as native evidence. Verify the slots
 * before removing them so a Local API response exposes neither private paths nor private path
 * field names. Missing or non-empty slots remain a native contract failure.
 */
internal fun verifyAndSanitizeQnnTextToImagePrivatePaths(
    result: JSONObject,
    options: LocalImageGenerationOptions
) {
    validateQnnNativeDetailStageMask(result)
    require(
        options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
            options.inputImage == null && options.maskImage == null && options.controlImage == null
    ) { "QNN text-to-image path sanitization requires a request without image inputs." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN text-to-image did not report nativeEffective input evidence."
        )
    require(
        result.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName &&
            nativeEffective.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName
    ) { "QNN text-to-image taskMode evidence differs from the request." }
    require(
        result.optInt("batchCount", -1) == options.batchCount &&
            nativeEffective.optInt("batchCount", -1) == options.batchCount
    ) { "QNN text-to-image batchCount evidence differs from the request." }
    listOf("inputImage", "maskImage", "controlImage").forEach { role ->
        val countField = "${role}ExecutionCount"
        val pathField = "${role}Path"
        require(
            result.optInt(countField, -1) == 0 &&
                nativeEffective.optInt(countField, -1) == 0
        ) { "QNN text-to-image reported an unexpected $countField." }
        require(
            result.has(pathField) && !result.isNull(pathField) &&
                nativeEffective.has(pathField) && !nativeEffective.isNull(pathField) &&
                result.getString(pathField).isBlank() &&
                nativeEffective.getString(pathField).isBlank()
        ) { "QNN text-to-image reported invalid $pathField evidence." }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }
}


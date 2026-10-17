package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.mca.localImageInputNativeFloatMatches as nativeFloatMatches

private const val SDCPP_STAGE_INPUT_IMAGE_DECODED = 128L
private const val SDCPP_STAGE_MASK_IMAGE_DECODED = 256L
private const val SDCPP_STAGE_CONTROL_IMAGE_DECODED = 512L
private const val SDCPP_STAGE_LORA_VALIDATED = 1_024L
private const val SDCPP_REQUIRED_SUCCESS_STAGES = 127L

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

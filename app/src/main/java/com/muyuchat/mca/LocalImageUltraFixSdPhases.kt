package com.muyuchat.mca

import org.json.JSONObject

    internal data class StageEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val tileInvocationCount: Long,
        val tileSuccessCount: Long,
        val stepCount: Long
    )

    internal data class VaeTilingPhaseEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val plannedTileCount: Long,
        val tileComputeAttemptCount: Long,
        val tileComputeSuccessCount: Long,
        val tileSizeX: Long,
        val tileSizeY: Long,
        val overlapX: Double,
        val overlapY: Double
    )

    internal data class VaeTilingEvidence(
        val enabled: Boolean,
        val requestedTileSize: Long,
        val requestedOverlap: Double,
        val encode: VaeTilingPhaseEvidence,
        val decode: VaeTilingPhaseEvidence
    )

    internal data class UltraFixEvidence(
        val version: Long,
        val generationCompleted: Boolean,
        val cancelled: Boolean,
        val previewPublished: Boolean,
        val sourceWidth: Long,
        val sourceHeight: Long,
        val targetWidth: Long,
        val targetHeight: Long,
        val sourceFit: String,
        val sourceResizedWidth: Long,
        val sourceResizedHeight: Long,
        val sourceCropLeft: Long,
        val sourceCropTop: Long,
        val tileSize: Long,
        val overlap: Double,
        val inversionSteps: Long,
        val refinementSteps: Long,
        val denoiseStepCount: Long,
        val sampleMethod: String,
        val nativeScheduler: String,
        val vaeEncode: StageEvidence,
        val ddimInversion: StageEvidence,
        val tiledUnetRefinement: StageEvidence,
        val tiledVaeDecode: StageEvidence,
        val physicalDiffusionModelComputeCount: Long,
        val qualityStepEvaluationCount: Long,
        val noiseInjectionStepCount: Long,
        val noiseInjectionSeedFingerprint: String,
        val noiseInjectionChecksum: String,
        val structureGuidanceStepCount: Long,
        val structureGuidanceChecksum: String,
        val trajectoryNoiseChecksum: String
    )

    internal data class SdUltraFixEvidenceContext(
        val outer: UltraFixEvidence,
        val outerVaeTiling: VaeTilingEvidence
    )

internal fun parseAndBindSdUltraFixEvidence(
    result: JSONObject,
    nativeEffective: JSONObject,
    request: LocalImageUltraFixOptions,
    source: LocalImagePreparedInput
): SdUltraFixEvidenceContext {
    val stageFields = setOf(
        "invocationCount",
        "successCount",
        "tileInvocationCount",
        "tileSuccessCount",
        "stepCount"
    )
    val evidenceFields = setOf(
        "version",
        "generationCompleted",
        "cancelled",
        "previewPublished",
        "sourceWidth",
        "sourceHeight",
        "targetWidth",
        "targetHeight",
        "sourceFit",
        "sourceResizedWidth",
        "sourceResizedHeight",
        "sourceCropLeft",
        "sourceCropTop",
        "tileSize",
        "overlap",
        "inversionSteps",
        "refinementSteps",
        "denoiseStepCount",
        "sampleMethod",
        "nativeScheduler",
        "vaeEncode",
        "ddimInversion",
        "tiledUnetRefinement",
        "tiledVaeDecode",
        "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount",
        "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint",
        "noiseInjectionChecksum",
        "structureGuidanceStepCount",
        "structureGuidanceChecksum",
        "trajectoryNoiseChecksum"
    )

    fun parseStage(parent: JSONObject, field: String, layer: String): StageEvidence {
        val stage = parent.optJSONObject(field) ?: error("$layer is missing $field.")
        stage.requireExactKeys(stageFields, "$layer.$field")
        return StageEvidence(
            invocationCount = stage.exactLong("invocationCount", "$layer.$field"),
            successCount = stage.exactLong("successCount", "$layer.$field"),
            tileInvocationCount = stage.exactLong("tileInvocationCount", "$layer.$field"),
            tileSuccessCount = stage.exactLong("tileSuccessCount", "$layer.$field"),
            stepCount = stage.exactLong("stepCount", "$layer.$field")
        )
    }

    fun parseEvidence(parent: JSONObject, layer: String): UltraFixEvidence {
        val evidence = parent.optJSONObject("ultraFix")
            ?: error("$layer UltraFix execution evidence is missing.")
        evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
        return UltraFixEvidence(
            version = evidence.exactLong("version", "$layer.ultraFix"),
            generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
            cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
            previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
            sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
            sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
            targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
            targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
            sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
            sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
            sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
            sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
            sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
            tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
            overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
            inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
            refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
            denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
            sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
            nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
            vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
            ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
            tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
            tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
            physicalDiffusionModelComputeCount = evidence.exactLong(
                "physicalDiffusionModelComputeCount",
                "$layer.ultraFix"
            ),
            qualityStepEvaluationCount = evidence.exactLong(
                "qualityStepEvaluationCount",
                "$layer.ultraFix"
            ),
            noiseInjectionStepCount = evidence.exactLong(
                "noiseInjectionStepCount",
                "$layer.ultraFix"
            ),
            noiseInjectionSeedFingerprint = evidence.exactString(
                "noiseInjectionSeedFingerprint",
                "$layer.ultraFix"
            ),
            noiseInjectionChecksum = evidence.exactString(
                "noiseInjectionChecksum",
                "$layer.ultraFix"
            ),
            structureGuidanceStepCount = evidence.exactLong(
                "structureGuidanceStepCount",
                "$layer.ultraFix"
            ),
            structureGuidanceChecksum = evidence.exactString(
                "structureGuidanceChecksum",
                "$layer.ultraFix"
            ),
            trajectoryNoiseChecksum = evidence.exactString(
                "trajectoryNoiseChecksum",
                "$layer.ultraFix"
            )
        )
    }

    fun parseVaeTilingPhase(
        parent: JSONObject,
        field: String,
        layer: String
    ): VaeTilingPhaseEvidence {
        val phase = parent.optJSONObject(field) ?: error("$layer is missing $field.")
        return VaeTilingPhaseEvidence(
            invocationCount = phase.exactLong("invocationCount", "$layer.$field"),
            successCount = phase.exactLong("successCount", "$layer.$field"),
            plannedTileCount = phase.exactLong("plannedTileCount", "$layer.$field"),
            tileComputeAttemptCount = phase.exactLong(
                "tileComputeAttemptCount",
                "$layer.$field"
            ),
            tileComputeSuccessCount = phase.exactLong(
                "tileComputeSuccessCount",
                "$layer.$field"
            ),
            tileSizeX = phase.exactLong("tileSizeX", "$layer.$field"),
            tileSizeY = phase.exactLong("tileSizeY", "$layer.$field"),
            overlapX = phase.exactDouble("overlapX", "$layer.$field"),
            overlapY = phase.exactDouble("overlapY", "$layer.$field")
        )
    }

    fun parseVaeTiling(parent: JSONObject, layer: String): VaeTilingEvidence {
        val tiling = parent.optJSONObject("vaeTiling")
            ?: error("$layer UltraFix VAE tiling evidence is missing.")
        return VaeTilingEvidence(
            enabled = tiling.exactBoolean("enabled", "$layer.vaeTiling"),
            requestedTileSize = tiling.exactLong("requestedTileSize", "$layer.vaeTiling"),
            requestedOverlap = tiling.exactDouble("requestedOverlap", "$layer.vaeTiling"),
            encode = parseVaeTilingPhase(tiling, "encode", "$layer.vaeTiling"),
            decode = parseVaeTilingPhase(tiling, "decode", "$layer.vaeTiling")
        )
    }

    val outer = parseEvidence(result, "outer")
    val inner = parseEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) { "Outer and nativeEffective UltraFix evidence conflict." }
    val outerVaeTiling = parseVaeTiling(result, "outer")
    val innerVaeTiling = parseVaeTiling(nativeEffective, "nativeEffective")
    require(outerVaeTiling == innerVaeTiling) {
        "Outer and nativeEffective UltraFix VAE tile plans conflict."
    }
    require(outerVaeTiling.enabled &&
        outerVaeTiling.requestedTileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outerVaeTiling.requestedOverlap - request.overlap) <= 1.0e-6
    ) { "UltraFix VAE tiling evidence does not match the immutable tile request." }
    val sourceWidth = source.orientedWidth.toLong()
    val sourceHeight = source.orientedHeight.toLong()
    val targetWidth = request.targetWidth.toLong()
    val targetHeight = request.targetHeight.toLong()
    val fitByWidth = Math.multiplyExact(targetWidth, sourceHeight) >=
        Math.multiplyExact(targetHeight, sourceWidth)
    val expectedResizedWidth: Long
    val expectedResizedHeight: Long
    if (fitByWidth) {
        expectedResizedWidth = targetWidth
        expectedResizedHeight = Math.multiplyExact(sourceHeight, targetWidth) / sourceWidth
    } else {
        expectedResizedWidth = Math.multiplyExact(sourceWidth, targetHeight) / sourceHeight
        expectedResizedHeight = targetHeight
    }
    require(expectedResizedWidth >= targetWidth && expectedResizedHeight >= targetHeight) {
        "UltraFix center-cover geometry does not cover the target canvas."
    }
    val expectedCropLeft = (expectedResizedWidth - targetWidth) / 2L
    val expectedCropTop = (expectedResizedHeight - targetHeight) / 2L
    require(result.exactString("strengthMechanism", "outer") == "ddim_inversion" &&
        nativeEffective.exactString("strengthMechanism", "nativeEffective") == "ddim_inversion"
    ) { "UltraFix did not prove its DDIM inversion strength mechanism." }
    require(outer.version == STABLE_DIFFUSION_ULTRAFIX_EVIDENCE_VERSION &&
        outer.generationCompleted && !outer.cancelled && !outer.previewPublished &&
        outer.sourceWidth == source.orientedWidth.toLong() &&
        outer.sourceHeight == source.orientedHeight.toLong() &&
        outer.targetWidth == request.targetWidth.toLong() &&
        outer.targetHeight == request.targetHeight.toLong() &&
        outer.sourceFit == "cover_center" &&
        outer.sourceResizedWidth == expectedResizedWidth &&
        outer.sourceResizedHeight == expectedResizedHeight &&
        outer.sourceCropLeft == expectedCropLeft &&
        outer.sourceCropTop == expectedCropTop &&
        outer.tileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outer.overlap - request.overlap) <= 1.0e-6 &&
        outer.inversionSteps == request.inversionSteps.toLong() &&
        outer.refinementSteps == request.refinementSteps.toLong() &&
        outer.denoiseStepCount == request.inversionSteps.toLong() &&
        outer.sampleMethod == result.exactString("sampleMethod", "outer") &&
        outer.nativeScheduler == result.exactString("nativeScheduler", "outer")
    ) { "Native UltraFix evidence does not match the immutable Android request." }
        return SdUltraFixEvidenceContext(outer, outerVaeTiling)
}

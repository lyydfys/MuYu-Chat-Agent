package com.muyuchat.mca

import java.security.MessageDigest
import org.json.JSONObject

internal fun verifyQnnUltraFixEvidenceBody(
    result: JSONObject,
    nativeEffective: JSONObject,
    request: LocalImageUltraFixOptions,
    inputImage: LocalImagePreparedInput?,
    requestedUseCfg: Boolean,
    outputBytes: ByteArray,
    outputMimeType: String
) {
    val source = requireNotNull(inputImage) { "QNN UltraFix evidence requires its prepared source." }

    fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
        require(keys().asSequence().toSet() == expected) {
            "$layer QNN UltraFix evidence fields do not match the strict v2 schema."
        }
    }

    fun JSONObject.exactLong(field: String, layer: String): Long {
        require(has(field) && !isNull(field)) { "$layer is missing $field." }
        val raw = get(field)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "$layer $field must be an exact integer."
        }
        return (raw as Number).toLong()
    }

    fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
        require(has(field) && !isNull(field) && get(field) is Boolean) {
            "$layer $field must be boolean."
        }
        return getBoolean(field)
    }

    fun JSONObject.exactString(field: String, layer: String): String {
        require(has(field) && !isNull(field) && get(field) is String) {
            "$layer $field must be a string."
        }
        return getString(field)
    }

    fun JSONObject.exactDouble(field: String, layer: String): Double {
        require(has(field) && !isNull(field) && get(field) is Number) {
            "$layer $field must be numeric."
        }
        return (get(field) as Number).toDouble().also { value ->
            require(value.isFinite()) { "$layer $field must be finite." }
        }
    }

    data class StageEvidence(
        val invocationCount: Long,
        val successCount: Long,
        val tileInvocationCount: Long,
        val tileSuccessCount: Long,
        val stepCount: Long
    )

    data class QnnEvidence(
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
        val tileCount: Long,
        val tilePlanSha256: String,
        val inversionSteps: Long,
        val refinementSteps: Long,
        val denoiseStepCount: Long,
        val sampleMethod: String,
        val nativeScheduler: String,
        val vaeEncode: StageEvidence,
        val ddimInversion: StageEvidence,
        val tiledUnetRefinement: StageEvidence,
        val tiledVaeDecode: StageEvidence,
        val encoderGraphExecutionCount: Long,
        val inversionPositiveGraphExecutionCount: Long,
        val refinementPositiveGraphExecutionCount: Long,
        val refinementNegativeGraphExecutionCount: Long,
        val decoderGraphExecutionCount: Long,
        val physicalDiffusionModelComputeCount: Long,
        val qualityStepEvaluationCount: Long,
        val noiseInjectionStepCount: Long,
        val noiseInjectionSeedFingerprint: String,
        val noiseInjectionChecksum: String,
        val structureGuidanceStepCount: Long,
        val structureGuidanceChecksum: String,
        val trajectoryNoiseChecksum: String,
        val outputSha256: String,
        val outputBytes: Long,
        val outputAtomicCommit: Boolean
    )

    val stageFields = setOf(
        "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
    )
    val evidenceFields = setOf(
        "version", "generationCompleted", "cancelled", "previewPublished",
        "sourceWidth", "sourceHeight", "targetWidth", "targetHeight", "sourceFit",
        "sourceResizedWidth", "sourceResizedHeight", "sourceCropLeft", "sourceCropTop",
        "tileSize", "overlap", "tileCount", "tilePlanSha256", "inversionSteps",
        "refinementSteps", "denoiseStepCount", "sampleMethod", "nativeScheduler",
        "vaeEncode", "ddimInversion", "tiledUnetRefinement", "tiledVaeDecode",
        "encoderGraphExecutionCount", "inversionPositiveGraphExecutionCount",
        "refinementPositiveGraphExecutionCount", "refinementNegativeGraphExecutionCount",
        "decoderGraphExecutionCount", "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount", "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint", "noiseInjectionChecksum",
        "structureGuidanceStepCount", "structureGuidanceChecksum", "trajectoryNoiseChecksum",
        "outputSha256", "outputBytes", "outputAtomicCommit"
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

    fun parseEvidence(parent: JSONObject, layer: String): QnnEvidence {
        val evidence = parent.optJSONObject("ultraFix")
            ?: error("$layer QNN UltraFix execution evidence is missing.")
        evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
        return QnnEvidence(
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
            tileCount = evidence.exactLong("tileCount", "$layer.ultraFix"),
            tilePlanSha256 = evidence.exactString("tilePlanSha256", "$layer.ultraFix"),
            inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
            refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
            denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
            sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
            nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
            vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
            ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
            tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
            tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
            encoderGraphExecutionCount = evidence.exactLong(
                "encoderGraphExecutionCount", "$layer.ultraFix"
            ),
            inversionPositiveGraphExecutionCount = evidence.exactLong(
                "inversionPositiveGraphExecutionCount", "$layer.ultraFix"
            ),
            refinementPositiveGraphExecutionCount = evidence.exactLong(
                "refinementPositiveGraphExecutionCount", "$layer.ultraFix"
            ),
            refinementNegativeGraphExecutionCount = evidence.exactLong(
                "refinementNegativeGraphExecutionCount", "$layer.ultraFix"
            ),
            decoderGraphExecutionCount = evidence.exactLong(
                "decoderGraphExecutionCount", "$layer.ultraFix"
            ),
            physicalDiffusionModelComputeCount = evidence.exactLong(
                "physicalDiffusionModelComputeCount", "$layer.ultraFix"
            ),
            qualityStepEvaluationCount = evidence.exactLong(
                "qualityStepEvaluationCount", "$layer.ultraFix"
            ),
            noiseInjectionStepCount = evidence.exactLong(
                "noiseInjectionStepCount", "$layer.ultraFix"
            ),
            noiseInjectionSeedFingerprint = evidence.exactString(
                "noiseInjectionSeedFingerprint", "$layer.ultraFix"
            ),
            noiseInjectionChecksum = evidence.exactString(
                "noiseInjectionChecksum", "$layer.ultraFix"
            ),
            structureGuidanceStepCount = evidence.exactLong(
                "structureGuidanceStepCount", "$layer.ultraFix"
            ),
            structureGuidanceChecksum = evidence.exactString(
                "structureGuidanceChecksum", "$layer.ultraFix"
            ),
            trajectoryNoiseChecksum = evidence.exactString(
                "trajectoryNoiseChecksum", "$layer.ultraFix"
            ),
            outputSha256 = evidence.exactString("outputSha256", "$layer.ultraFix"),
            outputBytes = evidence.exactLong("outputBytes", "$layer.ultraFix"),
            outputAtomicCommit = evidence.exactBoolean("outputAtomicCommit", "$layer.ultraFix")
        )
    }

    val outer = parseEvidence(result, "outer")
    val inner = parseEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) { "Outer and nativeEffective QNN UltraFix evidence conflict." }
    require(result.exactString("strengthMechanism", "outer") == "ddim_inversion" &&
        nativeEffective.exactString("strengthMechanism", "nativeEffective") == "ddim_inversion"
    ) { "QNN UltraFix did not prove DDIM inversion strength mechanism." }
    require(outer.version == QNN_ULTRAFIX_EVIDENCE_VERSION &&
        outer.generationCompleted && !outer.cancelled && !outer.previewPublished
    ) { "QNN UltraFix evidence is incomplete or has an unsupported version." }

    val plan = localQnnUltraFixTilePlanEvidence(request)
    val sourceWidth = source.orientedWidth.toLong()
    val sourceHeight = source.orientedHeight.toLong()
    val targetWidth = request.targetWidth.toLong()
    val targetHeight = request.targetHeight.toLong()
    require(sourceWidth > 0L && sourceHeight > 0L) {
        "QNN UltraFix source geometry must retain positive prepared dimensions."
    }
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
    require(outer.sourceWidth == sourceWidth && outer.sourceHeight == sourceHeight &&
        outer.targetWidth == targetWidth && outer.targetHeight == targetHeight &&
        outer.sourceFit == "cover_center" &&
        outer.sourceResizedWidth == expectedResizedWidth &&
        outer.sourceResizedHeight == expectedResizedHeight &&
        outer.sourceCropLeft == (expectedResizedWidth - targetWidth) / 2L &&
        outer.sourceCropTop == (expectedResizedHeight - targetHeight) / 2L &&
        outer.tileSize == request.tileSize.toLong() &&
        kotlin.math.abs(outer.overlap - request.overlap) <= 1.0e-6 &&
        outer.tileCount == plan.tileCount && outer.tilePlanSha256 == plan.tilePlanSha256 &&
        QNN_OUTPUT_SHA256.matches(outer.tilePlanSha256) &&
        outer.inversionSteps == request.inversionSteps.toLong() &&
        outer.refinementSteps == request.refinementSteps.toLong() &&
        outer.denoiseStepCount == request.inversionSteps.toLong()
    ) { "QNN UltraFix evidence does not match the immutable request or fixed tile plan." }
    listOf("sampleMethod", "nativeScheduler").forEach { field ->
        val value = outer.run { if (field == "sampleMethod") sampleMethod else nativeScheduler }
        if (result.has(field)) require(result.exactString(field, "outer") == value) {
            "QNN outer $field evidence conflicts with nested UltraFix evidence."
        }
        if (nativeEffective.has(field)) require(nativeEffective.exactString(field, "nativeEffective") == value) {
            "QNN nativeEffective $field evidence conflicts with nested UltraFix evidence."
        }
    }

    fun requireStage(name: String, stage: StageEvidence, invocations: Long, tiles: Long, steps: Long) {
        require(stage.invocationCount == invocations && stage.successCount == invocations &&
            stage.tileInvocationCount == tiles && stage.tileSuccessCount == tiles &&
            stage.stepCount == steps
        ) { "$name QNN UltraFix stage evidence is incomplete." }
    }
    val tail = request.inversionSteps.toLong()
    val branches = if (requestedUseCfg) 2L else 1L
    val inversionTiles = Math.multiplyExact(plan.tileCount, tail)
    val refinementPositive = inversionTiles
    val refinementNegative = if (requestedUseCfg) inversionTiles else 0L
    val refinementTiles = Math.addExact(refinementPositive, refinementNegative)
    val expectedPhysical = Math.addExact(inversionTiles, refinementTiles)
    requireStage("VAE encode", outer.vaeEncode, 1L, plan.tileCount, 1L)
    requireStage("DDIM inversion", outer.ddimInversion, tail, inversionTiles, tail)
    requireStage("tiled UNet refinement", outer.tiledUnetRefinement, tail, refinementTiles, tail)
    requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, plan.tileCount, 1L)
    require(outer.encoderGraphExecutionCount == plan.tileCount &&
        outer.inversionPositiveGraphExecutionCount == inversionTiles &&
        outer.refinementPositiveGraphExecutionCount == refinementPositive &&
        outer.refinementNegativeGraphExecutionCount == refinementNegative &&
        outer.decoderGraphExecutionCount == plan.tileCount &&
        outer.physicalDiffusionModelComputeCount == expectedPhysical &&
        nativeEffective.exactBoolean("useCfg", "nativeEffective") == requestedUseCfg &&
        result.exactBoolean("useCfg", "outer") == requestedUseCfg &&
        nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") ==
            Math.addExact(inversionTiles, refinementPositive) &&
        nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") ==
            refinementNegative &&
        nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") == 0L &&
        nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
        nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") == expectedPhysical
    ) { "QNN UltraFix logical and physical graph counts are inconsistent." }
    if (result.has("actualDiffusionModelComputeCount")) {
        require(result.exactLong("actualDiffusionModelComputeCount", "outer") == expectedPhysical &&
            result.exactLong("actualPositiveDiffusionModelComputeCount", "outer") ==
                inversionTiles + refinementPositive &&
            result.exactLong("actualNegativeDiffusionModelComputeCount", "outer") == refinementNegative &&
            result.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") == 0L
        ) { "QNN UltraFix outer physical counters are inconsistent." }
    }

    val executedSeed = result.exactLong("seed", "outer")
    val zeroQualityChecksum = "0000000000000000"
    val noiseEvidenceConsistent =
        (outer.noiseInjectionStepCount == 0L) ==
            (outer.noiseInjectionChecksum == zeroQualityChecksum)
    val structureEvidenceConsistent =
        (outer.structureGuidanceStepCount == 0L) ==
            (outer.structureGuidanceChecksum == zeroQualityChecksum)
    val qualityActionCount = runCatching {
        Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
    }.getOrDefault(-1L)
    require(executedSeed in 0L..0xffff_ffffL &&
        nativeEffective.exactLong("seed", "nativeEffective") == executedSeed &&
        outer.qualityStepEvaluationCount == Math.max(tail - 1L, 0L) &&
        outer.noiseInjectionStepCount in 0L..outer.qualityStepEvaluationCount &&
        outer.structureGuidanceStepCount in 0L..outer.qualityStepEvaluationCount &&
        outer.noiseInjectionSeedFingerprint ==
            localImageUltraFixNoiseSeedFingerprint(executedSeed, request.inversionSteps) &&
        QNN_OUTPUT_SHA256.matches(outer.noiseInjectionSeedFingerprint) &&
        LOWERCASE_UINT64_HEX.matches(outer.noiseInjectionChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.structureGuidanceChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.trajectoryNoiseChecksum) &&
        if (tail == 1L) {
            outer.noiseInjectionStepCount == 0L && outer.structureGuidanceStepCount == 0L &&
                outer.noiseInjectionChecksum == zeroQualityChecksum &&
                outer.structureGuidanceChecksum == zeroQualityChecksum &&
                outer.trajectoryNoiseChecksum == zeroQualityChecksum
        } else {
            noiseEvidenceConsistent && structureEvidenceConsistent &&
                qualityActionCount >= outer.qualityStepEvaluationCount &&
                outer.trajectoryNoiseChecksum != zeroQualityChecksum
        }
    ) { "QNN UltraFix quality, seed, or checksum evidence is invalid." }

    listOf(result, nativeEffective).forEach { layer ->
        require(layer.exactBoolean("img2imgAddNoiseApplied", "QNN UltraFix") == false &&
            layer.exactString("img2imgNoiseChecksum", "QNN UltraFix") == "0000000000000000"
        ) { "QNN UltraFix must prove addNoise=false." }
    }

    data class CommitEvidence(val sha256: String, val sizeBytes: Long, val atomic: Boolean)
    fun parseCommit(parent: JSONObject, layer: String): CommitEvidence {
        val sha256 = parent.exactString("outputSha256", layer)
        require(QNN_OUTPUT_SHA256.matches(sha256)) { "$layer output SHA-256 is malformed." }
        return CommitEvidence(
            sha256 = sha256,
            sizeBytes = parent.exactLong("outputSizeBytes", layer),
            atomic = parent.exactBoolean("outputAtomicCommit", layer)
        )
    }
    val outerCommit = parseCommit(result, "outer")
    val innerCommit = parseCommit(nativeEffective, "nativeEffective")
    val nestedCommit = CommitEvidence(
        sha256 = outer.outputSha256,
        sizeBytes = outer.outputBytes,
        atomic = outer.outputAtomicCommit
    )
    require(outerCommit == innerCommit && outerCommit == nestedCommit && outerCommit.atomic &&
        outerCommit.sizeBytes in MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES &&
        outputMimeType == "image/png" && outputBytes.size.toLong() == outerCommit.sizeBytes
    ) { "QNN UltraFix output commit evidence is incomplete or conflicts across layers." }
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(outputBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    require(actualSha256 == outerCommit.sha256) {
        "QNN UltraFix output evidence does not bind the exact returned PNG bytes."
    }
}

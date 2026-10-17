package com.muyuchat.mca

import java.security.MessageDigest
import org.json.JSONObject

internal fun verifySdUltraFixStagesAndCommit(
    result: JSONObject,
    nativeEffective: JSONObject,
    request: LocalImageUltraFixOptions,
    requestedUseCfg: Boolean,
    outputs: List<LocalImageOutput>,
    outer: UltraFixEvidence,
    outerVaeTiling: VaeTilingEvidence
) {

    fun requireStage(
        name: String,
        stage: StageEvidence,
        invocations: Long,
        steps: Long,
        requireTiles: Boolean
    ) {
        require(stage.invocationCount == invocations && stage.successCount == invocations &&
            stage.stepCount == steps && stage.tileInvocationCount == stage.tileSuccessCount &&
            (!requireTiles || stage.tileInvocationCount > 0L)
        ) { "$name UltraFix stage evidence is incomplete." }
    }

    requireStage("VAE encode", outer.vaeEncode, 1L, 1L, requireTiles = true)
    requireStage(
        "DDIM inversion",
        outer.ddimInversion,
        request.inversionSteps.toLong(),
        request.inversionSteps.toLong(),
        requireTiles = true
    )
    requireStage(
        "tiled UNet refinement",
        outer.tiledUnetRefinement,
        request.inversionSteps.toLong(),
        request.inversionSteps.toLong(),
        requireTiles = true
    )
    requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, 1L, requireTiles = true)

    fun requireVaePhasePlan(
        name: String,
        stage: StageEvidence,
        phase: VaeTilingPhaseEvidence
    ) {
        require(phase.invocationCount == 1L && phase.successCount == 1L &&
            phase.plannedTileCount > 0L &&
            phase.tileComputeAttemptCount == phase.plannedTileCount &&
            phase.tileComputeSuccessCount == phase.tileComputeAttemptCount &&
            phase.tileSizeX > 0L && phase.tileSizeY > 0L &&
            phase.overlapX in 0.0..0.5 && phase.overlapY in 0.0..0.5 &&
            stage.invocationCount == phase.invocationCount &&
            stage.successCount == phase.successCount &&
            stage.tileInvocationCount == phase.plannedTileCount &&
            stage.tileInvocationCount == phase.tileComputeAttemptCount &&
            stage.tileSuccessCount == phase.tileComputeSuccessCount
        ) { "$name UltraFix evidence does not match its physical VAE tile plan." }
    }

    fun requireRepeatedTilePlan(
        name: String,
        stage: StageEvidence,
        completedSteps: Long
    ): Long {
        require(completedSteps > 0L &&
            stage.invocationCount == completedSteps &&
            stage.successCount == completedSteps &&
            stage.stepCount == completedSteps &&
            stage.tileInvocationCount > 0L &&
            stage.tileInvocationCount == stage.tileSuccessCount &&
            stage.tileInvocationCount % completedSteps == 0L
        ) { "$name UltraFix evidence does not contain a complete per-step tile plan." }
        val tilesPerStep = stage.tileInvocationCount / completedSteps
        require(tilesPerStep > 0L &&
            Math.multiplyExact(tilesPerStep, completedSteps) == stage.tileInvocationCount
        ) { "$name UltraFix tile plan is invalid." }
        return tilesPerStep
    }

    requireVaePhasePlan("VAE encode", outer.vaeEncode, outerVaeTiling.encode)
    requireVaePhasePlan("tiled VAE decode", outer.tiledVaeDecode, outerVaeTiling.decode)
    val inversionTilesPerStep = requireRepeatedTilePlan(
        "DDIM inversion",
        outer.ddimInversion,
        request.inversionSteps.toLong()
    )
    val refinementTilesPerStep = requireRepeatedTilePlan(
        "tiled UNet refinement",
        outer.tiledUnetRefinement,
        request.inversionSteps.toLong()
    )
    require(inversionTilesPerStep == refinementTilesPerStep) {
        "DDIM inversion and UNet refinement did not use one latent tile plan."
    }

    val expectedPositive = Math.addExact(
        outer.ddimInversion.tileInvocationCount,
        outer.tiledUnetRefinement.tileInvocationCount
    )
    val expectedNegative = if (requestedUseCfg) {
        outer.tiledUnetRefinement.tileInvocationCount
    } else {
        0L
    }
    val expectedPhysical = Math.addExact(expectedPositive, expectedNegative)
    require(outer.physicalDiffusionModelComputeCount == expectedPhysical &&
        result.exactLong("actualDiffusionModelComputeCount", "outer") == expectedPhysical &&
        result.exactLong("actualPositiveDiffusionModelComputeCount", "outer") == expectedPositive &&
        result.exactLong("actualNegativeDiffusionModelComputeCount", "outer") == expectedNegative &&
        result.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") == 0L &&
        result.exactLong("actualSamplingStepCount", "outer") == request.inversionSteps.toLong() &&
        result.exactLong("actualSamplingPassCount", "outer") == 1L &&
        result.exactLong("totalUnetExecutionCount", "outer") == expectedPhysical &&
        nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") ==
            expectedPositive &&
        nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") ==
            expectedNegative &&
        nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") == 0L &&
        nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
        nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") == expectedPhysical
    ) { "UltraFix physical diffusion evidence is incomplete or misclassified." }

    val qualityStepCount = Math.max(outer.inversionSteps - 1L, 0L)
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
    require(executedSeed >= 0L &&
        nativeEffective.exactLong("seed", "nativeEffective") == executedSeed
    ) { "UltraFix quality evidence is missing the resolved non-negative seed." }
    require(outer.qualityStepEvaluationCount == qualityStepCount &&
        outer.noiseInjectionStepCount in 0L..qualityStepCount &&
        outer.structureGuidanceStepCount in 0L..qualityStepCount &&
        outer.noiseInjectionSeedFingerprint ==
            localImageUltraFixNoiseSeedFingerprint(executedSeed, outer.inversionSteps.toInt()) &&
        QNN_OUTPUT_SHA256.matches(outer.noiseInjectionSeedFingerprint) &&
        LOWERCASE_UINT64_HEX.matches(outer.noiseInjectionChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.structureGuidanceChecksum) &&
        LOWERCASE_UINT64_HEX.matches(outer.trajectoryNoiseChecksum) &&
        if (qualityStepCount == 0L) {
            outer.noiseInjectionStepCount == 0L &&
                outer.structureGuidanceStepCount == 0L &&
                outer.noiseInjectionChecksum == zeroQualityChecksum &&
                outer.structureGuidanceChecksum == zeroQualityChecksum &&
                outer.trajectoryNoiseChecksum == zeroQualityChecksum
        } else {
            noiseEvidenceConsistent && structureEvidenceConsistent &&
                qualityActionCount >= qualityStepCount &&
                outer.trajectoryNoiseChecksum != zeroQualityChecksum
        }
    ) { "UltraFix quality, seed, and checksum evidence is incomplete." }

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
    require(outerCommit == innerCommit && outerCommit.atomic &&
        outerCommit.sizeBytes in
            MIN_STABLE_DIFFUSION_OUTPUT_PNG_BYTES..MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES
    ) { "UltraFix output commit evidence is incomplete or conflicts across layers." }
    val output = outputs.single()
    val actualSha256 = MessageDigest.getInstance("SHA-256")
        .digest(output.bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    require(output.mimeType == "image/png" &&
        output.bytes.size.toLong() == outerCommit.sizeBytes &&
        actualSha256 == outerCommit.sha256
    ) { "UltraFix output evidence does not bind the exact returned PNG bytes." }
}

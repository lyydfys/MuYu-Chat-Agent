package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

internal typealias QnnInpaintSchedule = QnnImg2ImgSchedule

internal fun resolveQnnInpaintSchedule(
    steps: Int,
    fullTimetableCount: Int,
    strength: Double,
): QnnInpaintSchedule = resolveQnnImg2ImgSchedule(
    steps = steps,
    fullTimetableCount = fullTimetableCount,
    strength = strength,
)

internal fun ImageExecutionProfile.supportedQnnInpaintSchedulers(): Set<ImageSchedulerAlgorithm> =
    if (hasExecutableQnnInpaintTopology()) {
        capabilities.supportedSchedulers - ImageSchedulerAlgorithm.PNDM_PLMS
    } else {
        emptySet()
    }

internal fun ImageExecutionProfileResolution.withQnnInpaintProductSchedule(
    options: LocalImageGenerationOptions,
): ImageExecutionProfileResolution {
    if (profile.runtime != LocalImageRuntime.QNN_HTP ||
        options.taskMode != LocalImageTaskMode.INPAINT
    ) {
        return this
    }
    require(profile.hasExecutableQnnInpaintTopology()) {
        "Resolved QNN inpaint requires a shared VAE encoder, executable four-channel or mask-conditioned UNet, and VAE decoder."
    }
    require(profile.scheduler.algorithm in profile.supportedQnnInpaintSchedulers()) {
        "QNN inpaint cannot enter the requested scheduler at a strength-derived begin index."
    }
    val resolved = layers.resolved
    val schedule = resolveQnnInpaintSchedule(
        steps = resolved.steps,
        fullTimetableCount = resolved.timetableCount,
        strength = options.strength ?: 1.0,
    )
    val branches = if (resolved.useCfg) 2 else 1
    return copy(
        layers = layers.copy(
            resolved = resolved.copy(
                timetableCount = schedule.effectiveSteps,
                unetExecutionCount = Math.multiplyExact(schedule.effectiveSteps, branches),
            ),
        ),
    )
}

internal fun qnnInpaintNativeFloatMatches(first: Double?, second: Double): Boolean {
    if (first == null || !first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    return kotlin.math.abs(first - second) <= 1e-6 * scale
}

internal val QNN_INPAINT_ENCODER_GRAPH_EXECUTE_STAGE = 1uL shl 63
internal val QNN_INPAINT_SHA256 = Regex("^[a-f0-9]{64}$")
internal val QNN_INPAINT_UINT64_HEX = Regex("^[a-f0-9]{16}$")
internal const val QNN_INPAINT_ZERO_UINT64_HEX = "0000000000000000"

package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicInteger

/**
 * T2B22-T1（b 工位）拆片：`SdxlPhaseProcessLifetime`、
 * `LocalImageProgress.forSdxlPhase`（private→internal）与 `sdxlPhaseProgressStep`
 * 自 SdxlImagePhaseWorkerService.kt 逐字节平移；仅 1 处声明行登记改写。
 */

internal class SdxlPhaseProcessLifetime {
    private val state = AtomicInteger(STATE_ACCEPTING)

    @Volatile
    private var phaseOwnership: SdxlRequestFileLock? = null

    val acceptsRequests: Boolean
        get() = state.get() == STATE_ACCEPTING

    val isRetiring: Boolean
        get() = state.get() == STATE_RETIRING

    val hasRetainedPhaseOwnership: Boolean
        get() = phaseOwnership != null

    fun tryClaimRequest(): Boolean = state.compareAndSet(STATE_ACCEPTING, STATE_CLAIMED)

    fun beginRetirement(): Boolean = state.getAndSet(STATE_RETIRING) != STATE_RETIRING

    fun retainPhaseOwnershipUntilProcessDeath(ownership: SdxlRequestFileLock) {
        synchronized(this) {
            check(state.get() != STATE_ACCEPTING) {
                "SDXL phase ownership requires a claimed process."
            }
            check(phaseOwnership == null) {
                "SDXL phase process already owns a request lease."
            }
            // Deliberately never close this lock. The isolated phase process is disposable, and
            // its death is the only boundary that may release recovery/admission for this request.
            phaseOwnership = ownership
        }
    }

    private companion object {
        const val STATE_ACCEPTING = 0
        const val STATE_CLAIMED = 1
        const val STATE_RETIRING = 2
    }
}

internal fun LocalImageProgress.forSdxlPhase(
    phase: SdxlImagePhase,
    contract: SdxlImageExecutionContract
): LocalImageProgress {
    requireSdxlPreviewDisabled("Split-SDXL phase journal progress")
    val params = contract.paramsObject()
    val taskMode = LocalImageTaskMode.fromWireName(params.getString("taskMode"))
    val ultraFix = contract.ultraFixRequestOrNull() != null
    val mappedStep = sdxlPhaseProgressStep(
        phase = phase,
        taskMode = taskMode,
        fullSteps = contract.steps,
        beginIndex = params.optInt("img2imgBeginIndex", 0),
        effectiveSteps = params.optInt("effectiveDenoiseSteps", contract.steps),
        physicalStep = step,
        ultraFix = ultraFix
    )
    return copy(
        step = mappedStep,
        steps = contract.steps,
        width = contract.width,
        height = contract.height
    ).withSdxlPreviewDisabled()
}

internal fun sdxlPhaseProgressStep(
    phase: SdxlImagePhase,
    taskMode: LocalImageTaskMode,
    fullSteps: Int,
    beginIndex: Int,
    effectiveSteps: Int,
    physicalStep: Int,
    ultraFix: Boolean = false
): Int {
    require(fullSteps > 0) { "SDXL progress requires positive full steps." }
    if (phase == SdxlImagePhase.VAE) return fullSteps
    if (phase == SdxlImagePhase.UNET && ultraFix) {
        require(taskMode == LocalImageTaskMode.IMG2IMG && effectiveSteps > 0) {
            "Split-SDXL UltraFix progress requires an encoder-backed img2img tail."
        }
        val physicalTotal = Math.multiplyExact(effectiveSteps, 2)
        return ((physicalStep.coerceIn(0, physicalTotal).toLong() * fullSteps) /
            physicalTotal).toInt().coerceIn(0, fullSteps)
    }
    if (phase != SdxlImagePhase.UNET ||
        taskMode !in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)
    ) {
        return physicalStep.coerceIn(0, fullSteps)
    }
    require(beginIndex in 0 until fullSteps && effectiveSteps == fullSteps - beginIndex) {
        "SDXL img2img progress schedule is inconsistent."
    }
    return (beginIndex + physicalStep.coerceIn(0, effectiveSteps)).coerceAtMost(fullSteps)
}

package com.muyuchat.mca


/**
 * T2B22-T1（b 工位）拆片：`SdxlNativePhaseFailure`（private→internal）与
 * `sdxlRuntimeCandidateFailureCode` / `isSdxlRuntimeCandidateFailureCode` 自
 * SdxlImagePhaseWorkerService.kt 逐字节平移；仅 1 处声明行登记改写。
 */

internal class SdxlNativePhaseFailure(
    val executionStage: String,
    message: String
) : IllegalStateException(message)

internal fun sdxlRuntimeCandidateFailureCode(
    phase: SdxlImagePhase,
    executionStage: String
): String? {
    val retryable = when (phase) {
        SdxlImagePhase.ENCODER -> executionStage in setOf(
            "runtime_unavailable",
            "sdxl_encoder_load_failed",
            "encoder_profile_mismatch"
        )
        SdxlImagePhase.UNET -> executionStage in setOf(
            "runtime_unavailable",
            "unet_load_failed",
            "unet_profile_mismatch"
        )
        SdxlImagePhase.VAE -> executionStage in setOf(
            "runtime_unavailable",
            "vae_load_failed",
            "vae_profile_mismatch"
        )
    }
    return if (retryable) "sdxl_${phase.wireName}_runtime_candidate_failed" else null
}

internal fun isSdxlRuntimeCandidateFailureCode(
    phase: SdxlImagePhase,
    code: String
): Boolean = code == "sdxl_${phase.wireName}_runtime_candidate_failed"

package com.muyuchat.core.engine

import kotlinx.coroutines.Job

/**
 * Admission decision for the engine's single native generation stop target.
 *
 * The engine allows exactly one stream to own the stop target, and a stopped stream is expected to
 * release it in its own `finally`. A stream whose collector has ended can never run that block
 * again, so its token would stay set forever and every later send would be rejected with
 * "another native generation already owns the stop target" -- the F3 block reproduced on device
 * (3/3, still failing five minutes after a successful stop).
 *
 * Reclaiming is therefore allowed only with proof that the owning stream is dead: its collector job
 * is no longer active. A live collector is still refused, and an unproven owner (no job recorded) is
 * refused too, so two genuinely concurrent generations keep being rejected.
 */
internal data class GenerationStopTargetAdmission(
    val reclaimTarget: Boolean,
    val clearStopRequestedToken: Boolean,
    val rejection: String?
) {
    val rejected: Boolean get() = rejection != null
}

internal fun decideGenerationStopTargetAdmission(
    hasStopTarget: Boolean,
    stopTargetOwner: Job?,
    stopRequestedTokenPresent: Boolean,
    stopRequestedTokenBelongsToTarget: Boolean
): GenerationStopTargetAdmission {
    if (!hasStopTarget) {
        return if (stopRequestedTokenPresent) {
            GenerationStopTargetAdmission(
                reclaimTarget = false,
                clearStopRequestedToken = false,
                rejection = UNWINDING_STOP_REJECTION
            )
        } else {
            GenerationStopTargetAdmission(reclaimTarget = false, clearStopRequestedToken = false, rejection = null)
        }
    }
    val ownerAlive = stopTargetOwner != null && stopTargetOwner.isActive
    if (ownerAlive || stopTargetOwner == null) {
        // A live owner still holds the single-generation guarantee; an unproven owner must not be
        // reclaimed either, because admitting a second concurrent stream is the worse failure.
        return GenerationStopTargetAdmission(reclaimTarget = false, clearStopRequestedToken = false, rejection = OWNED_TARGET_REJECTION)
    }
    if (stopRequestedTokenPresent && !stopRequestedTokenBelongsToTarget) {
        // A stop for a different stream is genuinely in flight: never drop it.
        return GenerationStopTargetAdmission(reclaimTarget = false, clearStopRequestedToken = false, rejection = UNWINDING_STOP_REJECTION)
    }
    return GenerationStopTargetAdmission(reclaimTarget = true, clearStopRequestedToken = true, rejection = null)
}

internal const val OWNED_TARGET_REJECTION = "another native generation already owns the stop target"
internal const val UNWINDING_STOP_REJECTION = "the previous native generation is still unwinding its stop request"

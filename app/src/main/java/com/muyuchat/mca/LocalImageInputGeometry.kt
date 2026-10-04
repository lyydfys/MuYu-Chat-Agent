package com.muyuchat.mca

import java.security.MessageDigest

/**
 * Oriented dimensions, denoising tail length and the canonical UltraFix noise fingerprint.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim.
 */

internal fun localImageOrientedDimensions(
    encodedWidth: Int,
    encodedHeight: Int,
    exifOrientation: Int
): Pair<Int, Int> {
    require(encodedWidth > 0 && encodedHeight > 0)
    require(exifOrientation in 1..8)
    return if (exifOrientation in setOf(5, 6, 7, 8)) {
        encodedHeight to encodedWidth
    } else {
        encodedWidth to encodedHeight
    }
}

/** Mirrors the Float strength crossing JNI so every layer selects the same denoising tail. */
internal fun localImageDenoisingTailStepCount(totalSteps: Int, strength: Double): Int {
    require(totalSteps > 0) { "Denoising step count must be positive." }
    val wireStrength = strength.toFloat()
    require(wireStrength.isFinite() && wireStrength in 0.0f..1.0f) {
        "Denoising strength must be finite and in [0, 1]."
    }
    val beginIndex = (totalSteps.toFloat() * (1.0f - wireStrength))
        .toInt()
        .coerceIn(0, totalSteps - 1)
    return totalSteps - beginIndex
}

/** Canonical quality-noise seed binding shared by the native stable and QNN bridges. */
internal fun localImageUltraFixNoiseSeedFingerprint(
    seed: Long,
    denoiseStepCount: Int
): String {
    require(denoiseStepCount > 0) { "UltraFix denoising tail must be positive." }
    val descriptor = "mca-ultrafix-quality-noise-v1|seed=${seed.toULong()}|steps=$denoiseStepCount"
    return MessageDigest.getInstance("SHA-256")
        .digest(descriptor.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

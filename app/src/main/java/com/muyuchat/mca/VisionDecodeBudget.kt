package com.muyuchat.mca

/**
 * Decode budget for images that are sent to a vision model.
 *
 * These are the pure limits that decide how far a user image is downsampled before it
 * reaches `BitmapFactory`: the largest edge worth keeping, and the total pixel budget that
 * keeps a malformed or very large image from exhausting the process before the isolated
 * native runner can reject it.
 *
 * The policy is deliberately free of Android types so it can be tested on the JVM and moved
 * to a runtime/domain module later. The Android-facing `BitmapFactory.Options` construction
 * stays with the platform code that uses it.
 *
 * Extracted verbatim from `MainViewModel` during P2; no value or branch was changed.
 */
internal object VisionDecodeBudget {
    /** Longest edge retained for a vision input. */
    const val MAX_EDGE = 1280

    /** Total decoded pixels allowed for a vision input. */
    const val MAX_DECODE_PIXELS = 4_000_000L

    /** Largest power of two the sampler will reach, guarding the doubling loop. */
    private const val MAX_SAMPLE = 1 shl 29

    /**
     * Largest power-of-two sample size whose result stays within both limits.
     *
     * A non-positive dimension is treated as unsamplable and returns 1. The edge test is
     * strict (`>` rather than `>=`), so an image exactly at `maxEdge * 2` is decoded at
     * full size. Both limits are evaluated on the *sampled* dimensions, and the loop stops
     * at [MAX_SAMPLE] so the doubling cannot overflow.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (
            maxOf(width / sample, height / sample) > maxEdge * 2 ||
            (width.toLong() / sample.toLong()) * (height.toLong() / sample.toLong()) > MAX_DECODE_PIXELS
        ) {
            if (sample >= MAX_SAMPLE) break
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}

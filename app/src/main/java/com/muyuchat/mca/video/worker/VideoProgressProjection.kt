package com.muyuchat.mca.video.worker

/**
 * Projects the single stable-diffusion.cpp progress channel onto real sampler steps.
 *
 * sd.cpp reports text-encoder, conditioning and sampler progress through the same callback, and
 * the native video bridge labels every callback `"sampling"`. Reporting those values verbatim made
 * a new run show the previous/encoder counter (for example `825/1263`) and claim SAMPLE before its
 * own sampler started. Only callbacks whose total equals the frozen sampler step count are real
 * sampler progress; everything else stays out of the run projection.
 */
internal object VideoProgressProjection {

    fun samplerStep(phase: String?, step: Long, total: Long, requestedSteps: Int): Long? {
        if (phase != "sampling") return null
        if (requestedSteps <= 0) return null
        val expected = requestedSteps.toLong()
        if (total != expected) return null
        if (step < 0L || step > expected) return null
        return step
    }
}

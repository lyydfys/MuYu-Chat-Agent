package com.muyuchat.core.mediatek

import kotlin.math.abs
import kotlin.math.max

/** Provenance of one ordered NPUG route slot inside a chunk pass, before the step is known. */
sealed interface MediaTekNpugSlotSpec {
    /** First chunk only: patchified latent tokens. */
    data object PatchTokens : MediaTekNpugSlotSpec

    /** Later chunks: the previous chunk's hidden state at the same tensor scale. */
    data object HiddenState : MediaTekNpugSlotSpec

    data object FixedContext : MediaTekNpugSlotSpec

    data object ZeroContext : MediaTekNpugSlotSpec

    data class Modulation(val block: Int, val segment: Int) : MediaTekNpugSlotSpec

    data class FinalModulation(val segment: Int) : MediaTekNpugSlotSpec

    data class RopeHalf(val half: Int) : MediaTekNpugSlotSpec
}

/** One route slot for a concrete sampling step. */
sealed interface MediaTekNpugSlot {
    data object PatchTokens : MediaTekNpugSlot

    data object HiddenState : MediaTekNpugSlot

    data object FixedContext : MediaTekNpugSlot

    data object ZeroContext : MediaTekNpugSlot

    data class Modulation(val step: Int, val block: Int, val segment: Int) : MediaTekNpugSlot

    data class FinalModulation(val step: Int, val segment: Int) : MediaTekNpugSlot

    data class RopeHalf(val half: Int) : MediaTekNpugSlot
}

/**
 * One DiT chunk of the reference four-graph chain. The chain runs
 * `dva1 -> dva2 -> dva3 -> ditc1c` once per sampling step; the last chunk adds
 * the two final modulation segments.
 */
data class MediaTekNpugChunkPlan(
    val variantId: String,
    val graphId: String,
    val blockStart: Int,
    val blockCount: Int,
    val inputCount: Int,
    val inputScale: Double,
    val outputElementCount: Long,
    val outputScale: Double,
    val finalChunk: Boolean,
    val slots: List<MediaTekNpugSlotSpec>,
) {
    fun slotsForStep(step: Int): List<MediaTekNpugSlot> = slots.map { spec ->
        when (spec) {
            MediaTekNpugSlotSpec.PatchTokens -> MediaTekNpugSlot.PatchTokens
            MediaTekNpugSlotSpec.HiddenState -> MediaTekNpugSlot.HiddenState
            MediaTekNpugSlotSpec.FixedContext -> MediaTekNpugSlot.FixedContext
            MediaTekNpugSlotSpec.ZeroContext -> MediaTekNpugSlot.ZeroContext
            is MediaTekNpugSlotSpec.Modulation ->
                MediaTekNpugSlot.Modulation(step, spec.block, spec.segment)
            is MediaTekNpugSlotSpec.FinalModulation ->
                MediaTekNpugSlot.FinalModulation(step, spec.segment)
            is MediaTekNpugSlotSpec.RopeHalf -> MediaTekNpugSlot.RopeHalf(spec.half)
        }
    }
}

/**
 * Structural plan for one reference-style NPUG/AOT sampling loop. It binds the
 * manifest's ordered execution variants to their routes, the shared AMOD
 * modulation file, the recovered sigma schedule and the VAE input contract.
 *
 * The plan is deliberately device-free: it is the part of the chain that can be
 * validated without a Neuron runtime. Actual restore/bind/run remains in
 * [MediaTekNeuronRuntimeAdapter].
 */
data class MediaTekNpugChainPlan(
    val profileId: String,
    val tokenCount: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val latentChannels: Int,
    val steps: Int,
    val sigmas: List<Double>,
    val chunks: List<MediaTekNpugChunkPlan>,
    val modulation: MediaTekBundleAssetContract,
    val vaeInputScale: Double,
    val vaeInputZeroPoint: Int,
    val vaeOutputScale: Double,
    val vaeInputElementCount: Long,
) {
    val height: Int get() = gridHeight * PATCH_SIZE
    val width: Int get() = gridWidth * PATCH_SIZE
    val imageWidth: Int get() = gridWidth * MediaTekAotProfile.PIXELS_PER_GRID
    val imageHeight: Int get() = gridHeight * MediaTekAotProfile.PIXELS_PER_GRID

    fun chunk(index: Int): MediaTekNpugChunkPlan = chunks[index]

    /** Patchify in the reference layout: 16 latent channels plus one always-zero channel, then 2x2 patches. */
    fun patchTokens(latentNchw: FloatArray): FloatArray =
        MediaTekAnimaTensorOps.patchifyLatent(
            latentNchw,
            latentChannels,
            height,
            width,
            PATCH_SIZE,
            PATCH_FEATURES,
        )

    /** Final chunk flow tokens -> latent NCHW; the final layer packs channels fastest inside each patch. */
    fun flowToLatent(flowTokens: FloatArray): FloatArray =
        MediaTekAnimaTensorOps.unpatchifyPrediction(
            flowTokens,
            latentChannels,
            height,
            width,
            PATCH_SIZE,
        )

    /**
     * Normalized sampling latent -> VAE input: apply the model's per-channel VAE
     * mean/std, then transpose NCHW -> NHWC and requantize at the route scale.
     * Feeding the normalized latent directly leaves the VAE range underused and
     * produces a strong 8 px block texture in the decoded image.
     */
    fun vaeInput(latentNchw: FloatArray): ByteArray {
        require(latentNchw.size == latentChannels * height * width) {
            "latent is ${latentNchw.size} floats; expected ${latentChannels}x${height}x${width}"
        }
        require(latentChannels == VAE_MEAN.size) { "unsupported VAE channel count $latentChannels" }
        val plane = height * width
        val denormalized = FloatArray(latentNchw.size) { index ->
            val channel = index / plane
            latentNchw[index] * VAE_STD[channel] + VAE_MEAN[channel]
        }
        return MediaTekNpugVaeCodec.nchwToNhwcInt16(
            denormalized,
            latentChannels,
            height,
            width,
            vaeInputScale,
            vaeInputZeroPoint,
        )
    }

    companion object {
        const val LATENT_CHANNELS = 16
        private val VAE_MEAN = floatArrayOf(
            -0.7571f, -0.7089f, -0.9113f, 0.1075f, -0.1745f, 0.9653f, -0.1517f, 1.5508f,
            0.4134f, -0.0715f, 0.5517f, -0.3632f, -0.1922f, -0.9497f, 0.2503f, -0.2921f,
        )
        private val VAE_STD = floatArrayOf(
            2.8184f, 1.4541f, 2.3275f, 2.6558f, 1.2196f, 1.7708f, 2.6052f, 2.0743f,
            3.2687f, 2.1526f, 2.8652f, 1.5579f, 1.6382f, 1.1253f, 2.8251f, 1.9160f,
        )
        const val PATCH_SIZE = 2
        const val PATCH_FEATURES = 68
        const val FINAL_FEATURES = 64
        const val HIDDEN_WIDTH = 2048
        const val BLOCK_COUNT = 28
        const val BLOCK_SEGMENTS = 9
        const val FINAL_SEGMENTS = 2
    }
}

/** Euler transport step recovered from the reference loop: `latent += flow * (sigmaNext - sigma)`. */
object MediaTekNpugEuler {
    fun update(latent: FloatArray, flow: FloatArray, sigma: Double, sigmaNext: Double): FloatArray {
        require(latent.size == flow.size) { "latent and flow must have the same element count" }
        require(sigma.isFinite() && sigmaNext.isFinite()) { "sigma values must be finite" }
        val delta = sigmaNext - sigma
        return FloatArray(latent.size) { index ->
            val updated = (latent[index] + flow[index] * delta).toFloat()
            require(updated.isFinite()) { "Euler update produced a non-finite latent at index $index" }
            updated
        }
    }
}

object MediaTekNpugChainPlanner {
    fun plan(
        manifest: MediaTekAotManifest,
        profileId: String,
        steps: Int,
        routes: Map<String, MediaTekNpugRoute>,
    ): MediaTekNpugChainPlan {
        val profile = manifest.profile(profileId)
            ?: throw IllegalArgumentException("profile $profileId is not declared")
        val problems = mutableListOf<String>()
        problems += manifest.validate()
        if (steps !in profile.supportedSteps) {
            problems += "steps $steps is not supported by profile $profileId"
        }
        val modulation = profile.modulations.firstOrNull { it.stepCount == steps }
        if (modulation == null) {
            problems += "profile $profileId has no modulation asset for $steps steps"
        }
        val variants = manifest.variants
        if (variants.size < 2) {
            problems += "an NPUG chain needs at least two execution variants"
        } else if (MediaTekNpugChainPlan.BLOCK_COUNT % variants.size != 0) {
            problems += "${variants.size} execution variants cannot split ${MediaTekNpugChainPlan.BLOCK_COUNT} blocks"
        }
        variants.forEach { variant ->
            if (routes[variant.id] == null) problems += "variant ${variant.id}: no parsed route was supplied"
        }
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))

        val blocksPerChunk = MediaTekNpugChainPlan.BLOCK_COUNT / variants.size
        val tokenCount = profile.tokenCount
        val contextDims = manifest.conditioning.contextShape.takeIf { it.isNotEmpty() }
        val contextWidth = contextDims?.last()
        val contextLength = contextDims?.first()
        val chunks = ArrayList<MediaTekNpugChunkPlan>(variants.size)
        var referenceContextDims: List<Int>? = null
        var referenceZeroDims: List<Int>? = null
        var referenceRopeDims: List<Int>? = null

        variants.forEachIndexed { index, variant ->
            val route = routes.getValue(variant.id)
            val last = index == variants.lastIndex
            val blockStart = index * blocksPerChunk
            val segments = blocksPerChunk * MediaTekNpugChainPlan.BLOCK_SEGMENTS +
                if (last) MediaTekNpugChainPlan.FINAL_SEGMENTS else 0
            val expectedInputs = 3 + segments + 2
            if (route.inputs.size != expectedInputs) {
                problems += "variant ${variant.id}: route has ${route.inputs.size} inputs; a ${blocksPerChunk}-block " +
                    "chunk with ${if (last) "final segments" else "no final segments"} needs $expectedInputs"
            }
            if (route.outputs.size != 1) {
                problems += "variant ${variant.id}: chunk routes must declare exactly one output"
            }

            val patchInput = route.inputs.getOrNull(0)
            val expectedInputFeatures = if (index == 0) {
                MediaTekNpugChainPlan.PATCH_FEATURES
            } else {
                MediaTekNpugChainPlan.HIDDEN_WIDTH
            }
            if (patchInput == null) {
                problems += "variant ${variant.id}: missing first input"
            } else if (patchInput.dims.elements() != tokenCount.toLong() * expectedInputFeatures ||
                patchInput.dims.last() != expectedInputFeatures
            ) {
                problems += "variant ${variant.id}: first input must be [1,$tokenCount,$expectedInputFeatures]" +
                    " but is ${patchInput.dims}"
            }

            val context = route.inputs.getOrNull(1)
            if (context == null) {
                problems += "variant ${variant.id}: missing context input"
            } else if (contextLength != null && contextWidth != null) {
                if (context.dims.elements() != contextLength.toLong() * contextWidth ||
                    context.dims.last() != contextWidth
                ) {
                    problems += "variant ${variant.id}: context input must be [1,$contextLength,$contextWidth]" +
                        " but is ${context.dims}"
                }
            } else if (context.dims.size != 3 || context.dims[0] != 1) {
                problems += "variant ${variant.id}: dynamic context input must be rank 3"
            }

            if (index == 0) {
                referenceContextDims = context?.dims
                referenceRopeDims = route.inputs.getOrNull(expectedInputs - 2)?.dims
            } else {
                if (context != null && referenceContextDims != null && context.dims != referenceContextDims) {
                    problems += "variant ${variant.id}: context input shape ${context.dims} differs from the first chunk" +
                        " $referenceContextDims"
                }
                val ropeDims = route.inputs.getOrNull(expectedInputs - 2)?.dims
                if (ropeDims != null && referenceRopeDims != null && ropeDims != referenceRopeDims) {
                    problems += "variant ${variant.id}: RoPE half shape $ropeDims differs from the first chunk" +
                        " $referenceRopeDims"
                }
            }
            val zero = route.inputs.getOrNull(2)
            if (zero == null) {
                problems += "variant ${variant.id}: missing zero-context input"
            } else {
                val expectedLength = contextLength ?: zero.dims.lastOrNull()
                if (zero.dims.size != 3 || zero.dims[0] != 1 || zero.dims[1] != 1 ||
                    (expectedLength != null && zero.dims[2] != expectedLength)
                ) {
                    problems += "variant ${variant.id}: zero-context input must be [1,1,${expectedLength ?: "?"}]" +
                        " but is ${zero.dims}"
                }
            }

            if (index == 0) {
                referenceZeroDims = zero?.dims
            } else if (zero != null && referenceZeroDims != null && zero.dims != referenceZeroDims) {
                problems += "variant ${variant.id}: zero-context input shape ${zero.dims} differs from the first chunk" +
                    " $referenceZeroDims"
            }

            for (slot in 0 until segments) {
                val input = route.inputs.getOrNull(3 + slot)
                if (input == null) break
                if (input.dims != listOf(1, 1, MediaTekNpugChainPlan.HIDDEN_WIDTH)) {
                    problems += "variant ${variant.id}: modulation slot $slot must be [1,1,${MediaTekNpugChainPlan.HIDDEN_WIDTH}]" +
                        " but is ${input.dims}"
                }
            }

            val ropeLow = route.inputs.getOrNull(expectedInputs - 2)
            val ropeHigh = route.inputs.getOrNull(expectedInputs - 1)
            if (ropeLow == null || ropeHigh == null) {
                problems += "variant ${variant.id}: missing RoPE halves"
            } else {
                if (ropeLow.dims != ropeHigh.dims) {
                    problems += "variant ${variant.id}: RoPE halves have different shapes"
                }
                if (ropeLow.dims.size != 2 || ropeLow.dims[0] != tokenCount ||
                    ropeLow.dims[1] <= 0 || ropeLow.dims[1] % 2 != 0
                ) {
                    problems += "variant ${variant.id}: RoPE half must be [$tokenCount,even]" +
                        " but is ${ropeLow.dims}"
                }
            }

            val output = route.outputs.firstOrNull()
            val expectedOutputFeatures = if (last) {
                MediaTekNpugChainPlan.FINAL_FEATURES
            } else {
                MediaTekNpugChainPlan.HIDDEN_WIDTH
            }
            val expectedOutputElements = tokenCount.toLong() * expectedOutputFeatures
            if (output == null) {
                problems += "variant ${variant.id}: missing output"
            } else if (output.dims.elements() != expectedOutputElements) {
                problems += "variant ${variant.id}: output must contain $expectedOutputElements elements" +
                    " but ${output.dims} contains ${output.dims.elements()}"
            }

            if (index > 0) {
                val previous = chunks[index - 1]
                if (!sameScale(previous.outputScale, patchInput?.scale ?: Double.NaN)) {
                    problems += "variant ${variant.id}: input scale ${patchInput?.scale} does not match the previous" +
                        " chunk output scale ${previous.outputScale}"
                }
            }

            val slots = buildList {
                add(if (index == 0) MediaTekNpugSlotSpec.PatchTokens else MediaTekNpugSlotSpec.HiddenState)
                add(MediaTekNpugSlotSpec.FixedContext)
                add(MediaTekNpugSlotSpec.ZeroContext)
                for (slot in 0 until blocksPerChunk * MediaTekNpugChainPlan.BLOCK_SEGMENTS) {
                    add(
                        MediaTekNpugSlotSpec.Modulation(
                            blockStart + slot / MediaTekNpugChainPlan.BLOCK_SEGMENTS,
                            slot % MediaTekNpugChainPlan.BLOCK_SEGMENTS,
                        ),
                    )
                }
                if (last) {
                    for (segment in 0 until MediaTekNpugChainPlan.FINAL_SEGMENTS) {
                        add(MediaTekNpugSlotSpec.FinalModulation(segment))
                    }
                }
                add(MediaTekNpugSlotSpec.RopeHalf(0))
                add(MediaTekNpugSlotSpec.RopeHalf(1))
            }
            chunks += MediaTekNpugChunkPlan(
                variantId = variant.id,
                graphId = route.graph,
                blockStart = blockStart,
                blockCount = blocksPerChunk,
                inputCount = route.inputs.size,
                inputScale = patchInput?.scale ?: Double.NaN,
                outputElementCount = output?.dims?.elements() ?: -1L,
                outputScale = output?.scale ?: Double.NaN,
                finalChunk = last,
                slots = slots,
            )
        }

        val lastChunk = chunks.last()
        val vaeInput = profile.vae.input
        if (vaeInput.dims.elements() != lastChunk.outputElementCount) {
            problems += "VAE input ${vaeInput.dims} must match the final chunk output element count" +
                " ${lastChunk.outputElementCount}"
        }
        if (vaeInput.dims.last() != MediaTekNpugChainPlan.LATENT_CHANNELS) {
            problems += "VAE input must expose ${MediaTekNpugChainPlan.LATENT_CHANNELS} latent channels"
        }
        if (profile.vae.output.dims.elements() !=
            profile.widthPixels.toLong() * profile.heightPixels * 3
        ) {
            problems += "VAE output must contain width x height x 3 elements"
        }
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))

        return MediaTekNpugChainPlan(
            profileId = profile.id,
            tokenCount = tokenCount,
            gridWidth = profile.gridWidth,
            gridHeight = profile.gridHeight,
            latentChannels = MediaTekNpugChainPlan.LATENT_CHANNELS,
            steps = steps,
            sigmas = MediaTekNpugSigmaSchedules.values(profile.sigmaSchedule, steps),
            chunks = chunks,
            modulation = modulation!!.asset,
            vaeInputScale = vaeInput.scale,
            vaeInputZeroPoint = vaeInput.zeroPoint,
            vaeOutputScale = profile.vae.output.scale,
            vaeInputElementCount = vaeInput.dims.elements(),
        )
    }

    private fun sameScale(left: Double, right: Double): Boolean =
        left == right || (left.isFinite() && right.isFinite() &&
            abs(left - right) <= 1e-12 * max(abs(left), abs(right)))
}

private fun List<Int>.elements(): Long =
    fold(1L) { product, dimension -> Math.multiplyExact(product, dimension.toLong()) }

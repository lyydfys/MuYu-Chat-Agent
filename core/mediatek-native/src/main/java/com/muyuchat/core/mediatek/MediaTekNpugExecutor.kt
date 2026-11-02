package com.muyuchat.core.mediatek

import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt

/** Narrow runtime port so chain execution can be unit-tested without a Neuron device. */
interface MediaTekNpugGraphSession {
    class Network(val inputPaddedBytes: LongArray)

    fun open(file: File, inputBytes: LongArray, outputCount: Int, inputRanks: IntArray): Network

    fun run(inputs: List<ByteArray>, outputBytes: Int): List<ByteArray>

    fun close()
}

/** Default session: one adapter instance per compiled network, closed after that chunk. */
class MediaTekNpugNeuronSession : MediaTekNpugGraphSession {
    private val adapter = MediaTekNeuronRuntimeAdapter()

    override fun open(file: File, inputBytes: LongArray, outputCount: Int, inputRanks: IntArray): MediaTekNpugGraphSession.Network =
        adapter.open(file, inputBytes.size, outputCount, inputRanks).getOrThrow().let { info ->
            MediaTekNpugGraphSession.Network(info.inputPaddedBytes.toLongArray())
        }

    override fun run(inputs: List<ByteArray>, outputBytes: Int): List<ByteArray> =
        adapter.run(inputs, outputBytes).getOrThrow()

    override fun close() {
        adapter.close()
    }
}

class MediaTekNpugCancelledException(message: String) : RuntimeException(message)

data class MediaTekNpugChunkRun(
    val step: Int,
    val variantId: String,
    val bytes: Int,
    val sha256: String,
    val minimum: Int,
    val maximum: Int,
    val mean: Double,
    val standardDeviation: Double,
)

data class MediaTekNpugExecutionResult(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val steps: Int,
    val sigmas: List<Double>,
    val chunkRuns: List<MediaTekNpugChunkRun>,
    val runtimeMessage: String,
    /** Final denoised latent (16 x 64 x 64, channel-major) before the VAE. */
    val finalLatent: FloatArray,
)

data class MediaTekNpugExecutionRequest(
    val plan: MediaTekNpugChainPlan,
    val manifest: MediaTekAotManifest,
    val routes: Map<String, MediaTekNpugRoute>,
    val resolveAsset: (MediaTekBundleAssetContract) -> File,
    val contextValues: FloatArray? = null,
    val latent: FloatArray,
    val isCancelled: () -> Boolean,
    val onProgress: (completedSteps: Int, totalSteps: Int) -> Unit = { _, _ -> },
    val onChunk: (MediaTekNpugChunkRun) -> Unit = {},
)

/**
 * Executes the recovered four-chunk chain once per sampling step and decodes the
 * final latent with the VAE. Every chunk restores one compiled network, runs,
 * and closes before the next chunk starts, so peak native memory does not scale
 * with the number of chunks.
 *
 * Cancellation is checked between chunks; a cancelled run closes the open
 * network before throwing and never returns a partial image.
 */
class MediaTekNpugExecutor(
    private val openSession: () -> MediaTekNpugGraphSession = { MediaTekNpugNeuronSession() },
) {
    fun execute(request: MediaTekNpugExecutionRequest): MediaTekNpugExecutionResult {
        val plan = request.plan
        require(plan.latentChannels == MediaTekNpugChainPlan.LATENT_CHANNELS) { "unsupported latent channel count" }
        require(request.latent.size == plan.latentChannels * plan.height * plan.width) {
            "latent is ${request.latent.size} floats; expected ${plan.latentChannels}x${plan.height}x${plan.width}"
        }
        require(request.latent.all(Float::isFinite)) { "latent contains a non-finite value" }
        val profile = request.manifest.profile(plan.profileId)
            ?: throw IllegalArgumentException("profile ${plan.profileId} is not declared")
        val firstRoute = request.routes.getValue(plan.chunks.first().variantId)
        val context = request.contextValues?.let { quantizeContext(it, firstRoute) }
            ?: request.manifest.conditioning.contextAsset?.let { readContext(request.resolveAsset(it), firstRoute) }
            ?: throw IllegalArgumentException("NPUG execution requires an ACTX context or encoded text")
        val rope = readRope(request.resolveAsset(profile.ropeAsset), plan, firstRoute)
        val modulation = MediaTekNpugModulationFile.parse(
            request.resolveAsset(plan.modulation).readBytes(),
            blockSegments = MediaTekNpugChainPlan.BLOCK_SEGMENTS,
            finalSegments = MediaTekNpugChainPlan.FINAL_SEGMENTS,
        )
        require(modulation.stepCount == plan.steps) {
            "AMOD has ${modulation.stepCount} steps; the plan needs ${plan.steps}"
        }

        var latent = request.latent.copyOf()
        val runs = ArrayList<MediaTekNpugChunkRun>(plan.chunks.size * plan.steps)
        for (step in 0 until plan.steps) {
            checkCancelled(request)
            var hidden: ByteArray? = null
            val tokens = plan.patchTokens(latent)
            for (chunk in plan.chunks) {
                checkCancelled(request)
                val route = request.routes[chunk.variantId]
                    ?: throw IllegalArgumentException("variant ${chunk.variantId} has no parsed route")
                val variant = request.manifest.variants.firstOrNull { it.id == chunk.variantId }
                    ?: throw IllegalArgumentException("variant ${chunk.variantId} is not declared")
                val compiled = variant.compiledNetwork
                    ?: throw IllegalArgumentException("variant ${chunk.variantId} has no compiled network")
                val inputs = assembleInputs(plan, chunk, route, step, tokens, hidden, context, rope, modulation)
                val session = openSession()
                try {
                    val network = session.open(
                        request.resolveAsset(compiled.asset),
                        route.inputs.map { dimsProduct(it.dims) * 2 }.toLongArray(),
                        1,
                        route.inputs.map { it.rank }.toIntArray(),
                    )
                    val padded = inputs.mapIndexed { index, bytes ->
                        val paddedSize = network.inputPaddedBytes[index]
                        require(paddedSize >= bytes.size) {
                            "runtime padding for input $index is $paddedSize, smaller than ${bytes.size}"
                        }
                        bytes.copyOf(paddedSize.toInt())
                    }
                    val outputBytes = Math.multiplyExact(chunk.outputElementCount, 2L).toInt()
                    val output = session.run(padded, outputBytes).singleOrNull()
                        ?: throw IllegalStateException("chunk ${chunk.variantId} did not return exactly one output")
                    require(output.size == outputBytes) {
                        "chunk ${chunk.variantId} returned ${output.size} bytes; expected $outputBytes"
                    }
                    hidden = output
                    val run = chunkRun(step, chunk, output)
                    require(run.maximum > run.minimum) {
                        "chunk ${chunk.variantId} at step $step produced a constant output; refusing an unhealthy hidden state"
                    }
                    runs += run
                    request.onChunk(run)
                } finally {
                    session.close()
                }
            }
            checkCancelled(request)
            val flowBytes = hidden ?: throw IllegalStateException("chain produced no hidden state at step $step")
            val flow = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(
                flowBytes,
                plan.chunks.last().outputScale,
                0,
            )
            latent = MediaTekNpugEuler.update(
                latent,
                plan.flowToLatent(flow),
                plan.sigmas[step],
                plan.sigmas[step + 1],
            )
            request.onProgress(step + 1, plan.steps)
        }

        checkCancelled(request)
        val vaeInput = plan.vaeInput(latent)
        val vaeSession = openSession()
        val image: FloatArray
        try {
            val network = vaeSession.open(
                request.resolveAsset(profile.vae.asset),
                longArrayOf(plan.vaeInputElementCount * 2),
                1,
                intArrayOf(profile.vae.input.dims.size),
            )
            val padded = vaeInput.copyOf(network.inputPaddedBytes[0].toInt())
            val outputBytes = Math.multiplyExact(plan.imageWidth.toLong() * plan.imageHeight * 3, 2L).toInt()
            val bytes = vaeSession.run(listOf(padded), outputBytes).singleOrNull()
                ?: throw IllegalStateException("VAE did not return exactly one output")
            require(bytes.size == outputBytes) {
                "VAE returned ${bytes.size} bytes; expected $outputBytes"
            }
            image = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(bytes, plan.vaeOutputScale, 0)
        } finally {
            vaeSession.close()
        }
        val expectedRgb = plan.imageWidth.toLong() * plan.imageHeight * 3
        require(image.size.toLong() == expectedRgb) {
            "VAE produced ${image.size} values; expected $expectedRgb"
        }
        require(image.all(Float::isFinite)) { "VAE produced a non-finite image value" }
        val imageMin = image.min()
        val imageMax = image.max()
        require(imageMax > imageMin) {
            "VAE produced a constant image; refusing to publish an unhealthy output"
        }
        val png = MediaTekPngEncoder.encodeNcdhwRgb(
            MediaTekNpugVaeCodec.nhwcToNcdhwDepthOne(image, plan.imageHeight, plan.imageWidth, 3),
            plan.imageWidth,
            plan.imageHeight,
        )
        return MediaTekNpugExecutionResult(
            pngBytes = png,
            width = plan.imageWidth,
            height = plan.imageHeight,
            steps = plan.steps,
            sigmas = plan.sigmas,
            chunkRuns = runs,
            runtimeMessage = "NPUG compiled networks were restored and executed via the Neuron runtime; " +
                "device numerics and quality remain unverified.",
            finalLatent = latent.copyOf(),
        )
    }

    private fun assembleInputs(
        plan: MediaTekNpugChainPlan,
        chunk: MediaTekNpugChunkPlan,
        route: MediaTekNpugRoute,
        step: Int,
        tokens: FloatArray,
        hidden: ByteArray?,
        context: ByteArray,
        rope: List<ByteArray>,
        modulation: MediaTekNpugModulationFile,
    ): List<ByteArray> = chunk.slotsForStep(step).mapIndexed { index, slot ->
        val logicalBytes = route.inputs[index].dims.fold(1L) { product, dim -> product * dim } * 2
        val bytes = when (slot) {
            MediaTekNpugSlot.PatchTokens ->
                MediaTekNpugVaeCodec.quantizeInt16LittleEndian(tokens, chunk.inputScale, 0)
            MediaTekNpugSlot.HiddenState ->
                requireNotNull(hidden) { "chunk ${chunk.variantId} needs a hidden state" }
            MediaTekNpugSlot.FixedContext -> context
            MediaTekNpugSlot.ZeroContext ->
                ByteArray(Math.multiplyExact(route.inputs[2].dims.fold(1L) { total, dim -> total * dim }, 2L).toInt())
            is MediaTekNpugSlot.Modulation -> modulation.segmentBytes(step, slot.block, slot.segment)
            is MediaTekNpugSlot.FinalModulation -> modulation.finalSegmentBytes(step, slot.segment)
            is MediaTekNpugSlot.RopeHalf -> rope[slot.half]
        }
        require(bytes.size.toLong() == logicalBytes) {
            "slot $index (${slot::class.simpleName}) is ${bytes.size} B; route needs $logicalBytes"
        }
        bytes
    }

    private fun readContext(file: File, route: MediaTekNpugRoute): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size >= 16) { "ACTX context is truncated" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "ACTX") { "ACTX magic is invalid" }
        val version = readU32(bytes, 4)
        require(version == 1) { "ACTX version $version is unsupported" }
        val tokens = readU32(bytes, 8)
        val width = readU32(bytes, 12)
        val expected = route.inputs[1].dims
        require(tokens == expected[1] && width == expected[2]) {
            "ACTX shape ${tokens}x$width does not match route ${expected}"
        }
        val values = FloatArray(tokens * width)
        val buffer = java.nio.ByteBuffer.wrap(bytes, 16, values.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().get(values)
        require(values.all(Float::isFinite)) { "ACTX context contains a non-finite value" }
        return quantizeContext(values, route)
    }

    private fun quantizeContext(values: FloatArray, route: MediaTekNpugRoute): ByteArray {
        val expected = route.inputs[1].dims
        require(values.size.toLong() == expected[1].toLong() * expected[2]) {
            "ACTX context has ${values.size} values; route expects ${expected[1]}x${expected[2]}"
        }
        require(values.all(Float::isFinite)) { "ACTX context contains a non-finite value" }
        return MediaTekNpugVaeCodec.quantizeInt16LittleEndian(values, route.inputs[1].scale, 0)
    }

    private fun readRope(file: File, plan: MediaTekNpugChainPlan, route: MediaTekNpugRoute): List<ByteArray> {
        val bytes = file.readBytes()
        require(bytes.size % 2 == 0) { "RoPE table is not an int16 array" }
        val half = bytes.size / 2
        val ropeWidth = route.inputs[route.inputs.size - 1].dims[1]
        val expectedHalf = plan.tokenCount.toLong() * ropeWidth * 2
        require(half.toLong() == expectedHalf) {
            "RoPE half is $half B; expected $expectedHalf"
        }
        return listOf(bytes.copyOfRange(0, half), bytes.copyOfRange(half, bytes.size))
    }

    private fun chunkRun(step: Int, chunk: MediaTekNpugChunkPlan, output: ByteArray): MediaTekNpugChunkRun {
        val digest = MessageDigest.getInstance("SHA-256").digest(output)
        val sha = digest.joinToString("") { "%02x".format(it) }
        var minimum = Short.MAX_VALUE.toInt()
        var maximum = Short.MIN_VALUE.toInt()
        var sum = 0.0
        var sumSquares = 0.0
        val count = output.size / 2
        for (index in 0 until count) {
            val value = ((output[index * 2].toInt() and 0xFF) or (output[index * 2 + 1].toInt() shl 8)).toShort().toInt()
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
            sumSquares += value.toDouble() * value
        }
        val mean = if (count > 0) sum / count else 0.0
        val variance = if (count > 0) max(0.0, sumSquares / count - mean * mean) else 0.0
        return MediaTekNpugChunkRun(
            step = step,
            variantId = chunk.variantId,
            bytes = output.size,
            sha256 = sha,
            minimum = minimum,
            maximum = maximum,
            mean = mean,
            standardDeviation = sqrt(variance),
        )
    }

    private fun dimsProduct(dims: List<Int>): Long =
        dims.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension.toLong()) }

    private fun checkCancelled(request: MediaTekNpugExecutionRequest) {
        if (request.isCancelled()) throw MediaTekNpugCancelledException("NPUG generation was cancelled")
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

}

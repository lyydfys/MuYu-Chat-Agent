package com.muyuchat.core.mediatek

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure tensor transforms used by the Anima sampler. Tensor arrays use NCHW storage. */
object MediaTekAnimaTensorOps {
    fun patchifyLatent(
        latent: FloatArray,
        channels: Int,
        height: Int,
        width: Int,
        patchSize: Int,
        paddedChannelsPerPatch: Int,
    ): FloatArray {
        require(channels > 0 && height > 0 && width > 0 && patchSize > 0)
        require(height % patchSize == 0 && width % patchSize == 0)
        val patchArea = Math.multiplyExact(patchSize, patchSize)
        require(paddedChannelsPerPatch >= channels * patchArea)
        require(latent.size.toLong() == channels.toLong() * height * width)
        require(latent.all(Float::isFinite))
        val rows = height / patchSize
        val columns = width / patchSize
        val tokens = Math.multiplyExact(rows, columns)
        val result = FloatArray(Math.multiplyExact(tokens, paddedChannelsPerPatch))
        for (tokenY in 0 until rows) {
            for (tokenX in 0 until columns) {
                val token = tokenY * columns + tokenX
                for (channel in 0 until channels) {
                    for (patchY in 0 until patchSize) {
                        for (patchX in 0 until patchSize) {
                            val sourceY = tokenY * patchSize + patchY
                            val sourceX = tokenX * patchSize + patchX
                            val source = (channel * height + sourceY) * width + sourceX
                            val feature = channel * patchArea + patchY * patchSize + patchX
                            result[token * paddedChannelsPerPatch + feature] = latent[source]
                        }
                    }
                }
            }
        }
        return result
    }

    fun unpatchifyPrediction(
        prediction: FloatArray,
        channels: Int,
        height: Int,
        width: Int,
        patchSize: Int,
    ): FloatArray {
        require(channels > 0 && height > 0 && width > 0 && patchSize > 0)
        require(height % patchSize == 0 && width % patchSize == 0)
        val rows = height / patchSize
        val columns = width / patchSize
        val patchArea = Math.multiplyExact(patchSize, patchSize)
        require(prediction.size.toLong() == rows.toLong() * columns * channels * patchArea)
        require(prediction.all(Float::isFinite))
        val latent = FloatArray(Math.multiplyExact(Math.multiplyExact(channels, height), width))
        for (tokenY in 0 until rows) {
            for (tokenX in 0 until columns) {
                val token = tokenY * columns + tokenX
                for (patchY in 0 until patchSize) {
                    for (patchX in 0 until patchSize) {
                        val patchOffset = (patchY * patchSize + patchX) * channels
                        for (channel in 0 until channels) {
                            val targetY = tokenY * patchSize + patchY
                            val targetX = tokenX * patchSize + patchX
                            val target = (channel * height + targetY) * width + targetX
                            latent[target] = prediction[token * channels * patchArea + patchOffset + channel]
                        }
                    }
                }
            }
        }
        return latent
    }

    fun classifierFreeGuidance(
        positive: FloatArray,
        negative: FloatArray,
        scale: Float,
    ): FloatArray {
        require(positive.size == negative.size && scale.isFinite() && scale >= 0f)
        require(positive.all(Float::isFinite) && negative.all(Float::isFinite))
        return FloatArray(positive.size) { index ->
            (negative[index] + scale * (positive[index] - negative[index])).also {
                require(it.isFinite()) { "CFG produced a non-finite flow value at index $index" }
            }
        }
    }

    fun eulerUpdate(latent: FloatArray, flow: FloatArray, sigma: Double, sigmaNext: Double): FloatArray {
        require(latent.size == flow.size)
        require(sigma.isFinite() && sigmaNext.isFinite() && sigma in 0.0..1.0 && sigmaNext in 0.0..1.0)
        require(sigmaNext <= sigma)
        require(latent.all(Float::isFinite) && flow.all(Float::isFinite))
        val delta = (sigmaNext - sigma).toFloat()
        return FloatArray(latent.size) { index ->
            (latent[index] + flow[index] * delta).also {
                require(it.isFinite()) { "Euler update produced a non-finite latent value at index $index" }
            }
        }
    }

    fun validateNumericGate(
        input: MediaTekTensorData.Float32?,
        output: MediaTekTensorData.Float32,
        gate: MediaTekNumericDriftGate,
        stage: MediaTekAnimaNumericStage,
    ): List<String> {
        val stats = output.stats()
        val errors = mutableListOf<String>()
        if (!stats.standardDeviation.isFinite() || stats.standardDeviation !in gate.minimumStd..gate.maximumStd) {
            errors += "$stage standard deviation ${stats.standardDeviation} is outside [${gate.minimumStd},${gate.maximumStd}]"
        }
        val maximumAbs = minOf(gate.maximumAbs, MediaTekNumericDriftGate.RUNTIME_MAXIMUM_ABS)
        if (!stats.maxAbs.isFinite() || stats.maxAbs > maximumAbs) {
            errors += "$stage maximum absolute value ${stats.maxAbs} exceeds $maximumAbs"
        }
        val minRatio = if (stage == MediaTekAnimaNumericStage.DIT_BLOCK) {
            maxOf(
                gate.minimumOutputToInputStdRatio ?: MediaTekNumericDriftGate.DIT_BLOCK_MIN_STD_RATIO,
                MediaTekNumericDriftGate.DIT_BLOCK_MIN_STD_RATIO,
            )
        } else gate.minimumOutputToInputStdRatio
        val maxRatio = if (stage == MediaTekAnimaNumericStage.DIT_BLOCK) {
            minOf(
                gate.maximumOutputToInputStdRatio ?: MediaTekNumericDriftGate.DIT_BLOCK_MAX_STD_RATIO,
                MediaTekNumericDriftGate.DIT_BLOCK_MAX_STD_RATIO,
            )
        } else gate.maximumOutputToInputStdRatio
        if ((minRatio != null || maxRatio != null) && input == null) {
            errors += "$stage drift ratio gate requires an input tensor"
        } else if (input != null && minRatio != null && maxRatio != null) {
            val inputStd = input.stats().standardDeviation
            val ratio = if (inputStd == 0.0) {
                if (stats.standardDeviation == 0.0) 1.0 else Double.POSITIVE_INFINITY
            } else stats.standardDeviation / inputStd
            if (!ratio.isFinite() || ratio !in minRatio..maxRatio) {
                errors += "$stage output/input std ratio $ratio is outside [$minRatio,$maxRatio]"
            }
        }
        return errors
    }

    fun decodeFloat16LittleEndian(bytes: ByteArray, expectedElements: Int): FloatArray {
        require(expectedElements >= 0 && bytes.size.toLong() == expectedElements.toLong() * 2L)
        val values = FloatArray(expectedElements)
        for (index in 0 until expectedElements) {
            val bits = (bytes[index * 2].toInt() and 0xff) or ((bytes[index * 2 + 1].toInt() and 0xff) shl 8)
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits ushr 10) and 0x1f
            val fraction = bits and 0x03ff
            val floatBits = when (exponent) {
                0 -> if (fraction == 0) sign else {
                    var normalized = fraction
                    var shift = 0
                    while ((normalized and 0x0400) == 0) {
                        normalized = normalized shl 1
                        shift++
                    }
                    val mantissa = (normalized and 0x03ff) shl 13
                    sign or ((127 - 14 - shift) shl 23) or mantissa
                }
                0x1f -> sign or (0xff shl 23) or (fraction shl 13)
                else -> sign or ((exponent + 112) shl 23) or (fraction shl 13)
            }
            values[index] = Float.fromBits(floatBits)
        }
        require(values.all(Float::isFinite)) { "FP16 asset contains NaN or infinity" }
        return values
    }

    /** Matches stable-diffusion.cpp's MT19937RNG PyTorch CPU `randn` fill order. */
    fun standardNormalLikeTorchMt19937(seed: Long, count: Int): FloatArray {
        require(seed >= 0L && count >= 0)
        val random = TorchMt19937(seed)
        return FloatArray(count).also(random::fillNormal)
    }
}

private class TorchMt19937(seed: Long) {
    private val state = IntArray(624)
    private var left = 1
    private var next = 0
    private var hasNextGaussian = false
    private var nextGaussian = 0.0

    init {
        state[0] = seed.toInt()
        for (index in 1 until state.size) {
            val previous = state[index - 1]
            val unsignedPrevious = previous.toLong() and 0xffffffffL
            state[index] = (1812433253L * (unsignedPrevious xor (unsignedPrevious ushr 30)) + index).toInt()
        }
    }

    private fun twist(u: Int, v: Int): Int =
        ((u and -0x80000000) or (v and 0x7fffffff)).ushr(1) xor if ((v and 1) != 0) 0x9908b0df.toInt() else 0

    private fun nextState() {
        var position = 0
        var count = 624 - 397 + 1
        while (--count > 0) {
            state[position] = state[position + 397] xor twist(state[position], state[position + 1])
            position++
        }
        count = 397
        while (--count > 0) {
            state[position] = state[position + (397 - 624)] xor twist(state[position], state[position + 1])
            position++
        }
        state[position] = state[position + (397 - 624)] xor twist(state[position], state[0])
        left = 624
        next = 0
    }

    private fun nextUInt32(): Long {
        if (--left == 0) nextState()
        var value = state[next++]
        value = value xor (value ushr 11)
        value = value xor ((value shl 7) and 0x9d2c5680.toInt())
        value = value xor ((value shl 15) and 0xefc60000.toInt())
        value = value xor (value ushr 18)
        return value.toLong() and 0xffffffffL
    }

    private fun nextUInt64(): ULong = (nextUInt32().toULong() shl 32) or nextUInt32().toULong()

    private fun uniformFloat(): Float = ((nextUInt32() and 0x00ffffffL).toFloat() * (1.0f / 16777216.0f))

    private fun uniformDouble(value: ULong): Double =
        ((value and ((1uL shl 53) - 1uL)).toDouble() * (1.0 / 9007199254740992.0))

    private fun normalDouble(): Double {
        if (hasNextGaussian) {
            hasNextGaussian = false
            return nextGaussian
        }
        val u1 = uniformDouble(nextUInt64())
        val u2 = uniformDouble(nextUInt64())
        val radius = sqrt(-2.0 * java.lang.StrictMath.log1p(-u2))
        val theta = 2.0 * Math.PI * u1
        val value = radius * cos(theta)
        nextGaussian = radius * sin(theta)
        hasNextGaussian = true
        return value
    }

    fun fillNormal(values: FloatArray) {
        if (values.size < 16) {
            for (index in values.indices) values[index] = normalDouble().toFloat()
            return
        }
        for (index in values.indices) values[index] = uniformFloat()
        var index = 0
        while (index < values.size - 15) {
            normalFill16(values, index)
            index += 16
        }
        if (values.size % 16 != 0) {
            val start = values.size - 16
            for (offset in 0 until 16) values[start + offset] = uniformFloat()
            normalFill16(values, start)
        }
    }

    private fun normalFill16(values: FloatArray, start: Int) {
        for (index in 0 until 8) {
            val u1 = 1f - values[start + index]
            val u2 = values[start + index + 8]
            val radius = sqrt(-2f * ln(u1))
            val theta = (2f * Math.PI.toFloat()) * u2
            values[start + index] = radius * cos(theta)
            values[start + index + 8] = radius * sin(theta)
        }
    }
}

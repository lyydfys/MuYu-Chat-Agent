package com.muyuchat.core.mediatek

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU text path of the reference Anima package: Qwen3-0.6B followed by a
 * six-layer T5-stream adapter that writes the lk x 1024 ACTX context.
 *
 * The math and hyper-parameters follow the locked libanima disassembly. It is
 * intentionally single-threaded: correctness is the contract, and the device
 * path can parallelise or move this to text NPU graphs later.
 */
class MediaTekAotTextEncoder(
    private val weights: MediaTekAotTextWeights,
    private val tokenizer: MediaTekAotTokenizer,
) {
    data class Encoding(
        val context: FloatArray,
        val lk: Int,
        val qwenTokens: IntArray,
        val t5Tokens: IntArray,
        val elapsedMs: Long,
        val contextSha256: String,
        val minimum: Float,
        val maximum: Float,
        val mean: Float,
        val standardDeviation: Float,
    )

    fun requireSupportedModel() {
        require(weights.has("q.embed")) { "ATXW is missing q.embed" }
        require(weights.has("q.norm")) { "ATXW is missing q.norm" }
        require(weights.has("a.embed")) { "ATXW is missing a.embed" }
        require(weights.has("a.out")) { "ATXW is missing a.out" }
        require(weights.has("a.outb")) { "ATXW is missing a.outb" }
        require(weights.has("a.norm")) { "ATXW is missing a.norm" }
        for (layer in 0 until LAYERS) {
            for (suffix in QWEN_SUFFIXES) {
                val name = "q.$layer.$suffix"
                require(weights.has(name)) { "ATXW is missing $name" }
            }
        }
        for (layer in 0 until ADAPTER_LAYERS) {
            for (suffix in ADAPTER_SUFFIXES) {
                val name = "a.$layer.$suffix"
                require(weights.has(name)) { "ATXW is missing $name" }
            }
        }
    }

    fun encode(
        prompt: String,
        lk: Int = DEFAULT_LK,
        isCancelled: () -> Boolean = { false },
    ): Encoding {
        val tokens = tokenizer.encodePrompt(prompt, lk)
        return encodeTokens(tokens.qwen, tokens.t5, lk, isCancelled)
    }

    fun encodeTokens(
        qwenTokens: IntArray,
        t5Tokens: IntArray,
        lk: Int = DEFAULT_LK,
        isCancelled: () -> Boolean = { false },
    ): Encoding {
        require(lk in 2..1024) { "lk must be within 2..1024" }
        require(qwenTokens.isNotEmpty()) { "the Qwen stream must not be empty" }
        require(t5Tokens.isNotEmpty()) { "the T5 stream must not be empty" }
        require(qwenTokens.size <= lk) { "Qwen tokens ${qwenTokens.size} exceed lk $lk" }
        require(t5Tokens.size <= lk) { "T5 tokens ${t5Tokens.size} exceed lk $lk" }
        requireSupportedModel()
        val started = System.nanoTime()
        val qwen = runQwen(qwenTokens, isCancelled)
        val context = runAdapter(t5Tokens, qwen, lk, isCancelled)
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        return statistics(context, lk, qwenTokens, t5Tokens, elapsed)
    }

    fun encodeQwenHidden(
        qwenTokens: IntArray,
        isCancelled: () -> Boolean = { false },
    ): Array<FloatArray> {
        require(qwenTokens.isNotEmpty()) { "the Qwen stream must not be empty" }
        requireSupportedModel()
        return runQwen(qwenTokens, isCancelled)
    }

    private fun runQwen(tokens: IntArray, isCancelled: () -> Boolean): Array<FloatArray> {
        val positions = tokens.size
        val hidden = Array(positions) { weights.embedding("q.embed", tokens[it]) }
        val rope = Rotary(HEAD_DIM, ROPE_THETA, max(2, positions))
        val attentionScale = (1.0 / sqrt(HEAD_DIM.toDouble())).toFloat()
        for (layer in 0 until LAYERS) {
            cancelled(isCancelled, "Qwen layer $layer")
            val inNorm = weights.vector("q.$layer.in_norm")
            val postNorm = weights.vector("q.$layer.post_norm")
            val qNorm = weights.vector("q.$layer.qn")
            val kNorm = weights.vector("q.$layer.kn")
            val q = Array(positions) { FloatArray(Q_HEADS * HEAD_DIM) }
            val k = Array(positions) { FloatArray(KV_HEADS * HEAD_DIM) }
            val v = Array(positions) { FloatArray(KV_HEADS * HEAD_DIM) }
            for (position in 0 until positions) {
                val normalized = rmsNorm(hidden[position], inNorm)
                val qValues = weights.linear(normalized, "q.$layer.q")
                val kValues = weights.linear(normalized, "q.$layer.k")
                val vValues = weights.linear(normalized, "q.$layer.v")
                for (head in 0 until Q_HEADS) {
                    rmsNormHead(qValues, head * HEAD_DIM, HEAD_DIM, qNorm)
                    rope.apply(qValues, head * HEAD_DIM, position)
                }
                for (head in 0 until KV_HEADS) {
                    rmsNormHead(kValues, head * HEAD_DIM, HEAD_DIM, kNorm)
                    rope.apply(kValues, head * HEAD_DIM, position)
                }
                q[position] = qValues
                k[position] = kValues
                v[position] = vValues
            }
            val attended = Array(positions) { FloatArray(Q_HEADS * HEAD_DIM) }
            attention(q, k, v, positions, positions, Q_HEADS, KV_HEADS, HEAD_DIM, true, attentionScale, attended, isCancelled)
            for (position in 0 until positions) {
                val projected = weights.linear(attended[position], "q.$layer.o")
                addInPlace(hidden[position], projected)
            }
            for (position in 0 until positions) {
                if (position and 15 == 0) cancelled(isCancelled, "Qwen MLP layer $layer")
                val normalized = rmsNorm(hidden[position], postNorm)
                val gate = weights.linear(normalized, "q.$layer.gate")
                val up = weights.linear(normalized, "q.$layer.up")
                val activated = FloatArray(FFN) { silu(gate[it]) * up[it] }
                val down = weights.linear(activated, "q.$layer.down")
                addInPlace(hidden[position], down)
            }
        }
        val finalNorm = weights.vector("q.norm")
        return Array(positions) { rmsNorm(hidden[it], finalNorm) }
    }

    private fun runAdapter(
        t5Tokens: IntArray,
        qwen: Array<FloatArray>,
        lk: Int,
        isCancelled: () -> Boolean,
    ): FloatArray {
        val positions = t5Tokens.size
        val qwenPositions = qwen.size
        val stream = Array(positions) { weights.embedding("a.embed", t5Tokens[it]) }
        val ropeSelf = Rotary(ADAPTER_HEAD_DIM, ADAPTER_ROPE_THETA, max(2, positions))
        val ropeCross = Rotary(ADAPTER_HEAD_DIM, ADAPTER_ROPE_THETA, max(2, qwenPositions))
        val attentionScale = (1.0 / sqrt(ADAPTER_HEAD_DIM.toDouble())).toFloat()
        for (layer in 0 until ADAPTER_LAYERS) {
            cancelled(isCancelled, "adapter layer $layer")
            val selfNorm = weights.vector("a.$layer.n_self")
            val selfQNorm = weights.vector("a.$layer.sa_qn")
            val selfKNorm = weights.vector("a.$layer.sa_kn")
            val q = Array(positions) { FloatArray(HIDDEN) }
            val k = Array(positions) { FloatArray(HIDDEN) }
            val v = Array(positions) { FloatArray(HIDDEN) }
            for (position in 0 until positions) {
                val normalized = rmsNorm(stream[position], selfNorm)
                val qValues = weights.linear(normalized, "a.$layer.sa_q")
                val kValues = weights.linear(normalized, "a.$layer.sa_k")
                val vValues = weights.linear(normalized, "a.$layer.sa_v")
                for (head in 0 until ADAPTER_HEADS) {
                    rmsNormHead(qValues, head * ADAPTER_HEAD_DIM, ADAPTER_HEAD_DIM, selfQNorm)
                    ropeSelf.apply(qValues, head * ADAPTER_HEAD_DIM, position)
                    rmsNormHead(kValues, head * ADAPTER_HEAD_DIM, ADAPTER_HEAD_DIM, selfKNorm)
                    ropeSelf.apply(kValues, head * ADAPTER_HEAD_DIM, position)
                }
                q[position] = qValues
                k[position] = kValues
                v[position] = vValues
            }
            val attended = Array(positions) { FloatArray(HIDDEN) }
            attention(q, k, v, positions, positions, ADAPTER_HEADS, ADAPTER_HEADS, ADAPTER_HEAD_DIM, false, attentionScale, attended, isCancelled)
            for (position in 0 until positions) {
                val projected = weights.linear(attended[position], "a.$layer.sa_o")
                addInPlace(stream[position], projected)
            }

            val crossNorm = weights.vector("a.$layer.n_cross")
            val crossQNorm = weights.vector("a.$layer.ca_qn")
            val crossKNorm = weights.vector("a.$layer.ca_kn")
            val crossQ = Array(positions) { FloatArray(HIDDEN) }
            for (position in 0 until positions) {
                val normalized = rmsNorm(stream[position], crossNorm)
                val qValues = weights.linear(normalized, "a.$layer.ca_q")
                for (head in 0 until ADAPTER_HEADS) {
                    rmsNormHead(qValues, head * ADAPTER_HEAD_DIM, ADAPTER_HEAD_DIM, crossQNorm)
                    ropeSelf.apply(qValues, head * ADAPTER_HEAD_DIM, position)
                }
                crossQ[position] = qValues
            }
            val crossK = Array(qwenPositions) { FloatArray(HIDDEN) }
            val crossV = Array(qwenPositions) { FloatArray(HIDDEN) }
            for (position in 0 until qwenPositions) {
                val kValues = weights.linear(qwen[position], "a.$layer.ca_k")
                val vValues = weights.linear(qwen[position], "a.$layer.ca_v")
                for (head in 0 until ADAPTER_HEADS) {
                    rmsNormHead(kValues, head * ADAPTER_HEAD_DIM, ADAPTER_HEAD_DIM, crossKNorm)
                    ropeCross.apply(kValues, head * ADAPTER_HEAD_DIM, position)
                }
                crossK[position] = kValues
                crossV[position] = vValues
            }
            val crossAttended = Array(positions) { FloatArray(HIDDEN) }
            attention(crossQ, crossK, crossV, positions, qwenPositions, ADAPTER_HEADS, ADAPTER_HEADS, ADAPTER_HEAD_DIM, false, attentionScale, crossAttended, isCancelled)
            for (position in 0 until positions) {
                val projected = weights.linear(crossAttended[position], "a.$layer.ca_o")
                addInPlace(stream[position], projected)
            }

            val mlpNorm = weights.vector("a.$layer.n_mlp")
            val fc1Bias = weights.vector("a.$layer.fc1b")
            val fc2Bias = weights.vector("a.$layer.fc2b")
            for (position in 0 until positions) {
                if (position and 15 == 0) cancelled(isCancelled, "adapter MLP layer $layer")
                val normalized = rmsNorm(stream[position], mlpNorm)
                val first = weights.linear(normalized, "a.$layer.fc1", fc1Bias)
                for (index in first.indices) first[index] = gelu(first[index])
                val second = weights.linear(first, "a.$layer.fc2", fc2Bias)
                addInPlace(stream[position], second)
            }
        }
        val outputBias = weights.vector("a.outb")
        val outputNorm = weights.vector("a.norm")
        val context = FloatArray(lk * HIDDEN)
        for (position in 0 until min(positions, lk)) {
            val projected = weights.linear(stream[position], "a.out", outputBias)
            val normalized = rmsNorm(projected, outputNorm)
            System.arraycopy(normalized, 0, context, position * HIDDEN, HIDDEN)
        }
        return context
    }

    private fun attention(
        q: Array<FloatArray>,
        k: Array<FloatArray>,
        v: Array<FloatArray>,
        queryCount: Int,
        keyCount: Int,
        heads: Int,
        kvHeads: Int,
        headDim: Int,
        causal: Boolean,
        scale: Float,
        out: Array<FloatArray>,
        isCancelled: () -> Boolean,
    ) {
        val scores = FloatArray(keyCount)
        for (query in 0 until queryCount) {
            if (query and 31 == 0) cancelled(isCancelled, "attention")
            val queryRow = q[query]
            val outputRow = out[query]
            val keys = if (causal) query + 1 else keyCount
            for (head in 0 until heads) {
                val offset = head * headDim
                val kvOffset = (head * kvHeads / heads) * headDim
                var maximum = Float.NEGATIVE_INFINITY
                for (key in 0 until keys) {
                    var dot = 0f
                    val keyRow = k[key]
                    for (dimension in 0 until headDim) dot += queryRow[offset + dimension] * keyRow[kvOffset + dimension]
                    val score = dot * scale
                    scores[key] = score
                    if (score > maximum) maximum = score
                }
                var sum = 0.0
                for (key in 0 until keys) {
                    val weight = exp((scores[key] - maximum).toDouble())
                    scores[key] = weight.toFloat()
                    sum += weight
                }
                val inverse = if (sum == 0.0) 0f else (1.0 / sum).toFloat()
                for (dimension in 0 until headDim) {
                    var accumulator = 0f
                    for (key in 0 until keys) accumulator += scores[key] * v[key][kvOffset + dimension]
                    outputRow[offset + dimension] = accumulator * inverse
                }
            }
        }
    }

    private fun statistics(
        context: FloatArray,
        lk: Int,
        qwenTokens: IntArray,
        t5Tokens: IntArray,
        elapsedMs: Long,
    ): Encoding {
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var squares = 0.0
        context.forEach { value ->
            minimum = min(minimum, value)
            maximum = max(maximum, value)
            sum += value
            squares += value.toDouble() * value
        }
        val mean = sum / context.size
        val variance = max(0.0, squares / context.size - mean * mean)
        val digest = ByteBuffer.allocate(context.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        context.forEach { digest.putFloat(it) }
        val sha = MessageDigest.getInstance("SHA-256").digest(digest.array())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return Encoding(
            context = context,
            lk = lk,
            qwenTokens = qwenTokens.copyOf(),
            t5Tokens = t5Tokens.copyOf(),
            elapsedMs = elapsedMs,
            contextSha256 = sha,
            minimum = minimum,
            maximum = maximum,
            mean = mean.toFloat(),
            standardDeviation = sqrt(variance).toFloat(),
        )
    }

    private fun rmsNorm(input: FloatArray, weight: FloatArray): FloatArray {
        var sum = 0.0
        for (value in input) sum += value.toDouble() * value
        val scale = (1.0 / sqrt(sum / input.size + EPSILON)).toFloat()
        return FloatArray(input.size) { input[it] * scale * weight[it] }
    }

    private fun rmsNormHead(values: FloatArray, base: Int, size: Int, weight: FloatArray) {
        var sum = 0.0
        for (index in 0 until size) {
            val value = values[base + index]
            sum += value.toDouble() * value
        }
        val scale = (1.0 / sqrt(sum / size + EPSILON)).toFloat()
        for (index in 0 until size) values[base + index] = values[base + index] * scale * weight[index]
    }

    private fun addInPlace(target: FloatArray, addition: FloatArray) {
        for (index in target.indices) target[index] += addition[index]
    }

    private fun silu(value: Float): Float = value / (1f + exp(-value.toDouble()).toFloat())

    private fun gelu(value: Float): Float =
        (0.5f * value * (1f + erf(value.toDouble() / sqrt(2.0)))).toFloat()

    private fun cancelled(isCancelled: () -> Boolean, stage: String) {
        if (isCancelled()) throw MediaTekNpugCancelledException("text encoding was cancelled during $stage")
    }

    private class Rotary(headDim: Int, theta: Double, positions: Int) {
        private val half = headDim / 2
        private val cosines = Array(positions) { FloatArray(half) }
        private val sines = Array(positions) { FloatArray(half) }

        init {
            for (position in 0 until positions) {
                for (index in 0 until half) {
                    val angle = position * Math.pow(theta, -2.0 * index / headDim)
                    cosines[position][index] = cos(angle).toFloat()
                    sines[position][index] = sin(angle).toFloat()
                }
            }
        }

        fun apply(values: FloatArray, base: Int, position: Int) {
            val cosine = cosines[position]
            val sine = sines[position]
            for (index in 0 until half) {
                val first = values[base + index]
                val second = values[base + index + half]
                values[base + index] = first * cosine[index] - second * sine[index]
                values[base + index + half] = second * cosine[index] + first * sine[index]
            }
        }
    }

    companion object {
        const val DEFAULT_LK = 256
        private const val HIDDEN = 1024
        private const val LAYERS = 28
        private const val FFN = 3072
        private const val Q_HEADS = 16
        private const val KV_HEADS = 8
        private const val HEAD_DIM = 128
        private const val ADAPTER_LAYERS = 6
        private const val ADAPTER_HEADS = 16
        private const val ADAPTER_HEAD_DIM = 64
        private const val EPSILON = 1e-6
        private const val ROPE_THETA = 1e6
        private const val ADAPTER_ROPE_THETA = 1e4
        private val QWEN_SUFFIXES = listOf(
            "in_norm", "q", "k", "v", "o", "qn", "kn", "post_norm", "gate", "up", "down",
        )
        private val ADAPTER_SUFFIXES = listOf(
            "n_self", "sa_q", "sa_k", "sa_v", "sa_o", "sa_qn", "sa_kn",
            "n_cross", "ca_q", "ca_k", "ca_v", "ca_o", "ca_qn", "ca_kn",
            "n_mlp", "fc1", "fc1b", "fc2", "fc2b",
        )

        private fun erf(x: Double): Double {
            val t = 1.0 / (1.0 + 0.5 * abs(x))
            val y = 1.0 - t * exp(
                -x * x - 1.26551223 + t * (
                    1.00002368 + t * (
                        0.37409196 + t * (
                            0.09678418 + t * (
                                -0.18628806 + t * (
                                    0.27886807 + t * (
                                        -1.13520398 + t * (
                                            1.48851587 + t * (-0.82215223 + t * 0.17087277)
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
            return if (x >= 0) y else -y
        }
    }
}

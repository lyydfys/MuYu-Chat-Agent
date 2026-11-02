package com.muyuchat.core.mediatek

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Conversion helpers for the reference VAE contract (`mca.mediatek-image/4`):
 * NHWC int16 with a per-tensor scale on the wire, NCDHW float in MCA.
 */
object MediaTekNpugVaeCodec {
    fun dequantizeInt16LittleEndian(bytes: ByteArray, scale: Double, zeroPoint: Int): FloatArray {
        require(scale.isFinite() && scale > 0.0) { "scale must be finite and positive" }
        require(zeroPoint in Short.MIN_VALUE..Short.MAX_VALUE) { "zeroPoint is outside int16" }
        require(bytes.size % 2 == 0) { "int16 byte count must be even" }
        val values = FloatArray(bytes.size / 2)
        for (index in values.indices) {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            val quantized = (high shl 8) or low
            values[index] = ((quantized - zeroPoint) * scale).toFloat()
        }
        return values
    }

    fun quantizeInt16LittleEndian(values: FloatArray, scale: Double, zeroPoint: Int): ByteArray {
        require(scale.isFinite() && scale > 0.0) { "scale must be finite and positive" }
        require(zeroPoint in Short.MIN_VALUE..Short.MAX_VALUE) { "zeroPoint is outside int16" }
        val bytes = ByteArray(values.size * 2)
        for (index in values.indices) {
            val value = values[index]
            require(value.isFinite()) { "cannot quantize a non-finite value at index $index" }
            val raw = value / scale + zeroPoint
            val rounded = if (raw >= 0.0) floor(raw + 0.5) else ceil(raw - 0.5)
            val quantized = rounded.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt()
            bytes[index * 2] = (quantized and 0xFF).toByte()
            bytes[index * 2 + 1] = ((quantized shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** [c][1][h][w] -> [h][w][c], used for the VAE input latent. */
    fun ncdhwDepthOneToNhwc(values: FloatArray, channels: Int, height: Int, width: Int): FloatArray {
        require(channels > 0 && height > 0 && width > 0) { "dimensions must be positive" }
        require(values.size == channels * height * width) {
            "expected ${channels * height * width} values, got ${values.size}"
        }
        val result = FloatArray(values.size)
        var out = 0
        for (h in 0 until height) {
            for (w in 0 until width) {
                for (c in 0 until channels) {
                    result[out++] = values[(c * height + h) * width + w]
                }
            }
        }
        return result
    }

    /**
     * NCHW float -> NHWC int16 at the VAE's own input scale. The chain's final
     * chunk and the VAE do not share a tensor scale, so this is an explicit
     * requantization rather than a byte copy.
     */
    fun nchwToNhwcInt16(
        values: FloatArray,
        channels: Int,
        height: Int,
        width: Int,
        scale: Double,
        zeroPoint: Int,
    ): ByteArray = quantizeInt16LittleEndian(
        ncdhwDepthOneToNhwc(values, channels, height, width),
        scale,
        zeroPoint,
    )

    /** [h][w][c] -> [c][1][h][w], used for the VAE RGB output. */
    fun nhwcToNcdhwDepthOne(values: FloatArray, height: Int, width: Int, channels: Int): FloatArray {
        require(channels > 0 && height > 0 && width > 0) { "dimensions must be positive" }
        require(values.size == channels * height * width) {
            "expected ${channels * height * width} values, got ${values.size}"
        }
        val result = FloatArray(values.size)
        for (c in 0 until channels) {
            for (h in 0 until height) {
                for (w in 0 until width) {
                    result[(c * height + h) * width + w] = values[(h * width + w) * channels + c]
                }
            }
        }
        return result
    }
}

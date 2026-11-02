package com.muyuchat.core.mediatek

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.max

/**
 * ATXW v1 text weights: a directory of FP16 tensors followed by a contiguous
 * data region. The published reference file stores Qwen3-0.6B and its adapter.
 */
class MediaTekAotTextWeights private constructor(
    val file: File,
    private val channel: FileChannel,
    private val data: ByteBuffer,
    val dataBase: Int,
    private val tensors: Map<String, Tensor>,
) : AutoCloseable {
    data class Tensor(val name: String, val dims: IntArray, val offset: Long) {
        val rowCount: Int = if (dims.size >= 2) dims[0] else 1
        val rowLength: Int = if (dims.size >= 2) dims[1] else dims[0]
        val elementCount: Long = dims.fold(1L) { acc, value -> Math.multiplyExact(acc, value.toLong()) }
        val byteLength: Long = Math.multiplyExact(elementCount, 2L)
    }

    val fileSize: Long = file.length()
    val tensorCount: Int get() = tensors.size
    val dataEnd: Long = tensors.values.maxOf { it.offset + it.byteLength } + dataBase

    fun tensor(name: String): Tensor = tensors[name]
        ?: throw IllegalArgumentException("ATXW tensor $name is missing")

    fun has(name: String): Boolean = tensors.containsKey(name)

    fun names(): Set<String> = tensors.keys

    fun vector(name: String): FloatArray {
        val tensor = tensor(name)
        require(tensor.dims.size == 1) { "ATXW tensor $name is not a vector" }
        val out = FloatArray(tensor.rowLength)
        fillRow(tensor, 0, out)
        return out
    }

    fun embedding(name: String, token: Int): FloatArray {
        val tensor = tensor(name)
        require(tensor.dims.size == 2) { "ATXW tensor $name is not an embedding" }
        require(token in 0 until tensor.rowCount) { "token $token is outside ${tensor.name}" }
        val out = FloatArray(tensor.rowLength)
        fillRow(tensor, token, out)
        return out
    }

    /** Computes `weight * x + bias` with the tensor's [out, in] row-major layout. */
    fun linear(x: FloatArray, name: String, bias: FloatArray? = null): FloatArray {
        val tensor = tensor(name)
        require(tensor.dims.size == 2) { "ATXW tensor $name is not a matrix" }
        require(x.size == tensor.rowLength) {
            "ATXW tensor $name expects ${tensor.rowLength} inputs, got ${x.size}"
        }
        if (bias != null) require(bias.size == tensor.rowCount) { "bias size mismatch for $name" }
        val out = FloatArray(tensor.rowCount)
        val scratch = ByteArray(tensor.rowLength * 2)
        val source = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (row in 0 until tensor.rowCount) {
            source.position((dataBase + tensor.offset + row.toLong() * tensor.rowLength * 2L).toInt())
            source.get(scratch)
            var sum = bias?.get(row) ?: 0f
            for (column in 0 until tensor.rowLength) {
                val bits = (scratch[column * 2].toInt() and 0xff) or
                    ((scratch[column * 2 + 1].toInt() and 0xff) shl 8)
                sum += x[column] * halfToFloat(bits)
            }
            out[row] = sum
        }
        return out
    }

    private fun fillRow(tensor: Tensor, row: Int, out: FloatArray) {
        require(row in 0 until tensor.rowCount) { "row $row is outside ${tensor.name}" }
        val source = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position((dataBase + tensor.offset + row.toLong() * tensor.rowLength * 2L).toInt())
        for (column in 0 until tensor.rowLength) {
            out[column] = halfToFloat(source.getShort().toInt() and 0xffff)
        }
    }

    override fun close() {
        channel.close()
    }

    companion object {
        const val MAGIC = 0x57585441
        const val VERSION = 1

        fun open(file: File): MediaTekAotTextWeights {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                val size = channel.size()
                require(size > 0x3f) { "ATXW file is too small" }
                require(size <= Int.MAX_VALUE.toLong()) { "ATXW file is too large to map" }
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                require(readInt(buffer, 0) == MAGIC) { "not an ATXW container" }
                require(readInt(buffer, 4) == VERSION) { "unsupported ATXW version ${readInt(buffer, 4)}" }
                val count = readInt(buffer, 8)
                require(count in 1..4096) { "invalid ATXW tensor count $count" }
                var offset = 12
                val tensors = LinkedHashMap<String, Tensor>(count)
                for (index in 0 until count) {
                    require(offset + 2 <= size) { "truncated ATXW directory" }
                    val nameLength = readShort(buffer, offset)
                    offset += 2
                    require(nameLength in 1..256) { "invalid ATXW tensor name length $nameLength" }
                    require(offset + nameLength + 1 <= size) { "truncated ATXW tensor name" }
                    val name = String(bytes(buffer, offset, nameLength), Charsets.UTF_8)
                    offset += nameLength
                    val rank = buffer.get(offset).toInt() and 0xff
                    offset += 1
                    require(rank in 1..3) { "invalid ATXW rank $rank for $name" }
                    val dims = IntArray(rank) { dimension ->
                        val value = readInt(buffer, offset)
                        offset += 4
                        require(value > 0) { "invalid ATXW dimension $value for $name" }
                        value
                    }
                    require(offset + 8 <= size) { "truncated ATXW tensor offset" }
                    val tensorOffset = readLong(buffer, offset)
                    offset += 8
                    require(tensorOffset >= 0) { "negative ATXW offset for $name" }
                    val tensor = Tensor(name, dims, tensorOffset)
                    require(tensors.put(name, tensor) == null) { "duplicate ATXW tensor $name" }
                }
                val dataBase = align64(offset)
                require(dataBase <= size) { "ATXW data base $dataBase exceeds file size $size" }
                for (tensor in tensors.values) {
                    require(tensor.offset + tensor.byteLength <= size - dataBase) {
                        "${tensor.name} exceeds the ATXW data region"
                    }
                }
                return MediaTekAotTextWeights(file, channel, buffer, dataBase, tensors)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        internal fun align64(value: Int): Int = (value + 63) and 63.inv()

        internal fun halfToFloat(bits: Int): Float {
            val sign = (bits ushr 15) and 1
            val exponent = (bits ushr 10) and 0x1f
            val mantissa = bits and 0x3ff
            val magnitude = when {
                exponent == 0 -> if (mantissa == 0) 0f else Math.scalb(mantissa / 1024f, -14)
                exponent == 31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> Math.scalb(1f + mantissa / 1024f, exponent - 15)
            }
            return if (sign == 1) -magnitude else magnitude
        }

        private fun readShort(buffer: ByteBuffer, offset: Int): Int =
            (buffer.get(offset).toInt() and 0xff) or ((buffer.get(offset + 1).toInt() and 0xff) shl 8)

        private fun readInt(buffer: ByteBuffer, offset: Int): Int =
            (buffer.get(offset).toInt() and 0xff) or
                ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                ((buffer.get(offset + 2).toInt() and 0xff) shl 16) or
                ((buffer.get(offset + 3).toInt() and 0xff) shl 24)

        private fun readLong(buffer: ByteBuffer, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((buffer.get(offset + index).toLong() and 0xff) shl (index * 8))
            }
            return value
        }

        private fun bytes(buffer: ByteBuffer, offset: Int, length: Int): ByteArray {
            val out = ByteArray(length)
            for (index in 0 until length) out[index] = buffer.get(offset + index)
            return out
        }
    }
}

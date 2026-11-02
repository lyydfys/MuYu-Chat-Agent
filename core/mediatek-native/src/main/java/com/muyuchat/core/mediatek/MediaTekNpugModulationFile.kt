package com.muyuchat.core.mediatek

/**
 * Reader for the reference AMOD modulation container.
 *
 * Header (little-endian): `"AMOD" | version u32 | steps u32 | dimension u32 | blocks u32`.
 * Payload: `steps * dimension * (blocks * 9 + 2)` signed int16 units,
 * step-major, then block-major segments, then the two final segments.
 *
 * The units are the graph's quantized values, not IEEE FP16: decoding the same
 * bytes as half precision yields NaN/denormals. Each route input's tensor scale
 * converts them to float inside the graph, so the bytes are copied unchanged
 * into the matching int16 input.
 */
class MediaTekNpugModulationFile private constructor(
    val stepCount: Int,
    val dimension: Int,
    val blockCount: Int,
    val blockSegments: Int,
    val finalSegments: Int,
    private val payload: ByteArray,
) {
    val segmentsPerStep: Int get() = blockCount * blockSegments + finalSegments
    val elementCount: Long get() = stepCount.toLong() * dimension * segmentsPerStep
    val segmentBytes: Int get() = dimension * 2

    fun segmentBytes(step: Int, block: Int, segment: Int): ByteArray {
        requireBlock(block)
        requireSegment(segment, blockSegments, "segment")
        return copy(offset(step, block * blockSegments + segment))
    }

    fun finalSegmentBytes(step: Int, segment: Int): ByteArray {
        requireSegment(segment, finalSegments, "final segment")
        return copy(offset(step, blockCount * blockSegments + segment))
    }

    fun segmentInt16(step: Int, block: Int, segment: Int): ShortArray {
        requireBlock(block)
        requireSegment(segment, blockSegments, "segment")
        val first = offset(step, block * blockSegments + segment)
        return ShortArray(dimension) { index -> readInt16(payload, first + index * 2) }
    }

    fun finalSegmentInt16(step: Int, segment: Int): ShortArray {
        requireSegment(segment, finalSegments, "final segment")
        val first = offset(step, blockCount * blockSegments + segment)
        return ShortArray(dimension) { index -> readInt16(payload, first + index * 2) }
    }

    private fun requireBlock(block: Int) {
        require(block in 0 until blockCount) { "block $block is outside 0..${blockCount - 1}" }
    }

    private fun requireSegment(segment: Int, count: Int, label: String) {
        require(segment in 0 until count) { "$label $segment is outside 0..${count - 1}" }
    }

    private fun offset(step: Int, segmentInStep: Int): Int {
        require(step in 0 until stepCount) { "step $step is outside 0..${stepCount - 1}" }
        require(segmentInStep in 0 until segmentsPerStep) {
            "segment $segmentInStep is outside 0..${segmentsPerStep - 1}"
        }
        return ((step * segmentsPerStep) + segmentInStep) * dimension * 2
    }

    private fun copy(firstByte: Int): ByteArray =
        payload.copyOfRange(firstByte, firstByte + segmentBytes)

    private fun readInt16(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8))).toShort()

    companion object {
        const val MAGIC = "AMOD"
        const val HEADER_BYTES = 20

        fun parse(
            bytes: ByteArray,
            blockSegments: Int = 9,
            finalSegments: Int = 2,
        ): MediaTekNpugModulationFile {
            require(bytes.size >= HEADER_BYTES) { "AMOD header is truncated" }
            require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "AMOD magic is invalid" }
            val version = readU32(bytes, 4)
            require(version == 1) { "AMOD version $version is unsupported" }
            val steps = readU32(bytes, 8)
            val dimension = readU32(bytes, 12)
            val blocks = readU32(bytes, 16)
            require(steps in 1..64) { "AMOD steps $steps is outside 1..64" }
            require(dimension in 1..65536) { "AMOD dimension $dimension is outside 1..65536" }
            require(blocks in 1..1024) { "AMOD blocks $blocks is outside 1..1024" }
            require(blockSegments in 1..1024) { "AMOD blockSegments $blockSegments is outside 1..1024" }
            require(finalSegments in 0..1024) { "AMOD finalSegments $finalSegments is outside 0..1024" }
            val elements = steps.toLong() * dimension *
                (blocks.toLong() * blockSegments + finalSegments)
            val expected = HEADER_BYTES + elements * 2L
            require(expected == bytes.size.toLong()) {
                "AMOD payload is ${bytes.size - HEADER_BYTES} B; expected ${elements * 2L} B"
            }
            return MediaTekNpugModulationFile(
                steps,
                dimension,
                blocks,
                blockSegments,
                finalSegments,
                bytes.copyOfRange(HEADER_BYTES, bytes.size),
            )
        }

        private fun readU32(bytes: ByteArray, offset: Int): Int {
            val value = (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
            require(value <= Int.MAX_VALUE) { "AMOD header value at $offset is out of range" }
            return value.toInt()
        }
    }
}

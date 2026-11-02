package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugModulationFileTest {
    @Test
    fun readsStepMajorBlockSegmentsAndFinalSegmentsAsRawInt16Units() {
        val file = MediaTekNpugModulationFile.parse(amod(steps = 2, dimension = 4, blocks = 3))

        assertEquals(2, file.stepCount)
        assertEquals(4, file.dimension)
        assertEquals(3, file.blockCount)
        assertEquals(29, file.segmentsPerStep)
        assertEquals(8, file.segmentBytes)

        assertEquals(listOf<Short>(0, 1, 2, 3), file.segmentInt16(step = 0, block = 0, segment = 0).toList())
        assertEquals(
            listOf<Short>(104, 105, 106, 107),
            file.segmentInt16(step = 0, block = 2, segment = 8).toList(),
        )
        assertEquals(
            listOf<Short>(108, 109, 110, 111),
            file.finalSegmentInt16(step = 0, segment = 0).toList(),
        )
        assertEquals(
            listOf<Short>(116, 117, 118, 119),
            file.segmentInt16(step = 1, block = 0, segment = 0).toList(),
        )

        val raw = file.segmentBytes(step = 0, block = 0, segment = 0)
        assertEquals(listOf<Byte>(0, 0, 1, 0, 2, 0, 3, 0), raw.toList())
        assertEquals(8, file.finalSegmentBytes(step = 1, segment = 1).size)
    }

    @Test
    fun keepsLittleEndianSignedValuesWithoutRescaling() {
        val file = MediaTekNpugModulationFile.parse(
            amod(steps = 1, dimension = 4, blocks = 1, values = shortArrayOf(-2, 32767, -32768, 0)),
        )

        assertEquals(listOf<Short>(-2, 32767, -32768, 0), file.segmentInt16(0, 0, 0).toList())
        val raw = file.segmentBytes(0, 0, 0)
        assertEquals(0xFE.toByte(), raw[0])
        assertEquals(0xFF.toByte(), raw[1])
        assertEquals(0xFF.toByte(), raw[2])
        assertEquals(0x7F.toByte(), raw[3])
    }

    @Test
    fun rejectsMalformedHeadersAndPayloadSizes() {
        val valid = amod(steps = 1, dimension = 2, blocks = 1)
        assertRejected("magic") { MediaTekNpugModulationFile.parse(valid.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertRejected("version") {
            MediaTekNpugModulationFile.parse(valid.copyOf().also { it[4] = 2 })
        }
        assertRejected("payload") { MediaTekNpugModulationFile.parse(valid.copyOf(valid.size - 2)) }
        assertRejected("truncated") { MediaTekNpugModulationFile.parse(ByteArray(8)) }
    }

    @Test
    fun segmentIndexesAreBoundsChecked() {
        val file = MediaTekNpugModulationFile.parse(amod(steps = 1, dimension = 2, blocks = 1))
        assertRejected("step 1") { file.segmentInt16(1, 0, 0) }
        assertRejected("block 1") { file.segmentInt16(0, 1, 0) }
        assertRejected("segment 9") { file.segmentInt16(0, 0, 9) }
    }

    private fun amod(
        steps: Int,
        dimension: Int,
        blocks: Int,
        values: ShortArray? = null,
    ): ByteArray {
        val segments = blocks * 9 + 2
        val bytes = ByteArray(20 + steps * dimension * segments * 2)
        bytes[0] = 'A'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes[2] = 'O'.code.toByte()
        bytes[3] = 'D'.code.toByte()
        writeU32(bytes, 4, 1)
        writeU32(bytes, 8, steps)
        writeU32(bytes, 12, dimension)
        writeU32(bytes, 16, blocks)
        var index = 0
        for (step in 0 until steps) {
            for (segment in 0 until segments) {
                for (element in 0 until dimension) {
                    val value = values?.getOrNull(index) ?: index.toShort()
                    val offset = 20 + index * 2
                    bytes[offset] = (value.toInt() and 0xFF).toByte()
                    bytes[offset + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
                    index++
                }
            }
        }
        return bytes
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun assertRejected(expected: String, action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("expected '${error.message}' to contain '$expected'", error.message.orEmpty().contains(expected))
            return
        }
        throw AssertionError("expected an IllegalArgumentException containing '$expected'")
    }
}

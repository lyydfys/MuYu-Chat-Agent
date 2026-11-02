package com.muyuchat.mca.video.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Synthetic MP4 boxes, because the defect only appears in a MediaMuxer file with exactly one
 * sample: `stts` records delta 0 and every duration ends up 0.
 */
class Mp4DurationRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, out.size.toLong())
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun payload(size: Int, vararg fields: Pair<Int, Long>): ByteArray {
        val p = ByteArray(size)
        for ((at, value) in fields) writeU32(p, at, value)
        return p
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = ((value ushr 24) and 0xff).toByte()
        bytes[at + 1] = ((value ushr 16) and 0xff).toByte()
        bytes[at + 2] = ((value ushr 8) and 0xff).toByte()
        bytes[at + 3] = (value and 0xff).toByte()
    }

    private fun u32At(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)

    private fun find(bytes: ByteArray, type: String): Int {
        val marker = type.toByteArray(Charsets.US_ASCII)
        for (i in 4..bytes.size - marker.size) {
            if ((0 until marker.size).all { bytes[i + it] == marker[it] }) return i - 4
        }
        throw AssertionError("box $type not found")
    }

    private fun singleSampleMovie(sttsEntries: List<Pair<Long, Long>>): ByteArray {
        val sttsPayload = ByteArray(8 + sttsEntries.size * 8).also {
            writeU32(it, 4, sttsEntries.size.toLong())
            sttsEntries.forEachIndexed { i, (count, delta) ->
                writeU32(it, 8 + i * 8, count)
                writeU32(it, 12 + i * 8, delta)
            }
        }
        val mvhd = box("mvhd", payload(100, 8 to 10_000L))
        val tkhd = box("tkhd", payload(84, 16 to 0L))
        val mdhd = box("mdhd", payload(24, 12 to 90_000L, 16 to 0L))
        val stts = box("stts", sttsPayload)
        val stbl = box("stbl", stts)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + minf)
        val trak = box("trak", tkhd + mdia)
        val moov = box("moov", mvhd + trak)
        return box("ftyp", ByteArray(8)) + moov + box("mdat", ByteArray(16))
    }

    @Test
    fun `a single zero-delta sample gets the frame duration`() {
        val file = tmp.newFile("one.mp4")
        file.writeBytes(singleSampleMovie(listOf(1L to 0L)))

        assertTrue(Mp4DurationRepair.repairSingleSampleDuration(file, 24L))

        val bytes = file.readBytes()
        val mdhd = find(bytes, "mdhd")
        val tkhd = find(bytes, "tkhd")
        val mvhd = find(bytes, "mvhd")
        val stts = find(bytes, "stts")
        assertEquals(3_750, u32At(bytes, stts + 8 + 12)) // 90_000 / 24
        assertEquals(3_750, u32At(bytes, mdhd + 8 + 16))
        assertEquals(417, u32At(bytes, tkhd + 8 + 16)) // 3_750 * 10_000 / 90_000
        assertEquals(417, u32At(bytes, mvhd + 8 + 12))
    }

    @Test
    fun `a clip with real sample deltas is left byte-identical`() {
        val original = singleSampleMovie(listOf(1L to 3_750L))
        val file = tmp.newFile("many.mp4")
        file.writeBytes(original)

        assertFalse(Mp4DurationRepair.repairSingleSampleDuration(file, 24L))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `a file without a movie box is left alone`() {
        val file = tmp.newFile("empty.mp4")
        file.writeBytes(box("ftyp", ByteArray(8)))
        assertFalse(Mp4DurationRepair.repairSingleSampleDuration(file, 24L))
    }
}

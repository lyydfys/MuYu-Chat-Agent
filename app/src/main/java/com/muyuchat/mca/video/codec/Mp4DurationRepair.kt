package com.muyuchat.mca.video.codec

import java.io.File

/**
 * MediaMuxer writes `stts` with one entry whose delta is 0 when a clip has exactly one sample, so
 * `mdhd`/`tkhd`/`mvhd` all carry duration 0 and ffprobe reports `duration=0`. The sample itself is
 * valid, only its duration is missing, so the container metadata is repaired here instead of
 * duplicating a frame (which would change the frame count users see).
 */
internal object Mp4DurationRepair {

    private val CONTAINER = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    private class Box(val type: String, val start: Int, val payloadStart: Int, val end: Int)

    /** Returns true when the file described a single zero-delta sample and was patched. */
    fun repairSingleSampleDuration(file: File, fps: Long): Boolean {
        if (fps <= 0L || !file.isFile || file.length() <= 0L) return false
        val bytes = file.readBytes()
        val moov = children(bytes, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return false
        val trak = children(bytes, moov.payloadStart, moov.end).firstOrNull { it.type == "trak" } ?: return false
        val mdia = children(bytes, trak.payloadStart, trak.end).firstOrNull { it.type == "mdia" } ?: return false
        val minf = children(bytes, mdia.payloadStart, mdia.end).firstOrNull { it.type == "minf" } ?: return false
        val stbl = children(bytes, minf.payloadStart, minf.end).firstOrNull { it.type == "stbl" } ?: return false
        val stts = children(bytes, stbl.payloadStart, stbl.end).firstOrNull { it.type == "stts" } ?: return false
        val mdhd = children(bytes, mdia.payloadStart, mdia.end).firstOrNull { it.type == "mdhd" } ?: return false
        val tkhd = children(bytes, trak.payloadStart, trak.end).firstOrNull { it.type == "tkhd" } ?: return false
        val mvhd = children(bytes, moov.payloadStart, moov.end).firstOrNull { it.type == "mvhd" } ?: return false

        // Version 1 boxes use 64-bit times; not produced by MediaMuxer, so leave them untouched.
        if (bytes[stts.payloadStart].toInt() != 0 || bytes[mdhd.payloadStart].toInt() != 0) return false
        if (bytes[mvhd.payloadStart].toInt() != 0 || bytes[tkhd.payloadStart].toInt() != 0) return false
        if (u32(bytes, stts.payloadStart + 4) != 1L) return false
        val entry = stts.payloadStart + 8
        if (u32(bytes, entry) != 1L || u32(bytes, entry + 4) != 0L) return false

        val trackTimescale = u32(bytes, mdhd.payloadStart + 12)
        if (trackTimescale <= 0L) return false
        val ticks = maxOf(1L, Math.round(trackTimescale.toDouble() / fps.toDouble()))
        val movieTimescale = maxOf(1L, u32(bytes, mvhd.payloadStart + 8))
        val movieTicks = maxOf(1L, Math.round(ticks.toDouble() * movieTimescale.toDouble() / trackTimescale.toDouble()))

        writeU32(bytes, entry + 4, ticks)
        writeU32(bytes, mdhd.payloadStart + 16, ticks)
        writeU32(bytes, tkhd.payloadStart + 16, movieTicks)
        writeU32(bytes, mvhd.payloadStart + 12, movieTicks)
        file.writeBytes(bytes)
        return true
    }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var off = from
        while (off + 8 <= to) {
            var size = u32(bytes, off)
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                if (off + 16 > to) break
                size = ((u32(bytes, off + 8) shl 32) or u32(bytes, off + 12))
                header = 16
            }
            if (size == 0L) size = (to - off).toLong()
            if (size < header || off + size > to) break
            out.add(Box(type, off, off + header, (off + size).toInt()))
            if (type in CONTAINER) out.addAll(children(bytes, off + header, (off + size).toInt()))
            off += size.toInt()
        }
        return out
    }

    private fun u32(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) return 0L
        return ((bytes[at].toLong() and 0xff) shl 24) or
            ((bytes[at + 1].toLong() and 0xff) shl 16) or
            ((bytes[at + 2].toLong() and 0xff) shl 8) or
            (bytes[at + 3].toLong() and 0xff)
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Long) {
        if (at < 0 || at + 4 > bytes.size) return
        bytes[at] = ((value ushr 24) and 0xff).toByte()
        bytes[at + 1] = ((value ushr 16) and 0xff).toByte()
        bytes[at + 2] = ((value ushr 8) and 0xff).toByte()
        bytes[at + 3] = (value and 0xff).toByte()
    }
}

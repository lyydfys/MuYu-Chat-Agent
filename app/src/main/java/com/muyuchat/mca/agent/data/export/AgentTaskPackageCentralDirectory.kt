package com.muyuchat.mca.agent.data.export

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Raw central-directory pre-scan for task packages.
 *
 * This is the security gate before any decompression: it bounds entry count, archive size, per-entry
 * and total uncompressed sizes, compression ratio, method and encryption flags, Unix special-file
 * modes (symlink) and duplicate normalized paths. ZipInputStream extraction then re-verifies CRC and
 * hashes.
 */
internal object AgentTaskPackageCentralDirectory {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L

    fun scan(archive: File, limits: AgentTaskPackageLimits): List<AgentPackageZipEntry> {
        if (!archive.isFile) throw fail(AgentPackageErrorCode.IO_FAILURE, "archive is missing")
        val fileSize = archive.length()
        if (fileSize <= 0L) throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "archive is empty")
        if (fileSize > limits.maxArchiveBytes) {
            throw fail(AgentPackageErrorCode.PACKAGE_TOO_LARGE, "archive exceeds the size limit")
        }
        val tailLength = minOf(fileSize, 22L + 65_535L).toInt()
        val tail = ByteArray(tailLength)
        RandomAccessFile(archive, "r").use { random ->
            random.seek(fileSize - tailLength)
            random.readFully(tail)
        }
        var eocd = -1
        for (index in tailLength - 22 downTo 0) {
            if (readLe32(tail, index) == EOCD_SIGNATURE) {
                eocd = index
                break
            }
        }
        if (eocd < 0) throw fail(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "end of central directory not found")
        val diskNumber = readLe16(tail, eocd + 4)
        val centralDisk = readLe16(tail, eocd + 6)
        val entriesOnDisk = readLe16(tail, eocd + 8)
        val totalEntries = readLe16(tail, eocd + 10)
        val centralSize = readLe32(tail, eocd + 12)
        val centralOffset = readLe32(tail, eocd + 16)
        val commentLength = readLe16(tail, eocd + 20)
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
            throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "multi-disk archives are not supported")
        }
        if (totalEntries == 0xffff || centralSize == 0xffff_ffffL || centralOffset == 0xffff_ffffL) {
            throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "ZIP64 archives are not supported")
        }
        val eocdFileOffset = fileSize - tailLength + eocd
        if (eocdFileOffset + 22L + commentLength != fileSize) {
            throw fail(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "archive has trailing or missing data")
        }
        if (totalEntries > limits.maxEntries) {
            throw fail(AgentPackageErrorCode.TOO_MANY_ENTRIES, "archive has too many entries")
        }
        if (centralSize > 4L * 1024 * 1024) {
            throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "central directory is implausibly large")
        }
        if (centralOffset + centralSize > eocdFileOffset) {
            throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "central directory is out of bounds")
        }
        val central = ByteArray(centralSize.toInt())
        RandomAccessFile(archive, "r").use { random ->
            random.seek(centralOffset)
            random.readFully(central)
        }
        val result = ArrayList<AgentPackageZipEntry>(totalEntries)
        val seen = HashSet<String>(totalEntries)
        var cursor = 0
        var totalExpanded = 0L
        repeat(totalEntries) {
            if (cursor + 46 > central.size) {
                throw fail(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "central directory is truncated")
            }
            if (readLe32(central, cursor) != CENTRAL_SIGNATURE) {
                throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "central directory signature is invalid")
            }
            val flags = readLe16(central, cursor + 8)
            val method = readLe16(central, cursor + 10)
            val compressed = readLe32(central, cursor + 20)
            val uncompressed = readLe32(central, cursor + 24)
            val nameLength = readLe16(central, cursor + 28)
            val extraLength = readLe16(central, cursor + 30)
            val commentLength2 = readLe16(central, cursor + 32)
            val diskStart = readLe16(central, cursor + 34)
            val externalAttributes = readLe32(central, cursor + 38)
            val nameStart = cursor + 46
            val nameEnd = nameStart + nameLength
            if (nameEnd > central.size || nameEnd + extraLength + commentLength2 > central.size) {
                throw fail(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "central directory entry is truncated")
            }
            if (compressed == 0xffff_ffffL || uncompressed == 0xffff_ffffL) {
                throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "ZIP64 entries are not supported")
            }
            if (diskStart != 0) throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "entry is on another disk")
            if (flags and 0x0001 != 0 || flags and 0x0040 != 0) {
                throw fail(AgentPackageErrorCode.ENCRYPTED_ENTRY, "encrypted entries are not supported")
            }
            if (method != 0 && method != 8) {
                throw fail(AgentPackageErrorCode.UNSUPPORTED_COMPRESSION, "unsupported compression method")
            }
            rejectSpecialFileMode(externalAttributes)
            val nameBytes = central.copyOfRange(nameStart, nameEnd)
            val name = decodeEntryName(nameBytes, flags)
            if (name.endsWith('/')) {
                throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "directory entries are not allowed")
            }
            val safeName = AgentPackagePathGuard.validateArchiveEntry(name, limits)
                ?: throw fail(AgentPackageErrorCode.UNSAFE_PATH, "archive entry path is unsafe")
            if (!seen.add(AgentPackagePathGuard.normalizeForComparison(safeName))) {
                throw fail(AgentPackageErrorCode.DUPLICATE_ENTRY, "duplicate normalized archive entry")
            }
            if (uncompressed > limits.maxEntryBytes) {
                throw fail(AgentPackageErrorCode.ENTRY_TOO_LARGE, "archive entry is too large")
            }
            if (compressed == 0L && uncompressed > 0L) {
                throw fail(AgentPackageErrorCode.ENTRY_RATIO_EXCEEDED, "archive entry ratio is invalid")
            }
            if (compressed > 0L && (
                    uncompressed / compressed > limits.maxCompressionRatio ||
                        (uncompressed / compressed == limits.maxCompressionRatio.toLong() &&
                            uncompressed % compressed != 0L)
                    )
            ) {
                throw fail(AgentPackageErrorCode.ENTRY_RATIO_EXCEEDED, "archive entry ratio exceeds the limit")
            }
            if (uncompressed > limits.maxTotalExpandedBytes - totalExpanded) {
                throw fail(AgentPackageErrorCode.PACKAGE_TOO_LARGE, "expanded package exceeds the limit")
            }
            totalExpanded += uncompressed
            result += AgentPackageZipEntry(
                name = safeName,
                compressedSize = compressed,
                uncompressedSize = uncompressed
            )
            cursor = nameEnd + extraLength + commentLength2
        }
        if (result.none { it.name == AgentTaskPackageFormat.MANIFEST_ENTRY }) {
            throw fail(AgentPackageErrorCode.MISSING_MANIFEST, "manifest.json is missing")
        }
        return result
    }

    private fun rejectSpecialFileMode(externalAttributes: Long) {
        val mode = ((externalAttributes ushr 16) and 0xffffL).toInt()
        if (mode == 0) return
        when (mode and 0xf000) {
            0, 0x8000 -> Unit
            0xa000 -> throw fail(AgentPackageErrorCode.SYMLINK_ENTRY, "symlink entries are not allowed")
            else -> throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "special file entries are not allowed")
        }
    }

    private fun decodeEntryName(bytes: ByteArray, flags: Int): String {
        if (flags and 0x0800 != 0) {
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: Exception) {
                throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "entry name is not valid UTF-8")
            }
        }
        if (bytes.any { (it.toInt() and 0xff) > 0x7f }) {
            throw fail(AgentPackageErrorCode.INVALID_ARCHIVE, "non-UTF-8 entry names are not supported")
        }
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun fail(code: AgentPackageErrorCode, message: String): AgentPackageException =
        AgentPackageException(code, message)
}

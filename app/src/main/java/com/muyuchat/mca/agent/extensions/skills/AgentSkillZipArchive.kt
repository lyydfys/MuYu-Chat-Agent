package com.muyuchat.mca.agent.extensions.skills

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Random-access byte source so ZIP central-directory validation never trusts streaming headers. */
internal interface AgentSkillRandomAccessSource : Closeable {
    val size: Long

    fun read(offset: Long, length: Int): ByteArray
}

internal class ByteArrayRandomAccessSource(private val bytes: ByteArray) : AgentSkillRandomAccessSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw IOException("read outside the package")
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun close() = Unit
}

internal class FileRandomAccessSource(private val file: File) : AgentSkillRandomAccessSource {
    private val handle = RandomAccessFile(file, "r")

    override val size: Long get() = handle.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw IOException("read outside the package")
        }
        val result = ByteArray(length)
        handle.seek(offset)
        handle.readFully(result)
        return result
    }

    override fun close() = handle.close()
}

internal data class AgentSkillZipEntry(
    val path: String,
    val isDirectory: Boolean,
    val method: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val unixMode: Int?
)

/**
 * Minimal, defensive ZIP reader for Skill packages.
 *
 * It parses the central directory itself (instead of trusting `ZipInputStream` local headers) so it
 * can reject encrypted entries, unknown compression, Unix symlinks/devices, ZIP64, duplicate
 * normalized paths and per-entry compression bombs before any output is copied.
 */
internal class AgentSkillZipArchive(
    private val source: AgentSkillRandomAccessSource
) : Closeable {
    private var parsed: List<AgentSkillZipEntry>? = null
    private var centralDirectoryOffset: Long = 0L

    fun entries(): List<AgentSkillZipEntry> {
        parsed?.let { return it }
        val result = parseCentralDirectory()
        parsed = result
        return result
    }

    fun openStream(entry: AgentSkillZipEntry): InputStream {
        val header = source.read(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if ((buffer.int.toLong() and 0xFFFF_FFFFL) != LOCAL_SIGNATURE) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "missing local file header", entry.path)
        }
        buffer.short // version needed
        val flags = buffer.short.toInt() and 0xFFFF
        val method = buffer.short.toInt() and 0xFFFF
        if (flags and 0x41 != 0) {
            skillFailure(AgentSkillErrorCode.ENCRYPTED_ENTRY, "encrypted entries are not allowed", entry.path)
        }
        if (method != entry.method) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "local/central compression mismatch", entry.path)
        }
        buffer.short // modification time
        buffer.short // modification date
        buffer.int // crc32
        buffer.int // compressed size (central directory is authoritative)
        buffer.int // uncompressed size
        val nameLength = buffer.short.toInt() and 0xFFFF
        val extraLength = buffer.short.toInt() and 0xFFFF
        val nameBytes = source.read(entry.localHeaderOffset + LOCAL_HEADER_SIZE, nameLength)
        val localName = decodeName(nameBytes, entry.path)
        val normalizedLocal = if (entry.isDirectory) {
            AgentSkillPathPolicy.normalizeDirectory(localName, entry.path)
        } else {
            AgentSkillPathPolicy.normalizeFile(localName, entry.path)
        }
        if (normalizedLocal != entry.path) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "local entry name differs from central directory", entry.path)
        }
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
        if (dataStart < 0 || dataStart + entry.compressedSize > source.size ||
            dataStart + entry.compressedSize > centralDirectoryOffset
        ) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "entry data lies outside the package", entry.path)
        }
        val bounded = BoundedSourceStream(source, dataStart, entry.compressedSize)
        return when (entry.method) {
            STORED_METHOD -> bounded
            DEFLATED_METHOD -> InflaterInputStream(bounded, Inflater(true), INFLATE_BUFFER)
            else -> skillFailure(
                AgentSkillErrorCode.UNSUPPORTED_COMPRESSION,
                "unsupported compression method ${entry.method}",
                entry.path
            )
        }
    }

    override fun close() = source.close()

    private fun parseCentralDirectory(): List<AgentSkillZipEntry> {
        if (source.size < EOCD_MIN_SIZE) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "file is too small to be a ZIP package")
        }
        val tailLength = minOf(source.size, (EOCD_MIN_SIZE + MAX_COMMENT).toLong()).toInt()
        val tailStart = source.size - tailLength
        val tail = source.read(tailStart, tailLength)
        var eocdIndex = -1
        for (index in tailLength - EOCD_MIN_SIZE downTo 0) {
            if (readU32(tail, index) == EOCD_SIGNATURE) {
                eocdIndex = index
                break
            }
        }
        if (eocdIndex < 0) skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "missing ZIP end-of-central-directory record")
        val eocd = ByteBuffer.wrap(tail, eocdIndex, EOCD_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        eocd.int
        val diskNumber = eocd.short.toInt() and 0xFFFF
        val centralDisk = eocd.short.toInt() and 0xFFFF
        val entriesOnDisk = eocd.short.toInt() and 0xFFFF
        val totalEntries = eocd.short.toInt() and 0xFFFF
        val centralSize = eocd.int.toLong() and 0xFFFF_FFFFL
        val centralOffset = eocd.int.toLong() and 0xFFFF_FFFFL
        val commentLength = eocd.short.toInt() and 0xFFFF
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "multi-disk ZIP packages are not supported")
        }
        if (totalEntries == 0xFFFF || centralSize == 0xFFFF_FFFFL || centralOffset == 0xFFFF_FFFFL) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "ZIP64 packages are not supported")
        }
        if (totalEntries > AgentSkillLimits.MAX_ENTRIES) {
            skillFailure(
                AgentSkillErrorCode.ENTRY_COUNT_EXCEEDED,
                "package has more than ${AgentSkillLimits.MAX_ENTRIES} entries"
            )
        }
        val eocdPosition = tailStart + eocdIndex
        if (commentLength != tailLength - eocdIndex - EOCD_MIN_SIZE) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "ZIP comment length is inconsistent")
        }
        if (centralOffset + centralSize > eocdPosition) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "central directory lies outside the package")
        }
        centralDirectoryOffset = centralOffset

        val entries = ArrayList<AgentSkillZipEntry>(totalEntries)
        val seenPaths = LinkedHashSet<String>()
        var expandedTotal = 0L
        var position = centralOffset
        repeat(totalEntries) {
            val fixed = source.read(position, CENTRAL_HEADER_SIZE)
            val buffer = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN)
            if ((buffer.int.toLong() and 0xFFFF_FFFFL) != CENTRAL_SIGNATURE) {
                skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "bad central directory entry")
            }
            val versionMadeBy = buffer.short.toInt() and 0xFFFF
            buffer.short // version needed
            val flags = buffer.short.toInt() and 0xFFFF
            val method = buffer.short.toInt() and 0xFFFF
            buffer.short // time
            buffer.short // date
            buffer.int // crc
            val compressedSize = buffer.int.toLong() and 0xFFFF_FFFFL
            val uncompressedSize = buffer.int.toLong() and 0xFFFF_FFFFL
            val nameLength = buffer.short.toInt() and 0xFFFF
            val extraLength = buffer.short.toInt() and 0xFFFF
            val commentSize = buffer.short.toInt() and 0xFFFF
            val diskStart = buffer.short.toInt() and 0xFFFF
            buffer.short // internal attributes
            val externalAttributes = buffer.int.toLong() and 0xFFFF_FFFFL
            val localOffset = buffer.int.toLong() and 0xFFFF_FFFFL
            val next = position + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentSize
            if (next > centralOffset + centralSize) {
                skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "central directory entry is truncated")
            }
            val nameBytes = source.read(position + CENTRAL_HEADER_SIZE, nameLength)
            val rawName = decodeName(nameBytes, null)
            position = next
            if (diskStart != 0) skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "multi-disk entry is not supported")
            if (flags and 0x41 != 0) {
                skillFailure(AgentSkillErrorCode.ENCRYPTED_ENTRY, "encrypted entries are not allowed", rawName)
            }
            if (flags and 0x2000 != 0) {
                skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "masked local headers are not supported", rawName)
            }
            if (method != STORED_METHOD && method != DEFLATED_METHOD) {
                skillFailure(
                    AgentSkillErrorCode.UNSUPPORTED_COMPRESSION,
                    "unsupported compression method $method",
                    rawName
                )
            }
            if (compressedSize == 0xFFFF_FFFFL || uncompressedSize == 0xFFFF_FFFFL ||
                localOffset == 0xFFFF_FFFFL
            ) {
                skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "ZIP64 entries are not supported", rawName)
            }
            val unixHost = (versionMadeBy ushr 8) and 0xFF
            val unixMode = if (unixHost == UNIX_HOST) ((externalAttributes ushr 16) and 0xFFFFL).toInt() else null
            val unixType = unixMode?.and(0xF000)
            if (unixType == UNIX_SYMLINK) {
                skillFailure(AgentSkillErrorCode.SYMLINK_REJECTED, "symlink entries are not allowed", rawName)
            }
            if (unixMode != null && unixType != 0 && unixType != UNIX_REGULAR &&
                unixType != UNIX_DIRECTORY
            ) {
                skillFailure(AgentSkillErrorCode.NON_REGULAR_FILE, "only regular files are allowed", rawName)
            }
            val directory = rawName.endsWith("/") || unixType == UNIX_DIRECTORY
            val path = if (directory) {
                AgentSkillPathPolicy.normalizeDirectory(rawName, rawName)
            } else {
                AgentSkillPathPolicy.normalizeFile(rawName, rawName)
            }
            if (!seenPaths.add(path)) {
                skillFailure(AgentSkillErrorCode.DUPLICATE_PATH, "duplicate normalized path: '$path'", rawName)
            }
            if (directory) {
                if (uncompressedSize != 0L || compressedSize != 0L) {
                    skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "directory entry carries content", rawName)
                }
            } else {
                if (uncompressedSize > AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES) {
                    skillFailure(
                        AgentSkillErrorCode.ENTRY_SIZE_EXCEEDED,
                        "entry exceeds ${AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES} bytes",
                        rawName
                    )
                }
                expandedTotal += uncompressedSize
                if (expandedTotal > AgentSkillLimits.MAX_EXPANDED_BYTES) {
                    skillFailure(
                        AgentSkillErrorCode.EXPANDED_SIZE_EXCEEDED,
                        "expanded package exceeds ${AgentSkillLimits.MAX_EXPANDED_BYTES} bytes",
                        rawName
                    )
                }
                if (method == STORED_METHOD && compressedSize != uncompressedSize) {
                    skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "stored entry has inconsistent sizes", rawName)
                }
                if (uncompressedSize > 0L) {
                    if (compressedSize == 0L ||
                        uncompressedSize / maxOf(1L, compressedSize) > AgentSkillLimits.MAX_COMPRESSION_RATIO
                    ) {
                        skillFailure(
                            AgentSkillErrorCode.COMPRESSION_RATIO_EXCEEDED,
                            "entry exceeds the ${AgentSkillLimits.MAX_COMPRESSION_RATIO}:1 compression ratio",
                            rawName
                        )
                    }
                }
            }
            entries += AgentSkillZipEntry(
                path = path,
                isDirectory = directory,
                method = method,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localOffset,
                unixMode = unixMode
            )
        }
        rejectPrefixConflicts(entries)
        return entries
    }

    private fun rejectPrefixConflicts(entries: List<AgentSkillZipEntry>) {
        val files = entries.filterNot { it.isDirectory }.map { it.path }.toHashSet()
        for (entry in entries) {
            var prefix = entry.path
            while (true) {
                if (prefix in files && prefix != entry.path) {
                    skillFailure(
                        AgentSkillErrorCode.PATH_CONFLICT,
                        "a file path is a prefix of another entry",
                        entry.path
                    )
                }
                val slash = prefix.lastIndexOf('/')
                if (slash <= 0) break
                prefix = prefix.substring(0, slash)
            }
        }
    }

    private fun decodeName(bytes: ByteArray, entryPath: String?): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            skillFailure(AgentSkillErrorCode.MALFORMED_ZIP, "entry name is not valid UTF-8", entryPath)
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    private companion object {
        const val EOCD_SIGNATURE = 0x06054B50L
        const val CENTRAL_SIGNATURE = 0x02014B50L
        const val LOCAL_SIGNATURE = 0x04034B50L
        const val EOCD_MIN_SIZE = 22
        const val MAX_COMMENT = 0xFFFF
        const val CENTRAL_HEADER_SIZE = 46
        const val LOCAL_HEADER_SIZE = 30
        const val STORED_METHOD = 0
        const val DEFLATED_METHOD = 8
        const val INFLATE_BUFFER = 8 * 1024
        const val UNIX_SYMLINK = 0xA000
        const val UNIX_REGULAR = 0x8000
        const val UNIX_DIRECTORY = 0x4000
        const val UNIX_HOST = 3
    }
}

private class BoundedSourceStream(
    private val source: AgentSkillRandomAccessSource,
    private val start: Long,
    private val length: Long
) : InputStream() {
    private var position = 0L
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (closed) throw IOException("stream closed")
        if (count == 0) return 0
        val remaining = length - position
        if (remaining <= 0L) return -1
        val toRead = minOf(count.toLong(), remaining).toInt()
        val bytes = source.read(start + position, toRead)
        System.arraycopy(bytes, 0, buffer, offset, toRead)
        position += toRead
        return toRead
    }

    override fun available(): Int = (length - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        closed = true
    }
}

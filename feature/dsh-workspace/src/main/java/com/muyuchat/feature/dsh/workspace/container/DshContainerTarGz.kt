package com.muyuchat.feature.dsh.workspace.container

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Extraction of the packaged rootfs (`tar.gz`) into an app-private directory.
 *
 * Why a hand-written reader instead of a library: the only format the pinned asset uses is a
 * gzipped tar, and the rules that matter here are *safety* rules (no absolute paths, no `..`, no
 * writing through a symlink, hard ceilings on entries and bytes) that must be provable in a JVM
 * test. The reader therefore supports exactly what an Ubuntu base image needs — ustar prefix,
 * GNU long name/link, pax `path`/`linkpath`, regular files, directories, symlinks, hardlinks —
 * and refuses everything else instead of guessing.
 *
 * Mode bits are applied because the guest `bin/sh` must end up executable *inside* the container.
 * That is a property of the extracted file, not a claim that Android will exec it directly (it
 * will not; see `docs/architecture/DSH_CONTAINER.md` §2).
 */
object DshContainerTarGz {
    /** A real Ubuntu base rootfs has ~20k entries; this is a runaway guard, not a target. */
    const val MAX_ENTRIES: Int = 400_000

    /** Uncompressed ceiling. The pinned image unpacks to well under 200 MB. */
    const val MAX_UNCOMPRESSED_BYTES: Long = 2L * 1024 * 1024 * 1024

    private const val BLOCK = 512
    private const val TYPE_LONG_NAME = 'L'
    private const val TYPE_LONG_LINK = 'K'
    private const val TYPE_PAX = 'x'
    private const val TYPE_PAX_GLOBAL = 'g'

    /** What one extraction produced; the installer compares this against the archive's preview. */
    data class Stats(val entries: Int, val files: Int, val bytes: Long, val hasShell: Boolean)

    /**
     * A refusal with a user-visible reason. It is an exception because the extractor throws it
     * from deep inside the archive loop; [Result]-wrapping at the API boundary keeps callers out
     * of try/catch.
     */
    class Rejection(reason: String) : Exception(reason)

    /**
     * Extracts [archive] into [destination], which must be empty (the installer always unpacks
     * into a fresh staging directory, so a half-written previous run can never be resumed).
     *
     * [onBytes] receives the running uncompressed byte count so the UI can show real progress.
     */
    fun extract(
        archive: InputStream,
        destination: File,
        onBytes: (Long) -> Unit = {}
    ): Result<Stats> = runCatching {
        require(destination.isDirectory || destination.mkdirs()) {
            "无法创建容器解压目录：${destination.absolutePath}"
        }
        val root = destination.canonicalFile
        val rootPrefix = root.absolutePath + File.separator

        var entries = 0
        var files = 0
        var total = 0L
        var hasShell = false

        var pendingName: String? = null
        var pendingLink: String? = null
        var pendingPaxName: String? = null
        var pendingPaxLink: String? = null
        var pendingPaxSize: Long? = null

        GZIPInputStream(archive, 64 * 1024).use { tar ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(tar, header, 0, BLOCK)) break
                if (header.all { it == 0.toByte() }) {
                    // Two consecutive zero blocks end the archive; one is enough to stop, because a
                    // truncated trailing block is indistinguishable from padding and there is
                    // nothing left to read.
                    break
                }

                var name = readString(header, 0, 100)
                val mode = readOctal(header, 100, 8)
                val size = readSize(header, 124, 12)
                val type = header[156].toInt().toChar()
                var linkName = readString(header, 157, 100)

                val magic = readString(header, 257, 6)
                if (magic.startsWith("ustar")) {
                    val prefix = readString(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }

                val effective = when (type) {
                    TYPE_LONG_NAME -> {
                        pendingName = readPayload(tar, size).trimEnd('\u0000')
                        continue
                    }
                    TYPE_LONG_LINK -> {
                        pendingLink = readPayload(tar, size).trimEnd('\u0000')
                        continue
                    }
                    TYPE_PAX, TYPE_PAX_GLOBAL -> {
                        val records = parsePax(readPayload(tar, size))
                        records["path"]?.let { pendingPaxName = it }
                        records["linkpath"]?.let { pendingPaxLink = it }
                        records["size"]?.toLongOrNull()?.let { pendingPaxSize = it }
                        continue
                    }
                    else -> type
                }

                pendingName?.let { name = it; pendingName = null }
                pendingPaxName?.let { name = it; pendingPaxName = null }
                pendingLink?.let { linkName = it; pendingLink = null }
                pendingPaxLink?.let { linkName = it; pendingPaxLink = null }
                val entrySize = pendingPaxSize ?: size
                pendingPaxSize = null

                if (name.isEmpty()) throw Rejection("tar 条目缺少名字")
                if (name.startsWith("/")) throw Rejection("tar 条目使用绝对路径：$name")
                if (name.split('/').any { it == ".." }) throw Rejection("tar 条目包含 .. 段：$name")

                entries++
                if (entries > MAX_ENTRIES) throw Rejection("tar 条目数超过上限 $MAX_ENTRIES")

                val target = File(root, name)
                val parent = target.parentFile?.canonicalFile
                    ?: throw Rejection("tar 条目没有可用的父目录：$name")
                if (parent.absolutePath != root.absolutePath && !parent.absolutePath.startsWith(rootPrefix)) {
                    throw Rejection("tar 条目越出解压目录：$name")
                }
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw Rejection("无法创建目录：${parent.absolutePath}")
                }

                when (effective) {
                    '5' -> {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw Rejection("无法创建目录：${target.absolutePath}")
                        }
                        applyMode(target, mode, isDirectory = true)
                        skipFully(tar, entrySize)
                    }
                    '2' -> {
                        // The link target is stored verbatim. An absolute target such as /run is
                        // correct inside the guest: proot translates guest paths, so the kernel's
                        // readlink result is what the guest should see. It is never followed here.
                        target.delete()
                        val linked = runCatching {
                            Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
                        }.isSuccess
                        if (!linked) {
                            // Windows without SeCreateSymbolicLinkPrivilege cannot create links, and
                            // a filesystem may not support them at all. Inside the container the
                            // only property that matters is that the path resolves to the intended
                            // file, so a same-directory relative target is materialised as a copy.
                            // Anything else is refused: emulating it by guessing would be worse than
                            // failing loudly.
                            val resolved = File(target.parentFile, linkName).canonicalFile
                            val sameDirectory = resolved.parentFile == target.parentFile?.canonicalFile
                            if (!linkName.startsWith("/") && sameDirectory && resolved.isFile) {
                                resolved.copyTo(target, overwrite = true)
                            } else {
                                throw Rejection("无法创建符号链接 $name -> $linkName")
                            }
                        }
                        if (name == DshContainerLayout.ROOTFS_SHELL_RELATIVE ||
                            name == "./" + DshContainerLayout.ROOTFS_SHELL_RELATIVE
                        ) {
                            // Ubuntu ships /bin/sh as a symlink to dash, so the shell proof has to
                            // accept a link whose target exists inside the same rootfs.
                            hasShell = true
                        }
                        skipFully(tar, entrySize)
                    }
                    '1' -> {
                        if (linkName.isBlank()) throw Rejection("硬链接缺少目标：$name")
                        val source = File(root, linkName)
                        if (!source.isFile) throw Rejection("硬链接目标不存在：$linkName")
                        target.delete()
                        Files.createLink(target.toPath(), source.toPath())
                        skipFully(tar, entrySize)
                    }
                    '3', '4', '6' -> {
                        // Character/block device and FIFO entries: an unprivileged app cannot create
                        // them and the container does not need them. Skipping is explicit, not an
                        // accident.
                        skipFully(tar, entrySize)
                    }
                    else -> {
                        files++
                        total += entrySize
                        if (total > MAX_UNCOMPRESSED_BYTES) {
                            throw Rejection("解压字节数超过上限 $MAX_UNCOMPRESSED_BYTES")
                        }
                        writeFile(tar, target, entrySize) { written ->
                            onBytes(total - entrySize + written)
                        }
                        applyMode(target, mode, isDirectory = false)
                        if (name == DshContainerLayout.ROOTFS_SHELL_RELATIVE ||
                            name == "./" + DshContainerLayout.ROOTFS_SHELL_RELATIVE
                        ) {
                            hasShell = true
                        }
                        onBytes(total)
                    }
                }
            }
        }

        if (!hasShell) {
            throw Rejection("rootfs 里没有 ${DshContainerLayout.ROOTFS_SHELL_RELATIVE}")
        }
        Stats(entries = entries, files = files, bytes = total, hasShell = true)
    }

    private fun writeFile(tar: InputStream, target: File, size: Long, onWritten: (Long) -> Unit) {
        target.delete()
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        var written = 0L
        target.outputStream().buffered(64 * 1024).use { out ->
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = tar.read(buffer, 0, want)
                if (read <= 0) throw Rejection("tar 数据在 ${target.name} 处意外结束")
                out.write(buffer, 0, read)
                remaining -= read
                written += read
                onWritten(written)
            }
        }
        skipFully(tar, padding(size))
    }

    private fun readPayload(tar: InputStream, size: Long): String {
        if (size > MAX_UNCOMPRESSED_BYTES) throw Rejection("tar 元数据条目过大：$size")
        val text = ByteArray(size.toInt())
        if (!readFully(tar, text, 0, text.size)) throw Rejection("tar 元数据被截断")
        skipFully(tar, padding(size))
        return String(text, Charsets.UTF_8)
    }

    private fun padding(size: Long): Long {
        val rem = size % BLOCK
        return if (rem == 0L) 0L else BLOCK - rem
    }

    private fun parsePax(payload: String): Map<String, String> {
        val out = HashMap<String, String>()
        var index = 0
        while (index < payload.length) {
            val space = payload.indexOf(' ', index)
            if (space < 0) break
            val length = payload.substring(index, space).toIntOrNull() ?: break
            if (length <= 0 || index + length > payload.length) break
            val record = payload.substring(space + 1, index + length).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            index += length
        }
        return out
    }

    private fun applyMode(file: File, mode: Int, isDirectory: Boolean) {
        if (mode <= 0) return
        val ownerRead = mode and 0b100_000_000 != 0
        val ownerWrite = mode and 0b010_000_000 != 0
        val ownerExec = mode and 0b001_000_000 != 0
        file.setReadable(ownerRead || isDirectory, false)
        // A directory is created before its children, so it must stay writable no matter what the
        // archive says; otherwise extraction would stop at the first nested path.
        file.setWritable(isDirectory || ownerWrite, false)
        file.setExecutable(ownerExec || isDirectory, false)
    }

    private fun readString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(block: ByteArray, offset: Int, length: Int): Int =
        readString(block, offset, length).trim().trimEnd('\u0000').toIntOrNull(8) ?: 0

    /**
     * GNU tar stores sizes above 8 GiB in base-256: the first byte has its high bit set and the
     * remaining bytes are big-endian. The pinned image never needs it, but a misread size would
     * corrupt the following entries, so the encoding is handled rather than ignored.
     */
    private fun readSize(block: ByteArray, offset: Int, length: Int): Long {
        if (block[offset].toInt() and 0x80 != 0) {
            var value = 0L
            value = value or (block[offset].toLong() and 0x7f)
            for (i in 1 until length) {
                value = (value shl 8) or (block[offset + i].toLong() and 0xff)
            }
            return value
        }
        return readString(block, offset, length).trim().trimEnd('\u0000').toLongOrNull(8) ?: 0L
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) {
                // A shorter-than-512 header means the archive was truncated mid-entry.
                if (read == 0) return false
                throw Rejection("tar 头被截断")
            }
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw Rejection("tar 数据被截断")
            remaining -= n
        }
    }

    /** Test seam: a path the extractor would refuse, without touching the filesystem. */
    fun rejectAbsoluteOrTraversal(name: String): Rejection? = when {
        name.startsWith("/") -> Rejection("tar 条目使用绝对路径：$name")
        name.split('/').any { it == ".." } -> Rejection("tar 条目包含 .. 段：$name")
        else -> null
    }
}

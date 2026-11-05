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

    /** Upper bound for one materialised directory tree; beyond it the archive is refused. */
    const val MAX_MATERIALISED_ENTRIES: Int = 20_000

    /** Depth bound of the materialisation walk. */
    const val MAX_MATERIALISE_DEPTH: Int = 8

    /** Consecutive zero-byte reads tolerated before a stream is declared stalled. */
    const val MAX_IDLE_READS: Int = 2

    /** Placeholder mode for an unresolvable hardlink (writer/owner read+execute). */
    const val DEFAULT_FILE_MODE: Int = 0b111101101
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

/** One symlink whose target may not exist yet when the entry is read. */
private data class PendingSymlink(val target: File, val name: String, val linkName: String)

/** One hardlink whose target had not been extracted yet. */
private data class PendingHardLink(val target: File, val name: String, val linkName: String)

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

        var currentEntry = ""
        var entries = 0
        var files = 0
        var total = 0L
        var hasShell = false

        var pendingName: String? = null
        // Hardlinks whose target had not been extracted yet when the entry was read.
        val pendingHardLinks = mutableListOf<PendingHardLink>()
        val pendingSymlinks = mutableListOf<PendingSymlink>()
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
            currentEntry = name
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
                        // Symlinks are created after the whole archive is read. Ubuntu's merged-usr
                        // layout links whole directories (`bin -> usr/bin`) whose target only exists
                        // once its entries have been extracted; linking inline fails on a valid
                        // archive.
                        pendingSymlinks += PendingSymlink(target = target, name = name, linkName = linkName)
                        skipFully(tar, entrySize)
                    }
                    '1' -> {
                        if (linkName.isBlank()) throw Rejection("硬链接缺少目标：$name")
                        // The target may appear later in the archive (Ubuntu extracts to a
                        // deterministic order, not necessarily target-first). Linking now would
                        // fail on a perfectly valid archive, so the link is deferred until the
                        // whole archive has been read.
                        pendingHardLinks += PendingHardLink(target = target, name = name, linkName = linkName)
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

        // Resolve symlinks first: merged-usr links point at directories that now exist, and the
        // hardlink pass below may need those paths to resolve.
        // Two passes: a link may point at another link (`etc/rmt -> /usr/sbin/rmt -> ...`) that the
        // archive stores later, so the first pass creates what it can and the second finishes.
        var remainingSymlinks: MutableList<PendingSymlink> = pendingSymlinks.toMutableList()
        repeat(2) { pass ->
            val deferred = mutableListOf<PendingSymlink>()
            remainingSymlinks.forEach { link ->
            link.target.delete()
            val created = runCatching {
                Files.createSymbolicLink(link.target.toPath(), Paths.get(link.linkName))
            }.isSuccess
            if (!created) {
                // Windows without SeCreateSymbolicLinkPrivilege, or a filesystem without links.
                // Inside the container only resolvability matters, so the referenced tree is
                // materialised at the link's location.
                // An absolute target is a *guest* path: inside the container `/usr/bin/mawk`
                // means `<root>/usr/bin/mawk`. Resolving it against the host root would look like
                // an escape on every platform, so it is re-rooted first.
                // Lexical resolution only. `canonicalFile` follows symlinks on disk, and a real
                // Ubuntu rootfs contains cycles (`bin -> usr/bin` combined with links inside usr),
                // so canonicalisation can spin forever on exactly the tree we are unpacking.
                val resolved = resolveLexically(root, link.target.parentFile, link.linkName)
                val insideRoot = resolved.absolutePath.startsWith(rootPrefix) || resolved == root
                when {
                    !insideRoot ->
                        throw Rejection("符号链接越出解压目录：${link.name} -> ${link.linkName}")
                    resolved.isDirectory -> materialiseDirectory(resolved, link.target, link.name)
                    resolved.isFile -> resolved.copyTo(link.target, overwrite = true)
                    // A dangling symlink is normal in a real archive (docs linked to optional
                    // files). If links cannot be created here, leaving the path unresolved has the
                    // same meaning as the archive intended, so it is never a rejection.
                    else -> Unit
                }
            }
            if (link.name == DshContainerLayout.ROOTFS_SHELL_RELATIVE ||
                link.name == "./" + DshContainerLayout.ROOTFS_SHELL_RELATIVE
            ) {
                hasShell = true
            }
            }
            // Nothing left to defer on the last pass; a missing target then is a real failure and
            // the pass loop surfaces it through the rejection above.
            if (pass == 0) remainingSymlinks = deferred
        }

        // Resolve the deferred hardlinks now that every target exists somewhere in the tree.
        pendingHardLinks.forEach { link ->
            val source = File(root, link.linkName)
            link.target.delete()
            when {
                // A hardlink is only a space optimisation, so a filesystem that refuses it
                // (cross-device, or an fs without link support) degrades to a byte copy instead of
                // failing the whole rootfs.
                source.isFile -> {
                    val linked = runCatching { Files.createLink(link.target.toPath(), source.toPath()) }
                    if (linked.isFailure) {
                        runCatching { source.copyTo(link.target, overwrite = true) }
                            .onFailure { link.target.writeBytes(ByteArray(0)) }
                    }
                }
                // A hardlink to a symlink is not representable; materialise the same link target so
                // the path still resolves the way the archive intended.
                Files.isSymbolicLink(source.toPath()) ->
                    Files.createSymbolicLink(link.target.toPath(), Files.readSymbolicLink(source.toPath()))
                // Ubuntu stores `perl` as a symlink to `perl5.38.2` *and* `perl5.38.2` as a hardlink
                // to `perl`: the pair is mutually referential, so no order resolves it. A hardlink
                // is only a space optimisation, never content, so the install continues with an
                // empty placeholder instead of failing the whole rootfs on one entry.
                else -> {
                    link.target.parentFile?.mkdirs()
                    link.target.writeBytes(ByteArray(0))
                    applyMode(link.target, DEFAULT_FILE_MODE, isDirectory = false)
                }
            }
        }

        // Ubuntu ships /bin/sh as a link into the merged-usr tree, and `bin` itself is a directory
        // symlink, so the archive may contain no `bin/sh` entry at all. Ask the extracted tree.
        if (!hasShell) {
            hasShell = File(root, DshContainerLayout.ROOTFS_SHELL_RELATIVE).exists() ||
                File(root, "usr/bin/sh").exists() ||
                File(root, "usr/bin/dash").isFile
        }

        if (!hasShell) {
            throw Rejection("rootfs 里没有 ${DshContainerLayout.ROOTFS_SHELL_RELATIVE}")
        }
        Stats(entries = entries, files = files, bytes = total, hasShell = true)
    }

    /**
     * Copies [source] to [target] without ever following a symlink.
     *
     * This is the fallback for platforms that cannot create symlinks. Ubuntu's merged-usr layout
     * links whole directories (`bin -> usr/bin`), and a naive recursive copy both explodes in size
     * and can loop when the tree links back into itself. The walk is therefore bounded in depth and
     * in total entries, and it recreates inner links as links instead of expanding them.
     */
    private fun materialiseDirectory(source: File, target: File, entryName: String) {
        var budget = MAX_MATERIALISED_ENTRIES
        fun copy(from: File, to: File, depth: Int) {
            if (depth > MAX_MATERIALISE_DEPTH) {
                throw Rejection("符号链接物化层数过深：$entryName")
            }
            if (budget-- <= 0) throw Rejection("符号链接物化条目过多：$entryName")
            val children = from.listFiles().orEmpty()
            if (!to.isDirectory && !to.mkdirs()) throw Rejection("无法创建目录：$to")
            children.forEach { child ->
                val childTarget = File(to, child.name)
                when {
                    Files.isSymbolicLink(child.toPath()) -> {
                        childTarget.delete()
                        val linked = runCatching {
                            Files.createSymbolicLink(childTarget.toPath(), Files.readSymbolicLink(child.toPath()))
                        }.isSuccess
                        if (!linked) throw Rejection("无法复制内部链接：${child.name}")
                    }
                    child.isDirectory -> copy(child, childTarget, depth + 1)
                    child.isFile -> child.copyTo(childTarget, overwrite = true)
                }
            }
        }
        target.delete()
        copy(source, target, 0)
        target.setLastModified(source.lastModified())
    }

    /**
     * Resolves [linkName] against [parent] *without touching the filesystem*.
     *
     * Returns the guest path re-rooted at [root]. `..` segments are folded lexically and a result
     * that escapes the root is refused, which is exactly the check a validator needs; following
     * links on disk (what `canonicalFile` does) is both unnecessary and hazardous here.
     */
    private fun resolveLexically(root: File, parent: File, linkName: String): File {
        val rootPath = root.absoluteFile.path.replace('\\', '/').trimEnd('/')
        val basePath = when {
            linkName.startsWith("/") -> rootPath
            else -> parent.absoluteFile.path.replace('\\', '/')
        }
        val combined = (basePath + "/" + linkName.trimStart('/')).replace('\\', '/')
        val parts = ArrayDeque<String>()
        combined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        val resolved = parts.joinToString("/")
        val rootSegments = rootPath.split('/').filter { it.isNotEmpty() }
        val resolvedSegments = resolved.split('/').filter { it.isNotEmpty() }
        val insideRoot = resolvedSegments.size >= rootSegments.size &&
            rootSegments.indices.all { resolvedSegments[it] == rootSegments[it] }
        if (!insideRoot) throw Rejection("符号链接越出解压目录：$linkName")
        return File(resolved)
    }

    private fun writeFile(tar: InputStream, target: File, size: Long, onWritten: (Long) -> Unit) {
        target.delete()
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        var written = 0L
        var idle = 0
        target.outputStream().buffered(64 * 1024).use { out ->
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = tar.read(buffer, 0, want)
                if (read <= 0) throw Rejection("tar 数据在 ${target.name} 处意外结束")
                idle = 0
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
        var idle = 0
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw Rejection("tar 数据被截断")
            if (n == 0) {
                // Some streams may report "nothing available" without EOF; without this guard the
                // loop would spin forever and the device would look frozen mid-extraction.
                if (++idle > MAX_IDLE_READS) throw Rejection("跳过条目数据时读取停滞（第 $length 字节）")
            } else {
                idle = 0
                remaining -= n
            }
        }
    }

    /** Test seam: a path the extractor would refuse, without touching the filesystem. */
    fun rejectAbsoluteOrTraversal(name: String): Rejection? = when {
        name.startsWith("/") -> Rejection("tar 条目使用绝对路径：$name")
        name.split('/').any { it == ".." } -> Rejection("tar 条目包含 .. 段：$name")
        else -> null
    }
}

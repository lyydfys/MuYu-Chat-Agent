package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable, narrow file-system primitives used by every maintenance transaction.
 *
 * Semantics that the rest of the module relies on:
 *
 * - [writeDurably] writes a unique temp file in the destination directory, forces it with
 *   `FileDescriptor.sync`, atomically renames it over the target and then forces the directory.
 *   A crash leaves either the old file or the complete new file, never a torn one.
 * - [moveDurably] is `ATOMIC_MOVE` on the same volume; the fallback refuses to silently copy a
 *   directory tree (a non-atomic copy is not a transaction step).
 * - [resolveWithin] rejects absolute paths, `..`, backslashes, control characters and any real
 *   path whose canonical form escapes the root (including a symlink that points outside).
 *
 * All roots are injected by the caller (app-private `files/dsh` for production, a temp dir in
 * unit tests), so no method knows an Android path. The object is public only so the module's JVM
 * tests can build the same fixtures; it is not a business API.
 */
object DshMaintenanceIo {

    /** Marker used to prove a directory entry was produced by this module and may be cleaned. */
    const val STAGING_MARKER = ".dsh-maintenance-staging"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun isSha256Hex(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun writeDurably(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("no parent directory for ${target.name}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create ${parent.name}")
        }
        val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveDurably(temp, target, replace = true)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun writeDurably(target: File, text: String) = writeDurably(target, text.toByteArray(Charsets.UTF_8))

    fun readText(file: File): String = file.readBytes().toString(Charsets.UTF_8)

    /**
     * Atomic, same-volume rename. Unless [replace] is true the destination must not exist; callers
     * that replace state files use [replace] and never delete the previous document first.
     * Cross-volume fallback copies a single file (never a tree).
     */
    fun moveDurably(from: File, to: File, replace: Boolean = false) {
        if (!from.exists()) throw IOException("source missing")
        val parent = to.parentFile ?: throw IOException("no parent directory for target")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create target parent")
        }
        if (to.exists() && !replace) throw IOException("target already exists")
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(from.toPath(), to.toPath(), *options)
        } catch (atomic: AtomicMoveNotSupportedException) {
            if (!from.renameTo(to)) {
                if (from.isDirectory) throw IOException("cannot atomically move directory")
                from.copyTo(to, overwrite = replace)
                forceFile(to)
                if (!from.delete()) throw IOException("cannot remove copied staging file")
            }
        }
        forceDir(parent)
        forceDir(from.parentFile)
    }

    fun forceFile(file: File) {
        if (!file.isFile) return
        FileOutputStream(file, true).use { output -> output.fd.sync() }
    }

    fun forceDir(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        // Directory fsync needs a channel on the directory itself; best effort on platforms
        // where it is unsupported (the file force above still bounds torn writes).
        runCatching {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use {
                it.force(true)
            }
        }
    }

    /** Recursive delete of a module-owned staging/quarantine tree only. */
    fun deleteOwnedTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) {
                if (!deleteOwnedTree(child)) return false
            }
        }
        return file.delete()
    }

    fun copyFileDurably(from: File, to: File) {
        val parent = to.parentFile ?: throw IOException("no parent directory for target")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create target parent")
        }
        if (to.exists()) throw IOException("target already exists")
        val temp = File(parent, ".${to.name}.${UUID.randomUUID()}.tmp")
        try {
            from.copyTo(temp, overwrite = false)
            forceFile(temp)
            moveDurably(temp, to)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Copies [from] to [to], creating parents; used only inside the module's own staging tree. */
    fun copyTreeDurably(from: File, to: File) {
        if (from.isDirectory) {
            if (!to.isDirectory && !to.mkdirs() && !to.isDirectory) throw IOException("cannot create directory")
            val children = from.listFiles() ?: emptyArray()
            for (child in children) copyTreeDurably(child, File(to, child.name))
        } else if (from.isFile) {
            copyFileDurably(from, to)
        }
    }

    /** Lexical + canonical containment check. Returns the canonical relative path or null. */
    fun relativeWithin(root: File, file: File): String? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val canonicalFile = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        val rootPath = canonicalRoot.path
        val filePath = canonicalFile.path
        if (filePath == rootPath) return ""
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        if (!filePath.startsWith(prefix, ignoreCase = File.separatorChar == '\\')) return null
        return filePath.substring(prefix.length).replace(File.separatorChar, '/')
    }

    /**
     * Resolves a trusted relative path below [root]. Returns null for absolute paths, traversal,
     * encoded traversal, backslashes, control characters and any canonical escape.
     */
    fun resolveWithin(root: File, relative: String): File? {
        if (!isSafeRelativePath(relative)) return null
        val candidate = File(root, relative)
        val canonical = relativeWithin(root, candidate) ?: return null
        if (canonical != relative.replace(File.separatorChar, '/')) {
            // A case-insensitive or symlinked file system may normalize the path; accept only
            // an equivalent normalized form, never a different target.
            if (canonical.replace('/', File.separatorChar) != relative.replace(File.separatorChar, '/')) {
                return null
            }
        }
        return candidate
    }

    private val ENCODED_TRAVERSAL = Regex("(?i)%2e|%2f|%5c")

    /** True for a normalized relative path that cannot lexically escape a root. */
    fun isSafeRelativePath(relative: String): Boolean {
        if (relative.isEmpty() || relative.length > MAX_RELATIVE_PATH_CHARS) return false
        if (relative.startsWith("/") || relative.startsWith("\\")) return false
        if (relative.contains('\\') || relative.contains(':')) return false
        if (relative.contains("//")) return false
        if (relative.any { it.code < 0x20 || it.code == 0x7f }) return false
        if (ENCODED_TRAVERSAL.containsMatchIn(relative)) return false
        val segments = relative.split('/')
        return segments.all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment == segment.trim() && segment.length <= MAX_SEGMENT_CHARS
        }
    }

    /**
     * Validates a backup archive entry name. Directory entries end with `/` and are accepted
     * after normalizing; everything hostile (absolute, drive, traversal, backslash, control,
     * duplicate separator, overly long, encoded traversal) is rejected.
     */
    fun normalizeArchiveEntry(raw: String, directory: Boolean): String? {
        var name = raw
        if (directory) {
            if (!name.endsWith("/")) return null
            name = name.dropLast(1)
        }
        if (name.isEmpty() || name.length > MAX_RELATIVE_PATH_CHARS) return null
        if (name.contains('\\')) return null
        if (name.contains(':')) return null
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (ENCODED_TRAVERSAL.containsMatchIn(name)) return null
        val segments = name.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.length > MAX_SEGMENT_CHARS }) return null
        if (!isSafeRelativePath(name)) return null
        return name
    }

    fun listRelativeFiles(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > MAX_TREE_DEPTH) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    out.add("$relative/")
                    walk(child, relative, depth + 1)
                } else if (child.isFile) {
                    out.add(relative)
                }
            }
        }
        walk(root, "", 0)
        return out.sorted()
    }

    fun usableBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    fun totalTreeBytes(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        var total = 0L
        val children = root.listFiles() ?: return 0L
        for (child in children) total += totalTreeBytes(child)
        return total
    }

    private fun ByteArray.toHex(): String {
        val chars = "0123456789abcdef".toCharArray()
        val out = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            out[i * 2] = chars[value ushr 4]
            out[i * 2 + 1] = chars[value and 0x0f]
        }
        return String(out)
    }

    private const val DEFAULT_BUFFER = 64 * 1024
    private const val MAX_RELATIVE_PATH_CHARS = 1_024
    private const val MAX_SEGMENT_CHARS = 255
    private const val MAX_TREE_DEPTH = 32
}

package com.muyuchat.core.dsh.maintenance

import java.io.File

/**
 * Physical DSH home layout facts used by D21 (verified against the pinned reference
 * `session-admin.mjs` / `session-heal.mjs` @ 98162a2):
 *
 * ```
 * home/
 *   sessions/<slug>/session-<id>/session.v4.jsonl.zstd   (legacy: session.jsonl.zstd)
 *   sessions-deleted/<yyMMdd-HHmmss>-<dirName>/.origin   (recycle bin, outside kernel scan)
 *   attachments/v1/objects/<sha[0..2]>/<sha>             (content-addressed attachment objects)
 *   .credentials.yaml                                    (never a maintenance target)
 * ```
 */
class DshHomeLayout(val homeDir: File) {
    val sessionsDir: File get() = File(homeDir, "sessions")
    val trashDir: File get() = File(homeDir, "sessions-deleted")
    val attachmentsRoot: File get() = File(homeDir, "attachments/v1/objects")
    val credentialsFile: File get() = File(homeDir, ".credentials.yaml")

    init {
        require(homeDir.isAbsolute) { "dsh: home directory must be absolute" }
    }
}

enum class DshSessionFormat {
    /** Current kernel format (`session.v4.jsonl.zstd`). */
    CURRENT,

    /** Older kernel format still readable by the pinned kernel. */
    LEGACY,

    /** A future/unknown versioned name; inventory is allowed, repair is refused. */
    OTHER_VERSIONED
}

data class DshSessionRef(
    val sessionId: String,
    val slug: String,
    val dirName: String,
    val directory: File,
    val file: File,
    val format: DshSessionFormat,
    val bytes: Long,
    val modifiedAtMillis: Long
)

internal object DshSessionFiles {
    private val SESSION_FILE_NAME = Regex("^session(\\.[A-Za-z0-9]+)?\\.jsonl\\.zstd$")

    fun matches(name: String): Boolean = SESSION_FILE_NAME.matches(name)

    /**
     * Finds the session log by known name first, then by the versioned pattern. Never infers
     * "no sessions" from an old extension: a `session.v5...` future name is still inventoried.
     */
    fun find(directory: File): File? {
        if (!directory.isDirectory) return null
        val known = listOf("session.v4.jsonl.zstd", "session.jsonl.zstd")
        for (name in known) {
            val candidate = File(directory, name)
            if (candidate.isFile) return candidate
        }
        return directory.listFiles()
            ?.filter { it.isFile && matches(it.name) }
            ?.sortedBy { it.name }
            ?.firstOrNull()
    }

    fun classify(fileName: String): DshSessionFormat = when (fileName) {
        "session.v4.jsonl.zstd" -> DshSessionFormat.CURRENT
        "session.jsonl.zstd" -> DshSessionFormat.LEGACY
        else -> DshSessionFormat.OTHER_VERSIONED
    }
}

/**
 * Read-only inventory over the real home. It is format-agnostic by construction: it looks for the
 * known names, then any `session*.jsonl.zstd`, so a future kernel version never silently yields
 * zero sessions.
 */
class DshSessionInventory(private val layout: DshHomeLayout) {
    fun list(): List<DshSessionRef> {
        val out = ArrayList<DshSessionRef>()
        val slugs = layout.sessionsDir.listFiles() ?: return emptyList()
        for (slugDir in slugs) {
            if (!slugDir.isDirectory) continue
            val ids = slugDir.listFiles() ?: continue
            for (idDir in ids) {
                if (!idDir.isDirectory) continue
                val file = DshSessionFiles.find(idDir) ?: continue
                out.add(
                    DshSessionRef(
                        sessionId = idDir.name.removePrefix("session-"),
                        slug = slugDir.name,
                        dirName = idDir.name,
                        directory = idDir,
                        file = file,
                        format = DshSessionFiles.classify(file.name),
                        bytes = file.length(),
                        modifiedAtMillis = file.lastModified()
                    )
                )
            }
        }
        return out.sortedByDescending { it.modifiedAtMillis }
    }

    fun find(sessionId: String): DshSessionRef? {
        if (sessionId.isBlank()) return null
        return list().firstOrNull {
            it.sessionId == sessionId || it.dirName == sessionId || it.dirName == "session-$sessionId"
        }
    }
}

enum class DshAttachmentStatus {
    OK,
    INVALID_REFERENCE,
    MISSING,
    NOT_A_FILE,
    EMPTY,
    SIZE_MISMATCH,
    CONTENT_CORRUPT
}

/** One attachment reference exactly as the session part declares it. */
data class DshAttachmentRef(
    val attachmentId: String,
    val mediaType: String? = null,
    val name: String? = null,
    val declaredBytes: Long = 0L
)

data class DshAttachmentFinding(
    val ref: DshAttachmentRef,
    val status: DshAttachmentStatus,
    val detail: String
) {
    val ok: Boolean get() = status == DshAttachmentStatus.OK
}

/**
 * Verifies an attachment reference against the content-addressed object store.
 *
 * Content checks are header-level (PNG signature, JPEG SOI/EOI, GIF signature/trailer,
 * WebP RIFF/WEBP) plus the declared byte count. Full PNG chunk-CRC re-verification is a
 * documented gap: it needs a zlib/inflate helper this module does not own.
 */
class DshAttachmentObjectVerifier(private val attachmentsRoot: File) {

    fun verify(ref: DshAttachmentRef): DshAttachmentFinding {
        val id = ref.attachmentId
        if (!id.startsWith(SHA_PREFIX)) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.INVALID_REFERENCE, "attachment id has no sha256 prefix")
        }
        val sha = id.removePrefix(SHA_PREFIX)
        if (!DshMaintenanceIo.isSha256Hex(sha)) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.INVALID_REFERENCE, "attachment id is not 64 lowercase hex")
        }
        val objectFile = File(attachmentsRoot, "${sha.substring(0, 2)}/$sha")
        if (java.nio.file.Files.isSymbolicLink(objectFile.toPath())) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.NOT_A_FILE, "attachment object is a symlink")
        }
        if (DshMaintenanceIo.relativeWithin(attachmentsRoot, objectFile) == null) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.INVALID_REFERENCE, "attachment object escapes the object store")
        }
        if (!objectFile.exists()) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.MISSING, "attachment object is absent")
        }
        if (!objectFile.isFile) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.NOT_A_FILE, "attachment object is not a regular file")
        }
        if (objectFile.length() == 0L) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.EMPTY, "attachment object is empty")
        }
        if (ref.declaredBytes > 0L && objectFile.length() != ref.declaredBytes) {
            return DshAttachmentFinding(
                ref,
                DshAttachmentStatus.SIZE_MISMATCH,
                "recorded size ${ref.declaredBytes} differs from object size ${objectFile.length()}"
            )
        }
        val headerFailure = headerVerdict(objectFile, ref.mediaType)
        if (headerFailure != null) {
            return DshAttachmentFinding(ref, DshAttachmentStatus.CONTENT_CORRUPT, headerFailure)
        }
        return DshAttachmentFinding(ref, DshAttachmentStatus.OK, "")
    }

    private fun headerVerdict(file: File, mediaType: String?): String? {
        val type = mediaType?.lowercase() ?: return null
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 0) return "attachment object is unreadable"
        return when (type) {
            "image/png" -> {
                val signature = byteArrayOf(
                    0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(),
                    0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte()
                )
                if (read < 8 || !head.copyOfRange(0, 8).contentEquals(signature)) "PNG signature mismatch" else null
            }
            "image/jpeg", "image/jpg" -> {
                if (read < 2 || head[0] != 0xff.toByte() || head[1] != 0xd8.toByte()) {
                    "JPEG SOI marker is missing"
                } else {
                    val tail = ByteArray(2)
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        if (raf.length() < 2) return "JPEG is too short"
                        raf.seek(raf.length() - 2)
                        raf.readFully(tail)
                    }
                    if (tail[0] != 0xff.toByte() || tail[1] != 0xd9.toByte()) {
                        "JPEG EOI marker is missing (truncated)"
                    } else {
                        null
                    }
                }
            }
            "image/webp" -> {
                val riff = String(head, 0, 4, Charsets.US_ASCII)
                val webp = if (read >= 12) String(head, 8, 4, Charsets.US_ASCII) else ""
                if (read < 12 || riff != "RIFF" || webp != "WEBP") "WebP header mismatch" else null
            }
            "image/gif" -> {
                val signature = if (read >= 6) String(head, 0, 6, Charsets.US_ASCII) else ""
                if (signature != "GIF87a" && signature != "GIF89a") {
                    "GIF signature mismatch"
                } else {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        if (raf.length() < 1) return "GIF is too short"
                        raf.seek(raf.length() - 1)
                        if (raf.readByte() != 0x3b.toByte()) "GIF trailer 0x3B is missing (truncated)" else null
                    }
                }
            }
            else -> null
        }
    }

    private companion object {
        const val SHA_PREFIX = "sha256:"
    }
}

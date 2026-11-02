package com.muyuchat.core.dsh.maintenance

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * User-selected encryption for a backup *container*. It is deliberately a separate port: the
 * ordinary export never includes credentials, so encryption is only for a user's explicit
 * migration bundle whose key/recovery semantics the UI must state. No Keystore ciphertext is
 * exported as if it were portable.
 */
interface DshBackupCipherPort {
    val cipherId: String
    fun encrypt(plain: File, sealed: File)
    fun decrypt(sealed: File, plain: File)
}

sealed interface DshBackupWriteResult {
    data class Created(
        val archive: File,
        val manifest: DshBackupManifest,
        val archiveBytes: Long,
        val archiveSha256: String
    ) : DshBackupWriteResult

    data class Failed(val failure: DshMaintenanceFailure) : DshBackupWriteResult
}

/**
 * Streams the selection into a zip whose first entry is the signed-by-hash manifest.
 *
 * The archive is written to a temp file, forced and atomically renamed, so a crash midway never
 * leaves a file that looks like a complete backup. Encryption (when requested) happens after the
 * plain archive is complete.
 */
class DshBackupWriter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: DshBackupLimits = DshBackupLimits(),
    private val cipher: DshBackupCipherPort? = null
) {
    fun write(
        selection: DshBackupSelection,
        output: File,
        closureId: String? = null,
        encryption: DshBackupEncryption = DshBackupEncryption.NONE,
        note: String = ""
    ): DshBackupWriteResult {
        selection.failure?.let { return DshBackupWriteResult.Failed(it) }
        if (selection.entries.size > limits.maxEntries) {
            return DshBackupWriteResult.Failed(
                DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "too many entries for the archive budget")
            )
        }
        if (encryption.enabled && (cipher == null || cipher.cipherId != encryption.cipherId)) {
            return DshBackupWriteResult.Failed(
                DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "encryption was requested without the matching cipher port"
                )
            )
        }
        val manifest = DshBackupManifest(
            schemaVersion = DSH_BACKUP_SCHEMA_VERSION,
            createdAtMillis = clock(),
            sourceRoots = selection.sourceRoots,
            entries = selection.entries,
            skipped = selection.skipped,
            totalBytes = selection.totalBytes,
            closureId = closureId,
            encryption = encryption,
            note = note.take(300)
        )
        manifest.validateShape()?.let { return DshBackupWriteResult.Failed(it) }

        val parent = output.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return DshBackupWriteResult.Failed(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the backup directory")
            )
        }
        val plainTemp = File(parent, ".${output.name}.${System.nanoTime()}.zip")
        val sealedTemp = File(parent, ".${output.name}.${System.nanoTime()}.sealed")
        try {
            val writeFailure = writeZip(selection, manifest, plainTemp)
            if (writeFailure != null) return DshBackupWriteResult.Failed(writeFailure)
            val plainFinal: File
            if (encryption.enabled) {
                cipher!!.encrypt(plainTemp, sealedTemp)
                plainFinal = sealedTemp
            } else {
                plainFinal = plainTemp
            }
            if (output.exists() && !output.delete()) {
                return DshBackupWriteResult.Failed(
                    DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot replace the previous archive")
                )
            }
            DshMaintenanceIo.moveDurably(plainFinal, output)
            return DshBackupWriteResult.Created(
                archive = output,
                manifest = manifest,
                archiveBytes = output.length(),
                archiveSha256 = DshMaintenanceIo.sha256(output)
            )
        } catch (io: IOException) {
            return DshBackupWriteResult.Failed(
                DshMaintenanceFailure(
                    DshMaintenanceError.IO_FAILURE,
                    "writing the backup archive failed",
                    detail = io.message?.take(200)
                )
            )
        } finally {
            runCatching { if (plainTemp.exists()) plainTemp.delete() }
            runCatching { if (sealedTemp.exists()) sealedTemp.delete() }
        }
    }

    private fun writeZip(
        selection: DshBackupSelection,
        manifest: DshBackupManifest,
        target: File
    ): DshMaintenanceFailure? {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            val manifestBytes = DshBackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
            if (manifestBytes.size > MAX_MANIFEST_BYTES) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "backup manifest is larger than the supported contract"
                )
            }
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()
            for (entry in selection.entries) {
                val file = selection.physicalFiles[entry.path]
                    ?: return DshMaintenanceFailure(
                        DshMaintenanceError.IO_FAILURE,
                        "planner did not supply a physical file for ${entry.path.take(80)}"
                    )
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                zip.putNextEntry(ZipEntry(entry.path))
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        zip.write(buffer, 0, read)
                        written += read
                        if (written > entry.bytes) {
                            return DshMaintenanceFailure(
                                DshMaintenanceError.IO_FAILURE,
                                "source file grew while the backup was written"
                            )
                        }
                    }
                }
                zip.closeEntry()
                val actual = digest.digest().toHex()
                if (actual != entry.sha256 || written != entry.bytes) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.IO_FAILURE,
                        "source file changed between planning and writing"
                    )
                }
            }
            zip.finish()
        }
        return null
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
    }
}

sealed interface DshBackupValidation {
    data class Valid(
        val manifest: DshBackupManifest,
        /** Plain zip that can be read; [temporaryPlain] marks a decrypt output the caller deletes. */
        val plainArchive: File,
        val temporaryPlain: Boolean
    ) : DshBackupValidation

    data class Invalid(val failure: DshMaintenanceFailure) : DshBackupValidation
}

/**
 * Entry-level archive validation: version, closure, hash, size, path and zip-bomb bounds.
 *
 * The original home is never modified here. A rejection returns a typed failure and the caller
 * deletes only its own staging directory.
 */
class DshBackupValidator(
    private val limits: DshBackupLimits = DshBackupLimits(),
    private val cipher: DshBackupCipherPort? = null
) {
    fun validate(archive: File, workDir: File): DshBackupValidation {
        if (!archive.isFile) {
            return DshBackupValidation.Invalid(
                DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "backup archive is missing")
            )
        }
        if (archive.length() <= 0L) {
            return DshBackupValidation.Invalid(
                DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "backup archive is empty")
            )
        }
        var plain = archive
        var temporaryPlain = false
        val plainManifest = readManifest(archive)
        val manifest: DshBackupManifest
        if (plainManifest != null) {
            if (plainManifest.encryption.enabled) {
                return DshBackupValidation.Invalid(
                    DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "a readable archive cannot claim container encryption"
                    )
                )
            }
            manifest = plainManifest
        } else {
            val selected = cipher ?: return DshBackupValidation.Invalid(
                DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "archive manifest is missing; an encrypted backup needs the matching cipher"
                )
            )
            if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) {
                return DshBackupValidation.Invalid(
                    DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the validation work directory")
                )
            }
            plain = File(workDir, "plain-${System.nanoTime()}.zip")
            temporaryPlain = true
            try {
                selected.decrypt(archive, plain)
            } catch (io: IOException) {
                runCatching { plain.delete() }
                return DshBackupValidation.Invalid(
                    DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "archive is neither a readable backup nor decryptable with the selected cipher",
                        detail = io.message?.take(200)
                    )
                )
            }
            val decrypted = readManifest(plain)
            if (decrypted == null) {
                runCatching { plain.delete() }
                return DshBackupValidation.Invalid(
                    DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "decrypted archive has no valid manifest")
                )
            }
            if (!decrypted.encryption.enabled || decrypted.encryption.cipherId != selected.cipherId) {
                runCatching { plain.delete() }
                return DshBackupValidation.Invalid(
                    DshMaintenanceFailure(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "cipher identity does not match the archive manifest"
                    )
                )
            }
            manifest = decrypted
        }
        manifest.validateShape()?.let {
            if (temporaryPlain) runCatching { plain.delete() }
            return DshBackupValidation.Invalid(it)
        }
        val declared = manifest.entries.associateBy { it.path }
        if (declared.size != manifest.entries.size) {
            if (temporaryPlain) runCatching { plain.delete() }
            return DshBackupValidation.Invalid(
                DshMaintenanceFailure(DshMaintenanceError.ARCHIVE_INVALID, "manifest repeats an entry path")
            )
        }
        var totalUncompressed = 0L
        var entryCount = 0
        val seen = HashSet<String>()
        var failure: DshMaintenanceFailure? = null
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(plain))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == MANIFEST_ENTRY_NAME) {
                        entry = zip.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "unexpected directory entry in the backup"
                        )
                        break
                    }
                    val normalized = DshMaintenanceIo.normalizeArchiveEntry(name, directory = false)
                    if (normalized == null) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_MALICIOUS,
                            "archive entry path escapes the restore root"
                        )
                        break
                    }
                    entryCount++
                    if (entryCount > limits.maxEntries) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_MALICIOUS,
                            "archive exceeds the entry budget"
                        )
                        break
                    }
                    val expected = declared[normalized]
                    if (expected == null) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "archive contains an entry that the manifest does not declare"
                        )
                        break
                    }
                    if (!seen.add(normalized)) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "archive repeats an entry"
                        )
                        break
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        read += count
                        totalUncompressed += count
                        if (read > limits.maxEntryBytes || totalUncompressed > limits.maxTotalBytes) {
                            failure = DshMaintenanceFailure(
                                DshMaintenanceError.ARCHIVE_MALICIOUS,
                                "archive exceeds the supported size budget"
                            )
                            break
                        }
                        digest.update(buffer, 0, count)
                    }
                    if (failure != null) break
                    val actualSha = digest.digest().toHex()
                    if (read != expected.bytes || actualSha != expected.sha256) {
                        failure = DshMaintenanceFailure(
                            DshMaintenanceError.ARCHIVE_INVALID,
                            "archive entry does not match its manifest hash/size"
                        )
                        break
                    }
                    if (entry.compressedSize > 0 && read > MIN_RATIO_CHECK_BYTES) {
                        val ratio = read / entry.compressedSize
                        if (ratio > limits.maxCompressionRatio) {
                            failure = DshMaintenanceFailure(
                                DshMaintenanceError.ARCHIVE_MALICIOUS,
                                "archive entry exceeds the compression-ratio bound"
                            )
                            break
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (io: IOException) {
            failure = DshMaintenanceFailure(
                DshMaintenanceError.ARCHIVE_INVALID,
                "archive is corrupt or unreadable",
                detail = io.message?.take(200)
            )
        }
        if (failure == null && seen.size != declared.size) {
            failure = DshMaintenanceFailure(
                DshMaintenanceError.ARCHIVE_INVALID,
                "archive is missing entries that the manifest declares"
            )
        }
        if (failure == null && manifest.totalBytes != manifest.entries.sumOf { it.bytes }) {
            failure = DshMaintenanceFailure(
                DshMaintenanceError.ARCHIVE_INVALID,
                "manifest total does not match its entry inventory"
            )
        }
        if (failure == null && totalUncompressed > MIN_RATIO_CHECK_BYTES &&
            totalUncompressed / plain.length().coerceAtLeast(1L) > limits.maxCompressionRatio
        ) {
            failure = DshMaintenanceFailure(
                DshMaintenanceError.ARCHIVE_MALICIOUS,
                "archive exceeds the compression-ratio bound"
            )
        }
        if (failure != null) {
            if (temporaryPlain) runCatching { plain.delete() }
            return DshBackupValidation.Invalid(failure)
        }
        return DshBackupValidation.Valid(manifest, plain, temporaryPlain)
    }

    /** Reads the manifest from a plain zip, or null when this is not a readable backup zip. */
    private fun readManifest(archive: File): DshBackupManifest? {
        if (!archive.isFile) return null
        return try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                var entry = zip.nextEntry
                var found: DshBackupManifest? = null
                while (entry != null) {
                    if (entry.name == MANIFEST_ENTRY_NAME && !entry.isDirectory) {
                        val bytes = readBounded(zip, MAX_MANIFEST_BYTES)
                        found = DshBackupManifestCodec.decode(bytes.toString(Charsets.UTF_8))
                        break
                    }
                    entry = zip.nextEntry
                }
                found
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("manifest exceeds its budget")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        const val MIN_RATIO_CHECK_BYTES = 256L * 1024L
    }
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

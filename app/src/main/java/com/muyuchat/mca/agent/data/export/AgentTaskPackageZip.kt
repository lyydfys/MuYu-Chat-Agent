package com.muyuchat.mca.agent.data.export

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP staging reader/writer for task packages.
 *
 * Validation uses [AgentTaskPackageCentralDirectory] for the raw central-directory pre-scan and then
 * ZipInputStream extraction so every byte and CRC is checked against the declared metadata.
 */
internal object AgentTaskPackageZip {
    private const val FIXED_ZIP_TIME = 315_532_800_000L // 1980-01-01, deterministic entry time
    private const val BUFFER = 64 * 1024

    fun scanArchive(
        archive: File,
        limits: AgentTaskPackageLimits
    ): AgentPackageResult<List<AgentPackageZipEntry>> = try {
        AgentPackageResult.Success(AgentTaskPackageCentralDirectory.scan(archive, limits))
    } catch (error: AgentPackageException) {
        AgentPackageResult.Failure(error.code, error.message ?: "archive rejected")
    } catch (_: IOException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.IO_FAILURE, "cannot read the archive")
    }

    fun writeArchive(
        target: File,
        manifestBytes: ByteArray,
        artifacts: List<AgentPackageZipSource>,
        limits: AgentTaskPackageLimits
    ): AgentPackageResult<Unit> = try {
        FileOutputStream(target, false).use { fileOut ->
            val buffered = BufferedOutputStream(fileOut, BUFFER)
            val zip = ZipOutputStream(buffered)
            try {
                putBytes(zip, AgentTaskPackageFormat.MANIFEST_ENTRY, manifestBytes)
                artifacts.sortedBy { it.entry.relativePath }.forEach { source ->
                    putFile(zip, source, limits)
                }
                zip.finish()
                zip.flush()
                buffered.flush()
                fileOut.fd.sync()
            } finally {
                runCatching { zip.close() }
            }
        }
        if (target.length() > limits.maxArchiveBytes) {
            AgentPackageResult.Failure(AgentPackageErrorCode.PACKAGE_TOO_LARGE, "package exceeds the size limit")
        } else {
            AgentPackageResult.Success(Unit)
        }
    } catch (error: AgentPackageException) {
        AgentPackageResult.Failure(error.code, error.message ?: "cannot write the package")
    } catch (_: IOException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.IO_FAILURE, "cannot write the package")
    }

    fun readManifest(
        archive: File,
        entries: List<AgentPackageZipEntry>,
        limits: AgentTaskPackageLimits
    ): AgentPackageResult<ByteArray> = try {
        val manifestEntries = entries.filter { it.name == AgentTaskPackageFormat.MANIFEST_ENTRY }
        if (manifestEntries.isEmpty()) {
            throw packageError(AgentPackageErrorCode.MISSING_MANIFEST, "manifest.json is missing")
        }
        if (manifestEntries.size != 1) {
            throw packageError(AgentPackageErrorCode.DUPLICATE_ENTRY, "manifest.json is duplicated")
        }
        if (manifestEntries.single().uncompressedSize > limits.maxManifestBytes) {
            throw packageError(AgentPackageErrorCode.ENTRY_TOO_LARGE, "manifest is too large")
        }
        AgentPackageResult.Success(
            withEntries<ByteArray>(archive) { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.name == AgentTaskPackageFormat.MANIFEST_ENTRY) {
                        return@withEntries readBounded(input, limits.maxManifestBytes)
                    }
                    input.closeEntry()
                }
                throw packageError(AgentPackageErrorCode.MISSING_MANIFEST, "manifest.json is missing")
            }
        )
    } catch (error: AgentPackageException) {
        AgentPackageResult.Failure(error.code, error.message ?: "archive rejected")
    } catch (_: ZipException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "archive is truncated or corrupt")
    } catch (_: IOException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.IO_FAILURE, "cannot read the archive")
    }

    fun extractArtifacts(
        archive: File,
        manifest: AgentTaskPackageManifest,
        stagingDirectory: File,
        limits: AgentTaskPackageLimits
    ): AgentPackageResult<List<AgentImportedArtifact>> = try {
        val expected = manifest.artifacts.associateBy { it.relativePath }
        val resolved = linkedMapOf<String, AgentImportedArtifact>()
        var totalBytes = 0L
        withEntries(archive) { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == AgentTaskPackageFormat.MANIFEST_ENTRY) {
                    input.closeEntry()
                    continue
                }
                val safeName = AgentPackagePathGuard.validateArchiveEntry(entry.name, limits)
                    ?: throw packageError(AgentPackageErrorCode.UNSAFE_PATH, "archive entry path is unsafe")
                val descriptor = expected[safeName]
                    ?: throw packageError(
                        AgentPackageErrorCode.UNREFERENCED_ENTRY,
                        "archive contains an entry not referenced by the manifest"
                    )
                if (resolved.containsKey(safeName)) {
                    throw packageError(AgentPackageErrorCode.DUPLICATE_ENTRY, "archive entry is duplicated")
                }
                val target = AgentPackagePathGuard.resolveStagedFile(stagingDirectory, safeName, limits)
                    ?: throw packageError(AgentPackageErrorCode.UNSAFE_PATH, "staged path is unsafe")
                target.parentFile?.mkdirs()
                val copied = copyEntryToFile(input, target, limits.maxEntryBytes)
                totalBytes += copied.sizeBytes
                if (totalBytes > limits.maxTotalExpandedBytes) {
                    throw packageError(
                        AgentPackageErrorCode.PACKAGE_TOO_LARGE,
                        "expanded package exceeds the size limit"
                    )
                }
                if (copied.sizeBytes != descriptor.sizeBytes) {
                    throw packageError(AgentPackageErrorCode.SIZE_MISMATCH, "artifact size mismatch")
                }
                if (copied.sha256 != descriptor.sha256) {
                    throw packageError(AgentPackageErrorCode.HASH_MISMATCH, "artifact hash mismatch")
                }
                resolved[safeName] = AgentImportedArtifact(
                    relativePath = safeName,
                    mediaType = descriptor.mediaType,
                    sizeBytes = copied.sizeBytes,
                    sha256 = copied.sha256,
                    stagedFile = target
                )
                input.closeEntry()
            }
        }
        val missing = expected.keys.filterNot { resolved.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw packageError(AgentPackageErrorCode.MISSING_ARTIFACT, "manifest artifact is missing from the archive")
        }
        AgentPackageResult.Success(resolved.values.sortedBy { it.relativePath })
    } catch (error: AgentPackageException) {
        AgentPackageResult.Failure(error.code, error.message ?: "archive rejected")
    } catch (_: ZipException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "archive is truncated or corrupt")
    } catch (_: IOException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.IO_FAILURE, "cannot extract the archive")
    }

    /**
     * Re-reads a just-written staging package: manifest must equal [expected] and every artifact is
     * hashed again. This is the exporter's copy-on-success proof before the caller's stream is touched.
     */
    fun verifyStaged(
        archive: File,
        expected: AgentTaskPackageManifest,
        limits: AgentTaskPackageLimits
    ): AgentPackageResult<Unit> = try {
        val entries = when (val scan = scanArchive(archive, limits)) {
            is AgentPackageResult.Success -> scan.value
            is AgentPackageResult.Failure -> throw packageError(scan.code, scan.safeMessage)
        }
        val manifestBytes = when (val result = readManifest(archive, entries, limits)) {
            is AgentPackageResult.Success -> result.value
            is AgentPackageResult.Failure -> throw packageError(result.code, result.safeMessage)
        }
        val parsed = when (val result = AgentTaskPackageManifestCodec.decode(manifestBytes, limits)) {
            is AgentPackageResult.Success -> result.value
            is AgentPackageResult.Failure -> throw packageError(result.code, result.safeMessage)
        }
        if (parsed.task != expected.task ||
            parsed.exportedAtMillis != expected.exportedAtMillis ||
            parsed.artifacts != expected.artifacts.sortedBy { it.relativePath }
        ) {
            throw packageError(AgentPackageErrorCode.INVALID_ARCHIVE, "staged manifest does not match")
        }
        val expectedEntries = expected.artifacts.map { it.relativePath }.toSet() +
            AgentTaskPackageFormat.MANIFEST_ENTRY
        if (entries.map { it.name }.toSet() != expectedEntries) {
            throw packageError(AgentPackageErrorCode.UNREFERENCED_ENTRY, "staged archive entry set mismatch")
        }
        withEntries(archive) { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == AgentTaskPackageFormat.MANIFEST_ENTRY) {
                    input.closeEntry()
                    continue
                }
                val descriptor = expected.artifacts.firstOrNull { it.relativePath == entry.name }
                    ?: throw packageError(AgentPackageErrorCode.UNREFERENCED_ENTRY, "unexpected staged entry")
                val copied = copyEntryToFile(input, null, limits.maxEntryBytes)
                if (copied.sizeBytes != descriptor.sizeBytes) {
                    throw packageError(AgentPackageErrorCode.SIZE_MISMATCH, "staged artifact size mismatch")
                }
                if (copied.sha256 != descriptor.sha256) {
                    throw packageError(AgentPackageErrorCode.HASH_MISMATCH, "staged artifact hash mismatch")
                }
                input.closeEntry()
            }
        }
        AgentPackageResult.Success(Unit)
    } catch (error: AgentPackageException) {
        AgentPackageResult.Failure(error.code, error.message ?: "staged package verification failed")
    } catch (_: ZipException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.TRUNCATED_ARCHIVE, "staged package is corrupt")
    } catch (_: IOException) {
        AgentPackageResult.Failure(AgentPackageErrorCode.IO_FAILURE, "cannot verify the staged package")
    }

    // --- central directory scan -------------------------------------------------------------
    private fun <T> withEntries(archive: File, block: (ZipInputStream) -> T): T {
        ZipInputStream(BufferedInputStream(FileInputStream(archive), BUFFER)).use { input ->
            return block(input)
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(BUFFER)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw packageError(AgentPackageErrorCode.ENTRY_TOO_LARGE, "entry exceeds the bounded size")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class Copied(val sizeBytes: Long, val sha256: String)

    private fun copyEntryToFile(
        input: InputStream,
        target: File?,
        maxBytes: Long
    ): Copied {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val rawOutput = target?.let { file ->
            file.parentFile?.mkdirs()
            FileOutputStream(file, false)
        }
        try {
            val bufferedOutput = rawOutput?.let { BufferedOutputStream(it, BUFFER) }
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw packageError(AgentPackageErrorCode.ENTRY_TOO_LARGE, "entry exceeds the size limit")
                }
                digest.update(buffer, 0, read)
                bufferedOutput?.write(buffer, 0, read)
            }
            if (bufferedOutput != null) {
                bufferedOutput.flush()
                rawOutput.fd.sync()
            }
        } finally {
            runCatching { rawOutput?.close() }
        }
        if (target != null) {
            target.setLastModified(FIXED_ZIP_TIME)
        }
        return Copied(total, digest.digest().toHex())
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = java.util.zip.ZipEntry(name)
        entry.time = FIXED_ZIP_TIME
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, source: AgentPackageZipSource, limits: AgentTaskPackageLimits) {
        if (!source.file.isFile) {
            throw packageError(AgentPackageErrorCode.ARTIFACT_FILE_MISSING, "artifact file is missing")
        }
        if (source.file.length() != source.entry.sizeBytes) {
            throw packageError(AgentPackageErrorCode.SIZE_MISMATCH, "artifact file size changed")
        }
        val entry = java.util.zip.ZipEntry(source.entry.relativePath)
        entry.time = FIXED_ZIP_TIME
        zip.putNextEntry(entry)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(source.file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limits.maxEntryBytes) {
                    throw packageError(AgentPackageErrorCode.ENTRY_TOO_LARGE, "artifact file is too large")
                }
                digest.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
        if (total != source.entry.sizeBytes) {
            throw packageError(AgentPackageErrorCode.SIZE_MISMATCH, "artifact file size changed")
        }
        if (digest.digest().toHex() != source.entry.sha256) {
            throw packageError(AgentPackageErrorCode.HASH_MISMATCH, "artifact file hash changed")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun packageError(code: AgentPackageErrorCode, message: String): AgentPackageException =
        AgentPackageException(code, message)
}

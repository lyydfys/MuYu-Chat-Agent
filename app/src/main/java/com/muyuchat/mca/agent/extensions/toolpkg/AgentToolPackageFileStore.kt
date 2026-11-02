package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.deleteRecursivelyWithWritable
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.hashFile
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.isInside
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.makeReadOnly
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.moveDirectory
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.moveFile
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoreFiles.writeFileWithFsync
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Outcome of previewing one archive. A rejected package never creates a managed snapshot. */
sealed interface AgentToolPackageIngestResult {
    data class Previewed(
        val stored: AgentToolPackageStoredPackage,
        val idempotent: Boolean
    ) : AgentToolPackageIngestResult

    /**
     * The invalid package was rejected. [quarantined] is true when its (bounded) bytes were moved to
     * the private quarantine area for diagnosis; no managed snapshot exists.
     */
    data class Rejected(
        val error: AgentToolPackageError,
        val quarantineId: String? = null,
        val quarantined: Boolean = false
    ) : AgentToolPackageIngestResult
}

/** Result of re-checking one stored preview against the files on disk. */
sealed interface AgentToolPackageVerificationResult {
    data class Valid(val stored: AgentToolPackageStoredPackage) : AgentToolPackageVerificationResult

    data class Missing(val packageId: String) : AgentToolPackageVerificationResult

    data class Invalid(
        val packageId: String,
        val error: AgentToolPackageError
    ) : AgentToolPackageVerificationResult
}

/**
 * Private preview store for manifest-first tool packages (P0-B).
 *
 * Responsibilities:
 * - copy a user-selected archive into a private staging area while enforcing the archive-size limit;
 * - parse/validate it with [AgentToolPackageValidator] (no partial import);
 * - write an immutable read-only snapshot under `managed/<packageId>/<contentSha256>/`;
 * - move invalid archives (bounded) to `quarantine/<time>-<uuid>/` with a content-free reason;
 * - keep an atomic `index.json`, reject a second **different** snapshot for the same package id
 *   (updates are a P1 lifecycle operation), and return an existing snapshot idempotently when the
 *   content hash is identical;
 * - re-verify hashes from disk on [verify], so a tampered snapshot is reported, not trusted.
 *
 * This store has **no enabled flag, no install action and no catalog registration**. Nothing in P0
 * can turn a stored preview into a run tool.
 */
class AgentToolPackageFileStore(
    rootDirectory: File,
    hostCatalog: AgentToolPackageHostCatalog,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val root: File = rootDirectory.absoluteFile
    private val managedRoot = File(root, MANAGED_DIRECTORY)
    private val quarantineRoot = File(root, QUARANTINE_DIRECTORY)
    private val indexFile = File(root, INDEX_FILE_NAME)
    private val lock = Any()
    private val quarantineLock = Any()
    private val records = LinkedHashMap<String, AgentToolPackageStoreRecord>()
    private val parser = AgentToolPackageManifestParser(hostCatalog)
    private val validator = AgentToolPackageValidator(hostCatalog)

    init {
        if (!root.isDirectory && !root.mkdirs()) {
            failStore("cannot create the private tool-package store root")
        }
        if (!managedRoot.isDirectory && !managedRoot.mkdirs()) {
            failStore("cannot create the private tool-package managed directory")
        }
        if (!quarantineRoot.isDirectory && !quarantineRoot.mkdirs()) {
            failStore("cannot create the private tool-package quarantine directory")
        }
        if (indexFile.exists() && !indexFile.isFile) {
            failStore("the tool-package store index is not a regular file")
        }
        if (indexFile.isFile) loadIndex()
    }

    /** Packages currently indexed, sorted by id. This is metadata only (no disk hash pass). */
    fun summaries(): List<AgentToolPackageSummary> = synchronized(lock) {
        records.values.sortedBy { it.packageId }.map { record ->
            AgentToolPackageSummary(
                packageId = record.packageId,
                packageVersion = record.packageVersion,
                displayName = record.displayName,
                manifestSha256 = record.manifestSha256,
                contentSha256 = record.contentSha256,
                archiveSha256 = record.archiveSha256,
                toolCount = manifestToolCount(record),
                resourceCount = record.resources.size,
                storedAtMillis = record.storedAtMillis
            )
        }
    }

    /** Verified stored package, or null when no preview exists. Corruption is surfaced, not hidden. */
    fun find(packageId: String): AgentToolPackageStoredPackage? =
        when (val verification = verify(packageId)) {
            is AgentToolPackageVerificationResult.Valid -> verification.stored
            is AgentToolPackageVerificationResult.Missing -> null
            is AgentToolPackageVerificationResult.Invalid ->
                throw AgentToolPackageException(verification.error)
        }

    /** Re-hashes the snapshot from disk. Safe to call repeatedly; never mutates the store. */
    fun verify(packageId: String): AgentToolPackageVerificationResult = synchronized(lock) {
        val record = records[packageId]
            ?: return@synchronized AgentToolPackageVerificationResult.Missing(packageId)
        verifyLocked(record)
    }

    /**
     * Validates [archiveFile] and stores a private preview snapshot. Identical content is
     * idempotent; different content for an existing package id is rejected in P0.
     */
    fun preview(sourceUri: String, archiveFile: File): AgentToolPackageIngestResult {
        val sourceText = try {
            validateSourceUri(sourceUri)
        } catch (failure: AgentToolPackageException) {
            return AgentToolPackageIngestResult.Rejected(failure.error)
        }
        if (!archiveFile.isFile) {
            return AgentToolPackageIngestResult.Rejected(
                AgentToolPackageError(AgentToolPackageErrorCode.MALFORMED_ZIP, "the selected archive is not a file")
            )
        }
        if (archiveFile.length() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            return AgentToolPackageIngestResult.Rejected(
                AgentToolPackageError(
                    AgentToolPackageErrorCode.PACKAGE_TOO_LARGE,
                    "archive exceeds ${AgentToolPackageLimits.MAX_ARCHIVE_BYTES} bytes"
                )
            )
        }

        val stagingRoot = File(root, ".staging-${UUID.randomUUID()}")
        if (!stagingRoot.mkdirs()) {
            return AgentToolPackageIngestResult.Rejected(
                AgentToolPackageError(AgentToolPackageErrorCode.STORE_FAILURE, "cannot create the preview staging directory")
            )
        }
        val copiedArchive = File(stagingRoot, "archive.bin")
        try {
            val archiveHash = try {
                copyArchiveBounded(archiveFile, copiedArchive)
            } catch (failure: AgentToolPackageException) {
                return AgentToolPackageIngestResult.Rejected(failure.error)
            } catch (_: Exception) {
                return AgentToolPackageIngestResult.Rejected(
                    AgentToolPackageError(AgentToolPackageErrorCode.STORE_FAILURE, "the archive could not be copied")
                )
            }
            val source = AgentToolPackageSource(
                sourceUri = sourceText,
                kind = AgentToolPackageSourceKind.ZIP,
                archiveSizeBytes = copiedArchive.length(),
                archiveSha256 = archiveHash
            )

            val snapshotStaging = File(stagingRoot, "snapshot")
            var validated: AgentToolPackageValidated? = null
            var failure: AgentToolPackageError? = null
            try {
                AgentToolPackageZipArchive(FileToolPackageSource(copiedArchive)).use { archive ->
                    val content = AgentToolPackageZipContentSource(archive)
                    val validatedContent = validator.validate(source, content)
                    validated = validatedContent
                    writeSnapshotStaging(
                        snapshotStaging,
                        validatedContent,
                        content,
                        recordFor(validatedContent, source)
                    )
                }
            } catch (packageFailure: AgentToolPackageException) {
                failure = packageFailure.error
            } catch (_: IOException) {
                failure = AgentToolPackageError(
                    AgentToolPackageErrorCode.MALFORMED_ZIP,
                    "the archive could not be read"
                )
            } catch (_: Exception) {
                failure = AgentToolPackageError(
                    AgentToolPackageErrorCode.STORE_FAILURE,
                    "the archive could not be prepared for preview"
                )
            }

            if (failure != null) {
                return rejectAndQuarantine(stagingRoot, copiedArchive, source, failure)
            }
            val validatedContent = validated ?: return rejectAndQuarantine(
                stagingRoot,
                copiedArchive,
                source,
                AgentToolPackageError(
                    AgentToolPackageErrorCode.STORE_FAILURE,
                    "the archive produced no validated preview"
                )
            )
            return commit(validatedContent, source, snapshotStaging, copiedArchive)
        } finally {
            deleteRecursivelyWithWritable(stagingRoot)
        }
    }

    /** Removes one preview snapshot. This is maintenance only; there is no enable/disable action. */
    fun remove(packageId: String) {
        synchronized(lock) {
            val record = records[packageId]
                ?: throw AgentToolPackageException(
                    AgentToolPackageError(AgentToolPackageErrorCode.PACKAGE_NOT_FOUND, "no such previewed package")
                )
            val snapshotDir = snapshotDirectory(record)
            if (!snapshotDir.exists()) {
                records.remove(packageId)
                persistIndexLocked()
                return@synchronized
            }
            val trash = File(root, ".trash-${UUID.randomUUID()}")
            try {
                moveDirectory(snapshotDir, trash)
            } catch (_: Exception) {
                failStore("the preview snapshot could not be moved for removal")
            }
            records.remove(packageId)
            try {
                persistIndexLocked()
            } catch (failure: AgentToolPackageException) {
                records[packageId] = record
                runCatching { moveDirectory(trash, snapshotDir) }
                throw failure
            }
            deleteRecursivelyWithWritable(trash)
        }
    }

    private fun commit(
        validated: AgentToolPackageValidated,
        source: AgentToolPackageSource,
        snapshotStaging: File,
        copiedArchive: File
    ): AgentToolPackageIngestResult = synchronized(lock) {
        val manifest = validated.manifest
        val existing = records[manifest.packageId]
        if (existing != null) {
            if (existing.contentSha256 == validated.contentSha256) {
                return@synchronized when (val verification = verifyLocked(existing)) {
                    is AgentToolPackageVerificationResult.Valid ->
                        AgentToolPackageIngestResult.Previewed(verification.stored, idempotent = true)
                    is AgentToolPackageVerificationResult.Invalid ->
                        AgentToolPackageIngestResult.Rejected(verification.error)
                    is AgentToolPackageVerificationResult.Missing -> AgentToolPackageIngestResult.Rejected(
                        AgentToolPackageError(AgentToolPackageErrorCode.STORE_FAILURE, "the stored preview disappeared")
                    )
                }
            }
            val duplicate = AgentToolPackageError(
                AgentToolPackageErrorCode.DUPLICATE_PACKAGE_ID,
                "a different preview already exists for this package id; updates are a later lifecycle step"
            )
            val quarantineId = quarantine(copiedArchive, source, duplicate)
            return@synchronized AgentToolPackageIngestResult.Rejected(
                duplicate,
                quarantineId = quarantineId,
                quarantined = quarantineId != null
            )
        }

        val finalDirectory = snapshotDirectory(manifest.packageId, validated.contentSha256)
        if (finalDirectory.exists()) {
            val orphan = AgentToolPackageError(
                AgentToolPackageErrorCode.STORE_FAILURE,
                "a private snapshot exists without an index entry"
            )
            val quarantineId = quarantine(copiedArchive, source, orphan)
            return@synchronized AgentToolPackageIngestResult.Rejected(
                orphan,
                quarantineId = quarantineId,
                quarantined = quarantineId != null
            )
        }
        val record = recordFor(validated, source)
        try {
            val packageRoot = finalDirectory.parentFile ?: failStore("invalid snapshot directory")
            if (!packageRoot.isDirectory && !packageRoot.mkdirs()) {
                failStore("cannot create the package snapshot directory")
            }
            moveDirectory(snapshotStaging, finalDirectory)
            makeReadOnly(finalDirectory)
            records[manifest.packageId] = record
            try {
                persistIndexLocked()
            } catch (failure: AgentToolPackageException) {
                records.remove(manifest.packageId)
                deleteRecursivelyWithWritable(finalDirectory)
                throw failure
            }
        } catch (failure: AgentToolPackageException) {
            return@synchronized AgentToolPackageIngestResult.Rejected(failure.error)
        } catch (_: Exception) {
            records.remove(manifest.packageId)
            if (finalDirectory.exists()) deleteRecursivelyWithWritable(finalDirectory)
            return@synchronized AgentToolPackageIngestResult.Rejected(
                AgentToolPackageError(AgentToolPackageErrorCode.STORE_FAILURE, "the preview snapshot could not be stored")
            )
        }
        AgentToolPackageIngestResult.Previewed(
            stored = AgentToolPackageStoredPackage(
                preview = validated.preview,
                snapshotId = record.snapshotId,
                storedAtMillis = record.storedAtMillis
            ),
            idempotent = false
        )
    }

    private fun rejectAndQuarantine(
        stagingRoot: File,
        copiedArchive: File,
        source: AgentToolPackageSource,
        error: AgentToolPackageError
    ): AgentToolPackageIngestResult {
        if (error.code == AgentToolPackageErrorCode.PACKAGE_TOO_LARGE) {
            return AgentToolPackageIngestResult.Rejected(error)
        }
        val quarantineId = quarantine(copiedArchive, source, error)
        deleteRecursivelyWithWritable(stagingRoot)
        return AgentToolPackageIngestResult.Rejected(
            error,
            quarantineId = quarantineId,
            quarantined = quarantineId != null
        )
    }

    private fun verifyLocked(record: AgentToolPackageStoreRecord): AgentToolPackageVerificationResult {
        val snapshotDir = snapshotDirectory(record)
        if (!snapshotDir.isDirectory) {
            return invalid(record.packageId, PACKAGE_CORRUPT_DESCRIPTION)
        }
        if (!isInside(managedRoot, snapshotDir)) {
            return invalid(record.packageId, "the stored snapshot escaped the managed directory")
        }
        val manifestFile = File(snapshotDir, AgentToolPackageLimits.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile || manifestFile.length() > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
            return invalid(record.packageId, "the stored manifest is missing or too large")
        }
        val manifest = try {
            parser.parse(manifestFile.readBytes())
        } catch (failure: AgentToolPackageException) {
            return AgentToolPackageVerificationResult.Invalid(record.packageId, failure.error)
        } catch (_: Exception) {
            return invalid(record.packageId, "the stored manifest could not be read")
        }
        if (manifest.packageId != record.packageId ||
            manifest.packageVersion != record.packageVersion ||
            manifest.manifestSha256 != record.manifestSha256
        ) {
            return invalid(record.packageId, "the stored manifest does not match the index")
        }

        var totalExpanded = manifestFile.length()
        for (resource in record.resources) {
            val file = File(snapshotDir, resource.path)
            if (!file.isFile) {
                return invalid(record.packageId, "a declared resource is missing from the snapshot")
            }
            if (!isInside(snapshotDir, file)) {
                return invalid(record.packageId, "a stored resource escaped the snapshot directory")
            }
            if (file.length() != resource.sizeBytes || file.length() > AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES) {
                return invalid(record.packageId, "a stored resource changed size")
            }
            val actualHash = try {
                hashFile(file)
            } catch (_: Exception) {
                return invalid(record.packageId, "a stored resource could not be read")
            }
            if (actualHash != resource.sha256) {
                return invalid(record.packageId, "a stored resource no longer matches its hash")
            }
            totalExpanded += resource.sizeBytes
        }
        if (totalExpanded > AgentToolPackageLimits.MAX_EXPANDED_BYTES) {
            return invalid(record.packageId, "the stored snapshot exceeds the expanded size limit")
        }

        val expectedPaths = HashSet<String>()
        expectedPaths += AgentToolPackageLimits.MANIFEST_FILE_NAME
        expectedPaths += AgentToolPackageLimits.RECORD_FILE_NAME
        record.resources.forEach { expectedPaths += it.path }
        val actualPaths = snapshotDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(snapshotDir).invariantSeparatorsPath }
            .toSet()
        if (actualPaths != expectedPaths) {
            return invalid(record.packageId, "the stored snapshot contains unexpected or missing files")
        }
        val recordFile = File(snapshotDir, AgentToolPackageLimits.RECORD_FILE_NAME)
        val recordBytes = try {
            if (recordFile.length() > AgentToolPackageLimits.MAX_STORE_INDEX_BYTES) {
                return invalid(record.packageId, "the stored record is too large")
            }
            recordFile.readBytes()
        } catch (_: Exception) {
            return invalid(record.packageId, "the stored record could not be read")
        }
        val storedRecord = try {
            AgentToolPackageStoreCodec.parseRecord(ToolPackageJson.parseUtf8(recordBytes))
        } catch (_: Exception) {
            return invalid(record.packageId, "the stored record is corrupt")
        }
        if (storedRecord.packageId != record.packageId ||
            storedRecord.contentSha256 != record.contentSha256 ||
            storedRecord.manifestSha256 != record.manifestSha256 ||
            storedRecord.resources != record.resources
        ) {
            return invalid(record.packageId, "the stored record does not match the index")
        }

        val computedContentHash = try {
            AgentToolPackageContentHash.compute(manifest.manifestSha256, record.resources)
        } catch (failure: AgentToolPackageException) {
            return AgentToolPackageVerificationResult.Invalid(record.packageId, failure.error)
        }
        if (computedContentHash != record.contentSha256) {
            return invalid(record.packageId, "the stored content hash no longer matches")
        }
        val source = try {
            AgentToolPackageSource(
                sourceUri = record.sourceUri,
                kind = AgentToolPackageSourceKind.ZIP,
                archiveSizeBytes = record.archiveSizeBytes,
                archiveSha256 = record.archiveSha256
            )
        } catch (_: Exception) {
            return invalid(record.packageId, "the stored source metadata is invalid")
        }
        val bindings = try {
            validator.bindings(manifest, computedContentHash)
        } catch (failure: AgentToolPackageException) {
            return AgentToolPackageVerificationResult.Invalid(record.packageId, failure.error)
        }
        val preview = try {
            validator.preview(source, manifest, computedContentHash, record.resources, bindings, totalExpanded)
        } catch (failure: AgentToolPackageException) {
            return AgentToolPackageVerificationResult.Invalid(record.packageId, failure.error)
        }
        return AgentToolPackageVerificationResult.Valid(
            AgentToolPackageStoredPackage(
                preview = preview,
                snapshotId = record.snapshotId,
                storedAtMillis = record.storedAtMillis
            )
        )
    }

    private fun writeSnapshotStaging(
        snapshotDirectory: File,
        validated: AgentToolPackageValidated,
        content: AgentToolPackageContentSource,
        record: AgentToolPackageStoreRecord
    ) {
        if (!snapshotDirectory.mkdirs()) {
            failStore("cannot create the preview snapshot staging directory")
        }
        val manifestBytes = validated.manifest.canonicalJson.toByteArray(Charsets.UTF_8)
        writeFileWithFsync(File(snapshotDirectory, AgentToolPackageLimits.MANIFEST_FILE_NAME), manifestBytes)

        val filesByPath = content.files.associateBy { it.path }
        for (resource in validated.resources.sortedBy { it.path }) {
            val sourceFile = filesByPath[resource.path]
                ?: toolPackageFailure(
                    AgentToolPackageErrorCode.PACKAGE_CORRUPT,
                    "validated resource disappeared before the snapshot write",
                    resource.path
                )
            val destination = File(snapshotDirectory, resource.path)
            if (!isInside(snapshotDirectory, destination)) {
                failStore("a resource path escaped the snapshot staging directory")
            }
            destination.parentFile?.mkdirs()
            val (size, hash) = try {
                copyResourceAndHash(content, sourceFile, destination)
            } catch (failure: AgentToolPackageException) {
                throw failure
            } catch (_: Exception) {
                failStore("a preview resource could not be written")
            }
            if (size != resource.sizeBytes || hash != resource.sha256) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.PACKAGE_CORRUPT,
                    "a resource changed while the snapshot was written",
                    resource.path
                )
            }
        }
        writeFileWithFsync(
            File(snapshotDirectory, AgentToolPackageLimits.RECORD_FILE_NAME),
            ToolPackageJson.canonical(AgentToolPackageStoreCodec.encodeRecord(record)).toByteArray(Charsets.UTF_8)
        )
    }

    private fun copyResourceAndHash(
        content: AgentToolPackageContentSource,
        source: AgentToolPackageContentFile,
        destination: File
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        content.open(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES) {
                        toolPackageFailure(
                            AgentToolPackageErrorCode.ENTRY_SIZE_EXCEEDED,
                            "resource exceeds the per-entry size limit",
                            source.path
                        )
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
        return total to ToolPackageJson.toHex(digest.digest())
    }

    private fun recordFor(
        validated: AgentToolPackageValidated,
        source: AgentToolPackageSource
    ): AgentToolPackageStoreRecord = AgentToolPackageStoreRecord(
        packageId = validated.manifest.packageId,
        packageVersion = validated.manifest.packageVersion,
        displayName = validated.manifest.displayName,
        manifestSha256 = validated.manifest.manifestSha256,
        contentSha256 = validated.contentSha256,
        archiveSha256 = source.archiveSha256,
        sourceUri = source.sourceUri,
        archiveSizeBytes = source.archiveSizeBytes,
        storedAtMillis = clock().coerceAtLeast(0L),
        snapshotId = toolPackageSnapshotId(validated.manifest.packageId, validated.contentSha256),
        resources = validated.resources
    )

    private fun loadIndex() {
        if (indexFile.length() > AgentToolPackageLimits.MAX_STORE_INDEX_BYTES) {
            failStore("the preview store index exceeds its size limit")
        }
        val loaded = try {
            AgentToolPackageStoreCodec.parseIndex(indexFile.readBytes())
        } catch (failure: AgentToolPackageException) {
            throw failure
        } catch (_: Exception) {
            failStore("the preview store index is corrupt")
        }
        for (record in loaded) {
            if (!snapshotDirectory(record).isDirectory) {
                failStore("a preview store index entry has no snapshot directory")
            }
        }
        records.clear()
        loaded.forEach { record -> records[record.packageId] = record }
    }

    private fun persistIndexLocked() {
        val bytes = AgentToolPackageStoreCodec.encodeIndex(records.values)
        val temp = File(root, "$INDEX_FILE_NAME.${UUID.randomUUID()}.tmp")
        try {
            writeFileWithFsync(temp, bytes)
            moveFile(temp, indexFile, replace = true)
        } catch (failure: AgentToolPackageException) {
            temp.delete()
            throw failure
        } catch (_: Exception) {
            temp.delete()
            failStore("the preview store index could not be written")
        }
    }

    private fun quarantine(
        archive: File,
        source: AgentToolPackageSource,
        error: AgentToolPackageError
    ): String? {
        if (!archive.isFile) return null
        synchronized(quarantineLock) {
            val quarantinedAt = clock().coerceAtLeast(0L)
            val quarantineId = quarantinedAt.toString().padStart(13, '0') + "-" + UUID.randomUUID()
            val directory = File(quarantineRoot, quarantineId)
            if (!directory.mkdirs()) return null
            try {
                moveFile(archive, File(directory, "archive.bin"), replace = false)
            } catch (_: Exception) {
                deleteRecursivelyWithWritable(directory)
                return null
            }
            val reason = ToolPackageJson.objectOf(
                "code" to ToolPackageJson.string(error.code.name),
                "message" to ToolPackageJson.string(error.message),
                "sourceUri" to ToolPackageJson.string(source.sourceUri),
                "archiveSha256" to ToolPackageJson.string(source.archiveSha256),
                "archiveSizeBytes" to ToolPackageJson.integer(source.archiveSizeBytes),
                "quarantinedAtMillis" to ToolPackageJson.integer(quarantinedAt)
            )
            runCatching {
                writeFileWithFsync(
                    File(directory, "reason.json"),
                    ToolPackageJson.canonical(reason).toByteArray(Charsets.UTF_8)
                )
            }
            pruneQuarantineLocked()
            return quarantineId
        }
    }

    private fun pruneQuarantineLocked() {
        val directories = quarantineRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()
        if (directories.size <= AgentToolPackageLimits.MAX_QUARANTINE_COPIES) return
        directories
            .take(directories.size - AgentToolPackageLimits.MAX_QUARANTINE_COPIES)
            .forEach { deleteRecursivelyWithWritable(it) }
    }

    private fun copyArchiveBounded(source: File, destination: File): String {
        if (source.length() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            toolPackageFailure(
                AgentToolPackageErrorCode.PACKAGE_TOO_LARGE,
                "archive exceeds ${AgentToolPackageLimits.MAX_ARCHIVE_BYTES} bytes"
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
                        toolPackageFailure(
                            AgentToolPackageErrorCode.PACKAGE_TOO_LARGE,
                            "archive exceeds ${AgentToolPackageLimits.MAX_ARCHIVE_BYTES} bytes"
                        )
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
        return ToolPackageJson.toHex(digest.digest())
    }

    private fun validateSourceUri(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > AgentToolPackageLimits.MAX_SOURCE_URI_CHARS ||
            trimmed.any { it.isISOControl() }
        ) {
            toolPackageFailure(AgentToolPackageErrorCode.INVALID_SOURCE, "the package source URI is invalid")
        }
        return trimmed
    }

    private fun snapshotDirectory(record: AgentToolPackageStoreRecord): File =
        snapshotDirectory(record.packageId, record.contentSha256)

    private fun snapshotDirectory(packageId: String, contentSha256: String): File =
        File(File(managedRoot, packageId), contentSha256)

    private fun manifestToolCount(record: AgentToolPackageStoreRecord): Int = try {
        val manifestFile = File(snapshotDirectory(record), AgentToolPackageLimits.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) 0 else parser.parse(manifestFile.readBytes()).tools.size
    } catch (_: Exception) {
        0
    }

    private fun invalid(packageId: String, message: String): AgentToolPackageVerificationResult.Invalid =
        AgentToolPackageVerificationResult.Invalid(
            packageId,
            AgentToolPackageError(AgentToolPackageErrorCode.PACKAGE_CORRUPT, message)
        )

    private fun failStore(message: String): Nothing =
        toolPackageFailure(AgentToolPackageErrorCode.STORE_FAILURE, message)

    private companion object {
        const val MANAGED_DIRECTORY = "managed"
        const val QUARANTINE_DIRECTORY = "quarantine"
        const val INDEX_FILE_NAME = "index.json"
        const val COPY_BUFFER = 16 * 1024
        const val PACKAGE_CORRUPT_DESCRIPTION = "the stored preview snapshot is missing"
    }
}

package com.muyuchat.mca.agent.toolpack

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One durable activation record: which immutable content is active for which package id. */
data class ToolPackageInstalledRecord(
    val packageId: String,
    val packageVersion: String,
    val contentHash: String,
    val enabled: Boolean,
    val installedAtMillis: Long,
    val revision: Long,
    /**
     * Content of the previously active version (rollback target). It stays referenced by
     * [ToolPackageInstallStore.collectGarbage] so a rollback still works after an update.
     */
    val previousContentHash: String? = null,
    /** Set by an explicit user revocation; an enable is refused until the revocation is cleared. */
    val revokedAtMillis: Long? = null
) {
    init {
        require(packageId.isNotBlank()) { "installed record packageId must not be blank" }
        require(packageVersion.isNotBlank()) { "installed record packageVersion must not be blank" }
        require(contentHash.matches(Regex("^[0-9a-f]{64}$"))) { "installed content hash must be lowercase SHA-256" }
        require(previousContentHash == null || previousContentHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "previous content hash must be lowercase SHA-256"
        }
        require(previousContentHash == null || previousContentHash != contentHash) {
            "previous content hash must differ from the active content hash"
        }
        require(revokedAtMillis == null || revokedAtMillis > 0L) { "revocation timestamp must be positive" }
        require(revision > 0L) { "installed record revision must be positive" }
    }

    val revoked: Boolean get() = revokedAtMillis != null

    /** Content this record still makes reachable (active + rollback target). */
    val referencedContentHashes: List<String>
        get() = listOfNotNull(contentHash, previousContentHash).distinct()
}

sealed interface ToolPackageInstallResult {
    data class Installed(val record: ToolPackageInstalledRecord, val reusedContent: Boolean) :
        ToolPackageInstallResult

    data class Rejected(val code: String, val safeMessage: String) : ToolPackageInstallResult
}

sealed interface ToolPackageVerifyResult {
    data class Valid(
        val bundle: ExecutableToolPackage,
        val record: ToolPackageInstalledRecord?
    ) : ToolPackageVerifyResult

    data class Rejected(val code: String, val safeMessage: String) : ToolPackageVerifyResult
}

sealed interface ToolPackageActivationResult {
    data class Activated(val record: ToolPackageInstalledRecord) : ToolPackageActivationResult

    data class Rejected(val code: String, val safeMessage: String) : ToolPackageActivationResult
}

data class ToolPackageInstallScan(
    val records: List<ToolPackageInstalledRecord>,
    val unreadableStateFiles: List<String>
)

/** One explicit, user-visible lifecycle transition of the durable activation pointer. */
sealed interface ToolPackageStateChange {
    val packageId: String

    /** Activate the currently installed content (refused while the package is revoked). */
    data class Enable(override val packageId: String) : ToolPackageStateChange

    /** Keep the content installed but stop contributing tools. */
    data class Disable(override val packageId: String) : ToolPackageStateChange

    /** Explicit revocation: disable and refuse enable until [ClearRevocation]. */
    data class Revoke(override val packageId: String, val atMillis: Long) : ToolPackageStateChange

    data class ClearRevocation(override val packageId: String) : ToolPackageStateChange

    /** Swap the active content with [ToolPackageInstalledRecord.previousContentHash]. */
    data class Rollback(override val packageId: String) : ToolPackageStateChange

    /** Delete the durable record; content becomes eligible for reference GC. */
    data class Remove(override val packageId: String) : ToolPackageStateChange
}

sealed interface ToolPackageStateChangeResult {
    /** [record] is null only for [ToolPackageStateChange.Remove]. */
    data class Applied(val record: ToolPackageInstalledRecord?) : ToolPackageStateChangeResult

    data class Rejected(val code: String, val safeMessage: String) : ToolPackageStateChangeResult
}

data class ToolPackageRecoveryReport(
    val repairedTransactions: Int,
    val deletedContentHashes: List<String>,
    val deletedStagingDirectories: Int
)

data class ToolPackageGcReport(
    val deletedContentHashes: List<String>,
    val deletedDanglingStateRecords: List<String>,
    val keptContentHashes: List<String>
)

/** Thrown by the test-only fault seam to simulate a process death *without* in-process rollback. */
internal class ToolPackageSimulatedCrash(phase: String) : RuntimeException("simulated crash at $phase")

/**
 * File-backed install store for executable packages (N09a minimal transaction).
 *
 * Layout under [root] (all paths are app-private, never exposed to a script):
 *
 * ```text
 * content/<contentHash>/manifest.json
 * content/<contentHash>/scripts/<normalised path>
 * state/<packageId>.json          # active pointer + enabled flag, written atomically
 * staging/<txnId>/...             # never reachable by a run
 * journal/<txnId>.json            # crash evidence for recover()
 * ```
 *
 * Guarantees of this slice:
 * - **content-addressed**: the directory name is the package content hash; installing identical
 *   bytes twice reuses the same immutable content and returns `reusedContent=true`;
 * - **verify before activation**: [enable] re-reads and re-hashes the stored bytes, so a tampered
 *   package cannot become active even if its state record says `enabled`;
 * - **write-capable packages are refused activation** because the production catalog has no
 *   per-call approval owner injected (the same fail-closed rule [ToolPackageToolCatalog.project]
 *   applies); the bridge would refuse every nested write anyway;
 * - **single atomic active pointer**: a state record is replaced via temp file + atomic move, so a
 *   reader sees either the old or the new record, never a half-written one;
 * - **minimal transaction**: an in-process failure rolls the content/state/staging back to the
 *   previous record; [recover] repairs a crash that happened between the stages;
 * - **reference GC**: [collectGarbage] removes only content that no state record references and that
 *   the caller did not protect as a live run reference. The reference walk follows the exact
 *   dependency lock of every reachable stored manifest, so a dependency stays usable while any
 *   installed parent (or its rollback target) still declares it;
 * - **exact dependency admission**: [install] resolves every declared dependency against already
 *   stored records/content before it writes a byte. Missing, unprovisioned, mismatched or cyclic
 *   dependencies are typed rejections that leave the previous activation untouched.
 *
 * This store never fetches, downloads or executes dependencies or scripts and never touches Room.
 * Every method performs file I/O and must be called from an IO dispatcher (it switches internally).
 */
class ToolPackageInstallStore(
    root: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val faultInjector: (phase: String) -> Unit = {},
    private val writeCapabilityPolicy: ToolPackageWriteCapabilityPolicy = ToolPackageWriteCapabilityPolicy.REFUSE
) {
    private val rootDir = root.absoluteFile
    private val contentDir = File(rootDir, CONTENT)
    private val stateDir = File(rootDir, STATE)
    private val stagingDir = File(rootDir, STAGING)
    private val journalDir = File(rootDir, JOURNAL)

    suspend fun install(
        manifestBytes: ByteArray,
        scripts: Map<String, String>
    ): ToolPackageInstallResult = withContext(Dispatchers.IO) {
        val bundle = try {
            ExecutableToolPackageLoader().load(manifestBytes, scripts)
        } catch (failure: ToolPackageException) {
            return@withContext ToolPackageInstallResult.Rejected(
                failure.error.code.agentCode,
                failure.error.safeMessage
            )
        }
        val hash = bundle.contentHash
        // Resolve before the transaction starts: a dependency rejection must not move content or the
        // activation pointer. Nothing below runs (and no journal is written) on a rejection.
        val dependencyProblem = resolveDependencies(bundle.manifest)
        if (dependencyProblem is ToolPackageDependencyResolution.Rejected) {
            return@withContext ToolPackageInstallResult.Rejected(
                dependencyProblem.code,
                dependencyProblem.safeMessage
            )
        }
        val previous = readStateByPackageId(bundle.packageId)
        val txnId = "txn-" + hash.take(12) + "-" + clockMillis()
        val txn = ToolPackageJournalEntry(
            txnId = txnId,
            contentHash = hash,
            packageId = bundle.packageId,
            phase = PHASE_STAGING
        )
        var createdContent = false
        try {
            ensureDirectories()
            writeJournal(txn)
            val existing = contentHashDirectory(hash)
            val reuse = existing.isDirectory && verifyStoredContent(hash) is ToolPackageVerifyResult.Valid
            if (!reuse) {
                if (existing.exists()) deleteRecursively(existing)
                val staging = File(stagingDir, txnId)
                deleteRecursively(staging)
                writeContent(staging, manifestBytes, scripts)
                faultInjector(STAGE_AFTER_STAGING)
                moveAtomically(staging, existing)
                createdContent = true
            }
            faultInjector(STAGE_AFTER_CONTENT)
            val sameContent = previous != null && previous.contentHash == hash
            val record = ToolPackageInstalledRecord(
                packageId = bundle.packageId,
                packageVersion = bundle.packageVersion,
                contentHash = hash,
                // Re-installing the exact same content keeps its activation and revocation;
                // different bytes need a fresh enable step (N09: a new version is never silently
                // pre-approved) and start a new, un-revoked lifecycle.
                enabled = sameContent && previous != null && previous.enabled,
                installedAtMillis = clockMillis(),
                revision = (previous?.revision ?: 0L) + 1L,
                previousContentHash = when {
                    previous == null -> null
                    sameContent -> previous.previousContentHash
                    else -> previous.contentHash
                },
                revokedAtMillis = if (sameContent) previous?.revokedAtMillis else null
            )
            writeState(record)
            faultInjector(STAGE_AFTER_STATE)
            deleteJournal(txnId)
            faultInjector(STAGE_AFTER_JOURNAL)
            ToolPackageInstallResult.Installed(record, reusedContent = !createdContent)
        } catch (crash: ToolPackageSimulatedCrash) {
            // Simulated process death: leave journal/staging/content in place for recover() to repair.
            throw crash
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            rollbackInProcess(txnId, bundle.packageId, hash, previous, createdContent)
            throw cancelled
        } catch (failure: Exception) {
            rollbackInProcess(txnId, bundle.packageId, hash, previous, createdContent)
            ToolPackageInstallResult.Rejected(
                "TOOLPACK_INSTALL_FAILED",
                "The package could not be installed; the previous activation was kept."
            )
        }
    }

    /** Durable record of one package id, or null when it is not installed. */
    suspend fun record(packageId: String): ToolPackageInstalledRecord? = withContext(Dispatchers.IO) {
        readStateByPackageId(packageId)
    }

    /**
     * Atomic lifecycle transition of the active pointer.
     *
     * This is the **only** writer for enable/disable/revoke/rollback/remove, so the state file can
     * never be half-updated: every branch validates the current record, builds the next immutable
     * record and replaces the state file with one atomic move. Callers that need package-definition
     * policy (write-capability refusal, full content verification) do it in
     * [ToolPackageLifecycleService] *before* calling here.
     */
    suspend fun apply(change: ToolPackageStateChange): ToolPackageStateChangeResult =
        withContext(Dispatchers.IO) {
            val current = readStateByPackageId(change.packageId)
                ?: return@withContext ToolPackageStateChangeResult.Rejected(
                    "TOOLPACK_NOT_INSTALLED",
                    "No installed package matches this id."
                )
            val next: ToolPackageInstalledRecord? = when (change) {
                is ToolPackageStateChange.Enable -> {
                    if (current.revoked) {
                        return@withContext ToolPackageStateChangeResult.Rejected(
                            "TOOLPACK_REVOKED",
                            "This package was revoked; clear the revocation before enabling it again."
                        )
                    }
                    if (!contentHashDirectory(current.contentHash).isDirectory) {
                        return@withContext ToolPackageStateChangeResult.Rejected(
                            "TOOLPACK_NOT_INSTALLED",
                            "The installed content for this package is missing."
                        )
                    }
                    current.copy(
                        enabled = true,
                        revision = current.revision + 1L
                    )
                }
                is ToolPackageStateChange.Disable ->
                    current.copy(enabled = false, revision = current.revision + 1L)
                is ToolPackageStateChange.Revoke -> current.copy(
                    enabled = false,
                    revokedAtMillis = change.atMillis,
                    revision = current.revision + 1L
                )
                is ToolPackageStateChange.ClearRevocation -> current.copy(
                    revokedAtMillis = null,
                    revision = current.revision + 1L
                )
                is ToolPackageStateChange.Rollback -> {
                    val target = current.previousContentHash
                        ?: return@withContext ToolPackageStateChangeResult.Rejected(
                            "TOOLPACK_NO_ROLLBACK_TARGET",
                            "This package has no previous version to roll back to."
                        )
                    if (!contentHashDirectory(target).isDirectory) {
                        return@withContext ToolPackageStateChangeResult.Rejected(
                            "TOOLPACK_ROLLBACK_CONTENT_MISSING",
                            "The previous version's content is no longer stored."
                        )
                    }
                    current.copy(
                        contentHash = target,
                        previousContentHash = current.contentHash,
                        revision = current.revision + 1L
                    )
                }
                is ToolPackageStateChange.Remove -> null
            }
            try {
                if (next == null) {
                    File(stateDir, "${current.packageId}.json").delete()
                    ToolPackageStateChangeResult.Applied(null)
                } else {
                    writeState(next)
                    ToolPackageStateChangeResult.Applied(next)
                }
            } catch (_: Exception) {
                ToolPackageStateChangeResult.Rejected(
                    "TOOLPACK_STATE_WRITE_FAILED",
                    "The activation pointer could not be written; the previous state was kept."
                )
            }
        }

    /** Re-reads the stored bytes and re-derives the content hash; never trusts the directory name. */
    suspend fun verify(contentHash: String): ToolPackageVerifyResult = withContext(Dispatchers.IO) {
        val result = verifyStoredContent(contentHash)
        if (result is ToolPackageVerifyResult.Valid) {
            result.copy(record = findRecordByContentHash(contentHash))
        } else {
            result
        }
    }

    /** Activates verified, non-write content. Bumps the record revision. */
    suspend fun enable(contentHash: String): ToolPackageActivationResult = withContext(Dispatchers.IO) {
        val verified = when (val result = verifyStoredContent(contentHash)) {
            is ToolPackageVerifyResult.Valid -> result
            is ToolPackageVerifyResult.Rejected -> return@withContext ToolPackageActivationResult.Rejected(
                result.code,
                result.safeMessage
            )
        }
        if (verified.bundle.manifest.declaresWriteCapability && !writeCapabilityPolicy.allowsWriteCapability) {
            return@withContext ToolPackageActivationResult.Rejected(
                ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
                "Write-capable packages cannot be activated before the per-call approval pipeline exists."
            )
        }
        val previous = readStateByPackageId(verified.bundle.packageId)
        if (previous?.revoked == true) {
            return@withContext ToolPackageActivationResult.Rejected(
                "TOOLPACK_REVOKED",
                "This package was revoked; clear the revocation before enabling it again."
            )
        }
        val record = ToolPackageInstalledRecord(
            packageId = verified.bundle.packageId,
            packageVersion = verified.bundle.packageVersion,
            contentHash = contentHash,
            enabled = true,
            installedAtMillis = previous?.installedAtMillis ?: clockMillis(),
            revision = (previous?.revision ?: 0L) + 1L,
            previousContentHash = when {
                previous == null -> null
                previous.contentHash == contentHash -> previous.previousContentHash
                else -> previous.contentHash
            },
            revokedAtMillis = null
        )
        try {
            ensureDirectories()
            writeState(record)
        } catch (failure: Exception) {
            return@withContext ToolPackageActivationResult.Rejected(
                "TOOLPACK_STATE_WRITE_FAILED",
                "The activation pointer could not be written; the package stays disabled."
            )
        }
        ToolPackageActivationResult.Activated(record)
    }

    /** Deactivates content but keeps it installed, so a later enable needs no re-download. */
    suspend fun disable(contentHash: String): ToolPackageActivationResult = withContext(Dispatchers.IO) {
        val previous = findRecordByContentHash(contentHash)
            ?: return@withContext ToolPackageActivationResult.Rejected(
                "TOOLPACK_NOT_INSTALLED",
                "No installed package references this content hash."
            )
        val record = previous.copy(enabled = false, revision = previous.revision + 1L)
        try {
            writeState(record)
        } catch (failure: Exception) {
            return@withContext ToolPackageActivationResult.Rejected(
                "TOOLPACK_STATE_WRITE_FAILED",
                "The activation pointer could not be written."
            )
        }
        ToolPackageActivationResult.Activated(record)
    }

    suspend fun scan(): ToolPackageInstallScan = withContext(Dispatchers.IO) {
        val records = ArrayList<ToolPackageInstalledRecord>()
        val unreadable = ArrayList<String>()
        stateFiles().forEach { file ->
            val record = readStateFile(file)
            if (record == null) unreadable += file.name else records += record
        }
        ToolPackageInstallScan(records.sortedBy { it.packageId }, unreadable)
    }

    suspend fun activeRecords(): List<ToolPackageInstalledRecord> =
        scan().records.filter { it.enabled }

    /**
     * Repairs an interrupted install: an unattached content directory is removed, staging and the
     * journal entry are cleared. Content referenced by a state record, or by the exact dependency
     * lock of any reachable stored manifest, is kept.
     */
    suspend fun recover(): ToolPackageRecoveryReport = withContext(Dispatchers.IO) {
        var transactions = 0
        val deletedContent = ArrayList<String>()
        var deletedStaging = 0
        ensureDirectories()
        val referenced = referencedContentHashes(emptySet())
        journalFiles().forEach { file ->
            val entry = readJournal(file) ?: run {
                file.delete()
                transactions += 1
                return@forEach
            }
            transactions += 1
            File(stagingDir, entry.txnId).takeIf { it.exists() }?.let {
                deleteRecursively(it)
                deletedStaging += 1
            }
            val content = contentHashDirectory(entry.contentHash)
            if (entry.contentHash !in referenced && content.exists()) {
                deleteRecursively(content)
                deletedContent += entry.contentHash
            }
            file.delete()
        }
        stagingDir.listFiles()?.forEach { leftover ->
            if (deleteRecursively(leftover)) deletedStaging += 1
        }
        ToolPackageRecoveryReport(
            repairedTransactions = transactions,
            deletedContentHashes = deletedContent.sorted(),
            deletedStagingDirectories = deletedStaging
        )
    }

    /**
     * Reference-counted GC: content is deleted only when no state record references it and the
     * caller did not protect it (a running run pins its content hash until release).
     */
    suspend fun collectGarbage(protectedContentHashes: Set<String> = emptySet()): ToolPackageGcReport =
        withContext(Dispatchers.IO) {
            ensureDirectories()
            // Both the active content and the rollback target stay referenced until the record (or
            // its previous pointer) is replaced; deleting the rollback target would silently break
            // an otherwise valid update. The walk then follows each reachable manifest's exact
            // dependency lock (transitively), so dependency content survives while any installed
            // parent, including a rollback target, still declares it.
            val referenced = referencedContentHashes(protectedContentHashes)
            val deletedContent = ArrayList<String>()
            val deletedState = ArrayList<String>()
            contentDir.listFiles()?.forEach { directory ->
                if (!directory.isDirectory) return@forEach
                val hash = directory.name
                if (hash !in referenced) {
                    if (deleteRecursively(directory)) deletedContent += hash
                }
            }
            stateFiles().forEach { file ->
                val record = readStateFile(file)
                if (record == null || !contentHashDirectory(record.contentHash).isDirectory) {
                    if (file.delete()) deletedState += file.name
                }
            }
            ToolPackageGcReport(
                deletedContentHashes = deletedContent.sorted(),
                deletedDanglingStateRecords = deletedState.sorted(),
                keptContentHashes = referenced.sorted()
            )
        }

    private suspend fun verifyStoredContent(contentHash: String): ToolPackageVerifyResult {
        if (!contentHash.matches(Regex("^[0-9a-f]{64}$"))) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_CONTENT_HASH_INVALID",
                "The content hash is not a lowercase SHA-256 value."
            )
        }
        val directory = contentHashDirectory(contentHash)
        if (!directory.isDirectory) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_NOT_INSTALLED",
                "No stored content matches this hash."
            )
        }
        val manifestFile = File(directory, MANIFEST)
        if (!manifestFile.isFile) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_CONTENT_CORRUPT",
                "The stored package has no manifest."
            )
        }
        val manifestBytes = try {
            manifestFile.readBytes()
        } catch (_: IOException) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_CONTENT_CORRUPT",
                "The stored manifest could not be read."
            )
        }
        val scripts = try {
            readScripts(File(directory, SCRIPTS))
        } catch (_: IOException) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_CONTENT_CORRUPT",
                "The stored scripts could not be read."
            )
        }
        val bundle = try {
            ExecutableToolPackageLoader().load(manifestBytes, scripts)
        } catch (failure: ToolPackageException) {
            return ToolPackageVerifyResult.Rejected(
                failure.error.code.agentCode,
                failure.error.safeMessage
            )
        }
        if (bundle.contentHash != contentHash) {
            return ToolPackageVerifyResult.Rejected(
                "TOOLPACK_CONTENT_HASH_MISMATCH",
                "The stored bytes no longer match the content hash they were installed under."
            )
        }
        return ToolPackageVerifyResult.Valid(bundle, findRecordByContentHash(contentHash))
    }

    private fun readScripts(directory: File): Map<String, String> {
        if (!directory.isDirectory) return emptyMap()
        val scripts = LinkedHashMap<String, String>()
        val base = directory.toPath()
        Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = base.relativize(path).joinToString("/") { it.toString() }
                scripts[relative] = String(Files.readAllBytes(path), Charsets.UTF_8)
            }
        }
        return scripts
    }

    private fun writeContent(target: File, manifestBytes: ByteArray, scripts: Map<String, String>) {
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("could not create the package staging directory")
        }
        File(target, MANIFEST).writeBytes(manifestBytes)
        val scriptsRoot = File(target, SCRIPTS)
        for ((path, source) in scripts) {
            val file = File(scriptsRoot, path)
            val parent = file.parentFile ?: throw IOException("invalid script path")
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IOException("could not create a script directory")
            }
            val canonicalRoot = scriptsRoot.canonicalFile
            if (!file.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
                throw IOException("script path escapes the package directory")
            }
            file.writeBytes(source.toByteArray(Charsets.UTF_8))
        }
    }

    private fun rollbackInProcess(
        txnId: String,
        packageId: String,
        contentHash: String,
        previous: ToolPackageInstalledRecord?,
        createdContent: Boolean
    ) {
        runCatching {
            if (previous == null) {
                File(stateDir, "$packageId.json").delete()
            } else {
                writeState(previous)
            }
        }
        if (createdContent) runCatching { deleteRecursively(contentHashDirectory(contentHash)) }
        runCatching { deleteRecursively(File(stagingDir, txnId)) }
        runCatching { deleteJournal(txnId) }
    }

    private suspend fun resolveDependencies(
        manifest: ExecutableToolPackageManifest
    ): ToolPackageDependencyResolution = ToolPackageDependencyResolver(
        recordOf = { packageId -> readStateByPackageId(packageId) },
        verifyContent = { contentHash -> verifyStoredContent(contentHash) }
    ).resolve(manifest)

    private fun findRecordByContentHash(contentHash: String): ToolPackageInstalledRecord? =
        stateFiles().mapNotNull { readStateFile(it) }.firstOrNull { it.contentHash == contentHash }

    /**
     * Content that stays reachable: every record's active + rollback target, the caller's protected
     * set and the transitive exact dependency lock of each reachable stored manifest. Reading only
     * `manifest.json` keeps the walk cheap; an unreadable manifest contributes no dependencies.
     */
    private fun referencedContentHashes(protectedContentHashes: Set<String>): Set<String> {
        val referenced = HashSet<String>()
        stateFiles().mapNotNull { readStateFile(it) }
            .forEach { referenced.addAll(it.referencedContentHashes) }
        referenced.addAll(protectedContentHashes)
        val pending = ArrayDeque<String>()
        referenced.forEach { pending.addLast(it) }
        val expanded = HashSet<String>()
        var budget = MAX_REFERENCE_EXPANSIONS
        while (pending.isNotEmpty() && budget > 0) {
            budget -= 1
            val contentHash = pending.removeFirst()
            if (!expanded.add(contentHash)) continue
            val manifest = readStoredManifest(contentHash) ?: continue
            for (dependency in manifest.dependencies) {
                if (referenced.add(dependency.contentHash)) pending.addLast(dependency.contentHash)
            }
        }
        return referenced
    }

    /** Parses only the stored manifest of [contentHash]; `null` when absent or unreadable. */
    private fun readStoredManifest(contentHash: String): ExecutableToolPackageManifest? {
        if (!CONTENT_HASH.matches(contentHash)) return null
        val file = File(contentHashDirectory(contentHash), MANIFEST)
        if (!file.isFile || file.length() > ToolPackageLimits.MAX_MANIFEST_BYTES) return null
        return try {
            ExecutableToolPackageManifestParser().parse(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    private fun readStateByPackageId(packageId: String): ToolPackageInstalledRecord? =
        readStateFile(File(stateDir, "$packageId.json"))

    private fun writeState(record: ToolPackageInstalledRecord) {
        ensureDirectories()
        writeAtomically(
            File(stateDir, "${record.packageId}.json"),
            ToolPackageStateCodec.encode(record).toByteArray(Charsets.UTF_8)
        )
    }

    private fun readStateFile(file: File): ToolPackageInstalledRecord? {
        if (!file.isFile) return null
        return try {
            ToolPackageStateCodec.decode(String(file.readBytes(), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun stateFiles(): List<File> =
        stateDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    private fun journalFiles(): List<File> =
        journalDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    private fun writeJournal(entry: ToolPackageJournalEntry) {
        ensureDirectories()
        writeAtomically(
            File(journalDir, "${entry.txnId}.json"),
            ToolPackageStateCodec.encodeJournal(entry).toByteArray(Charsets.UTF_8)
        )
    }

    private fun readJournal(file: File): ToolPackageJournalEntry? = try {
        ToolPackageStateCodec.decodeJournal(String(file.readBytes(), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun deleteJournal(txnId: String) {
        File(journalDir, "$txnId.json").delete()
    }

    private fun contentHashDirectory(contentHash: String): File = File(contentDir, contentHash)

    private fun ensureDirectories() {
        listOf(contentDir, stateDir, stagingDir, journalDir).forEach { directory ->
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("could not create the package store layout")
            }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        moveAtomically(temp, target)
    }

    private fun moveAtomically(source: File, target: File) {
        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("could not create the target directory")
            }
        }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return false
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteRecursively(child) }
        }
        return file.delete()
    }

    private companion object {
        const val CONTENT = "content"
        const val STATE = "state"
        const val STAGING = "staging"
        const val JOURNAL = "journal"
        const val MANIFEST = "manifest.json"
        const val SCRIPTS = "scripts"

        /** Safety cap for the GC manifest-expansion walk; ordinary stores stay far below it. */
        const val MAX_REFERENCE_EXPANSIONS = 1024
        val CONTENT_HASH = Regex("^[0-9a-f]{64}$")

        const val PHASE_STAGING = "STAGING"

        const val STAGE_AFTER_STAGING = "after_staging"
        const val STAGE_AFTER_CONTENT = "after_content"
        const val STAGE_AFTER_STATE = "after_state"
        const val STAGE_AFTER_JOURNAL = "after_journal"
    }
}

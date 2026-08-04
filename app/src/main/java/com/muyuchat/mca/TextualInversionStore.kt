package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class TextualInversionImportResult(
    val artifact: TextualInversionArtifact,
    val duplicate: Boolean = false
)

private data class ArtifactFileVerification(
    val canonicalPath: String,
    val fileKey: String?,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val sha256: String
) {
    fun sameIdentity(other: ArtifactFileVerification): Boolean =
        canonicalPath == other.canonicalPath &&
            fileKey == other.fileKey &&
            sizeBytes == other.sizeBytes &&
            lastModifiedMillis == other.lastModifiedMillis &&
            sha256 == other.sha256
}

class TextualInversionSelectionLease internal constructor(
    val selection: TextualInversionSelection,
    val rootPath: String,
    private val verify: () -> Unit = {},
    private val release: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun verifyUnchanged() {
        check(!closed.get()) { "Textual inversion selection lease is already closed." }
        verify()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * App-private textual-inversion artifact store.
 *
 * The source URI is copied once into no-backup storage.  A bounded copy, descriptor-backed SHA-256
 * and format header check happen before the record is published.  The manifest is replaced only
 * after the artifact is durable, so cancellation before the commit point leaves the previous
 * snapshot intact.
 */
class TextualInversionStore private constructor(
    private val appContext: Context?,
    private val noBackupRoot: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val afterManifestCommit: () -> Unit,
    private val afterSelectionLeaseAcquired: () -> Unit,
    private val maxTotalBytes: Long,
    private val minFreeSpaceReserveBytes: Long,
    private val usableSpaceProvider: (File) -> Long
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        appContext = context.applicationContext ?: context,
        noBackupRoot = (context.applicationContext ?: context).noBackupFilesDir.canonicalFile,
        ioDispatcher = ioDispatcher,
        afterManifestCommit = {},
        afterSelectionLeaseAcquired = {},
        maxTotalBytes = MAX_TOTAL_BYTES,
        minFreeSpaceReserveBytes = MIN_FREE_SPACE_RESERVE_BYTES,
        usableSpaceProvider = { file -> file.usableSpace }
    )

    internal constructor(
        noBackupRoot: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        afterManifestCommit: () -> Unit = {},
        afterSelectionLeaseAcquired: () -> Unit = {},
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        minFreeSpaceReserveBytes: Long = MIN_FREE_SPACE_RESERVE_BYTES,
        usableSpaceProvider: (File) -> Long = { file -> file.usableSpace }
    ) : this(
        appContext = null,
        noBackupRoot = noBackupRoot.canonicalFile,
        ioDispatcher = ioDispatcher,
        afterManifestCommit = afterManifestCommit,
        afterSelectionLeaseAcquired = afterSelectionLeaseAcquired,
        maxTotalBytes = maxTotalBytes,
        minFreeSpaceReserveBytes = minFreeSpaceReserveBytes,
        usableSpaceProvider = usableSpaceProvider
    )

    init {
        require(maxTotalBytes >= TextualInversionContract.MIN_BYTES) {
            "Textual inversion aggregate quota must fit one minimum artifact."
        }
        require(minFreeSpaceReserveBytes >= 0L) {
            "Textual inversion free-space reserve must be non-negative."
        }
    }

    private val root = File(noBackupRoot, DIRECTORY).canonicalFile
    private val manifest = File(root, MANIFEST_NAME).absoluteFile
    private val manifestBackup = File(root, MANIFEST_BACKUP_NAME).absoluteFile
    private val lockFile = File(root, LOCK_NAME).absoluteFile
    private val verifiedFileCache = mutableMapOf<String, ArtifactFileVerification>()

    val rootPath: String
        get() = root.path

    suspend fun load(): List<TextualInversionArtifact> = withStoreLock(shared = false) { checkCancelled ->
        loadLocked(checkCancelled).also { records ->
            pruneOrphansLocked(records, checkCancelled)
        }
    }

    /**
     * Imports and validates one artifact.  [modelFingerprint] and [tokenizerFingerprint] are
     * optional at import time so the UI can keep a reusable library, but native generation must
     * call [bind] with both exact fingerprints before it can consume the artifact.
     */
    suspend fun importFromContentUri(
        uri: Uri,
        trigger: String,
        modelFingerprint: String? = null,
        tokenizerFingerprint: String? = null,
        displayNameOverride: String? = null
    ): TextualInversionImportResult {
        requireTextualInversionContentImportUri(uri.scheme, uri.authority)
        return importFromSource(
            trigger = trigger,
            modelFingerprint = modelFingerprint,
            tokenizerFingerprint = tokenizerFingerprint,
            displayNameProvider = {
                displayNameOverride?.trim().takeUnless { it.isNullOrBlank() } ?: displayName(uri)
            },
            openInput = {
                appContext?.contentResolver?.openInputStream(uri)
                    ?: throw IOException("Unable to open textual inversion source URI.")
            }
        )
    }

    internal suspend fun importFromStream(
        openInput: () -> InputStream,
        trigger: String,
        displayName: String,
        modelFingerprint: String? = null,
        tokenizerFingerprint: String? = null
    ): TextualInversionImportResult = importFromSource(
        trigger = trigger,
        modelFingerprint = modelFingerprint,
        tokenizerFingerprint = tokenizerFingerprint,
        displayNameProvider = { displayName },
        openInput = openInput
    )

    private suspend fun importFromSource(
        trigger: String,
        modelFingerprint: String?,
        tokenizerFingerprint: String?,
        displayNameProvider: () -> String,
        openInput: () -> InputStream
    ): TextualInversionImportResult = withCommittedMutation { checkCancelled, markCommitted ->
        checkCancelled()
        val normalizedTrigger = trigger.trim()
        require(TextualInversionContract.TRIGGER_PATTERN.matches(normalizedTrigger)) {
            "Textual inversion trigger is invalid."
        }
        val normalizedModel = modelFingerprint?.trim()?.lowercase()?.also {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion model fingerprint is invalid."
            }
        }
        val normalizedTokenizer = tokenizerFingerprint?.trim()?.lowercase()?.also {
            require(TextualInversionContract.SHA256_PATTERN.matches(it)) {
                "Textual inversion tokenizer fingerprint is invalid."
            }
        }
        val displayName = displayNameProvider().trim().takeIf(String::isNotBlank)
            ?: "textual-inversion.safetensors"
        val format = TextualInversionFormat.fromExtension(
            displayName.substringAfterLast('.', "").lowercase()
        )
        ensureRoot()
        val id = UUID.randomUUID().toString().lowercase()
        val fileName = "$id.${format.extension}"
        val target = directChild(fileName)
        val part = directChild(".$fileName.${UUID.randomUUID()}.part")
        val current = loadLocked(checkCancelled)
        val installedBytes = aggregateBytes(current)
        val remainingAggregateBytes = maxTotalBytes - installedBytes
        require(remainingAggregateBytes >= TextualInversionContract.MIN_BYTES) {
            "Textual inversion library has no remaining aggregate quota."
        }
        var published = false
        try {
            val input = openInput()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            input.use { source ->
                FileOutputStream(part).use { rawOutput ->
                    BufferedOutputStream(rawOutput).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            checkCancelled()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read.toLong() > remainingAggregateBytes - copied) {
                                throw TextualInversionStoreException(
                                    "Textual inversion library exceeds the 512 MiB aggregate quota."
                                )
                            }
                            val usableBytes = usableSpaceProvider(root)
                            if (usableBytes <= minFreeSpaceReserveBytes ||
                                read.toLong() > usableBytes - minFreeSpaceReserveBytes
                            ) {
                                throw TextualInversionStoreException(
                                    "Textual inversion import must leave 64 MiB of free storage."
                                )
                            }
                            copied += read.toLong()
                            if (copied > TextualInversionContract.MAX_BYTES) {
                                throw TextualInversionStoreException("Textual inversion exceeds the 100 MiB bound.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        rawOutput.fd.sync()
                    }
                }
            }
            require(copied in TextualInversionContract.MIN_BYTES..TextualInversionContract.MAX_BYTES) {
                "Textual inversion is empty or outside the supported size bound."
            }
            validateFormat(part, format)
            val sha256 = digest.digest().toHex()
            checkCancelled()

            val existing = current.firstOrNull {
                it.sha256.equals(sha256, ignoreCase = true) &&
                    it.sizeBytes == copied &&
                    it.trigger.equals(normalizedTrigger, ignoreCase = true)
            }
            if (existing != null) {
                part.delete()
                return@withCommittedMutation TextualInversionImportResult(
                    existing,
                    duplicate = true
                )
            }
            requireAggregateQuota(current, additionalBytes = copied)
            if (!moveIntoPlace(part, target)) {
                throw IOException("Unable to publish textual inversion artifact.")
            }
            val artifact = TextualInversionArtifact(
                id = id,
                name = displayName.substringBeforeLast('.').ifBlank { "Textual inversion" }
                    .take(TextualInversionContract.MAX_NAME_CHARS),
                trigger = normalizedTrigger,
                fileName = fileName,
                path = target.canonicalPath,
                sha256 = sha256,
                sizeBytes = copied,
                format = format,
                modelFingerprint = normalizedModel,
                tokenizerFingerprint = normalizedTokenizer,
                importedAt = System.currentTimeMillis()
            )
            checkCancelled()
            val result = TextualInversionImportResult(artifact)
            commitManifest(
                records = current + artifact,
                checkCancelled = checkCancelled,
                onCommitted = {
                    published = true
                    markCommitted(result)
                }
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!published) {
                part.delete()
                target.delete()
            }
        }
    }

    /** Creates a provisional binding. It does not mutate the artifact manifest. */
    suspend fun bind(
        id: String,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = withStoreLock(shared = true) { checkCancelled ->
        val artifact = findInstalledArtifact(id, loadLocked(checkCancelled))
        artifact.bind(
            modelFingerprint = modelFingerprint.trim().lowercase(),
            tokenizerFingerprint = tokenizerFingerprint.trim().lowercase(),
            profileId = profileId.trim(),
            profileRevision = profileRevision,
            runtime = runtime
        )
    }

    suspend fun prepareBinding(
        artifact: TextualInversionArtifact,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = withStoreLock(shared = true) { checkCancelled ->
        val current = findInstalledArtifact(artifact.id, loadLocked(checkCancelled))
        require(current.sha256 == artifact.sha256 && current.sizeBytes == artifact.sizeBytes) {
            "Textual inversion artifact changed since it was selected."
        }
        current.bind(modelFingerprint, tokenizerFingerprint, profileId, profileRevision, runtime)
    }

    /**
     * Holds a cross-process shared lease over the exact files used by native generation.
     * Callers must close the lease in `finally` before requesting the success commit.
     */
    suspend fun acquireSelectionLease(
        ids: List<String>,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP,
        executionAssetBinding: TextualInversionExecutionAssetBinding? = null
    ): TextualInversionSelectionLease {
        val callerContext = currentCoroutineContext()
        val undeliveredLease = AtomicReference<TextualInversionSelectionLease?>(null)
        return try {
            val lease = withContext(ioDispatcher + NonCancellable) {
                val checkCancelled = callerContext::ensureActive
                checkCancelled()
                val normalizedIds = normalizeSelectionIds(ids)
                processFileLockMutex.lock()
                var channel: java.nio.channels.FileChannel? = null
                var fileLock: java.nio.channels.FileLock? = null
                val artifactHandles = mutableListOf<RandomAccessFile>()
                val executionAssetHandles = mutableListOf<RandomAccessFile>()
                val acquiredLease = try {
                    checkCancelled()
                    ensureRoot()
                    val openedChannel = RandomAccessFile(lockFile, "rw").channel
                    channel = openedChannel
                    val acquiredLock = openedChannel.lock(0L, Long.MAX_VALUE, true)
                    fileLock = acquiredLock
                    checkCancelled()
                    val installed = loadLocked(checkCancelled)
                        .associateBy(TextualInversionArtifact::id)
                    val selectedArtifacts = normalizedIds.map { id ->
                        installed[id]
                            ?: throw TextualInversionStoreException(
                                "Textual inversion artifact $id is not installed."
                            )
                    }
                    val initialVerifications = mutableListOf<ArtifactFileVerification>()
                    selectedArtifacts.forEach { artifact ->
                        checkCancelled()
                        val handle = RandomAccessFile(File(artifact.path), "r")
                        try {
                            initialVerifications += verifyRecordDescriptor(
                                artifact = artifact,
                                handle = handle,
                                checkCancelled = checkCancelled
                            )
                            artifactHandles += handle
                        } catch (failure: Throwable) {
                            handle.close()
                            throw failure
                        }
                    }
                    val initialExecutionAssets = executionAssetBinding?.assets.orEmpty().map { asset ->
                        checkCancelled()
                        val handle = RandomAccessFile(File(asset.path), "r")
                        try {
                            verifyExecutionAssetDescriptor(asset, handle, checkCancelled).also {
                                executionAssetHandles += handle
                            }
                        } catch (failure: Throwable) {
                            handle.close()
                            throw failure
                        }
                    }
                    val bindings = normalizedIds.map { id ->
                        requireNotNull(installed[id]).bind(
                            modelFingerprint.trim().lowercase(),
                            tokenizerFingerprint.trim().lowercase(),
                            profileId.trim(),
                            profileRevision,
                            runtime
                        )
                    }
                    val selection = TextualInversionSelection(bindings, executionAssetBinding)
                    TextualInversionSelectionLease(
                        selection = selection,
                        rootPath = root.path,
                        verify = {
                            selectedArtifacts.forEachIndexed { index, artifact ->
                                val descriptorVerification = verifyRecordDescriptor(
                                    artifact = artifact,
                                    handle = artifactHandles[index],
                                    checkCancelled = {}
                                )
                                val pathVerification = requireRecordFileIsCurrent(
                                    artifact = artifact,
                                    checkCancelled = {},
                                    forceHash = true
                                )
                                require(descriptorVerification.sameIdentity(initialVerifications[index]) &&
                                    pathVerification.sameIdentity(initialVerifications[index])
                                ) {
                                    "Textual inversion artifact ${artifact.id} changed during native execution."
                                }
                            }
                            executionAssetBinding?.assets.orEmpty().forEachIndexed { index, asset ->
                                val descriptorVerification = verifyExecutionAssetDescriptor(
                                    expected = asset,
                                    handle = executionAssetHandles[index],
                                    checkCancelled = {}
                                )
                                val pathVerification = verifyExecutionAssetPath(asset, checkCancelled = {})
                                require(descriptorVerification.sameIdentity(initialExecutionAssets[index]) &&
                                    pathVerification.sameIdentity(initialExecutionAssets[index])
                                ) {
                                    "Prompt execution asset ${asset.label} changed during native execution."
                                }
                            }
                        }
                    ) {
                        try {
                            executionAssetHandles.asReversed().forEach { handle ->
                                runCatching { handle.close() }
                            }
                            artifactHandles.asReversed().forEach { handle ->
                                runCatching { handle.close() }
                            }
                        } finally {
                            try {
                                acquiredLock.release()
                            } finally {
                                try {
                                    openedChannel.close()
                                } finally {
                                    processFileLockMutex.unlock()
                                }
                            }
                        }
                    }.also {
                        channel = null
                        fileLock = null
                    }
                } catch (failure: Throwable) {
                    try {
                        executionAssetHandles.asReversed().forEach { handle ->
                            runCatching { handle.close() }
                        }
                        artifactHandles.asReversed().forEach { handle ->
                            runCatching { handle.close() }
                        }
                    } finally {
                        try {
                            fileLock?.release()
                        } finally {
                            try {
                                channel?.close()
                            } finally {
                                processFileLockMutex.unlock()
                            }
                        }
                    }
                    throw failure
                }
                undeliveredLease.set(acquiredLease)
                afterSelectionLeaseAcquired()
                acquiredLease
            }
            undeliveredLease.set(null)
            lease
        } catch (failure: Throwable) {
            val lease = undeliveredLease.getAndSet(null)
            if (lease != null) {
                try {
                    lease.close()
                } catch (releaseFailure: Throwable) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }

    /**
     * Persists the most recent successful model/tokenizer binding as advisory evidence only.
     * Future requests still create a fresh exact binding and are admitted to the real native
     * tensor/schema load, so using an artifact with one model never locks it to that model.
     */
    suspend fun commitSuccessfulBindings(
        selection: TextualInversionSelection,
        nativeBindingFingerprint: String,
        nativeBindingStage: String
    ): TextualInversionSelection = withCommittedMutation { checkCancelled, markCommitted ->
        require(selection.bindings.isNotEmpty()) { "A textual inversion selection is required." }
        require(nativeBindingStage == "conditioning_consumed") {
            "Textual inversion binding cannot be committed before native conditioning consumption."
        }
        require(
            nativeBindingFingerprint.trim().lowercase() == selection.bindingFingerprint
        ) { "Native textual inversion binding evidence does not match the requested selection." }

        val bindingsById = selection.bindings.associateBy { it.artifact.id }
        val current = loadLocked(checkCancelled)
        selection.executionAssetBinding?.assets.orEmpty().forEach { asset ->
            verifyExecutionAssetPath(asset, checkCancelled)
        }
        bindingsById.forEach { (id, binding) ->
            val installed = findInstalledArtifact(id, current)
            requireRecordFileIsCurrent(installed, checkCancelled, forceHash = true)
            require(
                installed.sha256 == binding.artifact.sha256 &&
                    installed.sizeBytes == binding.artifact.sizeBytes &&
                    installed.trigger.equals(binding.artifact.trigger, ignoreCase = true)
            ) { "Textual inversion artifact changed before the successful binding commit." }
        }
        val updated = current.map { artifact ->
            val binding = bindingsById[artifact.id] ?: return@map artifact
            artifact.copy(
                modelFingerprint = binding.modelFingerprint.lowercase(),
                tokenizerFingerprint = binding.tokenizerFingerprint.lowercase()
            )
        }
        checkCancelled()
        commitManifest(
            records = updated,
            checkCancelled = checkCancelled,
            onCommitted = { markCommitted(selection) }
        )
        selection
    }

    suspend fun clear(id: String): Boolean = withCommittedMutation { checkCancelled, markCommitted ->
        val current = loadLocked(checkCancelled)
        val item = current.firstOrNull { it.id == id.trim().lowercase() }
            ?: return@withCommittedMutation false
        val next = current.filterNot { it.id == item.id }
        checkCancelled()
        commitManifest(
            records = next,
            checkCancelled = checkCancelled,
            onCommitted = { markCommitted(true) }
        )
        directChild(item.fileName).delete()
        true
    }

    /** Removes only unreferenced, repository-owned files; never follows a symlink. */
    suspend fun pruneOrphans(): Int = withStoreLock(shared = false) { checkCancelled ->
        pruneOrphansLocked(loadLocked(checkCancelled), checkCancelled)
    }

    private fun pruneOrphansLocked(
        records: List<TextualInversionArtifact>,
        checkCancelled: () -> Unit
    ): Int {
        val referenced = records.map { it.fileName }.toSet()
        val staleCutoff = (System.currentTimeMillis() - STALE_TEMPORARY_AGE_MS).coerceAtLeast(0L)
        var deleted = 0
        root.listFiles()?.forEach { file ->
            checkCancelled()
            if (file == manifest || file == manifestBackup || file == lockFile) {
                return@forEach
            }
            if (file.name.startsWith(".")) {
                if (file.isStaleOwnedTemporaryFile(staleCutoff) && file.delete()) deleted++
                return@forEach
            }
            if (file.name !in referenced && file.isFile && !Files.isSymbolicLink(file.toPath())) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    private fun File.isStaleOwnedTemporaryFile(staleCutoff: Long): Boolean = try {
        val recognizedName = IMPORT_PART_NAME_PATTERN.matches(name) ||
            MANIFEST_TEMP_NAME_PATTERN.matches(name)
        val modifiedAt = lastModified()
        recognizedName &&
            isFile &&
            !Files.isSymbolicLink(toPath()) &&
            canonicalFile.parentFile == root &&
            canonicalFile.name == name &&
            modifiedAt > 0L &&
            modifiedAt <= staleCutoff
    } catch (_: Exception) {
        false
    }

    private class CommittedValue<T>(val value: T)

    /**
     * A caller cancellation may win before the manifest replacement, but not after it.  The
     * atomic holder lets us recover a committed result if withContext's return dispatch observes
     * cancellation after the non-cancellable IO block has already crossed its commit point.
     */
    private suspend fun <T> withCommittedMutation(
        block: (
            checkCancelled: () -> Unit,
            markCommitted: (T) -> Unit
        ) -> T
    ): T {
        val committed = AtomicReference<CommittedValue<T>?>(null)
        return try {
            withStoreLock(shared = false) { checkCancelled ->
                block(checkCancelled) { value ->
                    check(committed.compareAndSet(null, CommittedValue(value))) {
                        "Textual inversion mutation crossed its commit point more than once."
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            val completed = committed.get()
            if (completed != null) completed.value else throw cancelled
        }
    }

    private suspend fun <T> withStoreLock(
        shared: Boolean,
        block: (checkCancelled: () -> Unit) -> T
    ): T {
        val callerContext = currentCoroutineContext()
        return withContext(ioDispatcher + NonCancellable) {
            val checkCancelled = callerContext::ensureActive
            processFileLockMutex.withLock {
                checkCancelled()
                ensureRoot()
                RandomAccessFile(lockFile, "rw").channel.use { channel ->
                    val heldLock = channel.lock(0L, Long.MAX_VALUE, shared)
                    try {
                        if (!shared) recoverInterruptedManifestCommit(checkCancelled)
                        checkCancelled()
                        block(checkCancelled)
                    } finally {
                        heldLock.release()
                    }
                }
            }
        }
    }

    private fun normalizeSelectionIds(ids: List<String>): List<String> {
        require(ids.isNotEmpty()) { "At least one textual inversion id is required." }
        require(ids.size <= TextualInversionContract.MAX_COUNT) {
            "At most ${TextualInversionContract.MAX_COUNT} textual inversions may be active."
        }
        val normalized = ids.map { id ->
            id.trim().lowercase().also {
                require(TextualInversionContract.UUID_PATTERN.matches(it)) {
                    "Textual inversion ids must be UUIDs."
                }
            }
        }
        require(normalized.distinct().size == normalized.size) {
            "Textual inversion ids must be unique per request."
        }
        return normalized
    }

    private fun findInstalledArtifact(
        id: String,
        artifacts: List<TextualInversionArtifact>
    ): TextualInversionArtifact {
        val normalizedId = id.trim().lowercase()
        require(TextualInversionContract.UUID_PATTERN.matches(normalizedId)) {
            "Textual inversion id must be a UUID."
        }
        return artifacts.firstOrNull { it.id == normalizedId }
            ?: throw TextualInversionStoreException("Textual inversion artifact is not installed.")
    }

    private fun ensureRoot() {
        require(root.parentFile == noBackupRoot && root.name == DIRECTORY) {
            "Textual inversion root escaped no-backup storage."
        }
        if (!root.exists() && !root.mkdirs()) throw IOException("Unable to create textual inversion root.")
        require(root.isDirectory) { "Textual inversion root is not a directory." }
        listOf(manifest, manifestBackup, lockFile).forEach { metadata ->
            require(metadata.parentFile == root) { "Textual inversion metadata escaped its root." }
            if (metadata.exists()) {
                require(!Files.isSymbolicLink(metadata.toPath())) {
                    "Textual inversion metadata must not be a symbolic link."
                }
                require(metadata.canonicalFile.parentFile == root) {
                    "Textual inversion metadata escaped its root."
                }
            }
        }
    }

    private fun loadLocked(checkCancelled: () -> Unit): List<TextualInversionArtifact> {
        checkCancelled()
        if (!manifest.isFile) {
            if (manifestBackup.isFile) {
                throw TextualInversionStoreException(
                    "Textual inversion manifest recovery requires an exclusive store operation."
                )
            }
            return emptyList()
        }
        if (manifest.length() !in 1L..MAX_MANIFEST_BYTES) {
            throw TextualInversionStoreException("Textual inversion manifest is outside its bound.")
        }
        val parsed: List<TextualInversionArtifact> = try {
            val raw = manifest.readText(Charsets.UTF_8)
            val trimmed = raw.trimStart()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(raw)
            } else {
                val envelope = JSONObject(raw)
                val version = envelope.getInt("version")
                if (version != MANIFEST_VERSION) {
                    throw TextualInversionStoreException(
                        "Unsupported textual inversion manifest version: $version."
                    )
                }
                envelope.getJSONArray("records")
            }
            if (array.length() > MAX_RECORD_COUNT) {
                throw TextualInversionStoreException("Textual inversion manifest has too many records.")
            }
            buildList<TextualInversionArtifact> {
                for (index in 0 until array.length()) {
                    checkCancelled()
                    val artifact = TextualInversionArtifact.fromJson(array.getJSONObject(index))
                    requireRecordFileIsCurrent(artifact, checkCancelled)
                    add(artifact)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextualInversionStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw TextualInversionStoreException(
                "Textual inversion manifest is corrupt.",
                failure
            )
        }
        if (parsed.map(TextualInversionArtifact::id).distinct().size != parsed.size ||
            parsed.map { it.trigger.lowercase() }.distinct().size != parsed.size) {
            throw TextualInversionStoreException(
                "Textual inversion manifest contains duplicate ids or triggers."
            )
        }
        requireAggregateQuota(parsed)
        verifiedFileCache.keys.retainAll(parsed.map(TextualInversionArtifact::id).toSet())
        return parsed.sortedByDescending(TextualInversionArtifact::importedAt)
    }

    private fun requireRecordFileIsCurrent(
        artifact: TextualInversionArtifact,
        checkCancelled: () -> Unit,
        forceHash: Boolean = false
    ): ArtifactFileVerification {
        checkCancelled()
        return try {
            val before = describeRecordFile(artifact)
            val cached = verifiedFileCache[artifact.id]
            if (!forceHash && cached != null && cached.sameIdentity(before) &&
                cached.sha256 == artifact.sha256
            ) {
                return cached
            }
            val actualSha256 = sha256(File(before.canonicalPath), checkCancelled)
            val after = describeRecordFile(artifact).copy(sha256 = actualSha256)
            if (!before.sameIdentity(after) || actualSha256 != artifact.sha256) {
                throw TextualInversionStoreException(
                    "Textual inversion artifact ${artifact.id} differs from its manifest record."
                )
            }
            after.also { verifiedFileCache[artifact.id] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TextualInversionStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw TextualInversionStoreException(
                "Unable to verify textual inversion artifact ${artifact.id}.",
                failure
            )
        }
    }

    private fun describeRecordFile(artifact: TextualInversionArtifact): ArtifactFileVerification {
        val declared = File(artifact.path).absoluteFile
        if (Files.isSymbolicLink(declared.toPath())) {
            throw TextualInversionStoreException(
                "Textual inversion artifact ${artifact.id} differs from its manifest record."
            )
        }
        val file = declared.canonicalFile
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (file.parentFile != root || file.name != artifact.fileName ||
            !attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() != artifact.sizeBytes
        ) {
            throw TextualInversionStoreException(
                "Textual inversion artifact ${artifact.id} differs from its manifest record."
            )
        }
        return ArtifactFileVerification(
            canonicalPath = file.path,
            fileKey = attributes.fileKey()?.toString(),
            sizeBytes = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
            sha256 = artifact.sha256
        )
    }

    private fun verifyRecordDescriptor(
        artifact: TextualInversionArtifact,
        handle: RandomAccessFile,
        checkCancelled: () -> Unit
    ): ArtifactFileVerification {
        val before = describeRecordFile(artifact)
        require(handle.length() == artifact.sizeBytes) {
            "Textual inversion artifact ${artifact.id} descriptor size changed."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        handle.seek(0L)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var readBytes = 0L
        while (true) {
            checkCancelled()
            val read = handle.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            readBytes += read.toLong()
        }
        val actualSha256 = digest.digest().toHex()
        val after = describeRecordFile(artifact).copy(sha256 = actualSha256)
        require(readBytes == artifact.sizeBytes && handle.length() == artifact.sizeBytes &&
            before.sameIdentity(after) && actualSha256 == artifact.sha256
        ) { "Textual inversion artifact ${artifact.id} changed during descriptor verification." }
        verifiedFileCache[artifact.id] = after
        return after
    }

    private fun verifyExecutionAssetPath(
        expected: TextualInversionExecutionAssetDescriptor,
        checkCancelled: () -> Unit
    ): TextualInversionExecutionAssetDescriptor {
        checkCancelled()
        return describeExecutionAsset(expected).also { actual ->
            require(actual.sameIdentity(expected)) {
                "Prompt execution asset ${expected.label} changed after its exact snapshot."
            }
        }
    }

    private fun verifyExecutionAssetDescriptor(
        expected: TextualInversionExecutionAssetDescriptor,
        handle: RandomAccessFile,
        checkCancelled: () -> Unit
    ): TextualInversionExecutionAssetDescriptor {
        checkCancelled()
        val actual = describeExecutionAsset(expected)
        require(actual.sameIdentity(expected) && handle.length() == expected.sizeBytes) {
            "Prompt execution asset ${expected.label} changed before descriptor verification."
        }
        return actual
    }

    private fun describeExecutionAsset(
        expected: TextualInversionExecutionAssetDescriptor
    ): TextualInversionExecutionAssetDescriptor {
        val requested = File(expected.path).absoluteFile
        require(!Files.isSymbolicLink(requested.toPath())) {
            "Prompt execution asset ${expected.label} became a symbolic link."
        }
        val file = requested.canonicalFile
        require(file.path == expected.path) {
            "Prompt execution asset ${expected.label} changed canonical path."
        }
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink &&
            attributes.size() == expected.sizeBytes
        ) { "Prompt execution asset ${expected.label} is no longer the expected regular file." }
        return expected.copy(
            fileKey = attributes.fileKey()?.toString(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis()
        )
    }

    private fun commitManifest(
        records: List<TextualInversionArtifact>,
        checkCancelled: () -> Unit,
        onCommitted: () -> Unit = {}
    ) {
        ensureRoot()
        require(records.size <= MAX_RECORD_COUNT) { "Too many textual inversion records." }
        require(records.map(TextualInversionArtifact::id).distinct().size == records.size) {
            "Textual inversion record ids must be unique."
        }
        require(records.map { it.trigger.lowercase() }.distinct().size == records.size) {
            "Textual inversion triggers must be unique."
        }
        requireAggregateQuota(records)
        val temp = directChild(".$MANIFEST_NAME.${UUID.randomUUID()}.tmp")
        try {
            val array = JSONArray()
            records.forEach {
                checkCancelled()
                array.put(it.toJson())
            }
            val payload = JSONObject()
                .put("version", MANIFEST_VERSION)
                .put("records", array)
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(payload.size.toLong() <= MAX_MANIFEST_BYTES) {
                "Textual inversion manifest exceeds its size bound."
            }
            FileOutputStream(temp).use { raw ->
                BufferedOutputStream(raw).use { output ->
                    output.write(payload)
                    output.flush()
                    raw.fd.sync()
                }
            }
            checkCancelled()
            try {
                Files.move(
                    temp.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                fallbackManifestCommit(temp)
            }
            onCommitted()
            afterManifestCommit()
            manifestBackup.delete()
        } finally {
            temp.delete()
        }
    }

    private fun fallbackManifestCommit(temp: File) {
        manifestBackup.delete()
        if (manifest.exists()) {
            Files.move(
                manifest.toPath(),
                manifestBackup.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        try {
            Files.move(temp.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            if (!manifest.exists() && manifestBackup.exists()) {
                Files.move(
                    manifestBackup.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            throw failure
        }
    }

    private fun recoverInterruptedManifestCommit(checkCancelled: () -> Unit) {
        checkCancelled()
        if (!manifestBackup.exists()) return
        if (manifest.exists()) {
            manifestBackup.delete()
            return
        }
        Files.move(
            manifestBackup.toPath(),
            manifest.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun moveIntoPlace(source: File, target: File): Boolean {
        if (target.exists()) return false
        return try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            source.renameTo(target)
        }
    }

    private fun directChild(name: String): File = File(root, name).canonicalFile.also { file ->
        require(file.parentFile == root) { "Textual inversion path escaped its private root." }
    }

    private fun displayName(uri: Uri): String = try {
        appContext?.contentResolver?.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }?.trim()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: "textual-inversion.safetensors"

    private fun validateFormat(file: File, format: TextualInversionFormat) {
        when (format) {
            TextualInversionFormat.SAFETENSORS -> validateSafetensors(file)
            TextualInversionFormat.PYTORCH,
            TextualInversionFormat.CHECKPOINT -> validateTorchArchive(file)
            TextualInversionFormat.BINARY -> Unit
        }
    }

    private fun validateSafetensors(file: File) {
        BufferedInputStream(file.inputStream()).use { input ->
            val lengthBytes = ByteArray(Long.SIZE_BYTES)
            require(input.readFully(lengthBytes) == lengthBytes.size) {
                "Textual inversion safetensors header is truncated."
            }
            val headerLength = ByteBuffer.wrap(lengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
            require(headerLength in 2L..MAX_SAFETENSORS_HEADER_BYTES) {
                "Textual inversion safetensors header is outside its bound."
            }
            require(headerLength <= file.length() - Long.SIZE_BYTES) {
                "Textual inversion safetensors header exceeds the file."
            }
            val header = ByteArray(headerLength.toInt())
            require(input.readFully(header) == header.size) {
                "Textual inversion safetensors header is truncated."
            }
            val headerText = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(header))
                    .toString()
            } catch (failure: CharacterCodingException) {
                throw IllegalArgumentException(
                    "Textual inversion safetensors header is not valid UTF-8.",
                    failure
                )
            }
            val tokener = JSONTokener(headerText)
            val decoded = tokener.nextValue()
            require(decoded is JSONObject && tokener.nextClean() == '\u0000') {
                "Textual inversion safetensors header must be one complete JSON object."
            }
            val json = decoded
            json.opt("__metadata__")?.let { metadataValue ->
                require(metadataValue is JSONObject) {
                    "Textual inversion safetensors metadata must be an object."
                }
                metadataValue.keys().forEach { key ->
                    require(metadataValue.opt(key) is String) {
                        "Textual inversion safetensors metadata values must be strings."
                    }
                }
            }
            val tensorNames = json.keys().asSequence().filter { it != "__metadata__" }.toList()
            require(tensorNames.isNotEmpty()) {
                "Textual inversion safetensors has no tensor entries."
            }
            val dataBytes = file.length() - Long.SIZE_BYTES - headerLength
            val spans = tensorNames.map { name ->
                val tensor = json.opt(name) as? JSONObject
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor metadata must be an object."
                    )
                require(
                    tensor.keys().asSequence().toSet() ==
                        setOf("dtype", "shape", "data_offsets")
                ) { "Textual inversion safetensors tensor metadata has unknown or missing fields." }
                val dtype = tensor.opt("dtype") as? String
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor dtype must be a string."
                    )
                val elementBytes = when (dtype) {
                    "F16", "BF16" -> 2L
                    "F32" -> 4L
                    "F64" -> 8L
                    else -> throw IllegalArgumentException(
                        "Textual inversion safetensors tensor dtype is unsupported."
                    )
                }
                val shape = tensor.opt("shape") as? JSONArray
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors tensor shape must be an array."
                    )
                require(shape.length() > 0) {
                    "Textual inversion safetensors tensor shape must not be empty."
                }
                var elementCount = 1L
                for (index in 0 until shape.length()) {
                    val dimension = shape.opt(index).strictNonNegativeLong()
                    require(dimension > 0L && elementCount <= Long.MAX_VALUE / dimension) {
                        "Textual inversion safetensors tensor shape is empty or overflows."
                    }
                    elementCount *= dimension
                }
                require(elementCount <= Long.MAX_VALUE / elementBytes) {
                    "Textual inversion safetensors tensor byte count overflows."
                }
                val offsets = tensor.opt("data_offsets") as? JSONArray
                    ?: throw IllegalArgumentException(
                        "Textual inversion safetensors data offsets must be an array."
                    )
                require(offsets.length() == 2) {
                    "Textual inversion safetensors data offsets must contain two integers."
                }
                val start = offsets.opt(0).strictNonNegativeLong()
                val end = offsets.opt(1).strictNonNegativeLong()
                require(end > start && end - start == elementCount * elementBytes) {
                    "Textual inversion safetensors tensor byte span is invalid."
                }
                require(end <= dataBytes) {
                    "Textual inversion safetensors tensor byte span exceeds the file."
                }
                start to end
            }.sortedBy { it.first }
            var expectedStart = 0L
            spans.forEach { (start, end) ->
                require(start == expectedStart) {
                    "Textual inversion safetensors tensor spans overlap or leave holes."
                }
                expectedStart = end
            }
            require(expectedStart == dataBytes) {
                "Textual inversion safetensors data contains unreferenced trailing bytes."
            }
        }
    }

    private fun Any?.strictNonNegativeLong(): Long = when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        else -> throw IllegalArgumentException(
            "Textual inversion safetensors dimensions and offsets must be integers."
        )
    }.also { value ->
        require(value >= 0L) {
            "Textual inversion safetensors dimensions and offsets must be non-negative."
        }
    }

    private fun validateTorchArchive(file: File) {
        file.inputStream().buffered().use { input ->
            val first = input.read()
            val second = input.read()
            val zipContainer = first == 'P'.code && second == 'K'.code
            val pickleStream = first == 0x80 && second in 0x02..0x05
            require(zipContainer || pickleStream) {
                "Textual inversion PyTorch/checkpoint header is invalid."
            }
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun requireAggregateQuota(
        records: List<TextualInversionArtifact>,
        additionalBytes: Long = 0L
    ) {
        require(additionalBytes >= 0L) { "Textual inversion quota increment must be non-negative." }
        if (additionalBytes > maxTotalBytes) {
            throw TextualInversionStoreException(
                "Textual inversion library exceeds the 512 MiB aggregate quota."
            )
        }
        val installedBytes = aggregateBytes(records)
        if (additionalBytes > maxTotalBytes - installedBytes) {
            throw TextualInversionStoreException(
                "Textual inversion library exceeds the 512 MiB aggregate quota."
            )
        }
    }

    private fun aggregateBytes(records: List<TextualInversionArtifact>): Long {
        var total = 0L
        records.forEach { artifact ->
            if (artifact.sizeBytes > maxTotalBytes - total) {
                throw TextualInversionStoreException(
                    "Textual inversion library exceeds the 512 MiB aggregate quota."
                )
            }
            total += artifact.sizeBytes
        }
        return total
    }

    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    companion object {
        private const val DIRECTORY = "textual_inversions"
        private const val MANIFEST_NAME = "records.json"
        private const val MANIFEST_BACKUP_NAME = ".records.json.rollback"
        private const val LOCK_NAME = ".store.lock"
        private const val MANIFEST_VERSION = 2
        private const val MAX_RECORD_COUNT = 4_096
        internal const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
        internal const val MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_SAFETENSORS_HEADER_BYTES = 1L * 1024L * 1024L
        private const val STALE_TEMPORARY_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val IMPORT_PART_NAME_PATTERN = Regex(
            "^\\.$UUID_REGEX\\.(?:safetensors|pt|ckpt|bin)\\.$UUID_REGEX\\.part$"
        )
        private val MANIFEST_TEMP_NAME_PATTERN = Regex(
            "^\\.records\\.json\\.$UUID_REGEX\\.tmp$"
        )
        private val processFileLockMutex = Mutex()
    }
}

internal fun requireTextualInversionContentImportUri(
    scheme: String?,
    authority: String?
) {
    require(scheme.equals("content", ignoreCase = true) && !authority.isNullOrBlank()) {
        "Textual inversion import requires a content URI with an authority."
    }
}

class TextualInversionStoreException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

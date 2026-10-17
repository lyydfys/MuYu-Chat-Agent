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

/**
 * App-private textual-inversion artifact store.
 *
 * The source URI is copied once into no-backup storage.  A bounded copy, descriptor-backed SHA-256
 * and format header check happen before the record is published.  The manifest is replaced only
 * after the artifact is durable, so cancellation before the commit point leaves the previous
 * snapshot intact.
 */
class TextualInversionStore private constructor(
    internal val appContext: Context?,
    private val noBackupRoot: File,
    internal val ioDispatcher: CoroutineDispatcher,
    internal val afterManifestCommit: () -> Unit,
    internal val afterSelectionLeaseAcquired: () -> Unit,
    internal val maxTotalBytes: Long,
    internal val minFreeSpaceReserveBytes: Long,
    internal val usableSpaceProvider: (File) -> Long
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

    internal val root = File(noBackupRoot, DIRECTORY).canonicalFile
    internal val manifest = File(root, MANIFEST_NAME).absoluteFile
    internal val manifestBackup = File(root, MANIFEST_BACKUP_NAME).absoluteFile
    internal val lockFile = File(root, LOCK_NAME).absoluteFile
    internal val verifiedFileCache = mutableMapOf<String, ArtifactFileVerification>()

    val rootPath: String
        get() = root.path

    suspend fun load(): List<TextualInversionArtifact> = loadImpl()

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
    ): TextualInversionImportResult =
        importFromContentUriImpl(uri, trigger, modelFingerprint, tokenizerFingerprint, displayNameOverride)

    internal suspend fun importFromStream(
        openInput: () -> InputStream,
        trigger: String,
        displayName: String,
        modelFingerprint: String? = null,
        tokenizerFingerprint: String? = null
    ): TextualInversionImportResult = importFromStreamImpl(
        openInput, trigger, displayName, modelFingerprint, tokenizerFingerprint
    )


    /** Creates a provisional binding. It does not mutate the artifact manifest. */
    suspend fun bind(
        id: String,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = bindImpl(
        id, modelFingerprint, tokenizerFingerprint, profileId, profileRevision, runtime
    )

    suspend fun prepareBinding(
        artifact: TextualInversionArtifact,
        modelFingerprint: String,
        tokenizerFingerprint: String,
        profileId: String,
        profileRevision: Int,
        runtime: TextualInversionRuntime = TextualInversionRuntime.STABLE_DIFFUSION_CPP
    ): TextualInversionBinding = prepareBindingImpl(
        artifact, modelFingerprint, tokenizerFingerprint, profileId, profileRevision, runtime
    )

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
    ): TextualInversionSelectionLease = acquireSelectionLeaseImpl(
        ids, modelFingerprint, tokenizerFingerprint, profileId, profileRevision, runtime, executionAssetBinding
    )

    /**
     * Persists the most recent successful model/tokenizer binding as advisory evidence only.
     * Future requests still create a fresh exact binding and are admitted to the real native
     * tensor/schema load, so using an artifact with one model never locks it to that model.
     */
    suspend fun commitSuccessfulBindings(
        selection: TextualInversionSelection,
        nativeBindingFingerprint: String,
        nativeBindingStage: String
    ): TextualInversionSelection = commitSuccessfulBindingsImpl(
        selection, nativeBindingFingerprint, nativeBindingStage
    )

    suspend fun clear(id: String): Boolean = clearImpl(id)

    /** Removes only unreferenced, repository-owned files; never follows a symlink. */
    suspend fun pruneOrphans(): Int = pruneOrphansImpl()


    private class CommittedValue<T>(val value: T)

    /**
     * A caller cancellation may win before the manifest replacement, but not after it.  The
     * atomic holder lets us recover a committed result if withContext's return dispatch observes
     * cancellation after the non-cancellable IO block has already crossed its commit point.
     */
    internal suspend fun <T> withCommittedMutation(
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

    internal suspend fun <T> withStoreLock(
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

    internal fun ensureRoot() {
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

    companion object {
        private const val DIRECTORY = "textual_inversions"
        internal const val MANIFEST_NAME = "records.json"
        private const val MANIFEST_BACKUP_NAME = ".records.json.rollback"
        private const val LOCK_NAME = ".store.lock"
        internal const val MANIFEST_VERSION = 2
        internal const val MAX_RECORD_COUNT = 4_096
        internal const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
        internal const val MIN_FREE_SPACE_RESERVE_BYTES = 64L * 1024L * 1024L
        internal const val MAX_MANIFEST_BYTES = 4L * 1024L * 1024L
        internal const val COPY_BUFFER_BYTES = 64 * 1024
        internal const val MAX_SAFETENSORS_HEADER_BYTES = 1L * 1024L * 1024L
        internal const val STALE_TEMPORARY_AGE_MS = 24L * 60L * 60L * 1_000L
        internal const val UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        internal val IMPORT_PART_NAME_PATTERN = Regex(
            "^\\.$UUID_REGEX\\.(?:safetensors|pt|ckpt|bin)\\.$UUID_REGEX\\.part$"
        )
        internal val MANIFEST_TEMP_NAME_PATTERN = Regex(
            "^\\.records\\.json\\.$UUID_REGEX\\.tmp$"
        )
        internal val processFileLockMutex = Mutex()
    }
}

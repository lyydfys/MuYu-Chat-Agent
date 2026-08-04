package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal class SdxlRequestFileLock private constructor(
    private val randomAccessFile: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { randomAccessFile.close() }
    }

    companion object {
        fun acquire(
            file: File,
            shared: Boolean,
            timeoutMs: Long = SDXL_REQUEST_LOCK_TIMEOUT_MS
        ): SdxlRequestFileLock? {
            require(timeoutMs >= 0L) { "Split-SDXL lock timeout must be non-negative." }
            file.parentFile?.mkdirs()
            val randomAccessFile = RandomAccessFile(file, "rw")
            val channel = randomAccessFile.channel
            var ownershipTransferred = false
            try {
                val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
                while (true) {
                    val acquired = try {
                        channel.tryLock(0L, Long.MAX_VALUE, shared)
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (acquired != null) {
                        ownershipTransferred = true
                        return SdxlRequestFileLock(randomAccessFile, channel, acquired)
                    }
                    if (System.nanoTime() >= deadlineNanos) return null
                    Thread.sleep(SDXL_REQUEST_LOCK_POLL_MS)
                }
            } finally {
                if (!ownershipTransferred) {
                    runCatching { channel.close() }
                    runCatching { randomAccessFile.close() }
                }
            }
        }
    }
}

internal fun sdxlCleanupBarrierRetryDelayMs(attempt: Long): Long {
    require(attempt > 0L) { "Split-SDXL cleanup retry attempt must be positive." }
    var delayMs = SDXL_CLEANUP_RETRY_INITIAL_DELAY_MS
    var remainingDoublings = (attempt - 1L).coerceAtMost(6L)
    while (remainingDoublings > 0L && delayMs < SDXL_CLEANUP_RETRY_MAX_DELAY_MS) {
        delayMs = (delayMs * 2L).coerceAtMost(SDXL_CLEANUP_RETRY_MAX_DELAY_MS)
        remainingDoublings -= 1L
    }
    return delayMs
}

internal fun shouldReportSdxlCleanupBarrierRetry(attempt: Long): Boolean {
    require(attempt > 0L) { "Split-SDXL cleanup retry attempt must be positive." }
    val powerOfTwo = attempt and (attempt - 1L) == 0L
    val periodicAtMaximumDelay =
        sdxlCleanupBarrierRetryDelayMs(attempt) == SDXL_CLEANUP_RETRY_MAX_DELAY_MS &&
            attempt % SDXL_CLEANUP_RETRY_MAX_DELAY_REPORT_INTERVAL == 0L
    return powerOfTwo || periodicAtMaximumDelay
}

private fun pauseBeforeSdxlCleanupBarrierRetry(
    stage: String,
    attempt: Long,
    failure: Exception?
) {
    val delayMs = sdxlCleanupBarrierRetryDelayMs(attempt)
    if (failure != null && shouldReportSdxlCleanupBarrierRetry(attempt)) {
        runCatching {
            Log.w(
                SDXL_CLEANUP_LOG_TAG,
                "cleanup_barrier_retry stage=$stage attempt=$attempt delayMs=$delayMs " +
                    "failure=${failure.javaClass.simpleName}"
            )
        }
    }
    runCatching { Thread.sleep(delayMs) }
}

private fun incrementSdxlCleanupRetryAttempt(attempt: Long): Long =
    if (attempt == Long.MAX_VALUE) attempt else attempt + 1L

private fun splitSdxlRequestDigest(requestId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(requestId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun splitSdxlRequestLockFileForDigest(coordinationRoot: File, digest: String): File {
    require(digest.matches(Regex("^[0-9a-f]{64}$"))) { "Split-SDXL request lock digest is invalid." }
    return File(coordinationRoot, "split-request-$digest.lock")
}

private fun splitSdxlRequestLockFile(coordinationRoot: File, requestId: String): File =
    splitSdxlRequestLockFileForDigest(coordinationRoot, splitSdxlRequestDigest(requestId))

private fun splitSdxlAdmissionLockFile(coordinationRoot: File): File =
    File(coordinationRoot, "split-admission.lock")

private val SPLIT_SDXL_REQUEST_LOCK = Regex("^split-request-([0-9a-f]{64})\\.lock$")

private val SPLIT_SDXL_REQUEST_TOKEN = Regex(
    "^qnn-htp-[0-9]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)

private val SPLIT_SDXL_ORPHAN_ARTIFACT = Regex(
    "(${SPLIT_SDXL_REQUEST_TOKEN.pattern.removePrefix("^").removeSuffix("$")})" +
        "\\.(?:png|sdxl-conditioning\\.f32|latent\\.f32|latent\\.json|" +
        "input-rgb-nchw\\.f32|encoder-latent\\.f32|encoder-latent\\.json|" +
        "inpaint-mask-latent\\.f32|inpaint-mask-full\\.f32|" +
        "encoder-stage\\.json|unet-stage\\.json|unet-stage\\.json\\.projection\\.json|" +
        "vae-stage\\.json|qnn-stage\\.json)" +
        "(?:\\.part|\\.tmp)?$"
)

private val SPLIT_SDXL_ORPHAN_PREVIEW_DIRECTORY = Regex(
    "(${SPLIT_SDXL_REQUEST_TOKEN.pattern.removePrefix("^").removeSuffix("$")})" +
        "\\.unet-stage\\.json\\.previews$"
)

private fun requireSplitSdxlPreviewDisabled(
    params: JSONObject,
    boundary: String
) {
    require(!params.has("preview")) {
        "Split-SDXL $boundary does not support live preview."
    }
}

private fun isValidSplitSdxlJournalEntry(
    entry: ImageExecutionJournalEntry,
    handoffRoot: File
): Boolean {
    if (!SPLIT_SDXL_REQUEST_TOKEN.matches(entry.requestId) ||
        !entry.leaseToken.matches(Regex("^[0-9a-f]{32}$"))
    ) {
        return false
    }
    val root = runCatching { handoffRoot.canonicalFile }.getOrNull() ?: return false
    fun exactPath(path: String, suffix: String): Boolean {
        val declared = runCatching { File(path).absoluteFile }.getOrNull() ?: return false
        val canonical = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return declared == canonical && canonical.parentFile == root &&
            canonical.name == entry.requestId + suffix
    }
    if (!exactPath(entry.latentTempPath, ".latent.f32") ||
        !exactPath(entry.outputTempPath, ".png") ||
        entry.outputTempPaths.size != 1 ||
        !exactPath(entry.outputTempPaths.single(), ".png.part")
    ) {
        return false
    }
    val taskMode = runCatching {
        LocalImageTaskMode.fromWireName(
            JSONObject(entry.requestedSummaryJson).getString("taskMode")
        )
    }.getOrNull() ?: return false
    val expectedInputSuffixes = buildList {
        addAll(listOf(
        ".sdxl-conditioning.f32",
        ".sdxl-conditioning.f32.part",
        ".latent.json",
        ".latent.json.part",
        ".latent.f32.part",
        ".input-rgb-nchw.f32",
        ".input-rgb-nchw.f32.part",
        ".encoder-latent.f32",
        ".encoder-latent.f32.part",
        ".encoder-latent.json",
        ".encoder-latent.json.part",
        ".encoder-stage.json",
        ".encoder-stage.json.tmp",
        ".unet-stage.json",
        ".unet-stage.json.tmp",
        ".unet-stage.json.projection.json",
        ".unet-stage.json.projection.json.part",
        ".vae-stage.json",
        ".vae-stage.json.tmp"
        ))
        if (taskMode == LocalImageTaskMode.INPAINT) {
            add(".inpaint-mask-latent.f32")
            add(".inpaint-mask-latent.f32.part")
            add(".inpaint-mask-full.f32")
            add(".inpaint-mask-full.f32.part")
        }
    }
    if (entry.inputTempPaths.size != expectedInputSuffixes.size) return false
    return expectedInputSuffixes.all { suffix ->
        entry.inputTempPaths.any { path -> exactPath(path, suffix) }
    }
}

private fun listSplitSdxlOrphanArtifactsByDigest(
    handoffRoot: File
): Map<String, List<File>> {
    val root = handoffRoot.canonicalFile
    return root.listFiles().orEmpty().mapNotNull { file ->
        val match = when {
            file.isFile -> SPLIT_SDXL_ORPHAN_ARTIFACT.matchEntire(file.name)
            file.isDirectory -> SPLIT_SDXL_ORPHAN_PREVIEW_DIRECTORY.matchEntire(file.name)
            else -> null
        } ?: return@mapNotNull null
        splitSdxlRequestDigest(match.groupValues[1]) to file
    }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
}

private fun listSplitSdxlRequestLockDigests(
    coordinationRoot: File,
    directoryLister: (File) -> Array<File>?
): Set<String> {
    val root = coordinationRoot.canonicalFile
    val entries = checkNotNull(directoryLister(root)) {
        "Unable to enumerate persistent split-SDXL request locks; recovery is deferred."
    }
    return entries.mapNotNull { file ->
        SPLIT_SDXL_REQUEST_LOCK.matchEntire(file.name)?.groupValues?.getOrNull(1)
    }.toSet()
}

private fun listSplitSdxlCleanupDigests(
    artifactRoot: File,
    store: ImageExecutionJournalStore
): Set<String> = linkedSetOf<String>().apply {
    addAll(store.listJournalDigests())
    addAll(listSplitSdxlOrphanArtifactsByDigest(artifactRoot).keys)
}

private fun acquireSplitSdxlRecoveryLocks(
    artifactRoot: File,
    coordinationRoot: File,
    store: ImageExecutionJournalStore,
    requestLockTimeoutMs: Long,
    requestLockDirectoryLister: (File) -> Array<File>? = File::listFiles,
    retainedLockDigests: Set<String> = emptySet()
): LinkedHashMap<String, SdxlRequestFileLock> {
    require(requestLockTimeoutMs >= 0L) {
        "Split-SDXL recovery request lock timeout must be non-negative."
    }
    val artifacts = artifactRoot.canonicalFile
    val coordination = coordinationRoot.canonicalFile
    val acquired = linkedMapOf<String, SdxlRequestFileLock>()
    val confirmedDormant = linkedSetOf<String>()
    try {
        val cleanupDigests = listSplitSdxlCleanupDigests(artifacts, store)
        (cleanupDigests + retainedLockDigests).sorted().forEach { digest ->
            acquired[digest] = SdxlRequestFileLock.acquire(
                splitSdxlRequestLockFileForDigest(coordination, digest),
                shared = false,
                timeoutMs = requestLockTimeoutMs
            ) ?: error(
                "Split-SDXL phase digest $digest has not exited; recovery is deferred."
            )
        }

        // Request lock files remain stable inode tombstones until an admission-exclusive recovery
        // can prove both journal and artifact state are absent. Probe dormant history one
        // descriptor at a time so historical locks never exhaust the process descriptor limit.
        val dormantLockDigests = listSplitSdxlRequestLockDigests(
            coordination,
            requestLockDirectoryLister
        ) - cleanupDigests - retainedLockDigests
        dormantLockDigests.sorted().forEach { digest ->
            val probe = SdxlRequestFileLock.acquire(
                splitSdxlRequestLockFileForDigest(coordination, digest),
                shared = false,
                timeoutMs = requestLockTimeoutMs
            ) ?: error(
                "Split-SDXL phase digest $digest has not exited; recovery is deferred."
            )
            if (digest !in listSplitSdxlCleanupDigests(artifacts, store)) {
                val tombstone = splitSdxlRequestLockFileForDigest(coordination, digest)
                confirmedDormant += digest
                probe.close()
                runCatching { tombstone.delete() }
            } else {
                acquired[digest] = probe
            }
        }

        val newlyObservedCleanup = listSplitSdxlCleanupDigests(artifacts, store) - acquired.keys
        val newlyObservedLocks = listSplitSdxlRequestLockDigests(
            coordination,
            requestLockDirectoryLister
        ) -
            acquired.keys - confirmedDormant
        check(newlyObservedCleanup.isEmpty() && newlyObservedLocks.isEmpty()) {
            "Split-SDXL recovery observed a new active request; recovery is deferred."
        }
        return acquired
    } catch (error: Throwable) {
        acquired.values.toList().asReversed().forEach(SdxlRequestFileLock::close)
        throw error
    }
}

private fun deleteHeldDormantSplitSdxlLockTombstones(
    artifactRoot: File,
    coordinationRoot: File,
    store: ImageExecutionJournalStore,
    heldLocks: Map<String, SdxlRequestFileLock>,
    retainedLockDigests: Set<String> = emptySet()
) {
    val activeDigests = listSplitSdxlCleanupDigests(artifactRoot, store)
    heldLocks.keys
        .asSequence()
        .filterNot { digest -> digest in retainedLockDigests || digest in activeDigests }
        .forEach { digest ->
            // The caller owns admission plus this exact request inode exclusively. Re-read both
            // durable state sources immediately before unlinking the dormant tombstone.
            if (digest !in listSplitSdxlCleanupDigests(artifactRoot, store)) {
                heldLocks[digest]?.close()
                runCatching {
                    splitSdxlRequestLockFileForDigest(coordinationRoot, digest).delete()
                }
            }
        }
}

private fun sweepSplitSdxlOrphanArtifacts(
    handoffRoot: File,
    alreadyExclusivelyLockedDigests: Set<String>
): Set<String> {
    val artifactsByDigest = listSplitSdxlOrphanArtifactsByDigest(handoffRoot)
    val unlockedDigests = artifactsByDigest.keys - alreadyExclusivelyLockedDigests
    if (unlockedDigests.isNotEmpty()) return unlockedDigests
    artifactsByDigest.values.flatten()
        .sortedBy { artifact -> if (artifact.isDirectory) 0 else 1 }
        .forEach { artifact ->
            if (artifact.isDirectory) {
                val requestId = SPLIT_SDXL_ORPHAN_PREVIEW_DIRECTORY
                    .matchEntire(artifact.name)
                    ?.groupValues
                    ?.getOrNull(1)
                if (requestId != null) {
                    QnnImageStageJournal.cleanupSdxlProjectionPreview(
                        File(handoffRoot, "$requestId.unet-stage.json")
                    )
                }
            }
            runCatching { artifact.delete() }
        }
    return listSplitSdxlOrphanArtifactsByDigest(handoffRoot).keys
}

private fun cleanupLockedSplitSdxlProjectionDirectories(
    handoffRoot: File,
    exclusivelyLockedDigests: Set<String>
): Set<String> {
    val directories = handoffRoot.canonicalFile.listFiles().orEmpty().mapNotNull { file ->
        if (!file.isDirectory) return@mapNotNull null
        val match = SPLIT_SDXL_ORPHAN_PREVIEW_DIRECTORY.matchEntire(file.name)
            ?: return@mapNotNull null
        Triple(splitSdxlRequestDigest(match.groupValues[1]), match.groupValues[1], file)
    }
    val unlocked = directories.map { it.first }.toSet() - exclusivelyLockedDigests
    if (unlocked.isNotEmpty()) return unlocked
    directories.forEach { (_, requestId, _) ->
        QnnImageStageJournal.cleanupSdxlProjectionPreview(
            File(handoffRoot, "$requestId.unet-stage.json")
        )
    }
    return handoffRoot.canonicalFile.listFiles().orEmpty().mapNotNull { file ->
        SPLIT_SDXL_ORPHAN_PREVIEW_DIRECTORY.matchEntire(file.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::splitSdxlRequestDigest)
    }.toSet()
}

/**
 * Provider-owned durable lease for every request-scoped split-SDXL artifact.
 *
 * The lease is created before prompt conditioning or input-tensor preparation writes its first
 * byte. It intentionally remains non-terminal while [ImageExecutionPhase.PUBLISHING] means that
 * the coordinator has produced a PNG but the provider has not yet copied and cleaned it. Only the
 * provider may acknowledge the ownership transfer and remove the terminal journal.
 */
internal class SdxlTwoPhaseRequestLease private constructor(
    val requestId: String,
    private val artifactRoot: File,
    private val coordinationRoot: File,
    private val store: ImageExecutionJournalStore,
    private val trackedPaths: Set<String>,
    private val leaseToken: String,
    private val lockFile: File,
    private var ownerLock: SdxlRequestFileLock?,
    entry: ImageExecutionJournalEntry
) : AutoCloseable {
    var entry: ImageExecutionJournalEntry = entry
        private set

    fun requireGenerationIdentity(
        contract: SdxlImageExecutionContract,
        vararg artifacts: File
    ) {
        require(ownerLock != null) { "Split-SDXL request lease no longer owns its process lock." }
        val persisted = store.read(requestId)
            ?: error("Split-SDXL request lease journal disappeared before generation.")
        require(persisted.leaseToken == leaseToken &&
            isValidSplitSdxlJournalEntry(persisted, artifactRoot)
        ) {
            "Split-SDXL request lease epoch changed before generation."
        }
        entry = persisted
        require(entry.requestId == requestId &&
            entry.modelFingerprint.equals(contract.expected.modelFingerprint, ignoreCase = true) &&
            entry.profileFingerprint ==
            "${contract.expected.profileId}:${contract.expected.profileRevision}:${contract.expected.modelFingerprint}" &&
            entry.steps == contract.steps
        ) { "Split-SDXL request lease identity differs from the resolved execution contract." }
        artifacts.forEach { artifact ->
            require(artifact.canonicalPath in trackedPaths) {
                "Split-SDXL request artifact is not owned by its durable lease: ${artifact.path}"
            }
        }
    }

    fun update(next: ImageExecutionJournalEntry): ImageExecutionJournalEntry {
        require(ownerLock != null) { "Split-SDXL request lease no longer owns its process lock." }
        require(next.requestId == requestId) { "Split-SDXL request lease identity cannot change." }
        require(next.leaseToken == leaseToken) { "Split-SDXL request lease epoch cannot change." }
        entry = store.update(next, expectedLeaseToken = leaseToken)
        return entry
    }

    private fun releaseProcessOwnership() {
        ownerLock?.close()
        ownerLock = null
    }

    override fun close() = releaseProcessOwnership()

    internal fun releaseProcessOwnershipForFaultInjection() = close()

    /**
     * Acknowledges the provider's ownership boundary after it has copied the output. Cleanup runs
     * only while this method owns the request lock exclusively, so a cancelled isolated phase can
     * no longer recreate an artifact after deletion. If any artifact remains, the non-terminal
     * journal is deliberately retained so the next provider instance can recover it.
     */
    internal fun tryFinishAfterProviderCleanup(
        succeeded: Boolean,
        cancelled: Boolean,
        error: Throwable?,
        admissionTimeoutMs: Long = SDXL_REQUEST_LOCK_TIMEOUT_MS,
        requestTimeoutMs: Long = SDXL_REQUEST_FINISH_LOCK_TIMEOUT_MS
    ): Boolean {
        val admission = SdxlRequestFileLock.acquire(
            splitSdxlAdmissionLockFile(coordinationRoot),
            shared = false,
            timeoutMs = admissionTimeoutMs
        ) ?: return false
        return admission.use {
            close()
            val exclusive = SdxlRequestFileLock.acquire(
                lockFile,
                shared = false,
                timeoutMs = requestTimeoutMs
            ) ?: return@use false
            exclusive.use {
                finishUnderExclusiveBarrier(succeeded, cancelled, error)
                true
            }
        }
    }

    /**
     * Holds the provider's global native execution lease until every isolated phase has released
     * this request's exact persistent lock inode. Cancellation and finite timeouts may delay this
     * barrier, but can never let another native request overlap an unconfirmed QNN process.
     */
    suspend fun awaitFinishAfterProviderCleanup(
        succeeded: Boolean,
        cancelled: Boolean,
        error: Throwable?,
        lockAttemptTimeoutMs: Long = SDXL_REQUEST_LOCK_TIMEOUT_MS
    ) = withContext(NonCancellable) {
        var admission: SdxlRequestFileLock? = null
        var admissionAttempt = 0L
        while (admission == null) {
            var failure: Exception? = null
            admission = try {
                SdxlRequestFileLock.acquire(
                    splitSdxlAdmissionLockFile(coordinationRoot),
                    shared = false,
                    timeoutMs = lockAttemptTimeoutMs
                )
            } catch (error: Exception) {
                failure = error
                null
            }
            if (admission == null) {
                admissionAttempt = incrementSdxlCleanupRetryAttempt(admissionAttempt)
                pauseBeforeSdxlCleanupBarrierRetry("admission", admissionAttempt, failure)
            }
        }
        admission.use {
            // Admission prevents a new provider/recovery epoch while the provider drops its shared
            // ownership and waits for every already-dispatched phase's process-lifetime share.
            close()
            var exclusive: SdxlRequestFileLock? = null
            var requestAttempt = 0L
            while (exclusive == null) {
                var failure: Exception? = null
                exclusive = try {
                    SdxlRequestFileLock.acquire(
                        lockFile,
                        shared = false,
                        timeoutMs = lockAttemptTimeoutMs
                    )
                } catch (error: Exception) {
                    failure = error
                    null
                }
                if (exclusive == null) {
                    requestAttempt = incrementSdxlCleanupRetryAttempt(requestAttempt)
                    pauseBeforeSdxlCleanupBarrierRetry("request", requestAttempt, failure)
                }
            }
            exclusive.use {
                var finished = false
                var finishAttempt = 0L
                while (!finished) {
                    finished = try {
                        finishUnderExclusiveBarrier(succeeded, cancelled, error)
                        true
                    } catch (finishError: Exception) {
                        // Fail closed while admission, the exact request inode, and the global
                        // native gate remain held. A transient journal/storage failure may retry,
                        // but can never publish a runnable non-terminal epoch to a late phase.
                        finishAttempt = incrementSdxlCleanupRetryAttempt(finishAttempt)
                        pauseBeforeSdxlCleanupBarrierRetry(
                            "finish",
                            finishAttempt,
                            finishError
                        )
                        false
                    }
                }
            }
        }
    }

    private fun finishUnderExclusiveBarrier(
        succeeded: Boolean,
        cancelled: Boolean,
        error: Throwable?
    ) {
        var current = store.read(requestId)
        if (current == null) {
            QnnImageStageJournal.cleanupSdxlProjectionPreview(
                File(artifactRoot, "$requestId.unet-stage.json")
            )
            trackedPaths.forEach { path -> runCatching { File(path).delete() } }
            if (trackedPaths.none { path -> File(path).exists() }) {
                runCatching { lockFile.delete() }
            }
            return
        }
        require(current.requestId == requestId &&
            current.leaseToken == leaseToken &&
            isValidSplitSdxlJournalEntry(current, artifactRoot)
        ) {
            "Split-SDXL provider cannot finish a replaced request lease epoch."
        }
        entry = current
        if (!current.phase.terminal) {
            if (succeeded) {
                require(current.phase == ImageExecutionPhase.PUBLISHING) {
                    "Split-SDXL output cannot be acknowledged before the publishing boundary."
                }
            }
            if (cancelled && !current.cancellationRequested) {
                current = store.requestCancellation(
                    requestId,
                    expectedLeaseToken = leaseToken
                )
                entry = current
            }
            // Publish terminal/revoked state before touching any cache artifact. A phase whose
            // Binder transaction was accepted but has not yet taken its shared request lock must
            // observe this terminal epoch and reject before native execution.
            entry = store.markTerminal(
                requestId,
                phase = when {
                    succeeded -> ImageExecutionPhase.COMPLETED
                    cancelled -> ImageExecutionPhase.CANCELLED
                    else -> ImageExecutionPhase.FAILED
                },
                errorCode = when {
                    succeeded -> ""
                    cancelled -> "CANCELLED"
                    else -> "SDXL_PROVIDER_FAILED"
                },
                errorMessage = when {
                    succeeded -> ""
                    cancelled -> "Image generation was cancelled."
                    else -> error?.message.orEmpty().take(MAX_ERROR_MESSAGE_CHARS)
                },
                expectedLeaseToken = leaseToken
            )
        }
        QnnImageStageJournal.cleanupSdxlProjectionPreview(
            File(artifactRoot, "$requestId.unet-stage.json")
        )
        trackedPaths.forEach { path -> runCatching { File(path).delete() } }
        if (trackedPaths.any { path -> File(path).exists() }) return
        val deleted = store.deleteTerminal(requestId, expectedLeaseToken = leaseToken)
        if (deleted && store.read(requestId) == null) runCatching { lockFile.delete() }
    }

    companion object {
        private const val MAX_ERROR_MESSAGE_CHARS = 512

        fun acquire(
            requestId: String,
            params: JSONObject,
            workerPid: Int,
            coordinationRoot: File,
            embeddingsFile: File,
            latentFile: File,
            metadataFile: File,
            inputTensorFile: File,
            encoderLatentFile: File,
            encoderMetadataFile: File,
            outputFile: File,
            encoderJournal: File,
            unetJournal: File,
            vaeJournal: File,
            maskTensorFile: File? = null,
            fullMaskTensorFile: File? = null,
            parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer,
            clock: () -> Long = System::currentTimeMillis,
            recoveryRequestLockTimeoutMs: Long = SDXL_REQUEST_LOCK_TIMEOUT_MS,
            requestLockDirectoryLister: (File) -> Array<File>? = File::listFiles
        ): SdxlTwoPhaseRequestLease {
            require(workerPid > 0) { "Split-SDXL provider PID must be positive." }
            requireSplitSdxlPreviewDisabled(params, "lease artifact admission")
            require(params.optString("workerStrategy") == ImageWorkerStrategy.SPLIT_UNET_VAE.name) {
                "A split-SDXL request lease requires the real split UNet/VAE worker topology."
            }
            val profileId = params.optString("profileId").takeIf(String::isNotBlank)
                ?: error("Split-SDXL request lease requires a non-blank profileId contract field.")
            val profileRevision = params.optInt("profileRevision", -1)
            require(profileRevision >= 0) { "Split-SDXL request lease has an invalid profile revision." }
            val modelFingerprint = params.optString("modelFingerprint").takeIf(String::isNotBlank)
                ?: error("Split-SDXL request lease is missing modelFingerprint.")
            val steps = params.optInt("steps", 0)
            require(steps > 0) { "Split-SDXL request lease requires positive steps." }
            val taskMode = LocalImageTaskMode.fromWireName(params.getString("taskMode"))
            val inpaintArtifacts = if (taskMode == LocalImageTaskMode.INPAINT) {
                listOf(
                    requireNotNull(maskTensorFile) {
                        "Split-SDXL inpaint lease requires a latent mask artifact."
                    },
                    requireNotNull(fullMaskTensorFile) {
                        "Split-SDXL inpaint lease requires a full-resolution mask artifact."
                    },
                )
            } else {
                require(maskTensorFile == null && fullMaskTensorFile == null) {
                    "A non-inpaint split-SDXL lease cannot own mask artifacts."
                }
                emptyList()
            }
            val root = requireNotNull(outputFile.parentFile).canonicalFile
            val coordination = coordinationRoot.canonicalFile.apply { mkdirs() }
            val outputPart = File(outputFile.path + ".part")
            val inputArtifacts = buildList {
                addAll(listOf(
                embeddingsFile,
                File(embeddingsFile.path + ".part"),
                metadataFile,
                File(metadataFile.path + ".part"),
                File(latentFile.path + ".part"),
                inputTensorFile,
                File(inputTensorFile.path + ".part"),
                encoderLatentFile,
                File(encoderLatentFile.path + ".part"),
                encoderMetadataFile,
                File(encoderMetadataFile.path + ".part"),
                encoderJournal,
                File(encoderJournal.path + ".tmp"),
                unetJournal,
                File(unetJournal.path + ".tmp"),
                QnnImageStageJournal.sdxlProjectionPreviewJournalFile(unetJournal),
                File(QnnImageStageJournal.sdxlProjectionPreviewJournalFile(unetJournal).path + ".part"),
                vaeJournal,
                File(vaeJournal.path + ".tmp")
                ))
                inpaintArtifacts.forEach { artifact ->
                    add(artifact)
                    add(File(artifact.path + ".part"))
                }
            }
            val allArtifacts = listOf(latentFile, outputFile, outputPart) + inputArtifacts
            val canonicalPaths = allArtifacts.map { artifact -> artifact.canonicalPath }
            canonicalPaths.forEach { path ->
                require(path.startsWith(root.path + File.separator)) {
                    "Split-SDXL request artifacts must stay inside the private handoff root."
                }
            }
            val store = ImageExecutionJournalStore(
                directory = coordination,
                parentDirectorySyncer = parentDirectorySyncer,
                clock = clock
            )
            val admission = SdxlRequestFileLock.acquire(
                splitSdxlAdmissionLockFile(coordination),
                shared = false
            ) ?: error("Another split-SDXL recovery admission is active.")
            val recoveryLocks = linkedMapOf<String, SdxlRequestFileLock>()
            var ownerLock: SdxlRequestFileLock? = null
            val targetDigest = splitSdxlRequestDigest(requestId)
            val targetLockFile = splitSdxlRequestLockFile(coordination, requestId)
            try {
                recoveryLocks.putAll(
                    acquireSplitSdxlRecoveryLocks(
                        artifactRoot = root,
                        coordinationRoot = coordination,
                        store = store,
                        requestLockTimeoutMs = recoveryRequestLockTimeoutMs,
                        requestLockDirectoryLister = requestLockDirectoryLister,
                        retainedLockDigests = setOf(targetDigest)
                    )
                )
                check(cleanupLockedSplitSdxlProjectionDirectories(root, recoveryLocks.keys).isEmpty()) {
                    "Split-SDXL projection preview cleanup is blocked by an active request."
                }
                // Every readable request lock is now held exclusively, so neither a live provider
                // nor an isolated phase can touch the recorded artifacts while recovery cleans.
                store.pruneTerminalJournals(
                    cleanupRoots = listOf(root),
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) }
                )
                store.recoverInterrupted(
                    cleanupRoots = listOf(root),
                    preservePublishingOutputs = false,
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) },
                    isProcessAlive = { false }
                )
                store.pruneTerminalJournals(
                    cleanupRoots = listOf(root),
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) }
                )
                val busyOrphans = sweepSplitSdxlOrphanArtifacts(root, recoveryLocks.keys)
                check(busyOrphans.isEmpty()) {
                    "An unjournaled split-SDXL phase is still active; recovery is deferred."
                }
                check(listSplitSdxlCleanupDigests(root, store).isEmpty()) {
                    "Split-SDXL recovery could not durably remove an earlier request; admission is deferred."
                }

                deleteHeldDormantSplitSdxlLockTombstones(
                    artifactRoot = root,
                    coordinationRoot = coordination,
                    store = store,
                    heldLocks = recoveryLocks,
                    retainedLockDigests = setOf(targetDigest)
                )
                val targetExclusive = recoveryLocks.remove(targetDigest)
                    ?: SdxlRequestFileLock.acquire(
                        targetLockFile,
                        shared = false,
                        timeoutMs = recoveryRequestLockTimeoutMs
                    )
                    ?: error("Unable to reserve the split-SDXL request lock.")
                val leaseToken = UUID.randomUUID().toString().replace("-", "").lowercase()
                params.put(SDXL_REQUEST_LEASE_TOKEN_FIELD, leaseToken)
                    .put(SDXL_REQUEST_LEASE_LOCK_PATH_FIELD, targetLockFile.canonicalPath)
                val paramsSnapshot = JSONObject(params.toString()).toString()
                val createdAtMs = clock().coerceAtLeast(1L)
                val pendingEntry = ImageExecutionJournalEntry(
                    requestId = requestId,
                    modelFingerprint = modelFingerprint,
                    profileFingerprint = "$profileId:$profileRevision:$modelFingerprint",
                    requestedSummaryJson = paramsSnapshot,
                    resolvedSummaryJson = paramsSnapshot,
                    phase = ImageExecutionPhase.PREPARING,
                    steps = steps,
                    leaseToken = leaseToken,
                    workerPid = workerPid,
                    createdAtMs = createdAtMs,
                    latentTempPath = latentFile.canonicalPath,
                    outputTempPath = outputFile.canonicalPath,
                    outputTempPaths = listOf(outputPart.canonicalPath),
                    inputTempPaths = inputArtifacts.map { artifact -> artifact.canonicalPath }
                )
                require(isValidSplitSdxlJournalEntry(pendingEntry, root)) {
                    "Split-SDXL request artifacts do not match their exact token namespace."
                }
                val entry = try {
                    store.create(pendingEntry)
                } finally {
                    // The global admission lock makes this exclusive-to-shared handoff atomic with
                    // respect to every other recovery/acquire attempt.
                    targetExclusive.close()
                }
                ownerLock = SdxlRequestFileLock.acquire(targetLockFile, shared = true)
                    ?: error("Unable to retain the split-SDXL provider ownership lock.")
                return SdxlTwoPhaseRequestLease(
                    requestId = requestId,
                    artifactRoot = root,
                    coordinationRoot = coordination,
                    store = store,
                    trackedPaths = canonicalPaths.toSet(),
                    leaseToken = leaseToken,
                    lockFile = targetLockFile,
                    ownerLock = ownerLock,
                    entry = entry
                )
            } finally {
                recoveryLocks.values.forEach(SdxlRequestFileLock::close)
                admission.close()
            }
        }

        fun acquirePhaseOwnership(
            requestId: String,
            paramsJson: String,
            artifactRoot: File,
            coordinationRoot: File
        ): SdxlRequestFileLock {
            val params = JSONObject(paramsJson)
            val leaseToken = params.optString(SDXL_REQUEST_LEASE_TOKEN_FIELD)
            require(leaseToken.matches(Regex("^[0-9a-f]{32}$"))) {
                "Split-SDXL phase request is missing its lease epoch."
            }
            val artifacts = artifactRoot.canonicalFile
            val coordination = coordinationRoot.canonicalFile
            val expectedLockFile = splitSdxlRequestLockFile(coordination, requestId)
            val declaredLockFile = File(
                params.optString(SDXL_REQUEST_LEASE_LOCK_PATH_FIELD)
            ).canonicalFile
            require(declaredLockFile == expectedLockFile.canonicalFile) {
                "Split-SDXL phase request lease lock path is not canonical."
            }
            val phaseLock = SdxlRequestFileLock.acquire(declaredLockFile, shared = true)
                ?: error("Split-SDXL request lease is being recovered.")
            try {
                val persisted = ImageExecutionJournalStore(
                    coordination
                ).read(requestId) ?: error("Split-SDXL request lease journal is missing.")
                require(persisted.requestId == requestId &&
                    !persisted.phase.terminal && persisted.leaseToken == leaseToken &&
                    isValidSplitSdxlJournalEntry(persisted, artifacts)
                ) {
                    "Split-SDXL phase request lease epoch is stale."
                }
                return phaseLock
            } catch (error: Throwable) {
                phaseLock.close()
                throw error
            }
        }

        fun recoverAbandonedRequests(
            artifactRoot: File,
            coordinationRoot: File,
            requestLockTimeoutMs: Long = SDXL_REQUEST_LOCK_TIMEOUT_MS,
            parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer,
            requestLockDirectoryLister: (File) -> Array<File>? = File::listFiles
        ) {
            val root = artifactRoot.canonicalFile.apply { mkdirs() }
            val coordination = coordinationRoot.canonicalFile.apply { mkdirs() }
            val admission = SdxlRequestFileLock.acquire(
                splitSdxlAdmissionLockFile(coordination),
                shared = false
            ) ?: error("Another split-SDXL recovery admission is active.")
            val store = ImageExecutionJournalStore(
                directory = coordination,
                parentDirectorySyncer = parentDirectorySyncer
            )
            val recoveryLocks = linkedMapOf<String, SdxlRequestFileLock>()
            try {
                recoveryLocks.putAll(
                    acquireSplitSdxlRecoveryLocks(
                        artifactRoot = root,
                        coordinationRoot = coordination,
                        store = store,
                        requestLockTimeoutMs = requestLockTimeoutMs,
                        requestLockDirectoryLister = requestLockDirectoryLister
                    )
                )
                check(cleanupLockedSplitSdxlProjectionDirectories(root, recoveryLocks.keys).isEmpty()) {
                    "Split-SDXL projection preview cleanup is blocked by an active request."
                }
                store.pruneTerminalJournals(
                    cleanupRoots = listOf(root),
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) }
                )
                store.recoverInterrupted(
                    cleanupRoots = listOf(root),
                    preservePublishingOutputs = false,
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) },
                    isProcessAlive = { false }
                )
                store.pruneTerminalJournals(
                    cleanupRoots = listOf(root),
                    validateEntryForCleanup = { entry -> isValidSplitSdxlJournalEntry(entry, root) }
                )
                val busyOrphans = sweepSplitSdxlOrphanArtifacts(root, recoveryLocks.keys)
                check(busyOrphans.isEmpty()) {
                    "An unjournaled split-SDXL phase is still active; recovery is deferred."
                }
                check(listSplitSdxlCleanupDigests(root, store).isEmpty()) {
                    "Split-SDXL recovery could not durably remove an earlier request; recovery is deferred."
                }
                deleteHeldDormantSplitSdxlLockTombstones(
                    artifactRoot = root,
                    coordinationRoot = coordination,
                    store = store,
                    heldLocks = recoveryLocks
                )
            } finally {
                recoveryLocks.values.toList().asReversed().forEach(SdxlRequestFileLock::close)
                admission.close()
            }
        }
    }
}

internal class SdxlTwoPhaseCoordinator(
    context: Context
) {
    private val appContext = context.applicationContext
    private val coordinationRoot = File(
        appContext.noBackupFilesDir,
        SDXL_TWO_PHASE_COORDINATION_DIRECTORY
    ).apply { mkdirs() }
    private val cancelled = AtomicBoolean(false)
    private val stateLock = Any()
    private var activeClient: SdxlPhaseClient? = null

    /** Resets cancellation only at the provider's request-admission boundary. */
    fun begin() {
        synchronized(stateLock) {
            check(activeClient == null) { "Cannot begin a new SDXL request while a phase is active." }
            cancelled.set(false)
        }
    }

    fun cancel(): Boolean {
        cancelled.set(true)
        synchronized(stateLock) { activeClient }?.cancelAndTerminate()
        return true
    }

    fun prepareLease(
        requestId: String,
        params: JSONObject,
        embeddingsFile: File,
        latentFile: File,
        metadataFile: File,
        inputTensorFile: File,
        encoderLatentFile: File,
        encoderMetadataFile: File,
        outputFile: File,
        encoderJournal: File,
        unetJournal: File,
        vaeJournal: File,
        maskTensorFile: File? = null,
        fullMaskTensorFile: File? = null
    ): SdxlTwoPhaseRequestLease {
        requireSplitSdxlPreviewDisabled(params, "lease admission")
        return SdxlTwoPhaseRequestLease.acquire(
            requestId = requestId,
            params = params,
            workerPid = Process.myPid(),
            coordinationRoot = coordinationRoot,
            embeddingsFile = embeddingsFile,
            latentFile = latentFile,
            metadataFile = metadataFile,
            inputTensorFile = inputTensorFile,
            encoderLatentFile = encoderLatentFile,
            encoderMetadataFile = encoderMetadataFile,
            outputFile = outputFile,
            encoderJournal = encoderJournal,
            unetJournal = unetJournal,
            vaeJournal = vaeJournal,
            maskTensorFile = maskTensorFile,
            fullMaskTensorFile = fullMaskTensorFile
        )
    }

    suspend fun generate(
        lease: SdxlTwoPhaseRequestLease,
        requestId: String,
        bundleRoot: File,
        runtimeDirsJson: String,
        params: JSONObject,
        embeddingsFile: File,
        latentFile: File,
        metadataFile: File,
        preparedInput: SdxlPreparedInputTensor?,
        preparedInpaint: QnnPreparedInpaintInput? = null,
        inputTensorFile: File,
        encoderLatentFile: File,
        encoderMetadataFile: File,
        outputFile: File,
        encoderJournal: File,
        unetJournal: File,
        vaeJournal: File,
        onProgress: (LocalImageProgress) -> Unit
    ): String {
        requireSplitSdxlPreviewDisabled(params, "generation admission")
        val contract = SdxlImageExecutionContract.fromParams(params.toString())
        val ultraFix = contract.ultraFixRequestOrNull()
        val expectedUltraFixTileCount = ultraFix?.let(::expectedSdxlUltraFixTileCount)
        fun phaseParamsJson(): String =
            JSONObject(params.toString()).apply {
                requireSplitSdxlPreviewDisabled(this, "phase admission")
                // Keep the wire defensively clean even if a future mutable params source regresses.
                remove("preview")
            }.toString()
        val taskMode = LocalImageTaskMode.fromWireName(
            contract.paramsObject().getString("taskMode")
        )
        require(
            when (taskMode) {
                LocalImageTaskMode.TEXT_TO_IMAGE -> preparedInput == null && preparedInpaint == null
                LocalImageTaskMode.IMG2IMG -> preparedInput != null && preparedInpaint == null
                LocalImageTaskMode.INPAINT -> preparedInput == null && preparedInpaint != null
                else -> false
            }
        ) { "SDXL encoder admission does not match the resolved product task mode." }
        val encoderInput = preparedInpaint?.source ?: preparedInput
        ultraFix?.let { request ->
            val prepared = requireNotNull(preparedInput) {
                "Split-SDXL UltraFix requires one target-sized prepared RGB tensor."
            }
            require(taskMode == LocalImageTaskMode.IMG2IMG && preparedInpaint == null &&
                prepared.tensorWidth == request.targetWidth &&
                prepared.tensorHeight == request.targetHeight
            ) { "Split-SDXL UltraFix prepared input contract is invalid." }
        }
        preparedInpaint?.let { prepared ->
            require(prepared.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
                prepared.targetWidth == contract.width && prepared.targetHeight == contract.height
            ) { "Split-SDXL inpaint accepts only an exact latent_blend_4 topology." }
        }
        val expectedVaeEncoderContextSha256 = if (encoderInput != null) {
            params.getString("vaeEncoderContextSha256").lowercase().also { sha256 ->
                require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Resolved VAE encoder context SHA-256 is missing or malformed."
                }
            }
        } else {
            ""
        }
        encoderInput?.let { prepared ->
            require(File(prepared.tensorPath).canonicalFile == inputTensorFile.canonicalFile &&
                inputTensorFile.isFile && inputTensorFile.length() == prepared.tensorBytes &&
                sdxlArtifactSha256(inputTensorFile) == prepared.tensorSha256
            ) { "SDXL input tensor changed before encoder admission." }
        }
        lease.requireGenerationIdentity(
            contract,
            embeddingsFile,
            latentFile,
            metadataFile,
            inputTensorFile,
            encoderLatentFile,
            encoderMetadataFile,
            outputFile,
            encoderJournal,
            unetJournal,
            vaeJournal,
            *listOfNotNull(
                preparedInpaint?.maskTensorPath?.let(::File),
                preparedInpaint?.fullMaskTensorPath?.let(::File),
            ).toTypedArray()
        )
        require(lease.requestId == requestId) { "Split-SDXL request lease id mismatch." }
        var executionJournal = lease.entry
        fun updateExecutionJournal(
            phase: ImageExecutionPhase = executionJournal.phase,
            step: Int = executionJournal.step,
            nativeStageMask: Long = executionJournal.nativeStageMask,
            nativeGenerationSequence: Long? = executionJournal.nativeGenerationSequence
        ) {
            if (executionJournal.phase.terminal) return
            executionJournal = lease.update(
                executionJournal.copy(
                    phase = phase,
                    step = step.coerceAtLeast(executionJournal.step).coerceAtMost(contract.steps),
                    nativeStageMask = nativeStageMask or executionJournal.nativeStageMask,
                    nativeGenerationSequence = nativeGenerationSequence
                        ?: executionJournal.nativeGenerationSequence,
                    updatedAtMs = System.currentTimeMillis()
                        .coerceAtLeast(executionJournal.updatedAtMs + 1L)
                )
            )
        }
        var mergedStages = emptyList<String>()
        fun report(envelope: SdxlImagePhaseProgress) {
            require(envelope.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
                "Split-SDXL phase progress cannot carry live-preview evidence."
            }
            envelope.progress.requireSdxlPreviewDisabled("Split-SDXL phase progress")
            mergedStages = SdxlTwoPhaseJournal.merge(mergedStages, envelope)
            updateExecutionJournal(
                phase = when (envelope.phase) {
                    SdxlImagePhase.ENCODER -> ImageExecutionPhase.PREPARING
                    SdxlImagePhase.UNET -> ImageExecutionPhase.SAMPLING
                    SdxlImagePhase.VAE -> ImageExecutionPhase.DECODING
                },
                step = if (envelope.phase == SdxlImagePhase.ENCODER) {
                    0
                } else {
                    envelope.progress.step
                }
            )
            onProgress(
                envelope.progress.copy(
                    phase = "sdxl_${envelope.phase.wireName}:${envelope.progress.phase}",
                    message = "${envelope.phase.wireName.uppercase()} ${envelope.runtimeProfile} " +
                        "pid=${envelope.workerPid}: ${envelope.progress.message}",
                    stageTrace = mergedStages
                )
            )
        }
        QnnImageStageJournal.cleanupSdxlProjectionPreview(unetJournal)
        cleanupHandoff(
            latentFile,
            metadataFile,
            encoderLatentFile,
            encoderMetadataFile,
            outputFile,
            encoderJournal,
            unetJournal,
            vaeJournal
        )
        try {
            val phaseRuntimeDirs = stageBothRuntimeProfiles(
                bundleRoot = bundleRoot,
                packagedRuntimeDirsJson = runtimeDirsJson
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            var encoder: SdxlPhaseCompletion? = null
            var encoderNative: JSONObject? = null
            var encoderMetadata: SdxlEncoderLatentMetadata? = null
            var encoderTransportHtpArch: Int? = null
            var encoderRuntimeProfile: SdxlQnnRuntimeProfile? = null
            if (encoderInput != null) {
                val encoderAttempt = runPhaseWithRuntimeCandidates(
                    phase = SdxlImagePhase.ENCODER,
                    candidates = phaseRuntimeDirs.encoderCandidates,
                    request = SdxlImagePhaseRequest(
                        requestId = requestId,
                        phase = SdxlImagePhase.ENCODER,
                        runtimeProfile = phaseRuntimeDirs.encoderCandidates.first().runtimeProfile,
                        sourceArtifactProducerHtpArch = 0,
                        profileId = contract.expected.profileId,
                        profileRevision = contract.expected.profileRevision,
                        modelFingerprint = contract.expected.modelFingerprint,
                        steps = contract.steps,
                        width = contract.width,
                        height = contract.height,
                        bundleRoot = bundleRoot.canonicalPath,
                        paramsJson = phaseParamsJson(),
                        embeddingsPath = "",
                        latentPath = encoderLatentFile.canonicalPath,
                        metadataPath = encoderMetadataFile.canonicalPath,
                        outputPath = "",
                        journalPath = encoderJournal.canonicalPath,
                        conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                        expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256,
                        inputTensorPath = inputTensorFile.canonicalPath
                    ),
                    timeoutMs = sdxlEncoderPhaseTimeoutMs(
                        encoderExecutionCount = expectedUltraFixTileCount ?: 1
                    ),
                    onProgress = ::report
                )
                val completedEncoder = encoderAttempt.completion
                encoderRuntimeProfile = encoderAttempt.candidate.runtimeProfile
                encoder = completedEncoder
                check(completedEncoder.processDeathConfirmed) {
                    "VAE encoder process did not exit before UNet admission."
                }
                val completedEncoderNative = JSONObject(completedEncoder.result.nativeResultJson)
                encoderNative = completedEncoderNative
                val completedEncoderTransport = validateSdxlNativeTransport(
                    phase = SdxlImagePhase.ENCODER,
                    expectedRuntimeProfile = encoderAttempt.candidate.runtimeProfile,
                    nativeResult = completedEncoderNative
                )
                encoderTransportHtpArch = completedEncoderTransport
                validateSdxlEncoderNativeEvidence(contract, completedEncoderNative)
                val committedEncoderMetadata = SdxlEncoderLatentArtifact.validate(
                    requestId = requestId,
                    latentFile = encoderLatentFile,
                    metadataFile = encoderMetadataFile,
                    expectedProducerArch = completedEncoderTransport,
                    contract = contract
                )
                encoderMetadata = committedEncoderMetadata
                require(committedEncoderMetadata.encoderNativeGenerationSequence ==
                    completedEncoder.result.nativeGenerationSequence
                ) { "Encoder result sequence does not match committed latent metadata." }
                mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                    mergedStages,
                    SdxlImagePhase.ENCODER,
                    completedEncoder.result.workerPid,
                    completedEncoder.result.runtimeProfile,
                    "process_exit_confirmed"
                )
                params.put("encoderLatentSha256", committedEncoderMetadata.sha256)
                    .put("encoderNativeGenerationSequence", completedEncoder.result.nativeGenerationSequence)
                    .put("encoderNativeStageMask", completedEncoder.result.nativeStageMask)
                    .put(
                        "encoderNativeDetailStageMaskHex",
                        completedEncoder.result.nativeDetailStageMask.toFixedUInt64Hex()
                    )
                ultraFix?.let {
                    val tilePlanSha256 = completedEncoderNative
                        .getString("ultraFixTilePlanSha256").lowercase()
                    val tileCount = completedEncoderNative.getInt("ultraFixTileCount")
                    require(tilePlanSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                        tileCount == expectedUltraFixTileCount
                    ) {
                        "Split-SDXL UltraFix encoder did not publish a valid tile-plan handoff."
                    }
                    listOf(
                        "ultraFixEncoderGraphExecutionCount",
                        "ultraFixEncoderTileSuccessCount",
                        "ultraFixSourceWidth",
                        "ultraFixSourceHeight",
                        "ultraFixSourceResizedWidth",
                        "ultraFixSourceResizedHeight",
                        "ultraFixSourceCropLeft",
                        "ultraFixSourceCropTop"
                    ).forEach { field -> params.put(field, completedEncoderNative.get(field)) }
                    listOf(
                        "ultraFixEncoderInputProofSha256",
                        "ultraFixEncoderMeanProofSha256",
                        "ultraFixEncoderStdProofSha256"
                    ).forEach { field ->
                        val digest = completedEncoderNative.getString(field).lowercase()
                        require(digest.matches(Regex("^[a-f0-9]{64}$"))) {
                            "Split-SDXL UltraFix encoder $field is malformed."
                        }
                        params.put(field, digest)
                    }
                    params.put("ultraFixTilePlanSha256", tilePlanSha256)
                        .put("ultraFixTileCount", tileCount)
                }
                updateExecutionJournal(
                    phase = ImageExecutionPhase.SAMPLING,
                    step = 0,
                    nativeStageMask = completedEncoder.result.nativeStageMask
                )
                check(!cancelled.get()) { "SDXL generation was cancelled." }
            }
            val unetTimeoutExecutionCount = ultraFix?.let { request ->
                val tileCount = params.getInt("ultraFixTileCount")
                Math.multiplyExact(
                    Math.multiplyExact(tileCount, request.inversionSteps),
                    if (contract.expected.useCfg) 3 else 2
                )
            } ?: contract.expectedUnetExecutionCount
            val unetAttempt = runPhaseWithRuntimeCandidates(
                phase = SdxlImagePhase.UNET,
                candidates = phaseRuntimeDirs.unetCandidates,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.UNET,
                    runtimeProfile = phaseRuntimeDirs.unetCandidates.first().runtimeProfile,
                    sourceArtifactProducerHtpArch = encoderTransportHtpArch ?: 0,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    paramsJson = phaseParamsJson(),
                    embeddingsPath = embeddingsFile.canonicalPath,
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = "",
                    journalPath = unetJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                    maskTensorPath = preparedInpaint?.maskTensorPath.orEmpty(),
                    sourceLatentPath = if (encoderInput == null) "" else encoderLatentFile.canonicalPath,
                    sourceMetadataPath = if (encoderInput == null) "" else encoderMetadataFile.canonicalPath
                ),
                timeoutMs = sdxlUnetPhaseTimeoutMs(unetTimeoutExecutionCount),
                onProgress = ::report
            )
            val unet = unetAttempt.completion
            check(unet.processDeathConfirmed) { "UNet phase process did not exit before VAE admission." }
            val unetNative = JSONObject(unet.result.nativeResultJson)
            require(unet.result.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
                "Split-SDXL UNet result cannot carry live-preview evidence."
            }
            val unetTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.UNET,
                expectedRuntimeProfile = unetAttempt.candidate.runtimeProfile,
                nativeResult = unetNative
            )
            val metadata = SdxlLatentArtifact.validate(
                requestId = requestId,
                latentFile = latentFile,
                metadataFile = metadataFile,
                expectedProducerArch = unetTransportHtpArch,
                contract = contract
            )
            ultraFix?.let {
                require(unetNative.getString("ultraFixTilePlanSha256").lowercase() ==
                    params.getString("ultraFixTilePlanSha256").lowercase() &&
                    unetNative.getInt("ultraFixTileCount") == params.getInt("ultraFixTileCount")
                ) { "Split-SDXL UltraFix UNet changed the encoder tile-plan identity." }
                listOf(
                    "ultraFixInversionStepCount",
                    "ultraFixInversionGraphExecutionCount",
                    "ultraFixInversionTileSuccessCount",
                    "ultraFixRefinementStepCount",
                    "ultraFixRefinementPositiveGraphExecutionCount",
                    "ultraFixRefinementNegativeGraphExecutionCount",
                    "ultraFixRefinementTileSuccessCount",
                    "ultraFixPhysicalUnetGraphExecutionCount",
                    "ultraFixQualityStepEvaluationCount",
                    "ultraFixNoiseInjectionStepCount",
                    "ultraFixStructureGuidanceStepCount"
                ).forEach { field -> params.put(field, unetNative.get(field)) }
                listOf(
                    "ultraFixNoiseInjectionSeedFingerprint",
                    "ultraFixNoiseInjectionChecksum",
                    "ultraFixStructureGuidanceChecksum",
                    "ultraFixTrajectoryNoiseChecksum"
                ).forEach { field ->
                    val value = unetNative.getString(field).lowercase()
                    require(
                        (field.endsWith("SeedFingerprint") && value.matches(Regex("^[a-f0-9]{64}$"))) ||
                            (!field.endsWith("SeedFingerprint") && value.matches(Regex("^[a-f0-9]{16}$")))
                    ) { "Split-SDXL UltraFix $field is malformed." }
                    params.put(field, value)
                }
                params.put("ultraFixSampleMethod", unetNative.getString("ultraFixSampleMethod"))
                    .put("ultraFixNativeScheduler", unetNative.getString("ultraFixNativeScheduler"))
            }
            require(metadata.unetNativeGenerationSequence == unet.result.nativeGenerationSequence) {
                "UNet result sequence does not match committed latent metadata."
            }
            require(metadata.unetNativeStageMask == unet.result.nativeStageMask) {
                "UNet stage mask does not match committed latent metadata."
            }
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.UNET,
                unet.result.workerPid,
                unet.result.runtimeProfile,
                "process_exit_confirmed"
            )
            updateExecutionJournal(
                phase = ImageExecutionPhase.DECODING,
                step = contract.steps,
                nativeStageMask = unet.result.nativeStageMask,
                nativeGenerationSequence = unet.result.nativeGenerationSequence
            )
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            val vaeAttempt = runPhaseWithRuntimeCandidates(
                phase = SdxlImagePhase.VAE,
                candidates = phaseRuntimeDirs.vaeCandidates,
                request = SdxlImagePhaseRequest(
                    requestId = requestId,
                    phase = SdxlImagePhase.VAE,
                    runtimeProfile = phaseRuntimeDirs.vaeCandidates.first().runtimeProfile,
                    sourceArtifactProducerHtpArch = unetTransportHtpArch,
                    profileId = contract.expected.profileId,
                    profileRevision = contract.expected.profileRevision,
                    modelFingerprint = contract.expected.modelFingerprint,
                    steps = contract.steps,
                    width = contract.width,
                    height = contract.height,
                    bundleRoot = bundleRoot.canonicalPath,
                    paramsJson = phaseParamsJson(),
                    embeddingsPath = "",
                    latentPath = latentFile.canonicalPath,
                    metadataPath = metadataFile.canonicalPath,
                    outputPath = outputFile.canonicalPath,
                    journalPath = vaeJournal.canonicalPath,
                    conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                    inputTensorPath = preparedInpaint?.source?.tensorPath.orEmpty(),
                    fullMaskTensorPath = preparedInpaint?.fullMaskTensorPath.orEmpty()
                ),
                timeoutMs = sdxlVaePhaseTimeoutMs(
                    vaeExecutionCount = ultraFix?.let {
                        params.getInt("ultraFixTileCount")
                    } ?: SDXL_DEFAULT_VAE_EXECUTION_COUNT
                ),
                onProgress = ::report
            )
            val vae = vaeAttempt.completion
            check(vae.processDeathConfirmed) { "VAE phase process did not exit after PNG publication." }
            val vaeNative = JSONObject(vae.result.nativeResultJson)
            val vaeTransportHtpArch = validateSdxlNativeTransport(
                phase = SdxlImagePhase.VAE,
                expectedRuntimeProfile = vaeAttempt.candidate.runtimeProfile,
                nativeResult = vaeNative
            )
            validateSdxlVaeNativeEvidence(contract, vaeNative)
            check(outputFile.isFile && outputFile.length() > 0L) { "VAE phase output is missing." }
            mergedStages = SdxlTwoPhaseJournal.appendBoundary(
                mergedStages,
                SdxlImagePhase.VAE,
                vae.result.workerPid,
                vae.result.runtimeProfile,
                "process_exit_confirmed"
            )
            updateExecutionJournal(
                phase = ImageExecutionPhase.PUBLISHING,
                step = contract.steps,
                nativeStageMask = unet.result.nativeStageMask or vae.result.nativeStageMask
            )
            onProgress(
                LocalImageProgress(
                    phase = "sdxl_two_phase_finalizing",
                    message = buildString {
                        append("SDXL isolated phases finished; validating merged output and evidence: ")
                        encoderTransportHtpArch?.let { append("encoder=V$it, ") }
                        append("UNet=V$unetTransportHtpArch, VAE=V$vaeTransportHtpArch.")
                    },
                    step = contract.steps,
                    steps = contract.steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = contract.width,
                    height = contract.height,
                    cancelRequested = false,
                    stageTrace = mergedStages
                )
            )
            val finalResult = mergeSdxlPhaseNativeResults(
                contract = contract,
                encoderResult = encoder?.result,
                encoderNative = encoderNative,
                encoderMetadata = encoderMetadata,
                unetResult = unet.result,
                unetNative = unetNative,
                vaeResult = vae.result,
                vaeNative = vaeNative,
                metadata = metadata,
                encoderRuntimeProfile = encoderRuntimeProfile,
                unetRuntimeProfile = unetAttempt.candidate.runtimeProfile,
                vaeRuntimeProfile = vaeAttempt.candidate.runtimeProfile,
                unetTransportHtpArch = unetTransportHtpArch,
                vaeTransportHtpArch = vaeTransportHtpArch,
                outputFile = outputFile,
                stageTrace = mergedStages,
                preparedInpaint = preparedInpaint
            )
            // PUBLISHING remains non-terminal until LocalImageProvider has copied the PNG and
            // removed every request-scoped artifact. The durable lease must span that ownership
            // handoff so a provider crash after this return can still recover the output.
            return finalResult.toString()
        } finally {
            // The provider-owned lease performs all artifact cleanup only after it acquires the
            // request lock exclusively. Coroutine cancellation must not delete paths while an
            // isolated phase process can still be inside a synchronous native call.
        }
    }

    private suspend fun runPhaseWithRuntimeCandidates(
        phase: SdxlImagePhase,
        candidates: List<SdxlPhaseRuntimeCandidate>,
        request: SdxlImagePhaseRequest,
        timeoutMs: Long,
        onProgress: (SdxlImagePhaseProgress) -> Unit
    ): SdxlPhaseCandidateCompletion {
        val selected = executeSdxlRuntimeCandidates(
            phase = phase,
            candidates = candidates,
            isCancelled = cancelled::get
        ) { candidate ->
            runPhase(
                phase = phase,
                request = request.copy(
                    runtimeProfile = candidate.runtimeProfile
                ),
                timeoutMs = timeoutMs,
                onProgress = onProgress
            )
        }
        return SdxlPhaseCandidateCompletion(
            candidate = selected.candidate,
            completion = selected.value
        )
    }

    private suspend fun runPhase(
        phase: SdxlImagePhase,
        request: SdxlImagePhaseRequest,
        timeoutMs: Long,
        onProgress: (SdxlImagePhaseProgress) -> Unit
    ): SdxlPhaseCompletion {
        val client = SdxlPhaseClient(appContext, phase, onProgress)
        synchronized(stateLock) { activeClient = client }
        return try {
            check(!cancelled.get()) { "SDXL generation was cancelled." }
            client.execute(request, timeoutMs)
        } finally {
            synchronized(stateLock) { if (activeClient === client) activeClient = null }
            client.close()
        }
    }

    private fun cleanupHandoff(vararg files: File) {
        files.forEach { file ->
            runCatching { file.delete() }
            runCatching { File(file.path + ".part").delete() }
            file.parentFile?.mkdirs()
        }
    }

    private fun stageBothRuntimeProfiles(
        bundleRoot: File,
        packagedRuntimeDirsJson: String
    ): SdxlPhaseRuntimeDirectories {
        val bundleContextProfile = qnnImageBundleRuntimeProfileForArchOrNull(
            bundleRoot,
            SDXL_ARCHIVE_CONTEXT_HTP_ARCH
        )
        val preferredTransportArch = runCatching {
            DeviceProfileReader(appContext).read().accelerationProfile
        }.getOrNull()?.let { acceleration ->
            DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
                acceleration.chipsetCode
            ) ?: acceleration.qnnRuntime.htpArchVersion.takeIf { it > 0 }
        }
        val candidates = if (bundleContextProfile == null) {
            // Public SDXL archives contain graph contexts and prompt encoders,
            // but no host/Skel/Stub libraries. Enumerate every complete APK/OEM
            // transport. Device discovery only ranks the first attempt; all
            // candidates remain available to real load in disposable workers.
            sdxlRuntimeCandidatesForPackagedDirs(
                runtimeDirsJson = packagedRuntimeDirsJson,
                preferredHtpArch = preferredTransportArch
            )
        } else {
            val stager = QnnImageRuntimeStager(
                File(appContext.codeCacheDir, "qnn-image-runtime-sdxl-phases")
            )
            val availableProfiles = qnnImageBundleRuntimeProfiles(bundleRoot)
            val orderedProfiles = buildList {
                preferredTransportArch
                    ?.let { arch -> availableProfiles.firstOrNull { it.htpArchVersion == arch } }
                    ?.let(::add)
                add(bundleContextProfile)
                addAll(availableProfiles)
            }.distinctBy { profile -> profile.directory.canonicalPath to profile.htpArchVersion }
            val staged = orderedProfiles.mapNotNull { transportProfile ->
                val result = if (transportProfile.htpArchVersion == bundleContextProfile.htpArchVersion) {
                    stager.stage(bundleContextProfile)
                } else {
                    stager.stage(
                        QnnImageRuntimeStagePlan(
                            contextProfile = bundleContextProfile,
                            transportProfile = transportProfile
                        )
                    )
                }
                result.runtime?.let { runtime ->
                    val directory = runtime.directory.canonicalPath
                    SdxlPhaseRuntimeCandidate(
                        runtimeProfile = SdxlQnnRuntimeProfile(
                            hostDirectory = directory,
                            dspDirectory = directory,
                            htpArchVersion = transportProfile.htpArchVersion
                        )
                    )
                }
            }
            require(staged.isNotEmpty()) {
                "Unable to stage any complete SDXL QNN runtime candidate for real execution."
            }
            staged
        }
        // Every attempt gets one exact coherent host/DSP tuple. Side-loaded and staged
        // runtimes stay self-contained; an OEM platform runtime may bind two platform
        // directories explicitly. A retry advances only after the prior process has died.
        return SdxlPhaseRuntimeDirectories(
            encoderCandidates = candidates,
            unetCandidates = candidates,
            vaeCandidates = candidates
        )
    }
}

private fun requireSplitSdxlPreviewDisabledEvidence(
    phase: SdxlImagePhase,
    requestedPreview: SdxlProjectionPreviewRequest?,
    nativeResult: JSONObject
) {
    require(requestedPreview == null) {
        "Split-SDXL ${phase.wireName} result carried a forbidden live-preview request."
    }
    require(SdxlProjectionPreviewAudit.fromNativeResult(nativeResult) ==
        SdxlProjectionPreviewAudit.NONE
    ) { "Split-SDXL ${phase.wireName} result carried legacy projection-preview evidence." }

    fun requireExactZero(field: String) {
        val number = nativeResult.opt(field) as? Number
            ?: error("Split-SDXL $field evidence must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() && value == 0L) {
            "Split-SDXL ${phase.wireName} result carried preview evidence in $field."
        }
    }

    listOf(
        "previewVaeExecutionAttemptCount",
        "previewVaeExecutionCount",
        "previewVaeExecutionMsTotal",
        "previewPublicationCount",
        "previewLastStep",
        "previewLastRevision"
    ).forEach(::requireExactZero)
    listOf(
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount"
    ).forEach { field ->
        if (nativeResult.has(field)) requireExactZero(field)
    }
    require(nativeResult.opt("previewFailureCode") == "" &&
        (!nativeResult.has("previewPath") || nativeResult.opt("previewPath") == "") &&
        (!nativeResult.has("previewMimeType") || nativeResult.opt("previewMimeType") == "") &&
        (!nativeResult.has("previewNoisy") || nativeResult.opt("previewNoisy") == false)
    ) { "Split-SDXL ${phase.wireName} result exposed transient preview evidence." }
}

internal fun mergeSdxlPhaseNativeResults(
    contract: SdxlImageExecutionContract,
    unetResult: SdxlImagePhaseResult,
    unetNative: JSONObject,
    vaeResult: SdxlImagePhaseResult,
    vaeNative: JSONObject,
    metadata: SdxlLatentMetadata,
    unetRuntimeProfile: SdxlQnnRuntimeProfile,
    vaeRuntimeProfile: SdxlQnnRuntimeProfile,
    unetTransportHtpArch: Int,
    vaeTransportHtpArch: Int,
    outputFile: File,
    stageTrace: List<String>,
    encoderResult: SdxlImagePhaseResult? = null,
    encoderNative: JSONObject? = null,
    encoderMetadata: SdxlEncoderLatentMetadata? = null,
    encoderRuntimeProfile: SdxlQnnRuntimeProfile? = null,
    preparedInpaint: QnnPreparedInpaintInput? = null
): JSONObject {
    require(listOf(encoderResult, encoderNative, encoderMetadata, encoderRuntimeProfile)
        .all { it == null } ||
        listOf(encoderResult, encoderNative, encoderMetadata, encoderRuntimeProfile)
            .all { it != null }
    ) { "SDXL encoder result, native proof, metadata, and exact runtime profile must be present together." }
    val hasEncoder = encoderResult != null
    val taskMode = LocalImageTaskMode.fromWireName(
        contract.paramsObject().getString("taskMode")
    )
    val ultraFix = contract.ultraFixRequestOrNull()
    require(
        when (taskMode) {
            LocalImageTaskMode.TEXT_TO_IMAGE -> !hasEncoder && preparedInpaint == null
            LocalImageTaskMode.IMG2IMG -> hasEncoder && preparedInpaint == null
            LocalImageTaskMode.INPAINT -> hasEncoder && preparedInpaint != null
            else -> false
        }
    ) { "SDXL phase topology does not match the resolved product task mode." }
    preparedInpaint?.let { prepared ->
        require(prepared.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
            prepared.targetWidth == contract.width && prepared.targetHeight == contract.height
        ) { "Split-SDXL inpaint merge requires exact latent_blend_4 artifacts." }
    }
    require(ultraFix == null ||
        (taskMode == LocalImageTaskMode.IMG2IMG && hasEncoder && preparedInpaint == null)
    ) { "Split-SDXL UltraFix requires exactly the three encoder/UNet/VAE phases." }
    require(unetResult.phase == SdxlImagePhase.UNET) { "Expected UNet phase result." }
    require(vaeResult.phase == SdxlImagePhase.VAE) { "Expected VAE phase result." }
    val requestedPreview = SdxlProjectionPreviewRequest.fromParamsOrNull(contract.paramsObject())
    require(requestedPreview == null &&
        unetResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE &&
        vaeResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE &&
        (encoderResult == null || encoderResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE)
    ) { "Split-SDXL phase results cannot carry live-preview evidence." }
    requireSplitSdxlPreviewDisabledEvidence(
        phase = SdxlImagePhase.UNET,
        requestedPreview = requestedPreview,
        nativeResult = unetNative
    )
    requireSplitSdxlPreviewDisabledEvidence(
        phase = SdxlImagePhase.VAE,
        requestedPreview = requestedPreview,
        nativeResult = vaeNative
    )
    encoderNative?.let { native ->
        requireSplitSdxlPreviewDisabledEvidence(
            phase = SdxlImagePhase.ENCODER,
            requestedPreview = requestedPreview,
            nativeResult = native
        )
    }
    val disabledPreviewAudit = SdxlProjectionPreviewAudit.NONE
    require(encoderResult == null || encoderResult.phase == SdxlImagePhase.ENCODER) {
        "Expected VAE encoder phase result."
    }
    require(unetResult.requestId == vaeResult.requestId) { "SDXL phase request identity mismatch." }
    require(
        unetResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256 &&
            vaeResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256
    ) { "SDXL phase conditioning artifact identity mismatch." }
    require(unetResult.workerPid > 0 && vaeResult.workerPid > 0 &&
        (encoderResult == null || encoderResult.workerPid > 0)
    ) {
        "SDXL phase worker PID proof is missing."
    }
    require(unetNative.optBoolean("ok") && vaeNative.optBoolean("ok")) {
        "SDXL phase native success proof is missing."
    }
    require(unetTransportHtpArch > 0 && vaeTransportHtpArch > 0) {
        "SDXL phase physical transport proof is missing."
    }
    require(unetRuntimeProfile.htpArchVersion == unetTransportHtpArch &&
        vaeRuntimeProfile.htpArchVersion == vaeTransportHtpArch
    ) { "SDXL winning exact runtime profiles differ from the physical transport proof." }
    require(unetResult.runtimeProfile == sdxlTransportProfile(unetTransportHtpArch) &&
        vaeResult.runtimeProfile == sdxlTransportProfile(vaeTransportHtpArch)
    ) { "SDXL phase protocol runtime profile differs from native transport evidence." }
    if (encoderNative != null && encoderMetadata != null && encoderResult != null) {
        require(encoderRuntimeProfile?.htpArchVersion == encoderMetadata.htpArchVersion) {
            "SDXL encoder winning exact runtime profile differs from producer transport proof."
        }
        val expectedEncoderContextSha256 = contract.paramsObject()
            .getString("vaeEncoderContextSha256")
            .lowercase()
        require(encoderResult.requestId == unetResult.requestId &&
            encoderResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256 &&
            encoderNative.optBoolean("ok") &&
            encoderNative.getInt("htpArchVersion") > 0 &&
            encoderMetadata.htpArchVersion == encoderNative.getInt("htpArchVersion") &&
            encoderResult.runtimeProfile == sdxlTransportProfile(encoderMetadata.htpArchVersion) &&
            encoderMetadata.runtimeProfile == encoderResult.runtimeProfile &&
            encoderNative.getString("encoderContextSha256").lowercase() ==
                expectedEncoderContextSha256 &&
            encoderMetadata.encoderContextSha256 == expectedEncoderContextSha256
        ) { "SDXL encoder identity or producer transport proof mismatch." }
        validateSdxlEncoderNativeEvidence(contract, encoderNative)
        val encoderProof = SdxlNativePhaseProof.fromNativeResult(
            encoderNative,
            SdxlImagePhase.ENCODER
        )
        require(encoderProof.nativeGenerationSequence == encoderResult.nativeGenerationSequence &&
            encoderProof.nativeStageMask == encoderResult.nativeStageMask &&
            encoderProof.nativeDetailStageMask == encoderResult.nativeDetailStageMask &&
            encoderMetadata.encoderNativeGenerationSequence == encoderProof.nativeGenerationSequence &&
            encoderMetadata.encoderNativeStageMask == encoderProof.nativeStageMask &&
            encoderMetadata.encoderNativeDetailStageMask == encoderProof.nativeDetailStageMask &&
            File(encoderResult.artifactPath).canonicalFile == File(encoderMetadata.latentPath).canonicalFile
        ) { "SDXL encoder protocol or committed artifact proof mismatch." }
    }
    require(unetNative.getInt("htpArchVersion") == unetTransportHtpArch) { "UNet transport proof mismatch." }
    require(metadata.htpArchVersion == unetTransportHtpArch &&
        metadata.runtimeProfile == unetResult.runtimeProfile
    ) {
        "Committed latent UNet producer transport proof mismatch."
    }
    require(vaeNative.getInt("htpArchVersion") == vaeTransportHtpArch) { "VAE transport proof mismatch." }
    validateSdxlUnetNativeEvidence(contract, unetNative)
    val unetEffective = contract.requireNativeEffective(unetNative, SdxlImagePhase.UNET)
    if (encoderMetadata != null) {
        require(
            unetNative.getString("encoderLatentSha256").lowercase() == encoderMetadata.sha256 &&
                unetNative.getJSONObject("nativeEffective")
                    .getString("encoderLatentSha256").lowercase() == encoderMetadata.sha256
        ) { "UNet did not consume the exact committed VAE encoder latent." }
    }
    val metadataEffective = contract.requireNativeEffective(
        JSONObject().put("nativeEffective", JSONObject(metadata.nativeEffectiveJson)),
        SdxlImagePhase.UNET
    )
    require(metadataEffective == unetEffective) {
        "Committed latent native execution evidence differs from the UNet result."
    }
    validateSdxlVaeNativeEvidence(contract, vaeNative)
    ultraFix?.let { request ->
        val encoder = requireNotNull(encoderNative)
        val encoderPlan = encoder.getString("ultraFixTilePlanSha256").lowercase()
        val unetPlan = unetNative.getString("ultraFixTilePlanSha256").lowercase()
        val vaePlan = vaeNative.getString("ultraFixTilePlanSha256").lowercase()
        val tileCount = encoder.getInt("ultraFixTileCount")
        require(encoderPlan.matches(Regex("^[a-f0-9]{64}$")) &&
            encoderPlan == unetPlan && encoderPlan == vaePlan &&
            tileCount > 0 && unetNative.getInt("ultraFixTileCount") == tileCount &&
            vaeNative.getInt("ultraFixTileCount") == tileCount
        ) { "Split-SDXL UltraFix phase tile-plan identities conflict." }
        val inversionGraphs = Math.multiplyExact(tileCount, request.inversionSteps)
        val refinementPositive = inversionGraphs
        val refinementNegative = if (contract.expected.useCfg) refinementPositive else 0
        val physicalUnet = Math.addExact(
            Math.addExact(inversionGraphs, refinementPositive),
            refinementNegative
        )
        require(encoder.getInt("ultraFixEncoderGraphExecutionCount") == tileCount &&
            encoder.getInt("ultraFixEncoderTileSuccessCount") == tileCount &&
            unetNative.getInt("ultraFixInversionGraphExecutionCount") == inversionGraphs &&
            unetNative.getInt("ultraFixInversionTileSuccessCount") == inversionGraphs &&
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount") ==
                refinementPositive &&
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount") ==
                refinementNegative &&
            unetNative.getInt("ultraFixRefinementTileSuccessCount") ==
                refinementPositive + refinementNegative &&
            unetNative.getInt("ultraFixPhysicalUnetGraphExecutionCount") == physicalUnet &&
            vaeNative.getInt("ultraFixDecoderGraphExecutionCount") == tileCount &&
            vaeNative.getInt("ultraFixDecoderTileSuccessCount") == tileCount &&
            vaeNative.getBoolean("ultraFixOutputAtomicCommit")
        ) { "Split-SDXL UltraFix physical phase counts are incomplete." }
        val qualityStepCount = unetNative.getInt("ultraFixQualityStepEvaluationCount")
        val noiseStepCount = unetNative.getInt("ultraFixNoiseInjectionStepCount")
        val structureStepCount = unetNative.getInt("ultraFixStructureGuidanceStepCount")
        val seedFingerprint = unetNative.getString("ultraFixNoiseInjectionSeedFingerprint").lowercase()
        val noiseChecksum = unetNative.getString("ultraFixNoiseInjectionChecksum").lowercase()
        val structureChecksum = unetNative.getString("ultraFixStructureGuidanceChecksum").lowercase()
        val trajectoryChecksum = unetNative.getString("ultraFixTrajectoryNoiseChecksum").lowercase()
        require(
            qualityStepCount == request.inversionSteps - 1 &&
                seedFingerprint.matches(Regex("^[a-f0-9]{64}$")) &&
                noiseChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                structureChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                trajectoryChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                if (request.inversionSteps == 1) {
                    noiseStepCount == 0 && structureStepCount == 0 &&
                        noiseChecksum == "0000000000000000" &&
                        structureChecksum == "0000000000000000" &&
                        trajectoryChecksum == "0000000000000000"
                } else {
                    noiseStepCount > 0 && structureStepCount > 0 &&
                        noiseChecksum != "0000000000000000" &&
                        structureChecksum != "0000000000000000" &&
                        trajectoryChecksum != "0000000000000000"
                }
        ) { "Split-SDXL UltraFix quality execution evidence is incomplete." }
    }
    if (taskMode == LocalImageTaskMode.INPAINT) {
        val prepared = requireNotNull(preparedInpaint)
        val params = contract.paramsObject()
        val unetEffectiveJson = unetNative.getJSONObject("nativeEffective")
        require(encoderNative?.getString("taskMode") == taskMode.wireName &&
            unetEffectiveJson.getString("taskMode") == taskMode.wireName &&
            vaeNative.getString("taskMode") == taskMode.wireName
        ) { "Split-SDXL inpaint task identity changed across isolated phases." }
        require(unetEffectiveJson.getString("inpaintTopology") ==
            QnnInpaintMaskTopology.LATENT_BLEND_4.wireName &&
            File(unetEffectiveJson.getString("maskImageTensorPath")).canonicalFile ==
                File(prepared.maskTensorPath).canonicalFile &&
            unetEffectiveJson.getString("maskImageTensorSha256").lowercase() ==
                prepared.maskTensorSha256 &&
            unetEffectiveJson.getLong("maskImageTensorBytes") == prepared.maskTensorBytes &&
            unetEffectiveJson.getInt("inpaintPreserveStepCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintLatentBlendCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintSourceNoiseUseCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getString("inpaintSourceNoiseSha256")
                .matches(Regex("^[a-f0-9]{64}$")) &&
            unetEffectiveJson.getString("inpaintPreservedLatentChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$"))
        ) { "Split-SDXL UNet inpaint preservation evidence is incomplete." }
        require(File(vaeNative.getString("inputImageTensorPath")).canonicalFile ==
            File(prepared.source.tensorPath).canonicalFile &&
            vaeNative.getString("inputImageTensorSha256").lowercase() ==
                prepared.source.tensorSha256 &&
            File(vaeNative.getString("maskImageFullTensorPath")).canonicalFile ==
                File(prepared.fullMaskTensorPath).canonicalFile &&
            vaeNative.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256 &&
            vaeNative.getLong("maskImageFullTensorBytes") == prepared.fullMaskTensorBytes &&
            vaeNative.getJSONArray("maskImageFullTensorShape").toString() ==
                JSONArray(prepared.fullMaskTensorShape).toString() &&
            vaeNative.getString("inpaintFinalMode") ==
                "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend" &&
            vaeNative.getBoolean("inpaintPixelBlendApplied") &&
            vaeNative.getInt("inpaintPixelBlendLevels") ==
                qnnInpaintLaplacianLevelCount(prepared.targetWidth, prepared.targetHeight) &&
            vaeNative.getString("inpaintPixelBlendChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$")) &&
            params.getString("maskImageTensorSha256").lowercase() == prepared.maskTensorSha256 &&
            params.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256
        ) { "Split-SDXL VAE inpaint pixel-blend evidence is incomplete." }
    }
    require(metadata.unetNativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "Committed latent sequence mismatch."
    }
    require(File(unetResult.artifactPath).canonicalFile == File(metadata.latentPath).canonicalFile) {
        "UNet artifact path does not match committed latent metadata."
    }
    require(outputFile.isFile && outputFile.length() > 0L) { "SDXL output is missing." }
    require(File(vaeResult.artifactPath).canonicalFile == outputFile.canonicalFile) {
        "VAE protocol artifact path mismatch."
    }

    val unetProof = SdxlNativePhaseProof.fromNativeResult(unetNative, SdxlImagePhase.UNET)
    val vaeProof = SdxlNativePhaseProof.fromNativeResult(vaeNative, SdxlImagePhase.VAE)
    val encoderProof = encoderNative?.let {
        SdxlNativePhaseProof.fromNativeResult(it, SdxlImagePhase.ENCODER)
    }
    val mergedNativeDetailStageMask =
        unetProof.nativeDetailStageMask or vaeProof.nativeDetailStageMask or
            (encoderProof?.nativeDetailStageMask ?: 0uL)
    val phaseSequences = buildList {
        encoderProof?.nativeGenerationSequence?.let(::add)
        add(unetProof.nativeGenerationSequence)
        add(vaeProof.nativeGenerationSequence)
    }
    require(phaseSequences.distinct().size == phaseSequences.size) {
        "SDXL disposable phase instances reused one native generation identity."
    }
    val processExitBoundaries = buildList {
        if (encoderResult != null) {
            add(
                "${SdxlImagePhase.ENCODER.wireName}[pid=${encoderResult.workerPid}," +
                    "profile=${encoderResult.runtimeProfile}]:process_exit_confirmed"
            )
        }
        add(
            "${SdxlImagePhase.UNET.wireName}[pid=${unetResult.workerPid}," +
                "profile=${unetResult.runtimeProfile}]:process_exit_confirmed"
        )
        add(
            "${SdxlImagePhase.VAE.wireName}[pid=${vaeResult.workerPid}," +
                "profile=${vaeResult.runtimeProfile}]:process_exit_confirmed"
        )
    }
    val processExitIndices = processExitBoundaries.map(stageTrace::indexOf)
    require(processExitIndices.all { it >= 0 } &&
        processExitIndices.zipWithNext().all { (previous, next) -> previous < next }
    ) { "SDXL disposable process exits are missing or out of phase order." }
    require(unetProof.nativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "UNet protocol sequence mismatch."
    }
    require(unetProof.nativeStageMask == unetResult.nativeStageMask &&
        unetProof.nativeDetailStageMask == unetResult.nativeDetailStageMask
    ) { "UNet protocol stage proof mismatch." }
    require(vaeProof.nativeGenerationSequence == vaeResult.nativeGenerationSequence) {
        "VAE protocol sequence mismatch."
    }
    require(vaeProof.nativeStageMask == vaeResult.nativeStageMask &&
        vaeProof.nativeDetailStageMask == vaeResult.nativeDetailStageMask
    ) { "VAE protocol stage proof mismatch." }
    val nativeOutput = File(vaeNative.getString("outputPath")).canonicalFile
    require(nativeOutput == outputFile.canonicalFile) { "VAE output path proof mismatch." }
    require(vaeNative.getLong("outputBytes") == outputFile.length()) { "VAE output byte proof mismatch." }
    val outputSha256 = vaeNative.getString("outputSha256").lowercase()
    require(outputSha256 == sdxlArtifactSha256(outputFile)) { "VAE output SHA-256 proof mismatch." }
    val mimeType = vaeNative.getString("mimeType")
    require(mimeType == "image/png") { "VAE output MIME proof mismatch." }

    val unetNativeEffectiveJson = unetNative.optJSONObject("nativeEffective")
        ?: error("UNet native result is missing nativeEffective evidence.")
    val actualConditioningArtifactSha256 = unetNativeEffectiveJson
        .getString("conditioningArtifactSha256")
        .lowercase()
    require(actualConditioningArtifactSha256 == metadata.conditioningArtifactSha256) {
        "Committed latent conditioning evidence differs from the UNet result."
    }
    val metadataNativeEffectiveJson = JSONObject(metadata.nativeEffectiveJson)
    require(
        metadataNativeEffectiveJson.getString("conditioningArtifactSha256").lowercase() ==
            actualConditioningArtifactSha256
    ) { "Committed latent nativeEffective conditioning evidence was changed." }

    val sdxlPhaseProof = JSONObject()
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put("unetRuntimeProfileSha256", unetRuntimeProfile.identitySha256())
        .put("vaeRuntimeProfileSha256", vaeRuntimeProfile.identitySha256())
        .put("unetContextLoadCount", unetNative.getInt("unetContextLoadCount"))
        .put("unetSamplingLoopCount", unetNative.getInt("unetSamplingLoopCount"))
        .put("unetSamplingStepCount", unetNative.getInt("unetSamplingStepCount"))
        .put("unetGraphExecutionCount", unetNative.getInt("unetGraphExecutionCount"))
        .put(
            "unetContextReusedAcrossSteps",
            unetNative.getBoolean("unetContextReusedAcrossSteps")
        )
        .put("unetGraphName", unetNative.getString("unetGraphName"))
        .put("vaeContextLoadCount", vaeNative.getInt("vaeContextLoadCount"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("vaeTileCount", vaeNative.getInt("vaeTileCount"))
        .put("vaeTiled", vaeNative.getBoolean("vaeTiled"))
        .put("vaeGraphName", vaeNative.getString("vaeGraphName"))
        .put("vaeSourceLatentShape", vaeNative.getJSONArray("vaeSourceLatentShape"))
        .put("vaeInputLatentShape", vaeNative.getJSONArray("vaeInputLatentShape"))
        .put("vaeOutputTileShape", vaeNative.getJSONArray("vaeOutputTileShape"))
        .put("vaeFinalOutputShape", vaeNative.getJSONArray("vaeFinalOutputShape"))
        .put("vaeDecodeSpatialScale", vaeNative.getInt("vaeDecodeSpatialScale"))
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("pixelRangeConversion", vaeNative.getString("pixelRangeConversion"))
        .put("pixelRangeValueCount", vaeNative.getLong("pixelRangeValueCount"))
        .put("pixelRangeClampedValueCount", vaeNative.getLong("pixelRangeClampedValueCount"))
        .put("pixelRangeObservedMin", vaeNative.getDouble("pixelRangeObservedMin"))
        .put("pixelRangeObservedMax", vaeNative.getDouble("pixelRangeObservedMax"))
        .put("outputSha256", outputSha256)
        .put(
            "unetNativeDetailStageMaskHex",
            unetProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put(
            "vaeNativeDetailStageMaskHex",
            vaeProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
    if (encoderNative != null && encoderMetadata != null && encoderProof != null) {
        sdxlPhaseProof
            .put("encoderContextLoadCount", encoderNative.getInt("encoderContextLoadCount"))
            .put("encoderExecutionCount", encoderNative.getInt("encoderExecutionCount"))
            .put("encoderGraphName", encoderNative.getString("encoderGraphName"))
            .put("encoderInputShape", encoderNative.getJSONArray("encoderInputShape"))
            .put("encoderMeanShape", encoderNative.getJSONArray("encoderMeanShape"))
            .put("encoderStdShape", encoderNative.getJSONArray("encoderStdShape"))
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderLatentSha256", encoderMetadata.sha256)
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
            .put("encoderRuntimeProfileSha256", requireNotNull(encoderRuntimeProfile).identitySha256())
            .put("inputImageSha256", encoderMetadata.inputImageSha256)
            .put("inputImageTensorSha256", encoderMetadata.inputImageTensorSha256)
            .put(
                "encoderNativeDetailStageMaskHex",
                encoderProof.nativeDetailStageMask.toFixedUInt64Hex()
            )
    }
    if (taskMode == LocalImageTaskMode.INPAINT) {
        val unetEffective = unetNative.getJSONObject("nativeEffective")
        sdxlPhaseProof
            .put("inpaintTopology", unetEffective.getString("inpaintTopology"))
            .put("maskImageTensorSha256", unetEffective.getString("maskImageTensorSha256"))
            .put("maskImageFullTensorSha256", vaeNative.getString("maskImageFullTensorSha256"))
            .put("inpaintPreserveStepCount", unetEffective.getInt("inpaintPreserveStepCount"))
            .put("inpaintLatentBlendCount", unetEffective.getInt("inpaintLatentBlendCount"))
            .put("inpaintSourceNoiseSha256", unetEffective.getString("inpaintSourceNoiseSha256"))
            .put("inpaintSourceNoiseUseCount", unetEffective.getInt("inpaintSourceNoiseUseCount"))
            .put("inpaintPreservedLatentChecksum", unetEffective.getString("inpaintPreservedLatentChecksum"))
            .put("inpaintFinalMode", vaeNative.getString("inpaintFinalMode"))
            .put("inpaintPixelBlendLevels", vaeNative.getInt("inpaintPixelBlendLevels"))
            .put("inpaintPixelBlendChecksum", vaeNative.getString("inpaintPixelBlendChecksum"))
            .put("inpaintPixelBlendApplied", vaeNative.getBoolean("inpaintPixelBlendApplied"))
    }
    if (ultraFix != null) {
        sdxlPhaseProof
            .put("ultraFixTilePlanSha256", encoderNative!!.getString("ultraFixTilePlanSha256"))
            .put("ultraFixTileCount", encoderNative.getInt("ultraFixTileCount"))
            .put(
                "ultraFixEncoderGraphExecutionCount",
                encoderNative.getInt("ultraFixEncoderGraphExecutionCount")
            )
            .put(
                "ultraFixInversionGraphExecutionCount",
                unetNative.getInt("ultraFixInversionGraphExecutionCount")
            )
            .put(
                "ultraFixRefinementPositiveGraphExecutionCount",
                unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
            )
            .put(
                "ultraFixRefinementNegativeGraphExecutionCount",
                unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
            )
            .put(
                "ultraFixDecoderGraphExecutionCount",
                vaeNative.getInt("ultraFixDecoderGraphExecutionCount")
            )
            .put("ultraFixOutputSha256", vaeNative.getString("ultraFixOutputSha256"))
    }
    val ultraFixEvidence = ultraFix?.let { request ->
        val encoder = requireNotNull(encoderNative)
        val tileCount = encoder.getInt("ultraFixTileCount")
        val tilePlanSha256 = encoder.getString("ultraFixTilePlanSha256").lowercase()
        val inversionGraphs = unetNative.getInt("ultraFixInversionGraphExecutionCount")
        val refinementPositive =
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        val refinementNegative =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalUnet = unetNative.getInt("ultraFixPhysicalUnetGraphExecutionCount")
        val params = contract.paramsObject()
        val sourceWidth = params.getInt("inputImageOrientedWidth")
        val sourceHeight = params.getInt("inputImageOrientedHeight")
        require(sourceWidth > 0 && sourceHeight > 0 &&
            encoder.getInt("ultraFixSourceWidth") == sourceWidth &&
            encoder.getInt("ultraFixSourceHeight") == sourceHeight &&
            encoder.getInt("ultraFixSourceResizedWidth") >= request.targetWidth &&
            encoder.getInt("ultraFixSourceResizedHeight") >= request.targetHeight &&
            encoder.getInt("ultraFixSourceCropLeft") ==
                (encoder.getInt("ultraFixSourceResizedWidth") - request.targetWidth) / 2 &&
            encoder.getInt("ultraFixSourceCropTop") ==
                (encoder.getInt("ultraFixSourceResizedHeight") - request.targetHeight) / 2
        ) { "Split-SDXL UltraFix center-cover geometry evidence is invalid." }
        fun stage(
            invocations: Int,
            tileInvocations: Int,
            steps: Int
        ): JSONObject = JSONObject()
            .put("invocationCount", invocations)
            .put("successCount", invocations)
            .put("tileInvocationCount", tileInvocations)
            .put("tileSuccessCount", tileInvocations)
            .put("stepCount", steps)
        JSONObject()
            .put("version", 2)
            .put("generationCompleted", true)
            .put("cancelled", false)
            .put("previewPublished", false)
            .put("sourceWidth", sourceWidth)
            .put("sourceHeight", sourceHeight)
            .put("targetWidth", request.targetWidth)
            .put("targetHeight", request.targetHeight)
            .put("sourceFit", "cover_center")
            .put("sourceResizedWidth", encoder.getInt("ultraFixSourceResizedWidth"))
            .put("sourceResizedHeight", encoder.getInt("ultraFixSourceResizedHeight"))
            .put("sourceCropLeft", encoder.getInt("ultraFixSourceCropLeft"))
            .put("sourceCropTop", encoder.getInt("ultraFixSourceCropTop"))
            .put("tileSize", request.tileSize)
            .put("overlap", request.overlap)
            .put("inversionSteps", request.inversionSteps)
            .put("refinementSteps", request.refinementSteps)
            .put("denoiseStepCount", request.inversionSteps)
            .put("tileCount", tileCount)
            .put("tilePlanSha256", tilePlanSha256)
            .put("sampleMethod", unetNative.getString("ultraFixSampleMethod"))
            .put("nativeScheduler", unetNative.getString("ultraFixNativeScheduler"))
            .put("vaeEncode", stage(1, tileCount, 1))
            .put(
                "ddimInversion",
                stage(request.inversionSteps, inversionGraphs, request.inversionSteps)
            )
            .put(
                "tiledUnetRefinement",
                stage(
                    request.inversionSteps,
                    refinementPositive + refinementNegative,
                    request.inversionSteps
                )
            )
            .put("tiledVaeDecode", stage(1, tileCount, 1))
            .put("encoderGraphExecutionCount", encoder.getInt("ultraFixEncoderGraphExecutionCount"))
            .put("inversionPositiveGraphExecutionCount", inversionGraphs)
            .put("refinementPositiveGraphExecutionCount", refinementPositive)
            .put("refinementNegativeGraphExecutionCount", refinementNegative)
            .put("decoderGraphExecutionCount", vaeNative.getInt("ultraFixDecoderGraphExecutionCount"))
            .put("physicalDiffusionModelComputeCount", physicalUnet)
            .put(
                "qualityStepEvaluationCount",
                unetNative.getInt("ultraFixQualityStepEvaluationCount")
            )
            .put(
                "noiseInjectionStepCount",
                unetNative.getInt("ultraFixNoiseInjectionStepCount")
            )
            .put(
                "noiseInjectionSeedFingerprint",
                unetNative.getString("ultraFixNoiseInjectionSeedFingerprint")
            )
            .put(
                "noiseInjectionChecksum",
                unetNative.getString("ultraFixNoiseInjectionChecksum")
            )
            .put(
                "structureGuidanceStepCount",
                unetNative.getInt("ultraFixStructureGuidanceStepCount")
            )
            .put(
                "structureGuidanceChecksum",
                unetNative.getString("ultraFixStructureGuidanceChecksum")
            )
            .put(
                "trajectoryNoiseChecksum",
                unetNative.getString("ultraFixTrajectoryNoiseChecksum")
            )
            .put("outputSha256", outputSha256)
            .put("outputBytes", outputFile.length())
            .put("outputAtomicCommit", true)
    }
    val runtimeSessionMode = if (ultraFix != null) {
        SDXL_ISOLATED_ULTRAFIX_MODE
    } else if (hasEncoder) {
        SDXL_ISOLATED_ENCODER_UNET_VAE_MODE
    } else {
        SDXL_ISOLATED_UNET_VAE_MODE
    }
    val nativeEffectiveJson = JSONObject(unetNativeEffectiveJson.toString())
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("runtimeSessionMode", runtimeSessionMode)
        // transportHtpArch remains the compatibility alias for the primary UNet
        // sampling phase. Explicit per-phase fields carry the truthful topology.
        .put("transportHtpArch", unetTransportHtpArch)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("sdxlPhaseProof", sdxlPhaseProof)
    if (encoderMetadata != null) {
        nativeEffectiveJson
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
    }
    if (taskMode == LocalImageTaskMode.INPAINT) {
        listOf(
            "inputImageTensorPath",
            "inputImageTensorSha256",
            "inputImageTensorBytes",
            "maskImageFullTensorPath",
            "maskImageFullTensorSha256",
            "maskImageFullTensorBytes",
            "maskImageFullTensorShape",
            "maskImageFullTensorDtype",
            "maskImageFullTensorLayout",
            "maskImageFullTensorRange",
            "maskImageFullTensorPreprocess",
            "inpaintFinalMode",
            "inpaintPixelBlendLevels",
            "inpaintPixelBlendChecksum",
            "inpaintPixelBlendApplied",
        ).forEach { field -> nativeEffectiveJson.put(field, vaeNative.get(field)) }
        nativeEffectiveJson
            .put("inpaintMaskConsumed", true)
            .put("inpaintUnmaskedPreservationApplied", true)
            .put("encoderContextSha256", requireNotNull(encoderMetadata).encoderContextSha256)
            .put("encoderGraphName", requireNotNull(encoderNative).getString("encoderGraphName"))
            .put("encoderExecutionCount", 1)
            .put("encoderContextLoadCount", 1)
            .put("inpaintSourceEncoderExecutionCount", 1)
    }
    if (ultraFixEvidence != null && ultraFix != null) {
        val positiveCompute = Math.addExact(
            unetNative.getInt("ultraFixInversionGraphExecutionCount"),
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        )
        val negativeCompute =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalCompute = Math.addExact(positiveCompute, negativeCompute)
        nativeEffectiveJson
            .put("ultraFix", JSONObject(ultraFixEvidence.toString()))
            .put("strengthMechanism", "ddim_inversion")
            .put("outputSha256", outputSha256)
            .put("outputSizeBytes", outputFile.length())
            .put("outputAtomicCommit", true)
            .put("positiveDiffusionModelComputeCount", positiveCompute)
            .put("negativeDiffusionModelComputeCount", negativeCompute)
            .put("auxiliaryDiffusionModelComputeCount", 0)
            .put("samplingPassCount", 1)
            .put("totalUnetExecutionCount", physicalCompute)
    }
    val finalResult = JSONObject(nativeEffectiveJson.toString())
    finalResult.put("nativeEffective", nativeEffectiveJson)
        .put("ok", true)
        .put("backend", "qnn_htp")
        .put("npuActive", true)
        .put("qnnGraphExecution", true)
        .put("nativeExecution", true)
        .put(
            "executionStage",
            if (ultraFix != null) {
                "sdxl_three_phase_ultrafix_passed"
            } else {
                when (taskMode) {
                    LocalImageTaskMode.INPAINT -> "sdxl_three_phase_inpaint_passed"
                    LocalImageTaskMode.IMG2IMG -> "sdxl_three_phase_img2img_passed"
                    else -> "sdxl_two_phase_passed"
                }
            }
        )
        .put("runtimeSessionMode", runtimeSessionMode)
        .put("transportHtpArch", unetTransportHtpArch)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("unetWorkerPid", unetResult.workerPid)
        .put("unetRuntimeProfile", unetResult.runtimeProfile)
        .put("unetGraph", unetNative.getString("unetGraphName"))
        .put("unetProcessDeathConfirmed", true)
        .put("unetNativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put("unetNativeStageMask", unetProof.nativeStageMask)
        .put(
            "unetNativeDetailStageMaskHex",
            unetProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("vaeWorkerPid", vaeResult.workerPid)
        .put("vaeRuntimeProfile", vaeResult.runtimeProfile)
        .put("vaeGraph", vaeNative.getString("vaeGraphName"))
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put("vaeProcessDeathConfirmed", true)
        .put("vaeNativeGenerationSequence", vaeProof.nativeGenerationSequence)
        .put("vaeNativeStageMask", vaeProof.nativeStageMask)
        .put(
            "vaeNativeDetailStageMaskHex",
            vaeProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("nativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put(
            "nativeStageMask",
            unetProof.nativeStageMask or vaeProof.nativeStageMask or
                (encoderProof?.nativeStageMask ?: 0L)
        )
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("finalVaeExecutionCount", 1)
        .put("finalVaeGraphExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("previewRequested", disabledPreviewAudit.requested)
        .put("previewMode", disabledPreviewAudit.mode)
        .put("previewInterval", disabledPreviewAudit.interval)
        .put("previewVaeExecutionAttemptCount", 0)
        .put("previewVaeExecutionCount", 0)
        .put("previewVaeExecutionMsTotal", 0)
        .put("previewPublicationCount", disabledPreviewAudit.publicationCount)
        .put("previewLastStep", disabledPreviewAudit.lastStep)
        .put("previewLastRevision", disabledPreviewAudit.lastRevision)
        .put("previewFailureCode", disabledPreviewAudit.failureCode)
        .put("projectionPreviewAttemptCount", disabledPreviewAudit.attemptCount)
        .put("projectionPreviewPublicationCount", disabledPreviewAudit.publicationCount)
        .put("projectionPreviewProjectionMsTotal", disabledPreviewAudit.projectionMsTotal)
        .put("projectionPreviewLastStep", disabledPreviewAudit.lastStep)
        .put("projectionPreviewLastRevision", disabledPreviewAudit.lastRevision)
        .put("projectionPreviewFailureCode", disabledPreviewAudit.failureCode)
        .put("previewDegraded", disabledPreviewAudit.degraded)
        .put("outputPath", outputFile.canonicalPath)
        .put("mimeType", mimeType)
        .put("outputBytes", outputFile.length())
        .put("outputSha256", outputSha256)
        .put("latentSha256", metadata.sha256)
        .put("stageTrace", JSONArray(stageTrace))

    if (ultraFixEvidence != null && ultraFix != null) {
        val positiveCompute = Math.addExact(
            unetNative.getInt("ultraFixInversionGraphExecutionCount"),
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        )
        val negativeCompute =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalCompute = Math.addExact(positiveCompute, negativeCompute)
        finalResult
            .put("ultraFix", JSONObject(ultraFixEvidence.toString()))
            .put("strengthMechanism", "ddim_inversion")
            .put("sampleMethod", ultraFixEvidence.getString("sampleMethod"))
            .put("nativeScheduler", ultraFixEvidence.getString("nativeScheduler"))
            .put("actualDiffusionModelComputeCount", physicalCompute)
            .put("actualPositiveDiffusionModelComputeCount", positiveCompute)
            .put("actualNegativeDiffusionModelComputeCount", negativeCompute)
            .put("actualAuxiliaryDiffusionModelComputeCount", 0)
            .put("actualSamplingStepCount", ultraFix.inversionSteps)
            .put("actualSamplingPassCount", 1)
            .put("totalUnetExecutionCount", physicalCompute)
            .put("outputSizeBytes", outputFile.length())
            .put("outputAtomicCommit", true)
    }

    if (encoderResult != null && encoderNative != null && encoderMetadata != null && encoderProof != null) {
        finalResult
            .put("encoderWorkerPid", encoderResult.workerPid)
            .put("encoderRuntimeProfile", encoderResult.runtimeProfile)
            .put("encoderGraph", encoderNative.getString("encoderGraphName"))
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
            .put("encoderProcessDeathConfirmed", true)
            .put("encoderNativeGenerationSequence", encoderProof.nativeGenerationSequence)
            .put("encoderNativeStageMask", encoderProof.nativeStageMask)
            .put(
                "encoderNativeDetailStageMaskHex",
                encoderProof.nativeDetailStageMask.toFixedUInt64Hex()
            )
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderLatentSha256", encoderMetadata.sha256)
        listOf(
            "encoderContextLoadCount",
            "encoderExecutionCount",
            "encoderGraphName",
            "encoderContextSha256",
            "encoderContextLoadMs",
            "encoderExecuteMs",
            "encoderInputName",
            "encoderMeanOutputName",
            "encoderStdOutputName",
            "encoderInputDtype",
            "encoderMeanDtype",
            "encoderStdDtype",
            "encoderInputShape",
            "encoderMeanShape",
            "encoderStdShape",
            "posteriorSampling",
            "posteriorSampleCount",
            "encoderLatentScalingFactor",
            "inputImagePath",
            "inputImageSha256",
            "inputImageSizeBytes",
            "inputImageSourceReadByNative",
            "inputImageSourceValidation",
            "inputImageTensorPath",
            "inputImageTensorSha256",
            "inputImageTensorBytes",
            "inputImageTensorShape",
            "inputImageTensorDtype",
            "inputImageTensorLayout",
            "inputImageTensorRange",
            "inputImagePreprocess"
        ).forEach { field ->
            if (encoderNative.has(field) && !encoderNative.isNull(field)) {
                finalResult.put(field, encoderNative.get(field))
            }
        }
    }

    listOf(
        "timesteps",
        "sigmas",
        "initNoiseSigma",
        "scaleModelInput",
        "unetContextLoadCount",
        "unetSamplingLoopCount",
        "unetSamplingStepCount",
        "unetGraphExecutionCount",
        "unetContextReusedAcrossSteps",
        "unetContextLoadMs",
        "unetExecuteMsTotal"
    ).forEach { field ->
        if (unetNative.has(field) && !unetNative.isNull(field)) {
            finalResult.put(field, unetNative.get(field))
        }
    }
    listOf(
        "vaeContextLoadMs",
        "vaeExecuteMs",
        "vaeTileCount",
        "vaeTiled",
        "vaeContextLoadCount",
        "vaeSourceLatentShape",
        "vaeInputLatentShape",
        "vaeOutputTileShape",
        "vaeFinalOutputShape",
        "vaeDecodeSpatialScale",
        "pixelChecksum",
        "pixelRangeConversion",
        "pixelRangeValueCount",
        "pixelRangeClampedValueCount",
        "pixelRangeObservedMin",
        "pixelRangeObservedMax"
    ).forEach { field ->
        if (vaeNative.has(field) && !vaeNative.isNull(field)) {
            finalResult.put(field, vaeNative.get(field))
        }
    }
    (vaeNative.optJSONObject("runtimeEvidence")
        ?: vaeNative.optJSONObject("runtime"))?.let { finalResult.put("runtimeEvidence", it) }
    validateSdxlFlatNativeEffective(finalResult)
    return finalResult
}

internal data class SdxlPhaseRuntimeDirectories(
    val encoderCandidates: List<SdxlPhaseRuntimeCandidate>,
    val unetCandidates: List<SdxlPhaseRuntimeCandidate>,
    val vaeCandidates: List<SdxlPhaseRuntimeCandidate>
)

internal data class SdxlPhaseRuntimeCandidate(
    val runtimeProfile: SdxlQnnRuntimeProfile
) {
    val phaseHtpArch: Int get() = runtimeProfile.htpArchVersion
}

internal data class SdxlPhaseCandidateCompletion(
    val candidate: SdxlPhaseRuntimeCandidate,
    val completion: SdxlPhaseCompletion
)

internal data class SdxlRuntimeCandidateExecution<T>(
    val candidate: SdxlPhaseRuntimeCandidate,
    val value: T
)

internal fun shouldRetrySdxlRuntimeCandidate(
    phase: SdxlImagePhase,
    errorCode: String,
    cancelled: Boolean,
    hasNextCandidate: Boolean
): Boolean = !cancelled && hasNextCandidate &&
    isSdxlRuntimeCandidateFailureCode(phase, errorCode)

internal suspend fun <T> executeSdxlRuntimeCandidates(
    phase: SdxlImagePhase,
    candidates: List<SdxlPhaseRuntimeCandidate>,
    isCancelled: () -> Boolean,
    execute: suspend (SdxlPhaseRuntimeCandidate) -> T
): SdxlRuntimeCandidateExecution<T> {
    require(candidates.isNotEmpty()) { "SDXL ${phase.wireName} has no runtime candidate." }
    for ((index, candidate) in candidates.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw CancellationException("SDXL generation was cancelled.")
        try {
            return SdxlRuntimeCandidateExecution(candidate, execute(candidate))
        } catch (error: LocalImageWorkerRemoteException) {
            currentCoroutineContext().ensureActive()
            if (!shouldRetrySdxlRuntimeCandidate(
                    phase = phase,
                    errorCode = error.code,
                    cancelled = isCancelled(),
                    hasNextCandidate = index < candidates.lastIndex
                )
            ) {
                throw error
            }
        }
    }
    error("SDXL ${phase.wireName} exhausted its runtime candidates without a result.")
}

@Suppress("UNUSED_PARAMETER")
internal fun orderedSdxlRuntimeDirs(primary: String, fallback: List<String>): String =
    JSONArray(listOf(File(primary).canonicalPath)).toString()

/**
 * Selects one complete APK/app-private QNN runtime for an isolated SDXL phase.
 * The directory may contain several physical-device transports; native context
 * metadata selects the compatible one and a real graph execute decides support.
 */
internal fun isolatedSdxlPackagedRuntimeDirs(runtimeDirsJson: String): String {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    val coherent = directories.firstOrNull(File::hasCoherentSdxlQnnRuntime)
        ?: error("The APK does not contain a complete QNN runtime for SDXL graph execution.")
    return orderedSdxlRuntimeDirs(coherent.path, emptyList())
}

internal fun sdxlRuntimeCandidatesForPackagedDirs(
    runtimeDirsJson: String,
    preferredHtpArch: Int? = null
): List<SdxlPhaseRuntimeCandidate> {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    require(
        directories.any { directory ->
            File(directory, "libQnnSystem.so").isFile && File(directory, "libQnnHtp.so").isFile
        }
    ) { "The packaged QNN runtime has no host System/HTP pair." }
    val candidates = qnnImagePackagedRuntimeCandidates(
        searchDirectories = directories,
        preferredHtpArchVersion = preferredHtpArch
    )
    require(candidates.isNotEmpty()) {
        "The packaged QNN runtime has no coherent executable HTP transport candidate."
    }
    return candidates.map { candidate ->
        SdxlPhaseRuntimeCandidate(
            runtimeProfile = candidate.runtimeProfile
        )
    }
}

private fun File.hasCoherentSdxlQnnRuntime(): Boolean {
    if (!File(this, "libQnnSystem.so").isFile || !File(this, "libQnnHtp.so").isFile) return false
    return listFiles().orEmpty().any { skel ->
        val arch = SDXL_HTP_SKEL.matchEntire(skel.name)?.groupValues?.getOrNull(1) ?: return@any false
        File(this, "libQnnHtpV${arch}Stub.so").isFile
    }
}

private val SDXL_HTP_SKEL = Regex("^libQnnHtpV(\\d+)Skel\\.so$")
internal const val SDXL_REQUEST_LEASE_TOKEN_FIELD = "sdxlRequestLeaseToken"
internal const val SDXL_REQUEST_LEASE_LOCK_PATH_FIELD = "sdxlRequestLeaseLockPath"
private const val SDXL_REQUEST_LOCK_TIMEOUT_MS = 5_000L
private const val SDXL_REQUEST_FINISH_LOCK_TIMEOUT_MS = 1_000L
private const val SDXL_REQUEST_LOCK_POLL_MS = 25L
private const val SDXL_CLEANUP_RETRY_INITIAL_DELAY_MS = 25L
private const val SDXL_CLEANUP_RETRY_MAX_DELAY_MS = 1_000L
private const val SDXL_CLEANUP_RETRY_MAX_DELAY_REPORT_INTERVAL = 60L
private const val SDXL_CLEANUP_LOG_TAG = "MCA-SDXL-Cleanup"

internal data class SdxlPhaseCompletion(
    val result: SdxlImagePhaseResult,
    val processDeathConfirmed: Boolean
)

internal object SdxlTwoPhaseJournal {
    fun merge(
        previous: List<String>,
        envelope: SdxlImagePhaseProgress
    ): List<String> {
        var merged = previous
        val prefix = "${envelope.phase.wireName}[pid=${envelope.workerPid},profile=${envelope.runtimeProfile}]"
        if (merged.none { it == "$prefix:worker_started" }) {
            merged = merged + "$prefix:worker_started"
        }
        envelope.progress.stageTrace.forEach { stage ->
            val tagged = "$prefix:$stage"
            if (tagged !in merged) merged = merged + tagged
        }
        return merged
    }

    fun appendBoundary(
        previous: List<String>,
        phase: SdxlImagePhase,
        pid: Int,
        profile: String,
        boundary: String
    ): List<String> {
        val tagged = "${phase.wireName}[pid=$pid,profile=$profile]:$boundary"
        return if (tagged in previous) previous else previous + tagged
    }
}

private class SdxlPhaseClient(
    private val context: Context,
    private val phase: SdxlImagePhase,
    private val onProgress: (SdxlImagePhaseProgress) -> Unit
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val serviceReady = CompletableDeferred<ISdxlImagePhaseWorker>()
    private val result = CompletableDeferred<SdxlImagePhaseResult>()
    private sealed class TerminalOutcome {
        data class Success(val result: SdxlImagePhaseResult) : TerminalOutcome()
        data class Failure(val error: Throwable) : TerminalOutcome()
        object TimedOut : TerminalOutcome()
    }

    // Binder callbacks race the coroutine timeout on different threads. The first accepted
    // outcome is authoritative even when the coroutine has not resumed from result.await() yet.
    private val terminalOutcome = AtomicReference<TerminalOutcome?>(null)
    // Only the linked Binder death recipient may satisfy this proof. Android
    // connection callbacks can report a disconnected/dead/null binding without
    // proving that the exact disposable worker process instance has exited.
    private val binderDeath = CompletableDeferred<Unit>()
    private var service: ISdxlImagePhaseWorker? = null
    private var binder: IBinder? = null

    @Volatile
    private var workerPid: Int = -1
    private var bound = false
    private var bindingRequested = false
    private var closed = false

    @Volatile
    private var dispatchAttempted = false

    @Volatile
    private var requestId: String = ""

    private val deathRecipient = IBinder.DeathRecipient {
        binderDeath.complete(Unit)
        acceptFailure(
            LocalImageWorkerDisconnectedException(
                "SDXL ${phase.wireName} phase process died before result publication."
            )
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, connectedBinder: IBinder) {
            val connectedService = ISdxlImagePhaseWorker.Stub.asInterface(connectedBinder)
            val linkError = runCatching { connectedBinder.linkToDeath(deathRecipient, 0) }
                .exceptionOrNull()
            val accepted = synchronized(lifecycleLock) {
                if (closed || (!bound && !bindingRequested) || linkError != null) {
                    false
                } else {
                    binder = connectedBinder
                    service = connectedService
                    true
                }
            }
            if (!accepted) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                if (linkError != null) {
                    serviceReady.completeExceptionally(linkError)
                    acceptFailure(linkError)
                }
                runCatching { context.unbindService(this) }
                return
            }
            if (!serviceReady.complete(connectedService)) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                releaseBindingForExit()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase service disconnected.")
        }

        override fun onBindingDied(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase binding died.")
        }

        override fun onNullBinding(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase returned a null binding.")
        }
    }

    private val callback = object : ISdxlImagePhaseWorkerCallback.Stub() {
        override fun onProgress(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseProgress(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase progress identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            onProgress(envelope)
        }

        override fun onComplete(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseResult(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase result identity mismatch."))
                return
            }
            if (envelope.workerPid <= 0) {
                acceptFailure(IllegalStateException("SDXL phase did not report a worker PID."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            acceptSuccess(envelope)
        }

        override fun onError(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseError(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase error identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            acceptFailure(LocalImageWorkerRemoteException(envelope.code, envelope.message))
        }
    }

    suspend fun execute(request: SdxlImagePhaseRequest, timeoutMs: Long): SdxlPhaseCompletion {
        requestId = request.requestId
        val serviceClass = when (phase) {
            SdxlImagePhase.ENCODER -> SdxlEncoderWorkerService::class.java
            SdxlImagePhase.UNET -> SdxlUnetWorkerService::class.java
            SdxlImagePhase.VAE -> SdxlVaeWorkerService::class.java
        }
        synchronized(lifecycleLock) {
            check(!closed) { "SDXL ${phase.wireName} phase client is closed." }
            check(!bindingRequested && !bound) { "SDXL ${phase.wireName} phase bind was already requested." }
            bindingRequested = true
        }
        val didBind = runCatching {
            context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            synchronized(lifecycleLock) { bindingRequested = false }
            throw error
        }
        val closedDuringBind = synchronized(lifecycleLock) {
            bindingRequested = false
            if (!closed) bound = didBind
            closed
        }
        if (closedDuringBind && didBind) {
            runCatching { context.unbindService(connection) }
        }
        check(!closedDuringBind && didBind) { "Unable to bind SDXL ${phase.wireName} phase worker." }
        val terminal = try {
            withTimeout(timeoutMs) {
                val remote = serviceReady.await()
                // A Binder exception or false return can occur after the remote process has begun
                // handling the transaction, so the exact-process exit barrier starts before IPC.
                dispatchAttempted = true
                check(remote.execute(SdxlImagePhaseProtocol.request(request), callback)) {
                    "SDXL ${phase.wireName} phase rejected the request."
                }
                result.await()
            }
            acceptedTerminal()
        } catch (timeout: TimeoutCancellationException) {
            acceptTimeoutOrObserveWinner()
        } catch (cancelled: CancellationException) {
            // Parent cancellation still propagates, but must not turn an already accepted Binder
            // success into a worker cancellation while this client establishes its exit proof.
            (terminalOutcome.get() as? TerminalOutcome.Success)?.let { success ->
                completeSuccessfulResult(success.result)
                throw cancelled
            }
            acceptFailureOrObserveWinner(cancelled)
        } catch (error: Throwable) {
            acceptFailureOrObserveWinner(error)
        }
        return when (terminal) {
            is TerminalOutcome.Success -> completeSuccessfulResult(terminal.result)
            is TerminalOutcome.Failure -> throwFailureAfterExit(terminal.error)
            TerminalOutcome.TimedOut -> throwTimeoutAfterExit(timeoutMs)
        }
    }

    private fun acceptSuccess(envelope: SdxlImagePhaseResult): Boolean =
        acceptTerminal(TerminalOutcome.Success(envelope))

    private fun acceptFailure(error: Throwable): Boolean =
        acceptTerminal(TerminalOutcome.Failure(error))

    private fun acceptTerminal(outcome: TerminalOutcome): Boolean {
        if (!terminalOutcome.compareAndSet(null, outcome)) return false
        when (outcome) {
            is TerminalOutcome.Success -> result.complete(outcome.result)
            is TerminalOutcome.Failure -> result.completeExceptionally(outcome.error)
            TerminalOutcome.TimedOut -> Unit
        }
        return true
    }

    private fun acceptedTerminal(): TerminalOutcome =
        checkNotNull(terminalOutcome.get()) { "SDXL ${phase.wireName} phase completed without a terminal outcome." }

    private fun acceptTimeoutOrObserveWinner(): TerminalOutcome {
        terminalOutcome.compareAndSet(null, TerminalOutcome.TimedOut)
        return acceptedTerminal()
    }

    private fun acceptFailureOrObserveWinner(error: Throwable): TerminalOutcome {
        terminalOutcome.compareAndSet(null, TerminalOutcome.Failure(error))
        return acceptedTerminal()
    }

    private suspend fun completeSuccessfulResult(
        completed: SdxlImagePhaseResult
    ): SdxlPhaseCompletion {
        // The child deliberately exits instead of unloading QNN. Release the binding first so
        // Android does not restart the disposable service. Binder death identifies the exact
        // process instance even if Android immediately reuses its numeric PID.
        releaseBindingForExit()
        if (!awaitExactBinderDeath()) {
            cancelAndTerminate()
            throw workerExitUnconfirmed(
                IllegalStateException("SDXL ${phase.wireName} phase completed without exact Binder death proof.")
            )
        }
        return SdxlPhaseCompletion(completed, true)
    }

    private suspend fun throwFailureAfterExit(error: Throwable): Nothing {
        if (!cancelAndAwaitExit()) throw workerExitUnconfirmed(error)
        throw error
    }

    private suspend fun throwTimeoutAfterExit(timeoutMs: Long): Nothing {
        val timeout = LocalImageWorkerRemoteException(
            code = "qnn_sdxl_${phase.wireName}_worker_timeout",
            message = "SDXL ${phase.wireName} phase exceeded ${timeoutMs / 1000L}s and its isolated worker exit was confirmed."
        )
        if (!cancelAndAwaitExit()) throw workerExitUnconfirmed(timeout)
        throw timeout
    }

    fun cancelAndTerminate() {
        val currentService = synchronized(lifecycleLock) { service }
        runCatching { currentService?.cancel(requestId) }
    }

    private suspend fun cancelAndAwaitExit(): Boolean =
        withContext(NonCancellable) {
            cancelAndTerminate()
            releaseBindingForExit()
            if (!requiresExactExitProof()) true else awaitExactBinderDeath()
        }

    private suspend fun awaitExactBinderDeath(): Boolean =
        withContext(NonCancellable) {
            runCatching {
                withTimeout(SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS) {
                    binderDeath.await()
                }
                true
            }.getOrDefault(false)
        }

    private fun requiresExactExitProof(): Boolean =
        dispatchAttempted || synchronized(lifecycleLock) { binder != null }

    private fun workerExitUnconfirmed(cause: Throwable): LocalImageWorkerRemoteException =
        LocalImageWorkerRemoteException(
            code = "qnn_sdxl_${phase.wireName}_worker_exit_unconfirmed",
            message = cause.message.orEmpty().ifBlank {
                "SDXL ${phase.wireName} phase did not confirm isolated worker exit."
            } + " The exact Binder death was not confirmed, so runtime fallback is unsafe."
        )

    private fun releaseBindingForExit() {
        val shouldUnbind = synchronized(lifecycleLock) {
            if (!bound) false else {
                bound = false
                true
            }
        }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }

    private fun failConnection(message: String) {
        val failure = LocalImageWorkerDisconnectedException(message)
        if (isClosed()) return
        serviceReady.completeExceptionally(failure)
        acceptFailure(failure)
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) { closed }

    private fun closeLifecycle(): Pair<IBinder?, Boolean> = synchronized(lifecycleLock) {
        if (closed) return@synchronized null to false
        closed = true
        val currentBinder = binder
        binder = null
        service = null
        val shouldUnbind = bound
        bound = false
        currentBinder to shouldUnbind
    }

    override fun close() {
        val (currentBinder, shouldUnbind) = closeLifecycle()
        currentBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }
}

private const val SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS = 5L * 1_000L

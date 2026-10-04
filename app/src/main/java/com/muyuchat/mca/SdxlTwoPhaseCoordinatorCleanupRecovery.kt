package com.muyuchat.mca

import android.util.Log
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONObject

internal const val SDXL_REQUEST_LOCK_TIMEOUT_MS = 5_000L
private const val SDXL_REQUEST_LOCK_POLL_MS = 25L
private const val SDXL_CLEANUP_RETRY_INITIAL_DELAY_MS = 25L
private const val SDXL_CLEANUP_RETRY_MAX_DELAY_MS = 1_000L
private const val SDXL_CLEANUP_RETRY_MAX_DELAY_REPORT_INTERVAL = 60L
private const val SDXL_CLEANUP_LOG_TAG = "MCA-SDXL-Cleanup"

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

internal fun pauseBeforeSdxlCleanupBarrierRetry(
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

internal fun incrementSdxlCleanupRetryAttempt(attempt: Long): Long =
    if (attempt == Long.MAX_VALUE) attempt else attempt + 1L

internal fun splitSdxlRequestDigest(requestId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(requestId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun splitSdxlRequestLockFileForDigest(coordinationRoot: File, digest: String): File {
    require(digest.matches(Regex("^[0-9a-f]{64}$"))) { "Split-SDXL request lock digest is invalid." }
    return File(coordinationRoot, "split-request-$digest.lock")
}

internal fun splitSdxlRequestLockFile(coordinationRoot: File, requestId: String): File =
    splitSdxlRequestLockFileForDigest(coordinationRoot, splitSdxlRequestDigest(requestId))

internal fun splitSdxlAdmissionLockFile(coordinationRoot: File): File =
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

internal fun requireSplitSdxlPreviewDisabled(
    params: JSONObject,
    boundary: String
) {
    require(!params.has("preview")) {
        "Split-SDXL $boundary does not support live preview."
    }
}

internal fun isValidSplitSdxlJournalEntry(
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

internal fun listSplitSdxlCleanupDigests(
    artifactRoot: File,
    store: ImageExecutionJournalStore
): Set<String> = linkedSetOf<String>().apply {
    addAll(store.listJournalDigests())
    addAll(listSplitSdxlOrphanArtifactsByDigest(artifactRoot).keys)
}

internal fun acquireSplitSdxlRecoveryLocks(
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

internal fun deleteHeldDormantSplitSdxlLockTombstones(
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

internal fun sweepSplitSdxlOrphanArtifacts(
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

internal fun cleanupLockedSplitSdxlProjectionDirectories(
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

package com.muyuchat.mca

import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

private const val SDXL_REQUEST_FINISH_LOCK_TIMEOUT_MS = 1_000L

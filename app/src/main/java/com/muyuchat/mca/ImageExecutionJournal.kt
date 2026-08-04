package com.muyuchat.mca

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

internal enum class ImageExecutionPhase {
    PREPARING,
    CONDITIONING,
    SAMPLING,
    DECODING,
    PUBLISHING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    val terminal: Boolean
        get() = this in TERMINAL_PHASES

    companion object {
        private val TERMINAL_PHASES = setOf(COMPLETED, FAILED, CANCELLED, INTERRUPTED)

        fun from(raw: String): ImageExecutionPhase = entries.firstOrNull {
            it.name.equals(raw.trim(), ignoreCase = true)
        } ?: error("Unknown image execution phase: $raw")
    }
}

internal data class ImageExecutionOutputArtifact(
    val index: Int,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
    val seed: Long? = null
) {
    init {
        require(index >= 0) { "Image journal output index must be non-negative." }
        require(mimeType.startsWith("image/")) { "Image journal output MIME type must use image/*." }
        require(bytes > 0L) { "Image journal output size must be positive." }
        require(SHA256.matches(sha256)) { "Image journal output SHA-256 is invalid." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("mimeType", mimeType)
        .put("bytes", bytes)
        .put("sha256", sha256.lowercase())
        .apply { seed?.let { put("seed", it) } }

    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun fromJson(json: JSONObject): ImageExecutionOutputArtifact =
            ImageExecutionOutputArtifact(
                index = json.getInt("index"),
                mimeType = json.requiredString("mimeType"),
                bytes = json.getLong("bytes"),
                sha256 = json.requiredString("sha256"),
                seed = json.optLongOrNull("seed")
            )
    }
}

internal data class ImageExecutionJournalEntry(
    val schemaVersion: Int = SCHEMA_VERSION,
    val requestId: String,
    val modelId: String = "",
    val modelName: String = "",
    val recommendationId: String = "",
    val recommendationRevision: String = "",
    val modelFingerprint: String,
    val profileFingerprint: String,
    val requestedSummaryJson: String = "{}",
    val resolvedSummaryJson: String = "{}",
    val phase: ImageExecutionPhase = ImageExecutionPhase.PREPARING,
    val step: Int = 0,
    val steps: Int = 0,
    val nativeStageMask: Long = 0L,
    val nativeGenerationSequence: Long? = null,
    val leaseToken: String = "",
    val workerPid: Int = -1,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    val latentTempPath: String = "",
    val outputTempPath: String = "",
    val outputTempPaths: List<String> = emptyList(),
    val outputArtifacts: List<ImageExecutionOutputArtifact> = emptyList(),
    val inputTempPaths: List<String> = emptyList(),
    val cancellationRequested: Boolean = false,
    val errorCode: String = "",
    val errorMessage: String = ""
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported image journal schema: $schemaVersion" }
        require(requestId.isNotBlank()) { "Image journal requestId must not be blank." }
        require(modelFingerprint.isNotBlank()) { "Image journal model fingerprint must not be blank." }
        require(profileFingerprint.isNotBlank()) { "Image journal profile fingerprint must not be blank." }
        require(step >= 0) { "Image journal step must be non-negative." }
        require(steps >= 0) { "Image journal steps must be non-negative." }
        require(steps == 0 || step <= steps) { "Image journal step must not exceed steps." }
        require(nativeGenerationSequence == null || nativeGenerationSequence >= 0L) {
            "Image journal native generation sequence must be non-negative."
        }
        require(leaseToken.isEmpty() || LEASE_TOKEN.matches(leaseToken)) {
            "Image journal lease token must be a lowercase 128-bit hex value."
        }
        require(workerPid >= -1) { "Image journal worker PID is invalid." }
        require(createdAtMs > 0L) { "Image journal creation time must be positive." }
        require(updatedAtMs >= createdAtMs) { "Image journal update time precedes creation time." }
        require(inputTempPaths.none(String::isBlank)) {
            "Image journal input temp paths must not contain blank values."
        }
        require(outputTempPaths.none(String::isBlank)) {
            "Image journal output temp paths must not contain blank values."
        }
        require(outputArtifacts.map(ImageExecutionOutputArtifact::index) == outputArtifacts.indices.toList()) {
            "Image journal output artifacts must be contiguous and start at zero."
        }
        requireJsonObject(requestedSummaryJson, "requested summary")
        requireJsonObject(resolvedSummaryJson, "resolved summary")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("requestId", requestId)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("recommendationId", recommendationId)
        .put("recommendationRevision", recommendationRevision)
        .put("modelFingerprint", modelFingerprint)
        .put("profileFingerprint", profileFingerprint)
        .put("requested", JSONObject(requestedSummaryJson))
        .put("resolved", JSONObject(resolvedSummaryJson))
        .put("phase", phase.name)
        .put("step", step)
        .put("steps", steps)
        .put("nativeStageMask", nativeStageMask)
        .apply {
            nativeGenerationSequence?.let { put("nativeGenerationSequence", it) }
        }
        .put("leaseToken", leaseToken)
        .put("workerPid", workerPid)
        .put("createdAtMs", createdAtMs)
        .put("updatedAtMs", updatedAtMs)
        .put("latentTempPath", latentTempPath)
        .put("outputTempPath", outputTempPath)
        .put("outputTempPaths", JSONArray(outputTempPaths))
        .put("outputArtifacts", JSONArray().apply { outputArtifacts.forEach { put(it.toJson()) } })
        .put("inputTempPaths", JSONArray(inputTempPaths))
        .put("cancellationRequested", cancellationRequested)
        .put("errorCode", errorCode)
        .put("errorMessage", errorMessage)

    companion object {
        const val SCHEMA_VERSION = 3
        private const val LEGACY_SCHEMA_VERSION = 1
        private val LEASE_TOKEN = Regex("^[0-9a-f]{32}$")

        fun fromJson(json: JSONObject): ImageExecutionJournalEntry {
            val storedSchemaVersion = json.optInt("schemaVersion", LEGACY_SCHEMA_VERSION)
            require(storedSchemaVersion in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION) {
                "Unsupported persisted image journal schema: $storedSchemaVersion"
            }
            return ImageExecutionJournalEntry(
                // Schema 1 entries are losslessly promoted in memory. Their new provenance fields
                // remain empty until a fresh request is started under schema 2.
                schemaVersion = SCHEMA_VERSION,
                requestId = json.requiredString("requestId"),
                modelId = json.optString("modelId"),
                modelName = json.optString("modelName"),
                recommendationId = json.optString("recommendationId"),
                recommendationRevision = json.optString("recommendationRevision"),
                modelFingerprint = json.requiredString("modelFingerprint"),
                profileFingerprint = json.requiredString("profileFingerprint"),
                requestedSummaryJson = (json.optJSONObject("requested") ?: JSONObject()).toString(),
                resolvedSummaryJson = (json.optJSONObject("resolved") ?: JSONObject()).toString(),
                phase = ImageExecutionPhase.from(json.requiredString("phase")),
                step = json.optInt("step", 0),
                steps = json.optInt("steps", 0),
                nativeStageMask = json.optLong("nativeStageMask", 0L),
                nativeGenerationSequence = json.optLongOrNull("nativeGenerationSequence"),
                leaseToken = json.optString("leaseToken"),
                workerPid = json.optInt("workerPid", -1),
                createdAtMs = json.optLong("createdAtMs", 0L),
                updatedAtMs = json.optLong("updatedAtMs", json.optLong("createdAtMs", 0L)),
                latentTempPath = json.optString("latentTempPath"),
                outputTempPath = json.optString("outputTempPath"),
                outputTempPaths = json.optJSONArray("outputTempPaths")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.orEmpty(),
                outputArtifacts = json.optJSONArray("outputArtifacts")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            add(ImageExecutionOutputArtifact.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                inputTempPaths = json.optJSONArray("inputTempPaths")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.orEmpty(),
                cancellationRequested = json.optBoolean("cancellationRequested", false),
                errorCode = json.optString("errorCode"),
                errorMessage = json.optString("errorMessage")
            )
        }

        private fun requireJsonObject(raw: String, label: String) {
            require(runCatching { JSONObject(raw) }.isSuccess) {
                "Image journal $label must be a JSON object."
            }
        }
    }
}

internal data class ImageExecutionCleanupReport(
    val deletedPaths: List<String> = emptyList(),
    val skippedPaths: List<String> = emptyList(),
    val failedPaths: List<String> = emptyList()
) {
    operator fun plus(other: ImageExecutionCleanupReport): ImageExecutionCleanupReport =
        ImageExecutionCleanupReport(
            deletedPaths = deletedPaths + other.deletedPaths,
            skippedPaths = skippedPaths + other.skippedPaths,
            failedPaths = failedPaths + other.failedPaths
        )
}

internal data class ImageExecutionRecoveryReport(
    val interrupted: List<ImageExecutionJournalEntry>,
    val stillRunning: List<ImageExecutionJournalEntry>,
    val invalidJournalFiles: List<String>,
    val cleanup: ImageExecutionCleanupReport
)

internal data class ImageExecutionTerminalResult(
    val entry: ImageExecutionJournalEntry,
    val cleanup: ImageExecutionCleanupReport
)

/**
 * Durable request lifecycle journal. It records metadata and cleanup paths only; native contexts
 * are never resumed. Recovery marks dead work as interrupted so a deterministic retry can restart
 * from the original seed/profile through the normal coordinator.
 */
internal class ImageExecutionJournalStore(
    private val directory: File,
    private val parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val inProcessStoreLock = processStoreLocks.computeIfAbsent(
        runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
    ) { ReentrantLock() }

    fun create(entry: ImageExecutionJournalEntry): ImageExecutionJournalEntry {
        require(entry.phase == ImageExecutionPhase.PREPARING) {
            "A new image journal must start in PREPARING."
        }
        return withStoreLock {
            val target = journalFile(entry.requestId)
            require(!target.exists()) { "Image journal already exists for ${entry.requestId}." }
            writeAtomic(target, entry)
            entry
        }
    }

    fun listReadableEntries(): List<ImageExecutionJournalEntry> {
        directory.mkdirs()
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith(JOURNAL_PREFIX) && file.name.endsWith(JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .mapNotNull { file ->
                runCatching {
                    readFile(file).takeIf { entry ->
                        journalFile(entry.requestId).canonicalFile == file.canonicalFile
                    }
                }.getOrNull()
            }
    }

    fun listJournalDigests(): Set<String> {
        directory.mkdirs()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            JOURNAL_FILE_PATTERN.matchEntire(file.name)?.groupValues?.getOrNull(1)
        }.toSet()
    }

    fun read(requestId: String): ImageExecutionJournalEntry? {
        val file = journalFile(requestId)
        if (!file.isFile) return null
        val entry = readFile(file)
        require(entry.requestId == requestId &&
            journalFile(entry.requestId).canonicalFile == file.canonicalFile
        ) {
            "Image journal file identity does not match its requestId."
        }
        return entry
    }

    fun update(
        next: ImageExecutionJournalEntry,
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry = withStoreLock {
        updateLocked(next, expectedLeaseToken)
    }

    private fun updateLocked(
        next: ImageExecutionJournalEntry,
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry {
        val current = read(next.requestId) ?: error("Image journal does not exist: ${next.requestId}")
        requireExpectedLeaseToken(current, expectedLeaseToken)
        validateIdentity(current, next)
        validateTransition(current, next)
        writeAtomic(journalFile(next.requestId), next)
        return next
    }

    fun requestCancellation(
        requestId: String,
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry = withStoreLock {
        val current = read(requestId) ?: error("Image journal does not exist: $requestId")
        requireExpectedLeaseToken(current, expectedLeaseToken)
        require(!current.phase.terminal) { "Terminal image journal cannot be cancelled." }
        updateLocked(
            current.copy(
                cancellationRequested = true,
                updatedAtMs = nextTimestamp(current)
            ),
            expectedLeaseToken = expectedLeaseToken
        )
    }

    fun finishCancelled(
        requestId: String,
        cleanupRoots: List<File>,
        message: String = "Image generation was cancelled."
    ): ImageExecutionTerminalResult {
        requestCancellation(requestId)
        val (terminal, cleanup) = withStoreLock {
            val current = read(requestId) ?: error("Image journal does not exist: $requestId")
            require(current.cancellationRequested) {
                "Cancelled image journal lost its cancellation request."
            }
            val currentCleanup = cleanupTransientFiles(current, cleanupRoots)
            val currentTerminal = updateLocked(current.copy(
                phase = ImageExecutionPhase.CANCELLED,
                updatedAtMs = nextTimestamp(current),
                errorCode = "CANCELLED",
                errorMessage = message
            ))
            currentTerminal to currentCleanup
        }
        return ImageExecutionTerminalResult(terminal, cleanup)
    }

    fun markTerminal(
        requestId: String,
        phase: ImageExecutionPhase,
        errorCode: String = "",
        errorMessage: String = "",
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry = withStoreLock {
        require(phase.terminal) { "markTerminal requires a terminal phase." }
        val current = read(requestId) ?: error("Image journal does not exist: $requestId")
        requireExpectedLeaseToken(current, expectedLeaseToken)
        updateLocked(
            current.copy(
                phase = phase,
                updatedAtMs = nextTimestamp(current),
                errorCode = errorCode,
                errorMessage = errorMessage
            ),
            expectedLeaseToken = expectedLeaseToken
        )
    }

    fun recoverInterrupted(
        cleanupRoots: List<File>,
        preservePublishingOutputs: Boolean = true,
        validateEntryForCleanup: (ImageExecutionJournalEntry) -> Boolean = { true },
        isProcessAlive: (Int) -> Boolean
    ): ImageExecutionRecoveryReport {
        directory.mkdirs()
        val interrupted = mutableListOf<ImageExecutionJournalEntry>()
        val stillRunning = mutableListOf<ImageExecutionJournalEntry>()
        val invalid = mutableListOf<String>()
        var cleanup = ImageExecutionCleanupReport()
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(JOURNAL_PREFIX) && file.name.endsWith(JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .forEach { file ->
                val entry = runCatching { readFile(file) }.getOrElse {
                    invalid += file.name
                    return@forEach
                }
                if (journalFile(entry.requestId).canonicalFile != file.canonicalFile ||
                    !validateEntryForCleanup(entry)
                ) {
                    invalid += file.name
                    return@forEach
                }
                if (entry.phase.terminal) return@forEach
                if (entry.workerPid > 0 && isProcessAlive(entry.workerPid)) {
                    stillRunning += entry
                    return@forEach
                }
                cleanup += cleanupTransientFiles(
                    entry = entry,
                    cleanupRoots = cleanupRoots,
                    preservePublishedOutputs = preservePublishingOutputs &&
                        entry.phase == ImageExecutionPhase.PUBLISHING
                )
                val recovered = update(
                    entry.copy(
                        phase = ImageExecutionPhase.INTERRUPTED,
                        updatedAtMs = nextTimestamp(entry),
                        errorCode = "WORKER_INTERRUPTED",
                        errorMessage = "The image worker ended before reaching a terminal state."
                    )
                )
                interrupted += recovered
            }
        return ImageExecutionRecoveryReport(
            interrupted = interrupted,
            stillRunning = stillRunning,
            invalidJournalFiles = invalid,
            cleanup = cleanup
        )
    }

    fun deleteTerminal(
        requestId: String,
        expectedLeaseToken: String? = null
    ): Boolean = withStoreLock {
        val current = read(requestId) ?: return@withStoreLock false
        requireExpectedLeaseToken(current, expectedLeaseToken)
        require(current.phase.terminal) { "Non-terminal image journal cannot be deleted." }
        journalFile(requestId).delete()
    }

    /**
     * Removes terminal request metadata while retaining active or unreadable journals.
     *
     * Callers that own temporary output artifacts may provide their cleanup roots. In that mode
     * every recorded transient is removed before its terminal journal is deleted, which also
     * closes the crash window left by older writers that published a terminal journal before the
     * surrounding provider had consumed its output.
     */
    fun pruneTerminalJournals(
        cleanupRoots: List<File> = emptyList(),
        validateEntryForCleanup: (ImageExecutionJournalEntry) -> Boolean = { true }
    ): ImageExecutionCleanupReport {
        directory.mkdirs()
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith(JOURNAL_PREFIX) && file.name.endsWith(JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .fold(ImageExecutionCleanupReport()) { report, file ->
                val entry = runCatching { readFile(file) }.getOrNull()
                if (entry?.phase?.terminal != true ||
                    journalFile(entry.requestId).canonicalFile != file.canonicalFile ||
                    !validateEntryForCleanup(entry)
                ) {
                    report
                } else {
                    val artifactCleanup = if (cleanupRoots.isEmpty()) {
                        ImageExecutionCleanupReport()
                    } else {
                        cleanupTransientFiles(
                            entry = entry,
                            cleanupRoots = cleanupRoots,
                            preservePublishedOutputs = false
                        )
                    }
                    val canDeleteJournal = artifactCleanup.skippedPaths.isEmpty() &&
                        artifactCleanup.failedPaths.isEmpty()
                    val journalCleanup = when {
                        !canDeleteJournal -> ImageExecutionCleanupReport()
                        runCatching { file.delete() }.getOrDefault(false) ->
                            ImageExecutionCleanupReport(deletedPaths = listOf(file.canonicalPath))
                        else -> ImageExecutionCleanupReport(failedPaths = listOf(file.canonicalPath))
                    }
                    report + artifactCleanup + journalCleanup
                }
            }
    }

    private fun cleanupTransientFiles(
        entry: ImageExecutionJournalEntry,
        cleanupRoots: List<File>,
        preservePublishedOutputs: Boolean = false
    ): ImageExecutionCleanupReport = sequenceOf(entry.latentTempPath)
        .plus(
            sequenceOf(entry.outputTempPath)
                .plus(entry.outputTempPaths.asSequence())
                .filter { path -> !preservePublishedOutputs || path.endsWith(".part") }
        )
        .plus(entry.inputTempPaths.asSequence())
        .filter(String::isNotBlank)
        .distinct()
        .fold(ImageExecutionCleanupReport()) { report, path ->
            report + cleanupPath(path, cleanupRoots)
        }

    private fun cleanupPath(path: String, cleanupRoots: List<File>): ImageExecutionCleanupReport {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return ImageExecutionCleanupReport(failedPaths = listOf(path))
        val allowed = cleanupRoots.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }.any { root -> candidate.path.startsWith(root.path + File.separator) }
        if (!allowed) return ImageExecutionCleanupReport(skippedPaths = listOf(candidate.path))
        if (!candidate.exists()) return ImageExecutionCleanupReport()
        return if (runCatching { candidate.delete() }.getOrDefault(false)) {
            ImageExecutionCleanupReport(deletedPaths = listOf(candidate.path))
        } else {
            ImageExecutionCleanupReport(failedPaths = listOf(candidate.path))
        }
    }

    private fun validateIdentity(
        current: ImageExecutionJournalEntry,
        next: ImageExecutionJournalEntry
    ) {
        require(current.schemaVersion == next.schemaVersion) { "Image journal schema cannot change." }
        require(current.requestId == next.requestId) { "Image journal request identity cannot change." }
        require(current.modelId == next.modelId) { "Image journal model id cannot change." }
        require(current.modelName == next.modelName) { "Image journal model name cannot change." }
        require(current.recommendationId == next.recommendationId) {
            "Image journal recommendation id cannot change."
        }
        require(current.recommendationRevision == next.recommendationRevision) {
            "Image journal recommendation revision cannot change."
        }
        require(current.modelFingerprint == next.modelFingerprint) { "Image journal model fingerprint cannot change." }
        require(current.profileFingerprint == next.profileFingerprint) { "Image journal profile fingerprint cannot change." }
        require(current.leaseToken == next.leaseToken) { "Image journal lease token cannot change." }
        require(current.createdAtMs == next.createdAtMs) { "Image journal creation time cannot change." }
        require(current.inputTempPaths == next.inputTempPaths) {
            "Image journal input temp paths cannot change."
        }
        require(normalizedJsonObject(current.requestedSummaryJson) == normalizedJsonObject(next.requestedSummaryJson)) {
            "Image journal requested summary cannot change."
        }
        require(normalizedJsonObject(current.resolvedSummaryJson) == normalizedJsonObject(next.resolvedSummaryJson)) {
            "Image journal resolved summary cannot change."
        }
    }

    private fun requireExpectedLeaseToken(
        current: ImageExecutionJournalEntry,
        expectedLeaseToken: String?
    ) {
        if (expectedLeaseToken == null) return
        require(expectedLeaseToken.isNotBlank() && current.leaseToken == expectedLeaseToken) {
            "Image journal lease token does not match the active request epoch."
        }
    }

    private fun validateTransition(
        current: ImageExecutionJournalEntry,
        next: ImageExecutionJournalEntry
    ) {
        require(!current.phase.terminal) { "Terminal image journal cannot transition." }
        require(next.updatedAtMs >= current.updatedAtMs) { "Image journal time must be monotonic." }
        require(!current.cancellationRequested || next.cancellationRequested) {
            "Image journal cancellation request cannot be cleared."
        }
        if (current.nativeGenerationSequence != null) {
            require(next.nativeGenerationSequence == current.nativeGenerationSequence) {
                "Image journal native generation sequence cannot change."
            }
        }
        if (current.workerPid > 0 && next.workerPid > 0) {
            require(current.workerPid == next.workerPid) { "Image journal worker PID cannot change." }
        }
        require((next.nativeStageMask or current.nativeStageMask) == next.nativeStageMask) {
            "Image journal native stage mask cannot lose observed stages."
        }
        require(next.outputTempPaths.containsAll(current.outputTempPaths)) {
            "Image journal output temp paths cannot lose observed paths."
        }
        require(next.outputArtifacts.take(current.outputArtifacts.size) == current.outputArtifacts) {
            "Image journal output artifacts cannot lose or rewrite published evidence."
        }
        if (current.steps > 0 && next.steps > 0) {
            require(current.steps == next.steps) { "Image journal total steps cannot change." }
        }
        require(next.step >= current.step || next.phase == ImageExecutionPhase.INTERRUPTED) {
            "Image journal step cannot move backwards."
        }
        require(next.phase in allowedNextPhases(current.phase)) {
            "Invalid image journal transition: ${current.phase} -> ${next.phase}"
        }
    }

    private fun allowedNextPhases(current: ImageExecutionPhase): Set<ImageExecutionPhase> = when (current) {
        ImageExecutionPhase.PREPARING -> setOf(
            ImageExecutionPhase.PREPARING,
            ImageExecutionPhase.CONDITIONING,
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.CONDITIONING -> setOf(
            ImageExecutionPhase.CONDITIONING,
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.SAMPLING -> setOf(
            ImageExecutionPhase.SAMPLING,
            ImageExecutionPhase.DECODING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.DECODING -> setOf(
            ImageExecutionPhase.DECODING,
            ImageExecutionPhase.PUBLISHING,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.PUBLISHING -> setOf(
            ImageExecutionPhase.PUBLISHING,
            ImageExecutionPhase.COMPLETED,
            ImageExecutionPhase.FAILED,
            ImageExecutionPhase.CANCELLED,
            ImageExecutionPhase.INTERRUPTED
        )
        ImageExecutionPhase.COMPLETED,
        ImageExecutionPhase.FAILED,
        ImageExecutionPhase.CANCELLED,
        ImageExecutionPhase.INTERRUPTED -> emptySet()
    }

    private fun nextTimestamp(current: ImageExecutionJournalEntry): Long =
        clock().coerceAtLeast(current.updatedAtMs + 1L)

    private fun readFile(file: File): ImageExecutionJournalEntry {
        require(file.length() in 1L..MAX_JOURNAL_BYTES) {
            "Image journal size is outside the bounded metadata contract."
        }
        return ImageExecutionJournalEntry.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }

    private fun normalizedJsonObject(raw: String): String = JSONObject(raw).toString()

    private fun writeAtomic(file: File, entry: ImageExecutionJournalEntry) {
        directory.mkdirs()
        val temp = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(entry.toJson().toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            durableMoveWithinParent(
                source = temp,
                target = file,
                move = { source, target ->
                    try {
                        Files.move(
                            source.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            source.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                },
                parentDirectorySyncer = parentDirectorySyncer
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private inline fun <T> withStoreLock(block: () -> T): T =
        inProcessStoreLock.withLock {
            directory.mkdirs()
            val lockFile = File(directory, STORE_LOCK_FILE)
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use { block() }
            }
        }

    private fun journalFile(requestId: String): File {
        require(requestId.isNotBlank()) { "Image journal requestId must not be blank." }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$JOURNAL_PREFIX$digest$JOURNAL_SUFFIX")
    }

    private companion object {
        const val JOURNAL_PREFIX = "image-execution-"
        const val JOURNAL_SUFFIX = ".json"
        const val STORE_LOCK_FILE = ".image-execution-store.lock"
        val JOURNAL_FILE_PATTERN = Regex("^image-execution-([0-9a-f]{64})\\.json$")
        const val MAX_JOURNAL_BYTES = 1024L * 1024L
        private val processStoreLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

private fun JSONObject.requiredString(name: String): String =
    optString(name).takeIf(String::isNotBlank) ?: error("Missing image journal field: $name")

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

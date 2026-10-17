package com.muyuchat.mca

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
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
    internal val directory: File,
    internal val parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer,
    internal val clock: () -> Long = System::currentTimeMillis
) {
    internal val inProcessStoreLock = processStoreLocks.computeIfAbsent(
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

    fun update(
        next: ImageExecutionJournalEntry,
        expectedLeaseToken: String? = null
    ): ImageExecutionJournalEntry = withStoreLock {
        updateLocked(next, expectedLeaseToken)
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

    internal companion object {
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

package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.json.JSONObject

/**
 * Reads the native QNN progress sidecar without entering JNI.  This remains
 * observable when the generation thread is blocked in graph/context teardown
 * or dlclose, which is exactly when a diagnostic trace is most valuable.
 */
internal object QnnImageStageJournal {
    private const val MAX_JOURNAL_BYTES = 256L * 1024L
    private const val MAX_PREVIEW_BYTES = 32L * 1024L * 1024L
    private const val MAX_PREVIEW_DIRECTORY_ENTRIES = 32
    private const val MAX_STALE_ROOT_ENTRIES = 256
    private const val MAX_STALE_DIRECTORIES_PER_SWEEP = 32
    private const val STALE_PREVIEW_AGE_MS = 24L * 60L * 60L * 1_000L
    private val PREVIEW_FILE_NAME = Regex("preview-([1-9][0-9]*)\\.png")
    private val PREVIEW_CLEANUP_FILE_NAME =
        Regex("preview-[1-9][0-9]*\\.png(?:\\.(?:tmp|part))?")
    private val REQUEST_PREVIEW_DIRECTORY_NAME =
        Regex(
            "qnn-htp-[0-9]+-[0-9a-fA-F-]{36}\\." +
                "(?:qnn|unet)-stage\\.json\\.previews"
        )
    private val SHARED_QNN_JOURNAL_NAME =
        Regex("qnn-htp-[0-9]+-[0-9a-fA-F-]{36}\\.qnn-stage\\.json")
    private val SPLIT_SDXL_UNET_JOURNAL_NAME =
        Regex("qnn-htp-[0-9]+-[0-9a-fA-F-]{36}\\.unet-stage\\.json")
    private const val SDXL_PROJECTION_JOURNAL_SUFFIX = ".projection.json"
    private val PREVIEW_FAILURE_CODES = setOf(
        "PREVIEW_STORAGE_INVALID",
        "PREVIEW_VAE_INPUT_BIND_FAILED",
        "PREVIEW_VAE_EXECUTE_FAILED",
        "PREVIEW_VAE_OUTPUT_READ_FAILED",
        "PREVIEW_REVISION_INVALID",
        "PREVIEW_PNG_WRITE_FAILED",
        "PREVIEW_PNG_INVALID",
        "PREVIEW_ATOMIC_RENAME_FAILED",
        "PREVIEW_DIRECTORY_FSYNC_FAILED",
        "PREVIEW_JOURNAL_COMMIT_FAILED"
    )
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )

    fun readOrPrevious(
        file: File,
        previous: LocalImageProgress?,
        threads: Int,
        width: Int,
        height: Int
    ): LocalImageProgress? = runCatching {
        if (file.name.endsWith(SDXL_PROJECTION_JOURNAL_SUFFIX)) {
            return@runCatching previous
        }
        if (!file.isFile || file.length() !in 1..MAX_JOURNAL_BYTES) {
            return@runCatching previous
        }
        val payload = file.inputStream().use(::readBoundedUtf8) ?: return@runCatching previous
        val json = JSONObject(payload)
        val trace = json.optJSONArray("stageTrace")?.let { stages ->
            buildList {
                for (index in 0 until stages.length()) {
                    stages.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.orEmpty()
        if (trace.isEmpty() && !json.optBoolean("active") && !json.optBoolean("cancelRequested")) {
            return@runCatching previous
        }
        val observedSteps = json.optInt("steps", previous?.steps ?: 0)
        val previewMode = json.optString("previewMode")
        val previewAttemptCount = json.optInt("previewVaeExecutionAttemptCount")
        val previewExecutionCount = json.optInt("previewVaeExecutionCount")
        val previewExecutionMsTotal = json.optLong("previewVaeExecutionMsTotal")
        val previewPublicationCount = json.optInt("previewPublicationCount")
        val previewLastStep = json.optInt("previewLastStep")
        val previewLastRevision = json.optLong("previewLastRevision")
        val previewFailureCode = json.optString("previewFailureCode")
        require(
            previewAttemptCount >= 0 && previewExecutionCount >= 0 &&
                previewExecutionMsTotal >= 0L && previewPublicationCount >= 0 &&
                previewLastStep >= 0 && previewLastRevision >= 0L &&
                previewExecutionCount <= previewAttemptCount &&
                previewLastRevision == previewPublicationCount.toLong()
        ) { "QNN preview audit counters are inconsistent." }
        require(previewMode != SdxlProjectionPreviewRequest.MODE) {
            "Split-SDXL projection sidecars are not readable progress."
        }
        require(previewPublicationCount <= previewExecutionCount) {
            "QNN VAE preview publication lacks a matching graph execution."
        }
        require(previewFailureCode.length <= 128 &&
            previewFailureCode.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
            (previewFailureCode.isEmpty() || previewFailureCode in PREVIEW_FAILURE_CODES)
        ) { "QNN preview failure code is invalid." }
        if (previewFailureCode == "PREVIEW_STORAGE_INVALID" ||
            (previewFailureCode == "PREVIEW_JOURNAL_COMMIT_FAILED" &&
                previewAttemptCount == 0 && previewExecutionCount == 0 &&
                previewPublicationCount == 0)
        ) {
            require(previewAttemptCount == 0 && previewExecutionCount == 0 &&
                previewPublicationCount == 0
            ) { "QNN preview initialization failure claimed execution evidence." }
        } else if (previewFailureCode.isNotEmpty()) {
            require(previewAttemptCount == previewPublicationCount + 1 &&
                previewExecutionCount in previewPublicationCount..previewAttemptCount
            ) { "QNN preview failure counters do not describe one stopped attempt." }
        }

        val previewPath = json.optString("previewPath")
        val previewRevision = json.optLong("previewRevision")
        val previewStep = json.optInt("previewStep")
        val previewWidth = json.optInt("previewWidth")
        val previewHeight = json.optInt("previewHeight")
        val previewFrameCount = json.optInt("previewFrameCount")
        if (previewPath.isNotBlank()) {
            require(json.optString("previewMimeType") == "image/png" &&
                previewMode == "vae" &&
                !json.optBoolean("previewNoisy") &&
                previewRevision > 0L && previewRevision == previewLastRevision &&
                previewStep > 0 && previewStep == previewLastStep &&
                previewStep < observedSteps && previewWidth > 0 && previewHeight > 0 &&
                previewFrameCount == previewPublicationCount
            ) { "QNN preview metadata is inconsistent." }
            val expectedDirectory = File(file.canonicalPath + ".previews").canonicalFile
            val candidate = File(previewPath).canonicalFile
            val match = PREVIEW_FILE_NAME.matchEntire(candidate.name)
            require(candidate.parentFile == expectedDirectory &&
                match?.groupValues?.get(1)?.toLongOrNull() == previewRevision &&
                candidate.isFile && candidate.length() in 1..MAX_PREVIEW_BYTES &&
                candidate.hasExpectedPreviewPngHeader(previewWidth, previewHeight)
            ) { "QNN preview path is outside the request-scoped private directory." }
            previous?.takeIf { it.previewPath.isNotBlank() }?.let { prior ->
                require(previewRevision >= prior.previewRevision) {
                    "QNN preview revision moved backwards."
                }
                if (previewRevision == prior.previewRevision) {
                    require(candidate.canonicalPath == File(prior.previewPath).canonicalPath) {
                        "QNN preview path changed without a new revision."
                    }
                }
            }
        } else {
            require(previewRevision == 0L && previewStep == 0 &&
                previewWidth == 0 && previewHeight == 0
            ) { "QNN preview pathless progress carried publishable metadata." }
        }

        LocalImageProgress(
            phase = json.optString("phase").ifBlank { previous?.phase.orEmpty() },
            message = json.optString("message").ifBlank { previous?.message.orEmpty() },
            step = json.optInt("step", previous?.step ?: 0),
            steps = observedSteps,
            elapsedMs = json.optLong("elapsedMs", previous?.elapsedMs ?: 0L),
            secondsPerStep = 0.0,
            threads = threads,
            width = width,
            height = height,
            cancelRequested = json.optBoolean("cancelRequested"),
            stageTrace = if (trace.size >= previous?.stageTrace.orEmpty().size) {
                trace
            } else {
                previous?.stageTrace.orEmpty()
            },
            previewPath = previewPath,
            previewMimeType = json.optString("previewMimeType"),
            previewMode = previewMode,
            previewStep = previewStep,
            previewRevision = previewRevision,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            previewFrameCount = previewFrameCount,
            previewNoisy = json.optBoolean("previewNoisy"),
            previewVaeExecutionAttemptCount = previewAttemptCount,
            previewVaeExecutionCount = previewExecutionCount,
            previewVaeExecutionMsTotal = previewExecutionMsTotal,
            previewPublicationCount = previewPublicationCount,
            previewLastStep = previewLastStep,
            previewLastRevision = previewLastRevision,
            previewFailureCode = previewFailureCode
        )
    }.getOrDefault(previous)

    internal fun readSdxlProjectionPreviewAuditOrNull(
        journalFile: File,
        width: Int,
        height: Int
    ): SdxlProjectionPreviewAudit? {
        // Kept only for worker-wire compatibility while old sidecars are cleaned up.
        return null
    }

    internal fun sdxlProjectionPreviewJournalFile(journalFile: File): File =
        File(journalFile.absolutePath + SDXL_PROJECTION_JOURNAL_SUFFIX)

    internal fun cleanupSdxlProjectionPreview(journalFile: File): Boolean {
        if (!SPLIT_SDXL_UNET_JOURNAL_NAME.matches(journalFile.name)) return false
        val directoryRemoved = cleanupRequestPreviewDirectory(journalFile)
        val sidecar = sdxlProjectionPreviewJournalFile(journalFile)
        val temporary = File(sidecar.path + ".part")
        val sidecarsRemoved = listOf(sidecar, temporary).all { file ->
            !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        }
        return directoryRemoved && sidecarsRemoved
    }

    internal fun readBoundedUtf8(input: InputStream): String? {
        val output = ByteArrayOutputStream(MAX_JOURNAL_BYTES.toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) return null
            total += count
            if (total > MAX_JOURNAL_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    internal fun cleanupRequestPreviewDirectory(journalFile: File): Boolean {
        val directory = requestPreviewDirectoryOrNull(journalFile) ?: return false
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return false
        val entries = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                if (entries.size >= MAX_PREVIEW_DIRECTORY_ENTRIES) return false
                entries += iterator.next().toFile()
            }
        }
        val canonicalDirectory = directory.canonicalFile
        entries.forEach { entry ->
            val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return@forEach
            if (canonicalEntry.parentFile != canonicalDirectory ||
                Files.isSymbolicLink(entry.toPath()) || !entry.isFile ||
                !PREVIEW_CLEANUP_FILE_NAME.matches(entry.name)
            ) {
                return@forEach
            }
            runCatching { entry.delete() }
        }
        return directory.delete()
    }

    internal fun sweepStalePreviewDirectories(
        outputRoot: File,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        require(nowMs >= 0L) { "Stale preview sweep time must be non-negative." }
        if (!outputRoot.isDirectory || Files.isSymbolicLink(outputRoot.toPath())) return 0
        val canonicalRoot = outputRoot.canonicalFile
        val cutoff = (nowMs - STALE_PREVIEW_AGE_MS).coerceAtLeast(0L)
        val candidates = mutableListOf<File>()
        Files.newDirectoryStream(outputRoot.toPath()).use { stream ->
            val iterator = stream.iterator()
            var inspected = 0
            while (iterator.hasNext() && inspected < MAX_STALE_ROOT_ENTRIES) {
                candidates += iterator.next().toFile()
                inspected += 1
            }
        }
        return candidates.asSequence()
            .filter { candidate ->
                candidate.isDirectory && !Files.isSymbolicLink(candidate.toPath()) &&
                    REQUEST_PREVIEW_DIRECTORY_NAME.matches(candidate.name) &&
                    candidate.lastModified() in 1..cutoff &&
                    runCatching { candidate.canonicalFile.parentFile == canonicalRoot }
                        .getOrDefault(false)
            }
            .sortedBy(File::lastModified)
            .take(MAX_STALE_DIRECTORIES_PER_SWEEP)
            .count { directory ->
                val journalName = directory.name.removeSuffix(".previews")
                cleanupRequestPreviewDirectory(File(outputRoot, journalName))
            }
    }

    private fun requestPreviewDirectoryOrNull(journalFile: File): File? = runCatching {
        if (!SHARED_QNN_JOURNAL_NAME.matches(journalFile.name) &&
            !SPLIT_SDXL_UNET_JOURNAL_NAME.matches(journalFile.name)
        ) {
            return@runCatching null
        }
        val parent = journalFile.parentFile?.canonicalFile ?: return@runCatching null
        val directory = File(parent, journalFile.name + ".previews").canonicalFile
        directory.takeIf { it.parentFile == parent }
    }.getOrNull()

    private fun File.hasExpectedPreviewPngHeader(expectedWidth: Int, expectedHeight: Int): Boolean =
        runCatching {
            val header = ByteArray(29)
            inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count <= 0) return@runCatching false
                    offset += count
                }
            }
            header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
                header.readPngU32(8) == 13L &&
                header.copyOfRange(12, 16).contentEquals(byteArrayOf(0x49, 0x48, 0x44, 0x52)) &&
                header.readPngU32(16) == expectedWidth.toLong() &&
                header.readPngU32(20) == expectedHeight.toLong() &&
                (header[24].toInt() and 0xff) == 8 &&
                (header[25].toInt() and 0xff) == 2 &&
                header[26].toInt() == 0 && header[27].toInt() == 0 &&
                header[28].toInt() == 0
        }.getOrDefault(false)

    private fun ByteArray.readPngU32(offset: Int): Long =
        ((this[offset].toLong() and 0xffL) shl 24) or
            ((this[offset + 1].toLong() and 0xffL) shl 16) or
            ((this[offset + 2].toLong() and 0xffL) shl 8) or
            (this[offset + 3].toLong() and 0xffL)
}

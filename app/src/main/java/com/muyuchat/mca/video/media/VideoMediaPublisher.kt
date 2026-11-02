package com.muyuchat.mca.video.media

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.muyuchat.core.video.contract.VideoDeleteResult
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoMediaAssetId
import com.muyuchat.core.video.contract.VideoMediaProjection
import com.muyuchat.core.video.contract.VideoResolvedParams
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.isWellFormedId
import com.muyuchat.mca.video.data.VideoMediaRecord
import com.muyuchat.mca.video.data.VideoRunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Owned video media publishing (proposal §8.2/§8.4, V1-04).
 *
 * Protocol: verify staged MP4 (container + decoded frames) → durable journal → fsync/atomic rename
 * inside the owned media root → one Room transaction (conditional run completion + media row) →
 * journal/temp cleanup. A crash in any window leaves a journal that [reconcile] can decide without
 * deleting another run's output or claiming an unconfirmed success. Playback references are
 * FileProvider content URIs, never filesystem paths.
 */
class VideoMediaPublisher(
    private val context: Context,
    private val store: VideoRunStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mediaIdSource: () -> String = { "media-${UUID.randomUUID().toString().lowercase()}" },
) {
    private val videoRoot = File(context.filesDir, "video")
    private val runsRoot = File(videoRoot, "runs")
    private val mediaRoot = File(videoRoot, "media")
    private val quarantineRoot = File(videoRoot, "quarantine")
    private val verifier = VideoMp4Verifier()

    sealed class PublishResult {
        /**
         * File verified, journaled and atomically renamed into the owned media root. The DB commit
         * is deliberately NOT done here: the process owner stays the only business state writer
         * and performs the conditional COMPLETED transaction, then calls [finalizePublish].
         */
        data class Published(val record: VideoMediaRecord, val reference: String) : PublishResult()

        /** Cancellation won before the DB commit; the staged file was removed (or quarantined). */
        data object Cancelled : PublishResult()

        data class Failed(val code: VideoErrorCode, val detail: String) : PublishResult()
    }

    fun runDirectory(runId: String): File = File(runsRoot, runId)

    fun stagedOutput(runId: String): File = File(runDirectory(runId), STAGED_OUTPUT)

    fun ensureRunDirectory(runId: String): File {
        require(isWellFormedId(runId)) { "malformed run id" }
        val directory = runDirectory(runId).canonicalFile
        require(isInside(runsRoot.canonicalFile, directory)) { "run directory escaped the owned root" }
        directory.mkdirs()
        return directory
    }

    fun referenceFor(relativePath: String): String {
        val file = File(mediaRoot, relativePath).canonicalFile
        require(isInside(mediaRoot.canonicalFile, file)) { "media path escaped the owned root" }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
    }

    fun projection(row: VideoMediaRecord): VideoMediaProjection = VideoMediaProjection(
        mediaId = VideoMediaAssetId(row.mediaId),
        runId = VideoRunId(row.runId),
        mimeType = row.mimeType,
        reference = referenceFor(row.relativePath),
        sizeBytes = row.sizeBytes,
        width = row.width,
        height = row.height,
        durationUs = row.durationUs,
        fps = row.fps,
        frameCount = row.frameCount,
        codec = row.codec,
        createdAtMs = row.createdAt,
    )

    suspend fun publish(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        params: VideoResolvedParams,
        shouldAbort: () -> Boolean,
    ): PublishResult = withContext(Dispatchers.IO) {
        val runDir = runDirectory(runId).canonicalFile
        if (!isInside(runsRoot.canonicalFile, runDir) || !runDir.isDirectory) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "运行暂存目录无效")
        }
        val staged = File(runDir, STAGED_OUTPUT).canonicalFile
        if (!isInside(runDir, staged)) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "暂存视频路径逃逸")
        }
        if (!staged.isFile || staged.length() <= 0L) {
            return@withContext PublishResult.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "暂存视频不存在或为空")
        }
        val verified = when (
            val result = verifier.verify(
                file = staged,
                expectedWidth = params.width.toInt(),
                expectedHeight = params.height.toInt(),
                expectedFrames = params.frames.toInt(),
                fps = params.fps.toInt(),
            )
        ) {
            is VideoMp4Verifier.Result.Verified -> result.probe
            is VideoMp4Verifier.Result.Failed -> return@withContext PublishResult.Failed(result.code, result.detail)
        }
        if (shouldAbort()) {
            cleanupRunDirectory(runDir)
            return@withContext PublishResult.Cancelled
        }
        val mediaId = mediaIdSource()
        if (!isWellFormedId(mediaId)) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "生成的媒体 ID 不符合契约")
        }
        val sha = sha256(staged)
            ?: return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "无法计算媒体摘要")
        val record = VideoMediaRecord(
            mediaId = mediaId,
            runId = runId,
            mimeType = MIME_MP4,
            relativePath = "$mediaId.mp4",
            sizeBytes = staged.length(),
            sha256 = sha,
            width = params.width,
            height = params.height,
            durationUs = verified.durationUs,
            fps = params.fps,
            frameCount = params.frames,
            codec = "h264",
            hasAudio = false,
            createdAt = clock(),
            deletedAt = null,
        )
        val journal = Journal(
            phase = PHASE_VERIFIED,
            runId = runId,
            attemptId = attemptId,
            expectedVersion = expectedVersion,
            media = record,
            stagedRelative = staged.relativeTo(videoRoot).invariantSeparatorsPath,
        )
        if (!writeJournal(runDir, journal)) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "无法写入发布 journal")
        }
        if (shouldAbort()) {
            cleanupRunDirectory(runDir)
            return@withContext PublishResult.Cancelled
        }
        val finalFile = File(mediaRoot, record.relativePath).canonicalFile
        if (!isInside(mediaRoot.canonicalFile, finalFile)) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "最终媒体路径逃逸")
        }
        mediaRoot.mkdirs()
        if (finalFile.exists()) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "最终媒体文件已存在，拒绝覆盖")
        }
        if (!atomicMove(staged, finalFile)) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "原子发布重命名失败")
        }
        if (!fsyncDirectory(mediaRoot) || !writeJournal(runDir, journal.copy(phase = PHASE_RENAMED))) {
            return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "发布落盘同步失败")
        }
        if (shouldAbort()) {
            if (!quarantine(finalFile)) {
                return@withContext PublishResult.Failed(VideoErrorCode.PUBLISH_FAILED, "取消后文件隔离失败；已保留 journal 供对账")
            }
            cleanupRunDirectory(runDir)
            return@withContext PublishResult.Cancelled
        }
        PublishResult.Published(record, referenceFor(record.relativePath))
    }

    /** Owner committed the COMPLETED transaction; remove the journal and attempt scratch space. */
    suspend fun finalizePublish(runId: String, record: VideoMediaRecord) = withContext(Dispatchers.IO) {
        val runDir = runDirectory(runId).canonicalFile
        if (!isInside(runsRoot.canonicalFile, runDir)) return@withContext
        val journalFile = File(runDir, JOURNAL_NAME)
        val journal = readJournal(journalFile)
        if (journal != null) writeJournal(runDir, journal.copy(phase = PHASE_COMMITTED, media = record))
        cleanupRunDirectory(runDir)
    }

    suspend fun deleteOwnedMedia(mediaId: VideoMediaAssetId): VideoDeleteResult = withContext(Dispatchers.IO) {
        val row = store.mediaById(mediaId.value) ?: return@withContext VideoDeleteResult.Unknown(mediaId)
        if (!store.markMediaDeleted(mediaId.value, clock())) {
            return@withContext VideoDeleteResult.Failed(mediaId, VideoErrorCode.PUBLISH_FAILED, "无法提交删除墓碑")
        }
        val file = File(mediaRoot, row.relativePath).canonicalFile
        if (!isInside(mediaRoot.canonicalFile, file)) {
            return@withContext VideoDeleteResult.Failed(mediaId, VideoErrorCode.PUBLISH_FAILED, "媒体路径逃逸，拒绝删除")
        }
        if (file.exists() && !file.delete()) {
            return@withContext VideoDeleteResult.Failed(
                mediaId,
                VideoErrorCode.PUBLISH_FAILED,
                "已隐藏媒体，但文件删除失败；清理将重试",
            )
        }
        store.deleteTombstonedMedia(mediaId.value)
        VideoDeleteResult.Deleted(mediaId)
    }

    /**
     * Startup reconciliation. Idempotent; operates only inside the owned video root and only on
     * attempts whose persisted state allows a decision. Unparseable journals are left untouched
     * as diagnostics rather than deleted.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val runs = runsRoot.listFiles()?.filter { it.isDirectory } ?: return@withContext
        runs.forEach { runDir ->
            val directory = runDir.canonicalFile
            if (!isInside(runsRoot.canonicalFile, directory)) return@forEach
            val journalFile = File(directory, JOURNAL_NAME)
            if (!journalFile.isFile) {
                cleanupStaleWithoutJournal(directory)
                return@forEach
            }
            val journal = readJournal(journalFile) ?: return@forEach
            val row = store.runById(journal.runId)
            when (journal.phase) {
                PHASE_VERIFIED -> {
                    if (row != null && row.attemptId == journal.attemptId && row.state == "PUBLISHING") {
                        retryVerified(journal, row.stateVersion, directory)
                    } else if (row == null || row.state in TERMINAL_STATES) {
                        // If a rename raced ahead of the journal, the unconfirmed bytes must not
                        // stay in the visible media root; preserve them in quarantine.
                        val finalFile = File(mediaRoot, journal.media.relativePath)
                        if (finalFile.isFile && !quarantine(finalFile)) return@forEach
                        cleanupRunDirectory(directory)
                    }
                }

                PHASE_RENAMED -> {
                    if (row != null && row.attemptId == journal.attemptId && row.state == "PUBLISHING") {
                        if (store.completeRunWithMedia(
                                runId = journal.runId,
                                attemptId = journal.attemptId,
                                expectedVersion = row.stateVersion,
                                fromState = "PUBLISHING",
                                media = journal.media,
                                nowMs = clock(),
                            )
                        ) {
                            writeJournal(directory, journal.copy(phase = PHASE_COMMITTED))
                            cleanupRunDirectory(directory)
                        }
                    } else if (row != null && row.state == "COMPLETED" &&
                        store.mediaById(journal.media.mediaId) != null
                    ) {
                        // The DB transaction is the authority; only the journal/scratch cleanup
                        // was interrupted. Never quarantine a confirmed success.
                        cleanupRunDirectory(directory)
                    } else {
                        // The run was cancelled or died before the DB commit: preserve the bytes
                        // in quarantine (they are not a confirmed success) and clean the temp dir.
                        val finalFile = File(mediaRoot, journal.media.relativePath)
                        if (finalFile.isFile && !quarantine(finalFile)) return@forEach
                        cleanupRunDirectory(directory)
                    }
                }

                PHASE_COMMITTED -> cleanupRunDirectory(directory)
            }
        }
    }

    /** Remove attempt scratch space only when no publish journal still needs it. */
    suspend fun abandon(runId: String) = withContext(Dispatchers.IO) {
        val runDir = runDirectory(runId).canonicalFile
        if (!isInside(runsRoot.canonicalFile, runDir)) return@withContext
        if (File(runDir, JOURNAL_NAME).isFile) return@withContext
        cleanupRunDirectory(runDir)
    }

    private fun retryVerified(journal: Journal, expectedVersion: Int, runDir: File) {
        val staged = File(videoRoot, journal.stagedRelative).canonicalFile
        val finalFile = File(mediaRoot, journal.media.relativePath).canonicalFile
        if (!isInside(mediaRoot.canonicalFile, finalFile)) return
        if (finalFile.isFile) {
            // Crash window: rename happened but the journal was not advanced to RENAMED. Commit
            // only when the bytes match the verified journal; otherwise leave it for diagnosis
            // instead of quarantining or deleting an uncertain file.
            val matches = finalFile.length() == journal.media.sizeBytes &&
                sha256(finalFile).equals(journal.media.sha256, ignoreCase = true)
            if (!matches) return
            if (!writeJournal(runDir, journal.copy(phase = PHASE_RENAMED))) return
            commitRenamed(journal, expectedVersion, runDir)
            return
        }
        if (!isInside(runDir, staged) || !staged.isFile) {
            cleanupRunDirectory(runDir)
            return
        }
        mediaRoot.mkdirs()
        if (!atomicMove(staged, finalFile) || !fsyncDirectory(mediaRoot)) return
        if (!writeJournal(runDir, journal.copy(phase = PHASE_RENAMED))) return
        commitRenamed(journal, expectedVersion, runDir)
    }

    private fun commitRenamed(journal: Journal, expectedVersion: Int, runDir: File) {
        if (store.completeRunWithMedia(
                runId = journal.runId,
                attemptId = journal.attemptId,
                expectedVersion = expectedVersion,
                fromState = "PUBLISHING",
                media = journal.media,
                nowMs = clock(),
            )
        ) {
            writeJournal(runDir, journal.copy(phase = PHASE_COMMITTED))
            cleanupRunDirectory(runDir)
        }
    }

    private fun cleanupStaleWithoutJournal(runDir: File) {
        val runId = runDir.name
        if (!isWellFormedId(runId)) return
        val ageMs = clock() - runDir.lastModified()
        if (ageMs < STALE_TEMP_MS) return
        val row = store.runById(runId) ?: run {
            cleanupRunDirectory(runDir)
            return
        }
        if (row.state in TERMINAL_STATES) cleanupRunDirectory(runDir)
    }

    private fun cleanupRunDirectory(runDir: File) {
        val canonical = runDir.canonicalFile
        if (!isInside(runsRoot.canonicalFile, canonical)) return
        canonical.deleteRecursively()
    }

    private fun quarantine(file: File): Boolean {
        if (!file.isFile) return true
        quarantineRoot.mkdirs()
        val target = File(quarantineRoot, "${clock()}-${file.name}")
        val moved = runCatching { Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(file.toPath(), target.toPath()) }
            .isSuccess
        return moved && target.isFile
    }

    private fun atomicMove(source: File, target: File): Boolean = runCatching {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

    private fun fsyncDirectory(directory: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 21) return@runCatching true
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
            true
        } finally {
            Os.close(fd)
        }
    }.getOrDefault(false)

    private fun writeJournal(runDir: File, journal: Journal): Boolean = runCatching {
        val file = File(runDir, JOURNAL_NAME)
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("phase", journal.phase)
            .put("runId", journal.runId)
            .put("attemptId", journal.attemptId)
            .put("expectedVersion", journal.expectedVersion)
            .put("stagedRelative", journal.stagedRelative)
            .put("media", JSONObject().apply {
                put("mediaId", journal.media.mediaId)
                put("runId", journal.media.runId)
                put("mimeType", journal.media.mimeType)
                put("relativePath", journal.media.relativePath)
                put("sizeBytes", journal.media.sizeBytes)
                put("sha256", journal.media.sha256)
                put("width", journal.media.width)
                put("height", journal.media.height)
                put("durationUs", journal.media.durationUs)
                put("fps", journal.media.fps)
                put("frameCount", journal.media.frameCount)
                put("codec", journal.media.codec)
                put("hasAudio", journal.media.hasAudio)
                put("createdAt", journal.media.createdAt)
            })
        FileOutputStream(file).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun readJournal(file: File): Journal? = runCatching {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val media = root.getJSONObject("media")
        Journal(
            phase = root.getString("phase"),
            runId = root.getString("runId"),
            attemptId = root.getString("attemptId"),
            expectedVersion = root.getInt("expectedVersion"),
            stagedRelative = root.getString("stagedRelative"),
            media = VideoMediaRecord(
                mediaId = media.getString("mediaId"),
                runId = media.getString("runId"),
                mimeType = media.getString("mimeType"),
                relativePath = media.getString("relativePath"),
                sizeBytes = media.getLong("sizeBytes"),
                sha256 = media.getString("sha256"),
                width = media.getLong("width"),
                height = media.getLong("height"),
                durationUs = media.getLong("durationUs"),
                fps = media.getLong("fps"),
                frameCount = media.getLong("frameCount"),
                codec = media.getString("codec"),
                hasAudio = media.getBoolean("hasAudio"),
                createdAt = media.getLong("createdAt"),
                deletedAt = null,
            ),
        )
    }.getOrNull()

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val candidatePath = candidate.absolutePath
        return candidatePath.startsWith(rootPath + File.separator)
    }

    private data class Journal(
        val phase: String,
        val runId: String,
        val attemptId: String,
        val expectedVersion: Int,
        val stagedRelative: String,
        val media: VideoMediaRecord,
    )

    private companion object {
        const val STAGED_OUTPUT = "output.mp4"
        const val JOURNAL_NAME = "journal.json"
        const val MIME_MP4 = "video/mp4"
        const val PHASE_VERIFIED = "verified"
        const val PHASE_RENAMED = "renamed"
        const val PHASE_COMMITTED = "committed"
        const val STALE_TEMP_MS = 24L * 60L * 60L * 1000L
        val TERMINAL_STATES = setOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED")
    }
}

package com.muyuchat.mca.video.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Video-domain Room tables (proposal §8.1, V1-02a minimal slice: revisions + runs + run events;
 * media/link tables arrive with V1-04 publishing). The image_assets table is NOT touched.
 *
 * State authority: [com.muyuchat.core.video.contract.VideoRunTransitions] is the only transition
 * table; the owner decides, and the DAO applies decisions through CONDITIONAL updates keyed on
 * `runId + attemptId + stateVersion` — a stale writer changes 0 rows and learns it from the
 * return value. Terminal commits and resource-release bookkeeping are idempotent by the same key.
 */
@Entity(
    tableName = "video_workflow_revisions",
    indices = [Index("workflowId"), Index("createdAt")],
)
data class VideoWorkflowRevisionEntity(
    @PrimaryKey val revisionId: String,
    val workflowId: String,
    val source: String,
    val schemaVersion: Int,
    val canonicalGraphJson: String,
    val rawSourceRef: String?,
    val canonicalSha256: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "video_runs",
    indices = [Index("workflowId"), Index("revisionId"), Index("state"), Index("updatedAt")],
)
data class VideoRunEntity(
    @PrimaryKey val runId: String,
    val attemptId: String,
    val requestId: String,
    val stateVersion: Int,
    val workflowId: String,
    val revisionId: String,
    val backend: String,
    val modelFingerprint: String,
    val profileFingerprint: String,
    val snapshotJson: String,
    val state: String,
    val resourceState: String,
    val stage: String,
    val step: Long?,
    val stepTotal: Long?,
    val errorCode: String?,
    val errorDetail: String?,
    val outputMediaId: String?,
    val remoteEndpointId: String?,
    val remotePromptId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val terminalAt: Long?,
)

@Entity(
    tableName = "video_run_events",
    indices = [Index("runId"), Index("atMs")],
)
data class VideoRunEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val attemptId: String,
    val state: String,
    val stage: String,
    val message: String?,
    val atMs: Long,
)

/**
 * One published, verified owned video file (V1-04). [relativePath] is relative to the owned
 * media root (`files/video/media`), never an absolute path or a user-supplied location. Deletion is
 * a tombstone first ([deletedAt]), so a crash between the DB marker and unlink can be reconciled.
 */
@Entity(
    tableName = "video_media_assets",
    indices = [Index("runId"), Index("createdAt"), Index("deletedAt")],
)
data class VideoMediaAssetEntity(
    @PrimaryKey val mediaId: String,
    val runId: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Long,
    val height: Long,
    val durationUs: Long,
    val fps: Long,
    val frameCount: Long,
    val codec: String,
    val hasAudio: Boolean,
    val createdAt: Long,
    val deletedAt: Long?,
)

@Dao
interface VideoWorkflowDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRevision(revision: VideoWorkflowRevisionEntity)

    @Query("SELECT * FROM video_workflow_revisions WHERE revisionId = :revisionId LIMIT 1")
    fun revisionById(revisionId: String): VideoWorkflowRevisionEntity?

    /**
     * Conditional transition: succeeds only for the CURRENT attempt at the EXPECTED version.
     * Returns affected rows (0 = stale writer; the owner re-reads and decides again).
     */
    @Query(
        "UPDATE video_runs SET state = :toState, stateVersion = stateVersion + 1, " +
            "stage = :stage, step = :step, stepTotal = :stepTotal, errorCode = :errorCode, " +
            "errorDetail = :errorDetail, terminalAt = :terminalAt, updatedAt = :nowMs " +
            "WHERE runId = :runId AND attemptId = :attemptId AND stateVersion = :expectedVersion AND state = :fromState",
    )
    fun transitionRun(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        toState: String,
        stage: String,
        step: Long?,
        stepTotal: Long?,
        errorCode: String?,
        errorDetail: String?,
        terminalAt: Long?,
        nowMs: Long,
    ): Int

    /** Progress-only update: stage/step may advance, the business state never changes here. */
    @Query(
        "UPDATE video_runs SET stage = :stage, step = :step, stepTotal = :stepTotal, updatedAt = :nowMs " +
            "WHERE runId = :runId AND attemptId = :attemptId",
    )
    fun updateRunProgress(runId: String, attemptId: String, stage: String, step: Long?, stepTotal: Long?, nowMs: Long): Int

    @Query(
        "UPDATE video_runs SET resourceState = :resourceState, updatedAt = :nowMs " +
            "WHERE runId = :runId AND attemptId = :attemptId",
    )
    fun updateRunResourceState(runId: String, attemptId: String, resourceState: String, nowMs: Long): Int

    @Query(
        "UPDATE video_runs SET outputMediaId = :mediaId, updatedAt = :nowMs " +
            "WHERE runId = :runId AND attemptId = :attemptId",
    )
    fun attachRunOutput(runId: String, attemptId: String, mediaId: String?, nowMs: Long): Int

    /** Remote prompt identity is metadata, not a business state transition. */
    @Query(
        "UPDATE video_runs SET remotePromptId = :promptId, updatedAt = :nowMs " +
            "WHERE runId = :runId AND attemptId = :attemptId AND remotePromptId IS NULL",
    )
    fun attachRemotePromptId(runId: String, attemptId: String, promptId: String, nowMs: Long): Int

    @Insert
    fun insertRun(run: VideoRunEntity)

    @Query("SELECT * FROM video_runs WHERE runId = :runId LIMIT 1")
    fun runById(runId: String): VideoRunEntity?

    @Query("SELECT * FROM video_runs ORDER BY createdAt DESC LIMIT :limit")
    fun recentRuns(limit: Int): List<VideoRunEntity>

    @Query("SELECT * FROM video_runs WHERE state IN (:states) ORDER BY createdAt DESC")
    fun runsInStates(states: List<String>): List<VideoRunEntity>

    @Insert
    fun insertRunEvent(event: VideoRunEventEntity)

    /** Bounded retention: only the newest [keep] events per run survive a sweep. */
    @Query(
        "DELETE FROM video_run_events WHERE runId = :runId AND id NOT IN " +
            "(SELECT id FROM video_run_events WHERE runId = :runId ORDER BY id DESC LIMIT :keep)",
    )
    fun trimRunEvents(runId: String, keep: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMedia(media: VideoMediaAssetEntity)

    @Query("SELECT * FROM video_media_assets WHERE mediaId = :mediaId LIMIT 1")
    fun mediaById(mediaId: String): VideoMediaAssetEntity?

    @Query("SELECT * FROM video_media_assets WHERE runId = :runId ORDER BY createdAt DESC")
    fun mediaByRun(runId: String): List<VideoMediaAssetEntity>

    @Query("SELECT * FROM video_media_assets WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    fun recentMedia(limit: Int): List<VideoMediaAssetEntity>

    @Query("SELECT * FROM video_media_assets ORDER BY createdAt DESC")
    fun allMedia(): List<VideoMediaAssetEntity>

    /** Tombstone first; the owned file is unlinked only after this marker is durable. */
    @Query("UPDATE video_media_assets SET deletedAt = :nowMs WHERE mediaId = :mediaId AND deletedAt IS NULL")
    fun markMediaDeleted(mediaId: String, nowMs: Long): Int

    @Query("DELETE FROM video_media_assets WHERE mediaId = :mediaId AND deletedAt IS NOT NULL")
    fun deleteTombstonedMedia(mediaId: String): Int

    /**
     * One atomic terminal commit: conditional run transition + output reference + media row. Any
     * failure rolls the whole transaction back; the staged file stays described by the journal so
     * reconciliation (not a blind delete) decides what happens next.
     */
    @Transaction
    fun completeRunWithMedia(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        stage: String,
        media: VideoMediaAssetEntity,
        nowMs: Long,
    ): Boolean {
        val rows = transitionRun(
            runId = runId,
            attemptId = attemptId,
            expectedVersion = expectedVersion,
            fromState = fromState,
            toState = "COMPLETED",
            stage = stage,
            step = null,
            stepTotal = null,
            errorCode = null,
            errorDetail = null,
            terminalAt = nowMs,
            nowMs = nowMs,
        )
        if (rows != 1) return false
        attachRunOutput(runId, attemptId, media.mediaId, nowMs)
        updateRunResourceState(runId, attemptId, "RELEASED", nowMs)
        insertMedia(media)
        return true
    }
}

/**
 * DDL + migration for the video tables. Pure SQL, mirroring the @Entity definitions exactly
 * (Room validates the post-migration schema against 30.json at test time).
 */
object VideoDatabaseSchema {

    const val TABLE_REVISIONS = "video_workflow_revisions"
    const val TABLE_RUNS = "video_runs"
    const val TABLE_EVENTS = "video_run_events"
    const val TABLE_MEDIA = "video_media_assets"

    val MIGRATION_29_30: Migration = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$TABLE_REVISIONS` (" +
                    "`revisionId` TEXT NOT NULL, `workflowId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                    "`schemaVersion` INTEGER NOT NULL, `canonicalGraphJson` TEXT NOT NULL, " +
                    "`rawSourceRef` TEXT, `canonicalSha256` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`revisionId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_workflow_revisions_workflowId` ON `$TABLE_REVISIONS` (`workflowId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_workflow_revisions_createdAt` ON `$TABLE_REVISIONS` (`createdAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$TABLE_RUNS` (" +
                    "`runId` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `requestId` TEXT NOT NULL, " +
                    "`stateVersion` INTEGER NOT NULL, `workflowId` TEXT NOT NULL, `revisionId` TEXT NOT NULL, " +
                    "`backend` TEXT NOT NULL, `modelFingerprint` TEXT NOT NULL, `profileFingerprint` TEXT NOT NULL, " +
                    "`snapshotJson` TEXT NOT NULL, `state` TEXT NOT NULL, `resourceState` TEXT NOT NULL, " +
                    "`stage` TEXT NOT NULL, `step` INTEGER, `stepTotal` INTEGER, `errorCode` TEXT, `errorDetail` TEXT, " +
                    "`outputMediaId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `terminalAt` INTEGER, " +
                    "PRIMARY KEY(`runId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_runs_workflowId` ON `$TABLE_RUNS` (`workflowId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_runs_revisionId` ON `$TABLE_RUNS` (`revisionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_runs_state` ON `$TABLE_RUNS` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_runs_updatedAt` ON `$TABLE_RUNS` (`updatedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$TABLE_EVENTS` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, " +
                    "`attemptId` TEXT NOT NULL, `state` TEXT NOT NULL, `stage` TEXT NOT NULL, " +
                    "`message` TEXT, `atMs` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_run_events_runId` ON `$TABLE_EVENTS` (`runId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_run_events_atMs` ON `$TABLE_EVENTS` (`atMs`)")
        }
    }

    /** V1-04: published owned media. Pure additive table; no existing row or column is touched. */
    val MIGRATION_30_31: Migration = object : Migration(30, 31) {        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `$TABLE_MEDIA` (" +
                    "`mediaId` TEXT NOT NULL, `runId` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                    "`relativePath` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, " +
                    "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `durationUs` INTEGER NOT NULL, " +
                    "`fps` INTEGER NOT NULL, `frameCount` INTEGER NOT NULL, `codec` TEXT NOT NULL, " +
                    "`hasAudio` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                    "PRIMARY KEY(`mediaId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_media_assets_runId` ON `$TABLE_MEDIA` (`runId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_media_assets_createdAt` ON `$TABLE_MEDIA` (`createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_video_media_assets_deletedAt` ON `$TABLE_MEDIA` (`deletedAt`)")
        }
    }

    /** V3-01: non-secret remote prompt identity for queue/history reconciliation. */
    val MIGRATION_31_32: Migration = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `$TABLE_RUNS` ADD COLUMN `remoteEndpointId` TEXT")
            db.execSQL("ALTER TABLE `$TABLE_RUNS` ADD COLUMN `remotePromptId` TEXT")
        }
    }
}

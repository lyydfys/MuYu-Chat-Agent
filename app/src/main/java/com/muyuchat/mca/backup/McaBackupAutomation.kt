package com.muyuchat.mca.backup

import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Automation rules shared by automatic backups and configuration snapshots.
 *
 * Everything here is a pure decision (delay, prune list, classification, file name) so the schedules
 * and retention policies are testable without WorkManager, a clock, or a database. The Android layer
 * above only reads these answers and does IO.
 */

/** When an automatic backup runs. */
internal sealed interface McaAutoBackupSchedule {
    val label: String

    /** Every day at [hour]:[minute] local time. */
    data class Daily(val hour: Int, val minute: Int) : McaAutoBackupSchedule {
        init {
            require(hour in 0..23) { "hour out of range: $hour" }
            require(minute in 0..59) { "minute out of range: $minute" }
        }

        override val label: String get() = "每天 %02d:%02d".format(hour, minute)
    }

    /** Every [hours] hours (1..168) counted from the moment the schedule was saved. */
    data class Interval(val hours: Int) : McaAutoBackupSchedule {
        init {
            require(hours in McaAutoBackupRules.MIN_INTERVAL_HOURS..McaAutoBackupRules.MAX_INTERVAL_HOURS) {
                "interval out of range: $hours"
            }
        }

        override val label: String get() = "每隔 $hours 小时"
    }
}

/** One file on disk, as the retention rules see it. */
internal data class McaAutoBackupFile(val name: String, val modifiedAtMillis: Long)

/** Pure scheduling and retention rules for automatic backups. */
internal object McaAutoBackupRules {

    const val KEEP = 3
    const val MIN_INTERVAL_HOURS = 1
    const val MAX_INTERVAL_HOURS = 168
    const val AUTO_DIR = "backup/auto"
    const val FILE_PREFIX = "auto-"

    /** `filesDir/backup/auto/auto-<timestamp>.json`. */
    fun fileName(timestampMillis: Long): String = "$FILE_PREFIX$timestampMillis.json"

    fun autoBackupDir(filesDir: File): File = File(filesDir, AUTO_DIR)

    fun autoBackupFile(filesDir: File, timestampMillis: Long): File =
        File(autoBackupDir(filesDir), fileName(timestampMillis))

    /**
     * Milliseconds until the next run.
     *
     * Daily: the next occurrence of the wall-clock time in [zone]; a time that already passed today
     * means tomorrow. Interval: one full period, because the period is anchored at the save time.
     * Both answers are strictly positive so a scheduler never busy-loops.
     */
    fun nextAutoBackupDelayMillis(
        nowMillis: Long,
        schedule: McaAutoBackupSchedule,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = when (schedule) {
        is McaAutoBackupSchedule.Daily -> {
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val target = LocalTime.of(schedule.hour, schedule.minute)
            var next = ZonedDateTime.of(now.toLocalDate(), target, zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            (next.toInstant().toEpochMilli() - nowMillis).coerceAtLeast(1L)
        }

        is McaAutoBackupSchedule.Interval -> schedule.hours * 60L * 60L * 1000L
    }

    /**
     * Files to delete so that only the newest [keep] survive.
     *
     * Ties on `modifiedAtMillis` are broken by name so the answer is deterministic; a non-positive
     * [keep] prunes everything (the caller may use it to clear the directory).
     */
    fun filesToPrune(candidates: List<McaAutoBackupFile>, keep: Int = KEEP): List<String> {
        if (keep <= 0) return candidates.map { it.name }
        if (candidates.size <= keep) return emptyList()
        val ordered = candidates.sortedWith(
            compareByDescending<McaAutoBackupFile> { it.modifiedAtMillis }.thenByDescending { it.name }
        )
        return ordered.drop(keep).map { it.name }
    }
}

/**
 * What one session row meant to the loader.
 *
 * A single unreadable session must never fail the whole history: it is classified here, moved to the
 * quarantine directory by the caller, and shown as a readable placeholder row.
 */
internal enum class McaSessionLoadStatus {
    OK,
    CORRUPT_QUARANTINED
}

/** Result of classifying one session load attempt. */
internal data class McaSessionLoadOutcome(
    val sessionId: String,
    val status: McaSessionLoadStatus,
    /** Relative quarantine path when the row was isolated; null for a healthy row. */
    val quarantinePath: String?,
    /** Why it was isolated, or null when the quarantine copy could not be written. */
    val reason: String?
) {
    val isCorrupt: Boolean get() = status == McaSessionLoadStatus.CORRUPT_QUARANTINED
}

internal object McaSessionCorruption {

    const val CORRUPT_DIR = "backup/corrupt-backup"

    /** Shown instead of throwing or printing an id. */
    const val CORRUPT_LABEL = "已损坏（已隔离，可在备份目录取回）"

    fun classifySessionLoad(load: Result<*>): McaSessionLoadStatus =
        if (load.isSuccess) McaSessionLoadStatus.OK else McaSessionLoadStatus.CORRUPT_QUARANTINED

    fun quarantineFileName(sessionId: String, timestampMillis: Long): String =
        "${sessionId.takeIf { it.isNotBlank() } ?: "unknown"}-$timestampMillis.json"

    fun quarantineDir(filesDir: File): File = File(filesDir, CORRUPT_DIR)

    fun quarantineFile(filesDir: File, sessionId: String, timestampMillis: Long): File =
        File(quarantineDir(filesDir), quarantineFileName(sessionId, timestampMillis))

    /** Title used by the session list; the original title is kept as the detail, never the id. */
    fun displayTitle(originalTitle: String?): String =
        originalTitle?.trim()?.takeIf { it.isNotBlank() }?.let { "$it（$CORRUPT_LABEL）" } ?: CORRUPT_LABEL
}

/** Which retention bucket a configuration snapshot belongs to. */
internal enum class McaConfigSnapshotKind(val wireName: String, val label: String) {
    /** Taken before a routine configuration change. */
    HEALTHY("healthy", "变更前"),

    /** Taken right before a repair/restore, so the pre-repair state is recoverable. */
    PRE_REPAIR("pre-repair", "修复前");

    companion object {
        fun fromWireName(raw: String?): McaConfigSnapshotKind =
            entries.firstOrNull { it.wireName.equals(raw?.trim(), ignoreCase = true) } ?: HEALTHY
    }
}

/** One configuration snapshot on disk. */
internal data class McaConfigSnapshot(
    val name: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val kind: McaConfigSnapshotKind,
    /** Short human summary, e.g. "全量 · 12 表 / 980 行". */
    val summary: String
)

internal object McaConfigSnapshots {

    const val DIR = "backup/snapshots"
    const val KEEP_HEALTHY = 3
    const val KEEP_PRE_REPAIR = 3

    fun dir(filesDir: File): File = File(filesDir, DIR)

    fun fileName(kind: McaConfigSnapshotKind, timestampMillis: Long, scope: McaBackupScope): String =
        "snapshot-${kind.wireName}-${scope.wireName}-$timestampMillis.json"

    fun kindOf(name: String): McaConfigSnapshotKind = when {
        name.contains("-${McaConfigSnapshotKind.PRE_REPAIR.wireName}-") -> McaConfigSnapshotKind.PRE_REPAIR
        else -> McaConfigSnapshotKind.HEALTHY
    }

    /** Files to delete so each bucket keeps its own last N, independent of the other bucket. */
    fun filesToPrune(
        candidates: List<McaConfigSnapshot>,
        keepHealthy: Int = KEEP_HEALTHY,
        keepPreRepair: Int = KEEP_PRE_REPAIR
    ): List<String> {
        val byKind = candidates.groupBy { it.kind }
        return buildList {
            byKind.forEach { (kind, snapshots) ->
                val keep = if (kind == McaConfigSnapshotKind.PRE_REPAIR) keepPreRepair else keepHealthy
                val ordered = snapshots.sortedWith(
                    compareByDescending<McaConfigSnapshot> { it.createdAtMillis }.thenByDescending { it.name }
                )
                addAll(ordered.drop(keep.coerceAtLeast(0)).map { it.name })
            }
        }
    }

    /** The snapshot the "从快照恢复" list should offer first: newest healthy, else newest pre-repair. */
    fun selectForRestore(candidates: List<McaConfigSnapshot>): McaConfigSnapshot? =
        candidates.filter { it.kind == McaConfigSnapshotKind.HEALTHY }
            .maxByOrNull { it.createdAtMillis }
            ?: candidates.maxByOrNull { it.createdAtMillis }

    /** "2026-11-05 03:20 · 128 KB · 全量 · 12 表 / 980 行". */
    fun describe(snapshot: McaConfigSnapshot, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(snapshot.createdAtMillis).atZone(zone))
        return "$stamp · ${formatBytes(snapshot.sizeBytes)} · ${snapshot.kind.label} · ${snapshot.summary}"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

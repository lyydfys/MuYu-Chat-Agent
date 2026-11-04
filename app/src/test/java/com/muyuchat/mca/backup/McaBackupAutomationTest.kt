package com.muyuchat.mca.backup

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the automation rules: when the next automatic backup runs, which files are pruned, how a
 * corrupt session is isolated, and how configuration snapshots are named/kept/selected.
 *
 * All pure decisions, no WorkManager and no clock of their own.
 */
class McaBackupAutomationTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val filesDir = File("/data/user/0/com.muyuchat.mca/files")

    @Test
    fun `a daily schedule later today waits until that time`() {
        val now = at(2026, 11, 5, 9, 0)

        val delay = McaAutoBackupRules.nextAutoBackupDelayMillis(now, McaAutoBackupSchedule.Daily(18, 30), zone)

        assertEquals((9 * 60 + 30) * 60_000L, delay)
    }

    @Test
    fun `a daily schedule that already passed waits until tomorrow`() {
        val now = at(2026, 11, 5, 22, 0)

        val delay = McaAutoBackupRules.nextAutoBackupDelayMillis(now, McaAutoBackupSchedule.Daily(3, 0), zone)

        assertEquals(5 * 60L * 60_000L, delay)
    }

    @Test
    fun `a daily schedule exactly now still returns a positive delay`() {
        val now = at(2026, 11, 5, 3, 0)

        val delay = McaAutoBackupRules.nextAutoBackupDelayMillis(now, McaAutoBackupSchedule.Daily(3, 0), zone)

        assertEquals(24 * 60L * 60_000L, delay)
        assertTrue("a scheduler must never receive a zero delay", delay > 0)
    }

    @Test
    fun `interval schedules return one full period`() {
        val now = at(2026, 11, 5, 3, 0)

        assertEquals(
            60L * 60_000L,
            McaAutoBackupRules.nextAutoBackupDelayMillis(now, McaAutoBackupSchedule.Interval(1), zone)
        )
        assertEquals(
            168L * 60L * 60_000L,
            McaAutoBackupRules.nextAutoBackupDelayMillis(now, McaAutoBackupSchedule.Interval(168), zone)
        )
    }

    @Test
    fun `out of range schedules are refused at construction`() {
        assertTrue(runCatching { McaAutoBackupSchedule.Daily(24, 0) }.isFailure)
        assertTrue(runCatching { McaAutoBackupSchedule.Daily(0, 60) }.isFailure)
        assertTrue(runCatching { McaAutoBackupSchedule.Interval(0) }.isFailure)
        assertTrue(runCatching { McaAutoBackupSchedule.Interval(169) }.isFailure)
        assertEquals("每天 03:05", McaAutoBackupSchedule.Daily(3, 5).label)
        assertEquals("每隔 12 小时", McaAutoBackupSchedule.Interval(12).label)
    }

    @Test
    fun `retention keeps the newest three auto backups`() {
        val files = (1..5).map { McaAutoBackupFile("auto-$it.json", it.toLong()) }

        val pruned = McaAutoBackupRules.filesToPrune(files)

        assertEquals(listOf("auto-2.json", "auto-1.json"), pruned)
    }

    @Test
    fun `retention never prunes when there is nothing to spare`() {
        assertTrue(McaAutoBackupRules.filesToPrune(emptyList()).isEmpty())
        assertTrue(
            McaAutoBackupRules.filesToPrune((1..3).map { McaAutoBackupFile("auto-$it.json", it.toLong()) }).isEmpty()
        )
        assertEquals(
            listOf("auto-1.json", "auto-2.json", "auto-3.json"),
            McaAutoBackupRules.filesToPrune((1..3).map { McaAutoBackupFile("auto-$it.json", it.toLong()) }, keep = 0)
        )
    }

    @Test
    fun `auto backup paths are stable and live under backup auto`() {
        val file = McaAutoBackupRules.autoBackupFile(filesDir, 1_700_000_000_000L)

        assertEquals("auto-1700000000000.json", file.name)
        assertTrue(file.parentFile!!.path.endsWith("backup${File.separator}auto"))
        assertEquals(McaAutoBackupRules.AUTO_DIR, McaAutoBackupRules.AUTO_DIR)
    }

    @Test
    fun `one unreadable session is isolated while the others stay readable`() {
        val loads = listOf(
            "s1" to Result.success("ok"),
            "s2" to Result.failure(IllegalStateException("bad json")),
            "s3" to Result.success("ok")
        )

        val statuses = loads.map { (id, result) -> id to McaSessionCorruption.classifySessionLoad(result) }

        assertEquals(McaSessionLoadStatus.OK, statuses[0].second)
        assertEquals(McaSessionLoadStatus.CORRUPT_QUARANTINED, statuses[1].second)
        assertEquals(McaSessionLoadStatus.OK, statuses[2].second)
    }

    @Test
    fun `every session failing is still classified instead of throwing`() {
        val statuses = listOf("a", "b").map {
            McaSessionCorruption.classifySessionLoad(Result.failure<Unit>(IllegalArgumentException("corrupt")))
        }

        assertTrue(statuses.all { it == McaSessionLoadStatus.CORRUPT_QUARANTINED })
        assertTrue(McaSessionCorruption.CORRUPT_LABEL.contains("已隔离"))
        assertFalse(McaSessionCorruption.CORRUPT_LABEL.contains("{"))
    }

    @Test
    fun `the quarantine path names the session and the moment`() {
        val file = McaSessionCorruption.quarantineFile(filesDir, "session-1", 1_700_000_000_000L)

        assertEquals("session-1-1700000000000.json", file.name)
        assertTrue(file.parentFile!!.path.endsWith("backup${File.separator}corrupt-backup"))
        assertEquals("unknown-1.json", McaSessionCorruption.quarantineFileName("", 1L))
        // A missing title still produces a readable row instead of an id.
        assertTrue(McaSessionCorruption.displayTitle(null).contains(McaSessionCorruption.CORRUPT_LABEL))
        assertTrue(McaSessionCorruption.displayTitle("旧对话").startsWith("旧对话（"))
    }

    @Test
    fun `snapshot names carry the kind and the scope`() {
        val name = McaConfigSnapshots.fileName(McaConfigSnapshotKind.PRE_REPAIR, 42L, McaBackupScope.CHAT)

        assertEquals("snapshot-pre-repair-chat-42.json", name)
        assertEquals(McaConfigSnapshotKind.PRE_REPAIR, McaConfigSnapshots.kindOf(name))
        assertEquals(
            McaConfigSnapshotKind.HEALTHY,
            McaConfigSnapshots.kindOf("snapshot-healthy-full-7.json")
        )
        assertEquals(
            McaConfigSnapshotKind.HEALTHY,
            McaConfigSnapshots.kindOf("unexpected-name.json")
        )
    }

    @Test
    fun `snapshot retention keeps three of each bucket`() {
        val healthy = (1..5).map { snapshot("healthy-$it", it.toLong(), McaConfigSnapshotKind.HEALTHY) }
        val preRepair = (1..5).map { snapshot("repair-$it", it.toLong(), McaConfigSnapshotKind.PRE_REPAIR) }

        val pruned = McaConfigSnapshots.filesToPrune(healthy + preRepair)

        assertEquals(4, pruned.size)
        assertTrue(pruned.containsAll(listOf("healthy-2", "healthy-1", "repair-2", "repair-1")))
        assertFalse(pruned.contains("healthy-5"))
        assertFalse(pruned.contains("repair-5"))
    }

    @Test
    fun `restore selection prefers the newest healthy snapshot`() {
        val healthy = listOf(snapshot("healthy-a", 10L, McaConfigSnapshotKind.HEALTHY))
        val preRepair = listOf(snapshot("repair-a", 99L, McaConfigSnapshotKind.PRE_REPAIR))

        assertEquals("healthy-a", McaConfigSnapshots.selectForRestore(healthy + preRepair)?.name)
        assertEquals(
            "repair-a",
            McaConfigSnapshots.selectForRestore(preRepair)?.name
        )
        assertNull(McaConfigSnapshots.selectForRestore(emptyList()))
    }

    @Test
    fun `the snapshot row shows time size kind and summary`() {
        val row = McaConfigSnapshots.describe(
            McaConfigSnapshot(
                name = "snapshot-healthy-full-1.json",
                createdAtMillis = 1_792_303_500_000L,
                sizeBytes = 131_072L,
                kind = McaConfigSnapshotKind.HEALTHY,
                summary = "全量 · 12 表 / 980 行"
            ),
            zone
        )

        assertNotNull(row)
        assertTrue(row, row.contains("2026-10-18"))
        assertTrue(row, row.contains("128.0 KB"))
        assertTrue(row, row.contains("变更前"))
        assertTrue(row, row.contains("全量 · 12 表 / 980 行"))
    }

    private fun snapshot(name: String, createdAt: Long, kind: McaConfigSnapshotKind) =
        McaConfigSnapshot(name = name, createdAtMillis = createdAt, sizeBytes = 1L, kind = kind, summary = "s")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}

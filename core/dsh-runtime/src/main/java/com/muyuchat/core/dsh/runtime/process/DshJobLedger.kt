package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.runtime.payload.DshJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Lifecycle of one observed job process, as recorded by the ledger. */
enum class DshJobStatus(val wire: String) {
    /** Seen alive with the generation token; it may still be running. */
    OBSERVED("observed"),

    /** A later generation proved the identity and settled the process tree. */
    REAPED("reaped"),

    /** The census proved the process was gone while its host generation was still alive. */
    EXITED("exited"),

    /** Recovery refused to signal: at least one identity fact no longer matched. */
    SKIPPED("skipped");

    companion object {
        fun fromWire(value: String?): DshJobStatus? = DshJobStatus.entries.firstOrNull { it.wire == value }
    }
}

/** One durable job-process record. Later lines for the same task id supersede earlier ones. */
data class DshJobRecord(
    val taskId: String,
    val generation: Long,
    val hostPid: Int,
    val pid: Int,
    val startTime: Long,
    val cmdlineHash: String,
    val command: String?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val status: DshJobStatus
)

/**
 * Bounded read model for the console/status surface.
 *
 * It says only what MCA itself observed. The official `job_list` registry is host memory, so an
 * orphan here is a process, not a reconstructed job record.
 */
data class DshJobOrphan(
    val taskId: String,
    val generation: Long,
    val command: String?,
    val firstSeenMs: Long
)

/**
 * Append-only JSONL ledger of job processes, one file per host generation.
 *
 * **Single writer:** the `:dsh` service (through the owner's job-survival collaborator) is the
 * only writer of a ledger directory; the app process must never append to it. Appends are
 * serialized by an in-process lock, written as one `O_APPEND` write and synced, so a `kill -9`
 * cannot tear a line (a power loss may lose only the last few lines, which recovery treats as
 * "not observed" instead of guessing).
 *
 * **Bounded:** `jobs-<generation>.jsonl` is the rotation unit. A file is compacted to one line per
 * task id when it reaches [maxLinesPerFile], and resolved generation files beyond
 * [maxGenerationFiles] are pruned. A generation file that still holds an `observed` record is
 * never pruned: its evidence is the only honest trace of an unresolved process.
 *
 * **Idempotent:** re-observing an unchanged identity inside [lastSeenRefreshMillis] writes
 * nothing; a repeated status is a no-op. Readers fold by task id with last line wins.
 */
internal class DshJobLedger(
    private val directory: File,
    private val maxLinesPerFile: Int = MAX_LINES_PER_FILE,
    private val maxGenerationFiles: Int = MAX_GENERATION_FILES,
    private val lastSeenRefreshMillis: Long = LAST_SEEN_REFRESH_MS
) {
    private val lock = ReentrantLock()
    private val recordsByTask = LinkedHashMap<String, DshJobRecord>()
    private val lineCounts = HashMap<Long, Int>()
    private var loaded = false

    /**
     * Records one live sighting. Creates the task record on first sight, refreshes `lastSeenMs`
     * at most once per [lastSeenRefreshMillis], and reopens a resolved record only if the same
     * process identity is observed alive again (which a recycled PID cannot fake).
     */
    fun observe(
        taskId: String,
        generation: Long,
        hostPid: Int,
        pid: Int,
        startTime: Long,
        cmdlineHash: String,
        command: String?,
        nowMs: Long
    ): Boolean = lock.withLock {
        ensureLoadedLocked()
        val existing = recordsByTask[taskId]
        val next = DshJobRecord(
            taskId = taskId,
            generation = generation,
            hostPid = hostPid,
            pid = pid,
            startTime = startTime,
            cmdlineHash = cmdlineHash,
            command = command,
            firstSeenMs = existing?.firstSeenMs ?: nowMs,
            lastSeenMs = nowMs,
            status = DshJobStatus.OBSERVED
        )
        if (existing != null &&
            existing.status == DshJobStatus.OBSERVED &&
            existing.generation == generation &&
            existing.startTime == startTime &&
            existing.cmdlineHash == cmdlineHash &&
            existing.command == command &&
            nowMs - existing.lastSeenMs < lastSeenRefreshMillis
        ) {
            return@withLock true
        }
        appendLocked(next)
    }

    /** Applies a terminal/refusal status to a record this ledger already holds. */
    fun mark(taskId: String, status: DshJobStatus, nowMs: Long): Boolean = lock.withLock {
        ensureLoadedLocked()
        val existing = recordsByTask[taskId] ?: return@withLock false
        if (existing.status == status) return@withLock true
        appendLocked(existing.copy(status = status, lastSeenMs = nowMs))
    }

    /** All known records, folded to the latest line per task id. */
    fun records(): List<DshJobRecord> = lock.withLock {
        ensureLoadedLocked()
        recordsByTask.values.sortedWith(compareBy({ it.firstSeenMs }, { it.taskId }))
    }

    /** `observed` records from a strictly older generation, oldest sighting first. */
    fun observedBefore(generation: Long, limit: Int = MAX_RECOVERY_CANDIDATES): List<DshJobRecord> =
        records()
            .filter { it.status == DshJobStatus.OBSERVED && it.generation < generation }
            .sortedBy { it.firstSeenMs }
            .take(limit)

    /** Newest unresolved processes, bounded. Nothing is reconstructed beyond these facts. */
    fun orphans(limit: Int = MAX_ORPHANS): List<DshJobOrphan> =
        records()
            .asSequence()
            .filter { it.status == DshJobStatus.OBSERVED }
            .sortedByDescending { it.firstSeenMs }
            .take(limit)
            .map { DshJobOrphan(it.taskId, it.generation, it.command, it.firstSeenMs) }
            .toList()

    // ------------------------------------------------------------------ persistence

    private fun appendLocked(record: DshJobRecord): Boolean {
        val generation = record.generation
        if ((lineCounts[generation] ?: 0) >= maxLinesPerFile) {
            compactLocked(generation)
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        val file = generationFile(generation)
        val line = encode(record) + "\n"
        return try {
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            lineCounts[generation] = (lineCounts[generation] ?: 0) + 1
            recordsByTask[record.taskId] = record
            true
        } catch (t: Throwable) {
            // A failed append must not end the census; the next poll retries the same record.
            false
        }
    }

    /** Rewrites one generation file as one line per task id, atomically. */
    private fun compactLocked(generation: Long) {
        if (!directory.isDirectory) return
        val file = generationFile(generation)
        val latest = recordsByTask.values.filter { it.generation == generation }
        val temp = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temp, false).use { out ->
                for (record in latest) {
                    out.write((encode(record) + "\n").toByteArray(Charsets.UTF_8))
                }
                out.flush()
                out.fd.sync()
            }
            moveReplacing(temp, file)
            lineCounts[generation] = latest.size
        } catch (t: Throwable) {
            temp.delete()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val files = directory.listFiles() ?: return
        for (file in files) {
            val generation = generationOf(file) ?: continue
            var lines = 0
            try {
                file.forEachLine { raw ->
                    lines++
                    val text = raw.trim()
                    if (text.isEmpty()) return@forEachLine
                    val record = parseLine(text) ?: return@forEachLine
                    recordsByTask[record.taskId] = record
                }
            } catch (t: Throwable) {
                // Keep the lines read so far; an unreadable tail is evidence of nothing.
            }
            lineCounts[generation] = lines
        }
        pruneLocked()
    }

    /**
     * Deletes resolved generation files beyond [maxGenerationFiles], oldest generation first.
     * A file with an unresolved `observed` record is kept regardless of age.
     */
    private fun pruneLocked() {
        val generations = lineCounts.keys.sorted()
        if (generations.size <= maxGenerationFiles) return
        val unresolvedGenerations = recordsByTask.values
            .filter { it.status == DshJobStatus.OBSERVED }
            .map { it.generation }
            .toSet()
        for (generation in generations.dropLast(maxGenerationFiles)) {
            if (generation in unresolvedGenerations) continue
            generationFile(generation).delete()
            lineCounts.remove(generation)
        }
    }

    private fun generationFile(generation: Long): File = File(directory, "$FILE_PREFIX$generation$FILE_SUFFIX")

    private fun generationOf(file: File): Long? {
        if (!file.isFile) return null
        val name = file.name
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        return name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull()
    }

    private fun encode(record: DshJobRecord): String = DshJson.write(
        linkedMapOf(
            "taskId" to record.taskId,
            "generation" to record.generation,
            "hostPid" to record.hostPid,
            "pid" to record.pid,
            "startTime" to record.startTime,
            "cmdlineHash" to record.cmdlineHash,
            "command" to record.command,
            "firstSeenMs" to record.firstSeenMs,
            "lastSeenMs" to record.lastSeenMs,
            "status" to record.status.wire
        )
    )

    /** Malformed lines are skipped; they are never interpreted as a partial identity. */
    private fun parseLine(line: String): DshJobRecord? {
        return try {
            val map = DshJson.asObject(DshJson.parse(line), "job record")
            val status = DshJobStatus.fromWire(DshJson.optString(map, "status"))
            if (status == null) {
                null
            } else {
                DshJobRecord(
                    taskId = DshJson.asString(map, "taskId", "job record"),
                    generation = DshJson.asLong(map, "generation", "job record"),
                    hostPid = DshJson.asInt(map, "hostPid", "job record"),
                    pid = DshJson.asInt(map, "pid", "job record"),
                    startTime = DshJson.asLong(map, "startTime", "job record"),
                    cmdlineHash = DshJson.asString(map, "cmdlineHash", "job record"),
                    command = DshJson.optString(map, "command"),
                    firstSeenMs = DshJson.asLong(map, "firstSeenMs", "job record"),
                    lastSeenMs = DshJson.asLong(map, "lastSeenMs", "job record"),
                    status = status
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (t: Throwable) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_PREFIX = "jobs-"
        const val FILE_SUFFIX = ".jsonl"
        const val MAX_LINES_PER_FILE = 4_000
        const val MAX_GENERATION_FILES = 6
        const val LAST_SEEN_REFRESH_MS = 30_000L
        const val MAX_RECOVERY_CANDIDATES = 32
        const val MAX_ORPHANS = 32
    }
}

package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Journal phases. Every transition is forced to disk before the module reports it, so a crash
 * always leaves one of these explicit states rather than an implied one.
 *
 * Terminal phases: [COMMITTED], [ABORTED], [ROLLED_BACK], [RECOVERY_REQUIRED]. A recovery sweep
 * only ever acts on the non-terminal ones.
 */
enum class DshMaintenancePhase {
    PREPARED,
    QUIESCED,
    SNAPSHOT_READY,
    APPLYING,
    HEALTH_PENDING,
    COMMITTED,
    ABORTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED;

    val isTerminal: Boolean
        get() = this == COMMITTED || this == ABORTED || this == ROLLED_BACK || this == RECOVERY_REQUIRED
}

/** One physical step of a transaction. Paths are absolute app-private paths (never user content). */
enum class DshStepAction { INSTALL_FILE, INSTALL_TREE, MOVE_ASIDE, RELOCATE }

data class DshJournalStepRecord(
    val index: Int,
    val action: DshStepAction,
    val targetPath: String,
    val stagingPath: String?,
    val expectedSha256: String?,
    val backupPath: String,
    val hadTarget: Boolean,
    val applied: Boolean,
    /** Destination for RELOCATE (session recycle). */
    val destinationPath: String? = null
)

/** Latest durable state of one transaction. The journal file holds one such line per transition. */
data class DshJournalState(
    val seq: Int,
    val transactionId: String,
    val kind: DshMaintenanceKind,
    val phase: DshMaintenancePhase,
    val atMillis: Long,
    val owner: String,
    val operationId: String,
    val admissionProof: String,
    val targetRoot: String,
    val restorePoint: String?,
    val steps: List<DshJournalStepRecord>,
    val detail: String?
)

/**
 * Append-only, forced journal store below `dataRoot/maintenance/journal`.
 *
 * Each line is a complete state snapshot; the last decodable line wins. A torn final line
 * (power loss during append) is ignored, which is why a phase can never be "half reported".
 */
internal class DshMaintenanceJournalStore(dataRoot: File) {

    val maintenanceRoot: File = File(dataRoot, "maintenance")
    val journalDir: File = File(maintenanceRoot, "journal")
    val workRoot: File = File(maintenanceRoot, "work")
    val restorePointRoot: File = File(maintenanceRoot, "restore-points")

    fun journalFile(transactionId: String): File = File(journalDir, "$transactionId.jsonl")

    fun append(state: DshJournalState) {
        val file = journalFile(state.transactionId)
        if (!file.parentFile.isDirectory && !file.parentFile.mkdirs() && !file.parentFile.isDirectory) {
            throw java.io.IOException("cannot create maintenance journal directory")
        }
        val line = encode(state) + "\n"
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        DshMaintenanceIo.forceDir(file.parentFile)
    }

    /** Latest decodable state for one transaction, or null when no journal exists yet. */
    fun read(transactionId: String): DshJournalState? {
        val file = journalFile(transactionId)
        if (!file.isFile) return null
        var latest: DshJournalState? = null
        file.forEachLine { line ->
            if (line.isNotBlank()) decode(line)?.let { latest = it }
        }
        return latest
    }

    /**
     * All latest states, newest transaction first.
     *
     * An unreadable journal directory is **not** "no transactions": the caller would then start a
     * host on top of an unknown transaction state. [readJournalStates] therefore throws there and
     * the startup sweep turns it into `Blocked`; [allOrEmpty] keeps the tolerant read for display
     * and cleanup paths that must never crash a projection.
     */
    fun all(): List<DshJournalState> = readJournalStates(
        listFiles = { journalDir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") } },
        directoryExists = { journalDir.exists() },
        readOne = { file -> read(file.name.removeSuffix(".jsonl")) }
    )

    /** Display/cleanup read: an unreadable directory degrades to an empty list. */
    fun allOrEmpty(): List<DshJournalState> =
        runCatching { all() }.getOrDefault(emptyList())

    fun pending(): List<DshJournalState> = all().filter { !it.phase.isTerminal }

    /** Removes the journal of a terminal transaction older than the retention window. */
    fun deleteJournal(transactionId: String): Boolean = journalFile(transactionId).delete()

    private fun encode(state: DshJournalState): String {
        val json = JSONObject()
        json.put("v", 1)
        json.put("seq", state.seq)
        json.put("txn", state.transactionId)
        json.put("kind", state.kind.name)
        json.put("phase", state.phase.name)
        json.put("at", state.atMillis)
        json.put("owner", state.owner)
        json.put("operation", state.operationId)
        json.put("admission", state.admissionProof)
        json.put("targetRoot", state.targetRoot)
        if (state.restorePoint != null) json.put("restorePoint", state.restorePoint)
        json.put("detail", state.detail ?: "")
        val steps = JSONArray()
        for (step in state.steps) {
            steps.put(
                JSONObject().apply {
                    put("index", step.index)
                    put("action", step.action.name)
                    put("target", step.targetPath)
                    if (step.stagingPath != null) put("staging", step.stagingPath)
                    if (step.expectedSha256 != null) put("sha256", step.expectedSha256)
                    put("backup", step.backupPath)
                    put("hadTarget", step.hadTarget)
                    put("applied", step.applied)
                    if (step.destinationPath != null) put("destination", step.destinationPath)
                }
            )
        }
        json.put("steps", steps)
        return json.toString()
    }

    private fun decode(line: String): DshJournalState? = runCatching {
        val json = JSONObject(line)
        if (json.optInt("v", -1) != 1) return null
        val rawSteps = json.optJSONArray("steps") ?: JSONArray()
        val steps = ArrayList<DshJournalStepRecord>(rawSteps.length())
        for (index in 0 until rawSteps.length()) {
            val step = rawSteps.optJSONObject(index) ?: return null
            val action = DshStepAction.entries.firstOrNull { it.name == step.optString("action") } ?: return null
            steps.add(
                DshJournalStepRecord(
                    index = step.optInt("index"),
                    action = action,
                    targetPath = step.optString("target"),
                    stagingPath = step.optString("staging").takeIf { it.isNotEmpty() },
                    expectedSha256 = step.optString("sha256").takeIf { it.isNotEmpty() },
                    backupPath = step.optString("backup"),
                    hadTarget = step.optBoolean("hadTarget"),
                    applied = step.optBoolean("applied"),
                    destinationPath = step.optString("destination").takeIf { it.isNotEmpty() }
                )
            )
        }
        val kind = DshMaintenanceKind.entries.firstOrNull { it.name == json.optString("kind") } ?: return null
        val phase = DshMaintenancePhase.entries.firstOrNull { it.name == json.optString("phase") } ?: return null
        DshJournalState(
            seq = json.optInt("seq"),
            transactionId = json.optString("txn"),
            kind = kind,
            phase = phase,
            atMillis = json.optLong("at"),
            owner = json.optString("owner"),
            operationId = json.optString("operation"),
            admissionProof = json.optString("admission"),
            targetRoot = json.optString("targetRoot"),
            restorePoint = json.optString("restorePoint").takeIf { it.isNotEmpty() },
            steps = steps,
            detail = json.optString("detail").takeIf { it.isNotEmpty() }
        )
    }.getOrNull()
}

/**
 * Reads one journal directory. [listFiles] is the platform listing (null means "could not list");
 * a directory that exists but cannot be listed is an unproven state and raises.
 */
internal fun readJournalStates(
    listFiles: () -> Array<File>?,
    directoryExists: () -> Boolean,
    readOne: (File) -> DshJournalState?
): List<DshJournalState> {
    val files = listFiles() ?: run {
        if (directoryExists()) throw java.io.IOException("the maintenance journal directory is unreadable")
        return emptyList()
    }
    val out = ArrayList<DshJournalState>()
    for (file in files) {
        readOne(file)?.let(out::add)
    }
    return out.sortedByDescending { it.atMillis }
}

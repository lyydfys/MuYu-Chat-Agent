package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import org.json.JSONObject

/**
 * One durable trial record, as written by the app-side `DshClosureTrialHost`.
 *
 * **Single writer:** `:core:dsh-maintenance` owns `files/dsh/trial/active.json` and writes it
 * before/after every trial transition. The runtime owner only *reads* it, and only to answer the
 * one question its own memory cannot answer: after a process death, does a trial process still
 * exist that this owner must release before production may start?
 */
internal data class DshTrialJournalRecord(
    val phase: String,
    val candidateFingerprint: String,
    val candidateRoot: String,
    val homeRoot: String,
    val workspaceRoot: String,
    val tmpRoot: String,
    val pid: Long,
    val generation: Long,
    /** The file exists but could not be parsed; callers must fail closed. */
    val unreadable: Boolean = false
) {
    /** True when the journal does not positively prove that no trial process can exist. */
    val mayHoldProcess: Boolean
        get() = unreadable || phase in PHASES_THAT_MAY_HOLD_A_PROCESS

    private companion object {
        val PHASES_THAT_MAY_HOLD_A_PROCESS = setOf("LAUNCHING", "RUNNING", "STOPPING", "RECOVERY_REQUIRED")
    }
}

/**
 * Read-only view of the app-owned trial journal.
 *
 * A missing file is positive evidence that no trial was ever launched (the app writes LAUNCHING
 * before it calls the runtime); an unreadable file is evidence of nothing and must stay UNKNOWN.
 */
internal class DshTrialJournal(private val file: File) {

    fun read(): DshTrialJournalRecord? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val phase = json.optString("phase")
            DshTrialJournalRecord(
                phase = phase,
                candidateFingerprint = json.optString("candidateFingerprint"),
                candidateRoot = json.optString("candidateRoot"),
                homeRoot = json.optString("homeRoot"),
                workspaceRoot = json.optString("workspaceRoot"),
                tmpRoot = json.optString("tmpRoot"),
                pid = json.optLong("pid", 0L),
                generation = json.optLong("generation", 0L)
            )
        }.getOrElse {
            DshTrialJournalRecord(
                phase = "",
                candidateFingerprint = "",
                candidateRoot = "",
                homeRoot = "",
                workspaceRoot = "",
                tmpRoot = "",
                pid = 0L,
                generation = 0L,
                unreadable = true
            )
        }
    }
}

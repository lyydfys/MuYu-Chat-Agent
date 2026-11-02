package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * SIGTERM 15. Escalation to [SIGKILL] happens only after the configured grace budget.
 */
internal const val TRIAL_SIGTERM = 15

/** SIGKILL 9. */
internal const val TRIAL_SIGKILL = 9

/**
 * Reads `/proc/<pid>/cmdline` and returns it as one space-separated line, or null when unreadable.
 *
 * The command line is the only way to attribute a journaled PID to *this* installation after the
 * owner process died: a PID can be recycled, and killing on a bare number would be wrong. Returns
 * null (fail closed) on any platform without `/proc` or without permission.
 */
internal fun readProcCommandLine(pid: Int): String? {
    if (pid <= 0) return null
    val file = File("/proc/$pid/cmdline")
    if (!file.isFile) return null
    return runCatching {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8).replace('\u0000', ' ').trim()
        if (text.length > MAX_COMMAND_LINE_CHARS) text.take(MAX_COMMAND_LINE_CHARS) else text
    }.getOrNull()
}

private const val MAX_COMMAND_LINE_CHARS = 8_192

/**
 * Releases a trial process that outlived its owner process, using only facts that survive a
 * process death: the app-owned durable journal, `/proc/<pid>/cmdline` attribution and the bounded
 * same-UID payload scan.
 *
 * Order is fixed and non-negotiable:
 * 1. a journaled PID is only signalled after its command line still references the verified
 *    payload tree or the packaged Node library (same-UID attribution);
 * 2. SIGTERM, wait the configured grace budget, then SIGKILL and wait the kill budget;
 * 3. STOPPED requires the PID to be gone **and** the payload scan to be empty. Anything less is
 *    `Survived`/`Unverified`, never a stop claim.
 */
internal class DshTrialRecovery(
    private val ioDispatcher: CoroutineDispatcher,
    private val isAlive: (Int) -> Boolean,
    private val commandLine: (Int) -> String?,
    private val signal: (Int, Int) -> Boolean,
    private val scan: () -> List<Int>?,
    private val needles: List<String>,
    private val pollMillis: Long,
    private val stopGraceMillis: Long,
    private val stopKillMillis: Long,
    private val clock: () -> Long,
    private val log: (String) -> Unit
) {

    /** True only when the process command line still references this installation's payload. */
    fun commandLineMatches(pid: Int): Boolean = matchesNeedles(pid, needles)

    private fun matchesNeedles(pid: Int, candidates: List<String>): Boolean {
        val needlesPresent = candidates.filter { it.isNotBlank() }
        if (needlesPresent.isEmpty()) return false
        val line = commandLine(pid)?.takeIf { it.isNotBlank() } ?: return false
        return needlesPresent.any { line.contains(it) }
    }

    /**
     * Needles that attribute a journaled PID to this installation after a process death.
     *
     * A trial launched from the staged candidate references the candidate payload tree, not the
     * installed one, so the journal's candidate root contributes its own payload path. The packaged
     * Node library remains a shared needle for both generations.
     */
    private fun attributionNeedles(record: DshTrialJournalRecord): List<String> {
        val extra = record.candidateRoot.trim().takeIf { it.isNotEmpty() }
            ?.let { File(it, DshTrialRoots.CANDIDATE_PAYLOAD_DIR_NAME).absolutePath }
        return if (extra == null) needles else needles + extra
    }

    suspend fun release(record: DshTrialJournalRecord): DshTrialStopPolicy.Verdict =
        withContext(ioDispatcher) {
            val rawPid = record.pid
            if (rawPid <= 0L || rawPid > Int.MAX_VALUE) {
                return@withContext scanOnly("the trial journal names no process")
            }
            val pid = rawPid.toInt()
            if (!isAlive(pid)) return@withContext scanOnly("the journaled trial process is gone")
            if (!matchesNeedles(pid, attributionNeedles(record))) {
                // Never kill a PID whose ownership cannot be proved; the journal stays for the
                // rescue surface instead.
                return@withContext DshTrialStopPolicy.Verdict.Unverified(
                    "the journaled trial pid does not reference the verified payload"
                )
            }
            log("dsh: trial: releasing a recovered trial generation pid=$pid")
            var delivered = signal(pid, TRIAL_SIGTERM)
            if (delivered) awaitExit(pid, stopGraceMillis)
            if (isAlive(pid)) {
                delivered = signal(pid, TRIAL_SIGKILL) || delivered
                if (delivered) awaitExit(pid, stopKillMillis)
            }
            val alive = isAlive(pid)
            val scanned = scan()
            when {
                scanned == null -> DshTrialStopPolicy.Verdict.Unverified(
                    if (alive) {
                        "the recovered trial process is still alive and the survivor scan is unavailable"
                    } else {
                        "the payload survivor scan is unavailable"
                    }
                )

                alive -> DshTrialStopPolicy.Verdict.Survived((scanned + pid).distinct().sorted())
                scanned.isNotEmpty() -> DshTrialStopPolicy.Verdict.Survived(scanned.distinct().sorted())
                !delivered -> DshTrialStopPolicy.Verdict.Unverified(
                    "the process is gone but no stop signal could be delivered"
                )

                else -> DshTrialStopPolicy.Verdict.Stopped(listOf(pid))
            }
        }

    private suspend fun awaitExit(pid: Int, budgetMillis: Long) {
        val deadline = clock() + budgetMillis
        while (clock() < deadline) {
            if (!isAlive(pid)) return
            delay(pollMillis)
        }
    }

    /** No PID to attribute: the bounded payload scan is the only positive evidence. */
    private fun scanOnly(detail: String): DshTrialStopPolicy.Verdict {
        val scanned = scan()
            ?: return DshTrialStopPolicy.Verdict.Unverified("$detail; the payload survivor scan is unavailable")
        return if (scanned.isEmpty()) {
            DshTrialStopPolicy.Verdict.Stopped(emptyList())
        } else {
            DshTrialStopPolicy.Verdict.Survived(scanned.distinct().sorted())
        }
    }
}

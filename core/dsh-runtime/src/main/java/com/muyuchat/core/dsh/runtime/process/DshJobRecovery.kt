package com.muyuchat.core.dsh.runtime.process

import kotlinx.coroutines.delay

/** What one recovery pass did. [survived] keeps those task records `observed`. */
internal data class DshJobRecoveryResult(
    val examined: Int,
    val reaped: List<String>,
    val skipped: List<String>,
    val survived: List<Int>,
    /** More candidates existed than the bounded pass examined (or the budget ran out). */
    val truncated: Boolean
)

/**
 * Settles job processes that outlived the Node host generation that started them.
 *
 * The official `job_list` registry is host memory and is **not** reconstructed here; this only
 * recovers processes. A prior-generation ledger record is signalled only when *all* of these
 * facts still match at recovery time:
 *
 * 1. the PID is alive;
 * 2. `/proc/<pid>/stat` field 22 (start time) equals the recorded value - the PID-reuse guard;
 * 3. `/proc/<pid>/environ` still carries `MCA_DSH_GENERATION=<recorded generation>`;
 * 4. the SHA-256 of `/proc/<pid>/cmdline` equals the recorded hash.
 *
 * Any failed check marks the record `skipped` and sends no signal. A fully matching candidate is
 * settled with SIGTERM on its process tree, SIGKILL survivors after [graceMillis], and marked
 * `reaped` only when no tracked PID is alive after [killMillis]. The pass is bounded by
 * [maxCandidates] and [budgetMillis] so a broken ledger can never delay a host start indefinitely.
 */
internal class DshJobRecovery(
    private val source: DshJobProcessSource,
    private val ledger: DshJobLedger,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val killMillis: Long = DEFAULT_KILL_MILLIS,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {

    suspend fun recover(currentGeneration: Long): DshJobRecoveryResult {
        // One extra candidate proves truncation without reading an unbounded history.
        val candidates = ledger.observedBefore(currentGeneration, maxCandidates + 1)
        val examined = candidates.take(maxCandidates)
        val reaped = ArrayList<String>()
        val skipped = ArrayList<String>()
        val survived = ArrayList<Int>()
        var unfinished = 0
        val deadline = clock() + budgetMillis
        for (record in examined) {
            if (clock() >= deadline) {
                unfinished++
                continue
            }
            val mismatch = identityMismatch(record)
            if (mismatch != null) {
                if (ledger.mark(record.taskId, DshJobStatus.SKIPPED, clock())) {
                    skipped.add(record.taskId)
                } else {
                    unfinished++
                }
                log("dsh: job recovery skipped task=${record.taskId} reason=$mismatch")
                continue
            }
            if (reap(record)) reaped.add(record.taskId) else survived.add(record.pid)
        }
        return DshJobRecoveryResult(
            examined = reaped.size + skipped.size + survived.size,
            reaped = reaped,
            skipped = skipped,
            survived = survived,
            truncated = candidates.size > maxCandidates || unfinished > 0
        )
    }

    /** Null when every identity fact still matches; otherwise the first concrete mismatch. */
    private fun identityMismatch(record: DshJobRecord): String? {
        if (record.pid <= 0) return "invalid pid"
        if (!source.isAlive(record.pid)) return "process gone"
        val startTime = source.startTime(record.pid) ?: return "startTime unreadable"
        if (startTime != record.startTime) return "startTime mismatch (pid reuse)"
        val token = source.environmentValue(record.pid, DshJobIdentity.GENERATION_ENV)
        if (token != record.generation.toString()) return "generation token mismatch"
        val cmdline = source.cmdline(record.pid) ?: return "cmdline unreadable"
        if (DshJobIdentity.cmdlineHash(cmdline) != record.cmdlineHash) return "cmdline mismatch"
        return null
    }

    /**
     * Signals the verified tree. The root identity was checked immediately before this call; the
     * tree snapshot is bounded to the children present at that moment (the same primitive the
     * host stop uses), so no unrelated process is addressed by PID alone.
     *
     * Escalation repeats the guard because the grace budget is long enough for the root (or a
     * tracked child) to exit and have its PID recycled: the root is only re-signalled while its
     * full identity still matches, and a child only while it still carries the generation token.
     */
    private suspend fun reap(record: DshJobRecord): Boolean {
        val tracked = linkedSetOf<Int>()
        tracked.addAll(source.descendantsOf(record.pid))
        tracked.add(record.pid)
        log("dsh: job recovery reaping task=${record.taskId} pids=${tracked.size}")
        source.signalTree(record.pid, DshProcessTree.SIGTERM)
        if (awaitGone(tracked, graceMillis)) {
            ledger.mark(record.taskId, DshJobStatus.REAPED, clock())
            log("dsh: job recovery reaped task=${record.taskId} (SIGTERM)")
            return true
        }
        // Late children can appear while the root is shutting down; capture them once more.
        tracked.addAll(source.descendantsOf(record.pid))
        val rootIsStillOurs = identityMismatch(record) == null
        if (!rootIsStillOurs) {
            log("dsh: job recovery will not escalate task=${record.taskId} by pid (identity changed)")
        }
        for (pid in tracked) {
            if (!source.isAlive(pid)) continue
            val ours = if (pid == record.pid) {
                rootIsStillOurs
            } else {
                carriesGeneration(pid, record.generation)
            }
            if (ours) source.signal(pid, DshProcessTree.SIGKILL)
        }
        if (rootIsStillOurs) source.signalTree(record.pid, DshProcessTree.SIGKILL)
        if (awaitGone(tracked, killMillis)) {
            ledger.mark(record.taskId, DshJobStatus.REAPED, clock())
            log("dsh: job recovery reaped task=${record.taskId} (SIGKILL)")
            return true
        }
        log("dsh: job recovery could not settle task=${record.taskId}; it stays observed")
        return false
    }

    private fun carriesGeneration(pid: Int, generation: Long): Boolean =
        source.environmentValue(pid, DshJobIdentity.GENERATION_ENV) == generation.toString()

    private suspend fun awaitGone(pids: Collection<Int>, budgetMillis: Long): Boolean {
        val deadline = clock() + budgetMillis
        while (clock() < deadline) {
            if (pids.none { source.isAlive(it) }) return true
            delay(pollMillis)
        }
        return pids.none { source.isAlive(it) }
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 2_000L
        const val DEFAULT_KILL_MILLIS = 1_000L
        const val DEFAULT_POLL_MILLIS = 100L
        const val DEFAULT_MAX_CANDIDATES = 8
        const val DEFAULT_BUDGET_MILLIS = 6_000L
    }
}

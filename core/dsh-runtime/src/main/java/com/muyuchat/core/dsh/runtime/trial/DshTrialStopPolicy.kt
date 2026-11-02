package com.muyuchat.core.dsh.runtime.trial

import com.muyuchat.core.dsh.runtime.process.DshStopOutcome

/**
 * The only accepted meaning of "the isolated trial stopped".
 *
 * A trial release requires two independent positive facts: the host's own SIGTERM -> SIGKILL
 * outcome settled, **and** the bounded same-UID payload scan found no survivor. A missing scan is
 * not a clean result, and a returned PID is never rounded down to STOPPED — the trial journal must
 * stay in `RECOVERY_REQUIRED` instead.
 */
internal object DshTrialStopPolicy {

    sealed interface Verdict {
        /** Positively proved: no trial process (and no reparented payload child) is alive. */
        data class Stopped(val pids: List<Int>) : Verdict

        /** The stop attempt left live processes; [pids] is what must stay fenced. */
        data class Survived(val pids: List<Int>) : Verdict

        /** The stop cannot be proved either way (scan/signals unavailable). */
        data class Unverified(val detail: String) : Verdict
    }

    /**
     * [outcome] is the host's own stop result (null when there is no in-memory host, e.g. after a
     * process death); [survivors] is the union of the host's surviving PIDs and the payload scan,
     * or null when the scan itself was unavailable.
     */
    fun verdict(outcome: DshStopOutcome?, survivors: List<Int>?): Verdict {
        if (survivors == null) {
            return Verdict.Unverified("the payload survivor scan is unavailable")
        }
        if (outcome != null && (!outcome.stopped || outcome.fenceRequired)) {
            val alive = survivors.ifEmpty { outcome.survivingPids }.ifEmpty { outcome.trackedPids }
            return if (alive.isEmpty()) {
                Verdict.Unverified("the host did not settle the process tree")
            } else {
                Verdict.Survived(alive.distinct().sorted())
            }
        }
        if (survivors.isNotEmpty()) return Verdict.Survived(survivors.distinct().sorted())
        return Verdict.Stopped(emptyList())
    }
}

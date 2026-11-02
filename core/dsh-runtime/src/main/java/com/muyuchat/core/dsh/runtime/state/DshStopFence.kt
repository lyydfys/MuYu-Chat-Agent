package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.runtime.process.DshStopOutcome

/**
 * The single stop fence of the one DSH host slot.
 *
 * It owns exactly one piece of state: the PIDs that a previous stop attempt could not prove gone.
 * The owner still owns every lifecycle transition; this class only answers the bounded questions
 * the owner must not answer optimistically:
 *
 * - which PIDs survive a stop attempt (`null` = the bounded scan itself is unavailable, which must
 *   fail closed, never mean "no orphans");
 * - whether a new generation may start at all, including the "fence was raised and is now
 *   positively cleared" case;
 * - which PIDs must be retained when a stop could not be verified.
 *
 * Both generations of the owner (production and the isolated closure trial) share this fence, so a
 * trial can never start on top of a surviving production tree and vice versa.
 */
internal class DshStopFence(
    private val isAlive: (Int) -> Boolean,
    /** Bounded same-UID payload scan; null means the scan could not run. */
    private val scan: () -> List<Int>?,
    private val log: (String) -> Unit
) {

    /** What the owner must do before starting any generation. */
    sealed interface Decision {
        /** Nothing of ours survives; the start may proceed. */
        data object Clear : Decision

        /**
         * A raised fence is now positively cleared. The owner must publish
         * [DshRuntimeStateMachine.confirmExitedAfterFence] before starting.
         */
        data object ClearedFence : Decision

        /** Start is refused; [reason] is path-free and safe for a snapshot diagnostics field. */
        data class Blocked(val reason: String) : Decision
    }

    /** PIDs a previous stop attempt could not settle. Empty when nothing is fenced. */
    @Volatile
    var pids: List<Int> = emptyList()
        private set

    /** The bounded same-UID scan result; null means unavailable (callers must fail closed). */
    fun scanOrNull(): List<Int>? = scan()

    /** Fenced PIDs that still answer as alive. */
    fun aliveFenced(): List<Int> = pids.filter { isAlive(it) }

    /** Surviving PIDs of [outcome] plus any same-UID payload orphan; null = unverifiable. */
    fun survivorsAfter(outcome: DshStopOutcome): List<Int>? {
        val scanned = scanOrNull() ?: return null
        return (outcome.survivingPids + scanned).distinct().sorted()
    }

    /** Retains [candidates] as the fence. Duplicates and ordering noise are removed. */
    fun retain(candidates: List<Int>) {
        pids = candidates.distinct().sorted()
    }

    fun clear() {
        pids = emptyList()
    }

    /**
     * Decides whether a new generation may start.
     *
     * [fenceRaised] must be [DshRuntimeStateMachine.isStopFenceRaised]: a raised fence is only
     * lifted by a positive scan of every fenced and reparented process. An unavailable scan is
     * never treated as a clean result.
     */
    fun checkBeforeStart(fenceRaised: Boolean): Decision {
        if (fenceRaised) {
            val orphans = scanOrNull()
                ?: return Decision.Blocked("stop fence verification failed: orphan scan unavailable")
            val stillAlive = (aliveFenced() + orphans).distinct().sorted()
            if (stillAlive.isNotEmpty()) {
                retain(stillAlive)
                log("dsh: stop fence blocked start; pids=$stillAlive")
                return Decision.Blocked("the stop fence is still active for pids $stillAlive")
            }
            clear()
            return Decision.ClearedFence
        }
        // ALWAYS scan before any new generation. This covers a crash or service restart (no
        // in-memory fence) and a fence that could not name a root pid.
        val orphans = scanOrNull()
            ?: return Decision.Blocked("stale DSH host state cannot be verified: orphan scan unavailable")
        if (orphans.isNotEmpty()) {
            retain(pids + orphans)
            log("dsh: refusing start; same-UID payload orphans alive pids=$orphans")
            return Decision.Blocked("orphaned DSH host processes are still alive pids=$orphans")
        }
        if (pids.isNotEmpty()) clear()
        return Decision.Clear
    }
}

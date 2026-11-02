package com.muyuchat.mca.agent.toolpack

/**
 * Bounded per-execution ledger for nested host calls.
 *
 * It owns exactly three pieces of mutable state: the stable-ordinal table (so one logical call
 * executes at most once), the consumed approval ids (so one approval authorises at most one call)
 * and the cumulative host output counter. Entries are bounded by
 * [ToolPackageRuntimeLimits.maxHostCalls]; the script cannot grow the table without spending its
 * call budget.
 *
 * The table keeps the *determined outcome* of each ordinal, not the script arguments. A replay with
 * the same method and canonical arguments hash returns that outcome; a replay with any other method
 * or hash is refused by the caller.
 */
internal class ToolPackageHostCallLedger(private val limits: ToolPackageRuntimeLimits) {

    private val outcomes = LinkedHashMap<Int, Outcome>()
    private val inFlight = HashSet<Int>()
    private val approvals = HashSet<String>()
    private var totalResultBytes: Long = 0L
    private var callCount: Int = 0
    private var sequence: Int = 0

    /** Reserves an ordinal, or reports the earlier outcome of a replayed ordinal. */
    @Synchronized
    fun begin(method: String, requestedOrdinal: Int, argumentsHash: String): Start {
        val ordinal = if (requestedOrdinal == ToolPackageHostCaller.AUTO_ORDINAL) {
            nextAutoOrdinal()
        } else {
            requestedOrdinal
        }
        val earlier = outcomes[ordinal]
        if (earlier != null) {
            return if (earlier.method == method && earlier.argumentsHash == argumentsHash) {
                Start.Replay(ordinal, earlier)
            } else {
                Start.OrdinalMismatch
            }
        }
        if (ordinal in inFlight) return Start.OrdinalBusy
        if (callCount >= limits.maxHostCalls) return Start.LimitExceeded
        callCount += 1
        inFlight += ordinal
        return Start.Fresh(ordinal)
    }

    /** Records the determined outcome of a reserved ordinal. */
    @Synchronized
    fun finish(ordinal: Int, outcome: Outcome) {
        inFlight -= ordinal
        outcomes[ordinal] = outcome
    }

    /** Releases a reservation that produced no determined outcome; the ordinal becomes reusable. */
    @Synchronized
    fun abandon(ordinal: Int) {
        if (inFlight.remove(ordinal)) {
            callCount -= 1
        }
    }

    /** True when this approval id is consumed for the first time. */
    @Synchronized
    fun consumeApproval(approvalId: String): Boolean = approvals.add(approvalId)

    /** Adds one result to the execution's cumulative output and reports whether it still fits. */
    @Synchronized
    fun accountResult(resultBytes: Long): Boolean {
        totalResultBytes += resultBytes
        return totalResultBytes <= limits.maxHostTotalResultBytes
    }

    private fun nextAutoOrdinal(): Int {
        do {
            sequence += 1
        } while (outcomes.containsKey(sequence) || sequence in inFlight)
        return sequence
    }

    /** The determined outcome of one stable ordinal: never both a result and an error. */
    class Outcome(
        val method: String,
        val argumentsHash: String,
        val resultJson: String?,
        val error: ToolPackageError?,
        val resultBytes: Long
    ) {
        companion object {
            fun returned(method: String, argumentsHash: String, json: String, bytes: Long): Outcome =
                Outcome(method, argumentsHash, json, null, bytes)

            fun refused(method: String, argumentsHash: String, error: ToolPackageError): Outcome =
                Outcome(method, argumentsHash, null, error, 0L)
        }
    }

    sealed interface Start {
        data class Fresh(val ordinal: Int) : Start

        data class Replay(val ordinal: Int, val outcome: Outcome) : Start

        data object OrdinalMismatch : Start

        data object OrdinalBusy : Start

        data object LimitExceeded : Start
    }
}

package com.muyuchat.core.agent.runtime.run

/**
 * Per-step watchdog for one Agent run.
 *
 * A step that never returns (a tool that ignores cancellation, a transport that stops delivering)
 * otherwise leaves the run RUNNING forever: nothing in the loop is waiting on a budget, so the user's
 * only exit is a manual stop. This class records when each step index started and answers, at any
 * moment, which of those steps have exceeded [stepTimeoutMillis] without finishing. It decides
 * nothing and stops nothing: the owner keeps the single state machine, reads this watchdog and then
 * applies its normal stop/pause path.
 *
 * Semantics:
 *  - starting the same index twice keeps the **first** start time, so a retry cannot reset the clock
 *    and thereby evade the timeout;
 *  - a finished step is forgotten, so it can never be reported again;
 *  - a clock that moves backwards (a negative delta) is treated as "not overdue" instead of
 *    fabricating a timeout; the step is reported only once the clock has advanced past the limit
 *    again. This is why the caller must inject a monotonic clock in production
 *    ([AgentSystemMonotonicClock]); the wall-clock default exists so a plain `System.currentTimeMillis`
 *    host still behaves conservatively.
 *  - every method is safe to call from the loop thread, a timer thread and the UI at once.
 */
class AgentStepWatchdog(
    private val stepTimeoutMillis: Long = DEFAULT_STEP_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(stepTimeoutMillis > 0L) { "step timeout must be positive" }
    }

    private val lock = Any()
    private val startedAtMillis = LinkedHashMap<Int, Long>()

    /** Records the start of [stepIndex]. A repeated start keeps the original start time. */
    fun onStepStarted(stepIndex: Int) {
        require(stepIndex >= 0) { "step index must not be negative" }
        val now = clock()
        synchronized(lock) {
            startedAtMillis.putIfAbsent(stepIndex, now)
        }
    }

    /** Forgets [stepIndex]; a finished step is never reported as overdue afterwards. */
    fun onStepFinished(stepIndex: Int) {
        require(stepIndex >= 0) { "step index must not be negative" }
        synchronized(lock) {
            startedAtMillis.remove(stepIndex)
        }
    }

    /** Step indexes that are still running and have exceeded the timeout, in ascending order. */
    fun timedOutSteps(): List<Int> {
        val now = clock()
        synchronized(lock) {
            if (startedAtMillis.isEmpty()) return emptyList()
            return startedAtMillis.entries
                .asSequence()
                .filter { (_, startedAt) -> isOverdue(now, startedAt) }
                .map { it.key }
                .sorted()
                .toList()
        }
    }

    /**
     * True when the newest step that is still running has exceeded the timeout.
     *
     * "Current" is the highest open step index: the loop advances step indexes monotonically, so a
     * lower one can only still be open because a batch started it before the current step.
     */
    fun isCurrentStepOverdue(): Boolean {
        val now = clock()
        synchronized(lock) {
            var currentIndex: Int? = null
            var currentStartedAt = 0L
            startedAtMillis.forEach { (index, startedAt) ->
                if (currentIndex == null || index > currentIndex!!) {
                    currentIndex = index
                    currentStartedAt = startedAt
                }
            }
            val index = currentIndex ?: return false
            return isOverdue(now, currentStartedAt)
        }
    }

    /** Number of steps currently registered; a finished step is not counted. */
    fun activeStepCount(): Int = synchronized(lock) { startedAtMillis.size }

    /**
     * Overdue test that survives a non-monotonic clock.
     *
     * A backward jump makes `now - startedAt` negative, which is never treated as an expiry. The
     * arithmetic is written as a subtraction on the difference so a large `now` cannot overflow.
     */
    private fun isOverdue(now: Long, startedAt: Long): Boolean {
        val elapsed = now - startedAt
        if (elapsed < 0L) return false
        return elapsed >= stepTimeoutMillis
    }

    companion object {
        /** Three minutes: longer than any single bounded tool, short enough to notice a hang. */
        const val DEFAULT_STEP_TIMEOUT_MILLIS: Long = 180_000L
    }
}

package com.muyuchat.mca.composition

internal data class ProcessCloseFailure(
    val step: String,
    val error: Throwable
)

/**
 * Settles process-scoped resources exactly once, in declaration order.
 *
 * A process root can be closed twice (the last explicit Activity finish and `onTerminate`). A step
 * that throws must not keep the remaining native, worker or scope resources owned, so every step
 * runs and the failures are returned for logging instead of aborting the sequence.
 */
internal class ProcessCloseSequence(
    private vararg val steps: Pair<String, () -> Unit>
) {
    private val lock = Any()
    private var closed = false

    fun close(): List<ProcessCloseFailure> = synchronized(lock) {
        if (closed) return emptyList()
        closed = true
        val failures = ArrayList<ProcessCloseFailure>(0)
        for ((name, step) in steps) {
            try {
                step()
            } catch (error: Throwable) {
                failures += ProcessCloseFailure(name, error)
            }
        }
        failures
    }
}

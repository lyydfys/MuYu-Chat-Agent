package com.muyuchat.mca

/**
 * LiteRT generates asynchronously between Binder calls. Cancellation therefore belongs to a
 * generation epoch, not to one generateNextChunk transaction. No JNI runs under this monitor.
 */
internal class LocalChatCancellationState {
    data class Operation(val epoch: Long, val id: Long, val stage: String)
    data class Stop(val epoch: Long, val id: Long)
    data class Request(val stop: Stop, val dispatch: Boolean)

    private var epoch = 0L
    private var sequence = 0L
    private var operation: Operation? = null
    private var generating = false
    private var stop: Stop? = null
    private var cancelReturned = false

    @Synchronized
    fun begin(stage: String): Operation {
        check(operation == null) { "A LiteRT native operation is already active." }
        // Decode is allowed to drain Done while a cancellation is pending. A replacement load,
        // prefill or unload cannot race a delayed native cancel on the old Conversation.
        check(stop == null || stage == "decode") { "LiteRT cancellation is still in progress." }
        if (stage != "decode") epoch += 1L
        if (stage.contains("prefill")) generating = true
        return Operation(epoch, ++sequence, stage).also { operation = it }
    }

    @Synchronized
    fun finish(expected: Operation, terminal: Boolean, runnerStillActive: Boolean = false) {
        if (operation != expected) return
        operation = null
        if (terminal || expected.stage == "load" || expected.stage == "unload" ||
            expected.stage == "shutdown" || expected.stage == "invalidate") generating = runnerStillActive
        settle()
    }

    @Synchronized
    fun request(runnerStillActive: Boolean = false): Request? {
        stop?.let { return Request(it, false) }
        if (operation == null && !generating && !runnerStillActive) return null
        if (runnerStillActive) generating = true
        val ticket = Stop(epoch, ++sequence)
        stop = ticket
        cancelReturned = false
        return Request(ticket, true)
    }

    @Synchronized
    fun returned(expected: Stop) {
        if (stop != expected) return
        cancelReturned = true
        settle()
    }

    @Synchronized
    fun generationCompleted(expected: Stop) {
        if (stop != expected || operation != null) return
        generating = false
        settle()
    }

    @Synchronized
    fun needsRecovery(expected: Stop): Boolean =
        stop == expected && epoch == expected.epoch &&
            (!cancelReturned || generating || operation != null)

    @Synchronized
    fun recoverIfNeeded(expected: Stop, recover: () -> Unit): Boolean {
        if (!needsRecovery(expected)) return false
        // Keep replacement admission excluded until the process-recovery action is issued.
        recover()
        return true
    }

    @Synchronized
    fun clear() {
        epoch += 1L
        operation = null
        generating = false
        stop = null
    }

    private fun settle() {
        if (cancelReturned && !generating && operation == null) stop = null
    }
}

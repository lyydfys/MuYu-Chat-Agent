package com.muyuchat.mca

import org.json.JSONObject

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

/**
 * Bounded stop-latency evidence for the isolated text worker.
 *
 * A user/engine stop arrives as a one-way Binder call while a synchronous `beginCompletion` or
 * `generateNextChunk` transaction may still be blocked in native code. The worker therefore records
 * (a) the stop request, (b) the exact native operation token and generation it targets, (c) the
 * moment that operation observed the stop, and (d) the moment the worker became idle again. The
 * elapsed values are device-verification evidence only; they never change cancellation semantics.
 *
 * The token match is exact. A stop that was bound to operation token 7 can never be reported as
 * observed or idle by a replacement operation token 8, so a delayed stop cannot be credited to a
 * newer request. A stop that arrives between native operations carries no token; it is reported as
 * "no active native operation" and is never credited to a later operation.
 */
internal class LocalChatStopLatencyTrace(private val nowMs: () -> Long) {
    data class Ticket(
        val sequence: Long,
        val stage: String,
        val operationToken: Long,
        val generation: Long,
        val requestKey: String?,
        val requestedAtMs: Long
    )

    data class Observation(
        val ticket: Ticket,
        val observedAtMs: Long
    ) {
        val elapsedMs: Long get() = (observedAtMs - ticket.requestedAtMs).coerceAtLeast(0L)
    }

    data class Idle(
        val observation: Observation,
        val idleAtMs: Long
    ) {
        val stopObservedToIdleMs: Long
            get() = (idleAtMs - observation.observedAtMs).coerceAtLeast(0L)
        val stopRequestedToIdleMs: Long
            get() = (idleAtMs - observation.ticket.requestedAtMs).coerceAtLeast(0L)
    }

    private var sequence = 0L
    private var pending: Ticket? = null
    private var observed: Observation? = null

    /**
     * Records a stop request. Returns null when there is no native operation to bind to (the runner
     * stop is still dispatched; there is simply no bounded latency to time), or when a request for
     * the same operation token is already pending so a repeated `requestStop` cannot restart the
     * latency clock.
     */
    @Synchronized
    fun request(
        stage: String?,
        operationToken: Long,
        generation: Long,
        requestKey: String?
    ): Ticket? {
        val normalizedStage = stage?.takeIf { it.isNotBlank() } ?: return null
        // No exact native operation token means there is nothing whose latency can be bounded here.
        if (operationToken == 0L) return null
        pending?.let { active ->
            if (active.operationToken == operationToken && active.generation == generation) return null
        }
        val ticket = Ticket(
            sequence = ++sequence,
            stage = normalizedStage,
            operationToken = operationToken,
            generation = generation,
            requestKey = requestKey,
            requestedAtMs = nowMs()
        )
        pending = ticket
        observed = null
        return ticket
    }

    /** The exact native operation returned after the stop was requested; null for any other token. */
    @Synchronized
    fun observe(operationToken: Long): Observation? {
        val ticket = pending ?: return null
        if (!ticket.matches(operationToken)) return null
        if (observed != null) return null
        return Observation(ticket, nowMs()).also { observed = it }
    }

    /** The worker has no active native operation and no running generation for this exact stop. */
    @Synchronized
    fun idle(operationToken: Long, generation: Long): Idle? {
        val ticket = pending ?: return null
        if (!ticket.matches(operationToken)) return null
        if (ticket.generation != generation) return null
        val observation = observed ?: return null
        pending = null
        observed = null
        return Idle(observation, nowMs())
    }

    @Synchronized
    fun pendingToken(): Long? = pending?.operationToken

    @Synchronized
    fun hasPendingObservation(): Boolean = pending != null && observed != null

    private fun Ticket.matches(operationToken: Long): Boolean =
        this.operationToken == operationToken
}

/**
 * Extracts a bounded, prompt-free request identity from the begin payload for stop-latency logs.
 *
 * The engine already carries `conversationOwnerId` (the exact request id for one-shot calls, or the
 * persistent session identity) through the params JSON. Only values matching a conservative token
 * alphabet are logged; anything else falls back to the worker's own operation token, so a log line
 * can never leak prompt or path content.
 */
internal object LocalChatWorkerStopRequestKey {
    private val SAFE_KEY = Regex("[A-Za-z0-9:._-]{1,64}")

    fun fromParamsJson(paramsJson: String?): String? {
        val root = runCatching { JSONObject(paramsJson.orEmpty().ifBlank { "{}" }) }.getOrNull()
            ?: return null
        val owner = sequenceOf("conversationOwnerId", "requestId")
            .map { key -> root.optString(key) }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return normalize(owner)
    }

    fun normalize(raw: String): String? {
        val value = raw.trim().removePrefix("request:").trim()
        return value.takeIf { SAFE_KEY.matches(it) }
    }
}

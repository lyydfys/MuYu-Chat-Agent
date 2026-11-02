package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRetryability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AgentCommandPriority { NORMAL, CONTROL }

sealed interface AgentProcessorResult<out T> {
    data class Completed<T>(val value: T) : AgentProcessorResult<T>

    data class Rejected(val error: AgentError) : AgentProcessorResult<Nothing>
}

/**
 * Single-run serial command processor.
 *
 * - At most [maxPendingNormal] ordinary commands may wait; a full queue returns COMMAND_BUSY
 *   instead of growing without bound (never `Channel.UNLIMITED`).
 * - Pause/cancel use [AgentCommandPriority.CONTROL]; they are dispatched before every queued
 *   ordinary command, so a flood of normal commands cannot starve cancellation.
 * - Exactly one command executes at a time (one owner state writer); commands perform model/tool
 *   I/O only in explicit child coroutines owned by the caller, never inside the processor lock.
 * - Caller cancellation removes a still-queued command and propagates [CancellationException];
 *   it is never swallowed. Closing rejects all new commands.
 */
class AgentCommandProcessor(
    private val scope: CoroutineScope,
    private val maxPendingNormal: Int = AgentContract.MAX_PENDING_COMMANDS,
    private val maxPendingControl: Int = AgentContract.MAX_PENDING_COMMANDS
) {
    private val mutex = Mutex()
    private val normalQueue = ArrayDeque<Queued>()
    private val controlQueue = ArrayDeque<Queued>()
    private var running = false

    @Volatile
    private var closed = false

    @Volatile
    private var pendingCountValue = 0

    init {
        require(maxPendingNormal > 0) { "maxPendingNormal must be positive" }
        require(maxPendingControl > 0) { "maxPendingControl must be positive" }
    }

    suspend fun <T> submit(
        priority: AgentCommandPriority,
        block: suspend () -> T
    ): AgentProcessorResult<T> {
        val deferred = CompletableDeferred<AgentProcessorResult<Any?>>()
        val item = Queued(block = { block() }, deferred = deferred)
        var startDrain = false
        val rejection = mutex.withLock {
            when {
                closed -> busy("The agent owner is closed and rejects new commands.")
                !scope.isActive -> busy("The agent owner scope is no longer active.")
                priority == AgentCommandPriority.NORMAL &&
                    normalQueue.size >= maxPendingNormal -> busy(
                    "The agent command queue is full ($maxPendingNormal pending commands)."
                )
                priority == AgentCommandPriority.CONTROL &&
                    controlQueue.size >= maxPendingControl -> busy(
                    "The control command queue is full ($maxPendingControl pending commands)."
                )
                else -> {
                    if (priority == AgentCommandPriority.CONTROL) {
                        controlQueue.addLast(item)
                    } else {
                        normalQueue.addLast(item)
                    }
                    pendingCountValue = normalQueue.size + controlQueue.size
                    if (!running) {
                        running = true
                        startDrain = true
                    }
                    null
                }
            }
        }
        if (rejection != null) return rejection
        if (startDrain) scope.launch { drain() }
        return try {
            @Suppress("UNCHECKED_CAST")
            deferred.await() as AgentProcessorResult<T>
        } catch (cancelled: CancellationException) {
            mutex.withLock { removeQueuedLocked(item) }
            throw cancelled
        }
    }

    fun close() {
        closed = true
    }

    fun isClosed(): Boolean = closed

    fun pendingCommands(): Int = pendingCountValue

    private suspend fun drain() {
        while (true) {
            val item = mutex.withLock {
                val next = controlQueue.removeFirstOrNull() ?: normalQueue.removeFirstOrNull()
                pendingCountValue = normalQueue.size + controlQueue.size
                if (next == null) running = false
                next
            } ?: return
            try {
                val value = item.block()
                item.deferred.complete(AgentProcessorResult.Completed(value))
            } catch (cancelled: CancellationException) {
                item.deferred.completeExceptionally(cancelled)
                mutex.withLock { cancelPendingLocked() }
                throw cancelled
            } catch (failure: Throwable) {
                // Unexpected owner/command failure is surfaced to the caller, never swallowed.
                item.deferred.completeExceptionally(failure)
            }
        }
    }

    private fun removeQueuedLocked(item: Queued) {
        normalQueue.remove(item)
        controlQueue.remove(item)
        pendingCountValue = normalQueue.size + controlQueue.size
    }

    private fun cancelPendingLocked() {
        val pending = normalQueue.toList() + controlQueue.toList()
        normalQueue.clear()
        controlQueue.clear()
        pendingCountValue = 0
        running = false
        for (item in pending) {
            item.deferred.completeExceptionally(CancellationException("Agent command processor cancelled"))
        }
    }

    private fun busy(message: String): AgentProcessorResult<Nothing> =
        AgentProcessorResult.Rejected(
            AgentError(
                code = AgentErrorCodes.COMMAND_BUSY,
                category = AgentErrorCategory.RESOURCE,
                retryability = AgentRetryability.SAFE_RETRY,
                safeMessage = message,
                correlationId = CORRELATION_ID
            )
        )

    private class Queued(
        val block: suspend () -> Any?,
        val deferred: CompletableDeferred<AgentProcessorResult<Any?>>
    )

    companion object {
        private const val CORRELATION_ID = "agent-command-processor"
    }
}

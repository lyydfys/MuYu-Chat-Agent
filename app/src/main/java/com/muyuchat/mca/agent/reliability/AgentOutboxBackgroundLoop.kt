package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxDispatcher
import com.muyuchat.core.agent.runtime.journal.AgentDispatchPassReport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Explicit background lifecycle for the dispatch outbox (N03/N19 skeleton).
 *
 * The app composition starts one loop; work sources (approval completion, WorkManager wake, startup
 * recovery) only [wake] it. Semantics mirror the existing run owner:
 * - [start] is single-flight and idempotent: a second start never creates a second pump;
 * - [wake] never runs a pass inline and never duplicates a pass: repeated wakes while one pass is
 *   running coalesce into at most one follow-up pass (and the outbox claim/ledger gate makes even
 *   that harmless);
 * - [stop] is cooperative: it stops taking work, lets the in-flight pass settle and reports
 *   [AgentOutboxStopResult.TimedOut] without pretending the executor stopped when it did not;
 * - [cancel] is abrupt process-style shutdown for a composition close: an in-flight dispatch stays
 *   `DISPATCHING`/`RUNNING` and is fenced by the next dispatcher pass, never resent blindly.
 *
 * The loop uses only structured concurrency in the provided scope; it introduces no second
 * executor, thread pool or scheduler.
 */
class AgentOutboxBackgroundLoop(
    private val dispatcher: AgentDispatchOutboxDispatcher,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS
) : AgentOutboxWakeTarget {
    init {
        require(pollIntervalMillis > 0L) { "pollIntervalMillis must be positive" }
        require(stopTimeoutMillis > 0L) { "stopTimeoutMillis must be positive" }
    }

    private val lock = Any()
    private val wakeToken = Channel<Unit>(Channel.CONFLATED)
    private val wakePending = AtomicBoolean(false)

    private var loopJob: Job? = null
    private var stopRequested = false

    @Volatile
    var passes: Int = 0
        private set

    @Volatile
    var lastReport: AgentDispatchPassReport? = null
        private set

    /** Simple class name of the last non-cancellation pass failure, if any; never a success claim. */
    @Volatile
    var lastPassFailureRef: String? = null
        private set

    override fun isRunning(): Boolean = synchronized(lock) { loopJob?.isActive == true }

    fun start(): AgentOutboxStartResult = synchronized(lock) {
        if (loopJob?.isActive == true) return AgentOutboxStartResult.AlreadyRunning
        stopRequested = false
        wakePending.set(false)
        val job = scope.launch { runLoop() }
        loopJob = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (loopJob === job) loopJob = null
            }
        }
        AgentOutboxStartResult.Started
    }

    /**
     * Requests one follow-up pass. Returns [AgentOutboxWakeResult.NotRunning] when the loop is not
     * started, so a caller can decide to start it instead of silently buffering unbounded work.
     */
    override fun wake(): AgentOutboxWakeResult {
        if (!isRunning()) return AgentOutboxWakeResult.NotRunning
        if (!wakePending.compareAndSet(false, true)) return AgentOutboxWakeResult.Coalesced
        wakeToken.trySend(Unit)
        return AgentOutboxWakeResult.Accepted
    }

    /** Stops acquiring new passes and waits for the current one to settle, bounded by the timeout. */
    suspend fun stop(): AgentOutboxStopResult {
        val job = synchronized(lock) {
            val current = loopJob
            if (current == null || !current.isActive) return AgentOutboxStopResult.AlreadyStopped
            stopRequested = true
            current
        }
        wakeToken.trySend(Unit)
        return if (withTimeoutOrNull(stopTimeoutMillis) { job.join() } == null) {
            AgentOutboxStopResult.TimedOut
        } else {
            AgentOutboxStopResult.Stopped
        }
    }

    /** Abrupt shutdown: the current pass is cancelled instead of being allowed to finish. */
    suspend fun cancel(): AgentOutboxCancelResult {
        val job = synchronized(lock) {
            val current = loopJob
            if (current == null || !current.isActive) return AgentOutboxCancelResult.AlreadyStopped
            stopRequested = true
            current
        }
        job.cancelAndJoin()
        return AgentOutboxCancelResult.Cancelled
    }

    private suspend fun runLoop() {
        while (coroutineContext.isActive && !stopRequested) {
            wakePending.set(false)
            passes += 1
            try {
                lastReport = dispatcher.dispatchOnce()
                lastPassFailureRef = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A store/ledger failure must not be reported as a successful pass. Keep the fence:
                // durable rows are settled on the next pass from the invocation ledger.
                lastPassFailureRef = failure.javaClass.simpleName
            }
            if (stopRequested) break
            withTimeoutOrNull(pollIntervalMillis) { wakeToken.receive() }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 15_000L
        const val DEFAULT_STOP_TIMEOUT_MILLIS: Long = 5_000L
    }
}

sealed interface AgentOutboxStartResult {
    data object Started : AgentOutboxStartResult
    data object AlreadyRunning : AgentOutboxStartResult
}

sealed interface AgentOutboxWakeResult {
    data object Accepted : AgentOutboxWakeResult
    data object Coalesced : AgentOutboxWakeResult
    data object NotRunning : AgentOutboxWakeResult
}

sealed interface AgentOutboxStopResult {
    data object Stopped : AgentOutboxStopResult
    data object TimedOut : AgentOutboxStopResult
    data object AlreadyStopped : AgentOutboxStopResult
}

sealed interface AgentOutboxCancelResult {
    data object Cancelled : AgentOutboxCancelResult
    data object AlreadyStopped : AgentOutboxCancelResult
}

/**
 * Narrow view of the background loop used by the approval sink. Keeping it separate lets the sink
 * be tested without a coroutine loop and prevents the sink from owning start/stop policy.
 */
interface AgentOutboxWakeTarget {
    fun isRunning(): Boolean
    fun wake(): AgentOutboxWakeResult
}

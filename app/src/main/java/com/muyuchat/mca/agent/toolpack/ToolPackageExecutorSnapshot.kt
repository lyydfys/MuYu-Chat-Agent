package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Versioned, awaitable view over the enabled executable-package surface.
 *
 * The composition root cannot block on package-store I/O while it is being created, but a run that
 * starts immediately after cold start must not freeze a catalog without the packages the user
 * enabled. [awaitInitial] is the resolution barrier: the first catalog read either observes an
 * already committed version or runs exactly one load itself.
 *
 * Refreshes are ordered by ticket, so an older, slower lifecycle refresh can never overwrite a
 * newer one. A committed version is a whole immutable list; readers never observe a torn mix.
 */
internal class ToolPackageExecutorSnapshot {

    private val mutex = Mutex()
    private var issuedVersion = 0L
    private var committedVersion = 0L
    private var initialLoad: CompletableDeferred<Unit>? = null

    @Volatile
    private var executors: List<AgentToolExecutor> = emptyList()

    /** The last committed list; never blocks. Callers that need initialization use [awaitInitial]. */
    fun current(): List<AgentToolExecutor> = executors

    val committed: Long get() = committedVersion

    /**
     * Returns only after at least one load committed. [load] failure commits an empty list so a
     * broken package store degrades to "no package tools" instead of hanging the first run.
     */
    suspend fun awaitInitial(load: suspend () -> List<AgentToolExecutor>) {
        var ownsLoad = false
        val gate = mutex.withLock {
            if (committedVersion > 0L) return
            initialLoad ?: CompletableDeferred<Unit>().also { created ->
                initialLoad = created
                // A refresh already in flight will complete the gate; only claim the load when
                // nothing has been issued yet.
                ownsLoad = issuedVersion == committedVersion
            }
        }
        if (ownsLoad) refresh(load)
        gate.await()
    }

    /** Loads off the caller's thread and commits only when this refresh is the newest one. */
    suspend fun refresh(load: suspend () -> List<AgentToolExecutor>): Long? {
        val ticket = mutex.withLock { ++issuedVersion }
        val next = try {
            load()
        } catch (cancelled: CancellationException) {
            mutex.withLock { completeInitialLocked() }
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        return mutex.withLock {
            if (ticket < committedVersion) {
                null
            } else {
                executors = next
                committedVersion = ticket
                completeInitialLocked()
                ticket
            }
        }
    }

    private fun completeInitialLocked() {
        initialLoad?.complete(Unit)
        initialLoad = null
    }
}

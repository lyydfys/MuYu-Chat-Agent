package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RuntimeEpoch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Top-level Process Runtime Host supervisor.
 * Unique owner responsible for process initialization, monotonic runtime epoch increments,
 * graceful shutdown, and native health evaluation.
 */
class RuntimeSupervisor(initialEpoch: Long = 1L) : RuntimePort {

    private val epochCounter = AtomicLong(initialEpoch)
    private val isRunning = AtomicBoolean(true)
    private val backends = ConcurrentHashMap<String, BackendPort>()
    val requestRegistry = RequestRegistry()
    val cancellationController = RequestCancellationController()
    val callbackRouter = CallbackRouter()

    override val currentEpoch: RuntimeEpoch
        get() = RuntimeEpoch(epochCounter.get())

    override val isHealthy: Boolean
        get() = isRunning.get()

    /**
     * Advances the process runtime epoch.
     * All requests, sessions, or late callbacks tied to older epochs become immediately stale and isolated.
     */
    override fun advanceEpoch(): RuntimeEpoch {
        val next = epochCounter.incrementAndGet()
        // Drain any existing active requests registered under the previous epoch
        requestRegistry.drainAll("Runtime epoch advanced to $next")
        cancellationController.cancelAllActive("Runtime epoch advanced to $next")
        return RuntimeEpoch(next)
    }

    override fun getBackend(kind: String): BackendPort? {
        return backends[kind]
    }

    override fun registerBackend(kind: String, backend: BackendPort) {
        check(isRunning.get()) { "RuntimeSupervisor is shutdown." }
        backends[kind] = backend
    }

    /**
     * Produces a runtime health diagnostic report.
     */
    fun evaluateHealth(): RuntimeHealthReport {
        return RuntimeHealthReport(
            epoch = currentEpoch,
            isHealthy = isRunning.get(),
            activeRequestCount = requestRegistry.count(),
            totalSessions = backends.size,
            details = mapOf(
                "activeBackends" to backends.keys.joinToString(","),
                "droppedStaleEvents" to callbackRouter.droppedStaleCount().toString(),
                "callbackFailures" to callbackRouter.callbackFailureCount().toString()
            )
        )
    }

    /**
     * Executes process-level graceful shutdown.
     */
    override fun shutdownProcess() {
        if (!isRunning.compareAndSet(true, false)) return

        // 1. Cancel active requests and drain callback router
        cancellationController.cancelAllActive("Process runtime shutdown")
        requestRegistry.drainAll("Process runtime shutdown")
        callbackRouter.clear()

        // 2. Shut down all registered backends
        for ((_, backend) in backends) {
            backend.close()
        }
        backends.clear()
    }

    override fun close() {
        shutdownProcess()
    }
}

package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.runtime.RuntimeHealthReport
import com.muyuchat.core.engine.runtime.RuntimePort
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified Facade for process-level runtime lifecycle, health checks, and epoch migrations.
 * Serves UI, Local API, and background Workers identically without duplicating backend logic.
 */
class RuntimeFacade(
    val supervisor: RuntimeSupervisor = RuntimeSupervisor()
) {
    private val isDisposed = AtomicBoolean(false)

    val currentEpoch: RuntimeEpoch
        get() = supervisor.currentEpoch

    val isHealthy: Boolean
        get() = supervisor.isHealthy && !isDisposed.get()

    /**
     * Advances the process runtime epoch. Automatically drains and isolates stale requests.
     */
    fun advanceEpoch(): RuntimeEpoch {
        check(!isDisposed.get()) { "RuntimeFacade is disposed." }
        return supervisor.advanceEpoch()
    }

    /**
     * Evaluates current subsystem health and resource state.
     */
    fun evaluateHealth(): RuntimeHealthReport {
        return supervisor.evaluateHealth()
    }

    /**
     * Gracefully shuts down the process runtime host.
     */
    fun shutdown() {
        if (isDisposed.compareAndSet(false, true)) {
            supervisor.shutdownProcess()
        }
    }
}

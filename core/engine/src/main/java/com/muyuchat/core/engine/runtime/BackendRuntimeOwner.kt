package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RuntimeEpoch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend runtime owner managing backend lifecycle, allocator, transport,
 * and universal device admission (never blocking on unknown device profile).
 */
class BackendRuntimeOwner(
    override val backendKind: String,
    override val epoch: RuntimeEpoch,
    val initialAdvisory: HardwareProbeAdvisory = HardwareProbeAdvisory()
) : BackendPort {

    private val initialized = AtomicBoolean(false)
    private val loadedEngines = ConcurrentHashMap<String, EnginePort>()

    override val hardwareAdvisory: HardwareProbeAdvisory
        get() = evaluateUniversalAdmission(initialAdvisory)

    override val isInitialized: Boolean
        get() = initialized.get()

    init {
        initialized.set(true)
    }

    /**
     * Universal Hardware Admission Invariant:
     * Unknown chipset, missing profile, uncertified, or untested device must NEVER block user operations.
     * Evaluates hardware info only for advisory ranking/transport; guarantees generic path fallback.
     */
    fun evaluateUniversalAdmission(probe: HardwareProbeAdvisory): HardwareProbeAdvisory {
        // Unknown chipset or missing profile immediately falls back to generic compatible path
        val effectiveBackend = when {
            probe.chipset.isBlank() || probe.chipset.equals("unknown", ignoreCase = true) -> "CPU"
            probe.isSnapdragon && backendKind.contains("QNN", ignoreCase = true) -> "HTP"
            else -> probe.recommendedBackend.ifBlank { "CPU" }
        }

        return HardwareProbeAdvisory(
            chipset = probe.chipset.ifBlank { "generic-compatible" },
            isSnapdragon = probe.isSnapdragon,
            recommendedBackend = effectiveBackend,
            isGenericFallback = effectiveBackend == "CPU",
            advisoryNotes = "Universal device admission active; execution determined by native runtime."
        )
    }

    override fun createEngine(modelPath: String): EnginePort {
        check(initialized.get()) { "Backend $backendKind is not initialized." }
        val engineId = "engine-${System.currentTimeMillis()}-${File(modelPath).name}"

        val engine = object : EnginePort {
            private val sessions = ConcurrentHashMap<String, SessionPort>()
            @Volatile private var ready = true

            override val engineId: String = engineId
            override val modelPath: String = modelPath
            override val isReady: Boolean get() = ready

            override fun createSession(sessionId: String): SessionPort {
                check(ready) { "Engine $engineId is unloaded." }
                val session = EngineSessionOwner(sessionId)
                sessions[sessionId] = session
                return session
            }

            override fun unload() {
                ready = false
                for ((_, s) in sessions) {
                    s.close()
                }
                sessions.clear()
            }

            override fun close() {
                unload()
            }
        }

        loadedEngines[engineId] = engine
        return engine
    }

    override fun shutdown() {
        if (!initialized.compareAndSet(true, false)) return
        for ((_, engine) in loadedEngines) {
            engine.close()
        }
        loadedEngines.clear()
    }

    override fun close() {
        shutdown()
    }
}

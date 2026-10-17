package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RuntimeEpoch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Backend runtime owner managing backend lifecycle, allocator, transport,
 * and universal device admission (never blocking on unknown device profile).
 *
 * ## Wiring status: skeleton, no native loader
 *
 * This owner is not constructed by production code. It allocates engine/session identity and
 * lifecycle bookkeeping only: [createEngine] never reads model bytes and therefore reports
 * `isReady == false` for every engine it creates. Callers must treat a non-ready engine as "not
 * loaded" and fail explicitly instead of presenting it as a successful load.
 */
class BackendRuntimeOwner(
    override val backendKind: String,
    override val epoch: RuntimeEpoch,
    val initialAdvisory: HardwareProbeAdvisory = HardwareProbeAdvisory(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Real loader hook: when present, [createEngine] returns the engine this factory built (e.g.
     * the MNN chat runtime port, whose loader actually reads model bytes) instead of the
     * bookkeeping-only skeleton engine. An absent factory keeps the audited failure mode above:
     * `isReady == false` and an explicit load failure in ModelLifecycleFacade.
     */
    private val engineFactory: ((engineId: String, modelPath: String) -> EnginePort)? = null
) : BackendPort {

    private val initialized = AtomicBoolean(false)
    private val loadedEngines = ConcurrentHashMap<String, EnginePort>()

    /**
     * Monotonic per-owner engine sequence. The previous identity was derived from the wall clock and
     * the file basename, so two engines for same-named files created in the same millisecond shared
     * one identity and the second silently replaced (and leaked) the first.
     */
    private val engineSequence = AtomicLong(0L)

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
        val engineId = "engine-$backendKind-${engineSequence.incrementAndGet()}-${clock()}-${File(modelPath).name}"

        engineFactory?.let { factory ->
            val engine = factory(engineId, modelPath)
            loadedEngines[engineId] = engine
            return engine
        }

        val engine = object : EnginePort {
            private val sessions = ConcurrentHashMap<String, SessionPort>()
            @Volatile private var closed = false

            override val engineId: String = engineId
            override val modelPath: String = modelPath

            /**
             * Always false: this skeleton has no wired native loader, so no model bytes were read
             * and no engine can legitimately claim to be loaded.
             */
            override val isReady: Boolean get() = false

            override fun createSession(sessionId: String): SessionPort {
                check(!closed) { "Engine $engineId is unloaded." }
                val session = EngineSessionOwner(sessionId)
                // Compare-and-put, never a plain put: a second session for an id that is already
                // live used to replace (and thereby leak) the first one, which then became
                // unreachable for unload(). The rejection is a conflict, not a device/profile
                // decision, and the freshly built session holds no resource.
                if (sessions.putIfAbsent(sessionId, session) != null) {
                    session.close()
                    throw IllegalStateException(
                        "Engine $engineId already has a live session '$sessionId'; " +
                            "refusing to replace it."
                    )
                }
                return session
            }

            override fun unload() {
                closed = true
                var firstFailure: Throwable? = null
                for ((_, session) in sessions) {
                    try {
                        session.close()
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                    }
                }
                sessions.clear()
                firstFailure?.let { failure ->
                    throw IllegalStateException("Engine $engineId could not close every session.", failure)
                }
            }

            override fun close() {
                unload()
            }
        }

        loadedEngines[engineId] = engine
        return engine
    }

    /**
     * Releases one engine this owner created and stops tracking it.
     *
     * The production MNN runner calls this when a newer load supersedes an engine object: the
     * superseded bookkeeping must not accumulate for the process lifetime, and a superseded engine
     * never issues a native unload for the model the newer load just installed.
     * Returns false when the id is unknown (already released).
     */
    fun releaseEngine(engineId: String): Boolean {
        val engine = loadedEngines.remove(engineId) ?: return false
        engine.close()
        return true
    }

    override fun shutdown() {
        if (!initialized.compareAndSet(true, false)) return
        val engines = loadedEngines.values.toList()
        loadedEngines.clear()
        var firstFailure: Throwable? = null
        for (engine in engines) {
            try {
                engine.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure ->
            throw IllegalStateException("Backend $backendKind could not close every engine.", failure)
        }
    }

    override fun close() {
        shutdown()
    }
}

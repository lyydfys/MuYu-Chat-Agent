package com.muyuchat.mca.facade

import com.muyuchat.core.engine.runtime.BackendPort
import com.muyuchat.core.engine.runtime.EnginePort
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Facade for model lifecycle operations (load, unload, query).
 *
 * ## Wiring status: no production caller and no native loader
 *
 * Nothing in the application, Local API, or workers constructs this facade, and the backends it can
 * be given have no wired native loader. [loadModel] therefore fails explicitly instead of returning
 * an engine that claims to be ready without having loaded anything.
 */
class ModelLifecycleFacade(
    private val supervisor: RuntimeSupervisor
) {
    private val activeEngines = ConcurrentHashMap<String, EnginePort>()

    /**
     * Loads a model into the specified backend.
     *
     * A backend that cannot actually load the model must fail here. Reporting success while nothing
     * was loaded is exactly the fake-ready defect: the caller would publish "model loaded" to the UI
     * and only discover the truth on the first generation.
     */
    fun loadModel(backendKind: String, modelPath: String): EnginePort {
        val file = File(modelPath)
        require(file.exists() && file.isFile) { "Model file does not exist: $modelPath" }

        val backend = supervisor.getBackend(backendKind)
            ?: throw IllegalStateException("Backend $backendKind is not available or registered.")

        val engine = backend.createEngine(modelPath)
        if (!engine.isReady) {
            // No wired native loader produced this engine: release it and fail clearly rather than
            // returning an unloaded engine as a successful load.
            runCatching { engine.close() }
            throw UnsupportedOperationException(
                "Backend $backendKind did not load ${file.name}: no native loader is wired for this skeleton."
            )
        }
        activeEngines[engine.engineId] = engine
        return engine
    }

    /**
     * Unloads an engine by its ID.
     */
    fun unloadModel(engineId: String): Boolean {
        val engine = activeEngines.remove(engineId) ?: return false
        engine.close()
        return true
    }

    /**
     * Checks if a model engine is loaded.
     */
    fun isModelLoaded(engineId: String): Boolean {
        return activeEngines[engineId]?.isReady == true
    }

    /**
     * Unloads all currently loaded models, attempting every close even when one throws.
     */
    fun unloadAll() {
        val engines = activeEngines.values.toList()
        activeEngines.clear()
        var firstFailure: Throwable? = null
        for (engine in engines) {
            try {
                engine.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { failure ->
            throw IllegalStateException("ModelLifecycleFacade could not unload every engine.", failure)
        }
    }
}

package com.muyuchat.mca.facade

import com.muyuchat.core.engine.runtime.BackendPort
import com.muyuchat.core.engine.runtime.EnginePort
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Facade for model lifecycle operations (load, unload, query).
 */
class ModelLifecycleFacade(
    private val supervisor: RuntimeSupervisor
) {
    private val activeEngines = ConcurrentHashMap<String, EnginePort>()

    /**
     * Loads a model into the specified backend.
     */
    fun loadModel(backendKind: String, modelPath: String): EnginePort {
        val file = File(modelPath)
        require(file.exists() && file.isFile) { "Model file does not exist: $modelPath" }

        val backend = supervisor.getBackend(backendKind)
            ?: throw IllegalStateException("Backend $backendKind is not available or registered.")

        val engine = backend.createEngine(modelPath)
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
     * Unloads all currently loaded models.
     */
    fun unloadAll() {
        for ((_, engine) in activeEngines) {
            engine.close()
        }
        activeEngines.clear()
    }
}

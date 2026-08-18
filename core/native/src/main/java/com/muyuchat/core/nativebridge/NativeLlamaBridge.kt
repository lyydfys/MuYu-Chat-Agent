package com.muyuchat.core.nativebridge

class NativeLlamaBridge {
    companion object {
        const val LOAD_SIGNATURE_MISMATCH = -11

        val loadError: Throwable? = runCatching {
            System.loadLibrary("mca_native")
        }.exceptionOrNull()

        val isAvailable: Boolean
            get() = loadError == null
    }

    external fun initBackends(nativeLibDir: String)
    external fun loadModel(modelPath: String, paramsJson: String): Int
    external fun unloadModel()
    external fun beginCompletion(messagesJson: String, paramsJson: String): Int
    /** Begins completion while opportunistically restoring/saving a fixed prefix state. */
    external fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        restoreStatePath: String?,
        writeStatePath: String?,
        fixedSystemPrompt: String,
        fullSessionState: Boolean
    ): Int
    /**
     * Lock-free snapshot updated by native prompt batches while beginCompletion
     * is still running. A zero total means that this runtime cannot report an
     * exact prefill total for the current request.
     */
    external fun getPrefillProgressJson(): String
    /** Clears the prior request's lock-free prefill snapshot before a new begin. */
    external fun resetPrefillProgress()
    external fun generateNextChunk(): String?
    /** Drops all text-only KV/checkpoint state after a conversation edit. */
    external fun invalidateTextContext()
    external fun requestStop()
    external fun requestStopIfActive(): Boolean
    external fun getRuntimeStatsJson(): String
    external fun shutdown()
}


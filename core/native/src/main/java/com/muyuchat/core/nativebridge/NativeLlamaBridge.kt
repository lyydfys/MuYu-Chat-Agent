package com.muyuchat.core.nativebridge

class NativeLlamaBridge {
    companion object {
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
    external fun generateNextChunk(): String?
    external fun requestStop()
    external fun getRuntimeStatsJson(): String
    external fun shutdown()
}


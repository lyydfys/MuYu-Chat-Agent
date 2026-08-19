package com.muyuchat.core.nativebridge

class NativeMnnBridge {
    companion object {
        const val LOAD_SIGNATURE_MISMATCH = -11

        val loadError: Throwable? = runCatching {
            System.loadLibrary("mca_mnn_native")
        }.exceptionOrNull()

        val isAvailable: Boolean
            get() = loadError == null

        val runnerReady: Boolean
            get() = isAvailable && runCatching { NativeMnnBridge().isRunnerReady() }.getOrDefault(false)
    }

    external fun isRunnerReady(): Boolean
    external fun initBackends(nativeLibDir: String)
    external fun loadModel(configPath: String, paramsJson: String): Int
    external fun unloadModel()
    external fun beginCompletion(messagesJson: String, paramsJson: String): Int
    external fun generateNextChunk(): String?
    external fun requestStop()
    external fun requestStopIfActive(): Boolean
    external fun getPrefillProgressJson(): String
    external fun resetPrefillProgress()
    external fun getRuntimeStatsJson(): String
    external fun shutdown()
}

package com.muyuchat.core.nativebridge

class NativeMnnBridge {
    companion object {
        const val LOAD_SIGNATURE_MISMATCH = -11

        /**
         * Failure to load `libmca_mnn_native.so` itself. Only this condition means
         * "the MNN bridge is not packaged in this APK".
         */
        private val libraryLoadError: Throwable? = runCatching {
            System.loadLibrary("mca_mnn_native")
        }.exceptionOrNull()

        /**
         * Linkage failure of the capability probe. A JNI entrypoint missing from
         * an otherwise loadable library is a packaging/link defect, not "this APK
         * has no MNN capability", so it is reported through [loadError] instead of
         * being silently folded into `runnerReady == false`.
         */
        private val entryPointLoadError: Throwable? = libraryLoadError ?: runCatching {
            NativeMnnBridge().isRunnerReady()
        }.exceptionOrNull()

        val loadError: Throwable?
            get() = libraryLoadError ?: entryPointLoadError

        val isAvailable: Boolean
            get() = libraryLoadError == null

        val runnerReady: Boolean
            get() = libraryLoadError == null && entryPointLoadError == null &&
                runCatching { NativeMnnBridge().isRunnerReady() }.getOrDefault(false)
    }

    external fun isRunnerReady(): Boolean
    external fun initBackends(nativeLibDir: String)
    external fun loadModel(configPath: String, paramsJson: String): Int
    external fun unloadModel()
    external fun beginCompletion(messagesJson: String, paramsJson: String): Int
    external fun generateNextChunk(): String?
    external fun requestStop()
    external fun requestStopIfActive(): Boolean
    external fun isGenerationRunning(): Boolean
    external fun getPrefillProgressJson(): String
    external fun resetPrefillProgress()
    external fun getRuntimeStatsJson(): String
    external fun shutdown()
}

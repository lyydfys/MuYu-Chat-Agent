package com.muyuchat.core.nativebridge

class NativeMnnDiffusionBridge {
    companion object {
        val loadError: Throwable? = runCatching {
            System.loadLibrary("mca_mnn_native")
        }.exceptionOrNull()

        val isAvailable: Boolean
            get() = loadError == null

        val runnerReady: Boolean
            get() = isAvailable && runCatching { NativeMnnDiffusionBridge().isRunnerReady() }.getOrDefault(false)
    }

    external fun isRunnerReady(): Boolean
    external fun inspectBundle(bundleRoot: String): String
    external fun runUnetSmoke(bundleRoot: String, backendMode: String): String
    external fun encodeSd15PromptEmbeddings(
        bundleRoot: String,
        prompt: String,
        outputPath: String,
        backendMode: String,
        threads: Int,
        tokenEmbeddingMode: String
    ): String
    external fun encodeSdxlPromptConditioning(
        bundleRoot: String,
        prompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        backendMode: String,
        threads: Int
    ): String
    external fun generate(
        bundleRoot: String,
        paramsJson: String,
        outputPath: String
    ): String
    external fun getProgress(): String
    external fun cancel()
    external fun getRuntimeStatsJson(): String
}

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
    /**
     * Tokenizes a CLIP prompt into the unconditional + conditional pair used
     * by QNN Stable Diffusion text_encoder graphs. The returned array contains
     * exactly 154 ids (77 negative, followed by 77 positive) for the default
     * SD1.5/SD2.1 CLIP configuration, or an empty array when the sidecar
     * tokenizer cannot be loaded.
     */
    external fun tokenizePromptTokenIds(bundleRoot: String, prompt: String): IntArray

    /**
     * Parameterized form for bundles whose tokenizer vocabulary is provided by
     * a shared sidecar. This keeps the tokenizer reusable for SD1.5/SD2.1 and
     * avoids baking a per-model or per-device admission rule into the bridge.
     */
    external fun tokenizePromptTokenIdsWithConfig(
        bundleRoot: String,
        prompt: String,
        tokenizerRoot: String,
        bosId: Int,
        eosId: Int,
        maxTokens: Int
    ): IntArray
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

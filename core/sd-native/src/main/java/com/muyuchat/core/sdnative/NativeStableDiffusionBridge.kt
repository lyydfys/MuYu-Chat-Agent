package com.muyuchat.core.sdnative

class NativeStableDiffusionBridge {
    companion object {
        val loadError: Throwable? = runCatching {
            System.loadLibrary("mca_sd_native")
        }.exceptionOrNull()

        val isAvailable: Boolean
            get() = loadError == null
    }

    external fun generate(
        modelPath: String,
        bundleRoot: String,
        paramsJson: String,
        outputPath: String
    ): String

    external fun upscale(
        upscalerPath: String,
        upscalerRoot: String,
        inputPath: String,
        paramsJson: String,
        outputPath: String
    ): String

    external fun getSystemInfo(): String
    external fun getProgress(): String
    external fun getNativeConfig(): String

    /**
     * Measures one prompt with stable-diffusion.cpp's production tokenizer
     * without loading a diffusion graph. Unsupported tokenizer kinds return an
     * error JSON instead of an estimated count.
     */
    external fun measurePromptTokens(
        tokenizerKind: String,
        prompt: String,
        maxTokens: Int
    ): String

    external fun cancel()
    external fun shutdown()
}

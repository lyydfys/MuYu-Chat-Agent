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

    /**
     * One fused Wan2.2 TI2V-5B generation. [requestJson] carries explicit staged paths and typed
     * numeric fields; the returned JSON reports `succeeded`/`cancelled`/`failed` plus the frame
     * spool. The native layer writes PPM frames one by one and frees each decoded frame after the
     * write; the full native decode peak is NOT removed by that (proposal §4.2/§6.5).
     */
    external fun generateVideo(requestJson: String): String

    /**
     * Push variant of [generateVideo]: every decoded frame is handed to [consumer] on the calling
     * thread instead of being spooled, so the caller can encode as the model decodes and no frame
     * reaches the disk. Returning false from the consumer aborts the run (status "cancelled").
     */
    external fun generateVideoStreaming(requestJson: String, consumer: VideoFrameConsumer): String

    fun interface VideoFrameConsumer {
        fun onFrame(index: Int, rgb: ByteArray): Boolean
    }

    /** Honest sampler progress (`{\"phase\",\"step\",\"steps\"}`); no synthetic percentages. */
    external fun getVideoProgress(): String

    /** Cooperative cancel of the current video attempt (DSO-global, isolated in :local_video). */
    external fun cancelVideo()

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

    /** Exact Anima Qwen2/T5 token IDs, weights, and masks using stable-diffusion.cpp tokenizers. */
    external fun tokenizeAnimaPrompt(prompt: String, qwenMaxTokens: Int, t5MaxTokens: Int): String

    external fun cancel()
    external fun shutdown()
}

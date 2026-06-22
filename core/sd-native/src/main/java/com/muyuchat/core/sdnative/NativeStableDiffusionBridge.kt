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

    external fun getSystemInfo(): String
    external fun getProgress(): String
    external fun getNativeConfig(): String
    external fun cancel()
    external fun shutdown()
}

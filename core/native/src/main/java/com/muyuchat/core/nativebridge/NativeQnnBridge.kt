package com.muyuchat.core.nativebridge

class NativeQnnBridge {
    companion object {
        val loadError: Throwable? = runCatching {
            System.loadLibrary("mca_qnn_native")
        }.exceptionOrNull()

        val isAvailable: Boolean
            get() = loadError == null

        val runnerReady: Boolean
            get() = isAvailable && runCatching { NativeQnnBridge().isRunnerReady() }.getOrDefault(false)
    }

    external fun isRunnerReady(): Boolean
    external fun inspectRuntime(runtimeDirsJson: String): String
    external fun inspectBundle(bundleRoot: String): String
    external fun runImageSmoke(bundleRoot: String, runtimeDirsJson: String, smokeSpecJson: String): String
    external fun runImagePipelineProbe(
        bundleRoot: String,
        runtimeDirsJson: String,
        paramsJson: String,
        outputPath: String
    ): String
    external fun runImageSemanticGenerate(
        bundleRoot: String,
        runtimeDirsJson: String,
        paramsJson: String,
        embeddingsPath: String,
        outputPath: String
    ): String
    external fun runSdxlUnetPhase(
        bundleRoot: String,
        runtimeProfileJson: String,
        paramsJson: String,
        embeddingsPath: String,
        initialLatentPath: String,
        latentPath: String
    ): String
    external fun runSdxlEncoderPhase(
        bundleRoot: String,
        runtimeProfileJson: String,
        paramsJson: String,
        inputTensorPath: String,
        latentPath: String,
        expectedVaeEncoderContextSha256: String
    ): String
    external fun runSdxlVaePhase(
        bundleRoot: String,
        runtimeProfileJson: String,
        paramsJson: String,
        latentPath: String,
        outputPath: String
    ): String
    external fun cancelImageGeneration(): Boolean
    external fun getImageGenerationProgressJson(): String
    external fun runVisionSmoke(bundleRoot: String, runtimeDirsJson: String, smokeSpecJson: String): String
    external fun getRuntimeStatsJson(): String
}

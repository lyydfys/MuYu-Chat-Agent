package com.muyuchat.mca

internal enum class ImageSchedulerAlgorithm {
    DPMPP_2M,
    EULER,
    EULER_A,
    DDIM,
    PNDM_PLMS,
    LCM,
    FLOW_MATCH
}

internal enum class ImagePredictionType { EPSILON, V_PREDICTION, SAMPLE, FLOW }
internal enum class ImageTimestepSpacing { LEADING, TRAILING, LINSPACE }
internal enum class ImageNoiseSchedule { LINEAR, SCALED_LINEAR, SIGMA, MODEL_DECLARED }
internal enum class ImageFinalSigmaType { ZERO, SIGMA_MIN }
internal enum class ImageRngContract { MT19937, RUNTIME_NATIVE }

internal data class ImageSchedulerContract(
    val algorithm: ImageSchedulerAlgorithm,
    val predictionType: ImagePredictionType,
    val numTrainTimesteps: Int = 1_000,
    val noiseSchedule: ImageNoiseSchedule = ImageNoiseSchedule.MODEL_DECLARED,
    val betaStart: Double? = null,
    val betaEnd: Double? = null,
    val timestepSpacing: ImageTimestepSpacing = ImageTimestepSpacing.LEADING,
    val stepsOffset: Int = 0,
    val setAlphaToOne: Boolean = true,
    val skipPrkSteps: Boolean = false,
    val finalSigmaType: ImageFinalSigmaType = ImageFinalSigmaType.ZERO,
    val clipSample: Boolean = false,
    val clipSampleRange: Double = 1.0,
    val thresholding: Boolean = false,
    val eta: Double = 0.0,
    val lowerOrderFinal: Boolean = true,
    val initNoiseSigma: Double = 1.0,
    val scaleModelInput: Boolean = false,
    val order: Int = 1,
    val defaultSteps: Int,
    val minSteps: Int,
    val maxSteps: Int,
    val rng: ImageRngContract = ImageRngContract.MT19937,
    val seedBits: Int = 32
) {
    fun expectedTimestepCount(steps: Int): Int = when {
        algorithm != ImageSchedulerAlgorithm.PNDM_PLMS -> steps
        skipPrkSteps -> steps + 1
        else -> steps + 9 // 12 PRK warmup calls plus the remaining steps - 3 PLMS calls.
    }
}

internal enum class ImageTensorLayout { NCHW, NHWC, BSH, BCHW, RUNTIME_NATIVE }

internal data class ImageLatentContract(
    val channels: Int,
    val downsampleFactor: Int,
    val schedulerLayout: ImageTensorLayout,
    val graphLayout: ImageTensorLayout,
    val initialShape: List<Int>,
    val dataType: ImageEmbeddingDiskDataType = ImageEmbeddingDiskDataType.FP32
)

internal enum class ImageVaeScalingLocation { HOST_BEFORE_GRAPH, GRAPH_INTERNAL, NONE, RUNTIME_NATIVE }
internal enum class ImagePixelRange { NEGATIVE_ONE_TO_ONE, ZERO_TO_ONE, ZERO_TO_255, RUNTIME_NATIVE }
internal enum class ImageChannelOrder { RGB, BGR, RUNTIME_NATIVE }

internal data class ImageVaeContract(
    val scalingLocation: ImageVaeScalingLocation,
    val scalingFactor: Double,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val inputLayout: ImageTensorLayout,
    val outputLayout: ImageTensorLayout,
    val outputRange: ImagePixelRange,
    val channelOrder: ImageChannelOrder
)

internal enum class ImageTensorDataType { INT32, UINT16, FP16, FP32, BF16, RUNTIME_NATIVE }

internal data class ImageTensorContract(
    val role: String,
    val name: String,
    val shape: List<Int>,
    val dataType: ImageTensorDataType,
    val scale: Double? = null,
    val zeroPoint: Int? = null
)

internal data class ImageGraphArtifactContract(
    val relativePath: String,
    val graphName: String = "model",
    val inputs: List<ImageTensorContract> = emptyList(),
    val outputs: List<ImageTensorContract> = emptyList()
)

internal enum class ImageWorkerStrategy {
    IN_PROCESS,
    DEDICATED_WORKER,
    SPLIT_UNET_VAE,
    SHARED_UNET_VAE,
    SHARED_TEXT_UNET_VAE
}

internal data class ImageGraphContract(
    val textEncoder: ImageGraphArtifactContract? = null,
    val unet: ImageGraphArtifactContract? = null,
    val vae: ImageGraphArtifactContract? = null,
    val vaeEncoder: ImageGraphArtifactContract? = null,
    val controlNet: ImageGraphArtifactContract? = null,
    val schedulerSidecar: String? = null,
    val tokenizerSidecar: String? = null,
    val configSidecars: List<String> = emptyList(),
    val qnnSdk: String? = null,
    val htpArch: Int? = null,
    val contextMetadataFingerprint: String? = null,
    val workerStrategy: ImageWorkerStrategy
)

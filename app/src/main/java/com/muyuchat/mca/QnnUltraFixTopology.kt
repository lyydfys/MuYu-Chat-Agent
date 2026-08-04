package com.muyuchat.mca

import kotlin.math.abs

internal const val QNN_SHARED_ULTRAFIX_TILE_SIZE = 512
internal const val QNN_SHARED_ULTRAFIX_MIN_SIDE = 512
internal const val QNN_SHARED_ULTRAFIX_MAX_SIDE = 2_048
internal const val QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE = 64
internal const val QNN_SPLIT_SDXL_ULTRAFIX_TILE_SIZE = 1_024
internal const val QNN_SPLIT_SDXL_ULTRAFIX_MIN_SIDE = 1_024
internal const val QNN_SPLIT_SDXL_ULTRAFIX_MAX_SIDE = 2_048
internal const val QNN_SPLIT_SDXL_ULTRAFIX_DIMENSION_MULTIPLE = 64

private val QNN_ULTRAFIX_SCHEDULERS = setOf(
    ImageSchedulerAlgorithm.DPMPP_2M,
    ImageSchedulerAlgorithm.EULER,
    ImageSchedulerAlgorithm.DDIM
)

/**
 * UltraFix admission is derived from the graph topology that will actually be executed. The
 * fixed tensor shapes are deliberately kept at the native tile size; the requested canvas is a
 * host-side tiled envelope and must never rewrite the loaded graph contract. Device identity,
 * recommendation ids, validation history, and chipset profiles are intentionally absent.
 */
internal fun ImageExecutionProfile.hasExecutableQnnUltraFixTopology(): Boolean {
    if (runtime != LocalImageRuntime.QNN_HTP ||
        task != ImageTask.TEXT_TO_IMAGE ||
        capabilities.requiresControlImage ||
        graph.vaeEncoder == null || graph.unet == null || graph.vae == null ||
        scheduler.predictionType != ImagePredictionType.EPSILON ||
        scheduler.algorithm !in QNN_ULTRAFIX_SCHEDULERS ||
        latent.channels != 4 || latent.downsampleFactor != 8 ||
        vae.scalingLocation != ImageVaeScalingLocation.HOST_BEFORE_GRAPH
    ) {
        return false
    }
    return when (graph.workerStrategy) {
        ImageWorkerStrategy.SHARED_UNET_VAE,
        ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->
            family in setOf(LocalImageModelFamily.SD15, LocalImageModelFamily.SD21) &&
                latent.initialShape == listOf(1, 4, 64, 64) &&
                vae.inputShape == listOf(1, 4, 64, 64) &&
                vae.outputShape == listOf(1, 3, 512, 512) &&
                abs(vae.scalingFactor - 0.18215) <= 1.0e-9

        ImageWorkerStrategy.SPLIT_UNET_VAE ->
            family == LocalImageModelFamily.SDXL &&
                latent.initialShape == listOf(1, 4, 128, 128) &&
                vae.inputShape == listOf(1, 4, 128, 128) &&
                vae.outputShape == listOf(1, 3, 1024, 1024) &&
                abs(vae.scalingFactor - 0.13025) <= 1.0e-9

        ImageWorkerStrategy.IN_PROCESS,
        ImageWorkerStrategy.DEDICATED_WORKER -> false
    }
}

internal fun ImageExecutionProfile.hasExecutableSharedQnnUltraFixTopology(): Boolean =
    hasExecutableQnnUltraFixTopology() && graph.workerStrategy in setOf(
        ImageWorkerStrategy.SHARED_UNET_VAE,
        ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
    )

internal fun ImageExecutionProfile.hasExecutableSplitSdxlQnnUltraFixTopology(): Boolean =
    hasExecutableQnnUltraFixTopology() && graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE

/**
 * Remove stale manifest capability claims and publish the exact bounds implied by the loaded
 * executable topology. This is intentionally generic: two packages with the same topology get
 * the same capability regardless of recommendation id, chipset, or validation history.
 */
internal fun ImageExecutionProfile.withTopologyDerivedQnnUltraFixCapability(): ImageExecutionProfile {
    if (runtime != LocalImageRuntime.QNN_HTP) return this
    val topology = when {
        hasExecutableSharedQnnUltraFixTopology() -> QnnUltraFixTopologyContract(
            minSide = QNN_SHARED_ULTRAFIX_MIN_SIDE,
            maxSide = QNN_SHARED_ULTRAFIX_MAX_SIDE,
            dimensionMultiple = QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE,
            requiredTileSize = QNN_SHARED_ULTRAFIX_TILE_SIZE
        )
        hasExecutableSplitSdxlQnnUltraFixTopology() -> QnnUltraFixTopologyContract(
            minSide = QNN_SPLIT_SDXL_ULTRAFIX_MIN_SIDE,
            maxSide = QNN_SPLIT_SDXL_ULTRAFIX_MAX_SIDE,
            dimensionMultiple = QNN_SPLIT_SDXL_ULTRAFIX_DIMENSION_MULTIPLE,
            requiredTileSize = QNN_SPLIT_SDXL_ULTRAFIX_TILE_SIZE
        )
        else -> null
    }
    val capabilities = if (topology == null) {
        capabilities.copy(
            supportsUltraFix = false,
            ultraFixMinWidth = 0,
            ultraFixMaxWidth = 0,
            ultraFixMinHeight = 0,
            ultraFixMaxHeight = 0,
            ultraFixWidthMultiple = 0,
            ultraFixHeightMultiple = 0,
            ultraFixRequiredTileSize = 0
        )
    } else {
        capabilities.copy(
            supportsUltraFix = true,
            ultraFixMinWidth = topology.minSide,
            ultraFixMaxWidth = topology.maxSide,
            ultraFixMinHeight = topology.minSide,
            ultraFixMaxHeight = topology.maxSide,
            ultraFixWidthMultiple = topology.dimensionMultiple,
            ultraFixHeightMultiple = topology.dimensionMultiple,
            ultraFixRequiredTileSize = topology.requiredTileSize
        )
    }
    return copy(capabilities = capabilities)
}

private data class QnnUltraFixTopologyContract(
    val minSide: Int,
    val maxSide: Int,
    val dimensionMultiple: Int,
    val requiredTileSize: Int
)

package com.muyuchat.mca

/**
 * Topology-only QNN inpaint admission.
 *
 * A regular four-channel UNet is executable through Local Dream-compatible
 * per-step source-latent blending when a real VAE encoder and decoder share the
 * product topology. Explicit nine-channel and separate-mask graph contracts are
 * detected as stricter future paths. This helper intentionally does not inspect
 * recommendation ids, chipset profiles, device certification, or smoke history.
 */
internal enum class QnnInpaintMaskTopology {
    NONE,
    LATENT_BLEND_4,
    CONCATENATED_LATENT_9,
    SEPARATE_MASK_INPUT,
    ;

    val wireName: String
        get() = when (this) {
            NONE -> "none"
            LATENT_BLEND_4 -> "latent_blend_4"
            CONCATENATED_LATENT_9 -> "concatenated_latent_9"
            SEPARATE_MASK_INPUT -> "separate_mask_input"
        }

    val requiresNativeMaskBinding: Boolean
        get() = this == CONCATENATED_LATENT_9 || this == SEPARATE_MASK_INPUT

    val requiresMaskedImageLatent: Boolean
        get() = this == CONCATENATED_LATENT_9
}

internal enum class QnnInpaintTensorLayout {
    UNKNOWN,
    NCHW,
}

internal data class QnnInpaintTopologyInspection(
    val topology: QnnInpaintMaskTopology = QnnInpaintMaskTopology.NONE,
    val layout: QnnInpaintTensorLayout = QnnInpaintTensorLayout.UNKNOWN,
    val sampleInputName: String? = null,
    val maskInputName: String? = null,
    val sampleChannels: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val reason: String,
) {
    val supported: Boolean
        get() = topology != QnnInpaintMaskTopology.NONE &&
            layout == QnnInpaintTensorLayout.NCHW &&
            sampleInputName != null && width > 0 && height > 0

    /** True only when the loaded UNet itself consumes the mask. */
    val requiresNativeMaskBinding: Boolean
        get() = topology.requiresNativeMaskBinding

    /** The conventional nine-channel graph also consumes a VAE-encoded masked RGB image. */
    val requiresMaskedImageLatent: Boolean
        get() = topology.requiresMaskedImageLatent
}

internal object QnnInpaintTopology {
    fun inspect(inputs: List<QnnSmokeTensorSpec>): QnnInpaintTopologyInspection {
        if (inputs.isEmpty()) {
            return unsupported("QNN UNet exposes no inputs.")
        }
        val sampleIndex = likelySampleIndex(inputs)
        if (sampleIndex == AMBIGUOUS_INDEX) {
            return unsupported("QNN inpaint sample input is ambiguous; a semantic sample/latent name is required.")
        }
        if (sampleIndex < 0 || sampleIndex >= inputs.size) {
            return unsupported("QNN UNet has no four- or nine-channel latent sample input.")
        }
        val sample = inputs[sampleIndex]
        val sampleShape = spatialShape(sample.shape)
            ?: return unsupported("QNN inpaint sample tensor must be a rank-4 NCHW image tensor.")
        val semanticMaskIndices = inputs.indices.filter { index ->
            index != sampleIndex && containsMaskToken(inputs[index].name)
        }
        if (semanticMaskIndices.size > 1) {
            return unsupported("QNN UNet exposes multiple semantic mask inputs; the contract is ambiguous.")
        }
        if (sampleShape.channels == 9) {
            if (semanticMaskIndices.isNotEmpty()) {
                return unsupported(
                    "QNN UNet exposes both a concatenated nine-channel sample and a separate semantic mask input.",
                )
            }
            return QnnInpaintTopologyInspection(
                topology = QnnInpaintMaskTopology.CONCATENATED_LATENT_9,
                layout = sampleShape.layout,
                sampleInputName = sample.name,
                sampleChannels = sampleShape.channels,
                width = sampleShape.width,
                height = sampleShape.height,
                reason = "UNet sample exposes the conventional four-latent + one-mask + four-masked-latent contract.",
            )
        }
        if (sampleShape.channels != 4) {
            return unsupported(
                "QNN UNet latent sample must expose four channels for a separate-mask contract or nine concatenated channels.",
            )
        }

        if (semanticMaskIndices.isEmpty()) {
            return QnnInpaintTopologyInspection(
                topology = QnnInpaintMaskTopology.LATENT_BLEND_4,
                layout = sampleShape.layout,
                sampleInputName = sample.name,
                sampleChannels = sampleShape.channels,
                width = sampleShape.width,
                height = sampleShape.height,
                reason = "UNet exposes a regular four-channel latent; inpaint is applied by per-step source-latent blending.",
            )
        }
        val maskIndex = semanticMaskIndices.single()
        val candidate = spatialShape(inputs[maskIndex].shape)
            ?: return unsupported("QNN mask input must be a rank-4 NCHW tensor.")
        if (candidate.channels != 1 ||
            candidate.width != sampleShape.width ||
            candidate.height != sampleShape.height ||
            candidate.layout != sampleShape.layout
        ) {
            return unsupported("QNN mask input must be one channel with the same spatial shape and layout as the latent sample.")
        }
        return QnnInpaintTopologyInspection(
            topology = QnnInpaintMaskTopology.SEPARATE_MASK_INPUT,
            layout = sampleShape.layout,
            sampleInputName = sample.name,
            maskInputName = inputs[maskIndex].name,
            sampleChannels = sampleShape.channels,
            width = sampleShape.width,
            height = sampleShape.height,
            reason = "UNet exposes a separate one-channel mask tensor.",
        )
    }

    fun inspect(spec: QnnSmokeSpec): QnnInpaintTopologyInspection = inspect(spec.inputs)

    fun inspect(graph: ImageGraphArtifactContract): QnnInpaintTopologyInspection =
        inspect(
            graph.inputs.map { tensor ->
                QnnSmokeTensorSpec(
                    name = tensor.name,
                    role = tensor.role,
                    dataType = tensor.dataType.name,
                    shape = tensor.shape,
                )
            },
        )

    fun hasExecutableMaskTopology(spec: QnnSmokeSpec): Boolean = inspect(spec).supported

    private data class SpatialShape(
        val layout: QnnInpaintTensorLayout,
        val width: Int,
        val height: Int,
        val channels: Int,
    )

    private fun spatialShape(shape: List<Int>): SpatialShape? {
        if (shape.size != 4 || shape[0] != 1) return null
        val nchwChannels = shape[1]
        if (nchwChannels == 1 || nchwChannels == 4 || nchwChannels == 9) {
            if (shape[2] > 0 && shape[3] > 0) {
                return SpatialShape(
                    layout = QnnInpaintTensorLayout.NCHW,
                    width = shape[3],
                    height = shape[2],
                    channels = nchwChannels,
                )
            }
        }
        return null
    }

    private fun likelySampleIndex(inputs: List<QnnSmokeTensorSpec>): Int {
        inputs.forEachIndexed { index, tensor ->
            when (tensor.name.trim().lowercase()) {
                "sample", "latent", "noisy_sample", "model_input", "x" -> return index
            }
        }
        var candidate = -1
        inputs.forEachIndexed { index, tensor ->
            val shape = spatialShape(tensor.shape) ?: return@forEachIndexed
            if (shape.channels != 4 && shape.channels != 9) return@forEachIndexed
            if (candidate >= 0) return AMBIGUOUS_INDEX
            candidate = index
        }
        return candidate
    }

    private fun containsMaskToken(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in setOf(
                "mask",
                "inpaint_mask",
                "inpaintmask",
                "denoise_mask",
                "denoisemask",
                "mask_image",
                "masked_image",
                "maskinput",
                "mask_input",
                "masktensor",
                "mask_tensor",
                "conditioning_mask",
            )) {
            return true
        }
        lower.indices.forEach { index ->
            if (!lower.startsWith("mask", index)) return@forEach
            val end = index + 4
            val leftBoundary = index == 0 || !lower[index - 1].isLetterOrDigit()
            val rightBoundary = end == lower.length || !lower[end].isLetterOrDigit()
            if (leftBoundary && rightBoundary) return true
        }
        return false
    }

    private fun unsupported(reason: String): QnnInpaintTopologyInspection =
        QnnInpaintTopologyInspection(reason = reason)

    private const val AMBIGUOUS_INDEX = -3
}

internal fun QnnSmokeSpec.inspectInpaintTopology(): QnnInpaintTopologyInspection =
    QnnInpaintTopology.inspect(this)

internal fun ImageGraphArtifactContract.inspectInpaintTopology(): QnnInpaintTopologyInspection =
    QnnInpaintTopology.inspect(this)

internal fun ImageExecutionProfile.inspectQnnInpaintTopology(): QnnInpaintTopologyInspection =
    graph.unet?.let { unet ->
        if (unet.inputs.isNotEmpty()) {
            unet.inspectInpaintTopology()
        } else if (
            latent.channels == 4 && latent.downsampleFactor == 8 &&
            latent.schedulerLayout == ImageTensorLayout.NCHW &&
            latent.graphLayout == ImageTensorLayout.NCHW &&
            latent.initialShape.size == 4 && latent.initialShape[0] == 1 &&
            latent.initialShape[1] == 4 &&
            latent.initialShape[2] > 0 && latent.initialShape[3] > 0
        ) {
            QnnInpaintTopologyInspection(
                topology = QnnInpaintMaskTopology.LATENT_BLEND_4,
                layout = QnnInpaintTensorLayout.NCHW,
                sampleInputName = "runtime_inspected_sample",
                sampleChannels = 4,
                width = latent.initialShape[3],
                height = latent.initialShape[2],
                reason = "Profile declares a four-channel NCHW latent contract; native execution must confirm the loaded UNet tensor before running.",
            )
        } else {
            QnnInpaintTopologyInspection(
                reason = "QNN profile omits UNet input metadata and has no executable four-channel NCHW latent contract.",
            )
        }
    } ?: QnnInpaintTopologyInspection(reason = "QNN profile does not declare a UNet graph artifact.")

internal fun ImageExecutionProfile.hasExecutableQnnInpaintTopology(): Boolean {
    if (runtime != LocalImageRuntime.QNN_HTP ||
        graph.vaeEncoder == null || graph.unet == null || graph.vae == null
    ) {
        return false
    }
    val inspected = inspectQnnInpaintTopology()
    return when (graph.workerStrategy) {
        ImageWorkerStrategy.SHARED_UNET_VAE,
        ImageWorkerStrategy.SHARED_TEXT_UNET_VAE -> inspected.supported

        ImageWorkerStrategy.SPLIT_UNET_VAE ->
            family == LocalImageModelFamily.SDXL &&
                defaults.width == 1024 && defaults.height == 1024 &&
                latent.channels == 4 && latent.downsampleFactor == 8 &&
                latent.schedulerLayout == ImageTensorLayout.NCHW &&
                latent.graphLayout == ImageTensorLayout.NCHW &&
                latent.initialShape == listOf(1, 4, 128, 128) &&
                vae.inputShape == listOf(1, 4, 128, 128) &&
                vae.outputShape == listOf(1, 3, 1024, 1024) &&
                inspected.supported &&
                inspected.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
                inspected.layout == QnnInpaintTensorLayout.NCHW &&
                inspected.sampleChannels == 4 &&
                inspected.width == 128 && inspected.height == 128

        ImageWorkerStrategy.IN_PROCESS,
        ImageWorkerStrategy.DEDICATED_WORKER -> false
    }
}

package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * Collects package identity without consulting hardware. Exact catalog
 * matching may select a richer execution profile; unknown evidence remains a
 * normal generic-compatible import.
 */
internal fun localImageRecommendationEvidence(
    model: LocalImageModelRecord,
    bundleRoot: File?,
    manifestJson: JSONObject?
): ImageRecommendationEvidence {
    val manifestEvidence = manifestJson?.let(::localImageManifestRecommendationEvidence)
        ?: ImageRecommendationEvidence()
    val modelArtifactPaths = buildList {
        add(model.displayName)
        add(model.fileName)
        add(model.path)
        model.bundleRoot?.takeIf(String::isNotBlank)?.let(::add)
        bundleRoot?.absolutePath?.takeIf(String::isNotBlank)?.let(::add)
    }
    return ImageRecommendationEvidence(
        aliases = (listOf(model.id) + manifestEvidence.aliases).distinct(),
        sourceRepositories = (
            listOf(model.source).filter(String::isNotBlank) +
                manifestEvidence.sourceRepositories
            ).distinct(),
        artifactPaths = (modelArtifactPaths + manifestEvidence.artifactPaths).distinct()
    )
}

internal fun localImageManifestRecommendationEvidence(
    manifestJson: JSONObject
): ImageRecommendationEvidence {
    val aliases = buildList {
        manifestJson.optString("recommendationId").takeIf(String::isNotBlank)?.let(::add)
        manifestJson.optString("id").takeIf(String::isNotBlank)?.let(::add)
    }
    val sourceRepositories = buildList {
        manifestJson.optString("sourceRepo").takeIf(String::isNotBlank)?.let(::add)
        val components = manifestJson.optJSONArray("components")
        if (components != null) {
            for (index in 0 until components.length()) {
                val component = components.optJSONObject(index) ?: continue
                val role = component.optString("role").trim().uppercase()
                if (role in PRIMARY_IMAGE_COMPONENT_ROLES) {
                    component.optString("sourceRepo").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
    val artifactPaths = buildList {
        listOf("title", "recommendedFileName", "primary", "primaryFile").forEach { field ->
            manifestJson.optString(field).takeIf(String::isNotBlank)?.let(::add)
        }
        val components = manifestJson.optJSONArray("components")
        if (components != null) {
            for (index in 0 until components.length()) {
                val component = components.optJSONObject(index) ?: continue
                listOf("path", "fileName", "sourcePath").forEach { field ->
                    component.optString(field).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
    return ImageRecommendationEvidence(
        aliases = aliases.distinct(),
        sourceRepositories = sourceRepositories.distinct(),
        artifactPaths = artifactPaths.distinct()
    )
}

private val PRIMARY_IMAGE_COMPONENT_ROLES = setOf("DIFFUSION", "MODEL", "UNET", "TRANSFORMER")

internal fun discoverLocalImageExecutionCapabilities(
    root: File,
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): ImageCapabilityDiscovery? {
    if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION) return null
    val artifactSet = root.selectImageCapabilityArtifactSet(runtime, family) ?: return null
    val artifactDirectory = artifactSet.directory
    val tokenizerJson = artifactDirectory.findDirectExecutionArtifact("tokenizer.json")
    val tokenEmbedding = artifactDirectory.findDirectExecutionArtifact("token_emb.bin")
    val textEncoder = artifactDirectory.findDirectExecutionArtifact(*artifactSet.textEncoderNames)
    val unet = artifactDirectory.findDirectExecutionArtifact("unet.bin", "unet.mnn")
    val vae = artifactDirectory.findDirectExecutionArtifact(
        "vae.bin",
        "vae_decoder.bin",
        "vae_decoder.mnn",
        "vae.mnn"
    )
    val vaeEncoder = artifactDirectory.findDirectExecutionArtifact(
        "vae_encoder.bin",
        "vae_encoder.mnn"
    )
    if (tokenizerJson == null && textEncoder == null && unet == null && vae == null) return null
    val maxLength = 77
    val padId = 49_407
    val tokenizer = ImageTokenizerContract(
        backend = if (tokenizerJson != null) ImageTokenizerBackend.TOKENIZERS_CPP else ImageTokenizerBackend.MNN_MTOK,
        assets = tokenizerJson?.let { file ->
            listOf(ImageProfileAsset(file.relativeTo(root).invariantSeparatorsPath, file.sha256ForProfile()))
        }.orEmpty(),
        bosId = 49_406,
        eosId = 49_407,
        padId = padId,
        maxLength = maxLength,
        clip1PadRule = ImageClipPadRule.EOS,
        clip2PadRule = if (family == LocalImageModelFamily.SDXL) ImageClipPadRule.ZERO else null,
        supportsPromptWeighting = tokenizerJson != null,
        separateNegativePrompt = true
    )
    val width = when (family) {
        LocalImageModelFamily.SD21 -> 1_024
        LocalImageModelFamily.SDXL -> 2_048
        else -> 768
    }
    val textEncoderName = textEncoder?.name?.lowercase()
    val qnnGraphInternalVae = runtime == LocalImageRuntime.QNN_HTP &&
        textEncoderName == "text_encoder.bin" &&
        vae?.name.equals("vae.bin", ignoreCase = true)
    val mnnGraphInternalConditioning = runtime == LocalImageRuntime.MNN_DIFFUSION &&
        textEncoderName == "text_encoder.mnn"
    val tokenTableDataType = when (tokenEmbedding?.length()) {
        75_890_688L -> ImageEmbeddingDiskDataType.FP16
        151_781_376L -> ImageEmbeddingDiskDataType.FP32
        else -> null
    }
    val conditioningDataType = if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        resolveMnnConditioningDiskDataType(
            graphInternal = mnnGraphInternalConditioning,
            tokenEmbeddingByteSize = tokenEmbedding?.length()
        )
    } else {
        tokenTableDataType ?: if (qnnGraphInternalVae) {
            ImageEmbeddingDiskDataType.GRAPH_INTERNAL
        } else {
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE
        }
    }
    val conditioningConversion = when (conditioningDataType) {
        ImageEmbeddingDiskDataType.FP16 -> ImageEmbeddingConversionStrategy.NONE
        ImageEmbeddingDiskDataType.FP32 -> ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL -> ImageEmbeddingConversionStrategy.GRAPH_EXECUTION
        else -> ImageEmbeddingConversionStrategy.RUNTIME_NATIVE
    }
    return ImageCapabilityDiscovery(
        family = family,
        tokenizer = tokenizer,
        conditioning = ImageConditioningContract(
            diskDataType = conditioningDataType,
            exactByteSize = tokenEmbedding?.length()?.takeIf {
                conditioningDataType == ImageEmbeddingDiskDataType.FP16 ||
                    conditioningDataType == ImageEmbeddingDiskDataType.FP32
            },
            textEncoderInputShape = listOf(1, maxLength),
            textEncoderOutputShapes = if (family == LocalImageModelFamily.SDXL) {
                listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
            } else {
                listOf(listOf(1, maxLength, width))
            },
            conversionStrategy = conditioningConversion,
            dualEncoder = family == LocalImageModelFamily.SDXL,
            pooledOutput = family == LocalImageModelFamily.SDXL,
            concatenationOrder = if (family == LocalImageModelFamily.SDXL) {
                listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
            } else {
                listOf("negative", "positive")
            }
        ),
        vae = ImageVaeContract(
            scalingLocation = if (qnnGraphInternalVae) {
                ImageVaeScalingLocation.GRAPH_INTERNAL
            } else {
                ImageVaeScalingLocation.HOST_BEFORE_GRAPH
            },
            scalingFactor = if (family == LocalImageModelFamily.SDXL) 0.13025 else 0.18215,
            inputShape = if (family == LocalImageModelFamily.SDXL) listOf(1, 4, 128, 128) else listOf(1, 4, 64, 64),
            outputShape = if (family == LocalImageModelFamily.SDXL) listOf(1, 3, 1024, 1024) else listOf(1, 3, 512, 512),
            inputLayout = ImageTensorLayout.NCHW,
            outputLayout = ImageTensorLayout.NCHW,
            outputRange = ImagePixelRange.NEGATIVE_ONE_TO_ONE,
            channelOrder = ImageChannelOrder.RGB
        ),
        graph = ImageGraphContract(
            textEncoder = textEncoder?.toGraphArtifact(root),
            unet = unet?.toGraphArtifact(root),
            vae = vae?.toGraphArtifact(root),
            vaeEncoder = vaeEncoder?.toGraphArtifact(root),
            schedulerSidecar = File(artifactDirectory, "scheduler/scheduler_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            tokenizerSidecar = File(artifactDirectory, "tokenizer/tokenizer_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            workerStrategy = when {
                runtime == LocalImageRuntime.QNN_HTP &&
                    family == LocalImageModelFamily.SDXL &&
                    textEncoderName == "clip.mnn" -> ImageWorkerStrategy.SPLIT_UNET_VAE
                runtime == LocalImageRuntime.QNN_HTP &&
                    textEncoderName == "clip_v2.mnn" -> ImageWorkerStrategy.SHARED_UNET_VAE
                qnnGraphInternalVae -> ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
                else -> ImageWorkerStrategy.IN_PROCESS
            }
        )
    )
}

private data class ImageCapabilityArtifactSet(
    val directory: File,
    val textEncoderNames: Array<out String>
)

/**
 * Capability discovery must describe one physical graph bundle. Walking the root independently
 * for every component can splice unrelated or partially extracted directories into a profile
 * that no native process could load.
 */
private fun File.selectImageCapabilityArtifactSet(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): ImageCapabilityArtifactSet? {
    val completeClipBundle = when {
        runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL ->
            findCompleteExecutionArtifactDirectory(
                requiredNames = arrayOf(
                    "clip.mnn",
                    "clip_2.mnn",
                    "clip_2.mnn.weight",
                    "tokenizer.json",
                    "token_emb.bin",
                    "token_emb_2.bin",
                    "pos_emb.bin",
                    "pos_emb_2.bin"
                ),
                additionalRequirement = { directory ->
                    directory.hasCompleteDirectDiffusionGraph(runtime)
                }
            )?.let { directory ->
                ImageCapabilityArtifactSet(directory, arrayOf("clip.mnn"))
            }
        family in setOf(LocalImageModelFamily.SD15, LocalImageModelFamily.SD21) ->
            findCompleteExecutionArtifactDirectory(
                requiredNames = arrayOf(
                    "clip_v2.mnn",
                    "tokenizer.json",
                    "token_emb.bin",
                    "pos_emb.bin"
                ),
                additionalRequirement = { directory ->
                    directory.hasCompleteDirectDiffusionGraph(runtime)
                }
            )?.let { directory ->
                ImageCapabilityArtifactSet(directory, arrayOf("clip_v2.mnn"))
            }
        else -> null
    }
    if (completeClipBundle != null) return completeClipBundle

    val fallbackTextEncoderNames = if (runtime == LocalImageRuntime.QNN_HTP) {
        arrayOf("text_encoder.bin", "text_encoder.mnn")
    } else {
        arrayOf("text_encoder.mnn", "text_encoder.bin")
    }
    findExecutionArtifact(*fallbackTextEncoderNames)?.parentFile?.let { directory ->
        return ImageCapabilityArtifactSet(directory, fallbackTextEncoderNames)
    }
    val graphAnchor = findExecutionArtifact("unet.bin", "unet.mnn", "vae.bin", "vae_decoder.bin", "vae_decoder.mnn")
        ?: return null
    val graphDirectory = graphAnchor.parentFile ?: return null
    return ImageCapabilityArtifactSet(graphDirectory, fallbackTextEncoderNames)
}

private fun File.findCompleteExecutionArtifactDirectory(
    requiredNames: Array<out String>,
    additionalRequirement: (File) -> Boolean
): File? {
    val anchorName = requiredNames.first().lowercase()
    return walkTopDown()
        .filter { file -> file.isFile && file.name.lowercase() == anchorName }
        .mapNotNull(File::getParentFile)
        .distinctBy { directory -> runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath } }
        .sortedBy { directory -> runCatching { directory.relativeTo(this).invariantSeparatorsPath }.getOrElse { directory.path } }
        .firstOrNull { directory ->
            requiredNames.all { name -> directory.findDirectExecutionArtifact(name) != null } &&
                additionalRequirement(directory)
        }
}

private fun File.hasCompleteDirectDiffusionGraph(runtime: LocalImageRuntime): Boolean =
    when (runtime) {
        LocalImageRuntime.QNN_HTP ->
            findDirectExecutionArtifact("unet.bin") != null &&
                findDirectExecutionArtifact("vae_decoder.bin", "vae.bin") != null
        LocalImageRuntime.MNN_DIFFUSION ->
            findDirectExecutionArtifact("unet.mnn") != null &&
                findDirectExecutionArtifact("vae_decoder.mnn", "vae.mnn") != null
        else -> false
    }

private fun File.findDirectExecutionArtifact(vararg names: String): File? {
    val expected = names.map(String::lowercase)
    val filesByName = listFiles()
        ?.asSequence()
        ?.filter(File::isFile)
        ?.associateBy { file -> file.name.lowercase() }
        .orEmpty()
    return expected.firstNotNullOfOrNull(filesByName::get)
}

private fun File.toGraphArtifact(root: File): ImageGraphArtifactContract =
    ImageGraphArtifactContract(relativeTo(root).invariantSeparatorsPath)

private fun File.findExecutionArtifact(vararg names: String): File? {
    val expected = names.map(String::lowercase).toSet()
    return walkTopDown().firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
}

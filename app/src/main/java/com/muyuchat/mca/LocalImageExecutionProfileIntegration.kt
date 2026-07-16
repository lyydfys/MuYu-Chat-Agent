package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Resolves the immutable execution contract used by one concrete generation request. */
internal fun resolveLocalImageExecutionProfile(
    model: LocalImageModelRecord,
    options: LocalImageGenerationOptions,
    bundleRoot: File?,
    familyOverride: LocalImageModelFamily? = null
): ImageExecutionProfileResolution {
    val canonicalRoot = bundleRoot
        ?.takeIf(File::isDirectory)
        ?.let { root -> runCatching { root.canonicalFile }.getOrElse { root.absoluteFile } }
    val manifestFile = canonicalRoot?.let(::findImageManifestFile)
    val manifestJson = manifestFile?.let { file ->
        runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrElse { error ->
                throw ImageExecutionProfileJsonException(
                    code = "PROFILE_JSON_INVALID",
                    field = "manifest",
                    message = "Image bundle manifest is not valid JSON.",
                    cause = error
                )
            }
    }
    val recommendationId = manifestJson
        ?.optString("recommendationId")
        ?.takeIf(String::isNotBlank)
        ?: manifestJson?.optString("id")?.takeIf(String::isNotBlank)
    val manifestProfile = manifestJson?.let(ImageExecutionProfileJson::parseManifest)
    val inferredRecommendationFamily = recommendationId
        ?.let(LocalImageModelFamily::from)
        ?.takeUnless { it == LocalImageModelFamily.CUSTOM }
    val effectiveFamily = familyOverride
        ?: manifestProfile?.family
        ?: model.family.takeUnless { it == LocalImageModelFamily.CUSTOM }
        ?: inferredRecommendationFamily
        ?: model.family
    val sidecar = canonicalRoot?.let(ImageExecutionProfileJson::parseSidecars)
    val discovery = canonicalRoot?.let { root ->
        discoverLocalImageExecutionCapabilities(root, model.runtime, effectiveFamily)
    }
    val schedulerOverride = options.sampleMethod
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::imageSchedulerAlgorithmFromProductName)
    val overrides = ImageGenerationOverrides(
        scheduler = schedulerOverride,
        steps = options.steps,
        cfgScale = options.cfgScale,
        useCfg = options.useCfg,
        width = options.width,
        height = options.height,
        seed = options.seed?.toLong(),
        negativePrompt = options.negativePrompt,
        negativePromptSpecified = options.negativePrompt != null
    )
    val resolution = ImageExecutionProfileResolver.resolve(
        ImageExecutionProfileResolverInput(
            modelFingerprint = modelExecutionFingerprint(model),
            runtime = model.runtime,
            family = effectiveFamily,
            recommendationId = recommendationId,
            recommendationRevision = manifestJson
                ?.optString("revision")
                ?.takeIf(String::isNotBlank),
            manifestProfile = manifestProfile,
            sidecar = sidecar,
            capabilityDiscovery = discovery,
            userOverrides = overrides
        )
    )
    require(resolution.profile.runtime == model.runtime) {
        "Image execution profile runtime ${resolution.profile.runtime} does not match ${model.runtime}."
    }
    require(resolution.profile.family == effectiveFamily) {
        "Image execution profile family ${resolution.profile.family} does not match $effectiveFamily."
    }
    return resolution
}

internal fun imageSchedulerAlgorithmFromProductName(value: String): ImageSchedulerAlgorithm =
    when (value.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "euler", "euler_discrete" -> ImageSchedulerAlgorithm.EULER
        "euler_a", "euler_ancestral", "euler_ancestral_discrete" -> ImageSchedulerAlgorithm.EULER_A
        "ddim", "ddim_trailing" -> ImageSchedulerAlgorithm.DDIM
        "pndm", "pndm_plms", "plms" -> ImageSchedulerAlgorithm.PNDM_PLMS
        "dpm++2m", "dpmpp_2m", "dpm_plus_plus_2m" -> ImageSchedulerAlgorithm.DPMPP_2M
        "lcm" -> ImageSchedulerAlgorithm.LCM
        "flow", "flow_match", "flowmatch" -> ImageSchedulerAlgorithm.FLOW_MATCH
        else -> throw IllegalArgumentException("Unsupported image scheduler: $value")
    }

internal fun imageSchedulerProductName(value: ImageSchedulerAlgorithm): String = when (value) {
    ImageSchedulerAlgorithm.EULER -> "euler"
    ImageSchedulerAlgorithm.EULER_A -> "euler_a"
    ImageSchedulerAlgorithm.DDIM -> "ddim"
    ImageSchedulerAlgorithm.PNDM_PLMS -> "pndm"
    ImageSchedulerAlgorithm.DPMPP_2M -> "dpmpp_2m"
    ImageSchedulerAlgorithm.LCM -> "lcm"
    ImageSchedulerAlgorithm.FLOW_MATCH -> "flow_match"
}

private fun findImageManifestFile(root: File): File? {
    val direct = File(root, "manifest.json")
    if (direct.isFile) return direct
    return root.walkTopDown()
        .maxDepth(4)
        .firstOrNull { file -> file.isFile && file.name.equals("manifest.json", ignoreCase = true) }
}

private fun discoverLocalImageExecutionCapabilities(
    root: File,
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): ImageCapabilityDiscovery? {
    if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION) return null
    val tokenizerJson = root.findExecutionArtifact("tokenizer.json")
    val tokenEmbedding = root.findExecutionArtifact("token_emb.bin")
    val textEncoder = root.findExecutionArtifact("text_encoder.bin", "text_encoder.mnn")
    val unet = root.findExecutionArtifact("unet.bin", "unet.mnn")
    val vae = root.findExecutionArtifact("vae.bin", "vae_decoder.bin", "vae_decoder.mnn")
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
        separateNegativePrompt = true
    )
    val width = when (family) {
        LocalImageModelFamily.SD21 -> 1_024
        LocalImageModelFamily.SDXL -> 2_048
        else -> 768
    }
    val qnnGraphInternalVae = runtime == LocalImageRuntime.QNN_HTP &&
        textEncoder?.extension.equals("bin", ignoreCase = true) &&
        vae?.name.equals("vae.bin", ignoreCase = true)
    val mnnGraphInternalConditioning = runtime == LocalImageRuntime.MNN_DIFFUSION &&
        textEncoder?.extension.equals("mnn", ignoreCase = true)
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
            schedulerSidecar = File(root, "scheduler/scheduler_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            tokenizerSidecar = File(root, "tokenizer/tokenizer_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            workerStrategy = if (qnnGraphInternalVae) {
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
            } else {
                ImageWorkerStrategy.IN_PROCESS
            }
        )
    )
}

/** Resolves MNN conditioning storage without guessing from an unknown token-table size. */
internal fun resolveMnnConditioningDiskDataType(
    graphInternal: Boolean,
    tokenEmbeddingByteSize: Long?
): ImageEmbeddingDiskDataType {
    val tokenTableDataType = when (tokenEmbeddingByteSize) {
        null -> null
        75_890_688L -> ImageEmbeddingDiskDataType.FP16
        151_781_376L -> ImageEmbeddingDiskDataType.FP32
        else -> throw IllegalArgumentException(
            "PACKAGE_FORMAT_INVALID: token_emb.bin must be exactly 75890688 bytes (FP16) " +
                "or 151781376 bytes (FP32); found $tokenEmbeddingByteSize bytes."
        )
    }
    return if (graphInternal) {
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL
    } else {
        tokenTableDataType ?: ImageEmbeddingDiskDataType.RUNTIME_NATIVE
    }
}

private fun File.toGraphArtifact(root: File): ImageGraphArtifactContract =
    ImageGraphArtifactContract(relativeTo(root).invariantSeparatorsPath)

private fun File.findExecutionArtifact(vararg names: String): File? {
    val expected = names.map(String::lowercase).toSet()
    return walkTopDown().firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
}

private fun modelExecutionFingerprint(model: LocalImageModelRecord): String {
    val stored = model.sha256.trim().lowercase()
    if (stored.matches(Regex("^[0-9a-f]{64}$"))) return stored
    val primary = File(model.path)
    require(primary.isFile) { "Image model fingerprint is unavailable." }
    return primary.sha256ForProfile()
}

private fun File.sha256ForProfile(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

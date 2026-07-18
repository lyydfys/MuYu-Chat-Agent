package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal typealias LocalImageManifestProfileResolver =
    (ImageExecutionProfileResolverInput) -> ImageExecutionProfileResolution

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
    val manifestBehavior = if (manifestProfile == null) {
        manifestJson?.let(ImageExecutionProfileJson::parseManifestBehavior)
    } else {
        null
    }
    val sidecar = canonicalRoot?.let { root ->
        parseLocalImageExecutionProfileSidecars(root, manifestProfile)
    }
    val inferredRecommendationFamily = recommendationId
        ?.let(LocalImageModelFamily::from)
        ?.takeUnless { it == LocalImageModelFamily.CUSTOM }
    val effectiveFamily = familyOverride
        ?: manifestProfile?.family
        ?: manifestBehavior?.family
        ?: sidecar?.behavior?.family
        ?: model.family.takeUnless { it == LocalImageModelFamily.CUSTOM }
        ?: inferredRecommendationFamily
        ?: model.family
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
            manifestBehavior = manifestBehavior,
            sidecar = sidecar,
            capabilityDiscovery = discovery,
            recommendationEvidence = localImageRecommendationEvidence(
                model = model,
                bundleRoot = canonicalRoot,
                manifestJson = manifestJson
            ),
            userOverrides = overrides
        )
    )
    require(resolution.profile.runtime == model.runtime) {
        "Image execution profile runtime ${resolution.profile.runtime} does not match ${model.runtime}."
    }
    require(effectiveFamily == LocalImageModelFamily.CUSTOM || resolution.profile.family == effectiveFamily) {
        "Image execution profile family ${resolution.profile.family} does not match $effectiveFamily."
    }
    return resolution.withProductDenoisingSchedule(options)
}

/**
 * Resolves only a complete, versioned manifest profile before readiness
 * inspects required paths. A narrowly migrated catalog profile must not be
 * rejected because an older persisted revision named sidecars that were never
 * present in the extracted archive. This path intentionally reuses the
 * persisted fingerprint and never hashes a large graph during UI readiness;
 * real generation rebinds the profile to LocalImageModelRecord.sha256.
 *
 * Graph-only legacy manifests are left to the lightweight manifest parser.
 * A versioned profile, however, is owned by this app and is therefore parsed
 * and validated fail-closed instead of being downgraded to legacy discovery.
 */
internal fun resolveEffectiveLocalImageManifestProfile(
    manifestJson: JSONObject,
    resolver: LocalImageManifestProfileResolver = ImageExecutionProfileResolver::resolve
): ImageExecutionProfile? {
    val rawProfile = manifestJson.opt("executionProfile") as? JSONObject ?: return null
    val isVersionedProfile = listOf(
        "schemaVersion",
        "profileId",
        "profileRevision",
        "modelFingerprint"
    ).any(rawProfile::has)
    if (!isVersionedProfile) return null

    val persisted = requireNotNull(ImageExecutionProfileJson.parseManifest(manifestJson))
    // Readiness must stay O(manifest + directory entries). The persisted
    // fingerprint is the migration identity here; real generation resolves
    // the LocalImageModelRecord fingerprint again and rejects any mismatch
    // before native execution.
    val installedFingerprint = persisted.modelFingerprint.trim().lowercase()
    val recommendationId = manifestJson
        .optString("recommendationId")
        .takeIf(String::isNotBlank)
        ?: manifestJson.optString("id").takeIf(String::isNotBlank)
    return resolver(
        ImageExecutionProfileResolverInput(
            modelFingerprint = installedFingerprint,
            runtime = persisted.runtime,
            family = persisted.family,
            recommendationId = recommendationId,
            recommendationRevision = manifestJson
                .optString("revision")
                .takeIf(String::isNotBlank),
            manifestProfile = persisted,
            recommendationEvidence = localImageManifestRecommendationEvidence(manifestJson)
        )
    ).profile
}

/**
 * The configured sampler step count remains unchanged for img2img/inpaint, while native starts
 * later in that schedule according to strength. Bind the strict evidence to the actually visited
 * timetable instead of pretending every configured step executed.
 */
internal fun ImageExecutionProfileResolution.withProductDenoisingSchedule(
    options: LocalImageGenerationOptions
): ImageExecutionProfileResolution {
    if (profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP ||
        options.taskMode !in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)
    ) {
        return this
    }
    val strength = options.strength ?: 1.0
    if (strength >= 1.0) return this
    require(strength.isFinite() && strength > 0.0) {
        "Image strength must be finite and in (0, 1]."
    }
    val resolved = layers.resolved
    val timetableCount = minOf(
        resolved.steps,
        (resolved.steps.toDouble() * strength).toInt() + 1
    ).coerceAtLeast(1)
    val branchCount = if (resolved.useCfg) 2 else 1
    return copy(
        layers = layers.copy(
            resolved = resolved.copy(
                timetableCount = timetableCount,
                unetExecutionCount = timetableCount * branchCount
            )
        )
    )
}

/** Reads standard package sidecars plus JSON config sidecars declared by a full manifest profile. */
internal fun parseLocalImageExecutionProfileSidecars(
    bundleRoot: File,
    manifestProfile: ImageExecutionProfile?
): ImageProfileSidecar? {
    val graph = manifestProfile?.graph
    val behaviorSidecars = buildList {
        add(DEFAULT_IMAGE_BEHAVIOR_SIDECAR)
        graph?.configSidecars
            .orEmpty()
            .filter(::isJsonImageBehaviorSidecar)
            .forEach(::add)
    }
    return ImageExecutionProfileJson.parseSidecars(
        bundleRoot = bundleRoot,
        schedulerRelativePath = graph?.schedulerSidecar ?: DEFAULT_IMAGE_SCHEDULER_SIDECAR,
        tokenizerRelativePath = graph?.tokenizerSidecar ?: DEFAULT_IMAGE_TOKENIZER_SIDECAR,
        behaviorRelativePaths = behaviorSidecars
    )
}

private fun isJsonImageBehaviorSidecar(relativePath: String): Boolean {
    val fileName = relativePath
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
    return fileName.endsWith(".json") && fileName !in NON_BEHAVIOR_RUNTIME_JSON_FILES
}

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

private fun localImageManifestRecommendationEvidence(
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

/** A quality-bound MNN SD1.5 profile can only use the strict direct runner. */
internal fun resolveMnnDiffusionProfileRunner(
    profile: ImageExecutionProfile,
    requestedRunner: String?
): String {
    val resolved = resolveMnnDiffusionRunner(profile.family, requestedRunner)
    if (
        profile.runtime == LocalImageRuntime.MNN_DIFFUSION &&
        profile.profileId == "mnn.sd15.official.512"
    ) {
        require(resolved == "direct") {
            "The MNN SD1.5 quality profile requires runner=direct; module cannot satisfy its execution contract."
        }
        return "direct"
    }
    return resolved
}

private const val DEFAULT_IMAGE_SCHEDULER_SIDECAR = "scheduler/scheduler_config.json"
private const val DEFAULT_IMAGE_TOKENIZER_SIDECAR = "tokenizer/tokenizer_config.json"
private const val DEFAULT_IMAGE_BEHAVIOR_SIDECAR = "config.json"

private val NON_BEHAVIOR_RUNTIME_JSON_FILES = setOf(
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "added_tokens.json",
    "vocab.json",
    "scheduler_config.json"
)

private val PRIMARY_IMAGE_COMPONENT_ROLES = setOf("DIFFUSION", "MODEL", "UNET", "TRANSFORMER")

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
    val vaeEncoder = root.findExecutionArtifact("vae_encoder.bin", "vae_encoder.mnn")
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
            vaeEncoder = vaeEncoder?.toGraphArtifact(root),
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

internal fun File.sha256ForProfile(): String {
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

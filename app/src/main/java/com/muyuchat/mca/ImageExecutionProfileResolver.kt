package com.muyuchat.mca

internal data class ImageProfileSidecar(
    val profileId: String? = null,
    val profileRevision: Int? = null,
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val task: ImageTask? = null,
    val tokenizer: ImageTokenizerContract? = null,
    val conditioning: ImageConditioningContract? = null,
    val scheduler: ImageSchedulerContract? = null,
    val latent: ImageLatentContract? = null,
    val vae: ImageVaeContract? = null,
    val graph: ImageGraphContract? = null,
    val defaults: ImageGenerationDefaults? = null,
    val capabilities: ImageGenerationCapabilities? = null
)

internal data class ImageCapabilityDiscovery(
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val task: ImageTask? = null,
    val tokenizer: ImageTokenizerContract? = null,
    val conditioning: ImageConditioningContract? = null,
    val scheduler: ImageSchedulerContract? = null,
    val latent: ImageLatentContract? = null,
    val vae: ImageVaeContract? = null,
    val graph: ImageGraphContract? = null,
    val defaults: ImageGenerationDefaults? = null,
    val capabilities: ImageGenerationCapabilities? = null
)

internal data class ImageGenerationOverrides(
    val expectedProfileId: String? = null,
    val expectedProfileRevision: Int? = null,
    val scheduler: ImageSchedulerAlgorithm? = null,
    val predictionType: ImagePredictionType? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val useCfg: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val seed: Long? = null,
    val negativePrompt: String? = null,
    val negativePromptSpecified: Boolean = false
)

/** Device hints may tune execution later, but are never part of profile admission. */
internal data class ImageDeviceExecutionHints(
    val localProfileKnown: Boolean = false,
    val preferredWorkerStrategy: ImageWorkerStrategy? = null,
    val preferredThreads: Int? = null
)

internal data class ImageExecutionProfileResolverInput(
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily,
    val recommendationId: String? = null,
    val recommendationRevision: String? = null,
    val manifestProfile: ImageExecutionProfile? = null,
    val sidecar: ImageProfileSidecar? = null,
    val capabilityDiscovery: ImageCapabilityDiscovery? = null,
    val userOverrides: ImageGenerationOverrides = ImageGenerationOverrides(),
    val deviceHints: ImageDeviceExecutionHints = ImageDeviceExecutionHints()
)

internal data class ImageExecutionProfileResolution(
    val profile: ImageExecutionProfile,
    val layers: ImageExecutionLayers,
    val fieldSources: Map<String, ImageProfileSource>,
    val sourceChain: List<ImageProfileSource>,
    val validation: ImageProfileValidationReport,
    val warnings: List<String>,
    /** Always false: missing/unknown device information is advisory. */
    val deviceAdmissionRestricted: Boolean = false
)

internal class ImageProfileResolutionException(
    val validation: ImageProfileValidationReport
) : IllegalArgumentException(
    validation.issues.joinToString(" ") { issue -> "${issue.code}:${issue.field}:${issue.message}" }
)

internal data class BuiltInImageProfileTarget(
    val recommendationId: String,
    val profileId: String
)

internal object ImageExecutionProfileResolver {
    val builtInTargets: List<BuiltInImageProfileTarget> = listOf(
        BuiltInImageProfileTarget("cyberrealistic_sd15_qnn228", "community.sd15.qnn228"),
        BuiltInImageProfileTarget("realisticvisionhyper_sd15_qnn228", "community.sd15.hyper.qnn228"),
        BuiltInImageProfileTarget("dreamshaper_sd15_qnn228", "community.sd15.qnn228"),
        BuiltInImageProfileTarget("meinamix_sd15_qnn228", "community.sd15.legacy-fp32.qnn228"),
        BuiltInImageProfileTarget("sdxl_base_qnn228", "community.sdxl.base.qnn228"),
        BuiltInImageProfileTarget("realismsdxl_dmd2_alt_qnn228", "community.sdxl.dmd2-alt.qnn228"),
        BuiltInImageProfileTarget("animagine_xl_v4_qnn228", "community.sdxl.base.qnn228"),
        BuiltInImageProfileTarget("cyberrealisticxl_qnn228", "community.sdxl.base.qnn228"),
        BuiltInImageProfileTarget("qualcomm_sd15_gen5_qnn", "qualcomm.sd15.gen5.qnn245"),
        BuiltInImageProfileTarget("qualcomm_sd21_gen5_qnn", "qualcomm.sd21.gen5.qnn245"),
        BuiltInImageProfileTarget("qualcomm_controlnet_canny_gen5_qnn", "qualcomm.controlnet-canny.gen5.qnn245"),
        BuiltInImageProfileTarget("sd15_mnn_512_quality", "mnn.sd15.official.512"),
        BuiltInImageProfileTarget("mnn_sana_edit_v2", "mnn.sana-edit.v2"),
        BuiltInImageProfileTarget("sd_turbo_512_experimental", "sdcpp.sd-turbo"),
        BuiltInImageProfileTarget("z_image_turbo_q4", "sdcpp.z-image-turbo"),
        BuiltInImageProfileTarget("flux2_klein_4b_q4", "sdcpp.flux2-klein"),
        BuiltInImageProfileTarget("qwen_image_2512_q2", "sdcpp.qwen-image"),
        BuiltInImageProfileTarget("longcat_image_q4", "sdcpp.longcat-image")
    )

    private val targetByRecommendationId = builtInTargets.associateBy(BuiltInImageProfileTarget::recommendationId)
    private val bundleIdAliases = mapOf(
        "sd15_mnn_bundle" to "sd15_mnn_512_quality",
        "z_image_turbo_q2_bundle" to "z_image_turbo_q4"
    )

    fun resolve(input: ImageExecutionProfileResolverInput): ImageExecutionProfileResolution {
        val fieldSources = linkedMapOf<String, ImageProfileSource>()
        val sourceChain = mutableListOf<ImageProfileSource>()
        val fingerprint = input.modelFingerprint.trim().lowercase()

        val base: ImageExecutionProfile = if (input.manifestProfile != null) {
            sourceChain += ImageProfileSource.MANIFEST
            markAllProfileFields(fieldSources, ImageProfileSource.MANIFEST)
            input.manifestProfile
        } else {
            val builtInTarget = input.recommendationId
                ?.let { id ->
                    val recommendationId = bundleIdAliases[id] ?: id.removeSuffix("_bundle")
                    targetByRecommendationId[id] ?: targetByRecommendationId[recommendationId]
                }
            when {
                builtInTarget != null -> {
                    sourceChain += ImageProfileSource.BUILT_IN
                    markAllProfileFields(fieldSources, ImageProfileSource.BUILT_IN)
                    builtInProfile(
                        target = builtInTarget,
                        modelFingerprint = fingerprint,
                        recommendationRevision = input.recommendationRevision
                    )
                }
                input.capabilityDiscovery != null -> {
                    sourceChain += ImageProfileSource.CAPABILITY_DISCOVERY
                    markAllProfileFields(fieldSources, ImageProfileSource.CAPABILITY_DISCOVERY)
                    applyCapabilityDiscovery(
                        genericProfile(input.runtime, input.family, fingerprint),
                        input.capabilityDiscovery
                    )
                }
                else -> {
                    sourceChain += ImageProfileSource.GENERIC_FALLBACK
                    markAllProfileFields(fieldSources, ImageProfileSource.GENERIC_FALLBACK)
                    genericProfile(input.runtime, input.family, fingerprint)
                }
            }
        }

        // An explicit manifest profile is already a signed binding claim and
        // must retain its declared fingerprint so mismatch validation can
        // reject stale or transplanted metadata. Resolver-created templates
        // are materialized against the current model bytes here.
        var resolvedProfile = if (input.manifestProfile != null) {
            base
        } else {
            base.copy(modelFingerprint = fingerprint)
        }
        if (input.manifestProfile == null && input.sidecar != null) {
            sourceChain.add(0, ImageProfileSource.SIDECAR)
            resolvedProfile = applySidecar(resolvedProfile, input.sidecar, fieldSources)
        }
        resolvedProfile = applyUserOverrides(resolvedProfile, input.userOverrides, fieldSources)
        if (hasUserOverrides(input.userOverrides)) sourceChain += ImageProfileSource.USER_OVERRIDE
        resolvedProfile = resolvedProfile.copy(
            provenance = ImageProfileProvenance(
                primarySource = sourceChain.first(),
                sources = sourceChain.distinct(),
                recommendationId = input.recommendationId,
                recommendationRevision = input.recommendationRevision,
                notes = if (input.deviceHints.localProfileKnown) emptyList() else listOf(
                    "Device-local profile is unavailable; native load and real execution remain authoritative."
                )
            )
        )

        val profileValidation = ImageExecutionProfileValidator.validate(resolvedProfile, fingerprint)
        val expectationIssues = buildList {
            input.userOverrides.expectedProfileId?.let { expected ->
                if (expected != resolvedProfile.profileId) {
                    add(ImageProfileValidationIssue("PROFILE_EXPECTATION_MISMATCH", "profileId", "Requested profile ID was not resolved."))
                }
            }
            input.userOverrides.expectedProfileRevision?.let { expected ->
                if (expected != resolvedProfile.profileRevision) {
                    add(ImageProfileValidationIssue("PROFILE_EXPECTATION_MISMATCH", "profileRevision", "Requested profile revision was not resolved."))
                }
            }
        }
        val validation = ImageProfileValidationReport(profileValidation.issues + expectationIssues)
        if (!validation.valid) throw ImageProfileResolutionException(validation)

        val requested = ImageRequestedExecution(
            profileId = input.userOverrides.expectedProfileId,
            profileRevision = input.userOverrides.expectedProfileRevision,
            modelFingerprint = fingerprint,
            scheduler = input.userOverrides.scheduler,
            predictionType = input.userOverrides.predictionType,
            steps = input.userOverrides.steps,
            cfgScale = input.userOverrides.cfgScale,
            useCfg = input.userOverrides.useCfg,
            width = input.userOverrides.width,
            height = input.userOverrides.height,
            seed = input.userOverrides.seed
        )
        val resolved = resolvedExecution(resolvedProfile)
        return ImageExecutionProfileResolution(
            profile = resolvedProfile,
            layers = ImageExecutionLayers(requested = requested, resolved = resolved),
            fieldSources = fieldSources.toMap(),
            sourceChain = sourceChain.distinct(),
            validation = validation,
            warnings = resolvedProfile.provenance.notes,
            deviceAdmissionRestricted = false
        )
    }

    private fun applySidecar(
        base: ImageExecutionProfile,
        sidecar: ImageProfileSidecar,
        sources: MutableMap<String, ImageProfileSource>
    ): ImageExecutionProfile {
        fun mark(field: String, present: Boolean) {
            if (present) sources[field] = ImageProfileSource.SIDECAR
        }
        mark("profileId", sidecar.profileId != null)
        mark("profileRevision", sidecar.profileRevision != null)
        mark("family", sidecar.family != null)
        mark("variant", sidecar.variant != null)
        mark("task", sidecar.task != null)
        mark("tokenizer", sidecar.tokenizer != null)
        mark("conditioning", sidecar.conditioning != null)
        mark("scheduler", sidecar.scheduler != null)
        mark("latent", sidecar.latent != null)
        mark("vae", sidecar.vae != null)
        mark("graph", sidecar.graph != null)
        mark("defaults", sidecar.defaults != null)
        mark("capabilities", sidecar.capabilities != null)
        return base.copy(
            profileId = sidecar.profileId ?: base.profileId,
            profileRevision = sidecar.profileRevision ?: base.profileRevision,
            family = sidecar.family ?: base.family,
            variant = sidecar.variant ?: base.variant,
            task = sidecar.task ?: base.task,
            tokenizer = sidecar.tokenizer ?: base.tokenizer,
            conditioning = sidecar.conditioning ?: base.conditioning,
            scheduler = sidecar.scheduler ?: base.scheduler,
            latent = sidecar.latent ?: base.latent,
            vae = sidecar.vae ?: base.vae,
            graph = sidecar.graph ?: base.graph,
            defaults = sidecar.defaults ?: base.defaults,
            capabilities = sidecar.capabilities ?: base.capabilities
        )
    }

    private fun applyCapabilityDiscovery(
        base: ImageExecutionProfile,
        discovery: ImageCapabilityDiscovery
    ): ImageExecutionProfile = base.copy(
        family = discovery.family ?: base.family,
        variant = discovery.variant ?: base.variant,
        task = discovery.task ?: base.task,
        tokenizer = discovery.tokenizer ?: base.tokenizer,
        conditioning = discovery.conditioning ?: base.conditioning,
        scheduler = discovery.scheduler ?: base.scheduler,
        latent = discovery.latent ?: base.latent,
        vae = discovery.vae ?: base.vae,
        graph = discovery.graph ?: base.graph,
        defaults = discovery.defaults ?: base.defaults,
        capabilities = discovery.capabilities ?: base.capabilities
    )

    private fun applyUserOverrides(
        base: ImageExecutionProfile,
        overrides: ImageGenerationOverrides,
        sources: MutableMap<String, ImageProfileSource>
    ): ImageExecutionProfile {
        val schedulerChanged = overrides.scheduler != null || overrides.predictionType != null
        if (schedulerChanged) sources["scheduler"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.steps != null) sources["defaults.steps"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.cfgScale != null) sources["defaults.cfgScale"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.useCfg != null) sources["defaults.useCfg"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.width != null) sources["defaults.width"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.height != null) sources["defaults.height"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.seed != null) sources["defaults.seed"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.negativePromptSpecified) sources["defaults.defaultNegativePrompt"] = ImageProfileSource.USER_OVERRIDE
        return base.copy(
            scheduler = base.scheduler.copy(
                algorithm = overrides.scheduler ?: base.scheduler.algorithm,
                predictionType = overrides.predictionType ?: base.scheduler.predictionType
            ),
            defaults = base.defaults.copy(
                width = overrides.width ?: base.defaults.width,
                height = overrides.height ?: base.defaults.height,
                steps = overrides.steps ?: base.defaults.steps,
                cfgScale = overrides.cfgScale ?: base.defaults.cfgScale,
                seed = overrides.seed ?: base.defaults.seed,
                useCfg = overrides.useCfg ?: base.defaults.useCfg,
                defaultNegativePrompt = if (overrides.negativePromptSpecified) {
                    overrides.negativePrompt
                } else {
                    base.defaults.defaultNegativePrompt
                }
            )
        )
    }

    private fun resolvedExecution(profile: ImageExecutionProfile): ImageResolvedExecution {
        val timetableCount = profile.scheduler.expectedTimestepCount(profile.defaults.steps)
        val branches = if (profile.defaults.useCfg) 2 else 1
        val graphName = profile.graph.unet?.graphName
            ?: profile.graph.controlNet?.graphName
            ?: "runtime-native"
        return ImageResolvedExecution(
            profileId = profile.profileId,
            profileRevision = profile.profileRevision,
            modelFingerprint = profile.modelFingerprint,
            runtime = profile.runtime,
            scheduler = profile.scheduler.algorithm,
            predictionType = profile.scheduler.predictionType,
            steps = profile.defaults.steps,
            timetableCount = timetableCount,
            unetExecutionCount = timetableCount * branches,
            cfgScale = profile.defaults.cfgScale,
            useCfg = profile.defaults.useCfg,
            unconditionalBranch = profile.defaults.useCfg,
            tokenizerBackend = profile.tokenizer.backend,
            tokenCount = profile.tokenizer.maxLength * if (profile.tokenizer.separateNegativePrompt) 2 else 1,
            embeddingDiskDataType = profile.conditioning.diskDataType,
            vaeScalingLocation = profile.vae.scalingLocation,
            vaeScalingFactor = profile.vae.scalingFactor,
            width = profile.defaults.width,
            height = profile.defaults.height,
            seed = profile.defaults.seed,
            graphName = graphName,
            fallback = false
        )
    }

    private fun builtInProfile(
        target: BuiltInImageProfileTarget,
        modelFingerprint: String,
        recommendationRevision: String?
    ): ImageExecutionProfile {
        val profile = profileTemplate(target.profileId, modelFingerprint)
        return profile.copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.BUILT_IN,
                sources = listOf(ImageProfileSource.BUILT_IN),
                recommendationId = target.recommendationId,
                recommendationRevision = recommendationRevision
            )
        )
    }

    private fun profileTemplate(profileId: String, fingerprint: String): ImageExecutionProfile = when (profileId) {
        "community.sd15.qnn228" -> qnnSd15Profile(profileId, fingerprint)
        "community.sd15.hyper.qnn228" -> qnnSd15Profile(profileId, fingerprint, ImageModelVariant.HYPER, 8, 2.0)
        "community.sd15.legacy-fp32.qnn228" -> qnnSd15Profile(
            profileId,
            fingerprint,
            ImageModelVariant.LEGACY_FP32,
            conditioningType = ImageEmbeddingDiskDataType.FP32,
            conversion = ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING
        )
        "community.sdxl.base.qnn228" -> qnnSdxlProfile(profileId, fingerprint)
        "community.sdxl.dmd2-alt.qnn228" -> qnnSdxlProfile(
            profileId,
            fingerprint,
            variant = ImageModelVariant.DMD2_ALT,
            steps = 4,
            cfg = 1.0,
            useCfg = false
        )
        "qualcomm.sd15.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = false)
        "qualcomm.sd21.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = true)
        "qualcomm.controlnet-canny.gen5.qnn245" -> qnnControlNetProfile(profileId, fingerprint)
        "mnn.sd15.official.512" -> mnnSd15Profile(profileId, fingerprint)
        "mnn.sana-edit.v2" -> sanaEditProfile(profileId, fingerprint)
        "sdcpp.sd-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.SD_TURBO, ImageModelVariant.SD_TURBO, 4, 0.0, ImageSchedulerAlgorithm.EULER_A)
        "sdcpp.z-image-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.Z_IMAGE, ImageModelVariant.Z_IMAGE_TURBO, 9, 0.0, ImageSchedulerAlgorithm.FLOW_MATCH)
        "sdcpp.flux2-klein" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.FLUX, ImageModelVariant.FLUX2_KLEIN, 20, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024)
        "sdcpp.qwen-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.QWEN_IMAGE, ImageModelVariant.QWEN_IMAGE, 20, 4.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024)
        "sdcpp.longcat-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.LONGCAT_IMAGE, ImageModelVariant.LONGCAT_IMAGE, 20, 4.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024)
        else -> error("Unknown built-in image profile target: $profileId")
    }

    private fun qnnSd15Profile(
        profileId: String,
        fingerprint: String,
        variant: ImageModelVariant = ImageModelVariant.STANDARD,
        steps: Int = 20,
        cfg: Double = 7.0,
        conditioningType: ImageEmbeddingDiskDataType = ImageEmbeddingDiskDataType.FP16,
        conversion: ImageEmbeddingConversionStrategy = ImageEmbeddingConversionStrategy.NONE
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.QNN_HTP,
        family = LocalImageModelFamily.SD15,
        variant = variant,
        scheduler = scheduler(ImageSchedulerAlgorithm.DPMPP_2M, ImagePredictionType.EPSILON, steps, if (variant == ImageModelVariant.HYPER) 1 else 10, 50),
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP),
        conditioning = conditioning(conditioningType, conversion, 768),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
        graph = qnnGraph("clip_text_encoder_qnn_context.bin", "unet.bin", "vae_decoder.bin", "2.28", 68),
        defaults = defaults(512, steps, cfg, useCfg = true),
        capabilities = capabilities(512, setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER, ImageSchedulerAlgorithm.PNDM_PLMS))
    )

    private fun qnnSdxlProfile(
        profileId: String,
        fingerprint: String,
        variant: ImageModelVariant = ImageModelVariant.SDXL_BASE,
        steps: Int = 30,
        cfg: Double = 7.0,
        useCfg: Boolean = true
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.QNN_HTP,
        family = LocalImageModelFamily.SDXL,
        variant = variant,
        scheduler = scheduler(ImageSchedulerAlgorithm.DPMPP_2M, ImagePredictionType.EPSILON, steps, 1, 50),
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP, dualClip = true),
        conditioning = conditioning(ImageEmbeddingDiskDataType.FP16, ImageEmbeddingConversionStrategy.NONE, 2_048, dualEncoder = true, pooled = true),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025, 1024),
        graph = qnnGraph("clip.mnn", "unet.bin", "vae_decoder.bin", "2.28", 73, ImageWorkerStrategy.SPLIT_UNET_VAE),
        defaults = defaults(1024, steps, cfg, useCfg),
        capabilities = capabilities(1024, setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER, ImageSchedulerAlgorithm.LCM))
    )

    private fun qnnGen5Profile(
        profileId: String,
        fingerprint: String,
        sd21: Boolean
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.QNN_HTP,
        family = if (sd21) LocalImageModelFamily.SD21 else LocalImageModelFamily.SD15,
        variant = if (sd21) ImageModelVariant.SD21 else ImageModelVariant.STANDARD,
        scheduler = if (sd21) {
            scheduler(
                ImageSchedulerAlgorithm.DDIM,
                ImagePredictionType.V_PREDICTION,
                20,
                10,
                50,
                stepsOffset = 1,
                timestepSpacing = ImageTimestepSpacing.LEADING,
                setAlphaToOne = false,
                clipSample = false
            )
        } else {
            scheduler(
                ImageSchedulerAlgorithm.EULER,
                ImagePredictionType.EPSILON,
                20,
                10,
                50,
                stepsOffset = 0,
                timestepSpacing = ImageTimestepSpacing.LINSPACE,
                setAlphaToOne = false,
                scaleModelInput = true
            )
        },
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP, padZero = sd21),
        conditioning = conditioning(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, if (sd21) 1_024 else 768),
        vae = vae(ImageVaeScalingLocation.GRAPH_INTERNAL, 0.18215, 512),
        graph = qnnGraph("text_encoder.bin", "unet.bin", "vae.bin", "2.45.0.260326154327", 81, ImageWorkerStrategy.SHARED_TEXT_UNET_VAE),
        defaults = defaults(512, 20, 7.5, useCfg = true),
        capabilities = capabilities(512, setOf(if (sd21) ImageSchedulerAlgorithm.DDIM else ImageSchedulerAlgorithm.EULER))
    )

    private fun qnnControlNetProfile(profileId: String, fingerprint: String): ImageExecutionProfile {
        val base = qnnGen5Profile(profileId, fingerprint, sd21 = false)
        return base.copy(
            variant = ImageModelVariant.CONTROLNET_CANNY,
            task = ImageTask.CONTROL_IMAGE,
            graph = base.graph.copy(controlNet = ImageGraphArtifactContract("controlnet.bin")),
            capabilities = base.capabilities.copy(requiresControlImage = true)
        )
    }

    private fun mnnSd15Profile(profileId: String, fingerprint: String): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.MNN_DIFFUSION,
        family = LocalImageModelFamily.SD15,
        variant = ImageModelVariant.STANDARD,
        scheduler = scheduler(ImageSchedulerAlgorithm.DPMPP_2M, ImagePredictionType.EPSILON, 20, 10, 50),
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP),
        conditioning = conditioning(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, 768),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
        graph = ImageGraphContract(
            textEncoder = ImageGraphArtifactContract("text_encoder.mnn"),
            unet = ImageGraphArtifactContract("unet.mnn"),
            vae = ImageGraphArtifactContract("vae_decoder.mnn"),
            workerStrategy = ImageWorkerStrategy.IN_PROCESS
        ),
        defaults = defaults(512, 20, 7.0, useCfg = true),
        capabilities = capabilities(512, setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER, ImageSchedulerAlgorithm.PNDM_PLMS))
    )

    private fun sanaEditProfile(profileId: String, fingerprint: String): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.MNN_DIFFUSION,
        family = LocalImageModelFamily.SANA,
        variant = ImageModelVariant.SANA_EDIT,
        task = ImageTask.IMAGE_EDIT,
        scheduler = scheduler(
            ImageSchedulerAlgorithm.FLOW_MATCH,
            ImagePredictionType.FLOW,
            20,
            1,
            100
        ),
        tokenizer = ImageTokenizerContract(
            backend = ImageTokenizerBackend.MNN_MTOK,
            maxLength = 256,
            clip1PadRule = ImageClipPadRule.MODEL_DECLARED,
            supportsPromptWeighting = false,
            separateNegativePrompt = true
        ),
        conditioning = conditioning(
            ImageEmbeddingDiskDataType.GRAPH_INTERNAL,
            ImageEmbeddingConversionStrategy.GRAPH_EXECUTION,
            1
        ),
        vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, 512),
        graph = ImageGraphContract(
            textEncoder = ImageGraphArtifactContract("llm/llm.mnn"),
            unet = ImageGraphArtifactContract("transformer.mnn"),
            vae = ImageGraphArtifactContract("vae_decoder.mnn"),
            configSidecars = listOf("llm/meta_queries.mnn"),
            workerStrategy = ImageWorkerStrategy.DEDICATED_WORKER
        ),
        defaults = defaults(512, 20, 4.5, useCfg = true),
        capabilities = capabilities(
            512,
            setOf(ImageSchedulerAlgorithm.FLOW_MATCH)
        ).copy(requiresInputImage = true, supportsMask = true)
    )

    private fun sdcppProfile(
        profileId: String,
        fingerprint: String,
        family: LocalImageModelFamily,
        variant: ImageModelVariant,
        steps: Int,
        cfg: Double,
        algorithm: ImageSchedulerAlgorithm,
        size: Int = 512,
        runtime: LocalImageRuntime = LocalImageRuntime.STABLE_DIFFUSION_CPP
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = runtime,
        family = family,
        variant = variant,
        scheduler = scheduler(
            algorithm,
            if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) ImagePredictionType.FLOW else ImagePredictionType.EPSILON,
            steps,
            1,
            100
        ),
        tokenizer = clipTokenizer(ImageTokenizerBackend.SDCPP_NATIVE),
        conditioning = conditioning(ImageEmbeddingDiskDataType.RUNTIME_NATIVE, ImageEmbeddingConversionStrategy.RUNTIME_NATIVE, 1),
        vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
        graph = ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS),
        defaults = defaults(size, steps, cfg, useCfg = cfg > 1.0),
        capabilities = stableDiffusionCapabilities(
            setOf(algorithm, ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER_A)
        )
    )

    private fun genericProfile(
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily,
        fingerprint: String
    ): ImageExecutionProfile {
        val size = if (family == LocalImageModelFamily.SDXL) 1024 else 512
        val algorithm = when (runtime) {
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> ImageSchedulerAlgorithm.EULER_A
            LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION -> ImageSchedulerAlgorithm.PNDM_PLMS
            else -> ImageSchedulerAlgorithm.EULER
        }
        val graph = when (runtime) {
            LocalImageRuntime.QNN_HTP -> qnnGraph("text_encoder.bin", "unet.bin", "vae.bin", null, null)
            LocalImageRuntime.MNN_DIFFUSION -> ImageGraphContract(
                textEncoder = ImageGraphArtifactContract("text_encoder.mnn"),
                unet = ImageGraphArtifactContract("unet.mnn"),
                vae = ImageGraphArtifactContract("vae_decoder.mnn"),
                workerStrategy = ImageWorkerStrategy.IN_PROCESS
            )
            else -> ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS)
        }
        return profile(
            profileId = "generic.compat.${runtime.name.lowercase()}.${family.name.lowercase()}",
            fingerprint = fingerprint,
            runtime = runtime,
            family = family,
            variant = ImageModelVariant.GENERIC_COMPATIBLE,
            scheduler = scheduler(algorithm, ImagePredictionType.EPSILON, 20, 1, 100, skipPrk = algorithm == ImageSchedulerAlgorithm.PNDM_PLMS),
            tokenizer = clipTokenizer(if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) ImageTokenizerBackend.SDCPP_NATIVE else ImageTokenizerBackend.MNN_MTOK),
            conditioning = conditioning(ImageEmbeddingDiskDataType.RUNTIME_NATIVE, ImageEmbeddingConversionStrategy.RUNTIME_NATIVE, 1),
            vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
            graph = graph,
            defaults = defaults(size, 20, 7.0, useCfg = true),
            capabilities = if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                stableDiffusionCapabilities(ImageSchedulerAlgorithm.entries.toSet())
            } else {
                capabilities(size, ImageSchedulerAlgorithm.entries.toSet())
            }
        ).copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.GENERIC_FALLBACK,
                sources = listOf(ImageProfileSource.GENERIC_FALLBACK)
            )
        )
    }

    private fun profile(
        profileId: String,
        fingerprint: String,
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily,
        variant: ImageModelVariant,
        scheduler: ImageSchedulerContract,
        tokenizer: ImageTokenizerContract,
        conditioning: ImageConditioningContract,
        vae: ImageVaeContract,
        graph: ImageGraphContract,
        defaults: ImageGenerationDefaults,
        capabilities: ImageGenerationCapabilities,
        task: ImageTask = ImageTask.TEXT_TO_IMAGE
    ): ImageExecutionProfile = ImageExecutionProfile(
        profileId = profileId,
        profileRevision = 1,
        modelFingerprint = fingerprint,
        runtime = runtime,
        family = family,
        variant = variant,
        task = task,
        provenance = ImageProfileProvenance(ImageProfileSource.BUILT_IN, listOf(ImageProfileSource.BUILT_IN)),
        tokenizer = tokenizer,
        conditioning = conditioning,
        scheduler = scheduler,
        latent = ImageLatentContract(4, 8, ImageTensorLayout.NCHW, ImageTensorLayout.NCHW, listOf(1, 4, defaults.height / 8, defaults.width / 8)),
        vae = vae,
        graph = graph,
        defaults = defaults,
        capabilities = capabilities
    )

    private fun scheduler(
        algorithm: ImageSchedulerAlgorithm,
        prediction: ImagePredictionType,
        defaultSteps: Int,
        minSteps: Int,
        maxSteps: Int,
        stepsOffset: Int = 0,
        timestepSpacing: ImageTimestepSpacing = when (algorithm) {
            ImageSchedulerAlgorithm.EULER,
            ImageSchedulerAlgorithm.DPMPP_2M -> ImageTimestepSpacing.LINSPACE
            else -> ImageTimestepSpacing.LEADING
        },
        setAlphaToOne: Boolean = false,
        scaleModelInput: Boolean = false,
        skipPrk: Boolean = false,
        clipSample: Boolean = false
    ) = ImageSchedulerContract(
        algorithm = algorithm,
        predictionType = prediction,
        noiseSchedule = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) ImageNoiseSchedule.SIGMA else ImageNoiseSchedule.SCALED_LINEAR,
        betaStart = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) null else 0.00085,
        betaEnd = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) null else 0.012,
        timestepSpacing = timestepSpacing,
        stepsOffset = stepsOffset,
        setAlphaToOne = setAlphaToOne,
        skipPrkSteps = skipPrk,
        clipSample = clipSample,
        scaleModelInput = scaleModelInput,
        defaultSteps = defaultSteps,
        minSteps = minSteps,
        maxSteps = maxSteps
    )

    private fun clipTokenizer(
        backend: ImageTokenizerBackend,
        dualClip: Boolean = false,
        padZero: Boolean = false
    ) = ImageTokenizerContract(
        backend = backend,
        bosId = 49_406,
        eosId = 49_407,
        padId = if (dualClip || padZero) 0 else 49_407,
        maxLength = 77,
        clip1PadRule = if (padZero) ImageClipPadRule.ZERO else ImageClipPadRule.EOS,
        clip2PadRule = if (dualClip) ImageClipPadRule.ZERO else null,
        supportsPromptWeighting = true,
        separateNegativePrompt = true
    )

    private fun conditioning(
        dataType: ImageEmbeddingDiskDataType,
        conversion: ImageEmbeddingConversionStrategy,
        width: Int,
        dualEncoder: Boolean = false,
        pooled: Boolean = false
    ) = ImageConditioningContract(
        diskDataType = dataType,
        textEncoderInputShape = listOf(1, 77),
        textEncoderOutputShapes = if (dualEncoder) listOf(listOf(1, 77, 768), listOf(1, 77, 1_280)) else listOf(listOf(1, 77, width)),
        conversionStrategy = conversion,
        dualEncoder = dualEncoder,
        pooledOutput = pooled,
        concatenationOrder = if (dualEncoder) listOf("clip1_hidden", "clip2_hidden", "clip2_pooled") else listOf("negative", "positive")
    )

    private fun vae(location: ImageVaeScalingLocation, factor: Double, size: Int) = ImageVaeContract(
        scalingLocation = location,
        scalingFactor = factor,
        inputShape = listOf(1, 4, size / 8, size / 8),
        outputShape = listOf(1, 3, size, size),
        inputLayout = ImageTensorLayout.NCHW,
        outputLayout = ImageTensorLayout.NCHW,
        outputRange = ImagePixelRange.NEGATIVE_ONE_TO_ONE,
        channelOrder = ImageChannelOrder.RGB
    )

    private fun qnnGraph(
        textEncoder: String,
        unet: String,
        vae: String,
        qnnSdk: String?,
        htpArch: Int?,
        strategy: ImageWorkerStrategy = ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
    ) = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract(textEncoder),
        unet = ImageGraphArtifactContract(unet),
        vae = ImageGraphArtifactContract(vae),
        schedulerSidecar = "scheduler/scheduler_config.json",
        tokenizerSidecar = "tokenizer/tokenizer_config.json",
        qnnSdk = qnnSdk,
        htpArch = htpArch,
        workerStrategy = strategy
    )

    private fun defaults(size: Int, steps: Int, cfg: Double, useCfg: Boolean) = ImageGenerationDefaults(
        width = size,
        height = size,
        steps = steps,
        cfgScale = cfg,
        seed = 42L,
        useCfg = useCfg
    )

    private fun capabilities(size: Int, schedulers: Set<ImageSchedulerAlgorithm>) = ImageGenerationCapabilities(
        supportedSchedulers = schedulers,
        minWidth = size,
        maxWidth = size,
        minHeight = size,
        maxHeight = size,
        supportsNegativePrompt = true,
        supportsPromptWeighting = true
    )

    private fun stableDiffusionCapabilities(
        schedulers: Set<ImageSchedulerAlgorithm>
    ) = ImageGenerationCapabilities(
        supportedSchedulers = schedulers,
        minWidth = 256,
        maxWidth = 1_536,
        minHeight = 256,
        maxHeight = 1_536,
        widthMultiple = 64,
        heightMultiple = 64,
        supportsNegativePrompt = true,
        supportsPromptWeighting = true
    )

    private fun markAllProfileFields(
        target: MutableMap<String, ImageProfileSource>,
        source: ImageProfileSource
    ) {
        listOf(
            "profileId",
            "profileRevision",
            "runtime",
            "family",
            "variant",
            "task",
            "tokenizer",
            "conditioning",
            "scheduler",
            "latent",
            "vae",
            "graph",
            "defaults",
            "capabilities"
        ).forEach { field -> target[field] = source }
    }

    private fun hasUserOverrides(value: ImageGenerationOverrides): Boolean =
        value.expectedProfileId != null ||
            value.expectedProfileRevision != null ||
            value.scheduler != null ||
            value.predictionType != null ||
            value.steps != null ||
            value.cfgScale != null ||
            value.useCfg != null ||
            value.width != null ||
            value.height != null ||
            value.seed != null ||
            value.negativePromptSpecified
}

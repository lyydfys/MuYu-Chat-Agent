package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

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
    val capabilities: ImageGenerationCapabilities? = null,
    val behavior: ImagePackageBehaviorConfig? = null
)

/**
 * Partial user-facing behavior declared by a model package. Unlike a complete
 * execution profile, every field is optional and only overrides the matching
 * built-in or generic field. It never grants device admission or bypasses a
 * native load/execute failure.
 */
internal data class ImagePackageBehaviorConfig(
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val defaultPrompt: String? = null,
    val defaultNegativePrompt: String? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val scheduler: ImageSchedulerAlgorithm? = null,
    val width: Int? = null,
    val height: Int? = null,
    val useCfg: Boolean? = null
) {
    fun overlay(higherPriority: ImagePackageBehaviorConfig): ImagePackageBehaviorConfig =
        ImagePackageBehaviorConfig(
            family = higherPriority.family ?: family,
            variant = higherPriority.variant ?: variant,
            defaultPrompt = higherPriority.defaultPrompt ?: defaultPrompt,
            defaultNegativePrompt = higherPriority.defaultNegativePrompt ?: defaultNegativePrompt,
            steps = higherPriority.steps ?: steps,
            cfgScale = higherPriority.cfgScale ?: cfgScale,
            scheduler = higherPriority.scheduler ?: scheduler,
            width = higherPriority.width ?: width,
            height = higherPriority.height ?: height,
            useCfg = higherPriority.useCfg ?: useCfg
        )
}

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

/**
 * Stable package identity carried independently from device information.
 * Every value is advisory unless it matches an exact catalog identity rule;
 * an unknown value always falls through to capability discovery or the
 * generic compatible profile.
 */
internal data class ImageRecommendationEvidence(
    val aliases: List<String> = emptyList(),
    val sourceRepositories: List<String> = emptyList(),
    val artifactPaths: List<String> = emptyList()
)

internal data class ImageExecutionProfileResolverInput(
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily,
    val recommendationId: String? = null,
    val recommendationRevision: String? = null,
    val manifestProfile: ImageExecutionProfile? = null,
    val manifestBehavior: ImagePackageBehaviorConfig? = null,
    val sidecar: ImageProfileSidecar? = null,
    val capabilityDiscovery: ImageCapabilityDiscovery? = null,
    val recommendationEvidence: ImageRecommendationEvidence = ImageRecommendationEvidence(),
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

private data class BuiltInImageProfileIdentityRule(
    val recommendationId: String,
    val aliases: Set<String> = emptySet(),
    val primaryRepositories: Set<String> = emptySet(),
    val artifactMarkers: Set<String> = emptySet(),
    val modelFingerprints: Set<String> = emptySet()
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
    private val identityRules: List<BuiltInImageProfileIdentityRule> = listOf(
        identityRule(
            "cyberrealistic_sd15_qnn228",
            aliases = setOf("cyberrealistic-sd15-qnn228-8gen2"),
            repositories = setOf("Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28"),
            artifacts = setOf(
                "cyberrealistic_final_qnn2.28_min.zip",
                "cyberrealistic_final_qnn2.28_8gen2.zip"
            ),
            fingerprints = setOf("9daf0e4d80d14ae93c774faf5366702c58b0cdb71618d5e5130b54226936bf3f")
        ),
        identityRule(
            "realisticvisionhyper_sd15_qnn228",
            repositories = setOf("Mr-J-369/RealisticVisionHyper-SD1.5-qnn2.28"),
            artifacts = setOf("RealisticVisionHyper-qnn2.28-min.zip"),
            fingerprints = setOf("7f552ad7f9070f1e482d93d3785ceedd6f3fc1d437db9c5da00d81d9edd34b86")
        ),
        identityRule(
            "dreamshaper_sd15_qnn228",
            repositories = setOf("Mr-J-369/DreamShaper-SD1.5-qnn2.28"),
            artifacts = setOf("DreamShaperV8-qnn2.28-min.zip"),
            fingerprints = setOf("e4fbd2a28db64b038372d1847d82b66f2f754ed0e95d412a283104b9382ae59c")
        ),
        identityRule(
            "meinamix_sd15_qnn228",
            repositories = setOf("Mr-J-369/MeinaMix-SD1.5-qnn2.28"),
            artifacts = setOf("MeinaMix-qnn2.28-8gen2.zip")
        ),
        identityRule(
            "sdxl_base_qnn228",
            repositories = setOf("xororz/sdxl-qnn"),
            artifacts = setOf("sdxl_base_qnn2.28_8gen3.zip"),
            fingerprints = setOf("426e36987fd3b84dd05255cb12bc5463c427c8b55598bd3b2486a72291d6be7f")
        ),
        identityRule(
            "realismsdxl_dmd2_alt_qnn228",
            repositories = setOf("Mr-J-369/RealismByStableYogiV8.0_DMD2_ALT-SDXL-qnn2.28"),
            artifacts = setOf("realismSDXLByStable_v80DMD2ALT_qnn2.28_8gen3.zip"),
            fingerprints = setOf("e95df91391f1f6f6f39416985ada906fec77d65496d3f52f54feb0c3da3744e8")
        ),
        identityRule(
            "animagine_xl_v4_qnn228",
            repositories = setOf("YuuiKurata/animagineXL_qnn2.28"),
            artifacts = setOf("animagineXL40_v4Opt_qnn2.28_8gen3.zip"),
            fingerprints = setOf("a08612048ad60e834ae7f5a1b234cfb7edd299e28dc20abab1a4a9be5bf34dfc")
        ),
        identityRule(
            "cyberrealisticxl_qnn228",
            repositories = setOf("xororz/sdxl-qnn"),
            artifacts = setOf("cyber_realistic_v10_qnn2.28_8gen3.zip"),
            fingerprints = setOf("2af39e9c80629a27406112e91627657981b50f28b477e7adaf9415d886e08ea2")
        ),
        identityRule(
            "qualcomm_sd15_gen5_qnn",
            repositories = setOf("qualcomm/Stable-Diffusion-v1.5"),
            artifacts = setOf("stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
        ),
        identityRule(
            "qualcomm_sd21_gen5_qnn",
            repositories = setOf("qualcomm/Stable-Diffusion-v2.1"),
            artifacts = setOf("stable_diffusion_v2_1-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
        ),
        identityRule(
            "qualcomm_controlnet_canny_gen5_qnn",
            repositories = setOf("qualcomm/ControlNet-Canny"),
            artifacts = setOf("controlnet_canny-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
        ),
        identityRule(
            "sd15_mnn_512_quality",
            aliases = setOf("sd15_mnn_bundle"),
            repositories = setOf("MNN/stable-diffusion-v1-5-mnn-opencl"),
            artifacts = setOf("stable-diffusion-v1-5-mnn-opencl")
        ),
        identityRule(
            "mnn_sana_edit_v2",
            repositories = setOf("MNN/MNN-Sana-Edit-V2"),
            artifacts = setOf("MNN-Sana-Edit-V2")
        ),
        identityRule(
            "sd_turbo_512_experimental",
            repositories = setOf("AI-ModelScope/sd-turbo"),
            artifacts = setOf("sd_turbo.safetensors"),
            fingerprints = setOf("3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549")
        ),
        identityRule(
            "z_image_turbo_q4",
            aliases = setOf("z_image_turbo_q2_bundle"),
            repositories = setOf("hf/leejet-Z-Image-Turbo-GGUF"),
            artifacts = setOf("z_image_turbo-Q2_K.gguf"),
            fingerprints = setOf("a9cf1b0368e24c2f9d542d2951c01f6f7fc85ed8c9ed39b5b37b15375508d58a")
        ),
        identityRule(
            "flux2_klein_4b_q4",
            repositories = setOf("hf/leejet-FLUX.2-klein-4B-GGUF"),
            artifacts = setOf("flux-2-klein-4b-Q4_0.gguf")
        ),
        identityRule(
            "qwen_image_2512_q2",
            repositories = setOf("unsloth/Qwen-Image-2512-GGUF"),
            artifacts = setOf("qwen-image-2512-Q2_K.gguf"),
            fingerprints = setOf("176678f0d4e6c613c5a318014f16d829438b8feec9454bde7b3070a520bf1728")
        ),
        identityRule(
            "longcat_image_q4",
            repositories = setOf("vantagewithai/LongCat-Image-GGUF"),
            artifacts = setOf("LongCat-Image-Q4_0.gguf"),
            fingerprints = setOf("d494513ea95e82fb7069cdb914738f22dfc940fc770000fbbc8ad0a7a445f601")
        )
    )
    private val stableDiffusionCppSchedulers = setOf(
        ImageSchedulerAlgorithm.EULER,
        ImageSchedulerAlgorithm.EULER_A,
        ImageSchedulerAlgorithm.DDIM,
        ImageSchedulerAlgorithm.DPMPP_2M,
        ImageSchedulerAlgorithm.LCM,
        ImageSchedulerAlgorithm.FLOW_MATCH
    )

    fun resolve(input: ImageExecutionProfileResolverInput): ImageExecutionProfileResolution {
        val fieldSources = linkedMapOf<String, ImageProfileSource>()
        val sourceChain = mutableListOf<ImageProfileSource>()
        val fingerprint = input.modelFingerprint.trim().lowercase()
        val packageFamily = if (input.manifestProfile == null) {
            input.manifestBehavior?.family ?: input.sidecar?.behavior?.family
        } else {
            input.manifestProfile.family
        }
        val fallbackFamily = packageFamily ?: input.capabilityDiscovery?.family ?: input.family

        val base: ImageExecutionProfile = if (input.manifestProfile != null) {
            sourceChain += ImageProfileSource.MANIFEST
            markAllProfileFields(fieldSources, ImageProfileSource.MANIFEST)
            input.manifestProfile
        } else {
            val builtInTarget = resolveBuiltInTarget(input)
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
                        genericProfile(input.runtime, fallbackFamily, fingerprint),
                        input.capabilityDiscovery
                    )
                }
                else -> {
                    sourceChain += ImageProfileSource.GENERIC_FALLBACK
                    markAllProfileFields(fieldSources, ImageProfileSource.GENERIC_FALLBACK)
                    genericProfile(input.runtime, fallbackFamily, fingerprint)
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
        if (input.manifestProfile == null && input.manifestBehavior != null) {
            sourceChain.add(0, ImageProfileSource.MANIFEST)
            resolvedProfile = applyPackageBehavior(
                base = resolvedProfile,
                behavior = input.manifestBehavior,
                source = ImageProfileSource.MANIFEST,
                sources = fieldSources
            )
        }
        if (input.userOverrides.negativePromptSpecified &&
            !input.userOverrides.negativePrompt.isNullOrEmpty() &&
            !resolvedProfile.capabilities.supportsNegativePrompt
        ) {
            throw ImageProfileResolutionException(
                ImageProfileValidationReport(
                    listOf(
                        ImageProfileValidationIssue(
                            code = "NEGATIVE_PROMPT_UNSUPPORTED",
                            field = "defaults.defaultNegativePrompt",
                            message = "This image profile has no executable negative-prompt branch."
                        )
                    )
                )
            )
        }
        resolvedProfile = applyUserOverrides(resolvedProfile, input.userOverrides, fieldSources)
        if (hasUserOverrides(input.userOverrides)) sourceChain.add(0, ImageProfileSource.USER_OVERRIDE)
        resolvedProfile = resolvedProfile.copy(
            provenance = ImageProfileProvenance(
                primarySource = sourceChain.first(),
                sources = sourceChain.distinct(),
                recommendationId = resolvedProfile.provenance.recommendationId ?: input.recommendationId,
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

    private fun resolveBuiltInTarget(
        input: ImageExecutionProfileResolverInput
    ): BuiltInImageProfileTarget? {
        // Exact bytes are stronger identity evidence than a requested card or
        // legacy bundle alias. This prevents stale request metadata from
        // silently applying another recommendation's execution profile.
        val fingerprint = input.modelFingerprint.trim().lowercase()
        identityRules.singleOrNull { rule -> fingerprint in rule.modelFingerprints }
            ?.let { return targetByRecommendationId.getValue(it.recommendationId) }

        val aliasTokens = buildSet {
            input.recommendationId
                ?.takeIf(String::isNotBlank)
                ?.let { add(normalizeIdentityToken(it)) }
            input.recommendationEvidence.aliases
                .asSequence()
                .filter(String::isNotBlank)
                .map(::normalizeIdentityToken)
                .forEach(::add)
        }
        identityRules.singleOrNull { rule -> rule.aliases.any(aliasTokens::contains) }
            ?.let { return targetByRecommendationId.getValue(it.recommendationId) }

        val sourceRepositories = input.recommendationEvidence.sourceRepositories
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeRepositoryIdentity)
            .toSet()
        val artifactTokens = input.recommendationEvidence.artifactPaths
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { value -> artifactIdentityTokens(value).asSequence() }
            .toSet()
        val repositoryMatches = identityRules.filter { rule ->
            rule.primaryRepositories.any(sourceRepositories::contains)
        }
        if (repositoryMatches.size == 1) {
            return targetByRecommendationId.getValue(repositoryMatches.single().recommendationId)
        }
        repositoryMatches.singleOrNull { rule ->
            rule.artifactMarkers.any(artifactTokens::contains)
        }?.let { return targetByRecommendationId.getValue(it.recommendationId) }

        identityRules.singleOrNull { rule ->
            rule.artifactMarkers.any(artifactTokens::contains)
        }?.let { return targetByRecommendationId.getValue(it.recommendationId) }
        return null
    }

    private fun identityRule(
        recommendationId: String,
        aliases: Set<String> = emptySet(),
        repositories: Set<String> = emptySet(),
        artifacts: Set<String> = emptySet(),
        fingerprints: Set<String> = emptySet()
    ): BuiltInImageProfileIdentityRule {
        require(targetByRecommendationId.containsKey(recommendationId)) {
            "Unknown recommendation identity rule: $recommendationId"
        }
        val normalizedAliases = buildSet {
            add(normalizeIdentityToken(recommendationId))
            add(normalizeIdentityToken("${recommendationId}_bundle"))
            aliases.mapTo(this, ::normalizeIdentityToken)
        }
        return BuiltInImageProfileIdentityRule(
            recommendationId = recommendationId,
            aliases = normalizedAliases,
            primaryRepositories = repositories.mapTo(linkedSetOf(), ::normalizeRepositoryIdentity),
            artifactMarkers = (artifacts + aliases + recommendationId + "${recommendationId}_bundle")
                .mapTo(linkedSetOf(), ::normalizeIdentityToken),
            modelFingerprints = fingerprints.mapTo(linkedSetOf()) { it.trim().lowercase() }
        )
    }

    private fun normalizeRepositoryIdentity(value: String): String {
        val normalized = value.trim().lowercase().replace('\\', '/').trim('/')
        val withoutProvider = listOf("hugging_face:", "modelscope:")
            .firstOrNull(normalized::startsWith)
            ?.let { prefix -> normalized.removePrefix(prefix) }
            ?: normalized
        return withoutProvider.removeSuffix(".git").trim('/')
    }

    private fun artifactIdentityTokens(value: String): Set<String> = buildSet {
        value.trim()
            .replace('\\', '/')
            .split('/')
            .asSequence()
            .filter(String::isNotBlank)
            .forEach { segment ->
                val normalized = normalizeIdentityToken(segment)
                if (normalized.isNotBlank()) {
                    add(normalized)
                    normalized.removePrefix("bundle_").takeIf(String::isNotBlank)?.let(::add)
                    normalized.removeSuffix("_bundle").takeIf(String::isNotBlank)?.let(::add)
                }
                val stem = segment.substringBeforeLast('.', segment)
                val normalizedStem = normalizeIdentityToken(stem)
                if (normalizedStem.isNotBlank()) {
                    add(normalizedStem)
                    normalizedStem.removePrefix("bundle_").takeIf(String::isNotBlank)?.let(::add)
                    normalizedStem.removeSuffix("_bundle").takeIf(String::isNotBlank)?.let(::add)
                }
            }
    }

    private fun normalizeIdentityToken(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

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
        val replaced = base.copy(
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
        return sidecar.behavior?.let { behavior ->
            applyPackageBehavior(replaced, behavior, ImageProfileSource.SIDECAR, sources)
        } ?: replaced
    }

    private fun applyPackageBehavior(
        base: ImageExecutionProfile,
        behavior: ImagePackageBehaviorConfig,
        source: ImageProfileSource,
        sources: MutableMap<String, ImageProfileSource>
    ): ImageExecutionProfile {
        fun mark(field: String, present: Boolean) {
            if (present) sources[field] = source
        }

        mark("family", behavior.family != null)
        mark("variant", behavior.variant != null)
        mark("scheduler.algorithm", behavior.scheduler != null)
        mark("scheduler.defaultSteps", behavior.steps != null)
        mark("defaults.defaultPrompt", behavior.defaultPrompt != null)
        mark("defaults.defaultNegativePrompt", behavior.defaultNegativePrompt != null)
        mark("defaults.steps", behavior.steps != null)
        mark("defaults.cfgScale", behavior.cfgScale != null)
        mark("defaults.width", behavior.width != null)
        mark("defaults.height", behavior.height != null)
        mark("defaults.useCfg", behavior.useCfg != null || behavior.cfgScale != null)

        val width = behavior.width ?: base.defaults.width
        val height = behavior.height ?: base.defaults.height
        val dimensionsChanged = width != base.defaults.width || height != base.defaults.height
        if (dimensionsChanged) {
            sources["latent.initialShape"] = source
            sources["vae.inputShape"] = source
            sources["vae.outputShape"] = source
        }
        val cfgScale = behavior.cfgScale ?: base.defaults.cfgScale
        val useCfg = behavior.useCfg
            ?: behavior.cfgScale?.let { kotlin.math.abs(it - 1.0) > 1.0e-12 }
            ?: base.defaults.useCfg
        val scheduler = base.scheduler.copy(
            algorithm = behavior.scheduler ?: base.scheduler.algorithm,
            defaultSteps = behavior.steps ?: base.scheduler.defaultSteps
        )
        val resizedContracts = resizedImageContracts(base, width, height)
        return base.copy(
            family = behavior.family ?: base.family,
            variant = behavior.variant ?: base.variant,
            scheduler = scheduler,
            latent = if (dimensionsChanged) resizedContracts.first else base.latent,
            vae = if (dimensionsChanged) resizedContracts.second else base.vae,
            defaults = base.defaults.copy(
                width = width,
                height = height,
                steps = behavior.steps ?: base.defaults.steps,
                cfgScale = cfgScale,
                useCfg = useCfg,
                defaultPrompt = behavior.defaultPrompt ?: base.defaults.defaultPrompt,
                defaultNegativePrompt = behavior.defaultNegativePrompt
                    ?: base.defaults.defaultNegativePrompt
            )
        )
    }

    private fun resizedImageContracts(
        base: ImageExecutionProfile,
        width: Int,
        height: Int
    ): Pair<ImageLatentContract, ImageVaeContract> {
        val latentWidth = width / base.latent.downsampleFactor
        val latentHeight = height / base.latent.downsampleFactor
        return base.latent.copy(
            initialShape = resizedImageTensorShape(
                layout = base.latent.schedulerLayout,
                current = base.latent.initialShape,
                channels = base.latent.channels,
                width = latentWidth,
                height = latentHeight
            )
        ) to base.vae.copy(
            inputShape = resizedImageTensorShape(
                layout = base.vae.inputLayout,
                current = base.vae.inputShape,
                channels = base.latent.channels,
                width = latentWidth,
                height = latentHeight
            ),
            outputShape = resizedImageTensorShape(
                layout = base.vae.outputLayout,
                current = base.vae.outputShape,
                channels = 3,
                width = width,
                height = height
            )
        )
    }

    private fun resizedImageTensorShape(
        layout: ImageTensorLayout,
        current: List<Int>,
        channels: Int,
        width: Int,
        height: Int
    ): List<Int> = when (layout) {
        ImageTensorLayout.NCHW,
        ImageTensorLayout.BCHW -> listOf(1, channels, height, width)
        ImageTensorLayout.NHWC -> listOf(1, height, width, channels)
        else -> current
    }

    private fun applyCapabilityDiscovery(
        base: ImageExecutionProfile,
        discovery: ImageCapabilityDiscovery
    ): ImageExecutionProfile {
        val tokenizer = discovery.tokenizer ?: base.tokenizer
        val discoveredCapabilities = discovery.capabilities ?: base.capabilities
        val capabilities = discoveredCapabilities.copy(
            supportsPromptWeighting = tokenizer.supportsPromptWeighting &&
                base.runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION)
        )
        return base.copy(
            family = discovery.family ?: base.family,
            variant = discovery.variant ?: base.variant,
            task = discovery.task ?: base.task,
            tokenizer = tokenizer,
            conditioning = discovery.conditioning ?: base.conditioning,
            scheduler = discovery.scheduler ?: base.scheduler,
            latent = discovery.latent ?: base.latent,
            vae = discovery.vae ?: base.vae,
            graph = discovery.graph ?: base.graph,
            defaults = discovery.defaults ?: base.defaults,
            capabilities = capabilities
        )
    }

    private fun applyUserOverrides(
        base: ImageExecutionProfile,
        overrides: ImageGenerationOverrides,
        sources: MutableMap<String, ImageProfileSource>
    ): ImageExecutionProfile {
        val schedulerChanged = overrides.scheduler != null || overrides.predictionType != null
        val width = overrides.width ?: base.defaults.width
        val height = overrides.height ?: base.defaults.height
        val dimensionsChanged = width != base.defaults.width || height != base.defaults.height
        if (schedulerChanged) sources["scheduler"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.steps != null) sources["defaults.steps"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.cfgScale != null) sources["defaults.cfgScale"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.useCfg != null) sources["defaults.useCfg"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.width != null) sources["defaults.width"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.height != null) sources["defaults.height"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.seed != null) sources["defaults.seed"] = ImageProfileSource.USER_OVERRIDE
        if (overrides.negativePromptSpecified) sources["defaults.defaultNegativePrompt"] = ImageProfileSource.USER_OVERRIDE
        if (dimensionsChanged) {
            sources["latent.initialShape"] = ImageProfileSource.USER_OVERRIDE
            sources["vae.inputShape"] = ImageProfileSource.USER_OVERRIDE
            sources["vae.outputShape"] = ImageProfileSource.USER_OVERRIDE
        }
        val resizedContracts = resizedImageContracts(base, width, height)
        return base.copy(
            scheduler = base.scheduler.copy(
                algorithm = overrides.scheduler ?: base.scheduler.algorithm,
                predictionType = overrides.predictionType ?: base.scheduler.predictionType
            ),
            latent = if (dimensionsChanged) resizedContracts.first else base.latent,
            vae = if (dimensionsChanged) resizedContracts.second else base.vae,
            defaults = base.defaults.copy(
                width = width,
                height = height,
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
        val tokenCount = when {
            profile.family == LocalImageModelFamily.SANA &&
                profile.tokenizer.backend == ImageTokenizerBackend.MNN_MTOK -> {
                // Sana reports the sequence axis consumed by each conditioning
                // branch. Positive and negative branches are separate executions,
                // so adding both 256-token sequences would misstate the native
                // contract as 512 tokens.
                profile.conditioning.textEncoderInputShape.getOrNull(1)
                    ?.takeIf { it > 0 }
                    ?: profile.tokenizer.maxLength
            }
            profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
                // stable-diffusion.cpp only prepares the negative conditioner
                // when CFG actually executes. This is the resolved minimum
                // branch capacity; native reports the prompt-dependent token
                // count separately after real conditioning runs.
                profile.tokenizer.maxLength * branches
            }
            profile.runtime == LocalImageRuntime.QNN_HTP -> {
                // QNN packages consume the fixed negative/positive CLIP
                // payload contract even when a distilled UNet executes only
                // the positive branch.
                profile.tokenizer.maxLength * 2
            }
            else -> profile.tokenizer.maxLength *
                if (profile.tokenizer.separateNegativePrompt) 2 else 1
        }
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
            tokenCount = tokenCount,
            promptWeightingSupported =
                profile.tokenizer.supportsPromptWeighting &&
                    profile.capabilities.supportsPromptWeighting,
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
        val profile = profileTemplate(target, modelFingerprint)
        return profile.copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.BUILT_IN,
                sources = listOf(ImageProfileSource.BUILT_IN),
                recommendationId = target.recommendationId,
                recommendationRevision = recommendationRevision
            )
        )
    }

    private fun profileTemplate(
        target: BuiltInImageProfileTarget,
        fingerprint: String
    ): ImageExecutionProfile = when (val profileId = target.profileId) {
        "community.sd15.qnn228" -> qnnSd15Profile(
            profileId,
            fingerprint,
            defaultNegativePrompt = if (target.recommendationId == "cyberrealistic_sd15_qnn228") {
                RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
            } else {
                RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
            }
        )
        "community.sd15.hyper.qnn228" -> qnnSd15Profile(
            profileId,
            fingerprint,
            ImageModelVariant.HYPER,
            8,
            2.0,
            defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
        )
        "community.sd15.legacy-fp32.qnn228" -> qnnSd15Profile(
            profileId,
            fingerprint,
            ImageModelVariant.LEGACY_FP32,
            conditioningType = ImageEmbeddingDiskDataType.FP32,
            conversion = ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
            defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
        )
        "community.sdxl.base.qnn228" -> qnnSdxlProfile(
            profileId = profileId,
            fingerprint = fingerprint,
            steps = when (target.recommendationId) {
                "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_STEPS
                "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS
                else -> 30
            },
            cfg = when (target.recommendationId) {
                "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_CFG
                "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_CFG
                else -> 7.0
            },
            defaultNegativePrompt = when (target.recommendationId) {
                "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
                "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
                else -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
            }
        )
        "community.sdxl.dmd2-alt.qnn228" -> qnnSdxlProfile(
            profileId,
            fingerprint,
            variant = ImageModelVariant.DMD2_ALT,
            steps = 4,
            cfg = 1.0,
            useCfg = false,
            timestepSpacing = ImageTimestepSpacing.LINSPACE,
            defaultNegativePrompt = null,
            supportsNegativePrompt = false
        )
        "qualcomm.sd15.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = false)
        "qualcomm.sd21.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = true)
        "qualcomm.controlnet-canny.gen5.qnn245" -> qnnControlNetProfile(profileId, fingerprint)
        "mnn.sd15.official.512" -> mnnSd15Profile(profileId, fingerprint)
        "mnn.sana-edit.v2" -> sanaEditProfile(profileId, fingerprint)
        "sdcpp.sd-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.SD_TURBO, ImageModelVariant.SD_TURBO, 4, 1.0, ImageSchedulerAlgorithm.EULER_A, supportsNegativePrompt = false)
        "sdcpp.z-image-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.Z_IMAGE, ImageModelVariant.Z_IMAGE_TURBO, 8, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, supportsNegativePrompt = false)
        "sdcpp.flux2-klein" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.FLUX, ImageModelVariant.FLUX2_KLEIN, 4, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, supportsNegativePrompt = false)
        "sdcpp.qwen-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.QWEN_IMAGE, ImageModelVariant.QWEN_IMAGE, 40, 2.5, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT)
        "sdcpp.longcat-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.LONGCAT_IMAGE, ImageModelVariant.LONGCAT_IMAGE, 20, 5.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT)
        else -> error("Unknown built-in image profile target: $profileId")
    }

    private fun qnnSd15Profile(
        profileId: String,
        fingerprint: String,
        variant: ImageModelVariant = ImageModelVariant.STANDARD,
        steps: Int = 20,
        cfg: Double = 7.0,
        conditioningType: ImageEmbeddingDiskDataType = ImageEmbeddingDiskDataType.FP16,
        conversion: ImageEmbeddingConversionStrategy = ImageEmbeddingConversionStrategy.NONE,
        defaultNegativePrompt: String = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.QNN_HTP,
        family = LocalImageModelFamily.SD15,
        variant = variant,
        scheduler = scheduler(
            ImageSchedulerAlgorithm.DPMPP_2M,
            ImagePredictionType.EPSILON,
            steps,
            if (variant == ImageModelVariant.HYPER) 1 else 10,
            50,
            timestepSpacing = ImageTimestepSpacing.LEADING,
            order = 2
        ),
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP),
        conditioning = conditioning(conditioningType, conversion, 768),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
        graph = qnnGraph(
            "clip_text_encoder_qnn_context.bin",
            "unet.bin",
            "vae_decoder.bin",
            "2.28",
            68,
            vaeEncoder = "vae_encoder.bin"
        ),
        defaults = defaults(
            512,
            steps,
            cfg,
            useCfg = true,
            defaultNegativePrompt = defaultNegativePrompt
        ),
        capabilities = capabilities(512, setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER, ImageSchedulerAlgorithm.PNDM_PLMS))
    )

    private fun qnnSdxlProfile(
        profileId: String,
        fingerprint: String,
        variant: ImageModelVariant = ImageModelVariant.SDXL_BASE,
        steps: Int = 30,
        cfg: Double = 7.0,
        useCfg: Boolean = true,
        timestepSpacing: ImageTimestepSpacing = ImageTimestepSpacing.TRAILING,
        defaultNegativePrompt: String? = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT,
        supportsNegativePrompt: Boolean = true
    ): ImageExecutionProfile = profile(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.QNN_HTP,
        family = LocalImageModelFamily.SDXL,
        variant = variant,
        scheduler = scheduler(
            ImageSchedulerAlgorithm.DPMPP_2M,
            ImagePredictionType.EPSILON,
            steps,
            1,
            50,
            timestepSpacing = timestepSpacing,
            order = 2
        ),
        tokenizer = clipTokenizer(
            ImageTokenizerBackend.TOKENIZERS_CPP,
            dualClip = true,
            separateNegativePrompt = supportsNegativePrompt
        ),
        conditioning = conditioning(ImageEmbeddingDiskDataType.FP16, ImageEmbeddingConversionStrategy.NONE, 2_048, dualEncoder = true, pooled = true),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025, 1024),
        graph = qnnGraph(
            textEncoder = "clip.mnn",
            unet = "unet.bin",
            vae = "vae_decoder.bin",
            qnnSdk = "2.28",
            htpArch = null,
            strategy = ImageWorkerStrategy.SPLIT_UNET_VAE,
            vaeEncoder = "vae_encoder.bin"
        ),
        defaults = defaults(1024, steps, cfg, useCfg, defaultNegativePrompt),
        capabilities = capabilities(
            1024,
            setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER, ImageSchedulerAlgorithm.LCM),
            supportsNegativePrompt = supportsNegativePrompt
        )
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
                1,
                100,
                stepsOffset = 1,
                timestepSpacing = ImageTimestepSpacing.LEADING,
                setAlphaToOne = false,
                skipPrk = true,
                clipSample = false
            )
        } else {
            scheduler(
                ImageSchedulerAlgorithm.EULER,
                ImagePredictionType.EPSILON,
                20,
                1,
                100,
                stepsOffset = 1,
                timestepSpacing = ImageTimestepSpacing.LINSPACE,
                setAlphaToOne = false,
                scaleModelInput = true,
                skipPrk = true
            )
        },
        tokenizer = clipTokenizer(
            ImageTokenizerBackend.TOKENIZERS_CPP,
            padZero = sd21,
            supportsPromptWeighting = false
        ),
        conditioning = conditioning(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, if (sd21) 1_024 else 768),
        vae = vae(ImageVaeScalingLocation.GRAPH_INTERNAL, 0.18215, 512),
        graph = qnnGraph("text_encoder.bin", "unet.bin", "vae.bin", "2.45.0.260326154327", 81, ImageWorkerStrategy.SHARED_TEXT_UNET_VAE),
        defaults = defaults(
            512,
            20,
            7.5,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        ),
        capabilities = capabilities(
            512,
            setOf(if (sd21) ImageSchedulerAlgorithm.DDIM else ImageSchedulerAlgorithm.EULER),
            supportsPromptWeighting = false
        )
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
        scheduler = scheduler(
            ImageSchedulerAlgorithm.DPMPP_2M,
            ImagePredictionType.EPSILON,
            20,
            10,
            50,
            timestepSpacing = ImageTimestepSpacing.LEADING,
            order = 2
        ),
        tokenizer = clipTokenizer(ImageTokenizerBackend.TOKENIZERS_CPP),
        conditioning = conditioning(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, 768),
        vae = vae(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
        graph = ImageGraphContract(
            textEncoder = ImageGraphArtifactContract("text_encoder.mnn"),
            unet = ImageGraphArtifactContract("unet.mnn"),
            vae = ImageGraphArtifactContract("vae_decoder.mnn"),
            workerStrategy = ImageWorkerStrategy.IN_PROCESS
        ),
        defaults = defaults(
            512,
            20,
            7.0,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        ),
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
            10,
            2,
            50
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
            width = 1,
            maxLength = 256
        ),
        vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, 512),
        graph = ImageGraphContract(
            textEncoder = ImageGraphArtifactContract("llm/llm.mnn"),
            unet = ImageGraphArtifactContract("transformer.mnn"),
            vae = ImageGraphArtifactContract("vae_decoder.mnn"),
            vaeEncoder = ImageGraphArtifactContract("vae_encoder.mnn"),
            configSidecars = listOf("llm/meta_queries.mnn"),
            workerStrategy = ImageWorkerStrategy.DEDICATED_WORKER
        ),
        defaults = defaults(
            512,
            10,
            4.5,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT
        ),
        capabilities = capabilities(
            512,
            setOf(ImageSchedulerAlgorithm.FLOW_MATCH),
            supportsPromptWeighting = false
        ).copy(requiresInputImage = true, supportsMask = false)
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
        defaultNegativePrompt: String? = null,
        supportsNegativePrompt: Boolean = true,
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
        tokenizer = clipTokenizer(
            ImageTokenizerBackend.SDCPP_NATIVE,
            separateNegativePrompt = supportsNegativePrompt
        ),
        conditioning = conditioning(
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
            ImageEmbeddingConversionStrategy.RUNTIME_NATIVE,
            1,
            separateNegativePrompt = supportsNegativePrompt
        ),
        vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
        graph = ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS),
        defaults = defaults(
            size,
            steps,
            cfg,
            useCfg = kotlin.math.abs(cfg - 1.0) > 1.0e-12,
            defaultNegativePrompt = defaultNegativePrompt
        ),
        capabilities = stableDiffusionCapabilities(
            when (algorithm) {
                ImageSchedulerAlgorithm.FLOW_MATCH -> setOf(ImageSchedulerAlgorithm.FLOW_MATCH)
                ImageSchedulerAlgorithm.EULER_A -> setOf(
                    ImageSchedulerAlgorithm.EULER_A,
                    ImageSchedulerAlgorithm.EULER,
                    ImageSchedulerAlgorithm.DPMPP_2M
                )
                else -> setOf(algorithm)
            },
            supportsNegativePrompt = supportsNegativePrompt
        )
    )

    private fun genericProfile(
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily,
        fingerprint: String
    ): ImageExecutionProfile {
        val flowFamily = family in setOf(
            LocalImageModelFamily.Z_IMAGE,
            LocalImageModelFamily.QWEN_IMAGE,
            LocalImageModelFamily.GLM_IMAGE,
            LocalImageModelFamily.LONGCAT_IMAGE,
            LocalImageModelFamily.DREAMLITE,
            LocalImageModelFamily.FLUX
        )
        val size = if (family == LocalImageModelFamily.SDXL || flowFamily) 1024 else 512
        val conditionalOnly = family in setOf(
            LocalImageModelFamily.SD_TURBO,
            LocalImageModelFamily.Z_IMAGE,
            LocalImageModelFamily.FLUX
        )
        val genericSteps = when (family) {
            LocalImageModelFamily.SD_TURBO,
            LocalImageModelFamily.FLUX -> 4
            LocalImageModelFamily.Z_IMAGE -> 8
            LocalImageModelFamily.QWEN_IMAGE -> 40
            else -> 20
        }
        val genericCfg = when (family) {
            LocalImageModelFamily.QWEN_IMAGE -> 2.5
            LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
            else -> if (conditionalOnly) 1.0 else 7.0
        }
        val algorithm = when (runtime) {
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> if (flowFamily) {
                ImageSchedulerAlgorithm.FLOW_MATCH
            } else {
                ImageSchedulerAlgorithm.EULER_A
            }
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
            scheduler = scheduler(
                algorithm,
                if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) {
                    ImagePredictionType.FLOW
                } else {
                    ImagePredictionType.EPSILON
                },
                genericSteps,
                1,
                100,
                skipPrk = algorithm == ImageSchedulerAlgorithm.PNDM_PLMS
            ),
            tokenizer = clipTokenizer(
                if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                    ImageTokenizerBackend.SDCPP_NATIVE
                } else {
                    ImageTokenizerBackend.MNN_MTOK
                },
                separateNegativePrompt = !conditionalOnly
            ),
            conditioning = conditioning(
                ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
                ImageEmbeddingConversionStrategy.RUNTIME_NATIVE,
                1,
                separateNegativePrompt = !conditionalOnly
            ),
            vae = vae(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
            graph = graph,
            defaults = defaults(
                size,
                genericSteps,
                genericCfg,
                useCfg = !conditionalOnly
            ),
            capabilities = if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                stableDiffusionCapabilities(
                    if (flowFamily) {
                        setOf(ImageSchedulerAlgorithm.FLOW_MATCH)
                    } else {
                        stableDiffusionCppSchedulers
                    },
                    supportsNegativePrompt = !conditionalOnly
                )
            } else {
                capabilities(
                    size,
                    ImageSchedulerAlgorithm.entries.toSet(),
                    supportsPromptWeighting = false,
                    supportsNegativePrompt = !conditionalOnly
                )
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
        clipSample: Boolean = false,
        order: Int = 1
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
        order = order,
        defaultSteps = defaultSteps,
        minSteps = minSteps,
        maxSteps = maxSteps
    )

    private fun clipTokenizer(
        backend: ImageTokenizerBackend,
        dualClip: Boolean = false,
        padZero: Boolean = false,
        supportsPromptWeighting: Boolean = backend == ImageTokenizerBackend.TOKENIZERS_CPP,
        separateNegativePrompt: Boolean = true
    ) = ImageTokenizerContract(
        backend = backend,
        bosId = 49_406,
        eosId = 49_407,
        padId = if (dualClip || padZero) 0 else 49_407,
        maxLength = 77,
        clip1PadRule = if (padZero) ImageClipPadRule.ZERO else ImageClipPadRule.EOS,
        clip2PadRule = if (dualClip) ImageClipPadRule.ZERO else null,
        supportsPromptWeighting = supportsPromptWeighting,
        separateNegativePrompt = separateNegativePrompt
    )

    private fun conditioning(
        dataType: ImageEmbeddingDiskDataType,
        conversion: ImageEmbeddingConversionStrategy,
        width: Int,
        maxLength: Int = 77,
        dualEncoder: Boolean = false,
        pooled: Boolean = false,
        separateNegativePrompt: Boolean = true
    ) = ImageConditioningContract(
        diskDataType = dataType,
        textEncoderInputShape = listOf(1, maxLength),
        textEncoderOutputShapes = if (dualEncoder) {
            listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
        } else {
            listOf(listOf(1, maxLength, width))
        },
        conversionStrategy = conversion,
        dualEncoder = dualEncoder,
        pooledOutput = pooled,
        concatenationOrder = when {
            dualEncoder -> listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
            separateNegativePrompt -> listOf("negative", "positive")
            else -> listOf("positive")
        }
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
        strategy: ImageWorkerStrategy = ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
        vaeEncoder: String? = null
    ) = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract(textEncoder),
        unet = ImageGraphArtifactContract(unet),
        vae = ImageGraphArtifactContract(vae),
        vaeEncoder = vaeEncoder?.let(::ImageGraphArtifactContract),
        schedulerSidecar = "scheduler/scheduler_config.json",
        tokenizerSidecar = "tokenizer/tokenizer_config.json",
        qnnSdk = qnnSdk,
        htpArch = htpArch,
        workerStrategy = strategy
    )

    private fun defaults(
        size: Int,
        steps: Int,
        cfg: Double,
        useCfg: Boolean,
        defaultNegativePrompt: String? = null
    ) = ImageGenerationDefaults(
        width = size,
        height = size,
        steps = steps,
        cfgScale = cfg,
        seed = 42L,
        useCfg = useCfg,
        defaultNegativePrompt = defaultNegativePrompt
    )

    private fun capabilities(
        size: Int,
        schedulers: Set<ImageSchedulerAlgorithm>,
        supportsPromptWeighting: Boolean = true,
        supportsNegativePrompt: Boolean = true
    ) = ImageGenerationCapabilities(
        supportedSchedulers = schedulers,
        minWidth = size,
        maxWidth = size,
        minHeight = size,
        maxHeight = size,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsPromptWeighting = supportsPromptWeighting
    )

    private fun stableDiffusionCapabilities(
        schedulers: Set<ImageSchedulerAlgorithm>,
        supportsNegativePrompt: Boolean = true
    ) = ImageGenerationCapabilities(
        supportedSchedulers = schedulers,
        minWidth = 256,
        maxWidth = 1_536,
        minHeight = 256,
        maxHeight = 1_536,
        widthMultiple = 64,
        heightMultiple = 64,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsPromptWeighting = false
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

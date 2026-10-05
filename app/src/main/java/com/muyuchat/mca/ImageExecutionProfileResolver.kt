package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RecommendedImageDefaults

internal object ImageExecutionProfileResolver {
    private const val QWEN_IMAGE_21_RECOMMENDATION_ID = "qwen_image_21_mnn_opencl"
    private const val QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT =
        "2525485bba44d4d8176d05522fa7e8b4b1080c9e7b7df18dfde7d58e667ff055"
    private val MIGRATABLE_PINNED_RECOMMENDATIONS = setOf(
        "cyberrealistic_sd15_qnn228",
        "realisticvisionhyper_sd15_qnn228",
        "dreamshaper_sd15_qnn228",
        "meinamix_sd15_qnn228",
        "qualcomm_sd15_gen5_qnn",
        "qualcomm_sd21_gen5_qnn",
        "qualcomm_controlnet_canny_gen5_qnn",
        "sdxl_base_qnn228",
        "realismsdxl_dmd2_alt_qnn228",
        "animagine_xl_v4_qnn228",
        "cyberrealisticxl_qnn228",
        "sd_turbo_512_experimental",
        QWEN_IMAGE_21_RECOMMENDATION_ID
    )
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
        BuiltInImageProfileTarget("qwen_image_21_q4_k_m", "sdcpp.qwen-image-2.1"),
        BuiltInImageProfileTarget("qwen_image_21_mnn_opencl", "mnn.qwen-image-2.1.opencl"),
        BuiltInImageProfileTarget("longcat_image_q4", "sdcpp.longcat-image")
    )

    private val targetByRecommendationId = builtInTargets.associateBy(BuiltInImageProfileTarget::recommendationId)
    private val catalogProfileContractsByRecommendationId: Map<String, CatalogImageProfileContract> by lazy {
        runCatching {
            val catalogModels = ModelScopeClient().recommendedModels()
                .asSequence()
                .filter { model -> model.kind == ModelScopeRecommendedKind.IMAGE }
                .filter { model -> model.id in targetByRecommendationId }
                .toList()
            require(catalogModels.size == catalogModels.map { it.id }.toSet().size) {
                "Catalog image recommendation identities must be unique."
            }
            require(catalogModels.mapTo(linkedSetOf()) { it.id } == targetByRecommendationId.keys) {
                "Catalog image recommendations and compatibility targets must stay complete."
            }
            catalogModels.associate { model ->
                val bundle = requireNotNull(model.imageEngineBundle)
                val primary = bundle.requiredComponents.first { component ->
                    component.role == ImageEngineBundleComponentRole.DIFFUSION
                }
                val fingerprint = requireNotNull(
                    primary.sha256
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { value -> value.matches(Regex("^[0-9a-f]{64}$")) }
                )
                model.id to CatalogImageProfileContract(
                    bundle = bundle,
                    aliases = listOfNotNull(
                        model.id,
                        bundle.id,
                        bundle.recommendationId
                    ).mapTo(linkedSetOf(), ::normalizeIdentityToken),
                    primaryRepositories = setOf(model.repoId, primary.repoId)
                        .mapTo(linkedSetOf(), ::normalizeRepositoryIdentity),
                    artifactMarkers = setOf(
                        model.recommendedFileName,
                        primary.fileName,
                        primary.relativePath,
                        bundle.id
                    ).asSequence()
                        .filter(::isStrongCatalogImageArtifactIdentity)
                        .mapTo(linkedSetOf(), ::normalizeIdentityToken),
                    modelFingerprint = fingerprint
                )
            }
        }.getOrDefault(emptyMap())
    }
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
            "qwen_image_21_q4_k_m",
            repositories = setOf("unsloth/Qwen-Image-2.1-GGUF"),
            artifacts = setOf("qwen-image-2.1-Q4_K_M.gguf"),
            fingerprints = setOf("631d532e7ca71e8d90a87c71d3699761a812039d22e3370e87498d87754660fe")
        ),
        identityRule(
            "qwen_image_21_mnn_opencl",
            repositories = setOf("evankuo/Qwen-Image-2.1-MNN")
        ),
        identityRule(
            "longcat_image_q4",
            repositories = setOf("vantagewithai/LongCat-Image-GGUF"),
            artifacts = setOf("LongCat-Image-Q4_0.gguf"),
            fingerprints = setOf("d494513ea95e82fb7069cdb914738f22dfc940fc770000fbbc8ad0a7a445f601")
        )
    )
    private val GENERIC_DIFFUSION_ARTIFACT_STEMS = setOf(
        "unet",
        "transformer",
        "model",
        "diffusion_model"
    )

    fun resolve(input: ImageExecutionProfileResolverInput): ImageExecutionProfileResolution {
        val fieldSources = linkedMapOf<String, ImageProfileSource>()
        val sourceChain = mutableListOf<ImageProfileSource>()
        val fingerprint = input.modelFingerprint.trim().lowercase()
        val manifestProfile = migratePinnedCatalogManifestProfile(input, fingerprint)
        val packageFamily = if (manifestProfile == null) {
            input.manifestBehavior?.family ?: input.sidecar?.behavior?.family
        } else {
            manifestProfile.family
        }
        val fallbackFamily = packageFamily ?: input.capabilityDiscovery?.family ?: input.family

        val base: ImageExecutionProfile = if (manifestProfile != null) {
            sourceChain += ImageProfileSource.MANIFEST
            imageExecutionProfileMarkAllFields(fieldSources, ImageProfileSource.MANIFEST)
            manifestProfile
        } else {
            val builtInTarget = resolveBuiltInTarget(input)
            when {
                builtInTarget != null -> {
                    sourceChain += ImageProfileSource.BUILT_IN
                    imageExecutionProfileMarkAllFields(fieldSources, ImageProfileSource.BUILT_IN)
                    builtInProfile(
                        target = builtInTarget,
                        modelFingerprint = fingerprint,
                        recommendationRevision = input.recommendationRevision
                    )
                }
                input.capabilityDiscovery != null -> {
                    sourceChain += ImageProfileSource.CAPABILITY_DISCOVERY
                    imageExecutionProfileMarkAllFields(fieldSources, ImageProfileSource.CAPABILITY_DISCOVERY)
                    imageExecutionProfileApplyCapabilityDiscovery(
                        genericProfile(input.runtime, fallbackFamily, fingerprint),
                        input.capabilityDiscovery
                    )
                }
                else -> {
                    sourceChain += ImageProfileSource.GENERIC_FALLBACK
                    imageExecutionProfileMarkAllFields(fieldSources, ImageProfileSource.GENERIC_FALLBACK)
                    genericProfile(input.runtime, fallbackFamily, fingerprint)
                }
            }
        }

        // An explicit manifest profile is already a signed binding claim and
        // must retain its declared fingerprint so mismatch validation can
        // reject stale or transplanted metadata. Resolver-created templates
        // are materialized against the current model bytes here.
        var resolvedProfile = if (manifestProfile != null) {
            base
        } else {
            base.copy(modelFingerprint = fingerprint)
        }
        if (manifestProfile == null && input.sidecar != null) {
            sourceChain.add(0, ImageProfileSource.SIDECAR)
            resolvedProfile = imageExecutionProfileApplySidecar(resolvedProfile, input.sidecar, fieldSources)
        }
        if (manifestProfile == null && input.manifestBehavior != null) {
            sourceChain.add(0, ImageProfileSource.MANIFEST)
            resolvedProfile = imageExecutionProfileApplyPackageBehavior(
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
        // Reconcile any signed/sidecar capability claim with the graph topology before user
        // dimensions are applied. User overrides select a canvas; they cannot manufacture a VAE
        // encoder or change the fixed native tile contract.
        resolvedProfile = resolvedProfile.withTopologyDerivedQnnUltraFixCapability()
        resolvedProfile = imageExecutionProfileApplyUserOverrides(resolvedProfile, input.userOverrides, fieldSources)
        if (imageExecutionProfileHasUserOverrides(input.userOverrides)) sourceChain.add(0, ImageProfileSource.USER_OVERRIDE)
        resolvedProfile = resolvedProfile.withTopologyDerivedTextualInversionCapability()
        resolvedProfile = resolvedProfile.withEvidenceBoundNativeChineseTextEncoder()
        resolvedProfile = resolvedProfile.copy(
            provenance = ImageProfileProvenance(
                primarySource = sourceChain.first(),
                sources = sourceChain.distinct(),
                recommendationId = resolvedProfile.provenance.recommendationId,
                recommendationRevision = resolvedProfile.provenance.recommendationRevision,
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
        val resolved = imageExecutionProfileResolvedExecution(resolvedProfile)
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
        val fingerprintMatches = if (fingerprint.isBlank()) {
            emptyList()
        } else {
            identityRules.filter { rule -> fingerprint in rule.effectiveModelFingerprints() }
        }
        if (fingerprintMatches.size == 1) {
            return targetByRecommendationId.getValue(fingerprintMatches.single().recommendationId)
        }
        if (fingerprintMatches.isNotEmpty()) return null

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
            rule.effectivePrimaryRepositories().any(sourceRepositories::contains)
        }
        val artifactMatches = identityRules.filter { rule ->
            rule.effectiveArtifactMarkers().any(artifactTokens::contains)
        }
        val exactEvidenceMatches = when {
            repositoryMatches.isNotEmpty() && artifactMatches.isNotEmpty() ->
                repositoryMatches.filter(artifactMatches::contains)
            repositoryMatches.isNotEmpty() -> repositoryMatches
            else -> artifactMatches
        }
        if (exactEvidenceMatches.size == 1) {
            return targetByRecommendationId.getValue(exactEvidenceMatches.single().recommendationId)
        }
        // Known-but-conflicting package evidence is not an invitation to trust
        // a stale card id. Preserve the generic compatible path instead.
        if (repositoryMatches.isNotEmpty() || artifactMatches.isNotEmpty()) return null

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
        identityRules.singleOrNull { rule -> rule.effectiveAliases().any(aliasTokens::contains) }
            ?.let { return targetByRecommendationId.getValue(it.recommendationId) }
        return null
    }

    private fun BuiltInImageProfileIdentityRule.catalogContract(): CatalogImageProfileContract? =
        catalogProfileContractsByRecommendationId[recommendationId]

    private fun BuiltInImageProfileIdentityRule.effectiveAliases(): Set<String> =
        aliases + catalogContract()?.aliases.orEmpty()

    private fun BuiltInImageProfileIdentityRule.effectivePrimaryRepositories(): Set<String> =
        primaryRepositories + catalogContract()?.primaryRepositories.orEmpty()

    private fun BuiltInImageProfileIdentityRule.effectiveArtifactMarkers(): Set<String> =
        artifactMarkers + catalogContract()?.artifactMarkers.orEmpty()

    private fun BuiltInImageProfileIdentityRule.effectiveModelFingerprints(): Set<String> =
        modelFingerprints + listOfNotNull(catalogContract()?.modelFingerprint)

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

    private fun isStrongCatalogImageArtifactIdentity(value: String): Boolean {
        val leaf = value.trim().replace('\\', '/').substringAfterLast('/')
        val stem = leaf.substringBeforeLast('.', leaf)
        return normalizeIdentityToken(stem) !in GENERIC_DIFFUSION_ARTIFACT_STEMS
    }

    private fun builtInProfile(
        target: BuiltInImageProfileTarget,
        modelFingerprint: String,
        recommendationRevision: String?
    ): ImageExecutionProfile {
        // New and repaired installs materialize the catalog-owned contract. The
        // handwritten template remains only as a safe compatibility path when
        // the catalog cannot be loaded by an old package resolver.
        val profile = catalogProfileContractsByRecommendationId[target.recommendationId]
            ?.bundle
            ?.let { bundle ->
                runCatching {
                    materializeDownloadedImageExecutionProfile(bundle, modelFingerprint)
                }.getOrNull()
            }
            ?: requireNotNull(
                legacyBuiltInProfileForCompatibility(target.recommendationId, modelFingerprint)
            )
        return profile.copy(
            provenance = ImageProfileProvenance(
                primarySource = ImageProfileSource.BUILT_IN,
                sources = listOf(ImageProfileSource.BUILT_IN),
                recommendationId = target.recommendationId,
                recommendationRevision = recommendationRevision
            )
        )
    }

    /**
     * Direct Chinese is a generic evidence-bound runtime capability, never a catalog, model ID,
     * chipset, or device admission rule. Keep a declaration only when its consumed graph,
     * tokenizer assets, hashes, sizes, and native topology form one self-consistent contract.
     *
     * Actual bytes are re-hashed at the request boundary and native must then load and execute
     * that exact encoder before an image can succeed. An incomplete declaration merely remains
     * English-dominant; it must not prevent the package from running its normal English path.
     */
    private fun ImageExecutionProfile.withEvidenceBoundNativeChineseTextEncoder(): ImageExecutionProfile {
        val declared = textEncoderLanguage ?: return this
        if (declared.capability != ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL) {
            return this
        }
        return if (hasVerifiedNativeSimplifiedChineseTextEncoder()) {
            this
        } else {
            copy(textEncoderLanguage = null)
        }
    }

    /**
     * Revision migration is deliberately narrower than normal recommendation
     * resolution. It only repairs an older persisted contract for its exact
     * pinned catalog bundle (archives require their pinned `sourcePath!/member`
     * proof; Qwen direct-file bundles have a separate exact-file proof). Aliases,
     * device hints and profile names alone can never select a migration target.
     */
    private fun migratePinnedCatalogManifestProfile(
        input: ImageExecutionProfileResolverInput,
        fingerprint: String
    ): ImageExecutionProfile? {
        val persisted = input.manifestProfile ?: return null
        val persistedFingerprint = persisted.modelFingerprint.trim().lowercase()
        if (persistedFingerprint != fingerprint) {
            // A known Qwen bundle revision wrote the VAE decoder SHA into the profile identity
            // even though the registered model row points at the DIFFUSION primary. Permit only
            // that observed legacy value, and only after the Qwen-specific check below proves the
            // current input is the exact pinned bundle. A complete bundle fingerprint is also
            // allowed to reach that check; it must never be treated as a primary-file SHA.
            val qwenCandidate = resolveBuiltInTarget(input)
                ?.recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID
            val completeQwenCandidate = qwenCandidate &&
                input.modelFingerprintScope == ImageModelFingerprintScope.COMPLETE_BUNDLE
            if (!qwenCandidate ||
                (!completeQwenCandidate &&
                    persistedFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT)
            ) {
                return persisted
            }
        }
        val target = resolveBuiltInTarget(input)
            ?.takeIf { it.recommendationId in MIGRATABLE_PINNED_RECOMMENDATIONS }
            ?: return persisted
        val recommendationId = target.recommendationId
        val contract = catalogProfileContractsByRecommendationId[recommendationId]
            ?: return persisted
        if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
            if (!qwenImage21MigrationMetadataAgrees(input, persisted, contract)) return persisted
        }
        if (!manifestMigrationIdentityAgrees(input, persisted, recommendationId)) return persisted
        if (!manifestMigrationSourceEvidenceAgrees(input, recommendationId)) return persisted

        val catalogProfile = materializeDownloadedImageExecutionProfile(
            bundle = contract.bundle,
            modelFingerprint = fingerprint
        ) ?: return persisted
        if (
            persisted.profileId != catalogProfile.profileId ||
            (if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
                persisted.profileRevision != 1
            } else {
                persisted.profileRevision <= 0 ||
                    persisted.profileRevision >= catalogProfile.profileRevision
            }) ||
            persisted.runtime != catalogProfile.runtime ||
            persisted.family != catalogProfile.family ||
            persisted.task != catalogProfile.task
        ) {
            return persisted
        }
        return catalogProfile.copy(
            provenance = catalogProfile.provenance.copy(
                primarySource = ImageProfileSource.MANIFEST,
                sources = listOf(ImageProfileSource.MANIFEST),
                recommendationId = recommendationId,
                recommendationRevision = persisted.provenance.recommendationRevision
                    ?: input.recommendationRevision
                    ?: contract.bundle.requiredComponents
                        .firstOrNull { component -> component.role == ImageEngineBundleComponentRole.DIFFUSION }
                        ?.revision,
                notes = persisted.provenance.notes
            )
        )
    }

    /**
     * Qwen's package is a pinned set of direct Hugging Face files, not an archive. Older manifests
     * do not persist the source revision in the top-level metadata, so the primary file's exact
     * catalog SHA-256, repository, direct source path and the catalog's immutable revision jointly
     * bind it. If either old provenance or the current request does declare a revision, require it
     * to match that same pinned revision.
     */
    private fun qwenImage21MigrationMetadataAgrees(
        input: ImageExecutionProfileResolverInput,
        persisted: ImageExecutionProfile,
        contract: CatalogImageProfileContract
    ): Boolean {
        val bundle = contract.bundle
        val primary = bundle.requiredComponents.singleOrNull { component ->
            component.role == ImageEngineBundleComponentRole.DIFFUSION &&
                component.fileName.equals("dit.mnn", ignoreCase = true)
        } ?: return false
        val pinnedRevision = primary.revision.trim().takeIf(String::isNotEmpty) ?: return false
        val pinnedRepository = normalizeRepositoryIdentity(primary.repoId)
        val pinnedFingerprint = primary.sha256?.trim()?.lowercase() ?: return false
        val pinnedLegacyVaeFingerprint = bundle.requiredComponents
            .singleOrNull { component ->
                component.role == ImageEngineBundleComponentRole.VAE &&
                    component.fileName.equals("vae_decoder.mnn", ignoreCase = true)
            }
            ?.sha256
            ?.trim()
            ?.lowercase()
        val inputFingerprint = input.modelFingerprint.trim().lowercase()
        val persistedFingerprint = persisted.modelFingerprint.trim().lowercase()
        val readinessUsesKnownLegacyIdentity =
            inputFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT &&
                persistedFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT &&
                pinnedLegacyVaeFingerprint == QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT
        val inputUsesCompleteBundleIdentity =
            input.modelFingerprintScope == ImageModelFingerprintScope.COMPLETE_BUNDLE
        val completeBundleEvidenceAgrees = inputUsesCompleteBundleIdentity &&
            qwenImage21CompleteBundleEvidenceAgrees(
                evidence = input.bundleComponentEvidence,
                bundle = bundle
            )
        val inputFingerprintAgrees = if (inputUsesCompleteBundleIdentity) {
            // The complete digest is computed from the observed files by the integration layer.
            // The resolver only accepts it when the same observation proves every pinned required
            // component, so a catalog primary SHA can never be mistaken for the bundle digest.
            completeBundleEvidenceAgrees && inputFingerprint.matches(Regex("^[0-9a-f]{64}$"))
        } else {
            inputFingerprint == pinnedFingerprint || readinessUsesKnownLegacyIdentity
        }
        if (
            bundle.recommendationId != QWEN_IMAGE_21_RECOMMENDATION_ID ||
            bundle.requiredComponents.isEmpty() ||
            bundle.requiredComponents.any { component ->
                component.provider != ModelRepositoryProvider.HUGGING_FACE ||
                    normalizeRepositoryIdentity(component.repoId) != pinnedRepository ||
                    component.revision.trim() != pinnedRevision ||
                    component.fileName.isBlank() ||
                    component.fileName.endsWith(".zip", ignoreCase = true) ||
                    component.fileName.contains("!/")
            } ||
            contract.modelFingerprint != pinnedFingerprint ||
            !inputFingerprintAgrees ||
            (persistedFingerprint != pinnedFingerprint &&
                (persistedFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT ||
                    pinnedLegacyVaeFingerprint != QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT)) ||
            input.recommendationId?.trim() != QWEN_IMAGE_21_RECOMMENDATION_ID ||
            persisted.provenance.recommendationId?.trim() != QWEN_IMAGE_21_RECOMMENDATION_ID ||
            input.recommendationRevision?.trim()?.takeIf(String::isNotEmpty)
                ?.let { it != pinnedRevision } == true ||
            persisted.provenance.recommendationRevision?.trim()?.takeIf(String::isNotEmpty)
                ?.let { it != pinnedRevision } == true
        ) {
            return false
        }
        return qwenImage21DirectFileSourceEvidenceAgrees(input, contract, pinnedRepository)
    }

    /**
     * Validates the actual complete bundle observation against the immutable catalog closure.
     * Extra files are allowed because optional edit assets may be installed, but every required
     * path must occur exactly once with the catalog role, size, and SHA-256. This is intentionally
     * Qwen-specific: no generic complete-bundle shortcut may weaken other model migrations.
     */
    private fun qwenImage21CompleteBundleEvidenceAgrees(
        evidence: List<ImageBundleComponentEvidence>,
        bundle: ImageEngineBundleSpec
    ): Boolean {
        if (evidence.isEmpty()) return false
        val observedByPath = evidence.groupBy { normalizeBundleComponentPath(it.relativePath) }
        if (observedByPath.keys.any(String::isBlank)) return false
        return bundle.requiredComponents.all { expected ->
            val expectedPath = normalizeBundleComponentPath(expected.relativePath)
            val candidates = observedByPath[expectedPath].orEmpty()
            candidates.size == 1 && candidates.single().let { actual ->
                actual.role.trim().uppercase() == expected.role.name &&
                    actual.sha256.trim().equals(expected.sha256?.trim(), ignoreCase = true) &&
                    (expected.expectedSizeBytes == null || actual.sizeBytes == expected.expectedSizeBytes)
            }
        }
    }

    private fun normalizeBundleComponentPath(value: String): String = value
        .trim()
        .replace('\\', '/')
        .trimStart('/')
        .lowercase()

    private fun qwenImage21DirectFileSourceEvidenceAgrees(
        input: ImageExecutionProfileResolverInput,
        contract: CatalogImageProfileContract,
        pinnedRepository: String = normalizeRepositoryIdentity(
            contract.bundle.requiredComponents.firstOrNull { component ->
                component.role == ImageEngineBundleComponentRole.DIFFUSION &&
                    component.fileName.equals("dit.mnn", ignoreCase = true)
            }?.repoId.orEmpty()
        )
    ): Boolean {
        val requiredComponents = contract.bundle.requiredComponents
        val pinnedPaths = requiredComponents.map { component ->
            normalizeDirectSourcePath(component.fileName)
        }.toSet()
        if (pinnedPaths.isEmpty() || pinnedPaths.any(String::isBlank)) return false
        val evidenceRepositories = input.recommendationEvidence.sourceRepositories
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeRepositoryIdentity)
            .filter { repository -> '/' in repository }
            .toSet()
        val directPaths = input.recommendationEvidence.artifactPaths
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeDirectSourcePath)
            .toSet()
        return evidenceRepositories == setOf(pinnedRepository) &&
            directPaths.none { path -> "!/" in path || path.endsWith(".zip") } &&
            pinnedPaths.all(directPaths::contains)
    }

    private fun normalizeDirectSourcePath(value: String): String = value
        .trim()
        .replace('\\', '/')
        .trimStart('/')
        .lowercase()

    private fun manifestMigrationIdentityAgrees(
        input: ImageExecutionProfileResolverInput,
        persisted: ImageExecutionProfile,
        recommendationId: String
    ): Boolean {
        val targetRule = identityRules.single { rule -> rule.recommendationId == recommendationId }
        val persistedIdentity = persisted.provenance.recommendationId
            ?.takeIf(String::isNotBlank)
            ?: return false
        if (persistedIdentity.trim() != recommendationId) return false
        input.recommendationId
            ?.takeIf(String::isNotBlank)
            ?.let { requestedIdentity ->
                if (requestedIdentity.trim() != recommendationId) return false
            }

        val conflictingAlias = input.recommendationEvidence.aliases
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeIdentityToken)
            .any { alias ->
                identityRules.any { rule ->
                    rule.recommendationId != recommendationId && alias in rule.effectiveAliases()
                }
            }
        if (conflictingAlias) return false
        val conflictingRepository = input.recommendationEvidence.sourceRepositories
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeRepositoryIdentity)
            .any { repository ->
                repository !in targetRule.effectivePrimaryRepositories() &&
                    identityRules.any { rule ->
                        rule.recommendationId != recommendationId &&
                            repository in rule.effectivePrimaryRepositories()
                    }
            }
        if (conflictingRepository) return false
        val conflictingArtifact = input.recommendationEvidence.artifactPaths
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { path -> artifactIdentityTokens(path).asSequence() }
            .any { artifact ->
                identityRules.any { rule ->
                    rule.recommendationId != recommendationId &&
                        artifact in rule.effectiveArtifactMarkers()
                }
            }
        return !conflictingArtifact
    }

    /**
     * Installed archive profiles fingerprint the extracted diffusion graph, not the deleted ZIP.
     * Bind migration to both immutable publisher identity fields retained in the generated manifest
     * so a hand-written recommendation id alone cannot opt an unrelated package into this repair.
     */
    private fun manifestMigrationSourceEvidenceAgrees(
        input: ImageExecutionProfileResolverInput,
        recommendationId: String
    ): Boolean {
        val contract = catalogProfileContractsByRecommendationId[recommendationId]
            ?: return false
        if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) {
            // Direct-file Qwen bundle evidence is checked separately. Keep the existing strict
            // `archive!/member` rule unchanged for archive-backed recommendations.
            return qwenImage21DirectFileSourceEvidenceAgrees(input, contract)
        }
        val primaryComponents = contract.bundle.requiredComponents
            .filter { component -> component.role == ImageEngineBundleComponentRole.DIFFUSION }
        val pinnedRepositories = primaryComponents
            .asSequence()
            .map { component -> normalizeRepositoryIdentity(component.repoId) }
            .filter(String::isNotBlank)
            .toSet()
        val pinnedArchiveSourcePaths = primaryComponents
            .asSequence()
            .map { component -> normalizePinnedArchiveSourcePath(component.fileName) }
            .filter(String::isNotBlank)
            .toSet()
        if (pinnedRepositories.isEmpty() || pinnedArchiveSourcePaths.isEmpty()) return false
        val repositoryEvidence = input.recommendationEvidence.sourceRepositories
            .asSequence()
            .filter(String::isNotBlank)
            .map(::normalizeRepositoryIdentity)
            // Local records also carry provider labels such as "local". Only owner/repository
            // identities are publisher evidence for this migration boundary.
            .filter { repository -> '/' in repository }
            .toSet()
        val archiveSourceEvidence = input.recommendationEvidence.artifactPaths
            .asSequence()
            .filter(String::isNotBlank)
            .mapNotNull(::archiveSourcePathOrNull)
            .toSet()
        return repositoryEvidence.isNotEmpty() &&
            repositoryEvidence.all(pinnedRepositories::contains) &&
            archiveSourceEvidence.isNotEmpty() &&
            archiveSourceEvidence.all(pinnedArchiveSourcePaths::contains)
    }

    private fun archiveSourcePathOrNull(value: String): String? {
        val normalized = value.trim().replace('\\', '/')
        val archiveDelimiter = normalized.indexOf("!/")
        if (archiveDelimiter <= 0) return null
        return normalizePinnedArchiveSourcePath(
            normalized
            .substring(0, archiveDelimiter)
        )
    }

    private fun normalizePinnedArchiveSourcePath(value: String): String = value
        .trim()
        .replace('\\', '/')
        .trim('/')
        .lowercase()

    /**
     * Retained only for packages installed before catalog profiles were persisted. Such profiles
     * have no immutable multilingual semantic evidence and therefore remain English-dominant.
     */
    internal fun legacyBuiltInProfileForCompatibility(
        recommendationId: String,
        modelFingerprint: String
    ): ImageExecutionProfile? = targetByRecommendationId[recommendationId]
        ?.let { target -> profileTemplate(target, modelFingerprint.trim().lowercase()) }

}

internal fun isQwenImage21LegacyVaeProfileFingerprint(value: String): Boolean =
    value.trim().equals(
        "2525485bba44d4d8176d05522fa7e8b4b1080c9e7b7df18dfde7d58e667ff055",
        ignoreCase = true
    )

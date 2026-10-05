package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.RecommendedImageDefaults

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
        BuiltInImageProfileTarget("qwen_image_21_q4_k_m", "sdcpp.qwen-image-2.1"),
        BuiltInImageProfileTarget("qwen_image_21_mnn_opencl", "mnn.qwen-image-2.1.opencl"),
        BuiltInImageProfileTarget("longcat_image_q4", "sdcpp.longcat-image")
    )

    private val targetByRecommendationId = builtInTargets.associateBy(BuiltInImageProfileTarget::recommendationId)
    // P5/S44: read by the manifest-migration extraction (read-only data map).
    internal val catalogProfileContractsByRecommendationId: Map<String, CatalogImageProfileContract> by lazy {
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

    // P5/S44: called by the manifest-migration extraction (read-only classifier).
    internal fun resolveBuiltInTarget(
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

    // P5/S45: the moved identity-rule table calls this read-only factory (pure constructor).
    internal fun identityRule(
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

    // P5/S44: called by the manifest-migration extraction (pure normalizer).
    internal fun normalizeRepositoryIdentity(value: String): String {
        val normalized = value.trim().lowercase().replace('\\', '/').trim('/')
        val withoutProvider = listOf("hugging_face:", "modelscope:")
            .firstOrNull(normalized::startsWith)
            ?.let { prefix -> normalized.removePrefix(prefix) }
            ?: normalized
        return withoutProvider.removeSuffix(".git").trim('/')
    }

    // P5/S44: called by the manifest-migration extraction (pure tokenizer).
    internal fun artifactIdentityTokens(value: String): Set<String> = buildSet {
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

    // P5/S44: called by the manifest-migration extraction (pure normalizer).
    internal fun normalizeIdentityToken(value: String): String = value
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

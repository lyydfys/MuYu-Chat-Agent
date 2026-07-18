package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.RecommendedImageDefaults
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExecutionProfileResolverTest {
    private val pinnedQnnSd15Ids = linkedSetOf(
        "cyberrealistic_sd15_qnn228",
        "realisticvisionhyper_sd15_qnn228",
        "dreamshaper_sd15_qnn228",
        "meinamix_sd15_qnn228"
    )

    @Test
    fun `all eighteen recommendation ids resolve to their target profiles`() {
        val expected = linkedMapOf(
            "cyberrealistic_sd15_qnn228" to "community.sd15.qnn228",
            "realisticvisionhyper_sd15_qnn228" to "community.sd15.hyper.qnn228",
            "dreamshaper_sd15_qnn228" to "community.sd15.qnn228",
            "meinamix_sd15_qnn228" to "community.sd15.legacy-fp32.qnn228",
            "sdxl_base_qnn228" to "community.sdxl.base.qnn228",
            "realismsdxl_dmd2_alt_qnn228" to "community.sdxl.dmd2-alt.qnn228",
            "animagine_xl_v4_qnn228" to "community.sdxl.base.qnn228",
            "cyberrealisticxl_qnn228" to "community.sdxl.base.qnn228",
            "qualcomm_sd15_gen5_qnn" to "qualcomm.sd15.gen5.qnn245",
            "qualcomm_sd21_gen5_qnn" to "qualcomm.sd21.gen5.qnn245",
            "qualcomm_controlnet_canny_gen5_qnn" to "qualcomm.controlnet-canny.gen5.qnn245",
            "sd15_mnn_512_quality" to "mnn.sd15.official.512",
            "mnn_sana_edit_v2" to "mnn.sana-edit.v2",
            "sd_turbo_512_experimental" to "sdcpp.sd-turbo",
            "z_image_turbo_q4" to "sdcpp.z-image-turbo",
            "flux2_klein_4b_q4" to "sdcpp.flux2-klein",
            "qwen_image_2512_q2" to "sdcpp.qwen-image",
            "longcat_image_q4" to "sdcpp.longcat-image"
        )

        assertEquals(expected.keys, ImageExecutionProfileResolver.builtInTargets.map { it.recommendationId }.toSet())
        expected.forEach { (recommendationId, profileId) ->
            val resolution = resolve(recommendationId = recommendationId)
            assertEquals(profileId, resolution.profile.profileId)
            assertEquals(FINGERPRINT, resolution.profile.modelFingerprint)
            assertEquals(ImageProfileSource.BUILT_IN, resolution.profile.provenance.primarySource)
            assertTrue(resolution.validation.valid)
            assertFalse(resolution.deviceAdmissionRestricted)
        }
    }

    @Test
    fun `all catalog profiles match both runtime resolution and legacy compatibility templates`() {
        val models = ModelScopeClient().recommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.IMAGE }

        assertEquals(18, models.size)
        models.forEach { model ->
            val catalogProfile = requireNotNull(
                materializeDownloadedImageExecutionProfile(
                    bundle = requireNotNull(model.imageEngineBundle),
                    modelFingerprint = FINGERPRINT
                )
            )
            val fallbackProfile = resolve(model.id).profile
            val legacyProfile = requireNotNull(
                ImageExecutionProfileResolver.legacyBuiltInProfileForCompatibility(
                    recommendationId = model.id,
                    modelFingerprint = FINGERPRINT
                )
            )

            assertEquals(
                "${model.id} runtime resolution drifted from its catalog profile",
                catalogProfile.copy(provenance = fallbackProfile.provenance),
                fallbackProfile
            )
            assertEquals(
                "${model.id} legacy compatibility template drifted from its catalog profile",
                catalogProfile.copy(provenance = legacyProfile.provenance),
                legacyProfile
            )
        }
    }

    @Test
    fun `all catalog primary fingerprints recover their exact profile despite a stale card id`() {
        val models = ModelScopeClient().recommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.IMAGE }

        assertEquals(18, models.size)
        models.forEach { model ->
            val bundle = requireNotNull(model.imageEngineBundle)
            val primary = bundle.requiredComponents.first {
                it.role == ImageEngineBundleComponentRole.DIFFUSION
            }
            val staleId = models.first { it.id != model.id }.id
            val resolution = ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = staleId,
                    fingerprint = requireNotNull(primary.sha256),
                    family = LocalImageModelFamily.CUSTOM,
                    recommendationEvidence = ImageRecommendationEvidence(
                        sourceRepositories = listOf(model.repoId),
                        artifactPaths = listOf(model.recommendedFileName)
                    )
                )
            )

            assertEquals(model.id, resolution.profile.provenance.recommendationId)
            assertEquals(bundle.executionProfile!!.profileId, resolution.profile.profileId)
        }
    }

    @Test
    fun `historical alias source repository and bundle artifacts recover exact profiles`() {
        val historicalAlias = ImageExecutionProfileResolver.resolve(
            input(recommendationId = "cyberrealistic-sd15-qnn228-8gen2")
        )
        assertEquals("community.sd15.qnn228", historicalAlias.profile.profileId)
        assertEquals(
            "cyberrealistic_sd15_qnn228",
            historicalAlias.profile.provenance.recommendationId
        )

        val sourceOnly = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                recommendationEvidence = ImageRecommendationEvidence(
                    sourceRepositories = listOf(
                        "hugging_face:Mr-J-369/RealisticVisionHyper-SD1.5-qnn2.28"
                    )
                )
            )
        )
        assertEquals("community.sd15.hyper.qnn228", sourceOnly.profile.profileId)

        val bundlePathOnly = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                recommendationEvidence = ImageRecommendationEvidence(
                    artifactPaths = listOf(
                        "/image_models/bundle-dreamshaper_sd15_qnn228/DreamShaperV8-qnn2.28-min.zip"
                    )
                )
            )
        )
        assertEquals("community.sd15.qnn228", bundlePathOnly.profile.profileId)
    }

    @Test
    fun `exact fingerprint outranks conflicting request and source while ambiguous repository needs artifact evidence`() {
        val conflictingRequest = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "cyberrealistic_sd15_qnn228",
                fingerprint = "3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549"
            )
        )
        assertEquals("sdcpp.sd-turbo", conflictingRequest.profile.profileId)
        assertEquals(
            "sd_turbo_512_experimental",
            conflictingRequest.profile.provenance.recommendationId
        )

        val fingerprintMatch = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                fingerprint = "3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM,
                recommendationEvidence = ImageRecommendationEvidence(
                    sourceRepositories = listOf("Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28")
                )
            )
        )
        assertEquals("sdcpp.sd-turbo", fingerprintMatch.profile.profileId)

        val ambiguousSource = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                family = LocalImageModelFamily.SDXL,
                recommendationEvidence = ImageRecommendationEvidence(
                    sourceRepositories = listOf("hugging_face:xororz/sdxl-qnn")
                )
            )
        )
        assertEquals(ImageProfileSource.GENERIC_FALLBACK, ambiguousSource.profile.provenance.primarySource)

        val disambiguated = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                family = LocalImageModelFamily.SDXL,
                recommendationEvidence = ImageRecommendationEvidence(
                    sourceRepositories = listOf("hugging_face:xororz/sdxl-qnn"),
                    artifactPaths = listOf("cyber_realistic_v10_qnn2.28_8gen3.zip")
                )
            )
        )
        assertEquals("community.sdxl.base.qnn228", disambiguated.profile.profileId)
        assertEquals("cyberrealisticxl_qnn228", disambiguated.profile.provenance.recommendationId)
    }

    @Test
    fun `exact repository and artifact evidence outrank stale aliases while conflicts use generic fallback`() {
        val models = ModelScopeClient().recommendedModels()
            .filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        listOf(
            "qualcomm_sd21_gen5_qnn",
            "qualcomm_controlnet_canny_gen5_qnn"
        ).forEach { actualId ->
            val actual = models.single { it.id == actualId }
            val resolved = ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = "qualcomm_sd15_gen5_qnn",
                    fingerprint = OTHER_FINGERPRINT,
                    family = LocalImageModelFamily.CUSTOM,
                    recommendationEvidence = ImageRecommendationEvidence(
                        sourceRepositories = listOf(actual.repoId),
                        artifactPaths = listOf(actual.recommendedFileName)
                    )
                )
            )

            assertEquals(actualId, resolved.profile.provenance.recommendationId)
            assertEquals(actual.imageEngineBundle!!.executionProfile!!.profileId, resolved.profile.profileId)
        }

        val sd21 = models.single { it.id == "qualcomm_sd21_gen5_qnn" }
        val control = models.single { it.id == "qualcomm_controlnet_canny_gen5_qnn" }
        val conflict = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "qualcomm_sd15_gen5_qnn",
                fingerprint = OTHER_FINGERPRINT,
                family = LocalImageModelFamily.SD15,
                recommendationEvidence = ImageRecommendationEvidence(
                    sourceRepositories = listOf(sd21.repoId),
                    artifactPaths = listOf(control.recommendedFileName)
                )
            )
        )

        assertEquals(ImageProfileSource.GENERIC_FALLBACK, conflict.profile.provenance.primarySource)
        assertNull(conflict.profile.provenance.recommendationId)
        assertNull(conflict.profile.provenance.recommendationRevision)
    }

    @Test
    fun `local model source restores recommendation profile even when imported family is custom`() {
        val root = Files.createTempDirectory("image-profile-source-identity").toFile()
        try {
            val model = LocalImageModelRecord(
                displayName = "Stable Diffusion Turbo",
                path = root.resolve("sd_turbo.safetensors").absolutePath,
                fileName = "sd_turbo.safetensors",
                sizeBytes = 1L,
                sha256 = FINGERPRINT,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM,
                source = "modelscope:AI-ModelScope/sd-turbo",
                bundleRoot = root.absolutePath
            )
            val resolution = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            )

            assertEquals("sdcpp.sd-turbo", resolution.profile.profileId)
            assertEquals(LocalImageModelFamily.SD_TURBO, resolution.profile.family)
            assertEquals(ImageProfileSource.BUILT_IN, resolution.profile.provenance.primarySource)
            assertFalse(resolution.deviceAdmissionRestricted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pinned image packages retain their actual conditioning storage contracts`() {
        val cyber = resolve("cyberrealistic_sd15_qnn228").profile
        assertEquals(ImageEmbeddingDiskDataType.FP16, cyber.conditioning.diskDataType)
        assertEquals(ImageEmbeddingConversionStrategy.NONE, cyber.conditioning.conversionStrategy)

        listOf(
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        ).forEach { id ->
            val profile = resolve(id).profile
            assertEquals(ImageEmbeddingDiskDataType.FP32, profile.conditioning.diskDataType)
            assertEquals(
                ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
                profile.conditioning.conversionStrategy
            )
        }
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        ).forEach { id ->
            val profile = resolve(id).profile
            assertEquals(2, profile.profileRevision)
            assertEquals("clip_v2.mnn", profile.graph.textEncoder?.relativePath)
            assertEquals(ImageWorkerStrategy.SHARED_UNET_VAE, profile.graph.workerStrategy)
            assertNull(profile.graph.schedulerSidecar)
            assertNull(profile.graph.tokenizerSidecar)
            assertEquals(
                listOf("tokenizer.json", "token_emb.bin", "pos_emb.bin"),
                profile.graph.configSidecars
            )
        }

        listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        ).forEach { id ->
            val profile = resolve(id).profile
            assertEquals(ImageEmbeddingDiskDataType.FP16, profile.conditioning.diskDataType)
            assertTrue(profile.conditioning.dualEncoder)
            assertEquals(ImageClipPadRule.EOS, profile.tokenizer.clip1PadRule)
            assertEquals(ImageClipPadRule.ZERO, profile.tokenizer.clip2PadRule)
        }

        val gen5 = resolve("qualcomm_sd15_gen5_qnn").profile
        assertEquals(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, gen5.conditioning.diskDataType)
        assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, gen5.tokenizer.backend)

        val mnn = resolve("sd15_mnn_512_quality").profile
        assertEquals(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, mnn.conditioning.diskDataType)
        assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, mnn.tokenizer.backend)

        val sana = resolve("mnn_sana_edit_v2").profile
        assertEquals(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, sana.conditioning.diskDataType)
        assertEquals(ImageTokenizerBackend.MNN_MTOK, sana.tokenizer.backend)
        assertEquals(LocalImageRuntime.MNN_DIFFUSION, sana.runtime)
    }

    @Test
    fun `community sd15 qnn and mnn profiles pin leading dpmpp 2m`() {
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "meinamix_sd15_qnn228",
            "sd15_mnn_512_quality"
        ).forEach { id ->
            val scheduler = resolve(id).profile.scheduler
            assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, scheduler.algorithm)
            assertEquals(ImageTimestepSpacing.LEADING, scheduler.timestepSpacing)
            assertEquals(2, scheduler.order)
        }
    }

    @Test
    fun `community sdxl pins trailing without changing dmd2 or gen5 schedulers`() {
        val sdxl = resolve("sdxl_base_qnn228").profile.scheduler
        assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, sdxl.algorithm)
        assertEquals(ImageTimestepSpacing.TRAILING, sdxl.timestepSpacing)
        assertEquals(2, sdxl.order)
        assertNull(resolve("sdxl_base_qnn228").profile.graph.htpArch)

        val dmd2 = resolve("realismsdxl_dmd2_alt_qnn228").profile.scheduler
        assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, dmd2.algorithm)
        assertEquals(ImageTimestepSpacing.LINSPACE, dmd2.timestepSpacing)
        assertEquals(2, dmd2.order)

        val gen5 = resolve("qualcomm_sd15_gen5_qnn").profile.scheduler
        assertEquals(ImageSchedulerAlgorithm.EULER, gen5.algorithm)
        assertEquals(ImageTimestepSpacing.LINSPACE, gen5.timestepSpacing)
        assertEquals(1, gen5.stepsOffset)
        assertTrue(gen5.skipPrkSteps)
        assertTrue(gen5.scaleModelInput)
        assertEquals(1, gen5.minSteps)
        assertEquals(100, gen5.maxSteps)

        val gen5Sd21 = resolve("qualcomm_sd21_gen5_qnn").profile.scheduler
        assertEquals(ImageSchedulerAlgorithm.DDIM, gen5Sd21.algorithm)
        assertEquals(ImagePredictionType.V_PREDICTION, gen5Sd21.predictionType)
        assertEquals(ImageTimestepSpacing.LEADING, gen5Sd21.timestepSpacing)
        assertEquals(1, gen5Sd21.stepsOffset)
        assertTrue(gen5Sd21.skipPrkSteps)
        assertEquals(1, gen5Sd21.minSteps)
        assertEquals(100, gen5Sd21.maxSteps)
    }

    @Test
    fun `DMD2 and Turbo builtins expose their exact family and variant`() {
        val dmd2 = resolve("realismsdxl_dmd2_alt_qnn228")
        assertEquals(LocalImageModelFamily.SDXL, dmd2.profile.family)
        assertEquals(ImageModelVariant.DMD2_ALT, dmd2.profile.variant)
        assertFalse(dmd2.profile.defaults.useCfg)
        assertEquals(4, dmd2.layers.resolved.unetExecutionCount)
        assertEquals(154, dmd2.layers.resolved.tokenCount)
        assertEquals(listOf(ImageProfileSource.BUILT_IN), dmd2.sourceChain)

        val turbo = resolve("sd_turbo_512_experimental")
        assertEquals(LocalImageModelFamily.SD_TURBO, turbo.profile.family)
        assertEquals(ImageModelVariant.SD_TURBO, turbo.profile.variant)
        assertEquals(1.0, turbo.profile.defaults.cfgScale, 0.0)
        assertFalse(turbo.profile.defaults.useCfg)
        assertEquals(77, turbo.layers.resolved.tokenCount)
        assertEquals(listOf(ImageProfileSource.BUILT_IN), turbo.sourceChain)

        val zImageTurbo = resolve("z_image_turbo_q4")
        assertEquals(LocalImageModelFamily.Z_IMAGE, zImageTurbo.profile.family)
        assertEquals(ImageModelVariant.Z_IMAGE_TURBO, zImageTurbo.profile.variant)
        assertEquals(8, zImageTurbo.profile.defaults.steps)
        assertEquals(8, zImageTurbo.layers.resolved.unetExecutionCount)
        assertEquals(1.0, zImageTurbo.profile.defaults.cfgScale, 0.0)
        assertFalse(zImageTurbo.profile.defaults.useCfg)
        assertEquals(77, zImageTurbo.layers.resolved.tokenCount)
        assertEquals(listOf(ImageProfileSource.BUILT_IN), zImageTurbo.sourceChain)
    }

    @Test
    fun `recommended CFG models expose concise family defaults and model tuned controls`() {
        val expectedNegativePrompts = mapOf(
            "cyberrealistic_sd15_qnn228" to RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
            "realisticvisionhyper_sd15_qnn228" to RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
            "dreamshaper_sd15_qnn228" to RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            "meinamix_sd15_qnn228" to RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT,
            "sdxl_base_qnn228" to RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT,
            "animagine_xl_v4_qnn228" to RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT,
            "cyberrealisticxl_qnn228" to RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT,
            "qualcomm_sd15_gen5_qnn" to RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            "qualcomm_sd21_gen5_qnn" to RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            "qualcomm_controlnet_canny_gen5_qnn" to RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            "sd15_mnn_512_quality" to RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            "mnn_sana_edit_v2" to RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT,
            "qwen_image_2512_q2" to RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT,
            "longcat_image_q4" to RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT
        )

        expectedNegativePrompts.forEach { (id, expected) ->
            val profile = resolve(id).profile
            assertTrue("$id must expose its executable negative branch", profile.capabilities.supportsNegativePrompt)
            assertTrue("$id must tokenize negative and positive prompts separately", profile.tokenizer.separateNegativePrompt)
            assertEquals(id, expected, profile.defaults.defaultNegativePrompt)
        }

        val animagine = resolve("animagine_xl_v4_qnn228").profile.defaults
        assertEquals(RecommendedImageDefaults.ANIMAGINE_XL_STEPS, animagine.steps)
        assertEquals(RecommendedImageDefaults.ANIMAGINE_XL_CFG, animagine.cfgScale, 0.0)

        val cyberXl = resolve("cyberrealisticxl_qnn228").profile.defaults
        assertEquals(RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS, cyberXl.steps)
        assertEquals(RecommendedImageDefaults.CYBERREALISTIC_XL_CFG, cyberXl.cfgScale, 0.0)
    }

    @Test
    fun `conditional only recommendations never expose or accept a negative prompt branch`() {
        listOf(
            "realismsdxl_dmd2_alt_qnn228",
            "sd_turbo_512_experimental",
            "z_image_turbo_q4",
            "flux2_klein_4b_q4"
        ).forEach { id ->
            val profile = resolve(id).profile
            assertFalse("$id must remain conditional-only", profile.defaults.useCfg)
            assertFalse("$id must not advertise negative prompts", profile.capabilities.supportsNegativePrompt)
            assertFalse("$id must not allocate a negative token branch", profile.tokenizer.separateNegativePrompt)
            assertNull("$id must not inject a default negative prompt", profile.defaults.defaultNegativePrompt)
        }
        listOf(
            "sd_turbo_512_experimental",
            "z_image_turbo_q4",
            "flux2_klein_4b_q4"
        ).forEach { id ->
            assertEquals(
                "$id must retain one active tokenizer branch of parsing capacity",
                77,
                resolve(id).layers.resolved.tokenCount
            )
        }

        val unsupported = expectResolutionFailure {
            ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = "sd_turbo_512_experimental",
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = LocalImageModelFamily.SD_TURBO,
                    userOverrides = ImageGenerationOverrides(
                        negativePrompt = "blur",
                        negativePromptSpecified = true
                    )
                )
            )
        }
        assertTrue(unsupported.validation.issues.any { it.code == "NEGATIVE_PROMPT_UNSUPPORTED" })

        val explicitlyEmpty = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "sd_turbo_512_experimental",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO,
                userOverrides = ImageGenerationOverrides(
                    negativePrompt = "",
                    negativePromptSpecified = true
                )
            )
        )
        assertEquals("", explicitlyEmpty.profile.defaults.defaultNegativePrompt)

        listOf(
            LocalImageModelFamily.SD_TURBO,
            LocalImageModelFamily.Z_IMAGE,
            LocalImageModelFamily.FLUX
        ).forEach { family ->
            val generic = ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = null,
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = family
                )
            ).profile
            assertFalse("$family generic fallback must remain conditional-only", generic.defaults.useCfg)
            assertFalse(generic.capabilities.supportsNegativePrompt)
            assertFalse(generic.tokenizer.separateNegativePrompt)
        }
    }

    @Test
    fun `generic flow-family fallbacks keep native workflow step counts`() {
        val expectedSteps = mapOf(
            LocalImageModelFamily.Z_IMAGE to 8,
            LocalImageModelFamily.QWEN_IMAGE to 40,
            LocalImageModelFamily.LONGCAT_IMAGE to 20
        )

        expectedSteps.forEach { (family, steps) ->
            val profile = ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = null,
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = family
                )
            ).profile
            assertEquals("$family generic step count", steps, profile.defaults.steps)
            assertEquals(ImageSchedulerAlgorithm.FLOW_MATCH, profile.scheduler.algorithm)
        }
    }

    @Test
    fun `manifest profile wins over sidecar builtin and capability discovery`() {
        val builtIn = resolve("qualcomm_sd15_gen5_qnn").profile
        val manifest = builtIn.copy(
            profileId = "package.manifest.profile",
            profileRevision = 7,
            provenance = ImageProfileProvenance(ImageProfileSource.MANIFEST, listOf(ImageProfileSource.MANIFEST))
        )
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "qualcomm_sd15_gen5_qnn",
                manifestProfile = manifest,
                sidecar = ImageProfileSidecar(profileId = "package.sidecar.profile"),
                capabilityDiscovery = ImageCapabilityDiscovery(variant = ImageModelVariant.HYPER)
            )
        )

        assertEquals("package.manifest.profile", resolution.profile.profileId)
        assertEquals(7, resolution.profile.profileRevision)
        assertEquals(ImageProfileSource.MANIFEST, resolution.fieldSources.getValue("profileId"))
        assertEquals(listOf(ImageProfileSource.MANIFEST), resolution.sourceChain)
    }

    @Test
    fun `sidecar fields override builtin while builtin beats capability discovery`() {
        val original = resolve("cyberrealistic_sd15_qnn228").profile
        val sidecarScheduler = original.scheduler.copy(
            algorithm = ImageSchedulerAlgorithm.EULER,
            defaultSteps = 24
        )
        val sidecarDefaults = original.defaults.copy(steps = 24)
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "cyberrealistic_sd15_qnn228",
                sidecar = ImageProfileSidecar(
                    profileId = "package.scheduler.sidecar",
                    scheduler = sidecarScheduler,
                    defaults = sidecarDefaults
                ),
                capabilityDiscovery = ImageCapabilityDiscovery(variant = ImageModelVariant.HYPER)
            )
        )

        assertEquals("package.scheduler.sidecar", resolution.profile.profileId)
        assertEquals(ImageSchedulerAlgorithm.EULER, resolution.profile.scheduler.algorithm)
        assertEquals(ImageModelVariant.STANDARD, resolution.profile.variant)
        assertEquals(ImageProfileSource.SIDECAR, resolution.fieldSources.getValue("scheduler"))
        assertEquals(ImageProfileSource.BUILT_IN, resolution.fieldSources.getValue("variant"))
        assertEquals(
            listOf(ImageProfileSource.SIDECAR, ImageProfileSource.BUILT_IN),
            resolution.sourceChain
        )
    }

    @Test
    fun `sidecar behavior overrides builtin behavior and resizes tensor shapes`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "sd_turbo_512_experimental",
                sidecar = ImageProfileSidecar(
                    behavior = ImagePackageBehaviorConfig(
                        family = LocalImageModelFamily.SDXL,
                        variant = ImageModelVariant.DMD2_ALT,
                        defaultPrompt = "sidecar prompt",
                        steps = 6,
                        cfgScale = 2.5,
                        scheduler = ImageSchedulerAlgorithm.DPMPP_2M,
                        width = 768,
                        height = 512,
                        useCfg = true
                    )
                )
            )
        )

        assertEquals(LocalImageModelFamily.SDXL, resolution.profile.family)
        assertEquals(ImageModelVariant.DMD2_ALT, resolution.profile.variant)
        assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, resolution.profile.scheduler.algorithm)
        assertEquals(6, resolution.profile.scheduler.defaultSteps)
        assertEquals(6, resolution.profile.defaults.steps)
        assertEquals(2.5, resolution.profile.defaults.cfgScale, 0.0)
        assertTrue(resolution.profile.defaults.useCfg)
        assertEquals(768, resolution.profile.defaults.width)
        assertEquals(512, resolution.profile.defaults.height)
        assertEquals(listOf(1, 4, 64, 96), resolution.profile.latent.initialShape)
        assertEquals(listOf(1, 4, 64, 96), resolution.profile.vae.inputShape)
        assertEquals(listOf(1, 3, 512, 768), resolution.profile.vae.outputShape)

        listOf(
            "family",
            "variant",
            "scheduler.algorithm",
            "scheduler.defaultSteps",
            "defaults.defaultPrompt",
            "defaults.steps",
            "defaults.cfgScale",
            "defaults.width",
            "defaults.height",
            "defaults.useCfg",
            "latent.initialShape",
            "vae.inputShape",
            "vae.outputShape"
        ).forEach { field ->
            assertEquals(field, ImageProfileSource.SIDECAR, resolution.fieldSources.getValue(field))
        }
        assertEquals(ImageProfileSource.BUILT_IN, resolution.fieldSources.getValue("profileId"))
        assertEquals(
            listOf(ImageProfileSource.SIDECAR, ImageProfileSource.BUILT_IN),
            resolution.sourceChain
        )
        assertEquals(resolution.sourceChain, resolution.profile.provenance.sources)
    }

    @Test
    fun `cfg zero package behavior remains an explicit unconditional cfg branch`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "sd_turbo_512_experimental",
                sidecar = ImageProfileSidecar(
                    behavior = ImagePackageBehaviorConfig(cfgScale = 0.0)
                )
            )
        )

        assertEquals(0.0, resolution.profile.defaults.cfgScale, 0.0)
        assertTrue(resolution.profile.defaults.useCfg)
        assertEquals(
            resolution.layers.resolved.timetableCount * 2,
            resolution.layers.resolved.unetExecutionCount
        )
    }

    @Test
    fun `manifest behavior overrides sidecar behavior field by field`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "sd_turbo_512_experimental",
                manifestBehavior = ImagePackageBehaviorConfig(
                    family = LocalImageModelFamily.SD_TURBO,
                    variant = ImageModelVariant.SD_TURBO,
                    defaultPrompt = "manifest prompt",
                    steps = 4,
                    cfgScale = 1.0,
                    scheduler = ImageSchedulerAlgorithm.EULER_A,
                    width = 768,
                    height = 576,
                    useCfg = false
                ),
                sidecar = ImageProfileSidecar(
                    behavior = ImagePackageBehaviorConfig(
                        family = LocalImageModelFamily.SDXL,
                        variant = ImageModelVariant.DMD2_ALT,
                        defaultPrompt = "sidecar prompt",
                        defaultNegativePrompt = "sidecar negative",
                        steps = 7,
                        cfgScale = 3.0,
                        scheduler = ImageSchedulerAlgorithm.DPMPP_2M,
                        width = 640,
                        height = 512,
                        useCfg = true
                    )
                )
            )
        )

        assertEquals(LocalImageModelFamily.SD_TURBO, resolution.profile.family)
        assertEquals(ImageModelVariant.SD_TURBO, resolution.profile.variant)
        assertEquals(ImageSchedulerAlgorithm.EULER_A, resolution.profile.scheduler.algorithm)
        assertEquals(4, resolution.profile.defaults.steps)
        assertEquals(1.0, resolution.profile.defaults.cfgScale, 0.0)
        assertFalse(resolution.profile.defaults.useCfg)
        assertEquals("manifest prompt", resolution.profile.defaults.defaultPrompt)
        assertEquals("sidecar negative", resolution.profile.defaults.defaultNegativePrompt)
        assertEquals(768, resolution.profile.defaults.width)
        assertEquals(576, resolution.profile.defaults.height)
        assertEquals(listOf(1, 4, 72, 96), resolution.profile.latent.initialShape)
        assertEquals(listOf(1, 4, 72, 96), resolution.profile.vae.inputShape)
        assertEquals(listOf(1, 3, 576, 768), resolution.profile.vae.outputShape)

        listOf(
            "family",
            "variant",
            "scheduler.algorithm",
            "scheduler.defaultSteps",
            "defaults.defaultPrompt",
            "defaults.steps",
            "defaults.cfgScale",
            "defaults.width",
            "defaults.height",
            "defaults.useCfg",
            "latent.initialShape",
            "vae.inputShape",
            "vae.outputShape"
        ).forEach { field ->
            assertEquals(field, ImageProfileSource.MANIFEST, resolution.fieldSources.getValue(field))
        }
        assertEquals(
            ImageProfileSource.SIDECAR,
            resolution.fieldSources.getValue("defaults.defaultNegativePrompt")
        )
        assertEquals(
            listOf(
                ImageProfileSource.MANIFEST,
                ImageProfileSource.SIDECAR,
                ImageProfileSource.BUILT_IN
            ),
            resolution.sourceChain
        )
        assertEquals(ImageProfileSource.MANIFEST, resolution.profile.provenance.primarySource)
        assertEquals(resolution.sourceChain, resolution.profile.provenance.sources)
    }

    @Test
    fun `user overrides are highest priority and resize package tensor shapes`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "sd_turbo_512_experimental",
                manifestBehavior = ImagePackageBehaviorConfig(
                    steps = 5,
                    cfgScale = 1.0,
                    scheduler = ImageSchedulerAlgorithm.EULER_A,
                    width = 768,
                    height = 576,
                    useCfg = false
                ),
                sidecar = ImageProfileSidecar(
                    behavior = ImagePackageBehaviorConfig(
                        steps = 7,
                        cfgScale = 3.0,
                        scheduler = ImageSchedulerAlgorithm.DPMPP_2M,
                        width = 640,
                        height = 512,
                        useCfg = true
                    )
                ),
                userOverrides = ImageGenerationOverrides(
                    scheduler = ImageSchedulerAlgorithm.DPMPP_2M,
                    steps = 3,
                    cfgScale = 2.0,
                    useCfg = true,
                    width = 896,
                    height = 640,
                    seed = 2_026_071_701L
                )
            )
        )

        assertEquals(ImageSchedulerAlgorithm.DPMPP_2M, resolution.profile.scheduler.algorithm)
        assertEquals(3, resolution.profile.defaults.steps)
        assertEquals(2.0, resolution.profile.defaults.cfgScale, 0.0)
        assertTrue(resolution.profile.defaults.useCfg)
        assertEquals(896, resolution.profile.defaults.width)
        assertEquals(640, resolution.profile.defaults.height)
        assertEquals(2_026_071_701L, resolution.profile.defaults.seed)
        assertEquals(listOf(1, 4, 80, 112), resolution.profile.latent.initialShape)
        assertEquals(listOf(1, 4, 80, 112), resolution.profile.vae.inputShape)
        assertEquals(listOf(1, 3, 640, 896), resolution.profile.vae.outputShape)

        listOf(
            "scheduler",
            "defaults.steps",
            "defaults.cfgScale",
            "defaults.useCfg",
            "defaults.width",
            "defaults.height",
            "defaults.seed",
            "latent.initialShape",
            "vae.inputShape",
            "vae.outputShape"
        ).forEach { field ->
            assertEquals(field, ImageProfileSource.USER_OVERRIDE, resolution.fieldSources.getValue(field))
        }
        assertEquals(
            listOf(
                ImageProfileSource.USER_OVERRIDE,
                ImageProfileSource.MANIFEST,
                ImageProfileSource.SIDECAR,
                ImageProfileSource.BUILT_IN
            ),
            resolution.sourceChain
        )
        assertEquals(ImageProfileSource.USER_OVERRIDE, resolution.profile.provenance.primarySource)
        assertEquals(resolution.sourceChain, resolution.profile.provenance.sources)
        assertEquals(896, resolution.layers.resolved.width)
        assertEquals(640, resolution.layers.resolved.height)
    }

    @Test
    fun `capability discovery wins over generic fallback when no exact recommendation exists`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM,
                capabilityDiscovery = ImageCapabilityDiscovery(
                    family = LocalImageModelFamily.FLUX,
                    variant = ImageModelVariant.FLUX2_KLEIN
                )
            )
        )

        assertEquals(ImageModelVariant.FLUX2_KLEIN, resolution.profile.variant)
        assertEquals(LocalImageModelFamily.FLUX, resolution.profile.family)
        assertEquals(ImageSchedulerAlgorithm.FLOW_MATCH, resolution.profile.scheduler.algorithm)
        assertEquals(ImagePredictionType.FLOW, resolution.profile.scheduler.predictionType)
        assertEquals(4, resolution.profile.defaults.steps)
        assertEquals(1.0, resolution.profile.defaults.cfgScale, 0.0)
        assertEquals(1024, resolution.profile.defaults.width)
        assertEquals(setOf(ImageSchedulerAlgorithm.FLOW_MATCH), resolution.profile.capabilities.supportedSchedulers)
        assertTrue(resolution.profile.tokenizer.supportsPromptWeighting)
        assertTrue(resolution.profile.capabilities.supportsPromptWeighting)
        assertTrue(resolution.layers.resolved.promptWeightingSupported)
        assertEquals(ImageProfileSource.CAPABILITY_DISCOVERY, resolution.profile.provenance.primarySource)
        assertEquals(listOf(ImageProfileSource.CAPABILITY_DISCOVERY), resolution.sourceChain)
    }

    @Test
    fun `capability discovery cannot re-enable prompt weighting after an explicit disable`() {
        val generic = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM
            )
        ).profile
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM,
                capabilityDiscovery = ImageCapabilityDiscovery(
                    tokenizer = generic.tokenizer.copy(supportsPromptWeighting = true),
                    capabilities = generic.capabilities.copy(supportsPromptWeighting = false)
                )
            )
        )

        assertTrue(resolution.profile.tokenizer.supportsPromptWeighting)
        assertFalse(resolution.profile.capabilities.supportsPromptWeighting)
        assertFalse(resolution.layers.resolved.promptWeightingSupported)
    }

    @Test
    fun `unknown device and unknown model retain generic compatible run path`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.CUSTOM,
                family = LocalImageModelFamily.CUSTOM,
                deviceHints = ImageDeviceExecutionHints(
                    localProfileKnown = false,
                    preferredWorkerStrategy = ImageWorkerStrategy.DEDICATED_WORKER,
                    preferredThreads = 3
                )
            )
        )

        assertEquals(ImageModelVariant.GENERIC_COMPATIBLE, resolution.profile.variant)
        assertEquals(ImageProfileSource.GENERIC_FALLBACK, resolution.profile.provenance.primarySource)
        assertFalse(resolution.deviceAdmissionRestricted)
        assertTrue(resolution.validation.valid)
        assertTrue(resolution.warnings.single().contains("native load"))
    }

    @Test
    fun `built in capability claims expose only implemented sampler and weighting paths`() {
        val stable = resolve("sd_turbo_512_experimental").profile
        assertEquals(
            setOf(
                ImageSchedulerAlgorithm.EULER_A,
                ImageSchedulerAlgorithm.EULER,
                ImageSchedulerAlgorithm.DPMPP_2M
            ),
            stable.capabilities.supportedSchedulers
        )
        assertFalse(ImageSchedulerAlgorithm.PNDM_PLMS in stable.capabilities.supportedSchedulers)
        assertTrue(stable.capabilities.supportsPromptWeighting)
        assertTrue(stable.tokenizer.supportsPromptWeighting)
        assertTrue(stable.capabilities.supportsClipSkip)
        assertTrue(stable.capabilities.supportsVaeTiling)
        assertTrue(stable.capabilities.supportsLivePreview)
        assertTrue(stable.capabilities.supportsLora)
        assertEquals(8, stable.capabilities.maxBatchCount)

        val communityQnn = resolve("cyberrealistic_sd15_qnn228").profile
        assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, communityQnn.tokenizer.backend)
        assertTrue(communityQnn.tokenizer.supportsPromptWeighting)
        assertTrue(communityQnn.capabilities.supportsPromptWeighting)
        assertFalse(communityQnn.capabilities.supportsClipSkip)
        assertFalse(communityQnn.capabilities.supportsVaeTiling)
        assertFalse(communityQnn.capabilities.supportsLivePreview)
        assertFalse(communityQnn.capabilities.supportsLora)
        assertEquals(1, communityQnn.capabilities.maxBatchCount)

        val officialMnn = resolve("sd15_mnn_512_quality").profile
        assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, officialMnn.tokenizer.backend)
        assertFalse(officialMnn.tokenizer.supportsPromptWeighting)
        assertFalse(officialMnn.capabilities.supportsPromptWeighting)
        assertFalse(officialMnn.capabilities.supportsClipSkip)
        assertFalse(officialMnn.capabilities.supportsVaeTiling)
        assertFalse(officialMnn.capabilities.supportsLivePreview)
        assertFalse(officialMnn.capabilities.supportsLora)
        assertEquals(1, officialMnn.capabilities.maxBatchCount)
        listOf(
            resolve("qualcomm_sd15_gen5_qnn"),
            resolve("qualcomm_sd21_gen5_qnn"),
            resolve("qualcomm_controlnet_canny_gen5_qnn")
        ).forEach { resolution ->
            assertEquals(ImageTokenizerBackend.TOKENIZERS_CPP, resolution.profile.tokenizer.backend)
            assertFalse(resolution.profile.tokenizer.supportsPromptWeighting)
            assertFalse(resolution.profile.capabilities.supportsPromptWeighting)
            assertFalse(resolution.profile.capabilities.supportsClipSkip)
            assertFalse(resolution.profile.capabilities.supportsVaeTiling)
            assertFalse(resolution.profile.capabilities.supportsLivePreview)
            assertFalse(resolution.profile.capabilities.supportsLora)
            assertEquals(1, resolution.profile.capabilities.maxBatchCount)
            assertFalse(resolution.layers.resolved.promptWeightingSupported)
            assertEquals(154, resolution.layers.resolved.tokenCount)
        }
        val sana = resolve("mnn_sana_edit_v2").profile
        assertFalse(sana.tokenizer.supportsPromptWeighting)
        assertFalse(sana.capabilities.supportsPromptWeighting)
        assertFalse(sana.capabilities.supportsClipSkip)
        assertFalse(sana.capabilities.supportsVaeTiling)
        assertFalse(sana.capabilities.supportsLivePreview)
        assertFalse(sana.capabilities.supportsLora)
        assertEquals(1, sana.capabilities.maxBatchCount)

        val flowStable = resolve("z_image_turbo_q4").profile
        assertTrue(flowStable.tokenizer.supportsPromptWeighting)
        assertTrue(flowStable.capabilities.supportsPromptWeighting)
        assertFalse(flowStable.capabilities.supportsClipSkip)
        assertTrue(flowStable.capabilities.supportsVaeTiling)
        assertTrue(flowStable.capabilities.supportsLivePreview)
        assertTrue(flowStable.capabilities.supportsLora)
        assertEquals(8, flowStable.capabilities.maxBatchCount)

        val genericStable = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM
            )
        ).profile
        assertFalse(ImageSchedulerAlgorithm.PNDM_PLMS in genericStable.capabilities.supportedSchedulers)
        assertEquals(
            setOf(
                ImageSchedulerAlgorithm.EULER,
                ImageSchedulerAlgorithm.EULER_A,
                ImageSchedulerAlgorithm.DDIM,
                ImageSchedulerAlgorithm.DPMPP_2M,
                ImageSchedulerAlgorithm.LCM,
                ImageSchedulerAlgorithm.FLOW_MATCH
            ),
            genericStable.capabilities.supportedSchedulers
        )
        assertTrue(genericStable.tokenizer.supportsPromptWeighting)
        assertTrue(genericStable.capabilities.supportsPromptWeighting)
        val genericMnn = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.MNN_DIFFUSION,
                family = LocalImageModelFamily.CUSTOM
            )
        ).profile
        assertFalse(genericMnn.tokenizer.supportsPromptWeighting)
        assertFalse(genericMnn.capabilities.supportsPromptWeighting)
    }

    @Test
    fun `modern stable diffusion builtins retain model-specific flow defaults`() {
        val flux = resolve("flux2_klein_4b_q4").profile
        assertEquals(4, flux.defaults.steps)
        assertEquals(1.0, flux.defaults.cfgScale, 0.0)
        assertEquals(setOf(ImageSchedulerAlgorithm.FLOW_MATCH), flux.capabilities.supportedSchedulers)

        val qwen = resolve("qwen_image_2512_q2").profile
        assertEquals(40, qwen.defaults.steps)
        assertEquals(2.5, qwen.defaults.cfgScale, 0.0)
        assertEquals(setOf(ImageSchedulerAlgorithm.FLOW_MATCH), qwen.capabilities.supportedSchedulers)

        val longCat = resolve("longcat_image_q4").profile
        assertEquals(20, longCat.defaults.steps)
        assertEquals(5.0, longCat.defaults.cfgScale, 0.0)
        assertEquals(setOf(ImageSchedulerAlgorithm.FLOW_MATCH), longCat.capabilities.supportedSchedulers)
    }

    @Test
    fun `MNN quality profile deterministically requires direct runner`() {
        val profile = resolve("sd15_mnn_512_quality").profile
        assertEquals(ImageWorkerStrategy.IN_PROCESS, profile.graph.workerStrategy)
        assertEquals("direct", resolveMnnDiffusionProfileRunner(profile, null))
        assertEquals("direct", resolveMnnDiffusionProfileRunner(profile, "direct"))
        assertInvalid { resolveMnnDiffusionProfileRunner(profile, "module") }
    }

    @Test
    fun `explicit user values are final and explicit blank negative prompt is preserved`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "cyberrealistic_sd15_qnn228",
                userOverrides = ImageGenerationOverrides(
                    expectedProfileId = "community.sd15.qnn228",
                    expectedProfileRevision = 2,
                    scheduler = ImageSchedulerAlgorithm.EULER,
                    predictionType = ImagePredictionType.EPSILON,
                    steps = 22,
                    cfgScale = 6.25,
                    useCfg = true,
                    width = 512,
                    height = 512,
                    seed = 2_026_071_700L,
                    negativePrompt = "",
                    negativePromptSpecified = true
                )
            )
        )

        assertEquals(ImageSchedulerAlgorithm.EULER, resolution.profile.scheduler.algorithm)
        assertEquals(22, resolution.profile.defaults.steps)
        assertEquals(6.25, resolution.profile.defaults.cfgScale, 0.0)
        assertEquals(2_026_071_700L, resolution.profile.defaults.seed)
        assertEquals("", resolution.profile.defaults.defaultNegativePrompt)
        assertEquals(ImageProfileSource.USER_OVERRIDE, resolution.fieldSources.getValue("defaults.steps"))
        assertEquals(
            listOf(ImageProfileSource.USER_OVERRIDE, ImageProfileSource.BUILT_IN),
            resolution.sourceChain
        )
        assertEquals(22, resolution.layers.resolved.steps)
    }

    @Test
    fun `profile is bound to exact model fingerprint and mismatch is rejected`() {
        val first = resolve("qualcomm_sd15_gen5_qnn").profile
        val second = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "qualcomm_sd15_gen5_qnn",
                fingerprint = OTHER_FINGERPRINT
            )
        ).profile
        assertNotEquals(first.bindingFingerprint, second.bindingFingerprint)

        val mismatchedManifest = first.copy(modelFingerprint = OTHER_FINGERPRINT)
        val exception = expectResolutionFailure {
            ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = null,
                    manifestProfile = mismatchedManifest
                )
            )
        }
        assertTrue(exception.validation.issues.any { it.code == "MODEL_FINGERPRINT_MISMATCH" })
    }

    @Test
    fun `older pinned qnn sd15 manifests migrate only to their exact catalog contract`() {
        val models = ModelScopeClient().recommendedModels()
            .filter { model -> model.id in pinnedQnnSd15Ids }
        assertEquals(pinnedQnnSd15Ids, models.mapTo(linkedSetOf()) { it.id })

        models.forEach { model ->
            val bundle = requireNotNull(model.imageEngineBundle)
            val fingerprint = model.id.sha256TestFingerprint()
            val current = requireNotNull(
                materializeDownloadedImageExecutionProfile(bundle, fingerprint)
            )
            val persisted = current.copy(
                profileRevision = 1,
                graph = current.graph.copy(
                    schedulerSidecar = "scheduler/scheduler_config.json",
                    tokenizerSidecar = "tokenizer/tokenizer_config.json",
                    configSidecars = emptyList()
                )
            )

            val resolved = ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = model.id,
                    fingerprint = fingerprint,
                    manifestProfile = persisted,
                    recommendationEvidence = pinnedRecommendationEvidence(model.id)
                )
            ).profile

            assertEquals(2, resolved.profileRevision)
            assertEquals(
                "${model.id} did not migrate to the complete catalog contract",
                current.copy(provenance = resolved.provenance),
                resolved
            )
            assertEquals(ImageProfileSource.MANIFEST, resolved.provenance.primarySource)
        }
    }

    @Test
    fun `pinned manifest migration rejects changed fingerprints missing source proof and conflicts`() {
        val model = ModelScopeClient().recommendedModels()
            .single { it.id == "cyberrealistic_sd15_qnn228" }
        val bundle = requireNotNull(model.imageEngineBundle)
        val installedFingerprint = model.id.sha256TestFingerprint()
        val current = requireNotNull(
            materializeDownloadedImageExecutionProfile(bundle, installedFingerprint)
        )
        val oldGraph = current.graph.copy(
            schedulerSidecar = "scheduler/scheduler_config.json",
            tokenizerSidecar = "tokenizer/tokenizer_config.json",
            configSidecars = emptyList()
        )

        val fingerprintFailure = expectResolutionFailure {
            ImageExecutionProfileResolver.resolve(
                input(
                    recommendationId = model.id,
                    fingerprint = OTHER_FINGERPRINT,
                    manifestProfile = current.copy(profileRevision = 1, graph = oldGraph),
                    recommendationEvidence = pinnedRecommendationEvidence(model.id)
                )
            )
        }
        assertTrue(fingerprintFailure.validation.issues.any {
            it.code == "MODEL_FINGERPRINT_MISMATCH"
        })

        val missingSourceProof = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = model.id,
                fingerprint = installedFingerprint,
                manifestProfile = current.copy(profileRevision = 1, graph = oldGraph)
            )
        ).profile
        assertEquals(1, missingSourceProof.profileRevision)
        assertEquals("scheduler/scheduler_config.json", missingSourceProof.graph.schedulerSidecar)

        val aliasOnlySourceProof = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = model.id,
                fingerprint = installedFingerprint,
                manifestProfile = current.copy(profileRevision = 1, graph = oldGraph),
                recommendationEvidence = ImageRecommendationEvidence(
                    aliases = listOf(model.id),
                    sourceRepositories = listOf(model.repoId),
                    artifactPaths = listOf(
                        model.id,
                        requireNotNull(model.imageEngineBundle).id,
                        "local/bundle-${model.id}/unet.bin"
                    )
                )
            )
        ).profile
        assertEquals(1, aliasOnlySourceProof.profileRevision)
        assertEquals("scheduler/scheduler_config.json", aliasOnlySourceProof.graph.schedulerSidecar)

        val conflictingIdentity = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = model.id,
                fingerprint = installedFingerprint,
                manifestProfile = current.copy(
                    profileRevision = 1,
                    provenance = current.provenance.copy(
                        recommendationId = "dreamshaper_sd15_qnn228"
                    ),
                    graph = oldGraph
                ),
                recommendationEvidence = pinnedRecommendationEvidence(model.id)
            )
        ).profile
        assertEquals(1, conflictingIdentity.profileRevision)
        assertEquals("scheduler/scheduler_config.json", conflictingIdentity.graph.schedulerSidecar)

        val conflictingProfileId = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = model.id,
                fingerprint = installedFingerprint,
                manifestProfile = current.copy(
                    profileId = "community.sd15.other.qnn228",
                    profileRevision = 1,
                    graph = oldGraph
                ),
                recommendationEvidence = pinnedRecommendationEvidence(model.id)
            )
        ).profile
        assertEquals(1, conflictingProfileId.profileRevision)
        assertEquals("community.sd15.other.qnn228", conflictingProfileId.profileId)
    }

    @Test
    fun `unsafe graph path and unsupported scheduler fail final validation`() {
        val base = resolve("qualcomm_sd15_gen5_qnn").profile
        val unsafe = base.copy(
            graph = base.graph.copy(unet = base.graph.unet!!.copy(relativePath = "../unet.bin"))
        )
        val unsafeReport = ImageExecutionProfileValidator.validate(unsafe, FINGERPRINT)
        assertTrue(unsafeReport.issues.any { it.code == "PROFILE_PATH_INVALID" })

        val unsupported = base.copy(
            scheduler = base.scheduler.copy(algorithm = ImageSchedulerAlgorithm.DDIM)
        )
        val unsupportedReport = ImageExecutionProfileValidator.validate(unsupported, FINGERPRINT)
        assertTrue(unsupportedReport.issues.any { it.code == "SCHEDULER_UNSUPPORTED" })

        val impossiblePromptWeighting = base.copy(
            tokenizer = base.tokenizer.copy(supportsPromptWeighting = false),
            capabilities = base.capabilities.copy(supportsPromptWeighting = true)
        )
        val weightingReport = ImageExecutionProfileValidator.validate(
            impossiblePromptWeighting,
            FINGERPRINT
        )
        assertTrue(weightingReport.issues.any {
            it.code == "CAPABILITY_CONTRACT_INVALID" && it.field == "supportsPromptWeighting"
        })
    }

    @Test
    fun `requested resolved and native effective layers require exact execution match`() {
        val resolution = resolve("qualcomm_sd21_gen5_qnn")
        val missingNative = ImageExecutionContractValidator.validate(resolution.layers)
        assertEquals(EXECUTION_CONTRACT_MISMATCH, missingNative.errorCode)
        assertEquals("nativeEffective", missingNative.mismatches.single().field)

        val native = resolution.layers.resolved.toNativeEffective()
        val valid = ImageExecutionContractValidator.validate(
            resolution.layers.copy(nativeEffective = native)
        )
        assertTrue(valid.valid)
        assertNull(valid.errorCode)

        val mismatch = ImageExecutionContractValidator.validate(
            resolution.layers.copy(
                nativeEffective = native.copy(
                    scheduler = ImageSchedulerAlgorithm.EULER,
                    predictionType = ImagePredictionType.EPSILON,
                    fallback = true
                )
            )
        )
        assertFalse(mismatch.valid)
        assertEquals(EXECUTION_CONTRACT_MISMATCH, mismatch.errorCode)
        assertEquals(setOf("scheduler", "predictionType", "fallback"), mismatch.mismatches.map { it.field }.toSet())
    }

    @Test
    fun `pndm contract exposes repeated timetable and branch execution counts`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = null,
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.CUSTOM
            )
        )

        assertEquals(ImageSchedulerAlgorithm.PNDM_PLMS, resolution.layers.resolved.scheduler)
        assertEquals(21, resolution.layers.resolved.timetableCount)
        assertEquals(42, resolution.layers.resolved.unetExecutionCount)
    }

    @Test
    fun `pndm override accounts for the full prk warmup timetable`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "cyberrealistic_sd15_qnn228",
                userOverrides = ImageGenerationOverrides(
                    scheduler = ImageSchedulerAlgorithm.PNDM_PLMS
                )
            )
        )

        assertFalse(resolution.profile.scheduler.skipPrkSteps)
        assertEquals(29, resolution.layers.resolved.timetableCount)
        assertEquals(58, resolution.layers.resolved.unetExecutionCount)
    }

    @Test
    fun `task profiles declare concrete input capabilities`() {
        val control = resolve("qualcomm_controlnet_canny_gen5_qnn").profile
        assertEquals(ImageTask.CONTROL_IMAGE, control.task)
        assertTrue(control.capabilities.requiresControlImage)
        assertTrue(control.graph.controlNet != null)

        val edit = resolve("mnn_sana_edit_v2").profile
        assertEquals(ImageTask.IMAGE_EDIT, edit.task)
        assertTrue(edit.capabilities.requiresInputImage)
        assertFalse(edit.capabilities.supportsMask)
        assertEquals(listOf(1, 256), edit.conditioning.textEncoderInputShape)
        assertEquals(listOf(listOf(1, 256, 1)), edit.conditioning.textEncoderOutputShapes)
        assertTrue(edit.tokenizer.separateNegativePrompt)
        assertEquals(256, resolve("mnn_sana_edit_v2").layers.resolved.tokenCount)
        assertEquals(10, edit.defaults.steps)
        assertEquals(2, edit.scheduler.minSteps)
        assertEquals(50, edit.scheduler.maxSteps)
    }

    private fun resolve(recommendationId: String): ImageExecutionProfileResolution =
        ImageExecutionProfileResolver.resolve(input(recommendationId = recommendationId))

    private fun input(
        recommendationId: String?,
        fingerprint: String = FINGERPRINT,
        runtime: LocalImageRuntime = LocalImageRuntime.QNN_HTP,
        family: LocalImageModelFamily = LocalImageModelFamily.SD15,
        manifestProfile: ImageExecutionProfile? = null,
        manifestBehavior: ImagePackageBehaviorConfig? = null,
        sidecar: ImageProfileSidecar? = null,
        capabilityDiscovery: ImageCapabilityDiscovery? = null,
        recommendationEvidence: ImageRecommendationEvidence = ImageRecommendationEvidence(),
        userOverrides: ImageGenerationOverrides = ImageGenerationOverrides(),
        deviceHints: ImageDeviceExecutionHints = ImageDeviceExecutionHints(localProfileKnown = true)
    ) = ImageExecutionProfileResolverInput(
        modelFingerprint = fingerprint,
        runtime = runtime,
        family = family,
        recommendationId = recommendationId,
        recommendationRevision = "test-revision",
        manifestProfile = manifestProfile,
        manifestBehavior = manifestBehavior,
        sidecar = sidecar,
        capabilityDiscovery = capabilityDiscovery,
        recommendationEvidence = recommendationEvidence,
        userOverrides = userOverrides,
        deviceHints = deviceHints
    )

    private fun pinnedRecommendationEvidence(recommendationId: String): ImageRecommendationEvidence {
        val model = ModelScopeClient().recommendedModels().single { it.id == recommendationId }
        val bundle = requireNotNull(model.imageEngineBundle)
        val primary = bundle.requiredComponents.single { component ->
            component.role == ImageEngineBundleComponentRole.DIFFUSION
        }
        val bundleRoot = File("bundle-${model.id}")
        val record = LocalImageModelRecord(
            displayName = model.title,
            path = File(bundleRoot, "unet.bin").path,
            fileName = "unet.bin",
            sizeBytes = 1L,
            sha256 = model.id.sha256TestFingerprint(),
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SD15,
            bundleRoot = bundleRoot.path
        )
        val manifest = JSONObject()
            .put("recommendationId", model.id)
            .put("id", bundle.id)
            .put(
                "components",
                JSONArray().put(
                    JSONObject()
                        .put("role", "DIFFUSION")
                        .put("sourceRepo", primary.repoId)
                        .put("sourcePath", "${primary.fileName}!/unet.bin")
                        .put("path", "unet.bin")
                )
            )
        return localImageRecommendationEvidence(record, bundleRoot, manifest)
    }

    private fun String.sha256TestFingerprint(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun expectResolutionFailure(block: () -> Unit): ImageProfileResolutionException {
        return try {
            block()
            throw AssertionError("Expected profile resolution to fail.")
        } catch (error: ImageProfileResolutionException) {
            error
        }
    }

    private fun ImageResolvedExecution.toNativeEffective() = ImageNativeEffectiveExecution(
        profileId = profileId,
        profileRevision = profileRevision,
        modelFingerprint = modelFingerprint,
        runtime = runtime,
        scheduler = scheduler,
        predictionType = predictionType,
        steps = steps,
        timetableCount = timetableCount,
        unetExecutionCount = unetExecutionCount,
        cfgScale = cfgScale,
        useCfg = useCfg,
        unconditionalBranch = unconditionalBranch,
        tokenizerBackend = tokenizerBackend,
        tokenCount = tokenCount,
        promptWeightingSupported = promptWeightingSupported,
        promptWeightingApplied = false,
        positiveWeightedTokenCount = 0,
        negativeWeightedTokenCount = 0,
        promptWeightFingerprint = "c".repeat(64),
        embeddingDiskDataType = embeddingDiskDataType,
        vaeScalingLocation = vaeScalingLocation,
        vaeScalingFactor = vaeScalingFactor,
        width = width,
        height = height,
        seed = seed,
        graphName = graphName,
        fallback = fallback
    )

    private companion object {
        val FINGERPRINT: String = "a".repeat(64)
        val OTHER_FINGERPRINT: String = "b".repeat(64)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid execution selection.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

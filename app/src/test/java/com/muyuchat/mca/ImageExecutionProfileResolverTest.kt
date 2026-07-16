package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExecutionProfileResolverTest {
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
    fun `pinned image packages retain their actual conditioning storage contracts`() {
        listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228"
        ).forEach { id ->
            val profile = resolve(id).profile
            assertEquals(ImageEmbeddingDiskDataType.FP16, profile.conditioning.diskDataType)
            assertEquals(ImageEmbeddingConversionStrategy.NONE, profile.conditioning.conversionStrategy)
        }

        val legacyFp32 = resolve("meinamix_sd15_qnn228").profile
        assertEquals(ImageEmbeddingDiskDataType.FP32, legacyFp32.conditioning.diskDataType)
        assertEquals(
            ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
            legacyFp32.conditioning.conversionStrategy
        )

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
        assertEquals(ImageProfileSource.CAPABILITY_DISCOVERY, resolution.profile.provenance.primarySource)
        assertEquals(listOf(ImageProfileSource.CAPABILITY_DISCOVERY), resolution.sourceChain)
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
    fun `explicit user values are final and explicit blank negative prompt is preserved`() {
        val resolution = ImageExecutionProfileResolver.resolve(
            input(
                recommendationId = "cyberrealistic_sd15_qnn228",
                userOverrides = ImageGenerationOverrides(
                    expectedProfileId = "community.sd15.qnn228",
                    expectedProfileRevision = 1,
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
        assertEquals(ImageProfileSource.USER_OVERRIDE, resolution.sourceChain.last())
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
    fun `task profiles declare concrete input capabilities`() {
        val control = resolve("qualcomm_controlnet_canny_gen5_qnn").profile
        assertEquals(ImageTask.CONTROL_IMAGE, control.task)
        assertTrue(control.capabilities.requiresControlImage)
        assertTrue(control.graph.controlNet != null)

        val edit = resolve("mnn_sana_edit_v2").profile
        assertEquals(ImageTask.IMAGE_EDIT, edit.task)
        assertTrue(edit.capabilities.requiresInputImage)
        assertTrue(edit.capabilities.supportsMask)
    }

    private fun resolve(recommendationId: String): ImageExecutionProfileResolution =
        ImageExecutionProfileResolver.resolve(input(recommendationId = recommendationId))

    private fun input(
        recommendationId: String?,
        fingerprint: String = FINGERPRINT,
        runtime: LocalImageRuntime = LocalImageRuntime.QNN_HTP,
        family: LocalImageModelFamily = LocalImageModelFamily.SD15,
        manifestProfile: ImageExecutionProfile? = null,
        sidecar: ImageProfileSidecar? = null,
        capabilityDiscovery: ImageCapabilityDiscovery? = null,
        userOverrides: ImageGenerationOverrides = ImageGenerationOverrides(),
        deviceHints: ImageDeviceExecutionHints = ImageDeviceExecutionHints(localProfileKnown = true)
    ) = ImageExecutionProfileResolverInput(
        modelFingerprint = fingerprint,
        runtime = runtime,
        family = family,
        recommendationId = recommendationId,
        recommendationRevision = "test-revision",
        manifestProfile = manifestProfile,
        sidecar = sidecar,
        capabilityDiscovery = capabilityDiscovery,
        userOverrides = userOverrides,
        deviceHints = deviceHints
    )

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
}

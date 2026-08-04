package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiPreviewMode
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageUiCapabilitiesTest {
    @Test
    fun productBatchMaximumExpandsOnlyCoordinatorBackedRuntimes() {
        assertEquals(8, productImageBatchCountForUi(LocalImageRuntime.QNN_HTP, 1))
        assertEquals(8, productImageBatchCountForUi(LocalImageRuntime.MNN_DIFFUSION, 1))
        assertEquals(1, productImageBatchCountForUi(LocalImageRuntime.STABLE_DIFFUSION_CPP, 1))
        assertEquals(4, productImageBatchCountForUi(LocalImageRuntime.STABLE_DIFFUSION_CPP, 4))
        assertEquals(1, productImageBatchCountForUi(LocalImageRuntime.ONNX_RUNTIME, 1))
        assertEquals(1, productImageBatchCountForUi(LocalImageRuntime.CUSTOM, 1))
    }

    @Test
    fun livePreviewCapabilityFollowsRuntimeWorkerTopologyAndTaskOnly() {
        data class Expected(
            val runtime: LocalImageRuntime,
            val workerStrategy: ImageWorkerStrategy?,
            val task: ImageTask?,
            val previewMode: ImageGenerationUiPreviewMode?,
            val defaultPreviewInterval: Int
        )

        listOf(
            Expected(
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                ImageWorkerStrategy.SPLIT_UNET_VAE,
                ImageTask.IMAGE_EDIT,
                ImageGenerationUiPreviewMode.PROJECTION,
                1
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE,
                ImageGenerationUiPreviewMode.VAE,
                4
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE,
                ImageGenerationUiPreviewMode.VAE,
                4
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.CONTROL_IMAGE,
                ImageGenerationUiPreviewMode.VAE,
                5
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                ImageWorkerStrategy.SPLIT_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE,
                null,
                0
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                ImageWorkerStrategy.DEDICATED_WORKER,
                ImageTask.TEXT_TO_IMAGE,
                null,
                0
            ),
            Expected(
                LocalImageRuntime.QNN_HTP,
                null,
                null,
                null,
                0
            ),
            Expected(
                LocalImageRuntime.MNN_DIFFUSION,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE,
                null,
                0
            ),
            Expected(
                LocalImageRuntime.ONNX_RUNTIME,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.CONTROL_IMAGE,
                null,
                0
            ),
            Expected(
                LocalImageRuntime.CUSTOM,
                ImageWorkerStrategy.IN_PROCESS,
                ImageTask.TEXT_TO_IMAGE,
                null,
                0
            )
        ).forEach { expected ->
            val actual = localImagePreviewTopologyForUi(
                runtime = expected.runtime,
                task = expected.task,
                hasSharedQnnVaePreviewTopology = expected.runtime == LocalImageRuntime.QNN_HTP &&
                    expected.workerStrategy in setOf(
                        ImageWorkerStrategy.SHARED_UNET_VAE,
                        ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
                    )
            )

            assertEquals(expected.previewMode, actual.previewMode)
            assertEquals(expected.defaultPreviewInterval, actual.defaultPreviewInterval)
            assertEquals(expected.previewMode != null, actual.supportsLivePreview)
        }
    }

    @Test
    fun sevenSharedQnnCatalogShapesUseVaeWithoutModelIdAdmission() {
        data class SharedShape(
            val family: LocalImageModelFamily,
            val variant: ImageModelVariant,
            val workerStrategy: ImageWorkerStrategy,
            val task: ImageTask
        )

        val sharedShapes = listOf(
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.STANDARD,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.HYPER,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.STANDARD,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.LEGACY_FP32,
                ImageWorkerStrategy.SHARED_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.STANDARD,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD21,
                ImageModelVariant.SD21,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.TEXT_TO_IMAGE
            ),
            SharedShape(
                LocalImageModelFamily.SD15,
                ImageModelVariant.CONTROLNET_CANNY,
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
                ImageTask.CONTROL_IMAGE
            )
        )

        assertEquals(7, sharedShapes.size)
        sharedShapes.forEach { shape ->
            val actual = localImagePreviewTopologyForUi(
                runtime = LocalImageRuntime.QNN_HTP,
                task = shape.task,
                hasSharedQnnVaePreviewTopology = shape.workerStrategy in setOf(
                    ImageWorkerStrategy.SHARED_UNET_VAE,
                    ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
                )
            )

            val description = "${shape.family}/${shape.variant}/${shape.workerStrategy}/${shape.task}"
            assertEquals(description, ImageGenerationUiPreviewMode.VAE, actual.previewMode)
            assertEquals(
                description,
                if (shape.task == ImageTask.CONTROL_IMAGE) 5 else 4,
                actual.defaultPreviewInterval
            )
        }
    }

    @Test
    fun qnnControlPackageOnlyExposesControlMode() {
        val root = Files.createTempDirectory("qnn-control-ui").toFile()
        try {
            val primary = File(root, "controlnet.bin").apply { writeBytes(byteArrayOf(1)) }
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put("recommendationId", "qualcomm_controlnet_canny_gen5_qnn")
                    .put("task", "CONTROL_IMAGE")
                    .toString(),
                Charsets.UTF_8
            )
            val model = record(root, primary, LocalImageRuntime.QNN_HTP, LocalImageModelFamily.SD15)

            assertEquals(
                setOf(ImageGenerationUiTaskMode.CONTROL),
                model.supportedImageTaskModesForUi()
            )
            assertEquals(true, model.supportsNegativePromptForUi())
            val capabilities = model.imageCapabilitiesForUi()
            assertEquals(false, capabilities.supportsClipSkip)
            assertEquals(false, capabilities.supportsVaeTiling)
            assertEquals(true, capabilities.supportsLivePreview)
            assertEquals(ImageGenerationUiPreviewMode.VAE, capabilities.previewMode)
            assertEquals(5, capabilities.defaultPreviewInterval)
            assertEquals(false, capabilities.supportsLora)
            assertEquals(1, capabilities.nativeMaxBatchCount)
            assertEquals(8, capabilities.maxBatchCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sanaEditPackageDoesNotAdvertiseUnsupportedMaskModes() {
        val root = Files.createTempDirectory("sana-edit-ui").toFile()
        try {
            val primary = File(root, "transformer.mnn").apply { writeBytes(byteArrayOf(1)) }
            writeRecommendationManifest(root, "mnn_sana_edit_v2")
            val model = record(root, primary, LocalImageRuntime.MNN_DIFFUSION, LocalImageModelFamily.SANA)
            val defaults = model.executionDefaultsForUi()

            assertEquals(
                setOf(ImageGenerationUiTaskMode.EDIT),
                model.supportedImageTaskModesForUi()
            )
            assertEquals(true, model.supportsNegativePromptForUi())
            assertEquals("flow_match", defaults.sampler)
            assertEquals(listOf("flow_match"), defaults.supportedSamplers)
            val capabilities = model.imageCapabilitiesForUi()
            assertEquals(false, capabilities.supportsClipSkip)
            assertEquals(false, capabilities.supportsVaeTiling)
            assertEquals(false, capabilities.supportsLivePreview)
            assertNull(capabilities.previewMode)
            assertEquals(0, capabilities.defaultPreviewInterval)
            assertEquals(false, capabilities.supportsLora)
            assertEquals(1, capabilities.nativeMaxBatchCount)
            assertEquals(8, capabilities.maxBatchCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun distilledConditionalOnlyFamiliesDoNotOfferUnusedNegativePrompt() {
        val root = Files.createTempDirectory("distilled-negative-ui").toFile()
        try {
            val primary = File(root, "model.gguf").apply { writeBytes(byteArrayOf(1)) }
            val model = record(
                root,
                primary,
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                LocalImageModelFamily.FLUX
            )

            assertEquals(false, model.supportsNegativePromptForUi())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedProfileCapabilityOverridesFamilyNegativePromptFallback() {
        val root = Files.createTempDirectory("declared-negative-ui").toFile()
        try {
            val primary = File(root, "unet.bin").apply { writeBytes(byteArrayOf(1)) }
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put(
                        "executionProfile",
                        JSONObject().put(
                            "capabilities",
                            JSONObject().put("supportsNegativePrompt", false)
                        )
                    )
                    .toString(),
                Charsets.UTF_8
            )
            val model = record(root, primary, LocalImageRuntime.QNN_HTP, LocalImageModelFamily.SDXL)

            assertEquals(false, model.supportsNegativePromptForUi())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installedProfileDrivesEditableUiExecutionDefaults() {
        val root = Files.createTempDirectory("image-ui-defaults").toFile()
        try {
            val primary = File(root, "model.gguf").apply { writeBytes(byteArrayOf(1)) }
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put(
                        "executionProfile",
                        JSONObject()
                            .put(
                                "defaults",
                                JSONObject()
                                    .put("width", 1024)
                                    .put("height", 768)
                                    .put("steps", 28)
                                    .put("cfgScale", 5.5)
                                    .put("seed", 77)
                            )
                            .put(
                                "scheduler",
                                JSONObject()
                                    .put("algorithm", "DPMPP_2M")
                                    .put("minSteps", 10)
                                    .put("maxSteps", 50)
                            )
                            .put(
                                "capabilities",
                                JSONObject()
                                    .put("minWidth", 256)
                                    .put("maxWidth", 1536)
                                    .put("minHeight", 256)
                                    .put("maxHeight", 1536)
                                    .put("widthMultiple", 64)
                                    .put("heightMultiple", 64)
                                    .put(
                                        "supportedSchedulers",
                                        org.json.JSONArray().put("DPMPP_2M").put("EULER_A")
                                    )
                            )
                    )
                    .toString(),
                Charsets.UTF_8
            )
            val defaults = record(
                root,
                primary,
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                LocalImageModelFamily.SDXL
            ).executionDefaultsForUi()

            assertEquals(1024, defaults.width)
            assertEquals(768, defaults.height)
            assertEquals(28, defaults.steps)
            assertEquals(10, defaults.minSteps)
            assertEquals(50, defaults.maxSteps)
            assertEquals(5.5, defaults.cfgScale, 0.0)
            assertEquals(77, defaults.seed)
            assertEquals("dpmpp_2m", defaults.sampler)
            assertEquals(listOf("dpmpp_2m", "euler_a"), defaults.supportedSamplers)
            assertEquals(true, defaults.supportsCustomSize)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stableDiffusionCheckpointExposesNativeImageInputModes() {
        val root = Files.createTempDirectory("sdcpp-ui").toFile()
        try {
            val primary = File(root, "model.safetensors").apply { writeBytes(byteArrayOf(1)) }
            val model = record(
                root,
                primary,
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                LocalImageModelFamily.SD15
            )

            assertEquals(
                setOf(
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                    ImageGenerationUiTaskMode.IMG2IMG,
                    ImageGenerationUiTaskMode.INPAINT
                ),
                model.supportedImageTaskModesForUi()
            )
            val capabilities = model.imageCapabilitiesForUi()
            assertEquals(true, capabilities.supportsClipSkip)
            assertEquals(true, capabilities.supportsVaeTiling)
            assertEquals(true, capabilities.supportsLivePreview)
            assertEquals(ImageGenerationUiPreviewMode.PROJECTION, capabilities.previewMode)
            assertEquals(1, capabilities.defaultPreviewInterval)
            assertEquals(true, capabilities.supportsLora)
            assertEquals(8, capabilities.nativeMaxBatchCount)
            assertEquals(8, capabilities.maxBatchCount)
            assertNull(capabilities.readinessError)
            assertNull(model.localImageReadinessForUi(null, capabilities))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedDeclaredProfileIsVisibleAndCannotSilentlyUseLegacyDefaults() {
        val root = Files.createTempDirectory("broken-image-profile-ui").toFile()
        try {
            val primary = File(root, "model.gguf").apply { writeBytes(byteArrayOf(1)) }
            File(root, "scheduler").mkdirs()
            File(root, "scheduler/scheduler_config.json").writeText("{}", Charsets.UTF_8)
            val model = record(
                root,
                primary,
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                LocalImageModelFamily.SD15
            )

            val capabilities = model.imageCapabilitiesForUi()

            assertNotNull(capabilities.readinessError)
            assertTrue(capabilities.readinessError.orEmpty().contains("执行配置无效"))
            assertEquals(
                capabilities.readinessError,
                model.localImageReadinessForUi(null, capabilities)
            )
            assertEquals("配置错误", model.localImageReadinessLabelForUi(null, capabilities))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gen5ProfilesSupplyTheirRealSchedulersWithoutPersistedExecutionProfile() {
        listOf(
            Triple("qualcomm_sd15_gen5_qnn", LocalImageModelFamily.SD15, "euler"),
            Triple("qualcomm_sd21_gen5_qnn", LocalImageModelFamily.SD21, "ddim")
        ).forEach { (recommendationId, family, sampler) ->
            val root = Files.createTempDirectory("gen5-ui-defaults").toFile()
            try {
                val model = recommendedRecord(
                    root = root,
                    recommendationId = recommendationId,
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = family
                )
                val defaults = model.executionDefaultsForUi()

                assertEquals(sampler, defaults.sampler)
                assertEquals(listOf(sampler), defaults.supportedSamplers)
                assertEquals(1, defaults.minSteps)
                assertEquals(100, defaults.maxSteps)
                assertEquals(setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE), model.supportedImageTaskModesForUi())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun everySplitSdxlRecommendationExposesImageInputsWithoutUnsupportedLivePreview() {
        listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        ).forEach { recommendationId ->
            val root = Files.createTempDirectory("split-sdxl-ui").toFile()
            try {
                val model = recommendedRecord(
                    root = root,
                    recommendationId = recommendationId,
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SDXL
                )
                val profile = resolveLocalImageExecutionProfile(
                    model = model,
                    options = LocalImageGenerationOptions(),
                    bundleRoot = root
                ).profile
                val capabilities = model.imageCapabilitiesForUi()

                assertEquals(
                    "$recommendationId worker strategy",
                    ImageWorkerStrategy.SPLIT_UNET_VAE,
                    profile.graph.workerStrategy
                )
                assertEquals(
                    "$recommendationId VAE encoder",
                    "vae_encoder.bin",
                    profile.graph.vaeEncoder?.relativePath
                )
                assertEquals(
                    "$recommendationId task modes",
                    setOf(
                        ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                        ImageGenerationUiTaskMode.IMG2IMG,
                        ImageGenerationUiTaskMode.INPAINT
                    ),
                    capabilities.supportedTaskModes
                )
                assertEquals("$recommendationId preview", false, capabilities.supportsLivePreview)
                assertNull("$recommendationId preview mode", capabilities.previewMode)
                assertEquals("$recommendationId preview interval", 0, capabilities.defaultPreviewInterval)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun everySharedSd15RecommendationExposesTopologyBackedImg2ImgAndInpaint() {
        val recommendationIds = listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        )
        recommendationIds.forEach { recommendationId ->
            val root = Files.createTempDirectory("shared-sd15-img2img-ui").toFile()
            try {
                val model = recommendedRecord(
                    root = root,
                    recommendationId = recommendationId,
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15
                )
                val profile = resolveLocalImageExecutionProfile(
                    model = model,
                    options = LocalImageGenerationOptions(),
                    bundleRoot = root
                ).profile
                val capabilities = model.imageCapabilitiesForUi()

                assertEquals(
                    "$recommendationId worker strategy",
                    ImageWorkerStrategy.SHARED_UNET_VAE,
                    profile.graph.workerStrategy
                )
                assertEquals(
                    "$recommendationId VAE encoder",
                    "vae_encoder.bin",
                    profile.graph.vaeEncoder?.relativePath
                )
                assertTrue("$recommendationId executable topology", profile.hasSharedQnnImg2ImgTopology())
                assertTrue("$recommendationId inpaint topology", profile.hasExecutableQnnInpaintTopology())
                assertEquals(
                    "$recommendationId inpaint mode",
                    QnnInpaintMaskTopology.LATENT_BLEND_4,
                    profile.inspectQnnInpaintTopology().topology
                )
                assertEquals(
                    "$recommendationId task modes",
                    setOf(
                        ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                        ImageGenerationUiTaskMode.IMG2IMG,
                        ImageGenerationUiTaskMode.INPAINT
                    ),
                    capabilities.supportedTaskModes
                )
                assertEquals("$recommendationId native batch", 1, capabilities.nativeMaxBatchCount)
                assertEquals("$recommendationId product batch", 8, capabilities.maxBatchCount)
                assertEquals(
                    "$recommendationId global samplers",
                    listOf("dpmpp_2m", "euler", "pndm"),
                    capabilities.executionDefaults.supportedSamplers
                )
                assertEquals(
                    "$recommendationId img2img samplers",
                    listOf("dpmpp_2m", "euler"),
                    capabilities.executionDefaults.img2ImgSupportedSamplers
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun onlyPndmTopologyDoesNotAdvertiseQnnImg2Img() {
        val root = Files.createTempDirectory("only-pndm-qnn-ui").toFile()
        try {
            val model = recommendedRecord(
                root = root,
                recommendationId = "cyberrealistic_sd15_qnn228",
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            )
            val resolvedProfile = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ).profile
            val profile = resolvedProfile.copy(
                profileId = "unknown.only-pndm.shared-qnn",
                modelFingerprint = "e".repeat(64),
                capabilities = resolvedProfile.capabilities.copy(
                    supportedSchedulers = setOf(ImageSchedulerAlgorithm.PNDM_PLMS)
                )
            )

            assertTrue(profile.hasExecutableQnnImg2ImgTopology())
            assertTrue(profile.hasExecutableQnnInpaintTopology())
            assertFalse(profile.exposesQnnImg2ImgForUi())
            assertFalse(profile.exposesQnnInpaintForUi())
            assertTrue(
                ImageSchedulerAlgorithm.PNDM_PLMS in
                    profile.supportedSchedulersForProductTask(LocalImageTaskMode.TEXT_TO_IMAGE)
            )
            assertTrue(
                profile.supportedSchedulersForProductTask(LocalImageTaskMode.IMG2IMG).isEmpty()
            )
            assertTrue(profile.supportedQnnInpaintSchedulers().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericSharedQnnPublishesTaskAwareSamplersWithoutDeviceAdmission() {
        val root = Files.createTempDirectory("generic-shared-qnn-ui").toFile()
        try {
            val primary = File(root, "unet.bin").apply { writeBytes(byteArrayOf(1)) }
            listOf("text_encoder.bin", "vae.bin", "vae_encoder.bin").forEach { name ->
                File(root, name).writeBytes(byteArrayOf(1))
            }
            val model = record(
                root = root,
                primary = primary,
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            )

            val capabilities = model.imageCapabilitiesForUi()
            assertEquals(null, capabilities.readinessError)
            assertTrue(ImageGenerationUiTaskMode.IMG2IMG in capabilities.supportedTaskModes)
            assertTrue(ImageGenerationUiTaskMode.INPAINT in capabilities.supportedTaskModes)
            assertEquals("pndm", capabilities.executionDefaults.sampler)
            assertTrue("pndm" in capabilities.executionDefaults.supportedSamplers)
            assertFalse("pndm" in capabilities.executionDefaults.img2ImgSupportedSamplers)
            assertEquals(
                "dpmpp_2m",
                capabilities.executionDefaults.img2ImgSupportedSamplers.first()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun distilledAndTunedProfilesSupplyExactUiDefaultsWithoutPersistedExecutionProfile() {
        data class Expected(
            val recommendationId: String,
            val family: LocalImageModelFamily,
            val steps: Int,
            val cfgScale: Double,
            val supportsNegativePrompt: Boolean,
            val supportedSamplers: List<String>,
            val supportedTaskModes: Set<ImageGenerationUiTaskMode>
        )

        listOf(
            Expected(
                "realismsdxl_dmd2_alt_qnn228",
                LocalImageModelFamily.SDXL,
                4,
                1.0,
                false,
                listOf("dpmpp_2m", "euler"),
                setOf(
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                    ImageGenerationUiTaskMode.IMG2IMG,
                    ImageGenerationUiTaskMode.INPAINT
                )
            ),
            Expected(
                "realisticvisionhyper_sd15_qnn228",
                LocalImageModelFamily.SD15,
                8,
                2.0,
                true,
                listOf("dpmpp_2m", "euler", "pndm"),
                setOf(
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                    ImageGenerationUiTaskMode.IMG2IMG,
                    ImageGenerationUiTaskMode.INPAINT
                )
            ),
            Expected(
                "animagine_xl_v4_qnn228",
                LocalImageModelFamily.SDXL,
                28,
                5.0,
                true,
                listOf("dpmpp_2m", "euler"),
                setOf(
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                    ImageGenerationUiTaskMode.IMG2IMG,
                    ImageGenerationUiTaskMode.INPAINT
                )
            )
        ).forEach { expected ->
            val root = Files.createTempDirectory("tuned-image-ui-defaults").toFile()
            try {
                val model = recommendedRecord(
                    root = root,
                    recommendationId = expected.recommendationId,
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = expected.family
                )
                val defaults = model.executionDefaultsForUi()

                assertEquals(expected.steps, defaults.steps)
                assertEquals(expected.cfgScale, defaults.cfgScale, 0.0)
                assertEquals(1, defaults.minSteps)
                assertEquals(50, defaults.maxSteps)
                assertEquals("dpmpp_2m", defaults.sampler)
                assertEquals(expected.supportedSamplers, defaults.supportedSamplers)
                assertEquals(expected.supportsNegativePrompt, model.supportsNegativePromptForUi())
                assertEquals(expected.supportedTaskModes, model.supportedImageTaskModesForUi())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun everyRecommendedFlowFamilyOffersStableDiffusionImageInputs() {
        data class Expected(
            val recommendationId: String,
            val family: LocalImageModelFamily,
            val steps: Int,
            val cfgScale: Double,
            val supportsNegativePrompt: Boolean
        )

        listOf(
            Expected("z_image_turbo_q4", LocalImageModelFamily.Z_IMAGE, 8, 1.0, false),
            Expected("flux2_klein_4b_q4", LocalImageModelFamily.FLUX, 4, 1.0, false),
            Expected("qwen_image_2512_q2", LocalImageModelFamily.QWEN_IMAGE, 40, 2.5, true),
            Expected("longcat_image_q4", LocalImageModelFamily.LONGCAT_IMAGE, 20, 5.0, true)
        ).forEach { expected ->
            val root = Files.createTempDirectory("flow-image-ui-defaults").toFile()
            try {
                val model = recommendedRecord(
                    root = root,
                    recommendationId = expected.recommendationId,
                    runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family = expected.family,
                    primaryName = "model.gguf"
                )
                val defaults = model.executionDefaultsForUi()

                assertEquals(expected.steps, defaults.steps)
                assertEquals(expected.cfgScale, defaults.cfgScale, 0.0)
                assertEquals("flow_match", defaults.sampler)
                assertEquals(listOf("flow_match"), defaults.supportedSamplers)
                assertEquals(expected.supportsNegativePrompt, model.supportsNegativePromptForUi())
                val capabilities = model.imageCapabilitiesForUi()
                assertEquals(false, capabilities.supportsClipSkip)
                assertEquals(true, capabilities.supportsVaeTiling)
                assertEquals(true, capabilities.supportsLivePreview)
                assertEquals(ImageGenerationUiPreviewMode.PROJECTION, capabilities.previewMode)
                assertEquals(1, capabilities.defaultPreviewInterval)
                assertEquals(true, capabilities.supportsLora)
                assertEquals(8, capabilities.nativeMaxBatchCount)
                assertEquals(8, capabilities.maxBatchCount)
                assertEquals(
                    setOf(
                        ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                        ImageGenerationUiTaskMode.IMG2IMG,
                        ImageGenerationUiTaskMode.INPAINT
                    ),
                    model.supportedImageTaskModesForUi()
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun genericFlowFamilyUiFallbacksKeepNativeWorkflowStepCounts() {
        mapOf(
            LocalImageModelFamily.Z_IMAGE to 8,
            LocalImageModelFamily.QWEN_IMAGE to 40,
            LocalImageModelFamily.LONGCAT_IMAGE to 20
        ).forEach { (family, expectedSteps) ->
            val root = Files.createTempDirectory("generic-flow-image-ui").toFile()
            try {
                val primary = File(root, "model.gguf").apply { writeBytes(byteArrayOf(1)) }
                val defaults = record(
                    root,
                    primary,
                    LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family
                ).executionDefaultsForUi()

                assertEquals("$family generic UI step count", expectedSteps, defaults.steps)
                assertEquals("flow_match", defaults.sampler)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun concreteStableDiffusionControlComponentAddsControlToImageInputCapabilities() {
        val root = Files.createTempDirectory("sdcpp-control-ui").toFile()
        try {
            val primary = File(root, "model.safetensors").apply { writeBytes(byteArrayOf(1)) }
            File(root, "controlnet.safetensors").writeBytes(byteArrayOf(1))
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put(
                        "components",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "DIFFUSION")
                                    .put("path", primary.name)
                                    .put("required", true)
                            )
                            .put(
                                JSONObject()
                                    .put("role", "CONTROL_NET")
                                    .put("path", "controlnet.safetensors")
                                    .put("required", false)
                            )
                    )
                    .toString(),
                Charsets.UTF_8
            )
            val model = record(
                root,
                primary,
                LocalImageRuntime.STABLE_DIFFUSION_CPP,
                LocalImageModelFamily.SD15
            )

            assertEquals(
                setOf(
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
                    ImageGenerationUiTaskMode.IMG2IMG,
                    ImageGenerationUiTaskMode.INPAINT,
                    ImageGenerationUiTaskMode.CONTROL
                ),
                model.supportedImageTaskModesForUi()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolverFailureKeepsLegacyTaskAndSurfacesInvalidDeclaredProfile() {
        val root = Files.createTempDirectory("legacy-image-task-ui").toFile()
        try {
            val primary = File(root, "legacy.bin").apply { writeBytes(byteArrayOf(1)) }
            File(root, "manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put("task", "IMAGE_EDIT")
                    .put("executionProfile", JSONObject().put("defaults", JSONObject()))
                    .toString(),
                Charsets.UTF_8
            )
            val model = record(root, primary, LocalImageRuntime.CUSTOM, LocalImageModelFamily.CUSTOM)
            val capabilities = model.imageCapabilitiesForUi()

            assertEquals(setOf(ImageGenerationUiTaskMode.EDIT), capabilities.supportedTaskModes)
            assertTrue(capabilities.readinessError.orEmpty().contains("PROFILE_FORMAT_INVALID"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun recommendedRecord(
        root: File,
        recommendationId: String,
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily,
        primaryName: String = "primary.bin"
    ): LocalImageModelRecord {
        val primary = File(root, primaryName).apply { writeBytes(byteArrayOf(1)) }
        writeRecommendationManifest(root, recommendationId)
        return record(root, primary, runtime, family)
    }

    private fun writeRecommendationManifest(root: File, recommendationId: String) {
        File(root, "manifest.json").writeText(
            JSONObject()
                .put("schema", "mca.image_engine.bundle.v1")
                .put("recommendationId", recommendationId)
                .toString(),
            Charsets.UTF_8
        )
    }

    private fun record(
        root: File,
        primary: File,
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily
    ) = LocalImageModelRecord(
        displayName = primary.nameWithoutExtension,
        path = primary.absolutePath,
        fileName = primary.name,
        sizeBytes = primary.length(),
        sha256 = "0".repeat(64),
        runtime = runtime,
        family = family,
        bundleRoot = root.absolutePath
    )
}

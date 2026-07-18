package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageUiCapabilitiesTest {
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
                            .put("scheduler", JSONObject().put("algorithm", "DPMPP_2M"))
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
                assertEquals(setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE), model.supportedImageTaskModesForUi())
            } finally {
                root.deleteRecursively()
            }
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
            val supportedSamplers: List<String>
        )

        listOf(
            Expected(
                "realismsdxl_dmd2_alt_qnn228",
                LocalImageModelFamily.SDXL,
                4,
                1.0,
                false,
                listOf("dpmpp_2m", "euler", "lcm")
            ),
            Expected(
                "realisticvisionhyper_sd15_qnn228",
                LocalImageModelFamily.SD15,
                8,
                2.0,
                true,
                listOf("dpmpp_2m", "euler", "pndm")
            ),
            Expected(
                "animagine_xl_v4_qnn228",
                LocalImageModelFamily.SDXL,
                28,
                5.0,
                true,
                listOf("dpmpp_2m", "euler", "lcm")
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
                assertEquals("dpmpp_2m", defaults.sampler)
                assertEquals(expected.supportedSamplers, defaults.supportedSamplers)
                assertEquals(expected.supportsNegativePrompt, model.supportsNegativePromptForUi())
                assertEquals(setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE), model.supportedImageTaskModesForUi())
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

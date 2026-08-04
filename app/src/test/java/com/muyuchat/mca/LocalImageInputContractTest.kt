package com.muyuchat.mca

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalImageInputContractTest {
    @Test
    fun `runtime validation defers textual inversion admission to the resolved graph topology`() {
        val options = LocalImageGenerationOptions(
            textualInversionIds = listOf("123e4567-e89b-12d3-a456-426614174000")
        )

        LocalImageRuntime.entries.forEach { runtime ->
            validateLocalImageRuntimeProductOptions(runtime, options)
        }
    }

    @Test
    fun `portable history may omit source images without weakening product validation`() {
        val restoredHistoryDraft = LocalImageInputDraft(
            taskMode = LocalImageTaskMode.INPAINT,
            strength = 0.6
        )

        restoredHistoryDraft.validateForHistory()
        try {
            restoredHistoryDraft.validate()
            fail("Expected live product input validation to require source images")
        } catch (_: IllegalArgumentException) {
            // Expected: backup history is readable, but cannot execute until inputs are selected again.
        }
    }

    @Test
    fun `native params use canonical role paths and strict controls`() {
        val input = prepared("/cache/worker/input.img", "a", width = 768, height = 512)
        val options = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.IMG2IMG,
            inputImage = input,
            strength = 0.65,
            clipSkip = 1,
            batchCount = 1,
            vaeTiling = LocalImageVaeTilingOptions(512, 0.5)
        )

        val params = options.putProductInputNativeParams(JSONObject())

        assertEquals("img2img", params.getString("taskMode"))
        assertEquals(input.path, params.getString("inputImagePath"))
        assertEquals(input.sha256, params.getString("inputImageSha256"))
        assertEquals(0.65, params.getDouble("strength"), 0.0)
        assertEquals(1, params.getInt("clipSkip"))
        assertFalse(params.has("maskImagePath"))
    }

    @Test
    fun `zero denoise strength remains img2img and is not rejected`() {
        val input = prepared("/cache/worker/zero-strength.img", "0")
        val options = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.IMG2IMG,
            inputImage = input,
            strength = 0.0
        )

        options.validateProductInputContract()
        assertEquals(
            0.0,
            options.putProductInputNativeParams(JSONObject()).getDouble("strength"),
            0.0
        )
        assertInvalid {
            options.copy(strength = -0.01).validateProductInputContract()
        }
    }

    @Test
    fun `ultrafix structured request canonically owns every duplicated execution control`() {
        val request = LocalImageUltraFixOptions(
            targetWidth = 1_024,
            targetHeight = 1_024,
            strength = 0.4,
            inversionSteps = 4,
            refinementSteps = 10,
            tileSize = 512,
            overlap = 0.25
        )
        val base = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.IMG2IMG,
            inputImage = prepared("/cache/worker/ultrafix.img", "7", width = 512, height = 384),
            ultraFix = request
        )

        val canonical = base.withCanonicalUltraFixControls()

        assertEquals(request.targetWidth, canonical.width)
        assertEquals(request.targetHeight, canonical.height)
        assertEquals(request.refinementSteps, canonical.steps)
        assertEquals(request.strength, canonical.strength!!, 0.0)
        assertEquals(request.tileSize, canonical.vaeTiling?.tileSize)
        assertEquals(request.overlap, canonical.vaeTiling?.overlap ?: -1.0, 0.0)
        assertEquals(canonical, LocalImageGenerationOptions.fromJson(base.toJson()))

        listOf(
            base.copy(width = 512),
            base.copy(height = 512),
            base.copy(steps = 9),
            base.copy(strength = 0.5),
            base.copy(vaeTiling = LocalImageVaeTilingOptions(256, 0.25)),
            base.copy(vaeTiling = LocalImageVaeTilingOptions(512, 0.5))
        ).forEach { conflicting ->
            assertInvalid { conflicting.withCanonicalUltraFixControls() }
        }
    }

    @Test
    fun `ultrafix denoising tail uses the native Float wire boundary`() {
        val adjacentStrength = 0.4000000000000001

        assertEquals(4, localImageDenoisingTailStepCount(10, 0.4))
        assertEquals(4, localImageDenoisingTailStepCount(10, adjacentStrength))
        LocalImageUltraFixOptions(
            targetWidth = 1_024,
            targetHeight = 1_024,
            strength = adjacentStrength,
            inversionSteps = 4,
            refinementSteps = 10,
            tileSize = 512,
            overlap = 0.25
        )
        assertInvalid {
            LocalImageUltraFixOptions(
                targetWidth = 1_024,
                targetHeight = 1_024,
                strength = adjacentStrength,
                inversionSteps = 5,
                refinementSteps = 10,
                tileSize = 512,
                overlap = 0.25
            )
        }
    }

    @Test
    fun `prepared inputs retain encoded and EXIF oriented dimensions across IPC`() {
        (1..8).forEach { orientation ->
            val expected = if (orientation in 5..8) 480 to 640 else 640 to 480
            assertEquals(expected, localImageOrientedDimensions(640, 480, orientation))
            val input = LocalImagePreparedInput(
                path = "/cache/worker/oriented-$orientation.jpg",
                mimeType = "image/jpeg",
                sha256 = orientation.toString().repeat(64),
                sizeBytes = 1_024L,
                width = 640,
                height = 480,
                orientedWidth = expected.first,
                orientedHeight = expected.second,
                exifOrientation = orientation
            )
            assertEquals(input, LocalImagePreparedInput.fromJson(input.toJson()))
        }

        val rotatedSource = LocalImagePreparedInput(
            path = "/cache/worker/source.jpg",
            mimeType = "image/jpeg",
            sha256 = "a".repeat(64),
            sizeBytes = 1_024L,
            width = 640,
            height = 480,
            orientedWidth = 480,
            orientedHeight = 640,
            exifOrientation = 6
        )
        LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.INPAINT,
            inputImage = rotatedSource,
            maskImage = prepared("/cache/worker/mask.png", "b", width = 480, height = 640),
            strength = 0.5
        ).validateProductInputContract()
    }

    @Test
    fun `invalid role combinations fail but runtime discovery never blocks a valid task`() {
        assertInvalid { LocalImageVaeTilingOptions(512, 0.5001) }
        assertInvalid {
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImage = prepared("/cache/input.img", "a")
            ).validateProductInputContract()
        }
        val control = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.CONTROL,
            controlImage = prepared("/cache/control.img", "c"),
            controlStrength = 0.8
        )
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.QNN_HTP, control)
        validateLocalImageRuntimeProductOptions(
            LocalImageRuntime.MNN_DIFFUSION,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = prepared("/cache/edit.img", "e")
            )
        )
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(
                    taskMode = LocalImageTaskMode.EDIT,
                    inputImage = prepared("/cache/edit-strength.img", "f"),
                    strength = 0.7
                )
            )
            fail("Expected unsupported MNN edit strength")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_edit_strength", error.code)
        }

        validateLocalImageRuntimeProductOptions(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageGenerationOptions(batchCount = 2)
        )
        val preview = LocalImageGenerationOptions(
            preview = LocalImagePreviewOptions(
                interval = 2,
                mode = LocalImagePreviewMode.PROJECTION
            )
        )
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.STABLE_DIFFUSION_CPP, preview)
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.QNN_HTP, preview)
        val qnnPreview = LocalImageGenerationOptions(
            preview = LocalImagePreviewOptions(
                interval = 2,
                mode = LocalImagePreviewMode.VAE
            )
        )
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.QNN_HTP, qnnPreview)
        try {
            validateLocalImageRuntimeProductOptions(LocalImageRuntime.MNN_DIFFUSION, preview)
            fail("Expected unsupported MNN preview")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_preview", error.code)
        }
        assertProductRejected("unsupported_clip_skip") {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.QNN_HTP,
                LocalImageGenerationOptions(clipSkip = 1)
            )
        }
        assertProductRejected("unsupported_vae_tiling") {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(
                    vaeTiling = LocalImageVaeTilingOptions(512, 0.5)
                )
            )
        }
        assertProductRejected("unsupported_lora") {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(loras = listOf(preparedLora()))
            )
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(batchCount = 2)
            )
            fail("Expected unsupported MNN batch")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_batch_count", error.code)
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.QNN_HTP,
                LocalImageGenerationOptions(distilledGuidance = 3.5)
            )
            fail("Expected unsupported QNN distilled guidance")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_distilled_guidance", error.code)
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(flowShift = 3.0)
            )
            fail("Expected unsupported MNN flow shift")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_flow_shift", error.code)
        }
    }

    @Test
    fun `resolved profile rejects advanced controls before unsupported native execution`() {
        val stableClassic = resolvedProfile(
            recommendationId = "sd_turbo_512_experimental",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.SD_TURBO
        )
        validateLocalImageProfileProductOptions(
            stableClassic,
            LocalImageGenerationOptions(
                clipSkip = 1,
                batchCount = 8,
                vaeTiling = LocalImageVaeTilingOptions(512, 0.5),
                preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.PROJECTION)
            )
        )
        validateLocalImageProfileProductOptions(
            stableClassic,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                ultraFix = LocalImageUltraFixOptions(
                    targetWidth = 4_096,
                    targetHeight = 2_048,
                    strength = 0.4,
                    inversionSteps = 4,
                    refinementSteps = 10,
                    tileSize = 512,
                    overlap = 0.25
                )
            )
        )
        validateLocalImageProfileProductOptions(
            stableClassic.copy(
                profileId = "custom.sdcpp.runtime-preview",
                capabilities = stableClassic.capabilities.copy(supportsLivePreview = false)
            ),
            LocalImageGenerationOptions(
                preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.PROJECTION)
            )
        )

        val stableFlow = resolvedProfile(
            recommendationId = "z_image_turbo_q4",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.Z_IMAGE
        )
        assertProductRejected("unsupported_clip_skip") {
            validateLocalImageProfileProductOptions(
                stableFlow,
                LocalImageGenerationOptions(clipSkip = 1)
            )
        }

        val qnn = resolvedProfile(
            recommendationId = "cyberrealistic_sd15_qnn228",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SD15
        )
        assertProductRejected("unsupported_batch_count") {
            validateLocalImageProfileProductOptions(qnn, LocalImageGenerationOptions(batchCount = 2))
        }
        assertProductRejected("unsupported_vae_tiling") {
            validateLocalImageProfileProductOptions(
                qnn,
                LocalImageGenerationOptions(
                    vaeTiling = LocalImageVaeTilingOptions(512, 0.5)
                )
            )
        }
        validateLocalImageProfileProductOptions(
            qnn,
            LocalImageGenerationOptions(
                preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.VAE)
            )
        )
        assertProductRejected("invalid_preview_interval") {
            validateLocalImageProfileProductOptions(
                qnn,
                LocalImageGenerationOptions(
                    preview = LocalImagePreviewOptions(11, LocalImagePreviewMode.VAE)
                )
            )
        }
        val topologyOnlyQnn = qnn.copy(
            profileId = "custom.shared.qnn",
            capabilities = qnn.capabilities.copy(supportsLivePreview = false)
        )
        validateLocalImageProfileProductOptions(
            topologyOnlyQnn,
            LocalImageGenerationOptions(
                preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.VAE)
            )
        )
        assertProductRejected("unsupported_preview") {
            validateLocalImageProfileProductOptions(
                qnn,
                LocalImageGenerationOptions(
                    preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.PROJECTION)
                )
            )
        }

        val splitQnn = resolvedProfile(
            recommendationId = "sdxl_base_qnn228",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SDXL
        )
        assertProductRejected("unsupported_preview") {
            validateLocalImageProfileProductOptions(
                splitQnn,
                LocalImageGenerationOptions(
                    preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.VAE)
                )
            )
        }
        assertProductRejected("unsupported_preview") {
            validateLocalImageProfileProductOptions(
                splitQnn,
                LocalImageGenerationOptions(
                    preview = LocalImagePreviewOptions(2, LocalImagePreviewMode.PROJECTION)
                )
            )
        }
    }

    @Test
    fun `split qnn accepts img2img and inpaint with encoder and rejects unrelated task modes`() {
        val splitQnn = resolvedProfile(
            recommendationId = "sdxl_base_qnn228",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SDXL
        )
        validateLocalImageProfileProductOptions(
            splitQnn,
            LocalImageGenerationOptions(taskMode = LocalImageTaskMode.TEXT_TO_IMAGE)
        )

        val input = prepared("/cache/split-qnn-input.png", "1", width = 1024, height = 1024)
        val mask = prepared("/cache/split-qnn-mask.png", "2", width = 1024, height = 1024)
        val control = prepared("/cache/split-qnn-control.png", "3", width = 1024, height = 1024)
        validateLocalImageProfileProductOptions(
            splitQnn,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input,
                strength = 0.0
            )
        )
        validateLocalImageProfileProductOptions(
            splitQnn,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImage = input,
                maskImage = mask,
                strength = 0.6
            )
        )
        listOf(
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input
            ),
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = control,
                controlStrength = 1.0
            )
        ).forEach { options ->
            assertProductRejected("task_mode_execution_unsupported") {
                validateLocalImageProfileProductOptions(splitQnn, options)
            }
        }

        val sharedControlQnn = resolvedProfile(
            recommendationId = "qualcomm_controlnet_canny_gen5_qnn",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SD15
        )
        validateLocalImageProfileProductOptions(
            sharedControlQnn,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = control,
                controlStrength = 1.0
            )
        )
    }

    @Test
    fun `shared qnn image input admission follows executable topology without id or device gates`() {
        val input = prepared("/cache/shared-qnn-input.png", "4")
        val img2imgOptions = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.IMG2IMG,
            inputImage = input,
            strength = 0.6
        )
        val inpaintOptions = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.INPAINT,
            inputImage = input,
            maskImage = prepared("/cache/shared-qnn-mask.png", "5"),
            strength = 0.6
        )
        val recommendationIds = listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        )
        val representative = recommendationIds.map { recommendationId ->
            resolvedProfile(
                recommendationId = recommendationId,
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            ).also { profile ->
                assertTrue("$recommendationId topology", profile.hasSharedQnnImg2ImgTopology())
                assertTrue("$recommendationId inpaint topology", profile.hasExecutableQnnInpaintTopology())
                assertEquals(
                    QnnInpaintMaskTopology.LATENT_BLEND_4,
                    profile.inspectQnnInpaintTopology().topology
                )
                validateLocalImageProfileProductOptions(profile, img2imgOptions)
                validateLocalImageProfileProductOptions(profile, inpaintOptions)
            }
        }.first()

        val genericSharedTextTopology = representative.copy(
            profileId = "custom.qnn.shared-text.img2img",
            modelFingerprint = "b".repeat(64),
            graph = representative.graph.copy(
                textEncoder = ImageGraphArtifactContract("text_encoder.bin"),
                workerStrategy = ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
            )
        )
        assertTrue(genericSharedTextTopology.hasSharedQnnImg2ImgTopology())
        assertTrue(genericSharedTextTopology.hasExecutableQnnInpaintTopology())
        validateLocalImageProfileProductOptions(genericSharedTextTopology, img2imgOptions)
        validateLocalImageProfileProductOptions(genericSharedTextTopology, inpaintOptions)

        listOf(
            representative.copy(
                profileId = "custom.qnn.no-encoder",
                graph = representative.graph.copy(vaeEncoder = null)
            ),
            representative.copy(
                profileId = "custom.qnn.noncoherent",
                graph = representative.graph.copy(workerStrategy = ImageWorkerStrategy.DEDICATED_WORKER)
            )
        ).forEach { unsupported ->
            assertFalse(unsupported.hasExecutableQnnImg2ImgTopology())
            assertFalse(unsupported.hasExecutableQnnInpaintTopology())
            listOf(img2imgOptions, inpaintOptions).forEach { unsupportedOptions ->
                assertProductRejected("task_mode_execution_unsupported") {
                    validateLocalImageProfileProductOptions(unsupported, unsupportedOptions)
                }
            }
        }

        listOf(
            LocalImageGenerationOptions(taskMode = LocalImageTaskMode.EDIT, inputImage = input),
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = prepared("/cache/shared-qnn-control.png", "6"),
                controlStrength = 1.0
            )
        ).forEach { unsupportedOptions ->
            assertProductRejected("task_mode_execution_unsupported") {
                validateLocalImageProfileProductOptions(representative, unsupportedOptions)
            }
        }
    }

    @Test
    fun `qnn strength conditioned image modes filter pndm while text to image retains it`() {
        val recommendationIds = listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        )
        val profiles = recommendationIds.map { recommendationId ->
            resolvedProfile(
                recommendationId = recommendationId,
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            )
        }
        profiles.forEach { profile ->
            assertTrue(
                profile.capabilities.supportedSchedulers.contains(ImageSchedulerAlgorithm.PNDM_PLMS)
            )
            assertTrue(
                ImageSchedulerAlgorithm.PNDM_PLMS in
                    profile.supportedSchedulersForProductTask(LocalImageTaskMode.TEXT_TO_IMAGE)
            )
            assertFalse(
                ImageSchedulerAlgorithm.PNDM_PLMS in
                    profile.supportedSchedulersForProductTask(LocalImageTaskMode.IMG2IMG)
            )
            assertFalse(
                ImageSchedulerAlgorithm.PNDM_PLMS in
                    profile.supportedSchedulersForProductTask(LocalImageTaskMode.INPAINT)
            )
            validateLocalImageTaskSamplerCapability(
                profile = profile,
                taskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
                requestedScheduler = ImageSchedulerAlgorithm.PNDM_PLMS
            )
            assertProductRejected("unsupported_img2img_sampler") {
                validateLocalImageTaskSamplerCapability(
                    profile = profile,
                    taskMode = LocalImageTaskMode.IMG2IMG,
                    requestedScheduler = ImageSchedulerAlgorithm.PNDM_PLMS
                )
            }
            assertProductRejected("unsupported_inpaint_sampler") {
                validateLocalImageTaskSamplerCapability(
                    profile = profile,
                    taskMode = LocalImageTaskMode.INPAINT,
                    requestedScheduler = ImageSchedulerAlgorithm.PNDM_PLMS
                )
            }
        }

        val genericUnknownProfile = profiles.first().copy(
            profileId = "unknown.generic.shared.qnn",
            modelFingerprint = "f".repeat(64)
        )
        assertTrue(genericUnknownProfile.hasSharedQnnImg2ImgTopology())
        assertProductRejected("unsupported_img2img_sampler") {
            validateLocalImageTaskSamplerCapability(
                profile = genericUnknownProfile,
                taskMode = LocalImageTaskMode.IMG2IMG,
                requestedScheduler = ImageSchedulerAlgorithm.PNDM_PLMS
            )
        }
        assertProductRejected("unsupported_inpaint_sampler") {
            validateLocalImageTaskSamplerCapability(
                profile = genericUnknownProfile,
                taskMode = LocalImageTaskMode.INPAINT,
                requestedScheduler = ImageSchedulerAlgorithm.PNDM_PLMS
            )
        }
    }

    @Test
    fun `generic shared qnn omitted image input sampler falls back while explicit pndm stays rejected`() {
        val root = Files.createTempDirectory("generic-shared-qnn-sampler").toFile()
        try {
            val primary = root.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
            listOf("text_encoder.bin", "vae.bin", "vae_encoder.bin").forEach { name ->
                root.resolve(name).writeBytes(byteArrayOf(1))
            }
            val model = LocalImageModelRecord(
                id = "unknown-shared-qnn",
                displayName = "Unknown shared QNN",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "0".repeat(64),
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15,
                bundleRoot = root.absolutePath
            )

            val img2imgResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(taskMode = LocalImageTaskMode.IMG2IMG),
                bundleRoot = root
            )
            assertTrue(img2imgResolution.profile.hasSharedQnnImg2ImgTopology())
            assertEquals(
                ImageSchedulerAlgorithm.DPMPP_2M,
                img2imgResolution.layers.resolved.scheduler
            )
            assertEquals(null, img2imgResolution.layers.requested.scheduler)
            assertFalse(img2imgResolution.sourceChain.first() == ImageProfileSource.USER_OVERRIDE)
            assertEquals(
                ImageSchedulerAlgorithm.DPMPP_2M,
                model.validateProductTaskSampler(LocalImageTaskMode.IMG2IMG, null)
            )
            try {
                model.validateProductTaskSampler(LocalImageTaskMode.IMG2IMG, "plms")
                fail("Expected explicit PNDM/PLMS to be rejected for QNN img2img")
            } catch (error: LocalImageProductContractException) {
                assertEquals("unsupported_img2img_sampler", error.code)
                val apiFailure = requireNotNull(error.toLocalImageApiProviderExceptionOrNull())
                assertEquals(422, apiFailure.httpStatus)
                assertEquals("unsupported_img2img_sampler", apiFailure.code)
            }

            val inpaintResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(taskMode = LocalImageTaskMode.INPAINT),
                bundleRoot = root
            )
            assertTrue(inpaintResolution.profile.hasExecutableQnnInpaintTopology())
            assertEquals(
                ImageSchedulerAlgorithm.DPMPP_2M,
                inpaintResolution.layers.resolved.scheduler
            )
            assertEquals(
                ImageSchedulerAlgorithm.DPMPP_2M,
                model.validateProductTaskSampler(LocalImageTaskMode.INPAINT, null)
            )
            try {
                model.validateProductTaskSampler(LocalImageTaskMode.INPAINT, "pndm")
                fail("Expected explicit PNDM/PLMS to be rejected for QNN inpaint")
            } catch (error: LocalImageProductContractException) {
                assertEquals("unsupported_inpaint_sampler", error.code)
                val apiFailure = requireNotNull(error.toLocalImageApiProviderExceptionOrNull())
                assertEquals(422, apiFailure.httpStatus)
                assertEquals("unsupported_inpaint_sampler", apiFailure.code)
            }

            val textResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions(taskMode = LocalImageTaskMode.TEXT_TO_IMAGE),
                bundleRoot = root
            )
            assertEquals(
                ImageSchedulerAlgorithm.PNDM_PLMS,
                textResolution.layers.resolved.scheduler
            )
            assertEquals(
                ImageSchedulerAlgorithm.PNDM_PLMS,
                model.validateProductTaskSampler(LocalImageTaskMode.TEXT_TO_IMAGE, null)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `split qnn explicit pndm keeps the task specific api rejection`() {
        val root = Files.createTempDirectory("split-qnn-sampler").toFile()
        try {
            val primary = root.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
            root.resolve("manifest.json").writeText(
                JSONObject()
                    .put("schema", "mca.image_engine.bundle.v1")
                    .put("recommendationId", "sdxl_base_qnn228")
                    .toString(),
                Charsets.UTF_8
            )
            val model = LocalImageModelRecord(
                id = "unknown-device-split-qnn",
                displayName = "Split QNN",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "1".repeat(64),
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SDXL,
                bundleRoot = root.absolutePath
            )

            assertEquals(
                ImageSchedulerAlgorithm.DPMPP_2M,
                model.validateProductTaskSampler(LocalImageTaskMode.IMG2IMG, null)
            )
            try {
                model.validateProductTaskSampler(LocalImageTaskMode.IMG2IMG, "pndm")
                fail("Expected split-QNN img2img PNDM to be rejected")
            } catch (error: LocalImageProductContractException) {
                assertEquals("unsupported_img2img_sampler", error.code)
                val apiFailure = requireNotNull(error.toLocalImageApiProviderExceptionOrNull())
                assertEquals(422, apiFailure.httpStatus)
                assertEquals("unsupported_img2img_sampler", apiFailure.code)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shared qnn img2img verifier binds encoder tail schedule and bit63 proof`() {
        val root = Files.createTempDirectory("shared-qnn-img2img-proof").toFile()
        try {
            val source = root.resolve("source.png").apply { writeBytes(byteArrayOf(1)) }.canonicalFile
            val tensor = root.resolve("input-rgb-nchw.f32").apply { writeBytes(byteArrayOf(2)) }.canonicalFile
            val input = prepared(source.path, "a")
            val prepared = QnnPreparedInputTensor(
                sourcePath = source.path,
                sourceSha256 = input.sha256,
                sourceBytes = input.sizeBytes,
                sourceWidth = 512,
                sourceHeight = 512,
                exifOrientation = 1,
                orientedWidth = 512,
                orientedHeight = 512,
                tensorPath = tensor.path,
                tensorSha256 = "b".repeat(64),
                tensorBytes = 1L * 3L * 512L * 512L * Float.SIZE_BYTES,
                tensorWidth = 512,
                tensorHeight = 512
            )
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input,
                strength = 0.6
            )
            val profile = resolvedProfile(
                recommendationId = "cyberrealistic_sd15_qnn228",
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15
            )
            val encoderContextSha256 = "c".repeat(64)
            val result = sharedQnnImg2ImgResultEvidence(
                options = options,
                prepared = prepared,
                profile = profile,
                encoderContextSha256 = encoderContextSha256
            )

            val audit = verifyAndSanitizeSharedQnnImg2ImgProductInput(
                result = result,
                options = options,
                prepared = prepared,
                profile = profile,
                expectedVaeEncoderContextSha256 = encoderContextSha256,
                expectedVaeEncoderGraphName = "model"
            )

            assertTrue(audit.getBoolean("nativeExecution"))
            assertEquals(prepared.tensorSha256, audit.getString("inputImageTensorSha256"))
            assertEquals("00000000000000ff", audit.getString("img2imgNoiseChecksum"))
            assertFalse(result.has("inputImagePath"))
            assertFalse(result.has("inputImageTensorPath"))
            assertFalse(result.getJSONObject("nativeEffective").has("inputImagePath"))

            val missingEncoderExecute = sharedQnnImg2ImgResultEvidence(
                options,
                prepared,
                profile,
                encoderContextSha256
            ).put(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD, "00000000000003ff")
            missingEncoderExecute.getJSONObject("nativeEffective")
                .put(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD, "00000000000003ff")
            assertInvalid {
                verifyAndSanitizeSharedQnnImg2ImgProductInput(
                    missingEncoderExecute,
                    options,
                    prepared,
                    profile,
                    encoderContextSha256,
                    "model"
                )
            }

            val badTailTimestep = sharedQnnImg2ImgResultEvidence(
                options,
                prepared,
                profile,
                encoderContextSha256
            ).put("img2imgAddNoiseTimestep", -123.0)
            badTailTimestep.getJSONObject("nativeEffective")
                .put("img2imgAddNoiseTimestep", -123.0)
            assertInvalid {
                verifyAndSanitizeSharedQnnImg2ImgProductInput(
                    badTailTimestep,
                    options,
                    prepared,
                    profile,
                    encoderContextSha256,
                    "model"
                )
            }

            val numericNoiseChecksum = sharedQnnImg2ImgResultEvidence(
                options,
                prepared,
                profile,
                encoderContextSha256
            ).put("img2imgNoiseChecksum", 255L)
            numericNoiseChecksum.getJSONObject("nativeEffective")
                .put("img2imgNoiseChecksum", 255L)
            assertInvalid {
                verifyAndSanitizeSharedQnnImg2ImgProductInput(
                    numericNoiseChecksum,
                    options,
                    prepared,
                    profile,
                    encoderContextSha256,
                    "model"
                )
            }

            val zeroNoiseChecksum = sharedQnnImg2ImgResultEvidence(
                options,
                prepared,
                profile,
                encoderContextSha256
            ).put("img2imgNoiseChecksum", "0000000000000000")
            zeroNoiseChecksum.getJSONObject("nativeEffective")
                .put("img2imgNoiseChecksum", "0000000000000000")
            assertInvalid {
                verifyAndSanitizeSharedQnnImg2ImgProductInput(
                    zeroNoiseChecksum,
                    options,
                    prepared,
                    profile,
                    encoderContextSha256,
                    "model"
                )
            }

            val inconsistentNoiseChecksum = sharedQnnImg2ImgResultEvidence(
                options,
                prepared,
                profile,
                encoderContextSha256
            ).put("img2imgNoiseChecksum", "00000000000000aa")
            assertInvalid {
                verifyAndSanitizeSharedQnnImg2ImgProductInput(
                    inconsistentNoiseChecksum,
                    options,
                    prepared,
                    profile,
                    encoderContextSha256,
                    "model"
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native input evidence is verified then private paths are replaced by hashes`() {
        val root = Files.createTempDirectory("native-input-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "d", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input,
                strength = 0.8
            )
            val nativeEffective = nativeInputEvidence(options)
            val result = JSONObject(nativeEffective.toString())
                .put("nativeEffective", nativeEffective)

            val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

            assertFalse(result.has("inputImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("inputImagePath"))
            assertEquals(input.sha256, result.getString("inputImageSha256"))
            assertEquals(input.sha256, audit.getJSONObject("inputImage").getString("sha256"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion control image requires physical ControlNet evidence`() {
        val root = Files.createTempDirectory("stable-controlnet-evidence").toFile()
        try {
            val source = root.resolve("control.png").apply { writeText("control") }.canonicalFile
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = prepared(source.path, "c"),
                controlStrength = 0.8
            )
            val nativeEffective = nativeInputEvidence(options)
            val result = JSONObject(nativeEffective.toString())
                .put("nativeEffective", nativeEffective)

            val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

            assertEquals(1, audit.getInt("controlImageExecutionCount"))
            val failedNative = nativeInputEvidence(options)
            val failedCompute = JSONObject(failedNative.toString())
                .put("nativeEffective", failedNative)
            listOf(
                failedCompute.getJSONObject("controlNetEvidence"),
                failedNative.getJSONObject("controlNetEvidence")
            ).forEach { it.put("computeSuccessCount", 0L) }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(failedCompute, options)
            }
            val missingResidualNative = nativeInputEvidence(options)
            val missingResidual = JSONObject(missingResidualNative.toString())
                .put("nativeEffective", missingResidualNative)
            listOf(
                missingResidual.getJSONObject("controlNetEvidence"),
                missingResidualNative.getJSONObject("controlNetEvidence")
            ).forEach { it.put("residualConsumptionCount", 0L) }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(missingResidual, options)
            }

            val shortNative = nativeInputEvidence(options)
            val shortResult = JSONObject(shortNative.toString())
                .put("nativeEffective", shortNative)
            listOf(
                shortResult.getJSONObject("controlNetEvidence"),
                shortNative.getJSONObject("controlNetEvidence")
            ).forEach { evidence ->
                listOf(
                    "computeAttemptCount",
                    "computeSuccessCount",
                    "positiveComputeAttemptCount",
                    "positiveComputeSuccessCount",
                    "residualConsumptionCount",
                    "positiveResidualConsumptionCount"
                ).forEach { evidence.put(it, 19L) }
            }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(shortResult, options)
            }

            val missingConsumptionNative = nativeInputEvidence(options)
            val missingConsumption = JSONObject(missingConsumptionNative.toString())
                .put("nativeEffective", missingConsumptionNative)
            listOf(
                missingConsumption.getJSONObject("imageInputConsumption"),
                missingConsumptionNative.getJSONObject("imageInputConsumption")
            ).forEach { it.put("control", "none") }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(missingConsumption, options)
            }

            val cfgNative = nativeInputEvidence(options, useCfg = true)
            val cfgResult = JSONObject(cfgNative.toString())
                .put("nativeEffective", cfgNative)
            verifyAndSanitizeStableDiffusionProductInput(cfgResult, options)
            assertEquals(
                40L,
                cfgResult.getJSONObject("controlNetEvidence").getLong("computeSuccessCount")
            )
            assertEquals(
                20L,
                cfgResult.getJSONObject("controlNetEvidence").getLong("negativeComputeSuccessCount")
            )

            val shortCfgNative = nativeInputEvidence(options, useCfg = true)
            val shortCfg = JSONObject(shortCfgNative.toString())
                .put("nativeEffective", shortCfgNative)
            listOf(
                shortCfg.getJSONObject("controlNetEvidence"),
                shortCfgNative.getJSONObject("controlNetEvidence")
            ).forEach { evidence ->
                listOf(
                    "negativeComputeAttemptCount",
                    "negativeComputeSuccessCount",
                    "negativeResidualConsumptionCount"
                ).forEach { evidence.put(it, 19L) }
                listOf(
                    "computeAttemptCount",
                    "computeSuccessCount",
                    "residualConsumptionCount"
                ).forEach { evidence.put(it, 39L) }
            }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(shortCfg, options)
            }

            val wrongUnetNative = nativeInputEvidence(options, useCfg = true)
                .put("unetExecutionCount", 39)
            val wrongUnet = JSONObject(wrongUnetNative.toString())
                .put("nativeEffective", wrongUnetNative)
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(wrongUnet, options)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion VAE tiling requires completed physical decode tiles`() {
        val options = LocalImageGenerationOptions(
            batchCount = 2,
            vaeTiling = LocalImageVaeTilingOptions(tileSize = 512, overlap = 0.5)
        )
        val nativeEffective = nativeInputEvidence(options)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)

        val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

        assertTrue(audit.getJSONObject("vaeTiling").getBoolean("enabled"))
        val failedNative = nativeInputEvidence(options)
        val failed = JSONObject(failedNative.toString())
            .put("nativeEffective", failedNative)
        listOf(
            failed.getJSONObject("vaeTiling").getJSONObject("decode"),
            failedNative.getJSONObject("vaeTiling").getJSONObject("decode")
        ).forEach { it.put("tileComputeSuccessCount", 0L) }
        assertInvalid { verifyAndSanitizeStableDiffusionProductInput(failed, options) }

        val requestEchoOnlyNative = nativeInputEvidence(options)
        val requestEchoOnly = JSONObject(requestEchoOnlyNative.toString())
            .put("nativeEffective", requestEchoOnlyNative)
        listOf(
            requestEchoOnly.getJSONObject("vaeTiling").getJSONObject("decode"),
            requestEchoOnlyNative.getJSONObject("vaeTiling").getJSONObject("decode")
        ).forEach { phase ->
            listOf(
                "invocationCount",
                "successCount",
                "plannedTileCount",
                "tileComputeAttemptCount",
                "tileComputeSuccessCount",
                "tileSizeX",
                "tileSizeY"
            ).forEach { phase.put(it, 0L) }
            phase.put("overlapX", 0.0).put("overlapY", 0.0)
        }
        assertInvalid {
            verifyAndSanitizeStableDiffusionProductInput(requestEchoOnly, options)
        }

        val shortDecodeNative = nativeInputEvidence(options)
        val shortDecode = JSONObject(shortDecodeNative.toString())
            .put("nativeEffective", shortDecodeNative)
        listOf(
            shortDecode.getJSONObject("vaeTiling").getJSONObject("decode"),
            shortDecodeNative.getJSONObject("vaeTiling").getJSONObject("decode")
        ).forEach { phase ->
            listOf(
                "invocationCount",
                "successCount",
                "plannedTileCount",
                "tileComputeAttemptCount",
                "tileComputeSuccessCount"
            ).forEach { phase.put(it, 1L) }
        }
        assertInvalid {
            verifyAndSanitizeStableDiffusionProductInput(shortDecode, options)
        }
    }

    @Test
    fun `stable diffusion preview requires real native callback publication evidence`() {
        val options = LocalImageGenerationOptions(
            preview = LocalImagePreviewOptions(
                interval = 2,
                mode = LocalImagePreviewMode.PROJECTION
            )
        )
        val nativeEffective = nativeInputEvidence(options)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)

        val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

        assertEquals("projection", audit.getJSONObject("preview").getString("mode"))
        val missingPublication = JSONObject(result.toString())
            .put("previewPublicationCount", 0)
        assertInvalid {
            verifyAndSanitizeStableDiffusionProductInput(missingPublication, options)
        }
    }

    @Test
    fun `native float evidence accepts ordinary C float round trip`() {
        val root = Files.createTempDirectory("native-float-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = prepared(source.path, "f"),
                strength = 0.65,
                vaeTiling = LocalImageVaeTilingOptions(512, 0.3)
            )
            val nativeEffective = nativeInputEvidence(options)
                .put("strength", 0.6499999761581421)
            nativeEffective.getJSONObject("vaeTiling")
                .put("requestedOverlap", 0.30000001192092896)
            val result = JSONObject(nativeEffective.toString())
                .put("nativeEffective", nativeEffective)

            val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

            assertTrue(audit.getBoolean("nativeExecution"))

            val missingEncodeNative = nativeInputEvidence(options)
            val missingEncode = JSONObject(missingEncodeNative.toString())
                .put("nativeEffective", missingEncodeNative)
            listOf(
                missingEncode.getJSONObject("vaeTiling").getJSONObject("encode"),
                missingEncodeNative.getJSONObject("vaeTiling").getJSONObject("encode")
            ).forEach { phase ->
                listOf(
                    "invocationCount",
                    "successCount",
                    "plannedTileCount",
                    "tileComputeAttemptCount",
                    "tileComputeSuccessCount",
                    "tileSizeX",
                    "tileSizeY"
                ).forEach { phase.put(it, 0L) }
                phase.put("overlapX", 0.0).put("overlapY", 0.0)
            }
            assertInvalid {
                verifyAndSanitizeStableDiffusionProductInput(missingEncode, options)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion input result rejects missing or request-synthesized digest`() {
        val root = Files.createTempDirectory("stable-native-input-digest").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "b", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input
            )

            val missing = nativeInputEvidence(options).let { nativeEffective ->
                JSONObject(nativeEffective.toString()).put("nativeEffective", nativeEffective)
            }
            missing.getJSONObject("nativeEffective").remove("inputImageSha256")
            assertInvalid { verifyAndSanitizeStableDiffusionProductInput(missing, options) }

            val mismatched = nativeInputEvidence(options).let { nativeEffective ->
                JSONObject(nativeEffective.toString()).put("nativeEffective", nativeEffective)
            }
            mismatched.getJSONObject("nativeEffective")
                .put("inputImageSha256", "0".repeat(64))
            assertInvalid { verifyAndSanitizeStableDiffusionProductInput(mismatched, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion LoRA result requires applied native tensors and exposes no path`() {
        val options = LocalImageGenerationOptions(loras = listOf(preparedLora(multiplier = 0.75)))
        val nativeEffective = nativeInputEvidence(options)
        val result = JSONObject(nativeEffective.toString()).put("nativeEffective", nativeEffective)

        val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

        assertEquals(1, audit.getJSONArray("loras").length())
        assertFalse(audit.getJSONArray("loras").getJSONObject(0).has("path"))
        assertEquals(1, audit.getJSONObject("loraEvidence").getInt("appliedCount"))

        val missingApplication = JSONObject(result.toString())
        missingApplication.getJSONObject("nativeEffective")
            .getJSONObject("loraEvidence")
            .put("appliedTensorCount", 0)
        assertInvalid { verifyAndSanitizeStableDiffusionProductInput(missingApplication, options) }

        val missingStage = JSONObject(result.toString()).put("nativeStageMask", 127L)
        assertInvalid { verifyAndSanitizeStableDiffusionProductInput(missingStage, options) }
    }

    @Test
    fun `MNN text result sanitizes empty role paths and publishes execution audit`() {
        val options = LocalImageGenerationOptions()
        val result = mnnResultEvidence(options, seed = 41L)

        val audit = verifyAndSanitizeMnnProductInput(result, options)

        assertFalse(result.has("inputImagePath"))
        assertFalse(result.has("maskImagePath"))
        assertFalse(result.has("controlImagePath"))
        val nativeEffective = result.getJSONObject("nativeEffective")
        assertFalse(nativeEffective.has("inputImagePath"))
        assertFalse(nativeEffective.has("maskImagePath"))
        assertFalse(nativeEffective.has("controlImagePath"))
        assertEquals("text_to_image", nativeEffective.getString("taskMode"))
        assertEquals(1, nativeEffective.getInt("batchCount"))
        assertTrue(audit.getBoolean("nativeExecution"))
        assertEquals(0, audit.getInt("inputImageExecutionCount"))
    }

    @Test
    fun `MNN edit result replaces private source path with content hash`() {
        val root = Files.createTempDirectory("mnn-edit-input-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "e", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input
            )
            val result = mnnResultEvidence(options, seed = 42L)

            val audit = verifyAndSanitizeMnnProductInput(result, options)

            assertFalse(result.has("inputImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("inputImagePath"))
            assertEquals(input.sha256, result.getString("inputImageSha256"))
            assertEquals(
                input.sha256,
                result.getJSONObject("nativeEffective").getString("inputImageSha256")
            )
            assertEquals(input.sha256, audit.getJSONObject("inputImage").getString("sha256"))
            assertEquals(1, audit.getInt("inputImageExecutionCount"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MNN text result rejects every missing nativeEffective product field`() {
        val options = LocalImageGenerationOptions()
        listOf(
            "taskMode",
            "batchCount",
            "inputImageExecutionCount",
            "maskImageExecutionCount",
            "controlImageExecutionCount",
            "inputImagePath",
            "maskImagePath",
            "controlImagePath"
        ).forEach { field ->
            val result = mnnResultEvidence(options, seed = 43L)
            result.getJSONObject("nativeEffective").remove(field)

            assertInvalid { verifyAndSanitizeMnnProductInput(result, options) }
        }
    }

    @Test
    fun `MNN edit result rejects missing or request-synthesized input digest`() {
        val root = Files.createTempDirectory("mnn-edit-native-digest").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "f", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input
            )
            val missing = mnnResultEvidence(options, seed = 44L)
            missing.getJSONObject("nativeEffective").remove("inputImageSha256")
            assertInvalid { verifyAndSanitizeMnnProductInput(missing, options) }

            val mismatched = mnnResultEvidence(options, seed = 44L)
            mismatched.getJSONObject("nativeEffective")
                .put("inputImageSha256", "0".repeat(64))
            assertInvalid { verifyAndSanitizeMnnProductInput(mismatched, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN ControlNet verifies per-step residual execution and removes private path`() {
        val root = Files.createTempDirectory("qnn-control-input-evidence").toFile()
        try {
            val source = root.resolve("control.png").apply { writeText("control") }.canonicalFile
            val input = prepared(source.path, "c", width = 512, height = 512)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = input,
                controlStrength = 0.65
            )
            val result = qnnControlResultEvidence(options, timetableCount = 20)

            val audit = verifyAndSanitizeQnnProductInput(result, options)

            assertFalse(result.has("controlImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("controlImagePath"))
            assertEquals(input.sha256, audit.getJSONObject("controlImage").getString("sha256"))
            assertEquals(20, audit.getInt("controlNetExecutionCount"))
            assertEquals(260, audit.getInt("controlNetResidualWriteCount"))
            assertEquals(20, audit.getInt("controlNetResidualUnetReuseCount"))
            assertEquals("positive", audit.getString("controlNetConditioningBranch"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN ControlNet rejects incomplete scheduler-step evidence`() {
        val root = Files.createTempDirectory("qnn-control-count-evidence").toFile()
        try {
            val source = root.resolve("control.png").apply { writeText("control") }.canonicalFile
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = prepared(source.path, "d"),
                controlStrength = 0.8
            )
            val result = qnnControlResultEvidence(options, timetableCount = 20)
            result.put("controlNetExecutionCount", 39)

            assertInvalid { verifyAndSanitizeQnnProductInput(result, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN text result verifies and removes empty worker path slots`() {
        val options = LocalImageGenerationOptions()
        val nativeEffective = nativeInputEvidence(options, qnnDetailMaskHex = true)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)

        verifyAndSanitizeQnnTextToImagePrivatePaths(result, options)

        listOf("inputImagePath", "maskImagePath", "controlImagePath").forEach { field ->
            assertFalse(result.has(field))
            assertFalse(result.getJSONObject("nativeEffective").has(field))
        }
    }

    @Test
    fun `QNN text result rejects a non-empty or missing worker path slot`() {
        val options = LocalImageGenerationOptions()
        val legacyNumericNative = nativeInputEvidence(options)
        val legacyNumeric = JSONObject(legacyNumericNative.toString())
            .put("nativeEffective", legacyNumericNative)
        assertInvalid {
            verifyAndSanitizeQnnTextToImagePrivatePaths(legacyNumeric, options)
        }

        val nonEmptyNative = nativeInputEvidence(options, qnnDetailMaskHex = true)
            .put("inputImagePath", "/private/input.png")
        val nonEmpty = JSONObject(nonEmptyNative.toString())
            .put("nativeEffective", nonEmptyNative)
        assertInvalid { verifyAndSanitizeQnnTextToImagePrivatePaths(nonEmpty, options) }

        val missingNative = nativeInputEvidence(options, qnnDetailMaskHex = true)
            .apply { remove("maskImagePath") }
        val missing = JSONObject(missingNative.toString()).put("nativeEffective", missingNative)
        assertInvalid { verifyAndSanitizeQnnTextToImagePrivatePaths(missing, options) }
    }

    private fun nativeInputEvidence(
        options: LocalImageGenerationOptions,
        includeUnusedHashes: Boolean = true,
        timetableCount: Int = 20,
        useCfg: Boolean = false,
        qnnDetailMaskHex: Boolean = false
    ): JSONObject {
        var stageMask = 127L
        if (options.inputImage != null) stageMask = stageMask or 128L
        if (options.maskImage != null) stageMask = stageMask or 256L
        if (options.controlImage != null) stageMask = stageMask or 512L
        if (options.loras.isNotEmpty()) stageMask = stageMask or 1_024L
        val loras = JSONArray().apply {
            options.loras.forEach { adapter ->
                put(
                    JSONObject()
                        .put("id", adapter.id)
                        .put("sha256", adapter.sha256)
                        .put("multiplier", adapter.multiplier)
                )
            }
        }
        val loraEvidence = JSONObject()
            .put("requestedCount", options.loras.size)
            .put("loadedCount", options.loras.size)
            .put("appliedCount", options.loras.size)
            .put("appliedTensorCount", if (options.loras.isEmpty()) 0L else 12L)
        val controlNetEvidence = JSONObject().apply {
            val positiveCount = if (options.controlImage == null) 0L else timetableCount.toLong()
            val negativeCount = if (options.controlImage == null || !useCfg) {
                0L
            } else {
                timetableCount.toLong()
            }
            val totalCount = positiveCount + negativeCount
            put("computeAttemptCount", totalCount)
            put("computeSuccessCount", totalCount)
            put("positiveComputeAttemptCount", positiveCount)
            put("positiveComputeSuccessCount", positiveCount)
            put("negativeComputeAttemptCount", negativeCount)
            put("negativeComputeSuccessCount", negativeCount)
            put("residualConsumptionCount", totalCount)
            put("positiveResidualConsumptionCount", positiveCount)
            put("negativeResidualConsumptionCount", negativeCount)
            put("auxiliaryResidualConsumptionCount", 0L)
        }
        fun vaePhaseEvidence(invocationCount: Long): JSONObject = JSONObject()
            .put("invocationCount", invocationCount)
            .put("successCount", invocationCount)
            .put("plannedTileCount", invocationCount)
            .put("tileComputeAttemptCount", invocationCount)
            .put("tileComputeSuccessCount", invocationCount)
            .put("tileSizeX", if (invocationCount > 0L) 64 else 0)
            .put("tileSizeY", if (invocationCount > 0L) 64 else 0)
            .put("overlapX", 0.0)
            .put("overlapY", 0.0)
        val vaeEncodeInvocationCount = if (options.vaeTiling != null && options.inputImage != null) {
            1L
        } else {
            0L
        }
        val vaeDecodeInvocationCount = if (options.vaeTiling != null) {
            options.batchCount.toLong()
        } else {
            0L
        }
        val vaeTilingEvidence = JSONObject()
            .put("enabled", options.vaeTiling != null)
            .put("requestedTileSize", options.vaeTiling?.tileSize ?: 0)
            .put("requestedOverlap", options.vaeTiling?.overlap ?: 0.0)
            .put("encode", vaePhaseEvidence(vaeEncodeInvocationCount))
            .put("decode", vaePhaseEvidence(vaeDecodeInvocationCount))
        val imageInputConsumption = JSONObject()
            .put("input", if (options.inputImage == null) "none" else "init_latent")
            .put("mask", if (options.maskImage == null) "none" else "denoise_mask")
            .put("control", if (options.controlImage == null) "none" else "controlnet_residual")
        return JSONObject()
        .put("taskMode", options.taskMode.wireName)
        .put("inputImagePath", options.inputImage?.path.orEmpty())
        .put("maskImagePath", options.maskImage?.path.orEmpty())
        .put("controlImagePath", options.controlImage?.path.orEmpty())
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
        .put("strength", options.strength ?: 1.0)
        .put("controlStrength", options.controlStrength ?: 1.0)
        .put("controlStrengthApplied", options.controlImage != null)
        .put("clipSkip", options.clipSkip ?: -1)
        .put("batchCount", options.batchCount)
        .put("steps", 20)
        .put("timetableCount", timetableCount)
        .put("unetExecutionCount", timetableCount * (if (useCfg) 2 else 1))
        .put("useCfg", useCfg)
        .put("imageInputConsumption", imageInputConsumption)
        .put("previewRequested", options.preview != null)
        .put("previewMode", options.preview?.mode?.wireName ?: "none")
        .put("previewInterval", options.preview?.interval ?: 0)
        .put("previewPublicationCount", if (options.preview == null) 0 else 3)
        .put("previewLastStep", if (options.preview == null) 0 else 6)
        .put("previewLastRevision", if (options.preview == null) 0L else 9L)
        .put("loras", loras)
        .put("loraEvidence", loraEvidence)
        .put("controlNetEvidence", controlNetEvidence)
        .put("nativeStageMask", stageMask)
        .apply {
            if (qnnDetailMaskHex) {
                put(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD, stageMask.toULong().toFixedUInt64Hex())
            } else {
                put("nativeDetailStageMask", stageMask)
            }
        }
        .put(
            "vaeTiling",
            vaeTilingEvidence
        )
        .apply {
            if (includeUnusedHashes) {
                put("inputImageSha256", options.inputImage?.sha256.orEmpty())
                put("maskImageSha256", options.maskImage?.sha256.orEmpty())
                put("controlImageSha256", options.controlImage?.sha256.orEmpty())
            } else {
                options.inputImage?.let { put("inputImageSha256", it.sha256) }
                options.maskImage?.let { put("maskImageSha256", it.sha256) }
                options.controlImage?.let { put("controlImageSha256", it.sha256) }
            }
        }
    }

    private fun mnnResultEvidence(
        options: LocalImageGenerationOptions,
        seed: Long
    ): JSONObject {
        val outputPath = "/cache/output.png"
        val outputBytes = 1_024L
        val outputSha256 = "a".repeat(64)
        val nativeEffective = nativeInputEvidence(options, includeUnusedHashes = false)
            .put("seed", seed)
            .put("outputPath", outputPath)
            .put("outputBytes", outputBytes)
            .put("outputSha256", outputSha256)
            .apply {
                options.inputImage?.let { put("inputImageSha256", it.sha256) }
                options.maskImage?.let { put("maskImageSha256", it.sha256) }
                options.controlImage?.let { put("controlImageSha256", it.sha256) }
            }
        return JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)
            .put(
                "outputs",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("path", outputPath)
                        .put("outputBytes", outputBytes)
                        .put("outputSha256", outputSha256)
                        .put("mimeType", "image/png")
                        .put("seed", seed)
                )
            )
    }

    private fun qnnControlResultEvidence(
        options: LocalImageGenerationOptions,
        timetableCount: Int
    ): JSONObject {
        val residualTensorCount = 13
        val nativeEffective = nativeInputEvidence(options, qnnDetailMaskHex = true)
            .put("useCfg", true)
            .put("unetExecutionCount", timetableCount * 2)
            .put("controlStrength", options.controlStrength ?: 1.0)
            .put("controlImageSha256", requireNotNull(options.controlImage).sha256)
            .put("controlStrengthApplied", true)
            .put("controlNetExecutionCount", timetableCount)
            .put("controlNetResidualTensorCount", residualTensorCount)
            .put("controlNetResidualWriteCount", timetableCount * residualTensorCount)
            .put("controlNetResidualUnetReuseCount", timetableCount)
            .put("controlNetConditioningBranch", "positive")
            .put("controlNetInputConsumed", true)
        return JSONObject(nativeEffective.toString())
            .put("timetableCount", timetableCount)
            .put("useCfg", true)
            .put("nativeEffective", nativeEffective)
    }

    private fun sharedQnnImg2ImgResultEvidence(
        options: LocalImageGenerationOptions,
        prepared: QnnPreparedInputTensor,
        profile: ImageExecutionProfile,
        encoderContextSha256: String
    ): JSONObject {
        val steps = profile.defaults.steps
        val schedule = resolveQnnImg2ImgSchedule(
            steps = steps,
            fullTimetableCount = steps,
            strength = options.strength ?: 1.0
        )
        val tailTimesteps = JSONArray().apply {
            repeat(schedule.effectiveSteps) { index -> put(900.0 - index) }
        }
        val runtimeSessionMode = if (
            profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) {
            "shared_text_unet_vae"
        } else {
            "shared_unet_vae"
        }
        val nativeEffective = JSONObject()
            .put("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
            .put("batchCount", 1)
            .put("inputImagePath", requireNotNull(options.inputImage).path)
            .put("maskImagePath", "")
            .put("controlImagePath", "")
            .put("inputImageExecutionCount", 1)
            .put("maskImageExecutionCount", 0)
            .put("controlImageExecutionCount", 0)
            .put("inputImageSha256", options.inputImage.sha256)
            .put("inputImageSourceReadByNative", false)
            .put("inputImageSourceValidation", "android_preprocess_provenance")
            .put("inputImageTensorPath", prepared.tensorPath)
            .put("inputImageTensorSha256", prepared.tensorSha256)
            .put("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
            .put("inputImageSourceWidth", prepared.sourceWidth)
            .put("inputImageSourceHeight", prepared.sourceHeight)
            .put("inputImageOrientedWidth", prepared.orientedWidth)
            .put("inputImageOrientedHeight", prepared.orientedHeight)
            .put("inputImageExifOrientation", prepared.exifOrientation)
            .put("inputImageTensorWidth", prepared.tensorWidth)
            .put("inputImageTensorHeight", prepared.tensorHeight)
            .put("inputImageTensorChannels", 3)
            .put("inputImageTensorBytes", prepared.tensorBytes)
            .put("inputImageTensorShape", JSONArray(listOf(1, 3, 512, 512)))
            .put("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
            .put("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
            .put("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)
            .put("encoderContextSha256", encoderContextSha256)
            .put("encoderContextLoadCount", 1)
            .put("encoderExecutionCount", 1)
            .put("encoderGraphName", "model")
            .put("encoderInputName", "input")
            .put("encoderMeanOutputName", "mean")
            .put("encoderStdOutputName", "std")
            .put("encoderInputDtype", "uint16")
            .put("encoderMeanDtype", "uint16")
            .put("encoderStdDtype", "uint16")
            .put("encoderInputShape", JSONArray(listOf(1, 3, 512, 512)))
            .put("encoderMeanShape", JSONArray(listOf(1, 4, 64, 64)))
            .put("encoderStdShape", JSONArray(listOf(1, 4, 64, 64)))
            .put("encoderInputBufferSha256", "d".repeat(64))
            .put("encoderMeanBufferSha256", "e".repeat(64))
            .put("encoderStdBufferSha256", "f".repeat(64))
            .put("encoderLatentSha256", "1".repeat(64))
            .put("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
            .put("posteriorSampleCount", 1L * 4L * 64L * 64L)
            .put("encoderLatentScalingFactor", profile.vae.scalingFactor)
            .put("encoderContextReleasedBeforeSharedSession", true)
            .put("encoderRuntimeMode", "standalone_encoder_then_shared_unet_vae")
            .put("runtimeSessionMode", runtimeSessionMode)
            .put("steps", steps)
            .put("strength", options.strength ?: 1.0)
            .put("fullTimetableCount", schedule.fullTimetableCount)
            .put("timetableCount", schedule.effectiveSteps)
            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
            .put("img2imgBeginIndex", schedule.beginIndex)
            .put("useCfg", profile.defaults.useCfg)
            .put(
                "unetExecutionCount",
                schedule.effectiveSteps * if (profile.defaults.useCfg) 2 else 1
            )
            .put("img2imgAddNoiseApplied", true)
            .put("img2imgAddNoiseBeginIndex", schedule.beginIndex)
            .put("img2imgAddNoiseTimestep", tailTimesteps.getDouble(0))
            .put("img2imgNoiseChecksum", "00000000000000ff")
            .put(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD, "80000000000003ff")
        return JSONObject(nativeEffective.toString())
            .put("timesteps", tailTimesteps)
            .put("nativeEffective", JSONObject(nativeEffective.toString()))
    }

    private fun prepared(
        path: String,
        digestCharacter: String,
        width: Int = 512,
        height: Int = 512
    ): LocalImagePreparedInput = LocalImagePreparedInput(
        path = path,
        mimeType = "image/png",
        sha256 = digestCharacter.repeat(64),
        sizeBytes = 1024L,
        width = width,
        height = height
    )

    private fun preparedLora(multiplier: Double = 1.0): LocalImagePreparedLora =
        LocalImagePreparedLora(
            id = "11111111-1111-4111-8111-111111111111",
            name = "Portrait",
            path = "/cache/image_loras/lora.safetensors",
            sha256 = "d".repeat(64),
            sizeBytes = 1_024L,
            multiplier = multiplier
        )

    private fun resolvedProfile(
        recommendationId: String,
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily
    ): ImageExecutionProfile = ImageExecutionProfileResolver.resolve(
        ImageExecutionProfileResolverInput(
            modelFingerprint = "a".repeat(64),
            runtime = runtime,
            family = family,
            recommendationId = recommendationId
        )
    ).profile

    private fun assertProductRejected(expectedCode: String, block: () -> Unit) {
        try {
            block()
            fail("Expected product input rejection: $expectedCode")
        } catch (error: LocalImageProductContractException) {
            assertEquals(expectedCode, error.code)
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid image input contract")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LocalImageWorkerProtocolTest {
    @Test
    fun `generation request round trips model prompt and explicit options`() {
        val model = LocalImageModelRecord(
            id = "model-1",
            displayName = "SD 1.5",
            path = "/data/user/0/com.muyuchat.mca/files/model.mnn",
            fileName = "model.mnn",
            sizeBytes = 123L,
            sha256 = "abc",
            runtime = LocalImageRuntime.MNN_DIFFUSION,
            family = LocalImageModelFamily.SD15,
            bundleRoot = "/data/user/0/com.muyuchat.mca/files/bundle"
        )

        val options = LocalImageGenerationOptions(
            negativePrompt = "",
            width = 512,
            height = 512,
            steps = 20,
            threads = 4,
            seed = 20260712,
            cfgScale = 7.0,
            flowShift = 0.0,
            sampleMethod = "euler",
            backendMode = "opencl",
            tokenEmbeddingMode = "auto",
            memoryMode = 0,
            useCfg = false,
            runner = "direct"
        )
        val parsed = LocalImageWorkerProtocol.parseGenerateRequest(
            LocalImageWorkerProtocol.generateRequest(
                requestId = "request-1",
                model = model,
                prompt = "a ceramic cup",
                options = options
            )
        )

        assertEquals("request-1", parsed.requestId)
        assertEquals("a ceramic cup", parsed.prompt)
        assertEquals(model.id, parsed.model.id)
        assertEquals(LocalImageRuntime.MNN_DIFFUSION, parsed.model.runtime)
        assertEquals(LocalImageModelFamily.SD15, parsed.model.family)
        assertEquals(512, parsed.options.width)
        assertEquals(512, parsed.options.height)
        assertEquals(20, parsed.options.steps)
        assertEquals(4, parsed.options.threads)
        assertEquals(20260712, parsed.options.seed)
        assertEquals(7.0, parsed.options.cfgScale ?: 0.0, 0.0)
        assertEquals(0.0, parsed.options.flowShift ?: -1.0, 0.0)
        assertEquals("euler", parsed.options.sampleMethod)
        assertEquals("opencl", parsed.options.backendMode)
        assertEquals("auto", parsed.options.tokenEmbeddingMode)
        assertEquals(0, parsed.options.memoryMode)
        assertEquals(false, parsed.options.useCfg)
        assertEquals("direct", parsed.options.runner)
        assertEquals("", parsed.options.negativePrompt)
    }

    @Test
    fun `omitted and explicit empty negative prompts remain distinct across worker ipc`() {
        val model = LocalImageModelRecord(
            id = "model-negative",
            displayName = "Image model",
            path = "/data/user/0/com.muyuchat.mca/files/model.bin",
            fileName = "model.bin",
            sizeBytes = 123L,
            sha256 = "def",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SD15,
            bundleRoot = "/data/user/0/com.muyuchat.mca/files/bundle"
        )
        val controls = LocalImageGenerationOptions(
            width = 768,
            height = 512,
            steps = 24,
            seed = 42,
            cfgScale = 6.5,
            sampleMethod = "dpmpp_2m"
        )

        val omitted = LocalImageWorkerProtocol.parseGenerateRequest(
            LocalImageWorkerProtocol.generateRequest("request-omitted", model, "prompt", controls)
        )
        val explicitEmptyPayload = LocalImageWorkerProtocol.generateRequest(
            requestId = "request-empty",
            model = model,
            prompt = "prompt",
            options = controls.copy(negativePrompt = "")
        )
        val explicitEmpty = LocalImageWorkerProtocol.parseGenerateRequest(explicitEmptyPayload)
        val optionJson = JSONObject(explicitEmptyPayload).getJSONObject("options")

        assertEquals(768, explicitEmpty.options.width)
        assertEquals(512, explicitEmpty.options.height)
        assertEquals(24, explicitEmpty.options.steps)
        assertEquals(42, explicitEmpty.options.seed)
        assertEquals(6.5, explicitEmpty.options.cfgScale ?: 0.0, 0.0)
        assertEquals("dpmpp_2m", explicitEmpty.options.sampleMethod)
        assertEquals("dpmpp_2m", optionJson.getString("sampler"))
        assertEquals("", explicitEmpty.options.negativePrompt)
        assertTrue(optionJson.has("negativePrompt"))
        assertEquals("", optionJson.getString("negativePrompt"))
        assertEquals(null, omitted.options.negativePrompt)
        assertTrue(!JSONObject(
            LocalImageWorkerProtocol.generateRequest("request-omitted-2", model, "prompt", controls)
        ).getJSONObject("options").has("negativePrompt"))
    }

    @Test
    fun `all product image modes and controls round trip across worker ipc`() {
        val model = LocalImageModelRecord(
            id = "image-modes",
            displayName = "Image model",
            path = "/data/user/0/com.muyuchat.mca/files/model.bin",
            fileName = "model.bin",
            sizeBytes = 123L,
            sha256 = "model-sha",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.SD15,
            bundleRoot = "/data/user/0/com.muyuchat.mca/files/bundle"
        )
        fun input(role: String) = LocalImagePreparedInput(
            path = "/data/user/0/com.muyuchat.mca/cache/local_image_dispatch_inputs/request/$role.img",
            mimeType = "image/png",
            sha256 = if (role == "input") "a".repeat(64) else if (role == "mask") "b".repeat(64) else "c".repeat(64),
            sizeBytes = 100L,
            width = 512,
            height = 512
        )
        val cases = listOf(
            LocalImageGenerationOptions(taskMode = LocalImageTaskMode.TEXT_TO_IMAGE),
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input("input"),
                strength = 0.65
            ),
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImage = input("input"),
                maskImage = input("mask"),
                strength = 0.8
            ),
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = input("control"),
                controlStrength = 1.25
            )
        )

        cases.forEachIndexed { index, options ->
            val requested = options.copy(
                negativePrompt = "",
                width = 512,
                height = 512,
                steps = 24,
                seed = 42,
                cfgScale = 6.5,
                sampleMethod = "dpmpp_2m",
                batchCount = 1,
                preview = LocalImagePreviewOptions(
                    interval = 2,
                    mode = LocalImagePreviewMode.PROJECTION
                )
            )
            val parsed = LocalImageWorkerProtocol.parseGenerateRequest(
                LocalImageWorkerProtocol.generateRequest("mode-$index", model, "prompt", requested)
            ).options

            assertEquals(requested, parsed)
        }
    }

    @Test
    fun `worker ipc carries prepared inpaint and advanced controls without base64`() {
        val model = LocalImageModelRecord(
            id = "model-input",
            displayName = "Image edit model",
            path = "/data/user/0/com.muyuchat.mca/files/model.gguf",
            fileName = "model.gguf",
            sizeBytes = 123L,
            sha256 = "model-sha",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.SD15,
            bundleRoot = "/data/user/0/com.muyuchat.mca/files/bundle"
        )
        val input = LocalImagePreparedInput(
            path = "/data/user/0/com.muyuchat.mca/cache/local_image_dispatch_inputs/r/input.img",
            mimeType = "image/png",
            sha256 = "a".repeat(64),
            sizeBytes = 1024L,
            width = 512,
            height = 512
        )
        val mask = input.copy(
            path = "/data/user/0/com.muyuchat.mca/cache/local_image_dispatch_inputs/r/mask.img",
            sha256 = "b".repeat(64)
        )
        val options = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.INPAINT,
            inputImage = input,
            maskImage = mask,
            strength = 0.7,
            clipSkip = 2,
            batchCount = 1,
            vaeTiling = LocalImageVaeTilingOptions(tileSize = 512, overlap = 0.5)
        )

        val payload = LocalImageWorkerProtocol.generateRequest("input-request", model, "edit", options)
        val parsed = LocalImageWorkerProtocol.parseGenerateRequest(payload)

        assertTrue(!payload.contains("base64"))
        assertEquals(LocalImageTaskMode.INPAINT, parsed.options.taskMode)
        assertEquals(input.path, parsed.options.inputImage?.path)
        assertEquals(mask.sha256, parsed.options.maskImage?.sha256)
        assertEquals(0.7, parsed.options.strength ?: 0.0, 0.0)
        assertEquals(2, parsed.options.clipSkip)
        assertEquals(512, parsed.options.vaeTiling?.tileSize)
    }

    @Test
    fun `progress and result expose worker pid`() {
        val progress = LocalImageProgress(
            phase = "sampling",
            message = "step 2",
            step = 2,
            steps = 8,
            elapsedMs = 1500L,
            secondsPerStep = 0.75,
            threads = 4,
            width = 512,
            height = 512,
            cancelRequested = false,
            requestOptionsJson = "{\"sampleMethod\":\"euler\",\"memoryMode\":0}",
            componentSelectionJson = "{\"mode\":\"manifest_roles\",\"fallback\":false}",
            previewPath = "/data/user/0/com.muyuchat.mca/cache/local_image_outputs/out.preview-1.png",
            previewMimeType = "image/png",
            previewMode = "projection",
            previewStep = 2,
            previewRevision = 7L,
            previewWidth = 64,
            previewHeight = 64,
            previewFrameCount = 1,
            previewNoisy = false,
            stageTrace = listOf(
                "context_lock",
                "context_binary_mmap",
                "context_create",
                "graph_execute",
                "png_write",
                "context_release"
            )
        )

        val parsedProgress = LocalImageWorkerProtocol.parseProgress(
            LocalImageWorkerProtocol.progress("request-2", workerPid = 4321, progress = progress)
        )
        val parsedResult = LocalImageWorkerProtocol.parseResult(
            LocalImageWorkerProtocol.result(
                requestId = "request-2",
                workerPid = 4321,
                outputPath = "/data/user/0/com.muyuchat.mca/cache/local_image_worker_results/out.png",
                mimeType = "image/png",
                executionMetadataJson = "{\"componentSelection\":{\"mode\":\"manifest_roles\"}}"
            )
        )

        assertEquals(4321, parsedProgress.workerPid)
        assertEquals("sampling", parsedProgress.progress.phase)
        assertEquals(progress.requestOptionsJson, parsedProgress.progress.requestOptionsJson)
        assertEquals(progress.componentSelectionJson, parsedProgress.progress.componentSelectionJson)
        assertEquals(progress.previewPath, parsedProgress.progress.previewPath)
        assertEquals(progress.previewMimeType, parsedProgress.progress.previewMimeType)
        assertEquals(progress.previewMode, parsedProgress.progress.previewMode)
        assertEquals(progress.previewStep, parsedProgress.progress.previewStep)
        assertEquals(progress.previewRevision, parsedProgress.progress.previewRevision)
        assertEquals(progress.previewWidth, parsedProgress.progress.previewWidth)
        assertEquals(progress.previewHeight, parsedProgress.progress.previewHeight)
        assertEquals(progress.previewFrameCount, parsedProgress.progress.previewFrameCount)
        assertEquals(progress.previewNoisy, parsedProgress.progress.previewNoisy)
        assertEquals(progress.stageTrace, parsedProgress.progress.stageTrace)
        assertEquals(4321, parsedResult.workerPid)
        assertTrue(parsedResult.outputPath.endsWith("out.png"))
        assertTrue(parsedResult.executionMetadataJson.contains("manifest_roles"))
        assertEquals(1, parsedResult.outputs.size)
        assertEquals(0, parsedResult.outputs.single().index)
    }

    @Test
    fun `result protocol preserves ordered multi output metadata`() {
        val payload = LocalImageWorkerProtocol.result(
            requestId = "batch",
            workerPid = 99,
            outputs = listOf(
                LocalImageWorkerProtocol.OutputEnvelope(0, "/cache/out-000.png", "image/png", 10L),
                LocalImageWorkerProtocol.OutputEnvelope(1, "/cache/out-001.png", "image/png", 11L)
            ),
            executionMetadataJson = "{}"
        )

        val parsed = LocalImageWorkerProtocol.parseResult(payload)

        assertEquals(2, parsed.outputs.size)
        assertEquals(listOf(10L, 11L), parsed.outputs.map { it.seed })
        assertEquals(parsed.outputPath, parsed.outputs.first().outputPath)
    }

    @Test
    fun `legacy progress without native stage trace remains readable`() {
        val payload = JSONObject()
            .put("version", 1)
            .put("requestId", "legacy")
            .put("workerPid", 123)
            .put(
                "progress",
                JSONObject()
                    .put("phase", "sampling")
                    .put("message", "legacy")
            )
            .toString()

        val parsed = LocalImageWorkerProtocol.parseProgress(payload)

        assertEquals(emptyList<String>(), parsed.progress.stageTrace)
        assertEquals("", parsed.progress.componentSelectionJson)
    }

    @Test
    fun `ipc preserves the exact sdxl retrieve bind and unload boundary`() {
        val stages = listOf(
            "unet_graph_retrieve_before",
            "unet_graph_retrieve_after",
            "unet_tensor_bind_before",
            "unet_tensor_bind_after",
            "vae_graph_retrieve_before",
            "vae_graph_retrieve_after",
            "vae_tensor_bind_before",
            "vae_tensor_bind_after",
            "vae_context_release_before",
            "vae_context_release_after",
            "unet_context_release_before",
            "unet_context_release_after",
            "backend_release_before",
            "backend_release_after",
            "runtime_unload_before"
        )
        val payload = LocalImageWorkerProtocol.progress(
            requestId = "sdxl-attempt-2",
            workerPid = 8750,
            progress = LocalImageProgress(
                phase = "context_release",
                message = "Releasing coherent QNN session",
                step = 1,
                steps = 1,
                elapsedMs = 1234L,
                secondsPerStep = 0.0,
                threads = 4,
                width = 1024,
                height = 1024,
                cancelRequested = false,
                stageTrace = stages
            )
        )

        assertEquals(stages, LocalImageWorkerProtocol.parseProgress(payload).progress.stageTrace)
    }
}

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
            LocalImageWorkerProtocol.generateRequest("request-1", model, "a ceramic cup", options)
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
        assertEquals(progress.stageTrace, parsedProgress.progress.stageTrace)
        assertEquals(4321, parsedResult.workerPid)
        assertTrue(parsedResult.outputPath.endsWith("out.png"))
        assertTrue(parsedResult.executionMetadataJson.contains("manifest_roles"))
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

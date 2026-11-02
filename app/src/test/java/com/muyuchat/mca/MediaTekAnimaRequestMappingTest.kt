package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaRequest
import com.muyuchat.core.mediatek.MediaTekExecutionEvidence
import com.muyuchat.core.mediatek.MediaTekGraphRunEvidence
import com.muyuchat.core.mediatek.MediaTekGraphStage
import com.muyuchat.core.mediatek.MediaTekRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class MediaTekAnimaRequestMappingTest {
    @Test
    fun `supported text-to-image controls map without changing prompt semantics`() {
        val options = LocalImageGenerationOptions(
            negativePrompt = "  low quality  ",
            width = 768,
            height = 1024,
            steps = 12,
            seed = 47,
            cfgScale = 4.25
        )

        val request = mediaTekAnimaRequestFromOptions("  portrait prompt  ", options, randomSeed = 99L)

        assertEquals(
            MediaTekAnimaRequest(
                prompt = "portrait prompt",
                negativePrompt = "  low quality  ",
                width = 768,
                height = 1024,
                steps = 12,
                cfgScale = 4.25f,
                seed = 47L
            ),
            request
        )
    }

    @Test
    fun `unspecified seed is made explicit and nonnegative`() {
        val request = mediaTekAnimaRequestFromOptions(
            prompt = "portrait",
            options = LocalImageGenerationOptions(),
            randomSeed = 99L
        )

        assertEquals(99L, request.seed)
    }

    @Test
    fun `batch img2img and lora fail before pipeline dispatch`() {
        val unsupported = listOf(
            LocalImageGenerationOptions(batchCount = 2),
            LocalImageGenerationOptions(taskMode = LocalImageTaskMode.IMG2IMG),
            LocalImageGenerationOptions(
                loras = listOf(
                    LocalImagePreparedLora(
                        id = "00000000-0000-0000-0000-000000000001",
                        name = "lora",
                        path = "lora.safetensors",
                        sha256 = "0".repeat(64),
                        sizeBytes = 16L,
                        multiplier = 1.0
                    )
                )
            )
        )

        unsupported.forEach { options ->
            val failure = runCatching {
                mediaTekAnimaRequestFromOptions("portrait", options, randomSeed = 5L)
            }.exceptionOrNull() as? LocalImageProductContractException
            requireNotNull(failure)
            assertEquals("unsupported_mediatek_anima_options", failure.code)
            assertTrue(failure.message.contains("尚未运行 NPU 图"))
        }
    }

    @Test
    fun `negative explicit seed is rejected`() {
        val failure = runCatching {
            mediaTekAnimaRequestFromOptions(
                prompt = "portrait",
                options = LocalImageGenerationOptions(seed = -1),
                randomSeed = 5L
            )
        }.exceptionOrNull() as? LocalImageProductContractException

        requireNotNull(failure)
        assertEquals("invalid_image_seed", failure.code)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun `execution metadata records graph runs without claiming an NPU partition`() {
        val outputHash = "a".repeat(64)
        val evidence = MediaTekExecutionEvidence(
            state = MediaTekRuntimeState.UNVERIFIED,
            requestedRuntime = "MEDIATEK_NEURON",
            actualDelegate = null,
            actualRuntimeVersion = null,
            npuPartitions = null,
            cpuFallbackOps = null,
            stages = setOf(MediaTekGraphStage.DIFFUSION),
            semanticGeneration = true,
            outputMime = "image/png",
            outputSha256 = outputHash,
            message = "LiteRT partition evidence is unavailable.",
        )
        val graphRun = MediaTekGraphRunEvidence(
            graphFile = "graphs/block_00.tflite",
            graphStage = MediaTekGraphStage.DIFFUSION,
            requestedAccelerator = "NPU",
            graphCompiled = true,
            graphRan = true,
            npuPartitions = null,
            cpuFallbackOps = null,
            providerDeviceSupportedAdvisory = true,
            providerLibraryReadyAdvisory = true,
            availableAccelerators = setOf("NPU"),
            outputSha256 = outputHash,
            elapsedMs = 17L,
            state = MediaTekRuntimeState.UNVERIFIED,
            message = "LiteRT NPU-targeted graph run returned.",
        )

        val metadata = JSONObject(
            mediaTekExecutionMetadataJson(
                evidence = evidence,
                graphRuns = listOf(graphRun),
                elapsedMs = 23L,
                width = 512,
                height = 512,
                seed = 7L,
                steps = 1,
            ),
        )
        val graphRuns = metadata.getJSONArray("graphRuns")
        val graph = graphRuns.getJSONObject(0)

        assertEquals(1, metadata.getInt("graphRunCount"))
        assertEquals("graphs/block_00.tflite", graph.getString("graph"))
        assertEquals("DIFFUSION", graph.getString("stage"))
        assertTrue(graph.getBoolean("ran"))
        assertEquals("UNVERIFIED", graph.getString("state"))
        assertFalse(metadata.getBoolean("npuPartitionCountAvailable"))
        assertFalse(metadata.getBoolean("provesNpuExecution"))
        assertTrue(metadata.isNull("actualDelegate"))
    }
}

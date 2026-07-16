package com.muyuchat.api.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageGenerationApiContractTest {
    @Test
    fun `explicit empty negative prompt remains distinct from omission`() {
        val explicit = ImageGenerationApiContract.parseRequest(
            """{"prompt":"a ceramic robot","negative_prompt":""}"""
        )
        val omitted = ImageGenerationApiContract.parseRequest(
            """{"prompt":"a ceramic robot"}"""
        )

        assertEquals("", explicit.negativePrompt)
        assertNull(omitted.negativePrompt)
        assertTrue(explicit.requestedControlsJson().has("negativePrompt"))
        assertFalse(omitted.requestedControlsJson().has("negativePrompt"))
    }

    @Test
    fun `request parser preserves sampler steps cfg seed and size`() {
        val request = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("model", "model-a")
                .put("prompt", "a small landscape")
                .put("negative_prompt", "blur")
                .put("size", "768X512")
                .put("n", 1)
                .put("response_format", "b64_json")
                .put("seed", -1)
                .put("steps", 28)
                .put("cfg_scale", 6.5)
                .put("sampler", "euler_a")
                .toString()
        )

        assertEquals("model-a", request.model)
        assertEquals(768, request.width)
        assertEquals(512, request.height)
        assertEquals(-1, request.seed)
        assertEquals(28, request.steps)
        assertEquals(6.5, request.cfgScale ?: 0.0, 0.0)
        assertEquals("euler_a", request.sampler)
        assertEquals(1, request.imageCount)
        assertEquals("b64_json", request.responseFormat)
    }

    @Test
    fun `omitted optional controls stay absent`() {
        val request = ImageGenerationApiContract.parseRequest("""{"prompt":"minimal"}""")

        assertNull(request.model)
        assertNull(request.width)
        assertNull(request.height)
        assertNull(request.seed)
        assertNull(request.steps)
        assertNull(request.cfgScale)
        assertNull(request.sampler)
    }

    @Test
    fun `invalid optional controls fail with stable codes`() {
        assertRejected("unsupported_image_count") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"x","n":0}""")
        }
        assertRejected("invalid_steps") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"x","steps":0}""")
        }
        assertRejected("invalid_sampler") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"x","sampler":"  "}""")
        }
        assertRejected("invalid_negative_prompt") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"x","negative_prompt":null}""")
        }
        assertRejected("unsupported_response_format") {
            ImageGenerationApiContract.parseRequest(
                """{"prompt":"x","response_format":"url"}"""
            )
        }
    }

    @Test
    fun `response requires strict native evidence for every runtime`() {
        val execution = strictExecution("MNN_DIFFUSION")
        val response = JSONObject()
            .put("request_id", "img-1")
            .put("execution", execution)
            .put("data", strictImageData())

        val parsed = ImageGenerationApiContract.parseResponse("img-1", response.toString())

        assertEquals("MNN_DIFFUSION", parsed.execution.getString("runtime"))
        assertTrue(parsed.execution.getBoolean("nativeExecution"))
        assertFalse(parsed.execution.getBoolean("fallback"))
    }

    @Test
    fun `response rejects missing sequence and qnn graph proof`() {
        val missingSequence = strictExecution("STABLE_DIFFUSION_CPP")
        missingSequence.remove("nativeGenerationSequence")
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-1",
                responseBody("img-1", missingSequence).toString()
            )
        }

        val missingQnnProof = strictExecution("QNN_HTP")
            .put("qnnGraphExecution", false)
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-2",
                responseBody("img-2", missingQnnProof).toString()
            )
        }
    }

    private fun strictExecution(runtime: String): JSONObject {
        val native = JSONObject()
            .put("profileId", "profile.image.v1")
            .put("profileRevision", 1)
            .put("modelFingerprint", "sha256:abc")
            .put("runtime", runtime)
            .put("scheduler", "EULER")
            .put("predictionType", "EPSILON")
            .put("steps", 20)
            .put("timetableCount", 20)
            .put("unetExecutionCount", 40)
            .put("cfgScale", 7.0)
            .put("useCfg", true)
            .put("unconditionalBranch", true)
            .put("tokenizerBackend", "TOKENIZERS_CPP")
            .put("tokenCount", 154)
            .put("embeddingDiskDataType", "GRAPH_INTERNAL")
            .put("vaeScalingLocation", "GRAPH_INTERNAL")
            .put("vaeScalingFactor", 0.18215)
            .put("width", 512)
            .put("height", 512)
            .put("seed", 7)
            .put("graphName", "model")
            .put("fallback", false)
        return JSONObject(native.toString())
            .put("nativeEffective", native)
            .put("nativeExecution", true)
            .put("nativeGenerationSequence", 9L)
            .put("fallback", false)
            .put("npuActive", runtime == "QNN_HTP")
            .put("qnnGraphExecution", runtime == "QNN_HTP")
    }

    private fun responseBody(requestId: String, execution: JSONObject): JSONObject =
        JSONObject()
            .put("request_id", requestId)
            .put("execution", execution)
            .put("data", strictImageData())

    private fun strictImageData(): org.json.JSONArray =
        org.json.JSONArray().put(
            JSONObject()
                .put("b64_json", "iVBORw0KGgo=")
                .put("mime_type", "image/png")
                .put("width", 512)
                .put("height", 512)
        )

    private fun assertRejected(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: ImageGenerationContractException) {
            assertEquals(code, error.code)
        }
    }
}

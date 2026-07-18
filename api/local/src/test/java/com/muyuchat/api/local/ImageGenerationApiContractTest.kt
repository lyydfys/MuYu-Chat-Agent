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
    fun `request parser rejects unknown fields unsupported samplers and invalid latent sizes`() {
        assertRejected("unknown_image_request_field") {
            ImageGenerationApiContract.parseRequest(
                JSONObject().put("prompt", "test").put("cfg", 7).toString()
            )
        }
        assertRejected("unsupported_sampler") {
            ImageGenerationApiContract.parseRequest(
                JSONObject().put("prompt", "test").put("sampler", "made_up").toString()
            )
        }
        listOf("63x512", "510x512").forEach { size ->
            assertRejected("invalid_image_size") {
                ImageGenerationApiContract.parseRequest(
                    JSONObject().put("prompt", "test").put("size", size).toString()
                )
            }
        }
        assertRejected("unknown_image_request_field") {
            ImageGenerationApiContract.parseRequest(
                JSONObject()
                    .put("prompt", "test")
                    .put(
                        "vae_tiling",
                        JSONObject().put("tile_size", 512).put("overlap", 0.5).put("tiles", 4)
                    )
                    .toString()
            )
        }
        assertRejected("invalid_seed") {
            ImageGenerationApiContract.parseRequest(
                """{"prompt":"test","seed":9223372036854775808}"""
            )
        }
    }

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
    fun `request parser preserves all image task inputs and strengths`() {
        val input = "data:image/png;base64,AAAA"
        val mask = "data:image/png;base64,BBBB"
        val control = "data:image/png;base64,CCCC"

        val text = ImageGenerationApiContract.parseRequest("""{"prompt":"text"}""")
        val img2img = ImageGenerationApiContract.parseRequest(
            JSONObject().put("prompt", "img").put("input_image", input).put("strength", 0.65).toString()
        )
        val inpaint = ImageGenerationApiContract.parseRequest(
            JSONObject().put("prompt", "paint").put("input_image", input).put("mask_image", mask)
                .put("strength", 0.8).toString()
        )
        val controlled = ImageGenerationApiContract.parseRequest(
            JSONObject().put("prompt", "control").put("control_image", control)
                .put("control_strength", 1.25).toString()
        )

        assertEquals(ImageGenerationApiTaskMode.TEXT_TO_IMAGE, text.taskMode)
        assertEquals(ImageGenerationApiTaskMode.IMG2IMG, img2img.taskMode)
        assertEquals(input, img2img.inputImage)
        assertEquals(0.65, img2img.strength ?: 0.0, 0.0)
        assertEquals(ImageGenerationApiTaskMode.INPAINT, inpaint.taskMode)
        assertEquals(mask, inpaint.maskImage)
        assertEquals(ImageGenerationApiTaskMode.CONTROL, controlled.taskMode)
        assertEquals(control, controlled.controlImage)
        assertEquals(1.25, controlled.controlStrength ?: 0.0, 0.0)
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
        assertRejected("invalid_seed") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"x","seed":-2}""")
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
    fun `image input modes infer and preserve strict product controls`() {
        val inpaint = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("prompt", "replace the cup with a vase")
                .put("input_image", "data:image/png;base64,AAAA")
                .put("mask_image", "data:image/png;base64,BBBB")
                .put("strength", 0.72)
                .put("clip_skip", 2)
                .put(
                    "vae_tiling",
                    JSONObject().put("tile_size", 512).put("overlap", 0.5)
                )
                .toString()
        )

        assertEquals(ImageGenerationApiTaskMode.INPAINT, inpaint.taskMode)
        assertEquals("data:image/png;base64,AAAA", inpaint.inputImage)
        assertEquals("data:image/png;base64,BBBB", inpaint.maskImage)
        assertEquals(0.72, inpaint.strength ?: 0.0, 0.0)
        assertEquals(2, inpaint.clipSkip)
        assertEquals(512, inpaint.vaeTiling?.tileSize)
        assertEquals(0.5, inpaint.vaeTiling?.overlap ?: -1.0, 0.0)

        val control = ImageGenerationApiContract.parseRequest(
            """{"prompt":"edge guided","task_mode":"control","control_image":"/data/local/tmp/control.png","control_strength":0.8}"""
        )
        assertEquals(ImageGenerationApiTaskMode.CONTROL, control.taskMode)
        assertEquals(0.8, control.controlStrength ?: 0.0, 0.0)
    }

    @Test
    fun `image input shape and remote references fail before provider dispatch`() {
        assertRejected("invalid_image_input_contract") {
            ImageGenerationApiContract.parseRequest(
                """{"prompt":"x","task_mode":"inpaint","input_image":"/tmp/source.png"}"""
            )
        }
        assertRejected("unsupported_image_input_reference") {
            ImageGenerationApiContract.parseRequest(
                """{"prompt":"x","task_mode":"img2img","input_image":"https://example.com/source.png"}"""
            )
        }
        assertRejected("conflicting_image_input") {
            ImageGenerationApiContract.parseRequest(
                """{"prompt":"x","input_image":"/tmp/a.png","source_image":"/tmp/b.png"}"""
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

        val nonQnn = strictExecution("MNN_DIFFUSION")
            .apply {
                remove("npuActive")
                remove("qnnGraphExecution")
            }
        ImageGenerationApiContract.parseResponse(
            "img-non-qnn",
            responseBody("img-non-qnn", nonQnn).toString()
        )
    }

    @Test
    fun `response requires actual prompt weighting evidence in flat and native fields`() {
        val missing = strictExecution("STABLE_DIFFUSION_CPP")
        missing.remove("promptWeightFingerprint")
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-missing-weight-proof",
                responseBody("img-missing-weight-proof", missing).toString()
            )
        }

        val mismatched = strictExecution("STABLE_DIFFUSION_CPP")
            .put("promptWeightingApplied", true)
            .put("positiveWeightedTokenCount", 1)
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-mismatched-weight-proof",
                responseBody("img-mismatched-weight-proof", mismatched).toString()
            )
        }

        val impossible = strictExecution("STABLE_DIFFUSION_CPP")
        impossible.put("promptWeightingApplied", true)
        impossible.put("positiveWeightedTokenCount", 1)
        impossible.getJSONObject("nativeEffective")
            .put("promptWeightingSupported", false)
            .put("promptWeightingApplied", true)
            .put("positiveWeightedTokenCount", 1)
        impossible.put("promptWeightingSupported", false)
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-impossible-weight-proof",
                responseBody("img-impossible-weight-proof", impossible).toString()
            )
        }
    }

    @Test
    fun `response binds requested controls to native effective and flat evidence`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"strict","size":"512x512","steps":20,"cfg_scale":7.0,"seed":7,"sampler":"euler"}"""
        )
        ImageGenerationApiContract.parseResponse(
            "img-ok",
            request,
            responseBody("img-ok", strictExecution("STABLE_DIFFUSION_CPP")).toString()
        )

        val flatMismatch = strictExecution("STABLE_DIFFUSION_CPP").put("steps", 19)
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-flat",
                request,
                responseBody("img-flat", flatMismatch).toString()
            )
        }

        val requestedMismatch = strictExecution("STABLE_DIFFUSION_CPP")
        requestedMismatch.put("steps", 19)
        requestedMismatch.getJSONObject("nativeEffective").put("steps", 19)
        assertRejected("image_control_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-requested",
                request,
                responseBody("img-requested", requestedMismatch).toString()
            )
        }
    }

    @Test
    fun `response never silently substitutes a different requested model`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"model":"requested-model","prompt":"strict"}"""
        )
        val response = responseBody(
            "img-model",
            strictExecution("STABLE_DIFFUSION_CPP")
        ).put("model", "different-model")

        assertRejected("image_model_identity_mismatch") {
            ImageGenerationApiContract.parseResponse("img-model", request, response.toString())
        }
    }

    @Test
    fun `worker failure codes map to stable non generic HTTP statuses`() {
        assertEquals(
            422,
            ImageGenerationProviderException.fromWorkerFailure(
                "unsupported_preview",
                "preview is unavailable"
            ).httpStatus
        )
        assertEquals(
            502,
            ImageGenerationProviderException.fromWorkerFailure(
                "EXECUTION_CONTRACT_MISMATCH",
                "native evidence mismatch"
            ).httpStatus
        )
        assertEquals(
            504,
            ImageGenerationProviderException.fromWorkerFailure(
                "qnn_sdxl_worker_timeout",
                "worker timed out"
            ).httpStatus
        )
        assertEquals(
            "invalid_code",
            ImageGenerationProviderException.fromWorkerFailure(
                " INVALID CODE ",
                "invalid"
            ).code
        )
    }

    @Test
    fun `img2img response proves consumed input hash and exact strength`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"edit","task_mode":"img2img","input_image":"data:image/png;base64,AAAA","strength":0.65}"""
        )
        val sha = "a".repeat(64)
        val execution = strictExecution("STABLE_DIFFUSION_CPP")
        execution.getJSONObject("nativeEffective")
            .put("taskMode", "img2img")
            .put("batchCount", 1)
            .put("inputImageExecutionCount", 1)
            .put("maskImageExecutionCount", 0)
            .put("controlImageExecutionCount", 0)
            .put("inputImageSha256", sha)
            .put("strength", 0.65)
        execution.put(
            "imageInput",
            JSONObject()
                .put("nativeExecution", true)
                .put("taskMode", "img2img")
                .put("batchCount", 1)
                .put("inputImageExecutionCount", 1)
                .put("maskImageExecutionCount", 0)
                .put("controlImageExecutionCount", 0)
                .put("inputImage", JSONObject().put("sha256", sha))
                .put("strength", 0.65)
        )

        ImageGenerationApiContract.parseResponse(
            "img-input",
            request,
            responseBody("img-input", execution).toString()
        )

        execution.getJSONObject("nativeEffective").put("strength", 0.6499999761581421)
        execution.getJSONObject("imageInput").put("strength", 0.6499999761581421)
        ImageGenerationApiContract.parseResponse(
            "img-input-float",
            request,
            responseBody("img-input-float", execution).toString()
        )

        execution.getJSONObject("imageInput").put("strength", 0.5)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-input-bad",
                request,
                responseBody("img-input-bad", execution).toString()
            )
        }
    }

    @Test
    fun `qnn control response proves consumed hash count and control strength without private paths`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"edge guided","task_mode":"control","control_image":"data:image/png;base64,AAAA","control_strength":0.8}"""
        )
        val sha = "b".repeat(64)

        fun controlExecution(): JSONObject {
            val execution = strictExecution("QNN_HTP")
            execution.getJSONObject("nativeEffective")
                .put("taskMode", "control")
                .put("batchCount", 1)
                .put("inputImageExecutionCount", 0)
                .put("maskImageExecutionCount", 0)
                .put("controlImageExecutionCount", 1)
                .put("controlImageSha256", sha)
                .put("controlStrength", 0.8)
            return execution.put(
                "imageInput",
                JSONObject()
                    .put("nativeExecution", true)
                    .put("taskMode", "control")
                    .put("batchCount", 1)
                    .put("inputImageExecutionCount", 0)
                    .put("maskImageExecutionCount", 0)
                    .put("controlImageExecutionCount", 1)
                    .put("controlImage", JSONObject().put("sha256", sha))
                    .put("controlStrength", 0.8)
            )
        }

        ImageGenerationApiContract.parseResponse(
            "img-control",
            request,
            responseBody("img-control", controlExecution()).toString()
        )

        val privatePath = controlExecution()
        privatePath.getJSONObject("nativeEffective")
            .put("controlImagePath", "/data/user/0/private/control.img")
        assertRejected("private_image_input_path_exposed") {
            ImageGenerationApiContract.parseResponse(
                "img-control-path",
                request,
                responseBody("img-control-path", privatePath).toString()
            )
        }

        val countMismatch = controlExecution()
        countMismatch.getJSONObject("imageInput").put("controlImageExecutionCount", 0)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-control-count",
                request,
                responseBody("img-control-count", countMismatch).toString()
            )
        }

        val hashMismatch = controlExecution()
        hashMismatch.getJSONObject("imageInput")
            .getJSONObject("controlImage")
            .put("sha256", "c".repeat(64))
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-control-hash",
                request,
                responseBody("img-control-hash", hashMismatch).toString()
            )
        }

        val strengthMismatch = controlExecution()
        strengthMismatch.getJSONObject("nativeEffective").put("controlStrength", 0.5)
        strengthMismatch.getJSONObject("imageInput").put("controlStrength", 0.5)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-control-strength",
                request,
                responseBody("img-control-strength", strengthMismatch).toString()
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
            .put("promptWeightingSupported", false)
            .put("promptWeightingApplied", false)
            .put("positiveWeightedTokenCount", 0)
            .put("negativeWeightedTokenCount", 0)
            .put("promptWeightFingerprint", "9b353b1ac542678089ce3d12ee96ddd6ba3b0252ec0675cdf0540e6aa6b1860e")
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

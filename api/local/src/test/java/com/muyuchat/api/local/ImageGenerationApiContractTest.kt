package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

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
        assertRejected("invalid_vae_tiling") {
            ImageGenerationApiContract.parseRequest(
                JSONObject()
                    .put("prompt", "test")
                    .put(
                        "vae_tiling",
                        JSONObject().put("tile_size", 512).put("overlap", 0.5001)
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
        val minimumImg2img = ImageGenerationApiContract.parseRequest(
            JSONObject().put("prompt", "img").put("input_image", input).put("strength", 0.0).toString()
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
        assertEquals(0.0, minimumImg2img.strength ?: -1.0, 0.0)
        assertEquals(ImageGenerationApiTaskMode.INPAINT, inpaint.taskMode)
        assertEquals(mask, inpaint.maskImage)
        assertEquals(ImageGenerationApiTaskMode.CONTROL, controlled.taskMode)
        assertEquals(control, controlled.controlImage)
        assertEquals(1.25, controlled.controlStrength ?: 0.0, 0.0)
    }

    @Test
    fun `structured UltraFix fills omitted duplicate execution controls`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())

        assertEquals(ImageGenerationApiTaskMode.IMG2IMG, request.taskMode)
        assertEquals(1024, request.width)
        assertEquals(768, request.height)
        assertEquals(10, request.steps)
        assertEquals(0.5, request.strength ?: -1.0, 0.0)
        assertEquals(512, request.vaeTiling?.tileSize)
        assertEquals(0.25, request.vaeTiling?.overlap ?: -1.0, 0.0)
        assertEquals(5, request.ultraFix?.inversionSteps)
        assertEquals(1, request.imageCount)
    }

    @Test
    fun `structured UltraFix accepts matching duplicate controls and rejects every conflict`() {
        val matching = ultraFixRequest()
            .put("size", "1024x768")
            .put("steps", 10)
            .put("strength", 0.5)
            .put("vae_tiling", JSONObject().put("tile_size", 512).put("overlap", 0.25))
        val parsed = ImageGenerationApiContract.parseRequest(matching.toString())
        assertEquals(1024, parsed.width)
        assertEquals(10, parsed.steps)

        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest().put("size", "768x768").toString()
            )
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(ultraFixRequest().put("steps", 9).toString())
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(ultraFixRequest().put("strength", 0.6).toString())
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest()
                    .put("vae_tiling", JSONObject().put("tile_size", 256).put("overlap", 0.25))
                    .toString()
            )
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest()
                    .put("vae_tiling", JSONObject().put("tile_size", 512).put("overlap", 0.5))
                    .toString()
            )
        }
    }

    @Test
    fun `structured UltraFix uses the native Float strength boundary`() {
        val adjacent = ultraFixRequest()
        adjacent.getJSONObject("ultrafix")
            .put("strength", 0.4000000000000001)
            .put("inversion_steps", 4)

        val parsed = ImageGenerationApiContract.parseRequest(adjacent.toString())
        assertEquals(4, parsed.ultraFix?.inversionSteps)

        adjacent.getJSONObject("ultrafix").put("inversion_steps", 5)
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(adjacent.toString())
        }
    }

    @Test
    fun `structured UltraFix is limited to one plain img2img output`() {
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest().put("task_mode", "edit").toString()
            )
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(ultraFixRequest().put("n", 2).toString())
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest()
                    .put("task_mode", "inpaint")
                    .put("mask_image", "data:image/png;base64,BBBB")
                    .toString()
            )
        }
        assertRejected("invalid_ultrafix") {
            ImageGenerationApiContract.parseRequest(
                ultraFixRequest()
                    .put("task_mode", "control")
                    .put("control_image", "data:image/png;base64,CCCC")
                    .toString()
            )
        }
    }

    @Test
    fun `UltraFix response binds strict native evidence to the exact PNG payload`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
        val png = ultraFixPng(0x31)
        val body = ultraFixResponseBody("img-ultrafix", request, png)

        val parsed = ImageGenerationApiContract.parseResponse(
            expectedRequestId = "img-ultrafix",
            expectedRequest = request,
            rawBody = body.toString()
        )

        assertEquals(Base64.getEncoder().encodeToString(png), parsed.data.getJSONObject(0).getString("b64_json"))
    }

    @Test
    fun `UltraFix response accepts threshold-skipped noise but binds count to checksum`() {
        listOf("STABLE_DIFFUSION_CPP", "QNN_HTP").forEachIndexed { index, runtime ->
            val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
            val requestId = "img-ultrafix-threshold-$index"
            val body = ultraFixResponseBody(requestId, request, ultraFixPng(0x32 + index), runtime)
            val evidenceLayers = listOf(
                body.getJSONObject("execution").getJSONObject("ultraFix"),
                body.getJSONObject("execution").getJSONObject("nativeEffective")
                    .getJSONObject("ultraFix")
            )
            evidenceLayers.forEach { evidence ->
                evidence.put("noiseInjectionStepCount", 0)
                evidence.put("noiseInjectionChecksum", "0000000000000000")
            }

            ImageGenerationApiContract.parseResponse(requestId, request, body.toString())

            evidenceLayers.forEach { evidence -> evidence.put("noiseInjectionStepCount", 1) }
            assertRejected("invalid_ultrafix_execution_evidence") {
                ImageGenerationApiContract.parseResponse(requestId, request, body.toString())
            }
        }
    }

    @Test
    fun `UltraFix response rejects a multi-step quality gap`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
        val requestId = "img-ultrafix-quality-gap"
        val body = ultraFixResponseBody(requestId, request, ultraFixPng(0x36))
        listOf(
            body.getJSONObject("execution").getJSONObject("ultraFix"),
            body.getJSONObject("execution").getJSONObject("nativeEffective")
                .getJSONObject("ultraFix")
        ).forEach { evidence ->
            evidence.put("noiseInjectionStepCount", 0)
            evidence.put("noiseInjectionChecksum", "0000000000000000")
            evidence.put("structureGuidanceStepCount", 0)
            evidence.put("structureGuidanceChecksum", "0000000000000000")
        }

        assertRejected("invalid_ultrafix_execution_evidence") {
            ImageGenerationApiContract.parseResponse(requestId, request, body.toString())
        }
    }

    @Test
    fun `UltraFix response accepts the known extended graph schema and rejects unknown fields`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
        val png = ultraFixPng(0x37)
        val body = ultraFixResponseBody(
            requestId = "img-ultrafix-extended",
            request = request,
            png = png,
            runtime = "QNN_HTP"
        )

        ImageGenerationApiContract.parseResponse(
            expectedRequestId = "img-ultrafix-extended",
            expectedRequest = request,
            rawBody = body.toString()
        )

        val execution = body.getJSONObject("execution")
        execution.getJSONObject("ultraFix").put("unknownCounter", 1)
        execution.getJSONObject("nativeEffective")
            .getJSONObject("ultraFix")
            .put("unknownCounter", 1)
        assertRejected("invalid_ultrafix_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                expectedRequestId = "img-ultrafix-extended",
                expectedRequest = request,
                rawBody = body.toString()
            )
        }
    }

    @Test
    fun `UltraFix response rejects missing and request-divergent execution evidence`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
        val png = ultraFixPng(0x42)

        val missing = ultraFixResponseBody("img-ultrafix-missing", request, png)
        missing.getJSONObject("execution").remove("ultraFix")
        assertRejected("invalid_ultrafix_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                expectedRequestId = "img-ultrafix-missing",
                expectedRequest = request,
                rawBody = missing.toString()
            )
        }

        val altered = ultraFixResponseBody("img-ultrafix-altered", request, png)
        val execution = altered.getJSONObject("execution")
        execution.getJSONObject("ultraFix").put("targetWidth", 768)
        execution.getJSONObject("nativeEffective")
            .getJSONObject("ultraFix")
            .put("targetWidth", 768)
        assertRejected("invalid_ultrafix_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                expectedRequestId = "img-ultrafix-altered",
                expectedRequest = request,
                rawBody = altered.toString()
            )
        }
    }

    @Test
    fun `UltraFix response rejects a replaced Base64 PNG payload`() {
        val request = ImageGenerationApiContract.parseRequest(ultraFixRequest().toString())
        val committed = ultraFixPng(0x53)
        val replaced = ultraFixPng(0x64)
        val body = ultraFixResponseBody("img-ultrafix-replaced", request, committed)
        body.getJSONArray("data").getJSONObject(0)
            .put("b64_json", Base64.getEncoder().encodeToString(replaced))

        assertRejected("invalid_ultrafix_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                expectedRequestId = "img-ultrafix-replaced",
                expectedRequest = request,
                rawBody = body.toString()
            )
        }
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
    fun `request parser accepts only pathless LoRA ids and multipliers`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val request = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("prompt", "portrait")
                .put(
                    "loras",
                    JSONArray().put(JSONObject().put("id", id).put("multiplier", 0.75))
                )
                .toString()
        )

        assertEquals(id, request.loras.single().id)
        assertEquals(0.75, request.loras.single().multiplier, 0.0)
        assertFalse(request.requestedControlsJson().getJSONArray("loras").getJSONObject(0).has("path"))

        assertRejected("unknown_image_request_field") {
            ImageGenerationApiContract.parseRequest(
                JSONObject()
                    .put("prompt", "portrait")
                    .put(
                        "loras",
                        JSONArray().put(
                            JSONObject()
                                .put("id", id)
                                .put("multiplier", 1.0)
                                .put("path", "/private/adapter.safetensors")
                        )
                    )
                    .toString()
            )
        }
        assertRejected("invalid_lora") {
            ImageGenerationApiContract.parseRequest(
                JSONObject()
                    .put("prompt", "portrait")
                    .put(
                        "loras",
                        JSONArray()
                            .put(JSONObject().put("id", id).put("multiplier", 1.0))
                            .put(JSONObject().put("id", id).put("multiplier", 0.5))
                    )
                    .toString()
            )
        }
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
    fun `stable runtime batch range is preserved for provider capability validation`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"batch","n":8}"""
        )

        assertEquals(8, request.imageCount)
        assertEquals(8, request.requestedControlsJson().getInt("n"))
        assertRejected("unsupported_image_count") {
            ImageGenerationApiContract.parseRequest("""{"prompt":"batch","n":9}""")
        }
    }

    @Test
    fun `batch response requires matching native count and contiguous outputs`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"batch","n":2}"""
        )
        val execution = strictExecution("STABLE_DIFFUSION_CPP")
            .put("batchCount", 2)
            .put("outputCount", 2)
            .put("n", 2)
            .put("samplingPassCount", 2)
            .put("actualSamplingPassCount", 2)
            .put("actualSamplingStepCount", 40)
            .put("totalUnetExecutionCount", 80)
            .put("actualDiffusionModelComputeCount", 80)
        execution.getJSONObject("nativeEffective")
            .put("batchCount", 2)
            .put("outputCount", 2)
            .put("n", 2)
            .put("samplingPassCount", 2)
            .put("totalUnetExecutionCount", 80)
        val data = JSONArray()
        repeat(2) { index ->
            data.put(
                JSONObject()
                    .put("index", index)
                    .put("b64_json", "iVBORw0KGgo=")
                    .put("mime_type", "image/png")
                    .put("width", 512)
                    .put("height", 512)
            )
        }
        execution.put("responseOutputEvidence", responseOutputEvidence(data))
        execution.bindPromptExecution(
            request = request,
            effectivePrompt = request.prompt,
            effectiveNegativePrompt = request.negativePrompt,
            languageCapability = "ENGLISH_DOMINANT"
        )
        val body = JSONObject()
            .put("request_id", "img-batch")
            .put("prompt_processing", directPromptProcessing(request))
            .put("execution", execution)
            .put("data", data)

        val parsed = ImageGenerationApiContract.parseResponse(
            expectedRequestId = "img-batch",
            expectedRequest = request,
            rawBody = body.toString()
        )

        assertEquals(2, parsed.data.length())

        val spoofed = JSONObject(execution.toString()).put("actualSamplingPassCount", 1)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                expectedRequestId = "img-batch",
                expectedRequest = request,
                rawBody = JSONObject(body.toString()).put("execution", spoofed).toString()
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
    fun `response image payload is canonically bound to byte evidence`() {
        val execution = strictExecution("QNN_HTP")
        val response = responseBody("img-output-binding", execution)
        ImageGenerationApiContract.parseResponse("img-output-binding", response.toString())

        response.getJSONArray("data").getJSONObject(0)
            .put("b64_json", Base64.getEncoder().encodeToString(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00)
            ))

        assertRejected("invalid_image_provider_response") {
            ImageGenerationApiContract.parseResponse("img-output-binding", response.toString())
        }
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
            responseBody("img-ok", strictExecution("STABLE_DIFFUSION_CPP"), request).toString()
        )

        val flatMismatch = strictExecution("STABLE_DIFFUSION_CPP").put("steps", 19)
        assertRejected("invalid_image_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-flat",
                request,
                responseBody("img-flat", flatMismatch, request).toString()
            )
        }

        val requestedMismatch = strictExecution("STABLE_DIFFUSION_CPP")
        requestedMismatch.put("steps", 19)
        requestedMismatch.getJSONObject("nativeEffective").put("steps", 19)
        assertRejected("image_control_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-requested",
                request,
                responseBody("img-requested", requestedMismatch, request).toString()
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
            strictExecution("STABLE_DIFFUSION_CPP"),
            request
        ).put("model", "different-model")

        assertRejected("image_model_identity_mismatch") {
            ImageGenerationApiContract.parseResponse("img-model", request, response.toString())
        }
    }

    @Test
    fun `authenticated response requires prompt processing bound to the exact request`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"a red cup","negative_prompt":"text"}"""
        )
        val valid = responseBody(
            "img-prompt-direct",
            strictExecution("STABLE_DIFFUSION_CPP"),
            request
        )

        val parsed = ImageGenerationApiContract.parseResponse(
            "img-prompt-direct",
            request,
            valid.toString()
        )
        assertEquals("DIRECT", parsed.promptProcessing?.method)
        assertEquals(request.prompt, parsed.promptProcessing?.effectivePrompt)

        listOf(
            "outer-sha" to { body: JSONObject ->
                body.getJSONObject("execution")
                    .put("nativePromptExecutionSha256", "b".repeat(64))
            },
            "nested-sha" to { body: JSONObject ->
                body.getJSONObject("execution").getJSONObject("nativeEffective")
                    .put("nativePromptExecutionSha256", "b".repeat(64))
            },
            "outer-stage" to { body: JSONObject ->
                body.getJSONObject("execution")
                    .put("nativePromptBindingStage", "conditioning_encoded")
            },
            "nested-stage" to { body: JSONObject ->
                body.getJSONObject("execution").getJSONObject("nativeEffective")
                    .put("nativePromptBindingStage", "conditioning_encoded")
            }
        ).forEach { (suffix, mutate) ->
            val requestId = "img-prompt-native-$suffix"
            val invalid = JSONObject(valid.toString()).put("request_id", requestId)
            mutate(invalid)
            assertRejected("invalid_image_execution_evidence") {
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    request,
                    invalid.toString()
                )
            }
        }

        assertRejected("invalid_prompt_processing_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-missing",
                request,
                responseBody("img-prompt-missing", strictExecution("STABLE_DIFFUSION_CPP")).toString()
            )
        }

        val originalMismatch = JSONObject(valid.toString())
            .put("request_id", "img-prompt-original-mismatch")
        originalMismatch.getJSONObject("prompt_processing").put("originalPrompt", "a blue cup")
        assertRejected("prompt_processing_request_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-original-mismatch",
                request,
                originalMismatch.toString()
            )
        }

        val effectiveMismatch = JSONObject(valid.toString())
            .put("request_id", "img-prompt-effective-mismatch")
        effectiveMismatch.getJSONObject("prompt_processing").put("effectivePrompt", "a blue cup")
        assertRejected("prompt_processing_execution_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-effective-mismatch",
                request,
                effectiveMismatch.toString()
            )
        }

        val nativePromptMismatch = JSONObject(valid.toString())
            .put("request_id", "img-prompt-native-content-mismatch")
        val forgedPromptSha256 = imagePromptExecutionSha256("a blue cup", request.negativePrompt.orEmpty())
        nativePromptMismatch.getJSONObject("execution")
            .put("nativePromptExecutionSha256", forgedPromptSha256)
            .getJSONObject("nativeEffective")
            .put("nativePromptExecutionSha256", forgedPromptSha256)
        assertRejected("prompt_processing_execution_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-native-content-mismatch",
                request,
                nativePromptMismatch.toString()
            )
        }
    }

    @Test
    fun `prompt processing v4 binds stable language topology and final model default negative`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"a red cup"}"""
        )
        val effectiveNegative = "text, watermark"
        val processing = directPromptProcessing(request)
            .put("effectiveNegativePrompt", effectiveNegative)
            .put("negativePromptSource", "MODEL_DEFAULT")
        val execution = strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
            request = request,
            effectivePrompt = request.prompt,
            effectiveNegativePrompt = effectiveNegative,
            languageCapability = "ENGLISH_DOMINANT"
        )
        val response = responseBody("img-prompt-model-default", execution)
            .put("prompt_processing", processing)

        val parsed = ImageGenerationApiContract.parseResponse(
            "img-prompt-model-default",
            request,
            response.toString()
        ).promptProcessing
        assertEquals("MODEL_DEFAULT", parsed?.negativePromptSource)
        assertEquals(effectiveNegative, parsed?.effectiveNegativePrompt)
        assertEquals("c".repeat(64), parsed?.promptLanguageBindingFingerprint)

        listOf(
            JSONObject(processing.toString()).apply { remove("negativePromptSource") } to
                "invalid_prompt_processing_evidence",
            JSONObject(processing.toString()).put("negativePromptSource", "USER") to
                "invalid_prompt_processing_evidence",
            JSONObject(processing.toString()).put(
                "promptLanguageBindingFingerprint",
                "e".repeat(64)
            ) to "prompt_processing_execution_mismatch",
            JSONObject(processing.toString()).put(
                "imageProfileBindingFingerprint",
                "d".repeat(64)
            ) to "prompt_processing_execution_mismatch",
            JSONObject(processing.toString()).put("effectiveNegativePrompt", JSONObject.NULL) to
                "invalid_prompt_processing_evidence"
        ).forEachIndexed { index, (invalidProcessing, expectedCode) ->
            val requestId = "img-prompt-model-default-invalid-$index"
            assertRejected(expectedCode) {
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    request,
                    responseBody(requestId, JSONObject(execution.toString()))
                        .put("prompt_processing", invalidProcessing)
                        .toString()
                )
            }
        }

        val wrongNativeHash = JSONObject(execution.toString()).put(
            "promptExecutionSha256",
            imagePromptExecutionSha256(request.prompt, "")
        )
        assertRejected("prompt_processing_execution_mismatch") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-model-default-wrong-hash",
                request,
                responseBody("img-prompt-model-default-wrong-hash", wrongNativeHash)
                    .put("prompt_processing", processing)
                    .toString()
            )
        }
    }

    @Test
    fun `authenticated response accepts native multilingual passthrough and strict local translation`() {
        val nativeRequest = ImageGenerationApiContract.parseRequest(
            """{"prompt":"一只红色杯子","negative_prompt":"不要文字"}"""
        )
        val nativeEvidence = directPromptProcessing(
            request = nativeRequest,
            method = "NATIVE_MULTILINGUAL"
        )
        val nativeResponse = responseBody(
            "img-prompt-native",
            strictExecution("MNN_DIFFUSION").bindPromptExecution(
                request = nativeRequest,
                effectivePrompt = nativeRequest.prompt,
                effectiveNegativePrompt = nativeRequest.negativePrompt,
                languageCapability = "NATIVE_MULTILINGUAL"
            )
        ).put("prompt_processing", nativeEvidence)
        assertEquals(
            "NATIVE_MULTILINGUAL",
            ImageGenerationApiContract.parseResponse(
                "img-prompt-native",
                nativeRequest,
                nativeResponse.toString()
            ).promptProcessing?.method
        )

        val sourcePrompt = "一只红色杯子放在蓝色桌子上，杯子左侧有两个绿色苹果"
        val sourceNegative = "不要人物，不要文字，不要多余水果"
        val translatedRequest = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("prompt", sourcePrompt)
                .put("negative_prompt", sourceNegative)
                .toString()
        )
        val translatedEvidence = translatedPromptProcessing(translatedRequest)
        val translatedResponse = responseBody(
            "img-prompt-translated",
            strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
                request = translatedRequest,
                effectivePrompt = translatedEvidence.getString("effectivePrompt"),
                effectiveNegativePrompt = translatedEvidence.getString("effectiveNegativePrompt"),
                languageCapability = "ENGLISH_DOMINANT"
            )
        ).put("prompt_processing", translatedEvidence)
        val parsed = ImageGenerationApiContract.parseResponse(
            "img-prompt-translated",
            translatedRequest,
            translatedResponse.toString()
        ).promptProcessing

        assertEquals("LOCAL_LLM_ZH_TO_EN", parsed?.method)
        assertEquals(
            "one red cup on a blue table, two green apples to the left of the cup",
            parsed?.effectivePrompt
        )
        assertEquals("people, text, extra fruit", parsed?.effectiveNegativePrompt)
        assertEquals(4, parsed?.translationContractVersion)

        listOf(
            JSONObject(translatedEvidence.toString()).apply { remove("translatorModelSha256") } to
                "invalid_prompt_processing_evidence",
            JSONObject(translatedEvidence.toString()).put("translationContractVersion", 3) to
                "invalid_prompt_processing_evidence",
            JSONObject(translatedEvidence.toString()).put("effectivePrompt", sourcePrompt) to
                "prompt_processing_execution_mismatch",
            JSONObject(translatedEvidence.toString()).put("imageProfileBindingFingerprint", "invalid") to
                "invalid_prompt_processing_evidence",
            JSONObject(translatedEvidence.toString()).put("translatorModelId", " ") to
                "invalid_prompt_processing_evidence"
        ).forEachIndexed { index, (invalidEvidence, expectedCode) ->
            val requestId = "img-prompt-translated-invalid-$index"
            val body = responseBody(
                requestId,
                strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
                    request = translatedRequest,
                    effectivePrompt = translatedEvidence.getString("effectivePrompt"),
                    effectiveNegativePrompt = translatedEvidence.getString("effectiveNegativePrompt"),
                    languageCapability = "ENGLISH_DOMINANT"
                )
            )
                .put("prompt_processing", invalidEvidence)
            assertRejected(expectedCode) {
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    translatedRequest,
                    body.toString()
                )
            }
        }
    }

    @Test
    fun `translated prompt processing rejects tampering of every proof hash`() {
        val request = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("prompt", "一只红色杯子放在蓝色桌子上，杯子左侧有两个绿色苹果")
                .put("negative_prompt", "不要人物，不要文字，不要多余水果")
                .toString()
        )
        val evidence = translatedPromptProcessing(request)
        val effectivePrompt = evidence.getString("effectivePrompt")
        val effectiveNegativePrompt = evidence.getString("effectiveNegativePrompt")
        val proofFields = listOf(
            "translationPlanSha256",
            "verificationReceiptSha256",
            "translationPhaseSystemPromptSha256",
            "verificationPhaseSystemPromptSha256",
            "translationProofFingerprint"
        )

        proofFields.forEachIndexed { index, field ->
            val requestId = "img-prompt-proof-tamper-$index"
            val invalidEvidence = JSONObject(evidence.toString()).put(field, "9".repeat(64))
            val execution = strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
                request = request,
                effectivePrompt = effectivePrompt,
                effectiveNegativePrompt = effectiveNegativePrompt,
                languageCapability = "ENGLISH_DOMINANT"
            )

            assertRejected("invalid_prompt_processing_evidence") {
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    request,
                    responseBody(requestId, execution)
                        .put("prompt_processing", invalidEvidence)
                        .toString()
                )
            }
        }
    }

    @Test
    fun `prompt processing cannot rewrite non Chinese fields or spoof encoder topology`() {
        val mixedRequest = ImageGenerationApiContract.parseRequest(
            """{"prompt":"a red cup","negative_prompt":"不要人物"}"""
        )
        val forged = translatedPromptProcessing(
            request = mixedRequest,
            effectivePrompt = "an armed spaceship",
            effectiveNegativePrompt = "people"
        )
        val forgedExecution = strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
            request = mixedRequest,
            effectivePrompt = "an armed spaceship",
            effectiveNegativePrompt = "people",
            languageCapability = "ENGLISH_DOMINANT"
        )
        assertRejected("invalid_prompt_processing_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-field-rewrite",
                mixedRequest,
                responseBody("img-prompt-field-rewrite", forgedExecution)
                    .put("prompt_processing", forged)
                    .toString()
            )
        }

        val chineseRequest = ImageGenerationApiContract.parseRequest(
            """{"prompt":"一只红色杯子"}"""
        )
        val spoofedNative = directPromptProcessing(
            request = chineseRequest,
            method = "NATIVE_MULTILINGUAL"
        )
        val englishDominantExecution = strictExecution("STABLE_DIFFUSION_CPP").bindPromptExecution(
            request = chineseRequest,
            effectivePrompt = chineseRequest.prompt,
            effectiveNegativePrompt = null,
            languageCapability = "ENGLISH_DOMINANT"
        )
        assertRejected("invalid_prompt_processing_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-prompt-topology-spoof",
                chineseRequest,
                responseBody("img-prompt-topology-spoof", englishDominantExecution)
                    .put("prompt_processing", spoofedNative)
                    .toString()
            )
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
            422,
            ImageGenerationProviderException.fromWorkerFailure(
                "lora_native_apply_incomplete",
                "adapter did not reach the graph"
            ).httpStatus
        )
        assertEquals(
            422,
            ImageGenerationProviderException.fromWorkerFailure(
                "execution_contract_unsupported",
                "the requested negative prompt cannot affect this execution"
            ).httpStatus
        )
        assertEquals(
            422,
            ImageGenerationProviderException.fromWorkerFailure(
                "prompt_weighting_execution_unsupported",
                "the selected text encoder cannot apply prompt weights"
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
        mapOf(
            "image_prompt_translation_busy" to 409,
            "image_prompt_translation_timeout" to 504,
            "image_prompt_translation_unavailable" to 503,
            "image_prompt_translation_failed" to 502,
            "image_prompt_translation_invalid" to 422,
            "image_prompt_translation_input_too_large" to 422,
            "image_prompt_translation_input_too_complex" to 422
        ).forEach { (code, expectedStatus) ->
            assertEquals(
                code,
                expectedStatus,
                ImageGenerationProviderException.fromWorkerFailure(code, "prompt processing failed")
                    .httpStatus
            )
        }
    }

    @Test
    fun `response proves requested LoRA identities multipliers and applied tensors`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val request = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("prompt", "portrait")
                .put(
                    "loras",
                    JSONArray().put(JSONObject().put("id", id).put("multiplier", 0.75))
                )
                .toString()
        )
        fun loraExecution(): JSONObject {
            val execution = strictExecution("STABLE_DIFFUSION_CPP")
            val loras = JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("sha256", "d".repeat(64))
                    .put("multiplier", 0.75)
            )
            val counts = JSONObject()
                .put("requestedCount", 1)
                .put("loadedCount", 1)
                .put("appliedCount", 1)
                .put("appliedTensorCount", 12)
            execution.put("loras", JSONArray(loras.toString()))
                .put("loraEvidence", JSONObject(counts.toString()))
            execution.getJSONObject("nativeEffective")
                .put("loras", JSONArray(loras.toString()))
                .put("loraEvidence", JSONObject(counts.toString()))
            return execution
        }

        ImageGenerationApiContract.parseResponse(
            "img-lora",
            request,
            responseBody("img-lora", loraExecution(), request).toString()
        )

        val missingTensorProof = loraExecution()
        missingTensorProof.getJSONObject("nativeEffective")
            .getJSONObject("loraEvidence")
            .put("appliedTensorCount", 0)
        assertRejected("invalid_lora_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-lora-bad",
                request,
                responseBody("img-lora-bad", missingTensorProof, request).toString()
            )
        }
    }

    @Test
    fun `response rejects LoRA tensor evidence when no adapter was requested`() {
        val request = ImageGenerationApiContract.parseRequest("""{"prompt":"plain portrait"}""")
        val execution = strictExecution("STABLE_DIFFUSION_CPP")
        val counts = JSONObject()
            .put("requestedCount", 0)
            .put("loadedCount", 0)
            .put("appliedCount", 0)
            .put("appliedTensorCount", 1)
        execution
            .put("loras", JSONArray())
            .put("loraEvidence", JSONObject(counts.toString()))
        execution.getJSONObject("nativeEffective")
            .put("loras", JSONArray())
            .put("loraEvidence", JSONObject(counts.toString()))

        assertRejected("invalid_lora_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-no-lora",
                request,
                responseBody("img-no-lora", execution, request).toString()
            )
        }
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
            responseBody("img-input", execution, request).toString()
        )

        execution.getJSONObject("nativeEffective").put("strength", 0.6499999761581421)
        execution.getJSONObject("imageInput").put("strength", 0.6499999761581421)
        ImageGenerationApiContract.parseResponse(
            "img-input-float",
            request,
            responseBody("img-input-float", execution, request).toString()
        )

        execution.getJSONObject("imageInput").put("strength", 0.5)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-input-bad",
                request,
                responseBody("img-input-bad", execution, request).toString()
            )
        }

        val zeroRequest = ImageGenerationApiContract.parseRequest(
            """{"prompt":"minimal edit","task_mode":"img2img","input_image":"data:image/png;base64,AAAA","strength":0.0}"""
        )
        execution.getJSONObject("nativeEffective").put("strength", 0.0)
        execution.getJSONObject("imageInput").put("strength", 0.0)
        ImageGenerationApiContract.parseResponse(
            "img-input-zero",
            zeroRequest,
            responseBody("img-input-zero", execution, zeroRequest).toString()
        )
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
            responseBody("img-control", controlExecution(), request).toString()
        )

        val privatePath = controlExecution()
        privatePath.getJSONObject("nativeEffective")
            .put("controlImagePath", "/data/user/0/private/control.img")
        assertRejected("private_image_input_path_exposed") {
            ImageGenerationApiContract.parseResponse(
                "img-control-path",
                request,
                responseBody("img-control-path", privatePath, request).toString()
            )
        }

        val privateOutputPath = strictExecution("QNN_HTP")
            .put(
                "outputs",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("path", "/data/user/0/private/output.png")
                )
            )
        assertRejected("private_image_input_path_exposed") {
            ImageGenerationApiContract.parseResponse(
                "img-output-path",
                responseBody("img-output-path", privateOutputPath).toString()
            )
        }

        val countMismatch = controlExecution()
        countMismatch.getJSONObject("imageInput").put("controlImageExecutionCount", 0)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-control-count",
                request,
                responseBody("img-control-count", countMismatch, request).toString()
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
                responseBody("img-control-hash", hashMismatch, request).toString()
            )
        }

        val strengthMismatch = controlExecution()
        strengthMismatch.getJSONObject("nativeEffective").put("controlStrength", 0.5)
        strengthMismatch.getJSONObject("imageInput").put("controlStrength", 0.5)
        assertRejected("invalid_image_input_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-control-strength",
                request,
                responseBody("img-control-strength", strengthMismatch, request).toString()
            )
        }
    }

    @Test
    fun `response binds requested textual inversions to consumed native evidence`() {
        val firstId = "11111111-1111-4111-8111-111111111111"
        val secondId = "22222222-2222-4222-8222-222222222222"
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"fixture prompt","textual_inversion_ids":["$firstId","$secondId"]}"""
        )

        fun execution(): JSONObject = strictExecution("STABLE_DIFFUSION_CPP")
            .withTextualInversionEvidence(listOf(firstId, secondId))

        val pathFreeExecution = execution()
        assertFalse(pathFreeExecution.has("textualInversionExecutionBundleRoot"))
        assertFalse(
            pathFreeExecution.getJSONObject("nativeEffective")
                .has("textualInversionExecutionBundleRoot")
        )
        ImageGenerationApiContract.parseResponse(
            "img-ti",
            request,
            responseBody("img-ti", pathFreeExecution, request).toString()
        )

        val consumedInBothPrompts = execution().apply {
            getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 4)
            getJSONObject("nativeEffective")
                .getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 4)
        }
        ImageGenerationApiContract.parseResponse(
            "img-ti-both-prompts",
            request,
            responseBody("img-ti-both-prompts", consumedInBothPrompts, request).toString()
        )

        val mismatchedConsumption = JSONObject(consumedInBothPrompts.toString()).apply {
            getJSONObject("nativeEffective")
                .getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 3)
        }
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-consumption-layers",
                request,
                responseBody("img-ti-consumption-layers", mismatchedConsumption, request).toString()
            )
        }

        val wrongIdentity = execution()
        wrongIdentity.getJSONArray("textualInversions")
            .getJSONObject(0)
            .put("id", secondId)
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-id",
                request,
                responseBody("img-ti-id", wrongIdentity, request).toString()
            )
        }

        val notConsumed = execution()
        notConsumed.getJSONObject("textualInversionEvidence").put("consumedMask", 0)
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-consume",
                request,
                responseBody("img-ti-consume", notConsumed, request).toString()
            )
        }

        val incomplete = execution()
        incomplete.getJSONObject("nativeEffective").remove("textualInversionEvidence")
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-incomplete",
                request,
                responseBody("img-ti-incomplete", incomplete, request).toString()
            )
        }

        listOf(
            "textualInversionExecutionAssetsSha256" to "f".repeat(64),
            "textualInversionExecutionRuntime" to "MNN_DIFFUSION",
            "textualInversionExecutionProfileFingerprint" to "f".repeat(64)
        ).forEachIndexed { index, (field, value) ->
            val mismatchedConsumerBinding = execution().apply {
                put(field, value)
                getJSONObject("nativeEffective").put(field, value)
            }
            assertRejected("invalid_textual_inversion_execution_evidence") {
                val requestId = "img-ti-consumer-binding-$index"
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    request,
                    responseBody(requestId, mismatchedConsumerBinding, request).toString()
                )
            }
        }

        val mutatedConsumerAsset = execution().apply {
            getJSONArray("textualInversionExecutionAssets").getJSONObject(0)
                .put("sha256", "f".repeat(64))
            getJSONObject("nativeEffective").getJSONArray("textualInversionExecutionAssets")
                .getJSONObject(0).put("sha256", "f".repeat(64))
        }
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-consumer-asset",
                request,
                responseBody("img-ti-consumer-asset", mutatedConsumerAsset, request).toString()
            )
        }

        listOf("a//b", "a/./b", "a/b/").forEachIndexed { index, invalidLabel ->
            val nonCanonicalLabel = execution().apply {
                getJSONArray("textualInversionExecutionAssets").getJSONObject(0)
                    .put("label", invalidLabel)
                getJSONObject("nativeEffective").getJSONArray("textualInversionExecutionAssets")
                    .getJSONObject(0).put("label", invalidLabel)
            }
            assertRejected("invalid_textual_inversion_execution_evidence") {
                val requestId = "img-ti-consumer-label-$index"
                ImageGenerationApiContract.parseResponse(
                    requestId,
                    request,
                    responseBody(requestId, nonCanonicalLabel, request).toString()
                )
            }
        }

        val forgedArtifactBinding = execution().apply {
            getJSONArray("textualInversions").getJSONObject(0)
                .put("bindingFingerprint", "f".repeat(64))
            getJSONObject("nativeEffective").getJSONArray("textualInversions").getJSONObject(0)
                .put("bindingFingerprint", "f".repeat(64))
        }
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-binding",
                request,
                responseBody("img-ti-binding", forgedArtifactBinding, request).toString()
            )
        }

        val forgedSelectionBinding = execution().apply {
            getJSONObject("textualInversionEvidence")
                .put("bindingFingerprint", "f".repeat(64))
            getJSONObject("nativeEffective").getJSONObject("textualInversionEvidence")
                .put("bindingFingerprint", "f".repeat(64))
        }
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-selection-binding",
                request,
                responseBody("img-ti-selection-binding", forgedSelectionBinding, request).toString()
            )
        }

        listOf("profileId" to "other.profile", "tokenizerFingerprint" to "d".repeat(64))
            .forEachIndexed { index, (field, value) ->
                val profileMismatch = execution().apply {
                    getJSONArray("textualInversions").getJSONObject(0).put(field, value)
                    getJSONObject("nativeEffective").getJSONArray("textualInversions")
                        .getJSONObject(0).put(field, value)
                    rebindTextualInversionEvidence()
                }
                assertRejected("invalid_textual_inversion_execution_evidence") {
                    val requestId = "img-ti-profile-mismatch-$index"
                    ImageGenerationApiContract.parseResponse(
                        requestId,
                        request,
                        responseBody(requestId, profileMismatch, request).toString()
                    )
                }
            }
    }

    @Test
    fun `qnn and mnn textual inversion responses use native embedding evidence`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"fixture prompt","textual_inversion_ids":["$id"]}"""
        )
        listOf("QNN_HTP" to true, "MNN_DIFFUSION" to false).forEach { (runtime, clipG) ->
            val execution = strictExecution(runtime)
                .withTextualInversionEvidence(listOf(id), clipGRequired = clipG)
            val requestId = "img-ti-${runtime.lowercase()}"
            ImageGenerationApiContract.parseResponse(
                requestId,
                request,
                responseBody(requestId, execution, request).toString()
            )
        }

        val unsupportedFormat = strictExecution("QNN_HTP")
            .withTextualInversionEvidence(listOf(id)).apply {
                getJSONArray("textualInversions").getJSONObject(0).put("format", "pytorch")
                getJSONObject("nativeEffective").getJSONArray("textualInversions")
                    .getJSONObject(0).put("format", "pytorch")
            }
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-qnn-format",
                request,
                responseBody("img-ti-qnn-format", unsupportedFormat, request).toString()
            )
        }
    }

    @Test
    fun `response validates consumer assets in native utf8 byte order`() {
        val id = "11111111-1111-4111-8111-111111111111"
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"fixture prompt","textual_inversion_ids":["$id"]}"""
        )
        val privateUse = ImageTextualInversionExecutionAsset(
            label = "\uE000.safetensors",
            sizeBytes = 1_024L,
            sha256 = "d".repeat(64)
        )
        val supplementary = ImageTextualInversionExecutionAsset(
            label = "\uD83D\uDE00.safetensors",
            sizeBytes = 2_048L,
            sha256 = "e".repeat(64)
        )
        val canonical = strictExecution("STABLE_DIFFUSION_CPP")
            .withTextualInversionEvidence(
                ids = listOf(id),
                executionAssets = listOf(privateUse, supplementary)
            )

        ImageGenerationApiContract.parseResponse(
            "img-ti-utf8-order",
            request,
            responseBody("img-ti-utf8-order", canonical, request).toString()
        )

        val reversed = strictExecution("STABLE_DIFFUSION_CPP")
            .withTextualInversionEvidence(
                ids = listOf(id),
                executionAssets = listOf(supplementary, privateUse)
            )
        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-utf8-order-reversed",
                request,
                responseBody("img-ti-utf8-order-reversed", reversed, request).toString()
            )
        }
    }

    @Test
    fun `response rejects scalar textual inversion evidence when none was requested`() {
        val request = ImageGenerationApiContract.parseRequest("""{"prompt":"fixture prompt"}""")
        val execution = strictExecution("STABLE_DIFFUSION_CPP")
            .put("textualInversionExecutionAssetsSha256", "a".repeat(64))

        assertRejected("invalid_textual_inversion_execution_evidence") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-unrequested-scalar",
                request,
                responseBody("img-ti-unrequested-scalar", execution, request).toString()
            )
        }

        val camelCasePrivateRoot = strictExecution("STABLE_DIFFUSION_CPP")
            .put("textualInversionExecutionBundleRoot", "not-a-path")
        assertRejected("private_image_input_path_exposed") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-unrequested-root",
                request,
                responseBody("img-ti-unrequested-root", camelCasePrivateRoot, request).toString()
            )
        }
        val compositePrivateRoot = strictExecution("STABLE_DIFFUSION_CPP")
            .put("textualInversionModelRootDigest", "not-a-path")
        assertRejected("private_image_input_path_exposed") {
            ImageGenerationApiContract.parseResponse(
                "img-ti-unrequested-model-root",
                request,
                responseBody(
                    "img-ti-unrequested-model-root",
                    compositePrivateRoot,
                    request
                ).toString()
            )
        }
    }

    private fun strictExecution(runtime: String): JSONObject {
        val nativePromptSha256 = imagePromptExecutionSha256("fixture prompt", "")
        val native = JSONObject()
            .put("profileId", "profile.image.v1")
            .put("profileRevision", 1)
            .put("modelFingerprint", "b".repeat(64))
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
            .put("nativePromptExecutionSha256", nativePromptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
            .put("embeddingDiskDataType", "GRAPH_INTERNAL")
            .put("vaeScalingLocation", "GRAPH_INTERNAL")
            .put("vaeScalingFactor", 0.18215)
            .put("width", 512)
            .put("height", 512)
            .put("seed", 7)
            .put("batchCount", 1)
            .put("graphName", "model")
            .put("fallback", false)
        if (runtime == "STABLE_DIFFUSION_CPP") {
            native
                .put("outputCount", 1)
                .put("n", 1)
                .put("samplingPassCount", 1)
                .put("totalUnetExecutionCount", 40)
        }
        return JSONObject(native.toString())
            .put("nativeEffective", native)
            .put("nativeExecution", true)
            .put("nativeGenerationSequence", 9L)
            .put("batchCount", 1)
            .put("fallback", false)
            .put("npuActive", runtime == "QNN_HTP")
            .put("qnnGraphExecution", runtime == "QNN_HTP")
            .put("responseOutputEvidence", responseOutputEvidence(strictImageData()))
            .apply {
                if (runtime == "STABLE_DIFFUSION_CPP") {
                    put("actualSamplingPassCount", 1)
                    put("actualSamplingStepCount", 20)
                    put("actualDiffusionModelComputeCount", 40)
                }
            }
    }

    private fun JSONObject.withTextualInversionEvidence(
        ids: List<String>,
        clipGRequired: Boolean = false,
        executionAssets: List<ImageTextualInversionExecutionAsset> = listOf(
            ImageTextualInversionExecutionAsset(
                label = "model.bin",
                sizeBytes = 4_096L,
                sha256 = "d".repeat(64)
            ),
            ImageTextualInversionExecutionAsset(
                label = "tokenizer.json",
                sizeBytes = 1_024L,
                sha256 = "e".repeat(64)
            )
        )
    ): JSONObject {
        val runtime = getString("runtime")
        val modelFingerprint = getString("modelFingerprint")
        val profileId = getString("profileId")
        val profileRevision = getLong("profileRevision")
        val profilePromptFingerprint = "c".repeat(64)
        val tokenizerFingerprint = imageTextualInversionExecutionAssetsSha256(
            runtime = runtime,
            profilePromptFingerprint = profilePromptFingerprint,
            assets = executionAssets
        )
        val executionAssetsJson = JSONArray().apply {
            executionAssets.forEach { asset ->
                put(
                    JSONObject()
                        .put("label", asset.label)
                        .put("sizeBytes", asset.sizeBytes)
                        .put("sha256", asset.sha256)
                )
            }
        }
        val artifacts = JSONArray()
        val bindings = mutableListOf<Pair<String, String>>()
        ids.forEachIndexed { index, id ->
            val trigger = "<ti$index>"
            val artifactSha256 = "a".repeat(64)
            val bindingFingerprint = imageTextualInversionBindingFingerprint(
                id = id,
                sha256 = artifactSha256,
                trigger = trigger,
                modelFingerprint = modelFingerprint,
                tokenizerFingerprint = tokenizerFingerprint,
                profileId = profileId,
                profileRevision = profileRevision,
                runtime = runtime
            )
            bindings += trigger to bindingFingerprint
            artifacts.put(
                JSONObject()
                    .put("id", id)
                    .put("trigger", trigger)
                    .put("sha256", artifactSha256)
                    .put("sizeBytes", 1_024L + index)
                    .put("format", "safetensors")
                    .put("modelFingerprint", modelFingerprint)
                    .put("tokenizerFingerprint", tokenizerFingerprint)
                    .put("profileId", profileId)
                    .put("profileRevision", profileRevision)
                    .put("runtime", runtime)
                    .put("bindingFingerprint", bindingFingerprint)
            )
        }
        val count = ids.size.toLong()
        val mask = if (ids.isEmpty()) 0L else (1L shl ids.size) - 1L
        val clipGMask = if (clipGRequired) mask else 0L
        val evidence = JSONObject()
            .put("requestedCount", count)
            .put("validatedCount", count)
            .put("loadAttemptCount", count)
            .put("loadedCount", count)
            .put("tokenizerMatchCount", count)
            .put("appliedCount", count)
            .put("appliedVectorCount", count)
            .put("conditioningConsumptionCount", count)
            .put("clipLAppliedCount", count)
            .put("clipGAppliedCount", java.lang.Long.bitCount(clipGMask).toLong())
            .put("requestedMask", mask)
            .put("loadedMask", mask)
            .put("tokenizerMatchMask", mask)
            .put("appliedMask", mask)
            .put("consumedMask", mask)
            .put("clipLMask", mask)
            .put("clipGMask", clipGMask)
            .put("clipGRequiredMask", clipGMask)
            .put("failureCode", "none")
            .put(
                "bindingFingerprint",
                if (ids.isEmpty()) "" else imageTextualInversionSelectionFingerprint(bindings)
            )
            .put(
                "nativeMode",
                if (ids.isEmpty()) {
                    "none"
                } else if (runtime == "STABLE_DIFFUSION_CPP") {
                    "SDCPP_CUSTOM_WORDS"
                } else {
                    "MNN_CLIP_INPUT_EMBEDDING"
                }
            )
            .put("bindingStage", if (ids.isEmpty()) "none" else "conditioning_consumed")
        put("textualInversions", JSONArray(artifacts.toString()))
        put("textualInversionEvidence", JSONObject(evidence.toString()))
        put("textualInversionExecutionAssets", JSONArray(executionAssetsJson.toString()))
        put("textualInversionExecutionAssetsSha256", tokenizerFingerprint)
        put("textualInversionExecutionRuntime", runtime)
        put("textualInversionExecutionProfileFingerprint", profilePromptFingerprint)
        getJSONObject("nativeEffective")
            .put("textualInversions", JSONArray(artifacts.toString()))
            .put("textualInversionEvidence", JSONObject(evidence.toString()))
            .put("textualInversionExecutionAssets", JSONArray(executionAssetsJson.toString()))
            .put("textualInversionExecutionAssetsSha256", tokenizerFingerprint)
            .put("textualInversionExecutionRuntime", runtime)
            .put("textualInversionExecutionProfileFingerprint", profilePromptFingerprint)
        return this
    }

    private fun JSONObject.rebindTextualInversionEvidence(): JSONObject {
        val runtime = getString("runtime")
        val artifacts = getJSONArray("textualInversions")
        val nativeArtifacts = getJSONObject("nativeEffective").getJSONArray("textualInversions")
        val bindings = buildList {
            for (index in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(index)
                val bindingFingerprint = imageTextualInversionBindingFingerprint(
                    id = artifact.getString("id"),
                    sha256 = artifact.getString("sha256"),
                    trigger = artifact.getString("trigger"),
                    modelFingerprint = artifact.getString("modelFingerprint"),
                    tokenizerFingerprint = artifact.getString("tokenizerFingerprint"),
                    profileId = artifact.getString("profileId"),
                    profileRevision = artifact.getLong("profileRevision"),
                    runtime = runtime
                )
                artifact.put("bindingFingerprint", bindingFingerprint)
                nativeArtifacts.getJSONObject(index).put("bindingFingerprint", bindingFingerprint)
                add(artifact.getString("trigger") to bindingFingerprint)
            }
        }
        val selectionFingerprint = imageTextualInversionSelectionFingerprint(bindings)
        getJSONObject("textualInversionEvidence")
            .put("bindingFingerprint", selectionFingerprint)
        getJSONObject("nativeEffective").getJSONObject("textualInversionEvidence")
            .put("bindingFingerprint", selectionFingerprint)
        return this
    }

    private fun responseBody(
        requestId: String,
        execution: JSONObject,
        request: ImageGenerationApiRequest? = null
    ): JSONObject =
        JSONObject()
            .put("request_id", requestId)
            .apply {
                request?.let {
                    execution.bindPromptExecution(
                        request = it,
                        effectivePrompt = it.prompt,
                        effectiveNegativePrompt = it.negativePrompt,
                        languageCapability = "ENGLISH_DOMINANT"
                    )
                    put("prompt_processing", directPromptProcessing(it))
                }
            }
            .put("execution", execution)
            .put("data", strictImageData())

    private fun JSONObject.bindPromptExecution(
        request: ImageGenerationApiRequest,
        effectivePrompt: String,
        effectiveNegativePrompt: String?,
        languageCapability: String
    ): JSONObject {
        val promptSha256 = imagePromptExecutionSha256(
            effectivePrompt,
            effectiveNegativePrompt.orEmpty()
        )
        getJSONObject("nativeEffective")
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
        return put("imageProfileBindingFingerprint", "a".repeat(64))
            .put("promptLanguageBindingFingerprint", "c".repeat(64))
            .put("textEncoderLanguageCapability", languageCapability)
            .put("promptExecutionSha256", promptSha256)
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
    }

    private fun directPromptProcessing(
        request: ImageGenerationApiRequest,
        method: String = "DIRECT"
    ): JSONObject = JSONObject()
        .put("version", 4)
        .put("originalPrompt", request.prompt)
        .put("effectivePrompt", request.prompt)
        .put("originalNegativePrompt", request.negativePrompt ?: JSONObject.NULL)
        .put("effectiveNegativePrompt", request.negativePrompt.orEmpty())
        .put("negativePromptSource", if (request.negativePrompt == null) "EMPTY" else "USER")
        .put("method", method)
        .put("imageProfileBindingFingerprint", "a".repeat(64))
        .put("promptLanguageBindingFingerprint", "c".repeat(64))

    private fun translatedPromptProcessing(
        request: ImageGenerationApiRequest,
        effectivePrompt: String =
            "one red cup on a blue table, two green apples to the left of the cup",
        effectiveNegativePrompt: String = "people, text, extra fruit"
    ): JSONObject {
        val negativePromptSource = if (request.negativePrompt == null) "EMPTY" else "USER"
        val resolvedEffectiveNegativePrompt = if (negativePromptSource == "EMPTY") {
            ""
        } else {
            effectiveNegativePrompt
        }
        val translationPlanSha256 = "d".repeat(64)
        val verificationReceiptSha256 = "e".repeat(64)
        val translationPhaseSystemPromptSha256 = "f".repeat(64)
        val verificationPhaseSystemPromptSha256 = "1".repeat(64)
        val translatorRuntime = "LLAMA_CPP"
        val translatorModelSha256 = "b".repeat(64)
        val promptLanguageBindingFingerprint = "c".repeat(64)
        val translationProofFingerprint = imagePromptTranslationProofFingerprint(
            contractVersion = 4,
            originalPrompt = request.prompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = request.negativePrompt,
            effectiveNegativePrompt = resolvedEffectiveNegativePrompt,
            negativePromptSource = negativePromptSource,
            translationPlanSha256 = translationPlanSha256,
            verificationReceiptSha256 = verificationReceiptSha256,
            translationPhaseSystemPromptSha256 = translationPhaseSystemPromptSha256,
            verificationPhaseSystemPromptSha256 = verificationPhaseSystemPromptSha256,
            translatorRuntime = translatorRuntime,
            translatorModelSha256 = translatorModelSha256,
            promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
        )
        return JSONObject()
            .put("version", 4)
            .put("originalPrompt", request.prompt)
            .put("effectivePrompt", effectivePrompt)
            .put("originalNegativePrompt", request.negativePrompt ?: JSONObject.NULL)
            .put("effectiveNegativePrompt", resolvedEffectiveNegativePrompt)
            .put("negativePromptSource", negativePromptSource)
            .put("method", "LOCAL_LLM_ZH_TO_EN")
            .put("translationContractVersion", 4)
            .put("imageProfileBindingFingerprint", "a".repeat(64))
            .put("promptLanguageBindingFingerprint", promptLanguageBindingFingerprint)
            .put("translatorModelId", "translator-model")
            .put("translatorModelName", "Translator Model")
            .put("translatorRuntime", translatorRuntime)
            .put("translatorModelSha256", translatorModelSha256)
            .put("translationPlanSha256", translationPlanSha256)
            .put("verificationReceiptSha256", verificationReceiptSha256)
            .put("translationPhaseSystemPromptSha256", translationPhaseSystemPromptSha256)
            .put("verificationPhaseSystemPromptSha256", verificationPhaseSystemPromptSha256)
            .put("translationProofFingerprint", translationProofFingerprint)
    }

    private fun strictImageData(): org.json.JSONArray =
        org.json.JSONArray().put(
            JSONObject()
                .put("b64_json", "iVBORw0KGgo=")
                .put("mime_type", "image/png")
                .put("width", 512)
                .put("height", 512)
        )

    private fun responseOutputEvidence(data: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            val bytes = Base64.getDecoder().decode(item.getString("b64_json"))
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            put(
                JSONObject()
                    .put("index", index)
                    .put("mimeType", item.getString("mime_type"))
                    .put("sizeBytes", bytes.size)
                    .put("sha256", sha256)
            )
        }
    }

    private fun imageUltraFixNoiseSeedFingerprint(seed: Long, steps: Long): String {
        val descriptor = "mca-ultrafix-quality-noise-v1|seed=${seed.toULong()}|steps=$steps"
        return MessageDigest.getInstance("SHA-256")
            .digest(descriptor.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun ultraFixResponseBody(
        requestId: String,
        request: ImageGenerationApiRequest,
        png: ByteArray,
        runtime: String = "STABLE_DIFFUSION_CPP"
    ): JSONObject = responseBody(
        requestId = requestId,
        execution = ultraFixExecution(request, png, runtime),
        request = request
    ).put("data", ultraFixImageData(request, png))

    private fun ultraFixExecution(
        request: ImageGenerationApiRequest,
        png: ByteArray,
        runtime: String
    ): JSONObject {
        val options = requireNotNull(request.ultraFix)
        val extendedSchema = runtime == "QNN_HTP"
        val inputSha256 = "8".repeat(64)
        val outputSha256 = MessageDigest.getInstance("SHA-256")
            .digest(png)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val inversionSteps = options.inversionSteps.toLong()
        val tileCount = if (extendedSchema) 6L else 9L
        val encoderTileCount = if (extendedSchema) tileCount else 4L
        val decoderTileCount = tileCount
        val inversionTileCount = inversionSteps * tileCount
        val refinementTileCount = inversionSteps * tileCount
        val positiveComputeCount = inversionTileCount + refinementTileCount
        val negativeComputeCount = refinementTileCount
        val physicalComputeCount = positiveComputeCount + negativeComputeCount
        val reportedRefinementTileCount = if (extendedSchema) {
            refinementTileCount + negativeComputeCount
        } else {
            refinementTileCount
        }

        fun stage(invocations: Long, tiles: Long, steps: Long): JSONObject = JSONObject()
            .put("invocationCount", invocations)
            .put("successCount", invocations)
            .put("tileInvocationCount", tiles)
            .put("tileSuccessCount", tiles)
            .put("stepCount", steps)

        val evidence = JSONObject()
            .put("version", if (extendedSchema) 2 else 5)
            .put("generationCompleted", true)
            .put("cancelled", false)
            .put("previewPublished", false)
            .put("sourceWidth", 512)
            .put("sourceHeight", 512)
            .put("targetWidth", options.targetWidth)
            .put("targetHeight", options.targetHeight)
            .put("sourceFit", "cover_center")
            .put("sourceResizedWidth", 1024)
            .put("sourceResizedHeight", 1024)
            .put("sourceCropLeft", 0)
            .put("sourceCropTop", 128)
            .put("tileSize", options.tileSize)
            .put("overlap", options.overlap)
            .put("inversionSteps", options.inversionSteps)
            .put("refinementSteps", options.refinementSteps)
            .put("denoiseStepCount", options.inversionSteps)
            .put("sampleMethod", "euler")
            .put("nativeScheduler", "default")
            .put("vaeEncode", stage(1L, encoderTileCount, 1L))
            .put("ddimInversion", stage(inversionSteps, inversionTileCount, inversionSteps))
            .put(
                "tiledUnetRefinement",
                stage(inversionSteps, reportedRefinementTileCount, inversionSteps)
            )
            .put("tiledVaeDecode", stage(1L, decoderTileCount, 1L))
            .put("physicalDiffusionModelComputeCount", physicalComputeCount)
            .put("qualityStepEvaluationCount", (inversionSteps - 1L).coerceAtLeast(0L))
            .put("noiseInjectionStepCount", (inversionSteps - 1L).coerceAtLeast(0L))
            .put(
                "noiseInjectionSeedFingerprint",
                imageUltraFixNoiseSeedFingerprint(7L, inversionSteps)
            )
            .put("noiseInjectionChecksum", "0123456789abcdef")
            .put("structureGuidanceStepCount", (inversionSteps - 1L).coerceAtLeast(0L))
            .put("structureGuidanceChecksum", "fedcba9876543210")
            .put("trajectoryNoiseChecksum", "13579bdf2468ace0")
            .apply {
                if (extendedSchema) {
                    put("tileCount", tileCount)
                    put("tilePlanSha256", "7b85cd6b0123b54c85226a27226048949da45a3d068e09f65955174c43b11ef7")
                    put("encoderGraphExecutionCount", encoderTileCount)
                    put("inversionPositiveGraphExecutionCount", inversionTileCount)
                    put("refinementPositiveGraphExecutionCount", refinementTileCount)
                    put("refinementNegativeGraphExecutionCount", negativeComputeCount)
                    put("decoderGraphExecutionCount", decoderTileCount)
                    put("outputSha256", outputSha256)
                    put("outputBytes", png.size)
                    put("outputAtomicCommit", true)
                }
            }

        val execution = strictExecution(runtime)
        val native = execution.getJSONObject("nativeEffective")
        listOf(execution, native).forEach { layer ->
            layer
                .put("width", options.targetWidth)
                .put("height", options.targetHeight)
                .put("steps", options.refinementSteps)
                .put("timetableCount", options.inversionSteps)
                .put("unetExecutionCount", physicalComputeCount)
                .put("totalUnetExecutionCount", physicalComputeCount)
                .put("strength", options.strength)
                .put("taskMode", "img2img")
                .put("inputImageExecutionCount", 1)
                .put("maskImageExecutionCount", 0)
                .put("controlImageExecutionCount", 0)
                .put("inputImageSha256", inputSha256)
                .put("ultraFix", JSONObject(evidence.toString()))
                .put("strengthMechanism", "ddim_inversion")
                .put("outputSha256", outputSha256)
                .put("outputSizeBytes", png.size)
                .put("outputAtomicCommit", true)
                .put("img2imgAddNoiseApplied", false)
                .put("img2imgNoiseChecksum", "0000000000000000")
        }
        native
            .put("positiveDiffusionModelComputeCount", positiveComputeCount)
            .put("negativeDiffusionModelComputeCount", negativeComputeCount)
            .put("auxiliaryDiffusionModelComputeCount", 0)
            .put("samplingPassCount", 1)
        execution
            .put("sampleMethod", "euler")
            .put("nativeScheduler", "default")
            .put("actualSamplingPassCount", 1)
            .put("actualSamplingStepCount", options.inversionSteps)
            .put("actualDiffusionModelComputeCount", physicalComputeCount)
            .put("actualPositiveDiffusionModelComputeCount", positiveComputeCount)
            .put("actualNegativeDiffusionModelComputeCount", negativeComputeCount)
            .put("actualAuxiliaryDiffusionModelComputeCount", 0)
            .put(
                "imageInput",
                JSONObject()
                    .put("nativeExecution", true)
                    .put("taskMode", "img2img")
                    .put("batchCount", 1)
                    .put("inputImageExecutionCount", 1)
                    .put("maskImageExecutionCount", 0)
                    .put("controlImageExecutionCount", 0)
                    .put("inputImage", JSONObject().put("sha256", inputSha256))
                    .put("strength", options.strength)
            )
        return execution.put(
            "responseOutputEvidence",
            responseOutputEvidence(ultraFixImageData(request, png))
        )
    }

    private fun ultraFixImageData(
        request: ImageGenerationApiRequest,
        png: ByteArray
    ): JSONArray {
        val options = requireNotNull(request.ultraFix)
        return JSONArray().put(
            JSONObject()
                .put("b64_json", Base64.getEncoder().encodeToString(png))
                .put("mime_type", "image/png")
                .put("width", options.targetWidth)
                .put("height", options.targetHeight)
        )
    }

    private fun ultraFixPng(marker: Int): ByteArray = ByteArray(96) { index ->
        ((marker + index * 17) and 0xff).toByte()
    }.also { bytes ->
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        ).copyInto(bytes)
    }

    private fun ultraFixRequest(): JSONObject = JSONObject()
        .put("prompt", "refine the source")
        .put("task_mode", "img2img")
        .put("input_image", "data:image/png;base64,AAAA")
        .put(
            "ultrafix",
            JSONObject()
                .put("target_width", 1024)
                .put("target_height", 768)
                .put("strength", 0.5)
                .put("inversion_steps", 5)
                .put("refinement_steps", 10)
                .put("tile_size", 512)
                .put("overlap", 0.25)
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

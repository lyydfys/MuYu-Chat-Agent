package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class McaLoopbackServerTest {
    @Test
    fun nonLoopbackListenerRequiresAuthenticationKey() {
        val server = McaLoopbackServer(port = freePort(), bindHost = "0.0.0.0", apiKey = "")
        try {
            server.start()
            throw AssertionError("Expected an unauthenticated non-loopback listener to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("requires a non-empty API key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oversizedRequestBodyIsRejectedBeforeProviderExecution() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                calls.incrementAndGet()
                error("provider must not run")
            }
            val request = "POST /v1/images/generations HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer secret\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${64L * 1024L * 1024L + 1L}\r\n\r\n"

            val response = rawHttp(port, request)

            assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"))
            assertTrue(response.contains("request_body_too_large"))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun unauthenticatedLargeBodyIsRejectedBeforeContentLengthAllocation() {
        withServer(apiKey = "secret") { port ->
            val request = "POST /v1/images/generations HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${64L * 1024L * 1024L + 1L}\r\n\r\n"

            val response = rawHttp(port, request)

            assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"))
            assertTrue(response.contains("unauthorized"))
        }
    }


    @Test
    fun authenticatedImagesApiUsesProductionProviderAndKeepsRequestIdentity() {
        val capturedRequestId = AtomicReference<String>()
        val capturedBody = AtomicReference<String>()
        val body = """{"prompt":"a ceramic cup","size":"512x512","steps":20}"""
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { requestId, requestBody ->
                capturedRequestId.set(requestId)
                capturedBody.set(requestBody)
                JSONObject()
                    .put("request_id", requestId)
                    .put("execution", strictImageExecution(runtime = "STABLE_DIFFUSION_CPP"))
                    .put("data", imageData())
                    .toString()
            }

            val response = rawHttp(
                port,
                authenticatedPost("/v1/images/generations", body = body)
            )
            val responseBody = responseJson(response)

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(capturedRequestId.get().startsWith("img-"))
            assertEquals(capturedRequestId.get(), responseBody.getString("request_id"))
            assertEquals(body, capturedBody.get())
        }
    }

    @Test
    fun bothAuthenticatedImagesRoutesPreserveRequestedControlsAndExecutionEvidence() {
        val capturedBodies = mutableListOf<JSONObject>()
        val body = JSONObject()
            .put("model", "image-model")
            .put("prompt", "a ceramic robot")
            .put("negative_prompt", "blur, low detail")
            .put("size", "512x512")
            .put("n", 1)
            .put("response_format", "b64_json")
            .put("seed", 20260717)
            .put("steps", 20)
            .put("cfg_scale", 7.0)
            .put("sampler", "dpmpp_2m")
            .toString()

        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { requestId, requestBody ->
                capturedBodies += JSONObject(requestBody)
                JSONObject()
                    .put("request_id", requestId)
                    .put("model", "image-model")
                    .put(
                        "execution",
                        strictImageExecution(runtime = "QNN_HTP")
                    )
                    .put("data", imageData())
                    .toString()
            }

            listOf("/v1/images/generations", "/images/generations").forEach { path ->
                val response = rawHttp(port, authenticatedPost(path, body = body))
                val responseBody = responseJson(response)
                val execution = responseBody.getJSONObject("execution")

                assertTrue(response.startsWith("HTTP/1.1 200 OK"))
                assertTrue(responseBody.getString("request_id").startsWith("img-"))
                assertEquals("dpmpp_2m", execution.getString("scheduler"))
                assertEquals(20, execution.getInt("steps"))
                assertEquals(7.0, execution.getDouble("cfgScale"), 0.0)
                assertEquals(20260717, execution.getInt("seed"))
                assertTrue(execution.getBoolean("nativeExecution"))
                assertTrue(execution.getBoolean("qnnGraphExecution"))
                assertFalse(execution.getBoolean("fallback"))
            }

            assertEquals(2, capturedBodies.size)
            capturedBodies.forEach { captured ->
                assertEquals("blur, low detail", captured.getString("negative_prompt"))
                assertEquals("dpmpp_2m", captured.getString("sampler"))
                assertEquals("512x512", captured.getString("size"))
                assertEquals(20, captured.getInt("steps"))
                assertEquals(7.0, captured.getDouble("cfg_scale"), 0.0)
                assertEquals(20260717, captured.getInt("seed"))
            }
        }
    }

    @Test
    fun imagesApiPreservesExplicitEmptyNegativePromptForTheProductionProvider() {
        val captured = AtomicReference<JSONObject>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { requestId, requestBody ->
                captured.set(JSONObject(requestBody))
                JSONObject()
                    .put("request_id", requestId)
                    .put("execution", strictImageExecution(runtime = "STABLE_DIFFUSION_CPP"))
                    .put("data", imageData())
                    .toString()
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/v1/images/generations",
                    body = """{"prompt":"disable defaults","negative_prompt":""}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(captured.get().has("negative_prompt"))
            assertEquals("", captured.get().getString("negative_prompt"))
        }
    }

    @Test
    fun imagesApiPreservesStructuredWorkerFailureStatusAndCode() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                throw ImageGenerationProviderException.fromWorkerFailure(
                    code = "unsupported_preview",
                    message = "Preview publication is not available."
                )
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/images/generations",
                    body = """{"prompt":"preview","preview":{"interval":2,"mode":"vae"}}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 422 Unprocessable Entity"))
            assertTrue(response.contains("unsupported_preview"))
            assertFalse(response.contains("image_generation_failed"))
        }
    }

    @Test
    fun authenticatedImagesApiAcceptsStrictQnnControlEvidenceWithoutPrivatePaths() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { requestId, _ ->
                JSONObject()
                    .put("request_id", requestId)
                    .put("execution", strictControlImageExecution())
                    .put("data", imageData())
                    .toString()
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/v1/images/generations",
                    body = """{"prompt":"edges","task_mode":"control","control_image":"data:image/png;base64,AAAA","control_strength":0.8}"""
                )
            )
            val execution = responseJson(response).getJSONObject("execution")

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(execution.getBoolean("npuActive"))
            assertEquals(1, execution.getJSONObject("imageInput").getInt("controlImageExecutionCount"))
            assertEquals(
                "b".repeat(64),
                execution.getJSONObject("nativeEffective").getString("controlImageSha256")
            )
            assertFalse(response.contains("controlImagePath"))
            assertFalse(response.contains("/data/user/0/"))
        }
    }

    @Test
    fun imagesApiRejectsOutOfRangeCountBeforeInvokingProvider() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                calls.incrementAndGet()
                "{}"
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/v1/images/generations",
                    body = """{"prompt":"too many images","n":9}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
            assertTrue(response.contains("unsupported_image_count"))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun imagesApiReturnsDedicatedBusyConflictWithoutInvokingProvider() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                calls.incrementAndGet()
                "{}"
            }
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    code = "generation_in_progress",
                    message = "A generation is already active.",
                    retryAfterMs = 1500L
                )
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/images/generations",
                    body = """{"prompt":"wait for the active request"}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("image_generation_busy"))
            assertTrue(response.contains("X-Retry-After-Ms: 1500"))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun imagesApiRejectsProviderResponseWithMismatchedIdentity() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                JSONObject()
                    .put("request_id", "img-wrong")
                    .put("execution", JSONObject())
                    .put("data", org.json.JSONArray())
                    .toString()
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/v1/images/generations",
                    body = """{"prompt":"identity check"}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            assertTrue(response.contains("image_request_identity_mismatch"))
        }
    }

    @Test
    fun imagesApiRejectsProviderThatSilentlySwitchesTheRequestedModel() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { requestId, _ ->
                JSONObject()
                    .put("request_id", requestId)
                    .put("model", "different-model")
                    .put("execution", strictImageExecution("STABLE_DIFFUSION_CPP"))
                    .put("data", imageData())
                    .toString()
            }

            val response = rawHttp(
                port,
                authenticatedPost(
                    "/v1/images/generations",
                    body = """{"model":"requested-model","prompt":"identity check"}"""
                )
            )

            assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            assertTrue(response.contains("image_model_identity_mismatch"))
        }
    }

    @Test
    fun imagesApiRequiresAuthenticationBeforeInvokingProvider() {
        val calls = AtomicInteger(0)
        val body = """{"prompt":"private prompt"}"""
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.imageGenerationProvider = { _, _ ->
                calls.incrementAndGet()
                "{}"
            }

            val response = rawHttp(
                port,
                "POST /v1/images/generations HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"))
            assertEquals(0, calls.get())
            assertFalse(response.contains("private prompt"))
        }
    }

    @Test
    fun idleRuntimeStateDoesNotClaimThatTheRuntimeIsBusy() {
        withServer(apiKey = "secret") { port ->
            val runtime = responseJson(rawHttp(port, authenticatedGet("/v1/mca/runtime")))

            assertFalse(runtime.getBoolean("busy"))
            assertEquals("idle", runtime.getString("code"))
            assertEquals("", runtime.getString("message"))
            assertEquals(0L, runtime.getLong("retry_after_ms"))
        }
    }

    @Test
    fun nonStreamingResponseIdIsPassedToContextAwareEngineProvider() {
        val capturedRequestId = AtomicReference<String>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatWithContextProvider = { _, context ->
                capturedRequestId.set(context.requestId)
                flowOf(
                    GenerateEvent.Chunk(text = "trace ok", stats = RuntimeStats(completionTokens = 1)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 1))
                )
            }

            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":false}"""
            val response = rawHttp(port, chatRequest(body))
            val responseId = Regex("\\\"id\\\":\\\"(chatcmpl-[A-Za-z0-9]+)\\\"")
                .find(response)
                ?.groupValues
                ?.get(1)

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertEquals(responseId, capturedRequestId.get())
            assertTrue(response.contains("\"requestId\":\"$responseId\""))

            val runtime = rawHttp(port, authenticatedGet("/v1/mca/runtime"))
            assertTrue(runtime.contains("\"requestId\":\"$responseId\""))
            assertFalse(runtime.contains("secret"))
            assertFalse(runtime.contains("\"content\":\"hi\""))
        }
    }

    @Test
    fun streamingResponseIdIsPassedToContextAwareEngineProvider() {
        val capturedRequestId = AtomicReference<String>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatWithContextProvider = { _, context ->
                capturedRequestId.set(context.requestId)
                flowOf(
                    GenerateEvent.Chunk(text = "stream trace ok", stats = RuntimeStats(completionTokens = 1)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 1))
                )
            }

            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val response = rawHttp(port, chatRequest(body))
            val responseId = Regex("\\\"id\\\":\\\"(chatcmpl-[A-Za-z0-9]+)\\\"")
                .find(response)
                ?.groupValues
                ?.get(1)

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertEquals(responseId, capturedRequestId.get())
            assertTrue(response.contains("data: [DONE]"))
            assertTrue(response.contains("\"requestId\":\"$responseId\""))
        }
    }

    @Test
    fun productVisionTracePublishesOnlyRequestBoundRedactedMediaEvidence() {
        val imageSha256 = "a".repeat(64)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatWithContextProvider = { _, context ->
                requireNotNull(context.visionDiagnosticSink).invoke(
                    "local_vision_input_prepared",
                    JSONObject()
                        .put("inputSha256", imageSha256.uppercase())
                        .put("preprocessing", "passthrough")
                        .put("nativeReadablePath", "D:\\private\\vision.png")
                        .put("prompt", "private-prompt-sentinel")
                        .put("apiKey", "private-token-sentinel")
                )
                flowOf(
                    GenerateEvent.Chunk(text = "vision trace ok", stats = RuntimeStats(completionTokens = 1)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 1))
                )
            }

            val response = rawHttp(port, chatRequest(imageChatBody(stream = false)))
            val completionJson = responseJson(response)
            val runtimeResponse = rawHttp(port, authenticatedGet("/v1/mca/runtime"))
            val metricsResponse = rawHttp(port, authenticatedGet("/metrics"))
            val runtime = responseJson(runtimeResponse)
            val metrics = responseJson(metricsResponse)
            val runtimeTrace = runtime.getJSONObject("trace")
            val metricsTrace = metrics.getJSONObject("requestTrace")
            val mediaTrace = runtimeTrace.getJSONObject("mediaTrace")

            assertTrue(completionJson.getString("id").startsWith("chatcmpl-"))
            assertEquals(completionJson.getString("id"), runtimeTrace.getString("requestId"))
            assertEquals(runtimeTrace.toString(), metricsTrace.toString())
            assertEquals(imageSha256, runtimeTrace.getString("apiImageSha256"))
            assertEquals(imageSha256, metrics.getString("apiImageSha256"))
            assertEquals(mediaTrace.toString(), metrics.getJSONObject("mediaTrace").toString())
            assertEquals(1, mediaTrace.getInt("schemaVersion"))
            assertEquals(1, mediaTrace.getInt("preparedCount"))
            assertEquals(0, mediaTrace.getInt("failedCount"))
            assertEquals(1, mediaTrace.getInt("preprocessingCount"))
            assertEquals(1, mediaTrace.getJSONObject("preprocessingCounts").getInt("passthrough"))
            assertEquals(imageSha256, mediaTrace.getString("inputSha256"))
            assertEquals(imageSha256, mediaTrace.getJSONArray("inputSha256s").getString(0))
            assertFalse(runtimeResponse.contains("private-prompt-sentinel"))
            assertFalse(runtimeResponse.contains("private-token-sentinel"))
            assertFalse(runtimeResponse.contains("vision.png"))
            assertFalse(metricsResponse.contains("private-prompt-sentinel"))
            assertFalse(metricsResponse.contains("private-token-sentinel"))
            assertFalse(metricsResponse.contains("vision.png"))
        }
    }

    @Test
    fun mediaTraceResetsForTheNextRequestAndRejectsStaleDiagnostics() {
        val firstContext = AtomicReference<LocalChatExecutionContext>()
        val secondContext = AtomicReference<LocalChatExecutionContext>()
        val firstSha256 = "b".repeat(64)
        val staleSha256 = "c".repeat(64)
        withServer(apiKey = "secret") {
            var callCount = 0
            LocalApiRuntime.streamChatWithContextProvider = { _, context ->
                callCount += 1
                if (callCount == 1) firstContext.set(context) else secondContext.set(context)
                flowOf(GenerateEvent.Done(RuntimeStats()))
            }

            LocalApiRuntime.streamChat(
                ChatRequest(emptyList()),
                LocalChatExecutionContext(
                    requestId = "ui-first",
                    visionDiagnosticSink = { _, _ -> error("caller sink failure must be isolated") }
                )
            )
            requireNotNull(firstContext.get().visionDiagnosticSink).invoke(
                "local_vision_input_prepared",
                JSONObject()
                    .put("inputSha256", firstSha256)
                    .put("preprocessing", "passthrough")
            )
            val firstTrace = JSONObject(LocalApiRuntime.traceJson())
            assertEquals(firstSha256, firstTrace.getString("uiImageSha256"))

            LocalApiRuntime.streamChat(
                ChatRequest(emptyList()),
                LocalChatExecutionContext(requestId = "ui-second")
            )
            requireNotNull(firstContext.get().visionDiagnosticSink).invoke(
                "local_vision_input_prepared",
                JSONObject()
                    .put("inputSha256", staleSha256)
                    .put("preprocessing", "passthrough")
            )
            requireNotNull(secondContext.get().visionDiagnosticSink).invoke(
                "local_vision_input_prepare_failed",
                JSONObject()
                    .put("inputSha256", staleSha256)
                    .put("preprocessing", "not_started")
                    .put("error", "D:\\private\\failure")
            )

            val secondTrace = JSONObject(LocalApiRuntime.traceJson())
            val secondMedia = secondTrace.getJSONObject("mediaTrace")
            assertEquals("ui-second", secondTrace.getString("requestId"))
            assertFalse(secondTrace.has("uiImageSha256"))
            assertEquals(0, secondMedia.getInt("preparedCount"))
            assertEquals(1, secondMedia.getInt("failedCount"))
            assertEquals(1, secondMedia.getInt("preprocessingCount"))
            assertEquals(1, secondMedia.getJSONObject("preprocessingCounts").getInt("not_started"))
            assertEquals(0, secondMedia.getJSONArray("inputSha256s").length())
            assertFalse(secondTrace.toString().contains("private"))
            assertFalse(secondTrace.toString().contains(staleSha256))

            LocalApiRuntime.clearRequestTrace()
            assertEquals(0, JSONObject(LocalApiRuntime.traceJson()).length())
        }
    }

    @Test
    fun multipleMediaDiagnosticsAggregateWithoutSingleImageAlias() {
        val firstSha256 = "d".repeat(64)
        val secondSha256 = "e".repeat(64)
        withServer(apiKey = "secret") {
            LocalApiRuntime.streamChatWithContextProvider = { _, context ->
                val sink = requireNotNull(context.visionDiagnosticSink)
                sink.invoke(
                    "local_vision_input_prepared",
                    JSONObject()
                        .put("inputSha256", firstSha256)
                        .put("preprocessing", "passthrough")
                )
                sink.invoke(
                    "local_vision_input_prepared",
                    JSONObject()
                        .put("inputSha256", secondSha256)
                        .put("preprocessing", "passthrough")
                )
                flowOf(GenerateEvent.Done(RuntimeStats()))
            }

            LocalApiRuntime.streamChat(
                ChatRequest(emptyList()),
                LocalChatExecutionContext(requestId = "ui-multi-image")
            )

            val trace = JSONObject(LocalApiRuntime.traceJson())
            val media = trace.getJSONObject("mediaTrace")
            val hashes = media.getJSONArray("inputSha256s")
            assertEquals(2, media.getInt("preparedCount"))
            assertEquals(0, media.getInt("failedCount"))
            assertEquals(2, media.getInt("preprocessingCount"))
            assertEquals(2, media.getJSONObject("preprocessingCounts").getInt("passthrough"))
            assertEquals(2, hashes.length())
            assertEquals(firstSha256, hashes.getString(0))
            assertEquals(secondSha256, hashes.getString(1))
            assertFalse(media.has("inputSha256"))
            assertFalse(trace.has("uiImageSha256"))
            assertFalse(trace.has("apiImageSha256"))
        }
    }

    @Test
    fun preflightAlwaysReturnsCorsHeaders() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(
                port,
                "OPTIONS /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://localhost\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            assertTrue(response.contains("Access-Control-Allow-Origin: *"))
            assertTrue(response.contains("Access-Control-Allow-Private-Network: true"))
        }
    }

    @Test
    fun healthAndWebPageDoNotRequireApiKey() {
        withServer(apiKey = "secret") { port ->
            val health = rawHttp(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val page = rawHttp(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertTrue(health.startsWith("HTTP/1.1 200 OK"))
            assertTrue(health.contains("MuYu Chat Agent"))
            assertTrue(page.startsWith("HTTP/1.1 200 OK"))
            assertTrue(page.contains("MCA Web Chat"))
            assertTrue(page.contains("id=\"imageInput\""))
            assertTrue(page.contains("image_url"))
        }
    }

    @Test
    fun protectedRoutesReturnOpenAiStyleErrorJsonWithoutKey() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(port, "GET /metrics HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"))
            assertTrue(response.contains("\"type\":\"mca_error\""))
            assertTrue(response.contains("\"code\":\"unauthorized\""))
        }
    }

    @Test
    fun modelsRouteDoesNotRequireApiKey() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.modelsJsonProvider = {
                """{"object":"list","data":[{"id":"local-vl","object":"model","owned_by":"local","vision_ready":true,"vision_projector":"mmproj-local-vl.gguf"}]}"""
            }
            val response = rawHttp(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("\"object\":\"list\""))
            assertTrue(response.contains("\"vision_ready\":true"))
            assertTrue(response.contains("\"vision_projector\":\"mmproj-local-vl.gguf\""))
        }
    }

    @Test
    fun modelsRouteRedactsPathsAndKeepsProfileStateScopedToEachModel() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = { """{"id":"active-model"}""" }
            LocalApiRuntime.generationParamsProvider = {
                GenerationParams(nCtx = 8190, nPredict = 64, systemPrompt = "")
            }
            LocalApiRuntime.nativeStatsJsonProvider = {
                """{"backend":"llama_cpp","loaded":true,"nCtx":8192,"maxAllTokens":8192}"""
            }
            LocalApiRuntime.modelsJsonProvider = {
                """{"object":"list","data":[
                    {"id":"active-model","path":"D:\\models\\active.gguf","n_ctx":32768,"context_length":32768,"max_output_tokens":4096},
                    {"id":"other-model","path":"/storage/emulated/0/other.gguf","n_ctx":32768,"profileId":"wrong-global-profile"}
                ]}"""
            }
            LocalApiRuntime.modelRuntimeStatesJsonProvider = {
                """{"other-model":{"profileId":"other-profile","profileRecordState":"committed","profileVerificationLevel":"safe","reloadRequired":true,"engineLifecycle":"unloaded","tuningJobState":"idle"}}"""
            }
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun profileJson(): String =
                    """{"modelId":"active-model","profileId":"active-profile","recordState":"committed","verification":"device_verified","reloadRequired":false,"engineLifecycle":"ready"}"""

                override fun tuningJson(): String =
                    """{"modelId":"active-model","jobId":"job-active","state":"succeeded"}"""
            }

            val response = rawHttp(port, "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val data = JSONObject(response.substringAfter("\r\n\r\n")).getJSONArray("data")
            val active = data.getJSONObject(0)
            val other = data.getJSONObject(1)

            assertFalse(response.contains("D:\\models"))
            assertFalse(response.contains("/storage/emulated"))
            assertFalse(active.has("path"))
            assertEquals(8190, active.getInt("n_ctx"))
            assertEquals(8190, active.getInt("context_length"))
            assertEquals(8190, active.getInt("max_context_length"))
            assertFalse(active.has("max_output_tokens"))
            assertEquals("active-profile", active.getString("profileId"))
            assertEquals("device_verified", active.getString("profileVerificationLevel"))
            assertEquals("succeeded", active.getString("tuningJobState"))
            assertEquals("other-profile", other.getString("profileId"))
            assertEquals("safe", other.getString("profileVerificationLevel"))
            assertTrue(other.getBoolean("reloadRequired"))
            assertFalse(other.toString().contains("active-profile"))
            assertFalse(other.toString().contains("wrong-global-profile"))
            assertFalse(other.has("n_ctx"))
            assertFalse(other.has("context_length"))
            assertFalse(other.has("max_context_length"))

            val metrics = responseJson(rawHttp(port, authenticatedGet("/metrics")))
            assertEquals(8192, metrics.getInt("nCtx"))
        }
    }

    @Test
    fun oversizedStreamingPromptReturns413BeforeSseOrGenerationStarts() {
        val generationCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.generationParamsProvider = {
                GenerationParams(nCtx = 512, nPredict = 16, systemPrompt = "")
            }
            LocalApiRuntime.streamChatProvider = {
                generationCalls.incrementAndGet()
                flowOf(GenerateEvent.Chunk("should not run", RuntimeStats()))
            }
            val body = JSONObject()
                .put("stream", true)
                .put(
                    "messages",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "长".repeat(400))
                    )
                )
                .toString()

            val response = rawHttp(port, chatRequest(body))
            val error = responseJson(response).getJSONObject("error")

            assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"))
            assertEquals("context_length_exceeded", error.getString("code"))
            assertEquals(512, error.getJSONObject("details").getInt("n_ctx"))
            assertEquals(512, error.getJSONObject("details").getInt("context_length"))
            assertFalse(response.contains("Content-Type: text/event-stream"))
            assertFalse(response.contains("data: [DONE]"))
            assertEquals(0, generationCalls.get())
        }
    }

    @Test
    fun compactCustomContextIsGuardedAgainstItsExactNativeSize() {
        val generationCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.generationParamsProvider = {
                GenerationParams(nCtx = 128, nPredict = 8, systemPrompt = "")
            }
            LocalApiRuntime.streamChatProvider = {
                generationCalls.incrementAndGet()
                flowOf(GenerateEvent.Chunk("should not run", RuntimeStats()))
            }
            val body = JSONObject()
                .put(
                    "messages",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "x".repeat(600))
                    )
                )
                .toString()

            val response = rawHttp(port, chatRequest(body))
            val details = responseJson(response).getJSONObject("error").getJSONObject("details")

            assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"))
            assertEquals(128, details.getInt("n_ctx"))
            assertEquals(128, details.getInt("context_length"))
            assertEquals(0, generationCalls.get())
        }
    }

    @Test
    fun downstreamContextOverflowIsNormalizedToStructured413() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.generationParamsProvider = {
                GenerationParams(nCtx = 8190, nPredict = 16, systemPrompt = "")
            }
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Error(
                        "当前输入约 9000 token，超过本机安全上下文预算。请缩短输入。",
                        RuntimeStats()
                    )
                )
            }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":false}"""

            val response = rawHttp(port, chatRequest(body))
            val error = responseJson(response).getJSONObject("error")

            assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"))
            assertEquals("context_length_exceeded", error.getString("code"))
            assertFalse(response.contains("generation_failed"))
        }
    }

    @Test
    fun metricsExposeCanonicalCoordinatorSignaturesWithoutProfileInternals() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.nativeStatsJsonProvider = {
                """{"backend":"llama_cpp","loaded":true,"modelFileSizeBytes":11686646144,"mmapFallbackAllowed":false,"mmapPrefetchEnabled":false,"mmap":true,"mlock":false,"generationSequence":17,"decodeTps":9.5,"modelPath":"D:\\private\\model.gguf","mnnDebugPrompt":"prompt-private","mnnDebugRawOutput":"output-private","loadedConfigJson":"{\"modelPath\":\"/data/private/model.gguf\"}","authToken":"token-private"}"""
            }
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun profileJson(): String =
                    """{"profileId":"profile-1","path":"D:\\private\\model.gguf","signatures":{"desired":"d","resolvedLoad":"r","activeLoaded":"a","committedExecution":"c","runtimeOverride":"NONE","effectiveExecution":"e"}}"""
            }

            val response = rawHttp(port, authenticatedGet("/metrics"))
            val metrics = JSONObject(response.substringAfter("\r\n\r\n"))
            val signatures = metrics.getJSONObject("coordinatorSignatures")

            assertEquals("d", signatures.getString("desired"))
            assertEquals("r", signatures.getString("resolved"))
            assertEquals("a", signatures.getString("active"))
            assertEquals("c", signatures.getString("committed"))
            assertEquals("NONE", signatures.getString("override"))
            assertEquals("e", signatures.getString("effective"))
            assertEquals("llama_cpp", metrics.getString("backend"))
            assertEquals(11_686_646_144L, metrics.getLong("modelFileSizeBytes"))
            assertFalse(metrics.getBoolean("mmapFallbackAllowed"))
            assertFalse(metrics.getBoolean("mmapPrefetchEnabled"))
            assertTrue(metrics.getBoolean("mmap"))
            assertFalse(metrics.getBoolean("mlock"))
            assertEquals(17L, metrics.getLong("generationSequence"))
            assertEquals(9.5, metrics.getDouble("decodeTps"), 0.0)
            assertFalse(response.contains("D:\\private"))
            assertFalse(response.contains("profile-1"))
            assertFalse(response.contains("prompt-private"))
            assertFalse(response.contains("output-private"))
            assertFalse(response.contains("/data/private"))
            assertFalse(response.contains("token-private"))
        }
    }

    @Test
    fun runtimeProfileTuningAndJobRoutesRedactPrivateCoordinatorFields() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    detailsJson = """{"deviceId":"device-private","activeJobId":"job-safe"}"""
                )

                override fun profileJson(): String =
                    """{"profileId":"profile-safe","identityHash":"identity-private","installationScopeId":"scope-private","apiKey":"key-private"}"""

                override fun tuningJson(): String =
                    """{"jobId":"job-safe","state":"recovering","rawPrompt":"prompt-private","authToken":"token-private","profile":{"identityHash":"nested-private"}}"""

                override fun tuningJob(jobId: String?): LocalApiControlResult =
                    LocalApiControlResult.Success(tuningJson())
            }

            val profile = rawHttp(port, authenticatedGet("/v1/mca/profile"))
            val tuning = rawHttp(port, authenticatedGet("/v1/mca/tuning"))
            val runtime = rawHttp(port, authenticatedGet("/v1/mca/runtime"))
            val job = rawHttp(port, authenticatedGet("/v1/tuning/jobs/current"))
            val combined = profile + tuning + runtime + job

            assertTrue(profile.contains("\"profileId\":\"profile-safe\""))
            assertTrue(tuning.contains("\"jobId\":\"job-safe\""))
            assertTrue(job.contains("\"tuningJobState\":\"recovering\""))
            listOf(
                "identity-private",
                "scope-private",
                "key-private",
                "prompt-private",
                "token-private",
                "nested-private",
                "device-private"
            ).forEach { secret -> assertFalse(combined.contains(secret)) }
            assertFalse(combined.contains("identityHash"))
            assertFalse(combined.contains("installationScopeId"))
            assertFalse(combined.contains("rawPrompt"))
            assertFalse(combined.contains("authToken"))
        }
    }

    @Test
    fun protectedRoutesAcceptXApiKeyHeader() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(
                port,
                "GET /metrics HTTP/1.1\r\nHost: 127.0.0.1\r\nX-API-Key: secret\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        }
    }

    @Test
    fun chatRouteReturnsJsonErrorWhenEngineIsUnavailable() {
        withServer(apiKey = "secret") { port ->
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":false}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            assertTrue(response.contains("\"code\":\"engine_unavailable\""))
        }
    }

    @Test
    fun streamingRequestWithRuntimeFieldsReturns409BeforeSseHeaders() {
        val generationCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = {
                generationCalls.incrementAndGet()
                flowOf(GenerateEvent.Chunk("should not run", RuntimeStats()))
            }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"n_ctx":32768,"n_gpu_layers":99}"""

            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("\"code\":\"parameter_scope_conflict\""))
            assertTrue(response.contains("\"allowed_scope\":\"generation_only\""))
            assertTrue(response.contains("n_ctx"))
            assertTrue(response.contains("n_gpu_layers"))
            assertFalse(response.contains("Content-Type: text/event-stream"))
            assertFalse(response.contains("data: [DONE]"))
            assertEquals(0, generationCalls.get())
        }
    }

    @Test
    fun busyCoordinatorReturns409AndRetryAfterBeforeGenerationStarts() {
        val generationCalls = AtomicInteger(0)
        val preflightCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    code = "tuning_in_progress",
                    message = "A tuning candidate is being validated.",
                    retryAfterMs = 2500,
                    detailsJson = """{"activeJobId":"job-7","engineLifecycle":"RELOADING"}"""
                )

                override fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult {
                    preflightCalls.incrementAndGet()
                    return LocalApiPreflightResult.Ready
                }
            }
            LocalApiRuntime.streamChatProvider = {
                generationCalls.incrementAndGet()
                flowOf(GenerateEvent.Chunk("should not run", RuntimeStats()))
            }
            val body = """{"model":"active-model","messages":[{"role":"user","content":"hi"}],"stream":true}"""

            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("Retry-After: 3"))
            assertTrue(response.contains("X-Retry-After-Ms: 2500"))
            assertTrue(response.contains("\"code\":\"tuning_in_progress\""))
            assertTrue(response.contains("\"activeJobId\":\"job-7\""))
            assertFalse(response.contains("Content-Type: text/event-stream"))
            assertEquals(0, preflightCalls.get())
            assertEquals(0, generationCalls.get())
        }
    }

    @Test
    fun coordinatorPreflightSeesRouteStreamingModelAndCanRejectBeforeSse() {
        val preflightCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult {
                    preflightCalls.incrementAndGet()
                    assertEquals("/v1/chat/completions", request.route)
                    assertTrue(request.streaming)
                    assertEquals("requested-model", request.requestedModel)
                    assertEquals("hi", request.chatRequest.messages.single().content)
                    return LocalApiPreflightResult.Rejected(
                        httpStatus = 409,
                        code = "model_mismatch",
                        message = "The requested model is not active.",
                        detailsJson = """{"activeModelId":"other-model"}"""
                    )
                }
            }
            val body = """{"model":"requested-model","messages":[{"role":"user","content":"hi"}],"stream":true}"""

            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("\"code\":\"model_mismatch\""))
            assertTrue(response.contains("\"activeModelId\":\"other-model\""))
            assertFalse(response.contains("Content-Type: text/event-stream"))
            assertEquals(1, preflightCalls.get())
        }
    }

    @Test
    fun authenticatedRuntimeControlRoutesExposeBusyProfileAndTuningJson() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    code = "tuning_in_progress",
                    retryAfterMs = 1200
                )

                override fun profileJson(): String =
                    """{"profileId":"profile-3","profileRecordState":"ACTIVE"}"""

                override fun tuningJson(): String =
                    """{"activeJobId":"job-9","tuningJobState":"RUNNING"}"""

                override fun tuningJob(jobId: String?): LocalApiControlResult =
                    LocalApiControlResult.Success(
                        """{"jobId":"job-9","tuningJobState":"RUNNING"}"""
                    )
            }

            val runtime = rawHttp(port, authenticatedGet("/v1/mca/runtime"))
            val profile = rawHttp(port, authenticatedGet("/v1/mca/profile"))
            val tuning = rawHttp(port, authenticatedGet("/v1/mca/tuning"))
            val currentJob = rawHttp(port, authenticatedGet("/v1/tuning/jobs/current"))

            assertTrue(runtime.startsWith("HTTP/1.1 200 OK"))
            assertTrue(runtime.contains("\"busy\":true"))
            assertTrue(runtime.contains("\"profileId\":\"profile-3\""))
            assertTrue(runtime.contains("\"activeJobId\":\"job-9\""))
            assertTrue(profile.contains("\"profileRecordState\":\"ACTIVE\""))
            assertTrue(tuning.contains("\"tuningJobState\":\"RUNNING\""))
            assertTrue(currentJob.startsWith("HTTP/1.1 200 OK"))
            assertTrue(currentJob.contains("\"activeJobId\":\"job-9\""))
        }
    }

    @Test
    fun tuningJobQueryAndActionsUseCoordinatorWhileRuntimeIsBusy() {
        val actionCalls = AtomicInteger(0)
        val lastQuery = AtomicReference("unset")
        val actionKeys = mutableListOf<String>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    code = "tuning_in_progress",
                    retryAfterMs = 5000,
                    detailsJson = """{"activeJobId":"job-42","engineLifecycle":"RELOADING"}"""
                )

                override fun tuningJob(jobId: String?): LocalApiControlResult {
                    lastQuery.set(jobId ?: "current")
                    return LocalApiControlResult.Success(
                        """{"jobId":"${jobId ?: "job-42"}","tuningJobState":"RUNNING","progress":0.5}"""
                    )
                }

                override fun pauseTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    actionCalls.incrementAndGet()
                    actionKeys += idempotencyKey
                    return LocalApiControlResult.Success(
                        """{"jobId":"$jobId","tuningJobState":"PAUSED"}"""
                    )
                }

                override fun resumeTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    actionCalls.incrementAndGet()
                    actionKeys += idempotencyKey
                    return LocalApiControlResult.Success(
                        """{"jobId":"$jobId","tuningJobState":"RUNNING"}"""
                    )
                }

                override fun cancelTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    actionCalls.incrementAndGet()
                    actionKeys += idempotencyKey
                    return LocalApiControlResult.Success(
                        """{"jobId":"$jobId","tuningJobState":"CANCELLED"}"""
                    )
                }
            }

            val current = rawHttp(port, authenticatedGet("/v1/tuning/jobs/current"))
            val query = rawHttp(port, authenticatedGet("/v1/tuning/jobs/job-42"))
            val pause = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-42/pause", "idem-pause"))
            val resume = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-42/resume", "idem-resume"))
            val cancel = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-42/cancel", "idem-cancel"))

            assertTrue(current.startsWith("HTTP/1.1 200 OK"))
            assertTrue(current.contains("\"tuningJobState\":\"RUNNING\""))
            assertTrue(query.startsWith("HTTP/1.1 200 OK"))
            assertEquals("job-42", lastQuery.get())
            assertTrue(pause.startsWith("HTTP/1.1 200 OK"))
            assertTrue(pause.contains("\"tuningJobState\":\"PAUSED\""))
            assertTrue(resume.contains("\"tuningJobState\":\"RUNNING\""))
            assertTrue(cancel.contains("\"tuningJobState\":\"CANCELLED\""))
            assertEquals(3, actionCalls.get())
            assertEquals(listOf("idem-pause", "idem-resume", "idem-cancel"), actionKeys)
            assertFalse(pause.startsWith("HTTP/1.1 409 Conflict"))
        }
    }

    @Test
    fun tuningJobRoutesRequireBearerAndIdempotencyKey() {
        val actionCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun cancelTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    actionCalls.incrementAndGet()
                    return LocalApiControlResult.Success("""{"jobId":"$jobId","tuningJobState":"CANCELLED"}""")
                }
            }

            val unauthorized = rawHttp(port, "GET /v1/tuning/jobs/current HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val missingKey = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-1/cancel"))

            assertTrue(unauthorized.startsWith("HTTP/1.1 401 Unauthorized"))
            assertTrue(unauthorized.contains("\"code\":\"unauthorized\""))
            assertTrue(missingKey.startsWith("HTTP/1.1 400 Bad Request"))
            assertTrue(missingKey.contains("\"code\":\"idempotency_key_required\""))
            assertEquals(0, actionCalls.get())
        }
    }

    @Test
    fun tuningCreateApplyAndRollbackUseAuthenticatedControlPlane() {
        val created = AtomicReference<LocalApiTuningJobCreateRequest>()
        val calls = mutableListOf<String>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    created.set(request)
                    calls += "create:$idempotencyKey"
                    return LocalApiControlResult.Success("""{"jobId":"job-created","tuningJobState":"QUEUED"}""", 201)
                }

                override fun applyTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    calls += "apply:$jobId:$idempotencyKey"
                    return LocalApiControlResult.Success("""{"jobId":"$jobId","applied":true}""")
                }

                override fun rollbackTuning(idempotencyKey: String): LocalApiControlResult {
                    calls += "rollback:$idempotencyKey"
                    return LocalApiControlResult.Success("""{"rolledBack":true}""")
                }
            }

            val createBody = """{"modelId":"qwen35 4b mnn bundle","mode":"standard","autoApply":false,"performancePreference":"balanced"}"""
            val create = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "idem-create", createBody))
            val apply = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-created/apply", "idem-apply"))
            val rollback = rawHttp(port, authenticatedPost("/v1/tuning/rollback", "idem-rollback"))

            assertTrue(create.startsWith("HTTP/1.1 201 Created"))
            assertEquals("qwen35 4b mnn bundle", created.get().modelId)
            assertEquals("standard", created.get().mode)
            assertFalse(created.get().autoApply)
            assertEquals("balanced", created.get().performancePreference)
            assertTrue(apply.contains("\"applied\":true"))
            assertTrue(rollback.contains("\"rolledBack\":true"))
            assertEquals(
                listOf("create:idem-create", "apply:job-created:idem-apply", "rollback:idem-rollback"),
                calls
            )
        }
    }

    @Test
    fun exactIdempotentRetryReplaysOriginalCreateResultOnlyOnce() {
        val calls = AtomicInteger(0)
        val persistedContext = AtomicReference<LocalApiIdempotencyContext>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotency: LocalApiIdempotencyContext
                ): LocalApiControlResult {
                    calls.incrementAndGet()
                    persistedContext.set(idempotency)
                    return LocalApiControlResult.Success(
                        """{"jobId":"job-once","state":"queued","autoApply":${request.autoApply}}""",
                        201
                    )
                }
            }
            val firstBody = """{"modelId":"model-a","mode":"standard","autoApply":false}"""
            val reorderedBody = """{"autoApply":false,"mode":"standard","modelId":"model-a"}"""

            val first = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "same-key", firstBody))
            val replay = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "same-key", reorderedBody))

            assertEquals(first, replay)
            assertTrue(replay.startsWith("HTTP/1.1 201 Created"))
            assertTrue(replay.contains("\"jobId\":\"job-once\""))
            assertEquals(1, calls.get())
            assertEquals("same-key", persistedContext.get().key)
            assertTrue(persistedContext.get().requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun actionBodyParticipatesInFingerprintAndUnknownFieldsAreRejected() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun cancelTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    calls.incrementAndGet()
                    return LocalApiControlResult.Success("""{"jobId":"$jobId","state":"canceling"}""")
                }
            }

            val accepted = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-a/cancel", "action-key"))
            val conflicting = rawHttp(
                port,
                authenticatedPost("/v1/tuning/jobs/job-a/cancel", "action-key", """{"force":true}""")
            )
            val unsupported = rawHttp(
                port,
                authenticatedPost("/v1/tuning/jobs/job-a/cancel", "new-action-key", """{"force":true}""")
            )

            assertTrue(accepted.startsWith("HTTP/1.1 200 OK"))
            assertTrue(conflicting.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(conflicting.contains("\"code\":\"idempotency_key_conflict\""))
            assertTrue(unsupported.startsWith("HTTP/1.1 400 Bad Request"))
            assertTrue(unsupported.contains("\"code\":\"unsupported_tuning_fields\""))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun powerSaveTuningModeReachesCoordinator() {
        val created = AtomicReference<LocalApiTuningJobCreateRequest>()
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    created.set(request)
                    return LocalApiControlResult.Success("""{"jobId":"power-job"}""", 201)
                }
            }
            val body = """{"modelId":"model-a","mode":"power_save","autoApply":false}"""

            val response = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "power-key", body))

            assertTrue(response.startsWith("HTTP/1.1 201 Created"))
            assertEquals("power_save", created.get().mode)
            assertFalse(created.get().autoApply)
        }
    }

    @Test
    fun idempotencyKeyReuseForDifferentRequestReturnsConflict() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    calls.incrementAndGet()
                    return LocalApiControlResult.Success("""{"jobId":"job-a"}""", 201)
                }
            }
            val firstBody = """{"modelId":"model-a","mode":"quick","autoApply":true}"""
            val conflictingBody = """{"modelId":"model-b","mode":"quick","autoApply":true}"""

            val first = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "reused-key", firstBody))
            val conflict = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "reused-key", conflictingBody))

            assertTrue(first.startsWith("HTTP/1.1 201 Created"))
            assertTrue(conflict.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(conflict.contains("\"code\":\"idempotency_key_conflict\""))
            assertTrue(conflict.contains("originalRequestFingerprint"))
            assertTrue(conflict.contains("conflictingRequestFingerprint"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun exactReplaySurvivesLoopbackServerRebindWithinAppProcess() {
        val calls = AtomicInteger(0)
        val firstPort = freePort()
        val secondPort = freePort()
        val firstServer = McaLoopbackServer(firstPort, "127.0.0.1", "secret")
        val secondServer = McaLoopbackServer(secondPort, "127.0.0.1", "secret")
        val body = """{"modelId":"model-a","mode":"quick","autoApply":true}"""
        try {
            firstServer.clearProcessIdempotencyCacheForTests()
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    calls.incrementAndGet()
                    return LocalApiControlResult.Success("""{"jobId":"job-rebind"}""", 201)
                }
            }
            firstServer.start()
            val first = rawHttp(
                firstPort,
                authenticatedPost("/v1/tuning/jobs", "rebind-key", body)
            )
            firstServer.shutdown()
            secondServer.start()
            val replay = rawHttp(
                secondPort,
                authenticatedPost("/v1/tuning/jobs", "rebind-key", body)
            )

            assertEquals(first, replay)
            assertEquals(1, calls.get())
        } finally {
            firstServer.shutdown()
            secondServer.shutdown()
            secondServer.clearProcessIdempotencyCacheForTests()
            LocalApiRuntime.controlPlane = null
        }
    }

    @Test
    fun lifecycleChangingTuningWritesReturnDetailedConflictWhileRuntimeIsBusy() {
        val mutationCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = LocalApiBusyState(
                    busy = true,
                    code = "tuning_in_progress",
                    message = "candidate is validating",
                    retryAfterMs = 2200,
                    detailsJson = """{"activeJobId":"job-running"}"""
                )

                override fun profileJson(): String =
                    """{"profileId":"profile-active","engineLifecycle":"reloading"}"""

                override fun tuningJson(): String =
                    """{"jobId":"job-running","state":"validating"}"""

                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    mutationCalls.incrementAndGet()
                    return LocalApiControlResult.Success("{}")
                }

                override fun applyTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult {
                    mutationCalls.incrementAndGet()
                    return LocalApiControlResult.Success("{}")
                }

                override fun rollbackTuning(idempotencyKey: String): LocalApiControlResult {
                    mutationCalls.incrementAndGet()
                    return LocalApiControlResult.Success("{}")
                }
            }
            val body = """{"modelId":"model-a","mode":"standard","autoApply":false}"""

            val create = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "busy-create", body))
            val apply = rawHttp(port, authenticatedPost("/v1/tuning/jobs/job-running/apply", "busy-apply"))
            val rollback = rawHttp(port, authenticatedPost("/v1/tuning/rollback", "busy-rollback"))

            listOf(create, apply, rollback).forEach { response ->
                assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
                assertTrue(response.contains("Retry-After: 3"))
                assertTrue(response.contains("\"activeJobId\":\"job-running\""))
                assertTrue(response.contains("\"tuningJobState\":\"validating\""))
                assertTrue(response.contains("\"engineLifecycle\":\"reloading\""))
                assertTrue(response.contains("\"activeProfileId\":\"profile-active\""))
                assertTrue(response.contains("\"retryAfterMs\":2200"))
            }
            assertEquals(0, mutationCalls.get())
        }
    }

    @Test
    fun transientLifecycleConflictDoesNotConsumeIdempotencyKey() {
        val busy = AtomicReference(true)
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun busyState(): LocalApiBusyState = if (busy.get()) {
                    LocalApiBusyState(
                        busy = true,
                        code = "model_reloading",
                        retryAfterMs = 100
                    )
                } else {
                    LocalApiBusyState.IDLE
                }

                override fun rollbackTuning(idempotencyKey: String): LocalApiControlResult {
                    calls.incrementAndGet()
                    return LocalApiControlResult.Success("""{"rolledBack":true}""")
                }
            }

            val blocked = rawHttp(port, authenticatedPost("/v1/tuning/rollback", "retry-key"))
            busy.set(false)
            val retried = rawHttp(port, authenticatedPost("/v1/tuning/rollback", "retry-key"))
            val replay = rawHttp(port, authenticatedPost("/v1/tuning/rollback", "retry-key"))

            assertTrue(blocked.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(retried.startsWith("HTTP/1.1 200 OK"))
            assertEquals(retried, replay)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun explicitJobQueryDoesNotMixGlobalStateAndCurrentKeepsRecoveringJob() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun tuningJob(jobId: String?): LocalApiControlResult =
                    if (jobId == null) {
                        LocalApiControlResult.Success(
                            """{"jobId":"job-recovering","state":"recovering","phase":"journal_recovery"}"""
                        )
                    } else {
                        LocalApiControlResult.Success(
                            """{"activeJobId":"unrelated-current","state":"running","job":{"jobId":"$jobId","state":"failed","phase":"canary"}}"""
                        )
                    }
            }

            val current = rawHttp(port, authenticatedGet("/v1/tuning/jobs/current"))
            val historical = rawHttp(port, authenticatedGet("/v1/tuning/jobs/job-old"))

            assertTrue(current.contains("\"jobId\":\"job-recovering\""))
            assertTrue(current.contains("\"tuningJobState\":\"recovering\""))
            assertTrue(historical.contains("\"jobId\":\"job-old\""))
            assertTrue(historical.contains("\"tuningJobState\":\"failed\""))
            assertFalse(historical.contains("unrelated-current"))
            assertFalse(historical.contains("\"state\":\"running\""))
        }
    }

    @Test
    fun explicitCurrentJobKeepsProgressHardGatesAndDiffEvidence() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun tuningJob(jobId: String?): LocalApiControlResult =
                    LocalApiControlResult.Success(
                        """{"jobId":"job-42","progress":{"completed":2,"total":4},"hardGates":{"safety":true,"signature":true},"diff":{"n_threads":[4,6]},"job":{"jobId":"job-42","state":"validating","phase":"canary"}}"""
                    )
            }

            val response = rawHttp(port, authenticatedGet("/v1/tuning/jobs/job-42"))
            val job = JSONObject(response.substringAfter("\r\n\r\n"))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertEquals(2, job.getJSONObject("progress").getInt("completed"))
            assertEquals(4, job.getJSONObject("progress").getInt("total"))
            assertTrue(job.getJSONObject("hardGates").getBoolean("safety"))
            assertTrue(job.getJSONObject("hardGates").getBoolean("signature"))
            assertEquals(6, job.getJSONObject("diff").getJSONArray("n_threads").getInt(1))
            assertEquals("validating", job.getString("tuningJobState"))
        }
    }

    @Test
    fun tuningCreateRejectsUnknownFieldsBeforeControlPlane() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
                override fun createTuningJob(
                    request: LocalApiTuningJobCreateRequest,
                    idempotencyKey: String
                ): LocalApiControlResult {
                    calls.incrementAndGet()
                    return LocalApiControlResult.Success("{}")
                }
            }
            val body = """{"modelId":"model","mode":"quick","autoApply":false,"n_ctx":8192}"""
            val response = rawHttp(port, authenticatedPost("/v1/tuning/jobs", "idem-create", body))

            assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
            assertTrue(response.contains("\"code\":\"unsupported_tuning_fields\""))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun formalTuningJobRouteDoesNotBypassMissingAppControlPlane() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(port, authenticatedGet("/v1/tuning/jobs/current"))

            assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            assertTrue(response.contains("\"code\":\"control_plane_unavailable\""))
        }
    }

    @Test
    fun streamingChatDoesNotStopWhenHiddenReasoningPrecedesVisibleContent() {
        val stopCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Chunk(
                        text = "",
                        stats = RuntimeStats(completionTokens = 128),
                        hiddenReasoning = true
                    ),
                    GenerateEvent.Chunk(
                        text = "visible answer",
                        stats = RuntimeStats(completionTokens = 130)
                    ),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 130))
                )
            }
            LocalApiRuntime.stopGenerationProvider = { stopCalls.incrementAndGet() }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"hide_reasoning":true}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("visible answer"))
            assertTrue(response.contains("data: [DONE]"))
            assertFalse(response.contains("[MCA 提示]"))
            assertEquals(0, stopCalls.get())
        }
    }

    @Test
    fun streamingChatAddsDoneWhenProviderCompletesWithoutDoneEvent() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = { flowOf(GenerateEvent.Chunk(text = "partial", stats = RuntimeStats(completionTokens = 1))) }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("partial"))
            assertTrue(response.contains("data: [DONE]"))
        }
    }

    @Test
    fun streamingChatReportsEmptyVisibleOutputForHiddenReasoningOnlyFlowWithoutStoppingGeneration() {
        val stopCalls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Chunk(
                        text = "",
                        stats = RuntimeStats(completionTokens = 256),
                        hiddenReasoning = true
                    )
                )
            }
            LocalApiRuntime.stopGenerationProvider = { stopCalls.incrementAndGet() }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"hide_reasoning":true}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("generation_empty_visible_output"))
            assertFalse(response.contains("[MCA 提示]"))
            assertTrue(response.contains("data: [DONE]"))
            assertEquals(0, stopCalls.get())
        }
    }

    @Test
    fun streamingChatClosesWithDoneEvenWhenProviderErrors() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = {
                flowOf(GenerateEvent.Error("native stopped", RuntimeStats(lastError = "native stopped")))
            }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("native stopped"))
            assertTrue(response.contains("data: [DONE]"))
        }
    }

    @Test
    fun nonStreamingLoadBoundErrorReturnsStructuredConflict() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Error(
                        message = "本次请求修改了需要重新加载模型的参数，请先应用参数并重新加载模型。",
                        stats = RuntimeStats(),
                        code = "model_reload_required",
                        changedFields = setOf("n_ctx"),
                        action = "apply_and_reload"
                    )
                )
            }
            val body = """{"messages":[{"role":"user","content":"hi"}]}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("model_reload_required"))
            assertTrue(response.contains("apply_and_reload"))
            assertTrue(response.contains("changedFields"))
            assertTrue(response.contains("n_ctx"))
            assertFalse(response.contains("Load-bound request fields"))
        }
    }

    @Test
    fun streamingChatClosesWithDoneForEmptyProviderFlow() {
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.streamChatProvider = { emptyFlow() }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("data: [DONE]"))
        }
    }

    @Test
    fun streamingReadyMnnVisionRequestDoesNotRequireLocalCertification() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"qwen35-mnn","displayName":"通义千问 3.5-0.8B MNN","runtime":"mnn","vision_validated":false,"stats":{"backend":"mnn_cpu","visionReady":true}}"""
            }
            LocalApiRuntime.modelsJsonProvider = {
                """{"object":"list","data":[{"id":"qwen35-mnn","runtime":"mnn","vision_ready":true,"vision_validated":false}]}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(
                    GenerateEvent.Chunk(text = "mnn vision open", stats = RuntimeStats(completionTokens = 1)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 1))
                )
            }
            val body = imageChatBody(stream = true)
            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("mnn vision open"))
            assertTrue(response.contains("data: [DONE]"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun nonStreamingReadyMnnVisionRequestDoesNotRequireLocalCertification() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"qwen35-mnn","displayName":"通义千问 3.5-0.8B MNN","runtime":"mnn","vision_validated":false,"stats":{"backend":"mnn_cpu","visionReady":true}}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(
                    GenerateEvent.Chunk(text = "mnn vision open", stats = RuntimeStats(completionTokens = 1)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 1))
                )
            }
            val body = imageChatBody(stream = false)
            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("mnn vision open"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun mnnVisionRequestWithoutVisualComponentReturnsModelSpecificNotReadyError() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"qwen35-mnn-text","displayName":"通义千问 3.5-2B MNN","runtime":"mnn","vision_validated":false,"stats":{"backend":"mnn_cpu","visionReady":false}}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(GenerateEvent.Chunk(text = "should not run", stats = RuntimeStats(completionTokens = 1)))
            }
            val body = imageChatBody(stream = true)
            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 409 Conflict"))
            assertTrue(response.contains("\"code\":\"mnn_vision_not_ready\""))
            assertTrue(response.contains("通义千问 3.5-2B MNN"))
            assertFalse(response.contains("Content-Type: text/event-stream"))
            assertFalse(response.contains("data: [DONE]"))
            assertFalse(response.contains("should not run"))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun validatedMnnVisionRequestPassesToProvider() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"mnn-validated","displayName":"MNN 图文验证模型","runtime":"mnn","vision_ready":true,"vision_validated":true,"stats":{"backend":"mnn_cpu","visionReady":true}}"""
            }
            LocalApiRuntime.modelsJsonProvider = {
                """{"object":"list","data":[{"id":"mnn-validated","runtime":"mnn","vision_ready":true,"vision_validated":true}]}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(
                    GenerateEvent.Chunk(text = "mnn vision ok", stats = RuntimeStats(completionTokens = 2)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 2))
                )
            }

            val response = rawHttp(port, chatRequest(imageChatBody(stream = true)))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("mnn vision ok"))
            assertTrue(response.contains("data: [DONE]"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun textOnlyMnnRequestStillStreamsNormally() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"qwen35-mnn","runtime":"mnn","stats":{"backend":"mnn_cpu","visionReady":true}}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(
                    GenerateEvent.Chunk(text = "mnn text ok", stats = RuntimeStats(completionTokens = 3)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 3))
                )
            }
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("mnn text ok"))
            assertTrue(response.contains("data: [DONE]"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun miniCpmVisionRequestStillPassesToProvider() {
        val calls = AtomicInteger(0)
        withServer(apiKey = "secret") { port ->
            LocalApiRuntime.loadedModelJsonProvider = {
                """{"id":"minicpm-v","displayName":"MiniCPM-V 4.6 Q4_K_M","runtime":"llama_cpp","vision_ready":true,"vision_validated":true,"stats":{"backend":"llama_cpp","visionReady":true}}"""
            }
            LocalApiRuntime.streamChatProvider = {
                calls.incrementAndGet()
                flowOf(
                    GenerateEvent.Chunk(text = "vision ok", stats = RuntimeStats(completionTokens = 2)),
                    GenerateEvent.Done(RuntimeStats(completionTokens = 2))
                )
            }
            val body = imageChatBody(stream = true)
            val response = rawHttp(port, chatRequest(body))

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("vision ok"))
            assertTrue(response.contains("data: [DONE]"))
            assertEquals(1, calls.get())
        }
    }

    private fun withServer(apiKey: String, block: (Int) -> Unit) {
        val port = freePort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = apiKey)
        try {
            server.clearProcessIdempotencyCacheForTests()
            LocalApiRuntime.engine = null
            LocalApiRuntime.nativeStatsJsonProvider = null
            LocalApiRuntime.streamChatProvider = null
            LocalApiRuntime.streamChatWithContextProvider = null
            LocalApiRuntime.stopGenerationProvider = null
            LocalApiRuntime.imageGenerationProvider = null
            LocalApiRuntime.controlPlane = null
            LocalApiRuntime.clearRequestTrace()
            LocalApiRuntime.loadedModelJsonProvider = { "{}" }
            LocalApiRuntime.generationParamsProvider = { GenerationParams() }
            LocalApiRuntime.modelsJsonProvider = { """{"object":"list","data":[]}""" }
            LocalApiRuntime.modelRuntimeStatesJsonProvider = { "{}" }
            server.start()
            block(port)
        } finally {
            LocalApiRuntime.engine = null
            LocalApiRuntime.nativeStatsJsonProvider = null
            LocalApiRuntime.streamChatProvider = null
            LocalApiRuntime.streamChatWithContextProvider = null
            LocalApiRuntime.stopGenerationProvider = null
            LocalApiRuntime.imageGenerationProvider = null
            LocalApiRuntime.controlPlane = null
            LocalApiRuntime.clearRequestTrace()
            LocalApiRuntime.loadedModelJsonProvider = { "{}" }
            LocalApiRuntime.generationParamsProvider = { GenerationParams() }
            LocalApiRuntime.modelsJsonProvider = { """{"object":"list","data":[]}""" }
            LocalApiRuntime.modelRuntimeStatesJsonProvider = { "{}" }
            server.shutdown()
        }
    }

    private fun chatRequest(body: String): String =
        "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Authorization: Bearer secret\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
            body

    private fun authenticatedGet(path: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Authorization: Bearer secret\r\n\r\n"

    private fun authenticatedPost(
        path: String,
        idempotencyKey: String? = null,
        body: String = ""
    ): String =
        buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Authorization: Bearer secret\r\n")
            idempotencyKey?.let { append("Idempotency-Key: $it\r\n") }
            if (body.isNotEmpty()) append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(body)
        }

    private fun imageChatBody(stream: Boolean): String =
        """{"messages":[{"role":"user","content":[{"type":"text","text":"describe"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}],"stream":$stream}"""

    private fun strictImageExecution(runtime: String): JSONObject {
        val nativeEffective = JSONObject()
            .put("profileId", "profile.image.v1")
            .put("profileRevision", 3)
            .put("modelFingerprint", "sha256:abc")
            .put("runtime", runtime)
            .put("scheduler", "dpmpp_2m")
            .put("predictionType", "epsilon")
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
            .put("seed", 20260717)
            .put("batchCount", 1)
            .put("graphName", "model")
            .put("fallback", false)
        if (runtime == "STABLE_DIFFUSION_CPP") {
            nativeEffective
                .put("outputCount", 1)
                .put("n", 1)
                .put("samplingPassCount", 1)
                .put("totalUnetExecutionCount", 40)
        }
        return JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)
            .put("backend", if (runtime == "QNN_HTP") "qnn_htp" else "native")
            .put("nativeGenerationSequence", 44L)
            .put("workerPid", 4321)
            .put("nativeExecution", true)
            .put("batchCount", 1)
            .put("fallback", false)
            .put("npuActive", runtime == "QNN_HTP")
            .put("qnnGraphExecution", runtime == "QNN_HTP")
            .put("outputBytes", 1024L)
            .apply {
                if (runtime == "STABLE_DIFFUSION_CPP") {
                    put("actualSamplingPassCount", 1)
                    put("actualSamplingStepCount", 20)
                    put("actualDiffusionModelComputeCount", 40)
                }
            }
    }

    private fun strictControlImageExecution(): JSONObject {
        val sha = "b".repeat(64)
        val execution = strictImageExecution("QNN_HTP")
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

    private fun imageData(): org.json.JSONArray =
        org.json.JSONArray().put(
            JSONObject()
                .put("b64_json", "iVBORw0KGgo=")
                .put("mime_type", "image/png")
                .put("width", 512)
                .put("height", 512)
        )

    private fun rawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun responseJson(response: String): JSONObject =
        JSONObject(response.substringAfter("\r\n\r\n"))

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

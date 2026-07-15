package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.muyuchat.core.modelstore.QairtExecutionAdmissionMode
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.telemetry.MemorySnapshot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McaInferenceServiceLoadRecoveryTest {
    @Test
    fun exactVerifiedQairtIdentityAllowsNormalHandleCreation() = runBlocking {
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val bundle = qairtBundle(kvSpan = 4_095)
        val identity = QairtBundleRuntimeIdentity(
            bundleSha256 = "qwen3-vl-4b-bundle-sha",
            chipset = "SM8750P",
            runtimeFingerprint = "geniex-qairt/test-runtime"
        )
        val verificationStore = QairtExecutionVerificationStore(
            File(bundle, "qairt-verifications.json")
        )
        verificationStore.recordVerified(identity)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to FakeLocalChatRunner(),
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = { constrainedMemorySnapshot() },
            qairtVerificationStoreOverride = verificationStore,
            qairtIdentityProviderOverride = { requestedSha ->
                identity.takeIf { it.bundleSha256 == requestedSha }
            }
        )

        service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 512, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256
        ).getOrThrow()

        assertEquals(1, qairtRunner.loadCalls)
        assertEquals(QairtExecutionAdmissionMode.VERIFIED_ALLOW, service.qairtExecutionAdmission?.mode)
    }

    @Test
    fun constrainedRamFullKvQairtGraphUsesIsolatedDryRunInsteadOfStaticBlock() = runBlocking {
        val mnnRunner = FakeLocalChatRunner()
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val bundle = qairtBundle(kvSpan = 4_095)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to mnnRunner,
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = { constrainedMemorySnapshot() }
        )

        val result = service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 512, nThreads = 4)
        )

        assertTrue(result.isFailure)
        assertEquals(0, qairtRunner.loadCalls)
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, service.qairtExecutionAdmission?.mode)
        assertTrue(result.exceptionOrNull() is QairtIsolatedDryRunRequiredException)
    }

    @Test
    fun constrainedRamAllowsSegmentedQairtGraph() = runBlocking {
        val mnnRunner = FakeLocalChatRunner()
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val bundle = qairtBundle(kvSpan = 1_023)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to mnnRunner,
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = { constrainedMemorySnapshot() }
        )

        val result = service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 512, nThreads = 4)
        )

        assertTrue(result.isFailure)
        assertEquals(0, qairtRunner.loadCalls)
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, service.qairtExecutionAdmission?.mode)
    }

    @Test
    fun unknownDeviceMemoryUsesIsolatedDryRunInsteadOfStaticBlock() = runBlocking {
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to FakeLocalChatRunner(),
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = { MemorySnapshot() }
        )

        val result = service.loadModel(
            modelPath = qairtBundle(kvSpan = 1_023).absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 512, nThreads = 4)
        )

        assertTrue(result.isFailure)
        assertEquals(0, qairtRunner.loadCalls)
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, service.qairtExecutionAdmission?.mode)
        assertTrue(service.qairtExecutionAdmission?.memoryAdvisory.orEmpty().contains("unavailable"))
    }

    @Test
    fun lowAvailableMemoryUsesIsolatedDryRunInsteadOfStaticBlock() = runBlocking {
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to FakeLocalChatRunner(),
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = {
                MemorySnapshot(
                    totalMemKb = 16L * 1024L * 1024L,
                    availMemKb = 1L * 1024L * 1024L
                )
            }
        )

        val result = service.loadModel(
            modelPath = qairtBundle(kvSpan = 1_023).absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 512, nThreads = 4)
        )

        assertTrue(result.isFailure)
        assertEquals(0, qairtRunner.loadCalls)
        assertEquals(QairtExecutionAdmissionMode.ISOLATED_DRY_RUN, service.qairtExecutionAdmission?.mode)
        assertTrue(service.qairtExecutionAdmission?.memoryAdvisory.orEmpty().contains("advisory native headroom"))
    }

    @Test
    fun failedLocalRuntimeLoadUnloadsRunnerAndLeavesExplicitErrorStats() = runBlocking {
        val runner = FakeLocalChatRunner(
            loadReturnCode = -202,
            statsJson = JSONObject()
                .put("backend", "mnn_cpu")
                .put("loaded", false)
                .put("lastError", "bad config")
                .toString()
        )
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

        val result = service.loadModel(
            modelPath = "/models/broken/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 6)
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Native loadModel failed: -202"))
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("bad config"))
        assertTrue(runner.requestStopCalls >= 2)
        assertTrue(runner.unloadCalls >= 2)
        assertEquals(1, runner.loadCalls)

        val stats = service.stats.value
        assertFalse(stats.loaded)
        assertEquals("mnn_cpu", stats.backend)
        assertEquals("/models/broken/config.json", stats.modelPath)
        assertEquals(32768, stats.nCtx)
        assertEquals(32768, stats.maxAllTokens)
        assertEquals(6, stats.nThreads)
        assertTrue(stats.lastError.orEmpty().contains("bad config"))
    }

    @Test
    fun mnnVisionGenerationReloadsSessionBeforeTheNextTextRequest() = runBlocking {
        val context = FakeContext()
        val image = File(context.cacheDir, "vision-smoke.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.enqueueGeneration("first turn")
        val visionEvents = service.streamChat(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        content = "describe",
                        imageAttachments = listOf(
                            ChatImageAttachment(
                                name = image.name,
                                uriString = image.absolutePath,
                                mimeType = "image/png"
                            )
                        )
                    )
                ),
                params = GenerationParams(
                    nCtx = 32768,
                    nPredict = 8,
                    nThreads = 4,
                    reasoningMode = ReasoningMode.OFF,
                    hideReasoning = true
                )
            )
        ).toList()

        assertTrue(visionEvents.any { it is GenerateEvent.Done })
        assertEquals(1, runner.loadCalls)

        runner.enqueueGeneration("second turn")
        val textEvents = service.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hello")),
                params = GenerationParams(
                    nCtx = 32768,
                    nPredict = 8,
                    nThreads = 4,
                    reasoningMode = ReasoningMode.OFF,
                    hideReasoning = true
                )
            )
        ).toList()

        assertTrue(textEvents.any { it is GenerateEvent.Done })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.unloadCalls)
        assertEquals(2, runner.beginCalls)
    }

    @Test
    fun visionPreparationReachesDebugAndRequestSinksWithoutAffectingInference() = runBlocking {
        val context = FakeContext()
        val image = File(context.cacheDir, "vision-diagnostic.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        val debugDiagnostics = mutableListOf<Pair<String, JSONObject>>()
        val requestDiagnostics = mutableListOf<Pair<String, JSONObject>>()
        val previousDebugSink = LocalChatRunnerDebug.stageSink

        try {
            LocalChatRunnerDebug.stageSink = { stage, details ->
                debugDiagnostics += stage to JSONObject(details.toString())
            }
            service.loadModel(
                modelPath = "/models/qwen/config.json",
                runtime = LocalChatRuntime.MNN_CPU,
                params = LoadParams(nCtx = 32768, nThreads = 4)
            ).getOrThrow()
            runner.enqueueGeneration("vision response")

            val events = service.streamChat(
                ChatRequest(
                    messages = listOf(
                        ChatMessage(
                            role = Role.USER,
                            content = "describe",
                            imageAttachments = listOf(
                                ChatImageAttachment(
                                    name = image.name,
                                    uriString = image.absolutePath,
                                    mimeType = "image/png"
                                )
                            )
                        )
                    ),
                    params = testGenerationParams()
                ),
                LocalChatExecutionContext(
                    requestId = "ui-vision-diagnostic",
                    visionDiagnosticSink = { stage, details ->
                        requestDiagnostics += stage to JSONObject(details.toString())
                        details.put("requestSinkMutation", true)
                        error("observer failures must not affect inference")
                    }
                )
            ).toList()

            assertTrue(events.any { it is GenerateEvent.Done })
            val debugPrepared = debugDiagnostics.single { it.first == "local_vision_input_prepared" }.second
            val requestPrepared = requestDiagnostics.single { it.first == "local_vision_input_prepared" }.second
            assertEquals(debugPrepared.getString("inputSha256"), requestPrepared.getString("inputSha256"))
            assertEquals("prepared", requestPrepared.getString("status"))
            assertEquals("passthrough", requestPrepared.getString("preprocessing"))
            assertFalse(requestPrepared.has("attachmentName"))
            assertFalse(requestPrepared.has("nativeReadablePath"))
            assertFalse(debugPrepared.has("requestSinkMutation"))
        } finally {
            LocalChatRunnerDebug.stageSink = previousDebugSink
        }
    }

    @Test
    fun visionPreparationFailureEmitsRequestDiagnosticSkipsNativeBeginAndKeepsSessionReusable() = runBlocking {
        val context = FakeContext()
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        val requestDiagnostics = mutableListOf<Pair<String, JSONObject>>()
        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        val failedEvents = service.streamChat(
            ChatRequest(
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        content = "describe",
                        imageAttachments = listOf(
                            ChatImageAttachment(
                                name = "missing.png",
                                uriString = File(context.cacheDir, "missing-vision-input.png").absolutePath,
                                mimeType = "image/png"
                            )
                        )
                    )
                ),
                params = testGenerationParams()
            ),
            LocalChatExecutionContext(
                requestId = "ui-missing-vision",
                visionDiagnosticSink = { stage, details ->
                    requestDiagnostics += stage to JSONObject(details.toString())
                    error("observer failures must not replace preparation errors")
                }
            )
        ).toList()

        assertTrue(
            failedEvents.any {
                it is GenerateEvent.Error && it.message.contains("本地图片预处理失败")
            }
        )
        assertEquals(0, runner.beginCalls)
        val failure = requestDiagnostics.single { it.first == "local_vision_input_prepare_failed" }.second
        assertEquals("failed", failure.getString("status"))
        assertEquals("not_started", failure.getString("preprocessing"))
        assertFalse(failure.has("inputSha256"))
        assertFalse(failure.has("errorType"))

        runner.enqueueGeneration("text recovery")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })
        assertEquals(1, runner.loadCalls)
        assertEquals(1, runner.beginCalls)
    }

    @Test
    fun mnnTextGenerationReusesLoadedSessionForTheFollowingTextRequest() = runBlocking {
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.enqueueGeneration("first text")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })
        assertEquals(1, runner.loadCalls)

        runner.enqueueGeneration("second text")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })

        assertEquals(1, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        assertEquals(1, runner.unloadCalls)
    }

    @Test
    fun gemma4TextIsolationRefreshesBeforeTheFollowingSuccessfulTextRequest() = runBlocking {
        val bundle = Files.createTempDirectory("gemma4-mnn-text-isolation").toFile()
        File(bundle, "config.json").writeText(
            """{"is_visual":false,"llm_config":"llm_config.json"}"""
        )
        File(bundle, "llm_config.json").writeText(
            """{"model_type":"gemma4","is_visual":false}"""
        )
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.enqueueGeneration("first text")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })
        assertEquals(1, runner.loadCalls)

        runner.enqueueGeneration("second text")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })

        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        assertEquals(2, runner.unloadCalls)
    }

    @Test
    fun repeatedHealthyMnnLoadForTheSameModelIsIdempotent() = runBlocking {
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        val params = LoadParams(nCtx = 32768, nThreads = 4)

        service.loadModel(
            modelPath = "/models/gemma/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = params
        ).getOrThrow()
        val reused = service.loadModel(
            modelPath = "/models/gemma/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = params
        ).getOrThrow()

        assertTrue(reused.loaded)
        assertEquals(1, runner.loadCalls)
        // The first load clears the initially selected default runner. The
        // second identical request must not tear down the active MNN session.
        assertEquals(1, runner.unloadCalls)
    }

    @Test
    fun downstreamChunkCollectorFailurePropagatesWithoutSecondFlowEmission() = runBlocking {
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()
        runner.enqueueGeneration("visible answer")

        val downstreamFailure = IllegalStateException("collector stopped")
        var thrown: Throwable? = null
        try {
            service.streamChat(textRequest()).collect { event ->
                if (event is GenerateEvent.Chunk) throw downstreamFailure
            }
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown === downstreamFailure)
        assertFalse(thrown?.message.orEmpty().contains("Flow exception transparency is violated"))
        assertTrue(runner.requestStopCalls > 0)
    }

    @Test
    fun mnnEmptyVisibleOutputIsReportedAsGenerationErrorAndRefreshesBeforeRetry() = runBlocking {
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        val emptyEvents = service.streamChat(textRequest()).toList()
        assertTrue(
            emptyEvents.any {
                it is GenerateEvent.Error && it.message.contains("没有生成可见正文")
            }
        )
        assertFalse(emptyEvents.any { it is GenerateEvent.Done })

        runner.enqueueGeneration("recovered text")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
    }

    @Test
    fun interruptedMnnNativeTurnRefreshesBeforeTheFollowingRequest() = runBlocking {
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.setGenerationStopReason("stop_requested")
        runner.enqueueGeneration("partial answer")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })
        assertEquals(1, runner.loadCalls)

        runner.setGenerationStopReason(null)
        runner.enqueueGeneration("recovered answer")
        assertTrue(service.streamChat(textRequest()).toList().any { it is GenerateEvent.Done })

        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        assertTrue(runner.unloadCalls >= 2)
    }

    @Test
    fun repeatedMnnVisionGenerationReloadsSessionBeforeNativeBeginCompletion() = runBlocking {
        val context = FakeContext()
        val image = File(context.cacheDir, "vision-smoke.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        val visionRequest = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = image.name,
                            uriString = image.absolutePath,
                            mimeType = "image/png"
                        )
                    )
                )
            ),
            params = GenerationParams(
                nCtx = 32768,
                nPredict = 8,
                nThreads = 4,
                reasoningMode = ReasoningMode.OFF,
                hideReasoning = true
            )
        )

        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.enqueueGeneration("first vision")
        val firstEvents = service.streamChat(visionRequest).toList()

        assertTrue(firstEvents.any { it is GenerateEvent.Done })
        assertEquals(1, runner.beginCalls)

        runner.enqueueGeneration("second vision")
        val secondEvents = service.streamChat(visionRequest).toList()

        assertTrue(secondEvents.any { it is GenerateEvent.Done })
        assertEquals(2, runner.beginCalls)
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.unloadCalls)
    }

    @Test
    fun mnnVisionBeginUsesContiguousImageTagsBeforeUserText() = runBlocking {
        val context = FakeContext()
        val firstImage = File(context.cacheDir, "first-vision.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val secondImage = File(context.cacheDir, "second-vision.png").apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe both",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = firstImage.name,
                            uriString = firstImage.absolutePath,
                            mimeType = "image/png"
                        ),
                        ChatImageAttachment(
                            name = secondImage.name,
                            uriString = secondImage.absolutePath,
                            mimeType = "image/png"
                        )
                    )
                )
            ),
            params = testGenerationParams()
        )

        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()
        runner.enqueueGeneration("vision response")

        assertTrue(service.streamChat(request).toList().any { it is GenerateEvent.Done })
        val content = JSONArray(runner.lastMessagesJson)
            .userMessage()
            .getString("content")

        assertEquals(
            "<img>${firstImage.absolutePath}</img><img>${secondImage.absolutePath}</img>describe both",
            content
        )
        assertFalse(content.contains("</img>\n"))
    }

    @Test
    fun nonMnnVisionBeginKeepsOpenAiTextFirstParts() = runBlocking {
        val context = FakeContext()
        val image = File(context.cacheDir, "llama-vision.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val mnnRunner = FakeLocalChatRunner()
        val llamaRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.LLAMA_CPP)
        val service = McaInferenceService(
            context = context,
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to mnnRunner,
                LocalChatRuntime.LLAMA_CPP to llamaRunner
            )
        )
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = image.name,
                            uriString = image.absolutePath,
                            mimeType = "image/png"
                        )
                    )
                )
            ),
            params = testGenerationParams()
        )

        service.loadModel(
            modelPath = "/models/minicpm/model.gguf",
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()
        llamaRunner.enqueueGeneration("vision response")

        assertTrue(service.streamChat(request).toList().any { it is GenerateEvent.Done })
        val content = JSONArray(llamaRunner.lastMessagesJson)
            .userMessage()
            .getJSONArray("content")

        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("describe", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
    }

    @Test
    fun llamaLoadSignatureMismatchReloadsCommittedProfileAndRetriesOnlyOnce() = runBlocking {
        val context = FakeContext()
        val runner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.LLAMA_CPP)
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.LLAMA_CPP to runner),
            installationScopeId = "test-installation"
        )
        service.loadModel(
            modelPath = "/models/llama/model.gguf",
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(nCtx = 4096, nThreads = 4)
        ).getOrThrow()
        runner.enqueueBeginReturnCodes(-11, 0)
        runner.enqueueGeneration("recovered")

        val events = service.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hello")),
                params = GenerationParams(
                    nCtx = 4096,
                    nThreads = 4,
                    nPredict = 32,
                    advancedJson = "{\"future_native\":true}"
                )
            ),
            LocalChatExecutionContext(requestId = "llama-recovery-1")
        ).toList()

        assertTrue(events.any { it is GenerateEvent.Done })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        val retryParams = JSONObject(runner.lastBeginParamsJson)
        assertFalse(retryParams.has("n_ctx"))
        assertFalse(retryParams.has("n_batch"))
        assertFalse(retryParams.has("advanced_json"))
        assertFalse(retryParams.has("future_native"))
        assertEquals(4, retryParams.getInt("n_threads"))
    }

    @Test
    fun repeatedLlamaLoadSignatureMismatchDoesNotEnterReloadLoop() = runBlocking {
        val runner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.LLAMA_CPP)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.LLAMA_CPP to runner),
            installationScopeId = "test-installation"
        )
        service.loadModel(
            modelPath = "/models/llama/model.gguf",
            runtime = LocalChatRuntime.LLAMA_CPP,
            params = LoadParams(nCtx = 4096, nThreads = 4)
        ).getOrThrow()
        runner.enqueueBeginReturnCodes(-11, -11, 0)

        val events = service.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hello")),
                params = GenerationParams(nCtx = 4096, nThreads = 4, nPredict = 32)
            ),
            LocalChatExecutionContext(requestId = "llama-recovery-2")
        ).toList()

        assertTrue(events.any { it is GenerateEvent.Error })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
    }

    @Test
    fun failedMnnVisionBeginReloadsSessionBeforeNextRequest() = runBlocking {
        val context = FakeContext()
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.beginReturnCode = -91
        val failedEvents = service.streamChat(visionRequest(context)).toList()

        assertTrue(failedEvents.any { it is GenerateEvent.Error })
        assertEquals(1, runner.loadCalls)

        runner.beginReturnCode = 0
        runner.enqueueGeneration("recovered")
        val recoveredEvents = service.streamChat(textRequest()).toList()

        assertTrue(recoveredEvents.any { it is GenerateEvent.Done })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        assertTrue(runner.unloadCalls >= 2)
    }

    @Test
    fun throwingMnnVisionBeginReloadsSessionBeforeNextRequest() = runBlocking {
        val context = FakeContext()
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = context,
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

        service.loadModel(
            modelPath = "/models/qwen/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32768, nThreads = 4)
        ).getOrThrow()

        runner.beginFailure = IllegalStateException("vision begin crashed")
        val failedEvents = service.streamChat(visionRequest(context)).toList()

        assertTrue(failedEvents.any { it is GenerateEvent.Error && it.message.contains("vision begin crashed") })
        assertEquals(1, runner.loadCalls)

        runner.beginFailure = null
        runner.enqueueGeneration("recovered")
        val recoveredEvents = service.streamChat(textRequest()).toList()

        assertTrue(recoveredEvents.any { it is GenerateEvent.Done })
        assertEquals(2, runner.loadCalls)
        assertEquals(2, runner.beginCalls)
        assertTrue(runner.unloadCalls >= 2)
    }

    @Test
    fun qairtSessionSupportsConsecutiveRequestsAndShutdownReleasesEveryRunner() = runBlocking {
        val mnnRunner = FakeLocalChatRunner()
        val qairtRunner = FakeLocalChatRunner(runnerRuntime = LocalChatRuntime.GENIEX_QAIRT)
        val bundle = qairtBundle(kvSpan = 1_023)
        val identity = QairtBundleRuntimeIdentity(
            bundleSha256 = "verified-qairt-session",
            chipset = "SM8750P",
            runtimeFingerprint = "geniex-qairt/test-runtime"
        )
        val verificationStore = QairtExecutionVerificationStore(File(bundle, "verifications.json"))
        verificationStore.recordVerified(identity)
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(
                LocalChatRuntime.MNN_CPU to mnnRunner,
                LocalChatRuntime.GENIEX_QAIRT to qairtRunner
            ),
            memorySnapshotProvider = { generousMemorySnapshot() },
            qairtVerificationStoreOverride = verificationStore,
            qairtIdentityProviderOverride = { requested -> identity.takeIf { it.bundleSha256 == requested } }
        )
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "describe the image")),
            params = GenerationParams(
                nCtx = 32768,
                nPredict = 8,
                nThreads = 4,
                reasoningMode = ReasoningMode.OFF,
                hideReasoning = true
            )
        )

        service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 32768, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256
        ).getOrThrow()

        qairtRunner.enqueueGeneration("first response")
        assertTrue(service.streamChat(request).toList().any { it is GenerateEvent.Done })
        qairtRunner.enqueueGeneration("second response")
        assertTrue(service.streamChat(request).toList().any { it is GenerateEvent.Done })

        assertEquals(1, qairtRunner.loadCalls)
        assertEquals(2, qairtRunner.beginCalls)
        assertEquals(0, qairtRunner.unloadCalls)

        service.shutdown()

        assertEquals(1, qairtRunner.shutdownCalls)
        assertEquals(1, mnnRunner.shutdownCalls)
        assertFalse(service.stats.value.loaded)
        assertTrue(service.parameterSignatureSnapshot()?.active == null)
    }

    @Test
    fun persistedExecutionProfileIsLoadedWithItsStableIdAndRevision() = runBlocking {
        val identity = ModelRuntimeIdentity(
            modelId = "persisted-model",
            artifactFingerprint = "sha256:persisted-model",
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersion = "mnn-test",
            nativeLibrarySha256 = "native-test",
            installationScopeId = "installation-test"
        )
        val coordinator = ParameterCoordinator()
        val profile = coordinator.resolveProfile(
            identity,
            "{\"n_ctx\":4096,\"n_threads\":4}",
            profileId = "persisted-profile",
            revision = 7
        ).profile
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            parameterCoordinator = coordinator
        )

        service.loadModel(
            modelPath = "/models/persisted/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 9999, nThreads = 2),
            runtimeIdentity = identity,
            executionProfile = profile
        ).getOrThrow()

        assertEquals("persisted-profile", service.activeExecutionProfile()?.profileId)
        assertEquals(7L, service.activeExecutionProfile()?.revision)
        assertEquals(4096, service.stats.value.nCtx)
        assertEquals(4, service.stats.value.nThreads)
    }

    @Test
    fun authorizedPendingProfileCommitsBeforeDoneAndUsesExactCandidateSignatures() = runBlocking {
        val identity = ModelRuntimeIdentity(
            modelId = "transaction-model",
            artifactFingerprint = "sha256:transaction-model",
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersion = "mnn-test",
            nativeLibrarySha256 = "native-test",
            installationScopeId = "installation-test"
        )
        val coordinator = ParameterCoordinator()
        val active = coordinator.resolveProfile(identity, "{\"n_ctx\":4096,\"n_threads\":4}", profileId = "active").profile
        val candidate = coordinator.resolveProfile(identity, "{\"n_ctx\":8192,\"n_threads\":4}", profileId = "candidate", revision = 2).profile
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            parameterCoordinator = coordinator
        )
        service.loadModel(
            "/models/transaction/config.json",
            LocalChatRuntime.MNN_CPU,
            LoadParams(nCtx = 4096, nThreads = 4),
            runtimeIdentity = identity,
            executionProfile = active
        ).getOrThrow()
        val authorization = service.stagePendingExecutionProfile("tx-candidate", candidate, "active")
        runner.enqueueGeneration("candidate answer")

        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "hello")),
            params = GenerationParams(nCtx = 8192, nPredict = 8, nThreads = 4, reasoningMode = ReasoningMode.OFF, hideReasoning = true)
        )
        val events = service.streamChat(
            request,
            LocalChatExecutionContext(requestId = "candidate-request", loadAuthorization = authorization)
        ).toList()

        assertTrue(events.any { it is GenerateEvent.Done })
        assertEquals("candidate", service.activeExecutionProfile()?.profileId)
        assertEquals(2L, service.activeExecutionProfile()?.revision)
        assertEquals(2, runner.loadCalls)
        assertEquals(candidate.committedExecutionSignature.digest, service.parameterSignatureSnapshot()?.committed?.digest)
    }

    @Test
    fun failedPendingProfileHasOneBoundedRollbackToCommittedProfile() = runBlocking {
        val identity = ModelRuntimeIdentity(
            modelId = "rollback-model",
            artifactFingerprint = "sha256:rollback-model",
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersion = "mnn-test",
            nativeLibrarySha256 = "native-test",
            installationScopeId = "installation-test"
        )
        val coordinator = ParameterCoordinator()
        val active = coordinator.resolveProfile(identity, "{\"n_ctx\":4096,\"n_threads\":4}", profileId = "active").profile
        val candidate = coordinator.resolveProfile(identity, "{\"n_ctx\":8192,\"n_threads\":4}", profileId = "candidate").profile
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            parameterCoordinator = coordinator
        )
        service.loadModel(
            "/models/rollback/config.json",
            LocalChatRuntime.MNN_CPU,
            LoadParams(nCtx = 4096, nThreads = 4),
            runtimeIdentity = identity,
            executionProfile = active
        ).getOrThrow()
        val authorization = service.stagePendingExecutionProfile("tx-rollback", candidate, "active")

        val events = service.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "hello")),
                params = GenerationParams(nCtx = 8192, nPredict = 8, nThreads = 4, reasoningMode = ReasoningMode.OFF, hideReasoning = true)
            ),
            LocalChatExecutionContext(requestId = "rollback-request", loadAuthorization = authorization)
        ).toList()

        assertTrue(events.any { it is GenerateEvent.Error })
        assertEquals("active", service.activeExecutionProfile()?.profileId)
        assertEquals(3, runner.loadCalls) // initial, candidate, one rollback
        assertTrue(service.stats.value.loaded)
    }

    @Test
    fun candidateEvaluationDispositionRestoresCommittedProfileAfterVisibleCanary() = runBlocking {
        val identity = ModelRuntimeIdentity(
            modelId = "canary-model",
            artifactFingerprint = "sha256:canary-model",
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersion = "mnn-test",
            nativeLibrarySha256 = "native-test",
            installationScopeId = "installation-test"
        )
        val coordinator = ParameterCoordinator()
        val active = coordinator.resolveProfile(identity, "{\"n_ctx\":4096,\"n_threads\":4}", profileId = "active").profile
        val candidate = coordinator.resolveProfile(identity, "{\"n_ctx\":8192,\"n_threads\":4}", profileId = "canary").profile
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            parameterCoordinator = coordinator
        )
        service.loadModel(
            "/models/canary/config.json",
            LocalChatRuntime.MNN_CPU,
            LoadParams(nCtx = 4096, nThreads = 4),
            runtimeIdentity = identity,
            executionProfile = active
        ).getOrThrow()
        val lease = service.acquireExclusiveLifecycleLease()
        val events = try {
            val authorization = service.stagePendingExecutionProfile("tx-canary", candidate, "active")
            runner.enqueueGeneration("visible canary")
            service.streamChat(
                ChatRequest(
                    messages = listOf(ChatMessage(Role.USER, "canary")),
                    params = GenerationParams(nCtx = 8192, nPredict = 8, nThreads = 4, reasoningMode = ReasoningMode.OFF, hideReasoning = true)
                ),
                LocalChatExecutionContext(
                    requestId = "canary-request",
                    loadAuthorization = authorization,
                    pendingProfileDisposition = PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                    lifecycleLease = lease
                )
            ).toList()
        } finally {
            lease.release()
        }

        assertTrue(events.any { it is GenerateEvent.Done })
        assertEquals("active", service.activeExecutionProfile()?.profileId)
        assertEquals(3, runner.loadCalls)
        assertTrue(service.stats.value.loaded)
    }

    @Test
    fun deferredCandidateCanBeCommittedOnlyAfterExternalCorrectnessGate() = runBlocking {
        val identity = ModelRuntimeIdentity(
            modelId = "deferred-model",
            artifactFingerprint = "sha256:deferred-model",
            runtime = LocalChatRuntime.MNN_CPU,
            runtimeVersion = "mnn-test",
            nativeLibrarySha256 = "native-test",
            installationScopeId = "installation-test"
        )
        val coordinator = ParameterCoordinator()
        val active = coordinator.resolveProfile(identity, "{\"n_ctx\":4096,\"n_threads\":4}", profileId = "active").profile
        val candidate = coordinator.resolveProfile(identity, "{\"n_ctx\":8192,\"n_threads\":4}", profileId = "deferred").profile
        val runner = FakeLocalChatRunner()
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            parameterCoordinator = coordinator
        )
        service.loadModel(
            "/models/deferred/config.json",
            LocalChatRuntime.MNN_CPU,
            LoadParams(nCtx = 4096, nThreads = 4),
            runtimeIdentity = identity,
            executionProfile = active
        ).getOrThrow()
        val lease = service.acquireExclusiveLifecycleLease()
        try {
            val authorization = service.stagePendingExecutionProfile("tx-deferred", candidate, "active")
            runner.enqueueGeneration("deferred canary")
            val events = service.streamChat(
                ChatRequest(
                    messages = listOf(ChatMessage(Role.USER, "deferred")),
                    params = GenerationParams(nCtx = 8192, nPredict = 8, nThreads = 4, reasoningMode = ReasoningMode.OFF, hideReasoning = true)
                ),
                LocalChatExecutionContext(
                    requestId = "deferred-request",
                    loadAuthorization = authorization,
                    pendingProfileDisposition = PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                    lifecycleLease = lease
                )
            ).toList()

            assertTrue(events.any { it is GenerateEvent.Done })
            assertEquals("active", service.activeExecutionProfile()?.profileId)
            service.commitDeferredPendingExecutionProfile(authorization, lease)
            assertEquals("deferred", service.activeExecutionProfile()?.profileId)
        } finally {
            lease.release()
        }
        assertEquals(2, runner.loadCalls)
    }

    private fun constrainedMemorySnapshot(): MemorySnapshot = MemorySnapshot(
        totalMemKb = 12L * 1024L * 1024L,
        availMemKb = 4L * 1024L * 1024L
    )

    private fun generousMemorySnapshot(): MemorySnapshot = MemorySnapshot(
        totalMemKb = 16L * 1024L * 1024L,
        availMemKb = 8L * 1024L * 1024L
    )

    private fun qairtBundle(kvSpan: Int): File {
        val bundle = Files.createTempDirectory("mca-qairt-admission").toFile()
        val metadata = """
            {
              "model_name": "Qwen3 test",
              "model_files": {
                "part2.bin": {
                  "inputs": {
                    "past_key_0_in": {"shape": [8, 1, 128, $kvSpan], "dtype": "uint8"},
                    "past_value_0_in": {"shape": [8, 1, $kvSpan, 128], "dtype": "uint8"}
                  }
                }
              }
            }
        """.trimIndent()
        File(bundle, "metadata.json").writeText(metadata)
        return bundle
    }

    private fun visionRequest(context: Context): ChatRequest {
        val image = File(context.cacheDir, "vision-begin-failure.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        return ChatRequest(
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    content = "describe",
                    imageAttachments = listOf(
                        ChatImageAttachment(
                            name = image.name,
                            uriString = image.absolutePath,
                            mimeType = "image/png"
                        )
                    )
                )
            ),
            params = testGenerationParams()
        )
    }

    private fun textRequest(): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "hello")),
        params = testGenerationParams()
    )

    private fun testGenerationParams(): GenerationParams = GenerationParams(
        nCtx = 32768,
        nPredict = 8,
        nThreads = 4,
        reasoningMode = ReasoningMode.OFF,
        hideReasoning = true
    )

    private class FakeContext : ContextWrapper(null) {
        private val root = File(System.getProperty("java.io.tmpdir"), "mca-engine-test-${System.nanoTime()}")
        private val files = File(root, "files").also { it.mkdirs() }
        private val cache = File(root, "cache").also { it.mkdirs() }
        private val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(root, "lib").also { it.mkdirs() }.absolutePath
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getApplicationInfo(): ApplicationInfo = appInfo
    }

    private class FakeLocalChatRunner(
        private val runnerRuntime: LocalChatRuntime = LocalChatRuntime.MNN_CPU,
        private val loadReturnCode: Int = 0,
        private var statsJson: String = loadedStatsJson(runnerRuntime)
    ) : LocalChatRunner {
        var loadCalls = 0
        var unloadCalls = 0
        var requestStopCalls = 0
        var beginCalls = 0
        var shutdownCalls = 0
        var beginReturnCode = 0
        var beginFailure: Throwable? = null
        var lastMessagesJson: String = ""
        var lastBeginParamsJson: String = ""
        val loadParamsJson = mutableListOf<String>()
        private val queuedBeginReturnCodes = ArrayDeque<Int>()
        private val chunks = ArrayDeque<String>()

        override val runtime: LocalChatRuntime = runnerRuntime
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit

        override fun loadModel(modelPath: String, paramsJson: String): Int {
            loadCalls += 1
            loadParamsJson += paramsJson
            if (loadReturnCode == 0) {
                statsJson = loadedStatsJson(runtime, paramsJson)
            }
            return loadReturnCode
        }

        override fun unloadModel() {
            unloadCalls += 1
        }

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
            beginCalls += 1
            lastMessagesJson = messagesJson
            lastBeginParamsJson = paramsJson
            beginFailure?.let { throw it }
            return if (queuedBeginReturnCodes.isEmpty()) beginReturnCode else queuedBeginReturnCodes.removeFirst()
        }

        override fun generateNextChunk(): String? =
            if (chunks.isEmpty()) null else chunks.removeFirst()

        override fun requestStop() {
            requestStopCalls += 1
        }

        override fun getRuntimeStatsJson(): String = statsJson
        override fun shutdown() {
            shutdownCalls += 1
            unloadModel()
        }

        fun enqueueGeneration(vararg values: String) {
            chunks.clear()
            values.forEach { chunks.addLast(it) }
        }

        fun enqueueBeginReturnCodes(vararg values: Int) {
            queuedBeginReturnCodes.clear()
            values.forEach(queuedBeginReturnCodes::addLast)
        }

        fun setGenerationStopReason(reason: String?) {
            val stats = JSONObject(statsJson)
            if (reason == null) {
                stats.remove("generationStopReason")
            } else {
                stats.put("generationStopReason", reason)
            }
            statsJson = stats.toString()
        }

        private companion object {
            fun loadedStatsJson(runtime: LocalChatRuntime, paramsJson: String = "{}"): String {
                val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
                val advanced = when (val raw = params.opt("advanced_json")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                fun value(key: String, fallback: Any): Any = when {
                    params.has(key) && !params.isNull(key) -> params.opt(key)
                    advanced.has(key) && !advanced.isNull(key) -> advanced.opt(key)
                    else -> fallback
                }
                val nCtx = (value("n_ctx", 8192) as Number).toInt()
                val nThreads = (value("n_threads", 4) as Number).toInt()
                val stats = JSONObject()
                    .put("backend", runtime.backendId)
                    .put("loaded", true)
                    .put("runnerReady", true)
                    .put("visionReady", true)
                    .put("nThreads", nThreads)
                    .put("nCtx", nCtx)
                    .put("maxAllTokens", nCtx)
                    .put("maxNewTokens", 8)
                    .put(
                        "backendDevices",
                        org.json.JSONArray().put(
                            if (runtime == LocalChatRuntime.GENIEX_QAIRT) "QAIRT NPU" else "cpu"
                        )
                    )
                if (runtime == LocalChatRuntime.MNN_CPU) {
                    val loadedConfig = JSONObject()
                        .put("backend_type", value("backend_type", "cpu"))
                        .put("precision", value("precision", "low"))
                        .put("memory", value("memory", "low"))
                        .put("power", value("power", "normal"))
                        .put("use_mmap", value("mmap", true))
                        .put("kvcache_mmap", value("kvcache_mmap", true))
                        .put("max_all_tokens", nCtx)
                        .put("n_ctx", nCtx)
                        .put("thread_num", nThreads)
                        .put("chunk", value("chunk", 128))
                    stats.put("loadedConfigJson", loadedConfig.toString())
                    stats.put("lastConfigJson", loadedConfig.toString())
                } else if (runtime == LocalChatRuntime.LLAMA_CPP) {
                    stats.put(
                        "effectiveConfig",
                        JSONObject()
                            .put("n_ctx", nCtx)
                            .put("n_threads", nThreads)
                            .put("n_threads_batch", value("n_threads_batch", nThreads))
                            .put("n_batch", value("n_batch", 512))
                            .put("n_ubatch", value("n_ubatch", 512))
                            .put("n_gpu_layers", value("n_gpu_layers", 0))
                            .put("main_gpu", value("main_gpu", 0))
                            .put("split_mode", value("split_mode", "none"))
                            .put("n_cpu_moe", value("n_cpu_moe", 0))
                            .put("cache_type_k", value("cache_type_k", "f16"))
                            .put("cache_type_v", value("cache_type_v", "f16"))
                            .put("flash_attn", value("flash_attn", "auto"))
                            .put("perf", value("perf", false))
                            .put("n_parallel", value("n_parallel", 1))
                            .put("spec_type", value("spec_type", "none"))
                            .put("spec_draft_n_max", value("spec_draft_n_max", 0))
                            .put("mmap", value("mmap", true))
                            .put("mlock", value("mlock", false))
                    )
                }
                return stats.toString()
            }
        }
    }

    private fun JSONArray.userMessage(): JSONObject =
        (0 until length())
            .asSequence()
            .map(::getJSONObject)
            .first { it.getString("role") == "user" }
}

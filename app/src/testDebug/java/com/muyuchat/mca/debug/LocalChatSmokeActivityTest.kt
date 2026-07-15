package com.muyuchat.mca.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.LocalChatRuntime
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class LocalChatSmokeActivityTest {
    @Test
    fun generationTimeoutIncludesDecodeBudgetAndClamps() {
        assertEquals(
            90_000L,
            localChatSmokeGenerationTimeoutMs(promptChars = 10, nCtx = 2048, maxTokens = 8)
        )
        assertEquals(
            900_000L,
            localChatSmokeGenerationTimeoutMs(promptChars = 335, nCtx = 4096, maxTokens = 128)
        )
        assertTrue(
            localChatSmokeGenerationTimeoutMs(promptChars = 28_000, nCtx = 16_384, maxTokens = 1) >
                300_000L
        )
        assertEquals(
            900_000L,
            localChatSmokeGenerationTimeoutMs(promptChars = 500_000, nCtx = 65_536, maxTokens = 128)
        )
    }

    @Test
    fun directSmokeUsesDeterministicSampling() {
        val params = localChatSmokeGenerationParams(nCtx = 2048, nThreads = 4, maxTokens = 16)

        assertEquals(0.0f, params.temperature)
        assertEquals(2048, params.nCtx)
        assertEquals(16, params.nPredict)
        assertEquals(4, params.nThreads)
        assertEquals(1, params.topK)
        assertEquals(1.0f, params.topP)
        assertEquals(0.0f, params.minP)
        assertEquals(1.08f, params.repeatPenalty)
        assertEquals(0.0f, params.presencePenalty)
        assertEquals(0.2f, params.frequencyPenalty)
        assertEquals(0, params.seed)
        assertEquals(ReasoningMode.OFF, params.reasoningMode)
        assertTrue(params.hideReasoning)
        assertEquals(LOCAL_CHAT_SMOKE_DEFAULT_SYSTEM_PROMPT, params.systemPrompt)
        assertTrue(JSONObject(params.advancedJson).getBoolean("mca_debug_trace"))
    }

    @Test
    fun directSmokePreservesQualityCaseSystemPrompt() {
        val params = localChatSmokeGenerationParams(
            nCtx = 4096,
            nThreads = 4,
            maxTokens = 256,
            systemPrompt = "Return only compilable Kotlin code."
        )

        assertEquals("Return only compilable Kotlin code.", params.systemPrompt)
    }

    @Test
    fun advancedJsonMergePreservesUnknownKeysAndForcesTrace() {
        val merged = resolveLocalChatSmokeAdvancedJson(
            """{"future_native":{"mode":"kept"},"mca_debug_trace":false}"""
        )
        val root = JSONObject(merged)

        assertEquals("kept", root.getJSONObject("future_native").getString("mode"))
        assertTrue(root.getBoolean("mca_debug_trace"))
    }

    @Test
    fun invalidAdvancedJsonIsRejectedClearly() {
        val error = runCatching {
            resolveLocalChatSmokeAdvancedJson("{broken-json")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Invalid advancedJson"))
    }

    @Test
    fun extendedSamplingReachesGenerationParams() {
        val sampling = LocalChatSmokeSampling(
            temperature = 0.7f,
            topK = 32,
            topP = 0.9f,
            minP = 0.08f,
            repeatPenalty = 1.15f,
            presencePenalty = 0.25f,
            frequencyPenalty = -0.3f,
            seed = 7
        )

        val params = localChatSmokeGenerationParams(
            nCtx = 4096,
            nThreads = 6,
            maxTokens = 64,
            sampling = sampling
        )

        assertEquals(0.08f, params.minP)
        assertEquals(1.15f, params.repeatPenalty)
        assertEquals(0.25f, params.presencePenalty)
        assertEquals(-0.3f, params.frequencyPenalty)
        assertEquals(0.08, sampling.toJson().getDouble("minP"), 0.0001)
    }

    @Test
    fun exactMergedAdvancedJsonReachesLoadAndGenerationParams() {
        val merged = resolveLocalChatSmokeAdvancedJson("""{"future_native":7}""")
        val load = localChatSmokeLoadParams(
            nCtx = 4096,
            nThreads = 4,
            visionProjectorPath = null,
            geniexComputeUnit = "htp",
            advancedJson = merged
        )
        val generation = localChatSmokeGenerationParams(
            nCtx = 4096,
            nThreads = 4,
            maxTokens = 32,
            advancedJson = merged
        )

        assertEquals(merged, load.advancedJson)
        assertEquals(merged, generation.advancedJson)
        assertEquals(7, JSONObject(load.advancedJson).getInt("future_native"))
        assertTrue(JSONObject(generation.advancedJson).getBoolean("mca_debug_trace"))
    }

    @Test
    fun cancelThenSecondResultRecordsCancellationAndRecoveryEvidence() {
        val first = localChatSmokeCancellationRequestResult(
            visibleText = "partial",
            imagePath = null,
            doneSeen = false,
            flowCompleted = true,
            error = "cancelled",
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", true),
            requestInFlightAtStop = true,
            cancellationPhase = "terminated_after_stop",
            preStopStatsCapturedAtMonotonicNs = 10L,
            stopAttemptedAtMonotonicNs = 11L,
            stopAcceptedAtMonotonicNs = 12L,
            terminalAtMonotonicNs = 13L,
            nativeStats = JSONObject()
                .put("request", 1)
                .put("backend", "mnn_cpu")
                .put("completionTokens", 3)
                .put("generationStopReason", "stop_requested")
        )
        val second = localChatSmokeGenerationResult(
            visibleText = "recovered",
            imagePath = null,
            doneSeen = true,
            flowCompleted = true,
            error = null,
            nativeStats = JSONObject().put("request", 2)
        )

        val result = localChatSmokeCancelThenSecondResult(first, second)
        val cancellation = result.getJSONObject("cancellation")

        assertEquals("partial", result.getJSONObject("firstRequest").getString("text"))
        assertEquals(1, result.getJSONObject("firstRequest").getJSONObject("nativeStats").getInt("request"))
        assertTrue(cancellation.getBoolean("visibleChunkSeen"))
        assertTrue(cancellation.getBoolean("stopRequested"))
        assertTrue(cancellation.getBoolean("stopCallSucceeded"))
        assertTrue(cancellation.getBoolean("terminated"))
        assertTrue(cancellation.getBoolean("success"))
        val firstRequest = result.getJSONObject("firstRequest")
        assertEquals("terminated_after_stop", firstRequest.getString("cancellationPhase"))
        assertEquals(12L, firstRequest.getLong("stopAcceptedAtMonotonicNs"))
        assertEquals(
            13L,
            firstRequest.getJSONObject("cancellationEvidence").getLong("terminalAtMonotonicNs")
        )
        assertEquals("recovered", result.getJSONObject("secondRequest").getString("text"))
        assertEquals(2, result.getJSONObject("secondRequest").getJSONObject("nativeStats").getInt("request"))
        assertTrue(result.getBoolean("cancellationSuccess"))
        assertTrue(result.getBoolean("recoverySuccess"))
    }

    @Test
    fun cancellationEvidenceRejectsNaturalMaxTokenCompletion() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = true,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 1,
            preStopStats = JSONObject()
                .put("backend", "llama.cpp-cpu")
                .put("completionTokens", 0)
                .put("maxNewTokens", 1),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "llama.cpp-cpu")
                .put("completionTokens", 1)
                .put("maxNewTokens", 1)
        )

        assertTrue(evidence.getBoolean("maxTokenLimitReached"))
        assertFalse(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceRejectsTerminalBeforeStop() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = true,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = "done",
            postStopTerminal = null,
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", true),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("completionTokens", 2)
                .put("generationStopReason", "stop_requested")
        )

        assertFalse(evidence.getBoolean("explicitOrderVerified"))
        assertFalse(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceRequiresMnnNativeCancelReason() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = true,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", true),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("completionTokens", 2)
                .put("generationStopReason", "normal_finished")
        )

        assertFalse(evidence.getBoolean("nativeEvidenceAccepted"))
        assertFalse(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceFallsBackToOrderedChainWithoutNativeReason() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = true,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "llama.cpp-cpu")
                .put("completionTokens", 1)
                .put("maxNewTokens", 64),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "llama.cpp-cpu")
                .put("completionTokens", 2)
                .put("maxNewTokens", 64)
        )

        assertTrue(evidence.getBoolean("explicitOrderVerified"))
        assertTrue(evidence.getBoolean("nativeEvidenceAccepted"))
        assertTrue(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceRejectsUnacceptedAtomicStop() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = true,
            stopCallAttempted = true,
            stopCallSucceeded = false,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", false),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("completionTokens", 2)
                .put("generationStopReason", "stop_requested")
        )

        assertFalse(evidence.getBoolean("atomicStopAccepted"))
        assertFalse(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceUsesAtomicAcceptanceInsteadOfStaleSnapshot() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = false,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "done",
            flowCompleted = true,
            flowError = null,
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", false),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("completionTokens", 2)
                .put("generationStopReason", "stop_requested")
        )

        assertFalse(
            evidence.getJSONObject("preStopActiveEvidence").getBoolean("activeAtStop")
        )
        assertTrue(evidence.getBoolean("atomicStopAccepted"))
        assertTrue(evidence.getBoolean("success"))
    }

    @Test
    fun cancellationEvidenceDoesNotRequireVisibleTextWhenRequestWasActive() {
        val evidence = localChatSmokeCancellationEvidence(
            visibleChunkSeen = false,
            stopCallAttempted = true,
            stopCallSucceeded = true,
            stopCallError = null,
            terminalBeforeStop = null,
            postStopTerminal = "error",
            flowCompleted = true,
            flowError = "no visible output after cancellation",
            timedOut = false,
            requestedMaxTokens = 64,
            preStopStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("generationActive", true),
            requestInFlightAtStop = true,
            nativeStats = JSONObject()
                .put("backend", "mnn_cpu")
                .put("completionTokens", 0)
                .put("generationStopReason", "stop_requested")
        )

        assertFalse(evidence.getBoolean("visibleChunkSeen"))
        assertTrue(evidence.getBoolean("success"))
    }

    @Test
    fun processGateRejectsConcurrentRunAndAllowsRunAfterRelease() {
        LocalChatSmokeProcessGate.release()
        try {
            assertTrue(LocalChatSmokeProcessGate.tryAcquire())
            assertFalse(LocalChatSmokeProcessGate.tryAcquire())
            LocalChatSmokeProcessGate.release()
            assertTrue(LocalChatSmokeProcessGate.tryAcquire())
        } finally {
            LocalChatSmokeProcessGate.release()
        }
    }

    @Test
    fun extractsCompleteVisibleSseTextWithoutHiddenReasoning() {
        val response = """
            HTTP/1.1 200 OK\r
            Content-Type: text/event-stream\r
            \r
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"不要落盘"}}]}

            data: {"choices":[{"delta":{"content":"完整中文"}}]}

            data: {"choices":[{"delta":{"content":"回答🙂"}}]}

            data: [DONE]
        """.trimIndent()

        val text = extractVisibleChatCompletionText(response)

        assertEquals("完整中文回答🙂", text)
        assertFalse(text.contains("不要落盘"))
        assertEquals(
            "d7912a84db80f3aa6b684db0e61875bf292a9be2214ce1c15eeaa4fa5158a857",
            localChatSmokeTextSha256(text)
        )
    }

    @Test
    fun ignoresMalformedAndNonSseLinesWhilePreservingEscapedContent() {
        val response = """
            HTTP/1.1 200 OK
            not-data: {"choices":[{"delta":{"content":"wrong"}}]}
            data: not-json
            data: {"choices":[{"delta":{"content":"line\nquote\""}}]}
            data: [DONE]
        """.trimIndent()

        assertEquals("line\nquote\"", extractVisibleChatCompletionText(response))
    }

    @Test
    fun imageSmokeRequiresNativeVisionReadinessForMnn() {
        assertTrue(
            requiresLocalChatSmokeVisionReady(
                runtime = LocalChatRuntime.MNN_CPU,
                imagePath = "/data/local/tmp/vision.png",
                visionProjectorPath = null
            )
        )
        assertFalse(
            requiresLocalChatSmokeVisionReady(
                runtime = LocalChatRuntime.MNN_CPU,
                imagePath = null,
                visionProjectorPath = null
            )
        )
    }

    @Test
    fun qairtDryRunRequiresExplicitNpuOrHtpEvidence() {
        assertTrue(
            hasQairtDryRunNpuEvidence(
                JSONObject()
                    .put("backend", "geniex_qairt")
                    .put("backendDevices", "QAIRT HTP / VLM")
            )
        )
        assertTrue(
            hasQairtDryRunNpuEvidence(
                JSONObject()
                    .put("backend", "geniex_qairt")
                    .put("backendDevices", "QAIRT NPU")
            )
        )
        assertFalse(
            hasQairtDryRunNpuEvidence(
                JSONObject()
                    .put("backend", "geniex_qairt")
                    .put("backendDevices", "CPU")
            )
        )
        assertFalse(
            hasQairtDryRunNpuEvidence(
                JSONObject()
                    .put("backend", "mnn_cpu")
                    .put("backendDevices", "QAIRT NPU")
            )
        )
    }

    @Test
    fun eventLogRemembersCleanDestroyStageForQairtCertification() {
        val file = File(Files.createTempDirectory("qairt-smoke-log").toFile(), "run.json")
        val log = AtomicSmokeEventLog(file, "run", 1L) { 2L }

        log.append(
            JSONObject()
                .put("status", "runner_stage")
                .put("stage", "qairt_vlm_destroy_ok")
        )

        assertTrue(log.hasRunnerStage("_destroy_ok"))
        assertTrue(log.destroyFailureStages().isEmpty())
    }
}

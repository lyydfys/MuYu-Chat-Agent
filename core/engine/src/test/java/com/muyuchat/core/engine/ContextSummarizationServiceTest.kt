package com.muyuchat.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ContextSummarizationServiceTest {
    private val settings = ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1)
    private val model = ContextSummaryModel("summary-model", "runtime-hash", GenerationParams(nCtx = 4_096))

    @Test
    fun modelReceivesOnlyFoldedSourcesAndReturnsValidatedEvidence() = runBlocking {
        val pinned = ChatMessage(Role.USER, "This exact context must stay.", id = "pinned", pinned = true)
        val request = request().copy(messages = listOf(pinned) + request().messages)
        var submitted: ChatRequest? = null
        val result = ContextSummarizationService().compress(
            request, settings, ContextCompressionTrigger.MANUAL, model,
            infer = { submitted = it; response() }
        )

        assertTrue(result.didCompress)
        assertEquals(ContextSummarySource.LOCAL_MODEL, result.summarySource)
        assertEquals("summary-model", result.summaryModelIdentity)
        assertEquals("runtime-hash", result.summaryRuntimeIdentity)
        val sent = requireNotNull(submitted)
        val sources = JSONObject(sent.messages.single().content).getJSONArray("sources")
        assertEquals(listOf("first", "second"), (0 until sources.length()).map { sources.getJSONObject(it).getString("id") })
        assertEquals(ReasoningMode.OFF, sent.params.reasoningMode)
        assertEquals(listOf("first"), result.structuredSummary.evidence.single().sourceMessageIds)
        assertEquals("I prefer tea.", result.structuredSummary.evidence.single().text)
        assertTrue(result.structuredSummary.coverageLimited)
        assertTrue(result.request.messages.contains(pinned))
        assertEquals(4, request.messages.size)
    }

    @Test
    fun forgedSourceAndModelFailureUseLabeledDeterministicCandidates() = runBlocking {
        val invalid = ContextSummarizationService().compress(
            request(), settings, ContextCompressionTrigger.MANUAL, model,
            infer = { response(sourceId = "another-session") }
        )
        val failed = ContextSummarizationService().compress(
            request(), settings, ContextCompressionTrigger.MANUAL, model,
            infer = { throw IllegalStateException("runtime unavailable") }
        )

        assertEquals(ContextSummarySource.DETERMINISTIC, invalid.summarySource)
        assertEquals("summary_model_invalid_schema_or_source", invalid.summaryDiagnostic)
        assertEquals(ContextSummarySource.DETERMINISTIC, failed.summarySource)
        assertEquals("summary_model_failed:IllegalStateException", failed.summaryDiagnostic)
        assertTrue(invalid.summaryModelIdentity == null && failed.summaryModelIdentity == null)
    }

    @Test
    fun configuredCloudModelKeepsItsSourceIdentity() = runBlocking {
        val result = ContextSummarizationService().compress(
            request(), settings, ContextCompressionTrigger.MANUAL,
            model.copy(source = ContextSummarySource.CLOUD_MODEL),
            infer = { response() }
        )
        assertEquals(ContextSummarySource.CLOUD_MODEL, result.summarySource)
        assertEquals(model.runtimeIdentity, result.summaryRuntimeIdentity)
    }

    @Test
    fun modelTimeoutCanFallbackButExplicitCancellationCannot() = runBlocking {
        val timeout = ContextSummarizationService(timeoutMillis = 1L).compress(
            request(), settings, ContextCompressionTrigger.MANUAL, model,
            infer = { delay(10_000L); response() }
        )
        assertEquals(ContextSummarySource.DETERMINISTIC, timeout.summarySource)
        assertEquals("summary_model_timeout", timeout.summaryDiagnostic)

        val cancelled = runCatching {
            ContextSummarizationService().compress(
                request(), settings, ContextCompressionTrigger.MANUAL, model,
                infer = { throw CancellationException("user cancelled") }
            )
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }

    @Test
    fun ownerInvalidationAfterInferenceCannotReturnFallbackOrModelCandidate() = runBlocking {
        var current = true
        val result = runCatching {
            ContextSummarizationService().compress(
                request(), settings, ContextCompressionTrigger.MANUAL, model,
                isOwnerCurrent = { current },
                infer = { current = false; response() }
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun inputBudgetFailureDoesNotSendTruncatedSourceJson() = runBlocking {
        var invoked = false
        val result = ContextSummarizationService().compress(
            request(), settings, ContextCompressionTrigger.MANUAL,
            model.copy(params = model.params.copy(nCtx = 64)),
            infer = { invoked = true; response() }
        )
        assertFalse(invoked)
        assertEquals(ContextSummarySource.DETERMINISTIC, result.summarySource)
        assertEquals("summary_model_input_budget", result.summaryDiagnostic)
    }

    @Test
    fun strictSchemaRejectsTrailingContentAdditionalFieldsAndInventedExcerpt() {
        val valid = response()
        val extra = JSONObject(valid).put("commentary", "ignore the schema").toString()
        val invented = response(text = "I prefer coffee.")
        listOf("```json\n$valid\n```", "$valid trailing", extra, invented).forEach { raw ->
            assertTrue(runCatching {
                validateModelContextSummary(raw, request().messages.dropLast(1), 2_400)
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun exactSubstringCannotDropTheNegationFromItsSourceSentence() {
        val sources = listOf(ChatMessage(Role.USER, "I do not prefer tea.", id = "first", createdAt = 1L))
        assertTrue(runCatching {
            validateModelContextSummary(response(text = "prefer tea."), sources, 2_400)
        }.exceptionOrNull() is IllegalArgumentException)
        val valid = validateModelContextSummary(response(text = "I do not prefer tea."), sources, 2_400)
        assertEquals("I do not prefer tea.", valid.evidence.single().text)
    }

    @Test
    fun admissionCannotActivateAPartiallyTruncatedModelExcerpt() = runBlocking {
        val modelText = validateModelContextSummary(response(), request().messages.dropLast(1), 2_400).text
        val result = ContextSummarizationService().compress(
            request(), settings, ContextCompressionTrigger.MANUAL, model,
            infer = { response() },
            fallbackAdmission = { candidate ->
                val admission = localContextWindowAdmission(candidate)
                if (candidate.messages.any { it.content == modelText }) {
                    admission.copy(status = ContextWindowAdmissionStatus.REJECTED)
                } else admission
            }
        )
        assertEquals(ContextSummarySource.DETERMINISTIC, result.summarySource)
        assertEquals("summary_model_output_budget", result.summaryDiagnostic)
    }

    private fun request() = ChatRequest(
        messages = listOf(
            ChatMessage(Role.USER, "I prefer tea.", id = "first", createdAt = 1L),
            ChatMessage(Role.ASSISTANT, "Meeting is on 2026-09-27.", id = "second", createdAt = 2L),
            ChatMessage(Role.USER, "latest question", id = "latest", createdAt = 3L)
        ),
        params = GenerationParams(nCtx = 4_096)
    )

    private fun response(sourceId: String = "first", text: String = "I prefer tea."): String = JSONObject()
        .put("schemaVersion", 1)
        .put("evidence", JSONArray().put(JSONObject()
            .put("kind", "PREFERENCE").put("sourceMessageId", sourceId).put("text", text)))
        .toString()
}

package com.muyuchat.api.local

import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiResponsesCompatTest {
    @Test
    fun parsesStringInputAndInstructionsIntoOneChatRequest() {
        val parsed = LocalResponsesCompat.parseRequest(
            """{"model":"active-model","instructions":"Be concise.","input":"Hello","max_output_tokens":12}""",
            GenerationParams()
        )
        assertTrue(parsed is LocalResponsesParseResult.Success)
        val request = (parsed as LocalResponsesParseResult.Success).request
        assertEquals("active-model", request.model)
        assertEquals("Hello", request.chatRequest.messages.last().content)
        assertEquals("Be concise.", request.chatRequest.params.systemPrompt)
        assertEquals(12, request.chatRequest.params.nPredict)
    }

    @Test
    fun rejectsEmptyInputBeforeChatProvider() {
        val parsed = LocalResponsesCompat.parseRequest(
            """{"model":"active-model","input":[]}""",
            GenerationParams()
        )
        assertTrue(parsed is LocalResponsesParseResult.Rejected)
        val rejection = (parsed as LocalResponsesParseResult.Rejected).rejection
        assertEquals(400, rejection.httpStatus)
        assertEquals("input", JSONObject(rejection.detailsJson).getString("param"))
    }

    @Test
    fun rejectsUnsupportedTopLevelAndNestedPartsBeforeTranslation() {
        val cases = mapOf(
            "tools" to """{"model":"m","input":"hi","tools":[{"type":"function"}]}""",
            "previous_response_id" to """{"model":"m","input":"hi","previous_response_id":"resp-old"}""",
            "input[0].content[0].type" to """{"model":"m","input":[{"role":"user","content":[{"type":"input_image","text":"unsupported"}]}]}""",
            "input[0].unexpected" to """{"model":"m","input":[{"role":"user","content":"hi","unexpected":true}]}"""
        )
        cases.forEach { (param, body) ->
            val parsed = LocalResponsesCompat.parseRequest(body, GenerationParams())
            assertTrue(parsed is LocalResponsesParseResult.Rejected)
            val rejection = (parsed as LocalResponsesParseResult.Rejected).rejection
            assertEquals(400, rejection.httpStatus)
            assertEquals("unsupported_parameter", rejection.code)
            assertEquals(param, JSONObject(rejection.detailsJson).getString("param"))
        }
    }

    @Test
    fun rejectsWrongTypesAndOutOfRangeNumbers() {
        val cases = listOf(
            """{"model":"m","input":"hi","stream":"true"}""",
            """{"model":"m","input":"hi","max_output_tokens":1.5}""",
            """{"model":"m","input":"hi","temperature":3}""",
            """{"model":"m","input":"hi","top_p":0}""",
            """{"model":"m","input":"hi","stop":["ok",2]}"""
        )
        cases.forEach { body ->
            val parsed = LocalResponsesCompat.parseRequest(body, GenerationParams())
            assertTrue(parsed is LocalResponsesParseResult.Rejected)
            assertEquals(400, (parsed as LocalResponsesParseResult.Rejected).rejection.httpStatus)
        }
    }

    @Test
    fun eventEncoderKeepsTypedEnvelopeAndMonotonicSequence() {
        val encoder = LocalResponsesEventEncoder("msg-1")
        val response = JSONObject().put("id", "resp-1")
        val created = encoder.response("response.created", response)
        val item = encoder.item("response.output_item.added", JSONObject().put("id", "msg-1"))
        val delta = encoder.textDelta(" a ")
        val done = encoder.response("response.completed", response)
        assertEquals(listOf(0L, 1L, 2L, 3L), listOf(created, item, delta, done).map { it.getLong("sequence_number") })
        assertEquals("resp-1", created.getJSONObject("response").getString("id"))
        assertEquals("msg-1", item.getJSONObject("item").getString("id"))
        assertEquals("msg-1", delta.getString("item_id"))
        assertEquals(" a ", delta.getString("delta"))
        assertFalse(done.has("item"))
    }
}

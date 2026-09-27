package com.muyuchat.api.local

import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}

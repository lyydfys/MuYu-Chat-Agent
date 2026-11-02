package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshModelRole
import com.muyuchat.core.dsh.contract.DshToolChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshOpenAiRequestTest {

    private val limits = DshGatewayLimits.Default

    @Test
    fun mapsTheFullDshToolConversationWithoutLosingFields() {
        val body = """
            {
              "model": "fake-model",
              "messages": [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": [
                  {"type": "text", "text": "hi "},
                  {"type": "text", "text": "there"}
                ]},
                {"role": "assistant", "content": null, "tool_calls": [
                  {"id": "call_1", "type": "function", "function": {"name": "read_file", "arguments": "{\"path\":\"a.txt\"}"}},
                  {"id": "call_2", "type": "function", "function": {"name": "shell", "arguments": "{}"}}
                ]},
                {"role": "tool", "tool_call_id": "call_1", "content": "file body"},
                {"role": "tool", "tool_call_id": "call_2", "content": "ok"}
              ],
              "tools": [
                {"type": "function", "function": {"name": "read_file", "description": "Read",
                  "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
                {"type": "function", "function": {"name": "shell", "description": "Shell",
                  "parameters": {"type": "object", "properties": {"cmd": {"type": "string"}}}}}
              ],
              "tool_choice": "none",
              "temperature": 0.25,
              "max_completion_tokens": 64,
              "stream": true,
              "stream_options": {"include_usage": true}
            }
        """.trimIndent()
        val success = parseSuccess(body)
        val request = success.request

        assertEquals("dsh:7:1", request.callId)
        assertEquals("fake-model", request.modelId)
        assertTrue(request.stream)
        assertTrue(success.includeUsage)
        assertEquals(0.25, request.temperature!!, 0.0)
        assertEquals(64, request.maxTokens)
        assertEquals(DshToolChoice.NONE, request.toolChoice)

        assertEquals(5, request.messages.size)
        assertEquals(DshModelRole.SYSTEM, request.messages[0].role)
        assertEquals("sys", request.messages[0].content)
        assertEquals(DshModelRole.USER, request.messages[1].role)
        assertEquals("hi there", request.messages[1].content)
        assertEquals(DshModelRole.ASSISTANT, request.messages[2].role)
        assertNull(request.messages[2].content)
        assertEquals(listOf("call_1", "call_2"), request.messages[2].toolCalls.map { it.id })
        assertEquals(listOf("read_file", "shell"), request.messages[2].toolCalls.map { it.name })
        assertEquals("""{"path":"a.txt"}""", request.messages[2].toolCalls[0].argumentsJson)
        assertEquals("{}", request.messages[2].toolCalls[1].argumentsJson)
        assertEquals(DshModelRole.TOOL, request.messages[3].role)
        assertEquals("call_1", request.messages[3].toolCallId)
        assertEquals("file body", request.messages[3].content)
        assertEquals("call_2", request.messages[4].toolCallId)

        assertEquals(listOf("read_file", "shell"), request.tools.map { it.name })
        assertEquals("Read", request.tools[0].description)
        assertEquals(
            """{"properties":{"path":{"type":"string"}},"required":["path"],"type":"object"}""",
            request.tools[0].parametersJson
        )
        assertEquals(
            """{"properties":{"cmd":{"type":"string"}},"type":"object"}""",
            request.tools[1].parametersJson
        )
    }

    @Test
    fun developerRoleMapsToSystemForDshProviderCompatibility() {
        val body = chatRequestBody(
            messagesJson = """[{"role":"developer","content":"dev rules"},{"role":"user","content":"hi"}]"""
        )
        val request = parseSuccess(body).request
        assertEquals(DshModelRole.SYSTEM, request.messages[0].role)
        assertEquals("dev rules", request.messages[0].content)
    }

    @Test
    fun namedToolChoiceIsValidatedThenMappedToRequired() {
        val tools = """"tools":[{"type":"function","function":{"name":"read_file","parameters":{}}}]"""
        val named = chatRequestBody(extraFieldsJson = tools + ""","tool_choice":{"type":"function","function":{"name":"read_file"}}""")
        assertEquals(DshToolChoice.REQUIRED, parseSuccess(named).request.toolChoice)

        val unknown = chatRequestBody(extraFieldsJson = tools + ""","tool_choice":{"type":"function","function":{"name":"missing"}}""")
        assertEquals("invalid_tool_choice", parseRejected(unknown).code)
    }

    @Test
    fun toolParametersStringIsPreservedAndObjectIsCanonicalised() {
        val objectParameters = chatRequestBody(
            extraFieldsJson = """"tools":[{"type":"function","function":{"name":"a","parameters":{"z":1,"a":2}}}]"""
        )
        assertEquals(
            """{"a":2,"z":1}""",
            parseSuccess(objectParameters).request.tools.single().parametersJson
        )
        val stringParameters = chatRequestBody(
            extraFieldsJson = """"tools":[{"type":"function","function":{"name":"a","parameters":"{\"x\": [1,2]}"}}]"""
        )
        assertEquals(
            """{"x": [1,2]}""",
            parseSuccess(stringParameters).request.tools.single().parametersJson
        )
    }

    @Test
    fun maxTokensFallsBackToLegacyField() {
        val body = chatRequestBody(extraFieldsJson = """"max_tokens":32""")
        assertEquals(32, parseSuccess(body).request.maxTokens)
    }

    @Test
    fun streamsAreNotFlaggedWithoutStream() {
        val request = parseSuccess(chatRequestBody()).request
        assertEquals(false, request.stream)
        assertNull(request.maxTokens)
        assertNull(request.temperature)
    }

    @Test
    fun malformedAndIncompleteBodiesAreRejectedWithStableCodes() {
        assertEquals("invalid_request", parseRejected("{").code)
        assertEquals(400, parseRejected("{").status)
        assertEquals("invalid_request", parseRejected("""{"model":"fake-model"}""").code)
        assertEquals("invalid_request", parseRejected("""{"messages":[{"role":"user","content":"x"}]}""").code)
        assertEquals(
            "invalid_request",
            parseRejected("""{"model":"fake-model","messages":[]}""").code
        )
        assertEquals(
            "unsupported_role",
            parseRejected("""{"model":"fake-model","messages":[{"role":"robot","content":"x"}]}""").code
        )
        assertEquals(
            "unsupported_role",
            parseRejected("""{"model":"fake-model","messages":[{"role":"function","content":"x"}]}""").code
        )
    }

    @Test
    fun malformedToolTranscriptFieldsAreRejected() {
        assertEquals(
            "unsupported_tool_call",
            parseRejected(
                """{"model":"fake-model","messages":[{"role":"assistant","content":"x","tool_calls":[{"id":"c","type":"custom","function":{"name":"n","arguments":"{}"}}]}]}"""
            ).code
        )
        assertEquals(
            "invalid_request",
            parseRejected(
                """{"model":"fake-model","messages":[{"role":"assistant","content":"x","tool_calls":[{"id":"c","type":"function","function":{"name":"n","arguments":{}}}]}]}"""
            ).code
        )
        assertEquals(
            "invalid_request",
            parseRejected(
                """{"model":"fake-model","messages":[{"role":"assistant","content":"x","tool_calls":[{"id":"c","type":"function","function":{"name":"n","arguments":"not json"}}]}]}"""
            ).code
        )
        assertEquals(
            "invalid_request",
            parseRejected("""{"model":"fake-model","messages":[{"role":"tool","content":"result"}]}""").code
        )
        assertEquals(
            "invalid_request",
            parseRejected("""{"model":"fake-model","messages":[{"role":"user","content":"x","tool_call_id":"c"}]}""").code
        )
        assertEquals(
            "invalid_request",
            parseRejected("""{"model":"fake-model","messages":[{"role":"assistant","content":null}]}""").code
        )
    }

    @Test
    fun imagePartsAreRejectedInsteadOfSilentlyDropped() {
        val rejection = parseRejected(
            """{"model":"fake-model","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"}}]}]}"""
        )
        assertEquals("unsupported_content_part", rejection.code)
        assertEquals(400, rejection.status)
    }

    @Test
    fun unsupportedSemanticFieldsAreRejected() {
        assertEquals("unsupported_parameter", parseRejected(chatRequestBody(extraFieldsJson = """"n":2""")).code)
        assertEquals(
            "unsupported_parameter",
            parseRejected(chatRequestBody(extraFieldsJson = """"response_format":{"type":"json_object"}""")).code
        )
        assertEquals(
            "unsupported_parameter",
            parseRejected(chatRequestBody(extraFieldsJson = """"logprobs":true""")).code
        )
        assertEquals(
            "unsupported_parameter",
            parseRejected(chatRequestBody(extraFieldsJson = """"parallel_tool_calls":false""")).code
        )
    }

    @Test
    fun numericAndTypeValidationIsStrict() {
        assertEquals(
            "invalid_temperature",
            parseRejected(chatRequestBody(extraFieldsJson = """"temperature":3""")).code
        )
        assertEquals(
            "invalid_request",
            parseRejected(chatRequestBody(extraFieldsJson = """"max_tokens":12,"max_completion_tokens":12""")).code
        )
        assertEquals(
            "invalid_request",
            parseRejected(chatRequestBody(extraFieldsJson = """"max_tokens":0""")).code
        )
        assertEquals(
            "invalid_request",
            parseRejected(chatRequestBody().replace("\"stream\":false", "\"stream\":\"yes\"").replace("\"stream\" : false", "\"stream\":\"yes\"")).code
        )
        assertEquals(
            "invalid_tool_choice",
            parseRejected(chatRequestBody(extraFieldsJson = """"tool_choice":"required"""")).code
        )
        assertEquals(
            "invalid_tool_choice",
            parseRejected(chatRequestBody(extraFieldsJson = """"tool_choice":"sometimes"""")).code
        )
    }

    private fun parseSuccess(body: String): DshOpenAiParse.Success {
        val parsed = DshOpenAiRequest.parse(body, callId = "dsh:7:1", limits = limits)
        assertTrue("Expected success but was $parsed", parsed is DshOpenAiParse.Success)
        return parsed as DshOpenAiParse.Success
    }

    private fun parseRejected(body: String): DshOpenAiParse.Rejected {
        val parsed = DshOpenAiRequest.parse(body, callId = "dsh:7:1", limits = limits)
        assertTrue("Expected rejection but was $parsed", parsed is DshOpenAiParse.Rejected)
        return parsed as DshOpenAiParse.Rejected
    }
}

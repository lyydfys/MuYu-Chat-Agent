package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshGatewayModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshOpenAiWireTest {

    @Test
    fun finishReasonsStayDistinct() {
        assertEquals("stop", DshOpenAiWire.finishReason(DshFinishReason.STOP))
        assertEquals("tool_calls", DshOpenAiWire.finishReason(DshFinishReason.TOOL_CALLS))
        assertEquals("length", DshOpenAiWire.finishReason(DshFinishReason.LENGTH))
        assertEquals("error", DshOpenAiWire.finishReason(DshFinishReason.ERROR))
        assertEquals("cancelled", DshOpenAiWire.finishReason(DshFinishReason.CANCELLED))
    }

    @Test
    fun roleTextAndReasoningFramesMatchOpenAiChunkShape() {
        val id = "chatcmpl-test"
        val role = JSONObject(DshOpenAiWire.roleChunk(id, 123L, "fake-model"))
        assertEquals("chat.completion.chunk", role.getString("object"))
        assertEquals("assistant", role.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("role"))
        assertTrue(role.getJSONArray("choices").getJSONObject(0).isNull("finish_reason"))

        val text = JSONObject(DshOpenAiWire.contentChunk(id, 123L, "fake-model", "héllo\n"))
        assertEquals("héllo\n", text.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("content"))

        val reasoning = JSONObject(DshOpenAiWire.reasoningChunk(id, 123L, "fake-model", "think"))
        assertEquals(
            "think",
            reasoning.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").getString("reasoning_content")
        )
    }

    @Test
    fun emptyDeltaHeartbeatIsAValidChunkWithNoContentOrFinish() {
        val chunk = JSONObject(DshOpenAiWire.emptyDeltaChunk("chatcmpl-test", 123L, "fake-model"))
        assertEquals("chat.completion.chunk", chunk.getString("object"))
        val choice = chunk.getJSONArray("choices").getJSONObject(0)
        assertEquals(0, choice.getJSONObject("delta").length())
        assertTrue(choice.isNull("finish_reason"))
    }

    @Test
    fun toolCallDeltaCarriesStableIndexIdAndName() {
        val chunk = JSONObject(
            DshOpenAiWire.toolCallChunk(
                id = "chatcmpl-test",
                created = 123L,
                model = "fake-model",
                index = 2,
                toolCallId = "call_2",
                name = "read_file",
                argumentsFragment = "{\"path\":"
            )
        )
        val call = chunk.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(2, call.getInt("index"))
        assertEquals("call_2", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))
        assertEquals("{\"path\":", call.getJSONObject("function").getString("arguments"))

        val continuation = JSONObject(
            DshOpenAiWire.toolCallChunk(
                id = "chatcmpl-test",
                created = 123L,
                model = "fake-model",
                index = 2,
                toolCallId = null,
                name = null,
                argumentsFragment = "\"a\"}"
            )
        )
        val continuationCall = continuation.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(2, continuationCall.getInt("index"))
        assertFalse(continuationCall.has("id"))
        assertFalse(continuationCall.getJSONObject("function").has("name"))
    }

    @Test
    fun usageChunkIsAbsentWithoutTokenCountsAndSumsWhenBothExist() {
        assertNull(DshOpenAiWire.usageChunk("id", 1L, "m", null, null))
        val usage = JSONObject(DshOpenAiWire.usageChunk("id", 1L, "m", 11, 5)!!)
            .getJSONObject("usage")
        assertEquals(11, usage.getInt("prompt_tokens"))
        assertEquals(5, usage.getInt("completion_tokens"))
        assertEquals(16, usage.getInt("total_tokens"))
        assertEquals(0, JSONObject(DshOpenAiWire.usageChunk("id", 1L, "m", 11, 5)!!).getJSONArray("choices").length())
    }

    @Test
    fun nonStreamCompletionEncodesToolCallsAndNullContent() {
        val toolCalls = listOf(
            DshAssembledToolCall(0, "call_1", "read_file", """{"path":"a"}"""),
            DshAssembledToolCall(1, "call_2", "shell", "{}")
        )
        val completion = JSONObject(
            DshOpenAiWire.completionJson(
                id = "chatcmpl-test",
                created = 1L,
                model = "fake-model",
                content = "",
                reasoning = "thinking",
                toolCalls = toolCalls,
                finishReason = DshFinishReason.TOOL_CALLS,
                promptTokens = 7,
                completionTokens = 3
            )
        )
        val choice = completion.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        val message = choice.getJSONObject("message")
        assertTrue(message.isNull("content"))
        assertEquals("thinking", message.getString("reasoning_content"))
        val encoded = message.getJSONArray("tool_calls")
        assertEquals(2, encoded.length())
        assertEquals("call_1", encoded.getJSONObject(0).getString("id"))
        assertEquals("function", encoded.getJSONObject(0).getString("type"))
        assertEquals("read_file", encoded.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("""{"path":"a"}""", encoded.getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(10, completion.getJSONObject("usage").getInt("total_tokens"))
    }

    @Test
    fun modelListingLabelsPromptCompatModelsHonestly() {
        val models = listOf(
            DshGatewayModel("native", "Native Model", 4096, 512, true, true),
            DshGatewayModel("prompted", "Prompted Model", 4096, 512, true, false),
            DshGatewayModel("labelled", "Labelled Model", null, null, true, false, "prompted-json"),
            DshGatewayModel("plain", "Plain Model", null, null, false, false)
        )
        val listing = JSONObject(DshOpenAiWire.modelsJson(models, "rev-1", 5L))
        assertEquals("list", listing.getString("object"))
        assertEquals("rev-1", listing.getString("mca_catalog_revision"))
        val data = listing.getJSONArray("data")
        assertEquals(4, data.length())

        val native = data.getJSONObject(0)
        assertEquals("Native Model", native.getString("name"))
        assertTrue(native.getBoolean("native_tool_calling"))
        assertFalse(native.has("tool_protocol_label"))
        assertEquals(4096, native.getInt("context_window"))
        assertEquals(512, native.getInt("max_output_tokens"))

        val prompted = data.getJSONObject(1)
        assertEquals("Prompted Model [prompt-compat]", prompted.getString("name"))
        assertEquals("Prompted Model [prompt-compat]", prompted.getString("display_name"))
        assertEquals("Prompted Model", prompted.getString("mca_display_name"))
        assertFalse(prompted.getBoolean("native_tool_calling"))
        assertEquals("prompt-compat", prompted.getString("tool_protocol_label"))

        val labelled = data.getJSONObject(2)
        assertEquals("Labelled Model [prompted-json]", labelled.getString("name"))
        assertEquals("prompted-json", labelled.getString("tool_protocol_label"))
        assertFalse(labelled.has("context_window"))

        val plain = data.getJSONObject(3)
        assertEquals("Plain Model", plain.getString("name"))
        assertFalse(plain.has("tool_protocol_label"))
    }

    @Test
    fun doneFrameAndErrorFrameAreOpenAiShaped() {
        assertEquals("data: [DONE]\n\n", DshOpenAiWire.DONE_FRAME)
        assertEquals("data: {\"x\":1}\n\n", DshOpenAiWire.frame("{\"x\":1}"))
        val error = JSONObject(DshOpenAiWire.errorChunk("server_error", "backend_error", "boom"))
            .getJSONObject("error")
        assertEquals("server_error", error.getString("type"))
        assertEquals("backend_error", error.getString("code"))
        assertEquals("boom", error.getString("message"))
    }
}

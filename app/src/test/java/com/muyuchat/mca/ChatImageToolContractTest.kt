package com.muyuchat.mca

import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ChatImageToolContractTest {
    @Test
    fun schemaAllowsPromptAndOptionalNegativePromptOnly() {
        val definition = chatImageToolDefinition()
        assertEquals(CHAT_IMAGE_TOOL_NAME, definition.name)
        val schema = JSONObject(definition.parametersJson)
        assertEquals("object", schema.getString("type"))
        assertEquals(setOf("prompt", "negative_prompt"), schema.getJSONObject("properties").keys().asSequence().toSet())
        assertEquals("prompt", schema.getJSONArray("required").getString(0))
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun acceptsOnlyBoundedNonBlankPrompt() {
        val result = validateChatImageToolCall(
            ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":\"  a small red kite  \"}")
        )
        assertEquals(ChatImageToolArguments.Valid("a small red kite"), result)
    }

    @Test
    fun acceptsChineseNegativePromptForTheBridgePath() {
        val result = validateChatImageToolCall(
            ChatToolCall(
                "call-1",
                CHAT_IMAGE_TOOL_NAME,
                JSONObject()
                    .put("prompt", "一只红猫")
                    .put("negative_prompt", "不要文字，不要水印")
                    .toString()
            )
        )
        assertEquals(
            ChatImageToolArguments.Valid("一只红猫", "不要文字，不要水印"),
            result
        )
    }

    @Test
    fun rejectsMalformedUnknownOrWronglyTypedArguments() {
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{")) is ChatImageToolArguments.Invalid)
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":\"cat\",\"width\":512}")) is ChatImageToolArguments.Invalid)
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":7}")) is ChatImageToolArguments.Invalid)
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":\"cat\",\"negative_prompt\":7}")) is ChatImageToolArguments.Invalid)
        assertTrue(validate(ChatToolCall("call-1", "delete_file", "{\"prompt\":\"cat\"}")) is ChatImageToolArguments.Invalid)
        assertTrue(validate(ChatToolCall("", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":\"cat\"}")) is ChatImageToolArguments.Invalid)
    }

    @Test
    fun rejectsBlankAndOverlongPrompt() {
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, "{\"prompt\":\"  \"}")) is ChatImageToolArguments.Invalid)
        val overLimit = "x".repeat(LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS + 1)
        assertTrue(validate(ChatToolCall("call-1", CHAT_IMAGE_TOOL_NAME, JSONObject().put("prompt", overLimit).toString())) is ChatImageToolArguments.Invalid)
    }

    @Test
    fun toolContextFingerprintDetectsHistoryChangesWithoutStoringInlineImageData() {
        val original = listOf(
            ChatMessage(Role.USER, "make this a watercolor", imageAttachments = listOf(
                ChatImageAttachment(
                    name = "reference.png",
                    uriString = "content://images/reference",
                    dataBase64 = "secret-image-bytes"
                )
            ))
        )
        val fingerprint = chatImageToolContextFingerprint(original)

        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains("secret-image-bytes"))
        assertTrue(fingerprint != chatImageToolContextFingerprint(original.map { it.copy(content = "changed") }))
        assertTrue(fingerprint != chatImageToolContextFingerprint(original.map { message ->
            message.copy(imageAttachments = message.imageAttachments.map { it.copy(dataBase64 = "changed") })
        }))
    }

    private fun validate(call: ChatToolCall) = validateChatImageToolCall(call)
}

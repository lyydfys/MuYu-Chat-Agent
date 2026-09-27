package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGeneratedImageRequestTest {
    @Test
    fun requestJsonRoundTripPreservesStatusMessageAndAllImageAssets() {
        val request = ChatGeneratedImageRequest(
            id = "image-task-17",
            prompt = "misty mountain village at sunrise",
            status = ChatGeneratedImageStatus.DONE,
            message = "Generated 2 images.",
            imageAssetIds = listOf("asset-1", "asset-2")
        )

        assertEquals(request, ChatGeneratedImageRequest.fromJsonOrNull(request.toJson()))
    }

    @Test
    fun legacyRequestJsonWithoutOptionalFieldsUsesSafeDefaults() {
        val legacy = JSONObject(
            """{"id":"legacy-task","prompt":"a quiet lake","status":"QUEUED"}"""
        )

        assertEquals(
            ChatGeneratedImageRequest(
                id = "legacy-task",
                prompt = "a quiet lake",
                status = ChatGeneratedImageStatus.QUEUED
            ),
            ChatGeneratedImageRequest.fromJsonOrNull(legacy)
        )
    }

    @Test
    fun assistantToolRequestRoundTripKeepsRecoverableContinuationContext() {
        val request = ChatGeneratedImageRequest(
            id = "assistant-image-task",
            prompt = "A blue fox under a pine tree",
            status = ChatGeneratedImageStatus.AWAITING_APPROVAL,
            origin = ChatGeneratedImageOrigin.ASSISTANT_TOOL,
            backendId = "LOCAL",
            modelId = "image-model-1",
            modelName = "MeinaMix",
            generationOptionsJson = "{\"batchCount\":1}",
            toolCallId = "call-1",
            toolArgumentsJson = "{\"prompt\":\"A blue fox under a pine tree\"}",
            toolChatModelId = "chat-model-1",
            toolParamsJson = "{\"temperature\":0.7}",
            toolRequestMessageCount = 4,
            toolRequestMessagesFingerprint = "a".repeat(64),
            toolContinuationStatus = ChatImageToolContinuationStatus.FAILED
        )

        assertEquals(request, ChatGeneratedImageRequest.fromJsonOrNull(request.toJson()))
        assertFalse(request.toJson().optBoolean("toolContinuationStarted"))
    }

    @Test
    fun continuationStatusAndLegacyStartedMirrorStayConsistent() {
        listOf(
            ChatImageToolContinuationStatus.NOT_STARTED to false,
            ChatImageToolContinuationStatus.RUNNING to true,
            ChatImageToolContinuationStatus.COMPLETED to true,
            ChatImageToolContinuationStatus.FAILED to false
        ).forEach { (status, legacyStarted) ->
            val request = ChatGeneratedImageRequest(
                id = "request-${status.name}",
                prompt = "a blue cup",
                status = ChatGeneratedImageStatus.DONE,
                origin = ChatGeneratedImageOrigin.ASSISTANT_TOOL,
                toolContinuationStatus = status,
                toolContinuationStarted = !legacyStarted
            )
            val restored = ChatGeneratedImageRequest.fromJsonOrNull(request.toJson())
            assertEquals(status, restored?.toolContinuationStatus)
            assertEquals(legacyStarted, restored?.toolContinuationStarted)
            assertEquals(legacyStarted, request.toJson().optBoolean("toolContinuationStarted"))
        }
        val legacy = ChatGeneratedImageRequest.fromJsonOrNull(
            JSONObject("""{"id":"legacy-tool","prompt":"cup","status":"DONE","origin":"ASSISTANT_TOOL","toolContinuationStarted":true}""")
        )
        assertEquals(ChatImageToolContinuationStatus.RUNNING, legacy?.toolContinuationStatus)
    }

    @Test
    fun absentOrInvalidLegacyGeneratedImagePayloadDoesNotBreakMessageParsing() {
        assertNull(ChatGeneratedImageRequest.fromJsonOrNull(null))
        assertNull(ChatGeneratedImageRequest.fromJsonOrNull(JSONObject()))
        assertNull(
            ChatGeneratedImageRequest.fromJsonOrNull(
                JSONObject("""{"id":"broken","prompt":"image","status":"unknown"}""")
            )
        )
    }

    @Test
    fun generatedImageAssetsAreNotSerializedAsUserVisionAttachments() {
        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = "",
            generatedImageRequest = ChatGeneratedImageRequest(
                id = "image-task-18",
                prompt = "blue ceramic cup",
                status = ChatGeneratedImageStatus.DONE,
                imageAssetIds = listOf("generated-asset")
            )
        )

        assertTrue(message.imageAttachments.isEmpty())
        val serialized = ChatRequest(listOf(message)).messagesJson(multimodal = true)
        assertFalse(serialized.contains("generated-asset"))
        assertFalse(serialized.contains("image_url"))
    }
}

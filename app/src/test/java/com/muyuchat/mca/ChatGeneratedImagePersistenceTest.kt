package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files

class ChatGeneratedImagePersistenceTest {
    @Test
    fun generatedImageRequestSurvivesRoomMessageRoundTripWithoutBecomingAnAttachment() {
        val request = ChatGeneratedImageRequest(
            id = "task-23",
            prompt = "a lantern beside a river",
            status = ChatGeneratedImageStatus.DONE,
            message = "Generated successfully",
            imageAssetIds = listOf("image-asset-23", "image-asset-24"),
            origin = ChatGeneratedImageOrigin.ASSISTANT_TOOL,
            currentJobId = "job-23",
            backendId = "mnn-cpu",
            modelId = "sd-model-23",
            generationOptionsJson = """{"version":1,"width":512,"height":512,"steps":20}""",
            toolCallId = "call-23",
            toolArgumentsJson = """{"prompt":"a lantern beside a river"}""",
            toolOutputJson = """{"status":"done","imageCount":2}"""
        )
        val original = ChatMessage(
            role = Role.ASSISTANT,
            content = "",
            generatedImageRequest = request
        )

        val persisted = original.toEntity(sessionId = "session-1", position = 3)
        val restored = persisted.toChatMessage()

        assertEquals(request, restored.generatedImageRequest)
        assertEquals(request.toJson().toString(), persisted.generatedImageJson)
        assertEquals("[]", persisted.imageAttachmentsJson)
        assertTrue(restored.imageAttachments.isEmpty())
    }

    @Test
    fun legacyRoomMessageWithoutGeneratedImageColumnValueLoadsNormally() {
        val legacy = ChatMessageEntity(
            sessionId = "old-session",
            position = 0,
            role = Role.ASSISTANT.name,
            content = "legacy reply",
            createdAt = 1L,
            tokenCount = null,
            generatedImageJson = null
        )

        val restored = legacy.toChatMessage()
        assertEquals("legacy reply", restored.content)
        assertNull(restored.generatedImageRequest)
    }

    @Test
    fun legacyRoomMessageWithDuplicateFileRepresentationsLoadsOneImage() {
        val image = Files.createTempFile("mca-chat-history-duplicate", ".png").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val attachments = JSONArray()
            .put(JSONObject().put("name", "first.png").put("uriString", image.absolutePath))
            .put(JSONObject().put("name", "second.png").put("uriString", image.toURI().toString()))
        val legacy = ChatMessageEntity(
            sessionId = "old-session",
            position = 0,
            role = Role.USER.name,
            content = "What is in this picture?",
            createdAt = 1L,
            tokenCount = null,
            imageAttachmentsJson = attachments.toString()
        )

        val restored = legacy.toChatMessage()

        assertEquals(1, restored.imageAttachments.size)
        assertEquals("first.png", restored.imageAttachments.single().name)
    }

    @Test
    fun olderGeneratedImageJsonWithoutOptionalFieldsUsesStableDefaults() {
        val oldPayload = JSONObject(
            """{"id":"legacy-task","prompt":"a cat","status":"DONE","imageAssetIds":["asset-1"]}"""
        )

        val restored = ChatGeneratedImageRequest.fromJsonOrNull(oldPayload)

        assertEquals(
            ChatGeneratedImageRequest(
                id = "legacy-task",
                prompt = "a cat",
                status = ChatGeneratedImageStatus.DONE,
                imageAssetIds = listOf("asset-1")
            ),
            restored
        )
    }

    @Test
    fun malformedGeneratedImageJsonDoesNotBreakTheContainingMessage() {
        val legacy = ChatMessageEntity(
            sessionId = "old-session",
            position = 0,
            role = Role.ASSISTANT.name,
            content = "still readable",
            createdAt = 1L,
            tokenCount = null,
            generatedImageJson = "not-json"
        )

        val restored = legacy.toChatMessage()

        assertEquals("still readable", restored.content)
        assertNull(restored.generatedImageRequest)
    }

    @Test
    fun imageBatchPersistenceRequiresTheRetainedRequestToReferenceEveryAsset() {
        val request = ChatGeneratedImageRequest(
            id = "batch-task",
            prompt = "two small landscapes",
            status = ChatGeneratedImageStatus.DONE,
            imageAssetIds = listOf("asset-a", "asset-b")
        )
        val sessions = listOf(
            ChatSessionRecord(
                id = "session-1",
                title = "Images",
                messages = listOf(ChatMessage(Role.ASSISTANT, "", generatedImageRequest = request))
            )
        )

        assertTrue(
            GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                sessions,
                listOf("asset-a", "asset-b")
            )
        )
        assertFalse(
            GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                sessions,
                listOf("asset-a")
            )
        )
        assertFalse(
            GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                sessions,
                listOf("asset-a", "asset-b", "unreferenced")
            )
        )
        assertFalse(
            GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                sessions,
                listOf("asset-a", "asset-a")
            )
        )
        val truncatedSnapshot = ChatHistoryPersistenceBounds.bound(
            sessions,
            ChatHistoryPersistenceLimits(maxSessions = 1, maxMessages = 1, maxSerializedBytes = 1)
        )
        assertFalse(
            GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                truncatedSnapshot,
                listOf("asset-a", "asset-b")
            )
        )
    }
}

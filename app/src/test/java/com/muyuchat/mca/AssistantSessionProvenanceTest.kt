package com.muyuchat.mca

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSessionProvenanceTest {
    @Test
    fun capturedPersonaRestoresAnIncorrectLegacyBinding() {
        val captured = snapshot("role-a", "Prompt A")
        val legacy = session("role-b", captured)

        val resolved = AssistantSessionProvenance.resolve(legacy, legacy, listOf(reply("Prompt A")))

        assertEquals("role-a", resolved.session.assistantId)
        assertEquals(captured, resolved.session.assistantSnapshotJson)
        assertFalse(resolved.session.mixedAssistantHistory)
        assertFalse(resolved.preservePersistedMessages)
    }

    @Test
    fun conflictingPromptEvidenceQuarantinesOldTranscriptAndSummary() {
        val captured = snapshot("role-b", "Prompt B")
        val session = session("role-b", captured)

        val resolved = AssistantSessionProvenance.resolve(session, session, listOf(reply("Prompt A")))

        assertTrue(resolved.session.mixedAssistantHistory)
        assertEquals("old summary", resolved.session.contextSummariesJson)
        assertTrue(resolved.preservePersistedMessages)
        assertFalse(AssistantSessionProvenance.isTrustedOwner(resolved.session, "role-b"))
    }

    @Test
    fun mixedHistoryIgnoresAnIncomingEmptyTranscript() {
        val original = session("role-a", snapshot("role-a", "Prompt A"))
            .copy(mixedAssistantHistory = true, modelMode = "LOCAL", modelId = "model-a")
        val sameOwner = AssistantSessionProvenance.resolve(
            original, original.copy(contextSummariesJson = null), emptyList()
        )
        assertEquals("old summary", sameOwner.session.contextSummariesJson)
        assertTrue(sameOwner.preservePersistedMessages)

        val otherOwner = original.copy(
            assistantId = "role-b",
            assistantSnapshotJson = snapshot("role-b", "Prompt B"),
            contextSummariesJson = null,
            modelMode = "CLOUD",
            modelId = "model-b"
        )

        val resolved = AssistantSessionProvenance.resolve(original, otherOwner, emptyList())

        assertEquals("role-a", resolved.session.assistantId)
        assertEquals(original.assistantSnapshotJson, resolved.session.assistantSnapshotJson)
        assertEquals("old summary", resolved.session.contextSummariesJson)
        assertEquals("LOCAL", resolved.session.modelMode)
        assertEquals("model-a", resolved.session.modelId)
        assertTrue(resolved.session.mixedAssistantHistory)
        assertTrue(resolved.preservePersistedMessages)
    }

    @Test
    fun explicitlyEditedSameRoleAcceptsItsEarlierPromptVersion() {
        val priorHash = MessageDigest.getInstance("SHA-256")
            .digest("Prompt A".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val revised = AssistantConversationSnapshot(
            assistantId = "role-a", name = "Role A", systemPrompt = "Prompt A revised",
            memoryEnabled = true, webSearchEnabled = false,
            fileContextEnabled = true, capturedAt = 100L,
            priorSystemPromptHashes = listOf(priorHash)
        )
        val persisted = session("role-a", revised.toJsonString())

        val resolved = AssistantSessionProvenance.resolve(
            persisted, persisted,
            listOf(reply("Prompt A"), reply("Prompt A").copy(
                messageId = "greeting", createdAt = 1L, contextAssemblyTraceJson = null
            ))
        )

        assertFalse(resolved.session.mixedAssistantHistory)
        assertTrue(AssistantSessionProvenance.isTrustedOwner(resolved.session, "role-a"))
    }

    @Test
    fun laterPersonaCannotTakeOverExistingSessionId() {
        val original = session("role-a", snapshot("role-a", "Prompt A"))
            .copy(modelMode = "LOCAL", modelId = "model-a")
        val attempted = session("role-b", snapshot("role-b", "Prompt B"))
            .copy(contextSummariesJson = "foreign summary", modelMode = "CLOUD", modelId = "model-b")

        val resolved = AssistantSessionProvenance.resolve(original, attempted, listOf(reply("Prompt B")))

        assertEquals("role-a", resolved.session.assistantId)
        assertEquals(original.assistantSnapshotJson, resolved.session.assistantSnapshotJson)
        assertEquals("old summary", resolved.session.contextSummariesJson)
        assertEquals("LOCAL", resolved.session.modelMode)
        assertEquals("model-a", resolved.session.modelId)
        assertTrue(resolved.session.mixedAssistantHistory)
        assertTrue(resolved.preservePersistedMessages)
    }

    @Test
    fun oldRoleBindingWithoutCapturedCardNeedsManualReview() {
        val legacy = session("role-a", null)

        assertTrue(AssistantSessionProvenance.resolve(legacy, legacy, listOf(reply("Prompt A")))
            .session.mixedAssistantHistory)
        assertFalse(AssistantSessionProvenance.resolve(legacy, legacy, emptyList())
            .session.mixedAssistantHistory)
    }

    @Test
    fun lateSnapshotCannotClaimAnEarlierUntracedReply() {
        val lateSnapshot = AssistantConversationSnapshot(
            assistantId = "role-b", name = "Role B", systemPrompt = "Prompt B",
            memoryEnabled = true, webSearchEnabled = false,
            fileContextEnabled = true, capturedAt = 100L
        ).toJsonString()
        val earlierReply = reply("Prompt B").copy(createdAt = 2L, contextAssemblyTraceJson = null)
        val legacy = session("role-b", lateSnapshot)

        assertTrue(AssistantSessionProvenance.resolve(legacy, legacy, listOf(earlierReply))
            .session.mixedAssistantHistory)
        assertTrue(AssistantSessionProvenance.resolve(
            legacy, legacy, listOf(earlierReply.copy(contextAssemblyTraceJson = "{}"))
        ).session.mixedAssistantHistory)
        assertTrue(AssistantSessionProvenance.resolve(
            legacy, legacy, listOf(earlierReply.copy(role = "USER", createdAt = 2L))
        ).session.mixedAssistantHistory)
    }

    private fun session(assistantId: String, snapshotJson: String?) = ChatSessionEntity(
        id = "chat-1",
        title = "History",
        pinned = false,
        manualTitle = false,
        updatedAt = 1L,
        assistantId = assistantId,
        assistantSnapshotJson = snapshotJson,
        contextSummariesJson = "old summary"
    )

    private fun snapshot(assistantId: String, prompt: String): String =
        AssistantConversationSnapshot(
            assistantId = assistantId,
            name = assistantId,
            systemPrompt = prompt,
            memoryEnabled = true,
            webSearchEnabled = false,
            fileContextEnabled = true,
            capturedAt = 1L
        ).toJsonString()

    private fun reply(prompt: String): ChatMessageEntity {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return ChatMessageEntity(
            sessionId = "chat-1",
            position = 0,
            role = "ASSISTANT",
            content = "Reply",
            createdAt = 2L,
            tokenCount = null,
            contextAssemblyTraceJson = JSONObject().put("rolePromptHash", hash).toString(),
            messageId = "reply-1"
        )
    }
}

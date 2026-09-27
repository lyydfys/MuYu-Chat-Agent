package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.compressChatRequestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryRecordTest {
    @Test
    fun candidateProjectsWithoutActivatingAndCommitRetainsOriginals() {
        val messages = messages()
        val candidate = candidate(messages)

        assertFalse(candidate.active)
        assertFalse(candidate.appliesTo(messages, "persona"))
        assertSame(messages, candidate.project(messages, "persona"))
        assertTrue(candidate.projectCandidate(messages, "persona").size < messages.size)

        val records = activateContextSummaryCandidate(emptyList(), candidate, messages, "persona")
        assertEquals(1, records.size)
        assertTrue(records.single().active)
        assertTrue(records.single().appliesTo(messages, "persona"))
        assertEquals(3, messages.size)
        assertEquals("I prefer tea.", messages.first().content)
    }

    @Test
    fun editingAnySourceRevisionMessageInvalidatesProjectionAndCommit() {
        val messages = messages()
        val candidate = candidate(messages)
        val edited = messages.map { if (it.id == "latest") it.copy(content = "changed question") else it }

        assertSame(edited, candidate.projectCandidate(edited, "persona"))
        assertTrue(activateContextSummaryCandidate(emptyList(), candidate, edited, "persona").isEmpty())
        val active = activateContextSummaryCandidate(emptyList(), candidate, messages, "persona").single()
        assertFalse(active.appliesTo(edited, "persona"))
        assertFalse(active.appliesTo(messages.reversed(), "persona"))
        assertFalse(active.appliesTo(messages, "changed persona"))
    }

    @Test
    fun insertedHistoryInvalidatesRevisionWhileAppendedTurnsRemainValid() {
        val messages = messages()
        val candidate = candidate(messages)
        val inserted = messages.toMutableList().apply {
            add(1, ChatMessage(Role.USER, "A newly restored historical fact.", id = "inserted", createdAt = 2L))
        }
        val appended = messages + ChatMessage(Role.ASSISTANT, "New answer.", id = "answer", createdAt = 4L)

        assertSame(inserted, candidate.projectCandidate(inserted, "persona"))
        assertTrue(activateContextSummaryCandidate(emptyList(), candidate, inserted, "persona").isEmpty())
        val active = activateContextSummaryCandidate(emptyList(), candidate, appended, "persona").single()
        assertFalse(active.appliesTo(inserted, "persona"))
        assertTrue(active.appliesTo(appended, "persona"))
        assertEquals(appended.last(), active.project(appended, "persona").last())
    }

    @Test
    fun competingCandidateAndRevokeCannotActivateAgainstOldParentRevision() {
        val messages = messages()
        val first = candidate(messages)
        val competing = candidate(messages)
        val active = activateContextSummaryCandidate(emptyList(), first, messages, "persona")

        assertSame(active, activateContextSummaryCandidate(active, competing, messages, "persona"))
        val replacement = candidate(messages, active)
        val revoked = revokeContextSummaries(active)
        assertSame(revoked, activateContextSummaryCandidate(revoked, replacement, messages, "persona"))
        assertFalse(revoked.single().active)
        assertEquals(ContextSummaryRecordStatus.REVOKED, revoked.single().status)
        assertEquals(first.text, revoked.single().text)
    }

    @Test
    fun summaryJsonRoundTripPreservesSourceIndexAndCoverage() {
        val messages = messages()
        val candidate = candidate(messages).copy(
            source = "CLOUD_MODEL", summaryModelIdentity = "provider/model",
            summaryRuntimeIdentity = "provider-runtime", summaryDiagnostic = "bounded_selection"
        )
        val records = activateContextSummaryCandidate(emptyList(), candidate, messages, "persona")
        val decoded = contextSummariesFromJson(contextSummariesToJson(records))

        assertEquals(records, decoded)
        assertTrue(decoded.single().appliesTo(messages, "persona"))
        assertNotNull(decoded.single().structuredSummary.evidence.firstOrNull())
        assertEquals(listOf("first", "second"), decoded.single().sourceMessageIds)
        assertTrue(decoded.single().tokenEstimate > 0)
    }

    @Test
    fun duplicateIdsAndNewPinnedSourceRejectCandidate() {
        val messages = messages()
        val candidate = candidate(messages)
        val pinned = messages.map { if (it.id == "first") it.copy(pinned = true) else it }

        assertSame(pinned, candidate.projectCandidate(pinned, "persona"))
        assertTrue(activateContextSummaryCandidate(emptyList(), candidate, pinned, "persona").isEmpty())
        val duplicates = messages + messages.first()
        assertSame(duplicates, candidate.projectCandidate(duplicates, "persona"))
    }

    private fun messages() = listOf(
        ChatMessage(Role.USER, "I prefer tea.", id = "first", createdAt = 1L),
        ChatMessage(Role.ASSISTANT, "Meeting is on 2026-09-27.", id = "second", createdAt = 2L),
        ChatMessage(Role.USER, "latest question", id = "latest", createdAt = 3L)
    )

    private fun candidate(
        messages: List<ChatMessage>,
        previous: List<ContextSummaryRecord> = emptyList()
    ): ContextSummaryRecord {
        val result = compressChatRequestContext(
            ChatRequest(messages, GenerationParams(nCtx = 4_096)),
            ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1),
            ContextCompressionTrigger.MANUAL
        )
        return requireNotNull(contextSummaryCandidate(messages, result, previous, "persona", 100L))
    }
}

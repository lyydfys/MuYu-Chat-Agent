package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript compaction: only old observations are rewritten, and nothing is ever dropped.
 *
 * The "keeps every entry" case is the anti-stub canary: a compactor that "solves" the budget by
 * trimming the list makes it fail.
 */
class AgentToolResultCompactorTest {

    private fun assistant(text: String) = AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = text)

    private fun toolResult(key: String, payload: String) = AgentTranscriptEntry.ToolResult(
        invocationId = "inv-$key",
        callHash = "hash-$key",
        toolKey = key,
        result = AgentJsonValue.Obj(listOf("summary" to AgentJsonValue.Str(payload)))
    )

    private fun text(entry: AgentTranscriptEntry): String = when (entry) {
        is AgentTranscriptEntry.ToolResult -> renderValue(entry.result)
        is AgentTranscriptEntry.AssistantEnvelope -> entry.rawEnvelope
        is AgentTranscriptEntry.UserAnswer -> entry.text
        is AgentTranscriptEntry.UserInterjection -> entry.text
        is AgentTranscriptEntry.ProtocolError -> entry.errorCode
    }

    @Test
    fun `an oversized old observation keeps its head and tail`() {
        val payload = "HEAD" + "x".repeat(5_000) + "TAIL"
        val entries = listOf(
            toolResult("read_file", payload),
            assistant("done"),
            assistant("done2"),
            assistant("done3"),
            assistant("done4")
        )

        val compacted = compactTranscript(entries, recentEntries = 4)

        val first = compacted.first() as AgentTranscriptEntry.ToolResult
        val rendered = renderValue(first.result)
        assertTrue("the head must survive", rendered.contains("HEAD"))
        assertTrue("the tail must survive", rendered.contains("TAIL"))
        assertTrue("the cut is marked", rendered.contains(AGENT_COMPACT_MARKER))
        assertTrue("the original size is reported", rendered.contains("共 "))
        assertTrue(
            "the compacted form must be much smaller than the original",
            rendered.length < payload.length / 4
        )
    }

    @Test
    fun `non tool entries are never rewritten`() {
        val answer = AgentTranscriptEntry.UserAnswer(questionId = "q1", text = "y".repeat(3_000))
        val interjection = AgentTranscriptEntry.UserInterjection(text = "z".repeat(3_000), receivedAtMillis = 7L)
        val envelope = AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = "e".repeat(3_000))
        val error = AgentTranscriptEntry.ProtocolError(errorCode = "AGENT_PROTOCOL", schemaHintJson = "{}")
        val entries = listOf(error, envelope, answer, interjection, toolResult("big", "w".repeat(9_000)))

        val compacted = compactTranscript(entries, maxBytes = 1_024, recentEntries = 0)

        assertSame("the answer is a user statement, not an observation", answer, compacted[2])
        assertSame(interjection, compacted[3])
        assertSame("the envelope must stay parseable protocol text", envelope, compacted[1])
        assertSame(error, compacted[0])
    }

    @Test
    fun `the entry count never changes`() {
        val entries = (0 until 12).map { index ->
            if (index % 3 == 0) assistant("step $index") else toolResult("tool$index", "p".repeat(3_000))
        }

        val compacted = compactTranscript(entries, maxBytes = 2_048, recentEntries = 2)

        assertEquals("compaction must never drop a step", entries.size, compacted.size)
    }

    @Test
    fun `the total budget is honoured by compacting the oldest first`() {
        val entries = (0 until 20).map { index -> toolResult("tool$index", "q".repeat(4_000)) }
        val budget = 8 * 1024

        val compacted = compactTranscript(entries, maxBytes = budget, recentEntries = 0)

        val total = compacted.sumOf { entryBytes(it) }
        assertTrue("total must fit the budget, was $total", total <= budget)
        assertEquals(entries.size, compacted.size)
        // The newest entry is the last one compacted, so it keeps more text than the first.
        assertTrue(
            "oldest observations give up the most",
            text(compacted.first()).length <= text(compacted.last()).length
        )
    }

    @Test
    fun `an already small transcript is returned unchanged`() {
        val entries = listOf(toolResult("small", "ok"), assistant("done"))

        val compacted = compactTranscript(entries, maxBytes = 32 * 1024, recentEntries = 4)

        assertSame("nothing to do means the same list, not a copy", entries, compacted)
    }

    @Test
    fun `an empty transcript is safe`() {
        assertEquals(emptyList<AgentTranscriptEntry>(), compactTranscript(emptyList()))
    }

    @Test
    fun `the recent window is never touched`() {
        val recent = toolResult("recent", "r".repeat(9_000))
        val entries = listOf(toolResult("old", "o".repeat(9_000)), recent)

        val compacted = compactTranscript(entries, maxBytes = 1_024, recentEntries = 1)

        assertSame("the newest observation is the model's working set", recent, compacted.last())
        assertFalse(
            "the older one was compacted",
            renderValue((compacted.first() as AgentTranscriptEntry.ToolResult).result)
                .contains("o".repeat(500))
        )
    }

    @Test
    fun `artifact ids survive compaction`() {
        val entry = AgentTranscriptEntry.ToolResult(
            invocationId = "inv-write",
            callHash = "hash-write",
            toolKey = "write_file",
            result = AgentJsonValue.Obj(
                listOf(
                    "summary" to AgentJsonValue.Str("w".repeat(6_000)),
                    "artifactIds" to AgentJsonValue.Arr(
                        listOf(AgentJsonValue.Str("art-1"), AgentJsonValue.Str("art-2"))
                    )
                )
            )
        )

        val compacted = compactTranscript(listOf(entry), recentEntries = 0).single()

        val fields = ((compacted as AgentTranscriptEntry.ToolResult).result as AgentJsonValue.Obj).fields.toMap()
        val ids = (fields["artifactIds"] as AgentJsonValue.Arr).items
            .mapNotNull { (it as? AgentJsonValue.Str)?.value }
        assertEquals(listOf("art-1", "art-2"), ids)
        assertTrue("the excerpt still names the original size", renderValue(compacted.result).contains("共 "))
    }

    @Test
    fun `compaction is idempotent`() {
        val entries = (0 until 6).map { index -> toolResult("tool$index", "s".repeat(5_000)) }

        val once = compactTranscript(entries, maxBytes = 4 * 1024, recentEntries = 1)
        val twice = compactTranscript(once, maxBytes = 4 * 1024, recentEntries = 1)

        assertEquals(once, twice)
    }

    @Test
    fun `entry identity fields are preserved`() {
        val original = AgentTranscriptEntry.ToolResult(
            invocationId = "inv-keep",
            callHash = "hash-keep",
            toolKey = "grep",
            result = AgentJsonValue.Str("g".repeat(7_000)),
            providerCallId = "call-1"
        )

        val compacted = compactTranscript(listOf(original), recentEntries = 0).single()
            as AgentTranscriptEntry.ToolResult

        assertEquals("inv-keep", compacted.invocationId)
        assertEquals("hash-keep", compacted.callHash)
        assertEquals("grep", compacted.toolKey)
        assertEquals("call-1", compacted.providerCallId)
    }

    @Test
    fun `only tool results count as compactable`() {
        assertTrue(isCompactableEntry(toolResult("x", "y")))
        assertFalse(isCompactableEntry(assistant("a")))
        assertFalse(isCompactableEntry(AgentTranscriptEntry.UserAnswer("q", "a")))
        assertFalse(isCompactableEntry(AgentTranscriptEntry.UserInterjection("t", 0L)))
        assertFalse(isCompactableEntry(AgentTranscriptEntry.ProtocolError("c", "{}")))
    }
}

package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for collapsing repeated tool observations.
 *
 * The loop replays the transcript on every model call, so the two things that must never break are
 * **positional integrity** (a pointer still occupies its slot and stays paired with its envelope)
 * and **the first copy's fidelity** (the model must still see the real document once).
 */
class AgentToolResultDeduperTest {

    @Test
    fun `a repeated result is replaced by a pointer to the first occurrence`() {
        val body = largeResult("notes.md", chars = 400)
        val entries = listOf(
            assistantEnvelope("read notes"),
            toolResult(invocationId = "inv-1", result = body),
            assistantEnvelope("read notes again"),
            toolResult(invocationId = "inv-2", result = body)
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertEquals(entries.size, deduped.size)
        // First copy untouched: the model still gets the document exactly once.
        assertSame(entries[1], deduped[1])
        val pointer = deduped[3] as AgentTranscriptEntry.ToolResult
        assertTrue(AgentToolResultDeduper.isPointer(pointer.result))
        val fields = (pointer.result as AgentJsonValue.Obj).fields.toMap()
        assertEquals(AgentJsonValue.Bool(true), fields[AgentToolResultDeduper.FIELD_DEDUPLICATED])
        assertEquals(AgentJsonValue.Num("1"), fields[AgentToolResultDeduper.FIELD_DUPLICATE_OF_STEP])
        val omitted = (fields[AgentToolResultDeduper.FIELD_OMITTED_CHARS] as AgentJsonValue.Num).literal.toInt()
        assertTrue("omitted must be the real payload size", omitted > 300)
        val message = (fields[AgentToolResultDeduper.FIELD_MESSAGE] as AgentJsonValue.Str).value
        assertEquals(AgentToolResultDeduper.pointerMessage(1, omitted), message)
        assertTrue(message.contains("与第 1 步的结果相同"))
    }

    @Test
    fun `the pointer keeps machine-readable identity but drops the payload`() {
        val result = AgentJsonValues.obj(
            "ok" to AgentJsonValue.Bool(true),
            "status" to AgentJsonValue.Str("SUCCEEDED"),
            "summary" to AgentJsonValue.Str("读取 notes.md"),
            "data" to AgentJsonValues.obj("content" to AgentJsonValue.Str("x".repeat(500)))
        )
        val entries = listOf(
            toolResult(invocationId = "inv-1", callHash = "hash-a", result = result),
            toolResult(invocationId = "inv-2", callHash = "hash-b", result = result)
        )

        val second = AgentToolResultDeduper.dedupe(entries)[1] as AgentTranscriptEntry.ToolResult

        // Entry-level identity is what a native provider pairs on; it must survive verbatim.
        assertEquals("inv-2", second.invocationId)
        assertEquals("hash-b", second.callHash)
        assertEquals("workspace.read_text", second.toolKey)
        assertEquals("call-2", second.providerCallId)
        val fields = (second.result as AgentJsonValue.Obj).fields.toMap()
        assertFalse("bulky data must be gone", fields.containsKey("data"))
        assertFalse("prose summary is payload too", fields.containsKey("summary"))
        assertEquals(AgentJsonValue.Bool(true), fields["ok"])
        assertEquals(AgentJsonValue.Str("SUCCEEDED"), fields["status"])
    }

    @Test
    fun `artifact ids survive on the pointer`() {
        val result = AgentJsonValues.obj(
            "ok" to AgentJsonValue.Bool(true),
            "artifactIds" to AgentJsonValues.arr(
                AgentJsonValue.Str("artifact-1"),
                AgentJsonValue.Str("artifact-2")
            ),
            "data" to AgentJsonValues.obj("content" to AgentJsonValue.Str("y".repeat(400)))
        )
        val entries = listOf(
            toolResult(invocationId = "inv-1", result = result),
            toolResult(invocationId = "inv-2", result = result)
        )

        val pointer = AgentToolResultDeduper.dedupe(entries)[1] as AgentTranscriptEntry.ToolResult
        val artifacts = (pointer.result as AgentJsonValue.Obj).fields.toMap()["artifactIds"] as AgentJsonValue.Arr

        assertEquals(
            listOf(AgentJsonValue.Str("artifact-1"), AgentJsonValue.Str("artifact-2")),
            artifacts.items
        )
    }

    @Test
    fun `different results from the same tool are left alone`() {
        val entries = listOf(
            toolResult(invocationId = "inv-1", result = AgentJsonValues.obj("n" to AgentJsonValue.Num("1"))),
            toolResult(invocationId = "inv-2", result = AgentJsonValues.obj("n" to AgentJsonValue.Num("2")))
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertSame(entries[0], deduped[0])
        assertSame(entries[1], deduped[1])
    }

    @Test
    fun `identical bytes from different tools are not duplicates of each other`() {
        val shared = AgentJsonValues.obj("value" to AgentJsonValue.Str("same bytes"))
        val entries = listOf(
            toolResult(invocationId = "inv-1", toolKey = "workspace.read_text", result = shared),
            toolResult(invocationId = "inv-2", toolKey = "artifact.read_text", result = shared)
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertSame(entries[1], deduped[1])
        assertFalse(AgentToolResultDeduper.isPointer((deduped[1] as AgentTranscriptEntry.ToolResult).result))
    }

    @Test
    fun `the entry count and every non-tool entry are preserved`() {
        val body = AgentJsonValues.obj("data" to AgentJsonValue.Str("z".repeat(200)))
        val entries = listOf(
            assistantEnvelope("first"),
            toolResult(invocationId = "inv-1", result = body),
            AgentTranscriptEntry.UserAnswer(questionId = "q-1", text = "继续"),
            AgentTranscriptEntry.ProtocolError(errorCode = "PROTOCOL_ERROR", schemaHintJson = "{}"),
            toolResult(invocationId = "inv-2", result = body),
            assistantEnvelope("last")
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertEquals(entries.size, deduped.size)
        assertEquals(entries.indices.map { entries[it]::class }, deduped.indices.map { deduped[it]::class })
        assertSame(entries[0], deduped[0])
        assertSame(entries[2], deduped[2])
        assertSame(entries[3], deduped[3])
        assertSame(entries[5], deduped[5])
    }

    @Test
    fun `pointers report the ordinal of the first tool result, not its list index`() {
        val body = AgentJsonValues.obj("data" to AgentJsonValue.Str("w".repeat(100)))
        val entries = listOf(
            assistantEnvelope("a"),
            assistantEnvelope("b"),
            toolResult(invocationId = "inv-1", result = body),
            assistantEnvelope("c"),
            toolResult(invocationId = "inv-2", result = body)
        )

        val pointer = AgentToolResultDeduper.dedupe(entries)[4] as AgentTranscriptEntry.ToolResult
        val ordinal =
            ((pointer.result as AgentJsonValue.Obj).fields.toMap()
                [AgentToolResultDeduper.FIELD_DUPLICATE_OF_STEP] as AgentJsonValue.Num).literal

        assertEquals("1", ordinal)
    }

    @Test
    fun `the third and later copies all point at the same first occurrence`() {
        val body = AgentJsonValues.obj("data" to AgentJsonValue.Str("q".repeat(120)))
        val entries = listOf(
            toolResult(invocationId = "inv-1", result = body),
            toolResult(invocationId = "inv-2", result = body),
            toolResult(invocationId = "inv-3", result = body)
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertSame(entries[0], deduped[0])
        val second = ((deduped[1] as AgentTranscriptEntry.ToolResult).result as AgentJsonValue.Obj)
            .fields.toMap()
        val third = ((deduped[2] as AgentTranscriptEntry.ToolResult).result as AgentJsonValue.Obj)
            .fields.toMap()
        assertEquals("1", (second[AgentToolResultDeduper.FIELD_DUPLICATE_OF_STEP] as AgentJsonValue.Num).literal)
        assertEquals("1", (third[AgentToolResultDeduper.FIELD_DUPLICATE_OF_STEP] as AgentJsonValue.Num).literal)
        assertEquals(second[AgentToolResultDeduper.FIELD_OMITTED_CHARS], third[AgentToolResultDeduper.FIELD_OMITTED_CHARS])
    }

    @Test
    fun `an empty or single-entry transcript is returned unchanged`() {
        val empty = emptyList<AgentTranscriptEntry>()
        assertSame(empty, AgentToolResultDeduper.dedupe(empty))

        val single = listOf(toolResult(invocationId = "inv-1", result = AgentJsonValues.obj()))
        assertSame(single, AgentToolResultDeduper.dedupe(single))
    }

    @Test
    fun `running twice produces the same transcript`() {
        val body = AgentJsonValues.obj("data" to AgentJsonValue.Str("r".repeat(300)))
        val entries = listOf(
            assistantEnvelope("a"),
            toolResult(invocationId = "inv-1", result = body),
            toolResult(invocationId = "inv-2", result = body),
            toolResult(invocationId = "inv-3", result = body)
        )

        val once = AgentToolResultDeduper.dedupe(entries)
        val twice = AgentToolResultDeduper.dedupe(once)

        assertEquals(once, twice)
        assertSame(once, twice)
        // The second pass must not treat the pointer text as a new duplication source.
        assertEquals(
            1,
            once.count { entry ->
                entry is AgentTranscriptEntry.ToolResult && !AgentToolResultDeduper.isPointer(entry.result)
            }
        )
    }

    @Test
    fun `a result that cannot be canonicalised is passed through untouched`() {
        val broken = AgentJsonValues.obj(
            "value" to AgentJsonValues.obj(
                "dup" to AgentJsonValue.Str("a"),
                "dup" to AgentJsonValue.Str("b")
            )
        )
        val entries = listOf(
            toolResult(invocationId = "inv-1", result = broken),
            toolResult(invocationId = "inv-2", result = broken)
        )

        val deduped = AgentToolResultDeduper.dedupe(entries)

        assertSame(entries[0], deduped[0])
        assertSame(entries[1], deduped[1])
    }

    @Test
    fun `the pointer is much smaller than the payload it replaces`() {
        val body = largeResult("big.md", chars = 2_000)
        val entries = listOf(
            toolResult(invocationId = "inv-1", result = body),
            toolResult(invocationId = "inv-2", result = body)
        )

        val pointer = (AgentToolResultDeduper.dedupe(entries)[1] as AgentTranscriptEntry.ToolResult).result
        val shape = (pointer as AgentJsonValue.Obj).fields.toMap()

        assertTrue("pointer carries only metadata", shape.size <= 8)
        assertNotEquals(body, pointer)
    }

    // ---------------------------------------------------------------- fixtures

    private fun largeResult(fileName: String, chars: Int): AgentJsonValue.Obj = AgentJsonValues.obj(
        "ok" to AgentJsonValue.Bool(true),
        "status" to AgentJsonValue.Str("SUCCEEDED"),
        "summary" to AgentJsonValue.Str("已读取 $fileName"),
        "data" to AgentJsonValues.obj(
            "path" to AgentJsonValue.Str("inputs/$fileName"),
            "content" to AgentJsonValue.Str("内".repeat(chars))
        )
    )

    private fun assistantEnvelope(text: String): AgentTranscriptEntry =
        AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = text)

    private fun toolResult(
        invocationId: String,
        result: AgentJsonValue,
        toolKey: String = "workspace.read_text",
        callHash: String = "call-hash",
        providerCallId: String? = null
    ): AgentTranscriptEntry.ToolResult = AgentTranscriptEntry.ToolResult(
        invocationId = invocationId,
        callHash = callHash,
        toolKey = toolKey,
        result = result,
        providerCallId = providerCallId ?: "call-${invocationId.substringAfterLast('-')}"
    )
}

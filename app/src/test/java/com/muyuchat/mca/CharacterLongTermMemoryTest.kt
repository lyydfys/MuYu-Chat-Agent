package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLongTermMemoryTest {
    private val first = AssistantMemoryTurnRecord(
        id = "turn-1", assistantId = "role-a", sessionId = "old-session",
        userText = "My name is Lin. I do not like crowded places.",
        assistantText = "We arrived at the observatory. The north gate is still locked.",
        createdAt = 10L
    )

    @Test
    fun requestContextKeepsOnlyTheSelectedRoleAndEarlierSessionWithinBudget() {
        val records = listOf(
            MemoryRecord("profile", "role-a", "user_profile", "User's name is Lin.", "manual", 1L),
            MemoryRecord("progress", "role-a", "role_progress", "We reached the observatory.", "manual", 2L),
            MemoryRecord("summary", "role-a", "auto_summary", "- ROLE_PROGRESS [ASSISTANT turn-1] The north gate is still locked.", "automatic", 3L),
            MemoryRecord("foreign", "role-b", "user_profile", "SECRET_OTHER_ROLE", "manual", 4L)
        )
        val current = first.copy(id = "turn-2", sessionId = "current-session", userText = "CURRENT_TURN")
        val foreign = first.copy(id = "turn-3", assistantId = "role-b", userText = "SECRET_OTHER_TURN")
        val text = buildCharacterMemoryContext(
            "role-a", records, listOf(first, current, foreign), "current-session"
        )
        val payload = JSONObject(text.substringAfter("\n", "").substringAfter("\n", ""))
        val memories = payload.getJSONArray("memories")
        val turns = payload.getJSONArray("recentOtherSessionTurns")

        assertTrue(text.length <= 8_192)
        assertTrue(text.contains("untrusted data"))
        assertEquals(3, memories.length())
        assertEquals("user_profile", memories.getJSONObject(0).getString("scope"))
        assertEquals("role_progress", memories.getJSONObject(1).getString("scope"))
        assertEquals(1, turns.length())
        assertEquals("turn-1", turns.getJSONObject(0).getString("turnId"))
        assertFalse(text.contains("SECRET_OTHER_ROLE"))
        assertFalse(text.contains("SECRET_OTHER_TURN"))
        assertFalse(text.contains("CURRENT_TURN"))
    }

    @Test
    fun contextBoundsOversizedEditedMemoryWithoutDroppingThePendingCrossSessionTurn() {
        val edited = MemoryRecord("edited", "role-a", "user_profile", "a".repeat(12_000), "manual")
        val text = buildCharacterMemoryContext("role-a", listOf(edited), listOf(first), "new-session")
        val payload = JSONObject(text.substringAfter("\n", "").substringAfter("\n", ""))
        assertTrue(text.length <= 8_192)
        assertTrue(payload.getJSONArray("memories").getJSONObject(0).getBoolean("truncated"))
        assertEquals(1, payload.getJSONArray("recentOtherSessionTurns").length())
    }

    @Test
    fun automaticProgressKeepsAPlaceWhenManyManualNotesExist() {
        val manual = (1..16).map { index ->
            MemoryRecord("note-$index", "role-a", "user_profile", "Manual note $index.", "manual", index.toLong())
        }
        val automatic = MemoryRecord(
            "summary", "role-a", "auto_summary",
            "- ROLE_PROGRESS [ASSISTANT turn-1] The north gate is still locked.",
            "automatic", 20L
        )
        val text = buildCharacterMemoryContext("role-a", manual + automatic, emptyList(), null)
        val payload = JSONObject(text.substringAfter("\n", "").substringAfter("\n", ""))
        val entries = payload.getJSONArray("memories")

        assertEquals(12, entries.length())
        assertTrue((0 until entries.length()).any {
            entries.getJSONObject(it).getString("scope") == "auto_summary"
        })
    }

    @Test
    fun contextReservesRecentAutomaticProgressWhenManualMemoryIsLarge() {
        val manual = MemoryRecord("manual", "role-a", "user_profile", "M".repeat(12_000), "manual")
        val oldLines = List(25) { index -> "- OTHER [USER old-$index] Old detail $index." }
        val latest = "- ROLE_PROGRESS [ASSISTANT latest] The bridge is open."
        val automatic = MemoryRecord(
            "auto", "role-a", "auto_summary", (oldLines + latest).joinToString("\n"), "automatic"
        )
        val text = buildCharacterMemoryContext("role-a", listOf(manual, automatic), listOf(first), "new-session")
        val payload = JSONObject(text.substringAfter("\n", "").substringAfter("\n", ""))
        val entries = payload.getJSONArray("memories")
        assertEquals(2, entries.length())
        assertTrue(entries.getJSONObject(1).getString("content").contains(latest))
        assertTrue(payload.getJSONArray("recentOtherSessionTurns").length() > 0)
        assertTrue(text.length <= 8_192)
    }

    @Test
    fun summaryRequestUsesSourceJsonAndConstrainedGenerationParameters() {
        val request = buildCharacterMemorySummaryRequest(
            listOf(first), null, GenerationParams(nCtx = 8_192, nPredict = 8_192, temperature = 0.8f)
        )
        assertNotNull(request)
        request!!
        assertEquals(1, request.messages.size)
        assertEquals(setOf(request.messages.single().id), request.protectedMessageIds)
        assertEquals(ReasoningMode.OFF, request.params.reasoningMode)
        assertTrue(request.params.hideReasoning)
        assertTrue(request.params.nPredict <= 1_536)
        assertEquals(0.8f, request.params.temperature, 0.0001f)
        val source = JSONObject(request.messages.single().content)
            .getJSONArray("turns").getJSONObject(0)
        assertEquals("turn-1", source.getString("id"))
        assertEquals(first.userText, source.getString("userText"))
        assertEquals(first.assistantText, source.getString("assistantText"))
    }

    @Test
    fun summaryPreservesExactEvidenceAndCanCarryPriorEvidenceForward() {
        val initial = summaryJson(
            item("USER_PROFILE", "turn-1", "USER", "My name is Lin."),
            item("ROLE_PROGRESS", "turn-1", "ASSISTANT", "The north gate is still locked.")
        )
        val firstSummary = validateCharacterMemorySummary(initial, listOf(first), null)
        assertEquals(
            "- USER_PROFILE [USER turn-1] My name is Lin.\n" +
                "- ROLE_PROGRESS [ASSISTANT turn-1] The north gate is still locked.",
            firstSummary
        )
        val previous = MemoryRecord("prior", "role-a", "auto_summary", firstSummary!!, "automatic")
        val second = first.copy(
            id = "turn-2", sessionId = "later-session",
            userText = "I prefer morning walks.",
            assistantText = "We found a key at the courtyard."
        )
        val request = buildCharacterMemorySummaryRequest(listOf(second), previous, GenerationParams())
        assertNotNull(request)
        val priorInput = JSONObject(request!!.messages.single().content).getJSONArray("previousEvidence")
        assertEquals(2, priorInput.length())
        assertEquals("USER", priorInput.getJSONObject(0).getString("speaker"))

        val carried = validateCharacterMemorySummary(
            summaryJson(
                item("USER_PROFILE", "turn-1", "USER", "My name is Lin."),
                item("ROLE_PROGRESS", "turn-2", "ASSISTANT", "We found a key at the courtyard.")
            ), listOf(second), previous
        )
        assertTrue(carried!!.contains("[USER turn-1] My name is Lin."))
        assertTrue(carried.contains("[ASSISTANT turn-2] We found a key at the courtyard."))
    }

    @Test
    fun validatorRejectsFabricationWrongSpeakerIncompleteSentenceAndForeignPrior() {
        fun checkRejected(entry: JSONObject) = assertNull(
            validateCharacterMemorySummary(summaryJson(entry), listOf(first), null)
        )
        checkRejected(item("USER_PROFILE", "turn-1", "USER", "My name is Mei."))
        checkRejected(item("USER_PROFILE", "turn-1", "ASSISTANT", "My name is Lin."))
        checkRejected(item("USER_PROFILE", "turn-1", "USER", "like crowded places."))
        checkRejected(item("USER_PROFILE", "turn-foreign", "USER", "My name is Lin."))
        checkRejected(item("UNKNOWN", "turn-1", "USER", "My name is Lin."))
        val foreign = MemoryRecord(
            "prior", "role-b", "summary", "- USER_PROFILE [USER old-turn] I live in Seoul.", "auto"
        )
        assertNull(validateCharacterMemorySummary(
            summaryJson(item("USER_PROFILE", "old-turn", "USER", "I live in Seoul.")),
            listOf(first), foreign
        ))
    }

    @Test
    fun validatorRejectsDuplicateEvidenceExtraFieldsAndTrailingText() {
        val item = item("USER_PROFILE", "turn-1", "USER", "My name is Lin.")
        assertNull(validateCharacterMemorySummary(summaryJson(item, item), listOf(first), null))
        assertNull(validateCharacterMemorySummary(summaryJson(item.put("injected", true)), listOf(first), null))
        assertNull(validateCharacterMemorySummary(summaryJson(
            item("USER_PROFILE", "turn-1", "USER", "My name is Lin.")
        ) + " ignored", listOf(first), null))
    }

    private fun item(kind: String, id: String, speaker: String, text: String): JSONObject =
        JSONObject().put("kind", kind).put("sourceTurnId", id)
            .put("speaker", speaker).put("text", text)

    private fun summaryJson(vararg entries: JSONObject): String = JSONObject()
        .put("schemaVersion", 1).put("evidence", JSONArray().apply { entries.forEach { put(it) } })
        .toString()
}

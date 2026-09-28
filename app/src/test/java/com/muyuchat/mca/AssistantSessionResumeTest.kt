package com.muyuchat.mca

import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantSessionResumeTest {
    @Test
    fun returningToRoleChoosesItsLatestConversationInsteadOfThePinnedConversation() {
        val older = session("old", "role-a", 10L, pinned = true)
        val newer = session("new", "role-a", 20L)
        val otherRole = session("foreign", "role-b", 30L)

        assertEquals("new", listOf(older, otherRole, newer).latestSessionForAssistant("role-a")?.id)
        assertEquals("foreign", listOf(older, otherRole, newer).latestSessionForAssistant("role-b")?.id)
        assertNull(listOf(older, otherRole, newer).latestSessionForAssistant("missing"))
    }

    @Test
    fun conflictingRoleIdentityDoesNotBecomeARoleResumeCandidate() {
        val captured = session("captured", "legacy-role", 20L).copy(
            assistantSnapshot = AssistantConversationSnapshot(
                assistantId = "role-a", name = "Role A", systemPrompt = "Persona",
                memoryEnabled = true, webSearchEnabled = false,
                fileContextEnabled = true, capturedAt = 1L
            )
        )

        assertNull(listOf(captured).latestSessionForAssistant("role-a"))
        assertNull(listOf(captured).latestSessionForAssistant("legacy-role"))
    }

    @Test
    fun importedGreetingStartsARealSessionAndRestoresWithoutAddingAnotherMessage() {
        val card = CharacterCardCodec.parseJson(
            """{"spec":"chara_card_v2","data":{"name":"Guide","description":"Stay in character.","first_mes":"Welcome back."}}"""
        ) as CharacterCardParseResult.Success
        val imported = card.card.toAssistantRecord().copy(id = "guide")
        val assistant = AssistantRecord.fromJson(imported.toJson())

        val session = requireNotNull(assistant.initialGreetingSession(
            modelMode = "local", modelId = "gguf-model", sessionId = "greeting-chat", capturedAt = 42L
        ))

        assertEquals(assistant.systemPrompt, session.assistantSnapshot?.systemPrompt)
        assertEquals("gguf-model", session.modelId)
        assertEquals("guide", session.assistantSnapshot?.assistantId)
        assertEquals(1, session.messages.size)
        assertEquals(Role.ASSISTANT, session.messages.single().role)
        assertEquals("Welcome back.", session.messages.single().content)
        assertEquals(session.messages, listOf(session).latestSessionForAssistant("guide")?.messages)
    }

    @Test
    fun greetingIsOnlyCreatedForAnImportedCardWithAFirstMessage() {
        assertNull(AssistantRecord(id = "manual", systemPrompt = "Prompt").initialGreetingSession(null, null))
        val card = CharacterCardCodec.parseJson(
            """{"spec":"chara_card_v2","data":{"name":"Silent","description":"Prompt"}}"""
        ) as CharacterCardParseResult.Success
        assertNull(card.card.toAssistantRecord().initialGreetingSession(null, null))
    }

    @Test
    fun roleWithoutGreetingGetsOwnedEmptyConversation() {
        val silent = AssistantRecord(id = "silent", systemPrompt = "Stay in character")
        val session = silent.newConversationSession("local", "model", "empty-chat", 42L)

        assertEquals("empty-chat", session.id)
        assertEquals(emptyList<com.muyuchat.core.engine.ChatMessage>(), session.messages)
        assertEquals("silent", session.assistantId)
        assertEquals("silent", session.assistantSnapshot?.assistantId)
        assertEquals("empty-chat", listOf(session).latestSessionForAssistant("silent")?.id)
    }

    @Test
    fun mixedConversationIsNotResumedAutomatically() {
        val mixed = session("mixed", "role-a", 30L).copy(mixedAssistantHistory = true)
        val safe = session("safe", "role-a", 10L)
        assertEquals("safe", listOf(mixed, safe).latestSessionForAssistant("role-a")?.id)
    }

    @Test
    fun legacyImportedPersonaPromptIsNotRewrittenWhenItsGreetingIsRecovered() {
        val rawCard = """{"spec":"chara_card_v2","data":{"name":"Guide","first_mes":"Hello."}}"""
        val existing = AssistantRecord(
            id = "existing", systemPrompt = "Stored persona\n\n开场白：\nHello.",
            characterCardJson = rawCard
        )

        val restored = AssistantRecord.fromJson(existing.toJson())

        assertEquals(existing.systemPrompt, restored.systemPrompt)
        assertEquals("Hello.", restored.initialGreetingMessage()?.content)
    }

    private fun session(id: String, assistantId: String, updatedAt: Long, pinned: Boolean = false) =
        ChatSessionRecord(
            id = id, title = id, messages = emptyList(), pinned = pinned,
            updatedAt = updatedAt, assistantId = assistantId,
            assistantSnapshot = AssistantConversationSnapshot(
                assistantId = assistantId, name = assistantId, systemPrompt = "Persona",
                memoryEnabled = false, webSearchEnabled = false,
                fileContextEnabled = true, capturedAt = updatedAt
            )
        )
}

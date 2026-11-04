package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.CHAT_MESSAGE_EDIT_MAX_CHARS
import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.mca.composition.conversationRegenerationWriter
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEditOperationsTest {

    private val assistant = AssistantRecord(name = "角色")
    private val firstUser = ChatMessage(Role.USER, "第一问")
    private val firstAnswer = ChatMessage(Role.ASSISTANT, "第一答")
    private val targetUser = ChatMessage(Role.USER, "第二问")
    private val staleAnswer = ChatMessage(Role.ASSISTANT, "将被作废的答")
    private val messages = listOf(firstUser, firstAnswer, targetUser, staleAnswer)

    private fun state(
        messages: List<ChatMessage> = this.messages,
        isGenerating: Boolean = false
    ): MainUiState {
        val session = ChatSessionRecord(
            id = SESSION,
            title = "对话",
            messages = messages,
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(1L)
        )
        return MainUiState(
            messages = messages,
            chatSessions = listOf(session),
            activeChatSessionId = SESSION,
            assistants = listOf(assistant),
            selectedAssistantId = assistant.id,
            isGenerating = isGenerating
        )
    }

    private fun target(messageId: String = targetUser.id) =
        ChatMessageIdentity(conversationId = SESSION, messageId = messageId)

    @Test
    fun `plan retires the target and every later message`() {
        val plan = requireNotNull(planConversationEdit(messages, 2, "改过的第二问", occurredAt = 9L))

        assertEquals(listOf("第一问", "第一答", "改过的第二问"), plan.requestMessages.map { it.content })
        assertEquals(Role.USER, plan.requestMessages.last().role)
        assertEquals(4, plan.persistedMessages.size)
        assertEquals("", plan.persistedMessages.last().content)
        assertEquals(Role.ASSISTANT, plan.persistedMessages.last().role)
        assertTrue("later assistant answer must not survive", plan.persistedMessages.none { it.id == staleAnswer.id })
    }

    @Test
    fun `plan keeps the earlier history untouched`() {
        val plan = requireNotNull(planConversationEdit(messages, 2, "新文本", occurredAt = 9L))
        assertEquals(listOf(firstUser.id, firstAnswer.id), plan.requestMessages.take(2).map { it.id })
    }

    @Test
    fun `plan preserves the edited message's own attachments`() {
        val attachment = ChatImageAttachment(name = "a.png", uriString = "content://a")
        val withImage = listOf(
            ChatMessage(Role.USER, "图", imageAttachments = listOf(attachment)),
            ChatMessage(Role.ASSISTANT, "答")
        )
        val plan = requireNotNull(planConversationEdit(withImage, 0, "", occurredAt = 9L))
        assertEquals(listOf(attachment), plan.requestMessages.single().imageAttachments)
        assertTrue(plan.requestMessages.single().content.isEmpty())
    }

    @Test
    fun `plan rejects assistant targets blank text and over-limit text`() {
        assertNull(planConversationEdit(messages, 1, "改", occurredAt = 9L))
        assertNull(planConversationEdit(messages, 2, "   ", occurredAt = 9L))
        assertNull(
            planConversationEdit(
                messages,
                2,
                "x".repeat(CHAT_MESSAGE_EDIT_MAX_CHARS + 1),
                occurredAt = 9L
            )
        )
    }

    @Test
    fun `edit persists before generation starts with the truncated request`() {
        val store = UiStateStore(state())
        val events = mutableListOf<String>()
        var requested: List<ChatMessage>? = null
        var persistedSessions: List<ChatSessionRecord>? = null
        val reservation = UiGenerationReservation(runId = 7L, supersededOwner = null)
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target(),
            editedText = "改过的第二问",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = { events += "discard" },
            reserveGeneration = { events += "reserve"; reservation },
            cancelPendingReservation = { events += "cancel" },
            persistMutation = { sessions, _, onCommitted, _ ->
                events += "persist"
                persistedSessions = sessions
                onCommitted()
                completedJob()
            },
            startGeneration = { request, usedReservation ->
                events += "start"
                assertEquals(reservation, usedReservation)
                requested = request
                true
            },
            acquirePreparationLease = { ChatEditPreparationLease { events += "lease-close" } }
        )

        assertNotNull(persistedSessions)
        assertEquals(
            listOf("第一问", "第一答", "改过的第二问"),
            requireNotNull(requested).map { it.content }
        )
        assertEquals(4, requireNotNull(persistedSessions).single().messages.size)
        val persistIndex = events.indexOf("persist")
        val startIndex = events.indexOf("start")
        assertTrue("generation must start only after persistence", persistIndex >= 0 && startIndex > persistIndex)
        assertEquals("改过的第二问", store.current.messages[2].content)
        assertEquals(Role.ASSISTANT, store.current.messages[3].role)
        assertTrue(store.current.isGenerating)
        assertEquals("正在应用编辑并重新发送…", store.current.statusMessage)
    }

    @Test
    fun `a failed durable write cancels the reservation and never starts generation`() {
        val store = UiStateStore(state())
        val events = mutableListOf<String>()
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target(),
            editedText = "改过的第二问",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { events += "reserve"; UiGenerationReservation(8L, null) },
            cancelPendingReservation = { events += "cancel" },
            persistMutation = { _, _, _, onCommitFailed ->
                events += "persist"
                onCommitFailed()
                completedJob()
            },
            startGeneration = { _, _ -> events += "start"; true },
            acquirePreparationLease = { ChatEditPreparationLease { events += "lease-close" } }
        )

        assertFalse(events.contains("start"))
        assertTrue(events.contains("cancel"))
    }

    @Test
    fun `editing is rejected while generating without touching persistence`() {
        val store = UiStateStore(state(isGenerating = true))
        val events = mutableListOf<String>()
        var status: String? = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target(),
            editedText = "改过的第二问",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = { status = it },
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = { events += "discard" },
            reserveGeneration = { events += "reserve"; UiGenerationReservation(9L, null) },
            cancelPendingReservation = { events += "cancel" },
            persistMutation = { _, _, _, _ -> events += "persist"; completedJob() },
            startGeneration = { _, _ -> events += "start"; true }
        )

        assertTrue(status.orEmpty().contains("请先停止当前生成"))
        assertEquals(emptyList<String>(), events)
        assertEquals(messages, store.current.messages)
    }

    @Test
    fun `editing the assistant row is rejected`() {
        val store = UiStateStore(state())
        var status: String? = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target(firstAnswer.id),
            editedText = "改答",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = { status = it },
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { error("must not reserve") },
            cancelPendingReservation = {},
            persistMutation = { _, _, _, _ -> error("must not persist") },
            startGeneration = { _, _ -> error("must not start") }
        )
        assertTrue(status.orEmpty().contains("只能编辑自己发送的消息"))
    }

    @Test
    fun `a stale message identity is a status instead of a partial write`() {
        val store = UiStateStore(state())
        var status: String? = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target("missing"),
            editedText = "改",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = { status = it },
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { error("must not reserve") },
            cancelPendingReservation = {},
            persistMutation = { _, _, _, _ -> error("must not persist") },
            startGeneration = { _, _ -> error("must not start") }
        )
        assertTrue(status.orEmpty().contains("消息已变化"))
    }

    @Test
    fun `an active image job in the retired tail blocks the edit`() {
        val request = ChatGeneratedImageRequest(
            id = "image-task",
            prompt = "猫",
            status = ChatGeneratedImageStatus.DONE
        )
        val withImage = listOf(
            ChatMessage(Role.USER, "问"),
            ChatMessage(Role.ASSISTANT, "答", generatedImageRequest = request)
        )
        val store = UiStateStore(state(messages = withImage))
        var status: String? = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = ChatMessageIdentity(SESSION, "问-missing"),
            editedText = "新问",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = { status = it },
            isChatImageRequestActive = { true },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { error("must not reserve") },
            cancelPendingReservation = {},
            persistMutation = { _, _, _, _ -> error("must not persist") },
            startGeneration = { _, _ -> error("must not start") }
        )
        // The identity does not resolve, so this asserts the stale-target guard; the active-image
        // guard for a resolved target is covered below with a real id.
        assertTrue(status.orEmpty().contains("消息已变化"))

        status = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = ChatMessageIdentity(SESSION, withImage.first().id),
            editedText = "新问",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = { status = it },
            isChatImageRequestActive = { true },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { error("must not reserve") },
            cancelPendingReservation = {},
            persistMutation = { _, _, _, _ -> error("must not persist") },
            startGeneration = { _, _ -> error("must not start") }
        )
        assertTrue(status.orEmpty().contains("图片任务"))
    }

    @Test
    fun `quote state and edit state are independent - edit carries no quote block`() {
        val store = UiStateStore(state())
        var requested: List<ChatMessage>? = null
        editAndResendMessageFor(
            application = null,
            store = store.conversationRegenerationWriter(),
            target = target(),
            editedText = "只改文本",
            rejectWhileConversationMutationInProgress = { false },
            showStatus = {},
            isChatImageRequestActive = { false },
            pendingAssistantImageToolTurns = mutableMapOf(),
            discardPendingAssistantOutput = {},
            reserveGeneration = { UiGenerationReservation(10L, null) },
            cancelPendingReservation = {},
            persistMutation = { _, _, onCommitted, _ ->
                onCommitted()
                completedJob()
            },
            startGeneration = { request, _ -> requested = request; true },
            acquirePreparationLease = { ChatEditPreparationLease {} }
        )
        assertFalse(requested.orEmpty().any { it.content.contains("[引用消息]") })
    }

    private fun completedJob(): Job = Job().apply { complete() }

    private companion object {
        const val SESSION = "session-a"
    }
}

package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.io.Serializable

/**
 * Stable target for a message action.
 *
 * [duplicateOrdinal] is only a compatibility disambiguator for legacy data that contains the same
 * id more than once (including blank ids). It is the occurrence among messages with that same id,
 * not the row index, so prepending or deleting unrelated messages does not change the identity.
 */
data class ChatMessageIdentity(
    val conversationId: String?,
    val messageId: String,
    val duplicateOrdinal: Int = 0
) : Serializable {
    fun resolveIndex(messages: List<ChatMessage>, activeConversationId: String?): Int? {
        if (conversationId != activeConversationId || duplicateOrdinal < 0) return null
        var occurrence = 0
        messages.forEachIndexed { index, message ->
            if (message.id == messageId) {
                if (occurrence == duplicateOrdinal) return index
                occurrence += 1
            }
        }
        return null
    }

    companion object {
        fun atIndex(
            messages: List<ChatMessage>,
            conversationId: String?,
            index: Int
        ): ChatMessageIdentity? {
            if (index !in messages.indices) return null
            val messageId = messages[index].id
            var duplicateOrdinal = 0
            for (priorIndex in 0 until index) {
                if (messages[priorIndex].id == messageId) duplicateOrdinal += 1
            }
            return ChatMessageIdentity(conversationId, messageId, duplicateOrdinal)
        }
    }
}

internal data class ChatMessageRowUi(
    val message: ChatMessage,
    val identity: ChatMessageIdentity
)

internal fun chatMessageRows(
    messages: List<ChatMessage>,
    conversationId: String?
): List<ChatMessageRowUi> {
    val occurrences = HashMap<String, Int>(messages.size)
    return messages.map { message ->
        val duplicateOrdinal = occurrences.getOrDefault(message.id, 0)
        occurrences[message.id] = duplicateOrdinal + 1
        ChatMessageRowUi(
            message = message,
            identity = ChatMessageIdentity(
                conversationId = conversationId,
                messageId = message.id,
                duplicateOrdinal = duplicateOrdinal
            )
        )
    }
}

sealed interface ChatMessageIntent {
    val target: ChatMessageIdentity

    data class Delete(override val target: ChatMessageIdentity) : ChatMessageIntent
    data class Regenerate(override val target: ChatMessageIdentity) : ChatMessageIntent
    data class DeleteLastTurn(override val target: ChatMessageIdentity) : ChatMessageIntent
}

/**
 * Keeps the legacy index-based callbacks usable for existing callers while preferring the
 * identity-aware intent path. The fallback resolves the captured target against the latest
 * available list immediately before invoking a legacy callback.
 */
internal class ChatMessageIntentDispatcher(
    private val currentMessages: () -> List<ChatMessage>,
    private val activeConversationId: () -> String?,
    private val onIntent: () -> ((ChatMessageIntent) -> Unit)?,
    private val legacyOnDeleteMessage: (Int) -> Unit,
    private val legacyOnRegenerate: () -> Unit,
    private val legacyOnDeleteLastTurn: () -> Unit
) {
    fun delete(target: ChatMessageIdentity) = dispatch(ChatMessageIntent.Delete(target))

    fun regenerate(target: ChatMessageIdentity) = dispatch(ChatMessageIntent.Regenerate(target))

    fun deleteLastTurn(target: ChatMessageIdentity) =
        dispatch(ChatMessageIntent.DeleteLastTurn(target))

    fun isCurrent(target: ChatMessageIdentity): Boolean =
        target.resolveIndex(currentMessages(), activeConversationId()) != null

    private fun dispatch(intent: ChatMessageIntent) {
        onIntent()?.let { handler ->
            handler(intent)
            return
        }

        val messages = currentMessages()
        val index = intent.target.resolveIndex(messages, activeConversationId()) ?: return
        when (intent) {
            is ChatMessageIntent.Delete -> legacyOnDeleteMessage(index)
            is ChatMessageIntent.Regenerate ->
                if (index == messages.lastAssistantIndex()) legacyOnRegenerate()
            is ChatMessageIntent.DeleteLastTurn ->
                if (index == messages.lastAssistantIndex()) legacyOnDeleteLastTurn()
        }
    }
}

private fun List<ChatMessage>.lastAssistantIndex(): Int =
    indexOfLast { message -> message.role == Role.ASSISTANT }

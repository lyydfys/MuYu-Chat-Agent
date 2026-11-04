package com.muyuchat.mca

import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.feature.chat.ChatQuotedMessage
import com.muyuchat.mca.composition.chatPromptDraftAuthority
import com.muyuchat.mca.composition.conversationRegenerationAuthority

/**
 * Sets or clears the one-turn composer quote.
 *
 * The quote is prompt-draft state, not conversation state: it never enters a persisted message and
 * the concrete [ChatPromptDraftFields] writer is the only authority that may change it.
 */
internal fun MainViewModel.setQuotedMessage(quote: ChatQuotedMessage?) {
    chatPromptDraftAuthority.update { it.copy(quotedMessage = quote) }
}

/**
 * Delegates one edit-and-resend turn to the existing durable-mutation and generation owners.
 *
 * The pending quote is cleared first: editing an older turn is not a quote of that turn, and the
 * quote is single-turn composer state.
 */
internal fun MainViewModel.editAndResendMessage(target: ChatMessageIdentity, editedText: String) {
    setQuotedMessage(null)
    editAndResendMessageFor(
        application = getApplication(),
        store = conversationRegenerationAuthority,
        target = target,
        editedText = editedText,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        showStatus = ::showStatus,
        isChatImageRequestActive = ::isChatImageRequestActive,
        pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
        discardPendingAssistantOutput = { discardPendingAssistantOutput() },
        reserveGeneration = ::reserveUiGenerationStart,
        cancelPendingReservation = { chatRuns.cancelPending(it) },
        persistMutation = { sessions, rollback, onCommitted, onCommitFailed ->
            persistConversationMutation(
                sessions = sessions,
                rollback = rollback,
                onCommitted = onCommitted,
                onCommitFailed = onCommitFailed
            )
        },
        startGeneration = { messages, reservation -> startGeneration(messages, reservation) }
    )
}

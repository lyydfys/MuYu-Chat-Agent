package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageToolContinuationStatus

// Chat session shape and the transcript text for a generated-image request.
//
// Moved here verbatim from MainViewModel during P2. These were already top-level declarations
// above the class.
//
// ChatSessionRecord is what a conversation is persisted as, and latestSessionForAssistant and
// withInterruptedChatImageRequests are how a session is found and how an interrupted image
// request is repaired when the app comes back. assistantTranscriptText is the sentence the
// transcript shows for each generated-image state, including the interrupted one, which says
// the last attempt was cut off and can be retried rather than presenting it as a failure.
//
// Kept in com.muyuchat.mca: no import changes anywhere else. Twelve test files name these types
// and not one of them reads MainViewModel as text, so this move needed no test edits at all --
// the first slice in this series where both of those are true.
//
// There is no private declaration in this file, so nothing needed its visibility widened. The
// five private types just below this block, in the conversation-mutation code, stayed where
// they are: the class body uses them, and moving them would have meant widening five
// declarations from private to internal for no benefit.
//
// No field, type, default, branch or sentence was edited.

data class ChatSessionRecord(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val pinned: Boolean = false,
    val manualTitle: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: String? = null,
    val assistantId: String? = null,
    /** Immutable persona contract captured when this conversation chose an assistant. */
    val assistantSnapshot: AssistantConversationSnapshot? = null,
    /** Historical ownership could not be proved; keep its messages viewable but isolated. */
    val mixedAssistantHistory: Boolean = false,
    val modelMode: String? = null,
    val modelId: String? = null,
    /** Optional per-conversation override. Null inherits the role/global appearance. */
    val appearanceOverride: ChatAppearance? = null,
    val contextSummaries: List<ContextSummaryRecord> = emptyList()
)

internal fun List<ChatSessionRecord>.latestSessionForAssistant(assistantId: String): ChatSessionRecord? =
    asSequence()
        .filter {
            !it.mixedAssistantHistory && it.assistantId == assistantId &&
                it.assistantSnapshot?.assistantId == assistantId
        }
        .maxWithOrNull(compareBy<ChatSessionRecord> { it.updatedAt }.thenBy { it.id })

internal data class ChatImageToolPermissionUiState(
    val available: Boolean,
    val unavailableReason: String,
    val autoApproval: Boolean,
    val canChange: Boolean
)

enum class ChatAppearanceScope {
    GLOBAL,
    ASSISTANT,
    SESSION
}

internal data class ConversationTailPrune(
    val messages: List<ChatMessage>,
    val removedMessageCount: Int
)

internal fun List<ChatSessionRecord>.withInterruptedChatImageRequests(): List<ChatSessionRecord> =
    map { session ->
        var changed = false
        val recoveredMessages = session.messages.map { message ->
            val request = message.generatedImageRequest
            if (request == null) {
                message
            } else if (request.status in setOf(
                    ChatGeneratedImageStatus.QUEUED,
                    ChatGeneratedImageStatus.GENERATING
                )
            ) {
                changed = true
                val interrupted = request.copy(
                    status = ChatGeneratedImageStatus.INTERRUPTED,
                    message = "应用未保留运行中的图片任务，请重试。",
                    toolContinuationStatus = if (
                        request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING
                    ) ChatImageToolContinuationStatus.FAILED else request.toolContinuationStatus,
                    toolContinuationStarted = false
                )
                message.copy(
                    content = interrupted.assistantTranscriptText(),
                    generatedImageRequest = interrupted
                )
            } else if (request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING) {
                changed = true
                val recoverable = request.copy(
                    toolContinuationStatus = ChatImageToolContinuationStatus.FAILED,
                    toolContinuationStarted = false,
                    message = "图片已保存，但角色后续回复中断；点击“继续角色回复”可重试。"
                )
                message.copy(
                    content = recoverable.assistantTranscriptText(),
                    generatedImageRequest = recoverable
                )
            } else {
                message
            }
        }
        if (changed) {
            session.copy(messages = recoveredMessages, updatedAt = System.currentTimeMillis())
        } else {
            session
        }
    }

internal fun ChatGeneratedImageRequest.assistantTranscriptText(): String = when (status) {
    ChatGeneratedImageStatus.AWAITING_APPROVAL -> "角色请求生成图片，等待你的确认。"
    ChatGeneratedImageStatus.QUEUED -> "正在准备图片生成。"
    ChatGeneratedImageStatus.GENERATING -> "正在生成图片。"
    ChatGeneratedImageStatus.DONE -> if (imageAssetIds.size > 1) {
        "已生成 ${imageAssetIds.size} 张图片。"
    } else {
        "图片已生成。"
    }
    ChatGeneratedImageStatus.FAILED -> "图片生成失败：${message.ifBlank { "请检查模型并重试。" }}"
    ChatGeneratedImageStatus.CANCELLED -> "图片生成已取消。"
    ChatGeneratedImageStatus.INTERRUPTED -> "上次图片生成中断，可重试。"
}

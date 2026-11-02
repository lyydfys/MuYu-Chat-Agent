package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus

/**
 * R3-S29：图片工具续写回合的完成结算自 MainViewModel 迁出（体逐字；请求端口注入、续写经 lambda）。
 */
internal fun completeAssistantImageToolTurnForJobFor(
    store: UiStateReadPort,
    requestPort: ChatImageRequestPort,
    findRequest: (String) -> Pair<String, ChatGeneratedImageRequest>?,
    continueTurn: (String, String) -> Unit,
    jobId: String
) {
    val job = store.current.imageJobs.firstOrNull { it.id == jobId } ?: return
    val requestId = job.spec?.chatMessageId ?: return
    val owner = findRequest(requestId) ?: return
    val (sessionId, request) = owner
    if (request.origin != ChatGeneratedImageOrigin.ASSISTANT_TOOL) return
    val output = when (request.status) {
        ChatGeneratedImageStatus.DONE -> toolResultJson("completed", "Image generated successfully.")
        ChatGeneratedImageStatus.CANCELLED -> toolResultJson("cancelled", "Image generation was cancelled.")
        ChatGeneratedImageStatus.FAILED -> toolResultJson("failed", "Image generation failed.")
        ChatGeneratedImageStatus.INTERRUPTED -> toolResultJson("interrupted", "Image generation was interrupted.")
        ChatGeneratedImageStatus.AWAITING_APPROVAL,
        ChatGeneratedImageStatus.QUEUED,
        ChatGeneratedImageStatus.GENERATING -> return
    }
    applyChatGeneratedImageRequest(requestPort, sessionId, requestId, request.copy(toolOutputJson = output))
    continueTurn(requestId, output)
}

internal fun continueAssistantImageTurnFor(
    store: UiStateReadPort,
    findRequest: (String) -> Pair<String, ChatGeneratedImageRequest>?,
    continueTurn: (String, String) -> Unit,
    requestId: String
) {
    if (!store.current.canWriteToActiveAssistantSession()) return
    val owner = findRequest(requestId) ?: return
    val (_, request) = owner
    if (!request.canContinueAssistantImageTurn()) return
    val status = when (request.status) {
        ChatGeneratedImageStatus.DONE -> "completed"
        ChatGeneratedImageStatus.FAILED -> "failed"
        ChatGeneratedImageStatus.CANCELLED -> "cancelled"
        ChatGeneratedImageStatus.INTERRUPTED -> "interrupted"
        ChatGeneratedImageStatus.AWAITING_APPROVAL,
        ChatGeneratedImageStatus.QUEUED,
        ChatGeneratedImageStatus.GENERATING -> return
    }
    val output = request.toolOutputJson ?: toolResultJson(
        status,
        when (status) {
            "completed" -> "Image generated successfully."
            "cancelled" -> "Image generation was cancelled."
            "interrupted" -> "Image generation was interrupted."
            else -> "Image generation failed."
        }
    )
    continueTurn(requestId, output)
}


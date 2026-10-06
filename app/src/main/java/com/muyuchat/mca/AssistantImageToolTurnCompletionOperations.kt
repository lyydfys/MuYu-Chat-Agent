package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus

/**
 * R3-S29：图片工具续写回合的完成结算自 MainViewModel 迁出（体逐字；请求端口注入、续写经 lambda）。
 */
internal fun completeAssistantImageToolTurnForJobFor(
    store: UiStateStore,
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

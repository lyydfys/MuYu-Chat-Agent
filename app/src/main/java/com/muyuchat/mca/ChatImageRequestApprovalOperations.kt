package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * What approving, rejecting or retrying a chat image request needs from its owner.
 *
 * Eleven members: the published state and its two routes, the running generation job the retry guard
 * checks, the status sink, the request lookup, the retry-snapshot builder, the single admission call,
 * the two request-update sinks, the assistant tool-turn continuation, and the writability check.
 *
 * The three entry points share one shape -- find the request, refuse when the conversation cannot be
 * written, re-read the selection, admit through [enqueueImageGeneration] -- so they move together:
 * splitting them would give three files the same eleven-member port and let their guards drift apart.
 */
internal interface ChatImageRequestApprovalPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The running image job, if any; the retry path refuses while it is active. */
    val imageGenerationJob: Job?

    fun showStatus(message: String?)

    /** True when the active conversation is still writable (not a read-only mixed history). */
    fun canWriteToActiveAssistantSession(state: MainUiState): Boolean

    /** Finds the conversation and request that own a request id. */
    fun findChatGeneratedImageRequest(requestId: String): Pair<String, ChatGeneratedImageRequest>?

    /** Rebuilds the job spec for a stored request, or null when its model/parameters are gone. */
    fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec?

    /** The one admission call both the UI and the bridge go through. */
    fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        requestedJobId: String? = null,
        requestedChatSessionId: String? = null,
        requestedChatMessageId: String? = null
    ): Boolean

    fun updateChatGeneratedImageRequest(
        chatSessionId: String,
        requestId: String,
        request: ChatGeneratedImageRequest
    )

    fun updateChatImageRequestFromJob(chatSessionId: String, requestId: String, jobId: String)

    /** Resumes a pending assistant tool turn with this request's terminal result. */
    fun continueAssistantImageToolTurn(requestId: String, outputJson: String)
}

/**
 * The tool-result envelope an assistant tool turn receives.
 *
 * `internal`, not `private`: the ViewModel still builds this envelope on paths that did not move
 * (resuming a terminal assistant tool turn), and as a same-package top-level function it is
 * reachable from there without an import.
 */
internal fun toolResultJson(status: String, message: String): String = JSONObject()
    .put("status", status)
    .put("message", message)
    .toString()

/**
 * Retries a chat image request: rebuilds the job spec, requeues the stored request, admits it and
 * reports the admission result back onto the request. Same guards, wording and ordering as before.
 *
 * Moved verbatim out of `MainViewModel` (58 lines).
 */
internal fun retryChatImageGeneration(port: ChatImageRequestApprovalPort, requestId: String) {
        val state = port.uiState()
        if (state.isGenerating || port.imageGenerationJob?.isActive == true) {
            port.updateUiState {
                it.copy(statusMessage = "当前有生成任务正在运行，完成或停止后再重试图片。")
            }
            return
        }
        val owner = state.chatSessions.firstNotNullOfOrNull { session ->
            session.messages.firstOrNull { it.generatedImageRequest?.id == requestId }
                ?.let { session.id to it }
        } ?: state.messages.firstOrNull { it.generatedImageRequest?.id == requestId }
            ?.let { message -> state.activeChatSessionId?.let { it to message } }
        if (owner == null) {
            port.showStatus("找不到这条图片请求，可能已从聊天记录删除。")
            return
        }
        val (sessionId, message) = owner
        if (state.activeChatSessionId != sessionId || !port.canWriteToActiveAssistantSession(state)) return
        val previous = message.generatedImageRequest ?: return
        val jobId = "ui-img-${UUID.randomUUID()}"
        val snapshot = port.buildChatImageRetrySnapshot(previous, sessionId)
        if (snapshot == null) {
            port.updateUiState {
                it.copy(statusMessage = "原生图模型或参数已不可用；请重新选择原模型，或明确切换模型后再重试。")
            }
            return
        }
        val queuedRequest = previous.copy(
            status = ChatGeneratedImageStatus.QUEUED,
            message = "正在重新排队生成图片。",
            imageAssetIds = emptyList(),
            currentJobId = jobId,
            toolOutputJson = null,
            toolContinuationStatus = ChatImageToolContinuationStatus.NOT_STARTED,
            toolContinuationStarted = false
        )
        port.updateChatGeneratedImageRequest(sessionId, requestId, queuedRequest)
        val admitted = port.enqueueImageGeneration(
            prompt = previous.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot,
            requestedJobId = jobId,
            requestedChatSessionId = sessionId,
            requestedChatMessageId = requestId
        )
        if (!admitted) {
            port.updateChatGeneratedImageRequest(
                chatSessionId = sessionId,
                requestId = requestId,
                request = queuedRequest.copy(
                    status = ChatGeneratedImageStatus.FAILED,
                    message = port.uiState().statusMessage ?: "无法开始图片生成，请检查已选模型。"
                )
            )
        } else {
            port.updateChatImageRequestFromJob(sessionId, requestId, jobId)
        }
}

/**
 * Approves an awaited chat image request and admits it. Same guards, wording and ordering as before.
 *
 * Moved verbatim out of `MainViewModel` (46 lines).
 */
internal fun approveChatImageRequest(port: ChatImageRequestApprovalPort, requestId: String) {
        val owner = port.findChatGeneratedImageRequest(requestId) ?: return
        val (chatSessionId, request) = owner
        val state = port.uiState()
        if (state.activeChatSessionId != chatSessionId || !port.canWriteToActiveAssistantSession(state)) return
        if (request.status != ChatGeneratedImageStatus.AWAITING_APPROVAL) return
        val snapshot = port.buildChatImageRetrySnapshot(request, chatSessionId)
        if (snapshot == null) {
            val failed = request.copy(
                status = ChatGeneratedImageStatus.FAILED,
                message = "原生图模型已不可用，请恢复该模型后重试；未切换到其他模型。"
            )
            port.updateChatGeneratedImageRequest(chatSessionId, requestId, failed)
            if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL) {
                port.continueAssistantImageToolTurn(requestId, toolResultJson("failed", failed.message))
            }
            return
        }
        val jobId = "ui-img-${UUID.randomUUID()}"
        val queued = request.copy(
            status = ChatGeneratedImageStatus.QUEUED,
            message = "已确认，正在准备图片生成。",
            currentJobId = jobId,
            imageAssetIds = emptyList()
        )
        port.updateChatGeneratedImageRequest(chatSessionId, requestId, queued)
        val admitted = port.enqueueImageGeneration(
            prompt = request.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot,
            requestedJobId = jobId,
            requestedChatSessionId = chatSessionId,
            requestedChatMessageId = requestId
        )
        if (!admitted) {
            val failed = queued.copy(
                status = ChatGeneratedImageStatus.FAILED,
                message = port.uiState().statusMessage ?: "无法开始图片生成，请检查已选模型。"
            )
            port.updateChatGeneratedImageRequest(chatSessionId, requestId, failed)
            if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL) {
                port.continueAssistantImageToolTurn(requestId, toolResultJson("failed", failed.message))
            }
        } else {
            port.updateChatImageRequestFromJob(chatSessionId, requestId, jobId)
        }
}

/**
 * Rejects an awaited chat image request and resumes the assistant tool turn with a denial.
 *
 * Moved verbatim out of `MainViewModel` (13 lines).
 */
internal fun rejectChatImageRequest(port: ChatImageRequestApprovalPort, requestId: String) {
        val owner = port.findChatGeneratedImageRequest(requestId) ?: return
        val (chatSessionId, request) = owner
        val state = port.uiState()
        if (state.activeChatSessionId != chatSessionId || !port.canWriteToActiveAssistantSession(state)) return
        if (request.status != ChatGeneratedImageStatus.AWAITING_APPROVAL) return
        val rejected = request.copy(
            status = ChatGeneratedImageStatus.CANCELLED,
            message = "你拒绝了这次生图请求，未生成图片。"
        )
        port.updateChatGeneratedImageRequest(chatSessionId, requestId, rejected)
        if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL) {
            port.continueAssistantImageToolTurn(requestId, toolResultJson("denied", rejected.message))
        }
}

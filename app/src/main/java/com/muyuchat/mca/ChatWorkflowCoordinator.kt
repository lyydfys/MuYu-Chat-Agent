package com.muyuchat.mca

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.core.engine.localContextWindowAdmission
import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.feature.chat.withQuotedContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates chat conversation workflow mutations, including turn deletion and rollbacks.
 */
internal class ChatWorkflowCoordinator(
    private val store: UiStateWriter<ConversationWorkflowFields>,
    private val rejectWhileConversationMutationInProgress: () -> Boolean,
    private val showStatus: (String) -> Unit,
    private val isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean = ::isChatImageRequestActive,
    private val pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    private val persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, Boolean) -> Job?
) {
    private val chatImageSubmissionPreflight = AtomicBoolean(false)

    /**
     * Deletes the last conversation turn (user prompt and corresponding assistant response).
     *
     * Calculates message indices for the last turn, updates UI state, cancels/removes pending
     * image tasks, updates session summaries, and commits durable mutation preserving reusable native prefix.
     */
    fun deleteLastConversationTurn(
        chatSessionId: String? = null,
        expectedTarget: ChatMessageIdentity? = null
    ): Boolean {
        val state = store.state.value
        if (rejectWhileConversationMutationInProgress()) return false
        if (expectedTarget != null) {
            val targetIndex = expectedTarget.resolveIndex(state.messages, state.activeChatSessionId)
            val lastAssistant = state.messages.indexOfLast { it.role == Role.ASSISTANT }
            if (targetIndex == null || targetIndex != lastAssistant) return false
        }
        if (!state.canWriteToActiveAssistantSession()) return false
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再删除本轮对话")
            return false
        }
        val prune = state.messages.pruneLastConversationTurn()
        if (prune == null) {
            showStatus("没有可删除的本轮对话")
            return false
        }
        val lastUserIndex = state.messages.indexOfLast { it.role == Role.USER }
        val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        val removedEndIndex = if (lastAssistantIndex > lastUserIndex) lastAssistantIndex else lastUserIndex
        val removedRequests = if (lastUserIndex >= 0 && removedEndIndex >= lastUserIndex) {
            state.messages.subList(lastUserIndex, removedEndIndex + 1)
                .mapNotNull { it.generatedImageRequest }
        } else emptyList()
        if (removedRequests.any(isChatImageRequestActive)) {
            showStatus("本轮仍关联运行中的图片任务，请等任务结束后再删除。")
            return false
        }
        val rollback = state.conversationMutationRollbackState()
        val sessionId = chatSessionId ?: state.activeChatSessionId ?: UUID.randomUUID().toString()
        val occurredAt = System.currentTimeMillis()
        val committed = store.updateAndGet {
            val updatedSessions = if (prune.messages.isEmpty()) {
                it.chatSessions.map { session ->
                    if (session.id == sessionId) session.copy(
                        messages = emptyList(),
                        contextSummaries = emptyList(),
                        updatedAt = occurredAt
                    ) else session
                }
            } else {
                it.chatSessions.upsertSessionFor(
                    state = it.snapshot,
                    sessionId = sessionId,
                    messages = prune.messages,
                    assistantId = it.selectedAssistantId,
                    modelMode = it.selectedChatBackend.bindingValue(),
                    modelId = it.snapshot.currentChatModelId(),
                    occurredAt = occurredAt
                )
            }
            it.copy(
                messages = prune.messages,
                activeChatSessionId = sessionId,
                chatSessions = updatedSessions,
                promptContextUsage = null,
                statusMessage = "已删除最后一轮对话"
            )
        }
        removedRequests.forEach { pendingAssistantImageToolTurns.remove(it.id) }
        persistConversationMutation(
            committed.chatSessions,
            rollback,
            true // preserveReusableNativePrefix = true
        )
        return true
    }

    fun sendMessage(viewModel: MainViewModel): Boolean {
        val state = store.current
        if (rejectWhileConversationMutationInProgress()) return false
        if (viewModel.assistantImageContinuationCoordinator.hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先确认、取消或等待上一张角色请求的图片完成。")
            return false
        }
        val preparedInput = viewModel.prepareChatInput(state)
        if ((preparedInput.text.isBlank() && preparedInput.imageAttachments.isEmpty()) || state.isGenerating) return false
        if (preparedInput.imageAttachments.size > 1) {
            if (!chatImageSubmissionPreflight.compareAndSet(false, true)) return false
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                try {
                    // The composer already performs an asynchronous dedup pass, but keep a final
                    // model-boundary check here as well. This protects message persistence and the
                    // actual runner input if a picker/import callback races that UI preflight.
                    val uniqueAttachments = preparedInput.imageAttachments
                        .deduplicatedVisionAttachmentsForSend(viewModel.getApplication<Application>())
                    withContext(Dispatchers.Main.immediate) {
                        val current = store.current
                        if (current.input != state.input || current.isGenerating) return@withContext
                        sendPreparedMessageAsync(viewModel, current, preparedInput.copy(imageAttachments = uniqueAttachments))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // A failed submission must never escape a viewModelScope coroutine: an
                    // uncaught throw here reaches the thread's default handler and kills the
                    // process, which is exactly what "tap send, app disappears" looked like.
                    reportSubmissionFailure(error)
                } finally {
                    chatImageSubmissionPreflight.set(false)
                }
            }
            return true
        }
        return sendPreparedMessage(viewModel, state, preparedInput)
    }

    internal fun sendPreparedMessage(
        viewModel: MainViewModel,
        state: MainUiState,
        preparedInput: PreparedChatInput
    ): Boolean {
        if (!chatImageSubmissionPreflight.compareAndSet(false, true)) return false
        viewModel.viewModelScope.launch {
            try {
                sendPreparedMessageAsync(viewModel, state, preparedInput)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportSubmissionFailure(error)
            } finally {
                chatImageSubmissionPreflight.set(false)
            }
        }
        return true
    }

    /**
     * Turns a submission-time failure into a visible status line instead of an uncaught exception.
     *
     * Everything before the provider call (context composition, admission, session persistence)
     * can fail on damaged storage or a store read error. Those paths used to escape the launched
     * coroutine and take the process down before any UI was published.
     */
    private fun reportSubmissionFailure(error: Throwable) {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        store.update {
            it.copy(
                isGenerating = false,
                statusMessage = "发送失败：$reason。请重试；若持续失败，请检查会话数据是否损坏。"
            )
        }
    }

    internal suspend fun sendPreparedMessageAsync(
        viewModel: MainViewModel,
        state: MainUiState,
        preparedInput: PreparedChatInput
    ) {
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) {
            showStatus("此对话的角色归属不明确或角色已删除，请在历史中查看并手动分支。")
            return
        }
        if (viewModel.assistantImageContinuationCoordinator.hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先确认、取消或等待上一张角色请求的图片完成。")
            return
        }
        if ((preparedInput.text.isBlank() && preparedInput.imageAttachments.isEmpty()) || state.isGenerating) return
        // Reject unsupported image input before creating/persisting a chat turn.
        // Previously the request was first saved and marked as generating, then
        // failed deeper in startGeneration. Besides confusing users, that sent
        // stale/unsupported image state closer to native runtimes and made a
        // repeated tap look like a duplicate-image submission.
        if (preparedInput.imageAttachments.isNotEmpty()) {
            if (state.selectedChatBackend == ChatBackend.CLOUD) {
                val cloud = state.selectedChatCloudConfig()?.normalized()
                val unavailable = when {
                    cloud == null || !cloud.configured -> "请先配置并选择云端聊天模型，再发送图片。"
                    !cloud.supportsVision -> "当前云端模型未开启图片输入。请在模型管理中为该模型启用图片输入后重试。"
                    else -> null
                }
                if (unavailable != null) {
                    showStatus(unavailable)
                    return
                }
            } else if (!isLocalVisionRunnerAvailable(viewModel.visionAttachmentPort)) {
                val unavailable = localVisionUnavailableReason(
                    state, runCatching { viewModel.runtimeAssembly.nativeStatsJson() }.getOrNull()
                )
                store.showStatus(unavailable)
                return
            }
        }
        if (hasExplicitChatImageCommand(preparedInput.text)) {
            if (preparedInput.imageAttachments.isNotEmpty() ||
                preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
            ) {
                store.update {
                    it.copy(statusMessage = "聊天生图暂不支持把已附加的图片或文件作为参考。请先移除附件，再发送 /image 图片描述。")
                }
                return
            }
            val imageIntent = parseExplicitChatImageIntent(preparedInput.text)
            if (imageIntent == null) {
                showStatus("请在 /image 后输入图片描述，再发送。")
                return
            }
            viewModel.hostSendChatImageRequestWithPromptBridge(
                visibleUserText = preparedInput.text,
                prompt = imageIntent.sourceText,
                requestedOutputCount = classifyChatImageIntent(preparedInput.text).requestedOutputCount
            )
            return
        }
        if (preparedInput.imageAttachments.isEmpty() &&
            !preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
        ) {
            val lastImageRequest = state.messages.asReversed().firstNotNullOfOrNull {
                it.generatedImageRequest
            }
            val imageDecision = classifyChatImageIntent(
                preparedInput.text,
                ChatImageIntentContext(
                    sessionId = state.activeChatSessionId,
                    lastImageRequestId = lastImageRequest?.id,
                    lastImageSessionId = state.activeChatSessionId,
                    recentText = state.messages.takeLast(8).map { it.content },
                    characterName = state.activeAssistantSnapshot()?.name ?: state.selectedAssistant()?.name
                )
            )
            when (imageDecision.route) {
                ChatImageIntentRoute.GENERATE -> {
                    imageDecision.intent?.let { intent ->
                        viewModel.hostSendChatImageRequestWithPromptBridge(
                            visibleUserText = preparedInput.text,
                            prompt = intent.sourceText,
                            requestedOutputCount = imageDecision.requestedOutputCount
                        )
                        return
                    }
                }
                ChatImageIntentRoute.AMBIGUOUS -> {
                    val actionId = UUID.randomUUID().toString()
                    viewModel.container.runtimeAdmission.pendingChatImageAction = PendingChatImageAction(
                        id = actionId,
                        kind = com.muyuchat.feature.chat.ChatImageActionKind.AMBIGUOUS,
                        visibleUserText = preparedInput.text,
                        requestedOutputCount = imageDecision.requestedOutputCount,
                        stillOwned = {
                            val current = store.current
                            current.activeChatSessionId == state.activeChatSessionId &&
                                current.messages == state.messages &&
                                current.input == state.input &&
                                current.selectedChatBackend == state.selectedChatBackend &&
                                current.selectedImageBackend == state.selectedImageBackend
                        }
                    )
                    store.update {
                        it.copy(
                            pendingChatImageActionDraft = com.muyuchat.feature.chat.ChatImageActionDraftUi(
                                id = actionId,
                                sourceText = preparedInput.text,
                                kind = com.muyuchat.feature.chat.ChatImageActionKind.AMBIGUOUS,
                                reason = "这句话可能是图片请求。请选择本轮操作，也可先编辑描述。"
                            ),
                            statusMessage = null
                        )
                    }
                    return
                }
                ChatImageIntentRoute.CHAT -> Unit
            }
        }
        if (viewModel.activeImageGenerationBackend == ImageBackend.LOCAL &&
            viewModel.activeImageGenerationJobId != null &&
            state.selectedChatBackend == ChatBackend.LOCAL
        ) {
            store.update {
                it.copy(statusMessage = "本地生图正在运行，请等图片完成或停止后再启动本地聊天，避免设备内存冲突。")
            }
            return
        }
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val conversationParams = assistantSnapshot?.applyTo(state.params) ?: state.params
        val conversationAssistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId
        val characterMemoryContext = if (state.assistants.any {
                it.id == conversationAssistantId && it.memoryEnabled
            }) {
            withContext(Dispatchers.IO) {
                buildCharacterMemoryContext(
                    assistantId = conversationAssistantId,
                    memories = viewModel.chatSessionStore.loadMemories(conversationAssistantId),
                    pendingTurns = viewModel.chatSessionStore.loadMemoryTurns(conversationAssistantId),
                    currentSessionId = state.activeChatSessionId
                )
            }
        } else ""
        val user = ChatMessage(
            role = Role.USER,
            content = preparedInput.text.ifBlank {
                if (preparedInput.imageAttachments.isNotEmpty()) "请描述这张图片。" else ""
            },
            imageAttachments = preparedInput.imageAttachments
        )
        // A quote is a request-scoped modifier: the persisted user message keeps the visible text,
        // while this copy carries the structured quote block into admission, token accounting and
        // every provider payload. No "他刚才说..." sentence is written into stored content.
        val requestUser = user.withQuotedContext(preparedInput.quotedMessage)
        // Context compression must run before the first admission decision.  The
        // old ordering rejected an over-budget request (or let a trimmed
        // request proceed without using that trimmed list) before the automatic
        // compression policy could fold historical turns.  Build a logical
        // request first, compress it in memory, then rebuild runtime context
        // and perform the final safety admission.  The persisted conversation
        // remains untouched; only the request crossing the runtime boundary is
        // compressed/trimmed.
        val preflightMessages = state.messages + requestUser
        val preflightContext = withContext(Dispatchers.IO) { viewModel.chatContextComposer.compose(
            messages = preflightMessages,
            params = conversationParams,
            assistantId = conversationAssistantId,
            chatSessionId = state.activeChatSessionId,
            knowledgeBaseIds = state.selectedKnowledgeBaseIds,
            fileContextEnabled = assistantSnapshot?.fileContextEnabled
                ?: state.selectedAssistant()?.fileContextEnabled
                ?: true
        ) }
        val preflightRequest = ChatRequest(
            messages = preflightMessages,
            params = conversationParams,
            protectedMessageIds = preflightMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
            runtimeSystemContext = listOf(preflightContext.runtimeSystemContext, characterMemoryContext)
                .filter(String::isNotBlank).joinToString("\n\n")
        )
        val initialAdmission = localContextWindowAdmission(preflightRequest)
        val preflightCompression = compressChatRequestContext(
            request = preflightRequest,
            settings = ContextCompressionSettings(
                threshold = viewModel.contextCompressionCoordinator.loadContextCompressionThreshold(),
                keepRecentMessages = 8
            ),
            // Force a compression attempt when the first safety decision is a
            // rejection.  This matters when the conservative token estimate is
            // below the configured threshold but runtime bytes/code points are
            // already over the window.
            trigger = if (initialAdmission.isAccepted) {
                ContextCompressionTrigger.AUTOMATIC
            } else {
                ContextCompressionTrigger.MANUAL
            }
        )
        val compressedPreflightMessages = preflightCompression.request.messages
        val rebuiltPreflightContext = withContext(Dispatchers.IO) { viewModel.chatContextComposer.compose(
            messages = compressedPreflightMessages,
            params = conversationParams,
            assistantId = conversationAssistantId,
            chatSessionId = state.activeChatSessionId,
            knowledgeBaseIds = state.selectedKnowledgeBaseIds,
            fileContextEnabled = assistantSnapshot?.fileContextEnabled
                ?: state.selectedAssistant()?.fileContextEnabled
                ?: true
        ) }
        val finalPreflightRequest = preflightCompression.request.copy(
            runtimeSystemContext = listOf(rebuiltPreflightContext.runtimeSystemContext, characterMemoryContext)
                .filter(String::isNotBlank).joinToString("\n\n")
        )
        val admission = localContextWindowAdmission(finalPreflightRequest)
        if (!admission.isAccepted) {
            store.update {
                it.copy(
                    statusMessage = if (state.input.contains(FILE_ATTACHMENT_MARKER)) {
                        "当前文件内容超过模型上下文，消息未发送。请将长文件导入知识库后再提问。"
                    } else {
                        admission.userMessage
                            ?: "当前输入无法放入本机安全上下文，请缩短后重试。"
                    }
                )
            }
            return
        }
        val currentOwner = store.current
        if (currentOwner.activeChatSessionId != state.activeChatSessionId ||
            !currentOwner.canWriteToActiveAssistantSession() ||
            currentOwner.input != state.input || currentOwner.messages != state.messages ||
            currentOwner.selectedAssistantId != state.selectedAssistantId ||
            currentOwner.assistants != state.assistants ||
            currentOwner.assistantMemories != state.assistantMemories ||
            currentOwner.selectedKnowledgeBaseIds != state.selectedKnowledgeBaseIds ||
            currentOwner.worldBooks != state.worldBooks || currentOwner.params != state.params ||
            currentOwner.selectedChatBackend != state.selectedChatBackend ||
            currentOwner.loadedModelId != state.loadedModelId ||
            currentOwner.selectedCloudChatModelId != state.selectedCloudChatModelId || currentOwner.isGenerating
        ) return
        viewModel.discardPendingAssistantOutput()
        val assistant = ChatMessage(Role.ASSISTANT, "")
        val fallbackSessionId = UUID.randomUUID().toString()
        val occurredAt = System.currentTimeMillis()
        val promptContextUsage = viewModel.contextCompressionCoordinator.promptContextUsageFor(
            plan = rebuiltPreflightContext,
            admission = admission,
            params = conversationParams
        )
        val (sessionsToPersist, chatSessionIdForKnowledgeBinding, selectedKnowledgeBaseIdsForBinding) = store.updateWithResult {
            val sessionId = it.activeChatSessionId ?: fallbackSessionId
            val messages = it.messages + user + assistant
            val sessionsToPersist = it.chatSessions.upsertSessionFor(
                state = it.snapshot,
                sessionId = sessionId,
                messages = messages,
                assistantId = conversationAssistantId,
                assistantSnapshot = assistantSnapshot,
                modelMode = it.selectedChatBackend.bindingValue(),
                modelId = it.snapshot.currentChatModelId(),
                occurredAt = occurredAt
            )
            it.copy(
                input = "",
                messages = messages,
                activeChatSessionId = sessionId,
                chatSessions = sessionsToPersist,
                isGenerating = true,
                promptContextUsage = promptContextUsage,
                statusMessage = null,
                generationPhase = null,
                generationTokenProgress = null,
                generationPersistProgress = null,
                generationStats = null
            ) to Triple(sessionsToPersist, sessionId, it.selectedKnowledgeBaseIds)
        }
        viewModel.hostPersistChatSessions(
            sessions = sessionsToPersist,
            knowledgeBinding = chatSessionIdForKnowledgeBinding?.let { sessionId ->
                sessionId to selectedKnowledgeBaseIdsForBinding
            }
        )

        // Retain originals for source IDs while carrying the exact admitted preflight snapshot.
        viewModel.startGenerationFromCoordinator(
            preflightMessages,
            preflightCompression = preflightCompression.copy(request = finalPreflightRequest)
        )
    }
}

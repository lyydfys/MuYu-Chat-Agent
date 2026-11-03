package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.ConversationMutationRollbackState
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.ModelRuntimeProfileStore
import org.json.JSONObject
import com.muyuchat.core.engine.Role

// MainUiState transitions for the generation lifecycle, moved verbatim from MainViewModel
// during P2.
//
// These are pure: each takes the current state (or the runtime stats) and returns the next
// state. They are grouped because they answer one question -- what does the UI state become
// when a generation starts, completes, is cancelled, or when the native runtime goes away --
// and having them in one file makes it possible to read that sequence without opening a
// 22000-line ViewModel.
//
// The distinction that matters and is preserved: afterGenerationTerminated clears the
// user-facing generation lock independently of completion bookkeeping, because that
// bookkeeping can cross a Binder or persistence boundary and the input has to be released
// before those run. afterNativeRuntimeReleased makes the native unload the authority for the
// loaded-model projection, so no UI or Local API field keeps presenting a released model as
// current.
//
// No field, default, condition or ordering was edited.

/**
 * A completed request releases the GENERATING lifecycle even when the final
 * RuntimeStats emission happened just before the ViewModel cleared isGenerating.
 */
internal fun RuntimeStats.lifecycleAfterGeneration(): AgentEngineLifecycle = when {
    loaded -> AgentEngineLifecycle.READY
    lastError?.isNotBlank() == true -> AgentEngineLifecycle.ERROR
    else -> AgentEngineLifecycle.UNLOADED
}

internal fun MainUiState.afterClearChatGenerationStopped(stats: RuntimeStats): MainUiState = copy(
    messages = emptyList(),
    input = "",
    activeChatSessionId = null,
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    promptContextUsage = null,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = "已清空对话，上下文已重置"
)

internal fun MainUiState.afterBackgroundGenerationStopped(
    stats: RuntimeStats,
    nativeStopIssued: Boolean = true
): MainUiState = copy(
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = if (nativeStopIssued) {
        "应用进入后台，已停止生成以降低发热和耗电。"
    } else {
        "应用进入后台，当前生成已结束或取消。"
    }
)

/**
 * Commits the last bounded stream batch, or removes the untouched assistant placeholder when a
 * UI generation is cancelled by process backgrounding.
 */
internal fun List<ChatMessage>.withBackgroundCancellationFinalized(
    pending: BackgroundCancelledAssistantOutput?
): List<ChatMessage> {
    val assistantIndex = indexOfLast { it.role == Role.ASSISTANT }
    if (assistantIndex < 0) return this
    val updated = toMutableList()
    val assistant = updated[assistantIndex]
    val pendingContent = pending?.content.orEmpty()
    val pendingReasoning = pending?.reasoning.orEmpty()
    val merged = assistant.copy(
        content = assistant.content + pendingContent,
        reasoningContent = assistant.reasoningContent + pendingReasoning,
        reasoningDurationMs = maxOf(
            assistant.reasoningDurationMs,
            pending?.reasoningDurationMs ?: 0L
        )
    )
    if (merged.content.isBlank() && merged.reasoningContent.isBlank()) {
        updated.removeAt(assistantIndex)
    } else {
        updated[assistantIndex] = merged
    }
    return updated
}

internal data class BackgroundCancelledAssistantOutput(
    val content: String,
    val reasoning: String,
    val reasoningDurationMs: Long
)

internal fun MainUiState.afterGenerationStarted(): MainUiState = copy(
    isGenerating = true,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    engineLifecycle = AgentEngineLifecycle.GENERATING
)

internal fun MainUiState.afterGenerationCompleted(stats: RuntimeStats): MainUiState =
    afterGenerationTerminated(stats)

/**
 * Clears the user-facing generation lock independently of any completion
 * bookkeeping.  Completion bookkeeping can cross a Binder or persistence
 * boundary, so the input must be released before those operations run.
 */
internal fun MainUiState.afterGenerationTerminated(
    stats: RuntimeStats,
    statusMessage: String? = null
): MainUiState = copy(
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = statusMessage ?: this.statusMessage
)

/**
 * Native unload is the authority for the active runtime projection. Persisted
 * committed/LKG profiles remain in [ModelRuntimeProfileStore], but no UI or
 * Local API field may continue to present them as the currently loaded model.
 */
internal fun MainUiState.afterNativeRuntimeReleased(
    lifecycle: AgentEngineLifecycle,
    statusMessage: String,
    stats: RuntimeStats = RuntimeStats(),
    busy: Boolean = false
): MainUiState = copy(
    loadedModelId = null,
    loadedModelName = null,
    busy = busy,
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    autoTuningInProgress = false,
    rollbackParams = null,
    profileId = null,
    revision = null,
    profileRecordState = AgentProfileRecordState.NONE,
    verification = AgentProfileVerification.UNKNOWN,
    engineLifecycle = lifecycle,
    tuningJobState = AgentTuningJobState.IDLE,
    reloadRequired = false,
    pendingProfile = null,
    rollbackProfile = null,
    tuningEtaSeconds = null,
    tuningPhase = null,
    tuningCandidateProgress = AgentCandidateProgress(),
    statusMessage = statusMessage
)

/**
 * A runner can detach its native handle before a JNI destroy call reports an
 * error. In that case the engine's Kotlin stats may still be stale, so consult
 * the native diagnostic as a secondary release witness. Missing/malformed
 * diagnostics never override an engine that still reports loaded.
 */
internal fun nativeRuntimeReleaseObserved(
    engineLoaded: Boolean,
    nativeStatsJson: String
): Boolean {
    if (!engineLoaded) return true
    return runCatching {
        val native = JSONObject(nativeStatsJson)
        native.has("loaded") && !native.optBoolean("loaded", true)
    }.getOrDefault(false)
}

internal fun MainUiState.restoreAfterConversationMutationFailure(
    durableSessions: List<ChatSessionRecord>,
    rollback: ConversationMutationRollbackState,
    statusMessage: String
): MainUiState {
    val durableActiveSessionId = rollback.activeChatSessionId
        ?.takeIf { activeId -> durableSessions.any { it.id == activeId } }
    val durableMessages = durableActiveSessionId
        ?.let { activeId -> durableSessions.firstOrNull { it.id == activeId }?.messages }
        .orEmpty()
    return copy(
        messages = durableMessages,
        activeChatSessionId = durableActiveSessionId,
        chatSessions = durableSessions,
        selectedKnowledgeBaseIds = if (durableActiveSessionId == null) {
            emptySet()
        } else {
            rollback.selectedKnowledgeBaseIds
        },
        isGenerating = false,
        generationPhase = rollback.generationPhase,
        generationTokenProgress = rollback.generationTokenProgress,
        promptContextUsage = rollback.promptContextUsage,
        statusMessage = statusMessage
    )
}

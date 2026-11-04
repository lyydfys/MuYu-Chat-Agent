package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import android.app.Application
import com.muyuchat.mca.presentation.ChatContextSnapshot
import java.util.concurrent.atomic.AtomicLong

/** Promoted from MainViewModel for the execution-owner seam; same shape, internal visibility. */
internal data class AssistantImageModelBinding(
    val backend: ImageBackend,
    val modelId: String,
    val modelName: String,
    val options: LocalImageGenerationOptions,
    val fingerprint: String
)

/**
 * The owner seam for the chat generation execution body moved out of
 * `MainViewModel.startGeneration` (P3 step 1). The ViewModel keeps the reservation, the LAZY
 * launcher, the run publish/foreground-lease tail and every collaborator; the moved body is
 * a receiver extension over this port so the verbatim flow stays byte-for-byte recognisable.
 */
internal interface ChatGenerationExecutionPort {

    /** Application context for local-only probes (message image attachments). */
    val application: Application

    // Field-limited capability; execution cannot replace the app's root snapshot.
    val uiState: StateFlow<MainUiState>
    val generationUiWriter: UiStateWriter<GenerationExecutionFields>

    val engine: McaInferenceService

    val chatRuns: ChatRunOwnership

    val chatSessionStore: ChatSessionStore

    val chatContextComposer: ChatContextComposer

    val cloudChatProvider: OpenAiCompatibleChatProvider

    val webSearchProvider: WebSearchProvider

    val manualContextCompressionRequests: ContextCompressionRequestStore

    val localConversationContextInvalidationSequence: AtomicLong

    var activeWebSearchDeferred: Deferred<WebSearchResult>?

    var activeWebSearchTaskId: String?

    var localConversationContextNeedsInvalidation: Boolean

    val foregroundRecoveryJob: Job?

    fun persistChatSessions(sessions: List<ChatSessionRecord>? = null, knowledgeBinding: Pair<String, Set<String>>? = null, generationRunId: Long? = null): Job?

    fun appendAssistant(delta: String, reasoningDelta: String = "", reasoningDurationMs: Long = 0L, forcePublish: Boolean = false, generationRunId: Long? = null)

    fun flushPendingAssistantOutput(generationRunId: Long? = null): Boolean

    fun removePendingAssistantPlaceholder(generationRunId: Long? = null)

    fun ensureVisibleAssistantReplyAfterCompletion(generationRunId: Long? = null)

    fun applyWebSearchAnswerGuardsToLastAssistant(generationRunId: Long? = null): WebSearchCitationAudit?

    fun recordWebSearchCitationAudit(recordId: String?, audit: WebSearchCitationAudit, generationRunId: Long? = null)

    fun attachWebSearchEvidenceToPendingAssistant(sources: List<ChatSourceReference>, trace: ChatWebSearchTrace?, generationRunId: Long? = null)

    fun attachContextAssemblyTrace(traceJson: String?, generationRunId: Long, sessionId: String?, assistantMessageId: String?)

    fun promptContextUsageFor(plan: ChatRuntimeContextPlan, admission: ContextWindowAdmission, params: GenerationParams): PromptContextUsage

    fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord>

    fun recordCompletedCharacterMemoryTurn(assistantId: String, sessionId: String, assistantSnapshot: AssistantConversationSnapshot, user: ChatMessage, assistant: ChatMessage, conversationPersistJob: Job?, backend: ChatBackend, params: GenerationParams, cloudConfig: CloudApiConfig?, localIdentity: String?)

    suspend fun summarizeContextForGeneration(request: ChatRequest, settings: ContextCompressionSettings, trigger: ContextCompressionTrigger, initialState: MainUiState, effectiveBackend: ChatBackend, cloudModelIdOverride: String?, generationRunId: Long, useModel: Boolean): ContextCompressionResult

    suspend fun ensureLocalConversationContextInvalidated(): Boolean

    fun loadContextCompressionThreshold(): ContextCompressionThreshold

    suspend fun handleAssistantImageToolCalls(calls: List<ChatToolCall>, originalRequestMessages: List<ChatMessage>, paramsBeforeAssistant: GenerationParams, chatSessionId: String?, cloudModelId: String?, chatModelIdentity: String?, imageModelBinding: AssistantImageModelBinding?, chatConfig: CloudApiConfig?): Boolean

    fun finishAssistantImageContinuation(requestId: String, completed: Boolean, failureMessage: String)

    /**
     * Folds one finished turn's cache evidence into the app-private aggregate. Fire-and-forget and
     * crash-tolerant: accounting must never fail or slow a chat turn.
     */
    fun recordPrefixCacheTelemetry(stats: RuntimeStats)

    fun chatImageToolChatModelIdentity(modelId: String?, config: CloudApiConfig?): String?

    suspend fun localVisionRunnerAvailable(): Boolean

    fun mergeExecutionProfile(generation: GenerationParams, profile: ModelExecutionProfile): GenerationParams

    fun persistGenerationParams(params: GenerationParams)

    fun canWriteToActiveAssistantSession(state: MainUiState): Boolean

    fun selectedAssistant(state: MainUiState): AssistantRecord?

    fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig?

    fun currentChatModelId(state: MainUiState): String?

    fun chatImageModelBinding(state: MainUiState): AssistantImageModelBinding?

    val activeRuntimeIdentity: ModelRuntimeIdentity?

    fun localVisionUnavailableMessage(state: MainUiState): String

    fun chatContextSnapshot(state: MainUiState, assistantId: String, fileContextEnabled: Boolean): ChatContextSnapshot

    fun isConfigurationActionError(event: GenerateEvent.Error): Boolean

    suspend fun withInlineImageDataForCloud(messages: List<ChatMessage>): List<ChatMessage>

    suspend fun withLocalImageFilesForVision(messages: List<ChatMessage>, createdFiles: MutableList<File>): List<ChatMessage>
}

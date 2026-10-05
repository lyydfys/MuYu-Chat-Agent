package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.Job
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.GenerationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicLong
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.engine.ModelRuntimeIdentity

/**
 * R3/C2（插队裁定 3）：自 MainViewModel 迁出的“运行期准入态”，由组合根持有；
 * VM 以 get/set 别名共享同一实例，owner 以构造注入读取——字段所有权离开 God object，
 * 但读写语义、初始化时点与调用点全部不变（零行为）。
 */
internal class RuntimeAdmissionState(
    val runtimeUserOverrideFields: MutableSet<String>
) {
    var activeRuntimeIdentity: ModelRuntimeIdentity? = null

    var activeModelForRuntimeProfile: ModelManifest? = null

    var activeAdaptiveRecommendation: AdaptiveTuningRecommendation? = null

    var activeVisionProjectorStamp: LoadedVisionProjectorStamp? = null

    @Volatile
    var activeImageGenerationJobId: String? = null

    @Volatile
    var activeImageGenerationBackend: ImageBackend? = null

    @Volatile
    var activeImageGenerationModelId: String? = null

    var imageGenerationJob: Job? = null

    @Volatile
    var imageLibraryBackupJob: Job? = null

    @Volatile
    var activeWebSearchDeferred: Deferred<WebSearchResult>? = null

    val imageFavoriteMutationSequence = AtomicLong(0L)

    val latestImageFavoriteMutations =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    val contentImportSelectionRevision = AtomicLong(0L)

    var conversationMutationBarrier: Job? = null

    var localConversationContextNeedsInvalidation = false

    val localConversationContextInvalidationSequence = AtomicLong(0L)

    var localConversationContextInvalidationJob: Job? = null

    var chatImagePromptBridgeJob: Job? = null

    val chatImagePromptBridgeGeneration = AtomicLong(0L)

    var pendingImageTranslation: PendingImageTranslation? = null
}

/** R3/C3-S9：`rejectWhileConversationMutationInProgress` 顶层化（体逐字等价，store/holder 参数化）。 */
internal fun rejectWhileConversationMutationInProgressInto(store: UiStateStore, admission: RuntimeAdmissionState): Boolean {
        if (admission.conversationMutationBarrier?.isActive != true) return false
        store.update {
            it.copy(statusMessage = "正在保存上一项对话修改，请稍候。")
        }
        return true
}

/** R3/C3-S9：`hasActiveAssistantImageExecution` 顶层化（纯函数，体逐字等价，state 参数化）。 */
internal fun hasActiveAssistantImageExecutionIn(state: MainUiState, chatSessionId: String?): Boolean {
        if (chatSessionId.isNullOrBlank()) return false
        val session = state.chatSessions.firstOrNull { it.id == chatSessionId }
        val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session?.messages.orEmpty()
        return messages.any { message ->
            message.generatedImageRequest?.let { request ->
                request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                    (request.status in setOf(
                        ChatGeneratedImageStatus.AWAITING_APPROVAL,
                        ChatGeneratedImageStatus.QUEUED,
                        ChatGeneratedImageStatus.GENERATING
                    ) || request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING)
            } == true
        }
}

/** R3/C3-S9：`markLocalConversationContextInvalid` 顶层化（体逐字等价；admission/scope/engine 参数化）。 */
internal fun markLocalConversationContextInvalidInto(admission: RuntimeAdmissionState, store: UiStateStore, scope: CoroutineScope, engine: McaInferenceService) {
        admission.localConversationContextNeedsInvalidation = true
        val sequence = admission.localConversationContextInvalidationSequence.incrementAndGet()
        // A durable deletion owns its own Room-then-KV barrier and must not be
        // cancelled by a later ordinary invalidation request.
        admission.localConversationContextInvalidationJob
            ?.takeUnless { it === admission.conversationMutationBarrier }
            ?.cancel()
        admission.localConversationContextInvalidationJob = scope.launch(Dispatchers.IO) {
            runCatching { engine.invalidateConversationContext() }
                .onSuccess {
                    if (admission.localConversationContextInvalidationSequence.get() == sequence) {
                        admission.localConversationContextNeedsInvalidation = false
                    }
                }
                .onFailure { error ->
                    if (admission.localConversationContextInvalidationSequence.get() == sequence) {
                        store.update {
                            it.copy(statusMessage = "上下文重置失败，下次本地生成前将重试：${error.message ?: "native runtime error"}")
                        }
                    }
                }
        }
}

/** R3/C3-S9：`loadModelGenerationParams` 顶层化（体逐字等价，profileStore 参数化）。 */
internal fun loadModelGenerationParamsFor(
    profileStore: ModelGenerationProfileStore,
    backend: ChatBackend,
    modelId: String?,
    defaults: GenerationParams
): GenerationParams = GenerationPreferences.loadModelGenerationParams(
    profileStore = profileStore,
    backend = backend,
    modelId = modelId,
    defaults = defaults
)

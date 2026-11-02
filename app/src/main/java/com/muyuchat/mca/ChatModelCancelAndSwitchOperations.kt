package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// R3-S25：聊天/图像取消与模型切换动作族自 MainViewModel 迁出（体逐字；依赖参数化）。

internal fun cancelImageGenerationForJobFor(
    scope: CoroutineScope,
    store: UiStateWriter<ImageJobCancellationFields>,
    admission: RuntimeAdmissionState,
    workerClient: LocalImageWorkerClient,
    activeWorkerRequestId: String?,
    expectedJobId: String?
) {
        scope.launch(Dispatchers.IO) {
            val jobId = admission.activeImageGenerationJobId
            if (expectedJobId != null && jobId != expectedJobId) return@launch
            val localGeneration = admission.activeImageGenerationBackend == ImageBackend.LOCAL
            if (jobId != null) {
                store.update { state ->
                    val job = state.imageJobs.firstOrNull { it.id == jobId }
                    if (job == null || job.status.terminal) return@update state
                    val message = if (localGeneration) {
                        "正在停止本地生图，等待 worker 释放本次执行"
                    } else {
                        "已停止图片生成"
                    }
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            if (localGeneration) {
                                ImageGenerationStatusRecord.CANCEL_REQUESTED
                            } else {
                                ImageGenerationStatusRecord.CANCELLED
                            },
                            message
                        ),
                        statusMessage = if (localGeneration) "正在停止本地生图…" else message
                    )
                }
                val nativeCancelRequested = if (localGeneration) {
                    workerClient.cancel(activeWorkerRequestId ?: jobId)
                } else {
                    false
                }
                if (!(localGeneration && nativeCancelRequested)) {
                    store.update { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        if (job?.status != ImageGenerationStatusRecord.CANCEL_REQUESTED) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.updateImageJob(
                                    jobId,
                                    ImageGenerationStatusRecord.CANCELLED,
                                    "已停止图片生成"
                                ),
                                statusMessage = "已停止图片生成"
                            )
                        }
                    }
                    admission.imageGenerationJob
                        ?.takeIf { admission.activeImageGenerationJobId == jobId }
                        ?.cancel()
                }
            } else {
                admission.imageGenerationJob?.cancel()
                store.update { state ->
                    val working = state.imageJobs.firstOrNull {
                        it.status == ImageGenerationStatusRecord.QUEUED ||
                            it.status == ImageGenerationStatusRecord.GENERATING ||
                            it.status == ImageGenerationStatusRecord.CANCEL_REQUESTED
                    }
                    if (working != null) {
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                working.id,
                                ImageGenerationStatusRecord.CANCELLED,
                                "已停止图片生成"
                            ),
                            statusMessage = "已停止图片生成"
                        )
                    } else {
                        state.copy(statusMessage = "当前没有正在生成的图片任务")
                    }
                }
            }
        }
    }

internal fun cancelChatImageGenerationFor(
    store: UiStatusWriter,
    findRequest: (String) -> Pair<String, ChatGeneratedImageRequest>?,
    rejectRequest: (String) -> Unit,
    cancelGenerationForJob: (String?) -> Unit,
    requestId: String
) {
        val owner = findRequest(requestId) ?: return
        val (chatSessionId, request) = owner
        val state = store.current
        if (state.activeChatSessionId != chatSessionId || !state.canWriteToActiveAssistantSession()) return
        if (request.status == ChatGeneratedImageStatus.AWAITING_APPROVAL) {
            rejectRequest(requestId)
            return
        }
        val jobId = request.currentJobId
        if (jobId.isNullOrBlank()) {
            store.showStatus("找不到这条图片任务的执行编号，请刷新聊天后重试。")
            return
        }
        cancelGenerationForJob(jobId)
    }

internal fun selectChatModelFor(
    store: UiStateReadPort,
    loadModel: (ModelManifest) -> Unit,
    selectCloudModel: (String?) -> Unit,
    choiceId: String
) {
        if (choiceId.startsWith(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX)) {
            selectCloudModel(choiceId.removePrefix(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX))
            return
        }
        if (choiceId == store.current.cloudApiConfig.chatChoiceId) {
            selectCloudModel(store.current.selectedCloudChatModelId)
            return
        }
        store.current.models.firstOrNull { it.id == choiceId }?.let(loadModel)
    }

internal fun selectCloudChatModelFor(
    store: UiStateWriter<ChatModelSelectionFields>,
    cloudApiStore: CloudApiStore,
    engine: McaInferenceService,
    scope: CoroutineScope,
    application: Application,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    modelId: String?
) {
        val model = store.current.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
        val config = model?.toChatConfig()?.normalized()
        if (config == null || !config.configured) {
            store.update {
                it.copy(statusMessage = "云端推理模型未配置。请到模型管理 > 云端 接入并保存对话推理模型。")
            }
            return
        }
        cloudApiStore.save(config)
        cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
        cloudApiStore.saveSelectedCloudChatModelId(model.id)
        val modelParams = loadModelGenerationParamsFor(
            modelGenerationProfileStore,
            backend = ChatBackend.CLOUD,
            modelId = model.id,
            defaults = store.current.params
        )
        GenerationPreferences.persistGenerationParams(application, modelParams)
        scope.launch(Dispatchers.IO) {
            engine.stopGeneration()
        }
        val sessionsToPersist = store.updateWithResult { fields ->
            val sessions = fields.chatSessions.bindSession(
                sessionId = fields.activeChatSessionId,
                assistantId = fields.selectedAssistantId,
                modelMode = ChatBackend.CLOUD.bindingValue(),
                modelId = model.id
            )
            fields.copy(
                cloudApiConfig = config,
                selectedCloudChatModelId = model.id,
                selectedChatBackend = ChatBackend.CLOUD,
                params = modelParams,
                chatSessions = sessions,
                busy = false,
                statusMessage = "已切换到云端模型：${config.safeDisplayName()}",
                tab = tabAfterLateModelLoad(fields.tab)
            ) to sessions
        }
        persistChatSessions(sessionsToPersist)
    }

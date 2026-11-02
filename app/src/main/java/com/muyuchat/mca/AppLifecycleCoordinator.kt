package com.muyuchat.mca

import android.app.Application
import android.content.Context
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.core.engine.McaInferenceService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 集中管理应用前后台生命周期切换、工作进程探活恢复与本地 API 状态校准。
 */
internal class AppLifecycleCoordinator(
    private val application: Application,
    private val uiStateStore: UiStateWriter<LocalApiSettingsFields>,
    private val scope: CoroutineScope,
    private val engine: McaInferenceService,
    private val chatRuns: ChatRunOwnership,
    private val localApiServerCoordinator: LocalApiServerCoordinator,
    private val offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    private val assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
    private val maybeCheckForAppUpdate: () -> Unit,
    val foregroundRecoverySequence: AtomicLong,
    private val getForegroundRecoveryJob: () -> Job?,
    private val setForegroundRecoveryJob: (Job?) -> Unit
) {
    companion object {
        private const val BACKGROUND_TASK_STATUS = "已转入后台运行，返回 MCA 后查看进度。"
    }

    private val viewModelScope: CoroutineScope = scope

    var foregroundRecoveryJob: Job?
        get() = getForegroundRecoveryJob()
        set(value) = setForegroundRecoveryJob(value)

    private val apiLifecycleRequestJob: Job?
        get() = localApiServerCoordinator.apiLifecycleRequestJob

    private val apiLifecycleSequence: AtomicLong
        get() = localApiServerCoordinator.apiLifecycleSequence

    private suspend fun applyLocalApiState(
        operation: Long,
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ): LocalApiApplyResult? = localApiServerCoordinator.applyLocalApiState(
        operation = operation,
        enabled = enabled,
        restEnabled = restEnabled,
        failurePrefix = failurePrefix
    )

    private fun loadApiPreferences(context: Context): LocalApiPreferences =
        GenerationPreferences.loadApiPreferences(context)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Application> getApplication(): T = application as T

    fun interruptForegroundRecovery() {
        foregroundRecoverySequence.incrementAndGet()
        foregroundRecoveryJob?.cancel()
    }

    fun onAppForegrounded() {
        // A file removed or replaced while backgrounded must restore the download action.
        viewModelScope.launch { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() }
        chatRuns.foreground()
        // R3/S22: 前后台写入经 UiStateWriter<LocalApiSettingsFields>。
        uiStateStore.update { state ->
            if (state.statusMessage != BACKGROUND_TASK_STATUS) {
                state
            } else {
                val imageActive = state.imageJobs.any { job -> !job.status.terminal }
                state.copy(
                    statusMessage = if (state.isGenerating || imageActive) {
                        "已回到前台，任务仍在运行。"
                    } else {
                        null
                    }
                )
            }
        }
        maybeCheckForAppUpdate()
        assistantImageContinuationCoordinator.resumePendingAssistantImageToolTurnIfReady()
        val recovery = foregroundRecoverySequence.incrementAndGet()
        foregroundRecoveryJob?.cancel()
        foregroundRecoveryJob = viewModelScope.launch(Dispatchers.IO) {
            // Construction-time recovery and process-foreground recovery share
            // the same serialized operation. Waiting for an already queued
            // request avoids the old start -> cancel -> stop -> start sequence
            // that could stop LocalApiForegroundService before Android delivered
            // its first onStartCommand callback.
            apiLifecycleRequestJob?.let { pending ->
                if (pending !== coroutineContext[Job]) pending.join()
            }
            if (recovery != foregroundRecoverySequence.get()) return@launch
            val preferences = loadApiPreferences(getApplication<Application>())
            val apiOperation = apiLifecycleSequence.incrementAndGet()
            val apiResult = applyLocalApiState(
                operation = apiOperation,
                enabled = preferences.apiEnabled,
                restEnabled = preferences.restEnabled,
                failurePrefix = "本机 API 恢复失败"
            ) ?: return@launch
            if (recovery != foregroundRecoverySequence.get()) return@launch

            val stateBeforeProbe = uiStateStore.current
            val canProbeWorker = stateBeforeProbe.loadedModelId != null &&
                !stateBeforeProbe.busy &&
                !stateBeforeProbe.isGenerating &&
                stateBeforeProbe.engineLifecycle !in setOf(
                    AgentEngineLifecycle.LOADING,
                    AgentEngineLifecycle.RELOADING,
                    AgentEngineLifecycle.ROLLING_BACK,
                    AgentEngineLifecycle.STOPPING
                )
            val health = if (canProbeWorker) {
                engine.tryRuntimeHealthSnapshot()
            } else {
                null
            }
            if (recovery != foregroundRecoverySequence.get()) return@launch

            uiStateStore.update { state ->
                if (recovery != foregroundRecoverySequence.get()) {
                    state
                } else {
                    val healthStillCurrent = health != null &&
                        state.loadedModelId == stateBeforeProbe.loadedModelId &&
                        !state.busy &&
                        !state.isGenerating &&
                        engine.stats.value == health.runtimeStats
                    val workerSessionLost = healthStillCurrent &&
                        health.workerSessionLost
                    val status = when {
                        apiResult.failure != null -> state.statusMessage
                        workerSessionLost && state.loadedModelId != null ->
                            "本地推理进程已被系统回收，将在下一次发送时自动恢复模型。"
                        else -> state.statusMessage
                    }
                    state.copy(
                        apiEnabled = apiResult.running,
                        restEnabled = apiResult.restEnabled,
                        engineLifecycle = if (workerSessionLost && state.loadedModelId != null) {
                            AgentEngineLifecycle.UNLOADED
                        } else {
                            state.engineLifecycle
                        },
                        statusMessage = status
                    )
                }
            }
            if (recovery == foregroundRecoverySequence.get()) {
                assistantImageContinuationCoordinator.resumePendingAssistantImageToolTurnIfReady()
            }
        }
    }
}

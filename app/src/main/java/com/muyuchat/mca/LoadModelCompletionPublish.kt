package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.presentation.afterGenerationTerminated
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.core.tuning.BootstrapLoadCanaryPolicy
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.engine.LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
import kotlinx.coroutines.CancellationException
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation

/**
 * T2B22-T3（b 工位）拆片：runLoadModelBody 内 L520-611 的完成发布段（markLoaded/
 * admission/推荐/session 绑定/单调发布 store.update/persistChatSessions）逐字节平移为
 * 段函数；壳内以登记 glue 调用（执行顺序不变）。
 */

/**
 * T2B22-T3（b 工位）拆片：完成发布段自 LoadModelOperations.kt 逐字节平移；壳内以登记 glue 调用。
 */
internal suspend fun publishLoadedModelStateFor(
    store: UiStateWriter<RuntimeProfileFields>,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    modelStore: ModelStoreRepository,
    model: ModelManifest,
    adaptive: AdaptiveTuningRecommendation,
    advisor: AgentAdvisor,
    device: DeviceProfile,
    runtimeProfileStore: ModelRuntimeProfileStore,
    identity: ModelRuntimeIdentity,
    managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    cloudApiStore: CloudApiStore,
    memoryAdmission: LocalModelMemoryAdmission,
    visionReadinessWarning: String?,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit
) {
        modelStore.markLoaded(model.id)
        admission.activeAdaptiveRecommendation = adaptive
        val recommendation = runCatching {
            advisor.recommend(
                device = device,
                localModels = listOf(model),
                remoteFiles = emptyList(),
                preference = store.current.preference
            )
        }.getOrNull()
        val profileState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
        val pendingTransaction = runtimeProfileStore.pendingTransaction(identity.identityHash)
        val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()
        val visionReadyAfterLoad = runCatching {
            JSONObject(nativeStatsAfterLoad).optBoolean("visionReady", false)
        }.getOrDefault(false)
        val logsAfterLoad = engine.recentEngineLogsOrEmpty()
        managedRuntimeReadinessRefreshGate.invalidate()
        val modelsAfterLoad = modelStore.listModels()
        val statsAfterLoad = engine.stats.value
        val executionProfileAfterLoad = engine.activeExecutionProfile()
        cloudApiStore.saveSelectedBackend(ChatBackend.LOCAL)
        val sessionsToPersist = store.updateWithResult { state ->
            val sessions = state.chatSessions.bindSession(
                sessionId = state.activeChatSessionId,
                assistantId = state.selectedAssistantId,
                modelMode = ChatBackend.LOCAL.bindingValue(),
                modelId = model.id
            )
            state.copy(
                loadedModelId = model.id,
                loadedModelName = model.displayName,
                selectedChatBackend = ChatBackend.LOCAL,
                models = modelsAfterLoad,
                chatSessions = sessions,
                busy = false,
                stats = statsAfterLoad,
                engineLifecycle = AgentEngineLifecycle.READY,
                autoTuningInProgress = false,
                deviceProfile = device,
                agentRecommendation = recommendation ?: state.agentRecommendation,
                profileId = executionProfileAfterLoad?.profileId,
                revision = executionProfileAfterLoad?.revision,
                profileRecordState = if (profileState?.activeProfile != null) {
                    AgentProfileRecordState.COMMITTED
                } else {
                    AgentProfileRecordState.NONE
                },
                verification = profileState?.activeProfile?.verificationLevel
                    ?.let(::agentVerification)
                    ?: AgentProfileVerification.SAFE,
                pendingProfile = pendingTransaction?.let { pending ->
                    AgentPendingProfile(
                        profileId = pending.pendingProfile.profileId,
                        revision = pending.pendingProfile.revision,
                        summary = "${pending.journal.stage} · ${pending.pendingProfile.resolvedLoadSignature.take(12)}",
                        readyToApply = pending.journal.state in setOf(
                            TuningJournalState.STAGED.name,
                            TuningJournalState.VALIDATING.name
                        )
                    )
                },
                rollbackProfile = profileState?.pointers?.rollbackTargetProfileId?.let { targetId ->
                    AgentRollbackProfile(targetId, null, "事务锁定的稳定回退目标", true)
                },
                tuningJobState = profileState?.activeJob?.let(::agentJobState)
                    ?: if (pendingTransaction != null) AgentTuningJobState.VALIDATING else AgentTuningJobState.IDLE,
                tuningPhase = pendingTransaction?.journal?.stage,
                tuningCandidateProgress = AgentCandidateProgress(),
                logs = logsAfterLoad,
                nativeStatsJson = nativeStatsAfterLoad,
                statusMessage = buildString {
                    append("已加载：").append(model.displayName)
                    if (memoryAdmission.mode == LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP) {
                        append("，稀疏 MoE mmap 模式已启用")
                    }
                    if (visionReadyAfterLoad) {
                        append("，本地视觉组件已就绪")
                    } else if (visionReadinessWarning != null) {
                        // A failed projector must disable image input, not discard a
                        // perfectly usable text runner. Vision readiness is an
                        // independent capability from chat-model readiness.
                        append("。图片输入未启用：").append(visionReadinessWarning)
                    }
                    if (visionReadinessWarning == null || visionReadyAfterLoad) {
                        append("。安全基线和正确性校准通过，可直接聊天；性能调优可在 Agent 页单独启动。")
                    } else {
                        append("。安全基线和正确性校准通过，可继续文字聊天；图片输入需修复投影器后再用。性能调优可在 Agent 页单独启动。")
                    }
                },
                // A model loaded from the DSH ball must not eject the user from DSH; the DSH
                // page (and its host) stays composed and the gateway refresh makes the model
                // serveable in place. The video workspace gets the same guarantee: an in-flight
                // video run must keep its page. Other entry points keep their chat landing.
                tab = tabAfterLateModelLoad(state.tab)
            ) to sessions
        }
    persistChatSessions(sessionsToPersist)
}

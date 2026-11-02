@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import com.muyuchat.core.designsystem.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.SmoothRightToLeftPage
import com.muyuchat.core.designsystem.mcaEnterReveal
import com.muyuchat.core.designsystem.McaIconSize

private const val MAX_VISIBLE_BENCHMARK_HISTORY = 10
internal const val MIN_CUSTOM_CONTEXT_LENGTH = 128
internal const val MAX_CUSTOM_CONTEXT_LENGTH = 1_048_576
private const val MIN_CONTEXT_SHORTCUT_EXPONENT = 7
private const val MAX_CONTEXT_SHORTCUT_EXPONENT = 20
internal const val CONTEXT_SHORTCUT_STEP_COUNT =
    MAX_CONTEXT_SHORTCUT_EXPONENT - MIN_CONTEXT_SHORTCUT_EXPONENT + 1

internal data class ContextLengthInputValidation(
    val value: Int? = null,
    val error: String? = null
) {
    val isValid: Boolean
        get() = value != null && error == null
}

internal fun validateContextLengthInput(raw: String): ContextLengthInputValidation {
    val normalized = raw.trim()
    if (normalized.isEmpty()) {
        return ContextLengthInputValidation(error = "请输入上下文长度")
    }
    val parsed = normalized.toLongOrNull()
        ?: return ContextLengthInputValidation(error = "请输入完整整数")
    if (parsed !in MIN_CUSTOM_CONTEXT_LENGTH.toLong()..MAX_CUSTOM_CONTEXT_LENGTH.toLong()) {
        return ContextLengthInputValidation(
            error = "可用范围为 $MIN_CUSTOM_CONTEXT_LENGTH–$MAX_CUSTOM_CONTEXT_LENGTH"
        )
    }
    return ContextLengthInputValidation(value = parsed.toInt())
}

internal fun contextLengthShortcutValue(step: Int): Int =
    1 shl (MIN_CONTEXT_SHORTCUT_EXPONENT + step.coerceIn(0, CONTEXT_SHORTCUT_STEP_COUNT - 1))

internal fun nearestContextLengthShortcutStep(value: Int): Int {
    val clamped = value.coerceIn(MIN_CUSTOM_CONTEXT_LENGTH, MAX_CUSTOM_CONTEXT_LENGTH)
    var bestStep = 0
    var bestDistance = Long.MAX_VALUE
    repeat(CONTEXT_SHORTCUT_STEP_COUNT) { step ->
        val distance = kotlin.math.abs(contextLengthShortcutValue(step).toLong() - clamped.toLong())
        if (distance < bestDistance) {
            bestStep = step
            bestDistance = distance
        }
    }
    return bestStep
}

@Immutable
data class AgentUiState(
    val deviceProfile: DeviceProfile? = null,
    val recommendation: AgentRecommendation? = null,
    val benchmark: BenchmarkResult? = null,
    val benchmarkHistory: List<BenchmarkHistoryItem> = emptyList(),
    val preference: UserPreference = UserPreference(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val loadedModelName: String? = null,
    val lastAutoTuningSummary: String? = null,
    val localStabilitySmokeSummary: String? = null,
    val tuningTrials: List<TuningTrialItem> = emptyList(),
    val agentDecisionHistory: List<AgentDecisionItem> = emptyList(),
    val params: GenerationParams = GenerationParams(),
    val profileId: String? = null,
    val revision: Long? = null,
    val profileRecordState: AgentProfileRecordState = AgentProfileRecordState.NONE,
    val verification: AgentProfileVerification = AgentProfileVerification.UNKNOWN,
    val engineLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.UNKNOWN,
    val tuningJobState: AgentTuningJobState = AgentTuningJobState.IDLE,
    val reloadRequired: Boolean = false,
    val pending: AgentPendingProfile? = null,
    val rollback: AgentRollbackProfile? = null,
    val etaSeconds: Long? = null,
    val phase: String? = null,
    val candidateProgress: AgentCandidateProgress = AgentCandidateProgress()
)

enum class AgentProfileRecordState(val label: String) {
    NONE("未建立"),
    STAGED("候选已暂存"),
    COMMITTED("已提交"),
    REJECTED("已拒绝")
}

enum class AgentProfileVerification(val label: String) {
    UNKNOWN("未验证"),
    SAFE("安全基线"),
    COMPATIBLE("本机兼容"),
    DEVICE_VERIFIED("正式双入口通过")
}

enum class AgentEngineLifecycle(val label: String) {
    UNKNOWN("状态未知"),
    UNLOADED("未加载"),
    LOADING("加载中"),
    READY("可推理"),
    GENERATING("生成中"),
    STOPPING("停止中"),
    RELOADING("重新加载中"),
    ROLLING_BACK("回滚中"),
    ERROR("运行异常")
}

enum class AgentTuningJobState(val label: String) {
    IDLE("无任务"),
    QUEUED("等待开始"),
    RUNNING("调优中"),
    PAUSED("已暂停"),
    CANCELING("取消中"),
    VALIDATING("验证候选"),
    SUCCEEDED("调优完成"),
    FAILED("调优失败"),
    RECOVERING("恢复中")
}

@Immutable
data class AgentPendingProfile(
    val profileId: String? = null,
    val revision: Long? = null,
    val summary: String? = null,
    val readyToApply: Boolean = false
)

@Immutable
data class AgentRollbackProfile(
    val targetProfileId: String? = null,
    val targetRevision: Long? = null,
    val summary: String? = null,
    val available: Boolean = false
)

@Immutable
data class AgentCandidateProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val currentCandidate: String? = null,
    val passed: Int = 0,
    val rejected: Int = 0
) {
    val fraction: Float
        get() = if (total > 0) completed.toFloat().div(total).coerceIn(0f, 1f) else 0f
}

enum class AgentTuningMode(val label: String) {
    QUICK("快速"),
    STANDARD("标准"),
    DEEP("深度"),
    POWER_SAVE("省电")
}

private enum class AgentInfoDialog {
    GUIDE,
    RESULT
}

@Immutable
data class BenchmarkHistoryItem(
    val timeText: String,
    val modelName: String,
    val decodeTps: Double,
    val ttftMs: Long,
    val nCtx: Int,
    val nThreads: Int,
    val stable: Boolean
)

@Immutable
data class TuningTrialItem(
    val threads: Int,
    val decodeTps: Double,
    val ttftMs: Long,
    val genTokens: Int,
    val stable: Boolean,
    val selected: Boolean
)

@Immutable
data class AgentDecisionItem(
    val timeText: String,
    val title: String,
    val detail: String
)

@Composable
fun AgentScreen(
    state: AgentUiState,
    onScan: () -> Unit,
    onPreferenceChange: (UserPreference) -> Unit,
    onApplyRecommendation: () -> Unit,
    onBenchmark: () -> Unit,
    onQuickDebug: () -> Unit,
    onStandardDebug: () -> Unit,
    onDeepDebug: () -> Unit,
    onPowerDebug: () -> Unit,
    onAgentInfo: () -> Unit,
    onParamsChange: (GenerationParams) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onStabilitySmoke: (() -> Unit)? = null,
    onStartTuning: ((AgentTuningMode, Boolean) -> Unit)? = null,
    onPauseTuning: (() -> Unit)? = null,
    onResumeTuning: (() -> Unit)? = null,
    onCancelTuning: (() -> Unit)? = null,
    onQueryTuningJob: (() -> Unit)? = null,
    onApplyPendingProfile: (() -> Unit)? = null,
    onDiscardPendingProfile: (() -> Unit)? = null,
    onRollbackProfile: (() -> Unit)? = null
) {
    var infoDialog by rememberSaveable { mutableStateOf<AgentInfoDialog?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val pageResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val mainContentActive = pageResumed && infoDialog == null
    val infoContentActive = pageResumed && infoDialog != null
    val presentationHolder = remember { AgentPagePresentationHolder() }
    val recommendation = state.recommendation
    val tuningPlan = recommendation?.tuningPlan
    val benchmark = state.benchmark
    // W9: recommendation/candidate copy and row text are derived once per recommendation value, so
    // a runtime/progress state update does not re-format every visible card.
    val recommendationUi = remember(recommendation) {
        recommendation?.let(::recommendationCardUi)
    }
    val candidateUis = remember(recommendation) {
        // Distinct by model id: the row key must be unique for LazyColumn, and the advisor's
        // candidate source can merge local and remote profiles.
        recommendation?.candidates?.take(5)
            ?.map(::candidateCardUi)
            ?.distinctBy { it.modelId }
            .orEmpty()
    }
    val presentations = remember(
        mainContentActive,
        state.benchmarkHistory,
        state.agentDecisionHistory,
        benchmark,
        state.tuningTrials,
        state.lastAutoTuningSummary,
        tuningPlan?.nThreads,
        tuningPlan?.reason,
        recommendation?.explanation
    ) {
        if (mainContentActive) {
            presentationHolder.refresh(
                benchmarkHistory = state.benchmarkHistory.take(MAX_VISIBLE_BENCHMARK_HISTORY),
                decisions = state.agentDecisionHistory.take(10),
                currentModelReport = AgentCurrentModelReportKey(
                    benchmark = benchmark,
                    autoTuningSummary = state.lastAutoTuningSummary
                ),
                diagnostics = benchmark?.let { currentBenchmark ->
                    AgentDiagnosticsKey(
                        benchmark = currentBenchmark,
                        trials = state.tuningTrials.toList(),
                        fallbackThreadCount = tuningPlan?.nThreads ?: 0,
                        recommendationReason = tuningPlan?.reason,
                        recommendationExplanation = recommendation?.explanation
                    )
                }
            )
        } else {
            presentationHolder.current
        }
    }
    val infoResult = remember(
        infoContentActive,
        infoDialog,
        benchmark,
        tuningPlan?.nCtx,
        tuningPlan?.nPredict,
        tuningPlan?.nThreads,
        tuningPlan?.reason,
        recommendation?.explanation
    ) {
        when {
            infoDialog == null -> null
            infoContentActive -> presentationHolder.refreshInfoResult(
                if (benchmark != null && recommendation != null && tuningPlan != null) {
                    AgentInfoResultKey(
                        benchmark = benchmark,
                        nCtx = tuningPlan.nCtx,
                        nPredict = tuningPlan.nPredict,
                        nThreads = tuningPlan.nThreads,
                        recommendationReason = tuningPlan.reason,
                        explanation = recommendation.explanation
                    )
                } else {
                    null
                }
            )
            else -> presentationHolder.currentInfoResult
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AgentHeader(
                    isBusy = state.isBusy,
                    statusMessage = state.statusMessage,
                    onBack = onBack,
                    onScan = onScan,
                    onBenchmark = onBenchmark
                )
            }

            state.deviceProfile?.let { profile ->
                item { HardwareOverview(profile) }
            }

            item {
                // Stable callback instance: keeps SmartDebugCard skippable while runtime progress
                // updates recompose the page.
                val onAgentInfoClick = remember(
                    benchmark == null,
                    recommendation == null,
                    onAgentInfo
                ) {
                    {
                        onAgentInfo()
                        infoDialog = if (benchmark == null || recommendation == null) {
                            AgentInfoDialog.GUIDE
                        } else {
                            AgentInfoDialog.RESULT
                        }
                    }
                }
                SmartDebugCard(
                    state = SmartDebugCardUi(
                        preference = state.preference,
                        isBusy = state.isBusy,
                        loadedModelName = state.loadedModelName,
                        hasBenchmark = benchmark != null,
                        hasRecommendation = recommendation != null,
                        localStabilitySmokeSummary = state.localStabilitySmokeSummary
                    ),
                    onPreferenceChange = onPreferenceChange,
                    onQuickDebug = onQuickDebug,
                    onStandardDebug = onStandardDebug,
                    onDeepDebug = onDeepDebug,
                    onPowerDebug = onPowerDebug,
                    onStabilitySmoke = onStabilitySmoke,
                    onAgentInfo = onAgentInfoClick
                )
            }

            item {
                CurrentModelReportCard(
                    loadedModelName = state.loadedModelName,
                    presentation = presentations.currentModelReport
                )
            }

            item {
                RuntimeProfileCard(
                    state = state,
                    onStartTuning = onStartTuning,
                    onPauseTuning = onPauseTuning,
                    onResumeTuning = onResumeTuning,
                    onCancelTuning = onCancelTuning,
                    onQueryTuningJob = onQueryTuningJob,
                    onApplyPendingProfile = onApplyPendingProfile,
                    onDiscardPendingProfile = onDiscardPendingProfile,
                    onRollbackProfile = onRollbackProfile
                )
            }

            recommendationUi?.let { ui ->
                item(key = "agent-recommendation") {
                    Box(
                        modifier = Modifier
                            .animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                            .mcaEnterReveal(index = 0)
                    ) {
                        RecommendationCard(
                            ui = ui,
                            onApplyRecommendation = onApplyRecommendation
                        )
                    }
                }
            }

            if (benchmark != null) {
                presentations.diagnostics?.let { diagnostics ->
                    item { DebugSummaryCard(diagnostics.summary) }
                    item { DebugDetailsCard(diagnostics.details) }
                }
            }

            if (candidateUis.isNotEmpty()) {
                item(key = "agent-candidates-header") { SectionTitle("候选模型") }
                itemsIndexed(candidateUis, key = { _, candidate -> candidate.modelId }) { index, candidate ->
                    Box(
                        modifier = Modifier
                            .animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                            .mcaEnterReveal(index = index)
                    ) {
                        CandidateCard(candidate)
                    }
                }
            }

            if (state.benchmarkHistory.isNotEmpty()) {
                item {
                    BenchmarkHistoryCard(
                        history = presentations.benchmarkHistoryRows,
                        totalCount = state.benchmarkHistory.size
                    )
                }
            }

            if (state.agentDecisionHistory.isNotEmpty()) {
                item {
                    AgentDecisionHistoryCard(presentations.decisionRows)
                }
            }

            item {
                AdvancedParamsCard(
                    params = state.params,
                    onParamsChange = onParamsChange
                )
            }
        }

        SmoothRightToLeftPage(
            visible = infoDialog != null,
            onDismiss = { infoDialog = null }
        ) { pageModifier, closePage ->
            AgentInfoPage(
                type = infoDialog ?: AgentInfoDialog.GUIDE,
                result = infoResult,
                onBack = closePage,
                modifier = pageModifier
            )
        }
    }
}

@Composable
private fun AgentInfoPage(
    type: AgentInfoDialog,
    result: AgentInfoResultPresentation?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isGuide = type == AgentInfoDialog.GUIDE
    val confirmInteraction = remember { MutableInteractionSource() }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                McaIconButton(
                    onClick = onBack,
                    contentDescription = "返回智能调参",
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(McaIcons.Back, contentDescription = null)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isGuide) "调参说明" else "调试解释", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("智能调参的工作方式与本次结果说明", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isGuide) {
                        Text("智能调参会根据本机 SoC、核心数、内存、温控、当前模型和实测 token/s 推荐参数。")
                        Text("智能调试只有一个入口：规则包负责参数搜索和安全阈值，当前已加载模型负责解释调试结果。")
                        Text("没有加载模型时，可以先查看体检和推荐；加载模型后才能运行快速、深度或省电调试。")
                    } else if (result == null) {
                        Text("当前还没有可解释的调试结果。请先运行快速调试、深度调试或省电调试。")
                    } else {
                        Text(result.speedText)
                        Text(result.planText)
                        Text(result.reasonText)
                        Text("深度调试会做多轮验证，并采用较优稳定速度；省电调试会明显偏向低线程和低发热。")
                    }
                }
            }
        }
        item {
            McaButton(style = McaButtonStyle.Primary,
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                interactionSource = confirmInteraction,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("知道了")
            }
        }
    }
}

@Composable
private fun AgentHeader(
    isBusy: Boolean,
    statusMessage: String?,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onBenchmark: () -> Unit
) {
    val scanTextInteraction = remember { MutableInteractionSource() }
    val scanButtonInteraction = remember { MutableInteractionSource() }
    val benchmarkButtonInteraction = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                McaIconButton(
                    onClick = onBack,
                    contentDescription = "返回聊天",
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(McaIcons.Close, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        McaIcons.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(McaIconSize.M)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("智能调参", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("本机体检、测速和参数优化", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            McaButton(style = McaButtonStyle.Text,
                onClick = onScan,
                enabled = !isBusy,
                interactionSource = scanTextInteraction
            ) {
                Icon(McaIcons.Refresh, contentDescription = null, modifier = Modifier.size(McaIconSize.Xs))
                Spacer(modifier = Modifier.width(4.dp))
                Text("体检")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            McaButton(style = McaButtonStyle.Primary,
                onClick = onScan,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f),
                interactionSource = scanButtonInteraction,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(McaIcons.AutoFixHigh, contentDescription = null)
                Text("体检", modifier = Modifier.padding(start = 6.dp))
            }
            McaButton(style = McaButtonStyle.Outlined,
                onClick = onBenchmark,
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f),
                interactionSource = benchmarkButtonInteraction,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(McaIcons.Speed, contentDescription = null)
                Text("测速", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.designsystem.SmoothRightToLeftPage

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

data class AgentPendingProfile(
    val profileId: String? = null,
    val revision: Long? = null,
    val summary: String? = null,
    val readyToApply: Boolean = false
)

data class AgentRollbackProfile(
    val targetProfileId: String? = null,
    val targetRevision: Long? = null,
    val summary: String? = null,
    val available: Boolean = false
)

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

data class BenchmarkHistoryItem(
    val timeText: String,
    val modelName: String,
    val decodeTps: Double,
    val ttftMs: Long,
    val nCtx: Int,
    val nThreads: Int,
    val stable: Boolean
)

data class TuningTrialItem(
    val threads: Int,
    val decodeTps: Double,
    val ttftMs: Long,
    val genTokens: Int,
    val stable: Boolean,
    val selected: Boolean
)

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
                SmartDebugCard(
                    state = state,
                    onPreferenceChange = onPreferenceChange,
                    onQuickDebug = onQuickDebug,
                    onStandardDebug = onStandardDebug,
                    onDeepDebug = onDeepDebug,
                    onPowerDebug = onPowerDebug,
                    onStabilitySmoke = onStabilitySmoke,
                    onAgentInfo = {
                        onAgentInfo()
                        infoDialog = if (state.benchmark == null || state.recommendation == null) {
                            AgentInfoDialog.GUIDE
                        } else {
                            AgentInfoDialog.RESULT
                        }
                    }
                )
            }

            item { CurrentModelReportCard(state) }

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

            state.recommendation?.let { recommendation ->
                item {
                    RecommendationCard(
                        recommendation = recommendation,
                        onApplyRecommendation = onApplyRecommendation
                    )
                }
            }

            state.benchmark?.let { benchmark ->
                item { DebugSummaryCard(state, benchmark) }
                item { DebugDetailsCard(state, benchmark) }
            }

            state.recommendation?.let { recommendation ->
                if (recommendation.candidates.isNotEmpty()) {
                    item { SectionTitle("候选模型") }
                    items(recommendation.candidates.take(5)) { candidate ->
                        CandidateCard(candidate)
                    }
                }
            }

            if (state.benchmarkHistory.isNotEmpty()) {
                item {
                    BenchmarkHistoryCard(
                        history = state.benchmarkHistory.take(MAX_VISIBLE_BENCHMARK_HISTORY),
                        totalCount = state.benchmarkHistory.size
                    )
                }
            }

            if (state.agentDecisionHistory.isNotEmpty()) {
                item {
                    AgentDecisionHistoryCard(state.agentDecisionHistory.take(10))
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
                state = state,
                onBack = closePage,
                modifier = pageModifier
            )
        }
    }
}

@Composable
private fun AgentInfoPage(
    type: AgentInfoDialog,
    state: AgentUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isGuide = type == AgentInfoDialog.GUIDE
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回智能调参")
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
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isGuide) {
                        Text("智能调参会根据本机 SoC、核心数、内存、温控、当前模型和实测 token/s 推荐参数。")
                        Text("智能调试只有一个入口：规则包负责参数搜索和安全阈值，当前已加载模型负责解释调试结果。")
                        Text("没有加载模型时，可以先查看体检和推荐；加载模型后才能运行快速、深度或省电调试。")
                    } else {
                        val benchmark = state.benchmark
                        val recommendation = state.recommendation
                        if (benchmark == null || recommendation == null) {
                            Text("当前还没有可解释的调试结果。请先运行快速调试、深度调试或省电调试。")
                        } else {
                            Text("本次实测速度 ${"%.2f".format(benchmark.decodeTps)} token/s，TTFT ${benchmark.ttftMs}ms。")
                            Text("推荐线程 ${recommendation.tuningPlan.nThreads}，上下文 ${recommendation.tuningPlan.nCtx}，回复长度 ${recommendation.tuningPlan.nPredict}。")
                            Text(recommendation.tuningPlan.reason.ifBlank { recommendation.explanation })
                            Text("深度调试会做多轮验证，并采用较优稳定速度；省电调试会明显偏向低线程和低发热。")
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(48.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "返回聊天")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = "智能调参",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("智能调参", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("本机体检、测速和参数优化", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onScan, enabled = !isBusy) {
                Icon(Icons.Default.Refresh, contentDescription = "体检", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("体检")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onScan, enabled = !isBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = "体检")
                Text("体检", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = onBenchmark, enabled = !isBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Speed, contentDescription = "测速")
                Text("测速", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

package com.muyuchat.feature.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.muyuchat.core.advisor.AgentCandidate
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAX_VISIBLE_BENCHMARK_HISTORY = 10
internal const val MIN_CUSTOM_CONTEXT_LENGTH = 128
internal const val MAX_CUSTOM_CONTEXT_LENGTH = 1_048_576
private const val MIN_CONTEXT_SHORTCUT_EXPONENT = 7
private const val MAX_CONTEXT_SHORTCUT_EXPONENT = 20
private const val CONTEXT_SHORTCUT_STEP_COUNT =
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
private fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = 240),
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = tween(durationMillis = 140)),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            fun closeWithMotion() {
                scope.launch {
                    offsetX.animateTo(
                        targetValue = widthPx,
                        animationSpec = tween(durationMillis = 180)
                    )
                    onDismiss()
                }
            }

            BackHandler(enabled = visible) {
                closeWithMotion()
            }

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithMotion)
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

@Composable
private fun HardwareOverview(profile: DeviceProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                title = "移动平台",
                value = socText(profile.socFamily.name),
                detail = "型号：${socDisplayName(profile)}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "核心",
                value = "${profile.estimatedBigCores}/${profile.cpuCores}",
                detail = "大核 / 总核心",
                modifier = Modifier.weight(1f)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("系统可用内存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(profile.availableRamBytes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "标称/总内存 ${formatBytes(profile.displayTotalRamBytes)} · 运行预算 ${formatBytes(profile.modelMemoryBudgetBytes)} · 温度 ${temperatureText(profile.batteryTemperatureC)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val progress = if (profile.displayTotalRamBytes > 0) {
                    (profile.availableRamBytes.toFloat() / profile.displayTotalRamBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreferencePicker(
    preference: UserPreference,
    onPreferenceChange: (UserPreference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("运行偏好")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(PerformanceMode.PowerSave, PerformanceMode.Balanced, PerformanceMode.Speed).forEach { mode ->
                FilterChip(
                    selected = preference.mode == mode,
                    onClick = { onPreferenceChange(preference.copy(mode = mode)) },
                    label = { Text(mode.label) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(PerformanceMode.Quality, PerformanceMode.LongContext).forEach { mode ->
                FilterChip(
                    selected = preference.mode == mode,
                    onClick = { onPreferenceChange(preference.copy(mode = mode)) },
                    label = { Text(mode.label) }
                )
            }
        }
    }
}

@Composable
private fun SmartDebugCard(
    state: AgentUiState,
    onPreferenceChange: (UserPreference) -> Unit,
    onQuickDebug: () -> Unit,
    onStandardDebug: () -> Unit,
    onDeepDebug: () -> Unit,
    onPowerDebug: () -> Unit,
    onStabilitySmoke: (() -> Unit)?,
    onAgentInfo: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, contentDescription = "智能调试", tint = MaterialTheme.colorScheme.primary)
                    Text("智能调试", fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "智能调试",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "当前模型：${shortName(state.loadedModelName) ?: "尚未加载"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "规则包负责实测调参，当前模型负责解释调试结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("调试目标")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(PerformanceMode.PowerSave, PerformanceMode.Balanced, PerformanceMode.Speed).forEach { mode ->
                        FilterChip(
                            selected = state.preference.mode == mode,
                            onClick = { onPreferenceChange(state.preference.copy(mode = mode)) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(PerformanceMode.Quality, PerformanceMode.LongContext).forEach { mode ->
                        FilterChip(
                            selected = state.preference.mode == mode,
                            onClick = { onPreferenceChange(state.preference.copy(mode = mode)) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onQuickDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("快速调试")
                }
                OutlinedButton(
                    onClick = onStandardDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("标准调试")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDeepDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("深度调试")
                }
                OutlinedButton(
                    onClick = onPowerDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("省电调试")
                }
            }
            TextButton(onClick = onAgentInfo, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.benchmark == null || state.recommendation == null) "调参说明" else "调试解释")
            }
            OutlinedButton(
                onClick = { onStabilitySmoke?.invoke() },
                enabled = onStabilitySmoke != null && !state.isBusy && state.loadedModelName != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("稳定性自检")
            }
            state.localStabilitySmokeSummary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CurrentModelReportCard(state: AgentUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = "体检", tint = MaterialTheme.colorScheme.primary)
                Text("当前模型体检", fontWeight = FontWeight.Bold)
            }
            Text(shortName(state.loadedModelName) ?: "尚未加载模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val benchmark = state.benchmark
            if (benchmark == null) {
                Text("加载模型后会自动短基准；也可以手动重测。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("速度 ${"%.2f".format(benchmark.decodeTps)} token/s") })
                    AssistChip(onClick = {}, label = { Text("首 token ${benchmark.ttftMs}ms") })
                    AssistChip(onClick = {}, label = { Text(if (benchmark.stable) "稳定" else "需排查") })
                }
                state.lastAutoTuningSummary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RuntimeProfileCard(
    state: AgentUiState,
    onStartTuning: ((AgentTuningMode, Boolean) -> Unit)?,
    onPauseTuning: (() -> Unit)?,
    onResumeTuning: (() -> Unit)?,
    onCancelTuning: (() -> Unit)?,
    onQueryTuningJob: (() -> Unit)?,
    onApplyPendingProfile: (() -> Unit)?,
    onDiscardPendingProfile: (() -> Unit)?,
    onRollbackProfile: (() -> Unit)?
) {
    var selectedMode by rememberSaveable { mutableStateOf(AgentTuningMode.QUICK) }
    var autoApply by rememberSaveable { mutableStateOf(false) }
    val jobState = state.tuningJobState
    val pending = state.pending
    val rollback = state.rollback
    val controlsConnected = listOf(
        onStartTuning,
        onPauseTuning,
        onResumeTuning,
        onCancelTuning,
        onQueryTuningJob,
        onApplyPendingProfile,
        onDiscardPendingProfile,
        onRollbackProfile
    ).any { it != null }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("运行配置与调优任务", fontWeight = FontWeight.Bold)
                Text(
                    "Profile、引擎和任务是三套独立状态；Activity 重建只查询同一任务，不会把候选误判为失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RuntimeStatusLine(
                label = "当前 Profile",
                value = buildString {
                    append(shortIdentifier(state.profileId) ?: "尚未建立")
                    state.revision?.let { append(" · r$it") }
                }
            )
            RuntimeStatusLine(
                label = "记录 / 验证",
                value = "${state.profileRecordState.label} · ${state.verification.label}"
            )
            RuntimeStatusLine(
                label = "引擎 / 任务",
                value = "${state.engineLifecycle.label} · ${jobState.label}"
            )

            if (state.reloadRequired) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "模型执行参数已变化，需要应用候选并重新加载。助手或会话的生成参数不会触发重载。",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            pending?.let { value ->
                RuntimeStatusLine(
                    label = "待应用候选",
                    value = buildString {
                        append(shortIdentifier(value.profileId) ?: "已暂存")
                        value.revision?.let { append(" · r$it") }
                        value.summary?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                    }
                )
            }
            rollback?.takeIf { it.available }?.let { value ->
                RuntimeStatusLine(
                    label = "回滚目标",
                    value = buildString {
                        append(shortIdentifier(value.targetProfileId) ?: "上一个稳定配置")
                        value.targetRevision?.let { append(" · r$it") }
                        value.summary?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                    }
                )
            }

            val progress = state.candidateProgress
            if (state.phase != null || state.etaSeconds != null || progress.total > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        buildString {
                            append(state.phase?.takeIf(String::isNotBlank) ?: jobState.label)
                            state.etaSeconds?.takeIf { it >= 0L }?.let { append(" · 预计剩余 ${formatEta(it)}") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            buildString {
                                append("候选 ${progress.completed.coerceIn(0, progress.total)}/${progress.total}")
                                progress.currentCandidate?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                                append(" · 通过 ${progress.passed} · 拒绝 ${progress.rejected}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("调优模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    AgentTuningMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            enabled = !jobState.isActive && !state.isBusy,
                            label = { Text(mode.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("通过后自动应用", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "默认关闭；关闭时先暂存候选，再由你点击“应用并重载”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoApply,
                        onCheckedChange = { autoApply = it },
                        enabled = !jobState.isActive && !state.isBusy
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onStartTuning?.invoke(selectedMode, autoApply) },
                    enabled = onStartTuning != null && !state.isBusy && state.loadedModelName != null && jobState.canStart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("开始调优")
                }
                OutlinedButton(
                    onClick = { onPauseTuning?.invoke() },
                    enabled = onPauseTuning != null &&
                        jobState.canPauseSearch(pending?.readyToApply == true),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("暂停")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onResumeTuning?.invoke() },
                    enabled = onResumeTuning != null &&
                        jobState.canResumeSearch(pending?.readyToApply == true),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("续跑")
                }
                OutlinedButton(
                    onClick = { onCancelTuning?.invoke() },
                    enabled = onCancelTuning != null &&
                        jobState.canCancelSearch(pending?.readyToApply == true),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (jobState == AgentTuningJobState.CANCELING) "取消中" else "取消任务")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onApplyPendingProfile?.invoke() },
                    enabled = onApplyPendingProfile != null &&
                        pending?.readyToApply == true &&
                        !state.isBusy &&
                        jobState.canApplyPendingWhenIdle,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("应用并重载")
                }
                OutlinedButton(
                    onClick = { onDiscardPendingProfile?.invoke() },
                    enabled = onDiscardPendingProfile != null && pending?.readyToApply == true && !state.isBusy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("撤销修改")
                }
            }
            OutlinedButton(
                onClick = { onRollbackProfile?.invoke() },
                enabled = onRollbackProfile != null && rollback?.available == true && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("回滚稳定配置")
            }
            TextButton(
                onClick = { onQueryTuningJob?.invoke() },
                enabled = onQueryTuningJob != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查询任务状态")
            }
            Text(
                if (controlsConnected) {
                    "模型忙碌时仍可查询或取消任务；会改变模型生命周期的操作会等待安全边界。"
                } else {
                    "任务控制面尚未接入；状态字段与操作入口已保留，接入后无需改动页面结构。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RuntimeStatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.36f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(0.64f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecommendationCard(
    recommendation: AgentRecommendation,
    onApplyRecommendation: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = "推荐", tint = MaterialTheme.colorScheme.primary)
                    Text("推荐参数", fontWeight = FontWeight.Bold)
                }
                RiskBadge(recommendation.risk.name)
            }
            Text(recommendation.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("上下文 ${recommendation.tuningPlan.nCtx}") })
                AssistChip(onClick = {}, label = { Text("线程 ${recommendation.tuningPlan.nThreads}") })
                AssistChip(onClick = {}, label = { Text("回复 ${recommendation.tuningPlan.nPredict}") })
            }
            if (recommendation.tuningPlan.reason.isNotBlank()) {
                Text(recommendation.tuningPlan.reason, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onApplyRecommendation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("应用参数")
            }
        }
    }
}

@Composable
private fun RiskBadge(label: String) {
    Surface(
        color = when (label.lowercase()) {
            "low" -> Color(0xFFE6F4EA)
            "medium" -> Color(0xFFFFF4D7)
            else -> Color(0xFFFCE8E6)
        },
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "风险 ${riskText(label)}",
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = when (label.lowercase()) {
                "low" -> Color(0xFF137333)
                "medium" -> Color(0xFFB06000)
                else -> Color(0xFFC5221F)
            }
        )
    }
}

@Composable
private fun CandidateCard(candidate: AgentCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(shortName(candidate.model.displayName) ?: "候选模型", fontWeight = FontWeight.Bold)
            Text("${sourceText(candidate.model.source)} · ${candidate.model.parametersB ?: "?"}B · ${candidate.model.quant ?: "未知量化"} · 匹配 ${candidate.score}")
            Text("预计 ${candidate.expectedDecodeTpsRange} · ${candidate.memoryRisk}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(candidate.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BenchmarkCard(benchmark: BenchmarkResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("测速结果")
            Text("首 token ${benchmark.ttftMs} ms · 速度 ${"%.2f".format(benchmark.decodeTps)} token/s · 总速 ${"%.2f".format(benchmark.e2eTps)} token/s")
            val rssKb = benchmark.processRssKb.takeIf { it > 0L } ?: benchmark.nativePssKb
            Text(
                "输出 ${benchmark.genTokens} · RSS ${formatBytes(rssKb * 1024)} · PSS ${formatBytes(benchmark.nativePssKb * 1024)} · 系统可用 ${formatBytes(benchmark.availMemKb * 1024)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            benchmark.error?.let { Text("问题：$it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DebugSummaryCard(state: AgentUiState, benchmark: BenchmarkResult) {
    val bestThread = benchmark.bestThreadCount.takeIf { it > 0 }
        ?: state.recommendation?.tuningPlan?.nThreads
        ?: 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("本次调试")
            Text(
                if (state.tuningTrials.isNotEmpty()) {
                    "已完成 ${state.tuningTrials.size} 轮线程测试，最佳 ${bestThread} 线程。"
                } else {
                    "已完成短基准测试。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("速度 ${"%.2f".format(benchmark.decodeTps)} token/s") })
                AssistChip(onClick = {}, label = { Text("TTFT ${benchmark.ttftMs}ms") })
            }
            val rssKb = benchmark.processRssKb.takeIf { it > 0L } ?: benchmark.nativePssKb
            Text(
                "输出 ${benchmark.genTokens} token · 内存 ${formatBytes(rssKb * 1024)} · 温控变化 ${benchmark.thermalDelta}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            benchmark.error?.let { Text("问题：$it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DebugDetailsCard(state: AgentUiState, benchmark: BenchmarkResult) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("调试详情")
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "查看")
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (!expanded) {
                Text(
                    state.recommendation?.tuningPlan?.reason
                        ?: "展开后查看线程扫描和调参解释。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (state.tuningTrials.isNotEmpty()) {
                Text("线程扫描", fontWeight = FontWeight.SemiBold)
                state.tuningTrials.forEach { trial ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "threads=${trial.threads}${if (trial.selected) " · 最佳" else ""}",
                            fontWeight = if (trial.selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            "${"%.2f".format(trial.decodeTps)} token/s",
                            color = if (trial.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (trial.selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    Text(
                        "TTFT ${trial.ttftMs}ms · 输出 ${trial.genTokens} · ${if (trial.stable) "稳定" else "异常"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text("短基准", fontWeight = FontWeight.SemiBold)
                Text(
                    "速度 ${"%.2f".format(benchmark.decodeTps)} token/s · TTFT ${benchmark.ttftMs}ms · decode ${benchmark.decodeMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.recommendation?.let { recommendation ->
                Text("调参解释", fontWeight = FontWeight.SemiBold)
                Text(
                    recommendation.tuningPlan.reason.ifBlank { recommendation.explanation },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdvancedParamsCard(
    params: GenerationParams,
    onParamsChange: (GenerationParams) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val advancedResult = remember(params.advancedJson) {
        LlamaAdvancedParams.parse(params.advancedJson)
    }
    val advanced = advancedResult.params
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("参数分层", fontWeight = FontWeight.Bold)
                    Text(
                        "模型执行配置与助手/会话生成配置独立保存，不再共用同一份参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "执行：上下文 ${params.nCtx} · 线程 ${params.nThreads} · 模板 ${params.chatTemplateMode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "生成：最大输出 ${params.nPredict} · 温度 ${"%.2f".format(params.temperature)} · top_p ${"%.2f".format(params.topP)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!expanded) return@Column

            ParameterLayerTitle(
                title = "模型执行参数（加载 / 热执行）",
                description = "只属于当前模型、设备和 runtime。加载字段变化会创建待应用候选；热执行字段仅在 runtime 明确支持时原位生效。"
            )
            ParameterSubsectionTitle(
                title = "加载参数",
                description = "上下文、batch、KV、GPU/MoE、MTP、mmap/mlock 等会进入加载签名，修改后需要受控重载。"
            )
            ContextLengthEditor(params.nCtx) {
                onParamsChange(params.copy(nCtx = it))
            }
            Text(
                "下列结构化控件是 llama.cpp 专属字段；MNN、QAIRT 等 runtime 必须由各自适配器过滤，禁止跨 runtime 透传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (advanced == null) {
                Text(
                    "高级 JSON 格式无效，请先在下方修正后再使用结构化控件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                AdvancedChoiceChips(
                    title = "逻辑批大小",
                    selected = (advanced.nBatch ?: 512).toString(),
                    choices = listOf("256", "512", "1024", "2048")
                ) { raw ->
                    val batch = raw.toInt()
                    val currentUbatch = advanced.nUbatch ?: 256
                    updateAdvanced(
                        advanced.copy(
                            nBatch = batch,
                            nUbatch = currentUbatch.coerceAtMost(batch)
                        )
                    )
                }
                val effectiveBatch = advanced.nBatch ?: 512
                AdvancedChoiceChips(
                    title = "物理批大小",
                    selected = (advanced.nUbatch ?: 256).toString(),
                    choices = listOf(32, 64, 128, 256, 512, 1024, 2048)
                        .filter { it <= effectiveBatch && effectiveBatch % it == 0 }
                        .map(Int::toString)
                ) { raw -> updateAdvanced(advanced.copy(nUbatch = raw.toInt())) }
                AdvancedChoiceChips(
                    title = "K Cache",
                    selected = advanced.cacheTypeK ?: "f16",
                    choices = listOf("f16", "q8_0", "q4_0")
                ) { updateAdvanced(advanced.copy(cacheTypeK = it)) }
                AdvancedChoiceChips(
                    title = "V Cache",
                    selected = advanced.cacheTypeV ?: "f16",
                    choices = listOf("f16", "q8_0", "q4_0")
                ) { updateAdvanced(advanced.copy(cacheTypeV = it)) }
                AdvancedChoiceChips(
                    title = "Flash Attention",
                    selected = advanced.flashAttn ?: "auto",
                    choices = listOf("auto", "on", "off")
                ) { updateAdvanced(advanced.copy(flashAttn = it)) }
                AdvancedChoiceChips(
                    title = "推测解码",
                    selected = advanced.specType ?: "none",
                    choices = listOf("none", "draft-mtp")
                ) { updateAdvanced(advanced.copy(specType = it)) }
                if (advanced.specType == "draft-mtp") {
                    CompactIntSlider(
                        "MTP 最大草稿 token",
                        advanced.specDraftNMax ?: 2,
                        0f..8f
                    ) { updateAdvanced(advanced.copy(specDraftNMax = it)) }
                }
                AdvancedChoiceChips(
                    title = "并行序列",
                    selected = (advanced.nParallel ?: 1).toString(),
                    choices = listOf("1")
                ) { updateAdvanced(advanced.copy(nParallel = it.toInt())) }
                Text(
                    "当前 Android JNI 为单会话执行器，并行序列固定为 1。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AdvancedChoiceChips(
                    title = "GPU 层",
                    selected = (advanced.nGpuLayers ?: 0).toString(),
                    choices = listOf("0", "-1", "-2", "999"),
                    labels = mapOf("0" to "CPU", "-1" to "自动", "-2" to "全部", "999" to "999")
                ) { updateAdvanced(advanced.copy(nGpuLayers = it.toInt())) }
                CompactIntSlider(
                    "主 GPU",
                    advanced.mainGpu ?: 0,
                    0f..3f
                ) { updateAdvanced(advanced.copy(mainGpu = it)) }
                AdvancedChoiceChips(
                    title = "拆分模式",
                    selected = advanced.splitMode ?: "none",
                    choices = listOf("none", "layer", "row", "tensor")
                ) { updateAdvanced(advanced.copy(splitMode = it)) }
                CompactIntSlider(
                    "CPU MoE 层",
                    advanced.nCpuMoe ?: 0,
                    0f..64f
                ) { updateAdvanced(advanced.copy(nCpuMoe = it)) }
                Text(
                    "GPU/MoE 控件记录的是请求值；是否支持及最终有效值必须以当前 APK 注册的 backend 与 Native 回读为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AdvancedBooleanChips("性能统计", advanced.perf ?: true) {
                    updateAdvanced(advanced.copy(perf = it))
                }
                AdvancedBooleanChips("内存映射 mmap", advanced.mmap ?: true) {
                    updateAdvanced(advanced.copy(mmap = it))
                }
                AdvancedBooleanChips("锁定内存 mlock", advanced.mlock ?: false) {
                    updateAdvanced(advanced.copy(mlock = it))
                }
            }

            ParameterSubsectionTitle(
                title = "热执行参数",
                description = "线程与缓存复用只有在当前 runtime 策略声明 HOT_EXECUTION 时才可原位应用；否则仍会进入待重载候选。"
            )
            CompactIntSlider("运行线程 n_threads", params.nThreads, 1f..16f) {
                onParamsChange(params.copy(nThreads = it))
            }
            if (advanced != null) {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                CompactIntSlider(
                    "批处理线程 n_threads_batch",
                    advanced.nThreadsBatch ?: params.nThreads,
                    1f..16f
                ) { updateAdvanced(advanced.copy(nThreadsBatch = it)) }
                AdvancedChoiceChips(
                    title = "前缀缓存复用 cache_reuse",
                    selected = (advanced.cacheReuse ?: 0).toString(),
                    choices = listOf("0", "128", "256", "512")
                ) { updateAdvanced(advanced.copy(cacheReuse = it.toInt())) }
            }

            ParameterSubsectionTitle(
                title = "模板与执行正确性 gate",
                description = "模板/Jinja 会改变 role、prompt 与特殊 token。每次变化必须重跑完整正确性验证，不能进入纯性能搜索。"
            )
            OutlinedTextField(
                value = params.chatTemplateMode,
                onValueChange = { onParamsChange(params.copy(chatTemplateMode = it.ifBlank { "auto" })) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("对话模板模式 chat_template_mode") }
            )
            if (advanced != null) {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                AdvancedBooleanChips("Jinja 模板 use_jinja", advanced.useJinja ?: true) {
                    updateAdvanced(advanced.copy(useJinja = it))
                }
            }
            Text(
                "cache_reuse 同样影响正确性，候选必须验证缓存命中与未命中回答等价。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (advancedResult.issues.isNotEmpty()) {
                advancedResult.errorMessages.forEach { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedTextField(
                value = params.advancedJson,
                onValueChange = { onParamsChange(params.copy(advancedJson = it.ifBlank { "{}" })) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Runtime 高级 JSON（当前模型专属）") },
                supportingText = { Text("未知或不支持字段必须隔离，不得直接透传 native。") }
            )

            ParameterLayerTitle(
                title = "助手 / 会话生成参数",
                description = "sampling、系统提示词和最大输出只影响生成，不触发模型重载。智能调参只能给出建议，绝不会静默改写用户设置。"
            )
            CompactIntSlider("最大输出 n_predict", params.nPredict, 128f..65536f) {
                onParamsChange(params.copy(nPredict = it))
            }
            CompactFloatSlider("创造性 temperature", params.temperature, 0f..2f) {
                onParamsChange(params.copy(temperature = it))
            }
            CompactFloatSlider("回答聚焦度 top_p", params.topP, 0.1f..1f) {
                onParamsChange(params.copy(topP = it))
            }
            CompactIntSlider("候选数量 top_k", params.topK, 0f..200f) {
                onParamsChange(params.copy(topK = it))
            }
            CompactFloatSlider("最小概率 min_p", params.minP, 0f..1f) {
                onParamsChange(params.copy(minP = it))
            }
            CompactFloatSlider("重复惩罚", params.repeatPenalty, 1.0f..1.3f) {
                onParamsChange(params.copy(repeatPenalty = it))
            }
            CompactFloatSlider("存在惩罚", params.presencePenalty, -2f..2f) {
                onParamsChange(params.copy(presencePenalty = it))
            }
            CompactFloatSlider("频率惩罚", params.frequencyPenalty, -2f..2f) {
                onParamsChange(params.copy(frequencyPenalty = it))
            }
            OutlinedTextField(
                value = params.systemPrompt,
                onValueChange = { onParamsChange(params.copy(systemPrompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("系统提示词（助手 / 会话）") },
                supportingText = { Text("保存到助手或会话生成层，不写入模型执行 Profile。") }
            )
        }
    }
}

@Composable
private fun ParameterLayerTitle(title: String, description: String) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ParameterSubsectionTitle(title: String, description: String) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AdvancedChoiceChips(
    title: String,
    selected: String,
    choices: List<String>,
    labels: Map<String, String> = emptyMap(),
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        choices.chunked(4).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowChoices.forEach { choice ->
                    FilterChip(
                        selected = selected == choice,
                        onClick = { onSelected(choice) },
                        label = { Text(labels[choice] ?: choice) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedBooleanChips(
    title: String,
    value: Boolean,
    onValue: (Boolean) -> Unit
) {
    AdvancedChoiceChips(
        title = title,
        selected = if (value) "on" else "off",
        choices = listOf("on", "off"),
        labels = mapOf("on" to "开启", "off" to "关闭")
    ) { onValue(it == "on") }
}

@Composable
private fun CompactIntSlider(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title：$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onValue(it.toInt()) },
            valueRange = range
        )
    }
}

@Composable
private fun ContextLengthEditor(
    value: Int,
    onValue: (Int) -> Unit
) {
    var exactText by remember { mutableStateOf(value.toString()) }
    val validation = validateContextLengthInput(exactText)

    LaunchedEffect(value) {
        if (validation.value != value) exactText = value.toString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = exactText,
            onValueChange = { input ->
                exactText = input
                validateContextLengthInput(input).value?.let { parsed ->
                    if (parsed != value) onValue(parsed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = validation.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("上下文长度 n_ctx（精确值）") },
            supportingText = {
                Text(
                    validation.error
                        ?: "精确值会原样保存；llama.cpp 如需内部分配对齐，不会改写这里的逻辑值。"
                )
            }
        )
        Text(
            "快捷值：${contextLengthShortcutValue(nearestContextLengthShortcutStep(value))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = nearestContextLengthShortcutStep(value).toFloat(),
            onValueChange = { position ->
                val shortcut = contextLengthShortcutValue(position.roundToInt())
                exactText = shortcut.toString()
                if (shortcut != value) onValue(shortcut)
            },
            valueRange = 0f..(CONTEXT_SHORTCUT_STEP_COUNT - 1).toFloat(),
            steps = CONTEXT_SHORTCUT_STEP_COUNT - 2
        )
        Text(
            "滑杆提供 128、256、512…1048576 的 2 倍快捷值；也可在上方输入范围内任意整数，例如 8190。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompactFloatSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title：${"%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range
        )
    }
}

@Composable
private fun BenchmarkHistoryCard(history: List<BenchmarkHistoryItem>, totalCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("历史测速")
            Text(
                "仅显示最近 ${history.size} 条 / 共 $totalCount 条，完整记录可在日志中导出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            history.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${item.timeText} · ${shortName(item.modelName)}", fontWeight = FontWeight.Medium)
                    Text(
                        "速度 ${"%.2f".format(item.decodeTps)} token/s · 首 token ${item.ttftMs}ms · 上下文 ${item.nCtx} · 线程 ${item.nThreads} · ${if (item.stable) "稳定" else "异常"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentDecisionHistoryCard(history: List<AgentDecisionItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("调参记录")
            Text(
                "最近 ${history.size} 条决策记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            history.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${item.timeText} · ${item.title}", fontWeight = FontWeight.Medium)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}

private fun shortName(value: String?): String? =
    value?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.let { if (it.length > 32) it.take(29) + "..." else it }

private fun shortIdentifier(value: String?): String? =
    value?.takeIf { it.isNotBlank() }?.let {
        if (it.length > 20) "${it.take(9)}…${it.takeLast(7)}" else it
    }

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600L -> "${seconds / 3600L}小时${(seconds % 3600L) / 60L}分"
    seconds >= 60L -> "${seconds / 60L}分${seconds % 60L}秒"
    else -> "${seconds}秒"
}

private val AgentTuningJobState.isActive: Boolean
    get() = when (this) {
        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.RECOVERING -> true

        AgentTuningJobState.IDLE,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> false
    }

/**
 * A staged candidate deliberately uses PAUSED while it waits for explicit user
 * approval. After process recreation the same persisted pending transaction is
 * surfaced as VALIDATING even though no validation coroutine is running. Busy
 * remains the concurrency guard; these idle states must therefore keep the
 * explicit "apply and reload" action available.
 */
internal val AgentTuningJobState.canApplyPendingWhenIdle: Boolean
    get() = when (this) {
        AgentTuningJobState.IDLE,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> true

        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.RECOVERING -> false
    }

private val AgentTuningJobState.canStart: Boolean
    get() = this == AgentTuningJobState.IDLE ||
        this == AgentTuningJobState.SUCCEEDED ||
        this == AgentTuningJobState.FAILED

private val AgentTuningJobState.canPause: Boolean
    get() = this == AgentTuningJobState.RUNNING || this == AgentTuningJobState.VALIDATING

internal fun AgentTuningJobState.canPauseSearch(hasReadyPendingProfile: Boolean): Boolean =
    canPause && !hasReadyPendingProfile

private val AgentTuningJobState.canResume: Boolean
    get() = this == AgentTuningJobState.PAUSED

internal fun AgentTuningJobState.canResumeSearch(hasReadyPendingProfile: Boolean): Boolean =
    canResume && !hasReadyPendingProfile

private val AgentTuningJobState.canCancel: Boolean
    get() = when (this) {
        AgentTuningJobState.QUEUED,
        AgentTuningJobState.RUNNING,
        AgentTuningJobState.PAUSED,
        AgentTuningJobState.VALIDATING,
        AgentTuningJobState.RECOVERING -> true

        AgentTuningJobState.IDLE,
        AgentTuningJobState.CANCELING,
        AgentTuningJobState.SUCCEEDED,
        AgentTuningJobState.FAILED -> false
    }

internal fun AgentTuningJobState.canCancelSearch(hasReadyPendingProfile: Boolean): Boolean =
    canCancel && !hasReadyPendingProfile

private fun riskText(label: String): String = when (label.lowercase()) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "blocked" -> "阻止"
    else -> "未知"
}

private fun sourceText(source: String): String = when (source.lowercase()) {
    "local" -> "本机"
    "modelscope" -> "魔塔"
    else -> source.ifBlank { "未知来源" }
}

private fun socText(value: String): String = when (value.lowercase()) {
    "snapdragon" -> "骁龙"
    "dimensity" -> "天玑"
    "exynos" -> "Exynos"
    "tensor" -> "Tensor"
    "kirin" -> "麒麟"
    else -> "未知平台"
}

private fun socDisplayName(profile: DeviceProfile): String {
    val raw = profile.accelerationProfile.chipsetCode.ifBlank {
        profile.socModel.ifBlank { profile.socManufacturer }
    }
    return DeviceAccelerationAnalyzer.publicChipsetDisplayName(raw, profile.socFamily)
}

private fun thermalText(value: String): String = when (value.lowercase()) {
    "none" -> "正常"
    "light" -> "轻微发热"
    "moderate" -> "偏热"
    "severe" -> "较热"
    "critical", "emergency", "shutdown" -> "过热"
    else -> "未知"
}

private fun temperatureText(value: Float?): String =
    value?.let { "%.1f°C".format(it) } ?: "未知"

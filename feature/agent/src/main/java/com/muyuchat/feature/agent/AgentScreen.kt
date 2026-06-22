package com.muyuchat.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.advisor.AgentCandidate
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference

private const val MAX_VISIBLE_BENCHMARK_HISTORY = 10

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
    val tuningTrials: List<TuningTrialItem> = emptyList(),
    val agentDecisionHistory: List<AgentDecisionItem> = emptyList(),
    val params: GenerationParams = GenerationParams()
)

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
    onDeepDebug: () -> Unit,
    onPowerDebug: () -> Unit,
    onAgentInfo: () -> Unit,
    onParamsChange: (GenerationParams) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var infoDialog by rememberSaveable { mutableStateOf<AgentInfoDialog?>(null) }

    infoDialog?.let { dialog ->
        AgentInfoDialog(
            type = dialog,
            state = state,
            onDismiss = { infoDialog = null }
        )
    }

    LazyColumn(
        modifier = modifier
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
                onDeepDebug = onDeepDebug,
                onPowerDebug = onPowerDebug,
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
}

@Composable
private fun AgentInfoDialog(
    type: AgentInfoDialog,
    state: AgentUiState,
    onDismiss: () -> Unit
) {
    val isGuide = type == AgentInfoDialog.GUIDE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isGuide) "调参说明" else "调试解释",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text("知道了")
            }
        }
    )
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
    onDeepDebug: () -> Unit,
    onPowerDebug: () -> Unit,
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
                    onClick = onDeepDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("深度调试")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onPowerDebug,
                    enabled = !state.isBusy && state.loadedModelName != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("省电调试")
                }
                TextButton(
                    onClick = onAgentInfo,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.benchmark == null || state.recommendation == null) "调参说明" else "调试解释")
                }
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
                    Text("高级参数", fontWeight = FontWeight.Bold)
                    Text(
                        "智能调试和手动调节共用同一份参数。",
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
            Text(
                "上下文 ${params.nCtx} · 回复 ${params.nPredict} · 线程 ${params.nThreads} · 创造性 ${"%.2f".format(params.temperature)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!expanded) return@Column

            CompactIntSlider("上下文长度", params.nCtx, 1024f..65536f) {
                onParamsChange(params.copy(nCtx = it))
            }
            CompactIntSlider("回复长度", params.nPredict, 128f..65536f) {
                onParamsChange(params.copy(nPredict = it))
            }
            CompactIntSlider("运行线程", params.nThreads, 1f..16f) {
                onParamsChange(params.copy(nThreads = it))
            }
            CompactFloatSlider("创造性", params.temperature, 0f..2f) {
                onParamsChange(params.copy(temperature = it))
            }
            CompactFloatSlider("回答聚焦度", params.topP, 0.1f..1f) {
                onParamsChange(params.copy(topP = it))
            }
            CompactFloatSlider("重复惩罚", params.repeatPenalty, 1.0f..1.3f) {
                onParamsChange(params.copy(repeatPenalty = it))
            }
            CompactFloatSlider("频率惩罚", params.frequencyPenalty, 0f..1f) {
                onParamsChange(params.copy(frequencyPenalty = it))
            }
            OutlinedTextField(
                value = params.systemPrompt,
                onValueChange = { onParamsChange(params.copy(systemPrompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("系统提示词") }
            )
            OutlinedTextField(
                value = params.advancedJson,
                onValueChange = { onParamsChange(params.copy(advancedJson = it.ifBlank { "{}" })) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("高级 JSON") }
            )
        }
    }
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
    val raw = profile.socLabel.ifBlank { profile.socModel }.ifBlank { profile.socManufacturer }
    if (raw.isBlank() || raw.equals("Unknown", ignoreCase = true)) return socText(profile.socFamily.name)
    return raw
        .replace("Qualcomm", "高通")
        .replace("Snapdragon", "骁龙")
        .replace("MediaTek", "联发科")
        .replace("Dimensity", "天玑")
        .replace(Regex("\\s+"), " ")
        .trim()
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

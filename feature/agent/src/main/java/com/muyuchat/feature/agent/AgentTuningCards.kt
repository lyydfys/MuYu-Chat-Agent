@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference

@Composable
internal fun SmartDebugCard(
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
internal fun CurrentModelReportCard(state: AgentUiState) {
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
internal fun RuntimeProfileCard(
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
internal fun RecommendationCard(
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
internal fun CandidateCard(candidate: AgentCandidate) {
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

private fun shortIdentifier(value: String?): String? =
    value?.takeIf { it.isNotBlank() }?.let {
        if (it.length > 20) "${it.take(9)}…${it.takeLast(7)}" else it
    }

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600L -> "${seconds / 3600L}小时${(seconds % 3600L) / 60L}分"
    seconds >= 60L -> "${seconds / 60L}分${seconds % 60L}秒"
    else -> "${seconds}秒"
}

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

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile

@Composable
internal fun HardwareOverview(profile: DeviceProfile) {
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
internal fun DebugSummaryCard(state: AgentUiState, benchmark: BenchmarkResult) {
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
internal fun DebugDetailsCard(state: AgentUiState, benchmark: BenchmarkResult) {
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
internal fun BenchmarkHistoryCard(history: List<BenchmarkHistoryItem>, totalCount: Int) {
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
internal fun AgentDecisionHistoryCard(history: List<AgentDecisionItem>) {
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

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
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

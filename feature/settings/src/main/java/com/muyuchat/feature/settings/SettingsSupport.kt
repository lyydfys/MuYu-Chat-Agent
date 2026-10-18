package com.muyuchat.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale

@Composable
internal fun ExpandableSettingsCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize(McaMotion.sizeChangePx),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExpandableSettingsHeader(
                title = title,
                summary = summary,
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) }
            )
            // Size is owned by animateContentSize above; this only fades the expanded content.
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = McaMotion.fade()),
                exit = fadeOut(animationSpec = McaMotion.fade())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable
internal fun ExpandableSettingsInlineSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .animateContentSize(McaMotion.sizeChangePx)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpandableSettingsHeader(
            title = title,
            summary = summary,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) }
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = McaMotion.fade()),
            exit = fadeOut(animationSpec = McaMotion.fade())
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ExpandableSettingsHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Draw-phase rotation: the arrow never re-composes while the spring runs.
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = McaMotion.sizeChange,
        label = "settings-expand-arrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Accessibility: the two-line header makes this row a full-width click target; keep it
            // at least 44dp tall without changing typography or horizontal density.
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .mcaPressScale(interactionSource),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
internal fun HelpTextBlock(
    title: String,
    primary: String,
    secondary: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(primary, style = MaterialTheme.typography.bodySmall)
        Text(
            secondary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun WebSearchDiagnosticUiItem.toEvidenceText(): String =
    buildString {
        appendLine("MCA 联网检索诊断")
        appendLine("状态: ${if (success) "成功" else "未完成"}")
        appendLine("服务: $providerLabel")
        appendLine("触发: $triggerModeLabel")
        appendLine("时间: $createdAtText")
        appendLine("耗时: ${elapsedMs}ms")
        appendLine("来源数: $sourceCount")
        if (cacheStatus.isNotBlank()) {
            appendLine("缓存: $cacheStatus")
        }
        if (closedLoopChecks.isNotEmpty()) {
            appendLine("闭环证据:")
            closedLoopChecks.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (triggerReasons.isNotEmpty()) {
            appendLine("触发依据:")
            triggerReasons.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (warnings.isNotEmpty()) {
            appendLine("检索警告:")
            warnings.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (healthLabel.isNotBlank()) {
            appendLine("检索健康: $healthLabel ($healthScore/100)")
            if (healthReasons.isNotEmpty()) {
                appendLine("健康依据: ${healthReasons.joinToString("；")}")
            }
        }
        val troubleshootingAdvice = webSearchTroubleshootingAdvice()
        if (troubleshootingAdvice.isNotEmpty()) {
            appendLine("处理建议:")
            troubleshootingAdvice.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (qualityLabel.isNotBlank()) {
            appendLine("资料质量: $qualityLabel ($qualityScore/100)")
            if (sourceTrustSummary.isNotEmpty()) {
                appendLine("来源类型: ${sourceTrustSummary.joinToString("；")}")
            }
            if (qualityReasons.isNotEmpty()) {
                appendLine("质量依据: ${qualityReasons.joinToString("；")}")
            }
        }
        if (researchConfidenceLabel.isNotBlank()) {
            appendLine("研究置信度: $researchConfidenceLabel ($researchConfidenceScore/100)")
            if (researchEvidenceGroups.isNotEmpty()) {
                appendLine("证据分组: ${researchEvidenceGroups.joinToString("；")}")
            }
            if (researchConflictWarnings.isNotEmpty()) {
                appendLine("冲突/不确定性: ${researchConflictWarnings.joinToString("；")}")
            }
            if (researchSynthesisGuidance.isNotEmpty()) {
                appendLine("综合建议: ${researchSynthesisGuidance.joinToString("；")}")
            }
        }
        appendLine("问题: ${query.ifBlank { "空问题" }}")
        appendLine("结果: $message")
        if (searchedQueries.isNotEmpty()) {
            appendLine("检索词:")
            searchedQueries.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (directUrls.isNotEmpty()) {
            appendLine("网页:")
            directUrls.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (topSources.isNotEmpty()) {
            appendLine("来源:")
            topSources.forEachIndexed { index, source ->
                appendLine("${index + 1}. ${source.title.ifBlank { source.url }}")
                appendLine("   URL: ${source.url}")
                if (source.trustLabel.isNotBlank() || source.hostLabel.isNotBlank()) {
                    appendLine("   类型: ${listOf(source.trustLabel, source.hostLabel).filter { it.isNotBlank() }.joinToString(" · ")}")
                }
                if (source.provider.isNotBlank()) appendLine("   Provider: ${source.provider}")
                if (source.snippet.isNotBlank()) appendLine("   摘要: ${source.snippet}")
            }
        }
    }.trim()

@Composable
internal fun InfoCard(title: String, primary: String, secondary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(primary)
            if (secondary.isNotBlank()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun formatKb(kb: Long): String {
    val gb = kb / 1024.0 / 1024.0
    val mb = kb / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}

internal fun shortName(value: String): String =
    value.substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { value }
        .let { if (it.length > 32) it.take(29) + "..." else it }

internal fun friendlyError(error: String): String = when {
    "no backends are loaded" in error -> "引擎后端没有初始化，请重启 APP 或重新加载模型。"
    "connection abort" in error.lowercase() -> "网络连接中断，可稍后继续下载。"
    "out of memory" in error.lowercase() || "oom" in error.lowercase() -> "内存不足，建议换小模型或降低上下文。"
    "not gguf" in error.lowercase() -> "文件不是可加载的 GGUF 模型。"
    else -> error.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(77) + "..." else it }
}

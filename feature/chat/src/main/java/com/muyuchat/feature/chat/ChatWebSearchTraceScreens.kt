package com.muyuchat.feature.chat

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S7：从 ChatChromeScreens.kt 等值移出的 Web 搜索追踪/来源展示簇。
// 原样逐字迁移，未做行为改动；跨区复用的私有 UI/文案/颜色助手按协议提升为 internal。
@Composable
// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun WebSearchTraceSection(
    title: String,
    values: List<String>,
    error: Boolean = false
) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        values.forEach { value ->
            Text(
                "• $value",
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatWebSearchTrace.webSearchTraceColor(): Color =
    when {
        running -> McaPrimaryBlue
        success && qualityScore >= 72 -> McaPrimaryBlue
        success -> Color(0xFF0F9D58)
        warnings.isNotEmpty() || conflictWarnings.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatWebSearchTrace.summaryLine(): String {
    val targetCount = (searchedQueries.size + directUrls.size).coerceAtLeast(sourceCount)
    return buildList {
        if (providerLabel.isNotBlank()) add(providerLabel)
        if (targetCount > 0) add(if (running) "计划 ${targetCount} 个目标" else "${targetCount} 个目标")
        if (sourceCount > 0) add("${sourceCount} 个来源")
        if (elapsedMs > 0) add(formatWebSearchElapsed(elapsedMs))
        if (qualityLabel.isNotBlank()) add("质量 $qualityLabel ${qualityScore}/100")
        if (researchConfidenceLabel.isNotBlank()) add("置信度 $researchConfidenceLabel ${researchConfidenceScore}/100")
    }.ifEmpty {
        listOf(message.ifBlank { query.ifBlank { "已记录本轮联网检索" } })
    }.joinToString(" · ")
}

private fun formatWebSearchElapsed(elapsedMs: Long): String =
    if (elapsedMs >= 1000L) {
        "${"%.1f".format(elapsedMs / 1000.0)}s"
    } else {
        "${elapsedMs}ms"
    }

@Composable
// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun WebSearchSourceDetailCard(
    source: ChatSourceReference,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = source.webSearchTrustColor().copy(alpha = 0.14f),
                    shape = CircleShape
                ) {
                    Text(
                        source.displayTrustLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = source.webSearchTrustColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    source.displayHostLabel(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起来源详情",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                source.title.ifBlank { source.url },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val reason = source.displayTrustReason()
            if (reason.isNotBlank() || source.provider.isNotBlank()) {
                Text(
                    listOf(source.provider, reason)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (source.snippet.isNotBlank()) {
                Text(
                    source.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                source.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA 来源链接", source.url))
                            )
                            Toast.makeText(context, "已复制来源链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("复制链接")
                }
                Button(onClick = onOpen) {
                    Text("应用内打开")
                }
            }
        }
    }
}

// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun List<ChatSourceReference>.webSearchSourceSummaryLabel(): String {
    if (isEmpty()) return "联网来源"
    val summary = map { it.displayTrustLabel() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(2)
        .joinToString(" · ") { "${it.key} ${it.value}" }
    return buildString {
        append("联网来源")
        append(" · ")
        append(size)
        append(" 个")
        if (summary.isNotBlank()) {
            append(" · ")
            append(summary)
        }
    }
}

// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatSourceReference.displayHostLabel(): String =
    hostLabel.ifBlank {
        runCatching { Uri.parse(url).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore("/") }
    }

// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatSourceReference.displayTrustLabel(): String =
    trustLabel.ifBlank {
        val host = displayHostLabel().lowercase()
        when {
            provider == "安全拦截" -> "安全拦截"
            host == "github.com" || host.endsWith(".github.com") -> "代码仓库"
            host == "huggingface.co" || host == "modelscope.cn" -> "模型社区"
            host == "arxiv.org" || host.endsWith(".arxiv.org") -> "学术论文"
            host.contains("docs") || url.contains("/docs", ignoreCase = true) || url.contains("/developer", ignoreCase = true) -> "开发者文档"
            host.contains("reddit") || host.contains("stackoverflow") || host.contains("zhihu") -> "社区讨论"
            else -> "普通网页"
        }
    }

// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatSourceReference.displayTrustReason(): String =
    trustReason.ifBlank {
        when (displayTrustLabel()) {
            "安全拦截" -> "受限地址未读取"
            "代码仓库" -> "开发者仓库"
            "模型社区" -> "模型托管与下载站点"
            "学术论文" -> "论文或预印本来源"
            "开发者文档" -> "文档、指南或开发者资料"
            "社区讨论" -> "论坛、问答或社交讨论"
            else -> ""
        }
    }

@Composable
// P9/S7: private→internal（跨文件被 ChatChromeScreens 复合区引用的 UI 助手）
internal fun ChatSourceReference.webSearchTrustColor(): Color =
    when (displayTrustLabel()) {
        "官方/一手", "开发者文档", "模型社区", "代码仓库" -> McaPrimaryBlue
        "学术论文" -> Color(0xFF5E6AD2)
        "社区讨论", "媒体报道" -> Color(0xFF0F9D58)
        "安全拦截" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

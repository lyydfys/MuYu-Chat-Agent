package com.muyuchat.feature.chat

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S15：从 ChatChromeScreens.kt 等值移出的推理面板与生成性能簇。
// 原样逐字迁移，未做行为改动；4 个跨区入口按协议提升 internal。
@Composable
// P9/S15: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun ReasoningPanel(
    content: String,
    durationMs: Long,
    awaitingFinalAnswer: Boolean,
    modifier: Modifier = Modifier
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val boundedContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val cleaned = remember(boundedContent) { cleanReasoningForDisplay(boundedContent) }
    if (cleaned.isBlank()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val seconds = durationMs.div(1000).coerceAtLeast(0)
    val title = if (awaitingFinalAnswer) {
        if (seconds > 0) "正在思考（用时 ${seconds} 秒）" else "正在思考"
    } else if (seconds > 0) {
        "已思考（用时 ${seconds} 秒）"
    } else {
        "已思考"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val displayText = if (expanded) cleaned else reasoningPreview(cleaned)
    val wrappedDisplayText = remember(displayText) { wrapForDisplay(displayText) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(muted.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wrappedDisplayText,
                    color = muted.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else REASONING_COLLAPSED_MAX_LINES,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = true
                )
                if (expanded && boundedContent.length < content.length) {
                    TextButton(
                        onClick = {
                            visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                                .coerceAtMost(content.length)
                        }
                    ) {
                        Text("显示更多（${boundedContent.length}/${content.length}）")
                    }
                }
            }
        }
    }
}

@Composable
// P9/S15: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun PendingReasoningPanel(
    ownerCreatedAt: Long,
    phase: GenerationPhase?,
    tokenProgress: TokenProgress?,
    persistProgress: PersistProgress?,
    modifier: Modifier = Modifier
) {
    // UI-visible waiting time is separate from runtime timing and persisted UTC.
    val startedAtElapsedMs = remember(ownerCreatedAt) { SystemClock.elapsedRealtime() }
    var elapsedMs by remember(ownerCreatedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val seconds = elapsedMs.div(1000).coerceAtLeast(0)
    val phaseTitle = generationPhaseLabel(phase)
    val progressText = tokenProgress?.let { progress ->
        if (phase == GenerationPhase.PREPROCESS) {
            "\u5df2\u5904\u7406 ${progress.completedTokens}/${progress.totalTokens} \u5f20\u56fe\u7247"
        } else {
            "\u5df2\u5904\u7406 ${progress.completedTokens}/${progress.totalTokens} tokens"
        }
    }
    val persist = persistProgress?.takeIf { it.isActive }
    val persistTitle = persist?.let {
        when (it.stage) {
            com.muyuchat.core.engine.PersistStage.ENCODING -> "\u7f13\u5b58\u7f16\u7801\u4e2d"
            com.muyuchat.core.engine.PersistStage.WRITING -> "\u7f13\u5b58\u5e8f\u5217\u5316"
            else -> phaseTitle
        }
    }
    val displayTitle = if (persistTitle != null) {
        if (seconds > 0) "$persistTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)" else persistTitle
    } else if (seconds > 0) {
        "$phaseTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)"
    } else {
        phaseTitle
    }
    val persistText = persist?.let { p ->
        val writtenMb = p.writtenBytes / 1048576.0
        val totalMb = p.totalBytes / 1048576.0
        "\u5df2\u5199\u5165 ${"%.1f".format(writtenMb)}/${"%.1f".format(totalMb)} MB"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = muted,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        if (phase != null) {
            if (persist != null && persist.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (persist.writtenBytes.toFloat() / persist.totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else if (tokenProgress != null) {
                LinearProgressIndicator(
                    progress = {
                        tokenProgress.completedTokens.toFloat() / tokenProgress.totalTokens.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            }
            (persistText ?: progressText)?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
}

@Composable
internal fun PromptContextUsageLine(usage: PromptContextUsage) {
    val summary = buildString {
        append("\u4fdd\u7559 ").append(usage.retainedMessageCount).append(" \u6761")
        if (usage.trimmedMessageCount > 0) {
            append(" \u00b7 \u88c1\u526a ").append(usage.trimmedMessageCount).append(" \u6761")
        }
        append(" \u00b7 \u89d2\u8272 ").append(usage.roleTokens).append(" tokens")
        append(" \u00b7 \u4e16\u754c\u4e66 ").append(usage.worldBookTokens).append(" tokens")
        append(" \u00b7 \u77e5\u8bc6\u5e93 ").append(usage.knowledgeTokens).append(" tokens")
    }
    val retained = usage.messageRetention.filter { it.retained }
    val trimmed = usage.messageRetention.filterNot { it.retained }
    val historyDetails = buildList {
        if (retained.isNotEmpty()) appendPromptDecision("\u4fdd\u7559", retained)
        if (trimmed.isNotEmpty()) appendPromptDecision("\u88c1\u526a", trimmed)
    }.joinToString(" \u00b7 ")
    val sourceDetails = buildList {
        appendIdDecision("\u4e16\u754c\u4e66\u547d\u4e2d", usage.selectedWorldBookEntryIds)
        appendIdDecision("\u4e16\u754c\u4e66\u8df3\u8fc7", usage.skippedWorldBookEntryIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u547d\u4e2d", usage.selectedKnowledgeChunkIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u8df3\u8fc7", usage.skippedKnowledgeChunkIds)
    }.joinToString(" \u00b7 ")
    val details = listOf(
        "$summary \u00b7 \u603b ${usage.totalEstimatedTokens} tokens",
        historyDetails,
        sourceDetails
    ).filter { it.isNotBlank() }.joinToString("\n")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .semantics {
                stateDescription = "\u5f53\u524d\u4e0a\u4e0b\u6587\u4f7f\u7528\u60c5\u51b5"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = details,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
        )
    }
}

private fun MutableList<String>.appendPromptDecision(
    label: String,
    decisions: List<PromptMessageRetention>
) {
    val visible = decisions.take(4).joinToString(",") { decision ->
        val role = when (decision.role) {
            Role.SYSTEM -> "S"
            Role.USER -> "U"
            Role.ASSISTANT -> "A"
        }
        "#${decision.originalIndex + 1}$role"
    }
    val remainder = decisions.size - minOf(decisions.size, 4)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun MutableList<String>.appendIdDecision(label: String, ids: List<String>) {
    if (ids.isEmpty()) return
    val visible = ids.take(3).joinToString(",") { it.take(8) }
    val remainder = ids.size - minOf(ids.size, 3)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun generationPhaseLabel(phase: GenerationPhase?): String = when (phase) {
    GenerationPhase.LOAD -> "\u6b63\u5728\u52a0\u8f7d\u8fd0\u884c\u65f6"
    GenerationPhase.PREPROCESS -> "\u6b63\u5728\u51c6\u5907\u56fe\u7247"
    GenerationPhase.TOKENIZE -> "\u6b63\u5728\u5206\u8bcd"
    GenerationPhase.PREFILL -> "\u6b63\u5728\u9884\u5904\u7406\u4e0a\u4e0b\u6587"
    GenerationPhase.DECODE -> "\u6b63\u5728\u751f\u6210\u56de\u7b54"
    GenerationPhase.PERSIST -> "\u6b63\u5728\u5b8c\u6210\u63a8\u7406\u6536\u5c3e"
    null -> "\u6b63\u5728\u601d\u8003"
}

internal fun generationPerformanceSummary(stats: RuntimeStats?): String? {
    val activeStats = stats ?: return null
    return buildList {
        activeStats.prefillTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("预填充 ${formatGenerationRate(rate)} token/s")
        }
        activeStats.effectivePromptTps
            .takeIf {
                it.isFinite() && it > 0.0 &&
                    activeStats.cacheReusedTokens > 0 &&
                    it > activeStats.prefillTps * 1.05
            }
            ?.let { rate ->
                add("缓存有效 ${formatGenerationRate(rate)} token/s")
            }
        activeStats.decodeTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("输出 ${formatGenerationRate(rate)} token/s")
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatGenerationRate(rate: Double): String =
    "%.1f".format(java.util.Locale.US, rate)

@Composable
// P9/S15: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun GenerationPerformanceLine(stats: RuntimeStats?) {
    val summary = generationPerformanceSummary(stats) ?: return
    // 真机实测（手机 560dpi）：这一行是回复区里最小的一档（h=44 对正文 h=61~64），
    // 且额外乘了 0.78 的透明度 —— `onSurfaceVariant` 本身已表达"次级"，
    // 再折减 alpha 就是"低对比"的实际来源。此处提到 labelMedium 并去掉多余折减。
    Text(
        text = summary,
        modifier = Modifier.padding(top = 1.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// P9/S15: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun cacheEvidenceLabel(stats: RuntimeStats): String? {
    val persistentReason = stats.persistentPrefixCacheReason
        ?.takeUnless { it in setOf("not_requested", "not_attempted", "model_unloaded") }
    val inMemoryReason = stats.cacheReuseReason
        ?.takeUnless { it in setOf("not_attempted", "model_unloaded") }
    return when {
        stats.persistentPrefixCacheHit && stats.cacheReuseReason == "persistent_session_hit" ->
            "会话快照命中 · 复用 ${stats.persistentPrefixCacheTokens} tokens"
        persistentReason == "session_state_saved" ->
            "会话快照已保存 · ${stats.persistentPrefixCacheTokens} tokens"
        stats.persistentPrefixCacheHit ->
            "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u547d\u4e2d \u00b7 \u590d\u7528 ${stats.persistentPrefixCacheTokens} tokens"
        persistentReason?.startsWith("session_") == true -> "会话快照未命中 · $persistentReason"
        persistentReason != null -> "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u672a\u547d\u4e2d \u00b7 $persistentReason"
        stats.cacheReuseReason == "unknown" -> "上下文 KV：未知"
        stats.cacheReuseHit -> "\u4e0a\u4e0b\u6587 KV \u547d\u4e2d \u00b7 \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        stats.cacheReusedTokens > 0 -> "\u4e0a\u4e0b\u6587 KV \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        inMemoryReason != null -> "\u4e0a\u4e0b\u6587 KV \u672a\u547d\u4e2d \u00b7 $inMemoryReason"
        else -> null
    }
}

/** Never attribute stale local throughput/KV evidence to a retained or cloud model selection. */
internal fun ChatUiState.hasReadyLocalModelForHeader(): Boolean =
    !selectedModelIsCloud && stats.loaded && selectedModelId != null &&
        localModels.any { it.id == selectedModelId && !it.cloud && it.loaded }

internal fun ChatUiState.modelHeaderSubtitle(): String {
    modelLoadMessage?.takeIf { it.isNotBlank() }?.let { return it }
    return listOfNotNull(
        modelReadinessLabel?.takeIf { it.isNotBlank() },
        selectedModelRuntimeLabel?.takeIf { it.isNotBlank() },
        generationPerformanceSummary(stats).takeIf { hasReadyLocalModelForHeader() }
    ).joinToString(" · ").ifBlank {
        if (selectedModelIsCloud && selectedModelName != null) "云端 API" else "待加载 · 点击选择模型"
    }
}

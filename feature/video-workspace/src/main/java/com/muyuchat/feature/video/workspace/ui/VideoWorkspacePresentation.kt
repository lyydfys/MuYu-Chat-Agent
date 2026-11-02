package com.muyuchat.feature.video.workspace.ui

import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoImportSeverity
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure display mappings of the video page (V1-05).
 *
 * Everything here turns contract values into user-visible text without touching Compose or
 * Android, so the honest wording (progress without invented percentages, import diagnostics)
 * is covered by plain JVM tests. Colours are never the only carrier of state: every mapping
 * returns a text label as well.
 */

/** `mm:ss` below one hour, `h:mm:ss` above. Unknown/negative durations read `00:00`. */
internal fun formatVideoDuration(durationUs: Long): String {
    val totalSeconds = durationUs.coerceAtLeast(0L) / 1_000_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Binary units (`1 KiB = 1024 B`); used for the media row and the save-as receipt. */
internal fun formatVideoBytes(sizeBytes: Long): String {
    val bytes = sizeBytes.coerceAtLeast(0L)
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1_024.0
    var unitIndex = 0
    while (value >= 1_024.0 && unitIndex < units.lastIndex) {
        value /= 1_024.0
        unitIndex += 1
    }
    val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}

/** `480 × 832`; never guesses a missing dimension. */
internal fun formatVideoResolution(width: Long, height: Long): String = "${width} × $height"

/** Local-time creation stamp shown in the library. */
internal fun formatVideoCreatedAt(
    createdAtMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(createdAtMs))

/** Chinese label of a run state (text carries the state, colour only reinforces it). */
internal fun videoRunStateLabel(state: VideoRunState): String = when (state) {
    VideoRunState.DRAFT -> "草稿"
    VideoRunState.QUEUED -> "排队中"
    VideoRunState.PREPARING -> "准备中"
    VideoRunState.CONDITIONING -> "条件编码中"
    VideoRunState.SAMPLING -> "采样中"
    VideoRunState.DECODING -> "解码中"
    VideoRunState.ENCODING -> "编码中"
    VideoRunState.PUBLISHING -> "发布中"
    VideoRunState.COMPLETED -> "已完成"
    VideoRunState.CANCEL_REQUESTED -> "已请求取消"
    VideoRunState.CANCELLED -> "已取消"
    VideoRunState.FAILED -> "失败"
    VideoRunState.INTERRUPTED -> "已中断"
    VideoRunState.UNKNOWN -> "状态未知"
    VideoRunState.RECONCILING -> "核对恢复中"
}

internal fun videoRunStageLabel(stage: VideoRunStage): String = when (stage) {
    VideoRunStage.NONE -> "无阶段"
    VideoRunStage.PREPARE_INPUTS -> "准备输入"
    VideoRunStage.LOAD_MODEL -> "加载模型"
    VideoRunStage.TEXT_ENCODE -> "文本编码"
    VideoRunStage.SAMPLE -> "采样"
    VideoRunStage.DECODE -> "解码"
    VideoRunStage.ENCODE -> "编码"
    VideoRunStage.VERIFY -> "校验"
    VideoRunStage.PUBLISH -> "发布"
    VideoRunStage.FINALIZE -> "收尾"
}

internal fun videoResourceStateLabel(state: VideoResourceState): String = when (state) {
    VideoResourceState.HELD -> "资源占用中"
    VideoResourceState.STOPPING -> "正在停止"
    VideoResourceState.RELEASED -> "资源已释放"
    VideoResourceState.UNCONFIRMED -> "释放未确认"
}

/**
 * Real progress only. A null [step] or [stepTotal] never produces a percentage; a step without a
 * total is shown as a count, and nothing at all is shown when the owner reported no step.
 */
internal fun videoRunProgress(step: Long?, stepTotal: Long?): String? {
    if (step == null) return null
    if (stepTotal == null || stepTotal <= 0L) return "步骤 $step"
    val percent = ((step.toDouble() / stepTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    return "步骤 $step / $stepTotal（$percent%）"
}

/** Also used by the player's position text; milliseconds are converted at the call site. */
internal fun formatVideoClock(positionMs: Long, durationMs: Long): String =
    "${formatVideoDuration(positionMs * 1_000L)} / ${formatVideoDuration(durationMs * 1_000L)}"

internal fun importSeverityLabel(severity: VideoImportSeverity): String = when (severity) {
    VideoImportSeverity.INFO -> "提示"
    VideoImportSeverity.WARNING -> "警告"
    VideoImportSeverity.ERROR -> "错误"
}

/** One diagnostic line: severity, node/path anchors when present, then the real message. */
internal fun importDiagnosticLine(diagnostic: VideoImportDiagnostic): String {
    val anchors = buildList {
        diagnostic.nodeId?.takeIf { it.isNotBlank() }?.let { add("节点 $it") }
        diagnostic.path?.takeIf { it.isNotBlank() }?.let { add("路径 $it") }
    }
    val prefix = "[${importSeverityLabel(diagnostic.severity)}]"
    return if (anchors.isEmpty()) {
        "$prefix ${diagnostic.message}"
    } else {
        "$prefix ${anchors.joinToString(" · ")}：${diagnostic.message}"
    }
}

/** `3 条诊断：1 个错误、1 个警告、1 个提示`; empty diagnostics read `无诊断`. */
internal fun importDiagnosticSummary(diagnostics: List<VideoImportDiagnostic>): String {
    if (diagnostics.isEmpty()) return "无诊断"
    val errors = diagnostics.count { it.severity == VideoImportSeverity.ERROR }
    val warnings = diagnostics.count { it.severity == VideoImportSeverity.WARNING }
    val infos = diagnostics.count { it.severity == VideoImportSeverity.INFO }
    val parts = buildList {
        if (errors > 0) add("$errors 个错误")
        if (warnings > 0) add("$warnings 个警告")
        if (infos > 0) add("$infos 个提示")
    }
    return "${diagnostics.size} 条诊断：${parts.joinToString("、")}"
}

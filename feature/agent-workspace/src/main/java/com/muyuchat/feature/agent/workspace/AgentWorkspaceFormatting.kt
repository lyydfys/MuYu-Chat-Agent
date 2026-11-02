package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure display formatting for the Agent workspace. No Android type, no locale-dependent test
 * assertions on the clock: dates are relative and times only appear in sanitized event details.
 */

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L
private const val KIB = 1_024.0

internal fun formatRelativeTime(nowMillis: Long, thenMillis: Long): String {
    val delta = nowMillis - thenMillis
    if (delta < MINUTE_MILLIS) return "刚刚"
    if (delta < HOUR_MILLIS) return "${delta / MINUTE_MILLIS} 分钟前"
    if (delta < DAY_MILLIS) return "${delta / HOUR_MILLIS} 小时前"
    return "${delta / DAY_MILLIS} 天前"
}

internal fun formatClockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1_024L) return "${bytes.coerceAtLeast(0L)} B"
    val kib = bytes / KIB
    if (kib < 1_024.0) return String.format(Locale.ROOT, "%.1f KB", kib)
    val mib = kib / KIB
    if (mib < 1_024.0) return String.format(Locale.ROOT, "%.1f MB", mib)
    return String.format(Locale.ROOT, "%.2f GB", mib / KIB)
}

/** Hashes are display-only; the full value is never claimed to be verified by the page. */
internal fun formatShortHash(hash: String?): String {
    val value = hash?.trim().orEmpty()
    if (value.isEmpty()) return "—"
    return if (value.length <= 16) value else value.take(12) + "…"
}

internal fun formatDurationShort(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L)
    if (totalSeconds < 60L) return "$totalSeconds 秒"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    if (minutes < 60L) return "$minutes 分 $seconds 秒"
    return "${minutes / 60L} 时 ${minutes % 60L} 分"
}

internal fun protocolLabel(mode: AgentProtocolMode): String = when (mode) {
    AgentProtocolMode.NATIVE -> "原生协议"
    AgentProtocolMode.PROMPTED_V1 -> "提示词协议 V1"
    AgentProtocolMode.NONE -> "不支持 Agent 协议"
}

internal fun offlineLabel(availableOffline: Boolean): String =
    if (availableOffline) "离线可用" else "需要联网"

internal const val LOCAL_MODEL_SECTION_TITLE = "本地模型"
internal const val CLOUD_MODEL_SECTION_TITLE = "云端模型"
internal const val MODEL_LOADED_BADGE = "已加载"
internal const val MODEL_UNLOADED_BADGE = "未加载"
internal const val MODEL_UNLOADED_ACTION = "去聊天页加载"
internal const val MODEL_DIRECT_LOAD_ACTION = "直接加载"
internal const val MODEL_UNLOADED_SELECTION_REASON = "该模型已安装但未加载，不能开始任务。"
internal const val MODEL_OFFLINE_SELECTION_REASON = "当前离线，所选模型需要联网。"
internal const val CLOUD_PROTOCOL_UNSUPPORTED_REASON = "该协议暂不支持 Agent"

/**
 * Badge text for a local catalog entry. A loaded local model gets an explicit check badge instead of
 * silently looking identical to an unloaded entry; cloud entries have no local engine load state and
 * therefore no load badge.
 */
internal fun modelLoadBadge(loadState: AgentModelLoadState): String = when (loadState) {
    AgentModelLoadState.LOADED -> MODEL_LOADED_BADGE
    AgentModelLoadState.UNLOADED -> MODEL_UNLOADED_BADGE
}

internal fun modelUnloadedHint(modelDisplayName: String): String =
    "“$modelDisplayName”已安装但未加载，不能开始任务。请先去聊天页加载。"

/** Picker-specific wording: this surface offers both the direct load and the leave-tab path. */
internal fun modelUnloadedPickerHint(modelDisplayName: String): String =
    "“$modelDisplayName”已下载但未加载，不能开始任务。可直接加载，或去聊天页加载。"

/**
 * One-line explanation for an effective selection that cannot start. Pure, ordered by the concrete
 * blocking contract fact; never claims a model is runnable when only the row is visible.
 */
internal fun modelSelectionReason(
    protocolMode: AgentProtocolMode,
    loadState: AgentModelLoadState,
    availableOffline: Boolean,
    networkEnabled: Boolean
): String? = when {
    protocolMode == AgentProtocolMode.NONE -> CLOUD_PROTOCOL_UNSUPPORTED_REASON
    loadState == AgentModelLoadState.UNLOADED -> MODEL_UNLOADED_SELECTION_REASON
    !networkEnabled && !availableOffline -> MODEL_OFFLINE_SELECTION_REASON
    else -> null
}

internal fun statusLabel(status: AgentWorkspaceStatus): String = when (status) {
    AgentWorkspaceStatus.EMPTY -> "还没有任务"
    AgentWorkspaceStatus.LOADING -> "载入中"
    AgentWorkspaceStatus.IDLE -> "待开始"
    AgentWorkspaceStatus.QUEUED -> "排队中"
    AgentWorkspaceStatus.GENERATING -> "生成中"
    AgentWorkspaceStatus.WAITING_RESOURCE -> "等待模型资源"
    AgentWorkspaceStatus.WAITING_APPROVAL -> "等待授权"
    AgentWorkspaceStatus.WAITING_USER -> "等待回答"
    AgentWorkspaceStatus.PAUSING -> "正在暂停"
    AgentWorkspaceStatus.STOPPING -> "正在停止"
    AgentWorkspaceStatus.PAUSED -> "已暂停"
    AgentWorkspaceStatus.DENIED -> "已拒绝"
    AgentWorkspaceStatus.BUDGET_LIMIT -> "已到运行上限"
    AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED -> "协议不支持"
    AgentWorkspaceStatus.MODEL_UNLOADED -> "模型未加载"
    AgentWorkspaceStatus.NO_MODEL -> "没有可用模型"
    AgentWorkspaceStatus.MCP_OFFLINE -> "MCP 已断线"
    AgentWorkspaceStatus.SKILL_MISSING_TOOL -> "Skill 缺少工具"
    AgentWorkspaceStatus.RESTORED -> "中断已恢复"
    AgentWorkspaceStatus.NEEDS_REVIEW -> "需要人工确认"
    AgentWorkspaceStatus.CANCELLED -> "已取消"
    AgentWorkspaceStatus.FAILED -> "失败"
    AgentWorkspaceStatus.SUCCEEDED -> "已完成"
    AgentWorkspaceStatus.PACKAGE_EXPORTING -> "正在导出任务包"
    AgentWorkspaceStatus.PACKAGE_IMPORTING -> "正在导入任务包"
    AgentWorkspaceStatus.PACKAGE_DELETING -> "正在删除任务"
    AgentWorkspaceStatus.PACKAGE_FAILED -> "任务包操作失败"
}

internal fun statusTone(status: AgentWorkspaceStatus): AgentStatusTone = when (status) {
    AgentWorkspaceStatus.GENERATING,
    AgentWorkspaceStatus.QUEUED,
    AgentWorkspaceStatus.WAITING_RESOURCE,
    AgentWorkspaceStatus.WAITING_APPROVAL,
    AgentWorkspaceStatus.WAITING_USER,
    AgentWorkspaceStatus.PACKAGE_EXPORTING,
    AgentWorkspaceStatus.PACKAGE_IMPORTING,
    AgentWorkspaceStatus.PACKAGE_DELETING -> AgentStatusTone.ACTIVE

    AgentWorkspaceStatus.SUCCEEDED -> AgentStatusTone.SUCCESS
    AgentWorkspaceStatus.PAUSING,
    AgentWorkspaceStatus.STOPPING,
    AgentWorkspaceStatus.PAUSED,
    AgentWorkspaceStatus.BUDGET_LIMIT,
    AgentWorkspaceStatus.MCP_OFFLINE,
    AgentWorkspaceStatus.SKILL_MISSING_TOOL,
    AgentWorkspaceStatus.RESTORED,
    AgentWorkspaceStatus.NEEDS_REVIEW,
    AgentWorkspaceStatus.MODEL_UNLOADED -> AgentStatusTone.WARNING

    AgentWorkspaceStatus.DENIED,
    AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED,
    AgentWorkspaceStatus.NO_MODEL,
    AgentWorkspaceStatus.CANCELLED,
    AgentWorkspaceStatus.FAILED,
    AgentWorkspaceStatus.PACKAGE_FAILED -> AgentStatusTone.ERROR

    AgentWorkspaceStatus.EMPTY,
    AgentWorkspaceStatus.LOADING,
    AgentWorkspaceStatus.IDLE -> AgentStatusTone.NEUTRAL
}

internal fun retryabilityLabel(retryability: AgentRetryability): String = when (retryability) {
    AgentRetryability.NEVER -> "不可重试"
    AgentRetryability.AFTER_USER_ACTION -> "处理后可重试"
    AgentRetryability.SAFE_RETRY -> "可安全重试"
}

/** Truncation helper shared by approval arguments and event details. */
internal fun boundedText(text: String?, maxChars: Int): String? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null
    return if (value.length <= maxChars) value else value.take(maxChars).trimEnd() + "\n…（内容已截断）"
}

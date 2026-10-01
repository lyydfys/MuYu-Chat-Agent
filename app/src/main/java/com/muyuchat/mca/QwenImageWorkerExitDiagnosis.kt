package com.muyuchat.mca

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal data class QwenWorkerExitEvidence(
    val processName: String,
    val timestampMs: Long,
    val reason: Int,
    val status: Int
)

internal fun currentQwenWorkerExit(
    exits: List<QwenWorkerExitEvidence>,
    packageName: String,
    requestStartedAtMs: Long
): QwenWorkerExitEvidence? = exits
    .filter { it.processName == "${packageName}:qwen_image21" && it.timestampMs >= requestStartedAtMs }
    .maxByOrNull { it.timestampMs }

internal fun qwenWorkerExitMessage(exit: QwenWorkerExitEvidence?): String = when (exit?.reason) {
    ApplicationExitInfo.REASON_LOW_MEMORY ->
        "系统因内存压力终止了 Qwen 生图进程。请减小分辨率或释放已加载的聊天模型后重试；手机总内存不等于本次可用内存。"
    ApplicationExitInfo.REASON_CRASH_NATIVE ->
        "Qwen 原生生图进程崩溃（系统退出码 "+exit.status+"）。可重新生成；请保留诊断日志以定位运行库或模型问题。"
    ApplicationExitInfo.REASON_CRASH ->
        "Qwen 生图进程发生异常。可重新生成；请查看诊断日志中的异常原因。"
    ApplicationExitInfo.REASON_ANR ->
        "Qwen 生图进程无响应并被系统终止。可重试或降低分辨率。"
    ApplicationExitInfo.REASON_USER_REQUESTED ->
        "Qwen 生图进程被系统或用户停止。请保持应用前台后重试。"
    else -> "Qwen 生图进程连接中断，系统尚未提供本次退出原因。可以重试；此状态不能直接判定为内存不足。"
}

internal fun readQwenWorkerExitMessage(context: Context, requestStartedAtMs: Long): String {
    val evidence = if (Build.VERSION.SDK_INT >= 30) runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val exits = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 16).orEmpty()
            .map { QwenWorkerExitEvidence(it.processName, it.timestamp, it.reason, it.status) }
        currentQwenWorkerExit(exits, context.packageName, requestStartedAtMs)
    }.getOrNull() else null
    return qwenWorkerExitMessage(evidence)
}

package com.muyuchat.mca

import com.muyuchat.core.modelstore.ChatModelRuntime
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Reports a bound GGUF projector that did not activate in native. This is a
 * vision-capability warning only: the text runner remains usable when native
 * successfully loaded the base model.
 */
internal fun llamaCppVisionReadinessWarning(
    runtime: ChatModelRuntime,
    configuredProjectorPath: String?,
    nativeStatsJson: String
): String? {
    if (runtime != ChatModelRuntime.LLAMA_CPP || configuredProjectorPath.isNullOrBlank()) return null
    val stats = runCatching { JSONObject(nativeStatsJson) }.getOrNull()
        ?: return "已绑定 mmproj，但运行时未返回视觉能力状态。请检查模型加载日志后重试。"
    if (!stats.optBoolean("loaded", false)) {
        return "主模型未能保持加载状态，因此 mmproj 没有启用。请重新加载主模型并查看加载错误。"
    }
    val expectedPath = canonicalVisionPath(configuredProjectorPath)
    val actualPath = stats.optString("mmprojPath")
        .takeIf(String::isNotBlank)
        ?.let(::canonicalVisionPath)
    if (actualPath == null || actualPath != expectedPath) {
        return "已绑定 mmproj，但当前 native runner 使用的投影器与绑定文件不一致。请重新绑定投影器并重新加载。"
    }
    if (!stats.optBoolean("visionReady", false)) {
        val reason = stats.optString("visionFailureReason")
            .takeIf { it.isNotBlank() && it != "null" }
        val detail = when (reason) {
            "native_capability_reports_no_vision" -> "native runtime 拒绝了该主模型/投影器组合"
            "mmproj_not_bound", "mmproj_file_missing", "mmproj_file_empty", "mmproj_file_unreadable" ->
                "native runtime 无法读取绑定的 mmproj"
            "model_type_is_text_only" -> "主 GGUF 被识别为纯文本模型"
            else -> "native runtime 未能创建视觉 runner"
        }
        return "视觉模型加载未完成：$detail。请确认主模型和 mmproj 来自同一模型仓库、同一架构版本，然后重新绑定并加载。"
    }
    return null
}

private fun canonicalVisionPath(path: String): String =
    runCatching {
        val file = if (path.startsWith("file:", ignoreCase = true)) File(URI(path)) else File(path)
        file.canonicalPath
    }.getOrDefault(path)

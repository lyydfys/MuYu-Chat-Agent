package com.muyuchat.mca

import org.json.JSONObject

internal enum class LocalModelLoadFailureKind {
    UNSUPPORTED_RUNTIME_CONFIG,
    LOAD_SIGNATURE_MISMATCH,
    BACKEND_UNAVAILABLE,
    MEMORY_PRESSURE,
    FILE_UNREADABLE,
    FILE_INTEGRITY,
    BUNDLE_INCOMPLETE,
    NATIVE_LOAD_FAILURE
}

internal data class LocalModelLoadFailure(
    val kind: LocalModelLoadFailureKind,
    val userMessage: String,
    val diagnosticDetail: String
)

internal object LocalModelLoadFailureClassifier {
    fun classify(message: String?, nativeStatsJson: String): LocalModelLoadFailure {
        val nativeLastError = runCatching { JSONObject(nativeStatsJson).optString("lastError") }
            .getOrDefault("")
        val detail = listOfNotNull(message?.trim(), nativeLastError.trim().takeIf { it.isNotBlank() })
            .distinct()
            .joinToString(" · ")
            .ifBlank { "unknown native load failure" }
        val lower = detail.lowercase()

        return when {
            lower.contains("completion config changes load-bound") ||
                lower.contains("load signature mismatch") ||
                lower.contains("reload the model before generating") -> failure(
                LocalModelLoadFailureKind.LOAD_SIGNATURE_MISMATCH,
                "模型加载期参数已经变化。请通过“应用并重新加载”使用该模型自己的配置；助手、会话和普通 API 请求不会直接改写加载参数。",
                detail
            )

            lower.contains("n_cpu_moe requires") ||
                lower.contains("main_gpu must be 0") ||
                lower.contains("n_gpu_layers requests gpu") ||
                lower.contains("main_gpu exceeds") ||
                lower.contains("unsupported runtime config") ||
                lower.contains("outside llama_max_devices") -> failure(
                LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG,
                "当前参数与已打包的运行时能力不兼容。CPU-only APK 会自动拒绝 GPU/MoE offload；请应用该模型的 CPU 安全配置后重新加载。",
                detail
            )

            lower.contains("no backends are loaded") ||
                lower.contains("no llama.cpp ggml backend devices") ||
                lower.contains("no usable non-cpu") ||
                lower.contains("backend unavailable") -> failure(
                LocalModelLoadFailureKind.BACKEND_UNAVAILABLE,
                "本地推理后端未就绪。请确认安装的是完整 APK，并在诊断中检查 backendReady 和已注册 backend。",
                detail
            )

            lower.contains("out of memory") ||
                lower.contains("std::bad_alloc") ||
                lower.contains("low memory") ||
                lower.contains("cannot allocate memory") -> failure(
                LocalModelLoadFailureKind.MEMORY_PRESSURE,
                "设备内存不足以按当前执行配置加载模型。请关闭后台应用，降低模型上下文/批处理，或使用更小量化模型后重试。",
                detail
            )

            lower.contains("mnn") && (
                lower.contains("bundle") && lower.contains("incomplete") ||
                    lower.contains("missing required") ||
                    lower.contains("llm.mnn.weight") ||
                    lower.contains("ple_embeddings_int4.bin")
                ) -> failure(
                LocalModelLoadFailureKind.BUNDLE_INCOMPLETE,
                "MNN 模型包不完整。请导入完整 ZIP/组件目录；单个 llm.mnn 不能运行，详情页会列出缺失组件。",
                detail
            )

            lower.contains("not readable") ||
                lower.contains("permission denied") ||
                lower.contains("no such file") ||
                lower.contains("file not found") -> failure(
                LocalModelLoadFailureKind.FILE_UNREADABLE,
                "模型文件不可读或已移动。请重新授权/导入，并确认文件仍位于 App 管理目录。",
                detail
            )

            lower.contains("invalid magic") ||
                lower.contains("not a gguf") ||
                lower.contains("truncated gguf") ||
                lower.contains("gguf header") && lower.contains("invalid") ||
                lower.contains("sha-256 mismatch") ||
                lower.contains("size mismatch") -> failure(
                LocalModelLoadFailureKind.FILE_INTEGRITY,
                "模型文件头、大小或 SHA-256 校验不通过。请重新校验文件，确认选择的是主模型而不是 mmproj；必要时删除后重新下载。",
                detail
            )

            else -> failure(
                LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE,
                message?.takeIf { it.isNotBlank() }
                    ?: "模型加载失败。请展开诊断查看结构化 native 错误和有效配置。",
                detail
            )
        }
    }

    private fun failure(
        kind: LocalModelLoadFailureKind,
        message: String,
        detail: String
    ): LocalModelLoadFailure = LocalModelLoadFailure(kind, message, detail.take(2_000))
}

package com.muyuchat.mca

import org.json.JSONObject

internal enum class LocalModelLoadFailureKind {
    UNSUPPORTED_RUNTIME_CONFIG,
    LOAD_SIGNATURE_MISMATCH,
    BACKEND_UNAVAILABLE,
    MEMORY_PRESSURE,
    FILE_UNREADABLE,
    FILE_INTEGRITY,
    GGUF_METADATA_OR_ARCHITECTURE_INVALID,
    NON_AUTOREGRESSIVE_CHAT,
    UNSUPPORTED_QUANTIZATION_OR_OPERATION,
    CONTEXT_CREATION_FAILED,
    SMOKE_EXECUTION_FAILED,
    TOKENIZER_OR_TEMPLATE_INVALID,
    WORKER_TIMEOUT,
    WORKER_PROCESS_CRASH,
    BUNDLE_INCOMPLETE,
    NATIVE_LOAD_FAILURE
}

internal data class LocalModelLoadFailure(
    val kind: LocalModelLoadFailureKind,
    val userMessage: String,
    val diagnosticDetail: String
)

internal object LocalModelLoadFailureClassifier {
    private data class NativeLoadStatus(
        val lastError: String = "",
        val failureCode: String = ""
    )

    fun classify(message: String?, nativeStatsJson: String): LocalModelLoadFailure {
        val nativeStatus = nativeLoadStatus(nativeStatsJson)
        val structuredCode = canonicalFailureCode(
            nativeStatus.failureCode.ifBlank {
                stableCodeFrom(message.orEmpty())
                    ?: stableCodeFrom(nativeStatus.lastError)
                    .orEmpty()
            }
        )
        val detail = LocalDiagnosticRedactor.sanitize(listOfNotNull(
            message?.trim(),
            nativeStatus.lastError.trim().takeIf { it.isNotBlank() },
            structuredCode.takeIf { it.isNotBlank() }?.let { "loadFailureCode=$it" }
        ).distinct().joinToString(" · "))
            .ifBlank { "unknown native load failure" }

        structuredFailure(structuredCode, detail)?.let { return it }

        val lower = detail.lowercase()

        return when {
            lower.contains("completion config changes load-bound") ||
                lower.contains("load signature mismatch") ||
                lower.contains("reload the model before generating") -> failure(
                LocalModelLoadFailureKind.LOAD_SIGNATURE_MISMATCH,
                "模型加载期参数已经变化。请通过“应用并重新加载”使用该模型自己的配置；助手、会话和普通 API 请求不会直接改写加载参数。",
                detail
            )

            lower.contains("not an autoregressive chat model") ||
                lower.contains("not autoregressive") ||
                lower.contains("not a chat model gguf") -> failure(
                LocalModelLoadFailureKind.NON_AUTOREGRESSIVE_CHAT,
                "所选 GGUF 不是当前文本生成路径可执行的自回归聊天模型。请选择聊天主模型，而不是 embedding、encoder、diffusion 或 mmproj 文件。",
                detail
            )

            lower.contains("tokenizer mismatch") ||
                lower.contains("tokenizer is incompatible") ||
                lower.contains("vocabulary mismatch") ||
                lower.contains("vocab size mismatch") ||
                lower.contains("chat template") && (
                    lower.contains("invalid") ||
                        lower.contains("unsupported") ||
                        lower.contains("parse") ||
                        lower.contains("mismatch")
                    ) -> failure(
                LocalModelLoadFailureKind.TOKENIZER_OR_TEMPLATE_INVALID,
                "模型 tokenizer 或 chat template 与当前 GGUF 不匹配。请使用同一模型导出的 tokenizer/config，并重新导入完整文件。",
                detail
            )

            lower.contains("invalid metadata") ||
                lower.contains("metadata key") ||
                lower.contains("metadata value") ||
                lower.contains("unknown model architecture") ||
                lower.contains("invalid architecture") ||
                lower.contains("unsupported model architecture") -> failure(
                LocalModelLoadFailureKind.GGUF_METADATA_OR_ARCHITECTURE_INVALID,
                "GGUF 元数据或模型架构无效。请重新导入完整主模型，并确认文件不是损坏的转换产物。",
                detail
            )

            lower.contains("unsupported quantization") ||
                lower.contains("unsupported tensor type") ||
                lower.contains("unsupported ggml type") ||
                lower.contains("unsupported operation") ||
                lower.contains("no kernel implementation") ||
                lower.contains("unsupported type") -> failure(
                LocalModelLoadFailureKind.UNSUPPORTED_QUANTIZATION_OR_OPERATION,
                "当前构建不支持该 GGUF 所需的量化或执行操作。可尝试其他量化或通用兼容配置。",
                detail
            )

            lower.contains("llama_init_from_model returned null") ||
                lower.contains("context creation failed") ||
                lower.contains("failed to create llama_context") ||
                lower.contains("llama_new_context_with_model") ||
                lower.contains("context/k v") ||
                lower.contains("context/kv") ||
                lower.contains("kv cache") && lower.contains("failed") -> failure(
                LocalModelLoadFailureKind.CONTEXT_CREATION_FAILED,
                "模型已被读取，但无法按当前上下文/KV 配置创建执行上下文。请降低上下文或批处理后重试。",
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
                lower.contains("cannot allocate memory") ||
                lower.contains("failed to allocate") -> failure(
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

            (lower.contains("smoke") && (
                lower.contains("failed") ||
                    lower.contains("failure") ||
                    lower.contains("未通过")
                )) ||
                lower.contains("安全基线正确性校准失败") ||
                lower.contains("correctness canary failed") -> failure(
                LocalModelLoadFailureKind.SMOKE_EXECUTION_FAILED,
                "模型加载后的实际执行校验未通过。请查看 native 诊断并按具体运行错误处理；这不是设备认证限制。",
                detail
            )

            lower.contains("worker_watchdog_timeout") ||
                lower.contains("native_load_timeout") ||
                lower.contains("context_creation_timeout") ||
                lower.contains("smoke_execution_timeout") ||
                lower.contains("native_worker_timeout") ||
                lower.contains("timed out") ||
                lower.contains("timeout") -> failure(
                LocalModelLoadFailureKind.WORKER_TIMEOUT,
                "隔离 native worker 超时，未取得完整执行证据。请降低上下文/批处理后重试。",
                detail
            )

            lower.contains("worker_process_crashed") ||
                lower.contains("worker crashed") ||
                lower.contains("process crashed") ||
                lower.contains("binder died") ||
                lower.contains("fatal signal") ||
                lower.contains("sigsegv") ||
                lower.contains("sigabrt") -> failure(
                LocalModelLoadFailureKind.WORKER_PROCESS_CRASH,
                "隔离 native worker 在返回完整证据前退出；主进程未采用该候选配置。",
                detail
            )

            else -> failure(
                LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE,
                "模型加载失败。请展开诊断查看结构化 native 错误和有效配置。",
                detail
            )
        }
    }

    private fun nativeLoadStatus(nativeStatsJson: String): NativeLoadStatus = runCatching {
        val stats = JSONObject(nativeStatsJson)
        val lastError = stats.optString("lastError").trim()
        val failureCode = sequenceOf("loadFailureCode", "lastErrorCode", "errorCode")
            .map(stats::optString)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: stableCodeFrom(lastError).orEmpty()
        NativeLoadStatus(lastError = lastError, failureCode = failureCode)
    }.getOrDefault(NativeLoadStatus())

    private fun structuredFailure(code: String, detail: String): LocalModelLoadFailure? = when (code) {
        "FILE_UNREADABLE" -> failure(
            LocalModelLoadFailureKind.FILE_UNREADABLE,
            "模型文件不可读或已移动。请重新授权/导入，并确认文件仍位于 App 管理目录。",
            detail
        )

        "GGUF_CORRUPT_OR_TRUNCATED" -> failure(
            LocalModelLoadFailureKind.FILE_INTEGRITY,
            "GGUF 文件已损坏、格式不正确或被截断。请重新校验文件，并确认选择的是主模型而不是 mmproj。",
            detail
        )

        "GGUF_METADATA_OR_ARCHITECTURE_INVALID" -> failure(
            LocalModelLoadFailureKind.GGUF_METADATA_OR_ARCHITECTURE_INVALID,
            "GGUF 元数据或模型架构无效。请重新导入完整主模型，并确认文件不是损坏的转换产物。",
            detail
        )

        "GGUF_NOT_AUTOREGRESSIVE_CHAT", "NON_AUTOREGRESSIVE_CHAT" -> failure(
            LocalModelLoadFailureKind.NON_AUTOREGRESSIVE_CHAT,
            "所选 GGUF 不是当前文本生成路径可执行的自回归聊天模型。请选择聊天主模型，而不是 embedding、encoder、diffusion 或 mmproj 文件。",
            detail
        )

        "TOKENIZER_OR_TEMPLATE_INVALID", "GGUF_TOKENIZER_OR_TEMPLATE_INVALID" -> failure(
            LocalModelLoadFailureKind.TOKENIZER_OR_TEMPLATE_INVALID,
            "模型 tokenizer 或 chat template 与当前 GGUF 不匹配。请使用同一模型导出的 tokenizer/config，并重新导入完整文件。",
            detail
        )

        "BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION", "UNSUPPORTED_QUANTIZATION_OR_OPERATION" -> failure(
            LocalModelLoadFailureKind.UNSUPPORTED_QUANTIZATION_OR_OPERATION,
            "当前构建不支持该 GGUF 所需的量化或执行操作。可尝试其他量化或通用兼容配置。",
            detail
        )

        "CONTEXT_CREATION_FAILED" -> failure(
            LocalModelLoadFailureKind.CONTEXT_CREATION_FAILED,
            "模型已被读取，但无法按当前上下文/KV 配置创建执行上下文。请降低上下文或批处理后重试。",
            detail
        )

        "OUT_OF_MEMORY" -> failure(
            LocalModelLoadFailureKind.MEMORY_PRESSURE,
            "设备内存不足以按当前执行配置加载模型。请关闭后台应用，降低模型上下文/批处理，或使用更小量化模型后重试。",
            detail
        )

        "SMOKE_EXECUTION_FAILED" -> failure(
            LocalModelLoadFailureKind.SMOKE_EXECUTION_FAILED,
            "模型加载后的实际执行校验未通过。请查看 native 诊断并按具体运行错误处理；这不是设备认证限制。",
            detail
        )

        "WORKER_TIMEOUT", "NATIVE_LOAD_TIMEOUT", "CONTEXT_CREATION_TIMEOUT", "SMOKE_EXECUTION_TIMEOUT",
        "NATIVE_WORKER_TIMEOUT", "WORKER_WATCHDOG_TIMEOUT" -> failure(
            LocalModelLoadFailureKind.WORKER_TIMEOUT,
            "隔离 native worker 超时，未取得完整执行证据。请降低上下文/批处理后重试。",
            detail
        )

        "WORKER_PROCESS_CRASHED", "WORKER_PROCESS_CRASH", "NATIVE_FATAL_SIGNAL" -> failure(
            LocalModelLoadFailureKind.WORKER_PROCESS_CRASH,
            "隔离 native worker 在返回完整证据前退出；主进程未采用该候选配置。",
            detail
        )

        "BACKEND_UNAVAILABLE" -> failure(
            LocalModelLoadFailureKind.BACKEND_UNAVAILABLE,
            "本地推理后端未就绪。请确认安装的是完整 APK，并在诊断中检查 backendReady 和已注册 backend。",
            detail
        )

        "RUNTIME_CONFIG_INVALID", "RUNTIME_CONFIG_UNSUPPORTED" -> failure(
            LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG,
            "当前参数与已打包的运行时能力不兼容。请应用该模型的通用兼容配置后重新加载。",
            detail
        )

        "BUNDLE_INCOMPLETE" -> failure(
            LocalModelLoadFailureKind.BUNDLE_INCOMPLETE,
            "模型包不完整。请导入完整 ZIP/组件目录，并根据详情页列出的缺失组件补齐文件。",
            detail
        )

        "GGUF_MODEL_LOAD_FAILED", "NATIVE_LOAD_EXCEPTION" -> failure(
            LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE,
            "GGUF 原生加载失败。请展开诊断查看稳定错误码和 native 详细信息。",
            detail
        )

        else -> null
    }

    private fun canonicalFailureCode(rawCode: String): String = rawCode.trim()
        .uppercase()
        .removePrefix("MCA_LOAD:")
        .removePrefix("MCA_LOAD_")

    private fun stableCodeFrom(value: String): String? {
        val upper = value.uppercase()
        return Regex("""\[MCA_LOAD:([A-Z0-9_]+)]""")
            .find(upper)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\bMCA_LOAD_([A-Z0-9_]+)\b""")
                .find(upper)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun failure(
        kind: LocalModelLoadFailureKind,
        message: String,
        detail: String
    ): LocalModelLoadFailure = LocalModelLoadFailure(kind, message, detail.take(2_000))
}

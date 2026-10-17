package com.muyuchat.mca

/** Failure classification and diagnostics for [RemoteLocalChatRunner] (T2B22-T3 split). */

internal fun RemoteLocalChatRunner.markWorkerSessionLostLocked(error: Throwable) {
        if (modelLoadedInWorker) workerSessionLost = true
        modelLoadedInWorker = false
        failure = error
    }

internal fun RemoteLocalChatRunner.workerFailureCodeLocked(): String? =
        (failure as? RemoteLocalChatRunnerException)
            ?.workerStageDiagnostic
            ?.failureCode

internal fun requiresExplicitAcceleratorReload(code: String?): Boolean =
        code.orEmpty().lowercase().let { it.startsWith("litert_gpu_") || it.startsWith("mnn_opencl_") }

internal fun explicitAcceleratorReloadMessage(code: String?): String? = when {
        code.orEmpty().lowercase().startsWith("litert_gpu_") ->
            "LiteRT-LM GPU 未在限定时间内完成，隔离会话已回收。当前请求不会自动回退到 CPU；请显式重新加载 GPU，或在模型设置中选择 CPU 后重试。"
        code.orEmpty().lowercase().startsWith("mnn_opencl_") ->
            "MNN OpenCL 未在限定时间内完成，隔离会话已回收。当前请求不会自动回退到 CPU；请显式重新加载 OpenCL，或在模型设置中选择 CPU 后重试。"
        else -> null
    }

internal fun RemoteLocalChatRunner.disconnectedFailure(stage: String, cause: Throwable): RemoteLocalChatRunnerException =
        workerFailure(stage, cause)

    /** Reads only the bounded, prompt-free worker sidecar after a Binder failure. */
internal fun RemoteLocalChatRunner.workerFailure(summary: String, cause: Throwable? = null): RemoteLocalChatRunnerException {
        val diagnostic = runCatching { stageJournal.read() }.getOrNull()
        val safeCause = LocalDiagnosticRedactor.sanitize(cause?.message)
        val message = buildString {
            append("The isolated local text worker ").append(summary.trim())
            if (safeCause.isNotBlank()) append(": ").append(safeCause)
            diagnostic?.let {
                append(". Last durable worker diagnostic: ").append(it.compactDescription())
                explicitAcceleratorReloadMessage(it.failureCode)?.let { hint ->
                    append(". ").append(hint)
                }
            }
        }
        return RemoteLocalChatRunnerException(message, cause, diagnostic)
    }

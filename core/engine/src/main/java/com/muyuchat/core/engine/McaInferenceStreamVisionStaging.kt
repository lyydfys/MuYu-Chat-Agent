package com.muyuchat.core.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel

internal suspend fun McaInferenceService.stageVisionInputsForStreamChat(
    incomingHasImageAttachments: Boolean,
    contextSafeRequest: ChatRequest,
    current: RuntimeStats,
    executionContext: LocalChatExecutionContext,
    emit: suspend (GenerateEvent) -> Unit
): ChatRequest? {
            val requestWithVisionFiles = if (incomingHasImageAttachments) {
                val visionStatsJson = nativeStatsJsonOnIo()
                if (!localVisionReady(activeRuntime, visionStatsJson)) {
                    val message = localVisionUnavailableMessage(activeRuntime, visionStatsJson)
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return null
                }
                // Image staging/decoding has no token count yet. Give the UI a distinct phase
                // so large photos do not look stuck at 0% of text prefill.
                emit(GenerateEvent.Phase(GenerationPhase.PREPROCESS, _stats.value))
                runCatching {
                    coroutineScope {
                        val progress = Channel<TokenProgress>(capacity = Channel.UNLIMITED)
                        val preparation = async(io) {
                            try {
                                LocalVisionInputPreparer.prepare(
                                    request = contextSafeRequest,
                                    cacheDir = appContext.cacheDir,
                                    diagnosticSink = { stage, details ->
                                        dispatchVisionDiagnostic(executionContext, stage, details)
                                    },
                                    onProgress = { completed, total ->
                                        progress.trySend(TokenProgress(completed, total))
                                    }
                                )
                            } finally {
                                progress.close()
                            }
                        }
                        try {
                            // Consume updates while decoding/staging is still running. Reusing
                            // TokenProgress keeps the event contract stable; the PREPROCESS phase
                            // makes the values mean images rather than prompt tokens to the UI.
                            for (imageProgress in progress) {
                                emit(
                                    GenerateEvent.Phase(
                                        GenerationPhase.PREPROCESS,
                                        _stats.value,
                                        imageProgress
                                    )
                                )
                            }
                            preparation.await()
                        } finally {
                            if (!preparation.isCompleted) preparation.cancel()
                        }
                    }
                }.getOrElse { error ->
                    val message = "本地图片预处理失败：${error.message ?: "无法读取图片"}"
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(
                        GenerateEvent.Error(
                            message = message,
                            stats = errorStats,
                            code = (error as? LocalVisionInputException)?.failureCode?.wireCode
                                ?: "local_vision_input_invalid"
                        )
                    )
                    return null
                }
            } else {
                contextSafeRequest
            }
            return requestWithVisionFiles
}

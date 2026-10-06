package com.muyuchat.mca

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import android.app.Application
import kotlinx.coroutines.launch

/**
 * The owner seam for the local image-upscaler catalogue: importing a model, selecting one, and
 * deleting one.
 *
 * Five members. The upscale lifecycle lock is **not** handed out: [withUpscaleLifecycleLock] runs a
 * block while holding it and reports whether an upscale job is running, so the delete path keeps the
 * exact lock ordering it had (`imageLibraryMutationMutex` outside, this lock inside) without any
 * caller being able to hold or nest the lock itself.
 *
 * The selection id is persisted through [persistSelectedUpscalerId] because its **boolean result is
 * part of the user-visible contract**: the original used `commit()` and distinguished a successful
 * selection from one that could not be persisted, and that distinction drives two different messages.
 */
internal const val IMAGE_UPSCALER_SELECTED_ID = "selected_id"

internal interface LocalImageUpscalerPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val upscalerStore: LocalImageUpscalerStore

    /** Persists the selected upscaler id and reports whether the write was committed. */
    fun persistSelectedUpscalerId(id: String?): Boolean

    /**
     * Runs [block] while holding the upscale lifecycle lock, passing whether an upscale job is
     * currently running. Used to answer "is this model in use" without exposing the lock.
     */
    fun <T> withUpscaleLifecycleLock(block: (upscaleRunning: Boolean) -> T): T
}

internal fun importLocalImageUpscalerModel(
    port: LocalImageUpscalerPort,
    scope: CoroutineScope,
uriString: String) {
        val uri = runCatching { Uri.parse(uriString.trim()) }.getOrNull()
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) {
            port.updateUiState {
                it.copy(localImageUpscalerMessage = "请选择可读取的 ESRGAN 模型文档。")
            }
            return
        }
        if (port.uiState().localImageUpscalerImporting) return
        port.updateUiState {
            it.copy(
                localImageUpscalerImporting = true,
                localImageUpscalerMessage = "正在导入放大模型…"
            )
        }
        scope.launch(Dispatchers.IO) {
            runCatching { port.upscalerStore.import(uri) }
                .onSuccess { imported ->
                    val records = port.upscalerStore.load()
                    val selectedId = imported.id.takeIf { id -> records.any { it.id == id } }
                        ?: records.firstOrNull()?.id
                    val selectionSaved = port.persistSelectedUpscalerId(selectedId)
                    if (!selectionSaved) {
                        port.updateUiState {
                            it.copy(
                                localImageUpscalers = records,
                                localImageUpscalerImporting = false,
                                localImageUpscalerMessage = "放大模型已导入，但所选模型未能持久化。"
                            )
                        }
                        return@onSuccess
                    }
                    port.updateUiState {
                        it.copy(
                            localImageUpscalers = records,
                            selectedLocalImageUpscalerId = selectedId,
                            localImageUpscalerImporting = false,
                            localImageUpscalerMessage = "已导入并选择放大模型：${imported.name}"
                        )
                    }
                }
                .onFailure { error ->
                    port.updateUiState {
                        it.copy(
                            localImageUpscalerImporting = false,
                            localImageUpscalerMessage =
                                "放大模型导入失败：${error.message ?: "文件无效"}"
                        )
                    }
                }
        }
    }

internal fun selectLocalImageUpscalerModel(
    port: LocalImageUpscalerPort,
id: String) {
        val upscalerId = id.trim()
        val record = port.uiState().localImageUpscalers.firstOrNull { it.id == upscalerId }
        if (record == null) {
            port.updateUiState {
                it.copy(localImageUpscalerMessage = "所选放大模型已不存在，请重新选择。")
            }
            return
        }
        val selectionSaved = port.persistSelectedUpscalerId(record.id)
        if (!selectionSaved) {
            port.updateUiState {
                it.copy(localImageUpscalerMessage = "所选放大模型未能持久化，请重试。")
            }
            return
        }
        port.updateUiState {
            it.copy(
                selectedLocalImageUpscalerId = record.id,
                localImageUpscalerMessage = "已选择放大模型：${record.name}"
            )
        }
    }

internal fun deleteLocalImageUpscalerModel(
    port: LocalImageUpscalerPort,
    scope: CoroutineScope,
id: String) {
        val upscalerId = id.trim()
        if (upscalerId.isEmpty()) return
        port.updateUiState { it.copy(localImageUpscalerDeletingId = upscalerId) }
        scope.launch(Dispatchers.IO) {
            val message = port.withUpscaleLifecycleLock { upscaleRunning ->
                if (upscaleRunning &&
                    port.uiState().activeLocalImageUpscalerId == upscalerId
                ) {
                    "该放大模型正被当前任务使用，任务结束后才能删除。"
                } else {
                    val record = port.upscalerStore.load().firstOrNull { it.id == upscalerId }
                    when {
                        record == null -> "放大模型已不存在。"
                        !port.upscalerStore.delete(upscalerId) ->
                            "放大模型删除失败，索引和文件均已保留。"
                        else -> "已删除放大模型：${record.name}"
                    }
                }
            }
            val records = port.upscalerStore.load()
            val previousSelectedId = port.uiState().selectedLocalImageUpscalerId
            val selectedId = previousSelectedId?.takeIf { id -> records.any { it.id == id } }
                ?: records.firstOrNull()?.id
            val selectionSaved = port.persistSelectedUpscalerId(selectedId)
            port.updateUiState {
                it.copy(
                    localImageUpscalers = records,
                    selectedLocalImageUpscalerId = selectedId,
                    localImageUpscalerDeletingId = null,
                    localImageUpscalerMessage = if (selectionSaved) {
                        message
                    } else {
                        "$message；后续选择未能持久化。"
                    }
                )
            }
        }
    }

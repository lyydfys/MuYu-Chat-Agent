package com.muyuchat.mca

import android.net.Uri
import kotlinx.coroutines.CancellationException

import android.app.Application
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The owner seam for the local LoRA and Textual-Inversion catalogues: importing an artifact and
 * deleting one.
 *
 * Six members. **The two lifecycle locks stay in the ViewModel** -- the generation paths take them in
 * seven places for LoRA and four for Textual Inversion -- so this interface exposes a *locked section*
 * instead of the locks: [withLoraLifecycleLock] and [withTextualInversionLifecycleLock] run a block
 * while holding them. That keeps both the lock objects and the existing nesting order out of reach,
 * while the delete paths keep the guarantee they had: the "is this artifact in use?" check and the
 * store write happen under the same lock.
 *
 * The coroutine scope is a parameter on the two import/delete entry points, because a scope belongs to
 * the caller.
 */
internal interface LocalImageAdapterPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val loraStore: LocalImageLoraStore

    val textualInversionStore: TextualInversionStore

    fun <T> withLoraLifecycleLock(block: () -> T): T

    fun <T> withTextualInversionLifecycleLock(block: () -> T): T
}

internal fun importLocalImageLoraAdapter(
    port: LocalImageAdapterPort,
    scope: CoroutineScope,
uriString: String) {
        val uri = runCatching { Uri.parse(uriString.trim()) }.getOrNull()
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) {
            port.updateUiState {
                it.copy(localImageLoraMessage = "请选择可读取的 LoRA 文档。")
            }
            return
        }
        if (port.uiState().localImageLoraImporting) return
        val existingIds = port.uiState().localImageLoras.mapTo(mutableSetOf(), LocalImageLoraRecord::id)
        port.updateUiState {
            it.copy(
                localImageLoraImporting = true,
                localImageLoraMessage = "正在导入 LoRA…"
            )
        }
        scope.launch(Dispatchers.IO) {
            runCatching { port.loraStore.import(uri) }
                .onSuccess { record ->
                    val records = port.loraStore.load()
                    port.updateUiState {
                        it.copy(
                            localImageLoras = records,
                            localImageLoraImporting = false,
                            localImageLoraMessage = if (record.id in existingIds) {
                                "相同 LoRA 已存在：${record.name}"
                            } else {
                                "已导入 LoRA：${record.name}"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    port.updateUiState {
                        it.copy(
                            localImageLoraImporting = false,
                            localImageLoraMessage = "LoRA 导入失败：${error.message ?: "文件无效"}"
                        )
                    }
                }
        }
    }

internal fun deleteLocalImageLoraAdapter(
    port: LocalImageAdapterPort,
    scope: CoroutineScope,
id: String) {
        val adapterId = id.trim()
        if (adapterId.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val deletion = port.withLoraLifecycleLock {
                if (adapterId in port.uiState().activeLocalImageLoraIds) {
                    false to "该 LoRA 正被当前图片任务使用，任务结束后才能删除。"
                } else {
                    val record = port.uiState().localImageLoras.firstOrNull { it.id == adapterId }
                    when {
                        record == null -> false to "LoRA 已不存在。"
                        port.loraStore.delete(adapterId) -> true to "已删除 LoRA：${record.name}"
                        else -> false to "LoRA 文件删除失败，索引和文件均已保留。"
                    }
                }
            }
            val records = port.loraStore.load()
            port.updateUiState {
                it.copy(
                    localImageLoras = records,
                    localImageLoraMessage = deletion.second
                )
            }
        }
    }

internal fun importLocalImageTextualInversionArtifact(
    port: LocalImageAdapterPort,
    scope: CoroutineScope,
uriString: String, trigger: String) {
        if (port.uiState().localImageTextualInversionLoading) {
            port.updateUiState {
                it.copy(localImageTextualInversionMessage = "Textual Inversion 库仍在加载，请稍后重试。")
            }
            return
        }
        val uri = runCatching { Uri.parse(uriString.trim()) }.getOrNull()
        val normalizedTrigger = trigger.trim()
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) {
            port.updateUiState {
                it.copy(localImageTextualInversionMessage = "请选择可读取的 Textual Inversion 文档。")
            }
            return
        }
        if (!TextualInversionContract.TRIGGER_PATTERN.matches(normalizedTrigger)) {
            port.updateUiState {
                it.copy(localImageTextualInversionMessage = "Textual Inversion 触发词格式无效。")
            }
            return
        }
        if (port.uiState().localImageTextualInversionImporting) return
        port.updateUiState {
            it.copy(
                localImageTextualInversionImporting = true,
                localImageTextualInversionMessage = "正在导入 Textual Inversion…"
            )
        }
        scope.launch(Dispatchers.IO) {
            try {
                val imported = port.textualInversionStore.importFromContentUri(
                    uri = uri,
                    trigger = normalizedTrigger
                )
                val records = port.textualInversionStore.load()
                port.updateUiState {
                    it.copy(
                        localImageTextualInversions = records,
                        localImageTextualInversionImporting = false,
                        localImageTextualInversionMessage = if (imported.duplicate) {
                            "相同 Textual Inversion 已存在：${imported.artifact.name}"
                        } else {
                            "已导入 Textual Inversion：${imported.artifact.name}"
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                port.updateUiState {
                    it.copy(
                        localImageTextualInversionImporting = false,
                        localImageTextualInversionMessage =
                            "Textual Inversion 导入失败：${error.message ?: "文件无效"}"
                    )
                }
            }
        }
    }

internal fun deleteLocalImageTextualInversionArtifact(
    port: LocalImageAdapterPort,
    scope: CoroutineScope,
id: String) {
        if (port.uiState().localImageTextualInversionLoading) {
            port.updateUiState {
                it.copy(localImageTextualInversionMessage = "Textual Inversion 库仍在加载，请稍后重试。")
            }
            return
        }
        val artifactId = runCatching { UUID.fromString(id.trim()).toString() }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val reserved = port.withTextualInversionLifecycleLock {
                val state = port.uiState()
                when {
                    artifactId in state.activeLocalImageTextualInversionIds -> false
                    artifactId in state.deletingLocalImageTextualInversionIds -> false
                    else -> {
                        port.updateUiState {
                            it.copy(
                                deletingLocalImageTextualInversionIds =
                                    it.deletingLocalImageTextualInversionIds + artifactId
                            )
                        }
                        true
                    }
                }
            }
            if (!reserved) {
                port.updateUiState {
                    it.copy(
                        localImageTextualInversionMessage =
                            "该 Textual Inversion 正被任务使用或删除中。"
                    )
                }
                return@launch
            }
            try {
                val record = port.uiState().localImageTextualInversions
                    .firstOrNull { it.id == artifactId }
                val deleted = port.textualInversionStore.clear(artifactId)
                val records = port.textualInversionStore.load()
                port.updateUiState {
                    it.copy(
                        localImageTextualInversions = records,
                        localImageTextualInversionMessage = when {
                            record == null || !deleted -> "Textual Inversion 已不存在。"
                            else -> "已删除 Textual Inversion：${record.name}"
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                port.updateUiState {
                    it.copy(
                        localImageTextualInversionMessage =
                            "Textual Inversion 删除失败：${error.message ?: "文件已保留"}"
                    )
                }
            } finally {
                port.withTextualInversionLifecycleLock {
                    port.updateUiState {
                        it.copy(
                            deletingLocalImageTextualInversionIds =
                                it.deletingLocalImageTextualInversionIds - artifactId
                        )
                    }
                }
            }
        }
    }

package com.muyuchat.mca

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun downloadOfficialViggleLoraFor(
    store: UiStateWriter<LocalImageAdapterFields>,
    scope: CoroutineScope,
    localImageLoraStore: LocalImageLoraStore
) {
    if (store.state.value.localImageLoraImporting) return
    store.update {
        it.copy(
            localImageLoraImporting = true,
            localImageLoraMessage = "正在下载 Viggle v0.2.1 LoRA…"
        )
    }
    scope.launch(Dispatchers.IO) {
        var lastReported = -1L
        runCatching {
            ViggleV021LoraDownloader.download(localImageLoraStore) { bytes ->
                val mb = bytes / (1024L * 1024L)
                if (mb != lastReported) {
                    lastReported = mb
                    store.update {
                        it.copy(
                            localImageLoraMessage =
                                "正在下载 Viggle v0.2.1 LoRA… ${bytes * 100 / ViggleV021LoraDownloader.EXPECTED_SIZE_BYTES}%"
                        )
                    }
                }
            }
        }.onSuccess { record ->
            store.update {
                it.copy(
                    localImageLoras = localImageLoraStore.load(),
                    localImageLoraImporting = false,
                    localImageLoraMessage = "已下载并校验 Viggle v0.2.1 LoRA：${record.name}"
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            store.update {
                it.copy(
                    localImageLoraImporting = false,
                    localImageLoraMessage = "Viggle LoRA 下载失败：${error.message ?: "网络或文件校验错误"}"
                )
            }
        }
    }
}

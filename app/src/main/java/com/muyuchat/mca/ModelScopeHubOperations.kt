package com.muyuchat.mca

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Operations for ModelScope Hub exploration, search, recommendation inspection,
 * and external page navigation.
 */
internal fun fetchRemoteFilesFor(
    scope: CoroutineScope,
    store: UiStateStore,
    client: ModelScopeClient,
    busy: (String) -> Unit,
    fail: (String) -> Unit,
    repoInput: String
) {
    scope.launch(Dispatchers.IO) {
        busy("正在查询 ModelScope 本地推理文件...")
        runCatching {
            client.listEngineFiles(repoInput)
        }.onSuccess { files ->
            store.update {
                it.copy(remoteFiles = files, busy = false, statusMessage = "找到 ${files.size} 个本地推理文件")
            }
        }.onFailure { error ->
            fail("查询失败：${error.message}")
        }
    }
}

internal fun fetchRecommendedFilesFor(
    scope: CoroutineScope,
    store: UiStateStore,
    client: ModelScopeClient,
    busy: (String) -> Unit,
    fail: (String) -> Unit,
    showStatus: (String) -> Unit,
    deviceProfile: DeviceProfile,
    model: ModelScopeRecommendedModel
) {
    recommendedDownloadBlockReason(model)?.let { reason ->
        showStatus(reason)
        return
    }
    scope.launch(Dispatchers.IO) {
        busy("正在读取推荐模型：${model.title}...")
        runCatching {
            client.listRecommendedFiles(
                model = model,
                preferredQairtChipsets = preferredQairtChipsets(deviceProfile)
            )
        }.onSuccess { files ->
            store.update {
                it.copy(
                    repoInput = model.repoId,
                    remoteFiles = files,
                    busy = false,
                    statusMessage = "已列出 ${model.title} 的 ${files.size} 个模型组件"
                )
            }
        }.onFailure { error ->
            fail("推荐模型读取失败：${error.message}")
        }
    }
}

internal fun downloadRecommendedFor(
    model: ModelScopeRecommendedModel,
    useAfterDownload: Boolean,
    fail: (String) -> Unit,
    enqueueManagedDownload: (ManagedDownloadRequest, Boolean) -> Unit
) {
    val blocked = recommendedDownloadBlockReason(model)
    if (blocked != null) {
        fail(blocked)
        return
    }
    enqueueManagedDownload(ManagedDownloadRequest.recommended(model), useAfterDownload)
}

internal fun recommendedDownloadBlockReason(model: ModelScopeRecommendedModel): String? {
    return if (model.downloadable) null else model.downloadBlockReason ?: "该模型暂不提供下载。"
}

internal fun searchHubModelsFor(
    scope: CoroutineScope,
    store: UiStateStore,
    client: ModelScopeClient,
    busy: (String) -> Unit,
    fail: (String) -> Unit,
    hubQuery: String,
    hubPage: Int,
    currentHubModels: List<ModelScopeHubModel>,
    reset: Boolean
) {
    val query = hubQuery.ifBlank { "MNN Qwen3.5" }
    val nextPage = if (reset) 1 else hubPage + 1
    scope.launch(Dispatchers.IO) {
        busy("正在从魔塔 API 拉取模型列表...")
        runCatching {
            client.searchModels(query = query, pageNumber = nextPage)
        }.onSuccess { result ->
            store.update {
                it.copy(
                    hubModels = if (reset) result.models else currentHubModels + result.models,
                    hubPage = result.pageNumber,
                    hubTotalCount = result.totalCount,
                    busy = false,
                    statusMessage = "魔塔模型：第 ${result.pageNumber} 页，已显示 ${if (reset) result.models.size else currentHubModels.size + result.models.size}/${result.totalCount}"
                )
            }
        }.onFailure { error ->
            fail("魔塔模型搜索失败：${error.message}")
        }
    }
}

internal fun fetchHubModelFilesFor(
    scope: CoroutineScope,
    store: UiStateStore,
    client: ModelScopeClient,
    busy: (String) -> Unit,
    fail: (String) -> Unit,
    model: ModelScopeHubModel
) {
    store.update { it.copy(repoInput = model.id) }
    scope.launch(Dispatchers.IO) {
        busy("正在读取 ${model.displayName} 的本地推理文件...")
        runCatching {
            client.listEngineFiles(model.id)
        }.onSuccess { files ->
            store.update {
                it.copy(
                    repoInput = model.id,
                    remoteFiles = files,
                    busy = false,
                    statusMessage = "已列出 ${model.displayName} 的 ${files.size} 个本地推理文件"
                )
            }
        }.onFailure { error ->
            fail("读取模型文件失败：${error.message}")
        }
    }
}

internal fun openModelScopePageFor(
    application: Application,
    fail: (String) -> Unit,
    url: String
) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        application.startActivity(intent)
    }.onFailure { error ->
        fail("无法打开浏览器：${error.message}")
    }
}

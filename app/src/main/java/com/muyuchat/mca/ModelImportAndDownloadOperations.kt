package com.muyuchat.mca

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RemoteModelFileKind
import com.muyuchat.core.download.fileKind
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource
import com.muyuchat.feature.modelhub.ModelHubDownloadPhase
import com.muyuchat.feature.modelhub.ModelImportTaskUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal const val DOWNLOAD_REQUESTS_PREFERENCES = "mca_download_requests"

/**
 * 顶层抽取：模型身份与会话引用迁移对齐。
 */
internal suspend fun reconcileModelIdentitiesFor(
    chatSessionPersistenceMutex: Mutex,
    modelIdentityReferenceMigration: ModelIdentityReferenceMigration,
    store: UiStateStore,
    getDurableChatSessions: () -> List<ChatSessionRecord>,
    setDurableChatSessions: (List<ChatSessionRecord>) -> Unit
) = chatSessionPersistenceMutex.withLock {
    val result = modelIdentityReferenceMigration.reconcileAndMigrate()
    if (result.idMapping.isNotEmpty()) {
        fun canonical(id: String?): String? = result.idMapping[id] ?: id
        store.update { state ->
            state.copy(
                models = result.models,
                loadedModelId = canonical(state.loadedModelId),
                chatSessions = state.chatSessions.map { it.copy(modelId = canonical(it.modelId)) },
                assistants = state.assistants.map { it.copy(defaultModelId = canonical(it.defaultModelId)) }
            )
        }
        setDurableChatSessions(getDurableChatSessions().map { it.copy(modelId = canonical(it.modelId)) })
    }
}

/**
 * R4-S17：模型导入、下载与多模态投影整族下沉 (ModelImportAndDownloadOperations)。
 * 统一管理后台模型下载/导入任务生命周期、WorkManager 状态收集、断点续传、本地模型对齐与视觉投影器绑定。
 */
internal class ModelImportAndDownloadCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateStore,
    private val modelStore: ModelStoreRepository,
    private val localImageModelStore: LocalImageModelStore,
    private val getManagedRuntimeReadinessRefreshGate: () -> ManagedRuntimeReadinessRefreshGate,
    private val getManagedRuntimeReadinessPort: () -> ManagedRuntimeReadinessPort,
    private val chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore,
    private val getInitialChatSessionIds: () -> List<String>,
    private val showStatus: (String) -> Unit,
    private val failDownload: (String) -> Unit,
    private val busy: (String) -> Unit,
    private val fail: (String) -> Unit,
    private val loadModel: (ModelManifest) -> Unit,
    private val reconcileModelIdentities: suspend () -> Unit
) {
    private val autoUseManagedDownloads = ConcurrentHashMap.newKeySet<String>()
    private var managedDownloadOwnedBusy = false

    fun refreshLocalModels() {
        scope.launch(Dispatchers.IO) {
            reconcileModelIdentities()
            refreshRuntimeReadiness(getManagedRuntimeReadinessPort())
        }
    }

    fun importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) {
        if (uris.isEmpty()) return
        enqueueManagedImport(
            ManagedModelImportRequest(
                uris = uris.distinct().map(Uri::toString),
                textOnly = textOnly,
                persistentAccess = persistentAccess
            )
        )
    }

    fun importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) {
        enqueueManagedImport(
            ManagedModelImportRequest(
                uris = listOf(uri.toString()),
                directory = true,
                textOnly = textOnly,
                persistentAccess = persistentAccess
            )
        )
    }

    fun pauseManagedImport(requestId: String) {
        launchImportOperation {
            WorkManager.getInstance(application)
                .cancelUniqueWork("${ManagedModelImportWorker.TAG}-$requestId")
        }
    }

    fun resumeManagedImport(requestId: String) {
        launchImportOperation {
            ManagedModelImportWorker.enqueue(application, ManagedModelImportRequest.read(application.filesDir, requestId))
        }
    }

    fun download(remote: RemoteModelFile, loadedModelId: String?) {
        enqueueManagedDownload(
            ManagedDownloadRequest(
                remote = remote,
                projectorTargetId = if (remote.fileKind() == RemoteModelFileKind.PROJECTOR) loadedModelId else null
            ),
            useAfterDownload = false
        )
    }

    fun pauseManagedDownloads() {
        launchModelOperation("暂停下载失败") {
            WorkManager.getInstance(application)
                .cancelAllWorkByTag(ManagedModelDownloadWorker.TAG).result.get()
        }
    }

    fun resumeManagedDownloads() {
        launchModelOperation("继续下载失败") {
            val workManager = WorkManager.getInstance(application)
            val requestPreferences = application.getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
            val requests = requestPreferences.all.mapNotNull { (key, raw) ->
                if (key.startsWith("use:")) return@mapNotNull null
                (raw as? String)?.let { value ->
                    runCatching { ManagedDownloadRequest.fromJson(value) }.getOrNull()
                }
            }
            require(requests.isNotEmpty()) { "没有可恢复的下载任务。" }
            var resumed = 0
            requests.forEach { request ->
                val infos = workManager
                    .getWorkInfosForUniqueWork("${ManagedModelDownloadWorker.TAG}-${request.identity}")
                    .get()
                val hasLiveWork = infos.any { info ->
                    info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.ENQUEUED ||
                        info.state == WorkInfo.State.BLOCKED
                }
                if (!hasLiveWork) {
                    if (requestPreferences.getBoolean("use:${request.identity}", false)) {
                        autoUseManagedDownloads.add(request.identity)
                    }
                    ManagedModelDownloadWorker.enqueue(application, request)
                    resumed++
                }
            }
            require(resumed > 0) { "下载任务仍在运行，或没有可恢复任务。" }
            showStatus("已继续下载，保留的临时文件将尝试断点续传。")
        }
    }

    fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) {
        launchModelOperation("无法开始下载") {
            application.getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(request.identity, request.toJson())
                .putBoolean("use:${request.identity}", useAfterDownload)
                .apply()
            if (useAfterDownload) autoUseManagedDownloads.add(request.identity)
            store.update { it.copy(
                downloadStatus = DownloadStatus.QUEUED,
                downloadTaskId = request.identity,
                downloadPhase = ModelHubDownloadPhase.QUEUED,
                downloadFailureSource = null,
                downloadedBytes = 0L,
                downloadTotalBytes = 0L,
                downloadSpeedBytesPerSecond = 0L,
                downloadRemainingSeconds = null,
                downloadFileName = request.remote?.name ?: request.recommendationId,
                downloadIntegrityStatus = "RUNNING",
                downloadIntegrityMessage = "等待文件下载完成后校验。",
                downloadExecutionStatus = "PENDING",
                downloadExecutionMessage = "等待安装阶段读取本机兼容性。",
                statusMessage = "下载任务已保存，网络可用时会继续；可暂停并保留进度。"
            ) }
            ManagedModelDownloadWorker.enqueue(application, request)
        }
    }

    fun attachVisionProjector(modelId: String, uri: Uri, loadedModelId: String?, selectedChatBackend: ChatBackend) {
        launchModelOperation("模型操作未完成") {
            busy("正在绑定本地视觉投影器...")
            val shouldReload = loadedModelId == modelId && selectedChatBackend == ChatBackend.LOCAL
            runCatching {
                modelStore.attachVisionProjector(modelId, uri)
            }.onSuccess { model ->
                getManagedRuntimeReadinessRefreshGate().invalidate()
                store.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        statusMessage = if (shouldReload) {
                            "已绑定视觉投影器：${model.visionProjectorFileName ?: "mmproj"}，正在重新加载模型以启用本地识图。"
                        } else {
                            "已绑定视觉投影器：${model.visionProjectorFileName ?: "mmproj"}，下次加载该模型后可本地识图。"
                        }
                    )
                }
                if (shouldReload) {
                    loadModel(model)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail("视觉文件绑定失败：${error.message}")
            }
        }
    }

    private fun enqueueManagedImport(request: ManagedModelImportRequest) {
        launchImportOperation {
            ManagedModelImportWorker.enqueue(application, request)
            store.update { it.copy(statusMessage = if (request.persistentAccess)
                "导入任务已保存，可在本地模型页查看进度或暂停；已完成组件可恢复。"
                else "导入已开始。此文件提供方不支持保留访问权限，重启后可能需要重新选择源文件。") }
        }
    }

    private fun launchImportOperation(operation: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                showStatus("导入未完成：${error.message}。请在本地模型页重试或重新选择源文件。")
            }
        }
        scope.launch(Dispatchers.IO) {
            chatImageToolAuthorizationStore.prune(getInitialChatSessionIds())
        }
    }

    fun observeManagedImports() {
        launchImportOperation {
            val receipts = application.getSharedPreferences("mca_import_receipts", Context.MODE_PRIVATE)
            val handled = receipts.getStringSet("handled", emptySet()).orEmpty().toMutableSet()
            WorkManager.getInstance(application).getWorkInfosByTagFlow(ManagedModelImportWorker.TAG).collect { infos ->
                val tasks = infos.sortedByDescending { info ->
                    info.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
                }.mapNotNull { info ->
                    info.tags.firstOrNull { it.startsWith(ManagedModelImportWorker.REQUEST_TAG_PREFIX) }
                        ?.removePrefix(ManagedModelImportWorker.REQUEST_TAG_PREFIX)?.let { it to info }
                }.distinctBy { it.first }
                val rows = tasks.map { (requestId, info) ->
                    val active = !info.state.isFinished
                    val message = when (info.state) {
                        WorkInfo.State.SUCCEEDED -> info.outputData.getString("message") ?: "模型导入完成"
                        WorkInfo.State.FAILED -> info.outputData.getString("error") ?: "导入未完成，请检查源文件和存储空间后重试。"
                        WorkInfo.State.CANCELLED -> "导入已暂停，可恢复已完成组件"
                        else -> info.progress.getString("message") ?: "正在等待导入"
                    }
                    ModelImportTaskUi(
                        id = requestId, message = message, file = info.progress.getString("file"),
                        bytes = info.progress.getLong("bytes", 0), totalBytes = info.progress.getLong("total", 0),
                        active = active, resumable = info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED
                    )
                }
                store.update { it.copy(importTasks = rows.filter { row -> row.active || row.resumable } +
                    rows.filter { row -> !row.active && !row.resumable }.take(3)) }
                val completed = tasks.map { it.second }.filter {
                    it.state == WorkInfo.State.SUCCEEDED && it.id.toString() !in handled
                }
                if (completed.isNotEmpty()) {
                    getManagedRuntimeReadinessRefreshGate().invalidate()
                    reconcileModelIdentities()
                    val models = modelStore.listModels()
                    store.update { it.copy(models = models) }
                    handled.addAll(completed.map { it.id.toString() })
                    receipts.edit().putStringSet("handled", handled.toSet()).apply()
                }
            }
        }
    }

    fun observeManagedDownloads(onImageDownloaded: (LocalImageModelRecord) -> Unit) {
        scope.launch {
            BackgroundDownloadHealth.failure.collect { message ->
                if (message != null) failDownload(message)
            }
        }
        launchModelOperation("下载任务恢复失败，请在模型页重试") {
            val acknowledgements = application.getSharedPreferences("mca_download_receipts", Context.MODE_PRIVATE)
            val handled = acknowledgements.getStringSet("handled", emptySet()).orEmpty().toMutableSet()
            WorkManager.getInstance(application)
                .getWorkInfosByTagFlow(ManagedModelDownloadWorker.TAG).collect { infos ->
                    val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?: infos.firstOrNull { !it.state.isFinished }
                    if (active != null) {
                        val progress = active.progress
                        store.update { state ->
                            if (!state.busy) managedDownloadOwnedBusy = true
                            state.copy(
                                busy = true,
                                downloadTaskId = active.tags.firstOrNull { tag ->
                                    tag.matches(Regex("[0-9a-f]{64}"))
                                } ?: active.id.toString(),
                                downloadStatus = if (active.state == WorkInfo.State.RUNNING) DownloadStatus.RUNNING else DownloadStatus.QUEUED,
                                downloadPhase = progress.getString("phase")?.let { name ->
                                    runCatching { ModelHubDownloadPhase.valueOf(name) }.getOrNull()
                                } ?: if (active.state == WorkInfo.State.RUNNING) {
                                    ModelHubDownloadPhase.DOWNLOADING
                                } else {
                                    ModelHubDownloadPhase.QUEUED
                                },
                                downloadFailureSource = progress.getString("failureSource")?.let { name ->
                                    runCatching { ModelHubDownloadFailureSource.valueOf(name) }.getOrNull()
                                },
                                downloadFileName = progress.getString("file") ?: state.downloadFileName,
                                downloadedBytes = progress.getLong("bytes", 0),
                                downloadTotalBytes = progress.getLong("total", 0),
                                downloadSpeedBytesPerSecond = if (active.state == WorkInfo.State.RUNNING) progress.getLong("speed", 0) else 0L,
                                downloadRemainingSeconds = if (active.state == WorkInfo.State.RUNNING) {
                                    progress.getLong("remainingSeconds", -1L).takeIf { it >= 0L }
                                } else null,
                                statusMessage = progress.getString("message") ?: "下载任务已保留，正在等待网络或其他下载完成…",
                                downloadIntegrityStatus = progress.getString("integrityStatus") ?: state.downloadIntegrityStatus,
                                downloadIntegrityMessage = progress.getString("integrityMessage") ?: state.downloadIntegrityMessage,
                                downloadExecutionStatus = progress.getString("executionStatus") ?: state.downloadExecutionStatus,
                                downloadExecutionMessage = progress.getString("executionMessage") ?: state.downloadExecutionMessage
                            )
                        }
                    }
                    infos.filter { it.state.isFinished && it.id.toString() !in handled }
                        .sortedBy { work -> work.tags.firstOrNull { it.startsWith("created:") }?.removePrefix("created:")?.toLongOrNull() ?: 0L }.forEach { info ->
                        recoverModelOperation(operation = {
                            val error = info.outputData.getString("error")
                            if (info.state == WorkInfo.State.SUCCEEDED && error == null) {
                                info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }?.let { identity ->
                                    application.getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
                                        .edit().remove(identity).remove("use:$identity").apply()
                                }
                                reconcileModelIdentities()
                                refreshRuntimeReadiness(getManagedRuntimeReadinessPort())
                                val imageId = info.outputData.getString("imageId")
                                val image = imageId?.let { id -> localImageModelStore.loadModels(discover = false).firstOrNull { it.matchesCatalogId(id) } }
                                if (image != null) {
                                    onImageDownloaded(image)
                                }
                                if (active == null) {
                                    store.update { it.copy(
                                        busy = if (managedDownloadOwnedBusy) false else it.busy,
                                        downloadStatus = DownloadStatus.DONE,
                                        downloadSpeedBytesPerSecond = 0,
                                        downloadTaskId = info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }
                                            ?: info.id.toString(),
                                        downloadPhase = ModelHubDownloadPhase.COMPLETED,
                                        downloadFailureSource = null,
                                        downloadRemainingSeconds = null,
                                        statusMessage = info.outputData.getString("message"),
                                        downloadIntegrityStatus = info.outputData.getString("integrityStatus") ?: it.downloadIntegrityStatus,
                                        downloadIntegrityMessage = info.outputData.getString("integrityMessage") ?: it.downloadIntegrityMessage,
                                        downloadExecutionStatus = info.outputData.getString("executionStatus") ?: it.downloadExecutionStatus,
                                        downloadExecutionMessage = info.outputData.getString("executionMessage") ?: it.downloadExecutionMessage
                                    ) }
                                    managedDownloadOwnedBusy = false
                                }
                                val model = info.outputData.getString("modelId")?.let(modelStore::getModel)
                                if (model != null) {
                                    val projectorDownloaded = info.outputData.getBoolean("projector", false)
                                    val reloadLoadedVisionModel = projectorDownloaded &&
                                        store.current.loadedModelId == model.id &&
                                        store.current.selectedChatBackend == ChatBackend.LOCAL
                                    if (reloadLoadedVisionModel) {
                                        store.update {
                                            it.copy(statusMessage = "视觉投影器已下载，正在重新加载模型以启用识图…")
                                        }
                                        loadModel(model)
                                    } else {
                                        useDownloadedChatModel(model, info.tags.any { autoUseManagedDownloads.remove(it) })
                                    }
                                }
                            } else if (active == null) {
                                store.update { it.copy(
                                    busy = if (managedDownloadOwnedBusy) false else it.busy,
                                    downloadStatus = if (info.state == WorkInfo.State.CANCELLED) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                                    downloadTaskId = info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }
                                        ?: info.id.toString(),
                                    downloadPhase = info.outputData.getString("phase")?.let { name ->
                                        runCatching { ModelHubDownloadPhase.valueOf(name) }.getOrNull()
                                    } ?: it.downloadPhase,
                                    downloadFailureSource = info.outputData.getString("failureSource")?.let { name ->
                                        runCatching { ModelHubDownloadFailureSource.valueOf(name) }.getOrNull()
                                    } ?: if (info.state == WorkInfo.State.CANCELLED) null
                                    else ModelHubDownloadFailureSource.DOWNLOAD,
                                    downloadSpeedBytesPerSecond = 0,
                                    downloadRemainingSeconds = null,
                                    statusMessage = error ?: "下载已暂停，进度已保留。再次点击下载可继续。",
                                    downloadIntegrityStatus = info.outputData.getString("integrityStatus") ?: it.downloadIntegrityStatus,
                                    downloadIntegrityMessage = info.outputData.getString("integrityMessage") ?: it.downloadIntegrityMessage,
                                    downloadExecutionStatus = info.outputData.getString("executionStatus") ?: it.downloadExecutionStatus,
                                    downloadExecutionMessage = info.outputData.getString("executionMessage") ?: it.downloadExecutionMessage
                                ) }
                                managedDownloadOwnedBusy = false
                            }
                        }, onFailure = { error ->
                            failDownload("模型文件已保留，但刷新列表失败：${error.message}。请释放存储空间后刷新本地模型。")
                        })
                        handled.add(info.id.toString())
                        acknowledgements.edit().putStringSet("handled", handled.toList().takeLast(128).toSet()).apply()
                    }
                }
        }
    }

    private fun launchModelOperation(message: String, operation: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            recoverModelOperation(operation = { operation() }, onFailure = { error ->
                Log.e("McaModelOperation", message, error)
                store.update { state ->
                    state.copy(
                        busy = false,
                        statusMessage = "$message：${error.message.orEmpty()}。请检查模型文件和存储空间后重试。",
                        engineLifecycle = if (state.engineLifecycle == AgentEngineLifecycle.LOADING) {
                            if (state.stats.loaded) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR
                        } else state.engineLifecycle
                    )
                }
            })
        }

    private fun useDownloadedChatModel(model: ModelManifest, requested: Boolean) {
        val state = store.current
        if (requested && !state.busy && !state.isGenerating && state.loadedModelId == null &&
            state.selectedChatBackend == ChatBackend.LOCAL && state.tab == AppTab.MODELS) {
            loadModel(model)
        }
    }
}

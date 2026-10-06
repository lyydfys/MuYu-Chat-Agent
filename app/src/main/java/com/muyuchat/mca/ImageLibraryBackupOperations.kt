package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ImageLibraryBackupState(
    val running: Boolean = false,
    val importing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val failed: Boolean = false
)

internal fun Throwable.imageLibraryBackupFailureMessage(): String = when (this) {
    is ImageLibraryBackupFormatException ->
        "备份文件格式无效：${message ?: "清单或文件校验失败"}"
    else -> "图片库备份失败：${message ?: "未知错误"}"
}

internal fun deleteIncompleteImageLibraryBackup(
    application: Application,
    destination: Uri
): Boolean =
    runCatching {
        DocumentsContract.deleteDocument(
            application.contentResolver,
            destination
        )
    }.getOrDefault(false)

internal fun startImageLibraryBackupJobFor(
    store: UiStateStore,
    scope: CoroutineScope,
    lifecycleLock: Any,
    sequence: AtomicLong,
    getActiveJob: () -> Job?,
    setActiveJob: (Job?) -> Unit,
    reservation: ImageLibraryBackupState,
    operation: suspend () -> Unit
): Boolean =
    synchronized(lifecycleLock) {
        val imageLibraryBackupJob = getActiveJob()
        if (imageLibraryBackupJob?.isCompleted == false) return@synchronized false
        val epoch = sequence.incrementAndGet()
        lateinit var launchedJob: Job
        launchedJob = scope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY
        ) {
            try {
                operation()
            } finally {
                synchronized(lifecycleLock) {
                    if (sequence.get() == epoch &&
                        getActiveJob() === launchedJob
                    ) {
                        setActiveJob(null)
                    }
                }
            }
        }
        setActiveJob(launchedJob)
        store.update { state -> state.copy(imageLibraryBackup = reservation) }
        launchedJob.start()
        true
    }

internal fun exportImageLibraryBackupFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    imageLibraryBackup: ImageLibraryBackup,
    imageLibraryMutationMutex: Mutex,
    awaitStartupReconciliation: suspend () -> Unit,
    lifecycleLock: Any,
    sequence: AtomicLong,
    getActiveJob: () -> Job?,
    setActiveJob: (Job?) -> Unit,
    showStatus: (String) -> Unit,
    uriString: String,
    favoritesOnly: Boolean
) {
    val destination = runCatching { Uri.parse(uriString) }
        .getOrNull()
        ?.takeIf { !it.scheme.isNullOrBlank() }
    if (destination == null) {
        store.update {
            it.copy(
                imageLibraryBackup = ImageLibraryBackupState(
                    message = "备份目标无效",
                    failed = true
                )
            )
        }
        return
    }
    fun deleteIncompleteImageLibraryBackup(destination: Uri): Boolean =
        deleteIncompleteImageLibraryBackup(application, destination)

    val started = startImageLibraryBackupJobFor(
        store = store,
        scope = scope,
        lifecycleLock = lifecycleLock,
        sequence = sequence,
        getActiveJob = getActiveJob,
        setActiveJob = setActiveJob,
        reservation = ImageLibraryBackupState(
            running = true,
            importing = false,
            total = 0
        )
    ) {
        try {
            awaitStartupReconciliation()
            val result = imageLibraryMutationMutex.withLock {
                val snapshot = store.state.value.images
                val total = snapshot.count { !favoritesOnly || it.favorite }
                store.update { state ->
                    state.copy(
                        imageLibraryBackup = state.imageLibraryBackup.copy(total = total)
                    )
                }
                imageLibraryBackup.export(
                    destination = destination,
                    images = snapshot,
                    favoritesOnly = favoritesOnly
                ) { done, progressTotal ->
                    store.update { state ->
                        state.copy(
                            imageLibraryBackup = state.imageLibraryBackup.copy(
                                done = done,
                                total = progressTotal
                            )
                        )
                    }
                }
            }
            val message = buildString {
                append("已导出 ")
                append(result.exported)
                append(" 张图片")
                if (result.skipped > 0) {
                    append("；跳过 ")
                    append(result.skipped)
                    append(" 个缺失或无效文件")
                }
            }
            store.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(message = message),
                    statusMessage = message
                )
            }
        } catch (error: CancellationException) {
            val deleted = withContext(NonCancellable + Dispatchers.IO) {
                deleteIncompleteImageLibraryBackup(destination)
            }
            store.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(
                        message = if (deleted) {
                            "已取消导出"
                        } else {
                            "已取消导出；未能删除不完整的备份文件"
                        },
                        failed = !deleted
                    )
                )
            }
            throw error
        } catch (error: Throwable) {
            val deleted = withContext(Dispatchers.IO) {
                deleteIncompleteImageLibraryBackup(destination)
            }
            val message = buildString {
                append(error.imageLibraryBackupFailureMessage())
                if (!deleted) append("；未能删除不完整的备份文件")
            }
            store.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(message = message, failed = true),
                    statusMessage = message
                )
            }
        }
    }
    if (!started) {
        showStatus("图片库备份任务正在进行")
    }
}

internal fun importImageLibraryBackupFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    imageLibraryBackup: ImageLibraryBackup,
    chatSessionStore: ChatSessionStore,
    imageAssetWriteMutex: Mutex,
    imageLibraryMutationMutex: Mutex,
    awaitStartupReconciliation: suspend () -> Unit,
    lifecycleLock: Any,
    sequence: AtomicLong,
    getActiveJob: () -> Job?,
    setActiveJob: (Job?) -> Unit,
    showStatus: (String) -> Unit,
    uriString: String
) {
    val source = runCatching { Uri.parse(uriString) }
        .getOrNull()
        ?.takeIf { !it.scheme.isNullOrBlank() }
    if (source == null) {
        store.update {
            it.copy(
                imageLibraryBackup = ImageLibraryBackupState(
                    message = "备份文件无效",
                    failed = true
                )
            )
        }
        return
    }
    val started = startImageLibraryBackupJobFor(
        store = store,
        scope = scope,
        lifecycleLock = lifecycleLock,
        sequence = sequence,
        getActiveJob = getActiveJob,
        setActiveJob = setActiveJob,
        reservation = ImageLibraryBackupState(
            running = true,
            importing = true
        )
    ) {
        try {
            awaitStartupReconciliation()
            val installedModelIds = store.state.value.localImageModels.mapTo(mutableSetOf()) { it.id }
                .apply {
                    addAll(
                        store.state.value.cloudModels
                            .filter { it.kind == CloudModelKind.IMAGE }
                            .map { it.id }
                    )
                }
            val result = imageAssetWriteMutex.withLock {
                imageLibraryMutationMutex.withLock {
                    val imported = imageLibraryBackup.import(
                        source = source,
                        existingImages = store.state.value.images,
                        installedModelIds = installedModelIds
                    ) { done, total ->
                        store.update { state ->
                            state.copy(
                                imageLibraryBackup = state.imageLibraryBackup.copy(
                                    done = done,
                                    total = total
                                )
                            )
                        }
                    }
                    val restoredImages = chatSessionStore.loadImages().sortedImagesForLibrary()
                    store.update { it.copy(images = restoredImages) }
                    imported
                }
            }
            val message = buildString {
                append("已恢复 ")
                append(result.imported)
                append(" 张图片")
                if (result.duplicates > 0) {
                    append("；跳过重复 ")
                    append(result.duplicates)
                    append(" 张")
                }
                if (result.failed > 0) {
                    append("；失败 ")
                    append(result.failed)
                    append(" 张")
                }
                if (result.missingModelIds.isNotEmpty()) {
                    append("；")
                    append(result.missingModelIds.size)
                    append(" 个原模型当前未安装，历史仍已保留")
                }
            }
            store.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(message = message),
                    statusMessage = message
                )
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                val restoredImages = chatSessionStore.loadImages().sortedImagesForLibrary()
                store.update {
                    it.copy(
                        images = restoredImages,
                        imageLibraryBackup = ImageLibraryBackupState(
                            message = "已取消恢复；已完成提交的图片仍会保留"
                        )
                    )
                }
            }
            throw error
        } catch (error: Throwable) {
            val message = error.imageLibraryBackupFailureMessage()
            store.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(message = message, failed = true),
                    statusMessage = message
                )
            }
        }
    }
    if (!started) {
        showStatus("图片库备份任务正在进行")
    }
}

internal fun cancelImageLibraryBackupFor(
    lifecycleLock: Any,
    getActiveJob: () -> Job?
) {
    synchronized(lifecycleLock) { getActiveJob() }?.cancel()
}

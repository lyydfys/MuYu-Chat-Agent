package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.muyuchat.core.modelstore.MnnImportMode
import com.muyuchat.core.modelstore.ModelImportOptions
import com.muyuchat.core.modelstore.ModelImportProgress
import com.muyuchat.core.modelstore.ModelStoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun modelImportProgressMessage(progress: ModelImportProgress): String {
    val stage = when (progress.stage) {
        "inspecting" -> "正在读取目录和模型配置"
        "copying" -> "正在复制模型组件"
        "verifying" -> "正在校验文件完整性"
        "committing" -> "正在加入本地模型"
        "completed" -> "模型导入完成"
        else -> "正在准备导入"
    }
    return stage + if (progress.fileCount > 0) "（${progress.fileIndex}/${progress.fileCount}）" else ""
}

class ManagedModelImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val request = ManagedModelImportRequest.read(applicationContext.filesDir, requireNotNull(inputData.getString(REQUEST_ID)))
            setForeground(foreground("正在等待导入…"))
            importMutex.withLock {
                coroutineScope {
                    val operationContext = coroutineContext
                    val progress = MutableStateFlow(ModelImportProgress("inspecting"))
                    val reporter = launch {
                        progress.sample(500).collectLatest { value ->
                            val message = modelImportProgressMessage(value)
                            setProgress(workDataOf("message" to message, "file" to value.relativePath?.take(500),
                                "bytes" to value.copiedBytes, "total" to (value.totalBytes ?: 0L)))
                            setForeground(foreground(message))
                        }
                    }
                    try {
                        val repository = ModelStoreRepository(applicationContext)
                        val options = ModelImportOptions(
                            mnnMode = if (request.textOnly) MnnImportMode.TEXT_ONLY else MnnImportMode.FULL,
                            resumeKey = request.id
                        )
                        val checkCancelled = {
                            operationContext.ensureActive()
                            if (isStopped) throw CancellationException("模型导入已暂停")
                        }
                        val model = if (request.directory) repository.importFromTreeUri(
                            Uri.parse(request.uris.single()), options = options,
                            onProgress = { progress.value = it }, checkCancelled = checkCancelled
                        ) else repository.importFromUris(
                            request.uris.map(Uri::parse), options = options,
                            onProgress = { progress.value = it }, checkCancelled = checkCancelled
                        )
                        Result.success(workDataOf("modelId" to model.id,
                            "message" to "已导入${model.runtime.label}：${model.displayName.take(500)}"))
                    } finally {
                        reporter.cancel()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.e("McaModelImport", "Model import failed", error)
            val detail = if (error is SecurityException) "源文件访问权限已失效，请重新选择模型目录或文件。"
                else "${error.message.orEmpty().take(900)}。请检查缺失组件、源文件权限和存储空间后重试。"
            Result.failure(workDataOf("error" to "导入未完成：$detail"))
        }
    }

    private fun foreground(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "模型导入", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MCA 模型导入")
            .setContentText(message.take(120)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "暂停", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }

    companion object {
        const val TAG = "mca-managed-model-import"
        const val REQUEST_ID = "requestId"
        const val REQUEST_TAG_PREFIX = "import-request:"
        private const val CHANNEL = "mca-model-imports"
        private val importMutex = Mutex()

        internal suspend fun enqueue(context: Context, request: ManagedModelImportRequest) {
            request.save(context.filesDir)
            val work = OneTimeWorkRequestBuilder<ManagedModelImportWorker>()
                .setInputData(workDataOf(REQUEST_ID to request.id))
                .addTag(TAG).addTag(REQUEST_TAG_PREFIX + request.id)
                .addTag("created:${System.currentTimeMillis()}").build()
            val result = WorkManager.getInstance(context)
                .enqueueUniqueWork("$TAG-${request.id}", ExistingWorkPolicy.KEEP, work).result
            try {
                kotlinx.coroutines.withTimeout(20_000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        result.addListener({
                            try { result.get(); continuation.resumeWith(kotlin.Result.success(Unit)) }
                            catch (error: Exception) { continuation.resumeWith(kotlin.Result.failure(error)) }
                        }, java.util.concurrent.Executor { it.run() })
                        continuation.invokeOnCancellation { result.cancel(true) }
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw java.io.IOException("保存导入任务超时，请检查存储空间后重试。", timeout)
            }
        }
    }
}

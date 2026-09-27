package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ManagedModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var installer: ManagedModelDownloadInstaller? = null
        try {
            setForeground(foreground("正在等待下载…"))
            installationMutex.withLock {
                isInstalling = true
                try {
                val request = ManagedDownloadRequest.fromJson(requireNotNull(inputData.getString(REQUEST)))
                installer = ManagedModelDownloadInstaller(applicationContext, request.projectorTargetId)
                val installed = coroutineScope {
                    val reporter = launch {
                    installer!!.progress.sample(750).collectLatest { progress ->
                            setProgress(workDataOf("file" to progress.downloadFileName,
                                "bytes" to progress.downloadedBytes, "total" to progress.downloadTotalBytes,
                                "speed" to progress.downloadSpeedBytesPerSecond, "message" to progress.statusMessage,
                                "phase" to progress.phase.name,
                                "failureSource" to progress.failureSource?.name,
                                "integrityStatus" to progress.integrityStatus,
                                "integrityMessage" to progress.integrityMessage,
                                "executionStatus" to progress.executionStatus,
                                "executionMessage" to progress.executionMessage))
                            setForeground(foreground(progress.statusMessage))
                        }
                    }
                    try { installer!!.install(request) } finally { reporter.cancel() }
                }
                Result.success(workDataOf("modelId" to installed.modelId, "imageId" to installed.imageId,
                    "projector" to installed.projector, "message" to installed.message,
                    "phase" to com.muyuchat.feature.modelhub.ModelHubDownloadPhase.COMPLETED.name,
                    "integrityStatus" to installed.integrityStatus,
                    "integrityMessage" to installed.integrityMessage,
                    "executionStatus" to installed.executionStatus,
                    "executionMessage" to installed.executionMessage))
                } finally { isInstalling = false }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Failures are terminal and actionable; process death is resumed by WorkManager.
            // In particular, ENOSPC must not cause an unbounded retry/write loop.
            installer?.markFailure(error)
            android.util.Log.e("McaModelDownload", "Download/install failed", error)
            val progress = installer?.progress?.value
            Result.failure(workDataOf(
                "error" to "下载或导入未完成：${error.message.orEmpty().take(900)}。已保留可恢复进度，请检查网络和存储空间后重试。",
                "phase" to progress?.phase?.name,
                "failureSource" to progress?.failureSource?.name,
                "integrityStatus" to (progress?.integrityStatus ?: "FAILED"),
                "integrityMessage" to (progress?.integrityMessage ?: error.message.orEmpty()),
                "executionStatus" to (progress?.executionStatus ?: "FAILED"),
                "executionMessage" to (progress?.executionMessage ?: "安装阶段未完成：${error.message.orEmpty()}")))
        }
    }

    private fun foreground(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "模型下载", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MCA 模型下载")
            .setContentText(message.take(120)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "暂停", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }

    companion object {
        const val REQUEST = "request"
        const val TAG = "mca-managed-model-download"
        private const val CHANNEL = "mca-model-downloads"
        private val installationMutex = Mutex()
        @Volatile internal var isInstalling = false
            private set

        internal suspend fun enqueue(context: Context, request: ManagedDownloadRequest): java.util.UUID {
            val work = OneTimeWorkRequestBuilder<ManagedModelDownloadWorker>()
                .setInputData(workDataOf(REQUEST to request.toJson()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG).addTag(request.identity).addTag("created:${System.currentTimeMillis()}").build()
            val result = WorkManager.getInstance(context)
                .enqueueUniqueWork("$TAG-${request.identity}", ExistingWorkPolicy.KEEP, work).result
            try { kotlinx.coroutines.withTimeout(20_000) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    result.addListener({
                        try {
                            result.get()
                            continuation.resumeWith(kotlin.Result.success(Unit))
                        } catch (error: Exception) {
                            continuation.resumeWith(kotlin.Result.failure(error))
                        }
                    }, java.util.concurrent.Executor { it.run() })
                    continuation.invokeOnCancellation { result.cancel(true) }
                }
            } } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw java.io.IOException("保存下载任务超时，请检查存储空间并重新打开应用。", timeout)
            }
            return work.id
        }
    }
}

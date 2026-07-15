package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Main-process client for the disposable QAIRT certification process. */
internal class QairtDryRunWorkerClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun certify(
        modelId: String,
        nCtx: Int,
        nThreads: Int,
        onProgress: (QairtDryRunWorkerProtocol.Progress) -> Unit = {}
    ): QairtDryRunWorkerProtocol.Result {
        val requestId = UUID.randomUUID().toString()
        val connected = CompletableDeferred<IQairtDryRunWorker>()
        val completed = CompletableDeferred<QairtDryRunWorkerProtocol.Result>()
        var bound = false
        var remote: IQairtDryRunWorker? = null
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val worker = IQairtDryRunWorker.Stub.asInterface(service)
                val recipient = IBinder.DeathRecipient {
                    if (!completed.isCompleted) {
                        completed.completeExceptionally(
                            QairtDryRunWorkerException("QAIRT 隔离进程已退出；本次验收未被记录。")
                        )
                    }
                }
                try {
                    service.linkToDeath(recipient, 0)
                } catch (_: RemoteException) {
                    completed.completeExceptionally(
                        QairtDryRunWorkerException("QAIRT 隔离进程在连接时退出。")
                    )
                    return
                }
                remote = worker
                binder = service
                deathRecipient = recipient
                connected.complete(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!completed.isCompleted) {
                    completed.completeExceptionally(QairtDryRunWorkerException("QAIRT 隔离服务已断开。"))
                }
            }

            override fun onBindingDied(name: ComponentName) {
                if (!completed.isCompleted) {
                    completed.completeExceptionally(QairtDryRunWorkerException("QAIRT 隔离服务绑定已失效。"))
                }
            }

            override fun onNullBinding(name: ComponentName) {
                if (!completed.isCompleted) {
                    completed.completeExceptionally(QairtDryRunWorkerException("QAIRT 隔离服务没有可用绑定。"))
                }
            }
        }

        val callback = object : IQairtDryRunWorkerCallback.Stub() {
            override fun onProgress(payloadJson: String) {
                runCatching { QairtDryRunWorkerProtocol.parseProgress(payloadJson) }
                    .onSuccess { progress ->
                        if (progress.requestId == requestId && !completed.isCompleted) {
                            runCatching { onProgress(progress) }
                        }
                    }
                    .onFailure { error ->
                        completed.completeExceptionally(
                            QairtDryRunWorkerException("QAIRT 隔离服务返回了无效进度。", error)
                        )
                    }
            }

            override fun onComplete(payloadJson: String) {
                val result = runCatching { QairtDryRunWorkerProtocol.parseComplete(payloadJson) }
                    .getOrElse { error ->
                        completed.completeExceptionally(
                            QairtDryRunWorkerException("QAIRT 隔离服务返回了无效结果。", error)
                        )
                        return
                    }
                if (result.requestId == requestId) {
                    completed.complete(result)
                } else {
                    completed.completeExceptionally(
                        QairtDryRunWorkerException("QAIRT 隔离服务返回了不匹配的请求。")
                    )
                }
            }

            override fun onError(payloadJson: String) {
                val error = runCatching { QairtDryRunWorkerProtocol.parseError(payloadJson) }
                    .getOrElse { parseError ->
                        completed.completeExceptionally(
                            QairtDryRunWorkerException("QAIRT 隔离服务返回了无效错误。", parseError)
                        )
                        return
                    }
                if (error.requestId.isBlank() || error.requestId == requestId) {
                    completed.completeExceptionally(QairtDryRunWorkerRemoteException(error.code, error.message))
                }
            }
        }

        try {
            bound = appContext.bindService(
                Intent(appContext, QairtDryRunWorkerService::class.java),
                connection,
                qairtDryRunBindingFlags()
            )
            check(bound) { "无法连接 QAIRT 隔离验收服务。" }
            val service = withTimeout(CONNECTION_TIMEOUT_MS) { connected.await() }
            val accepted = try {
                service.start(
                    QairtDryRunWorkerProtocol.start(requestId, modelId, nCtx, nThreads),
                    callback
                )
            } catch (error: RemoteException) {
                throw QairtDryRunWorkerException("QAIRT 隔离服务调用失败。", error)
            }
            check(accepted) { "QAIRT 隔离验收服务正忙，请稍后重试。" }
            return withTimeout(RUN_TIMEOUT_MS) { completed.await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DeadObjectException) {
            throw QairtDryRunWorkerException("QAIRT 隔离进程已退出；本次验收未被记录。", error)
        } finally {
            runCatching { remote?.cancel(QairtDryRunWorkerProtocol.cancel(requestId)) }
            deathRecipient?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val RUN_TIMEOUT_MS = 150_000L
    }
}

internal open class QairtDryRunWorkerException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class QairtDryRunWorkerRemoteException(val code: String, message: String) :
    QairtDryRunWorkerException(message)

/**
 * Certification is an explicit foreground user action. Keep only its bound,
 * disposable process important while QAIRT allocates large HTP/DMA buffers;
 * otherwise LMKD may kill the worker before it can unload and certify. The
 * priority boost ends when [QairtDryRunWorkerClient.certify] unbinds.
 */
internal fun qairtDryRunBindingFlags(): Int =
    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT

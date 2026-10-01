package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** DiT backend selection for Qwen-Image-2.1. Text encoder and VAE remain on CPU. */
internal enum class QwenImage21Backend(
    val wireName: String,
    val useGpu: Boolean,
    val nativeAuditName: String
) {
    CPU("CPU", false, "MNN_CPU"),
    OPENCL("OPENCL", true, "MNN_OPENCL"),
    QNN("QNN", true, "MNN_QNN");

    companion object {
        fun fromWire(value: String?, useGpu: Boolean): QwenImage21Backend =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
                ?: if (useGpu) OPENCL else CPU
    }
}

/** Main-process bridge for the disposable Qwen runtime service. */
internal class QwenImage21WorkerClient(context: Context) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val connectionLock = Any()

    @Volatile private var remote: IQwenImage21Worker? = null
    @Volatile private var remoteBinder: IBinder? = null
    private var connection: ServiceConnection? = null
    private var connected: CompletableDeferred<IQwenImage21Worker>? = null
    private var bound = false
    private var inFlightRequestId: String? = null
    private var inFlight: CompletableDeferred<JSONObject>? = null
    @Volatile private var restartNotBeforeElapsedMs = 0L

    suspend fun load(
        bundleRoot: String,
        useGpu: Boolean,
        threads: Int,
        backend: QwenImage21Backend = QwenImage21Backend.fromWire(null, useGpu),
        qnnRuntimePath: String? = null,
        bundleFingerprint: String? = null,
        requestId: String = UUID.randomUUID().toString(),
        onProgress: (QwenImage21WorkerProgress) -> Unit = {}
    ): QwenImage21WorkerStatus = operationMutex.withLock {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("bundleRoot", bundleRoot)
            .put("bundleFingerprint", bundleFingerprint.orEmpty())
            .put("useGpu", useGpu)
            .put("backend", backend.wireName)
            .put("qnnRuntimePath", qnnRuntimePath.orEmpty())
            .put("threads", threads.coerceIn(1, 16))
        val result = invokeAsync(
            requestId = requestId,
            payload = payload,
            timeoutMs = LOAD_TIMEOUT_MS,
            progressListener = onProgress
        ) { service, callback -> service.load(payload.toString(), callback) }
        QwenImage21WorkerStatus.parse(result)
    }

    suspend fun generate(
        requestId: String,
        bundleRoot: String,
        prompt: String,
        inputImagePath: String?,
        outputPath: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        threads: Int,
        useGpu: Boolean,
        backend: QwenImage21Backend = QwenImage21Backend.fromWire(null, useGpu),
        qnnRuntimePath: String? = null,
        onProgress: (Int) -> Unit = {},
        negativePrompt: String = "",
        bundleFingerprint: String? = null
    ): QwenImage21WorkerResult = operationMutex.withLock {
        require(requestId.isNotBlank()) { "Qwen generation request id is required." }
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("bundleRoot", bundleRoot)
            .put("bundleFingerprint", bundleFingerprint.orEmpty())
            .put("prompt", prompt)
            .put("negativePrompt", negativePrompt)
            .put("inputImagePath", inputImagePath.orEmpty())
            .put("outputPath", outputPath)
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("seed", seed)
            .put("threads", threads.coerceIn(1, 16))
            .put("useGpu", useGpu)
            .put("backend", backend.wireName)
            .put("qnnRuntimePath", qnnRuntimePath.orEmpty())
        val result = invokeAsync(
            requestId = requestId,
            payload = payload,
            timeoutMs = GENERATION_TIMEOUT_MS,
            progressListener = { progress ->
                if (progress.requestId == requestId) progress.progressPercent?.let { onProgress(it) }
            }
        ) { service, callback -> service.generate(payload.toString(), callback) }
        QwenImage21WorkerResult.parse(result)
    }

    /** The same request id cancels either a lengthy native load or a running generation. */
    suspend fun cancel(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        val worker = remote ?: return false
        return cancelActive(worker, requestId)
    }

    /** Non-suspending stop hook for LocalImageProvider's synchronous cancel path. */
    fun cancelImmediately(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        val worker = remote ?: return false
        val accepted = try {
            worker.cancel(requestId)
        } catch (_: RemoteException) {
            disconnectCurrent()
            true
        }
        if (accepted) {
            // The service terminates itself shortly after acknowledging cancellation. Delay only
            // the next bind (not this synchronous stop callback) so it cannot attach to old state.
            restartNotBeforeElapsedMs = SystemClock.elapsedRealtime() + CANCEL_REBIND_GUARD_MS
            disconnectCurrent()
        }
        return accepted
    }

    suspend fun status(): QwenImage21WorkerStatus {
        // A status read is a snapshot, not a native operation. Do not queue it behind the
        // operation mutex while a long model load or image generation is in progress.
        val activeWorker = remote?.takeIf { remoteBinder?.isBinderAlive == true }
        if (activeWorker != null) {
            try {
                val payload = withContext(Dispatchers.IO) { activeWorker.status() }
                return QwenImage21WorkerStatus.parse(JSONObject(payload))
            } catch (error: RemoteException) {
                disconnectCurrent()
            }
        }

        // Preserve single-bind setup when there is no live process, and serialize reconnection
        // against load/generate/unload. A dead worker will fail the in-flight operation quickly.
        val worker = operationMutex.withLock { ensureConnected() }
        val payload = withContext(Dispatchers.IO) { worker.status() }
        return QwenImage21WorkerStatus.parse(JSONObject(payload))
    }

    /** Unloads the resident native handle and releases the persistent service binding. */
    suspend fun unload(requestId: String = UUID.randomUUID().toString()): Unit = operationMutex.withLock {
        try {
            val payload = JSONObject().put("requestId", requestId)
            invokeAsync(
                requestId = requestId,
                payload = payload,
                timeoutMs = UNLOAD_TIMEOUT_MS,
                progressListener = {}
            ) { service, callback -> service.unload(requestId, callback) }
            Unit
        } finally {
            disconnectCurrent()
        }
    }

    /** Release a resident model by dropping the only binding; a later call starts a fresh process. */
    suspend fun disconnect() = operationMutex.withLock { disconnectCurrent() }

    private suspend fun invokeAsync(
        requestId: String,
        payload: JSONObject,
        timeoutMs: Long,
        progressListener: (QwenImage21WorkerProgress) -> Unit,
        invoke: (IQwenImage21Worker, IQwenImage21WorkerCallback) -> Unit
    ): JSONObject {
        val requestStartedAtMs = System.currentTimeMillis()
        val service = ensureConnected()
        val result = CompletableDeferred<JSONObject>()
        val callback = object : IQwenImage21WorkerCallback.Stub() {
            override fun onProgress(payloadJson: String) {
                val progress = runCatching { QwenImage21WorkerProgress.parse(JSONObject(payloadJson)) }
                    .getOrElse { error ->
                        result.completeExceptionally(
                            QwenImage21WorkerException("Qwen worker returned invalid progress data.", error)
                        )
                        return
                }
                if (progress.requestId == requestId && !result.isCompleted) {
                    runCatching { progressListener(progress) }
                }
            }

            override fun onComplete(payloadJson: String) {
                val parsed = runCatching { JSONObject(payloadJson) }.getOrElse { error ->
                    result.completeExceptionally(
                        QwenImage21WorkerException("Qwen worker returned an invalid result.", error)
                    )
                    return
                }
                if (parsed.optString("requestId") == requestId) {
                    result.complete(parsed)
                } else {
                    result.completeExceptionally(QwenImage21WorkerException("Qwen worker returned a mismatched request id."))
                }
            }

            override fun onError(payloadJson: String) {
                val error = runCatching { QwenImage21WorkerFailure.parse(JSONObject(payloadJson)) }
                    .getOrElse { parseError ->
                        result.completeExceptionally(
                            QwenImage21WorkerException("Qwen worker returned an invalid error response.", parseError)
                        )
                        return
                    }
                if (error.requestId.isBlank() || error.requestId == requestId) {
                    result.completeExceptionally(QwenImage21WorkerRemoteException(error.code, error.message))
                }
            }
        }

        synchronized(connectionLock) {
            inFlightRequestId = requestId
            inFlight = result
        }
        try {
            withContext(Dispatchers.IO) { invoke(service, callback) }
            return withTimeout(timeoutMs) { result.await() }
        } catch (cancelled: CancellationException) {
            // Binder cancellation is handled on a separate transaction and can kill only the
            // disposable Qwen process while nativeCreate/nativeGenerate is blocking elsewhere.
            withContext(NonCancellable) { cancelActive(service, requestId) }
            if (cancelled is TimeoutCancellationException) {
                throw QwenImage21WorkerException("Qwen image operation timed out; the isolated runtime is being restarted.", cancelled)
            }
            throw cancelled
        } catch (error: QwenImage21WorkerDisconnectedException) {
            disconnectCurrent()
            val message = withContext(Dispatchers.IO) { readQwenWorkerExitMessage(appContext, requestStartedAtMs) }
            throw QwenImage21WorkerException(message, error)
        } catch (error: RemoteException) {
            disconnectCurrent()
            val message = withContext(Dispatchers.IO) { readQwenWorkerExitMessage(appContext, requestStartedAtMs) }
            throw QwenImage21WorkerException(message, error)
        } finally {
            synchronized(connectionLock) {
                if (inFlight === result) {
                    inFlight = null
                    inFlightRequestId = null
                }
            }
        }
    }

    private suspend fun cancelActive(worker: IQwenImage21Worker, requestId: String): Boolean {
        val binder = remoteBinder
        val accepted = withContext(Dispatchers.IO) {
            try {
                worker.cancel(requestId)
            } catch (_: RemoteException) {
                // The disposable service already exited, so there is no native work to stop.
                true
            }
        }
        if (accepted) {
            // Service sends the cancellation callback first, then kills its process. Do not let
            // an immediate retry bind back to the still-cancelling, model-resident process.
            withTimeoutOrNull(CANCEL_RESTART_TIMEOUT_MS) {
                while (binder?.isBinderAlive == true) delay(CANCEL_DEATH_POLL_MS)
            }
            disconnectCurrent()
        }
        return accepted
    }

    private suspend fun ensureConnected(): IQwenImage21Worker {
        val restartWaitMs = (restartNotBeforeElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (restartWaitMs > 0L) delay(restartWaitMs)
        if (restartWaitMs > 0L) restartNotBeforeElapsedMs = 0L
        remote?.let { existing ->
            if (remoteBinder?.isBinderAlive == true) return existing
        }
        disconnectCurrent()
        val deferred = CompletableDeferred<IQwenImage21Worker>()
        lateinit var nextConnection: ServiceConnection
        nextConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val worker = IQwenImage21Worker.Stub.asInterface(service)
                val recipient = IBinder.DeathRecipient {
                    connectionDied(nextConnection, "Qwen isolated process died during an operation.")
                }
                try {
                    service.linkToDeath(recipient, 0)
                } catch (error: RemoteException) {
                    deferred.completeExceptionally(
                        QwenImage21WorkerException("Qwen isolated process died while connecting.", error)
                    )
                    return
                }
                synchronized(connectionLock) {
                    if (connection !== nextConnection) {
                        runCatching { service.unlinkToDeath(recipient, 0) }
                        return
                    }
                    remote = worker
                    remoteBinder = service
                }
                deferred.complete(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connectionDied(nextConnection, "Qwen isolated service disconnected.")
            }

            override fun onBindingDied(name: ComponentName) {
                connectionDied(nextConnection, "Qwen isolated service binding died.")
            }

            override fun onNullBinding(name: ComponentName) {
                deferred.completeExceptionally(QwenImage21WorkerException("Qwen isolated service returned a null binding."))
                connectionDied(nextConnection, "Qwen isolated service returned a null binding.")
            }
        }

        synchronized(connectionLock) {
            connection = nextConnection
            connected = deferred
        }
        val didBind = appContext.bindService(
            Intent(appContext, QwenImage21WorkerService::class.java),
            nextConnection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )
        synchronized(connectionLock) { bound = didBind }
        if (!didBind) {
            synchronized(connectionLock) {
                if (connection === nextConnection) {
                    connection = null
                    connected = null
                }
            }
            throw QwenImage21WorkerException("Unable to bind the Qwen isolated runtime service.")
        }
        return try {
            withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() }
        } catch (error: Throwable) {
            disconnectCurrent(nextConnection)
            if (error is CancellationException) throw error
            throw QwenImage21WorkerException("Timed out connecting to the Qwen isolated runtime service.", error)
        }
    }

    private fun connectionDied(expected: ServiceConnection, message: String) {
        val pending = synchronized(connectionLock) {
            if (connection !== expected) return
            remote = null
            remoteBinder = null
            connected?.completeExceptionally(QwenImage21WorkerDisconnectedException(message))
            inFlight.also { inFlight = null; inFlightRequestId = null }
        }
        pending?.completeExceptionally(QwenImage21WorkerDisconnectedException(message))
        disconnectCurrent(expected)
    }

    private fun disconnectCurrent(expected: ServiceConnection? = null) {
        val toUnbind = synchronized(connectionLock) {
            if (expected != null && connection !== expected) return
            val previous = connection
            val shouldUnbind = bound && previous != null
            connection = null
            connected = null
            bound = false
            remote = null
            remoteBinder = null
            inFlight?.completeExceptionally(QwenImage21WorkerException("Qwen isolated service was disconnected."))
            inFlight = null
            inFlightRequestId = null
            previous.takeIf { shouldUnbind }
        }
        if (toUnbind != null) runCatching { appContext.unbindService(toUnbind) }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val LOAD_TIMEOUT_MS = 20 * 60_000L
        private const val GENERATION_TIMEOUT_MS = 30 * 60_000L
        private const val UNLOAD_TIMEOUT_MS = 5 * 60_000L
        private const val CANCEL_RESTART_TIMEOUT_MS = 5_000L
        private const val CANCEL_DEATH_POLL_MS = 25L
        private const val CANCEL_REBIND_GUARD_MS = 350L
    }
}

internal data class QwenImage21WorkerProgress(
    val requestId: String,
    val operation: String,
    val stage: String,
    val progressPercent: Int?,
    val state: String
) {
    companion object {
        fun parse(json: JSONObject) = QwenImage21WorkerProgress(
            requestId = json.optString("requestId"),
            operation = json.optString("operation"),
            stage = json.optString("stage"),
            progressPercent = json.optInt("progress", -1).takeIf { it >= 0 },
            state = json.optString("state")
        )
    }
}

internal data class QwenImage21WorkerStatus(
    val state: String,
    val loaded: Boolean,
    val generating: Boolean,
    val bundleFingerprint: String,
    val bundleId: String,
    val backendConfigured: String,
    val textEncoderOnCpu: Boolean,
    val vaeOnCpu: Boolean,
    val threads: Int,
    val loadMs: Long,
    val lastErrorCode: String,
    val lastErrorMessage: String,
    val rawJson: String
) {
    companion object {
        fun parse(json: JSONObject) = QwenImage21WorkerStatus(
            state = json.optString("state", "UNKNOWN"),
            loaded = json.optBoolean("loaded"),
            generating = json.optBoolean("generating"),
            bundleFingerprint = json.optString("bundleFingerprint"),
            bundleId = json.optString("bundleId"),
            backendConfigured = json.optString("backendConfigured", "NONE"),
            textEncoderOnCpu = json.optBoolean("textEncoderOnCpu", true),
            vaeOnCpu = json.optBoolean("vaeOnCpu", true),
            threads = json.optInt("threads"),
            loadMs = json.optLong("loadMs"),
            lastErrorCode = json.optString("lastErrorCode"),
            lastErrorMessage = json.optString("lastErrorMessage"),
            rawJson = json.toString()
        )
    }
}

internal data class QwenImage21WorkerResult(
    val requestId: String,
    val outputPath: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Int,
    val elapsedMs: Long,
    val outputBytes: Long,
    val bundleFingerprint: String,
    val executionAudit: JSONObject
) {
    companion object {
        fun parse(json: JSONObject): QwenImage21WorkerResult {
            require(json.optString("state") == "READY") { "Qwen worker completed without a READY state." }
            val audit = json.optJSONObject("executionAudit")
                ?: throw QwenImage21WorkerException("Qwen worker omitted its execution audit.")
            return QwenImage21WorkerResult(
                requestId = json.optString("requestId"),
                outputPath = json.optString("outputPath"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                steps = json.optInt("steps"),
                seed = json.optInt("seed"),
                elapsedMs = json.optLong("elapsedMs"),
                outputBytes = json.optLong("outputBytes"),
                bundleFingerprint = json.optString("bundleFingerprint"),
                executionAudit = audit
            )
        }
    }
}

internal data class QwenImage21WorkerFailure(val requestId: String, val code: String, val message: String) {
    companion object {
        fun parse(json: JSONObject) = QwenImage21WorkerFailure(
            requestId = json.optString("requestId"),
            code = json.optString("code", "qwen_worker_failed"),
            message = json.optString("message", "Qwen image operation failed.")
        )
    }
}

internal open class QwenImage21WorkerException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class QwenImage21WorkerRemoteException(val code: String, message: String) :
    QwenImage21WorkerException(message)

internal class QwenImage21WorkerDisconnectedException(message: String) :
    QwenImage21WorkerException(message)

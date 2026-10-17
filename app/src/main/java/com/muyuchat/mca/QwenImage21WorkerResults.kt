package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.muyuchat.api.local.imagePromptExecutionSha256
import com.scsonic.qwenimage21.QwenImage21
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import com.muyuchat.mca.QwenImage21WorkerService.Companion.TAG
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_READY
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_GENERATING
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_FAILED
import com.muyuchat.mca.QwenImage21WorkerService.Companion.MAX_ERROR_CHARS

/**
 * T2B22-T4（b 工位）拆片：finish / fail / sendComplete / sendError(2 重载) / dispatchError /
 * unlinkDeath / emitProgress / statusPayload 以 QwenImage21WorkerService 扩展承载；
 * 自 QwenImage21WorkerService.kt 逐字节平移，仅登记声明行改写。
 */

internal fun QwenImage21WorkerService.finish(operation: ActiveOperation) {
        synchronized(lock) {
            if (active === operation) active = null
        }
        unlinkDeath(operation)
    }

internal fun QwenImage21WorkerService.fail(operation: ActiveOperation, error: Throwable) {
        val code = (error as? QwenNativeException)?.code ?: when (error) {
            is OutOfMemoryError -> "out_of_memory"
            is SecurityException -> "runtime_load_denied"
            is java.io.IOException -> "runtime_assets_unavailable"
            is UnsatisfiedLinkError -> "runtime_library_load_failed"
            else -> "qwen_worker_failed"
        }
        val message = safeMessage(error)
        lastErrorCode = code
        lastErrorMessage = message
        state = if (nativeHandle != 0L) STATE_READY else STATE_FAILED
        val accepted = synchronized(lock) {
            val terminalAccepted = active === operation && !operation.cancelled.get() &&
                operation.terminalSent.compareAndSet(false, true)
            if (terminalAccepted) active = null
            terminalAccepted
        }
        if (accepted) {
            // Release the operation slot before notifying the caller. Its continuation may issue
            // the next request as soon as the Binder callback returns.
            unlinkDeath(operation)
            dispatchError(operation.callback, operation.requestId, code, message)
        }
        Log.w(TAG, "operation_failed operation=${operation.kind} code=$code")
    }

internal fun QwenImage21WorkerService.sendComplete(operation: ActiveOperation, payload: JSONObject) {
        val accepted = synchronized(lock) {
            val terminalAccepted = active === operation && !operation.cancelled.get() &&
                operation.terminalSent.compareAndSet(false, true)
            if (terminalAccepted) active = null
            terminalAccepted
        }
        if (!accepted) return
        unlinkDeath(operation)
        runCatching { operation.callback.onComplete(payload.put("requestId", operation.requestId).toString()) }
    }

internal fun QwenImage21WorkerService.sendError(operation: ActiveOperation, code: String, message: String) {
        val accepted = synchronized(lock) {
            active === operation && operation.terminalSent.compareAndSet(false, true)
        }
        if (!accepted) return
        dispatchError(operation.callback, operation.requestId, code, message)
    }

internal fun QwenImage21WorkerService.sendError(
        callback: IQwenImage21WorkerCallback,
        requestId: String,
        code: String,
        message: String,
        terminalSent: AtomicBoolean = AtomicBoolean(false)
    ) {
        if (!terminalSent.compareAndSet(false, true)) return
        dispatchError(callback, requestId, code, message)
    }

internal fun QwenImage21WorkerService.dispatchError(callback: IQwenImage21WorkerCallback, requestId: String, code: String, message: String) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("code", code)
            .put("message", message.take(MAX_ERROR_CHARS))
            .toString()
        runCatching { callback.onError(payload) }
    }

internal fun QwenImage21WorkerService.unlinkDeath(operation: ActiveOperation) {
        operation.deathRecipient?.let { recipient ->
            runCatching { operation.callback.asBinder().unlinkToDeath(recipient, 0) }
        }
    }

internal fun QwenImage21WorkerService.emitProgress(operation: ActiveOperation, percent: Int, stage: String) {
        if (operation.cancelled.get() || operation.terminalSent.get()) return
        val payload = JSONObject()
            .put("requestId", operation.requestId)
            .put("operation", operation.kind)
            .put("stage", stage)
            .put("progress", percent.coerceIn(0, 100))
            .put("state", state)
            .toString()
        runCatching { operation.callback.onProgress(payload) }
    }

internal fun QwenImage21WorkerService.statusPayload(requestId: String): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("state", state)
        .put("loaded", nativeHandle != 0L)
        .put("generating", state == STATE_GENERATING)
        .put("bundleFingerprint", loadedFingerprint.orEmpty())
        .put("bundleId", loadedBundleId.orEmpty())
        .put("useGpu", loadedUseGpu)
        .put("backend", loadedBackend.wireName)
        .put("backendConfigured", if (nativeHandle == 0L) "NONE" else loadedBackendEffective)
        .put("backendExecutionConfirmedByNative", loadedBackendExecutionConfirmed)
        .put("textEncoderOnCpu", true)
        .put("vaeOnCpu", true)
        .put("threads", loadedThreads)
        .put("loadMs", modelLoadMs)
        .put("lastErrorCode", lastErrorCode.orEmpty())
        .put("lastErrorMessage", lastErrorMessage.orEmpty())
        .put("availableMemoryMb", lastAvailableMemoryMb)
        .put("estimatedPeakMemoryMb", lastMemoryAdvisory?.requiredMemoryMb ?: -1)
        .put("memoryEstimateAdvisoryOnly", true)
        .put("memoryAdvisory", lastMemoryAdvisory?.message.orEmpty())

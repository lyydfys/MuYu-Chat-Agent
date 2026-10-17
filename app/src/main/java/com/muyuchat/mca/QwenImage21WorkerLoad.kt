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
import com.muyuchat.mca.QwenImage21WorkerService.Companion.QNN_COMMON_ADSP_PATHS
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_LOADING
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_PREPARING
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_UNLOADING
import com.muyuchat.mca.QwenImage21WorkerService.Companion.STATE_READY
import com.muyuchat.mca.QwenImage21WorkerService.Companion.CANCEL_KILL_DELAY_MS

/**
 * T2B22-T4（b 工位）拆片：beginOperation / submit / ensureLoaded / releaseNativeHandle /
 * clearLoadedIdentity 以 QwenImage21WorkerService 扩展承载，自 QwenImage21WorkerService.kt
 * 逐字节平移；仅声明行登记（private fun -> internal fun <接收者>.；跨文件字段/伴生常量
 * private -> internal）。Service 类名、onBind、进程行为与 Manifest 锚不变。
 */

internal fun QwenImage21WorkerService.beginOperation(
        requestId: String,
        operation: String,
        callback: IQwenImage21WorkerCallback
    ): ActiveOperation? = synchronized(lock) {
        if (active != null) return@synchronized null
        val next = ActiveOperation(requestId, operation, callback)
        next.deathRecipient = IBinder.DeathRecipient {
            val shouldTerminateWorker = synchronized(lock) {
                if (active === next) {
                    next.cancelled.set(true)
                    true
                } else {
                    false
                }
            }
            if (shouldTerminateWorker) {
                handler.postDelayed({ Process.killProcess(Process.myPid()) }, CANCEL_KILL_DELAY_MS)
            }
        }
        try {
            callback.asBinder().linkToDeath(requireNotNull(next.deathRecipient), 0)
        } catch (_: RemoteException) {
            return@synchronized null
        }
        active = next
        state = when (operation) {
            "load" -> STATE_LOADING
            "generate" -> STATE_PREPARING
            "unload" -> STATE_UNLOADING
            else -> state
        }
        next
    }

internal fun QwenImage21WorkerService.submit(operation: ActiveOperation, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (error: Throwable) {
                    if (!operation.cancelled.get()) fail(operation, error)
                    finish(operation)
                }
            }
        } catch (error: Throwable) {
            fail(operation, error)
            finish(operation)
        }
    }

internal fun QwenImage21WorkerService.ensureLoaded(
        operation: ActiveOperation,
        bundleRoot: String,
        providedFingerprint: String?,
        useGpu: Boolean,
        backend: QwenImage21Backend,
        qnnRuntimePath: String?,
        threads: Int
    ) {
        checkNotCancelled(operation)
        val root = File(bundleRoot).canonicalFile
        require(root.isDirectory && root.canRead()) { "The Qwen model bundle directory is missing or unreadable." }
        val effectiveProvidedFingerprint = providedFingerprint?.takeIf(String::isNotBlank)
            ?: loadedBundleId?.takeIf { loadedRoot == root.absolutePath }
        val fingerprint = bundleFingerprint(root, effectiveProvidedFingerprint)
        val alreadyLoaded = nativeHandle != 0L && loadedFingerprint == fingerprint &&
            loadedRoot == root.absolutePath && loadedUseGpu == useGpu && loadedBackend == backend &&
            loadedQnnRuntimePath == qnnRuntimePath?.trim()?.takeIf(String::isNotBlank) &&
            loadedThreads == threads
        if (alreadyLoaded) {
            loadedRoot = root.absolutePath
            state = STATE_READY
            emitProgress(operation, 100, "model_already_loaded")
            return
        }

        if (nativeHandle != 0L) {
            state = STATE_UNLOADING
            releaseNativeHandle()
        }
        clearLoadedIdentity()
        state = STATE_LOADING
        emitProgress(operation, 0, "runtime_verifying")
        val started = SystemClock.elapsedRealtime()
        val normalizedQnnPath = qnnRuntimePath?.trim()?.takeIf(String::isNotBlank)
        if (backend == QwenImage21Backend.QNN) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Os.setenv("MNN_QNN_DIAG", "1", true)
            }
            val adspLibraryPath = buildList {
                add(normalizedQnnPath ?: applicationInfo.nativeLibraryDir)
                addAll(Os.getenv("ADSP_LIBRARY_PATH").orEmpty().split(';').filter(String::isNotBlank))
                addAll(QNN_COMMON_ADSP_PATHS)
            }.distinct().joinToString(";")
            Os.setenv("ADSP_LIBRARY_PATH", adspLibraryPath, true)
            Log.i(TAG, "qnn_adsp_library_path=$adspLibraryPath")
        }
        QwenImage21.loadRuntimeLibraries(applicationContext)
        checkNotCancelled(operation)
        emitProgress(operation, 5, "model_loading")
        lastErrorCode = null
        lastErrorMessage = null
        val nativeBackend = when (backend) {
            QwenImage21Backend.CPU -> QwenImage21.Backend.CPU
            QwenImage21Backend.OPENCL -> QwenImage21.Backend.OPENCL
            QwenImage21Backend.QNN -> QwenImage21.Backend.QNN
        }
        val handle = QwenImage21.create(root.absolutePath, nativeBackend, normalizedQnnPath, threads)
        modelLoadMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        if (handle == 0L) {
            throw QwenNativeException(
                "model_load_failed",
                "Qwen-Image-2.1 could not load the selected bundle. Check that all required text-to-image files exist and retry."
            )
        }
        val loadAudit = runCatching { JSONObject(QwenImage21.executionAudit(handle)) }
            .getOrElse { JSONObject() }
        val effectiveBackend = loadAudit.optString("effectiveBackend").trim().ifBlank { "MNN_UNKNOWN" }
        val requestedBackend = backend.nativeAuditName
        // Fail closed if the pinned MNN runtime cannot report its selected
        // primary backend, or if it differs from the user's explicit request.
        if (!loadAudit.optBoolean("backendResolved", false) || effectiveBackend != requestedBackend) {
            QwenImage21.release(handle)
            throw QwenNativeException(
                "backend_execution_unavailable",
                "Qwen-Image-2.1 requested $requestedBackend, but the native runtime could not confirm that primary backend (resolved: $effectiveBackend). Select a backend available to this model and retry."
            )
        }
        if (operation.cancelled.get()) {
            QwenImage21.release(handle)
            checkNotCancelled(operation)
        }
        nativeHandle = handle
        loadedRoot = root.absolutePath
        loadedFingerprint = fingerprint
        loadedBundleId = effectiveProvidedFingerprint ?: fingerprint
        loadedUseGpu = useGpu
        loadedBackend = backend
        loadedQnnRuntimePath = normalizedQnnPath
        loadedBackendEffective = effectiveBackend
        loadedBackendExecutionConfirmed = loadAudit.optBoolean("backendResolved", false)
        loadedThreads = threads
        state = STATE_READY
        emitProgress(operation, 100, "model_ready")
        Log.i(
            TAG,
            "model_loaded requested=$requestedBackend resolvedPrimary=$effectiveBackend loadMs=$modelLoadMs"
        )
    }

internal fun QwenImage21WorkerService.releaseNativeHandle() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) QwenImage21.release(handle)
    }

internal fun QwenImage21WorkerService.clearLoadedIdentity() {
        loadedFingerprint = null
        loadedBundleId = null
        loadedRoot = null
        loadedUseGpu = false
        loadedBackend = QwenImage21Backend.CPU
        loadedQnnRuntimePath = null
        loadedBackendEffective = "NONE"
        loadedBackendExecutionConfirmed = false
        loadedThreads = 0
        modelLoadMs = 0L
    }

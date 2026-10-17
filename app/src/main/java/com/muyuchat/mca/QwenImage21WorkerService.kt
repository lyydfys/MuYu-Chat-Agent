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

/**
 * Disposable Qwen native runtime. Its private MNN SONAME is loaded only here;
 * a native hang/cancel terminates this process without poisoning MCA's workers.
 */
class QwenImage21WorkerService : Service() {
    internal val lock = Any()
    internal val handler = Handler(Looper.getMainLooper())
    internal val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mca-qwen-image-21").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile internal var nativeHandle = 0L
    @Volatile internal var state = STATE_UNLOADED
    @Volatile internal var active: ActiveOperation? = null
    @Volatile internal var loadedFingerprint: String? = null
    @Volatile internal var loadedBundleId: String? = null
    @Volatile internal var loadedRoot: String? = null
    @Volatile internal var loadedUseGpu = false
    @Volatile internal var loadedBackend = QwenImage21Backend.CPU
    @Volatile internal var loadedQnnRuntimePath: String? = null
    @Volatile internal var loadedBackendEffective = "NONE"
    @Volatile internal var loadedBackendExecutionConfirmed = false
    @Volatile internal var loadedThreads = 0
    @Volatile internal var modelLoadMs = 0L
    @Volatile internal var lastErrorCode: String? = null
    @Volatile internal var lastErrorMessage: String? = null
    @Volatile private var lastCancelledRequestId: String? = null
    @Volatile internal var lastAvailableMemoryMb: Int = -1
    @Volatile internal var lastMemoryAdvisory: QwenImage21MemoryAdmission? = null

    private val binder = object : IQwenImage21Worker.Stub() {
        override fun load(requestJson: String, callback: IQwenImage21WorkerCallback) {
            val request = runCatching { LoadRequest.parse(requestJson) }
                .getOrElse { error ->
                    sendError(callback, requestIdFrom(requestJson), "invalid_request", safeMessage(error))
                    return
                }
            val next = beginOperation(request.requestId, "load", callback) ?: run {
                sendError(callback, request.requestId, "worker_busy", "Qwen image worker is busy.")
                return
            }
            submit(next) {
                val started = SystemClock.elapsedRealtime()
                try {
                    ensureLoaded(
                        operation = next,
                        bundleRoot = request.bundleRoot,
                        providedFingerprint = request.bundleFingerprint,
                        useGpu = request.useGpu,
                        backend = request.backend,
                        qnnRuntimePath = request.qnnRuntimePath,
                        threads = request.threads
                    )
                    if (!next.cancelled.get()) {
                        sendComplete(next, statusPayload(next.requestId).put("loadMs", modelLoadMs))
                    }
                } catch (error: Throwable) {
                    if (!next.cancelled.get()) fail(next, error)
                } finally {
                    finish(next)
                    Log.i(TAG, "load_finished durationMs=${(SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)}")
                }
            }
        }

        override fun generate(requestJson: String, callback: IQwenImage21WorkerCallback) {
            val request = runCatching { GenerateRequest.parse(requestJson) }
                .getOrElse { error ->
                    sendError(callback, requestIdFrom(requestJson), "invalid_request", safeMessage(error))
                    return
                }
            val next = beginOperation(request.requestId, "generate", callback) ?: run {
                sendError(callback, request.requestId, "worker_busy", "Qwen image worker is busy.")
                return
            }
            submit(next) {
                val started = SystemClock.elapsedRealtime()
                var generationStarted = started
                var handleForGeneration = 0L
                var nativeCallStarted = false
                var nativeGenerationSucceeded = false
                try {
                    val output = File(request.outputPath).absoluteFile
                    val outputParent = output.parentFile
                    require(outputParent != null && (outputParent.isDirectory || outputParent.mkdirs())) {
                        "Cannot create the output image directory."
                    }
                    val inputImage = request.inputImagePath?.takeIf(String::isNotBlank)?.let(::File)
                    require(inputImage == null) {
                        "Qwen-Image-2.1 text-to-image mode does not include image-edit components. Choose a text-to-image request."
                    }
                    ensureLoaded(
                        operation = next,
                        bundleRoot = request.bundleRoot,
                        providedFingerprint = request.bundleFingerprint,
                        useGpu = request.useGpu,
                        backend = request.backend,
                        qnnRuntimePath = request.qnnRuntimePath,
                        threads = request.threads
                    )
                    checkNotCancelled(next)
                    // This is a snapshot of system availability after loading, not total
                    // device RAM or an allocation limit. The peak estimate includes already
                    // resident model memory, so only real native execution can decide success.
                    lastAvailableMemoryMb = runCatching { QwenImage21.availableMemoryMB() }.getOrDefault(-1)
                    val admission = QwenImage21MemoryAdmissionPolicy.evaluate(
                        availableMemoryMb = lastAvailableMemoryMb,
                        width = request.width,
                        height = request.height
                    )
                    lastMemoryAdvisory = admission
                    admission.message?.let { Log.w(TAG, "memory_advisory $it") }
                    if (output.exists()) require(output.delete()) { "Cannot replace the existing output image." }
                    next.outputPath = output.absolutePath
                    handleForGeneration = nativeHandle
                    require(handleForGeneration != 0L) { "Qwen image runtime is not loaded." }
                    state = STATE_GENERATING
                    generationStarted = SystemClock.elapsedRealtime()
                    emitProgress(next, 0, "generation_started")
                    nativeCallStarted = true
                    val code = QwenImage21.generate(
                        handleForGeneration,
                        request.prompt,
                        null,
                        output.absolutePath,
                        request.width,
                        request.height,
                        request.steps,
                        request.seed,
                        QwenImage21.ProgressListener { percent ->
                            if (!next.cancelled.get()) emitProgress(next, percent.coerceIn(0, 100), "denoising")
                        }
                    )
                    checkNotCancelled(next)
                    if (code != 0) {
                        val detail = runCatching { QwenImage21.lastError(handleForGeneration) }
                            .getOrNull().orEmpty().ifBlank { "Native generation failed (code $code)." }
                        throw QwenNativeException("native_generation_failed", detail)
                    }
                    nativeGenerationSucceeded = true
                    require(output.isFile && output.length() > 0L) {
                        "Native generation returned success but did not write a non-empty PNG."
                    }
                    require(isPng(output)) { "Native generation output is not a valid PNG." }
                    state = STATE_READY
                    val elapsedMs = (SystemClock.elapsedRealtime() - generationStarted).coerceAtLeast(0L)
                    val nativeAudit = runCatching { JSONObject(QwenImage21.executionAudit(handleForGeneration)) }
                        .getOrElse { JSONObject() }
                    require(nativeAudit.optBoolean("nativeRunCompleted", false)) {
                        "Qwen native runtime did not confirm a completed generation."
                    }
                    require(nativeAudit.optBoolean("backendExecutionConfirmedByNative", false)) {
                        "Qwen native runtime did not confirm execution on the requested backend."
                    }
                    sendComplete(
                        next,
                        JSONObject()
                            .put("requestId", next.requestId)
                            .put("state", STATE_READY)
                            .put("outputPath", output.absolutePath)
                            .put("width", request.width)
                            .put("height", request.height)
                            .put("steps", request.steps)
                            .put("seed", request.seed)
                            .put("elapsedMs", elapsedMs)
                            .put("totalOperationElapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
                            .put("outputBytes", output.length())
                            .put("bundleFingerprint", loadedFingerprint.orEmpty())
                            .put("executionAudit", executionAudit(request, elapsedMs, output.length(), nativeAudit))
                    )
                    Log.i(TAG, "generation_finished durationMs=$elapsedMs outputBytes=${output.length()}")
                } catch (error: Throwable) {
                    if (!next.cancelled.get()) {
                        next.outputPath?.let { output -> runCatching { File(output).delete() } }
                        if (handleForGeneration != 0L &&
                            (error is QwenNativeException || error is OutOfMemoryError ||
                                (nativeCallStarted && !nativeGenerationSucceeded))
                        ) {
                            // MNN diffusion may leave partially initialized stage buffers after a
                            // failed run. This handle is cheap to recreate relative to the model;
                            // release it so a retry never reuses uncertain native state.
                            releaseNativeHandle()
                            clearLoadedIdentity()
                            state = STATE_FAILED
                        } else if (nativeHandle != 0L) {
                            state = STATE_READY
                        }
                        fail(next, error)
                    }
                } finally {
                    if (!next.cancelled.get() && state == STATE_GENERATING) state = STATE_READY
                    finish(next)
                }
            }
        }

        override fun cancel(requestId: String): Boolean {
            val target = synchronized(lock) {
                val current = active?.takeIf { it.requestId == requestId && !it.terminalSent.get() }
                if (current != null && current.cancelled.compareAndSet(false, true)) {
                    lastCancelledRequestId = requestId
                    current
                } else {
                    null
                }
            } ?: return lastCancelledRequestId == requestId
            state = STATE_CANCELLING
            target.outputPath?.let { output -> runCatching { File(output).delete() } }
            sendError(target, "cancelled", "Qwen image operation was cancelled; the isolated runtime is being restarted.")
            // nativeCreate/nativeGenerate are synchronous and have no safe interrupt hook.
            // Killing this process is the cancellation boundary; the next bind reloads cleanly.
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, CANCEL_KILL_DELAY_MS)
            return true
        }

        override fun unload(requestId: String, callback: IQwenImage21WorkerCallback) {
            val id = requestId.ifBlank { UUID.randomUUID().toString() }
            val next = beginOperation(id, "unload", callback) ?: run {
                sendError(callback, id, "worker_busy", "Qwen image worker is busy.")
                return
            }
            submit(next) {
                try {
                    releaseNativeHandle()
                    clearLoadedIdentity()
                    state = STATE_UNLOADED
                    sendComplete(next, statusPayload(next.requestId))
                } catch (error: Throwable) {
                    fail(next, error)
                } finally {
                    finish(next)
                }
            }
        }

        override fun status(): String = statusPayload(active?.requestId.orEmpty()).toString()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        active?.cancelled?.set(true)
        executor.shutdownNow()
        // This process owns no other runtime. Let the kernel reclaim native buffers if the
        // service is detached while a vendor destructor is blocked.
        val hadNativeWork = active != null || nativeHandle != 0L
        if (hadNativeWork) handler.postDelayed({ Process.killProcess(Process.myPid()) }, 250L)
        super.onDestroy()
    }
    companion object {
        internal const val TAG = "MCA-QwenImage21"
        internal val QNN_COMMON_ADSP_PATHS = listOf(
            "/system/lib/rfsa/adsp",
            "/system/vendor/lib/rfsa/adsp",
            "/vendor/lib/rfsa/adsp",
            "/vendor/dsp",
            "/vendor/dsp/cdsp",
            "/dsp"
        )
        private const val STATE_UNLOADED = "UNLOADED"
        internal const val STATE_LOADING = "LOADING"
        internal const val STATE_READY = "READY"
        internal const val STATE_PREPARING = "PREPARING"
        internal const val STATE_GENERATING = "GENERATING"
        private const val STATE_CANCELLING = "CANCELLING"
        internal const val STATE_UNLOADING = "UNLOADING"
        internal const val STATE_FAILED = "FAILED"
        internal const val CANCEL_KILL_DELAY_MS = 180L
        internal const val MAX_ERROR_CHARS = 1000
    }
}

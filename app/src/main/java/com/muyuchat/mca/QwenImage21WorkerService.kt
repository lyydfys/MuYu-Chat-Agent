package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
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
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mca-qwen-image-21").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var nativeHandle = 0L
    @Volatile private var state = STATE_UNLOADED
    @Volatile private var active: ActiveOperation? = null
    @Volatile private var loadedFingerprint: String? = null
    @Volatile private var loadedBundleId: String? = null
    @Volatile private var loadedRoot: String? = null
    @Volatile private var loadedUseGpu = false
    @Volatile private var loadedBackendEffective = "NONE"
    @Volatile private var loadedBackendExecutionConfirmed = false
    @Volatile private var loadedThreads = 0
    @Volatile private var modelLoadMs = 0L
    @Volatile private var lastErrorCode: String? = null
    @Volatile private var lastErrorMessage: String? = null
    @Volatile private var lastCancelledRequestId: String? = null
    @Volatile private var lastAvailableMemoryMb: Int = -1

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
                        threads = request.threads
                    )
                    checkNotCancelled(next)
                    // The native text encoder/DiT transition is the peak-memory
                    // boundary. Reject before entering it when the worker can
                    // measure that the observed reserve is unavailable.
                    lastAvailableMemoryMb = runCatching { QwenImage21.availableMemoryMB() }.getOrDefault(-1)
                    val admission = QwenImage21MemoryAdmissionPolicy.evaluate(
                        availableMemoryMb = lastAvailableMemoryMb,
                        width = request.width,
                        height = request.height
                    )
                    if (!admission.allowed) {
                        // Do not leave the already-loaded text/DiT handle resident after an
                        // admission failure; otherwise the user cannot recover simply by
                        // closing the chat model or choosing a smaller canvas.
                        releaseNativeHandle()
                        clearLoadedIdentity()
                        state = STATE_FAILED
                        throw QwenNativeException(
                            "out_of_memory",
                            admission.message.orEmpty()
                        )
                    }
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

    private fun beginOperation(
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

    private fun submit(operation: ActiveOperation, block: () -> Unit) {
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

    private fun ensureLoaded(
        operation: ActiveOperation,
        bundleRoot: String,
        providedFingerprint: String?,
        useGpu: Boolean,
        threads: Int
    ) {
        checkNotCancelled(operation)
        val root = File(bundleRoot).canonicalFile
        require(root.isDirectory && root.canRead()) { "The Qwen model bundle directory is missing or unreadable." }
        val effectiveProvidedFingerprint = providedFingerprint?.takeIf(String::isNotBlank)
            ?: loadedBundleId?.takeIf { loadedRoot == root.absolutePath }
        val fingerprint = bundleFingerprint(root, effectiveProvidedFingerprint)
        val alreadyLoaded = nativeHandle != 0L && loadedFingerprint == fingerprint &&
            loadedRoot == root.absolutePath && loadedUseGpu == useGpu && loadedThreads == threads
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
        QwenImage21.loadRuntimeLibraries(applicationContext)
        checkNotCancelled(operation)
        emitProgress(operation, 5, "model_loading")
        lastErrorCode = null
        lastErrorMessage = null
        val handle = QwenImage21.create(root.absolutePath, useGpu, threads)
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
        val requestedBackend = if (useGpu) "MNN_OPENCL" else "MNN_CPU"
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

    private fun releaseNativeHandle() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) QwenImage21.release(handle)
    }

    private fun clearLoadedIdentity() {
        loadedFingerprint = null
        loadedBundleId = null
        loadedRoot = null
        loadedUseGpu = false
        loadedBackendEffective = "NONE"
        loadedBackendExecutionConfirmed = false
        loadedThreads = 0
        modelLoadMs = 0L
    }

    private fun finish(operation: ActiveOperation) {
        synchronized(lock) {
            if (active === operation) active = null
        }
        unlinkDeath(operation)
    }

    private fun fail(operation: ActiveOperation, error: Throwable) {
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

    private fun sendComplete(operation: ActiveOperation, payload: JSONObject) {
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

    private fun sendError(operation: ActiveOperation, code: String, message: String) {
        val accepted = synchronized(lock) {
            active === operation && operation.terminalSent.compareAndSet(false, true)
        }
        if (!accepted) return
        dispatchError(operation.callback, operation.requestId, code, message)
    }

    private fun sendError(
        callback: IQwenImage21WorkerCallback,
        requestId: String,
        code: String,
        message: String,
        terminalSent: AtomicBoolean = AtomicBoolean(false)
    ) {
        if (!terminalSent.compareAndSet(false, true)) return
        dispatchError(callback, requestId, code, message)
    }

    private fun dispatchError(callback: IQwenImage21WorkerCallback, requestId: String, code: String, message: String) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("code", code)
            .put("message", message.take(MAX_ERROR_CHARS))
            .toString()
        runCatching { callback.onError(payload) }
    }

    private fun unlinkDeath(operation: ActiveOperation) {
        operation.deathRecipient?.let { recipient ->
            runCatching { operation.callback.asBinder().unlinkToDeath(recipient, 0) }
        }
    }

    private fun emitProgress(operation: ActiveOperation, percent: Int, stage: String) {
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

    private fun statusPayload(requestId: String): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("state", state)
        .put("loaded", nativeHandle != 0L)
        .put("generating", state == STATE_GENERATING)
        .put("bundleFingerprint", loadedFingerprint.orEmpty())
        .put("bundleId", loadedBundleId.orEmpty())
        .put("useGpu", loadedUseGpu)
        .put("backendConfigured", if (nativeHandle == 0L) "NONE" else loadedBackendEffective)
        .put("backendExecutionConfirmedByNative", loadedBackendExecutionConfirmed)
        .put("textEncoderOnCpu", true)
        .put("vaeOnCpu", true)
        .put("threads", loadedThreads)
        .put("loadMs", modelLoadMs)
        .put("lastErrorCode", lastErrorCode.orEmpty())
        .put("lastErrorMessage", lastErrorMessage.orEmpty())
        .put("availableMemoryMb", lastAvailableMemoryMb)

    private fun executionAudit(
        request: GenerateRequest,
        elapsedMs: Long,
        outputBytes: Long,
        nativeAudit: JSONObject
    ): JSONObject {
        // The Qwen runtime performs prompt conditioning inside the isolated worker. Publish the
        // same request-bound digest used by the image provider so the result cannot be detached
        // from the text that was actually submitted to native execution.
        val promptSha256 = imagePromptExecutionSha256(request.prompt, "")
        val nativeEffective = JSONObject()
            .put("runtime", "MNN_DIFFUSION")
            .put("nativeExecution", nativeAudit.optBoolean("nativeExecution", false))
            .put("fallback", nativeAudit.optBoolean("fallback", true))
            .put("nativeGenerationSequence", nativeAudit.optInt("nativeGenerationSequence", 0))
            .put("backendResolved", nativeAudit.optBoolean("backendResolved", false))
            .put("nativeRunCompleted", nativeAudit.optBoolean("nativeRunCompleted", false))
            .put("backendExecutionConfirmedByNative", nativeAudit.optBoolean("backendExecutionConfirmedByNative", false))
            .put("backendExecutionProof", nativeAudit.optString("backendExecutionProof"))
            .put("backendExecutionScope", nativeAudit.optString("backendExecutionScope"))
            .put("requestedBackend", nativeAudit.optString("requestedBackend"))
            .put("effectiveBackend", nativeAudit.optString("effectiveBackend"))
            .put("nativeGenerationCount", nativeAudit.optInt("nativeGenerationCount", 0))
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
        return JSONObject()
            .put("runtime", "MNN_DIFFUSION")
            .put("nativeExecution", nativeAudit.optBoolean("nativeExecution", false))
            .put("fallback", nativeAudit.optBoolean("fallback", true))
            .put("nativeGenerationSequence", nativeAudit.optInt("nativeGenerationSequence", 0))
            .put("backendResolved", nativeAudit.optBoolean("backendResolved", false))
            .put("backendConfigured", nativeAudit.optString("effectiveBackend").ifBlank { "MNN_UNKNOWN" })
            .put("backendExecutionConfirmedByNative", nativeAudit.optBoolean("backendExecutionConfirmedByNative", false))
            .put("backendExecutionProof", nativeAudit.optString("backendExecutionProof"))
            .put("backendExecutionScope", nativeAudit.optString("backendExecutionScope"))
            .put("nativeRunCompleted", nativeAudit.optBoolean("nativeRunCompleted", false))
            .put("requestedBackend", nativeAudit.optString("requestedBackend"))
            .put("effectiveBackend", nativeAudit.optString("effectiveBackend"))
            .put("nativeGenerationCount", nativeAudit.optInt("nativeGenerationCount", 0))
            .put("textEncoderOnCpu", true)
            .put("vaeOnCpu", true)
            .put("steps", request.steps)
            .put("width", request.width)
            .put("height", request.height)
            .put("seed", request.seed)
            .put("threads", request.threads)
            .put("loadMs", modelLoadMs)
            .put("generationElapsedMs", elapsedMs)
            .put("outputBytes", outputBytes)
            .put("pngValidated", true)
            .put("nativePromptExecutionSha256", promptSha256)
            .put("nativePromptBindingStage", "conditioning_consumed")
            .put("nativeEffective", nativeEffective)
    }

    private fun bundleFingerprint(root: File, supplied: String?): String {
        return qwenImage21BundleFingerprint(root, supplied)
    }

    private fun isPng(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val expected = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val actual = ByteArray(expected.size)
            var offset = 0
            while (offset < actual.size) {
                val read = input.read(actual, offset, actual.size - offset)
                if (read < 0) return@use false
                offset += read
            }
            expected.contentEquals(actual)
        }
    }.getOrDefault(false)

    private fun checkNotCancelled(operation: ActiveOperation) {
        if (operation.cancelled.get()) throw java.util.concurrent.CancellationException("Operation cancelled.")
    }

    private fun safeMessage(error: Throwable): String =
        (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
            .filterNot(Char::isISOControl)
            .take(MAX_ERROR_CHARS)

    private fun requestIdFrom(json: String): String = runCatching {
        JSONObject(json).optString("requestId").takeIf(String::isNotBlank)
    }.getOrNull() ?: UUID.randomUUID().toString()

    private data class ActiveOperation(
        val requestId: String,
        val kind: String,
        val callback: IQwenImage21WorkerCallback,
        val terminalSent: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        var deathRecipient: IBinder.DeathRecipient? = null
    ) {
        @Volatile var outputPath: String? = null
    }

    private data class LoadRequest(
        val requestId: String,
        val bundleRoot: String,
        val bundleFingerprint: String?,
        val useGpu: Boolean,
        val threads: Int
    ) {
        companion object {
            fun parse(json: String): LoadRequest {
                val value = JSONObject(json)
                val requestId = value.optString("requestId").trim().ifBlank { UUID.randomUUID().toString() }
                val root = value.optString("bundleRoot").trim()
                require(root.isNotBlank()) { "Model bundle path is required." }
                val threads = value.optInt("threads", 4).coerceIn(1, 16)
                return LoadRequest(
                    requestId, root, value.optString("bundleFingerprint").takeIf(String::isNotBlank),
                    value.optBoolean("useGpu", true), threads
                )
            }
        }
    }

    private data class GenerateRequest(
        val requestId: String,
        val bundleRoot: String,
        val bundleFingerprint: String?,
        val prompt: String,
        val negativePrompt: String,
        val inputImagePath: String?,
        val outputPath: String,
        val width: Int,
        val height: Int,
        val steps: Int,
        val seed: Int,
        val threads: Int,
        val useGpu: Boolean
    ) {
        companion object {
            fun parse(json: String): GenerateRequest {
                val value = JSONObject(json)
                val id = value.optString("requestId").trim()
                require(id.isNotBlank()) { "Generation request id is required." }
                val prompt = value.optString("prompt").trim()
                require(prompt.isNotBlank()) { "Image prompt cannot be empty." }
                val negative = value.optString("negativePrompt").trim()
                require(negative.isEmpty()) {
                    "Qwen-Image-2.1 MNN does not support a separate negative prompt. Clear the negative prompt and retry."
                }
                val root = value.optString("bundleRoot").trim()
                require(root.isNotBlank()) { "Model bundle path is required." }
                val output = value.optString("outputPath").trim()
                require(output.isNotBlank()) { "Output image path is required." }
                val width = value.optInt("width", 0)
                val height = value.optInt("height", 0)
                require(QwenImage21SizeContract.isSupported(width, height)) {
                    QwenImage21SizeContract.unsupportedSizeMessage(width, height)
                }
                val steps = value.optInt("steps", 20)
                require(steps in 2..40) { "Qwen generation steps must be between 2 and 40." }
                val threads = value.optInt("threads", 4).coerceIn(1, 16)
                return GenerateRequest(
                    requestId = id,
                    bundleRoot = root,
                    bundleFingerprint = value.optString("bundleFingerprint").takeIf(String::isNotBlank),
                    prompt = prompt,
                    negativePrompt = negative,
                    inputImagePath = value.optString("inputImagePath").takeIf(String::isNotBlank),
                    outputPath = output,
                    width = width,
                    height = height,
                    steps = steps,
                    seed = value.optInt("seed", 42),
                    threads = threads,
                    useGpu = value.optBoolean("useGpu", true)
                )
            }
        }
    }

    private class QwenNativeException(val code: String, message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "MCA-QwenImage21"
        private const val STATE_UNLOADED = "UNLOADED"
        private const val STATE_LOADING = "LOADING"
        private const val STATE_READY = "READY"
        private const val STATE_PREPARING = "PREPARING"
        private const val STATE_GENERATING = "GENERATING"
        private const val STATE_CANCELLING = "CANCELLING"
        private const val STATE_UNLOADING = "UNLOADING"
        private const val STATE_FAILED = "FAILED"
        private const val CANCEL_KILL_DELAY_MS = 180L
        private const val MAX_ERROR_CHARS = 1000
    }
}

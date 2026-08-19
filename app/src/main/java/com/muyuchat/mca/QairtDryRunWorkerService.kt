package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.QairtExecutionPurpose
import com.muyuchat.core.engine.Role
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Product QAIRT admission worker.  It is deliberately declared in
 * :qairt_smoke, so a native hang or crash cannot poison the normal chat/UI
 * process.  Success is persisted only by McaInferenceService after visible
 * output and a clean native destroy.
 */
class QairtDryRunWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modelStore: ModelStoreRepository

    @Volatile
    private var active: ActiveRequest? = null

    private val binder = object : IQairtDryRunWorker.Stub() {
        override fun start(requestJson: String, callback: IQairtDryRunWorkerCallback): Boolean {
            val request = runCatching { QairtDryRunWorkerProtocol.parseStart(requestJson) }
                .getOrElse { error ->
                    sendError(
                        callback,
                        requestId = requestIdFromMalformedPayload(requestJson),
                        code = "invalid_request",
                        message = error.message ?: "无效的 QAIRT 隔离安全启动请求。"
                    )
                    return false
                }
            val next = ActiveRequest(request, callback)
            val accepted = synchronized(lock) {
                if (active != null) false else {
                    active = next
                    true
                }
            }
            if (!accepted) {
                sendError(callback, request.requestId, "worker_busy", "已有 QAIRT 隔离安全启动正在运行。")
                return false
            }
            next.deathRecipient = IBinder.DeathRecipient { cancelForDeadClient(next) }
            try {
                callback.asBinder().linkToDeath(requireNotNull(next.deathRecipient), 0)
            } catch (_: RemoteException) {
                finish(next)
                return false
            }
            val job = scope.launch {
                val started = System.currentTimeMillis()
                val hardWatchdog = Runnable {
                    if (synchronized(lock) { active === next }) {
                        // Native graph calls are synchronous and are not safely interruptible.
                        // Kill only this disposable process; the app process receives binder death
                        // and never records the exact-identity verification.
                        val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(
                            stage = next.stage,
                            timeoutMs = HARD_PROCESS_TIMEOUT_MS
                        )
                        sendError(callback, request.requestId, diagnostic.code, diagnostic.message)
                        Process.killProcess(Process.myPid())
                    }
                }
                handler.postDelayed(hardWatchdog, HARD_PROCESS_TIMEOUT_MS)
                try {
                    val result = execute(next, started)
                    if (!next.cancelRequested && sendComplete(callback, result)) {
                        // Result delivery is the last action; McaInferenceService has already
                        // atomically persisted the exact identity before this callback.
                    }
                } catch (_: TimeoutCancellationException) {
                    if (!next.cancelRequested) {
                        val diagnostic = IsolatedNativeFailureDiagnostics.timeout(next.stage)
                        sendError(callback, request.requestId, diagnostic.code, diagnostic.message)
                    }
                } catch (_: CancellationException) {
                    // Caller died/cancelled. Do not certify and let finally unload any handle.
                } catch (error: Throwable) {
                    val diagnostic = IsolatedNativeFailureDiagnostics.classify(error, next.stage)
                    sendError(
                        callback,
                        request.requestId,
                        code = diagnostic.code,
                        message = diagnostic.message.take(MAX_ERROR_MESSAGE_CHARS)
                    )
                } finally {
                    handler.removeCallbacks(hardWatchdog)
                    finish(next)
                }
            }
            synchronized(lock) {
                if (active === next) next.job = job else job.cancel()
            }
            return true
        }

        override fun cancel(requestJson: String): Boolean {
            val requestId = QairtDryRunWorkerProtocol.parseCancel(requestJson) ?: return false
            val current = synchronized(lock) { active?.takeIf { it.request.requestId == requestId } } ?: return false
            current.cancelRequested = true
            current.job?.cancel(CancellationException("QAIRT dry-run cancelled."))
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        modelStore = ModelStoreRepository(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val current = synchronized(lock) { active.also { active = null } }
        current?.job?.cancel(CancellationException("QAIRT dry-run service destroyed."))
        current?.let(::unlinkDeathRecipient)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(activeRequest: ActiveRequest, startedAt: Long): QairtDryRunWorkerProtocol.Result {
        val request = activeRequest.request
        val initialModel = modelStore.getModel(request.modelId)
            ?: error("未找到待自动安全启动的 QAIRT 本地模型。")
        require(initialModel.runtime == ChatModelRuntime.GENIEX_QAIRT) {
            "隔离安全启动只适用于 QAIRT NPU 模型。"
        }
        val preflight = modelStore.validateForLoad(initialModel.id)
        require(preflight.canLoad) { "模型包校验失败：${preflight.message}" }
        // validateForLoad may refresh the directory fingerprint.  Re-read before
        // creating a handle so an old SHA can never inherit a newer bundle's pass.
        val model = modelStore.getModel(initialModel.id) ?: initialModel
        require(model.sha256.isNotBlank()) { "QAIRT 模型包缺少安全启动所需的 SHA-256。" }
        val engine = McaInferenceService(applicationContext)
        var loaded = false
        var imageFile: File? = null
        val visionChecked = QairtDryRunPolicy.requiresVision(model)
        try {
            emit(activeRequest, startedAt, "load", "正在创建 QAIRT NPU handle…")
            withTimeout(LOAD_TIMEOUT_MS) {
                engine.loadModel(
                    modelPath = model.path,
                    runtime = LocalChatRuntime.GENIEX_QAIRT,
                    params = LoadParams(nCtx = request.nCtx, nThreads = request.nThreads),
                    qairtBundleSha256 = model.sha256,
                    qairtExecutionPurpose = QairtExecutionPurpose.ISOLATED_DRY_RUN
                ).getOrThrow()
            }
            loaded = true
            val loadedStats = JSONObject(engine.nativeStatsJson())
            require(hasNpuEvidence(loadedStats)) { "QAIRT 已加载但未取得骁龙 NPU 执行证据。" }
            if (visionChecked) {
                require(loadedStats.optBoolean("visionReady", false)) {
                    "QAIRT 图文模型未报告 visionReady=true。"
                }
            }
            emit(activeRequest, startedAt, "npu_ready", "已确认 QAIRT 骁龙 NPU 执行证据，正在生成固定检查回答…")
            emit(activeRequest, startedAt, "smoke", "正在执行固定 QAIRT smoke 检查…")
            val answer = withTimeout(GENERATION_TIMEOUT_MS) {
                generateFixedAnswer(engine, model, visionChecked, request.nCtx).also { value ->
                    ensureActive()
                    require(value.isNotBlank()) { "QAIRT 隔离安全启动没有产生可见正文。" }
                    if (visionChecked) {
                        require(QairtDryRunPolicy.visionAnswerPasses(value)) {
                            "QAIRT 图文安全检查未正确识别蓝色方块。"
                        }
                    } else {
                        require(QairtDryRunPolicy.textAnswerPasses(value)) {
                            "QAIRT 文本安全检查没有正确回答固定算术题。"
                        }
                    }
                }
            }
            emit(activeRequest, startedAt, "unload", "固定回答通过，正在销毁 QAIRT NPU handle…")
            withTimeout(UNLOAD_TIMEOUT_MS) { engine.unloadModel() }
            loaded = false
            require(engine.recordVerifiedQairtDryRun(model.sha256)) {
                "QAIRT 安全启动未满足 NPU、可见回答和干净销毁条件。"
            }
            val evidence = loadedStats.opt("backendDevices")?.toString().orEmpty()
            emit(activeRequest, startedAt, "verified", "QAIRT 隔离安全启动已通过。")
            return QairtDryRunWorkerProtocol.Result(
                requestId = request.requestId,
                bundleSha256 = model.sha256,
                npuEvidence = evidence,
                visibleChars = answer.length,
                visionChecked = visionChecked,
                elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            )
        } finally {
            imageFile?.delete()
            if (loaded) runCatching { withTimeout(UNLOAD_TIMEOUT_MS) { engine.unloadModel() } }
            runCatching { engine.shutdown() }
        }
    }

    private suspend fun generateFixedAnswer(
        engine: McaInferenceService,
        model: ModelManifest,
        visionChecked: Boolean,
        nCtx: Int
    ): String {
        val image = if (visionChecked) createBlueSquareImage(model.id) else null
        val message = ChatMessage(
            role = Role.USER,
            content = if (visionChecked) QairtDryRunPolicy.VISION_PROMPT else QairtDryRunPolicy.TEXT_PROMPT,
            imageAttachments = image?.let {
                listOf(
                    ChatImageAttachment(
                        name = it.name,
                        uriString = it.absolutePath,
                        mimeType = "image/png",
                        width = VISUAL_IMAGE_SIZE,
                        height = VISUAL_IMAGE_SIZE,
                        sizeBytes = it.length()
                    )
                )
            }.orEmpty()
        )
        return try {
            val output = StringBuilder()
            engine.streamChat(
                ChatRequest(
                    messages = listOf(message),
                    params = GenerationParams(
                        nCtx = nCtx,
                        nPredict = DRY_RUN_MAX_TOKENS,
                        nThreads = 1,
                        temperature = 0.0f,
                        topK = 1,
                        topP = 1.0f,
                        systemPrompt = "你是本地模型验收器。严格按用户要求作答。"
                    )
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Phase -> Unit
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> output.append(event.text)
                    is GenerateEvent.Error -> error(event.message)
                    is GenerateEvent.Done -> Unit
                }
            }
            output.toString().trim()
        } finally {
            image?.delete()
        }
    }

    private fun createBlueSquareImage(modelId: String): File {
        val directory = File(cacheDir, "qairt_dry_run").apply { mkdirs() }
        val file = File(directory, "${modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")}-blue-square.png")
        val bitmap = Bitmap.createBitmap(VISUAL_IMAGE_SIZE, VISUAL_IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 90, 255) }
            val inset = VISUAL_IMAGE_SIZE / 4f
            canvas.drawRect(inset, inset, VISUAL_IMAGE_SIZE - inset, VISUAL_IMAGE_SIZE - inset, paint)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法写入 QAIRT 图文安全检查图片。" }
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun hasNpuEvidence(stats: JSONObject): Boolean {
        if (!stats.optString("backend").equals(LocalChatRuntime.GENIEX_QAIRT.backendId, ignoreCase = true)) {
            return false
        }
        val devices = stats.opt("backendDevices")?.toString().orEmpty().lowercase()
        return "qairt" in devices && ("npu" in devices || "htp" in devices)
    }

    private fun emit(activeRequest: ActiveRequest, startedAt: Long, stage: String, message: String) {
        if (activeRequest.cancelRequested) throw CancellationException("QAIRT dry-run cancelled.")
        activeRequest.stage = stage
        runCatching {
            activeRequest.callback.onProgress(
                QairtDryRunWorkerProtocol.progress(
                    requestId = activeRequest.request.requestId,
                    stage = stage,
                    message = message,
                    elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                )
            )
        }.onFailure { cancelForDeadClient(activeRequest) }
    }

    private fun sendComplete(callback: IQairtDryRunWorkerCallback, result: QairtDryRunWorkerProtocol.Result): Boolean =
        runCatching {
            callback.onComplete(
                QairtDryRunWorkerProtocol.complete(
                    requestId = result.requestId,
                    bundleSha256 = result.bundleSha256,
                    npuEvidence = result.npuEvidence,
                    visibleChars = result.visibleChars,
                    visionChecked = result.visionChecked,
                    elapsedMs = result.elapsedMs
                )
            )
        }.isSuccess

    private fun sendError(callback: IQairtDryRunWorkerCallback, requestId: String, code: String, message: String) {
        runCatching { callback.onError(QairtDryRunWorkerProtocol.error(requestId, code, message)) }
    }

    private fun cancelForDeadClient(activeRequest: ActiveRequest) {
        if (synchronized(lock) { active === activeRequest }) {
            activeRequest.cancelRequested = true
            activeRequest.job?.cancel(CancellationException("QAIRT dry-run client disconnected."))
        }
    }

    private fun finish(activeRequest: ActiveRequest) {
        synchronized(lock) {
            if (active === activeRequest) active = null
        }
        unlinkDeathRecipient(activeRequest)
    }

    private fun unlinkDeathRecipient(activeRequest: ActiveRequest) {
        activeRequest.deathRecipient?.let { recipient ->
            runCatching { activeRequest.callback.asBinder().unlinkToDeath(recipient, 0) }
        }
    }

    private fun requestIdFromMalformedPayload(raw: String): String =
        runCatching { JSONObject(raw).optString("requestId") }.getOrDefault("")

    private class ActiveRequest(
        val request: QairtDryRunWorkerProtocol.Request,
        val callback: IQairtDryRunWorkerCallback
    ) {
        var deathRecipient: IBinder.DeathRecipient? = null
        var job: Job? = null

        @Volatile
        var stage: String = "request"

        @Volatile
        var cancelRequested: Boolean = false
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 45_000L
        private const val GENERATION_TIMEOUT_MS = 75_000L
        private const val UNLOAD_TIMEOUT_MS = 15_000L
        private const val HARD_PROCESS_TIMEOUT_MS = 120_000L
        private const val DRY_RUN_MAX_TOKENS = 32
        private const val VISUAL_IMAGE_SIZE = 64
        private const val MAX_ERROR_MESSAGE_CHARS = 1_024
    }
}

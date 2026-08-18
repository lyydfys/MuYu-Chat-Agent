package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.core.engine.PersistentPrefixCacheRequest
import com.muyuchat.core.engine.defaultLocalChatRunner
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Owns long-lived native text runtimes outside the product/UI process.
 *
 * A malformed GGUF, unsupported quantization operation, native abort, or native
 * hang can therefore only terminate this disposable process.  The main process
 * sees a Binder failure and converts it into the ordinary model-load/generation
 * error path.  This is an execution boundary, not a device admission gate.
 */
class LocalChatWorkerService : Service() {
    private val lock = Any()
    private val nativeOperationGate = ReentrantLock()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val operationSequence = AtomicLong(0L)
    private val watchdogs = ConcurrentHashMap<Long, Runnable>()
    private val runners = mutableMapOf<LocalChatRuntime, LocalChatRunner>()

    private lateinit var stageJournal: LocalChatWorkerStageJournal

    @Volatile
    private var diagnosticRuntime: String? = null

    @Volatile
    private var diagnosticModelFingerprint: String? = null

    @Volatile
    private var diagnosticParameterSummary: Map<String, Any> = emptyMap()

    @Volatile
    private var decodeStageJournaled = false

    @Volatile
    private var activeRuntime: LocalChatRuntime? = null

    @Volatile
    private var activeRunner: LocalChatRunner? = null

    @Volatile
    private var retainedLoadFailureStatsJson: String? = null

    @Volatile
    private var lastStableRuntimeStatsJson: String = IDLE_RUNTIME_STATS_JSON

    @Volatile
    private var activeNativeStage: String? = null

    /** Incremented under [lock] whenever the active native session identity changes. */
    private var runtimeStateEpoch: Long = 0L

    @Volatile
    private var nativeLibDir: String = ""

    private val binder = object : ILocalChatWorker.Stub() {
        override fun initRuntime(runtimeName: String, nativeLibraryDir: String) {
            val runtime = requireSupportedRuntime(runtimeName)
            val runner = runnerFor(runtime)
            nativeLibDir = nativeLibraryDir
            diagnosticRuntime = runtime.name
            if (activeRunner == null) promoteStartingWorkerToForeground()
            guarded("init") {
                runner.initBackends(nativeLibraryDir)
            }
        }

        override fun loadModel(runtimeName: String, modelPath: String, paramsJson: String): Int {
            val runtime = requireSupportedRuntime(runtimeName)
            val runner = runnerFor(runtime)
            var successfulReadback: JSONObject? = null
            var loadSucceeded = false
            prepareLoadDiagnostic(runtime, modelPath, paramsJson)
            try {
                val result = guarded(
                    stage = "load",
                    paramsJson = paramsJson,
                    failureCode = { rc: Int -> if (rc == 0) null else "native_load_failed" }
                ) {
                    synchronized(lock) {
                    retainedLoadFailureStatsJson = null
                    val previous = activeRunner
                    if (previous != null && previous !== runner) {
                        runCatching { previous.unloadModel() }
                    }
                    if (nativeLibDir.isNotBlank()) {
                        runner.initBackends(nativeLibDir)
                    }
                    activeRuntime = runtime
                    activeRunner = runner
                    runtimeStateEpoch += 1L
                    val result = runner.loadModel(modelPath, paramsJson)
                    if (result != 0) {
                        val failureStatsJson = LocalChatWorkerLoadFailureStats.capture(
                            runtime = runtime,
                            nativeLoadResult = result,
                            nativeStatsJson = runCatching {
                                guardedNativeCall("stats", recordStage = false) {
                                    runner.getRuntimeStatsJson()
                                }
                            }.getOrNull(),
                            modelPath = modelPath
                        )
                        runCatching { runner.unloadModel() }
                        activeRuntime = null
                        activeRunner = null
                        retainedLoadFailureStatsJson = failureStatsJson
                        lastStableRuntimeStatsJson = failureStatsJson
                        runtimeStateEpoch += 1L
                    } else {
                        val readbackStats = runCatching {
                            guardedNativeCall("stats", recordStage = false) {
                                runner.getRuntimeStatsJson()
                            }
                        }.getOrNull()
                        readbackStats?.let { lastStableRuntimeStatsJson = it }
                        successfulReadback = readbackStats
                            ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    }
                        result
                    }
                }
                if (result != 0) return result
                // Record only bounded state flags at the load/IPC boundary. This
                // distinguishes a native false readback from a later Binder-side
                // session loss without logging a model path or request content.
                val readback = successfulReadback
                Log.i(
                    WORKER_LOG_TAG,
                    "native load returned success; readbackLoaded=${readback?.optBoolean("loaded", false)} " +
                        "context=${readback?.optInt("nCtx", 0)} " +
                        "backend=${readback?.optString("backend").orEmpty()}"
                )
                promoteLoadedModelToForeground()
                recordContextReady()
                loadSucceeded = true
                return result
            } finally {
                if (!loadSucceeded) {
                    runCatching { runner.requestStop() }
                    runCatching { runner.unloadModel() }
                    synchronized(lock) {
                        if (activeRunner === runner) {
                            activeRuntime = null
                            activeRunner = null
                            runtimeStateEpoch += 1L
                        }
                    }
                    leaveLoadedModelForeground()
                    stopSelf()
                }
            }
        }

        override fun unloadModel() {
            guarded("unload") {
                synchronized(lock) {
                    activeRunner?.let { runner ->
                        runCatching { runner.requestStop() }
                        runner.unloadModel()
                    }
                    activeRuntime = null
                    activeRunner = null
                    retainedLoadFailureStatsJson = null
                    lastStableRuntimeStatsJson = IDLE_RUNTIME_STATS_JSON
                    runtimeStateEpoch += 1L
                }
            }
            leaveLoadedModelForeground()
            stopSelf()
            clearActiveDiagnostic()
            decodeStageJournaled = false
        }

        override fun beginCompletion(requestPayload: ParcelFileDescriptor): Int {
            val request = readBeginRequest(requestPayload)
            decodeStageJournaled = false
            return guarded(
                stage = "prefill",
                paramsJson = request.paramsJson,
                failureCode = { rc: Int -> if (rc == 0) null else "native_prefill_failed" }
            ) {
                require(!request.hasPrefixCache) {
                    "An ordinary isolated text request must not contain prefix-cache metadata."
                }
                requireRunner().beginCompletion(request.messagesJson, request.paramsJson)
            }
        }

        override fun beginCompletionWithPrefixCache(
            requestPayload: ParcelFileDescriptor
        ): Int {
            val request = readBeginRequest(requestPayload)
            decodeStageJournaled = false
            return guarded(
                stage = "prefill",
                paramsJson = request.paramsJson,
                failureCode = { rc: Int -> if (rc == 0) null else "native_prefill_failed" }
            ) {
                require(request.hasPrefixCache) {
                    "A prefix-cache request must contain prefix-cache metadata."
                }
                requireRunner().beginCompletionWithPrefixCache(
                    messagesJson = request.messagesJson,
                    paramsJson = request.paramsJson,
                    prefixCache = PersistentPrefixCacheRequest(
                        restoreStatePath = request.restoreStatePath,
                        writeStatePath = request.writeStatePath,
                        fixedSystemPrompt = requireNotNull(request.fixedSystemPrompt) {
                            "A prefix-cache request must contain its fixed system prompt."
                        },
                        fullSessionState = request.fullSessionState
                    )
                )
            }
        }

        override fun getPrefillProgressJson(): String =
            activeRunner?.prefillProgress()?.let { progress ->
                "{\"completedTokens\":${progress.completedTokens},\"totalTokens\":${progress.totalTokens}}"
            } ?: "{\"completedTokens\":0,\"totalTokens\":0}"

        override fun resetPrefillProgress() {
            // The progress snapshot is independent from the text KV state.
            guarded("reset_prefill", recordStage = false) { activeRunner?.resetPrefillProgress() }
        }

        override fun generateNextChunk(): String? {
            val recordDecodeStart = !decodeStageJournaled
            return guarded("decode", recordStage = recordDecodeStart) {
                requireRunner().generateNextChunk()
            }.also {
                decodeStageJournaled = true
            }
        }

        override fun invalidateConversationContext() {
            guarded("invalidate", recordStage = false) { activeRunner?.invalidateConversationContext() }
        }

        override fun requestStop() {
            runCatching { activeRunner?.requestStop() }
        }

        override fun requestStopIfActive(): Boolean =
            runCatching { activeRunner?.requestStopIfActive() == true }.getOrDefault(false)

        override fun getRuntimeStatsJson(): String {
            if (!nativeOperationGate.tryLock()) {
                return deferredRuntimeStatsJson(activeNativeStage ?: "native_operation")
            }
            return try {
                guardedNativeCall("stats", recordStage = false) {
                    val snapshot = synchronized(lock) {
                        RuntimeStatsReadSnapshot(
                            epoch = runtimeStateEpoch,
                            runner = activeRunner,
                            fallback = retainedLoadFailureStatsJson ?: lastStableRuntimeStatsJson
                        )
                    }
                    val stats = snapshot.runner?.getRuntimeStatsJson() ?: snapshot.fallback
                    val accepted = synchronized(lock) {
                        if (isRuntimeStatsSnapshotCurrent(
                                capturedEpoch = snapshot.epoch,
                                currentEpoch = runtimeStateEpoch,
                                sameRunner = activeRunner === snapshot.runner
                            )
                        ) {
                            lastStableRuntimeStatsJson = stats
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        return@guardedNativeCall deferredRuntimeStatsJson("session_changed")
                    }
                    val readback = runCatching { JSONObject(stats) }.getOrNull()
                    Log.i(
                        WORKER_LOG_TAG,
                        "runtime stats read; activeRunner=${snapshot.runner != null} " +
                            "loaded=${readback?.optBoolean("loaded", false)} " +
                            "backend=${readback?.optString("backend").orEmpty()}"
                    )
                    stats
                }
            } finally {
                nativeOperationGate.unlock()
            }
        }

        override fun shutdown() {
            guarded("shutdown") {
                synchronized(lock) {
                    activeRunner?.let { runner ->
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        runCatching { runner.shutdown() }
                    }
                    activeRuntime = null
                    activeRunner = null
                    retainedLoadFailureStatsJson = null
                    lastStableRuntimeStatsJson = IDLE_RUNTIME_STATS_JSON
                    runtimeStateEpoch += 1L
                }
            }
            leaveLoadedModelForeground()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureForegroundChannel()
        stageJournal = LocalChatWorkerStageJournal.forContext(applicationContext)
        runCatching { stageJournal.recordWorkerStarted(Process.myPid(), processPssKb()) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started foreground service survives loss of the UI binding. Once
        // loading succeeds, this notification represents the resident model.
        if (activeRunner == null) {
            promoteStartingWorkerToForeground()
        } else {
            promoteLoadedModelToForeground()
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeRunner == null) {
            leaveLoadedModelForeground()
            stopSelf()
        }
        return false
    }

    override fun onDestroy() {
        leaveLoadedModelForeground()
        watchdogs.values.forEach(watchdogHandler::removeCallbacks)
        watchdogs.clear()
        synchronized(lock) {
            // The worker process is disposable. Do not synchronously enter a
            // potentially wedged native shutdown from Android's lifecycle.
            activeRuntime = null
            activeRunner = null
            runners.clear()
            retainedLoadFailureStatsJson = null
            lastStableRuntimeStatsJson = IDLE_RUNTIME_STATS_JSON
            runtimeStateEpoch += 1L
        }
        super.onDestroy()
    }

    private fun promoteLoadedModelToForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 本地模型已加载")
            .setContentText("正在保留本地推理上下文，返回应用可继续聊天。")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun promoteStartingWorkerToForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA \u6b63\u5728\u51c6\u5907\u672c\u5730\u6a21\u578b")
            .setContentText("\u6b63\u5728\u542f\u52a8\u9694\u79bb\u63a8\u7406\u8fdb\u7a0b\u3002")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun leaveLoadedModelForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "本地模型",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本地模型驻留和推理状态通知"
                setShowBadge(false)
            }
        )
    }

    private fun requireSupportedRuntime(name: String): LocalChatRuntime {
        val runtime = runCatching { LocalChatRuntime.valueOf(name) }
            .getOrElse { error("Unknown local text runtime.") }
        require(
            runtime == LocalChatRuntime.LLAMA_CPP ||
                runtime == LocalChatRuntime.MNN_CPU ||
                runtime == LocalChatRuntime.GENIEX_QAIRT
        ) {
            "The isolated text worker does not support this runtime."
        }
        return runtime
    }

    private fun requireRunner(): LocalChatRunner =
        activeRunner ?: error("No local text model is loaded in the isolated worker.")

    private fun runnerFor(runtime: LocalChatRuntime): LocalChatRunner = synchronized(lock) {
        runners.getOrPut(runtime) {
            defaultLocalChatRunner(runtime, applicationContext)
        }
    }

    private fun <T> guarded(
        stage: String,
        paramsJson: String? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T = nativeOperationGate.withLock {
        guardedNativeCall(stage, paramsJson, recordStage, failureCode, block)
    }

    private fun <T> guardedNativeCall(
        stage: String,
        paramsJson: String? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T {
        if (recordStage) recordStageStarted(stage, paramsJson)
        val token = operationSequence.incrementAndGet()
        val timeoutMs = watchdogTimeoutMs(stage)
        val previousStage = activeNativeStage
        activeNativeStage = stage
        val timeout = Runnable {
            if (watchdogs.remove(token) != null) {
                // A native call that never returns is as unsafe as a native
                // crash.  Kill only this worker process; the Binder client will
                // surface a bounded remote failure to the UI.
                val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(stage, timeoutMs)
                if (recordStage || stage == "decode") recordStageFailure(stage, diagnostic.code)
                Log.e(WORKER_LOG_TAG, "${diagnostic.code}: ${diagnostic.message}")
                Process.killProcess(Process.myPid())
            }
        }
        watchdogs[token] = timeout
        watchdogHandler.postDelayed(timeout, timeoutMs)
        return try {
            block().also { result ->
                if (recordStage) {
                    failureCode(result)?.let { code ->
                        recordStageFailure(stage, code)
                    } ?: recordStageCompleted(stage, paramsJson)
                }
            }
        } catch (error: Throwable) {
            if (recordStage || stage == "decode") {
                val code = runCatching {
                    IsolatedNativeFailureDiagnostics.classify(error, stage).code
                }.getOrDefault("native_worker_failed")
                recordStageFailure(stage, code)
            }
            throw error
        } finally {
            watchdogs.remove(token)?.let(watchdogHandler::removeCallbacks)
            if (activeNativeStage == stage) activeNativeStage = previousStage
        }
    }

    private fun watchdogTimeoutMs(stage: String): Long = when {
        stage == "load" -> NATIVE_LOAD_TIMEOUT_MS
        stage == "prefill" -> NATIVE_PREFILL_TIMEOUT_MS
        stage == "stats" -> NATIVE_STATS_TIMEOUT_MS
        else -> NATIVE_OPERATION_TIMEOUT_MS
    }

    private fun deferredRuntimeStatsJson(stage: String): String {
        return buildDeferredRuntimeStatsJson(lastStableRuntimeStatsJson, stage)
    }

    private fun prepareLoadDiagnostic(runtime: LocalChatRuntime, modelPath: String, paramsJson: String) {
        diagnosticRuntime = runtime.name
        diagnosticModelFingerprint = LocalChatWorkerStageJournal.modelFingerprint(modelPath)
        diagnosticParameterSummary = LocalChatWorkerStageJournal.parameterSummary(paramsJson)
    }

    private fun clearActiveDiagnostic() {
        diagnosticRuntime = null
        diagnosticModelFingerprint = null
        diagnosticParameterSummary = emptyMap()
    }

    private fun readBeginRequest(requestPayload: ParcelFileDescriptor):
        LocalChatWorkerRequestTransport.BeginRequest {
        recordStageStarted("preflight", null)
        return try {
            LocalChatWorkerRequestTransport.read(requestPayload).also {
                recordStageCompleted("preflight", it.paramsJson)
            }
        } catch (error: Throwable) {
            val code = runCatching {
                IsolatedNativeFailureDiagnostics.classify(error, "preflight").code
            }.getOrDefault("native_worker_failed")
            recordStageFailure("preflight", code)
            throw error
        }
    }

    private fun recordContextReady() {
        recordStageCompleted("context", null)
    }

    private fun recordStageStarted(stage: String, paramsJson: String?) {
        paramsJson?.let { diagnosticParameterSummary = LocalChatWorkerStageJournal.parameterSummary(it) }
        runCatching {
            stageJournal.recordStarted(
                stage = stage,
                runtime = diagnosticRuntime,
                modelFingerprint = diagnosticModelFingerprint,
                parameterSummary = diagnosticParameterSummary,
                workerPid = Process.myPid(),
                pssKb = processPssKb()
            )
        }
    }

    private fun recordStageCompleted(stage: String, paramsJson: String?) {
        paramsJson?.let { diagnosticParameterSummary = LocalChatWorkerStageJournal.parameterSummary(it) }
        runCatching {
            stageJournal.recordCompleted(
                stage = stage,
                runtime = diagnosticRuntime,
                modelFingerprint = diagnosticModelFingerprint,
                parameterSummary = diagnosticParameterSummary,
                workerPid = Process.myPid(),
                pssKb = processPssKb()
            )
        }
    }

    private fun recordStageFailure(stage: String, code: String) {
        runCatching {
            stageJournal.recordFailure(
                stage = stage,
                runtime = diagnosticRuntime,
                modelFingerprint = diagnosticModelFingerprint,
                parameterSummary = diagnosticParameterSummary,
                workerPid = Process.myPid(),
                pssKb = processPssKb(),
                failureCode = code
            )
        }
    }

    private fun processPssKb(): Long = runCatching {
        Debug.getPss().coerceAtLeast(0L)
    }.getOrDefault(0L)

    private companion object {
        private const val IDLE_RUNTIME_STATS_JSON = "{\"loaded\":false,\"runnerReady\":true}"
        private const val FOREGROUND_CHANNEL_ID = "mca_local_model"
        private const val FOREGROUND_NOTIFICATION_ID = 11436
        private const val WORKER_LOG_TAG = "McaLocalChatWorker"
        private const val NATIVE_OPERATION_TIMEOUT_MS = 3 * 60 * 1000L
        private const val NATIVE_STATS_TIMEOUT_MS = 15 * 1000L
        private const val NATIVE_LOAD_TIMEOUT_MS = 30 * 60 * 1000L
        private const val NATIVE_PREFILL_TIMEOUT_MS = 30 * 60 * 1000L
    }

    private data class RuntimeStatsReadSnapshot(
        val epoch: Long,
        val runner: LocalChatRunner?,
        val fallback: String
    )
}

internal fun isRuntimeStatsSnapshotCurrent(
    capturedEpoch: Long,
    currentEpoch: Long,
    sameRunner: Boolean
): Boolean = sameRunner && capturedEpoch == currentEpoch

internal fun buildDeferredRuntimeStatsJson(stableStatsJson: String, stage: String): String =
    runCatching {
        JSONObject(stableStatsJson)
            .put("runtimeStatsDeferred", true)
            .put("runtimeBusyStage", stage)
            .toString()
    }.getOrElse {
        JSONObject()
            .put("loaded", false)
            .put("runnerReady", true)
            .put("runtimeStatsDeferred", true)
            .put("runtimeBusyStage", stage)
            .toString()
    }

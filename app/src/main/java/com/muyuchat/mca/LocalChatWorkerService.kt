package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Owns long-lived native text runtimes outside the product/UI process.
 *
 * A malformed GGUF, unsupported quantization operation, native abort, or native
 * hang can therefore only terminate this disposable process.  The main process
 * sees a Binder failure and converts it into the ordinary model-load/generation
 * error path.  This is an execution boundary, not a device admission gate.
 */
open class LocalChatWorkerService : Service() {
    protected open val journalScope: String? = null
    protected open val foregroundNotificationId: Int get() = FOREGROUND_NOTIFICATION_ID
    protected open val nativeOperationTimeoutLimitMs: Long? = null
    private val lock = Any()
    internal val nativeOperationGate = ReentrantLock()
    internal val watchdogHandler = Handler(Looper.getMainLooper())
    internal val operationSequence = AtomicLong(0L)
    internal val generationSequence = AtomicLong(0L)
    internal val watchdogs = ConcurrentHashMap<Long, Runnable>()
    internal val cancellationWatchdogs = ConcurrentHashMap<Long, Runnable>()
    internal val liteRtCancellationState = LocalChatCancellationState()
    internal val liteRtCancellationWatchdogs = ConcurrentHashMap<Long, Runnable>()
    private val runners = mutableMapOf<LocalChatRuntime, LocalChatRunner>()

    internal lateinit var stageJournal: LocalChatWorkerStageJournal

    @Volatile
    internal var diagnosticRuntime: String? = null

    @Volatile
    internal var diagnosticModelFingerprint: String? = null

    @Volatile
    internal var diagnosticParameterSummary: Map<String, Any> = emptyMap()

    @Volatile
    private var decodeStageJournaled = false

    @Volatile
    internal var activeRuntime: LocalChatRuntime? = null

    @Volatile
    internal var activeRunner: LocalChatRunner? = null

    @Volatile
    private var retainedLoadFailureStatsJson: String? = null

    @Volatile
    internal var lastStableRuntimeStatsJson: String = withRuntimeStatsLoadGeneration(IDLE_RUNTIME_STATS_JSON, 0L)

    @Volatile
    internal var activeNativeStage: String? = null

    /** The token lets a delayed cancellation target only the native call that was active then. */
    @Volatile
    internal var activeNativeOperationToken: Long = 0L

    /** Retained after a successful load so decode calls without params keep their real backend budget. */
    @Volatile
    internal var activeOperationTarget: LocalChatWorkerOperationTarget? = null

    /** Incremented under [lock] whenever the active native session identity changes. */
    internal var runtimeStateEpoch: Long = 0L

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
            val loadTarget = localChatWorkerOperationTarget(runtime, paramsJson)
            activeOperationTarget = loadTarget
            var successfulReadback: JSONObject? = null
            var loadSucceeded = false
            var loadEpoch: Long? = null
            prepareLoadDiagnostic(runtime, modelPath, paramsJson)
            try {
                val result = guarded(
                    stage = "load",
                    paramsJson = paramsJson,
                    operationTarget = loadTarget,
                    failureCode = { rc: Int -> if (rc == 0) null else "native_load_failed" }
                ) {
                    synchronized(lock) {
                    retainedLoadFailureStatsJson = null
                    val previous = activeRunner
                    activeRuntime = null
                    activeRunner = null
                    invalidateRuntimeStatsLocked()
                    loadEpoch = runtimeStateEpoch
                    if (previous != null && previous !== runner) {
                        runCatching { previous.unloadModel() }
                    }
                    if (runtime == LocalChatRuntime.LITERT_LM && liteRtLmNpuRequested(paramsJson)) {
                        // LiteRT-LM's Qualcomm transport must not resolve the
                        // same SONAMEs from GenieX's QAIRT 2.45 APK payload.
                        // Stage the Edge Gallery-compatible set privately and
                        // let native load remain the final compatibility test.
                        val genericNativeLibDir = applicationContext.applicationInfo.nativeLibraryDir
                        if (genericNativeLibDir.isNotBlank()) {
                            // A previous request may have selected a staged
                            // directory. Reset first so a later staging miss
                            // cannot accidentally reuse that old transport.
                            nativeLibDir = genericNativeLibDir
                        }
                        val staged = LiteRtQualcommRuntimeStager.stage(applicationContext)
                        if (staged != null) {
                            nativeLibDir = staged.directory.absolutePath
                            Log.i(
                                WORKER_LOG_TAG,
                                "Using staged LiteRT Qualcomm runtime " +
                                    "variant=${staged.variant} " +
                                    "fingerprint=${staged.fingerprint.take(12)} reused=${staged.reused}"
                            )
                            runner.initBackends(nativeLibDir)
                        } else {
                            // A missing or incomplete staged asset set is not a
                            // device admission failure. Keep the generic native
                            // directory and let LiteRT's real load report the
                            // concrete compatibility error.
                            Log.w(
                                WORKER_LOG_TAG,
                                "LiteRT Qualcomm runtime staging unavailable; " +
                                    "continuing with generic native library directory"
                            )
                        }
                    }
                    if (nativeLibDir.isNotBlank()) {
                        runner.initBackends(nativeLibDir)
                    }
                    activeRuntime = runtime
                    activeRunner = runner
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
                        invalidateRuntimeStatsLocked(failureStatsJson)
                        retainedLoadFailureStatsJson = lastStableRuntimeStatsJson
                    } else {
                        val readbackStats = runCatching {
                            guardedNativeCall("stats", recordStage = false) {
                                runner.getRuntimeStatsJson()
                            }
                        }.getOrNull()
                        readbackStats?.let {
                            lastStableRuntimeStatsJson = withRuntimeStatsLoadGeneration(it, runtimeStateEpoch)
                        }
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
                    runCatching {
                        guarded("unload", recordStage = false) {
                            synchronized(lock) {
                                if (loadEpoch == runtimeStateEpoch) {
                                    activeRuntime = null
                                    activeRunner = null
                                    invalidateRuntimeStatsLocked()
                                    runCatching { runner.requestStop() }
                                    runner.unloadModel()
                                }
                            }
                        }
                    }
                    leaveLoadedModelForeground()
                    stopSelf()
                }
                // Keep the selected accelerator after a successful load: decode
                // calls have no parameter payload, yet must retain the same
                // backend-specific watchdog and cancellation behavior.
                if (!loadSucceeded && activeOperationTarget === loadTarget) {
                    activeOperationTarget = null
                }
            }
        }

        override fun unloadModel() {
            guarded("unload") {
                synchronized(lock) {
                    val runner = activeRunner
                    activeRuntime = null
                    activeRunner = null
                    activeOperationTarget = null
                    retainedLoadFailureStatsJson = null
                    invalidateRuntimeStatsLocked()
                    runner?.let {
                        runCatching { runner.requestStop() }
                        runner.unloadModel()
                    }
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
                requireRunner().beginCompletion(request.messagesJson, request.paramsJson).also {
                    if (it == 0) generationSequence.incrementAndGet()
                }
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
                ).also { if (it == 0) generationSequence.incrementAndGet() }
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
            if (requestLiteRtStop() != null) return
            if ((activeOperationTarget?.runtime ?: activeRuntime) == LocalChatRuntime.MNN_CPU) {
                // MNN publishes cancellation through a lock-free native flag,
                // but the Java runner still has a lifecycle monitor around its
                // bridge call.  Never invoke it on this Binder thread: a stale
                // implementation or JNI transition can still wait behind a
                // native operation and prevent the recovery watchdog from being
                // armed.  Arm recovery first, then signal on a disposable
                // thread.  The worker is isolated, so killing it after the
                // grace window cannot take down the UI process.
                scheduleForcedRecoveryAfterStop()
                scheduleMnnProducerRecoveryAfterStop()
                dispatchRunnerStop()
                return
            }
            scheduleForcedRecoveryAfterStop()
            dispatchRunnerStop()
        }

        override fun requestStopIfActive(): Boolean {
            requestLiteRtStop()?.let { return it }
            // This method is used by the Local API stop route, which needs a
            // synchronous accepted/not-accepted result. Do not call a native
            // requestStopIfActive() here: some runners serialize that query
            // behind the same native mutex as decode and can block Binder for
            // tens of seconds. The worker's stage token is the authoritative
            // active-operation proof; requestStop() itself is dispatched
            // without waiting for native generation to return.
            val active = activeNativeStage != null || activeRunner?.isGenerationRunning() == true
            if (!active) return false
            scheduleForcedRecoveryAfterStop()
            scheduleMnnProducerRecoveryAfterStop()
            dispatchRunnerStop()
            return true
        }

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
                    val stats = withRuntimeStatsLoadGeneration(
                        snapshot.runner?.getRuntimeStatsJson() ?: snapshot.fallback,
                        snapshot.epoch
                    )
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

        override fun canReleasePreparedInputs(): Boolean {
            if (!nativeOperationGate.tryLock()) return false
            return try {
                activeNativeStage == null && (activeRunner?.canReleasePreparedInputs() ?: true)
            } finally {
                nativeOperationGate.unlock()
            }
        }

        override fun shutdown() {
            guarded("shutdown") {
                synchronized(lock) {
                    val runner = activeRunner
                    activeRuntime = null
                    activeRunner = null
                    activeOperationTarget = null
                    retainedLoadFailureStatsJson = null
                    invalidateRuntimeStatsLocked()
                    runner?.let {
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        runCatching { runner.shutdown() }
                    }
                }
            }
            leaveLoadedModelForeground()
            stopSelf()
        }

        override fun recycleForReplacement() {
            // B44 (device A 2026-10-16): a replacement load must start in a fresh process.
            // Unload the resident model like shutdown(), then end this process so the QNN /
            // DMA-BUF allocator state cannot overlap the next model. The caller treats the
            // following Binder death as the recycle acknowledgement (oneway, no reply).
            guarded("recycle") {
                synchronized(lock) {
                    val runner = activeRunner
                    activeRuntime = null
                    activeRunner = null
                    activeOperationTarget = null
                    retainedLoadFailureStatsJson = null
                    invalidateRuntimeStatsLocked()
                    runner?.let {
                        runCatching { it.requestStop() }
                        runCatching { it.unloadModel() }
                        runCatching { it.shutdown() }
                    }
                }
            }
            leaveLoadedModelForeground()
            stopSelf()
            Process.killProcess(Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureForegroundChannel()
        stageJournal = LocalChatWorkerStageJournal.forContext(applicationContext, journalScope)
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
        cancellationWatchdogs.values.forEach(watchdogHandler::removeCallbacks)
        cancellationWatchdogs.clear()
        liteRtCancellationState.clear()
        liteRtCancellationWatchdogs.values.forEach(watchdogHandler::removeCallbacks)
        liteRtCancellationWatchdogs.clear()
        synchronized(lock) {
            // The worker process is disposable. Do not synchronously enter a
            // potentially wedged native shutdown from Android's lifecycle.
            activeRuntime = null
            activeRunner = null
            activeOperationTarget = null
            activeNativeStage = null
            activeNativeOperationToken = 0L
            runners.clear()
            retainedLoadFailureStatsJson = null
            invalidateRuntimeStatsLocked()
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
            foregroundNotificationId,
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
            foregroundNotificationId,
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
                runtime == LocalChatRuntime.GENIEX_LLAMA_CPP ||
                runtime == LocalChatRuntime.GENIEX_QAIRT ||
                runtime == LocalChatRuntime.LITERT_LM
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

    /** Extension seam for the native watchdog budget; the protected override contract stays. */
    internal val nativeOperationTimeoutLimitMsForCall: Long? get() = nativeOperationTimeoutLimitMs

    private companion object {
        private const val FOREGROUND_CHANNEL_ID = "mca_local_model"
        private const val FOREGROUND_NOTIFICATION_ID = 11436
    }
}

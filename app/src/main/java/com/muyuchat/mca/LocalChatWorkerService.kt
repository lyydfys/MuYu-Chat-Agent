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
    private val cancellationWatchdogs = ConcurrentHashMap<Long, Runnable>()
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

    /** The token lets a delayed cancellation target only the native call that was active then. */
    @Volatile
    private var activeNativeOperationToken: Long = 0L

    /** Retained after a successful load so decode calls without params keep their real backend budget. */
    @Volatile
    private var activeOperationTarget: LocalChatWorkerOperationTarget? = null

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
            val loadTarget = localChatWorkerOperationTarget(runtime, paramsJson)
            activeOperationTarget = loadTarget
            var successfulReadback: JSONObject? = null
            var loadSucceeded = false
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
                    activeRunner?.let { runner ->
                        runCatching { runner.requestStop() }
                        runner.unloadModel()
                    }
                    activeRuntime = null
                    activeRunner = null
                    activeOperationTarget = null
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
            if (scheduleForcedRecoveryAfterStop()) return
            runCatching { activeRunner?.requestStop() }
        }

        override fun requestStopIfActive(): Boolean {
            if (scheduleForcedRecoveryAfterStop()) return true
            return runCatching { activeRunner?.requestStopIfActive() == true }.getOrDefault(false)
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
                    activeOperationTarget = null
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
        cancellationWatchdogs.values.forEach(watchdogHandler::removeCallbacks)
        cancellationWatchdogs.clear()
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

    private fun liteRtLmNpuRequested(paramsJson: String): Boolean {
        val root = runCatching { JSONObject(paramsJson.ifBlank { "{}" }) }.getOrNull() ?: return false
        val advanced = root.opt("advanced_json")?.let { raw ->
            when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            }
        }
        fun value(vararg names: String): Any? = names.asSequence()
            .mapNotNull { name ->
                root.opt(name).takeIf { it != JSONObject.NULL }
                    ?: advanced?.opt(name)?.takeIf { it != JSONObject.NULL }
            }
            .firstOrNull()
        val raw = value("backend", "backend_type", "backendType")
        return when (raw?.toString()?.trim()?.lowercase()?.replace('-', '_')) {
            "npu", "qnn", "qualcomm" -> true
            else -> false
        }
    }

    private fun <T> guarded(
        stage: String,
        paramsJson: String? = null,
        operationTarget: LocalChatWorkerOperationTarget? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T = nativeOperationGate.withLock {
        guardedNativeCall(stage, paramsJson, operationTarget, recordStage, failureCode, block)
    }

    private fun <T> guardedNativeCall(
        stage: String,
        paramsJson: String? = null,
        operationTarget: LocalChatWorkerOperationTarget? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T {
        if (recordStage) recordStageStarted(stage, paramsJson)
        val token = operationSequence.incrementAndGet()
        val operationPolicy = watchdogOperationPolicy(stage, paramsJson, operationTarget)
        val timeoutMs = operationPolicy.timeoutMs
        val previousStage = activeNativeStage
        val previousToken = activeNativeOperationToken
        activeNativeStage = stage
        activeNativeOperationToken = token
        val timeout = Runnable {
            if (watchdogs.remove(token) != null) {
                // A native call that never returns is as unsafe as a native
                // crash.  Kill only this worker process; the Binder client will
                // surface a bounded remote failure to the UI.
                val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(
                    stage = stage,
                    timeoutMs = timeoutMs,
                    code = operationPolicy.timeoutFailureCode,
                    operationLabel = operationPolicy.operationLabel
                )
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
            cancellationWatchdogs.remove(token)?.let(watchdogHandler::removeCallbacks)
            if (activeNativeStage == stage && activeNativeOperationToken == token) {
                activeNativeStage = previousStage
                activeNativeOperationToken = previousToken
            }
        }
    }

    private fun watchdogOperationPolicy(
        stage: String,
        paramsJson: String?,
        operationTarget: LocalChatWorkerOperationTarget? = null
    ): LocalChatWorkerOperationPolicy {
        // Runtime stats are best-effort telemetry. During a long native
        // prefill/decode the stats call can wait behind the native operation;
        // a short 15s watchdog must never kill a worker that is still making
        // forward progress. Give this readback the same generous bound as the
        // active generation operation.
        if (stage.equals("stats", ignoreCase = true) &&
            (activeNativeStage.equals("prefill", ignoreCase = true) ||
                activeNativeStage.equals("decode", ignoreCase = true))
        ) {
            return LocalChatWorkerOperationPolicy(
                timeoutMs = 30L * 60L * 1_000L,
                timeoutFailureCode = "worker_watchdog_timeout",
                operationLabel = "runtime stats during native generation"
            )
        }
        // A load request can switch runtimes while the previous model is still
        // active. Its policy must come from the incoming target, not stale
        // activeRuntime state from the previous session.
        if (operationTarget != null) {
            return localChatWorkerOperationPolicy(operationTarget, stage)
        }
        val runtime = activeRuntime ?: diagnosticRuntime
            ?.let { raw -> runCatching { LocalChatRuntime.valueOf(raw) }.getOrNull() }
        val parsed = runtime?.let { localChatWorkerOperationTarget(it, paramsJson) }
        val target = parsed?.takeIf { it.backend != null } ?: activeOperationTarget ?: parsed
        return localChatWorkerOperationPolicy(target, stage)
    }

    /**
     * Some native calls hold their own non-interruptible mutex. Do not enter
     * that mutex from a second Binder thread after a user has pressed stop;
     * reclaim the isolated process after a short grace period instead.
     */
    private fun scheduleForcedRecoveryAfterStop(): Boolean {
        val stage = activeNativeStage ?: return false
        val token = activeNativeOperationToken
        if (token == 0L || !nativeOperationGate.isLocked) return false
        val policy = watchdogOperationPolicy(stage, null)
        if (!policy.forceProcessRecoveryOnCancel) return false
        if (cancellationWatchdogs.containsKey(token)) return true
        val watchdog = Runnable {
            if (cancellationWatchdogs.remove(token) == null ||
                activeNativeOperationToken != token ||
                !nativeOperationGate.isLocked
            ) {
                return@Runnable
            }
            val cancellationCode = policy.timeoutFailureCode
                .removeSuffix("_timeout")
                .plus("_cancel_timeout")
            val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(
                stage = stage,
                timeoutMs = NATIVE_CANCEL_GRACE_TIMEOUT_MS,
                code = cancellationCode,
                operationLabel = policy.operationLabel
            )
            recordStageFailure(stage, diagnostic.code)
            Log.w(WORKER_LOG_TAG, "${diagnostic.code}: ${diagnostic.message}")
            Process.killProcess(Process.myPid())
        }
        if (cancellationWatchdogs.putIfAbsent(token, watchdog) == null) {
            watchdogHandler.postDelayed(watchdog, NATIVE_CANCEL_GRACE_TIMEOUT_MS)
        }
        return true
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
        private const val NATIVE_CANCEL_GRACE_TIMEOUT_MS = 1_500L
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

/** The selected text runtime/backend at the load boundary, never a device admission decision. */
internal data class LocalChatWorkerOperationTarget(
    val runtime: LocalChatRuntime,
    val backend: String?
)

/**
 * Per-operation hard limit for an isolated text worker. The generic limits
 * preserve existing long-context behavior; only currently observed accelerator
 * wedges use the shorter bounds below.
 */
internal data class LocalChatWorkerOperationPolicy(
    val timeoutMs: Long,
    val timeoutFailureCode: String,
    val operationLabel: String? = null,
    val forceProcessRecoveryOnCancel: Boolean = false
)

internal fun localChatWorkerOperationTarget(
    runtime: LocalChatRuntime,
    paramsJson: String?
): LocalChatWorkerOperationTarget {
    val root = runCatching { JSONObject(paramsJson.orEmpty().ifBlank { "{}" }) }.getOrNull()
    val advanced = root?.opt("advanced_json")?.let { raw ->
        when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }
    fun value(vararg names: String): Any? = names.asSequence().mapNotNull { name ->
        root
            ?.takeIf { it.has(name) && !it.isNull(name) }
            ?.opt(name)
            ?: advanced
                ?.takeIf { it.has(name) && !it.isNull(name) }
                ?.opt(name)
    }.firstOrNull()
    val rawBackend = when (runtime) {
        LocalChatRuntime.MNN_CPU -> value("backend_type", "backendType", "backend")
        LocalChatRuntime.LITERT_LM -> value("backend", "backend_type", "backendType")
        else -> value("backend", "backend_type", "backendType")
    }
    val backend = rawBackend
        ?.toString()
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            when (value) {
                "gpu", "opencl", "open_cl" ->
                    if (runtime == LocalChatRuntime.MNN_CPU) "opencl" else "gpu"
                "cpu", "host" -> "cpu"
                "npu", "qnn", "qualcomm" -> "npu"
                else -> value
            }
        }
    return LocalChatWorkerOperationTarget(runtime = runtime, backend = backend)
}

internal fun localChatWorkerOperationPolicy(
    target: LocalChatWorkerOperationTarget?,
    rawStage: String
): LocalChatWorkerOperationPolicy {
    val stage = when {
        rawStage.equals("load", ignoreCase = true) -> "load"
        rawStage.contains("prefill", ignoreCase = true) -> "prefill"
        rawStage.contains("decode", ignoreCase = true) -> "decode"
        rawStage.equals("stats", ignoreCase = true) -> "stats"
        else -> "operation"
    }
    fun accelerated(
        timeoutMs: Long,
        failureCode: String,
        operationLabel: String
    ) = LocalChatWorkerOperationPolicy(
        timeoutMs = timeoutMs,
        timeoutFailureCode = failureCode,
        operationLabel = operationLabel,
        forceProcessRecoveryOnCancel = true
    )

    return when {
        target?.runtime == LocalChatRuntime.LITERT_LM && target.backend == "gpu" -> when (stage) {
            "load" -> accelerated(120_000L, "litert_gpu_load_timeout", "LiteRT-LM GPU")
            "prefill" -> accelerated(90_000L, "litert_gpu_prefill_timeout", "LiteRT-LM GPU")
            "decode" -> accelerated(90_000L, "litert_gpu_decode_timeout", "LiteRT-LM GPU")
            else -> defaultLocalChatWorkerOperationPolicy(stage)
        }
        target?.runtime == LocalChatRuntime.MNN_CPU && target.backend == "opencl" -> when (stage) {
            "load" -> accelerated(120_000L, "mnn_opencl_load_timeout", "MNN OpenCL")
            "prefill" -> accelerated(75_000L, "mnn_opencl_prefill_timeout", "MNN OpenCL")
            "decode" -> accelerated(45_000L, "mnn_opencl_decode_timeout", "MNN OpenCL")
            else -> defaultLocalChatWorkerOperationPolicy(stage)
        }
        else -> defaultLocalChatWorkerOperationPolicy(stage)
    }
}

private fun defaultLocalChatWorkerOperationPolicy(stage: String): LocalChatWorkerOperationPolicy =
    when (stage) {
        "load" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        "prefill" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        // Stats are advisory and can legitimately wait behind a long native
        // prefill/decode. A short timeout here used to kill healthy workers.
        "stats" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        else -> LocalChatWorkerOperationPolicy(3L * 60L * 1_000L, "worker_watchdog_timeout")
    }

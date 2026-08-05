package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.core.engine.PersistentPrefixCacheRequest
import com.muyuchat.core.engine.defaultLocalChatRunner
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

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
    private var nativeLibDir: String = ""

    private val binder = object : ILocalChatWorker.Stub() {
        override fun initRuntime(runtimeName: String, nativeLibraryDir: String) {
            val runtime = requireSupportedRuntime(runtimeName)
            val runner = runnerFor(runtime)
            nativeLibDir = nativeLibraryDir
            diagnosticRuntime = runtime.name
            guarded("init") {
                runner.initBackends(nativeLibraryDir)
            }
        }

        override fun loadModel(runtimeName: String, modelPath: String, paramsJson: String): Int {
            val runtime = requireSupportedRuntime(runtimeName)
            val runner = runnerFor(runtime)
            retainedLoadFailureStatsJson = null
            prepareLoadDiagnostic(runtime, modelPath, paramsJson)
            val result = guarded(
                stage = "load",
                paramsJson = paramsJson,
                failureCode = { rc: Int -> if (rc == 0) null else "native_load_failed" }
            ) {
                synchronized(lock) {
                    val previous = activeRunner
                    if (previous != null && previous !== runner) {
                        runCatching { previous.unloadModel() }
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
                                runner.getRuntimeStatsJson()
                            }.getOrNull(),
                            modelPath = modelPath
                        )
                        runCatching { runner.unloadModel() }
                        activeRuntime = null
                        activeRunner = null
                        retainedLoadFailureStatsJson = failureStatsJson
                    }
                    result
                }
            }
            if (result == 0) recordContextReady()
            return result
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
                }
            }
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
                        }
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

        override fun getRuntimeStatsJson(): String =
            activeRunner?.getRuntimeStatsJson()
                ?: retainedLoadFailureStatsJson
                ?: "{\"loaded\":false,\"runnerReady\":true}"

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
                }
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        stageJournal = LocalChatWorkerStageJournal.forContext(applicationContext)
        runCatching { stageJournal.recordWorkerStarted(Process.myPid(), processPssKb()) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        watchdogs.values.forEach(watchdogHandler::removeCallbacks)
        watchdogs.clear()
        synchronized(lock) {
            // The worker process is disposable. Do not synchronously enter a
            // potentially wedged native shutdown from Android's lifecycle.
            activeRuntime = null
            activeRunner = null
            runners.clear()
        }
        super.onDestroy()
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
    ): T {
        if (recordStage) recordStageStarted(stage, paramsJson)
        val token = operationSequence.incrementAndGet()
        val timeoutMs = watchdogTimeoutMs(stage)
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
        }
    }

    private fun watchdogTimeoutMs(stage: String): Long = when {
        stage == "load" -> NATIVE_LOAD_TIMEOUT_MS
        stage == "prefill" -> NATIVE_PREFILL_TIMEOUT_MS
        else -> NATIVE_OPERATION_TIMEOUT_MS
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
        private const val WORKER_LOG_TAG = "McaLocalChatWorker"
        private const val NATIVE_OPERATION_TIMEOUT_MS = 3 * 60 * 1000L
        private const val NATIVE_LOAD_TIMEOUT_MS = 30 * 60 * 1000L
        private const val NATIVE_PREFILL_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

package com.muyuchat.mca

import android.os.Process
import android.util.Log
import com.muyuchat.core.engine.LocalChatRuntime
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

internal const val WORKER_LOG_TAG = "McaLocalChatWorker"

internal const val NATIVE_CANCEL_GRACE_TIMEOUT_MS = 1_500L

internal fun <T> LocalChatWorkerService.guarded(
        stage: String,
        paramsJson: String? = null,
        operationTarget: LocalChatWorkerOperationTarget? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T = nativeOperationGate.withLock {
        guardedNativeCall(stage, paramsJson, operationTarget, recordStage, failureCode, block)
    }

internal fun <T> LocalChatWorkerService.guardedNativeCall(
        stage: String,
        paramsJson: String? = null,
        operationTarget: LocalChatWorkerOperationTarget? = null,
        recordStage: Boolean = true,
        failureCode: (T) -> String? = { null },
        block: () -> T
    ): T {
        val liteRtOperation = if (
            (operationTarget ?: activeOperationTarget)?.runtime == LocalChatRuntime.LITERT_LM &&
            stage !in setOf("stats", "init", "reset_prefill")
        ) liteRtCancellationState.begin(stage) else null
        var liteRtTerminal = true
        if (recordStage) recordStageStarted(stage, paramsJson)
        val token = operationSequence.incrementAndGet()
        val defaultPolicy = watchdogOperationPolicy(stage, paramsJson, operationTarget)
        val operationPolicy = nativeOperationTimeoutLimitMsForCall?.let { limit ->
            defaultPolicy.copy(
                timeoutMs = minOf(defaultPolicy.timeoutMs, limit),
                timeoutFailureCode = "offline_translation_${stage}_timeout",
                operationLabel = "Offline translation"
            )
        } ?: defaultPolicy
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
                liteRtTerminal = when {
                    stage.contains("prefill") -> failureCode(result) != null
                    stage == "decode" -> result == null
                    else -> false
                }
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
            liteRtOperation?.let { liteRtCancellationState.finish(it, liteRtTerminal,
                runnerStillActive = activeRunner?.isGenerationRunning() == true) }
            watchdogs.remove(token)?.let(watchdogHandler::removeCallbacks)
            cancellationWatchdogs.remove(token)?.let(watchdogHandler::removeCallbacks)
            if (activeNativeStage == stage && activeNativeOperationToken == token) {
                activeNativeStage = previousStage
                activeNativeOperationToken = previousToken
            }
        }
    }

    /**
     * LiteRT's asynchronous executor outlives a Binder decode call. Issue native cancel on its
     * own thread and retain recovery across chunk boundaries. Neither a blocked cancelProcess
     * nor a never-delivered terminal callback may hold the user's Stop action indefinitely.
     */
internal fun LocalChatWorkerService.requestLiteRtStop(): Boolean? {
        if ((activeOperationTarget?.runtime ?: activeRuntime) != LocalChatRuntime.LITERT_LM) return null
        val request = liteRtCancellationState.request(activeRunner?.isGenerationRunning() == true) ?: return false
        if (!request.dispatch) return true
        val ticket = request.stop
        val runner = activeRunner
        val stage = activeNativeStage ?: "decode"
        val backend = activeOperationTarget?.backend ?: "cpu"
        val watchdog = Runnable {
            if (runner?.isGenerationRunning() == false) {
                liteRtCancellationState.generationCompleted(ticket)
            }
            if (liteRtCancellationWatchdogs.remove(ticket.id) == null) return@Runnable
            liteRtCancellationState.recoverIfNeeded(ticket) {
                val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(
                    stage = stage,
                    timeoutMs = NATIVE_CANCEL_GRACE_TIMEOUT_MS,
                    code = "litert_${backend}_cancel_timeout",
                    operationLabel = "LiteRT-LM ${backend.uppercase()}"
                )
                recordStageFailure(stage, diagnostic.code)
                Log.w(WORKER_LOG_TAG, "${diagnostic.code}: ${diagnostic.message}")
                Process.killProcess(Process.myPid())
            }
        }
        liteRtCancellationWatchdogs[ticket.id] = watchdog
        watchdogHandler.postDelayed(watchdog, NATIVE_CANCEL_GRACE_TIMEOUT_MS)
        thread(isDaemon = true, name = "mca-litert-cancel-${ticket.id}") {
            try {
                runCatching { runner?.requestStop() }
            } finally {
                liteRtCancellationState.returned(ticket)
                if (runner?.isGenerationRunning() == false) {
                    liteRtCancellationState.generationCompleted(ticket)
                }
                if (!liteRtCancellationState.needsRecovery(ticket)) {
                    liteRtCancellationWatchdogs.remove(ticket.id)?.let(watchdogHandler::removeCallbacks)
                }
            }
        }
        return true
    }

internal fun LocalChatWorkerService.watchdogOperationPolicy(
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

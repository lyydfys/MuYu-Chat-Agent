package com.muyuchat.mca

import android.os.Process
import android.util.Log
import com.muyuchat.core.engine.LocalChatRuntime
import kotlin.concurrent.thread

    /**
     * Some native calls hold their own non-interruptible mutex. Do not enter
     * that mutex from a second Binder thread after a user has pressed stop;
     * reclaim the isolated process after a short grace period instead.
     */
internal fun LocalChatWorkerService.scheduleForcedRecoveryAfterStop(): Boolean {
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

internal fun LocalChatWorkerService.scheduleMnnProducerRecoveryAfterStop() {
        if (activeRuntime != LocalChatRuntime.MNN_CPU) return
        val runner = activeRunner ?: return
        val epoch = runtimeStateEpoch
        val generation = generationSequence.get()
        watchdogHandler.postDelayed({
            if (activeRunner === runner && runtimeStateEpoch == epoch &&
                generationSequence.get() == generation &&
                runner.isGenerationRunning() == true
            ) {
                recordStageFailure("decode", "mnn_visual_cancel_timeout")
                Log.w(WORKER_LOG_TAG, "mnn_visual_cancel_timeout: native producer did not release its inputs")
                Process.killProcess(Process.myPid())
            }
        }, NATIVE_CANCEL_GRACE_TIMEOUT_MS)
    }

internal fun LocalChatWorkerService.dispatchRunnerStop() {
        val runner = activeRunner ?: return
        // Some SDK-backed runners serialize cancellation with their generation
        // mutex. Never spend a Binder thread waiting for that mutex; the
        // isolated worker watchdog remains responsible for bounded recovery.
        thread(isDaemon = true, name = "mca-native-stop-${operationSequence.get()}") {
            runCatching { runner.requestStop() }
        }
    }

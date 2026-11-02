package com.muyuchat.mca

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.muyuchat.core.engine.LocalChatRuntime
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException

    /**
     * Some native calls hold their own non-interruptible mutex. Do not enter
     * that mutex from a second Binder thread after a user has pressed stop;
     * reclaim the isolated process after a short grace period instead.
     */
internal fun LocalChatWorkerService.scheduleForcedRecoveryAfterStop(): Boolean {
        val stage = activeNativeStage ?: return false
        val token = activeNativeOperationToken
        val policy = watchdogOperationPolicy(stage, null)
        if (!shouldArmForcedStopRecovery(
                stage = stage,
                operationToken = token,
                gateLocked = nativeOperationGate.isLocked,
                forceProcessRecoveryOnCancel = policy.forceProcessRecoveryOnCancel
            )
        ) {
            return false
        }
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
            Log.w(
                WORKER_LOG_TAG,
                "stop grace expired code=$cancellationCode stage=$stage op=$token " +
                    "graceMs=$NATIVE_CANCEL_GRACE_TIMEOUT_MS; reclaiming the disposable worker"
            )
            Process.killProcess(Process.myPid())
        }
        if (cancellationWatchdogs.putIfAbsent(token, watchdog) == null) {
            watchdogHandler.postDelayed(watchdog, NATIVE_CANCEL_GRACE_TIMEOUT_MS)
        }
        return true
    }

/**
 * Every explicit user stop of a native prompt-prefill or token-decode must end within the
 * cancellation grace window, whatever the runtime's normal `forceProcessRecoveryOnCancel` policy
 * says. A stop that wedges behind a closed native/SDK mutex can otherwise leave the worker busy
 * until the natural operation timeout (30 minutes). Process recovery is safe here because the
 * worker is disposable and isolated from the UI process.
 */
internal fun shouldArmForcedStopRecovery(
        stage: String?,
        operationToken: Long,
        gateLocked: Boolean,
        forceProcessRecoveryOnCancel: Boolean
    ): Boolean {
        if (stage == null || operationToken == 0L || !gateLocked) return false
        return stage == "prefill" || stage == "decode" || forceProcessRecoveryOnCancel
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
        val stage = activeNativeStage
        val token = activeNativeOperationToken
        // Some SDK-backed runners serialize cancellation with their generation
        // mutex. Never spend a Binder thread waiting for that mutex; the
        // isolated worker watchdog remains responsible for bounded recovery.
        thread(isDaemon = true, name = "mca-native-stop-${operationSequence.get()}") {
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                runner.requestStop()
                Log.i(
                    WORKER_LOG_TAG,
                    "native stop call returned stage=${stage ?: "-"} op=$token " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}"
                )
            } catch (cancellation: CancellationException) {
                // Never report a cancelled dispatch as an accepted native stop.
                Log.w(
                    WORKER_LOG_TAG,
                    "native stop call cancelled stage=${stage ?: "-"} op=$token " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}"
                )
                throw cancellation
            } catch (error: Throwable) {
                Log.w(
                    WORKER_LOG_TAG,
                    "native stop call failed stage=${stage ?: "-"} op=$token " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "error=${error::class.java.simpleName}"
                )
            }
        }
    }

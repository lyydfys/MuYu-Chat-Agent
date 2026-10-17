package com.muyuchat.mca

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import com.muyuchat.core.engine.LocalChatRuntime

internal const val IDLE_RUNTIME_STATS_JSON = "{\"loaded\":false,\"runnerReady\":true}"

internal fun LocalChatWorkerService.deferredRuntimeStatsJson(stage: String): String {
        return buildDeferredRuntimeStatsJson(lastStableRuntimeStatsJson, stage)
    }

internal fun LocalChatWorkerService.invalidateRuntimeStatsLocked(statsJson: String = IDLE_RUNTIME_STATS_JSON) {
        runtimeStateEpoch += 1L
        lastStableRuntimeStatsJson = withRuntimeStatsLoadGeneration(statsJson, runtimeStateEpoch)
    }

internal fun LocalChatWorkerService.prepareLoadDiagnostic(runtime: LocalChatRuntime, modelPath: String, paramsJson: String) {
        diagnosticRuntime = runtime.name
        diagnosticModelFingerprint = LocalChatWorkerStageJournal.modelFingerprint(modelPath)
        diagnosticParameterSummary = LocalChatWorkerStageJournal.parameterSummary(paramsJson)
    }

internal fun LocalChatWorkerService.clearActiveDiagnostic() {
        diagnosticRuntime = null
        diagnosticModelFingerprint = null
        diagnosticParameterSummary = emptyMap()
    }

internal fun LocalChatWorkerService.readBeginRequest(requestPayload: ParcelFileDescriptor):
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

internal fun LocalChatWorkerService.recordContextReady() {
        recordStageCompleted("context", null)
    }

internal fun LocalChatWorkerService.recordStageStarted(stage: String, paramsJson: String?) {
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

internal fun LocalChatWorkerService.recordStageCompleted(stage: String, paramsJson: String?) {
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

internal fun LocalChatWorkerService.recordStageFailure(stage: String, code: String) {
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

internal fun LocalChatWorkerService.processPssKb(): Long = runCatching {
        Debug.getPss().coerceAtLeast(0L)
    }.getOrDefault(0L)

package com.muyuchat.mca

import android.os.ParcelFileDescriptor
import org.json.JSONObject

/** Unavailable-stats payload and request-payload marshalling (T2B22-T3 split). */

internal fun RemoteLocalChatRunner.workerUnavailableStatsLocked(): String {
        val diagnostic = (failure as? RemoteLocalChatRunnerException)?.workerStageDiagnostic
            ?: runCatching { stageJournal.read() }.getOrNull()
        return JSONObject()
            .put("backend", runtime.backendId)
            .put("loaded", false)
            .put("runnerReady", !closed && !workerSessionLost)
            .put(WORKER_SESSION_LOST_FIELD, workerSessionLost)
            .apply {
                failure?.message?.takeIf { it.isNotBlank() }?.let { put("lastError", it) }
                diagnostic?.let {
                    put(WORKER_STAGE_JOURNAL_FIELD, it.toJson())
                    put("workerLastStage", it.stage)
                    put("workerLastPssKb", it.pssKb)
                }
            }
            .toString()
    }

internal inline fun <T> RemoteLocalChatRunner.withRequestPayload(
        request: LocalChatWorkerRequestTransport.BeginRequest,
        block: (ParcelFileDescriptor) -> T
    ): T {
        val file = LocalChatWorkerRequestTransport.write(appContext, request)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // The open descriptor remains valid after unlinking the cache
                // entry, so a process death cannot leave prompt text on disk.
                runCatching { file.delete() }
                return block(descriptor)
            }
        } finally {
            runCatching { file.delete() }
        }
    }

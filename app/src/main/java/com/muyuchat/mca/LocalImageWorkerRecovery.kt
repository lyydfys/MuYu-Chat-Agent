package com.muyuchat.mca

import android.util.Log
import java.io.File

internal fun LocalImageWorkerService.recoverInterruptedExecutions() {
        runCatching {
            // onCreate belongs to a new LocalImageWorkerService instance. A journal persisted by
            // the previous instance cannot represent work owned by this instance even if Android
            // has already reused its numeric PID for another process.
            executionJournal.recoverInterrupted(cleanupRoots = listOf(cacheDir)) { false }
        }.onSuccess { report ->
            if (report.interrupted.isNotEmpty() || report.invalidJournalFiles.isNotEmpty()) {
                Log.w(
                    "MCA-LocalImage",
                    "Recovered ${report.interrupted.size} interrupted image requests; " +
                        "invalid journals=${report.invalidJournalFiles.size}."
                )
            }
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("recover_journals", error)
        }
    }

internal fun LocalImageWorkerService.cleanupStaleResults() {
        val staleBefore = System.currentTimeMillis() - LocalImageWorkerService.RESULT_MAX_AGE_MS
        resultDirectory().listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part") || file.lastModified() < staleBefore)) {
                runCatching { file.delete() }
            }
        }
    }

internal fun LocalImageWorkerService.cleanupPartialResults() {
        resultDirectory().listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.forEach { runCatching { it.delete() } }
    }

internal fun LocalImageWorkerService.resultDirectory(): File = File(cacheDir, LocalImageWorkerService.RESULT_DIRECTORY)

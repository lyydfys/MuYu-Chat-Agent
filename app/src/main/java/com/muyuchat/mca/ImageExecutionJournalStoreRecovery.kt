package com.muyuchat.mca

import java.io.File

/** T2B22-T4: journal store recovery and transient cleanup; moved verbatim from ImageExecutionJournal.kt. */

internal fun ImageExecutionJournalStore.recoverInterrupted(
        cleanupRoots: List<File>,
        preservePublishingOutputs: Boolean = true,
        validateEntryForCleanup: (ImageExecutionJournalEntry) -> Boolean = { true },
        isProcessAlive: (Int) -> Boolean
    ): ImageExecutionRecoveryReport {
        directory.mkdirs()
        val interrupted = mutableListOf<ImageExecutionJournalEntry>()
        val stillRunning = mutableListOf<ImageExecutionJournalEntry>()
        val invalid = mutableListOf<String>()
        var cleanup = ImageExecutionCleanupReport()
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(ImageExecutionJournalStore.JOURNAL_PREFIX) && file.name.endsWith(ImageExecutionJournalStore.JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .forEach { file ->
                val entry = runCatching { readFile(file) }.getOrElse {
                    invalid += file.name
                    return@forEach
                }
                if (journalFile(entry.requestId).canonicalFile != file.canonicalFile ||
                    !validateEntryForCleanup(entry)
                ) {
                    invalid += file.name
                    return@forEach
                }
                if (entry.phase.terminal) return@forEach
                if (entry.workerPid > 0 && isProcessAlive(entry.workerPid)) {
                    stillRunning += entry
                    return@forEach
                }
                cleanup += cleanupTransientFiles(
                    entry = entry,
                    cleanupRoots = cleanupRoots,
                    preservePublishedOutputs = preservePublishingOutputs &&
                        entry.phase == ImageExecutionPhase.PUBLISHING
                )
                val recovered = update(
                    entry.copy(
                        phase = ImageExecutionPhase.INTERRUPTED,
                        updatedAtMs = nextTimestamp(entry),
                        errorCode = "WORKER_INTERRUPTED",
                        errorMessage = "The image worker ended before reaching a terminal state."
                    )
                )
                interrupted += recovered
            }
        return ImageExecutionRecoveryReport(
            interrupted = interrupted,
            stillRunning = stillRunning,
            invalidJournalFiles = invalid,
            cleanup = cleanup
        )
    }

internal fun ImageExecutionJournalStore.cleanupTransientFiles(
        entry: ImageExecutionJournalEntry,
        cleanupRoots: List<File>,
        preservePublishedOutputs: Boolean = false
    ): ImageExecutionCleanupReport = sequenceOf(entry.latentTempPath)
        .plus(
            sequenceOf(entry.outputTempPath)
                .plus(entry.outputTempPaths.asSequence())
                .filter { path -> !preservePublishedOutputs || path.endsWith(".part") }
        )
        .plus(entry.inputTempPaths.asSequence())
        .filter(String::isNotBlank)
        .distinct()
        .fold(ImageExecutionCleanupReport()) { report, path ->
            report + cleanupPath(path, cleanupRoots)
        }

private fun cleanupPath(path: String, cleanupRoots: List<File>): ImageExecutionCleanupReport {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return ImageExecutionCleanupReport(failedPaths = listOf(path))
        val allowed = cleanupRoots.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }.any { root -> candidate.path.startsWith(root.path + File.separator) }
        if (!allowed) return ImageExecutionCleanupReport(skippedPaths = listOf(candidate.path))
        if (!candidate.exists()) return ImageExecutionCleanupReport()
        return if (runCatching { candidate.delete() }.getOrDefault(false)) {
            ImageExecutionCleanupReport(deletedPaths = listOf(candidate.path))
        } else {
            ImageExecutionCleanupReport(failedPaths = listOf(candidate.path))
        }
    }

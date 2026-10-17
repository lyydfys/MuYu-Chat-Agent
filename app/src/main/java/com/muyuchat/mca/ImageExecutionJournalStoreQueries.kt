package com.muyuchat.mca

import java.io.File

/** T2B22-T4: journal store listing/read/prune queries; moved verbatim from ImageExecutionJournal.kt. */

internal fun ImageExecutionJournalStore.listReadableEntries(): List<ImageExecutionJournalEntry> {
        directory.mkdirs()
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith(ImageExecutionJournalStore.JOURNAL_PREFIX) && file.name.endsWith(ImageExecutionJournalStore.JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .mapNotNull { file ->
                runCatching {
                    readFile(file).takeIf { entry ->
                        journalFile(entry.requestId).canonicalFile == file.canonicalFile
                    }
                }.getOrNull()
            }
    }

internal fun ImageExecutionJournalStore.listJournalDigests(): Set<String> {
        directory.mkdirs()
        return directory.listFiles().orEmpty().mapNotNull { file ->
            ImageExecutionJournalStore.JOURNAL_FILE_PATTERN.matchEntire(file.name)?.groupValues?.getOrNull(1)
        }.toSet()
    }

internal fun ImageExecutionJournalStore.read(requestId: String): ImageExecutionJournalEntry? {
        val file = journalFile(requestId)
        if (!file.isFile) return null
        val entry = readFile(file)
        require(entry.requestId == requestId &&
            journalFile(entry.requestId).canonicalFile == file.canonicalFile
        ) {
            "Image journal file identity does not match its requestId."
        }
        return entry
    }

internal fun ImageExecutionJournalStore.deleteTerminal(
        requestId: String,
        expectedLeaseToken: String? = null
    ): Boolean = withStoreLock {
        val current = read(requestId) ?: return@withStoreLock false
        requireExpectedLeaseToken(current, expectedLeaseToken)
        require(current.phase.terminal) { "Non-terminal image journal cannot be deleted." }
        journalFile(requestId).delete()
    }

    /**
     * Removes terminal request metadata while retaining active or unreadable journals.
     *
     * Callers that own temporary output artifacts may provide their cleanup roots. In that mode
     * every recorded transient is removed before its terminal journal is deleted, which also
     * closes the crash window left by older writers that published a terminal journal before the
     * surrounding provider had consumed its output.
     */
internal fun ImageExecutionJournalStore.pruneTerminalJournals(
        cleanupRoots: List<File> = emptyList(),
        validateEntryForCleanup: (ImageExecutionJournalEntry) -> Boolean = { true }
    ): ImageExecutionCleanupReport {
        directory.mkdirs()
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith(ImageExecutionJournalStore.JOURNAL_PREFIX) && file.name.endsWith(ImageExecutionJournalStore.JOURNAL_SUFFIX)
        }
            .orEmpty()
            .sortedBy(File::getName)
            .fold(ImageExecutionCleanupReport()) { report, file ->
                val entry = runCatching { readFile(file) }.getOrNull()
                if (entry?.phase?.terminal != true ||
                    journalFile(entry.requestId).canonicalFile != file.canonicalFile ||
                    !validateEntryForCleanup(entry)
                ) {
                    report
                } else {
                    val artifactCleanup = if (cleanupRoots.isEmpty()) {
                        ImageExecutionCleanupReport()
                    } else {
                        cleanupTransientFiles(
                            entry = entry,
                            cleanupRoots = cleanupRoots,
                            preservePublishedOutputs = false
                        )
                    }
                    val canDeleteJournal = artifactCleanup.skippedPaths.isEmpty() &&
                        artifactCleanup.failedPaths.isEmpty()
                    val journalCleanup = when {
                        !canDeleteJournal -> ImageExecutionCleanupReport()
                        runCatching { file.delete() }.getOrDefault(false) ->
                            ImageExecutionCleanupReport(deletedPaths = listOf(file.canonicalPath))
                        else -> ImageExecutionCleanupReport(failedPaths = listOf(file.canonicalPath))
                    }
                    report + artifactCleanup + journalCleanup
                }
            }
    }

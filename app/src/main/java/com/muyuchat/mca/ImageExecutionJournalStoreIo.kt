package com.muyuchat.mca

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.withLock
import org.json.JSONObject

/** T2B22-T4: journal store file IO and store locking; moved verbatim from ImageExecutionJournal.kt. */

internal fun ImageExecutionJournalStore.nextTimestamp(current: ImageExecutionJournalEntry): Long =
        clock().coerceAtLeast(current.updatedAtMs + 1L)

internal fun ImageExecutionJournalStore.readFile(file: File): ImageExecutionJournalEntry {
        require(file.length() in 1L..ImageExecutionJournalStore.MAX_JOURNAL_BYTES) {
            "Image journal size is outside the bounded metadata contract."
        }
        return ImageExecutionJournalEntry.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }

internal fun ImageExecutionJournalStore.writeAtomic(file: File, entry: ImageExecutionJournalEntry) {
        directory.mkdirs()
        val temp = File(directory, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(entry.toJson().toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            durableMoveWithinParent(
                source = temp,
                target = file,
                move = { source, target ->
                    try {
                        Files.move(
                            source.toPath(),
                            target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            source.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                },
                parentDirectorySyncer = parentDirectorySyncer
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

internal inline fun <T> ImageExecutionJournalStore.withStoreLock(block: () -> T): T =
        inProcessStoreLock.withLock {
            directory.mkdirs()
            val lockFile = File(directory, ImageExecutionJournalStore.STORE_LOCK_FILE)
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use { block() }
            }
        }

internal fun ImageExecutionJournalStore.journalFile(requestId: String): File {
        require(requestId.isNotBlank()) { "Image journal requestId must not be blank." }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(requestId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "${ImageExecutionJournalStore.JOURNAL_PREFIX}$digest${ImageExecutionJournalStore.JOURNAL_SUFFIX}")
    }

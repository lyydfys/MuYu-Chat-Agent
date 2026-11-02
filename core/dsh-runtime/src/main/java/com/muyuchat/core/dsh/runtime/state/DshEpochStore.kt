package com.muyuchat.core.dsh.runtime.state

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable high-water mark for the host epoch.
 *
 * The owner is recreated together with the `:dsh` Service, so an in-memory counter restarts at
 * zero and a new generation can reuse epoch 1 (device evidence 2026-10-25). Persisting the last
 * allocated epoch lets the next service instance allocate a strictly larger value, so an old
 * generation's late callback can never match the published epoch.
 *
 * Only one non-negative number is stored. Malformed or unreadable state is treated as "no
 * history" (0) instead of failing the start: epoch monotonicity is defense in depth, not a
 * precondition for serving. Writes are atomic (temp file + rename) and best-effort; a failed write
 * never blocks a host start.
 */
class DshEpochStore(private val file: File) {

    fun lastEpoch(): Long = runCatching {
        if (!file.isFile) return@runCatching 0L
        val text = file.readText().trim()
        val value = text.toLongOrNull() ?: return@runCatching 0L
        value.coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun record(epoch: Long) {
        if (epoch < 0L) return
        runCatching {
            val parent = file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val temp = File(parent, file.name + ".tmp")
            temp.writeText(epoch.toString())
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.onFailure {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

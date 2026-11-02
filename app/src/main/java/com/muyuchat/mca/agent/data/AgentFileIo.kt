package com.muyuchat.mca.agent.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Streaming file primitives shared by the workspace and artifact stores. */
internal object AgentFileIo {
    const val BUFFER_BYTES: Int = 64 * 1024
    const val BINARY_SNIFF_BYTES: Int = 8 * 1024

    data class CopyOutcome(
        val sha256: String,
        val sizeBytes: Long,
        val binaryDetected: Boolean
    )

    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Streams [input] into [target], hashing exactly the bytes written and sniffing for NUL bytes in
     * the first [BINARY_SNIFF_BYTES]. The target is flushed and fsynced before returning; callers
     * still decide whether to rename or delete it.
     */
    fun copyAndHash(input: InputStream, target: File, maxBytes: Long): CopyOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var binary = false
        var sniffed = 0
        FileOutputStream(target, false).use { output ->
            input.use { source ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxBytes) {
                        throw AgentStorageException(
                            AgentDataErrorCodes.STORAGE_NO_SPACE,
                            "input exceeds the bounded import size of $maxBytes bytes"
                        )
                    }
                    digest.update(buffer, 0, read)
                    if (!binary && sniffed < BINARY_SNIFF_BYTES) {
                        val sniffLength = minOf(read, BINARY_SNIFF_BYTES - sniffed)
                        for (index in 0 until sniffLength) {
                            if (buffer[index] == 0.toByte()) {
                                binary = true
                                break
                            }
                        }
                        sniffed += sniffLength
                    }
                    output.write(buffer, 0, read)
                }
            }
            output.flush()
            runCatching { output.fd.sync() }
        }
        return CopyOutcome(digest.digest().toHex(), total, binary)
    }

    fun writeBytes(target: File, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target, false).use { output ->
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
            digest.update(bytes)
        }
        return digest.digest().toHex()
    }

    /** Best-effort parent-directory sync; the boolean records whether the platform supported it. */
    fun fsyncDirectory(directory: File): Boolean = try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        true
    } catch (_: IOException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    /**
     * Same-filesystem rename to the immutable physical target. Returns true when the platform
     * performed an atomic rename; a plain same-volume move is the documented fallback.
     */
    fun atomicMove(source: File, target: File): Boolean {
        require(!target.exists()) { "immutable target already exists: ${target.name}" }
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
            false
        }
    }

    fun readBounded(file: File, maxBytes: Long): ByteArray {
        if (file.length() > maxBytes) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "file ${file.name} exceeds the bounded read size"
            )
        }
        return file.readBytes()
    }

    fun deleteQuietly(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }

    fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

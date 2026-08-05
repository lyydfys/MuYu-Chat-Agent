package com.muyuchat.mca

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * File-backed payload used by the isolated text worker.
 *
 * Binder carries only the descriptor.  Keeping the payload length-prefixed
 * avoids JSON escaping/concatenation ambiguities and lets the worker reject a
 * malformed or unbounded request before handing it to native code.
 */
internal object LocalChatWorkerRequestTransport {
    private const val MAGIC = 0x4D434157 // "MCAW"
    private const val VERSION = 1
    // The admitted prompt itself may occupy 8 MiB before JSON escaping.
    private const val MAX_MESSAGES_BYTES = 16 * 1024 * 1024
    private const val MAX_PARAMS_BYTES = 1 * 1024 * 1024
    private const val MAX_FIXED_SYSTEM_PROMPT_BYTES = 128 * 1024
    private const val MAX_PATH_BYTES = 64 * 1024
    private const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
    private const val REQUEST_DIRECTORY = "local_chat_requests"

    data class BeginRequest(
        val messagesJson: String,
        val paramsJson: String,
        val restoreStatePath: String?,
        val writeStatePath: String?,
        val fixedSystemPrompt: String?
    ) {
        val hasPrefixCache: Boolean
            get() = restoreStatePath != null || writeStatePath != null || fixedSystemPrompt != null
    }

    fun write(context: Context, request: BeginRequest): File {
        val directory = File(context.cacheDir, REQUEST_DIRECTORY)
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create the isolated text request directory."
        }
        cleanupStaleRequests(directory)
        val file = File.createTempFile("begin-", ".bin", directory)
        try {
            FileOutputStream(file).use { output ->
                DataOutputStream(output).use { data ->
                    write(data, request)
                    data.flush()
                    output.fd.sync()
                }
            }
            require(file.length() <= MAX_PAYLOAD_BYTES) {
                "The isolated text request payload is too large."
            }
            return file
        } catch (error: Throwable) {
            runCatching { file.delete() }
            throw error
        }
    }

    /** Consumes and closes the descriptor supplied by Binder. */
    fun read(descriptor: ParcelFileDescriptor): BeginRequest =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            read(DataInputStream(input))
        }

    internal fun write(output: DataOutputStream, request: BeginRequest) {
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        writeText(output, request.messagesJson, MAX_MESSAGES_BYTES, "messagesJson")
        writeText(output, request.paramsJson, MAX_PARAMS_BYTES, "paramsJson")
        writeNullableText(output, request.restoreStatePath, MAX_PATH_BYTES, "restoreStatePath")
        writeNullableText(output, request.writeStatePath, MAX_PATH_BYTES, "writeStatePath")
        writeNullableText(
            output,
            request.fixedSystemPrompt,
            MAX_FIXED_SYSTEM_PROMPT_BYTES,
            "fixedSystemPrompt"
        )
    }

    internal fun read(input: DataInputStream): BeginRequest {
        if (input.readInt() != MAGIC) throw IOException("Invalid isolated text request payload.")
        if (input.readInt() != VERSION) throw IOException("Unsupported isolated text request version.")
        val messagesJson = readText(input, MAX_MESSAGES_BYTES, "messagesJson")
        val paramsJson = readText(input, MAX_PARAMS_BYTES, "paramsJson")
        val restoreStatePath = readNullableText(input, MAX_PATH_BYTES, "restoreStatePath")
        val writeStatePath = readNullableText(input, MAX_PATH_BYTES, "writeStatePath")
        val fixedSystemPrompt = readNullableText(
            input,
            MAX_FIXED_SYSTEM_PROMPT_BYTES,
            "fixedSystemPrompt"
        )
        if (input.read() != -1) throw IOException("Trailing data in isolated text request payload.")
        return BeginRequest(
            messagesJson = messagesJson,
            paramsJson = paramsJson,
            restoreStatePath = restoreStatePath,
            writeStatePath = writeStatePath,
            fixedSystemPrompt = fixedSystemPrompt
        )
    }

    private fun writeText(
        output: DataOutputStream,
        value: String,
        maxBytes: Int,
        fieldName: String
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) {
            "$fieldName exceeds the isolated text request limit."
        }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun writeNullableText(
        output: DataOutputStream,
        value: String?,
        maxBytes: Int,
        fieldName: String
    ) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            writeText(output, value, maxBytes, fieldName)
        }
    }

    private fun readText(
        input: DataInputStream,
        maxBytes: Int,
        fieldName: String
    ): String {
        val length = readLength(input, maxBytes, fieldName)
        if (length < 0) throw IOException("$fieldName must not be null.")
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (error: EOFException) {
            throw IOException("Truncated isolated text request field: $fieldName.", error)
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readNullableText(
        input: DataInputStream,
        maxBytes: Int,
        fieldName: String
    ): String? {
        val length = readLength(input, maxBytes, fieldName)
        if (length < 0) return null
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (error: EOFException) {
            throw IOException("Truncated isolated text request field: $fieldName.", error)
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readLength(input: DataInputStream, maxBytes: Int, fieldName: String): Int {
        val length = try {
            input.readInt()
        } catch (error: EOFException) {
            throw IOException("Truncated isolated text request header: $fieldName.", error)
        }
        if (length < -1 || length > maxBytes) {
            throw IOException("Invalid isolated text request length for $fieldName.")
        }
        return length
    }

    private fun cleanupStaleRequests(directory: File) {
        val staleBefore = System.currentTimeMillis() - STALE_REQUEST_MAX_AGE_MS
        directory.listFiles()?.forEach { candidate ->
            val modifiedAt = candidate.lastModified()
            if (candidate.isFile &&
                candidate.name.startsWith("begin-") &&
                candidate.name.endsWith(".bin") &&
                modifiedAt > 0L && modifiedAt < staleBefore
            ) {
                runCatching { candidate.delete() }
            }
        }
    }

    private const val STALE_REQUEST_MAX_AGE_MS = 24L * 60L * 60L * 1000L
}

package com.muyuchat.core.download

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** A local completion receipt supports install recovery; it is not publisher integrity evidence. */
class CompletedDownloadReceipt(private val file: File, private val identity: String) {
    private val receipt = File(file.parentFile, ".${file.name}.download-receipt")

    suspend fun matches(remote: RemoteModelFile): Boolean {
        if (!file.isFile || file.length() <= 0L || (remote.sizeBytes != null && file.length() != remote.sizeBytes)) return false
        val sourceHash = remote.sha256?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        val hash = sourceHash ?: runCatching {
            val json = JSONObject(receipt.readText())
            require(json.getString("identity") == identity && json.getLong("size") == file.length())
            json.getString("sha256")
        }.getOrNull() ?: return false
        return hash.equals(digest(file), true)
    }

    suspend fun record() {
        require(file.isFile && file.length() > 0L) { "Cannot record an empty completed model download." }
        val json = JSONObject().put("identity", identity).put("size", file.length()).put("sha256", digest(file))
        val staging = File(receipt.parentFile, "${receipt.name}.writing")
        staging.outputStream().use { output -> output.write(json.toString().toByteArray()); output.fd.sync() }
        Files.move(staging.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.muyuchat.core.dsh.runtime.config

import com.muyuchat.core.dsh.contract.console.DshShellSandboxStatus
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Durable, prompt-free shell-confinement fact for the console.
 *
 * Ownership: [DshGatewayConfigSeeder] is the only writer, once per successful seed. The app-side
 * console is a read-only consumer. A malformed or absent file reads as `null` (unknown), never as
 * "enforced"; the UI must then say the boundary has not been probed.
 */
object DshShellSandboxStatusStore {
    const val FILE_NAME = "shell-sandbox.status.json"

    fun write(dataRoot: File, status: DshShellSandboxStatus): Boolean {
        if (!dataRoot.isDirectory && !dataRoot.mkdirs() && !dataRoot.isDirectory) return false
        val target = File(dataRoot, FILE_NAME)
        val temp = File(dataRoot, "$FILE_NAME.mca-tmp")
        val json = JSONObject()
            .put("enforced", status.enforced)
            .put("landlockAbi", status.landlockAbi ?: -1)
            .put("partial", status.partial)
            .put("advisoryReason", status.advisoryReason ?: JSONObject.NULL)
            .put("updatedAtMillis", status.updatedAtMillis)
            .toString()
        return try {
            FileOutputStream(temp).use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: Throwable) {
            runCatching { temp.delete() }
            false
        }
    }

    /** Null when not probed, unreadable or malformed; never a fabricated enforced status. */
    fun read(dataRoot: File): DshShellSandboxStatus? = runCatching {
        val file = File(dataRoot, FILE_NAME)
        if (!file.isFile) return null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val abi = json.optInt("landlockAbi", -1).takeIf { it > 0 }
        val reason = json.optString("advisoryReason").takeIf { it.isNotBlank() }
        DshShellSandboxStatus(
            enforced = json.optBoolean("enforced", false),
            landlockAbi = abi,
            partial = json.optBoolean("partial", false),
            advisoryReason = reason,
            updatedAtMillis = json.optLong("updatedAtMillis", 0L).coerceAtLeast(0L)
        )
    }.getOrNull()
}

package com.muyuchat.mca.video.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

internal enum class RemoteComfyHistoryStatus { SUCCESS, ERROR, UNKNOWN }

/**
 * Pure readers for ComfyUI `/history/{prompt_id}` and `/queue` documents.
 *
 * History is the only terminal source. Output entries are treated as query parameters for the
 * SAME server's /view endpoint: a server-supplied `url`, host or path never becomes a new download
 * target, and only an `output` entry whose filename is a plain `*.mp4` name is accepted.
 */
internal object RemoteComfyHistory {

    fun record(root: JSONObject, promptId: String): JSONObject? =
        root.optJSONObject(promptId) ?: root.optJSONObject("history")?.optJSONObject(promptId)

    fun status(record: JSONObject): RemoteComfyHistoryStatus {
        val status = record.optJSONObject("status")
        val statusStr = status?.opt("status_str") as? String
        if (statusStr == "error" || hasExecutionError(status)) return RemoteComfyHistoryStatus.ERROR
        if (statusStr == "success") return RemoteComfyHistoryStatus.SUCCESS
        if (status?.optBoolean("completed") == true) return RemoteComfyHistoryStatus.SUCCESS
        return if ((record.optJSONObject("outputs")?.length() ?: 0) > 0) {
            RemoteComfyHistoryStatus.SUCCESS
        } else {
            RemoteComfyHistoryStatus.UNKNOWN
        }
    }

    /**
     * Error detail is built from TYPE name and node id only. `exception_message`, the prompt and
     * request metadata are never copied into Room, logs or the user-visible failure detail.
     */
    fun errorSummary(record: JSONObject): String {
        val status = record.optJSONObject("status")
        val messages = status?.optJSONArray("messages")
        var node: String? = null
        var type: String? = null
        if (messages != null) {
            for (index in 0 until messages.length()) {
                val entry = messages.optJSONArray(index) ?: continue
                val name = entry.opt(0) as? String ?: continue
                if (name != "execution_error" && name != "execution_interrupted") continue
                val data = entry.optJSONObject(1)
                node = (data?.opt("node_id") as? String) ?: (data?.opt("node") as? String)
                type = (data?.opt("exception_type") as? String) ?: name
                break
            }
        }
        if (type == null && status?.opt("status_str") == "error") type = "error"
        return RemoteComfySafeText.errorSummary(node, type)
    }

    private fun hasExecutionError(status: JSONObject?): Boolean {
        val messages = status?.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            val entry = messages.optJSONArray(index) ?: continue
            val name = entry.opt(0) as? String ?: continue
            if (name == "execution_error" || name == "execution_interrupted") return true
        }
        return false
    }
}

internal data class RemoteComfyOutputRef(
    val filename: String,
    val subfolder: String,
    val nodeId: String,
)

internal object RemoteComfyOutputSelector {

    private val ARRAY_PRIORITY = listOf("videos", "gifs", "images")

    fun select(record: JSONObject): RemoteComfyOutputRef? {
        val outputs = record.optJSONObject("outputs") ?: return null
        val nodeIds = outputs.keys().asSequence().sorted().toList()
        for (arrayName in ARRAY_PRIORITY) {
            for (nodeId in nodeIds) {
                val node = outputs.optJSONObject(nodeId) ?: continue
                val items = node.optJSONArray(arrayName) ?: continue
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if ((item.opt("type") as? String) != "output") continue
                    val filename = item.opt("filename") as? String ?: continue
                    if (!isSafeMp4Filename(filename)) continue
                    val subfolder = subfolderOf(item) ?: continue
                    if (!isSafeSubfolder(subfolder)) continue
                    return RemoteComfyOutputRef(filename, subfolder, nodeId)
                }
            }
        }
        return null
    }

    fun isSafeMp4Filename(filename: String): Boolean {
        if (filename.isBlank() || filename.length > 255) return false
        if (filename == "." || filename == "..") return false
        if (!filename.endsWith(".mp4", ignoreCase = true)) return false
        if (filename.contains('/') || filename.contains('\\') || filename.contains("..")) return false
        return filename.none { it.isISOControl() }
    }

    fun isSafeSubfolder(subfolder: String): Boolean {
        if (subfolder.length > 512) return false
        if (subfolder.startsWith("/") || subfolder.contains('\\')) return false
        if (subfolder.any { it.isISOControl() }) return false
        return subfolder.split('/').none { it == "." || it == ".." }
    }

    private fun subfolderOf(item: JSONObject): String? = when (val value = item.opt("subfolder")) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> null
    }
}

internal enum class RemoteComfyQueueState { RUNNING, PENDING, ABSENT, UNKNOWN }

internal object RemoteComfyQueue {

    fun state(root: JSONObject, promptId: String): RemoteComfyQueueState {
        val running = parseIds(root.optJSONArray("queue_running")) ?: return RemoteComfyQueueState.UNKNOWN
        val pending = parseIds(root.optJSONArray("queue_pending")) ?: return RemoteComfyQueueState.UNKNOWN
        return when {
            promptId in running -> RemoteComfyQueueState.RUNNING
            promptId in pending -> RemoteComfyQueueState.PENDING
            else -> RemoteComfyQueueState.ABSENT
        }
    }

    private fun parseIds(array: JSONArray?): Set<String>? {
        if (array == null) return null
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONArray(index)
            val raw = if (entry != null) {
                entry.opt(1)
            } else {
                array.optJSONObject(index)?.opt("prompt_id")
            }
            val id = when (raw) {
                is String -> raw
                is Number -> raw.toString()
                else -> return null
            }
            ids.add(id)
        }
        return ids
    }
}

internal object RemoteComfyMp4 {

    private const val PREFIX_BYTES = 12

    fun readPrefix(file: File, maxBytes: Int = PREFIX_BYTES): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        FileInputStream(file).use { input ->
            while (total < maxBytes) {
                val read = input.read(buffer, total, maxBytes - total)
                if (read <= 0) break
                total += read
            }
        }
        return buffer.copyOf(total)
    }

    /** An MP4 `ftyp` box tag must occur inside the first 12 bytes (normally at offset 4). */
    fun hasFtyp(prefix: ByteArray): Boolean {
        if (prefix.size < 8) return false
        val end = minOf(prefix.size, PREFIX_BYTES)
        for (index in 0..end - 4) {
            if (prefix[index] == 'f'.code.toByte() &&
                prefix[index + 1] == 't'.code.toByte() &&
                prefix[index + 2] == 'y'.code.toByte() &&
                prefix[index + 3] == 'p'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }
}

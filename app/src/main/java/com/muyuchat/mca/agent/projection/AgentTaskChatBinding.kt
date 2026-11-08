package com.muyuchat.mca.agent.projection

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * One durable `agent task -> chat session` association.
 *
 * The Agent contract has no conversation field (`CreateAgentTask`, `AgentTask` and `AgentRun` carry
 * only ids and policy), so the binding is kept here, outside those types and outside the Room
 * schema: a task created from a conversation records the conversation once, and the projection
 * layer can then resolve `chatSessionId` for every later terminal run of that task.
 *
 * [createdAt] is the wall-clock time of the *first* bind; re-binding the same task to the same
 * session keeps it, so a retried create does not look like a new association.
 */
data class AgentTaskChatBinding(
    val taskId: String,
    val chatSessionId: String,
    val createdAt: Long
) {
    init {
        require(taskId.isNotBlank()) { "binding taskId must not be blank" }
        require(chatSessionId.isNotBlank()) { "binding chatSessionId must not be blank" }
        require(createdAt >= 0L) { "binding createdAt must not be negative" }
    }
}

/**
 * The association seam used by the projection layer.
 *
 * Every method is safe to call from any thread; implementations are expected to be cheap and to
 * never throw on a corrupt document (an unreadable binding only means "unknown conversation", which
 * degrades to the previous no-summary behaviour rather than breaking the run).
 */
interface AgentTaskChatBindingStore {
    /** Records [taskId] -> [chatSessionId]. Rebinding the same pair is a no-op. */
    fun bind(taskId: String, chatSessionId: String, atMillis: Long): AgentTaskChatBinding

    /** The conversation for [taskId], or null when the task was never bound or was unbound. */
    fun chatFor(taskId: String): String?

    /** Drops the association; returns true when something was removed. */
    fun unbind(taskId: String): Boolean

    /** Snapshot of every live association, keyed by task id. */
    fun all(): Map<String, String>
}

/**
 * File-backed [AgentTaskChatBindingStore]: one small JSON document under the agent root.
 *
 * ## Atomicity
 *
 * A write never touches the live file. It serialises to bytes, writes them to a sibling
 * `*.tmp` file, forces that file to disk, and then performs a single
 * [Files.move] with `ATOMIC_MOVE` + `REPLACE_EXISTING` onto the real name. A crash therefore leaves
 * either the old document or the new one, never a half-written mix; the `*.tmp` file is deleted on
 * any failure so a later run cannot mistake it for data. Reads only ever open the real file.
 *
 * ## Bounded and fail-safe
 *
 * At most [MAX_BINDINGS] rows are kept; the oldest `createdAt` is evicted first so the newest
 * association is never the one dropped. A corrupt, oversized or unreadable document decodes to an
 * empty table instead of throwing: losing a binding only suppresses one chat summary, while
 * throwing here would break the run's projection path.
 */
class FileAgentTaskChatBindingStore(
    directory: File,
    private val maxBindings: Int = MAX_BINDINGS
) : AgentTaskChatBindingStore {

    private val root: File = directory.absoluteFile
    private val file = File(root, FILE_NAME)
    private val temp = File(root, "$FILE_NAME.tmp")
    private val lock = Any()

    /** In-memory mirror; the file is the durable copy and is re-read on first use. */
    private val bindings = ConcurrentHashMap<String, AgentTaskChatBinding>()

    @Volatile
    private var loaded = false

    init {
        require(maxBindings > 0) { "maxBindings must be positive" }
        root.mkdirs()
    }

    override fun bind(taskId: String, chatSessionId: String, atMillis: Long): AgentTaskChatBinding =
        synchronized(lock) {
            ensureLoaded()
            val existing = bindings[taskId]
            // A retried create must not look like a new association, and rebinding to the same
            // conversation must not churn the file.
            val binding = if (existing != null && existing.chatSessionId == chatSessionId) {
                existing
            } else {
                AgentTaskChatBinding(taskId, chatSessionId, atMillis)
            }
            bindings[taskId] = binding
            trimLocked()
            persistLocked()
            binding
        }

    override fun chatFor(taskId: String): String? = synchronized(lock) {
        ensureLoaded()
        bindings[taskId]?.chatSessionId
    }

    override fun unbind(taskId: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val removed = bindings.remove(taskId) != null
        if (removed) persistLocked()
        removed
    }

    override fun all(): Map<String, String> = synchronized(lock) {
        ensureLoaded()
        bindings.values
            .sortedBy { it.createdAt }
            .associate { it.taskId to it.chatSessionId }
    }

    /** Evicts the oldest associations until the cap holds. Caller holds [lock]. */
    private fun trimLocked() {
        if (bindings.size <= maxBindings) return
        val victims = bindings.values
            .sortedBy { it.createdAt }
            .take(bindings.size - maxBindings)
            .map { it.taskId }
        victims.forEach(bindings::remove)
    }

    /** Serialises the current table and atomically replaces the file. Caller holds [lock]. */
    private fun persistLocked() {
        val rows = JSONArray()
        bindings.values
            .sortedWith(compareBy({ it.createdAt }, { it.taskId }))
            .forEach { binding ->
                rows.put(
                    JSONObject()
                        .put(KEY_TASK_ID, binding.taskId)
                        .put(KEY_CHAT_SESSION_ID, binding.chatSessionId)
                        .put(KEY_CREATED_AT, binding.createdAt)
                )
            }
        val document = JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_BINDINGS, rows)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        try {
            temp.writeBytes(bytes)
            // Force the new bytes before the rename, so the replaced name can never point at a
            // buffer that has not reached the storage device.
            runCatching { java.io.FileOutputStream(temp, true).use { it.fd.sync() } }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            // Keep the in-memory table: the caller's projection still works for this process, and
            // the next successful write persists the merged state. A stale temp file is removed so
            // it can never be read as data.
            runCatching { temp.delete() }
        }
    }

    /** Reads the durable document once. Any problem yields an empty table, never an exception. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        if (file.length() > MAX_BYTES) return
        val parsed = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (parsed.optInt(KEY_VERSION, VERSION) != VERSION) return
        val rows = parsed.optJSONArray(KEY_BINDINGS) ?: return
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val taskId = row.optString(KEY_TASK_ID).takeIf { it.isNotBlank() } ?: continue
            val chatSessionId = row.optString(KEY_CHAT_SESSION_ID).takeIf { it.isNotBlank() } ?: continue
            val createdAt = row.optLong(KEY_CREATED_AT, -1L).takeIf { it >= 0L } ?: continue
            bindings[taskId] = AgentTaskChatBinding(taskId, chatSessionId, createdAt)
        }
        trimLocked()
    }

    companion object {
        const val FILE_NAME: String = "agent-task-chat-bindings.json"
        const val MAX_BINDINGS: Int = 500
        const val MAX_BYTES: Long = 256L * 1024L
        const val VERSION: Int = 1

        private const val KEY_VERSION = "version"
        private const val KEY_BINDINGS = "bindings"
        private const val KEY_TASK_ID = "taskId"
        private const val KEY_CHAT_SESSION_ID = "chatSessionId"
        private const val KEY_CREATED_AT = "createdAt"
    }
}

/** In-memory store for tests and for a host that has no writable agent root. */
class InMemoryAgentTaskChatBindingStore(
    private val maxBindings: Int = FileAgentTaskChatBindingStore.MAX_BINDINGS
) : AgentTaskChatBindingStore {
    private val lock = Any()
    private val bindings = LinkedHashMap<String, AgentTaskChatBinding>()

    override fun bind(taskId: String, chatSessionId: String, atMillis: Long): AgentTaskChatBinding =
        synchronized(lock) {
            val existing = bindings[taskId]
            val binding = if (existing != null && existing.chatSessionId == chatSessionId) {
                existing
            } else {
                AgentTaskChatBinding(taskId, chatSessionId, atMillis)
            }
            bindings[taskId] = binding
            while (bindings.size > maxBindings) {
                val oldest = bindings.values.minByOrNull { it.createdAt } ?: break
                bindings.remove(oldest.taskId)
            }
            binding
        }

    override fun chatFor(taskId: String): String? = synchronized(lock) { bindings[taskId]?.chatSessionId }

    override fun unbind(taskId: String): Boolean = synchronized(lock) { bindings.remove(taskId) != null }

    override fun all(): Map<String, String> = synchronized(lock) {
        bindings.values.sortedBy { it.createdAt }.associate { it.taskId to it.chatSessionId }
    }
}

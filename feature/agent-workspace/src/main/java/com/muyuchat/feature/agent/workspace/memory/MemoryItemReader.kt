package com.muyuchat.feature.agent.workspace.memory

/**
 * Full-body read used **only** by the management editor.
 *
 * The list stays excerpt-only (`AgentMemoryManagedItem`), so opening the editor needs one explicit
 * bounded per-item read. This is a separate capability rather than a widened list: a surface wired
 * with only `AgentMemoryAdminPort` shows a typed "not wired" error instead of pretending the body
 * is empty.
 */
data class MemoryItemDetail(
    val spaceId: String,
    val itemId: String,
    val title: String,
    val text: String,
    val revision: Long,
    val updatedAt: Long,
    val sourceKind: String,
    val sourceRef: String?,
    val revoked: Boolean
) {
    init {
        require(spaceId.isNotBlank()) { "detail spaceId must not be blank" }
        require(itemId.isNotBlank()) { "detail itemId must not be blank" }
        require(revision >= 1L) { "detail revision must be positive" }
    }
}

sealed interface MemoryItemReadResult {
    data class Loaded(val item: MemoryItemDetail) : MemoryItemReadResult

    data class Rejected(val code: String, val safeMessage: String) : MemoryItemReadResult {
        init {
            require(code.isNotBlank()) { "read rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "read rejection message must not be blank" }
        }
    }
}

/**
 * Narrow read seam implemented by the app adapter that also implements the admin port.
 *
 * It never writes and never searches: the caller must already know the `(spaceId, itemId)` pair from
 * an authorised list row. A foreign/missing id is reported as not found, so the editor cannot probe
 * another space.
 */
interface MemoryItemReader {
    suspend fun readItem(spaceId: String, itemId: String): MemoryItemReadResult

    companion object {
        /** Preview/test fallback; a wired surface must provide the real adapter. */
        val Unavailable: MemoryItemReader = object : MemoryItemReader {
            override suspend fun readItem(spaceId: String, itemId: String): MemoryItemReadResult =
                MemoryItemReadResult.Rejected(
                    code = "MEMORY_READ_UNAVAILABLE",
                    safeMessage = "记忆全文读取尚未接入，暂时无法编辑条目。"
                )
        }
    }
}

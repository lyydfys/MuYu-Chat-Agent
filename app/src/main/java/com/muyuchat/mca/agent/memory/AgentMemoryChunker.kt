package com.muyuchat.mca.agent.memory

import com.muyuchat.mca.WorldBookResolver

/**
 * Bounded deterministic chunker (plan §7.3).
 *
 * Behavior is a deliberate copy of the knowledge chunker's windowing (target 1 200 chars, 160-char
 * overlap, break on whitespace/CJK sentence punctuation) because that algorithm is already accepted
 * project behavior; the memory variant additionally *rejects* an oversized chunk count instead of
 * silently truncating the item. Chunk ids are `"<memoryId>:<position>"`, matching the knowledge
 * convention and never exposing a path.
 */
object AgentMemoryChunker {
    fun chunk(memoryId: String, spaceId: String, text: String, createdAt: Long): List<AgentMemoryChunkDraft> {
        val drafts = ArrayList<AgentMemoryChunkDraft>()
        var start = 0
        var position = 0
        while (start < text.length) {
            var end = (start + AgentMemoryLimits.CHUNK_TARGET_CHARS).coerceAtMost(text.length)
            if (end < text.length) {
                val preferredBreak = text.lastIndexOfAny(BREAK_CHARS, end - 1)
                if (preferredBreak > start + AgentMemoryLimits.CHUNK_TARGET_CHARS / 2) end = preferredBreak + 1
            }
            val content = text.substring(start, end).trim()
            if (content.isNotBlank()) {
                if (drafts.size == AgentMemoryLimits.MAX_CHUNKS_PER_ITEM) {
                    throw AgentMemoryTextException(
                        AgentMemoryError(
                            AgentMemoryErrorCodes.CHUNK_LIMIT,
                            "Memory text produces more than " +
                                "${AgentMemoryLimits.MAX_CHUNKS_PER_ITEM} chunks; split or shorten it."
                        )
                    )
                }
                drafts += AgentMemoryChunkDraft(
                    chunkId = "$memoryId:$position",
                    memoryId = memoryId,
                    spaceId = spaceId,
                    position = position,
                    text = content,
                    contentHash = AgentMemoryText.sha256(content),
                    estimatedTokens = WorldBookResolver.estimateTokens(content),
                    createdAt = createdAt
                )
                position++
            }
            if (end >= text.length) break
            start = (end - AgentMemoryLimits.CHUNK_OVERLAP_CHARS).coerceAtLeast(start + 1)
        }
        return drafts
    }

    private val BREAK_CHARS = charArrayOf('\n', ' ', '\u3002', '\uFF01', '\uFF1F', '.', '!', '?', '\t')
}

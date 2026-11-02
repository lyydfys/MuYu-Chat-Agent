package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Exports one memory space as the canonical bundle [AgentMemoryBundleImporter] reads (MEM-21).
 *
 * The bundle is the persisted record, not a display format: ids, content hashes, source kinds,
 * revisions and tombstones travel unchanged so a round trip preserves identity and relationships.
 * Chunks and derived counts are not exported — they are rebuilt from the text on import, which keeps
 * a single derivation rule. Paged reads are bounded; a space that exceeds the bundle limit is refused
 * instead of being truncated into a silently partial backup.
 */
class AgentMemoryBundleExporter(
    private val persistence: AgentMemoryPersistence
) {

    sealed interface Result {
        data class Exported(
            val spaceId: String,
            val itemCount: Int,
            val bundleJson: String
        ) : Result

        data class Rejected(val code: String, val spaceId: String, val safeMessage: String) : Result
    }

    suspend fun exportSpace(spaceId: String, includeRevoked: Boolean = true): Result {
        val space = persistence.findSpace(spaceId)
            ?: return Result.Rejected(
                "MEMORY_BUNDLE_SPACE_NOT_FOUND",
                spaceId,
                "The memory space does not exist."
            )
        val items = ArrayList<AgentMemoryItemRecord>()
        var offset = 0
        while (true) {
            val page = try {
                persistence.listItemPage(spaceId, null, includeRevoked, PAGE_SIZE, offset)
            } catch (_: Exception) {
                return Result.Rejected(
                    "MEMORY_BUNDLE_STORAGE_FAILED",
                    spaceId,
                    "The store refused the export page."
                )
            }
            if (page.isEmpty()) break
            items += page
            if (items.size > MAX_ITEMS) {
                return Result.Rejected(
                    "MEMORY_BUNDLE_TOO_LARGE",
                    spaceId,
                    "The space is larger than one bundle; export it through a smaller scope."
                )
            }
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
        return Result.Exported(spaceId, items.size, encode(space, items))
    }

    private fun encode(space: AgentMemorySpaceRecord, items: List<AgentMemoryItemRecord>): String =
        codec.write(
            AgentJsonValue.Obj(
                listOf(
                    "schemaVersion" to AgentJsonValue.Num(SCHEMA_VERSION),
                    "space" to AgentJsonValue.Obj(
                        listOf(
                            "spaceId" to AgentJsonValue.Str(space.spaceId),
                            "displayName" to AgentJsonValue.Str(space.displayName),
                            "createdAt" to AgentJsonValue.Num(space.createdAt.toString()),
                            "updatedAt" to AgentJsonValue.Num(space.updatedAt.toString()),
                            "revision" to AgentJsonValue.Num(space.revision.toString())
                        )
                    ),
                    "items" to AgentJsonValue.Arr(items.map(::itemJson))
                )
            )
        )

    private fun itemJson(item: AgentMemoryItemRecord): AgentJsonValue = AgentJsonValue.Obj(
        listOfNotNull(
            "memoryId" to AgentJsonValue.Str(item.memoryId),
            "spaceId" to AgentJsonValue.Str(item.spaceId),
            "title" to AgentJsonValue.Str(item.title),
            "text" to AgentJsonValue.Str(item.text),
            "contentHash" to AgentJsonValue.Str(item.contentHash),
            "sourceKind" to AgentJsonValue.Str(item.sourceKind.name),
            item.sourceRef?.let { "sourceRef" to AgentJsonValue.Str(it) },
            "createdAt" to AgentJsonValue.Num(item.createdAt.toString()),
            "updatedAt" to AgentJsonValue.Num(item.updatedAt.toString()),
            "revision" to AgentJsonValue.Num(item.revision.toString()),
            "revoked" to AgentJsonValue.Bool(item.revoked),
            item.revokedAt?.let { "revokedAt" to AgentJsonValue.Num(it.toString()) }
        )
    )

    private companion object {
        const val SCHEMA_VERSION = "1"
        const val PAGE_SIZE = 64
        const val MAX_ITEMS = 10_000
        val codec = StrictAgentJsonCodec()
    }
}

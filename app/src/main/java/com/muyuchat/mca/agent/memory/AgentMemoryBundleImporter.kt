package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Strict bulk import of one memory space and its items from a canonical JSON bundle (MEM-08).
 *
 * The bundle mirrors the persisted record, not a display format:
 *
 * ```
 * {"schemaVersion":1,
 *  "space":{"spaceId":"...","displayName":"...","createdAt":0,"updatedAt":0,"revision":1},
 *  "items":[{"memoryId":"...","title":"...","text":"...","contentHash":"<sha256 hex>",
 *            "sourceKind":"USER_TEXT","sourceRef":null,"createdAt":0,"updatedAt":0,
 *            "revision":1,"revoked":false,"revokedAt":null}]}
 * ```
 *
 * Policy, deliberately strict:
 * - an unknown field at any level is a rejection with its path, never ignored — a field that looks
 *   like a safety switch must not ride along silently;
 * - an unknown `sourceKind` is a rejection; the read path's historical fallback to `USER_TEXT` is a
 *   compatibility rule for stored rows, not a licence to import an unknown enum;
 * - fields a legacy item predates are documented defaults (`revision` = 1, `revoked` = false,
 *   `sourceRef` = null, `revokedAt` = null); a value that contradicts another (`revokedAt` set while
 *   `revoked` is false) is a rejection;
 * - `tokenCount`/`chunkCount` are not accepted: they are derived on import from the existing
 *   [AgentMemoryChunker], so a bundle cannot under-declare what is indexed;
 * - ids are preserved so relations outside the bundle stay continuous; the whole bundle commits in
 *   one transaction and a duplicate `(spaceId, contentHash)` fails the import instead of merging.
 *
 * Importing does not grant any retrieval authority: grants and task selections are separate owners.
 */
class AgentMemoryBundleImporter(
    private val persistence: AgentMemoryPersistence,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    sealed interface Result {
        data class Imported(val spaceId: String, val itemCount: Int) : Result

        data class Rejected(val code: String, val fieldPath: String, val safeMessage: String) : Result
    }

    suspend fun importBundle(bundleJson: String): Result {
        val root = when (val parsed = codec.parse(bundleJson)) {
            is AgentJsonParseResult.Parsed -> parsed.value
            is AgentJsonParseResult.Failed -> return reject(
                "MEMORY_BUNDLE_MALFORMED",
                "bundle",
                "The memory bundle is not well-formed strict JSON."
            )
        }
        val rootFields = objectFields(root) ?: return reject(
            "MEMORY_BUNDLE_MALFORMED", "bundle", "The memory bundle must be one JSON object."
        )
        unknown(rootFields, ROOT_FIELDS, "bundle")?.let { return it }
        val version = integer(rootFields, "schemaVersion", "bundle") ?: return reject(
            "MEMORY_BUNDLE_MISSING_FIELD", "bundle.schemaVersion", "The bundle must declare schemaVersion."
        )
        if (version != SCHEMA_VERSION) {
            return reject(
                "MEMORY_BUNDLE_UNSUPPORTED_VERSION",
                "bundle.schemaVersion",
                "The bundle declares an unsupported schemaVersion."
            )
        }
        val space = decodeSpace(rootFields["space"])
        if (space is Either.Failure) return space.rejection
        val itemsValue = rootFields["items"] ?: return reject(
            "MEMORY_BUNDLE_MISSING_FIELD", "bundle.items", "The bundle must declare its items."
        )
        val itemValues = (itemsValue as? AgentJsonValue.Arr)?.items ?: return reject(
            "MEMORY_BUNDLE_INVALID_FIELD", "bundle.items", "bundle.items must be an array."
        )
        if (itemValues.size > MAX_ITEMS) {
            return reject(
                "MEMORY_BUNDLE_TOO_LARGE", "bundle.items", "The bundle declares too many items."
            )
        }
        val decoded = ArrayList<Pair<AgentMemoryItemRecord, List<String>>>(itemValues.size)
        for ((index, value) in itemValues.withIndex()) {
            when (val item = decodeItem(value, index)) {
                is Either.Failure -> return item.rejection
                is Either.Success -> decoded += item.value
            }
        }

        val spaceRecord = (space as Either.Success).value
        return try {
            persistence.inTransaction {
                if (persistence.findSpace(spaceRecord.spaceId) == null) {
                    persistence.insertSpace(spaceRecord)
                }
                for ((record, chunkTexts) in decoded) {
                    val chunks = chunkTexts.flatMap { text ->
                        AgentMemoryChunker.chunk(record.memoryId, record.spaceId, text, record.createdAt)
                    }
                    // Counts are derived here, never taken from the bundle: an importer must not be
                    // able to declare fewer tokens or chunks than the text actually produces.
                    persistence.insertItemWithChunks(
                        record.copy(
                            tokenCount = chunks.sumOf { it.estimatedTokens },
                            chunkCount = chunks.size
                        ),
                        chunks
                    )
                }
            }
            Result.Imported(spaceRecord.spaceId, decoded.size)
        } catch (_: Exception) {
            // The store owns duplicate/revision diagnostics; the import reports a stable code and
            // the transaction guarantees nothing was half-written.
            reject(
                "MEMORY_BUNDLE_WRITE_REJECTED",
                "bundle.items",
                "The store refused the bundle; it was not applied as a whole."
            )
        }
    }

    private fun decodeSpace(value: AgentJsonValue?): Either<AgentMemorySpaceRecord> {
        val fields = objectFields(value) ?: return fail(
            "MEMORY_BUNDLE_MISSING_FIELD", "bundle.space", "The bundle must declare its space."
        )
        unknown(fields, SPACE_FIELDS, "bundle.space")?.let { return fail(it) }
        val spaceId = string(fields, "spaceId", "bundle.space")
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "bundle.space.spaceId", "spaceId is required.")
        val displayName = string(fields, "displayName", "bundle.space")
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "bundle.space.displayName", "displayName is required.")
        val createdAt = integer(fields, "createdAt", "bundle.space")
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "bundle.space.createdAt", "createdAt is required.")
        val updatedAt = integer(fields, "updatedAt", "bundle.space") ?: createdAt
        val revision = integer(fields, "revision", "bundle.space") ?: 1L
        return Either.Success(
            AgentMemorySpaceRecord(spaceId, displayName, createdAt, updatedAt, revision)
        )
    }

    private fun decodeItem(
        value: AgentJsonValue,
        index: Int
    ): Either<Pair<AgentMemoryItemRecord, List<String>>> {
        val path = "bundle.items[$index]"
        val fields = objectFields(value) ?: return fail(
            "MEMORY_BUNDLE_INVALID_FIELD", path, "Each item must be one JSON object."
        )
        unknown(fields, ITEM_FIELDS, path)?.let { return fail(it) }
        val memoryId = string(fields, "memoryId", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.memoryId", "memoryId is required.")
        val spaceId = string(fields, "spaceId", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.spaceId", "spaceId is required.")
        val title = string(fields, "title", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.title", "title is required.")
        val text = string(fields, "text", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.text", "text is required.")
        if (text.isBlank()) {
            return fail("MEMORY_BUNDLE_INVALID_FIELD", "$path.text", "text must not be blank.")
        }
        val contentHash = string(fields, "contentHash", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.contentHash", "contentHash is required.")
        if (!HASH_PATTERN.matches(contentHash)) {
            return fail(
                "MEMORY_BUNDLE_INVALID_FIELD", "$path.contentHash", "contentHash must be a SHA-256 hex digest."
            )
        }
        val sourceKindValue = string(fields, "sourceKind", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.sourceKind", "sourceKind is required.")
        val sourceKind = AgentMemorySourceKind.entries.firstOrNull { it.name == sourceKindValue }
            ?: return fail(
                "MEMORY_BUNDLE_INVALID_ENUM",
                "$path.sourceKind",
                "sourceKind is not a known memory source kind."
            )
        val createdAt = integer(fields, "createdAt", path)
            ?: return fail("MEMORY_BUNDLE_MISSING_FIELD", "$path.createdAt", "createdAt is required.")
        val updatedAt = integer(fields, "updatedAt", path) ?: createdAt
        val revision = integer(fields, "revision", path) ?: 1L
        val revoked = boolean(fields, "revoked", path) ?: false
        val revokedAt = integer(fields, "revokedAt", path)
        if (!revoked && revokedAt != null) {
            return fail(
                "MEMORY_BUNDLE_INVALID_FIELD",
                "$path.revokedAt",
                "revokedAt is only valid on a revoked item."
            )
        }
        if (revoked && revokedAt == null) {
            return fail(
                "MEMORY_BUNDLE_MISSING_FIELD",
                "$path.revokedAt",
                "A revoked item must declare revokedAt."
            )
        }
        return Either.Success(
            AgentMemoryItemRecord(
                memoryId = memoryId,
                spaceId = spaceId,
                title = title,
                text = text,
                contentHash = contentHash,
                sourceKind = sourceKind,
                sourceRef = string(fields, "sourceRef", path),
                tokenCount = 0,
                chunkCount = 0,
                createdAt = createdAt,
                updatedAt = updatedAt,
                revoked = revoked,
                revokedAt = revokedAt,
                revision = revision
            ) to listOf(text)
        )
    }

    private fun objectFields(value: AgentJsonValue?): Map<String, AgentJsonValue>? =
        (value as? AgentJsonValue.Obj)?.fields?.toMap()

    private fun unknown(fields: Map<String, AgentJsonValue>, allowed: Set<String>, path: String): Result? {
        val extra = fields.keys.firstOrNull { it !in allowed } ?: return null
        return reject(
            "MEMORY_BUNDLE_UNKNOWN_FIELD",
            "$path.$extra",
            "The bundle contains a field this importer does not recognise."
        )
    }

    private fun string(fields: Map<String, AgentJsonValue>, name: String, path: String): String? =
        (fields[name] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }

    private fun integer(fields: Map<String, AgentJsonValue>, name: String, path: String): Long? =
        (fields[name] as? AgentJsonValue.Num)?.literal?.toLongOrNull()

    private fun boolean(fields: Map<String, AgentJsonValue>, name: String, path: String): Boolean? =
        (fields[name] as? AgentJsonValue.Bool)?.value

    private fun reject(code: String, fieldPath: String, message: String): Result =
        Result.Rejected(code, fieldPath, message)

    private fun fail(code: String, path: String, message: String): Either<Nothing> =
        Either.Failure(Result.Rejected(code, path, message))

    private fun fail(rejection: Result): Either<Nothing> = Either.Failure(rejection)

    private sealed interface Either<out T> {
        data class Success<T>(val value: T) : Either<T>

        data class Failure(val rejection: Result) : Either<Nothing>
    }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_ITEMS = 10_000
        val ROOT_FIELDS = setOf("schemaVersion", "space", "items")
        val SPACE_FIELDS = setOf("spaceId", "displayName", "createdAt", "updatedAt", "revision")
        val ITEM_FIELDS = setOf(
            "memoryId", "spaceId", "title", "text", "contentHash", "sourceKind", "sourceRef",
            "createdAt", "updatedAt", "revision", "revoked", "revokedAt"
        )
        val HASH_PATTERN = Regex("^[0-9a-f]{64}$")
        val codec = StrictAgentJsonCodec()
    }
}

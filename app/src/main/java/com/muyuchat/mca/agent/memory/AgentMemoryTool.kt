package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.tools.AgentToolJson
import com.muyuchat.mca.agent.tools.AgentToolResults
import com.muyuchat.mca.agent.tools.AgentToolSchemaGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * `memory.search` — the only P1 memory tool (plan §7.4).
 *
 * Shape: read-only, PRIVATE, 10 s timeout, three arguments. The descriptor is built once from a
 * canonical schema and its SHA-256, so the same bytes feed the frozen-command hash, the catalog
 * duplicate/schema checks and the model prompt. There is no write, delete, link or remember tool.
 *
 * Execution path: the existing `AgentToolExecutor` seam. The broker/owner freezes the command and
 * authorizes it; this executor re-checks the tool identity, then enforces the frozen selection a
 * second time before touching storage. A model-supplied `spaceId` can only narrow the frozen scope.
 * Every failure is a typed `AgentToolResult`; cancellation is always rethrown.
 */
object AgentMemoryToolDescriptors {
    val MEMORY_SEARCH: AgentToolDescriptor = createMemorySearchDescriptor()

    private fun createMemorySearchDescriptor(): AgentToolDescriptor {
        val schemaJson = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("query"))
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "query",
                            JSONObject().put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", AgentMemoryLimits.MAX_QUERY_CHARS)
                        )
                        .put(
                            "spaceId",
                            JSONObject()
                                .put("type", JSONArray().put("string").put("null"))
                                .put("minLength", 1)
                                .put("maxLength", AgentMemoryLimits.MAX_SPACE_ID_CHARS)
                        )
                        .put(
                            "maxItems",
                            JSONObject().put("type", "integer")
                                .put("minimum", AgentMemoryLimits.MIN_MAX_ITEMS)
                                .put("maximum", AgentMemoryLimits.MAX_MAX_ITEMS)
                        )
                )
        )
        AgentToolSchemaGuard.requireSupported(schemaJson)
        return AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Search Memory Spaces",
            description = "Lexical search over the memory spaces selected for this task. " +
                "Returns bounded citations (space, memory, chunk, hashes, excerpt) and never " +
                "memory outside the task selection. Local-only; no network, no embeddings.",
            schemaJson = schemaJson,
            schemaHash = AgentToolJson.sha256Hex(schemaJson),
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE,
            timeoutMillis = TIMEOUT_MILLIS
        )
    }

    const val TOOL_KEY = "memory.search"

    /** EXACT-item read: same frozen selection and typed scope, no search/ranking. */
    val MEMORY_GET: AgentToolDescriptor = createMemoryGetDescriptor()

    const val TOOL_KEY_GET = "memory.get"

    private fun createMemoryGetDescriptor(): AgentToolDescriptor {
        val schemaJson = AgentToolJson.canonical(
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray().put("itemId"))
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "itemId",
                            JSONObject().put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", AgentMemoryLimits.MAX_MEMORY_ID_CHARS)
                        )
                        .put(
                            "spaceId",
                            JSONObject()
                                .put("type", JSONArray().put("string").put("null"))
                                .put("minLength", 1)
                                .put("maxLength", AgentMemoryLimits.MAX_SPACE_ID_CHARS)
                        )
                        .put(
                            "contentHash",
                            JSONObject()
                                .put("type", JSONArray().put("string").put("null"))
                                .put("minLength", 64)
                                .put("maxLength", 64)
                        )
                )
        )
        AgentToolSchemaGuard.requireSupported(schemaJson)
        return AgentToolDescriptor(
            toolKey = TOOL_KEY_GET,
            version = "1",
            displayName = "Get Memory Item",
            description = "Read one memory item by id from the memory spaces selected for this task. " +
                "Returns the stored text plus source metadata; an item outside the task selection or a " +
                "revoked item is reported as not found. Local-only; no network, no embeddings.",
            schemaJson = schemaJson,
            schemaHash = AgentToolJson.sha256Hex(schemaJson),
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE,
            timeoutMillis = TIMEOUT_MILLIS
        )
    }

    /**
     * Scope label carried in the existing frozen resource precondition.
     *
     * The frozen-command contract represents every tool by `(taskRoot, logicalPaths)`, and the
     * broker requires a non-empty path list. Memory has no workspace file, so this stable,
     * non-workspace label gives the command an auditable resource identity without pretending a
     * real file exists. [com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant] never covers it,
     * so `memory.search` still requires an explicit one-shot approval in V1; the frozen task
     * selection itself is enforced by [MemorySearchTool] and [AgentMemoryStore].
     */
    const val FROZEN_SCOPE_PATH = "memory"

    private const val TIMEOUT_MILLIS = 10_000L
}

class MemorySearchTool(
    private val store: AgentMemoryStore,
    private val grants: AgentMemoryReadGrantSource
) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentMemoryToolDescriptors.MEMORY_SEARCH

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult {
        if (command.toolKey != descriptor.toolKey) {
            return AgentToolResults.notExecuted(
                command,
                descriptor,
                AgentErrorCodes.TOOL_NOT_FOUND,
                "The frozen command does not target memory.search."
            )
        }
        if (command.toolVersion != descriptor.version || command.schemaHash != descriptor.schemaHash) {
            return AgentToolResults.notExecuted(
                command,
                descriptor,
                AgentErrorCodes.TOOL_INPUT_INVALID,
                "The frozen command schema does not match the current memory.search descriptor."
            )
        }

        val arguments = try {
            AgentMemoryArguments.parse(command)
        } catch (invalid: AgentMemoryInputException) {
            return AgentToolResults.invalidArguments(command, descriptor, invalid.message ?: INVALID_ARGUMENTS)
        }

        val grant = grants.grantForTask(context.taskId)
            ?: return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_DENIED,
                "This task has no frozen memory selection, so memory.search is not available."
            )
        if (grant.revoked) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                "The memory selection for this task was revoked."
            )
        }
        val requestedSpaceId = arguments.spaceId
        if (requestedSpaceId != null && !grant.covers(listOf(requestedSpaceId))) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_DENIED,
                "The requested memory space is outside this task's selection."
            )
        }
        // N02c: the approval must bind the exact frozen space set. A legacy pseudo-path command or a
        // drifted selection is not reinterpreted; the caller must re-request approval.
        val typedScope = AgentMemoryResourceScopes.precondition(grant.spaceIds)
        if (typedScope == null || !AgentMemoryResourceScopes.matches(command, typedScope)) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.RESOURCE_SCOPE_OUTDATED,
                "The approved memory scope is missing or outdated; re-request approval before searching memory."
            )
        }

        val outcome = store.search(
            grant = grant,
            query = arguments.query,
            requestedSpaceId = requestedSpaceId,
            maxItems = arguments.maxItems
        )
        return when (outcome) {
            is AgentMemorySearchOutcome.Found -> AgentToolResults.success(
                command = command,
                descriptor = descriptor,
                summary = "Searched ${grant.spaceIds.size} memory space(s); " +
                    "${outcome.result.citations.size} citation(s), " +
                    "${outcome.result.estimatedTokens} estimated token(s).",
                data = resultDocument(arguments, grant, outcome.result),
                truncated = outcome.result.truncated
            )
            is AgentMemorySearchOutcome.Rejected -> when (outcome.error.code) {
                AgentMemoryErrorCodes.QUERY_EMPTY,
                AgentMemoryErrorCodes.QUERY_TOO_LONG,
                AgentMemoryErrorCodes.MAX_ITEMS_INVALID ->
                    AgentToolResults.invalidArguments(command, descriptor, outcome.error.safeMessage)

                AgentMemoryErrorCodes.SPACE_NOT_SELECTED ->
                    AgentToolResults.denied(
                        command,
                        descriptor,
                        AgentErrorCodes.PERMISSION_DENIED,
                        outcome.error.safeMessage
                    )

                AgentMemoryErrorCodes.SELECTION_REVOKED,
                AgentMemoryErrorCodes.SPACE_NOT_FOUND ->
                    AgentToolResults.denied(
                        command,
                        descriptor,
                        AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                        outcome.error.safeMessage
                    )

                else -> AgentToolResults.failure(
                    command,
                    descriptor,
                    AgentErrorCodes.TOOL_FAILED,
                    outcome.error.safeMessage
                )
            }
        }
    }

    private fun resultDocument(
        arguments: AgentMemoryArguments.Parsed,
        grant: AgentMemoryReadGrant,
        result: AgentMemorySearchResult
    ): JSONObject = JSONObject()
        .put("query", arguments.query)
        .put("spaceIds", JSONArray().also { array -> grant.spaceIds.forEach(array::put) })
        .put("maxItems", arguments.maxItems)
        .put("citationCount", result.citations.size)
        .put(
            "citations",
            JSONArray().also { array ->
                result.citations.forEach { citation ->
                    array.put(
                        JSONObject()
                            .put("spaceId", citation.spaceId)
                            .put("memoryId", citation.memoryId)
                            .put("chunkId", citation.chunkId)
                            .put("contentHash", citation.contentHash)
                            .put("sourceHash", citation.sourceHash)
                            .put("excerpt", citation.excerpt)
                            .put("score", citation.score)
                            .put("estimatedTokens", citation.estimatedTokens)
                    )
                }
            }
        )
        .put("estimatedTokens", result.estimatedTokens)
        .put("scannedChunks", result.scannedChunks)
        .put("truncated", result.truncated)
        .put(
            "limits",
            JSONObject()
                .put("maxCitations", AgentMemoryLimits.MAX_RETURNED_CITATIONS)
                .put("maxEstimatedTokens", AgentMemoryLimits.MAX_RETURNED_TOKENS)
                .put("maxCandidates", AgentMemoryLimits.MAX_CANDIDATE_CHUNKS)
                // The scan budget is independent from the candidate/topK caps (N14/B05); a model
                // reading `truncated` can see how much of the space was actually examined.
                .put("maxScanChunks", AgentMemoryLimits.MAX_SCAN_CHUNKS)
        )

    private companion object {
        const val INVALID_ARGUMENTS = "The memory.search arguments are invalid."
    }
}

/**
 * `memory.get` — exact-item read inside the same frozen selection and typed scope as
 * `memory.search`. It never searches, never widens the space set and never exposes a foreign-space
 * item: a revoked/foreign/missing id is a plain `TOOL_FAILED` "not found".
 */
class MemoryGetTool(
    private val store: AgentMemoryStore,
    private val grants: AgentMemoryReadGrantSource
) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentMemoryToolDescriptors.MEMORY_GET

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult {
        if (command.toolKey != descriptor.toolKey) {
            return AgentToolResults.notExecuted(
                command,
                descriptor,
                AgentErrorCodes.TOOL_NOT_FOUND,
                "The frozen command does not target memory.get."
            )
        }
        if (command.toolVersion != descriptor.version || command.schemaHash != descriptor.schemaHash) {
            return AgentToolResults.notExecuted(
                command,
                descriptor,
                AgentErrorCodes.TOOL_INPUT_INVALID,
                "The frozen command schema does not match the current memory.get descriptor."
            )
        }
        val arguments = try {
            AgentMemoryArguments.parseGet(command)
        } catch (invalid: AgentMemoryInputException) {
            return AgentToolResults.invalidArguments(command, descriptor, invalid.message ?: INVALID_ARGUMENTS)
        }
        val grant = grants.grantForTask(context.taskId)
            ?: return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_DENIED,
                "This task has no frozen memory selection, so memory.get is not available."
            )
        if (grant.revoked) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                "The memory selection for this task was revoked."
            )
        }
        if (arguments.spaceId != null && !grant.covers(listOf(arguments.spaceId))) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.PERMISSION_DENIED,
                "The requested memory space is outside this task's selection."
            )
        }
        val typedScope = AgentMemoryResourceScopes.precondition(grant.spaceIds)
        if (typedScope == null || !AgentMemoryResourceScopes.matches(command, typedScope)) {
            return AgentToolResults.denied(
                command,
                descriptor,
                AgentErrorCodes.RESOURCE_SCOPE_OUTDATED,
                "The approved memory scope is missing or outdated; re-request approval before reading memory."
            )
        }
        return when (val outcome = store.getItem(grant, arguments.itemId, arguments.spaceId, arguments.contentHash)) {
            is AgentMemoryGetOutcome.Found -> {
                val item = outcome.item
                AgentToolResults.success(
                    command = command,
                    descriptor = descriptor,
                    summary = "Read memory item ${item.memoryId} from space ${item.spaceId} " +
                        "(${item.text.toByteArray(Charsets.UTF_8).size} bytes).",
                    data = JSONObject()
                        .put("itemId", item.memoryId)
                        .put("spaceId", item.spaceId)
                        .put("title", item.title)
                        .put("text", item.text)
                        .put("sourceKind", item.sourceKind.name)
                        .put("sourceRef", item.sourceRef ?: JSONObject.NULL)
                        .put("tokenCount", item.tokenCount)
                        .put("chunkCount", item.chunkCount)
                        .put("updatedAt", item.updatedAt)
                )
            }
            is AgentMemoryGetOutcome.Rejected -> when (outcome.error.code) {
                AgentMemoryErrorCodes.SELECTION_REVOKED,
                AgentMemoryErrorCodes.SPACE_NOT_FOUND ->
                    AgentToolResults.denied(
                        command,
                        descriptor,
                        AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                        outcome.error.safeMessage
                    )
                AgentMemoryErrorCodes.SPACE_NOT_SELECTED ->
                    AgentToolResults.denied(
                        command,
                        descriptor,
                        AgentErrorCodes.PERMISSION_DENIED,
                        outcome.error.safeMessage
                    )
                AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                AgentMemoryErrorCodes.STORAGE_FAILED ->
                    AgentToolResults.failure(
                        command,
                        descriptor,
                        AgentErrorCodes.TOOL_FAILED,
                        outcome.error.safeMessage
                    )
                else -> AgentToolResults.failure(
                    command,
                    descriptor,
                    AgentErrorCodes.TOOL_FAILED,
                    outcome.error.safeMessage
                )
            }
        }
    }

    private companion object {
        const val INVALID_ARGUMENTS = "The memory.get arguments are invalid."
    }
}

/** Strict reader for the three `memory.search` arguments; nothing is coerced. */
internal object AgentMemoryArguments {
    data class Parsed(val query: String, val spaceId: String?, val maxItems: Int)

    private const val FIELD_QUERY = "query"
    private const val FIELD_SPACE_ID = "spaceId"
    private const val FIELD_MAX_ITEMS = "maxItems"

    fun parse(command: FrozenToolCommand): Parsed {
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(command.normalizedArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> throw AgentMemoryInputException(parsed.error.safeMessage)
        }
        val unknown = fields.keys.firstOrNull { it !in setOf(FIELD_QUERY, FIELD_SPACE_ID, FIELD_MAX_ITEMS) }
        if (unknown != null) {
            throw AgentMemoryInputException("memory.search does not accept the field '$unknown'.")
        }
        val query = (fields[FIELD_QUERY] as? AgentArgValue.Text)?.value
            ?: throw AgentMemoryInputException("memory.search requires a string 'query' argument.")
        if (query.isEmpty() || query.length > AgentMemoryLimits.MAX_QUERY_CHARS) {
            throw AgentMemoryInputException(
                "The 'query' argument must contain 1-${AgentMemoryLimits.MAX_QUERY_CHARS} characters."
            )
        }
        if (query.any { it.isISOControl() }) {
            throw AgentMemoryInputException("The 'query' argument must not contain control characters.")
        }
        val spaceId = when (val value = fields[FIELD_SPACE_ID]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> {
                if (!AgentMemorySelection.isValidSpaceId(value.value)) {
                    throw AgentMemoryInputException("The 'spaceId' argument is not a valid memory space id.")
                }
                value.value
            }
            else -> throw AgentMemoryInputException("The 'spaceId' argument must be a string or null.")
        }
        val maxItems = when (val value = fields[FIELD_MAX_ITEMS]) {
            null -> AgentMemoryLimits.DEFAULT_MAX_ITEMS
            is AgentArgValue.Integer -> {
                if (value.value < AgentMemoryLimits.MIN_MAX_ITEMS || value.value > AgentMemoryLimits.MAX_MAX_ITEMS) {
                    throw AgentMemoryInputException(
                        "The 'maxItems' argument must be between ${AgentMemoryLimits.MIN_MAX_ITEMS} and " +
                            "${AgentMemoryLimits.MAX_MAX_ITEMS}."
                    )
                }
                value.value.toInt()
            }
            else -> throw AgentMemoryInputException("The 'maxItems' argument must be an integer.")
        }
        return Parsed(query = query, spaceId = spaceId, maxItems = maxItems)
    }

    data class GetParsed(val itemId: String, val spaceId: String?, val contentHash: String?)

    private const val FIELD_ITEM_ID = "itemId"
    private const val FIELD_CONTENT_HASH = "contentHash"

    fun parseGet(command: FrozenToolCommand): GetParsed {
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(command.normalizedArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> throw AgentMemoryInputException(parsed.error.safeMessage)
        }
        val unknown = fields.keys.firstOrNull { it !in setOf(FIELD_ITEM_ID, FIELD_SPACE_ID, FIELD_CONTENT_HASH) }
        if (unknown != null) {
            throw AgentMemoryInputException("memory.get does not accept the field '$unknown'.")
        }
        val itemId = (fields[FIELD_ITEM_ID] as? AgentArgValue.Text)?.value
            ?: throw AgentMemoryInputException("memory.get requires a string 'itemId' argument.")
        if (itemId.isEmpty() || itemId.length > AgentMemoryLimits.MAX_MEMORY_ID_CHARS) {
            throw AgentMemoryInputException(
                "The 'itemId' argument must contain 1-${AgentMemoryLimits.MAX_MEMORY_ID_CHARS} characters."
            )
        }
        if (itemId.any { it.isISOControl() }) {
            throw AgentMemoryInputException("The 'itemId' argument must not contain control characters.")
        }
        val spaceId = when (val value = fields[FIELD_SPACE_ID]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> {
                if (!AgentMemorySelection.isValidSpaceId(value.value)) {
                    throw AgentMemoryInputException("The 'spaceId' argument is not a valid memory space id.")
                }
                value.value
            }
            else -> throw AgentMemoryInputException("The 'spaceId' argument must be a string or null.")
        }
        val contentHash = when (val value = fields[FIELD_CONTENT_HASH]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> {
                if (!value.value.matches(Regex("^[0-9a-f]{64}$"))) {
                    throw AgentMemoryInputException(
                        "The 'contentHash' argument must be a lowercase SHA-256 string."
                    )
                }
                value.value
            }
            else -> throw AgentMemoryInputException("The 'contentHash' argument must be a string or null.")
        }
        return GetParsed(itemId = itemId, spaceId = spaceId, contentHash = contentHash)
    }
}

/** Typed, content-free argument failure; mapped to `TOOL_INPUT_INVALID`. */
internal class AgentMemoryInputException(message: String) : Exception(message)

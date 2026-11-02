package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/** Whether one host operation mutates host state. Declared capability, never a user grant. */
enum class ToolPackageHostAccess { READ_ONLY, WRITE }

/**
 * One host operation an executable package may request.
 *
 * The registry is code-owned. A package manifest only names a method; it cannot add a method,
 * change its access level, lower its data class or widen its argument surface. Workspace and memory
 * entries mirror the existing built-in tool schemas (`path`, `offset`, `limit`, `query`, ...); an
 * unknown argument is rejected before the handler is called.
 *
 * [resourceKind] is the closed resource family the operation may touch. It is declared here, not by
 * the package, and is re-checked on every call against the resource the owning handler resolves.
 */
data class ToolPackageHostApi(
    val method: String,
    val access: ToolPackageHostAccess,
    val dataClass: AgentDataClass,
    val requiredArguments: Set<String>,
    val allowedArguments: Set<String>,
    val resourceKind: ToolPackageHostResourceKind = ToolPackageHostResourceKind.NONE,
    val maxArgumentsBytes: Int = ToolPackageLimits.MAX_HOST_ARGUMENT_BYTES
) {
    init {
        require(AgentToolPackageToolRef.isValidHostOperationId(method)) {
            "host API method must be a dotted lowercase identifier: '$method'"
        }
        require(requiredArguments.all { it in allowedArguments }) {
            "host API required arguments must be a subset of its allowed arguments"
        }
        require(allowedArguments.all { AgentToolPackageToolRef.isValidParameterName(it) }) {
            "host API declares an invalid argument name"
        }
        require(maxArgumentsBytes in 1..ToolPackageLimits.MAX_HOST_ARGUMENT_BYTES) {
            "host API argument budget is outside the bounded range"
        }
        require(access == ToolPackageHostAccess.READ_ONLY || resourceKind != ToolPackageHostResourceKind.NONE) {
            "a write host API must declare the resource family it touches"
        }
    }
}

/**
 * The complete host API surface of the first executable slice.
 *
 * Filesystem, network, shell, native, UI and provider operations are deliberately absent: a script
 * cannot name them, and a call to any other method is rejected as unknown before it reaches a
 * handler. `artifact.write_text` exists so the per-call write-approval gate can be exercised; the
 * catalog projection still refuses write-capable packages because no production approval owner is
 * injected yet.
 */
object ToolPackageHostApis {
    const val WORKSPACE_LIST: String = "workspace.list"
    const val WORKSPACE_READ_TEXT: String = "workspace.read_text"
    const val WORKSPACE_SEARCH_TEXT: String = "workspace.search_text"
    const val MEMORY_SEARCH: String = "memory.search"
    const val ARTIFACT_WRITE_TEXT: String = "artifact.write_text"

    val WORKSPACE_LIST_API = ToolPackageHostApi(
        method = WORKSPACE_LIST,
        access = ToolPackageHostAccess.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE,
        requiredArguments = emptySet(),
        allowedArguments = setOf("path", "limit", "cursor"),
        resourceKind = ToolPackageHostResourceKind.WORKSPACE
    )

    val WORKSPACE_READ_TEXT_API = ToolPackageHostApi(
        method = WORKSPACE_READ_TEXT,
        access = ToolPackageHostAccess.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE,
        requiredArguments = setOf("path"),
        allowedArguments = setOf("path", "offset", "limit"),
        resourceKind = ToolPackageHostResourceKind.WORKSPACE
    )

    val WORKSPACE_SEARCH_TEXT_API = ToolPackageHostApi(
        method = WORKSPACE_SEARCH_TEXT,
        access = ToolPackageHostAccess.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE,
        requiredArguments = setOf("query"),
        allowedArguments = setOf("query", "path", "maxMatches"),
        resourceKind = ToolPackageHostResourceKind.WORKSPACE
    )

    val MEMORY_SEARCH_API = ToolPackageHostApi(
        method = MEMORY_SEARCH,
        access = ToolPackageHostAccess.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE,
        requiredArguments = setOf("query"),
        allowedArguments = setOf("query", "spaceId", "maxItems"),
        resourceKind = ToolPackageHostResourceKind.MEMORY
    )

    val ARTIFACT_WRITE_TEXT_API = ToolPackageHostApi(
        method = ARTIFACT_WRITE_TEXT,
        access = ToolPackageHostAccess.WRITE,
        dataClass = AgentDataClass.PRIVATE,
        requiredArguments = setOf("path", "content", "expectedOldHash"),
        allowedArguments = setOf("path", "content", "expectedOldHash"),
        resourceKind = ToolPackageHostResourceKind.WORKSPACE
    )

    val ALL: List<ToolPackageHostApi> = listOf(
        WORKSPACE_LIST_API,
        WORKSPACE_READ_TEXT_API,
        WORKSPACE_SEARCH_TEXT_API,
        MEMORY_SEARCH_API,
        ARTIFACT_WRITE_TEXT_API
    ).sortedBy { it.method }

    private val BY_METHOD: Map<String, ToolPackageHostApi> = ALL.associateBy { it.method }

    fun find(method: String): ToolPackageHostApi? = BY_METHOD[method]

    fun require(method: String): ToolPackageHostApi = requireNotNull(find(method)) {
        "host API is not registered: '$method'"
    }

    val READ_ONLY_METHODS: Set<String>
        get() = ALL.filter { it.access == ToolPackageHostAccess.READ_ONLY }.map { it.method }.toSet()

    val WRITE_METHODS: Set<String>
        get() = ALL.filter { it.access == ToolPackageHostAccess.WRITE }.map { it.method }.toSet()
}

/** Identity of the run that owns one script execution. It never contains an absolute path. */
data class ToolPackageExecutionScope(
    val invocationId: String,
    val runId: String,
    val runEpoch: Long,
    val taskId: String,
    val workspaceId: String,
    val policyVersion: Long
) {
    init {
        require(invocationId.isNotBlank()) { "execution scope invocationId must not be blank" }
        require(runId.isNotBlank()) { "execution scope runId must not be blank" }
        require(taskId.isNotBlank()) { "execution scope taskId must not be blank" }
        require(workspaceId.isNotBlank()) { "execution scope workspaceId must not be blank" }
        require(runEpoch > 0L) { "execution scope runEpoch must be positive" }
        require(policyVersion > 0L) { "execution scope policyVersion must be positive" }
    }
}

/**
 * Immutable request a host handler receives. The script cannot construct or forge it.
 *
 * [resource] is the exact resource the bridge resolved for this call. The handler must use it
 * instead of re-reading the script argument, so the resource that was evaluated (and, for a write,
 * approved) is the resource that is actually touched.
 */
data class ToolPackageHostOperationRequest(
    val api: ToolPackageHostApi,
    val argumentsJson: String,
    val instanceId: String,
    val packageId: String,
    val packageContentHash: String,
    val scope: ToolPackageExecutionScope,
    val resource: ToolPackageHostResource
)

/** Handler result. A denial is a typed policy decision, not a script-controlled string. */
sealed interface ToolPackageHostOperationResult {
    data class Returned(val json: String) : ToolPackageHostOperationResult

    data class Denied(val error: ToolPackageError) : ToolPackageHostOperationResult
}

/**
 * The app-owned implementation of one host capability family (workspace, memory, artifacts, ...).
 *
 * A handler receives only a typed request; it must enforce the real resource grant (workspace read
 * grant, memory space selection, invocation id) itself. The bridge is not a substitute for those
 * checks and never exposes a host path to the script.
 *
 * [resolveResource] runs before any approval or dispatch: it maps the script arguments onto the
 * handler's own resource surface and returns a typed refusal when the request does not name a
 * resource the handler can touch. The default refuses, so a handler that does not describe its
 * surface can never be reached by a resource-bearing call.
 */
interface ToolPackageHostOperationHandler {
    suspend fun handle(request: ToolPackageHostOperationRequest): ToolPackageHostOperationResult

    suspend fun resolveResource(
        request: ToolPackageHostResourceRequest
    ): ToolPackageHostResourceResolution = ToolPackageHostResourceResolution.Refused(
        ToolPackageError(
            ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED,
            "This host handler does not declare the resource surface of its capabilities."
        )
    )
}

/**
 * Narrow bridge handed to a script engine. It can only request a host call; it owns no host object.
 *
 * [stableCallOrdinal] is the guest's correlation id for the call. `0` lets the host assign the next
 * sequential ordinal. A repeated ordinal with the same method and canonical arguments resolves to
 * the single earlier outcome; a repeated ordinal with different arguments is refused.
 */
interface ToolPackageHostCaller {
    suspend fun call(
        method: String,
        argumentsJson: String,
        stableCallOrdinal: Int = AUTO_ORDINAL
    ): String

    companion object {
        /** The guest did not choose an ordinal; the host assigns the next sequential one. */
        const val AUTO_ORDINAL: Int = 0
    }
}

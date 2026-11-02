package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production host capability family for executable packages: workspace read-only operations.
 *
 * Every call is pinned to the execution scope's exact `workspaceId`/`taskId`; the script can only
 * name a logical path inside that workspace. [resolveResource] maps the script path onto the same
 * logical-path surface the workspace file port enforces (only `inputs/` and `artifacts/` are
 * addressable), so a path that the port would reject is refused before any approval or dispatch.
 * The handler then uses the resolved canonical path, never the raw script string.
 *
 * The handler enforces its own bounds on offset/limit (never trusting script numbers) and maps every
 * port failure to a typed denial. Workspace reads are always wired; `artifact.write_text` is wired
 * only into the bridge flow and is reachable only when the composition enables
 * [ToolPackageWriteCapabilityPolicy.ALLOW_WITH_PER_CALL_APPROVAL] and injects a per-call approval
 * port. A half-wired capability must not pretend to succeed.
 */
class WorkspaceToolPackageHostHandler(
    private val workspace: AgentWorkspaceFilePort
) : ToolPackageHostOperationHandler {

    override suspend fun resolveResource(
        request: ToolPackageHostResourceRequest
    ): ToolPackageHostResourceResolution {
        val arguments = runCatching { JSONObject(request.argumentsJson) }.getOrNull()
            ?: return refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED,
                "Host arguments are not a JSON object."
            )
        return when (request.api.resourceKind) {
            ToolPackageHostResourceKind.WORKSPACE -> resolveWorkspaceResource(request, arguments)
            ToolPackageHostResourceKind.MEMORY -> refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                "Memory reads are not available to executable packages."
            )
            ToolPackageHostResourceKind.NONE -> refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                "This host capability is not wired in the production runtime."
            )
        }
    }

    override suspend fun handle(
        request: ToolPackageHostOperationRequest
    ): ToolPackageHostOperationResult {
        val resource = request.resource as? ToolPackageHostResource.Workspace
            ?: return denied("This host capability is not wired in the production runtime.")
        val arguments = runCatching { JSONObject(request.argumentsJson) }.getOrNull()
            ?: return denied("Host arguments are not a JSON object.")
        return when (request.api.method) {
            ToolPackageHostApis.WORKSPACE_READ_TEXT -> readText(request, resource, arguments)
            ToolPackageHostApis.WORKSPACE_LIST -> list(request, resource, arguments)
            ToolPackageHostApis.WORKSPACE_SEARCH_TEXT -> search(request, resource, arguments)
            ToolPackageHostApis.ARTIFACT_WRITE_TEXT -> writeArtifact(request, resource, arguments)
            else -> denied("This host capability is not wired in the production runtime.")
        }
    }

    private fun resolveWorkspaceResource(
        request: ToolPackageHostResourceRequest,
        arguments: JSONObject
    ): ToolPackageHostResourceResolution {
        val api = request.api
        val access = if (api.access == ToolPackageHostAccess.WRITE) {
            ToolPackageHostResourceAccess.WRITE
        } else {
            ToolPackageHostResourceAccess.READ
        }
        val rawPath = arguments.optString("path", "")
        if (rawPath.isBlank()) {
            if (api.method == ToolPackageHostApis.WORKSPACE_LIST ||
                api.method == ToolPackageHostApis.WORKSPACE_SEARCH_TEXT
            ) {
                return ToolPackageHostResourceResolution.Resolved(
                    ToolPackageHostResource.Workspace(
                        workspaceId = request.scope.workspaceId,
                        relativePath = null,
                        access = ToolPackageHostResourceAccess.READ,
                        capability = api.method
                    )
                )
            }
            return refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED,
                "The host call must name a workspace path."
            )
        }
        val canonical = try {
            when (api.method) {
                ToolPackageHostApis.WORKSPACE_READ_TEXT -> AgentWorkspacePaths.fileLogicalPath(rawPath)
                ToolPackageHostApis.WORKSPACE_LIST -> AgentWorkspacePaths.directoryLogicalPath(rawPath)
                ToolPackageHostApis.WORKSPACE_SEARCH_TEXT -> AgentWorkspacePaths.directoryLogicalPath(rawPath)
                else -> AgentWorkspacePaths.fileLogicalPath(rawPath)
            }
        } catch (_: Exception) {
            return refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                "The requested path is outside the task workspace surface."
            )
        }
        if (access == ToolPackageHostResourceAccess.WRITE &&
            !canonical.startsWith(AgentWorkspacePaths.ARTIFACTS + "/")
        ) {
            return refused(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                "Artifact writes must target a file below artifacts/."
            )
        }
        return ToolPackageHostResourceResolution.Resolved(
            ToolPackageHostResource.Workspace(
                workspaceId = request.scope.workspaceId,
                relativePath = canonical,
                access = access,
                capability = api.method
            )
        )
    }

    private suspend fun readText(
        request: ToolPackageHostOperationRequest,
        resource: ToolPackageHostResource.Workspace,
        arguments: JSONObject
    ): ToolPackageHostOperationResult {
        val path = resource.relativePath ?: return denied("The read path must not be blank.")
        val offset = arguments.optLong("offset", 0L).coerceAtLeast(0L)
        val limit = arguments.optInt("limit", DEFAULT_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        return when (
            val result = workspace.readText(
                workspaceId = request.scope.workspaceId,
                taskId = request.scope.taskId,
                path = path,
                offsetBytes = offset,
                limitBytes = limit
            )
        ) {
            is AgentFileResult.Ok -> {
                val value = result.value
                ToolPackageHostOperationResult.Returned(
                    JSONObject()
                        .put("path", path)
                        .put("text", value.content)
                        .put("offsetBytes", value.offsetBytes)
                        .put("nextOffsetBytes", value.nextOffsetBytes)
                        .put("eof", value.eof)
                        .put("totalBytes", value.totalBytes)
                        .put("sha256", value.sha256)
                        .toString()
                )
            }

            is AgentFileResult.Failure -> denied(
                result.safeMessage.ifBlank { "The workspace read failed." },
                result.errorCode
            )
        }
    }

    private suspend fun list(
        request: ToolPackageHostOperationRequest,
        resource: ToolPackageHostResource.Workspace,
        arguments: JSONObject
    ): ToolPackageHostOperationResult {
        val path = resource.relativePath.orEmpty()
        val limit = arguments.optInt("limit", DEFAULT_LIST_ENTRIES).coerceIn(1, MAX_LIST_ENTRIES)
        val cursor = arguments.optString("cursor").takeIf { it.isNotBlank() }
        return when (
            val result = workspace.list(
                workspaceId = request.scope.workspaceId,
                taskId = request.scope.taskId,
                path = path,
                limit = limit,
                cursor = cursor
            )
        ) {
            is AgentFileResult.Ok -> {
                val entries = JSONArray()
                result.value.entries.forEach { entry ->
                    entries.put(
                        JSONObject()
                            .put("path", entry.logicalPath)
                            .put("isDirectory", entry.kind == com.muyuchat.core.agent.contract.AgentFileKind.DIRECTORY)
                            .put("sizeBytes", entry.sizeBytes)
                    )
                }
                ToolPackageHostOperationResult.Returned(
                    JSONObject()
                        .put("path", path)
                        .put("entries", entries)
                        .put("nextCursor", result.value.nextCursor)
                        .toString()
                )
            }

            is AgentFileResult.Failure -> denied(
                result.safeMessage.ifBlank { "The workspace listing failed." },
                result.errorCode
            )
        }
    }

    private suspend fun search(
        request: ToolPackageHostOperationRequest,
        resource: ToolPackageHostResource.Workspace,
        arguments: JSONObject
    ): ToolPackageHostOperationResult {
        val query = arguments.optString("query")
        if (query.isBlank()) return denied("The search query must not be blank.")
        val path = resource.relativePath.orEmpty()
        val maxMatches = arguments.optInt("maxMatches", DEFAULT_SEARCH_MATCHES)
            .coerceIn(1, MAX_SEARCH_MATCHES)
        return when (
            val result = workspace.searchText(
                workspaceId = request.scope.workspaceId,
                taskId = request.scope.taskId,
                query = query,
                path = path,
                maxMatches = maxMatches
            )
        ) {
            is AgentFileResult.Ok -> {
                val matches = JSONArray()
                result.value.matches.forEach { match ->
                    matches.put(
                        JSONObject()
                            .put("path", match.logicalPath)
                            .put("lineNumber", match.lineNumber)
                            .put("snippet", match.snippet)
                    )
                }
                ToolPackageHostOperationResult.Returned(
                    JSONObject()
                        .put("query", query)
                        .put("matches", matches)
                        .put("truncated", result.value.truncated)
                        .toString()
                )
            }

            is AgentFileResult.Failure -> denied(
                result.safeMessage.ifBlank { "The workspace search failed." },
                result.errorCode
            )
        }
    }

    /**
     * `artifact.write_text`: the only write host API in the translated/supported slice.
     *
     * The bridge has already consulted the per-call approval port for this exact binding before
     * this method is reached. The handler still re-checks the resolved resource access, the content
     * budget and the expected-old-hash shape; the workspace port performs the commit-journaled,
     * path-fenced write. A failure returns a typed denial and never a fake success.
     */
    private suspend fun writeArtifact(
        request: ToolPackageHostOperationRequest,
        resource: ToolPackageHostResource.Workspace,
        arguments: JSONObject
    ): ToolPackageHostOperationResult {
        if (resource.access != ToolPackageHostResourceAccess.WRITE) {
            return denied("The resolved artifact resource is not writable.")
        }
        val path = resource.relativePath
            ?: return denied("The artifact write path must not be blank.")
        val content = arguments.opt("content") as? String
            ?: return denied("The artifact write content must be a string.")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
            return denied("The artifact write content exceeds its byte budget.")
        }
        val expectedOldHash = when (val raw = arguments.opt("expectedOldHash")) {
            null, JSONObject.NULL -> null
            is String -> raw.takeIf { HEX_SHA256.matches(it) }
                ?: return denied("expectedOldHash must be a lowercase SHA-256 or null.")
            else -> return denied("expectedOldHash must be a lowercase SHA-256 or null.")
        }
        return when (
            val result = workspace.writeArtifact(
                workspaceId = request.scope.workspaceId,
                taskId = request.scope.taskId,
                invocationId = request.scope.invocationId,
                logicalPath = path,
                content = content,
                expectedOldHash = expectedOldHash,
                policyVersion = request.scope.policyVersion
            )
        ) {
            is AgentFileResult.Ok -> {
                val value = result.value
                ToolPackageHostOperationResult.Returned(
                    JSONObject()
                        .put("path", value.logicalPath)
                        .put("artifactId", value.artifactId)
                        .put("sizeBytes", value.sizeBytes)
                        .put("sha256", value.sha256)
                        .toString()
                )
            }

            is AgentFileResult.Failure -> denied(
                result.safeMessage.ifBlank { "The artifact write failed." },
                result.errorCode
            )
        }
    }

    private fun denied(message: String, cause: String? = null): ToolPackageHostOperationResult =
        ToolPackageHostOperationResult.Denied(
            ToolPackageError(
                ToolPackageErrorCode.HOST_CALL_DENIED,
                message + (cause?.let { " ($it)" } ?: "")
            )
        )

    private fun refused(
        code: ToolPackageErrorCode,
        message: String
    ): ToolPackageHostResourceResolution =
        ToolPackageHostResourceResolution.Refused(ToolPackageError(code, message))

    private companion object {
        const val DEFAULT_READ_BYTES = 64 * 1024
        const val MAX_READ_BYTES = 256 * 1024
        const val DEFAULT_LIST_ENTRIES = 100
        const val MAX_LIST_ENTRIES = 200
        const val DEFAULT_SEARCH_MATCHES = 20
        const val MAX_SEARCH_MATCHES = 50
        const val MAX_WRITE_BYTES = 32 * 1024
        val HEX_SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

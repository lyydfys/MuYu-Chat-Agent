package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Deterministic fixtures for the executable tool-package runtime tests. */
internal object ToolpackFixtures {
    const val PACKAGE_ID: String = "research-basics"
    const val TOOL_NAME: String = "read_brief"
    const val MAIN_ENTRY: String = "main.js"
    const val TOOL_ENTRY: String = "tools/read_brief.js"

    const val PARAMETER_SCHEMA: String =
        """{"additionalProperties":false,"properties":{"path":{"maxLength":512,"minLength":1,"type":"string"}},"required":["path"],"type":"object"}"""

    const val ZERO_ARGUMENT_SCHEMA: String =
        """{"additionalProperties":false,"properties":{},"type":"object"}"""

    fun toolJson(
        name: String = TOOL_NAME,
        entry: String = TOOL_ENTRY,
        inputSchema: String = PARAMETER_SCHEMA,
        timeoutMillis: Long? = 10_000L,
        displayName: String = "Read brief",
        description: String = "Read a workspace brief through the declared host API."
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("displayName", displayName)
        .put("description", description)
        .put("entry", entry)
        .put("inputSchema", inputSchema)
        .also { if (timeoutMillis != null) it.put("timeoutMillis", timeoutMillis) }

    fun dependencyJson(
        packageId: String,
        contentHash: String,
        version: String = "1.0.0"
    ): JSONObject = JSONObject()
        .put("packageId", packageId)
        .put("version", version)
        .put("contentHash", contentHash)

    fun manifestJson(
        schemaVersion: Int = ToolPackageLimits.FORMAT_VERSION,
        packageId: String = PACKAGE_ID,
        packageVersion: String = "1.0.0",
        hostApiVersion: Int = ToolPackageLimits.HOST_API_VERSION,
        displayName: String = "Research basics",
        description: String = "Read-only workspace and memory tools.",
        entry: String = MAIN_ENTRY,
        hostApis: List<String> = listOf(ToolPackageHostApis.WORKSPACE_READ_TEXT),
        tools: List<JSONObject> = listOf(toolJson()),
        dependencies: List<JSONObject>? = null,
        extraRoot: Map<String, Any?> = emptyMap()
    ): String {
        val root = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("packageId", packageId)
            .put("packageVersion", packageVersion)
            .put("hostApiVersion", hostApiVersion)
            .put("displayName", displayName)
            .put("description", description)
            .put("entry", entry)
            .put("hostApis", JSONArray(hostApis))
            .put("tools", JSONArray(tools))
        if (dependencies != null) root.put("dependencies", JSONArray(dependencies))
        extraRoot.forEach { (key, value) -> root.put(key, value) }
        return root.toString()
    }

    fun scripts(vararg extra: Pair<String, String>): Map<String, String> = buildMap {
        put(MAIN_ENTRY, "// registration is not executed in this slice\n")
        put(TOOL_ENTRY, "// fake engine placeholder\n")
        extra.forEach { (path, source) -> put(path, source) }
    }

    fun bundle(
        manifestJson: String = manifestJson(),
        scripts: Map<String, String> = scripts()
    ): ExecutableToolPackage = ExecutableToolPackageLoader().load(manifestJson, scripts)

    fun scope(
        invocationId: String = "inv-1",
        runId: String = "run-1",
        taskId: String = "task-1",
        workspaceId: String = "workspace-1"
    ): ToolPackageExecutionScope = ToolPackageExecutionScope(
        invocationId = invocationId,
        runId = runId,
        runEpoch = 3L,
        taskId = taskId,
        workspaceId = workspaceId,
        policyVersion = 2L
    )

    fun context(workspaceReadGranted: Boolean = true): AgentToolContext = AgentToolContext(
        taskId = "task-1",
        workspaceId = "workspace-1",
        runId = "run-1",
        stepIndex = 0,
        runEpoch = 3L,
        policyVersion = 2L,
        workspaceReadGranted = workspaceReadGranted
    )

    fun command(
        toolKey: String,
        toolVersion: String,
        schemaHash: String,
        argumentsJson: String = """{"path":"notes.txt"}"""
    ): FrozenToolCommand = FrozenToolCommand(
        invocationId = "inv-1",
        runId = "run-1",
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = toolKey,
        toolVersion = toolVersion,
        schemaHash = schemaHash,
        normalizedArgumentsJson = argumentsJson,
        argumentsHash = "arguments-hash",
        resourceFingerprint = "resource-fingerprint",
        runEpoch = 3L,
        policyVersion = 2L,
        expiresAtMillis = Long.MAX_VALUE
    )

    fun request(
        bundle: ExecutableToolPackage,
        argumentsJson: String = """{"path":"notes.txt"}""",
        approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
        toolName: String = bundle.manifest.tools.single().name
    ): ToolPackageExecutionRequest = ToolPackageExecutionRequest(
        scope = scope(),
        tool = requireNotNull(bundle.manifest.tool(toolName)),
        argumentsJson = argumentsJson,
        approval = approval
    )

    fun assertPackageError(expected: ToolPackageErrorCode, block: () -> Unit) {
        try {
            block()
        } catch (failure: ToolPackageException) {
            assertEquals(expected, failure.error.code)
            return
        }
        fail("expected ToolPackageException with $expected")
    }
}

/** Fake engine used to prove the host-side contract without shipping a JavaScript interpreter. */
internal class FakeScriptEngine(
    private val body: suspend (ToolPackageEngineRequest) -> ToolPackageEngineResult
) : ToolPackageScriptEngine {
    override val engineId: String = "fake-script-engine"

    var invocations: Int = 0
        private set

    override suspend fun execute(request: ToolPackageEngineRequest): ToolPackageEngineResult {
        invocations += 1
        return body(request)
    }
}

/**
 * Test double for the per-call approval port.
 *
 * The default decision approves the exact binding with a distinct approval id and the binding's own
 * revocation epoch, which is what a correct approval owner returns. Pass [decision] to simulate a
 * denial, a stale binding or a revoked epoch.
 */
internal class RecordingApproval(
    private val decision: ((ToolPackageHostCallBinding) -> ToolPackageHostApprovalDecision)? = null
) : ToolPackageHostApprovalPort {
    val requests = mutableListOf<ToolPackageHostCallBinding>()

    override suspend fun approve(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision {
        requests += binding
        return decision?.invoke(binding) ?: allow(binding)
    }

    companion object {
        fun allow(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision =
            ToolPackageHostApprovalDecision.Approved(
                approvalId = "approval-${binding.bindingId.take(12)}-${binding.stableCallOrdinal}",
                bindingId = binding.bindingId,
                revocation = binding.revocation
            )

        fun deny(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision =
            ToolPackageHostApprovalDecision.Refused(
                ToolPackageHostApprovalRefusal.DENIED,
                "The approval owner denied this host call."
            )

        fun allowing(): RecordingApproval = RecordingApproval()

        fun denying(): RecordingApproval = RecordingApproval(::deny)
    }
}

/**
 * Test double for the host handler.
 *
 * It resolves every requested workspace path as-is (it has no real workspace surface), so the bridge
 * contract can be exercised without a file port. Surface enforcement itself is covered by
 * `WorkspaceToolPackageHostHandlerTest` and the binding tests.
 */
internal class RecordingHostHandler(
    var result: ToolPackageHostOperationResult = ToolPackageHostOperationResult.Returned("""{"ok":true}""")
) : ToolPackageHostOperationHandler {
    val requests = mutableListOf<ToolPackageHostOperationRequest>()

    override suspend fun handle(request: ToolPackageHostOperationRequest): ToolPackageHostOperationResult {
        requests += request
        return result
    }

    override suspend fun resolveResource(
        request: ToolPackageHostResourceRequest
    ): ToolPackageHostResourceResolution {
        val arguments = runCatching { JSONObject(request.argumentsJson) }.getOrNull()
            ?: return refused(ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED)
        val api = request.api
        val access = if (api.access == ToolPackageHostAccess.WRITE) {
            ToolPackageHostResourceAccess.WRITE
        } else {
            ToolPackageHostResourceAccess.READ
        }
        val path = arguments.optString("path").takeIf { it.isNotBlank() }
        val resource = when (api.resourceKind) {
            ToolPackageHostResourceKind.WORKSPACE -> ToolPackageHostResource.Workspace(
                workspaceId = request.scope.workspaceId,
                relativePath = path,
                access = access,
                capability = api.method
            )
            ToolPackageHostResourceKind.MEMORY -> ToolPackageHostResource.Memory(
                workspaceId = request.scope.workspaceId,
                spaceId = arguments.optString("spaceId").ifBlank { request.scope.workspaceId },
                capability = api.method
            )
            ToolPackageHostResourceKind.NONE -> ToolPackageHostResource.None(api.method)
        }
        return ToolPackageHostResourceResolution.Resolved(resource)
    }

    private fun refused(code: ToolPackageErrorCode): ToolPackageHostResourceResolution =
        ToolPackageHostResourceResolution.Refused(
            ToolPackageError(code, "The test handler could not resolve the requested resource.")
        )
}


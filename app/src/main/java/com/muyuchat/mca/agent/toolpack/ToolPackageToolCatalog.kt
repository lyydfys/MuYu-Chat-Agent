package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.tools.AgentToolJson
import com.muyuchat.mca.agent.tools.AgentToolResults
import com.muyuchat.mca.agent.tools.runBuiltInTool
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Projects an executable package's exported tools into the frozen [AgentToolCatalog] surface.
 *
 * This is the only place a package becomes an [AgentToolExecutor]. The projection is fail-closed:
 * a package that declares a write-capable host API is refused because the production catalog has no
 * per-call approval owner injected, and the executor result shape must never report
 * `possibleSideEffects=false` after a write. Read-only packages (workspace/memory reads and
 * pure computation) are the supported minimal loop.
 *
 * [approval] is carried to every execution; the bridge asks it for exactly one decision per nested
 * write call. It defaults to the fail-closed deny-all port.
 *
 * Descriptor identity uses [ExecutableToolPackageManifest.runtimeToolKey]; the original package id
 * remains in the bundle, every host-call audit record and the UI source label derivation.
 */
class ToolPackageToolCatalog private constructor(
    private val byKey: Map<String, AgentToolExecutor>
) : AgentToolCatalog {

    override fun find(toolKey: String): AgentToolExecutor? = byKey[toolKey]

    override fun snapshot(): List<AgentToolDescriptor> =
        byKey.keys.sorted().map { key -> requireNotNull(byKey[key]).descriptor }

    companion object {
        fun project(
            bundle: ExecutableToolPackage,
            runtime: ToolPackageScriptRuntime,
            approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
            allowWriteCapability: Boolean = false
        ): ToolPackageToolCatalog {
            if (bundle.manifest.declaresWriteCapability && !allowWriteCapability) {
                throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED,
                        "Write-capable packages require the per-call approval pipeline before projection."
                    )
                )
            }
            val byKey = LinkedHashMap<String, AgentToolExecutor>(bundle.manifest.tools.size)
            for (tool in bundle.manifest.tools) {
                val descriptor = descriptorFor(bundle, tool, allowWriteCapability)
                if (byKey.put(descriptor.toolKey, ToolPackageToolExecutor(descriptor, bundle, tool, runtime, approval)) != null) {
                    throw ToolPackageException(
                        ToolPackageError(
                            ToolPackageErrorCode.MANIFEST_DUPLICATE_TOOL,
                            "The package projects two tools to the same runtime key."
                        )
                    )
                }
            }
            return ToolPackageToolCatalog(byKey)
        }

        private fun descriptorFor(
            bundle: ExecutableToolPackage,
            tool: ExecutableToolPackageTool,
            allowWriteCapability: Boolean
        ): AgentToolDescriptor {
            val descriptor = try {
                AgentToolDescriptor(
                    toolKey = bundle.runtimeToolKey(tool.name),
                    version = bundle.versionIdentity,
                    displayName = tool.displayName,
                    description = tool.description,
                    schemaJson = tool.inputSchemaJson,
                    schemaHash = AgentToolJson.sha256Hex(tool.inputSchemaJson),
                    // A package that declares a WRITE host API may perform a nested write after its
                    // own approval. Its exported tool must never be advertised as READ_ONLY: the
                    // descriptor is what the run pipeline shows and what the tool-level approval
                    // binds.
                    sideEffect = if (allowWriteCapability && bundle.manifest.declaresWriteCapability) {
                        AgentToolSideEffect.EXTERNAL_SIDE_EFFECT
                    } else {
                        AgentToolSideEffect.READ_ONLY
                    },
                    dataClass = AgentDataClass.PRIVATE,
                    timeoutMillis = tool.timeoutMillis
                )
            } catch (_: IllegalArgumentException) {
                throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.TOOL_KEY_UNSUPPORTED,
                        "The package id or tool entry cannot form a valid runtime tool key."
                    )
                )
            }
            return descriptor
        }
    }
}

/**
 * Executor for one exported package tool.
 *
 * It reuses the shared built-in execution envelope (tool/version/schema binding, cancellation
 * propagation) and adds the package-specific steps: create a fresh execution instance, build the
 * frozen run scope, run the isolated engine under the per-tool timeout and map the typed outcome to
 * the contract result. Workspace-capable packages still require the task's workspace read grant;
 * memory reads are enforced by their host handler, which owns the memory-space grant.
 */
internal class ToolPackageToolExecutor(
    override val descriptor: AgentToolDescriptor,
    private val bundle: ExecutableToolPackage,
    private val tool: ExecutableToolPackageTool,
    private val runtime: ToolPackageScriptRuntime,
    private val approval: ToolPackageHostApprovalPort
) : AgentToolExecutor {

    private val requiresWorkspaceReadGrant: Boolean = bundle.manifest.declaresWorkspaceRead

    override suspend fun execute(
        command: FrozenToolCommand,
        context: AgentToolContext
    ): AgentToolResult = runBuiltInTool(
        command = command,
        descriptor = descriptor,
        context = context,
        requiresReadGrant = requiresWorkspaceReadGrant
    ) {
        val scope = ToolPackageExecutionScope(
            invocationId = command.invocationId,
            runId = context.runId,
            runEpoch = context.runEpoch,
            taskId = context.taskId,
            workspaceId = context.workspaceId,
            policyVersion = context.policyVersion
        )
        coroutineScope {
            val execution = runtime.open(bundle).start(
                this,
                ToolPackageExecutionRequest(
                    scope = scope,
                    tool = tool,
                    argumentsJson = command.normalizedArgumentsJson,
                    approval = approval
                )
            )
            when (val outcome = execution.await()) {
                is ToolPackageExecutionResult.Succeeded -> AgentToolResults.success(
                    command = command,
                    descriptor = descriptor,
                    summary = "Package tool '${tool.displayName}' completed.",
                    data = JSONObject(outcome.resultJson)
                )
                is ToolPackageExecutionResult.Failed -> AgentToolResults.failure(
                    command = command,
                    descriptor = descriptor,
                    errorCode = outcome.error.code.agentCode,
                    summary = outcome.error.safeMessage,
                    possibleSideEffects = outcome.possibleSideEffects
                )
                is ToolPackageExecutionResult.Denied -> AgentToolResults.denied(
                    command = command,
                    descriptor = descriptor,
                    errorCode = outcome.error.code.agentCode,
                    summary = outcome.error.safeMessage
                )
                ToolPackageExecutionResult.Cancelled -> AgentToolResults.cancelled(command, descriptor)
            }
        }
    }
}

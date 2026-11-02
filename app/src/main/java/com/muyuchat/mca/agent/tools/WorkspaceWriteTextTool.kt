package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONObject

/**
 * `workspace.write_text`: create-only or hash-checked UTF-8 text write below `artifacts/`.
 *
 * The write still goes through the normal ASK approval path: this executor only sees an already
 * approved [FrozenToolCommand], and the file port re-checks the precondition atomically. The
 * executor adds the invariants it can still observe:
 *
 * - the logical path was canonicalised before approval and must still be a file below `artifacts/`;
 * - the content must be well-formed UTF-8 text inside the 32 KiB write budget;
 * - `expectedOldHash` must be stated explicitly (`null` means create-only) and, when present, must
 *   be a lowercase SHA-256 so the port can perform a compare-and-swap instead of a blind overwrite.
 */
class WorkspaceWriteTextTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentExtendedToolDescriptors.WORKSPACE_WRITE_TEXT

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("path", "content", "expectedOldHash"))
            val path = AgentToolArguments.writePath(
                AgentToolArguments.requiredText(fields, "path", 512)
            )
            AgentTextWriteSupport.requireCanonicalArtifactPath(path)
            val content = AgentToolArguments.requiredText(fields, "content", Int.MAX_VALUE)
            AgentTextWriteSupport.requireWithinWriteBudget(content, "content")
            val expectedOldHash = AgentToolArguments.requiredNullableText(fields, "expectedOldHash", 64)
            if (expectedOldHash != null && !AgentTextWriteSupport.isSha256Hex(expectedOldHash)) {
                throw AgentToolInputException(
                    "expectedOldHash must be a lowercase SHA-256 string or null."
                )
            }

            when (
                val written = filePort.writeArtifact(
                    workspaceId = context.workspaceId,
                    taskId = context.taskId,
                    invocationId = command.invocationId,
                    logicalPath = path,
                    content = content,
                    expectedOldHash = expectedOldHash,
                    policyVersion = command.policyVersion
                )
            ) {
                is AgentFileResult.Failure -> AgentToolResults.portFailure(command, descriptor, written)
                is AgentFileResult.Ok -> {
                    val value = written.value
                    if (!value.logicalPath.startsWith("artifacts/")) {
                        return@runBuiltInTool AgentToolResults.failure(
                            command,
                            descriptor,
                            "INTERNAL_INVARIANT",
                            "The file port committed a write outside artifacts/."
                        )
                    }
                    val data = JSONObject()
                        .put("path", value.logicalPath)
                        .put("artifactId", value.artifactId)
                        .put("sizeBytes", value.sizeBytes)
                        .put("sha256", value.sha256)
                        .put("created", expectedOldHash == null)
                        .put("expectedOldHash", expectedOldHash ?: JSONObject.NULL)
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = if (expectedOldHash == null) {
                            "Created a ${value.sizeBytes}-byte text file at ${value.logicalPath}."
                        } else {
                            "Replaced the text file at ${value.logicalPath} (${value.sizeBytes} bytes)."
                        },
                        data = data,
                        artifactIds = listOf(value.artifactId)
                    )
                }
            }
        }
}

package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentBuiltInToolArgumentNormalizer
import org.json.JSONObject

/**
 * `artifact.write_text`: create-only or hash-checked artifact write under `artifacts/`.
 *
 * The port performs staging and the atomic commit and re-checks the precondition; this executor
 * only freezes the invocation identity (`invocationId`/`policyVersion`) and translates the port
 * result. `expectedOldHash=null` is never an overwrite: it is create-only, and an existing logical
 * path is reported as PRECONDITION_CHANGED.
 */
class ArtifactWriteTextTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentToolDescriptors.ARTIFACT_WRITE_TEXT

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("path", "content", "expectedOldHash"))
            val path = AgentToolArguments.writePath(
                AgentToolArguments.requiredText(fields, "path", 512)
            )
            val content = AgentToolArguments.requiredText(fields, "content", Int.MAX_VALUE)
            if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
                throw AgentToolInputException("Artifact content exceeds the 32 KiB UTF-8 limit.")
            }
            val expectedOldHash = AgentToolArguments.requiredNullableText(fields, "expectedOldHash", 64)
            if (expectedOldHash != null && !SHA256_HEX.matches(expectedOldHash)) {
                throw AgentToolInputException("expectedOldHash must be a lowercase SHA-256 string or null.")
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
                    if (value.logicalPath.isBlank() || !value.logicalPath.startsWith("artifacts/")) {
                        return@runBuiltInTool AgentToolResults.failure(
                            command,
                            descriptor,
                            "INTERNAL_INVARIANT",
                            "The file port committed an artifact outside artifacts/."
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
                        command,
                        descriptor,
                        if (expectedOldHash == null) {
                            "Created a ${value.sizeBytes}-byte artifact at ${value.logicalPath}."
                        } else {
                            "Overwrote the artifact at ${value.logicalPath} (${value.sizeBytes} bytes)."
                        },
                        data,
                        artifactIds = listOf(value.artifactId)
                    )
                }
            }
        }

    private companion object {
        const val MAX_CONTENT_BYTES = AgentBuiltInToolArgumentNormalizer.MAX_WRITE_CONTENT_BYTES
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

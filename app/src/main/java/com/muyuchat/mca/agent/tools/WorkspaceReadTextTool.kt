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
 * `workspace.read_text`: one UTF-8 byte window of a workspace text file.
 *
 * Byte-offset and character-boundary semantics belong to the file port, the only component that
 * sees raw bytes: it reports a non-boundary offset as TOOL_INPUT_INVALID, shrinks the window end to
 * a whole character and reports LIMIT_TOO_SMALL when not even the first character fits. The
 * executor adds the invocation result shape and verifies the returned window is self-consistent.
 */
class WorkspaceReadTextTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentToolDescriptors.WORKSPACE_READ_TEXT

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = true) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("path", "offset", "limit"))
            val path = AgentToolArguments.readPath(
                AgentToolArguments.requiredText(fields, "path", 512)
            )
            val offset = AgentToolArguments.optionalInteger(fields, "offset", 0L, 0L, Long.MAX_VALUE)
            val limit = AgentToolArguments.optionalInteger(fields, "limit", 4_096L, 1L, 16_384L).toInt()

            when (val read = filePort.readText(context.workspaceId, context.taskId, path, offset, limit)) {
                is AgentFileResult.Failure -> AgentToolResults.portFailure(command, descriptor, read)
                is AgentFileResult.Ok -> {
                    val value = read.value
                    val returnedBytes = value.content.toByteArray(Charsets.UTF_8).size
                    if (value.offsetBytes != offset ||
                        value.nextOffsetBytes != offset + returnedBytes ||
                        value.nextOffsetBytes > value.totalBytes ||
                        returnedBytes > limit
                    ) {
                        return@runBuiltInTool AgentToolResults.failure(
                            command,
                            descriptor,
                            "INTERNAL_INVARIANT",
                            "The file port returned an inconsistent read window."
                        )
                    }
                    var content = value.content
                    var nextOffset = value.nextOffsetBytes
                    val data = JSONObject()
                        .put("path", path)
                        .put("offsetBytes", offset)
                        .put("returnedBytes", returnedBytes)
                        .put("nextOffsetBytes", nextOffset)
                        .put("eof", value.eof)
                        .put("totalBytes", value.totalBytes)
                        .put("sha256", value.sha256)
                        .put("content", content)

                    val shrink: (JSONObject) -> Boolean = { target ->
                        val codePoints = content.codePointCount(0, content.length)
                        if (codePoints <= 1) {
                            false
                        } else {
                            content = content.substring(0, content.offsetByCodePoints(0, codePoints / 2))
                            nextOffset = offset + content.toByteArray(Charsets.UTF_8).size
                            target.put("content", content)
                            target.put("returnedBytes", content.toByteArray(Charsets.UTF_8).size)
                            target.put("nextOffsetBytes", nextOffset)
                            target.put("eof", nextOffset >= value.totalBytes)
                            target.put("contentTruncated", true)
                            true
                        }
                    }

                    AgentToolResults.success(
                        command,
                        descriptor,
                        "Read a bounded byte range from $path (offset $offset).",
                        data,
                        shrink = shrink
                    )
                }
            }
        }
}

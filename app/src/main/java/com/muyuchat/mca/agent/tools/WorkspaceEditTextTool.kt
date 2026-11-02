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
 * `workspace.edit_text`: replace exactly one occurrence of `oldText` with `newText`.
 *
 * The edit is a compare-and-swap, not a blind rewrite:
 *
 * 1. the executor loads the current file through the port (bounded windows, 32 KiB maximum);
 * 2. `expectedOldHash` must equal the loaded file's SHA-256, so a change since the approval is a
 *    typed `PRECONDITION_CHANGED` failure instead of an overwrite of somebody else's content;
 * 3. `oldText` must occur exactly once — zero matches and ambiguous matches both fail without
 *    touching the file;
 * 4. the resulting content must stay inside the 32 KiB UTF-8 write budget, and the port is handed
 *    the same hash it just observed so a concurrent writer between read and commit still loses.
 *
 * Cancellation is propagated by the shared envelope; partial content is never committed because the
 * commit happens inside the file port after the whole payload is known.
 */
class WorkspaceEditTextTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentExtendedToolDescriptors.WORKSPACE_EDIT_TEXT

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = true) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("path", "oldText", "newText", "expectedOldHash"))
            val path = AgentToolArguments.writePath(
                AgentToolArguments.requiredText(fields, "path", 512)
            )
            AgentTextWriteSupport.requireCanonicalArtifactPath(path)
            val oldText = AgentToolArguments.requiredText(fields, "oldText", 8_192)
            if (oldText.isEmpty()) throw AgentToolInputException("oldText must not be empty.")
            AgentTextWriteSupport.requireValidText(oldText, "oldText")
            val newText = AgentToolArguments.requiredText(fields, "newText", 8_192)
            AgentTextWriteSupport.requireValidText(newText, "newText")
            val expectedOldHash = AgentToolArguments.requiredText(fields, "expectedOldHash", 64)
            if (!AgentTextWriteSupport.isSha256Hex(expectedOldHash)) {
                throw AgentToolInputException("expectedOldHash must be a lowercase SHA-256 string.")
            }

            val current = when (val loaded = loadCurrentContent(context, path)) {
                is CurrentContent.Loaded -> loaded
                is CurrentContent.Rejected -> {
                    return@runBuiltInTool AgentToolResults.failure(
                        command,
                        descriptor,
                        loaded.errorCode,
                        loaded.safeMessage
                    )
                }
            }
            if (current.sha256 != expectedOldHash) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "PRECONDITION_CHANGED",
                    "The file changed since this edit was approved; re-read it before editing."
                )
            }
            val firstMatch = current.content.indexOf(oldText)
            if (firstMatch < 0) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "TOOL_EDIT_NOT_FOUND",
                    "The requested oldText does not occur in ${current.path}."
                )
            }
            val secondMatch = current.content.indexOf(oldText, firstMatch + oldText.length)
            if (secondMatch >= 0) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "TOOL_EDIT_AMBIGUOUS",
                    "The requested oldText occurs more than once; the edit was not applied."
                )
            }
            val updated = current.content.substring(0, firstMatch) + newText +
                current.content.substring(firstMatch + oldText.length)
            if (AgentTextWriteSupport.utf8Size(updated) > AgentTextWriteSupport.MAX_CONTENT_BYTES) {
                return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "TOOL_INPUT_INVALID",
                    "The edited file would exceed the 32 KiB UTF-8 write budget."
                )
            }

            when (
                val written = filePort.writeArtifact(
                    workspaceId = context.workspaceId,
                    taskId = context.taskId,
                    invocationId = command.invocationId,
                    logicalPath = path,
                    content = updated,
                    expectedOldHash = current.sha256,
                    policyVersion = command.policyVersion
                )
            ) {
                is AgentFileResult.Failure -> AgentToolResults.portFailure(command, descriptor, written)
                is AgentFileResult.Ok -> {
                    val value = written.value
                    val data = JSONObject()
                        .put("path", value.logicalPath)
                        .put("artifactId", value.artifactId)
                        .put("sizeBytes", value.sizeBytes)
                        .put("sha256", value.sha256)
                        .put("previousSha256", expectedOldHash)
                        .put("replacedChars", oldText.length)
                        .put("insertedChars", newText.length)
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = "Edited ${value.logicalPath} (${value.sizeBytes} bytes).",
                        data = data,
                        artifactIds = listOf(value.artifactId)
                    )
                }
            }
        }

    /** Loads a whole workspace text file through bounded read windows. */
    private sealed interface CurrentContent {
        data class Loaded(val path: String, val content: String, val sha256: String) : CurrentContent

        data class Rejected(val errorCode: String, val safeMessage: String) : CurrentContent
    }

    private suspend fun loadCurrentContent(context: AgentToolContext, path: String): CurrentContent {
        val builder = StringBuilder()
        var offset = 0L
        var sha256: String? = null
        repeat(MAX_READ_WINDOWS) {
            when (
                val window = filePort.readText(
                    workspaceId = context.workspaceId,
                    taskId = context.taskId,
                    path = path,
                    offsetBytes = offset,
                    limitBytes = AgentTextWriteSupport.EDIT_READ_WINDOW_BYTES
                )
            ) {
                is AgentFileResult.Failure -> return CurrentContent.Rejected(
                    AgentToolResults.toolErrorCode(window.errorCode),
                    window.safeMessage.ifBlank { "The file could not be read." }
                )
                is AgentFileResult.Ok -> {
                    val value = window.value
                    if (value.offsetBytes != offset) {
                        return CurrentContent.Rejected(
                            "INTERNAL_INVARIANT",
                            "The file port returned an inconsistent read window."
                        )
                    }
                    if (sha256 != null && value.sha256 != sha256) {
                        return CurrentContent.Rejected(
                            "PRECONDITION_CHANGED",
                            "The file changed while it was being read."
                        )
                    }
                    sha256 = value.sha256
                    builder.append(value.content)
                    offset = value.nextOffsetBytes
                    if (value.eof) {
                        if (offset > AgentTextWriteSupport.MAX_EDIT_FILE_BYTES) {
                            return CurrentContent.Rejected(
                                "TOOL_FILE_TOO_LARGE",
                                "The file is larger than the 32 KiB edit budget; use workspace.write_text."
                            )
                        }
                        return CurrentContent.Loaded(path, builder.toString(), value.sha256)
                    }
                    if (offset >= AgentTextWriteSupport.MAX_EDIT_FILE_BYTES) {
                        return CurrentContent.Rejected(
                            "TOOL_FILE_TOO_LARGE",
                            "The file is larger than the 32 KiB edit budget; use workspace.write_text."
                        )
                    }
                }
            }
        }
        return CurrentContent.Rejected(
            "TOOL_FILE_TOO_LARGE",
            "The file needs more read windows than the edit budget allows."
        )
    }

    private companion object {
        const val MAX_READ_WINDOWS = 8
    }
}

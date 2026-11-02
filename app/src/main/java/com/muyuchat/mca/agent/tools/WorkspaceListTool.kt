package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentLogicalPaths
import org.json.JSONArray
import org.json.JSONObject

/**
 * `workspace.list`: bounded, non-recursive directory listing under the task workspace.
 *
 * Directories are read-only; the port owns the directory version behind `cursor`, so a cursor
 * issued after the directory changed is reported as CURSOR_STALE instead of silently skipping an
 * entry. Entries are sorted by their logical path before they are returned.
 */
class WorkspaceListTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentToolDescriptors.WORKSPACE_LIST

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = true) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("path", "limit", "cursor"))
            val path = AgentToolArguments.readPath(
                AgentToolArguments.optionalText(
                    fields,
                    "path",
                    AgentLogicalPaths.INPUTS_ROOT,
                    AgentLogicalPaths.MAX_PATH_CHARS
                )
            )
            val limit = AgentToolArguments.optionalInteger(fields, "limit", 100L, 1L, 200L).toInt()
            val cursor = AgentToolArguments.optionalNullableText(fields, "cursor", 1_024)

            when (val listed = filePort.list(context.workspaceId, context.taskId, path, limit, cursor)) {
                is AgentFileResult.Failure -> AgentToolResults.portFailure(command, descriptor, listed)
                is AgentFileResult.Ok -> {
                    val entries = listed.value.entries.sortedBy { it.logicalPath }
                    if (entries.size > limit) {
                        return@runBuiltInTool AgentToolResults.failure(
                            command,
                            descriptor,
                            "INTERNAL_INVARIANT",
                            "The file port returned more entries than the requested page limit."
                        )
                    }
                    val data = JSONObject()
                        .put("path", path)
                        .put("limit", limit)
                        .put("cursor", cursor ?: JSONObject.NULL)
                        .put("count", entries.size)
                        .put("nextCursor", listed.value.nextCursor ?: JSONObject.NULL)
                        .put(
                            "entries",
                            JSONArray().also { array ->
                                entries.forEach { entry ->
                                    array.put(
                                        JSONObject()
                                            .put("path", entry.logicalPath)
                                            .put("kind", entry.kind.name)
                                            .put("sizeBytes", entry.sizeBytes)
                                            .put("sha256", entry.sha256 ?: JSONObject.NULL)
                                    )
                                }
                            }
                        )
                    AgentToolResults.success(
                        command,
                        descriptor,
                        "Listed ${entries.size} direct entr${if (entries.size == 1) "y" else "ies"} under $path.",
                        data,
                        shrink = listShrink
                    )
                }
            }
        }

    private val listShrink: (JSONObject) -> Boolean = { data ->
        val entries = data.optJSONArray("entries")
        if (entries == null || entries.length() == 0) {
            false
        } else {
            entries.remove(entries.length() - 1)
            data.put("count", entries.length())
            data.put("nextCursor", JSONObject.NULL)
            data.put("pageIncomplete", true)
            true
        }
    }
}

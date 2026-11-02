package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentSearchMatch
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
 * `workspace.search_text`: literal, case-sensitive search over a workspace path.
 *
 * The query is never compiled as a regular expression. The port owns the 32 MiB total / 8 MiB
 * per-file scan budget and returns `truncated` plus `scannedBytes`; this executor only bounds the
 * snippets, orders matches by path then line number, and never reports `complete=true` when any
 * budget or page cap was reached.
 */
class WorkspaceSearchTextTool(private val filePort: AgentWorkspaceFilePort) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentToolDescriptors.WORKSPACE_SEARCH_TEXT

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = true) {
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("query", "path", "maxMatches"))
            val query = AgentToolArguments.requiredText(fields, "query", 256)
            if (query.isEmpty()) throw AgentToolInputException("Search query must contain at least one character.")
            if (query.any { it < ' ' }) throw AgentToolInputException("Search query must not contain control characters.")
            val path = AgentToolArguments.readPath(
                AgentToolArguments.optionalText(
                    fields,
                    "path",
                    AgentLogicalPaths.INPUTS_ROOT,
                    AgentLogicalPaths.MAX_PATH_CHARS
                )
            )
            val maxMatches = AgentToolArguments.optionalInteger(fields, "maxMatches", 20L, 1L, 100L).toInt()

            when (val searched = filePort.searchText(context.workspaceId, context.taskId, query, path, maxMatches)) {
                is AgentFileResult.Failure -> AgentToolResults.portFailure(command, descriptor, searched)
                is AgentFileResult.Ok -> {
                    val ordered = searched.value.matches
                        .sortedWith(compareBy<AgentSearchMatch>({ it.logicalPath }, { it.lineNumber }))
                    val bounded = ordered.map {
                        AgentSearchMatch(it.logicalPath, it.lineNumber, boundedSnippet(it.snippet))
                    }
                    val snippetTrimmed = bounded.indices.any { bounded[it].snippet != ordered[it].snippet }
                    val dropped = bounded.size > maxMatches
                    val page = if (dropped) bounded.take(maxMatches) else bounded
                    val truncated = searched.value.truncated || dropped || snippetTrimmed
                    val data = JSONObject()
                        .put("query", query)
                        .put("path", path)
                        .put("maxMatches", maxMatches)
                        .put("matchCount", page.size)
                        .put(
                            "matches",
                            JSONArray().also { array ->
                                page.forEach { match ->
                                    array.put(
                                        JSONObject()
                                            .put("path", match.logicalPath)
                                            .put("line", match.lineNumber)
                                            .put("snippet", match.snippet)
                                    )
                                }
                            }
                        )
                        .put("complete", !truncated)
                        .put("truncated", truncated)
                        .put("scannedBytes", searched.value.scannedBytes)
                        .put(
                            "scanLimits",
                            JSONObject()
                                .put("totalBytes", TOTAL_SCAN_BYTES)
                                .put("perFileBytes", PER_FILE_SCAN_BYTES)
                        )
                    val scanState = if (truncated) "partial scan." else "complete scan."
                    AgentToolResults.success(
                        command,
                        descriptor,
                        "Searched for the literal query under $path; ${page.size} match(es), $scanState",
                        data,
                        truncated = truncated,
                        shrink = searchShrink
                    )
                }
            }
        }

    private fun boundedSnippet(snippet: String): String =
        if (snippet.length <= MAX_SNIPPET_CHARS) {
            snippet
        } else {
            snippet.take(MAX_SNIPPET_CHARS - 1) + "\u2026"
        }

    private val searchShrink: (JSONObject) -> Boolean = { data ->
        val matches = data.optJSONArray("matches")
        if (matches == null || matches.length() == 0) {
            false
        } else {
            matches.remove(matches.length() - 1)
            data.put("matchCount", matches.length())
            data.put("truncated", true)
            data.put("complete", false)
            true
        }
    }

    private companion object {
        const val MAX_SNIPPET_CHARS = 240
        const val TOTAL_SCAN_BYTES = 32L * 1024 * 1024
        const val PER_FILE_SCAN_BYTES = 8L * 1024 * 1024
    }
}

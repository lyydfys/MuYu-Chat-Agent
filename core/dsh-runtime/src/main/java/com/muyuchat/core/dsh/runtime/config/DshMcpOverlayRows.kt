package com.muyuchat.core.dsh.runtime.config

/**
 * The official `@deepseek-ai/dsh-mcp-client` patch rows MCA composes from
 * [DshMcpServerConfigLoader]'s accepted servers.
 *
 * Shape (one row per server, exactly as the official package documents it):
 *
 * ```yaml
 * - id: mcp-<serverName>
 *   # MCA-owned MCP row; ...
 *   name: '@deepseek-ai/dsh-mcp-client'
 *   config:
 *     transport: stdio
 *     serverName: <serverName>
 *     command: "..."
 *     args: [...]
 *     env: {...}
 * ```
 *
 * Rules kept deliberately small:
 * - only the documented keys are emitted (`transport`, `serverName`, `command`/`args`/`env` or
 *   `url`/`headers`); optional official fields are left at their official defaults instead of being
 *   guessed here;
 * - the MCA declaration's `capabilities` expectation is **not** emitted: the official
 *   `mcp-client` config schema has no such key. Resources are served by the official
 *   `dsh-mcp-resources` model tools, not by anything this renderer forwards;
 * - an empty `args`/`env`/`headers` map is omitted rather than emitted as a YAML null, because a
 *   bare key would fail the official configuration schema;
 * - every scalar is double-quoted with backslash/quote escaping, so no declaration value can add
 *   YAML structure.
 *
 * Ownership marker: [OWNED_ROW_MARKER] is written inside the row so a later generation can tell an
 * MCA-generated row from a hand-written `dsh-mcp-client` row in the same file. A removed server then
 * drops its generated row, while a user row (no marker) is still preserved verbatim by the merge.
 */
internal object DshMcpOverlayRows {

    const val MCP_CLIENT_PACKAGE = "@deepseek-ai/dsh-mcp-client"

    /** Prefix of every MCA-generated MCP row id; cannot collide with the static managed row ids. */
    const val ROW_ID_PREFIX = "mcp-"

    /**
     * Comment inside each generated row. It is both the human explanation and the ownership proof
     * used by [ownedRowIds]; a row that lost this comment is treated as user-owned from then on.
     */
    const val OWNED_ROW_MARKER =
        "# MCA-owned MCP row; the source is " + DshMcpServerConfigLoader.FILE_NAME + " in this home."

    /** Empty string for no servers, so an absent declaration file cannot change the overlay bytes. */
    fun render(servers: List<DshMcpServerSpec>): String {
        if (servers.isEmpty()) return ""
        // The official patch language distinguishes an update (`- id: existing`) from an insert
        // (`- insert:` with indented rows). A top-level `- id: mcp-*` is read as an update of an
        // entry that does not exist yet ("entry ... not found"), so every generated MCP row lives
        // in its own explicit insert block.
        return buildString {
            appendLine("- insert:")
            for (server in servers) {
                appendLine("    - id: ${server.rowId}")
                appendLine("      $OWNED_ROW_MARKER")
                appendLine("      name: '$MCP_CLIENT_PACKAGE'")
                appendLine("      config:")
                when (server) {
                    is DshMcpStdioServer -> {
                        appendLine("        transport: ${DshMcpServerConfigLoader.TRANSPORT_STDIO}")
                        appendLine("        serverName: ${server.serverName}")
                        appendLine("        command: ${yamlScalar(server.command)}")
                        appendSequence("        args:", server.args)
                        appendMapping("        env:", server.env)
                    }
                    is DshMcpHttpServer -> {
                        appendLine("        transport: ${DshMcpServerConfigLoader.TRANSPORT_STREAMABLE_HTTP}")
                        appendLine("        serverName: ${server.serverName}")
                        appendLine("        url: ${yamlScalar(server.url)}")
                        appendMapping("        headers:", server.headers)
                    }
                }
            }
        }
    }

    /**
     * Row ids of previously generated MCA-owned MCP rows in [previous] overlay text.
     *
     * Used by the merge so a server removed from the declaration file also loses its row, without
     * ever matching a hand-written `dsh-mcp-client` row (those carry no [OWNED_ROW_MARKER]).
     */
    fun ownedRowIds(previous: String?): Set<String> {
        if (previous.isNullOrBlank()) return emptySet()
        val blocks = DshManagedOverlayMerge.parse(previous) ?: return emptySet()
        // Generated rows live inside an insert block (id null), so ownership is proven by the
        // marker plus the emitted row ids rather than by a top-level block id.
        return blocks.filter { it.raw.contains(OWNED_ROW_MARKER) }
            .flatMap { block ->
                Regex("- id: (${ROW_ID_PREFIX}[A-Za-z0-9_-]+)")
                    .findAll(block.raw)
                    .map { it.groupValues[1] }
                    .toList()
            }
            .toSet()
    }

    private fun StringBuilder.appendSequence(heading: String, values: List<String>) {
        if (values.isEmpty()) return
        appendLine(heading)
        for (value in values) appendLine("          - ${yamlScalar(value)}")
    }

    private fun StringBuilder.appendMapping(heading: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        appendLine(heading)
        for ((key, value) in values) appendLine("          $key: ${yamlScalar(value)}")
    }
}

/** YAML double-quoted scalar with backslash and quote escaping; the seeder's single quoting rule. */
internal fun yamlScalar(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

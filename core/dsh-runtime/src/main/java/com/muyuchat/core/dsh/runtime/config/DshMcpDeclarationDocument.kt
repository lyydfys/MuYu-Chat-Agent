package com.muyuchat.core.dsh.runtime.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes [servers] into the exact `mcp-servers.json` shape the production reader accepts.
 *
 * Only the keys [DshMcpServerConfigLoader] defines are emitted; empty `args`/`env`/`headers` are
 * omitted because the reader treats an absent collection and an empty one identically. Every value
 * is encoded by the JSON encoder, so a caller can never inject document structure (and an invalid
 * value is then refused by the reader instead of being written).
 *
 * Version rule: a document with no `capabilities` is written as schema version 1 (the original
 * format, readable by older builds); once any entry declares capabilities it is written as
 * [DshMcpServerConfigLoader.CAPABILITY_SCHEMA_VERSION], so an older strict reader fails closed on
 * the version instead of guessing about the field.
 *
 * This is not a second configuration format: it is the writer half of the one schema in
 * [DshMcpServerConfigLoader], and [DshMcpDeclarationStore] only publishes text the reader accepts.
 */
internal object DshMcpDeclarationDocument {
    fun render(servers: List<DshMcpServerSpec>): String {
        val array = JSONArray()
        for (server in servers) {
            val entry = JSONObject()
            when (server) {
                is DshMcpStdioServer -> {
                    entry.put("transport", DshMcpServerConfigLoader.TRANSPORT_STDIO)
                    entry.put("serverName", server.serverName)
                    entry.put("command", server.command)
                    if (server.args.isNotEmpty()) entry.put("args", JSONArray(server.args))
                    if (server.env.isNotEmpty()) entry.put("env", JSONObject(server.env))
                    putCapabilities(entry, server.capabilities)
                }
                is DshMcpHttpServer -> {
                    entry.put("transport", DshMcpServerConfigLoader.TRANSPORT_STREAMABLE_HTTP)
                    entry.put("serverName", server.serverName)
                    entry.put("url", server.url)
                    if (server.headers.isNotEmpty()) entry.put("headers", JSONObject(server.headers))
                    putCapabilities(entry, server.capabilities)
                }
            }
            array.put(entry)
        }
        return JSONObject()
            .put(
                "version",
                if (servers.any { it.capabilities.isNotEmpty() }) {
                    DshMcpServerConfigLoader.CAPABILITY_SCHEMA_VERSION
                } else {
                    DshMcpServerConfigLoader.SCHEMA_VERSION
                }
            )
            .put("servers", array)
            .toString(2) + "\n"
    }

    /**
     * Declaration-only expectation list. The official `dsh-mcp-client` row does not accept this
     * key, so [DshMcpOverlayRows] never forwards it; rendering it here only keeps the declaration
     * round-trip stable (the store refuses a document whose re-render differs from its parse).
     */
    private fun putCapabilities(entry: JSONObject, capabilities: Set<DshMcpCapability>) {
        if (capabilities.isEmpty()) return
        entry.put(
            "capabilities",
            JSONArray(capabilities.sortedBy { it.ordinal }.map { it.token })
        )
    }
}

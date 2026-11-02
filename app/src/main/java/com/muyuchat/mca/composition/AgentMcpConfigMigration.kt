package com.muyuchat.mca.composition

import java.io.File

/**
 * One-time repair for the historical double-nested MCP config path.
 *
 * `AgentMcpServerStore` already appends `agent/mcp`; an older composition passed a pre-joined
 * `files/agent/mcp`, so management and runs read different files. When the correct file does not
 * exist yet, copy the legacy one so user-authored servers survive the upgrade.
 */
object AgentMcpConfigMigration {

    fun migrateIfNeeded(filesDir: File): Boolean {
        val current = File(filesDir, "agent/mcp/servers.json")
        if (current.exists()) return false
        val legacy = File(filesDir, "agent/mcp/agent/mcp/servers.json")
        if (!legacy.isFile) return false
        return runCatching {
            current.parentFile?.mkdirs()
            legacy.copyTo(current, overwrite = false)
            true
        }.getOrDefault(false)
    }
}

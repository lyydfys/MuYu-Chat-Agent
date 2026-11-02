package com.muyuchat.mca.composition

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpConfigMigrationTest {

    @Test
    fun legacyDoubleNestedConfigIsCopiedOnce() {
        val root = Files.createTempDirectory("mcp-migrate").toFile()
        val legacy = root.resolve("agent/mcp/agent/mcp/servers.json")
        legacy.parentFile.mkdirs()
        legacy.writeText("""{"schemaVersion":2,"servers":[]}""")
        val current = root.resolve("agent/mcp/servers.json")

        assertTrue(AgentMcpConfigMigration.migrateIfNeeded(root))
        assertTrue(current.isFile)
        assertEquals(legacy.readText(), current.readText())
        assertFalse("a second call must not overwrite the migrated file", AgentMcpConfigMigration.migrateIfNeeded(root))
    }

    @Test
    fun existingCurrentConfigWins() {
        val root = Files.createTempDirectory("mcp-migrate-current").toFile()
        val legacy = root.resolve("agent/mcp/agent/mcp/servers.json")
        legacy.parentFile.mkdirs()
        legacy.writeText("legacy")
        val current = root.resolve("agent/mcp/servers.json")
        current.parentFile.mkdirs()
        current.writeText("current")

        assertFalse(AgentMcpConfigMigration.migrateIfNeeded(root))
        assertEquals("current", current.readText())
    }
    @Test
    fun compositionPassesTheUnjoinedFilesDir() {
        val source = java.io.File(
            "src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt"
        ).readText()
        assertTrue(
            "AgentMcpServerStore already appends agent/mcp; a pre-joined path creates a split-brain config",
            source.contains("AgentMcpServerStore(application.filesDir)")
        )
    }

}

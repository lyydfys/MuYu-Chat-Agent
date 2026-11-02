package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshotSkill
import com.muyuchat.mca.agent.extensions.skills.AgentSkillToolPattern
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult.Assembled
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult.Rejected
import com.muyuchat.mca.agent.tools.AgentToolCatalogConflict.DuplicateSkillId
import com.muyuchat.mca.agent.tools.AgentToolCatalogConflict.DuplicateToolKey
import com.muyuchat.mca.agent.tools.AgentToolCatalogConflict.SchemaConflict
import com.muyuchat.mca.agent.tools.AgentToolCatalogWarning.SkillToolMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogAssemblerTest {
    private val assembler = AgentToolCatalogAssembler()
    private val builtIns = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())

    @Test
    fun `builtin only assembly preserves descriptor identity and order`() {
        val result = assembled(assemble())
        assertEquals(
            builtIns.snapshot().map { it.toolKey },
            result.catalog.snapshot().map { it.toolKey }
        )
        assertEquals(builtIns.snapshot().map { it.toolKey }.toSet(), result.effectiveToolKeys)
        assertTrue(result.warnings.isEmpty())
        builtIns.snapshot().forEach { descriptor ->
            assertSame(builtIns.find(descriptor.toolKey), result.catalog.find(descriptor.toolKey))
        }
    }

    @Test
    fun `skill declarations intersect and never grant`() {
        val result = assembled(
            assemble(
                skills = listOf(
                    skill("workspace-only", listOf("workspace.*")),
                    skill("list-only", listOf("workspace.list"))
                )
            )
        )
        assertEquals(setOf("workspace.list"), result.effectiveToolKeys)
        assertEquals(listOf("workspace.list"), result.catalog.snapshot().map { it.toolKey })
        assertNull(result.catalog.find("artifact.write_text"))
        assertNull(result.catalog.find("workspace.read_text"))
        assertNotNull(result.catalog.find("workspace.list"))
    }

    @Test
    fun `empty skill declaration adds no restriction`() {
        val result = assembled(assemble(skills = listOf(skill("unrestricted", emptyList()))))
        assertEquals(builtIns.snapshot().map { it.toolKey }.toSet(), result.effectiveToolKeys)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `unknown skill tool pattern narrows to nothing with a typed warning`() {
        val result = assembled(assemble(skills = listOf(skill("bad", listOf("system.exec")))))
        assertTrue(result.effectiveToolKeys.isEmpty())
        assertTrue(result.catalog.snapshot().isEmpty())
        val warning = result.warnings.single()
        assertTrue(warning is SkillToolMissing)
        val missing = warning as SkillToolMissing
        assertEquals("SKILL_MISSING_TOOL", missing.code)
        assertEquals("bad", missing.skillId)
        assertEquals("system.exec", missing.pattern)
    }

    @Test
    fun `skill scopes cannot union to escalate`() {
        val result = assembled(
            assemble(
                skills = listOf(
                    skill("workspace", listOf("workspace.*")),
                    skill("mcp-alpha", listOf("mcp:alpha:*"))
                ),
                mcp = listOf(mcpExecutor("alpha", "read"))
            )
        )
        assertTrue(result.effectiveToolKeys.isEmpty())
        assertTrue(result.catalog.snapshot().isEmpty())
        assertNull(result.catalog.find("workspace.list"))
        assertNull(result.catalog.find("mcp:alpha:read"))
    }

    @Test
    fun `mcp tools are added namespaced and removed with their server`() {
        val withMcp = assembled(
            assemble(
                mcp = listOf(
                    mcpExecutor("beta", "write"),
                    mcpExecutor("alpha", "read")
                )
            )
        )
        assertEquals(builtIns.snapshot().size + 2, withMcp.catalog.snapshot().size)
        val keys = withMcp.catalog.snapshot().map { it.toolKey }
        assertEquals(keys.sorted(), keys)
        assertNotNull(withMcp.catalog.find("mcp:alpha:read"))
        assertNotNull(withMcp.catalog.find("mcp:beta:write"))

        val withoutMcp = assembled(assemble())
        assertNull(withoutMcp.catalog.find("mcp:alpha:read"))
        assertEquals(builtIns.snapshot().size, withoutMcp.catalog.snapshot().size)
    }

    @Test
    fun `mcp server wildcard narrows when present and warns when the server is gone`() {
        val connected = assembled(
            assemble(
                skills = listOf(skill("scoped", listOf("mcp:alpha:*"))),
                mcp = listOf(mcpExecutor("alpha", "read"), mcpExecutor("beta", "write"))
            )
        )
        assertEquals(setOf("mcp:alpha:read"), connected.effectiveToolKeys)
        assertTrue(connected.warnings.isEmpty())

        val disconnected = assembled(
            assemble(skills = listOf(skill("scoped", listOf("mcp:alpha:*"))))
        )
        assertTrue(disconnected.effectiveToolKeys.isEmpty())
        val warning = disconnected.warnings.single() as SkillToolMissing
        assertEquals("SKILL_MISSING_TOOL", warning.code)
        assertEquals("mcp:alpha:*", warning.pattern)
    }

    @Test
    fun `duplicate mcp tool key is rejected`() {
        val duplicate = mcpExecutor("alpha", "read")
        val rejected = rejected(assemble(mcp = listOf(duplicate, duplicate)))
        assertEquals(listOf("DUPLICATE_TOOL_KEY"), rejected.conflicts.map { it.code })
        val conflict = rejected.conflicts.single() as DuplicateToolKey
        assertEquals("mcp:alpha:read", conflict.toolKey)
        assertEquals(listOf(AgentToolSource.MCP), conflict.sources)
    }

    @Test
    fun `schema conflict for one tool key is rejected`() {
        val rejected = rejected(
            assemble(
                mcp = listOf(
                    mcpExecutor("alpha", "read", HASH_A),
                    mcpExecutor("alpha", "read", HASH_B)
                )
            )
        )
        val conflict = rejected.conflicts.single() as SchemaConflict
        assertEquals("TOOL_SCHEMA_CONFLICT", conflict.code)
        assertEquals("mcp:alpha:read", conflict.toolKey)
        assertEquals(listOf(HASH_A, HASH_B), conflict.variants.map { it.schemaHash })
    }

    @Test
    fun `non mcp executor passed as mcp adapter is rejected`() {
        val builtInExecutor = builtIns.find("workspace.list")!!
        val rejected = rejected(assemble(mcp = listOf(builtInExecutor)))
        assertEquals("MCP_TOOL_KEY_INVALID", rejected.conflicts.single().code)
        assertEquals("workspace.list", rejected.conflicts.single().subject)
    }

    @Test
    fun `duplicate skill id is rejected`() {
        val rejected = rejected(
            assemble(
                skills = listOf(
                    skill("duplicate", listOf("workspace.*")),
                    skill("duplicate", emptyList())
                )
            )
        )
        assertEquals("DUPLICATE_SKILL_ID", rejected.conflicts.single().code)
        assertEquals("duplicate", (rejected.conflicts.single() as DuplicateSkillId).skillId)
    }

    @Test
    fun `inconsistent builtin catalog is rejected`() {
        val inconsistent = object : AgentToolCatalog {
            override fun find(toolKey: String): AgentToolExecutor? = null

            override fun snapshot(): List<AgentToolDescriptor> = builtIns.snapshot()
        }
        val rejected = rejected(assemble(builtIn = inconsistent))
        assertEquals(
            builtIns.snapshot().map { it.toolKey }.sorted(),
            rejected.conflicts.map { it.subject }.sorted()
        )
        assertTrue(rejected.conflicts.all { it.code == "CATALOG_INCONSISTENT" })
    }

    @Test
    fun `conflicts are reported in deterministic order`() {
        val rejected = rejected(
            assemble(
                skills = listOf(
                    skill("duplicate", listOf("workspace.*")),
                    skill("duplicate", emptyList())
                ),
                mcp = listOf(
                    builtIns.find("workspace.list")!!,
                    mcpExecutor("alpha", "read"),
                    mcpExecutor("alpha", "read")
                )
            )
        )
        val codes = rejected.conflicts.map { it.code }
        assertEquals(codes.sorted(), codes)
        assertTrue(
            codes.containsAll(
                listOf("DUPLICATE_SKILL_ID", "DUPLICATE_TOOL_KEY", "MCP_TOOL_KEY_INVALID")
            )
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `assembled catalog is an immutable snapshot of the inputs`() {
        val mutableMcp = mutableListOf(mcpExecutor("alpha", "read"))
        val mutableSkills = mutableListOf(skill("unrestricted", emptyList()))
        val result = assembled(assemble(skills = mutableSkills, mcp = mutableMcp))

        mutableMcp.clear()
        mutableSkills.clear()

        assertNotNull(result.catalog.find("mcp:alpha:read"))
        assertEquals(
            setOf(
                "artifact.write_text",
                "workspace.list",
                "workspace.read_text",
                "workspace.search_text",
                "mcp:alpha:read"
            ),
            result.effectiveToolKeys
        )
        assertSame(result.catalog.snapshot(), result.catalog.snapshot())
        assertThrows(UnsupportedOperationException::class.java) {
            (result.catalog.snapshot() as MutableList<AgentToolDescriptor>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (result.effectiveToolKeys as MutableSet<String>).add("workspace.list")
        }
    }

    @Test
    fun `ordering is deterministic regardless of input order`() {
        val first = assembled(
            assemble(mcp = listOf(mcpExecutor("beta", "write"), mcpExecutor("alpha", "read")))
        ).catalog.snapshot().map { it.toolKey }
        val second = assembled(
            assemble(mcp = listOf(mcpExecutor("alpha", "read"), mcpExecutor("beta", "write")))
        ).catalog.snapshot().map { it.toolKey }
        assertEquals(first, second)
        assertEquals(first.sorted(), first)
    }

    @Test
    fun `narrowed catalog keeps the frozen descriptor the broker sees`() {
        val result = assembled(assemble(skills = listOf(skill("workspace", listOf("workspace.*")))))
        val executor = result.catalog.find("workspace.list")!!
        assertSame(builtIns.find("workspace.list"), executor)
        val frozen = toolCommand(executor.descriptor, "{}")
        assertEquals(executor.descriptor.schemaHash, frozen.schemaHash)
        assertEquals(executor.descriptor.version, frozen.toolVersion)
        assertNull(result.catalog.find("artifact.write_text"))
    }

    @Test
    fun `warnings are ordered by skill then pattern`() {
        val result = assembled(
            assemble(
                skills = listOf(
                    skill("z-skill", listOf("mcp:zeta:*"), version = 2L),
                    skill("a-skill", listOf("system.two", "system.one"))
                )
            )
        )
        val warnings = result.warnings.map {
            val missing = it as SkillToolMissing
            "${missing.skillId}:${missing.pattern}"
        }
        assertEquals(
            listOf("a-skill:system.one", "a-skill:system.two", "z-skill:mcp:zeta:*"),
            warnings
        )
    }

    private fun assemble(
        skills: List<AgentSkillSnapshotSkill> = emptyList(),
        mcp: List<AgentToolExecutor> = emptyList(),
        builtIn: AgentToolCatalog = builtIns
    ): AgentToolCatalogAssemblyResult = assembler.assemble(builtIn, skills, mcp)

    private fun assembled(result: AgentToolCatalogAssemblyResult): Assembled {
        assertTrue("expected Assembled, got $result", result is Assembled)
        return result as Assembled
    }

    private fun rejected(result: AgentToolCatalogAssemblyResult): Rejected {
        assertTrue("expected Rejected, got $result", result is Rejected)
        return result as Rejected
    }

    private fun skill(
        id: String,
        patterns: List<String>,
        version: Long = 1L
    ): AgentSkillSnapshotSkill = AgentSkillSnapshotSkill(
        id = id,
        version = version,
        name = "Skill $id",
        sha256 = HASH_A,
        packageVersion = "1",
        entryPath = "SKILL.md",
        declaredToolPatterns = patterns.map { AgentSkillToolPattern.parse(it) },
        sourceFingerprint = "tree:" + HASH_B
    )

    private fun mcpExecutor(
        serverId: String,
        remoteName: String,
        schemaHash: String = HASH_A
    ): AgentToolExecutor = FakeExecutor(mcpDescriptor(serverId, remoteName, schemaHash))

    private fun mcpDescriptor(
        serverId: String,
        remoteName: String,
        schemaHash: String
    ): AgentToolDescriptor = AgentToolDescriptor(
        toolKey = "mcp:$serverId:$remoteName",
        version = "mcp-1",
        displayName = "MCP $serverId/$remoteName",
        description = "test MCP tool",
        schemaJson = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
        schemaHash = schemaHash,
        sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
        dataClass = AgentDataClass.PRIVATE
    )

    private class FakeExecutor(override val descriptor: AgentToolDescriptor) : AgentToolExecutor {
        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult = error("assembler tests never execute a tool")
    }

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

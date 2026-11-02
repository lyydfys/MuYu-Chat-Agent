package com.muyuchat.mca.agent.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillToolPolicyTest {

    @Test
    fun `parses the three namespace forms and exact keys`() {
        assertEquals("workspace.*", AgentSkillToolPattern.parse("workspace.*").canonical)
        assertEquals("artifact.*", AgentSkillToolPattern.parse("artifact.*").canonical)
        assertEquals("mcp:files:*", AgentSkillToolPattern.parse("mcp:files:*").canonical)
        assertEquals("workspace.read", AgentSkillToolPattern.parse("workspace.read").canonical)
    }

    @Test
    fun `rejects regex globs invalid namespaces and malformed mcp ids`() {
        for (invalid in listOf(
            "*", "workspace.?", "workspace.[ab]", "workspace..read", "Workspace.*",
            "mcp:*", "mcp:Server:*", "mcp:server:tool", "workspace.*.read"
        )) {
            AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
                AgentSkillToolPattern.parse(invalid)
            }
        }
    }

    @Test
    fun `multiple skills intersect and never union`() {
        val available = setOf("workspace.read", "workspace.write", "artifact.write", "network.search")
        val effective = AgentSkillToolScope.effectiveToolKeysFromDeclarations(
            declarations = listOf(
                listOf("workspace.*", "artifact.*"),
                listOf("workspace.read", "mcp:files:*")
            ),
            availableToolKeys = available
        )
        assertEquals(setOf("workspace.read"), effective)
    }

    @Test
    fun `a skill without a declaration does not restrict`() {
        val available = setOf("workspace.read", "artifact.write")
        assertEquals(
            available,
            AgentSkillToolScope.effectiveToolKeysFromDeclarations(emptyList<List<String>>(), available)
        )
        assertEquals(
            available,
            AgentSkillToolScope.effectiveToolKeysFromDeclarations(listOf(emptyList<String>()), available)
        )
    }

    @Test
    fun `disjoint declarations intersect to empty instead of unioning`() {
        val available = setOf("workspace.read", "workspace.write", "artifact.write")
        val effective = AgentSkillToolScope.effectiveToolKeysFromDeclarations(
            declarations = listOf(
                listOf("workspace.*"),
                listOf("artifact.*")
            ),
            availableToolKeys = available
        )
        assertTrue(effective.isEmpty())
    }

    @Test
    fun `declaration can only narrow the available registry set`() {
        val effective = AgentSkillToolScope.effectiveToolKeysFromDeclarations(
            declarations = listOf(listOf("workspace.*", "mcp:files:*")),
            availableToolKeys = setOf("workspace.read", "artifact.write")
        )
        assertEquals(setOf("workspace.read"), effective)
    }

    @Test
    fun `duplicate and oversized declarations are rejected`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
            AgentSkillToolDeclaration(listOf("workspace.read", "workspace.read"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID) {
            AgentSkillToolDeclaration((1..65).map { "workspace.t$it" })
        }
    }
}


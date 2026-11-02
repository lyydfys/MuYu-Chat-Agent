package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentToolCatalogTest {
    private val catalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())

    @Test
    fun `catalog snapshot is sorted and complete`() {
        val snapshot = catalog.snapshot()
        assertEquals(4, snapshot.size)
        assertEquals(
            listOf(
                "artifact.write_text",
                "workspace.list",
                "workspace.read_text",
                "workspace.search_text"
            ),
            snapshot.map { it.toolKey }
        )
        snapshot.forEach { descriptor ->
            assertEquals("1", descriptor.version)
            assertTrue(descriptor.description.isNotBlank())
            assertEquals(64, descriptor.schemaHash.length)
            assertTrue(descriptor.schemaHash.matches(Regex("^[0-9a-f]{64}$")))
        }
        assertEquals(snapshot, catalog.snapshot())
    }

    @Test
    fun `descriptor schemas are the frozen golden subset`() {
        val byKey = catalog.snapshot().associateBy { it.toolKey }
        assertGolden(byKey.getValue("workspace.list"), LIST_SCHEMA, LIST_HASH, AgentToolSideEffect.READ_ONLY)
        assertGolden(byKey.getValue("workspace.read_text"), READ_SCHEMA, READ_HASH, AgentToolSideEffect.READ_ONLY)
        assertGolden(byKey.getValue("workspace.search_text"), SEARCH_SCHEMA, SEARCH_HASH, AgentToolSideEffect.READ_ONLY)
        assertGolden(byKey.getValue("artifact.write_text"), WRITE_SCHEMA, WRITE_HASH, AgentToolSideEffect.WORKSPACE_WRITE)
    }

    @Test
    fun `catalog is a findable immutable snapshot`() {
        val list = catalog.find("workspace.list")
        assertNotNull(list)
        assertEquals("workspace.list", list!!.descriptor.toolKey)
        assertNull(catalog.find("workspace.delete"))
        // The returned list is the snapshot: repeated calls must not expose a mutable builder.
        assertEquals(catalog.snapshot(), catalog.snapshot())
    }

    @Test
    fun `duplicate tool key fails at construction`() {
        val fake = FakeAgentWorkspaceFilePort()
        try {
            BuiltInAgentToolCatalog(listOf(WorkspaceListTool(fake), WorkspaceListTool(fake)))
            fail("a duplicate tool key must fail catalog construction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("duplicate"))
        }
    }

    @Test
    fun `non canonical schema text fails at construction`() {
        val fake = FakeAgentWorkspaceFilePort()
        val tampered = object : AgentToolExecutor {
            override val descriptor: AgentToolDescriptor = WorkspaceListTool(fake).descriptor.copy(
                schemaJson = "{ \"type\":\"object\", \"additionalProperties\":false, \"properties\":{\"path\":{\"type\":\"string\"}} }"
            )
            override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
                error("not executed")
        }
        try {
            BuiltInAgentToolCatalog(listOf(tampered))
            fail("a non-canonical schema must fail catalog construction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("canonical"))
        }
    }

    @Test
    fun `stale schema hash fails at construction`() {
        val fake = FakeAgentWorkspaceFilePort()
        val tampered = object : AgentToolExecutor {
            override val descriptor: AgentToolDescriptor =
                WorkspaceListTool(fake).descriptor.copy(schemaHash = "0".repeat(64))
            override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
                error("not executed")
        }
        try {
            BuiltInAgentToolCatalog(listOf(tampered))
            fail("a stale schema hash must fail catalog construction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("hash"))
        }
    }

    private fun assertGolden(
        descriptor: AgentToolDescriptor,
        schema: String,
        hash: String,
        sideEffect: AgentToolSideEffect
    ) {
        assertEquals(schema, descriptor.schemaJson)
        assertEquals(hash, descriptor.schemaHash)
        assertEquals(sideEffect, descriptor.sideEffect)
    }

    private companion object {
        const val LIST_SCHEMA =
            "{\"additionalProperties\":false,\"properties\":{\"cursor\":{\"maxLength\":1024,\"minLength\":1," +
                "\"type\":[\"string\",\"null\"]},\"limit\":{\"maximum\":200,\"minimum\":1,\"type\":\"integer\"}," +
                "\"path\":{\"maxLength\":512,\"minLength\":1,\"type\":\"string\"}},\"type\":\"object\"}"
        const val LIST_HASH = "347b80bb8cad980a9a36ea0c9f318de25914746380c36aed9f6d4c60bff8c130"

        const val READ_SCHEMA =
            "{\"additionalProperties\":false,\"properties\":{\"limit\":{\"maximum\":16384,\"minimum\":1," +
                "\"type\":\"integer\"},\"offset\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"path\":{\"maxLength\":512,\"minLength\":1,\"type\":\"string\"}}," +
                "\"required\":[\"path\"],\"type\":\"object\"}"
        const val READ_HASH = "ba732f4774cadadfbe3dbd3202f7171affea9c3523ce8b15f11f848a850599b1"

        const val SEARCH_SCHEMA =
            "{\"additionalProperties\":false,\"properties\":{\"maxMatches\":{\"maximum\":100," +
                "\"minimum\":1,\"type\":\"integer\"},\"path\":{\"maxLength\":512,\"minLength\":1," +
                "\"type\":\"string\"},\"query\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}}," +
                "\"required\":[\"query\"],\"type\":\"object\"}"
        const val SEARCH_HASH = "02f2a920e0c2bc133419a612735a586558bcec51a9622597fd6504d30ca22d68"

        const val WRITE_SCHEMA =
            "{\"additionalProperties\":false,\"properties\":{\"content\":{\"maxLength\":32768," +
                "\"type\":\"string\"},\"expectedOldHash\":{\"maxLength\":64,\"minLength\":64," +
                "\"type\":[\"string\",\"null\"]},\"path\":{\"maxLength\":512,\"minLength\":1," +
                "\"type\":\"string\"}},\"required\":[\"content\",\"expectedOldHash\",\"path\"],\"type\":\"object\"}"
        const val WRITE_HASH = "222565fe01b0f08e15225de068cb52fbc30ab82f0bd9af400d137bf3a793a3ce"
    }
}

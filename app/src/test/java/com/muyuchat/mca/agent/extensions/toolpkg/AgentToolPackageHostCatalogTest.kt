package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolPackageHostOperation
import com.muyuchat.core.agent.contract.AgentToolPackageHostParameter
import com.muyuchat.core.agent.contract.AgentToolPackageParameterType
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPackageHostCatalogTest {
    private val catalog = AgentToolPackageFixtures.hostCatalog()

    @Test
    fun `exposes only the code-owned read-only workspace operations`() {
        assertEquals(
            listOf("workspace.list", "workspace.read_text", "workspace.search_text"),
            catalog.operations().map { it.operationId }
        )
        assertNull(catalog.find("artifact.write_text"))
        assertNull(catalog.find("network.fetch"))
        assertNull(catalog.find("mcp:server:tool"))
        catalog.operations().forEach { operation ->
            assertEquals(AgentToolSideEffect.READ_ONLY, operation.sideEffect)
            assertEquals(AgentDataClass.PRIVATE, operation.dataClass)
            assertTrue(operation.maxTimeoutMillis > 0L)
        }
    }

    @Test
    fun `reads parameter metadata from the trusted built-in schema`() {
        val readText = catalog.find("workspace.read_text")!!
        assertEquals(listOf("limit", "offset", "path"), readText.parameters.map { it.name })
        val path = readText.parameters.single { it.name == "path" }
        assertEquals(AgentToolPackageParameterType.STRING, path.type)
        assertTrue(path.required)
        assertFalse(path.nullable)
        assertEquals(1, path.minLength)
        assertEquals(512, path.maxLength)
        val limit = readText.parameters.single { it.name == "limit" }
        assertEquals(AgentToolPackageParameterType.INTEGER, limit.type)
        assertEquals(1L, limit.minimum)
        assertEquals(16_384L, limit.maximum)
    }

    @Test
    fun `inert catalog factory rejects duplicate operation ids`() {
        val operation = AgentToolPackageHostOperation(
            operationId = "workspace.read_text",
            displayName = "Read",
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE,
            parameters = listOf(
                AgentToolPackageHostParameter(
                    name = "path",
                    type = AgentToolPackageParameterType.STRING,
                    required = true
                )
            ),
            maxTimeoutMillis = 30_000L
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolPackageHostCatalog.of(listOf(operation, operation))
        }
        assertNull(AgentToolPackageHostCatalog.EMPTY.find("workspace.read_text"))
    }
}

package com.muyuchat.core.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPackageContractTest {
    private fun sha(character: Char): String = character.toString().repeat(64)

    @Test
    fun `package tool refs use the reserved package key shape`() {
        val ref = AgentToolPackageToolRef("research-basics", "read_brief")
        assertEquals("pkg.research-basics.read_brief", ref.toolKey)
        assertTrue(AgentToolPackageToolRef.isValidPackageId("a1"))
        assertFalse(AgentToolPackageToolRef.isValidPackageId("Research"))
        assertFalse(AgentToolPackageToolRef.isValidPackageId("a"))
        assertTrue(AgentToolPackageToolRef.isValidToolName("read_brief"))
        assertFalse(AgentToolPackageToolRef.isValidToolName("ReadBrief"))
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolPackageToolRef("../escape", "read")
        }
    }

    @Test
    fun `binding freezes identity metadata and rejects unsorted exposure`() {
        val ref = AgentToolPackageToolRef("research-basics", "read_brief")
        val binding = AgentToolPackageBinding(
            ref = ref,
            packageVersion = "1.0.0",
            hostOperation = "workspace.read_text",
            presetCanonicalJson = """{"path":"inputs/brief.txt"}""",
            exposedParameters = listOf("limit", "offset"),
            timeoutMillis = 10_000L,
            packageContentHash = sha('a'),
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE
        )
        assertEquals("1.0.0+aaaaaaaaaaaaaaaa", binding.versionIdentity)
        assertTrue(binding.isReadOnly)
        assertEquals(ref, binding.ref)

        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(exposedParameters = listOf("offset", "limit"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(packageContentHash = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding.copy(hostOperation = "not a host operation")
        }
    }

    @Test
    fun `host operation metadata keeps the trusted side effect and data class floor`() {
        val operation = AgentToolPackageHostOperation(
            operationId = "workspace.read_text",
            displayName = "Read Workspace Text",
            sideEffect = AgentToolSideEffect.READ_ONLY,
            dataClass = AgentDataClass.PRIVATE,
            parameters = listOf(
                AgentToolPackageHostParameter(
                    name = "path",
                    type = AgentToolPackageParameterType.STRING,
                    required = true,
                    minLength = 1,
                    maxLength = 512
                )
            ),
            maxTimeoutMillis = 30_000L
        )
        assertEquals(AgentToolSideEffect.READ_ONLY, operation.sideEffect)
        assertEquals(AgentDataClass.PRIVATE, operation.dataClass)
        assertEquals("path", operation.parameter("path")?.name)
        assertThrows(IllegalArgumentException::class.java) {
            operation.copy(operationId = "workspace..read")
        }
    }
}

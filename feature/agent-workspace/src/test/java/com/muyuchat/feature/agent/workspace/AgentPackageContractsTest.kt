package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPackageContractsTest {

    @Test
    fun documentReferenceRejectsBlankValues() {
        assertThrows(IllegalArgumentException::class.java) { AgentDocumentRef("   ") }
        assertEquals("content://docs/task.zip", AgentDocumentRef("content://docs/task.zip").value)
    }

    @Test
    fun exportFileNameIsSafeLowercaseAndBounded() {
        assertEquals("agent-task-1.mca-task.zip", exportPackageFileName("task-1"))

        val hostile = exportPackageFileName("TASK:1/../" + "x".repeat(200))
        assertFalse(hostile.contains(':'))
        assertFalse(hostile.contains('/'))
        assertTrue(hostile.startsWith("agent-"))
        assertTrue(hostile.endsWith(".mca-task.zip"))
        assertTrue(hostile.length <= 6 + 48 + ".mca-task.zip".length)
        assertEquals(hostile, hostile.lowercase())

        assertEquals("agent-task.mca-task.zip", exportPackageFileName("  "))
    }

    @Test
    fun typedFailuresUseSafeMessageAndUnknownFailuresUseGenericCopy() {
        val typed = agentPackageErrorUi(
            AgentPackageOperationException("STORAGE_NO_SPACE", "空间不足，无法写入任务包。")
        )
        assertEquals("STORAGE_NO_SPACE", typed.code)
        assertEquals("空间不足，无法写入任务包。", typed.message)

        val unexpected = agentPackageErrorUi(IllegalStateException("raw provider body must not leak"))
        assertEquals("PACKAGE_OPERATION_FAILED", unexpected.code)
        assertFalse(unexpected.message.contains("raw provider"))
        assertTrue(unexpected.message.isNotBlank())
    }

    @Test
    fun unavailableActionsReturnATypedFailure() = runBlocking {
        val result = AgentPackageActions.Unavailable.importPackage(
            AgentDocumentRef("content://docs/import.zip")
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AgentPackageOperationException)
        assertEquals(
            "PACKAGE_UNAVAILABLE",
            (error as AgentPackageOperationException).code
        )
    }
}

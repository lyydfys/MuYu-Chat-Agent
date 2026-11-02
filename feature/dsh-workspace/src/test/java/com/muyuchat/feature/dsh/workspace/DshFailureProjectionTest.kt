package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshFailureProjectionTest {

    @Test
    fun everyFailureCodeHasASpecificPromptFreeMessage() {
        DshFailureCode.values().forEach { code ->
            val projection = DshFailureProjectionMapper.project(
                DshRuntimeFailure(code = code, message = "raw", retryable = true)
            )
            assertEquals(code, projection.code)
            assertTrue("title for $code", projection.title.isNotBlank())
            assertTrue("message for $code", projection.message.isNotBlank())
            assertTrue("primary action for $code", projection.primaryActionLabel.isNotBlank())
            assertTrue(
                "message for $code must not echo diagnostics",
                !projection.message.contains("raw")
            )
        }
    }

    @Test
    fun processExitIsRestartableAndLabelsTheNewGeneration() {
        val projection = DshFailureProjectionMapper.project(
            DshRuntimeFailure(DshFailureCode.PROCESS_EXITED, "exited", retryable = true)
        )
        assertTrue(projection.runtimeExited)
        assertTrue(projection.retryable)
        assertEquals("重新启动", projection.primaryActionLabel)
        assertTrue(projection.title.contains("退出"))
    }

    @Test
    fun contractRetryableFlagWins() {
        val nonRetryable = DshFailureProjectionMapper.project(
            DshRuntimeFailure(DshFailureCode.NODE_START_FAILED, "x", retryable = false)
        )
        assertFalse(nonRetryable.retryable)
        val retryable = DshFailureProjectionMapper.project(
            DshRuntimeFailure(DshFailureCode.PAYLOAD_MISSING, "x", retryable = true)
        )
        assertTrue(retryable.retryable)
    }

    @Test
    fun nullFailureProjectsStableInternalFailure() {
        val projection = DshFailureProjectionMapper.project(null)
        assertEquals(DshFailureCode.INTERNAL, projection.code)
        assertTrue(projection.message.isNotBlank())
    }

    @Test
    fun diagnosticsNeverReachTheUserProjection() {
        val projection = DshFailureProjectionMapper.project(
            DshRuntimeFailure(
                code = DshFailureCode.GATEWAY_UNAVAILABLE,
                message = "failed at /data/private/path",
                retryable = true,
                diagnostics = "token=should-not-appear"
            )
        )
        assertFalse(projection.message.contains("should-not-appear"))
        assertFalse(projection.message.contains("/data/private/path"))
    }

    @Test
    fun uiRaisedFailuresUseStableCodes() {
        assertEquals(
            DshFailureCode.AUTH_ENTRY_MISSING,
            DshFailureProjectionMapper.authenticationRejected().code
        )
        assertEquals(
            DshFailureCode.PROCESS_EXITED,
            DshFailureProjectionMapper.unexpectedStop().code
        )
        assertTrue(DshFailureProjectionMapper.unexpectedStop().runtimeExited)
    }
}

package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerOutcome
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistent-tree picker pauses the activity that hosts it. These cases pin that the pause
 * caused by our own system dialog is not treated as "the user left", which previously resolved
 * every real selection as `picker was dismissed`.
 */
class DshWorkspaceTreePickerBrokerTest {

    @Test
    fun `a pause inside the launch grace window does not cancel the pending pick`() {
        assertFalse(
            DshWorkspaceTreePickerPausePolicy.shouldCancel(
                nowMillis = 1_000L,
                suppressUntilMillis = 5_000L,
                pending = true
            )
        )
        assertTrue(
            DshWorkspaceTreePickerPausePolicy.shouldCancel(
                nowMillis = 6_000L,
                suppressUntilMillis = 5_000L,
                pending = true
            )
        )
        assertFalse(
            "no waiter means nothing to cancel",
            DshWorkspaceTreePickerPausePolicy.shouldCancel(
                nowMillis = 6_000L,
                suppressUntilMillis = 5_000L,
                pending = false
            )
        )
    }

    @Test
    fun `a selection survives the pause the picker itself causes`() = runBlocking {
        DshWorkspaceTreePickerBroker.setClockForTest { 1_000L }
        DshWorkspaceTreePickerBroker.setForeground(true)
        val pending = async { DshWorkspaceTreePickerBroker.port.pick(request()) }
        withTimeout(5_000) { DshWorkspaceTreePickerBroker.request.first { it != null } }

        DshWorkspaceTreePickerBroker.notePickerLaunched()
        DshWorkspaceTreePickerBroker.setForeground(false)
        DshWorkspaceTreePickerBroker.resolve(
            DshWorkspaceTreePickerOutcome.Selected(
                treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                flags = DshWorkspaceTreeFlags(read = true, write = true)
            )
        )

        val outcome = withTimeout(5_000) { pending.await() }
        assertTrue(outcome is DshWorkspaceTreePickerOutcome.Selected)
    }

    @Test
    fun `a real backgrounding still cancels instead of hanging`() = runBlocking {
        // Past any grace window an earlier case may have armed on the process-wide broker.
        DshWorkspaceTreePickerBroker.setClockForTest { 10_000_000L }
        DshWorkspaceTreePickerBroker.setForeground(true)
        val pending = async { DshWorkspaceTreePickerBroker.port.pick(request()) }
        withTimeout(5_000) { DshWorkspaceTreePickerBroker.request.first { it != null } }

        DshWorkspaceTreePickerBroker.setForeground(false)
        yield()

        val outcome = withTimeout(5_000) { pending.await() }
        assertEquals(DshWorkspaceTreePickerOutcome.Cancelled, outcome)
    }

    private fun request(): DshWorkspaceTreePickerRequest =
        DshWorkspaceTreePickerRequest(
            requestId = DshCapabilityRequestId("broker-test-request-0001"),
            generation = 1L,
            writeAccess = true
        )
}

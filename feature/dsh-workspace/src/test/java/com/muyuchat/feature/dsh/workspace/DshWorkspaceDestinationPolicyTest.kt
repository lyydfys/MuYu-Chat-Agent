package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWorkspaceDestinationPolicyTest {

    @Test
    fun consoleEntryExistsOnlyWhenTheCompositionRootInjectedAPort() {
        assertTrue(DshWorkspaceDestinationPolicy.consoleEntryAvailable(consolePortInjected = true))
        assertFalse(DshWorkspaceDestinationPolicy.consoleEntryAvailable(consolePortInjected = false))
    }

    @Test
    fun backFromTheConsoleReturnsToTheOfficialPage() {
        assertEquals(
            DshWorkspaceDestination.WEB,
            DshWorkspaceDestinationPolicy.backDestination(DshWorkspaceDestination.CONSOLE)
        )
    }

    @Test
    fun backOnTheWebDestinationIsNotConsumedByTheTabPolicy() {
        assertFalse(DshWorkspaceDestinationPolicy.consumesBack(DshWorkspaceDestination.WEB))
    }

    @Test
    fun backOnTheConsoleIsConsumedByTheTabPolicy() {
        assertTrue(DshWorkspaceDestinationPolicy.consumesBack(DshWorkspaceDestination.CONSOLE))
    }

    @Test
    fun webBackDestinationStaysOnWeb() {
        assertEquals(
            DshWorkspaceDestination.WEB,
            DshWorkspaceDestinationPolicy.backDestination(DshWorkspaceDestination.WEB)
        )
    }
}

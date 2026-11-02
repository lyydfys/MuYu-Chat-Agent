package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceAccessKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView file chooser is one-shot. These tests pin the two facts a console/adapter could
 * otherwise misread: a chooser result never carries a directory grant, and a tree intent is
 * refused instead of being downgraded into a chooser.
 */
class DshWebFileChooserPolicyTest {

    @Test
    fun treeIntentIsRefused() {
        assertEquals(
            DshFileChooserRefusal.TREE_GRANT_NOT_SUPPORTED,
            DshWebFileChooserPolicy.refusalFor(DshWebFileChooserPolicy.ACTION_OPEN_DOCUMENT_TREE)
        )
    }

    @Test
    fun ordinaryChooserActionsStayAllowed() {
        assertNull(DshWebFileChooserPolicy.refusalFor(DshWebFileChooserPolicy.ACTION_GET_CONTENT))
        assertNull(DshWebFileChooserPolicy.refusalFor(DshWebFileChooserPolicy.ACTION_OPEN_DOCUMENT))
        assertTrue(DshWebFileChooserPolicy.isOneShotChooser(DshWebFileChooserPolicy.ACTION_GET_CONTENT))
        assertTrue(DshWebFileChooserPolicy.isOneShotChooser(DshWebFileChooserPolicy.ACTION_OPEN_DOCUMENT))
        assertFalse(DshWebFileChooserPolicy.isOneShotChooser(DshWebFileChooserPolicy.ACTION_OPEN_DOCUMENT_TREE))
    }

    @Test
    fun chooserAccessIsOneShot() {
        assertEquals(DshWorkspaceAccessKind.ONE_SHOT_FILE_PICK, DshWebFileChooserPolicy.access.kind)
        assertFalse(DshWebFileChooserPolicy.access.persistentTreeGrant)
    }

    @Test
    fun consoleLabelsDistinguishOneShotFromPersistentTreeAccess() {
        assertTrue(
            DshConsoleProjector.capabilityLabel(DshCapability.FILE_PICK).contains("单次")
        )
        assertTrue(
            DshConsoleProjector.capabilityLabel(DshCapability.FILE_SAVE).contains("单次")
        )
        assertTrue(
            DshConsoleProjector.capabilityLabel(DshCapability.WORKSPACE_TREE).contains("持久")
        )
    }
}

package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshDocumentRef
import com.muyuchat.core.dsh.contract.capability.DshPickFilesResult
import com.muyuchat.core.dsh.contract.capability.DshSaveFileResult
import com.muyuchat.core.dsh.contract.capability.DshStagedFile
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceAccessKind
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceRelativePath
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honesty of the typed access projection: only a live tree grant may report persistent access.
 * These are the shapes a console, a Node tool or a future adapter maps from.
 */
class DshWorkspaceAccessProjectionTest {

    @Test
    fun aGrantedTreeIsTheOnlyPersistentAccess() {
        val granted = DshWorkspaceTreeGrantStatus(
            state = DshWorkspaceTreeGrantState.GRANTED,
            flags = com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags.READ_WRITE,
            grantedAtMillis = 1_000L
        )
        assertEquals(DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT, granted.access.kind)
        assertTrue(granted.access.persistentTreeGrant)

        listOf(
            DshWorkspaceTreeGrantState.NOT_GRANTED,
            DshWorkspaceTreeGrantState.REVOKED,
            DshWorkspaceTreeGrantState.INVALID,
            DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE
        ).forEach { state ->
            val status = DshWorkspaceTreeGrantStatus(state)
            assertEquals(DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT, status.access.kind)
            assertFalse("$state must not claim a persistent tree grant", status.access.persistentTreeGrant)
        }
    }

    @Test
    fun aStagedPickIsOneShotEvenThoughItLivesInTheWorkspace() {
        val staged = DshStagedFile(
            document = DshDocumentRef("documentref0001"),
            workspaceRelativePath = DshWorkspaceRelativePath.of(
                "inbox/3/workspace-tree-req-0001/notes.txt"
            ),
            displayName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 12L
        )

        val access = DshPickFilesResult.Picked(listOf(staged)).access

        assertEquals(DshWorkspaceAccessKind.ONE_SHOT_FILE_PICK, access.kind)
        assertFalse(access.persistentTreeGrant)
    }

    @Test
    fun anExportedSaveIsOneShot() {
        val access = DshSaveFileResult.Saved(
            document = DshDocumentRef("documentref0002"),
            displayName = "report.pdf"
        ).access

        assertEquals(DshWorkspaceAccessKind.DOWNLOAD_OR_EXPORT_SAVE, access.kind)
        assertFalse(access.persistentTreeGrant)
    }
}

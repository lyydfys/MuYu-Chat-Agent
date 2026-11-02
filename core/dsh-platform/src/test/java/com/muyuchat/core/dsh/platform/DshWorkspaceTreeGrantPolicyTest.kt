package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWorkspaceTreeGrantPolicyTest {

    @Test
    fun acceptsTheRootOfADocumentTree() {
        assertTrue(DshWorkspaceTreeGrantPolicy.isTreeUri(TREE_URI))
        assertTrue(
            DshWorkspaceTreeGrantPolicy.isTreeUri(
                "content://com.android.providers.downloads.documents/tree/downloads"
            )
        )
    }

    @Test
    fun rejectsDocumentsPathsAndForgedAuthorities() {
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri(DOCUMENT_URI))
        assertFalse(
            DshWorkspaceTreeGrantPolicy.isTreeUri(
                "content://authority/tree/primary%3ADownload/document/primary%3Aa.txt"
            )
        )
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("file:///storage/emulated/0/Download"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("/storage/emulated/0/Download"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content:///tree/primary"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content://user@authority/tree/primary"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content://authority/tree/primary?x=1"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content://authority/tree/primary#frag"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content://authority/tree/"))
        assertFalse(DshWorkspaceTreeGrantPolicy.isTreeUri("content://authority/tree/primary\nX"))
        assertFalse(
            DshWorkspaceTreeGrantPolicy.isTreeUri(
                "content://authority/tree/" + "a".repeat(DshWorkspaceTreeGrantPolicy.MAX_TREE_URI_CHARS)
            )
        )
    }

    @Test
    fun usabilityRequiresATreeUriAndReadAccess() {
        assertTrue(
            DshWorkspaceTreeGrantPolicy.isUsableSelection(TREE_URI, DshWorkspaceTreeFlags.READ)
        )
        assertTrue(
            DshWorkspaceTreeGrantPolicy.isUsableSelection(TREE_URI, DshWorkspaceTreeFlags.READ_WRITE)
        )
        assertFalse(
            DshWorkspaceTreeGrantPolicy.isUsableSelection(DOCUMENT_URI, DshWorkspaceTreeFlags.READ)
        )
        assertFalse(
            DshWorkspaceTreeGrantPolicy.isUsableSelection(TREE_URI, DshWorkspaceTreeFlags.NONE)
        )
    }

    @Test
    fun noRecordNeverClaimsAGrantEvenWhenThePlatformLooksHealthy() {
        val status = DshWorkspaceTreeGrantPolicy.statusOf(
            record = null,
            observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE),
            pendingPick = false,
            detail = null
        )

        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertFalse(status.access.persistentTreeGrant)
    }

    @Test
    fun revokedRecordNeverClaimsAGrant() {
        val record = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ_WRITE,
            grantedAtMillis = 10L,
            revokedAtMillis = 20L
        )

        val status = DshWorkspaceTreeGrantPolicy.statusOf(
            record = record,
            observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE),
            pendingPick = false,
            detail = null
        )

        assertEquals(DshWorkspaceTreeGrantState.REVOKED, status.state)
        assertTrue(status.flags.isEmpty)
        assertNull(status.grantedAtMillis)
        assertFalse(status.access.persistentTreeGrant)
    }

    @Test
    fun aHeldPermissionWithoutReadIsInvalid() {
        val record = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ,
            grantedAtMillis = 10L
        )

        val status = DshWorkspaceTreeGrantPolicy.statusOf(
            record = record,
            observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.NONE),
            pendingPick = false,
            detail = null
        )

        assertEquals(DshWorkspaceTreeGrantState.INVALID, status.state)
    }

    @Test
    fun missingPermissionIsInvalidAndMissingProviderIsUnavailable() {
        val record = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ,
            grantedAtMillis = 10L
        )

        assertEquals(
            DshWorkspaceTreeGrantState.INVALID,
            DshWorkspaceTreeGrantPolicy.statusOf(
                record,
                DshWorkspaceTreeGrantObservation.NotHeld,
                pendingPick = false,
                detail = null
            ).state
        )
        assertEquals(
            DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE,
            DshWorkspaceTreeGrantPolicy.statusOf(
                record,
                DshWorkspaceTreeGrantObservation.ProviderUnavailable,
                pendingPick = false,
                detail = null
            ).state
        )
    }

    @Test
    fun detailIsSanitizedToTheContractBounds() {
        val record = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ,
            grantedAtMillis = 10L
        )
        val hostile = "bad\u0000line\u0007" + "x".repeat(400)

        val status = DshWorkspaceTreeGrantPolicy.statusOf(
            record = null,
            observation = null,
            pendingPick = false,
            detail = hostile
        )

        val detail = status.detail.orEmpty()
        assertTrue(detail.length <= DshWorkspaceTreeGrantStatus.MAX_DETAIL_CHARS)
        assertFalse(detail.contains('\u0000'))
        assertFalse(detail.contains('\u0007'))
    }

    @Test
    fun invalidStateReportsItsOwnReasonNotAStaleAttemptDetail() {
        val record = DshWorkspaceTreeGrantRecord(
            treeUri = TREE_URI,
            flags = DshWorkspaceTreeFlags.READ,
            grantedAtMillis = 10L
        )

        val status = DshWorkspaceTreeGrantPolicy.statusOf(
            record = record,
            observation = DshWorkspaceTreeGrantObservation.NotHeld,
            pendingPick = false,
            detail = "the workspace folder picker was dismissed"
        )

        assertEquals(DshWorkspaceTreeGrantState.INVALID, status.state)
        assertEquals("the persisted tree permission is no longer held", status.detail)
    }

    @Test
    fun recordRejectsNonTreeUrisAndBackwardsReleaseTimes() {
        assertThrows(IllegalArgumentException::class.java) {
            DshWorkspaceTreeGrantRecord(
                treeUri = DOCUMENT_URI,
                flags = DshWorkspaceTreeFlags.READ,
                grantedAtMillis = 10L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DshWorkspaceTreeGrantRecord(
                treeUri = TREE_URI,
                flags = DshWorkspaceTreeFlags.READ,
                grantedAtMillis = 10L,
                revokedAtMillis = 5L
            )
        }
    }
}

package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceAccessKind
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantRequest
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantResult
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWorkspaceTreeGrantOwnerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val platform = FakeWorkspaceTreePlatform()
    private val store = FakeWorkspaceTreeStore()
    private val picker = FakeWorkspaceTreePicker()
    private var nowMillis = 5_000L

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun owner(): DshWorkspaceTreeGrantOwner = DshWorkspaceTreeGrantOwner(
        platform = platform,
        store = store,
        picker = picker,
        scope = scope,
        clock = { nowMillis },
        pickTimeoutMillis = 60_000L
    )

    private fun request(write: Boolean = true): DshWorkspaceTreeGrantRequest =
        DshWorkspaceTreeGrantRequest(
            requestId = DshCapabilityRequestId("workspace-tree-req-0001"),
            generation = 7L,
            reason = "grant a workspace folder",
            writeAccess = write
        )

    @Test
    fun initialStatusWithoutRecordIsNotGranted() {
        val status = owner().status()

        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertFalse(status.isPersistentTreeGrant)
        assertFalse(status.access.persistentTreeGrant)
        assertEquals(DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT, status.access.kind)
    }

    @Test
    fun grantFlowPersistsUriFlagsAndGrantTime() {
        val owner = owner()
        val started = owner.requestGrant(request())

        assertTrue(started is DshWorkspaceTreeGrantResult.PickStarted)
        assertTrue(owner.status().pendingPick)
        assertEquals(true, picker.lastRequest?.writeAccess)

        picker.outcome.complete(
            DshWorkspaceTreePickerOutcome.Selected(TREE_URI, DshWorkspaceTreeFlags.READ_WRITE)
        )

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.GRANTED, status.state)
        assertEquals(DshWorkspaceTreeFlags.READ_WRITE, status.flags)
        assertEquals(5_000L, status.grantedAtMillis)
        assertFalse(status.pendingPick)
        assertTrue(status.access.persistentTreeGrant)
        assertEquals(TREE_URI, platform.takenUri)
        assertEquals(DshWorkspaceTreeFlags.READ_WRITE, platform.takenFlags)
        assertEquals(TREE_URI, store.stored?.treeUri)
        assertEquals(1, store.writes)
    }

    @Test
    fun readOnlyRequestNeverPersistsWriteEvenWhenThePickerOffersIt() {
        val owner = owner()
        owner.requestGrant(request(write = false))
        picker.outcome.complete(
            DshWorkspaceTreePickerOutcome.Selected(TREE_URI, DshWorkspaceTreeFlags.READ_WRITE)
        )

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.GRANTED, status.state)
        assertTrue(status.flags.read)
        assertFalse(status.flags.write)
        assertEquals(DshWorkspaceTreeFlags.READ, platform.takenFlags)
        assertEquals(DshWorkspaceTreeFlags.READ, store.stored?.flags)
    }

    @Test
    fun pickerCancelledLeavesNotGrantedAndNeverTakesPermission() {
        val owner = owner()
        owner.requestGrant(request())
        picker.outcome.complete(DshWorkspaceTreePickerOutcome.Cancelled)

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertFalse(status.pendingPick)
        assertNull(platform.takenUri)
        assertNull(store.stored)
    }

    @Test
    fun userNotPresentLeavesNotGrantedWithDetail() {
        val owner = owner()
        owner.requestGrant(request())
        picker.outcome.complete(DshWorkspaceTreePickerOutcome.UserNotPresent)

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertNotNull(status.detail)
        assertNull(platform.takenUri)
    }

    @Test
    fun documentUriIsRejectedInsteadOfBecomingATreeGrant() {
        val owner = owner()
        owner.requestGrant(request())
        picker.outcome.complete(
            DshWorkspaceTreePickerOutcome.Selected(DOCUMENT_URI, DshWorkspaceTreeFlags.READ)
        )

        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, owner.status().state)
        assertNull(platform.takenUri)
        assertNull(store.stored)
    }

    @Test
    fun takeFailureMapsToProviderUnavailableDetail() {
        platform.takeResult = DshWorkspaceTreeGrantObservation.ProviderUnavailable
        val owner = owner()
        owner.requestGrant(request())
        picker.outcome.complete(
            DshWorkspaceTreePickerOutcome.Selected(TREE_URI, DshWorkspaceTreeFlags.READ_WRITE)
        )

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertTrue(status.detail.orEmpty().contains("provider"))
        assertNull(store.stored)
    }

    @Test
    fun takeNotHeldMapsToNotPersistedDetail() {
        platform.takeResult = DshWorkspaceTreeGrantObservation.NotHeld
        val owner = owner()
        owner.requestGrant(request())
        picker.outcome.complete(
            DshWorkspaceTreePickerOutcome.Selected(TREE_URI, DshWorkspaceTreeFlags.READ_WRITE)
        )

        val status = owner.status()
        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertTrue(status.detail.orEmpty().contains("persist"))
        assertNull(store.stored)
    }

    @Test
    fun statusTurnsInvalidWhenPermissionIsNoLongerHeld() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.NotHeld

        val status = owner().status()

        assertEquals(DshWorkspaceTreeGrantState.INVALID, status.state)
        assertFalse(status.access.persistentTreeGrant)
        assertTrue(status.flags.isEmpty)
        assertNull(status.grantedAtMillis)
    }

    @Test
    fun statusTurnsProviderUnavailableWhenObservationThrows() {
        store.stored = record(DshWorkspaceTreeFlags.READ)
        platform.throwOnObserve = true

        val status = owner().status()

        assertEquals(DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE, status.state)
        assertFalse(status.access.persistentTreeGrant)
    }

    @Test
    fun statusReportsTheModesThePlatformActuallyHolds() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ)

        val status = owner().status()

        assertEquals(DshWorkspaceTreeGrantState.GRANTED, status.state)
        assertTrue(status.flags.read)
        assertFalse(status.flags.write)
    }

    @Test
    fun persistedRevokedRecordSurvivesRestart() {
        store.stored = record(DshWorkspaceTreeFlags.READ).copy(revokedAtMillis = 9_000L)

        val status = owner().status()

        assertEquals(DshWorkspaceTreeGrantState.REVOKED, status.state)
        assertNull(platform.takenUri)
    }

    @Test
    fun releaseCallsPlatformAndPersistsRevokedMarker() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE)
        val owner = owner()

        val result = owner.release()

        assertTrue(result is DshWorkspaceTreeGrantResult.Released)
        val status = (result as DshWorkspaceTreeGrantResult.Released).status
        assertEquals(DshWorkspaceTreeGrantState.REVOKED, status.state)
        assertEquals(TREE_URI, platform.releasedUri)
        assertEquals(5_000L, store.stored?.revokedAtMillis)
        assertEquals(DshWorkspaceTreeGrantState.REVOKED, owner.status().state)
    }

    @Test
    fun releaseWithoutGrantDoesNotClaimRevoked() {
        val owner = owner()

        val result = owner.release()

        val status = (result as DshWorkspaceTreeGrantResult.Released).status
        assertEquals(DshWorkspaceTreeGrantState.NOT_GRANTED, status.state)
        assertNull(platform.releasedUri)
        assertNull(store.stored)
    }

    @Test
    fun releaseFailureDoesNotClaimRevoked() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE)
        platform.releaseResult = DshWorkspaceTreeGrantObservation.ProviderUnavailable
        val owner = owner()

        val result = owner.release()

        assertTrue(result is DshWorkspaceTreeGrantResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
            (result as DshWorkspaceTreeGrantResult.Rejected).failure.code
        )
        assertNull(store.stored?.revokedAtMillis)
        assertEquals(DshWorkspaceTreeGrantState.GRANTED, owner.status().state)
    }

    @Test
    fun secondRequestWhilePendingDoesNotOpenASecondPicker() {
        val owner = owner()
        owner.requestGrant(request())
        val second = owner.requestGrant(request())

        assertTrue(second is DshWorkspaceTreeGrantResult.PickStarted)
        assertEquals(1, picker.calls)
        assertTrue((second as DshWorkspaceTreeGrantResult.PickStarted).status.pendingPick)
    }

    @Test
    fun alreadyGrantedRequestDoesNotOpenPicker() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.Held(DshWorkspaceTreeFlags.READ_WRITE)

        val result = owner().requestGrant(request())

        assertTrue(result is DshWorkspaceTreeGrantResult.Granted)
        assertEquals(0, picker.calls)
    }

    @Test
    fun hostStopCancelsPendingPickAndKeepsGrant() {
        store.stored = record(DshWorkspaceTreeFlags.READ_WRITE)
        platform.observation = DshWorkspaceTreeGrantObservation.NotHeld
        val owner = owner()
        owner.requestGrant(request())

        owner.onHostStopped()

        val status = owner.status()
        assertFalse(status.pendingPick)
        assertEquals(DshWorkspaceTreeGrantState.INVALID, status.state)
    }

    @Test
    fun statusRejectsFlagsAndGrantTimeForNonGrantedStates() {
        val status = DshWorkspaceTreeGrantStatus(DshWorkspaceTreeGrantState.INVALID)
        assertTrue(status.flags.isEmpty)

        val failed = runCatching {
            DshWorkspaceTreeGrantStatus(
                state = DshWorkspaceTreeGrantState.INVALID,
                flags = DshWorkspaceTreeFlags.READ
            )
        }
        assertTrue(failed.isFailure)
    }

    @Test
    fun oneShotAccessCanNeverClaimAPersistentTreeGrant() {
        val oneShot = DshWorkspaceTreeGrantStatus(
            state = DshWorkspaceTreeGrantState.NOT_GRANTED
        ).access
        assertEquals(DshWorkspaceAccessKind.PERSISTENT_TREE_GRANT, oneShot.kind)
        assertFalse(oneShot.persistentTreeGrant)

        val fabricate = runCatching {
            com.muyuchat.core.dsh.contract.capability.DshWorkspaceAccess(
                kind = DshWorkspaceAccessKind.ONE_SHOT_FILE_PICK,
                persistentTreeGrant = true
            )
        }
        assertTrue(fabricate.isFailure)
    }

    private fun record(flags: DshWorkspaceTreeFlags) = DshWorkspaceTreeGrantRecord(
        treeUri = TREE_URI,
        flags = flags,
        grantedAtMillis = 1_000L
    )
}

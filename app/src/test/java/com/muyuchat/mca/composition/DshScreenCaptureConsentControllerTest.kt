package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentCodes
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM state-transition tests of the app screen-capture consent request.
 *
 * The real MediaProjection dialog, the foreground service and the Activity live on a device; this
 * suite pins the decisions that surround them: exactly-once claim (Activity recreation), cancel,
 * typed refusal, host-stop release and retry after a terminal state. No android.* object is used.
 */
class DshScreenCaptureConsentControllerTest {

    @Test
    fun `request allocates an id and waits for the host activity to claim it`() {
        val controller = DshScreenCaptureConsentController()

        assertTrue(controller.request())

        val state = controller.state.value
        assertEquals(DshScreenCaptureConsentPhase.REQUESTED, state.phase)
        assertEquals(1L, state.requestId)
        assertTrue(state.isPending)
        assertNull(state.code)
    }

    @Test
    fun `grant moves awaiting user to granted and clears the typed code`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        assertTrue(controller.claim(controller.state.value.requestId))

        assertTrue(controller.onGranted())

        assertEquals(DshScreenCaptureConsentPhase.GRANTED, controller.state.value.phase)
        assertNull(controller.state.value.code)
        assertFalse(controller.state.value.isPending)
    }

    @Test
    fun `claim is exactly once so an activity recreation cannot launch twice`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        val requestId = controller.state.value.requestId

        assertTrue(controller.claim(requestId))
        // The recreated composition asks again with the same request id.
        assertFalse(controller.claim(requestId))
        assertEquals(DshScreenCaptureConsentPhase.AWAITING_USER, controller.state.value.phase)
        assertEquals(requestId, controller.state.value.requestId)
    }

    @Test
    fun `claim with a stale or unknown id is ignored`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()

        assertFalse(controller.claim(999L))
        assertEquals(DshScreenCaptureConsentPhase.REQUESTED, controller.state.value.phase)
    }

    @Test
    fun `a cancelled dialog stays typed consent_required and can be retried`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        val firstId = controller.state.value.requestId
        controller.claim(firstId)

        assertTrue(controller.onCancelled())

        val cancelled = controller.state.value
        assertEquals(DshScreenCaptureConsentPhase.CANCELLED, cancelled.phase)
        assertEquals(DshScreenCaptureConsentCodes.CONSENT_REQUIRED, cancelled.code)
        assertFalse(cancelled.isPending)
        // A new request is accepted and receives a strictly newer identity.
        assertTrue(controller.request())
        assertEquals(DshScreenCaptureConsentPhase.REQUESTED, controller.state.value.phase)
        assertEquals(firstId + 1L, controller.state.value.requestId)
    }

    @Test
    fun `a rejected grant keeps its typed code and may be retried`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        controller.claim(controller.state.value.requestId)

        assertTrue(controller.onRejected(DshScreenCaptureConsentCodes.CONSENT_REQUIRED))

        assertEquals(DshScreenCaptureConsentPhase.REJECTED, controller.state.value.phase)
        assertEquals(DshScreenCaptureConsentCodes.CONSENT_REQUIRED, controller.state.value.code)
        assertTrue(controller.request())
    }

    @Test
    fun `a failed preparation ends the request without arming anything`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        val requestId = controller.state.value.requestId
        controller.claim(requestId)

        assertTrue(
            controller.fail(
                requestId,
                DshScreenCaptureConsentCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                "the mediaProjection foreground service refused to start"
            )
        )

        assertEquals(DshScreenCaptureConsentPhase.REJECTED, controller.state.value.phase)
        assertEquals(
            DshScreenCaptureConsentCodes.FOREGROUND_SERVICE_UNAVAILABLE,
            controller.state.value.code
        )
    }

    @Test
    fun `pending and granted requests refuse a duplicate request`() {
        val requested = DshScreenCaptureConsentController()
        assertTrue(requested.request())
        assertFalse(requested.request())

        val awaiting = DshScreenCaptureConsentController()
        awaiting.request()
        awaiting.claim(awaiting.state.value.requestId)
        assertFalse(awaiting.request())

        val granted = DshScreenCaptureConsentController()
        granted.request()
        granted.claim(granted.state.value.requestId)
        granted.onGranted()
        assertFalse(granted.request())
    }

    @Test
    fun `host stop releases a granted projection and permits a new request`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        controller.claim(controller.state.value.requestId)
        controller.onGranted()

        assertTrue(controller.onHostStopped())

        assertEquals(DshScreenCaptureConsentPhase.RELEASED, controller.state.value.phase)
        assertEquals(DshScreenCaptureConsentCodes.HOST_STOPPED, controller.state.value.code)
        assertTrue(controller.request())
    }

    @Test
    fun `the real capture owner release retires a granted projection`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        controller.claim(controller.state.value.requestId)
        controller.onGranted()

        // A failed start/restart releases the grant without publishing a host STOPPED snapshot.
        assertTrue(controller.onCaptureReleased())

        assertEquals(DshScreenCaptureConsentPhase.RELEASED, controller.state.value.phase)
        assertEquals(DshScreenCaptureConsentCodes.HOST_STOPPED, controller.state.value.code)
        assertTrue(controller.request())
        // The release fact is not a pending-state transition.
        assertFalse(controller.onCaptureReleased())
    }

    @Test
    fun `host stop cancels a dialog that belongs to the stopped generation`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()
        controller.claim(controller.state.value.requestId)

        assertTrue(controller.onHostStopped())

        assertEquals(DshScreenCaptureConsentPhase.CANCELLED, controller.state.value.phase)
        assertEquals(DshScreenCaptureConsentCodes.HOST_STOPPED, controller.state.value.code)
    }

    @Test
    fun `a stored grant that is no longer live projects as released and can be requested again`() {
        var live = true
        val controller = DshScreenCaptureConsentController(isGrantLive = { live })
        controller.request()
        controller.claim(controller.state.value.requestId)
        controller.onGranted()

        assertEquals(DshScreenCaptureConsentPhase.GRANTED, controller.projectedState().phase)

        // The unused-grant TTL or an owner release can kill the projection without a host stop.
        live = false
        val projected = controller.projectedState()

        assertEquals(DshScreenCaptureConsentPhase.RELEASED, projected.phase)
        assertEquals(DshScreenCaptureConsentCodes.HOST_STOPPED, projected.code)
        // A projection read never rewrites the stored state.
        assertEquals(DshScreenCaptureConsentPhase.GRANTED, controller.state.value.phase)
        // The user can request consent again because the reconciled state is not granted.
        assertTrue(controller.request())
    }

    @Test
    fun `host stop is a no-op when nothing is pending or granted`() {
        val controller = DshScreenCaptureConsentController()

        assertFalse(controller.onHostStopped())
        assertEquals(DshScreenCaptureConsentPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun `a new controller starts idle after process death`() {
        val before = DshScreenCaptureConsentController()
        before.request()
        before.claim(before.state.value.requestId)
        before.onGranted()

        // Process death drops every in-memory fact; the projection is gone with the process too.
        val after = DshScreenCaptureConsentController()

        assertEquals(DshScreenCaptureConsentPhase.IDLE, after.state.value.phase)
        assertEquals(0L, after.state.value.requestId)
        assertTrue(after.request())
    }

    @Test
    fun `terminal transitions are ignored for an unclaimed request`() {
        val controller = DshScreenCaptureConsentController()
        controller.request()

        assertFalse(controller.onGranted())
        assertFalse(controller.onCancelled())
        assertFalse(controller.onRejected(DshScreenCaptureConsentCodes.CONSENT_REQUIRED))
        // A refusal reported by the host Activity still ends the request with a typed code.
        assertTrue(controller.fail(controller.state.value.requestId, DshScreenCaptureConsentCodes.CONSENT_REQUIRED))
    }
}

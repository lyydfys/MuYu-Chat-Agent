package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentCodes
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshScreenCaptureConsentOwnerTest {
    @Test
    fun `a request is claimed only once and an old request cannot claim it`() {
        val fixture = Fixture()
        val id = fixture.request()
        assertFalse(fixture.owner.begin(id - 1))
        assertTrue(fixture.owner.begin(id))
        assertFalse(fixture.owner.begin(id))
        assertEquals(DshScreenCaptureConsentPhase.AWAITING_USER, fixture.consent.state.value.phase)
    }

    @Test
    fun `begin never starts the capture service before the system dialog`() {
        val fixture = Fixture(grantResult = true)
        fixture.owner.begin(fixture.request())
        assertTrue(fixture.grants.isEmpty())
    }

    @Test
    fun `a launcher rejection releases the prepared service once and preserves its code`() {
        val fixture = Fixture()
        val id = fixture.request()
        fixture.owner.begin(id)
        fixture.owner.reject(id - 1, "stale")
        assertTrue(fixture.grants.isEmpty())
        fixture.owner.reject(id, "dialog_unavailable")
        fixture.owner.reject(id, "duplicate")
        assertEquals(listOf(CANCELLED to null), fixture.grants)
        assertEquals(DshScreenCaptureConsentPhase.REJECTED, fixture.consent.state.value.phase)
        assertEquals("dialog_unavailable", fixture.consent.state.value.code)
    }

    @Test
    fun `a real accepted grant transitions to granted and duplicate results are ignored`() {
        val fixture = Fixture()
        fixture.owner.begin(fixture.request())
        fixture.owner.acceptResult(GRANTED, "projection")
        fixture.owner.acceptResult(GRANTED, "duplicate")
        assertEquals(listOf(GRANTED to "projection"), fixture.grants)
        assertEquals(DshScreenCaptureConsentPhase.GRANTED, fixture.consent.state.value.phase)
    }

    @Test
    fun `a positive system result without a real projection ends as typed rejection`() {
        val fixture = Fixture(grantResult = false)
        fixture.owner.begin(fixture.request())
        fixture.owner.acceptResult(GRANTED, "projection")
        assertEquals(DshScreenCaptureConsentPhase.REJECTED, fixture.consent.state.value.phase)
        assertEquals(DshScreenCaptureConsentCodes.CONSENT_REQUIRED, fixture.consent.state.value.code)
    }

    @Test
    fun `a cancelled or empty result releases the service without handing over a payload`() {
        listOf(CANCELLED to "discarded", GRANTED to null).forEach { (code, payload) ->
            val fixture = Fixture()
            fixture.owner.begin(fixture.request())
            fixture.owner.acceptResult(code, payload)
            assertEquals(listOf(code to null), fixture.grants)
            assertEquals(DshScreenCaptureConsentPhase.CANCELLED, fixture.consent.state.value.phase)
            assertEquals(DshScreenCaptureConsentCodes.CONSENT_REQUIRED, fixture.consent.state.value.code)
        }
    }

    @Test
    fun `a stopped host retires its pending dialog and a late result never arms capture`() {
        val fixture = Fixture()
        fixture.owner.begin(fixture.request())
        fixture.consent.onHostStopped()
        fixture.owner.acceptResult(GRANTED, "stale projection")
        fixture.owner.closePending()
        assertTrue(fixture.grants.isEmpty())
        assertEquals(DshScreenCaptureConsentCodes.HOST_STOPPED, fixture.consent.state.value.code)
    }

    @Test
    fun `teardown cancels unanswered consent even when platform cleanup throws`() {
        val fixture = Fixture(grantThrows = true)
        fixture.owner.begin(fixture.request())
        fixture.owner.closePending()
        fixture.owner.closePending()
        assertEquals(listOf(CANCELLED to null), fixture.grants)
        assertEquals(DshScreenCaptureConsentPhase.CANCELLED, fixture.consent.state.value.phase)
    }

    @Test
    fun `construction and teardown with no claimed request do not create capture resources`() {
        val fixture = Fixture()
        fixture.owner.closePending()
        fixture.request()
        fixture.owner.closePending()
        assertTrue(fixture.grants.isEmpty())
        assertEquals(DshScreenCaptureConsentPhase.REQUESTED, fixture.consent.state.value.phase)
    }

    private class Fixture(
        private val grantResult: Boolean = true,
        private val grantThrows: Boolean = false,
    ) {
        val consent = DshScreenCaptureConsentController()
        val grants = mutableListOf<Pair<Int, String?>>()
        val owner = DshScreenCaptureConsentOwner<String>(
            consent = consent,
            grantCapture = { code, data ->
                grants += code to data
                if (grantThrows) error("platform cleanup failed")
                grantResult
            },
            grantedResultCode = GRANTED,
            cancelledResultCode = CANCELLED,
        )

        fun request(): Long {
            assertTrue(consent.request())
            return consent.state.value.requestId
        }
    }

    private companion object {
        const val GRANTED = -1
        const val CANCELLED = 0
    }
}

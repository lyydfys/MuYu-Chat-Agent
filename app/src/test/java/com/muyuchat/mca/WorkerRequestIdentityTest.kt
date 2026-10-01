package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the request-id recovery used when a worker request cannot be parsed.
 *
 * The three previous copies were byte-identical, so this does not cover behaviour that was
 * previously untested. It exists because the recovery rule is now shared by the image worker,
 * the QNN dry-run worker and the tuning probe, and an error attributed one way in one worker
 * and another way elsewhere is hard to trace. If a future change makes any of these cases
 * behave differently, it should be a deliberate decision made in one place.
 */
class WorkerRequestIdentityTest {

    @Test
    fun `a parseable payload yields its request id`() {
        assertEquals("req-1", requestIdFromMalformedPayload("""{"requestId":"req-1"}"""))
    }

    @Test
    fun `an unparseable payload yields the empty string rather than throwing`() {
        // The whole point of the helper: the payload is already known to be broken, so
        // recovery must not add a second failure on top of the first.
        assertEquals("", requestIdFromMalformedPayload("not json at all"))
        assertEquals("", requestIdFromMalformedPayload("{"))
        assertEquals("", requestIdFromMalformedPayload(""))
    }

    @Test
    fun `a payload without a request id yields the empty string`() {
        assertEquals("", requestIdFromMalformedPayload("""{"other":"value"}"""))
        assertEquals("", requestIdFromMalformedPayload("[]"))
    }

    @Test
    fun `the empty string is the single unknown marker`() {
        // Callers treat "" as "attribution unknown". A null would have forced every call site
        // to handle two absent cases, so the contract is one marker, not two.
        val fromBroken = requestIdFromMalformedPayload("{{{")
        val fromMissing = requestIdFromMalformedPayload("{}")
        assertEquals(fromBroken, fromMissing)
        assertEquals("", fromBroken)
    }
}

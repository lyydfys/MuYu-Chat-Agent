package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshUrlRedactionTest {

    private val secret = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0"

    @Test
    fun queryAndFragmentAreAlwaysRedacted() {
        val redacted = DshUrlRedaction.redact("http://127.0.0.1:34567/?token=$secret")
        assertEquals("http://127.0.0.1:34567/?<redacted>", redacted)
        assertFalse(redacted.contains(secret))
        assertTrue(redacted.startsWith("http://127.0.0.1:34567/"))

        val withFragment = DshUrlRedaction.redact("http://127.0.0.1:34567/a#ticket=$secret")
        assertFalse(withFragment.contains(secret))
        assertTrue(withFragment.endsWith("#<redacted>"))
    }

    @Test
    fun highEntropyPathSegmentsAreRedacted() {
        val redacted = DshUrlRedaction.redact("http://127.0.0.1:34567/auth/$secret")
        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains("<redacted>"))
        // Normal route segments stay readable for diagnostics.
        assertEquals(
            "http://127.0.0.1:34567/session/42",
            DshUrlRedaction.redact("http://127.0.0.1:34567/session/42")
        )
    }

    @Test
    fun textRedactionRemovesAssignmentsAndEntropyRuns() {
        val redacted = DshUrlRedaction.redactText(
            "failed token=$secret&x=1 Authorization=Bearer abcdefghijklmnopqrstuvwxyz0123456789"
        )
        assertFalse(redacted.contains(secret))
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(redacted.contains("token=<redacted>"))
    }

    @Test
    fun emptyAndMalformedInputsStayLogSafe() {
        assertEquals("", DshUrlRedaction.redact(null))
        assertEquals("", DshUrlRedaction.redact(""))
        assertEquals("", DshUrlRedaction.redactText("  "))
        // No exception and no secret survives even when the input is not a URL.
        assertFalse(DshUrlRedaction.redact("not a url token=$secret").contains(secret))
    }
}

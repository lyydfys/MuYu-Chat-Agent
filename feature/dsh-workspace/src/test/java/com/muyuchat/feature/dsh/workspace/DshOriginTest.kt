package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshOriginTest {

    @Test
    fun parseAcceptsExactLoopbackOrigin() {
        assertEquals(
            "http://127.0.0.1:34567",
            DshOrigin.parse("http://127.0.0.1:34567")?.value
        )
        // A trailing slash is still an origin and is normalized away.
        assertEquals(
            "http://127.0.0.1:8000",
            DshOrigin.parse("  http://127.0.0.1:8000/  ")?.value
        )
    }

    @Test
    fun parseRejectsAnythingThatIsNotTheExactHttpLoopbackOrigin() {
        val rejected = listOf(
            null,
            "",
            "https://127.0.0.1:34567",
            "http://localhost:34567",
            "http://0.0.0.0:34567",
            "http://[::1]:34567",
            "http://127.0.0.1",
            "http://127.0.0.1:0",
            "http://127.0.0.1:65536",
            "http://127.0.0.1:34567/path",
            "http://127.0.0.1:34567?x=1",
            "http://127.0.0.1:34567#frag",
            "http://user:pass@127.0.0.1:34567",
            "http://127.0.0.1.evil.example:34567",
            "http://127.0.0.1:34567@evil.example"
        )
        rejected.forEach { raw ->
            assertNull("must reject $raw", DshOrigin.parse(raw))
            assertNull(DshOrigin.parse(raw)?.value)
        }
    }

    @Test
    fun matchesRequiresExactSchemeHostAndPort() {
        val origin = DshOrigin.parse("http://127.0.0.1:34567")!!
        assertTrue(origin.matches("http://127.0.0.1:34567"))
        assertTrue(origin.matches("http://127.0.0.1:34567/"))
        assertTrue(origin.matches("http://127.0.0.1:34567/session/42?tab=chat#frag"))
        assertFalse(origin.matches("http://127.0.0.1:34568/"))
        assertFalse(origin.matches("https://127.0.0.1:34567/"))
        assertFalse(origin.matches("http://localhost:34567/"))
        assertFalse(origin.matches("http://127.0.0.1:34567.evil.example/"))
        assertFalse(origin.matches("http://user:pass@127.0.0.1:34567/"))
        assertFalse(origin.matches("javascript:alert(1)"))
        assertFalse(origin.matches(null))
        assertFalse(origin.matches("not a url"))
        assertFalse(origin.matches(""))
    }

    @Test
    fun pathUrlAppendsOnlySameOriginPaths() {
        val origin = DshOrigin.parse("http://127.0.0.1:12345")!!
        assertEquals("http://127.0.0.1:12345/", origin.pathUrl("/"))
        assertEquals("http://127.0.0.1:12345/session/1", origin.pathUrl("/session/1"))
    }
}

package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.runtime.log.DshLogRedactor
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshAuthEntryParserTest {

    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-"

    @Test
    fun parsesOfficialReadinessLine() {
        val entry = DshAuthEntryParser.parseLine("dsh web: http://127.0.0.1:43223/?token=$token")
        assertNotNull(entry)
        assertEquals("http://127.0.0.1:43223", entry!!.origin)
        assertEquals(43223, entry.port)
        assertTrue(entry.authenticatedUrl.endsWith("?token=$token"))
    }

    @Test
    fun ignoresUnrelatedProviderOutput() {
        assertNull(DshAuthEntryParser.parseLine("listening on http://127.0.0.1:1234/"))
        assertNull(DshAuthEntryParser.parseLine("dsh web: https://127.0.0.1:1234/?token=$token"))
        assertNull(DshAuthEntryParser.parseLine("dsh web: http://10.0.0.2:1234/?token=$token"))
        assertNull(DshAuthEntryParser.parseLine("dsh web: http://127.0.0.1:1234/?token=short"))
        assertNull(DshAuthEntryParser.parseLine("dsh web: http://127.0.0.1:1234/"))
        assertNull(DshAuthEntryParser.parseLine("dsh web: http://127.0.0.1:1234/path?token=$token"))
    }

    @Test
    fun rejectsLoopbackLookalikes() {
        assertNull(DshAuthEntryParser.parseUrl("http://127.0.0.1.evil.example:1234/?token=$token"))
        assertNull(DshAuthEntryParser.parseUrl("http://127.0.0.1:0/?token=$token"))
        assertNull(DshAuthEntryParser.parseUrl("http://127.0.0.1:99999/?token=$token"))
    }

    @Test
    fun redactsEveryTokenQueryValue() {
        val raw = "dsh web: http://127.0.0.1:1/?token=$token and x?token=$token"
        val safe = DshAuthEntryParser.redact(raw)
        assertFalse(safe.contains(token))
        assertEquals(2, Regex("<redacted>").findAll(safe).count())
    }
}

class DshLogRedactorTest {

    @Test
    fun redactsTokensBearerAndKeyValues() {
        val token = "secret-token-value-1234"
        val line = "url=http://127.0.0.1:1/?token=$token Authorization: Bearer $token api_key=$token"
        val safe = DshLogRedactor.redact(line)
        assertFalse(safe.contains(token))
        assertTrue(safe.contains("<redacted>"))
    }

    @Test
    fun boundsLineLength() {
        val safe = DshLogRedactor.redact("x".repeat(10_000), maxChars = 128)
        assertTrue(safe.length <= 129)
    }

    @Test
    fun outputRingIsBoundedAndRedacted() {
        val ring = DshOutputRing(capacity = 3)
        ring.add("one ?token=aaaaaaaaaaaaaaaa")
        ring.add("two")
        ring.add("three")
        ring.add("?token=bbbbbbbbbbbbbbbb")
        assertEquals(4, ring.totalLines())
        assertEquals(1, ring.droppedLines())
        val snapshot = ring.snapshot()
        assertEquals(3, snapshot.size)
        assertFalse(snapshot.joinToString().contains("aaaaaaaaaaaaaaaa"))
        assertFalse(snapshot.joinToString().contains("bbbbbbbbbbbbbbbb"))
    }
}

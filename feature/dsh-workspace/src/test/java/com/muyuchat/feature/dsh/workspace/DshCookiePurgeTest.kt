package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCookiePurgeTest {

    @Test
    fun eachCookieBecomesAnExpiredFirstPartyCookie() {
        val headers = DshCookiePurge.expiryHeaders(
            "dsh-auth-abc=secret; dsh-auth-def=other"
        )
        assertEquals(2, headers.size)
        assertTrue(headers[0].startsWith("dsh-auth-abc=;"))
        assertTrue(headers[0].contains("Max-Age=0"))
        assertTrue(headers[0].contains("Path=/"))
        assertFalse(headers[0].contains("secret"))
        assertTrue(headers[1].startsWith("dsh-auth-def=;"))
    }

    @Test
    fun malformedNamesAreIgnoredAndValuesNeverAppear() {
        val header = "=; ;ok=1; bad name=2; good-name=3"
        val headers = DshCookiePurge.expiryHeaders(header)
        assertEquals(listOf("ok", "good-name"), DshCookiePurge.cookieNames(header))
        assertEquals(2, headers.size)
        headers.forEach { expiry ->
            assertFalse(expiry.contains("=1"))
            assertFalse(expiry.contains("=2"))
            assertFalse(expiry.contains("=3"))
        }
    }

    @Test
    fun emptyInputProducesNothing() {
        assertTrue(DshCookiePurge.expiryHeaders(null).isEmpty())
        assertTrue(DshCookiePurge.expiryHeaders("").isEmpty())
        assertTrue(DshCookiePurge.expiryHeaders("   ").isEmpty())
        assertTrue(DshCookiePurge.cookieNames(null).isEmpty())
        assertNull(DshCookiePurge.cookieNames(null).firstOrNull())
    }
}

class DshNavigationCodecTest {

    @Test
    fun roundTripKeepsOnlyThePath() {
        val encoded = DshNavigationCodec.encode("/session/42")
        assertEquals("dsh-nav-v1\n/session/42\n", encoded)
        assertEquals("/session/42", DshNavigationCodec.decode(encoded))
        assertEquals("/", DshNavigationCodec.decode(DshNavigationCodec.encode("/")))
    }

    @Test
    fun corruptOrUnsafeFilesDecodeToNull() {
        assertNull(DshNavigationCodec.decode(null))
        assertNull(DshNavigationCodec.decode(""))
        assertNull(DshNavigationCodec.decode("garbage"))
        assertNull(DshNavigationCodec.decode("dsh-nav-v1\nhttps://evil.example/"))
        assertNull(DshNavigationCodec.decode("dsh-nav-v1\n/a?token=leak"))
        assertNull(DshNavigationCodec.decode("dsh-nav-v1\n/a\n/b"))
        assertNull(DshNavigationCodec.encode("https://evil.example/"))
        assertNull(DshNavigationCodec.encode("/a?token=leak"))
    }

    @Test
    fun crlfAndTrailingNewlinesAreTolerated() {
        assertEquals(
            "/a/b",
            DshNavigationCodec.decode("dsh-nav-v1\r\n/a/b\r\n")
        )
    }

    @Test
    fun inMemoryStoreAppliesTheSameValidation() = kotlinx.coroutines.runBlocking {
        val store = InMemoryDshNavigationStore("https://evil.example/")
        assertNull(store.storedPath)
        assertNull(store.readLastPath())
        store.writeLastPath("/session/1")
        assertEquals("/session/1", store.readLastPath())
        store.writeLastPath("/session/1?token=leak")
        assertNull(store.readLastPath())
    }
}

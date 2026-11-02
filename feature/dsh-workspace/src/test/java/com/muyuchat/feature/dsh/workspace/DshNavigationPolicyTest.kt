package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshNavigationPolicyTest {

    private val origin = DshOrigin.parse("http://127.0.0.1:34567")!!

    @Test
    fun sameOriginHttpStaysInsideTheWebView() {
        assertEquals(
            DshNavigationDecision.KeepInsideWebView,
            DshNavigationPolicy.classify("http://127.0.0.1:34567/session/1", origin)
        )
        assertEquals(
            DshNavigationDecision.KeepInsideWebView,
            DshNavigationPolicy.classify("http://127.0.0.1:34567/?token=secret", origin)
        )
    }

    @Test
    fun externalHttpAndHttpsAreRoutedToAndroid() {
        val external = "https://example.com/docs?q=1"
        assertEquals(
            DshNavigationDecision.OpenExternally(external),
            DshNavigationPolicy.classify(external, origin)
        )
        assertEquals(
            DshNavigationDecision.OpenExternally("http://other.example/"),
            DshNavigationPolicy.classify("http://other.example/", origin)
        )
        // A different loopback port is a different origin: it must not inherit this session.
        assertEquals(
            DshNavigationDecision.OpenExternally("http://127.0.0.1:34568/"),
            DshNavigationPolicy.classify("http://127.0.0.1:34568/", origin)
        )
    }

    @Test
    fun nonHttpSchemesAndCredentialUrlsAreBlocked() {
        val blockedNonHttp = listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///sdcard/secret.txt",
            "content://com.example/secret",
            "blob:http://127.0.0.1:34567/uuid",
            "data:text/html,<h1>x</h1>",
            "about:blank",
            "ftp://example.com/file"
        )
        blockedNonHttp.forEach { url ->
            assertEquals(
                "must block $url",
                DshNavigationDecision.Blocked(DshBlockReason.NON_HTTP_SCHEME),
                DshNavigationPolicy.classify(url, origin)
            )
        }
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.CREDENTIALS_IN_URL),
            DshNavigationPolicy.classify("http://user:pass@example.com/", origin)
        )
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.CREDENTIALS_IN_URL),
            DshNavigationPolicy.classify("http://user:pass@127.0.0.1:34567/", origin)
        )
    }

    @Test
    fun malformedLongAndControlCharacterUrlsAreBlocked() {
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL),
            DshNavigationPolicy.classify("", origin)
        )
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL),
            DshNavigationPolicy.classify("http://127.0.0.1:34567/a b", origin)
        )
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL),
            DshNavigationPolicy.classify("http://127.0.0.1:34567/a\nb", origin)
        )
        val longUrl = "https://example.com/" + "a".repeat(DshNavigationPolicy.MAX_EXTERNAL_URL_LENGTH)
        assertEquals(
            DshNavigationDecision.Blocked(DshBlockReason.URL_TOO_LONG),
            DshNavigationPolicy.classify(longUrl, origin)
        )
        assertFalse(DshNavigationPolicy.isOpenableExternalUrl("javascript:alert(1)"))
        assertTrue(DshNavigationPolicy.isOpenableExternalUrl("https://example.com/"))
    }

    @Test
    fun sanitizeRestorePathAcceptsOnlySameOriginPaths() {
        assertEquals("/", DshNavigationPolicy.sanitizeRestorePath("/"))
        assertEquals("/session/42", DshNavigationPolicy.sanitizeRestorePath("/session/42"))
        assertEquals("/a%20b", DshNavigationPolicy.sanitizeRestorePath("/a%20b"))
        val rejected = listOf(
            null,
            "",
            "session/42",
            "//evil.example/path",
            "https://example.com/path",
            "http://127.0.0.1:1/path",
            "/session/42?x=1",
            "/session/42#frag",
            "/a b",
            "/a\\b",
            "/a/../b",
            "/./a",
            "/a/%2e%2e/b",
            "/a/%2F/b",
            "/" + "a".repeat(DshNavigationPolicy.MAX_RESTORE_PATH_LENGTH)
        )
        rejected.forEach { raw ->
            assertNull("must reject $raw", DshNavigationPolicy.sanitizeRestorePath(raw))
        }
    }

    @Test
    fun restoreTargetsAreSameOriginOnly() {
        assertEquals(
            "http://127.0.0.1:34567/session/7",
            DshNavigationPolicy.restoreUrl(origin, "/session/7")
        )
        assertNull(DshNavigationPolicy.restoreUrl(origin, "https://evil.example/"))
        assertNull(DshNavigationPolicy.restoreUrl(origin, "/a?token=x"))
    }

    @Test
    fun launchTokenIsDetectedButNeverReturned() {
        assertTrue(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/?token=abc"))
        assertTrue(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/?TOKEN=abc"))
        assertTrue(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/?a=1&token=abc"))
        assertFalse(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/"))
        assertFalse(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/?page=2"))
        assertFalse(DshNavigationPolicy.hasLaunchTokenQuery("http://127.0.0.1:34567/#token=abc"))
        assertFalse(DshNavigationPolicy.hasLaunchTokenQuery(null))

        assertTrue(
            DshNavigationPolicy.isCleanAuthenticatedUrl("http://127.0.0.1:34567/", origin)
        )
        assertFalse(
            DshNavigationPolicy.isCleanAuthenticatedUrl("http://127.0.0.1:34567/?token=x", origin)
        )
        assertFalse(
            DshNavigationPolicy.isCleanAuthenticatedUrl("https://127.0.0.1:34567/", origin)
        )
    }

    @Test
    fun pathForRestoreReturnsCleanSameOriginPathsOnly() {
        assertEquals(
            "/session/9",
            DshNavigationPolicy.pathForRestore("http://127.0.0.1:34567/session/9", origin)
        )
        assertEquals(
            "/",
            DshNavigationPolicy.pathForRestore("http://127.0.0.1:34567/", origin)
        )
        assertNull(
            DshNavigationPolicy.pathForRestore("http://127.0.0.1:34567/?token=x", origin)
        )
        assertNull(
            DshNavigationPolicy.pathForRestore("http://127.0.0.1:34567/session/9?x=1", origin)
        )
        assertNull(
            DshNavigationPolicy.pathForRestore("https://127.0.0.1:34567/session/9", origin)
        )
        assertNull(
            DshNavigationPolicy.pathForRestore("http://127.0.0.1:34568/session/9", origin)
        )
    }

    @Test
    fun restoreIsSkippedWhenAlreadyThere() {
        assertNull(
            DshNavigationPolicy.restoreTargetIfNeeded(
                origin,
                "http://127.0.0.1:34567/",
                "/"
            )
        )
        assertNull(
            DshNavigationPolicy.restoreTargetIfNeeded(
                origin,
                "http://127.0.0.1:34567/session/1",
                "/session/1"
            )
        )
        assertEquals(
            "http://127.0.0.1:34567/session/2",
            DshNavigationPolicy.restoreTargetIfNeeded(
                origin,
                "http://127.0.0.1:34567/",
                "/session/2"
            )
        )
        assertNull(
            DshNavigationPolicy.restoreTargetIfNeeded(
                origin,
                "http://127.0.0.1:34567/",
                "/session/2?token=leak"
            )
        )
    }
}

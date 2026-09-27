package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalBrowserUrlPolicyTest {
    @Test
    fun initialUrlRequiresHttpsAndHost() {
        assertTrue(InternalBrowserUrlPolicy.normalizeInitialUrl("https://example.com/a") != null)
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("http://example.com/a"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("javascript:alert(1)"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("intent://example.com/a"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("file:///sdcard/a"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("mailto:test@example.com"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("https://user:pass@example.com/a"))
    }

    @Test
    fun navigationStaysWithinTheExplicitSourceHost() {
        val initial = "https://www.example.com/results?q=mca"
        assertTrue(InternalBrowserUrlPolicy.allowsNavigation(initial, "https://example.com/docs"))
        assertTrue(InternalBrowserUrlPolicy.allowsNavigation(initial, "https://sub.example.com/docs"))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation(initial, "https://example.com.evil.test/"))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation(initial, "http://example.com/docs"))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation(initial, "https://other.test/docs"))
    }

    @Test
    fun searchUrlIsHttpsAndQueryIsEncoded() {
        val url = InternalBrowserUrlPolicy.buildSearchUrl(
            "https://www.google.com/search?q={query}",
            "MCA 本地生图 & API"
        )
        assertTrue(url?.startsWith("https://www.google.com/search?q=MCA+%E6%9C%AC%E5%9C%B0%E7%94%9F%E5%9B%BE+%26+API") == true)
        assertNull(InternalBrowserUrlPolicy.buildSearchUrl("http://example.com/?q={query}", "mca"))
        assertNull(InternalBrowserUrlPolicy.buildSearchUrl("https://example.com/search", "mca"))
        assertNull(InternalBrowserUrlPolicy.buildSearchUrl("https://example.com/search?q={query}", " "))
    }

    @Test
    fun searchHostMayBeExplicitlyAllowedWithoutOpeningAnArbitraryHost() {
        val source = "https://docs.example.com/article"
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation(source, "https://www.google.com/search?q=mca"))
        assertTrue(
            InternalBrowserUrlPolicy.allowsNavigation(
                source,
                "https://www.google.com/search?q=mca",
                setOf("google.com")
            )
        )
        assertFalse(
            InternalBrowserUrlPolicy.allowsNavigation(
                source,
                "https://evilgoogle.com/search?q=mca",
                setOf("google.com")
            )
        )
    }

    @Test
    fun approvedOriginDoesNotGrantAnotherPortOrSubdomain() {
        val origins = setOf("https://result.test:8443")
        assertTrue(InternalBrowserUrlPolicy.allowsNavigation("https://search.test", "https://result.test:8443/article",
            additionalApprovedOrigins = origins))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation("https://search.test", "https://result.test/article",
            additionalApprovedOrigins = origins))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation("https://search.test", "https://sub.result.test:8443/article",
            additionalApprovedOrigins = origins))
        assertFalse(InternalBrowserUrlPolicy.allowsNavigation("https://search.test", "https://search.test:8443/article"))
        assertNull(InternalBrowserUrlPolicy.normalizeInitialUrl("https://example.com/a\nb"))
    }
}

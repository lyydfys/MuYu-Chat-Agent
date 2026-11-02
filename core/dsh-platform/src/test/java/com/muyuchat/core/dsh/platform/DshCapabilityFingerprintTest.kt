package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityFingerprintTest {

    @Test
    fun samePayloadProducesTheSameDigest() {
        val first = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            mapOf("a" to "1", "b" to "2"),
            "{\"body\":true}"
        )
        val second = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            mapOf("b" to "2", "a" to "1"),
            "{\"body\":true}"
        )

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun methodPathQueryAndBodyAllAffectTheDigest() {
        val base = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            mapOf("a" to "1"),
            "{}"
        )

        assertNotEquals(
            base,
            capabilityPayloadFingerprint(DshCapabilityHttpMethod.GET, "/notify", mapOf("a" to "1"), "{}")
        )
        assertNotEquals(
            base,
            capabilityPayloadFingerprint(DshCapabilityHttpMethod.POST, "/usage", mapOf("a" to "1"), "{}")
        )
        assertNotEquals(
            base,
            capabilityPayloadFingerprint(DshCapabilityHttpMethod.POST, "/notify", mapOf("a" to "2"), "{}")
        )
        assertNotEquals(
            base,
            capabilityPayloadFingerprint(DshCapabilityHttpMethod.POST, "/notify", mapOf("a" to "1"), "{\"x\":1}")
        )
    }

    @Test
    fun concatenationAmbiguityIsNotPossible() {
        val joined = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            mapOf("ab" to "c"),
            ""
        )
        val split = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            mapOf("a" to "bc"),
            ""
        )

        assertNotEquals(joined, split)
    }
}

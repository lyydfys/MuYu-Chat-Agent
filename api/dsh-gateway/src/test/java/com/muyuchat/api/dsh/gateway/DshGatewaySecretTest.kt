package com.muyuchat.api.dsh.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshGatewaySecretTest {

    @Test
    fun tokensUseThePrefixAndFullEntropy() {
        val tokens = (1..64).map { DshGatewaySecret.newBearerToken() }
        assertEquals(64, tokens.toSet().size)
        tokens.forEach { token ->
            assertTrue(token.startsWith("dshg_"))
            // 32 random bytes base64url without padding is 43 characters.
            assertTrue(token.length >= "dshg_".length + 43)
        }
    }

    @Test
    fun tokenComparisonIsExactAndFailClosed() {
        val token = DshGatewaySecret.newBearerToken()
        assertTrue(DshGatewaySecret.matches(token, token))
        assertFalse(DshGatewaySecret.matches(token, token + "x"))
        assertFalse(DshGatewaySecret.matches(token, token.dropLast(1)))
        assertFalse(DshGatewaySecret.matches(token, null))
        assertFalse(DshGatewaySecret.matches(token, ""))
        assertFalse(DshGatewaySecret.matches("", ""))
    }

    @Test
    fun redactionRemovesTheSecretEverywhere() {
        val token = DshGatewaySecret.newBearerToken()
        val text = "header=Bearer $token; body=$token"
        val redacted = DshGatewaySecret.redact(text, token)
        assertFalse(redacted.contains(token))
        assertEquals("header=Bearer [redacted]; body=[redacted]", redacted)
    }
}

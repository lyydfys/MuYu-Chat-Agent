package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityErrorEnvelopeTest {

    @Test
    fun everyTypedFailureMapsToAConcreteHttpStatus() {
        assertEquals(401, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.UNAUTHORIZED))
        assertEquals(409, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.STALE_GENERATION))
        assertEquals(400, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.INVALID_REQUEST))
        assertEquals(403, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.PERMISSION_DENIED))
        assertEquals(403, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE))
        assertEquals(413, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.TOO_LARGE))
        assertEquals(429, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.RATE_LIMITED))
        assertEquals(504, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.TIMEOUT))
        assertEquals(409, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.REQUEST_IN_PROGRESS))
        assertEquals(409, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.OUTCOME_UNKNOWN))
        assertEquals(500, DshCapabilityErrorEnvelope.statusFor(DshCapabilityErrorCode.INTERNAL))
    }

    @Test
    fun errorDocumentMatchesTheOfficialPackagesOkAndErrorContract() {
        val result = DshCapabilityErrorEnvelope.error(
            DshCapabilityErrorCode.UNAUTHORIZED,
            "capability credential was rejected"
        )

        assertEquals(401, result.statusCode)
        assertTrue(result.bodyJson.startsWith("{\"ok\":false,\"error\":"))
        assertTrue(result.bodyJson.contains("\"code\":\"UNAUTHORIZED\""))
        assertTrue(result.bodyJson.contains("\"status\":401"))
        assertTrue(result.extraHeaders.isEmpty())
    }

    @Test
    fun retryDelayIsExposedBothInBodyAndHeader() {
        val result = DshCapabilityErrorEnvelope.error(
            DshCapabilityErrorCode.RATE_LIMITED,
            "slow down",
            retryAfterMillis = 2_500L
        )

        assertTrue(result.bodyJson.contains("\"retryAfterMs\":2500"))
        assertEquals("3", result.extraHeaders["Retry-After"])
    }

    @Test
    fun quoteEscapesJsonControlCharacters() {
        val quoted = DshCapabilityErrorEnvelope.quote("a\"b\\c\nd\re\tf\u0001g")

        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\"", quoted)
    }

    @Test
    fun statusReasonPhrasesCoverTheEmittedCodes() {
        assertEquals("Conflict", DshCapabilityHttpStatus.reason(409))
        assertEquals("Method Not Allowed", DshCapabilityHttpStatus.reason(405))
        assertEquals("Too Many Requests", DshCapabilityHttpStatus.reason(429))
        assertEquals("Status 499", DshCapabilityHttpStatus.reason(499))
    }

    @Test
    fun errorMessagesStayInsideTheDocumentWhenTheyContainBraces() {
        val result = DshCapabilityErrorEnvelope.error(
            DshCapabilityErrorCode.INVALID_REQUEST,
            "unexpected } token {"
        )

        assertTrue(result.bodyJson.contains("unexpected } token {"))
        assertEquals(1, result.bodyJson.split("\"ok\":false").size - 1)
        assertTrue(result.bodyJson.endsWith("\"status\":400}"))
    }
}

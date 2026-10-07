package com.muyuchat.core.engine.contract

import com.muyuchat.core.engine.contract.error.ErrorCatalogRegistry
import com.muyuchat.core.engine.contract.error.StableErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCatalogTest {

    @Test
    fun `error catalog registry covers all declared StableErrorCode values`() {
        val allCodes = StableErrorCode.entries
        val catalogEntries = ErrorCatalogRegistry.allEntries()

        assertEquals(allCodes.size, catalogEntries.size)

        for (code in allCodes) {
            val entry = ErrorCatalogRegistry.get(code)
            assertNotNull("Missing entry for code: $code", entry)
            assertEquals(code, entry.code)
            assertTrue("userMessageKey must not be blank for $code", entry.userMessageKey.isNotBlank())
            assertTrue("publicMessage must not be blank for $code", entry.publicMessage.isNotBlank())
        }
    }

    @Test
    fun `sanitizeForPublicApi strips internal codes when disabled`() {
        val envelope = ErrorCatalogRegistry.createEnvelope(
            code = StableErrorCode.RT_OUT_OF_MEMORY,
            nativeCode = 137,
            httpCode = 503,
            osCode = 12
        )

        // Native/OS codes hidden by default, HTTP code preserved
        val publicSanitized = envelope.sanitizeForPublicApi(
            allowNativeCode = false,
            allowHttpCode = true,
            allowOsCode = false
        )

        assertNull(publicSanitized.nativeCode)
        assertEquals(503, publicSanitized.httpCode)
        assertNull(publicSanitized.osCode)
    }

    @Test
    fun `createEnvelope inherits default category and phase from catalog`() {
        val envelope = ErrorCatalogRegistry.createEnvelope(StableErrorCode.MDL_NOT_FOUND)

        assertEquals(ErrorCategory.Model, envelope.category)
        assertEquals(ErrorPhase.Load, envelope.phase)
        assertEquals(Retryability.UserAction, envelope.retryability)
        assertEquals("error.model.not_found", envelope.userMessageKey)
    }
}

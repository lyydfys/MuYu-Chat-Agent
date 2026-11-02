package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshWebErrorClassifierTest {

    @Test
    fun androidWebViewErrorCodesMapToStableKinds() {
        assertEquals(DshWebErrorKind.NETWORK_UNAVAILABLE, DshWebErrorClassifier.classify(-2))
        assertEquals(DshWebErrorKind.CONNECTION_REFUSED, DshWebErrorClassifier.classify(-6))
        assertEquals(DshWebErrorKind.NETWORK_UNAVAILABLE, DshWebErrorClassifier.classify(-7))
        assertEquals(DshWebErrorKind.TIMEOUT, DshWebErrorClassifier.classify(-8))
        assertEquals(DshWebErrorKind.OTHER, DshWebErrorClassifier.classify(0))
        assertEquals(DshWebErrorKind.OTHER, DshWebErrorClassifier.classify(-1))
    }

    @Test
    fun everyKindHasADistinctUserMessage() {
        val messages = DshWebErrorKind.values().map { kind ->
            DshWebErrorClassifier.messageFor(kind).also { assertTrue(it.isNotBlank()) }
        }
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { message ->
            assertNotEquals("", message)
            assertTrue(message.contains("DSH") || message.contains("网络") || message.contains("页面"))
        }
    }
}

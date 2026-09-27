package com.muyuchat.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtConversationScopeTest {
    @Test
    fun sameHistoryCannotReuseConversationAfterContextRevisionChanges() {
        assertTrue(liteRtConversationScopeMatches("session-a", "4", "session-a", "4"))
        assertFalse(liteRtConversationScopeMatches("session-a", "4", "session-a", "5"))
        assertFalse(liteRtConversationScopeMatches("session-a", "4", "session-b", "4"))
    }

    @Test
    fun statelessRequestsNeverShareNativeConversationScope() {
        assertFalse(liteRtConversationScopeMatches(null, null, null, null))
        assertFalse(liteRtConversationScopeMatches("", "4", "", "4"))
        assertFalse(liteRtConversationScopeMatches("session-a", "4", null, "4"))
    }

    @Test
    fun legacySessionRemainsReusableUntilExplicitRevisionIsIntroduced() {
        assertTrue(liteRtConversationScopeMatches("session-a", null, "session-a", null))
        assertFalse(liteRtConversationScopeMatches("session-a", null, "session-a", "1"))
        assertFalse(liteRtConversationScopeMatches("session-a", "1", "session-a", null))
    }
}

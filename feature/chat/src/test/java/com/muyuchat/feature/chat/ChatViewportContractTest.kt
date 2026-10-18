package com.muyuchat.feature.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewportContractTest {
    @Test
    fun `unmeasured composer keeps the previous first frame inset`() {
        assertEquals(100.dp, resolveChatComposerOverlapDp(0.dp))
        assertEquals(118.dp, resolveChatListBottomPaddingDp(0.dp))
    }

    @Test
    fun `measured composer is the sole overlap source`() {
        val measured = 74.dp
        assertEquals(measured, resolveChatComposerOverlapDp(measured))
        assertEquals(92.dp, resolveChatListBottomPaddingDp(measured))
    }

    @Test
    fun `tall status composer grows the list inset with the same overlap`() {
        val measured = 156.dp
        assertEquals(measured, resolveChatComposerOverlapDp(measured))
        assertEquals(174.dp, resolveChatListBottomPaddingDp(measured))
    }
}

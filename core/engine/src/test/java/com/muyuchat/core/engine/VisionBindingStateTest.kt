package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionBindingStateTest {
    @Test
    fun wireNamesRemainStableAcrossUiAndApiBoundaries() {
        assertEquals(
            listOf("unbound", "bound_pending_reload", "loading", "ready", "load_failed"),
            VisionBindingState.values().map(VisionBindingState::wireName)
        )
    }
}

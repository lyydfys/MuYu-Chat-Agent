package com.muyuchat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPrefixCacheSettingsUiStateTest {
    @Test
    fun exposesPersistentPrefixCacheControlsAsDirectSettingsValues() {
        val state = SettingsUiState(
            persistentPrefixCacheEnabled = false,
            persistentPrefixCacheEntryCount = 3,
            persistentPrefixCacheBytes = 2_048L
        )

        assertFalse(state.persistentPrefixCacheEnabled)
        assertEquals(3, state.persistentPrefixCacheEntryCount)
        assertEquals(2_048L, state.persistentPrefixCacheBytes)
    }

    @Test
    fun defaultsToAnEnabledEmptyPersistentPrefixCache() {
        val state = SettingsUiState()

        assertTrue(state.persistentPrefixCacheEnabled)
        assertEquals(0, state.persistentPrefixCacheEntryCount)
        assertEquals(0L, state.persistentPrefixCacheBytes)
    }
}

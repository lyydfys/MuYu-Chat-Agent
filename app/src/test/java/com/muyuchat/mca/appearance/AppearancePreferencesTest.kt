package com.muyuchat.mca.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codec contract for [AppearanceThemeMode] and [AppearancePreferences].
 *
 * The store is in-memory on purpose: the SharedPreferences adapter is a five-line pass-through and
 * carries no logic worth a Robolectric dependency, while everything that can actually go wrong lives in
 * the codec and the key discipline exercised here.
 */
class AppearancePreferencesTest {

    @Test
    fun `a store with nothing written reads as system`() {
        val preferences = AppearancePreferences(MemoryAppearanceStore())

        assertEquals(AppearanceThemeMode.SYSTEM, preferences.loadThemeMode())
    }

    @Test
    fun `every mode survives a write and a read`() {
        val store = MemoryAppearanceStore()
        val preferences = AppearancePreferences(store)

        AppearanceThemeMode.entries.forEach { mode ->
            preferences.saveThemeMode(mode)
            assertEquals(mode, preferences.loadThemeMode())
        }
    }

    @Test
    fun `a stored mode is written under the appearance theme mode key`() {
        val store = MemoryAppearanceStore()

        AppearancePreferences(store).saveThemeMode(AppearanceThemeMode.DARK)

        assertEquals("dark", store.values[AppearancePreferences.KEY_THEME_MODE])
        assertEquals(setOf(AppearancePreferences.KEY_THEME_MODE), store.values.keys)
    }

    @Test
    fun `dirty stored text falls back to system`() {
        listOf("purple", "", "  ", "darkk", "1", "system-light").forEach { dirty ->
            assertEquals(
                "dirty value $dirty must not choose a mode",
                AppearanceThemeMode.SYSTEM,
                AppearanceThemeMode.fromWire(dirty)
            )
        }
    }

    @Test
    fun `a missing stored value falls back to system`() {
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromWire(null))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearancePreferences(MemoryAppearanceStore()).loadThemeMode())
    }

    @Test
    fun `stored text is matched case insensitively and trimmed`() {
        assertEquals(AppearanceThemeMode.DARK, AppearanceThemeMode.fromWire("Dark"))
        assertEquals(AppearanceThemeMode.DARK, AppearanceThemeMode.fromWire(" dark "))
        assertEquals(AppearanceThemeMode.LIGHT, AppearanceThemeMode.fromWire("LIGHT"))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromWire("System\n"))
    }

    @Test
    fun `wire names are unique and lower case`() {
        val names = AppearanceThemeMode.entries.map { it.wireName }

        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertEquals("wire name $name must already be normalised", name, name.lowercase())
            assertEquals("wire name $name must not need trimming", name, name.trim())
        }
    }

    @Test
    fun `saving the theme leaves unrelated keys in the same store alone`() {
        val store = MemoryAppearanceStore(mapOf("some_future_setting" to "keep-me"))
        val preferences = AppearancePreferences(store)

        preferences.saveThemeMode(AppearanceThemeMode.LIGHT)

        assertEquals("keep-me", store.values["some_future_setting"])
        assertEquals("light", store.values[AppearancePreferences.KEY_THEME_MODE])
    }

    @Test
    fun `clearing the theme removes only the theme key`() {
        val store = MemoryAppearanceStore(
            mapOf(
                AppearancePreferences.KEY_THEME_MODE to AppearanceThemeMode.DARK.wireName,
                "some_future_setting" to "keep-me"
            )
        )

        store.write(AppearancePreferences.KEY_THEME_MODE, null)

        assertNull(store.values[AppearancePreferences.KEY_THEME_MODE])
        assertEquals("keep-me", store.values["some_future_setting"])
    }

    @Test
    fun `the appearance store name is not the chat appearance store name`() {
        // The chat background record lives in mca_chat_appearance. Both names are user-visible only via
        // behaviour, but a collision would silently make a theme write touch background image state.
        assertEquals("mca_appearance", SharedPreferencesAppearanceStore.PREFERENCES_NAME)
        assertEquals(false, SharedPreferencesAppearanceStore.PREFERENCES_NAME.contains("chat"))
    }
}

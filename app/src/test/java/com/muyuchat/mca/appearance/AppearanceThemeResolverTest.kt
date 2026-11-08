package com.muyuchat.mca.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table of [AppearanceThemeResolver].
 *
 * "跟随系统" must equal the platform setting; the two pinned modes must ignore it, because a user who
 * picked 浅色 did so *because* the system is dark and reading this app in dark mode is what they wanted
 * to avoid. That is the whole contract of this file.
 */
class AppearanceThemeResolverTest {

    @Test
    fun `跟随系统时深色由系统决定`() {
        assertTrue(
            "系统深色时跟随系统必须是深色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = AppearanceThemeMode.SYSTEM)
        )
        assertFalse(
            "系统浅色时跟随系统必须是浅色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = AppearanceThemeMode.SYSTEM)
        )
    }

    @Test
    fun `固定浅色时即使系统深色也用浅色`() {
        assertFalse(
            "固定浅色必须压过系统的深色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = AppearanceThemeMode.LIGHT)
        )
        assertFalse(
            "固定浅色在系统浅色时仍是浅色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = AppearanceThemeMode.LIGHT)
        )
    }

    @Test
    fun `固定深色时即使系统浅色也用深色`() {
        assertTrue(
            "固定深色必须压过系统的浅色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = AppearanceThemeMode.DARK)
        )
        assertTrue(
            "固定深色在系统深色时仍是深色",
            AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = AppearanceThemeMode.DARK)
        )
    }

    @Test
    fun `三种模式与系统明暗的全部组合都与真值表一致`() {
        // Both system values for every mode, so a new mode or a changed branch cannot pass unnoticed.
        val expected = mapOf(
            (AppearanceThemeMode.SYSTEM to false) to false,
            (AppearanceThemeMode.SYSTEM to true) to true,
            (AppearanceThemeMode.LIGHT to false) to false,
            (AppearanceThemeMode.LIGHT to true) to false,
            (AppearanceThemeMode.DARK to false) to true,
            (AppearanceThemeMode.DARK to true) to true
        )

        expected.forEach { (input, dark) ->
            val (mode, systemDark) = input
            assertEquals(
                "mode=$mode systemDark=$systemDark",
                dark,
                AppearanceThemeResolver.resolveDarkTheme(systemDark = systemDark, mode = mode)
            )
        }
        // The table above is only exhaustive while every mode appears with both system values.
        assertEquals(
            "真值表必须覆盖每个模式的系统深色与系统浅色两种情况",
            AppearanceThemeMode.entries.size * 2,
            expected.size
        )
    }

    @Test
    fun `跟随系统是唯一会随系统翻转的模式`() {
        AppearanceThemeMode.entries.forEach { mode ->
            val light = AppearanceThemeResolver.resolveDarkTheme(systemDark = false, mode = mode)
            val dark = AppearanceThemeResolver.resolveDarkTheme(systemDark = true, mode = mode)
            assertEquals(
                "只有 SYSTEM 允许系统设置改变结果，mode=$mode",
                mode == AppearanceThemeMode.SYSTEM,
                light != dark
            )
        }
    }
}

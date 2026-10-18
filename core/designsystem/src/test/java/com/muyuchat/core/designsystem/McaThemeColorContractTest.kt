package com.muyuchat.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the MCA colour contract for both schemes.
 *
 * The first two tests pin the seventeen roles that existed before V01: those hex values are
 * frozen and must not move during design-system work. The remaining tests pin the roles that
 * used to fall through to Material3's lavender-grey defaults, and prove those roles now carry
 * MCA values instead of the library baseline.
 *
 * This is a JVM contract test over the [androidx.compose.material3.ColorScheme] values, not a
 * rendering or screenshot test.
 */
class McaThemeColorContractTest {

    private val light = mcaColorScheme(darkTheme = false)
    private val dark = mcaColorScheme(darkTheme = true)

    @Test
    fun `light scheme keeps the seventeen original palette values`() {
        assertEquals(Color(0xFF1A73E8), light.primary)
        assertEquals(Color.White, light.onPrimary)
        assertEquals(Color(0xFFE8F0FE), light.primaryContainer)
        assertEquals(Color(0xFF185ABC), light.onPrimaryContainer)
        assertEquals(Color(0xFF185ABC), light.secondary)
        assertEquals(Color.White, light.onSecondary)
        assertEquals(Color(0xFFD2E3FC), light.secondaryContainer)
        assertEquals(Color(0xFF174EA6), light.onSecondaryContainer)
        assertEquals(Color(0xFFF8F9FA), light.background)
        assertEquals(Color(0xFF202124), light.onBackground)
        assertEquals(Color.White, light.surface)
        assertEquals(Color(0xFF202124), light.onSurface)
        assertEquals(Color(0xFFF1F3F4), light.surfaceVariant)
        assertEquals(Color(0xFF5F6368), light.onSurfaceVariant)
        assertEquals(Color(0xFFDADCE0), light.outline)
        assertEquals(Color(0xFFEA4335), light.error)
        assertEquals(Color.White, light.onError)
    }

    @Test
    fun `dark scheme keeps the seventeen original palette values`() {
        assertEquals(Color(0xFF8AB4F8), dark.primary)
        assertEquals(Color(0xFF002D6B), dark.onPrimary)
        assertEquals(Color(0xFF1A73E8), dark.primaryContainer)
        assertEquals(Color(0xFFE8F0FE), dark.onPrimaryContainer)
        assertEquals(Color(0xFF89B5F8), dark.secondary)
        assertEquals(Color(0xFF002B70), dark.onSecondary)
        assertEquals(Color(0xFF185ABC), dark.secondaryContainer)
        assertEquals(Color(0xFFD2E3FC), dark.onSecondaryContainer)
        assertEquals(Color(0xFF121212), dark.background)
        assertEquals(Color(0xFFE8EAED), dark.onBackground)
        assertEquals(Color(0xFF202124), dark.surface)
        assertEquals(Color(0xFFE8EAED), dark.onSurface)
        assertEquals(Color(0xFF303134), dark.surfaceVariant)
        assertEquals(Color(0xFF9AA0A6), dark.onSurfaceVariant)
        assertEquals(Color(0xFF5F6368), dark.outline)
        assertEquals(Color(0xFFF28B82), dark.error)
        assertEquals(Color(0xFF601410), dark.onError)
    }

    @Test
    fun `light tertiary mirrors the one blue accent`() {
        assertEquals(light.secondary, light.tertiary)
        assertEquals(light.onSecondary, light.onTertiary)
        assertEquals(light.secondaryContainer, light.tertiaryContainer)
        assertEquals(light.onSecondaryContainer, light.onTertiaryContainer)
    }

    @Test
    fun `dark tertiary mirrors the one blue accent`() {
        assertEquals(dark.secondary, dark.tertiary)
        assertEquals(dark.onSecondary, dark.onTertiary)
        assertEquals(dark.secondaryContainer, dark.tertiaryContainer)
        assertEquals(dark.onSecondaryContainer, dark.onTertiaryContainer)
    }

    @Test
    fun `light error container uses the palette deep red for content`() {
        assertEquals(Color(0xFFFCE8E6), light.errorContainer)
        assertEquals(Color(0xFF601410), light.onErrorContainer)
    }

    @Test
    fun `dark error container uses the palette deep red for the container`() {
        assertEquals(Color(0xFF601410), dark.errorContainer)
        assertEquals(Color(0xFFF28B82), dark.onErrorContainer)
    }

    @Test
    fun `light outline variant stays on the existing neutral border tone`() {
        assertEquals(Color(0xFFDADCE0), light.outlineVariant)
        assertEquals(light.outline, light.outlineVariant)
    }

    @Test
    fun `dark outline variant is one neutral step below the dark outline`() {
        assertEquals(Color(0xFF3C4043), dark.outlineVariant)
    }

    @Test
    fun `light surface containers use the existing neutral steps`() {
        assertEquals(Color.White, light.surfaceBright)
        assertEquals(Color(0xFFE8EAED), light.surfaceDim)
        assertEquals(Color(0xFFF1F3F4), light.surfaceContainer)
        assertEquals(Color(0xFFF8F9FA), light.surfaceContainerLow)
        assertEquals(Color.White, light.surfaceContainerLowest)
        assertEquals(Color(0xFFE8EAED), light.surfaceContainerHigh)
        assertEquals(Color(0xFFE8EAED), light.surfaceContainerHighest)
    }

    @Test
    fun `dark surface containers use the existing neutral steps`() {
        assertEquals(Color(0xFF303134), dark.surfaceBright)
        assertEquals(Color(0xFF121212), dark.surfaceDim)
        assertEquals(Color(0xFF303134), dark.surfaceContainer)
        assertEquals(Color(0xFF202124), dark.surfaceContainerLow)
        assertEquals(Color(0xFF121212), dark.surfaceContainerLowest)
        assertEquals(Color(0xFF303134), dark.surfaceContainerHigh)
        assertEquals(Color(0xFF303134), dark.surfaceContainerHighest)
    }

    @Test
    fun `light inverse and chrome roles are explicit palette values`() {
        assertEquals(Color(0xFF202124), light.inverseSurface)
        assertEquals(Color(0xFFE8EAED), light.inverseOnSurface)
        assertEquals(Color(0xFF8AB4F8), light.inversePrimary)
        assertEquals(Color(0xFF1A73E8), light.surfaceTint)
        assertEquals(Color.Black, light.scrim)
    }

    @Test
    fun `dark inverse and chrome roles are explicit palette values`() {
        assertEquals(Color(0xFFE8EAED), dark.inverseSurface)
        assertEquals(Color(0xFF202124), dark.inverseOnSurface)
        assertEquals(Color(0xFF1A73E8), dark.inversePrimary)
        assertEquals(Color(0xFF8AB4F8), dark.surfaceTint)
        assertEquals(Color.Black, dark.scrim)
    }

    @Test
    fun `formerly missing light roles differ from the material3 baseline`() {
        val baseline = lightColorScheme()
        assertNotEquals(baseline.tertiary, light.tertiary)
        assertNotEquals(baseline.errorContainer, light.errorContainer)
        assertNotEquals(baseline.onErrorContainer, light.onErrorContainer)
        assertNotEquals(baseline.outlineVariant, light.outlineVariant)
        assertNotEquals(baseline.surfaceBright, light.surfaceBright)
        assertNotEquals(baseline.surfaceDim, light.surfaceDim)
        assertNotEquals(baseline.surfaceContainer, light.surfaceContainer)
        assertNotEquals(baseline.surfaceContainerLow, light.surfaceContainerLow)
        // Light surfaceContainerLowest is white in both MCA and the Material3 baseline; the
        // contract pins the value in `light surface containers use the existing neutral steps`
        // rather than pretending it differs from the library.
        assertNotEquals(baseline.surfaceContainerHigh, light.surfaceContainerHigh)
        assertNotEquals(baseline.surfaceContainerHighest, light.surfaceContainerHighest)
        assertNotEquals(baseline.inverseSurface, light.inverseSurface)
        assertNotEquals(baseline.inverseOnSurface, light.inverseOnSurface)
        assertNotEquals(baseline.inversePrimary, light.inversePrimary)
    }

    @Test
    fun `formerly missing dark roles differ from the material3 baseline`() {
        val baseline = darkColorScheme()
        assertNotEquals(baseline.tertiary, dark.tertiary)
        assertNotEquals(baseline.errorContainer, dark.errorContainer)
        assertNotEquals(baseline.onErrorContainer, dark.onErrorContainer)
        assertNotEquals(baseline.outlineVariant, dark.outlineVariant)
        assertNotEquals(baseline.surfaceBright, dark.surfaceBright)
        assertNotEquals(baseline.surfaceDim, dark.surfaceDim)
        assertNotEquals(baseline.surfaceContainer, dark.surfaceContainer)
        assertNotEquals(baseline.surfaceContainerLow, dark.surfaceContainerLow)
        assertNotEquals(baseline.surfaceContainerLowest, dark.surfaceContainerLowest)
        assertNotEquals(baseline.surfaceContainerHigh, dark.surfaceContainerHigh)
        assertNotEquals(baseline.surfaceContainerHighest, dark.surfaceContainerHighest)
        assertNotEquals(baseline.inverseSurface, dark.inverseSurface)
        assertNotEquals(baseline.inverseOnSurface, dark.inverseOnSurface)
        assertNotEquals(baseline.inversePrimary, dark.inversePrimary)
    }

    @Test
    fun `night window chrome values match values-night styles`() {
        // app/src/main/res/values-night/styles.xml: windowBackground/statusBar #121212,
        // navigationBar #202124. These are the scheme roles McaTheme hands to the window.
        assertEquals(Color(0xFF121212), dark.background)
        assertEquals(Color(0xFF202124), dark.surface)
    }

    @Test
    fun `light window chrome values match the day styles`() {
        // app/src/main/res/values/styles.xml: windowBackground/statusBar #F8F9FA,
        // navigationBar #FFFFFF.
        assertEquals(Color(0xFFF8F9FA), light.background)
        assertEquals(Color.White, light.surface)
    }

    @Test
    fun `light and dark schemes are distinct`() {
        assertNotEquals(light.primary, dark.primary)
        assertNotEquals(light.background, dark.background)
    }
}

package com.muyuchat.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Locks the WCAG 2.x contrast that the palette actually reaches for the role pairs the app
 * uses as text/background. Values come from the sRGB channels of the ColorScheme in
 * [McaTheme]; this is not a rendering, font-size, or device test.
 *
 * Known, intentionally untouched conflicts (frozen hex values):
 *  - light `onError` on `error` is 3.92:1;
 *  - dark `onPrimaryContainer` on `primaryContainer` is 3.93:1.
 * Both are below the 4.5:1 AA threshold for normal text. Fixing them would change a frozen
 * palette value and needs a separate visual decision, so they are documented instead of being
 * asserted as passing.
 *
 * `onSurfaceVariant.copy(alpha = ...)` is not asserted here because the alpha is chosen per
 * call site in feature code. The alpha audit is recorded in the V9/V01 task notes: in dark mode
 * alpha 0.62-0.72 lands between 2.8:1 and 4.2:1 depending on the background, so secondary text
 * that applies alpha needs >= 0.81 on surface and >= 0.94 on surfaceVariant to keep 4.5:1.
 */
class McaThemeContrastContractTest {

    private val light = mcaColorScheme(darkTheme = false)
    private val dark = mcaColorScheme(darkTheme = true)

    @Test
    fun `light secondary text keeps AA on every neutral surface`() {
        assertAtLeast(4.5, light.onSurfaceVariant, light.background)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surface)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceVariant)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceContainer)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceContainerLow)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceContainerHigh)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceContainerHighest)
        assertAtLeast(4.5, light.onSurfaceVariant, light.surfaceDim)
    }

    @Test
    fun `dark secondary text keeps AA on every neutral surface`() {
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.background)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surface)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceVariant)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceContainer)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceContainerLow)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceContainerHigh)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceContainerHighest)
        assertAtLeast(4.5, dark.onSurfaceVariant, dark.surfaceBright)
    }

    @Test
    fun `primary text keeps AA on the raised neutral surfaces`() {
        assertAtLeast(4.5, light.onSurface, light.surfaceVariant)
        assertAtLeast(4.5, light.onSurface, light.surfaceContainerHigh)
        assertAtLeast(4.5, dark.onSurface, dark.surfaceVariant)
        assertAtLeast(4.5, dark.onSurface, dark.surfaceContainerHigh)
    }

    @Test
    fun `error containers keep AA content in both schemes`() {
        assertAtLeast(4.5, light.onErrorContainer, light.errorContainer)
        assertAtLeast(4.5, dark.onErrorContainer, dark.errorContainer)
    }

    @Test
    fun `tertiary containers keep AA content in both schemes`() {
        assertAtLeast(4.5, light.onTertiaryContainer, light.tertiaryContainer)
        assertAtLeast(4.5, dark.onTertiaryContainer, dark.tertiaryContainer)
    }

    @Test
    fun `inverse surfaces keep AA content in both schemes`() {
        assertAtLeast(4.5, light.inverseOnSurface, light.inverseSurface)
        assertAtLeast(4.5, dark.inverseOnSurface, dark.inverseSurface)
    }

    private fun assertAtLeast(
        minimum: Double,
        foreground: Color,
        background: Color
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "expected >= $minimum:1 but was ${"%.2f".format(ratio)}:1 " +
                "for foreground ${foreground.toHex()} on background ${background.toHex()}",
            ratio >= minimum
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92
            else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    private fun Color.toHex(): String =
        "#%02X%02X%02X".format(
            (red * 255f).toInt().coerceIn(0, 255),
            (green * 255f).toInt().coerceIn(0, 255),
            (blue * 255f).toInt().coerceIn(0, 255)
        )
}

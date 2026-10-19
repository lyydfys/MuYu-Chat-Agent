// The light and dark schemes moved here from app's com.muyuchat.mca.ui package during P2, and
// are kept as the single colour authority for every module.
//
// The move itself changed nothing: every colour value, the light and dark schemes, the system
// bar behaviour and the visibility of each declaration are as they were. The only edits were
// the package line and this comment.
//
// It lives in a design system module rather than in app because the feature modules do not
// depend on app, so a theme in app can only ever be used by app. Nothing here reads app state;
// the theme is a colour scheme plus the window chrome that has to match it.
//
// V9/V01: the seventeen roles below are unchanged byte for byte. The roles the theme used to
// leave undeclared (outlineVariant, errorContainer, inverse*, surfaceTint, scrim,
// surfaceContainer*) fell back to Material3's lavender-grey baseline, which is not MCA's
// Google-grey language. They are now declared explicitly from the existing palette:
//   * outlineVariant reuses the palette's one neutral border tone in light (#DADCE0) and steps
//     one shade below the dark outline in dark (#3C4043);
//   * errorContainer/onErrorContainer reuse the palette's deep red (#601410) and light red
//     (#F28B82);
//   * surfaceContainer* reuse the existing background/surface/surfaceVariant neutral steps so
//     M3 components (menus, nav bars, sheets) stop falling back to lavender-grey;
//   * tertiary mirrors secondary because MCA has one blue accent and no tertiary surface.
// No brand hex, type, spacing, or shape token is changed. The role values and the contrast of
// the on/container pairs are asserted by McaThemeColorContractTest and
// McaThemeContrastContractTest.
package com.muyuchat.core.designsystem

import android.app.Activity
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = Color(0xFF185ABC),
    secondary = Color(0xFF185ABC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E3FC),
    onSecondaryContainer = Color(0xFF174EA6),
    // Mirrors secondary: MCA has one blue accent rather than a third hue.
    tertiary = Color(0xFF185ABC),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD2E3FC),
    onTertiaryContainer = Color(0xFF174EA6),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    // Google grey border tone the palette already uses; the light outline is itself a divider.
    outlineVariant = Color(0xFFDADCE0),
    outline = Color(0xFFDADCE0),
    error = Color(0xFFEA4335),
    onError = Color.White,
    // Google red 050 behind the palette's deep red text (11.1:1 contrast).
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF601410),
    inverseSurface = Color(0xFF202124),
    inverseOnSurface = Color(0xFFE8EAED),
    inversePrimary = Color(0xFF8AB4F8),
    surfaceTint = Color(0xFF1A73E8),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE8EAED),
    // MCA keeps one raised neutral step (surfaceVariant) rather than lavender containers.
    surfaceContainer = Color(0xFFF1F3F4),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainerLowest = Color.White,
    surfaceContainerHigh = Color(0xFFE8EAED),
    surfaceContainerHighest = Color(0xFFE8EAED)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002D6B),
    primaryContainer = Color(0xFF1A73E8),
    onPrimaryContainer = Color(0xFFE8F0FE),
    secondary = Color(0xFF89B5F8),
    onSecondary = Color(0xFF002B70),
    secondaryContainer = Color(0xFF185ABC),
    onSecondaryContainer = Color(0xFFD2E3FC),
    // Mirrors secondary: MCA has one blue accent rather than a third hue.
    tertiary = Color(0xFF89B5F8),
    onTertiary = Color(0xFF002B70),
    tertiaryContainer = Color(0xFF185ABC),
    onTertiaryContainer = Color(0xFFD2E3FC),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF303134),
    onSurfaceVariant = Color(0xFF9AA0A6),
    // One neutral step below the dark outline, for dividers and low-emphasis borders. It is
    // not used behind text, so it does not participate in the contrast contract below.
    outlineVariant = Color(0xFF3C4043),
    outline = Color(0xFF5F6368),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    // Palette deep red as the container, light red as its content (5.5:1 contrast).
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF28B82),
    inverseSurface = Color(0xFFE8EAED),
    inverseOnSurface = Color(0xFF202124),
    inversePrimary = Color(0xFF1A73E8),
    surfaceTint = Color(0xFF8AB4F8),
    scrim = Color.Black,
    surfaceBright = Color(0xFF303134),
    surfaceDim = Color(0xFF121212),
    surfaceContainer = Color(0xFF303134),
    surfaceContainerLow = Color(0xFF202124),
    surfaceContainerLowest = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF303134),
    surfaceContainerHighest = Color(0xFF303134)
)

/**
 * The scheme for [darkTheme]; exposed to unit tests so the colour contract is checked against
 * the same values the app composes with.
 */
internal fun mcaColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColors else LightColors

@Composable
fun McaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = mcaColorScheme(darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        // System-bar writes touch the window layout params, so apply them when the theme actually
        // changes instead of on every recomposition of the host (streaming chat recomposes often).
        DisposableEffect(darkTheme) {
            val window = (view.context as Activity).window
            window.applySystemBarColors(
                statusBarColor = colorScheme.background.toArgb(),
                navigationBarColor = colorScheme.surface.toArgb()
            )
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Suppress("DEPRECATION")
private fun Window.applySystemBarColors(statusBarColor: Int, navigationBarColor: Int) {
    this.statusBarColor = statusBarColor
    this.navigationBarColor = navigationBarColor
}

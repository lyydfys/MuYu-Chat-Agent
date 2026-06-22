package com.muyuchat.mca.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.view.Window

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = Color(0xFF185ABC),
    secondary = Color(0xFF185ABC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E3FC),
    onSecondaryContainer = Color(0xFF174EA6),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFDADCE0),
    error = Color(0xFFEA4335),
    onError = Color.White
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
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF202124),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF303134),
    onSurfaceVariant = Color(0xFF9AA0A6),
    outline = Color(0xFF5F6368),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410)
)

@Composable
fun McaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.applySystemBarColors(
                statusBarColor = colorScheme.background.toArgb(),
                navigationBarColor = colorScheme.surface.toArgb()
            )
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
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

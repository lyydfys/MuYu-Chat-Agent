package com.muyuchat.core.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Micro-craft tokens shared by icons, buttons, plates, chips and surfaces.
 *
 * Every value below was already in use as a literal across the chat, menu and agent surfaces.
 * Naming them changes nothing that is drawn; it only stops the next few hundred call sites from
 * drifting apart (17/18/19/20/21dp glyphs, 0.28..0.55 hairline alphas, ad-hoc radii).
 */
public object McaIconSize {
    /** Inline glyph next to small text. */
    public val Xs: Dp = 16.dp

    /** Glyph inside a 32dp plate (message actions). */
    public val S: Dp = 18.dp

    /** Default list/menu glyph. */
    public val M: Dp = 20.dp

    /** Toolbar / header glyph. */
    public val L: Dp = 24.dp

    /** Existing optical exceptions; these names preserve rendered geometry during migration. */
    public object Optical {
        public val Inline: Dp = 17.dp
        public val Compact: Dp = 19.dp
        public val Menu: Dp = 21.dp
        public val Action: Dp = 22.dp
        public val Navigation: Dp = 27.dp
    }
}

/** Visual plate sizes. The touch target stays 48dp; the plate is only what is drawn. */
public object McaPlateSize {
    public val Small: Dp = 28.dp
    public val Medium: Dp = 32.dp
    public val Large: Dp = 40.dp

    /** Equal-value badges used by the existing drawer and application menu rows. */
    public val DrawerBadge: Dp = 29.dp
    public val MenuBadge: Dp = 30.dp
}

public object McaShape {
    /** Squircle icon badge (menu rows, drawer entries). */
    public val Badge: RoundedCornerShape = RoundedCornerShape(10.dp)

    /** In-card secondary action pill. */
    public val CardAction: RoundedCornerShape = RoundedCornerShape(12.dp)

    public val Card: RoundedCornerShape = RoundedCornerShape(20.dp)

    public val Sheet: RoundedCornerShape = RoundedCornerShape(28.dp)

    /** True capsule at any height. */
    public val Capsule: RoundedCornerShape = RoundedCornerShape(percent = 50)
}

/**
 * Optical hairline: 0.5dp, tinted from the existing `outlineVariant` / `primary` roles. Dark themes
 * need a stronger alpha than light ones for the same perceived edge, so the pair lives here.
 */
public object McaHairline {
    public val Width: Dp = 0.5.dp

    @Composable
    @ReadOnlyComposable
    private fun isDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** Quiet edge for controls that sit on a tonal fill. */
    @Composable
    @ReadOnlyComposable
    public fun subtle(): Color =
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark()) 0.45f else 0.32f)

    /** Default edge for plates and pills; the disabled edge is roughly half as visible. */
    @Composable
    @ReadOnlyComposable
    public fun default(enabled: Boolean = true): Color =
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.45f else 0.22f)

    /** Edge for cards and surfaces. */
    @Composable
    @ReadOnlyComposable
    public fun strong(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    /** Edge of a selected / active control, tinted from [accent]. */
    public fun selected(accent: Color): Color = accent.copy(alpha = 0.38f)

    @Composable
    @ReadOnlyComposable
    public fun stroke(enabled: Boolean = true): BorderStroke = BorderStroke(Width, default(enabled))
}

public object McaTonal {
    /** Soft neutral fill behind icons and secondary pills. */
    @Composable
    @ReadOnlyComposable
    public fun fill(enabled: Boolean = true): Color =
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.45f else 0.22f)

    /** Existing denser supporting badge fill in the drawer and application menu. */
    @Composable
    @ReadOnlyComposable
    public fun badgeFill(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    /** Soft accent fill for an active / selected plate. */
    public fun accentFill(accent: Color): Color = accent.copy(alpha = 0.14f)
}

/**
 * Colour motion. [McaMotion.press] is a `FiniteAnimationSpec<Float>` and cannot drive a colour, so
 * state colour changes (selected, copied, menu open) share this one curve instead of each call site
 * inventing its own duration.
 */
public object McaColorMotion {
    public val state: FiniteAnimationSpec<Color> = tween(durationMillis = 160)
}

package com.muyuchat.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Carries the enclosing [McaIconButton] interaction source when the button's content slot is a
 * visual plate ([McaPlateSize]), so the bounded ripple renders strictly inside the plate's clipped
 * geometry instead of spilling into the outer 48dp touch-target box.
 */
internal val LocalMcaIconPlateInteraction = staticCompositionLocalOf<InteractionSource?> { null }

/**
 * The visual plate behind a small glyph: soft tonal fill, 0.5dp hairline, optional accent state.
 *
 * This is what was hand-written (clip + background + border) at the message actions, the copy/pin
 * pills, the composer sheet and the menu rows. It is drawing only: it adds no click handling and no
 * semantics, so the caller keeps owning the 48dp touch target and the accessible name, and the
 * plate can sit inside an `IconButton`, a `clickable` row or plain content.
 *
 * @param selected paints the accent fill and edge (copied, pinned, menu open).
 * @param shape [CircleShape] for action pills, [McaShape.Badge] for menu / drawer badges.
 */
@Composable
public fun McaIconPlate(
    modifier: Modifier = Modifier,
    size: Dp = McaPlateSize.Medium,
    shape: Shape = CircleShape,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color? = null,
    borderColor: Color? = null,
    animateColors: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.mcaIconPlate(
            size = size,
            shape = shape,
            selected = selected,
            enabled = enabled,
            accent = accent,
            containerColor = containerColor,
            borderColor = borderColor,
            animateColors = animateColors,
        ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Draws the same plate into an existing interaction chain so its ripple can remain on top. */
@Composable
public fun Modifier.mcaIconPlate(
    size: Dp,
    shape: Shape = CircleShape,
    selected: Boolean = false,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color? = null,
    borderColor: Color? = null,
    animateColors: Boolean = true,
): Modifier {
    val fillTarget = containerColor ?: if (selected && enabled) McaTonal.accentFill(accent) else McaTonal.fill(enabled)
    val edgeTarget = borderColor ?: if (selected && enabled) McaHairline.selected(accent) else McaHairline.default(enabled)
    val fill = if (animateColors) {
        animateColorAsState(fillTarget, McaColorMotion.state, label = "mcaIconPlateFill").value
    } else {
        fillTarget
    }
    val edge = if (animateColors) {
        animateColorAsState(edgeTarget, McaColorMotion.state, label = "mcaIconPlateEdge").value
    } else {
        edgeTarget
    }
    val plateInteraction = LocalMcaIconPlateInteraction.current
    return this
        .size(size)
        .clip(shape)
        .background(fill)
        .then(
            if (plateInteraction != null && enabled) {
                Modifier.indication(plateInteraction, ripple(bounded = true))
            } else {
                Modifier
            }
        )
        .border(McaHairline.Width, edge, shape)
}

package com.muyuchat.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's single icon-button surface.
 *
 * Why a shared component instead of `IconButton` at every call site:
 * - one 48dp touch target (Material guidance) regardless of the glyph size;
 * - a circular, bounded ripple that never spills outside the button;
 * - the shared [mcaPressScale] weight feedback, read in the layer phase;
 * - an optional tonal "selected" background derived from the existing palette, so selection
 *   reads without adding a new colour;
 * - the caller cannot forget the accessible name (it is a required parameter, applied on the
 *   button itself; pass `contentDescription = null` to the inner `Icon`).
 *
 * Geometry, colours and icon assets are unchanged: this only standardises the interaction
 * surface around the existing glyph.
 */
@Composable
public fun McaIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 48.dp,
    glyphSize: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedTint: Color = MaterialTheme.colorScheme.primary,
    selectedContainer: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .mcaPressScale(interaction, enabled = enabled)
            .clip(CircleShape)
            .background(if (selected) selectedContainer else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (selected) selectedTint else tint
        CompositionLocalProvider(LocalContentColor provides color) {
            Box(Modifier.size(glyphSize), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

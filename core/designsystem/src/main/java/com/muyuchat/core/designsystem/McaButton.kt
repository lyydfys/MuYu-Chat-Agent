package com.muyuchat.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Visual weight of a [McaButton]. */
public enum class McaButtonStyle {
    /** The one main action of a surface. */
    Primary,

    /** Soft secondary action: tonal fill + hairline. */
    Tonal,

    /** Quiet secondary action: transparent with a hairline. */
    Outlined,

    /** Lowest emphasis: text only. */
    Text,

    /** Irreversible action: error container. */
    Destructive,
}

/** Footprint of a [McaButton]. */
public enum class McaButtonSize {
    /** Standalone button: true capsule. */
    Regular,

    /** Action inside a card or list row: 12dp pill. */
    CardAction,
}

/**
 * The app's single text button surface.
 *
 * Wraps the Material3 buttons so every call site gets the same shape, hairline, disabled look,
 * press weight ([mcaPressScale], read in the layer phase) and `tick` haptic. Touch target, ripple and
 * semantics are the Material ones, so replacing a bare `Button` / `TextButton` / `OutlinedButton`
 * does not move any layout. A caller that already owns an [interactionSource] can pass it in.
 *
 * [contentColor] overrides the style's content colour (for example the error colour on a delete
 * action inside a card) without introducing a new palette entry.
 * [loadingIndicatorColor] may preserve an existing progress role independently of disabled text;
 * unspecified indicators inherit the button's current content colour.
 */
@Composable
public fun McaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: McaButtonStyle = McaButtonStyle.Primary,
    size: McaButtonSize = McaButtonSize.Regular,
    enabled: Boolean = true,
    loading: Boolean = false,
    contentColor: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    shape: Shape? = null,
    elevation: ButtonElevation? = null,
    pressScaleEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    contentPadding: PaddingValues? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    loadingIndicatorColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    val actionable = enabled && !loading
    val resolvedShape: Shape = shape ?: when (size) {
        McaButtonSize.Regular -> McaShape.Capsule
        McaButtonSize.CardAction -> McaShape.CardAction
    }
    val scheme = MaterialTheme.colorScheme
    val padding = contentPadding ?: when (style) {
        McaButtonStyle.Text -> ButtonDefaults.TextButtonContentPadding
        else -> ButtonDefaults.ContentPadding
    }
    val styleColors = colors ?: when (style) {
        McaButtonStyle.Primary -> ButtonDefaults.buttonColors(
            containerColor = containerColor.orElse(scheme.primary),
            contentColor = contentColor.orElse(scheme.onPrimary),
        )
        McaButtonStyle.Destructive -> ButtonDefaults.buttonColors(
            containerColor = containerColor.orElse(scheme.error),
            contentColor = contentColor.orElse(scheme.onError),
        )
        McaButtonStyle.Tonal -> ButtonDefaults.textButtonColors(
            containerColor = containerColor.orElse(McaTonal.fill(true)),
            contentColor = contentColor.orElse(scheme.primary),
            disabledContainerColor = McaTonal.fill(false),
        )
        McaButtonStyle.Outlined -> ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor.orElse(scheme.primary),
        )
        McaButtonStyle.Text -> ButtonDefaults.textButtonColors(
            containerColor = containerColor,
            contentColor = contentColor.orElse(scheme.primary),
        )
    }
    // Loading disables the actual Material clickable (ripple, focus and semantics included), but
    // an otherwise enabled action keeps its established colours while the result is pending.
    val resolvedColors = if (enabled && loading) {
        ButtonDefaults.buttonColors(
            containerColor = styleColors.containerColor,
            contentColor = styleColors.contentColor,
            disabledContainerColor = styleColors.containerColor,
            disabledContentColor = styleColors.contentColor,
        )
    } else {
        styleColors
    }
    val pressModifier = (if (pressScaleEnabled) {
        modifier.mcaPressScale(source, enabled = actionable)
    } else {
        modifier
    })
        .then(if (loading) Modifier.semantics { stateDescription = "处理中" } else Modifier)
    val handleClick = {
        if (actionable) {
            if (hapticsEnabled) haptics.tick()
            onClick()
        }
    }
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(8.dp))
        }
        content()
    }
    val loadingBody: @Composable RowScope.() -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            // Measure the same content in both states. Suppress drawing rather than using alpha
            // zero: the original label must remain in the button's merged accessibility name.
            Row(
                modifier = Modifier.drawWithContent { if (!loading) drawContent() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = buttonContent,
            )
            CircularProgressIndicator(
                // matchParentSize excludes this overlay from measurement, even for a narrow
                // label, so entering or leaving loading cannot grow a wrap-content button.
                modifier = Modifier.matchParentSize().wrapContentSize().size(McaIconSize.Xs)
                    .clearAndSetSemantics { },
                strokeWidth = 2.dp,
                color = loadingIndicatorColor.orElse(androidx.compose.material3.LocalContentColor.current),
            )
        }
    }
    // Preserve the original RowScope tree for the hundreds of non-loading call sites.
    val body = if (loading) loadingBody else buttonContent
    when (style) {
        McaButtonStyle.Primary -> Button(
            onClick = handleClick,
            modifier = pressModifier,
            enabled = actionable,
            shape = resolvedShape,
            colors = resolvedColors,
            elevation = elevation ?: ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = padding,
            interactionSource = source,
            content = body,
        )

        McaButtonStyle.Destructive -> Button(
            onClick = handleClick,
            modifier = pressModifier,
            enabled = actionable,
            shape = resolvedShape,
            colors = resolvedColors,
            elevation = elevation ?: ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = padding,
            interactionSource = source,
            content = body,
        )

        McaButtonStyle.Tonal -> TextButton(
            onClick = handleClick,
            modifier = pressModifier,
            enabled = actionable,
            shape = resolvedShape,
            colors = resolvedColors,
            border = border ?: BorderStroke(McaHairline.Width, McaHairline.default(enabled)),
            contentPadding = padding,
            interactionSource = source,
            content = body,
        )

        McaButtonStyle.Outlined -> OutlinedButton(
            onClick = handleClick,
            modifier = pressModifier,
            enabled = actionable,
            shape = resolvedShape,
            colors = resolvedColors,
            border = border ?: BorderStroke(
                McaHairline.Width,
                if (enabled) McaHairline.strong() else McaHairline.default(false),
            ),
            contentPadding = padding,
            interactionSource = source,
            content = body,
        )

        McaButtonStyle.Text -> TextButton(
            onClick = handleClick,
            modifier = pressModifier,
            enabled = actionable,
            shape = resolvedShape,
            colors = resolvedColors,
            border = border,
            contentPadding = padding,
            interactionSource = source,
            content = body,
        )
    }
}

private fun Color.orElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this


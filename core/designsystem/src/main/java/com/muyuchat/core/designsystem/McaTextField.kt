package com.muyuchat.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared single-line/multi-line text field, with a built-in clear button.
 *
 * The app had 95 text fields and only three could be cleared in one tap — typing a single wrong
 * character in a sampler value, an API key or a name meant a long-press, select-all and delete. This
 * wrapper exists so the clear affordance is a property of the control rather than something each
 * screen remembers to add.
 *
 * Behaviour contract (pinned by `McaTextFieldContractTest`):
 *
 * - The clear button appears only when there is something to clear (`value.isNotEmpty()`) and the
 *   field is interactive (`enabled && !readOnly`). An empty or disabled field therefore shows no
 *   extra glyph and keeps its original visual weight.
 * - Pressing it reports `onValueChange("")` — the same channel the keyboard uses — so a caller's
 *   own validation, dirty tracking and state restoration all keep working. It never bypasses
 *   [onValueChange] by writing state directly.
 * - A caller that supplies its own [trailingIcon] keeps it. This wrapper clears only when the slot
 *   is free, so a password-reveal toggle or a unit suffix is never silently replaced.
 * - Clearing is announced as [McaActionLabels.CLEAR] and carries the haptic tap used by every other
 *   icon action in the design system.
 *
 * Rationale for a wrapper instead of patching each call site: the alternative is 89 independent
 * edits of `OutlinedTextField`, which is both more code and no guarantee for the next field added.
 * Parameter names mirror `OutlinedTextField` so a call site migrates by renaming the symbol.
 */
@Composable
public fun McaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    textStyle: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    shape: androidx.compose.ui.graphics.Shape = OutlinedTextFieldDefaults.shape,
    colors: androidx.compose.material3.TextFieldColors = OutlinedTextFieldDefaults.colors(),
    /** Overrides the clear glyph size; the default follows the icon controls (20.dp). */
    clearGlyphSize: Dp = McaIconSize.M,
    /** False keeps a caller-owned affordance; true always shows the clear button when non-empty. */
    showClearButton: Boolean = true,
) {
    val showClear = showClearButton && value.isNotEmpty() && enabled && !readOnly
    val resolvedTrailing: @Composable (() -> Unit)? = when {
        trailingIcon != null -> trailingIcon
        showClear -> {
            {
                McaIconButton(
                    contentDescription = McaActionLabels.CLEAR,
                    onClick = { onValueChange("") },
                    size = 40.dp,
                    glyphSize = clearGlyphSize,
                ) {
                    Icon(McaIcons.Clear, contentDescription = null, modifier = Modifier.size(clearGlyphSize))
                }
            }
        }
        else -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = resolvedTrailing,
        supportingText = supportingText,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = textStyle,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
    )
}

/**
 * A read-only field that shows [value] until the caller supplies nothing to show.
 *
 * Kept separate from [McaTextField] on purpose: a read-only field has no clear button by contract
 * (clearing text the user cannot re-enter would be a destructive affordance), but it does need the
 * same typography and outline as the editable controls around it.
 */
@Composable
public fun McaReadOnlyTextField(
    value: String,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    McaTextField(
        value = value,
        onValueChange = {},
        modifier = modifier,
        enabled = true,
        readOnly = true,
        label = label,
        supportingText = supportingText,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
    )
}

/** Neutral helper for screens that render a caption under a field. */
@Composable
public fun McaFieldCaption(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** Colour role used by fields that must not look like the surrounding inputs. */
@Composable
public fun McaFieldDisabledTint(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

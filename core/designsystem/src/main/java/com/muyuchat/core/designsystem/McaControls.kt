package com.muyuchat.core.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Selectable chip: the Material3 [FilterChip] with the shared hairline (neutral at rest, accent when
 * selected), the layer-phase press weight and a `tick` haptic. Colours are the theme's own, and the
 * shape defaults to the Material one, so adopting it does not change the chip geometry; a caller
 * may opt into [McaShape.CardAction] explicitly.
 */
@Composable
public fun McaChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    pressScaleEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    val pressModifier = if (pressScaleEnabled) {
        modifier.mcaPressScale(interaction, enabled = enabled)
    } else {
        modifier
    }
    FilterChip(
        selected = selected,
        onClick = {
            if (hapticsEnabled) haptics.tick()
            onClick()
        },
        label = label,
        modifier = pressModifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        border = border ?: FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = McaHairline.default(),
            selectedBorderColor = McaHairline.selected(MaterialTheme.colorScheme.primary),
            borderWidth = McaHairline.Width,
            selectedBorderWidth = McaHairline.Width,
        ),
        interactionSource = interaction,
    )
}

/**
 * The Material3 [Switch] with a direction-aware toggle haptic. [onCheckedChange] may be `null` for a
 * switch whose row owns the toggle (`toggleable`); in that case the row is responsible for feedback.
 * Supply [contentDescription] when this switch owns the action; a row-owned switch inherits the
 * row's name instead, avoiding a second accessible toggle.
 */
@Composable
public fun McaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    colors: SwitchColors = SwitchDefaults.colors(),
    thumbContent: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val haptics = rememberMcaHaptics()
    val resolvedThumbContent = thumbContent ?: if (checked) {
        {
            androidx.compose.material3.Icon(
                imageVector = McaIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(McaIconSize.Xs),
            )
        }
    } else {
        null
    }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let { change ->
            { value: Boolean ->
                haptics.toggle(value)
                change(value)
            }
        },
        modifier = if (contentDescription != null) {
            modifier.semantics { this.contentDescription = contentDescription }
        } else {
            modifier
        },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = colors,
        thumbContent = resolvedThumbContent,
    )
}

/**
 * The Material3 [Slider]; a stepped slider (`steps > 0`) emits at most one `segmentTick` per input
 * event when its detent changes. A jump over several detents must not queue a vibration burst.
 * A continuous slider stays silent while dragging so it does not buzz.
 */
@Composable
public fun McaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val haptics = rememberMcaHaptics()
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val detents = remember(valueRange, steps) { McaSliderDetents(value, valueRange, steps) }
    // Recomposition caused by restored/persisted state never produces a drag haptic.
    SideEffect { detents.syncValue(value) }
    Slider(
        value = value,
        onValueChange = { next ->
            if (detents.onInput(next)) haptics.segmentTick()
            onValueChange(next)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = source,
    )
}

/** Keeps drag feedback bounded without turning external value updates into tactile events. */
internal class McaSliderDetents(
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    private val stepped = steps > 0
    private val detentCount = (steps + 1).coerceAtLeast(1)
    private var lastDetent = initialValue.detentIndex(valueRange, detentCount)
    private var observedValue = initialValue

    fun syncValue(value: Float) {
        if (observedValue != value) {
            observedValue = value
            lastDetent = value.detentIndex(valueRange, detentCount)
        }
    }

    fun onInput(value: Float): Boolean {
        val nextDetent = value.detentIndex(valueRange, detentCount)
        val changed = stepped && nextDetent != lastDetent
        lastDetent = nextDetent
        return changed
    }
}

private fun Float.detentIndex(
    range: ClosedFloatingPointRange<Float>,
    detentCount: Int,
): Int {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0
    return (((this - range.start) / span) * detentCount)
        .roundToInt()
        .coerceIn(0, detentCount)
}

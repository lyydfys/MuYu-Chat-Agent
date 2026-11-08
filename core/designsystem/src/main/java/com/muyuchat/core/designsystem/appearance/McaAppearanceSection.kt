package com.muyuchat.core.designsystem.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.mcaPressScale

/** Vertical gap between the rows of one section. */
private val SectionRowGap = 10.dp

/** Horizontal inset inside a row, matching the settings cards it sits beside. */
private val SectionRowHorizontalPadding = 14.dp

/** Gap between the leading plate and the label. */
private val SectionRowGlyphGap = 12.dp

/**
 * The appearance section of the settings surface: one title, one explanation and a three-way choice
 * between following the system colour mode and pinning light or dark.
 *
 * Stateless on purpose. The caller owns the selection and the write; this composable only renders it, so
 * a future golden screenshot can drive every state without an Activity or a preference store.
 *
 * The selected row carries two signals, not one: the container fill and a trailing check glyph. A
 * colour-only selection cue fails for colour-blind users and washes out at low contrast in dark mode,
 * and this is the one control whose accidental state is "the user cannot read the app".
 */
@Composable
public fun McaAppearanceSection(
    selected: McaAppearanceThemeChoice,
    onSelect: (McaAppearanceThemeChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SectionRowGap)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "外观",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "跟随系统会随手机的深色模式自动切换；固定浅色或深色后不再跟随。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(SectionRowGap)
        ) {
            McaAppearanceThemeChoice.entries.forEach { choice ->
                McaAppearanceThemeRow(
                    choice = choice,
                    selected = choice == selected,
                    onClick = { onSelect(choice) }
                )
            }
        }
    }
}

@Composable
private fun McaAppearanceThemeRow(
    choice: McaAppearanceThemeChoice,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.secondaryContainer else scheme.surface
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    val border = if (selected) {
        BorderStroke(1.dp, scheme.primary)
    } else {
        BorderStroke(McaHairline.Width, McaHairline.subtle())
    }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .mcaPressScale(interactionSource)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .background(container, McaShape.CardAction)
            .border(border, McaShape.CardAction)
            .padding(horizontal = SectionRowHorizontalPadding)
            // 48dp is the accessibility floor for a touch target; the plate inside stays smaller than
            // the row so the hit area is generous without the visual weight growing with it.
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            // The whole row is one radio option: the label alone must not be announced as a separate
            // node, or TalkBack reads the mode twice.
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically
    ) {
        McaIconPlate(
            size = McaPlateSize.Medium,
            accent = content
        ) {
            Icon(
                imageVector = choice.glyph(),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(McaIconSize.S)
            )
        }
        Spacer(Modifier.width(SectionRowGlyphGap))
        Text(
            text = choice.label,
            style = MaterialTheme.typography.bodyLarge,
            color = content,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = McaIcons.Check,
                // The row already announces its selected state; a described glyph would repeat it.
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(McaIconSize.M)
            )
        } else {
            Spacer(Modifier.size(McaIconSize.M))
        }
    }
}

private fun McaAppearanceThemeChoice.glyph() = when (this) {
    McaAppearanceThemeChoice.SYSTEM -> McaIcons.Tune
    McaAppearanceThemeChoice.LIGHT -> McaIcons.CheckCircle
    McaAppearanceThemeChoice.DARK -> McaIcons.Science
}

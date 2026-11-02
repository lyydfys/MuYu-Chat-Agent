package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.feature.agent.workspace.AgentStatusTone
import com.muyuchat.feature.agent.workspace.AgentTimelineRow
import com.muyuchat.core.designsystem.McaIconSize

private val TimelineRowShape = RoundedCornerShape(10.dp)

/**
 * One compact tool-summary row: an 18dp category glyph, a single-line primary title with time,
 * a single-line summary, and a trailing status. The row is borderless and only gets the soft
 * surfaceContainerLow tint while its details are expanded. Expand/collapse, click labels and the
 * expandable-only state description are kept.
 */
@Composable
internal fun AgentTimelineRowItem(
    row: AgentTimelineRow,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clickModifier = if (row.expandable) {
        Modifier.clickable(
            onClickLabel = if (row.expanded) "收起活动详情" else "展开活动详情",
            role = Role.Button,
            onClick = onToggle
        )
    } else {
        Modifier
    }
    // Only expandable rows are controls; a non-interactive row must not announce an expansion state.
    val expansionStateModifier = if (row.expandable) {
        Modifier.semantics { stateDescription = if (row.expanded) "已展开" else "已折叠" }
    } else {
        Modifier
    }
    val backgroundModifier = if (row.expanded) {
        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
    } else {
        Modifier
    }
    val hasStatus = row.tone != AgentStatusTone.NEUTRAL

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TimelineRowShape)
            .then(backgroundModifier)
            .then(clickModifier)
            .then(expansionStateModifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = agentTimelineCategoryIcon(agentTimelineCategory(row)),
                contentDescription = null,
                tint = activityGlyphTint(row.tone),
                modifier = Modifier.size(McaIconSize.S)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.repeatCount > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "×${row.repeatCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val meta = listOfNotNull(
                    row.source?.label,
                    row.toolState?.label
                ).joinToString(" · ")
                val secondary = listOfNotNull(
                    meta.takeIf { it.isNotBlank() },
                    row.summary?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            if (hasStatus) {
                Spacer(Modifier.width(8.dp))
                TimelineStatusGlyph(tone = row.tone)
            }
            if (row.expandable) {
                Spacer(Modifier.width(if (hasStatus) 2.dp else 8.dp))
                Icon(
                    imageVector = if (row.expanded) {
                        McaIcons.KeyboardArrowUp
                    } else {
                        McaIcons.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.S)
                )
            }
        }
        if (row.expanded && row.details.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 2.dp)
                ) {
                    row.details.forEachIndexed { index, detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.tone == AgentStatusTone.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStatusGlyph(tone: AgentStatusTone) {
    when (tone) {
        AgentStatusTone.ACTIVE -> CircularProgressIndicator(
            modifier = Modifier
                .size(14.dp)
                .semantics { contentDescription = "正在运行" },
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 1.5.dp
        )

        AgentStatusTone.SUCCESS -> Icon(
            imageVector = McaIcons.Check,
            contentDescription = "已完成",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(McaIconSize.Xs)
        )

        AgentStatusTone.ERROR -> Icon(
            imageVector = McaIcons.Error,
            contentDescription = "执行失败",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(McaIconSize.Xs)
        )

        AgentStatusTone.WARNING -> Icon(
            imageVector = McaIcons.Warning,
            contentDescription = "需要处理",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(McaIconSize.Xs)
        )

        AgentStatusTone.NEUTRAL -> Unit
    }
}

@Composable
private fun activityGlyphTint(tone: AgentStatusTone): Color = when (tone) {
    AgentStatusTone.ERROR -> MaterialTheme.colorScheme.error
    AgentStatusTone.ACTIVE, AgentStatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
    AgentStatusTone.WARNING, AgentStatusTone.NEUTRAL ->
        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
}

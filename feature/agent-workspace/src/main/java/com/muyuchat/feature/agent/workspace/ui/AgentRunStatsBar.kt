package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.feature.agent.workspace.AgentRunStatsUiState
import com.muyuchat.feature.agent.workspace.formatRunCacheHitPercent
import com.muyuchat.feature.agent.workspace.formatRunContextPercent
import com.muyuchat.feature.agent.workspace.formatRunRoundsAndSteps
import com.muyuchat.feature.agent.workspace.formatRunTokens

/**
 * Compact one-line cost/usage row placed at the very bottom, below the composer.
 *
 * Only values projected from real run fields are rendered. A segment disappears when its source is
 * absent: context percentage and cache hit have no run-contract source in this revision, so their
 * icons/labels are not drawn at all. A small indeterminate ring is shown only while the run is
 * really RUNNING; it never doubles as a percentage.
 */
@Composable
internal fun AgentRunStatsBar(
    stats: AgentRunStatsUiState,
    modifier: Modifier = Modifier
) {
    val activity = formatRunRoundsAndSteps(stats.rounds, stats.steps)
    val tokens = formatRunTokens(stats.tokensPerSecond, stats.totalTokens)
    val context = formatRunContextPercent(stats.contextPercent)
    val cache = formatRunCacheHitPercent(stats.cacheHitPercent)
    if (!stats.running && activity == null && tokens == null && context == null && cache == null) {
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent.runStats")
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (stats.running) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(12.dp)
                    .semantics { contentDescription = "正在运行" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 1.5.dp
            )
        }
        activity?.let { text ->
            RunStatSegment(icon = McaIcons.Schedule, text = text)
        }
        tokens?.let { text ->
            RunStatSegment(
                icon = McaIcons.Storage,
                text = text,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        cache?.let { text ->
            RunStatSegment(icon = McaIcons.Cached, text = text)
        }
        context?.let { text ->
            RunStatSegment(icon = McaIcons.DonutLarge, text = text)
        }
    }
}

@Composable
private fun RowScope.RunStatSegment(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

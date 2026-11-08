package com.muyuchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.engine.AgentTurnTrace

/**
 * One agent turn, rendered inside the chat transcript.
 *
 * The chat thread and the agent loop are the same conversation: asking the agent to "tidy up what we
 * just wrote" only works if the agent's work appears where that conversation lives. This component is
 * what the user sees of that work — a summary line, labelled as agent output so it is never mistaken
 * for the character speaking.
 *
 * Deliberately not a step-by-step log. The user's question is "did it do the thing", and the full
 * per-step record already exists in the task detail surface; replaying twenty tool results inline
 * would bury the conversation it is supposed to belong to. The context projection drops these
 * messages from the model prompt for the same reason (see `AgentTurnTrace.carriesContextSummary`).
 */
@Composable
internal fun AgentTraceBubble(
    trace: AgentTurnTrace,
    content: String,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = McaIcons.Build,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = agentTraceLabel(trace.kind),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Spacer0()
            Text(
                text = trace.turnId.take(8),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(thickness = McaHairline.Width, color = McaHairline.default())
        // The summary is the only thing the model ever sees of this turn, so it is also the thing the
        // user must be able to read without opening the task.
        val body = content.ifBlank { trace.finalSummary }
        Text(
            text = body.ifBlank { "Agent 本轮没有产出可读的结论。" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Human label for the transcript kind; unknown kinds fall back to a neutral word. */
internal fun agentTraceLabel(kind: String): String = when (kind) {
    "step" -> "Agent 步骤"
    "approval" -> "Agent 待审批"
    "question" -> "Agent 提问"
    "artifact" -> "Agent 产物"
    "summary" -> "Agent 总结"
    else -> "Agent"
}

/**
 * Fixed-width gap between the label and the turn id.
 *
 * A `Spacer` with `weight` would push the id to the far edge; a small fixed gap keeps the header
 * together when the label is long ("Agent 待审批").
 */
@Composable
private fun Spacer0() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp))
}

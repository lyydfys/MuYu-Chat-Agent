package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentStatusTone
import com.muyuchat.feature.agent.workspace.AgentWorkspaceErrorUi
import com.muyuchat.feature.agent.workspace.AgentWorkspaceStatus
import com.muyuchat.feature.agent.workspace.statusLabel
import com.muyuchat.feature.agent.workspace.statusTone

/**
 * Shared quiet surfaces for the Agent workspace. Everything uses McaTheme roles and the existing
 * Google-grey/blue language; no new colours, cards, gradients or elevation were introduced.
 */

@Composable
internal fun AgentStatusChip(
    status: AgentWorkspaceStatus,
    modifier: Modifier = Modifier
) {
    val tone = statusTone(status)
    val container = when (tone) {
        AgentStatusTone.ACTIVE, AgentStatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        AgentStatusTone.WARNING -> MaterialTheme.colorScheme.surfaceVariant
        AgentStatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        AgentStatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (tone) {
        AgentStatusTone.ACTIVE, AgentStatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentStatusTone.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentStatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        AgentStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = statusLabel(status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content
        )
    }
}

@Composable
internal fun AgentTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        modifier = modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor
    )
}

@Composable
internal fun AgentSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun AgentInlineBanner(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        color = container,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
            if (onDismiss != null) {
                McaIconButton(contentDescription = "关闭提示", onClick = onDismiss) {
                    Icon(McaIcons.Close, contentDescription = null, tint = contentColor)
                }
            }
        }
    }
}

@Composable
internal fun AgentErrorBanner(
    error: AgentWorkspaceErrorUi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val retryHint = if (error.retryable) "（可处理后重试）" else ""
    AgentInlineBanner(
        message = error.message + retryHint,
        modifier = modifier,
        isError = true,
        onDismiss = onDismiss
    )
}

@Composable
internal fun AgentStaticLoading(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun AgentPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    McaButton(
        style = McaButtonStyle.Primary,
        shape = ButtonDefaults.shape,
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !busy,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = if (busy) "处理中…" else text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun AgentMetaText(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

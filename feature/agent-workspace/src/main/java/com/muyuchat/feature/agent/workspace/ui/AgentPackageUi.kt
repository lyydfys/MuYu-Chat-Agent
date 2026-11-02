package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentDeleteConfirmationUi
import com.muyuchat.feature.agent.workspace.AgentPackageOperationUi
import com.muyuchat.feature.agent.workspace.AgentWorkspaceStatus

/** Quiet package-operation status line; progress is static text (no indeterminate animation). */
@Composable
internal fun AgentPackageStatusBanner(
    operation: AgentPackageOperationUi?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val current = operation ?: return
    when (current.status) {
        AgentWorkspaceStatus.PACKAGE_EXPORTING -> PackageProgressBanner(
            text = "正在导出任务包…",
            modifier = modifier
        )

        AgentWorkspaceStatus.PACKAGE_IMPORTING -> PackageProgressBanner(
            text = "正在校验并导入任务包…",
            modifier = modifier
        )

        AgentWorkspaceStatus.PACKAGE_DELETING -> PackageProgressBanner(
            text = "正在停止运行并删除任务…",
            modifier = modifier
        )

        AgentWorkspaceStatus.PACKAGE_FAILED -> AgentInlineBanner(
            message = current.message ?: "任务包操作失败。",
            modifier = modifier,
            isError = true,
            onDismiss = onDismiss
        )

        else -> Unit
    }
}

@Composable
private fun PackageProgressBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 48dp menu item; destructive actions carry the error colour but never request focus. */
@Composable
internal fun AgentPackageMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp)
    )
}

/**
 * Overflow toggle row. The check mark plus state description exposes the current filter state;
 * selecting it is never destructive.
 */
@Composable
internal fun AgentPackageToggleItem(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = onClick,
        enabled = enabled,
        trailingIcon = {
            if (checked) {
                Icon(McaIcons.Check, contentDescription = null)
            }
        },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (checked) "已开启" else "已关闭" }
    )
}

/**
 * Delete confirmation. The cancel action is first in reading order and nothing requests initial
 * focus, so the destructive button is never the default focus target.
 */
@Composable
internal fun AgentDeleteTaskDialog(
    confirmation: AgentDeleteConfirmationUi,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    McaDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("删除任务？") },
        text = {
            Column {
                Text(
                    text = confirmation.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "如果任务仍在运行，会先停止当前运行并等待停止完成，然后删除任务与工作区。" +
                        "已提交的产物会按删除策略清理；此操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在删除…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("取消")
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除任务")
            }
        }
    )
}

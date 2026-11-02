package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons

/**
 * DSH-shaped plus sheet. Both sections contain only entries that have an existing action; there is
 * no decorative "image"/"web"/"app" row because this feature has no real handler for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentHomePlusSheet(
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onFocusGoal: () -> Unit,
    onPermissions: () -> Unit,
    onModel: () -> Unit,
    onTaskHistory: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            PlusSectionHeader("添加")
            PlusActionRow(
                label = "文件",
                subtitle = "导入任务包",
                onClick = onImportFile
            )
            PlusActionRow(
                label = "目标",
                subtitle = "聚焦到输入框",
                onClick = onFocusGoal
            )
            PlusActionRow(
                label = "权限",
                subtitle = "网络与运行授权说明",
                onClick = onPermissions
            )
            Spacer(Modifier.height(8.dp))
            PlusSectionHeader("指令")
            PlusActionRow(
                label = "模型",
                subtitle = "选择本次运行使用的模型",
                onClick = onModel
            )
            PlusActionRow(
                label = "任务历史",
                subtitle = "打开左侧抽屉",
                onClick = onTaskHistory
            )
        }
    }
}

@Composable
private fun PlusSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun PlusActionRow(
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = McaIcons.Forward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

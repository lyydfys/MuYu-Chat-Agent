package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.muyuchat.feature.dsh.workspace.DshModelSwitchState

/**
 * Model panel launched from the DSH floating ball.
 *
 * The list is MCA's downloaded local models (the same source as the chat picker). Selecting one
 * submits the production load/switch owner; once that owner reports the model active, the user gets
 * one explicit "restart DSH" action because the official provider freezes its model list per host
 * generation. Nothing here invents a loaded state: the active id and the loading stage come from the
 * host projection.
 *
 * Large screens: the dialog keeps the platform default width on COMPACT and is capped at
 * 560dp (MEDIUM) / 640dp (EXPANDED) so it does not stretch across an unfolded foldable.
 */
@Composable
internal fun DshModelSwitchDialog(
    state: DshModelSwitchState,
    pendingModelId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val pendingLoaded = pendingModelId != null && state.activeModelId == pendingModelId
    val maxWidthDp = dshDialogMaxWidthFor(dshWindowWidth())
    McaDialog(
        onDismissRequest = onDismiss,
        // COMPACT passes the untouched default: no modifier, no DialogProperties override.
        modifier = if (maxWidthDp != null) {
            Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth()
        } else {
            Modifier
        },
        properties = if (maxWidthDp != null) {
            DialogProperties(usePlatformDefaultWidth = false)
        } else {
            DialogProperties()
        },
        title = { Text("选择 MCA 模型") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "选择后会由 MCA 加载该模型；加载完成后直接在 DSH 的模型选择器中使用，无需重启 DSH。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.status != null) {
                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (state.choices.isEmpty()) {
                    Text("MCA 还没有已下载的本地模型。", style = MaterialTheme.typography.bodyMedium)
                }
                state.choices.forEach { choice ->
                    val selected = pendingModelId == choice.modelId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.busy) { onSelect(choice.modelId) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                choice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (choice.loaded) {
                            Text("已加载", style = MaterialTheme.typography.labelMedium)
                        } else if (selected) {
                            Text(
                                if (state.busy) "加载中…" else "已选择",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (pendingLoaded) {
                    Text(
                        "模型已加载。可直接在 DSH 的模型选择器中使用它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) { Text("关闭") }
        }
    )
}

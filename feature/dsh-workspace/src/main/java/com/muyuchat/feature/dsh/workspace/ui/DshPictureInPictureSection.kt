package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.rememberMcaNotify
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone

/**
 * Picture-in-picture block of the DSH console.
 *
 * PiP is a *shell* capability (it needs an Activity), so this block only talks to [DshPipHost]. It
 * is the alternative to the app-outside floating ball: the whole DSH page keeps running in a small
 * system window, which is what the reference project offers for "watch DSH over another app"
 * without granting the overlay permission.
 */
@Composable
internal fun DshPictureInPictureSection(modifier: Modifier = Modifier) {
    val notify = rememberMcaNotify()
    val port = DshPipHost.port
    var note by remember { mutableStateOf<String?>(null) }
    val available = port?.isAvailable() == true
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "画中画",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        DshConsoleRowView(
            DshConsoleRow(
                label = "系统小窗",
                value = when {
                    port == null -> "当前版本没有接入画中画入口"
                    !available -> "当前系统不支持画中画"
                    else -> "整页 DSH 缩成小窗继续运行，不需要悬浮窗权限"
                },
                tone = if (available) DshConsoleTone.POSITIVE else DshConsoleTone.NEUTRAL
            )
        )
        note?.let { failure ->
            DshConsoleRowView(
                DshConsoleRow(
                    label = "最近一次结果",
                    value = failure,
                    tone = DshConsoleTone.NEGATIVE
                )
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            McaButton(
                style = McaButtonStyle.Primary,
                enabled = available,
                onClick = {
                    val failure = port?.enterShrunken()
                    note = failure
                    if (failure == null) notify("已进入画中画")
                }
            ) {
                Text("进入画中画")
            }
        }
    }
}

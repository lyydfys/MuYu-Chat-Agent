@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import com.muyuchat.core.designsystem.McaDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ImageEditorLoadFailure(message: String, onBack: () -> Unit) {
    BackHandler(enabled = !WindowInsets.isImeVisible, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("无法编辑这张图片", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        McaButton(style = McaButtonStyle.Primary,
            onClick = onBack,
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
        ) { Text("返回") }
    }
}

@Composable
internal fun DiscardImageEditsDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit
) {
    McaDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放弃本次编辑？") },
        text = { Text("未保存的裁剪或蒙版笔画将丢失。") },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text,onClick = onDiscard) { Text("放弃") }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text,onClick = onKeepEditing) { Text("继续编辑") }
        }
    )
}

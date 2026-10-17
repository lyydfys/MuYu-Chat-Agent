@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        Button(
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
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放弃本次编辑？") },
        text = { Text("未保存的裁剪或蒙版笔画将丢失。") },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text("放弃") }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text("继续编辑") }
        }
    )
}

package com.muyuchat.mca

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.feature.chat.ImeAwareAlertDialog

@Composable
internal fun ContentImportPreviewDialog(
    preview: ContentImportPreview,
    committing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ImeAwareAlertDialog(
        onDismissRequest = { if (!committing) onCancel() },
        title = { Text("导入预览：${preview.title}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(preview.format, style = MaterialTheme.typography.labelMedium)
                Text("SHA-256 ${preview.sourceHash.take(16)}", style = MaterialTheme.typography.labelSmall)
                Text(preview.owner.sessionId?.let { "所属对话：$it" } ?: "所属角色：${preview.owner.assistantId}",
                    style = MaterialTheme.typography.labelSmall)
                preview.fields.forEach { (name, value) ->
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Text(value.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall)
                }
                preview.warnings.forEach { warning ->
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (committing) LinearProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !committing) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !committing) { Text("取消") } }
    )
}

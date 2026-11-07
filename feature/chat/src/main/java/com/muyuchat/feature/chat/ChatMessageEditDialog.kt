package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaTextField

/**
 * Edits the text of one already-sent user message and re-submits it.
 *
 * The dialog only collects text; rollback, persistence and regeneration belong to the owner behind
 * `onConfirm`. It states the destructive consequence up front so the user knows every later turn is
 * retired before the new answer is generated.
 */
@Composable
internal fun ChatMessageEditDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val tooLong = text.length > CHAT_MESSAGE_EDIT_MAX_CHARS
    McaDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "编辑并重发",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "保存后会作废这条消息及之后的所有内容，并用编辑后的文本重新生成。助手回复不会被单独修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                McaTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 128.dp),
                    label = { Text("消息内容") },
                    minLines = 4,
                    maxLines = 10,
                    isError = tooLong,
                    supportingText = {
                        Text(
                            text = if (tooLong) {
                                "超出上限：${text.length} / $CHAT_MESSAGE_EDIT_MAX_CHARS"
                            } else {
                                "${text.length} / $CHAT_MESSAGE_EDIT_MAX_CHARS"
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Primary,
                enabled = text.isNotBlank() && !tooLong,
                onClick = { onConfirm(text.trim()) }
            ) {
                Text("保存并重发")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

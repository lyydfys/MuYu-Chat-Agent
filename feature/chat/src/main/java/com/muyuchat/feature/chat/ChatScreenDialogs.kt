package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatImageTranslationDraftDialog(
    draft: ChatImageTranslationDraftUi,
    onConfirm: (draftId: String, positive: String, negative: String?) -> Unit,
    onCancel: (draftId: String) -> Unit
) {
    var positive by rememberSaveable(draft.id) { mutableStateOf(draft.proposedPositive) }
    var negative by rememberSaveable(draft.id) {
        mutableStateOf(draft.proposedNegative.orEmpty())
    }
    AlertDialog(
        onDismissRequest = { onCancel(draft.id) },
        title = { Text("确认生图提示词") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(draft.sourceLabel, style = MaterialTheme.typography.bodySmall)
                Text("原始正向", style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(draft.originalPositive, style = MaterialTheme.typography.bodyMedium)
                }
                draft.originalNegative?.let { original ->
                    Text("原始负向", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(original, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = positive,
                    onValueChange = { positive = it },
                    label = { Text("使用的正向提示词") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 7
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("使用的负向提示词") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        draft.id,
                        positive,
                        negative.takeIf {
                            it.isNotEmpty() ||
                                draft.originalNegative != null ||
                                draft.proposedNegative != null
                        }
                    )
                },
                enabled = positive.isNotBlank()
            ) { Text("使用提示词") }
        },
        dismissButton = {
            TextButton(onClick = { onCancel(draft.id) }) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun ChatImageActionDraftDialog(
    draft: ChatImageActionDraftUi,
    onResolve: (draftId: String, proceed: Boolean, editedText: String) -> Unit
) {
    var editedText by rememberSaveable(draft.id) { mutableStateOf(draft.sourceText) }
    AlertDialog(
        onDismissRequest = { onResolve(draft.id, false, editedText) },
        title = { Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "确认本轮操作" else "确认云端整理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(draft.reason, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("原始描述") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onResolve(draft.id, true, editedText) },
                enabled = editedText.isNotBlank()
            ) { Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "生成图片" else "使用云端整理") }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(draft.id, false, editedText) }) {
                Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "继续聊天" else "取消")
            }
        }
    )
}

@Composable
internal fun ChatUltraFixSubmissionDialog(
    submission: Pair<String, ImageGenerationUiOptions>,
    onConfirm: (prompt: String, options: ImageGenerationUiOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val (prompt, options) = submission
    val ultraFix = requireNotNull(options.ultraFix)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始 UltraFix 精修？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将以 ${ultraFix.targetWidth}×${ultraFix.targetHeight} 分块处理整张图片，" +
                        "通常明显慢于普通生成并会增加设备发热。"
                )
                Text(
                    "当前去噪步数 ${ultraFix.inversionSteps} / 总步数 " +
                        "${ultraFix.refinementSteps}。建议该比例不超过 0.5。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(prompt, options) }
            ) { Text("开始精修") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
internal fun ChatNewConversationPickerDialog(
    show: Boolean,
    assistants: List<AssistantUiItem>,
    history: List<ChatHistoryItem>,
    selectedAssistantId: String?,
    onSelectAssistant: (assistantId: String) -> Unit,
    onResume: (sessionId: String) -> Unit,
    onCreate: (assistantId: String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    NewConversationAssistantDialog(
        assistants = assistants,
        history = history,
        selectedAssistantId = selectedAssistantId,
        onSelectAssistant = onSelectAssistant,
        onResume = onResume,
        onCreate = onCreate,
        onBack = onBack,
        onDismiss = onDismiss
    )
}

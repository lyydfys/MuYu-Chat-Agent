package com.muyuchat.feature.chat

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S25：从 ImageWorkspaceScreens.kt 等值移出的输入支持簇（参数导入对话框 + 角色/强度输入行）。
// 原样逐字迁移，未做行为改动；3 个跨区入口按协议提升 internal。
@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageGenerationParameterImportDialog(
    selection: ImageGenerationParameterImportSelection,
    currentModelName: String,
    currentTaskMode: ImageGenerationUiTaskMode,
    onSelectionChange: (ImageGenerationParameterImportSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val imported = selection.imported
    val sourceLabel = when (imported.source) {
        ImageGenerationParameterImportSource.MCA -> "MCA"
        ImageGenerationParameterImportSource.LOCAL_DREAM -> "Local Dream"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要导入的参数") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    buildString {
                        append("来源：").append(sourceLabel)
                        imported.sourceModelId?.let { append(" · 模型 ").append(it) }
                        imported.taskMode?.let { append(" · 模式 ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append("仅填入")
                        append(currentModelName.ifBlank { "当前模型" })
                        append("的").append(currentTaskMode.label)
                        append("面板，不会切换模型或模式，也不会立即生成。")
                        if (selection.hiddenFieldCount > 0) {
                            append(" 不兼容的 ")
                            append(selection.hiddenFieldCount)
                            append(" 项字段已隐藏。")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selection.compatibleFields.isEmpty()) {
                    Text(
                        "没有可用于当前模型与模式的字段。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    selection.compatibleFields.forEach { field ->
                        FilterChip(
                            selected = field in selection.selectedFields,
                            onClick = { onSelectionChange(selection.toggle(field)) },
                            label = { Text(field.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selection.selectedFields.isNotEmpty()
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageInputRoleRow(
    label: String,
    role: String,
    uri: String?,
    onPick: (String) -> Unit,
    onClear: (String) -> Unit,
    onEdit: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = { onPick(role) }) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (uri == null) "选择$label" else "更换$label")
        }
        Text(
            uri?.let(::imageInputDisplayName) ?: "未选择",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (uri != null) {
            if (onEdit != null) {
                IconButton(
                    onClick = { onEdit(role) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "裁剪$label")
                }
            }
            IconButton(onClick = { onClear(role) }) {
                Icon(Icons.Default.Close, contentDescription = "清除$label")
            }
        }
    }
}

@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageStrengthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun imageInputDisplayName(uriString: String): String = runCatching {
    Uri.parse(uriString).lastPathSegment?.substringAfterLast('/')
}.getOrNull()?.takeIf(String::isNotBlank)?.takeLast(64) ?: "已选择图片"

package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun ModelImportDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onImportDirectory: (Boolean) -> Unit,
    onImportFiles: (Boolean) -> Unit
) {
    if (!visible) return
    var modelImportTextOnly by rememberSaveable { mutableStateOf(false) }
    ImeAwareAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("导入本地聊天模型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("MNN 建议选择完整模型目录或 ZIP，以保留子目录、分词器和权重。GGUF、LiteRT-LM 可直接选择模型文件。导入进度在本地模型页查看。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = modelImportTextOnly, onCheckedChange = { modelImportTextOnly = it })
                    Text("仅导入 MNN 聊天组件（不启用视觉、音频）")
                }
                Text("默认完整导入。纯聊天模式需要完整的聊天权重和分词器，不会补齐缺失文件。")
                McaButton(style = McaButtonStyle.Text,onClick = {
                    onDismissRequest()
                    onImportDirectory(modelImportTextOnly)
                }) { Text("选择模型目录") }
                McaButton(style = McaButtonStyle.Text,onClick = {
                    onDismissRequest()
                    onImportFiles(modelImportTextOnly)
                }) { Text("选择文件 / ZIP") }
            }
        },
        confirmButton = { McaButton(style = McaButtonStyle.Text,onClick = onDismissRequest) { Text("取消") } }
    )
}

@Composable
fun AppUpdatePromptDialog(
    versionName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (versionName == null) return
    ImeAwareAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Text("MCA $versionName 已在官方 GitHub Release 发布。下载后会校验 SHA-256、包名和版本号，再交由系统安装。")
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text,onClick = onConfirm) { Text("查看更新") }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text,onClick = onDismiss) { Text("稍后") }
        }
    )
}

@Composable
fun ChatBrowserDialogs(
    internalBrowserUrl: String?,
    onDismissInternalBrowser: () -> Unit,
    browserTask: BrowserTask?,
    onBrowserTaskEvent: (BrowserTaskEvent) -> Unit
) {
    internalBrowserUrl?.let { url ->
        InternalBrowserDialog(
            initialUrl = url,
            onDismiss = onDismissInternalBrowser
        )
    }
    browserTask?.takeIf { task ->
        task.phase == BrowserTaskPhase.QUEUED || task.windowVisible
    }?.let { task ->
        InternalBrowserDialog(
            task = task,
            onTaskEvent = onBrowserTaskEvent,
            onDismiss = {
                onBrowserTaskEvent(BrowserTaskEvent(task.taskId, task.navigationId, BrowserTaskAction.CANCEL))
            }
        )
    }
}

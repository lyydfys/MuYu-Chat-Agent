package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.feature.video.workspace.RemoteUiState

/**
 * Explicit remote ComfyUI action surface (V3-01). Nothing here switches backends automatically:
 * the user types an endpoint, probes it, then explicitly submits. The API key lives only in this
 * composition's memory (never SavedState/Room/logs); only the non-secret endpoint id is persisted.
 */
@Composable
internal fun RemoteComfySection(
    wired: Boolean,
    state: RemoteUiState,
    onProbe: (baseUrl: String, apiKey: String?, allowTargetedCancel: Boolean) -> Unit,
    onSubmit: (baseUrl: String, apiKey: String?, allowTargetedCancel: Boolean) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var targetedCancel by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("远程 ComfyUI（显式选择）", style = MaterialTheme.typography.titleSmall)
        Text(
            "只提交已导入的 ComfyUI API-format 修订；本地失败不会自动切到远端。API Key 不写入数据库、日志或分享文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        McaTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Endpoint（例如 http://192.168.1.10:8188）") },
            singleLine = true,
            enabled = wired,
            modifier = Modifier.fillMaxWidth().testTag("video.remote_endpoint"))
        McaTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key（可选，仅本次会话）") },
            singleLine = true,
            enabled = wired,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("video.remote_api_key"))
        val status = when (state) {
            RemoteUiState.Idle -> if (wired) "未连接" else "远端端口未装配"
            RemoteUiState.Probing -> "正在探测…"
            is RemoteUiState.Ready -> "已连接；targeted cancel=" +
                if (state.capabilities.supportsTargetedCancel) "可用" else "不可用（仅能停止本机等待）"
            RemoteUiState.AuthFailed -> "认证失败（401/403）"
            is RemoteUiState.Failed -> "失败：${state.code} ${state.detail}"
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            A11yLabeledControl(
                contentDescription = "测试连接远端 ComfyUI",
                stateDescription = status,
                enabled = wired,
                onClick = { onProbe(baseUrl, apiKey, targetedCancel) },
                modifier = Modifier.weight(1f),
            ) {
                McaButton(
                    onClick = { onProbe(baseUrl, apiKey, targetedCancel) },
                    style = McaButtonStyle.Outlined,
                    enabled = wired,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("video.remote_probe"),
                ) {
                    Text("测试连接")
                }
            }
            A11yLabeledControl(
                contentDescription = "提交任务到远端 ComfyUI",
                stateDescription = status,
                enabled = wired,
                onClick = { onSubmit(baseUrl, apiKey, targetedCancel) },
                modifier = Modifier.weight(1f),
            ) {
                McaButton(
                    onClick = { onSubmit(baseUrl, apiKey, targetedCancel) },
                    style = McaButtonStyle.Tonal,
                    enabled = wired,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("video.remote_submit"),
                ) {
                    Text("提交到远端")
                }
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is RemoteUiState.Failed || state == RemoteUiState.AuthFailed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

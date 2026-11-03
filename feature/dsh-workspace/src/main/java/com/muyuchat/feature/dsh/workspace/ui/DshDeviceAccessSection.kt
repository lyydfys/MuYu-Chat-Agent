package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind
import java.io.File

/**
 * "设备权限" block of the read-only DSH console.
 *
 * An additive block inside the existing console column: it adds no destination, no tab and no
 * change to the surrounding layout. It renders what the two device channels really report and
 * offers exactly three intents — probe root (the only action that spawns `su`), connect ADB to the
 * typed endpoint (the only action that opens a socket) and refresh the ADB status (privilege-free).
 *
 * Entering the block deliberately does **not** run `su` or open a socket: a root probe raises a
 * root-manager dialog and an ADB connect starts a handshake, so both need an explicit tap.
 */
@Composable
fun DshDeviceAccessSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // The ADB identity lives in app-private storage: the key must survive page re-entries and must
    // never be readable by other apps.
    val identityFile = remember(context) { File(context.filesDir, "dsh/adbkey.pkcs8") }
    val factory = remember(identityFile) { DshDeviceAccessViewModel.factory(identityFile) }
    val viewModel: DshDeviceAccessViewModel = viewModel(
        key = "dsh-device-access",
        factory = factory
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }
    DshDeviceAccessSectionContent(
        state = state,
        onProbeRoot = viewModel::probeRoot,
        onProbeAdb = viewModel::probeAdb,
        onConnectAdb = viewModel::connectAdb,
        onAdbEndpointChange = viewModel::updateAdbEndpoint,
        modifier = modifier
    )
}

/** Stateless body of the block; the Route/ViewModel owns the channel objects. */
@Composable
internal fun DshDeviceAccessSectionContent(
    state: DshDeviceAccessUiState,
    onProbeRoot: () -> Unit,
    onProbeAdb: () -> Unit,
    onConnectAdb: () -> Unit,
    onAdbEndpointChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "设备权限",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Root 与 ADB 是两条独立通道，默认关闭、按需授权。命令统一经过危险命令白名单，" +
                "被拒绝的命令不会执行，也没有「仍然执行」的开关。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DshDeviceChannelBlock(
            title = "Root 通道（su -c）",
            view = state.root
        ) {
            DshDeviceActionButton(
                label = "探测 Root 授权",
                busy = state.busy == DshDeviceChannelKind.ROOT,
                enabled = state.busy == null,
                onClick = onProbeRoot,
                modifier = Modifier.fillMaxWidth()
            )
        }
        DshDeviceChannelBlock(
            title = "ADB 通道（无线调试）",
            view = state.adb
        ) {
            val endpointError = state.adbEndpointError
            OutlinedTextField(
                value = state.adbEndpoint,
                onValueChange = onAdbEndpointChange,
                label = { Text("ADB 端点") },
                singleLine = true,
                isError = endpointError != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (endpointError != null) {
                Text(
                    text = endpointError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshDeviceActionButton(
                    label = "连接 ADB",
                    busy = state.busy == DshDeviceChannelKind.ADB,
                    enabled = state.busy == null,
                    onClick = onConnectAdb,
                    modifier = Modifier.weight(1f)
                )
                DshDeviceActionButton(
                    label = "刷新状态",
                    busy = false,
                    enabled = state.busy == null,
                    onClick = onProbeAdb,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = "只读诊断：允许 id / getprop / ls / cat（仅 /proc、/sys）/ df / ps / pm list 等；" +
                "格式化、分区、SELinux、系统属性写入、提权与命令串联一律拒绝。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DshDeviceChannelBlock(
    title: String,
    view: DshDeviceChannelView,
    actions: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DshConsoleRowView(
            DshConsoleRow(
                label = "状态",
                // Unprobed channels are labelled "未探测" (and stay neutral) by the projection.
                value = view.statusLabel,
                tone = view.tone
            )
        )
        DshConsoleRowView(
            DshConsoleRow(label = "最近结果", value = view.resultSummary)
        )
        Text(
            text = view.grantHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        actions()
    }
}

@Composable
private fun DshDeviceActionButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    McaButton(
        style = McaButtonStyle.Outlined,
        onClick = onClick,
        enabled = enabled,
        loading = busy,
        modifier = modifier
    ) {
        Text(label)
    }
}

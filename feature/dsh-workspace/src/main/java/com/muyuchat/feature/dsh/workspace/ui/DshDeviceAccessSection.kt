package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind

/**
 * "设备权限" block of the read-only DSH console.
 *
 * An additive block inside the existing console column: it adds no destination, no tab and no
 * change to the surrounding layout. It renders what the two device channels really report and
 * offers exactly two intents — probe root (the only action that spawns `su`) and refresh the ADB
 * status (privilege-free).
 *
 * Entering the block deliberately does **not** run `su`: a root probe would raise a root-manager
 * dialog the user did not ask for. Root is probed only from the explicit button.
 */
@Composable
fun DshDeviceAccessSection(modifier: Modifier = Modifier) {
    val viewModel: DshDeviceAccessViewModel = viewModel(
        key = "dsh-device-access",
        factory = DshDeviceAccessViewModel.factory()
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }
    DshDeviceAccessSectionContent(
        state = state,
        onProbeRoot = viewModel::probeRoot,
        onProbeAdb = viewModel::probeAdb,
        modifier = modifier
    )
}

/** Stateless body of the block; the Route/ViewModel owns the channel objects. */
@Composable
internal fun DshDeviceAccessSectionContent(
    state: DshDeviceAccessUiState,
    onProbeRoot: () -> Unit,
    onProbeAdb: () -> Unit,
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
            view = state.root,
            busy = state.busy == DshDeviceChannelKind.ROOT,
            probeLabel = "探测 Root 授权",
            onProbe = onProbeRoot
        )
        DshDeviceChannelBlock(
            title = "ADB 通道（无线调试）",
            view = state.adb,
            busy = state.busy == DshDeviceChannelKind.ADB,
            probeLabel = "检测 ADB 状态",
            onProbe = onProbeAdb
        )
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
    busy: Boolean,
    probeLabel: String,
    onProbe: () -> Unit
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
        McaButton(
            style = McaButtonStyle.Outlined,
            onClick = onProbe,
            enabled = !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(probeLabel)
        }
    }
}

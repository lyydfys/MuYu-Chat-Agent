package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.device.DshAdbAutoConnectPolicy
import com.muyuchat.feature.dsh.workspace.device.DshAdbProvisioningStepResult
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelHost
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelStatus
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandFailure

/**
 * "设备权限" block of the read-only DSH console.
 *
 * An additive block inside the existing console column: it adds no destination, no tab and no
 * change to the surrounding layout. It renders what the two device channels really report and
 * offers exactly these intents — probe root, connect/disconnect ADB, refresh the ADB status,
 * toggle auto-reconnect and run the one-tap root provisioning behind a confirmation dialog.
 *
 * Entering the block deliberately does **not** run `su`, open a socket or provision anything: every
 * privileged action needs an explicit tap (provisioning additionally needs the confirmation dialog).
 */
@Composable
fun DshDeviceAccessSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // One process-level host owns the RSA identity, the ADB transport and the endpoint memory, so the
    // console and the agent `/shell` path drive the same session.
    val host = remember(context) { DshDeviceChannelHost.of(context) }
    val factory = remember(host) { DshDeviceAccessViewModel.factory(host) }
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
        onDisconnectAdb = viewModel::disconnectAdb,
        onSetAutoReconnect = viewModel::setAutoReconnect,
        onProvisionAdb = viewModel::provisionAdb,
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
    onDisconnectAdb: () -> Unit,
    onSetAutoReconnect: (Boolean) -> Unit,
    onProvisionAdb: () -> Unit,
    onAdbEndpointChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showProvisionConfirm by remember { mutableStateOf(false) }
    val rootReady = state.root.status == DshDeviceChannelStatus.READY
    val provisioningRunning = state.provisioning?.running == true

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
                busy = state.busy == DshDeviceChannelKind.ROOT && !provisioningRunning,
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
                    busy = state.busy == DshDeviceChannelKind.ADB && state.retryNote == null,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DshDeviceActionButton(
                    label = "断开",
                    busy = false,
                    enabled = state.busy == null,
                    onClick = onDisconnectAdb,
                    modifier = Modifier.weight(1f)
                )
                // The root-based one-tap path: enabled only with proven root, never a blind attempt.
                DshDeviceActionButton(
                    label = "用 Root 自动配置 ADB",
                    busy = provisioningRunning,
                    enabled = state.busy == null && rootReady,
                    onClick = { showProvisionConfirm = true },
                    modifier = Modifier.weight(1.6f)
                )
            }
            if (!rootReady) {
                Text(
                    text = "「用 Root 自动配置」需要先在上方探测 Root 并授权成功。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = state.autoConnect.enabled,
                    onCheckedChange = onSetAutoReconnect,
                    enabled = state.busy == null
                )
                Text(
                    text = "自动重连（1s → 2s → 4s → 8s → 8s，最多 " +
                        "${DshAdbAutoConnectPolicy.MAX_ATTEMPTS} 次，成功即重置）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.retryNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        state.provisioning?.let { provisioning ->
            DshAdbProvisioningResult(provisioning)
        }
        Text(
            text = "只读诊断：允许 id / getprop / ls / cat（仅 /proc、/sys）/ df / ps / pm list 等；" +
                "格式化、分区、SELinux、系统属性写入、提权与命令串联一律拒绝。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showProvisionConfirm) {
        McaDialog(
            onDismissRequest = { showProvisionConfirm = false },
            title = { Text("用 Root 自动配置 ADB？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "这会把本应用的公钥加入系统的 adb 授权列表，等于给本应用一个 shell 通道；" +
                            "仅在你信任本应用时使用。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "依次执行：探测 Root → 读取 adb_keys → 追加本应用公钥（不覆盖其它公钥）→ " +
                            "setprop service.adb.tcp.port 5555 并重启 adbd → 回连校验 uid=2000。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                McaButton(
                    style = McaButtonStyle.Primary,
                    onClick = {
                        showProvisionConfirm = false
                        onProvisionAdb()
                    }
                ) {
                    Text("确认配置")
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text, onClick = { showProvisionConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/** Step-by-step rendering of the last provisioning run, including what to do after a failure. */
@Composable
private fun DshAdbProvisioningResult(provisioning: DshAdbProvisioningUiState) {
    val title = when {
        provisioning.running -> "自动配置进行中…"
        provisioning.connected -> "自动配置成功"
        else -> "自动配置未完成"
    }
    val titleTone = when {
        provisioning.running -> MaterialTheme.colorScheme.onSurfaceVariant
        provisioning.connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = titleTone
        )
        provisioning.steps.forEach { step -> DshAdbProvisioningStepRow(step) }
        val failure = provisioning.failure
        if (!provisioning.running && failure != null) {
            Text(
                text = "下一步：" + dshAdbProvisioningNextStepHint(failure),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DshAdbProvisioningStepRow(step: DshAdbProvisioningStepResult) {
    DshConsoleRowView(
        DshConsoleRow(
            label = step.step.label,
            value = step.detail,
            tone = if (step.succeeded) DshConsoleTone.POSITIVE else DshConsoleTone.NEGATIVE
        )
    )
}

/**
 * What the user can do after a failed provisioning step. Pure, so the advice is testable and never
 * invented ad hoc in the layout.
 */
fun dshAdbProvisioningNextStepHint(failure: DshDeviceCommandFailure): String = when (failure) {
    DshDeviceCommandFailure.SELINUX_DENIED ->
        "SELinux 拒绝该操作：在 Magisk/内核侧放行 setprop service.adb.tcp.port（或改用系统无线调试配对后手动登记公钥）。"

    DshDeviceCommandFailure.NOT_GRANTED ->
        "先在 Magisk / KernelSU / APatch 中授予 MCA Root，然后重新点「探测 Root 授权」。"

    DshDeviceCommandFailure.TIMEOUT ->
        "adbd 没在预期时间内响应：等几秒重试，或手动执行 stop adbd; start adbd 后再试。"

    DshDeviceCommandFailure.POLICY_BLOCKED ->
        "公钥校验失败（不是合法 base64），已拒绝执行；请重新生成/加载 ADB 身份后再试。"

    DshDeviceCommandFailure.PAIRING_REQUIRED ->
        "设备尚未信任本应用公钥：先完成系统无线调试配对，或重试自动配置。"

    DshDeviceCommandFailure.CONNECT_FAILED ->
        "无法连接 127.0.0.1:5555：确认 adbd 已重启（stop adbd; start adbd）且端口已监听。"

    DshDeviceCommandFailure.HANDSHAKE_FAILED ->
        "ADB 握手失败：确认 adb_keys 已写入且 adbd 已重启，然后重试。"

    DshDeviceCommandFailure.PROTOCOL_ERROR ->
        "ADB 协议错误：检查设备 ROM 的 adbd 是否被裁剪（部分定制系统移除了 tcp 调试）。"

    DshDeviceCommandFailure.OUTPUT_LIMIT ->
        "命令输出超过上限：本不该发生在固定脚本上，请把原因码反馈给维护者。"

    DshDeviceCommandFailure.NOT_INTEGRATED ->
        "ADB 通道未接入：先手动「连接 ADB」确认通道可用。"

    DshDeviceCommandFailure.EXEC_FAILED ->
        "命令执行失败：可先手动「连接 ADB」确认端口，再重试自动配置。"
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

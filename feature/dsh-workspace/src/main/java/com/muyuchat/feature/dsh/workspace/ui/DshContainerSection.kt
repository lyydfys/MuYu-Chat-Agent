package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.container.DshContainerHost
import com.muyuchat.feature.dsh.workspace.container.DshContainerProbeRun
import com.muyuchat.feature.dsh.workspace.container.DshContainerProbeState
import com.muyuchat.feature.dsh.workspace.container.DshContainerState
import kotlinx.coroutines.launch

/**
 * "免 root 容器（proot）" block of the read-only DSH console.
 *
 * An additive block of the existing console column: no destination, no tab, no layout reorder.
 * It reports whether this *particular* install can build a proot argv — proot binary present in
 * an executable location and a user-supplied rootfs with `bin/sh` — and, only in that case,
 * offers the single fixed probe command (`proot … -- /bin/sh -c 'echo ok'`).
 *
 * It never downloads or ships a rootfs, never accepts a command from the user and never treats
 * Termux or a SAF tree as a dependency: those only appear in the explanatory detail.
 */
@Composable
fun DshContainerSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    // One process-level host: the console and any future caller must agree on the same verdict.
    val host = remember(context) { DshContainerHost.of(context) }
    var state by remember(host) { mutableStateOf<DshContainerState?>(null) }
    var probeRun by remember(host) { mutableStateOf<DshContainerProbeRun?>(null) }
    var busy by remember(host) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(host) {
        busy = true
        state = host.snapshot()
        busy = false
    }

    DshContainerSectionContent(
        state = state,
        probeRun = probeRun,
        busy = busy,
        onRefresh = {
            scope.launch {
                busy = true
                probeRun = null
                state = host.snapshot()
                busy = false
            }
        },
        onRunProbe = {
            scope.launch {
                busy = true
                probeRun = host.runProbe()
                busy = false
            }
        },
        modifier = modifier
    )
}

/** Stateless body of the block; the host owns every fact this row set renders. */
@Composable
internal fun DshContainerSectionContent(
    state: DshContainerState?,
    probeRun: DshContainerProbeRun?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRunProbe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val probe = state?.probe
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "免 root 容器（proot）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "proot 在应用自己的 uid 上运行，`-0` 只在容器内伪装 root，不会提权。" +
                "本版不内置 rootfs，也不自动下载；proot 与 rootfs 由用户自备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DshConsoleRowView(
            DshConsoleRow(
                label = "状态",
                value = when {
                    probe == null -> "正在检测…"
                    else -> dshContainerStatusLabel(probe.state)
                },
                tone = if (probe == null) DshConsoleTone.NEUTRAL else dshContainerTone(probe.state)
            )
        )
        if (probe != null) {
            DshConsoleRowView(DshConsoleRow(label = "原因", value = probe.reason))
            Text(
                text = probe.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state?.spec?.let { spec ->
                DshConsoleRowView(DshConsoleRow(label = "proot", value = spec.prootPath))
                DshConsoleRowView(DshConsoleRow(label = "rootfs", value = spec.rootfsPath))
            }
        }
        probeRun?.let { run -> DshContainerProbeRunRows(run) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            McaButton(
                style = McaButtonStyle.Outlined,
                enabled = !busy,
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Text("重新检测")
            }
            McaButton(
                style = McaButtonStyle.Primary,
                enabled = !busy && probe?.state == DshContainerProbeState.SUPPORTED,
                loading = busy,
                onClick = onRunProbe,
                modifier = Modifier.weight(1.4f)
            ) {
                Text("运行固定探针")
            }
        }
        Text(
            text = "探针是唯一可执行的容器命令：固定为 /bin/sh -c 'echo ok'，不接受任何输入。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Outcome rows of the fixed probe; a failure stays visible instead of looking like a fresh state. */
@Composable
private fun DshContainerProbeRunRows(run: DshContainerProbeRun) {
    when (run) {
        is DshContainerProbeRun.NotRunnable -> DshConsoleRowView(
            DshConsoleRow(
                label = "探针未运行",
                value = run.detail,
                tone = DshConsoleTone.WARNING
            )
        )

        is DshContainerProbeRun.Finished -> {
            DshConsoleRowView(
                DshConsoleRow(
                    label = "探针结果",
                    value = when {
                        run.succeeded -> "容器可运行（echo ok）"
                        run.timedOut -> "超时，已强制结束"
                        run.exitCode != null -> "退出码 ${run.exitCode}"
                        else -> run.failure.orEmpty()
                    },
                    tone = if (run.succeeded) DshConsoleTone.POSITIVE else DshConsoleTone.NEGATIVE
                )
            )
            run.output.trim().takeIf { it.isNotEmpty() }?.let { output ->
                Text(
                    text = output,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun dshContainerStatusLabel(state: DshContainerProbeState): String = when (state) {
    DshContainerProbeState.SUPPORTED -> "可用"
    DshContainerProbeState.MISSING_PROOT -> "缺少 proot"
    DshContainerProbeState.MISSING_ROOTFS -> "缺少 rootfs"
    DshContainerProbeState.NOT_PERMITTED -> "proot 不能执行"
}

private fun dshContainerTone(state: DshContainerProbeState): DshConsoleTone = when (state) {
    DshContainerProbeState.SUPPORTED -> DshConsoleTone.POSITIVE
    DshContainerProbeState.NOT_PERMITTED -> DshConsoleTone.WARNING
    DshContainerProbeState.MISSING_PROOT,
    DshContainerProbeState.MISSING_ROOTFS -> DshConsoleTone.NEUTRAL
}

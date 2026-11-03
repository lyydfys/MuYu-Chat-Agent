package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.feature.dsh.workspace.DshConsoleRow
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.DshConsoleUiState
import com.muyuchat.core.designsystem.McaIconSize

/**
 * Read-only DSH console surface (D20).
 *
 * It renders the projection and submits typed intents to the Route, which forwards them to the
 * injected console port. It owns no runtime/model/session state, performs no writes and never
 * re-implements official DSH settings/session/plugin pages; those are [DshConsoleIntent.OpenOfficialSurface]
 * intents that navigate the official UI. The screen reuses the app Material theme exactly like the
 * workspace shell, so no separate console theme is introduced.
 */
@Composable
fun DshConsoleScreen(
    state: DshConsoleUiState,
    onIntent: (DshConsoleIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maintenanceContent: @Composable ColumnScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DSH 控制台",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                McaIconButton(
                    contentDescription = "刷新控制台",
                    onClick = { onIntent(DshConsoleIntent.Refresh) },
                    glyphSize = McaIconSize.L,
                ) {
                    Icon(
                        imageVector = McaIcons.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaButton(style = McaButtonStyle.Text, onClick = onBack) { Text("返回") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val current = state) {
                DshConsoleUiState.Loading -> DshConsoleLoading()
                is DshConsoleUiState.Failure -> DshConsoleFailure(current, onIntent)
                is DshConsoleUiState.Ready -> DshConsoleContent(current, onIntent, maintenanceContent)
            }
        }
    }
}

@Composable
private fun DshConsoleLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(
            text = "正在读取宿主状态",
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DshConsoleFailure(
    failure: DshConsoleUiState.Failure,
    onIntent: (DshConsoleIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "无法读取 DSH 宿主状态",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = "错误代码：${failure.code}",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (failure.retryable) {
            McaButton(
                style = McaButtonStyle.Primary,
                onClick = { onIntent(DshConsoleIntent.Refresh) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun DshConsoleContent(
    state: DshConsoleUiState.Ready,
    onIntent: (DshConsoleIntent) -> Unit,
    maintenanceContent: @Composable ColumnScope.() -> Unit
) {
    val available = state.availableIntents
    val enabled = !state.busy && !state.refreshing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.model.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                section.rows.forEach { row -> DshConsoleRowView(row) }
            }
        }
        // D23 maintenance surfaces (backups/sessions/safe mode/patch/theme/upgrade). The section
        // owns only its projection and submits typed intents to its own injected port.
        maintenanceContent()
        if (state.model.surfaces.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "官方入口",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                state.model.surfaces.forEach { surface ->
                    val intent = DshConsoleIntent.OpenOfficialSurface(surface.surface)
                    McaButton(
                        style = McaButtonStyle.Outlined,
                        onClick = { onIntent(intent) },
                        enabled = enabled && surface.available && intent in available,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(surface.label)
                    }
                }
                if (state.model.surfaces.any { !it.available }) {
                    // The owner did not advertise a typed route for this surface. Keep the action
                    // visible but disabled instead of pretending the official page was reached.
                    Text(
                        text = "官方账号页面暂无控制台路由，请在 DSH 官方页面内打开。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "宿主操作",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            DshConsoleAction(
                label = "重新启动运行时",
                intent = DshConsoleIntent.RestartRuntime,
                available = available,
                enabled = enabled,
                onIntent = onIntent
            )
            DshConsoleAction(
                label = "停止运行时",
                intent = DshConsoleIntent.StopRuntime,
                available = available,
                enabled = enabled,
                onIntent = onIntent
            )
            // Real user entry of the virtual-screen consent. It is rendered only when the vscreen
            // family is registered for this generation; the policy disables it while a request is
            // pending or a projection is already granted.
            if (state.snapshot.capabilities.any { it.capability == DshCapability.VIRTUAL_SCREEN }) {
                DshConsoleAction(
                    label = "授权屏幕捕获",
                    intent = DshConsoleIntent.RequestScreenCapture,
                    available = available,
                    enabled = enabled,
                    onIntent = onIntent
                )
            }
            DshConsoleAction(
                label = "获取维护屏障",
                intent = DshConsoleIntent.AcquireMaintenanceBarrier,
                available = available,
                enabled = enabled,
                onIntent = onIntent
            )
            DshConsoleAction(
                label = "释放维护屏障",
                intent = DshConsoleIntent.ReleaseMaintenanceBarrier,
                available = available,
                enabled = enabled,
                onIntent = onIntent
            )
            DshConsoleAction(
                label = "导出诊断",
                intent = DshConsoleIntent.ExportDiagnostics,
                available = available,
                enabled = enabled,
                onIntent = onIntent
            )
            Text(
                text = "操作由现有运行时、能力与维护 owner 执行；控制台只提交请求并重新读取状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "投影版本 ${state.model.revision}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun DshConsoleRowView(row: DshConsoleRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = row.label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = row.value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodySmall,
            color = toneColor(row.tone)
        )
    }
}

@Composable
private fun DshConsoleAction(
    label: String,
    intent: DshConsoleIntent,
    available: Set<DshConsoleIntent>,
    enabled: Boolean,
    onIntent: (DshConsoleIntent) -> Unit
) {
    val allowed = intent in available
    McaButton(
        style = McaButtonStyle.Outlined,
        onClick = { onIntent(intent) },
        enabled = enabled && allowed,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)
    }
}

@Composable
private fun toneColor(tone: DshConsoleTone): Color = when (tone) {
    DshConsoleTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    DshConsoleTone.POSITIVE -> MaterialTheme.colorScheme.primary
    DshConsoleTone.WARNING -> MaterialTheme.colorScheme.tertiary
    DshConsoleTone.NEGATIVE -> MaterialTheme.colorScheme.error
}

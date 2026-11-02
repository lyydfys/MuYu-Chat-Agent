package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.designsystem.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.feature.dsh.workspace.DshMaintenanceConsolePolicy
import com.muyuchat.feature.dsh.workspace.DshMaintenanceConsoleUiState
import com.muyuchat.feature.dsh.workspace.DshMaintenanceConsoleViewModel
import com.muyuchat.feature.dsh.workspace.DshMaintenanceDocumentReader
import com.muyuchat.feature.dsh.workspace.requiresUserDocument
import com.muyuchat.feature.dsh.workspace.withDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D23 maintenance section host.
 *
 * A missing port renders an honest disabled note; a present port is projected by
 * [DshMaintenanceConsoleViewModel]. This composable never touches files, SAF, session data or the
 * maintenance owner — it only shows the bounded projection and submits typed intents.
 */
@Composable
fun DshMaintenanceConsoleHost(
    port: DshMaintenanceConsolePort?,
    modifier: Modifier = Modifier,
    refreshKey: Any? = null
) {
    if (port == null) {
        DshMaintenanceConsoleDisabledNote(
            text = "维护接口未接入，数据维护功能不可用。",
            modifier = modifier
        )
        return
    }
    val viewModel: DshMaintenanceConsoleViewModel = viewModel(
        key = "dsh-maintenance-console",
        factory = DshMaintenanceConsoleViewModel.factory(port)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }
    // The maintenance capabilities are host-phase derived; refresh them whenever the console
    // projection changes (stop/restart, manual console refresh) instead of only on first show.
    LaunchedEffect(refreshKey) { if (refreshKey != null) viewModel.refresh() }
    // Patch/theme actions need a user-selected document. The picker is launched only after the
    // policy confirmation, and the document is strictly validated by the maintenance adapter.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDocument by remember { mutableStateOf<DshMaintenanceConsoleIntent?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingDocument
        pendingDocument = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val document = withContext(Dispatchers.IO) {
                DshMaintenanceDocumentReader.read(context, uri)
            }
            if (document == null) {
                viewModel.reportDocumentUnreadable()
            } else {
                viewModel.execute(withDocument(target, document))
            }
        }
    }
    DshMaintenanceConsoleSection(
        state = state,
        onIntent = viewModel::execute,
        onRetry = viewModel::retry,
        onRequestDocument = { intent ->
            pendingDocument = intent
            // The content is validated strictly downstream (patch JSON / theme schema), so the
            // picker must not hide a file just because a provider reports a generic MIME type.
            val launched = runCatching { picker.launch(arrayOf("*/*")) }.isSuccess
            if (!launched) {
                pendingDocument = null
                viewModel.reportDocumentUnreadable()
            }
        },
        modifier = modifier
    )
}

/**
 * Stateless maintenance console section. Destructive intents are never submitted directly: the
 * section first shows the policy confirmation, then submits the confirmed copy of the intent.
 */
@Composable
fun DshMaintenanceConsoleSection(
    state: DshMaintenanceConsoleUiState,
    onIntent: (DshMaintenanceConsoleIntent) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Called instead of [onIntent] for the confirmed intents that need a user document. */
    onRequestDocument: ((DshMaintenanceConsoleIntent) -> Unit)? = null
) {
    var pendingConfirmation by remember { mutableStateOf<DshMaintenanceConsoleIntent?>(null) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "数据维护",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        when (state) {
            DshMaintenanceConsoleUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            }

            is DshMaintenanceConsoleUiState.Disabled ->
                DshMaintenanceConsoleDisabledNote(text = state.reason)

            is DshMaintenanceConsoleUiState.Failure -> {
                Text(
                    text = "无法读取维护状态（${state.code}）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (state.retryable) {
                    McaButton(
                        style = McaButtonStyle.Primary,
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重试")
                    }
                }
            }

            is DshMaintenanceConsoleUiState.Ready -> DshMaintenanceReadyContent(
                state = state,
                onIntent = onIntent,
                onRequestConfirmation = { pendingConfirmation = it }
            )
        }
    }
    val confirmationTarget = pendingConfirmation
    val confirmation = confirmationTarget?.let(DshMaintenanceConsolePolicy::confirmationFor)
    if (confirmationTarget != null && confirmation != null) {
        DshMaintenanceConfirmationDialog(
            title = confirmation.title,
            detail = confirmation.detail,
            confirmLabel = confirmation.confirmLabel,
            onConfirm = {
                pendingConfirmation = null
                val confirmed = DshMaintenanceConsolePolicy.confirmed(confirmationTarget)
                val needsDocument = requiresUserDocument(confirmed) && onRequestDocument != null
                if (needsDocument) {
                    onRequestDocument?.invoke(confirmed)
                } else {
                    onIntent(confirmed)
                }
            },
            onDismiss = { pendingConfirmation = null }
        )
    }
}

@Composable
private fun DshMaintenanceReadyContent(
    state: DshMaintenanceConsoleUiState.Ready,
    onIntent: (DshMaintenanceConsoleIntent) -> Unit,
    onRequestConfirmation: (DshMaintenanceConsoleIntent) -> Unit
) {
    val enabled = !state.busy
    val available = state.availableIntents

    state.notice?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    state.model.sections.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            section.rows.forEach { row -> DshConsoleRowView(row) }
        }
    }
    Text(
        text = "备份",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.model.backups.isEmpty()) {
        Text(
            text = state.model.backupsNote ?: "尚无备份",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        state.model.backups.forEach { backup ->
            MaintenanceItemRow(label = backup.label, detail = backup.detail) {
                DshMaintenanceActionButton(
                    label = "恢复",
                    enabled = enabled && DshMaintenanceConsoleIntent.RestoreBackup(backup.backupId) in available,
                    onIntent = { onRequestConfirmation(DshMaintenanceConsoleIntent.RestoreBackup(backup.backupId)) }
                )
            }
        }
    }
    Text(
        text = "会话",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.model.sessions.isEmpty()) {
        Text(
            text = state.model.sessionsNote ?: "暂无会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        state.model.sessions.forEach { session ->
            MaintenanceItemRow(label = session.label, detail = session.detail) {
                if (session.trashed) {
                    DshMaintenanceActionButton(
                        label = "恢复",
                        enabled = enabled &&
                            DshMaintenanceConsoleIntent.RestoreTrashedSession(session.sessionId) in available,
                        onIntent = {
                            onIntent(DshMaintenanceConsoleIntent.RestoreTrashedSession(session.sessionId))
                        }
                    )
                    DshMaintenanceActionButton(
                        label = "彻底删除",
                        enabled = enabled &&
                            DshMaintenanceConsoleIntent.PurgeTrashedSession(session.sessionId) in available,
                        onIntent = {
                            onRequestConfirmation(
                                DshMaintenanceConsoleIntent.PurgeTrashedSession(session.sessionId)
                            )
                        }
                    )
                } else {
                    DshMaintenanceActionButton(
                        label = "移入回收站",
                        enabled = enabled &&
                            DshMaintenanceConsoleIntent.TrashSession(session.sessionId) in available,
                        onIntent = { onIntent(DshMaintenanceConsoleIntent.TrashSession(session.sessionId)) }
                    )
                    DshMaintenanceActionButton(
                        label = "修复",
                        enabled = enabled &&
                            DshMaintenanceConsoleIntent.RepairSession(session.sessionId) in available,
                        onIntent = {
                            onRequestConfirmation(
                                DshMaintenanceConsoleIntent.RepairSession(session.sessionId)
                            )
                        }
                    )
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(
                com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind.LIST_BACKUPS
            ),
            enabled = enabled && DshMaintenanceConsoleIntent.ListBackups in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.ListBackups) }
        )
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(
                com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind.LIST_SESSIONS
            ),
            enabled = enabled && DshMaintenanceConsoleIntent.ListSessions in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.ListSessions) }
        )
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(
                com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind.RUN_SELF_CHECK
            ),
            enabled = enabled && DshMaintenanceConsoleIntent.RunSelfCheck in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.RunSelfCheck) }
        )
        // Precise repair of the build's declared managed files. It is offered only when the host
        // reports that operation (inventory + verified source present) and always confirms first.
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(
                com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind.REPAIR_MANAGED_FILES
            ),
            enabled = enabled && DshMaintenanceConsoleIntent.RepairManagedFiles() in available,
            onIntent = {
                onRequestConfirmation(DshMaintenanceConsoleIntent.RepairManagedFiles())
            }
        )
        DshMaintenanceActionButton(
            label = "创建备份",
            enabled = enabled && DshMaintenanceConsoleIntent.CreateBackup() in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.CreateBackup()) }
        )
        val safeModeIntent = if (state.model.safeModeActive) {
            DshMaintenanceConsoleIntent.ExitSafeMode
        } else {
            DshMaintenanceConsoleIntent.EnterSafeMode
        }
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(safeModeIntent.kind),
            enabled = enabled && safeModeIntent in available,
            onIntent = { onIntent(safeModeIntent) }
        )
        DshMaintenanceActionButton(
            label = "应用控制台补丁",
            enabled = enabled && DshMaintenanceConsoleIntent.ApplyConsolePatch() in available,
            onIntent = { onRequestConfirmation(DshMaintenanceConsoleIntent.ApplyConsolePatch()) }
        )
        DshMaintenanceActionButton(
            label = "回滚控制台补丁",
            enabled = enabled && DshMaintenanceConsoleIntent.RollbackConsolePatch in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.RollbackConsolePatch) }
        )
        DshMaintenanceActionButton(
            label = "导入主题",
            enabled = enabled && DshMaintenanceConsoleIntent.ImportTheme() in available,
            onIntent = { onRequestConfirmation(DshMaintenanceConsoleIntent.ImportTheme()) }
        )
        DshMaintenanceActionButton(
            label = "导入 MCP 声明",
            enabled = enabled && DshMaintenanceConsoleIntent.ImportMcpDeclaration() in available,
            onIntent = { onRequestConfirmation(DshMaintenanceConsoleIntent.ImportMcpDeclaration()) }
        )
        DshMaintenanceActionButton(
            label = "重置主题",
            enabled = enabled && DshMaintenanceConsoleIntent.ResetTheme in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.ResetTheme) }
        )
        DshMaintenanceActionButton(
            label = "运行整闭包升级",
            enabled = enabled && DshMaintenanceConsoleIntent.RunClosureUpgrade() in available,
            onIntent = { onRequestConfirmation(DshMaintenanceConsoleIntent.RunClosureUpgrade()) }
        )
        DshMaintenanceActionButton(
            label = "恢复升级",
            enabled = enabled && DshMaintenanceConsoleIntent.ResumeClosureUpgrade in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.ResumeClosureUpgrade) }
        )
        DshMaintenanceActionButton(
            label = DshMaintenanceConsolePolicy.kindLabel(
                com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE
            ),
            enabled = enabled && DshMaintenanceConsoleIntent.AbandonClosureUpgrade() in available,
            onIntent = {
                onRequestConfirmation(DshMaintenanceConsoleIntent.AbandonClosureUpgrade())
            }
        )
        DshMaintenanceActionButton(
            label = "导出诊断",
            enabled = enabled && DshMaintenanceConsoleIntent.ExportDiagnostics in available,
            onIntent = { onIntent(DshMaintenanceConsoleIntent.ExportDiagnostics) }
        )
    }
}

@Composable
private fun MaintenanceItemRow(
    label: String,
    detail: String,
    actions: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        actions()
    }
}

@Composable
private fun DshMaintenanceActionButton(
    label: String,
    enabled: Boolean,
    onIntent: () -> Unit
) {
    McaButton(
        style = McaButtonStyle.Outlined,
        onClick = onIntent,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label)
    }
}

@Composable
private fun DshMaintenanceConsoleDisabledNote(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "数据维护",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DshMaintenanceConfirmationDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    McaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) { Text("取消") }
        }
    )
}

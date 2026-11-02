package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoImportSeverity
import com.muyuchat.feature.video.workspace.ImportUiState

/**
 * ComfyUI API-format import preview/save surface (V1-05).
 *
 * The dialog never upgrades a partial answer: diagnostics are listed verbatim with severity and
 * node/path anchors, a non-runnable document is explicitly "savable but not executable", and a
 * rejection keeps the port's code/detail visible. Only a [ImportUiState.Saved] carries a
 * revision id.
 */
@Composable
internal fun VideoImportDialog(
    state: ImportUiState,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is ImportUiState.Idle) return
    val fileName = when (state) {
        is ImportUiState.Idle -> null
        is ImportUiState.Previewing -> state.fileName
        is ImportUiState.Preview -> state.fileName
        is ImportUiState.Rejected -> state.fileName
        is ImportUiState.Saving -> state.fileName
        is ImportUiState.Saved -> state.fileName
    }
    val saving = state is ImportUiState.Saving
    val dismissAction: (@Composable () -> Unit)? = if (state is ImportUiState.Preview) {
        {
            A11yLabeledControl(
                contentDescription = "取消导入",
                onClick = onDismiss,
            ) {
                McaButton(
                    onClick = onDismiss,
                    style = McaButtonStyle.Text,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }
            }
        }
    } else {
        null
    }
    McaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("video.import_dialog"),
        title = { Text(importDialogTitle(state)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                fileName?.let { name ->
                    Text(
                        "文件：$name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when (state) {
                    is ImportUiState.Idle -> Unit
                    is ImportUiState.Previewing -> PreviewingBody()
                    is ImportUiState.Preview -> PreviewBody(
                        runnable = state.runnable,
                        diagnostics = state.diagnostics,
                    )

                    is ImportUiState.Saving -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "正在保存修订…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { contentDescription = "正在保存修订" },
                            )
                        }
                        state.diagnostics.takeIf { it.isNotEmpty() }?.let { diagnostics ->
                            Spacer(Modifier.height(8.dp))
                            DiagnosticList(diagnostics)
                        }
                        if (!state.runnable) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "该修订不可运行，保存后不会执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is ImportUiState.Rejected -> RejectedBody(state)
                    is ImportUiState.Saved -> SavedBody(
                        revisionId = state.revisionId.value,
                        runnable = state.runnable,
                        diagnostics = state.diagnostics,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is ImportUiState.Preview -> A11yLabeledControl(
                    contentDescription = "保存导入修订",
                    onClick = onSave,
                ) {
                    McaButton(
                        onClick = onSave,
                        style = McaButtonStyle.Primary,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("video.import_save"),
                    ) {
                        Text("保存修订")
                    }
                }

                is ImportUiState.Saving -> A11yLabeledControl(
                    contentDescription = "保存导入修订",
                    stateDescription = "正在保存修订…",
                    enabled = false,
                    onClick = {},
                ) {
                    McaButton(
                        onClick = {},
                        style = McaButtonStyle.Primary,
                        enabled = true,
                        loading = true,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("保存修订")
                    }
                }

                is ImportUiState.Saved, is ImportUiState.Rejected -> A11yLabeledControl(
                    contentDescription = "关闭导入预览",
                    onClick = onDismiss,
                ) {
                    McaButton(
                        onClick = onDismiss,
                        style = McaButtonStyle.Text,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("关闭")
                    }
                }

                is ImportUiState.Previewing -> A11yLabeledControl(
                    contentDescription = "取消导入",
                    onClick = onDismiss,
                ) {
                    McaButton(
                        onClick = onDismiss,
                        style = McaButtonStyle.Text,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("取消")
                    }
                }

                is ImportUiState.Idle -> Unit
            }
        },
        dismissButton = dismissAction,
    )
}

private fun importDialogTitle(state: ImportUiState): String = when (state) {
    is ImportUiState.Previewing -> "正在读取 ComfyUI 工作流"
    is ImportUiState.Preview -> "ComfyUI 导入预览"
    is ImportUiState.Rejected -> if (state.code != null) "导入被拒绝" else "导入失败"
    is ImportUiState.Saving -> "保存导入修订"
    is ImportUiState.Saved -> "导入已保存"
    is ImportUiState.Idle -> ""
}

@Composable
private fun PreviewingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "正在解析 API 格式并校验可执行性…",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PreviewBody(
    runnable: Boolean,
    diagnostics: List<VideoImportDiagnostic>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (runnable) McaIcons.CheckCircle else McaIcons.Warning,
                contentDescription = null,
                tint = if (runnable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(McaIconSize.M),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (runnable) "可运行" else "不可运行，可保存，但不会执行",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .testTag("video.import_runnable")
                    .semantics {
                        contentDescription =
                            if (runnable) "此工作流可运行" else "此工作流不可运行，可保存但不会执行"
                    },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (runnable) {
                "端口判定可运行：可执行节点已映射到固定基线。保存后可提交，实际执行仍取决于本机模型与资源。"
            } else {
                "端口判定不可运行。保存后仅作为修订记录，提交时仍会被拒绝。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(8.dp))
        DiagnosticList(diagnostics)
    }
}

@Composable
private fun RejectedBody(state: ImportUiState.Rejected) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                McaIcons.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(McaIconSize.M),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.code?.let { "错误码：${it.name}" } ?: "文件读取/解析失败",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.detail?.let { detail ->
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("video.import_error"),
            )
        }
        if (state.diagnostics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            DiagnosticList(state.diagnostics)
        }
    }
}

@Composable
private fun SavedBody(
    revisionId: String,
    runnable: Boolean,
    diagnostics: List<VideoImportDiagnostic>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                McaIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(McaIconSize.M),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "已保存为修订 $revisionId",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .testTag("video.import_saved")
                    .semantics { contentDescription = "已保存为修订 $revisionId" },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "工作流已载入编辑器；在提交生成之前不会执行任何节点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!runnable) {
            Spacer(Modifier.height(6.dp))
            Text(
                "此修订不可运行，仅作为记录保存；提交时会被拒绝。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (diagnostics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            DiagnosticList(diagnostics)
        }
    }
}

@Composable
private fun DiagnosticList(diagnostics: List<VideoImportDiagnostic>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            importDiagnosticSummary(diagnostics),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("video.import_summary"),
        )
        if (diagnostics.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "没有需要处理的诊断。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        diagnostics.forEach { diagnostic ->
            Spacer(Modifier.height(6.dp))
            Text(
                importDiagnosticLine(diagnostic),
                style = MaterialTheme.typography.bodySmall,
                color = when (diagnostic.severity) {
                    VideoImportSeverity.ERROR -> MaterialTheme.colorScheme.error
                    VideoImportSeverity.WARNING -> MaterialTheme.colorScheme.onSurface
                    VideoImportSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

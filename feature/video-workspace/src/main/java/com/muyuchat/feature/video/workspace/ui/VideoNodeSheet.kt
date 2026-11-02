package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoWorkflowIssue
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel
import com.muyuchat.feature.video.workspace.ui.VideoNodePresentations.FieldKind

/**
 * The parameter sheet of one node (proposal §9.3). Fields come from the type registry; edits
 * write straight into the draft (a draft MAY be temporarily invalid — the shared validator's
 * issues for this node are shown inline). Deleting a node first previews the edges that die
 * with it; nothing is removed implicitly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoNodeSheet(
    node: VideoNode,
    issues: List<VideoWorkflowIssue>,
    viewModel: VideoWorkspaceViewModel,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                VideoNodePresentations.labelOf(node.typeId),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                node.nodeId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (issues.isNotEmpty()) {
                IssuesBlock(issues)
                Spacer(Modifier.height(8.dp))
            }

            VideoNodePresentations.fieldsOf(node.typeId).forEach { field ->
                NodeFieldRow(node, field, viewModel)
                Spacer(Modifier.height(6.dp))
            }

            ConnectionsBlock(node, viewModel)
            Spacer(Modifier.height(12.dp))

            A11yLabeledControl(
                contentDescription = "删除此节点",
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                McaButton(
                    onClick = { confirmDelete = true },
                    style = McaButtonStyle.Destructive,
                    shape = ButtonDefaults.filledTonalShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("删除此节点")
                }
            }

            if (confirmDelete) {
                val impact = viewModel.deleteImpact(node.nodeId)
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("删除节点") },
                    text = {
                        Text(
                            if (impact.isEmpty()) {
                                "该节点没有连线，删除只移除它本身。"
                            } else {
                                buildString {
                                    append("删除会同时移除 ${impact.size} 条连线：\n")
                                    impact.take(6).forEach { edge ->
                                        append("· ${edge.fromNodeId}.${edge.fromPort} → ${edge.toNodeId}.${edge.toPort}\n")
                                    }
                                    if (impact.size > 6) append("… 共 ${impact.size} 条\n")
                                    append("下游节点会因缺少输入而标记问题，不会被自动删除。")
                                }
                            },
                        )
                    },
                    confirmButton = {
                        val confirmDeleteAction: () -> Unit = {
                            confirmDelete = false
                            viewModel.deleteNode(node.nodeId)
                            onDismiss()
                        }
                        A11yLabeledControl(
                            contentDescription = "确认删除节点",
                            onClick = confirmDeleteAction,
                        ) {
                            TextButton(onClick = confirmDeleteAction) { Text("删除") }
                        }
                    },
                    dismissButton = {
                        A11yLabeledControl(
                            contentDescription = "取消删除",
                            onClick = { confirmDelete = false },
                        ) {
                            TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun IssuesBlock(issues: List<VideoWorkflowIssue>) {
    Column {
        issues.take(5).forEach { issue ->
            Text(
                "· ${issue.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (issues.size > 5) {
            Text(
                "… 另有 ${issues.size - 5} 个问题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeFieldRow(
    node: VideoNode,
    field: VideoNodePresentations.FieldSpec,
    viewModel: VideoWorkspaceViewModel,
) {
    val current = node.params[field.name]
    when (field.kind) {
        FieldKind.PROMPT_TEXT, FieldKind.NEGATIVE_TEXT, FieldKind.SAMPLER_NAME -> {
            val initial = (current as? VideoNodeValue.TextValue)?.text.orEmpty()
            var text by remember(node.nodeId, field.name) { mutableStateOf(initial) }
            LaunchedEffect(initial) { if (text != initial && initial.isNotEmpty()) text = initial }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    viewModel.setParam(node.nodeId, field.name, VideoNodeValue.TextValue(value))
                },
                label = { Text(field.label) },
                supportingText = field.hint?.let { hint -> { Text(hint) } },
                minLines = if (field.kind == FieldKind.PROMPT_TEXT) 2 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FieldKind.LONG, FieldKind.SEED -> {
            val initial = (current as? VideoNodeValue.LongValue)?.value?.toString().orEmpty()
            var text by remember(node.nodeId, field.name) { mutableStateOf(initial) }
            var parseError by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    val parsed = value.toLongOrNull()
                    if (parsed == null) {
                        parseError = value.isNotEmpty()
                    } else {
                        parseError = false
                        viewModel.setParam(node.nodeId, field.name, VideoNodeValue.LongValue(parsed))
                    }
                },
                label = { Text(field.label) },
                isError = parseError,
                supportingText = {
                    when {
                        parseError -> Text("请输入整数")
                        field.hint != null -> Text(field.hint)
                        else -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FieldKind.DECIMAL -> {
            val initial = when (current) {
                is VideoNodeValue.DecimalValue -> formatDecimal(current)
                else -> ""
            }
            var text by remember(node.nodeId, field.name) { mutableStateOf(initial) }
            var parseError by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    val decimal = parseDecimal(value)
                    if (decimal == null) {
                        parseError = value.isNotEmpty()
                    } else {
                        parseError = false
                        viewModel.setParam(node.nodeId, field.name, decimal)
                    }
                },
                label = { Text(field.label) },
                isError = parseError,
                supportingText = {
                    when {
                        parseError -> Text("请输入小数（如 6.0）")
                        field.hint != null -> Text(field.hint)
                        else -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionsBlock(node: VideoNode, viewModel: VideoWorkspaceViewModel) {
    val document = viewModel.document()
    val incoming = document.edges.filter { it.toNodeId == node.nodeId }
    val outgoing = document.edges.filter { it.fromNodeId == node.nodeId }
    if (incoming.isEmpty() && outgoing.isEmpty()) return
    Column {
        Text(
            "连线",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        (incoming + outgoing).forEach { edge ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${edge.fromNodeId}.${edge.fromPort} → ${edge.toNodeId}.${edge.toPort}",
                    style = MaterialTheme.typography.bodySmall,
                )
                A11yLabeledControl(
                    contentDescription =
                        "断开连线 ${edge.fromNodeId}.${edge.fromPort} → ${edge.toNodeId}.${edge.toPort}",
                    onClick = {
                        viewModel.disconnect(edge.fromNodeId, edge.fromPort, edge.toNodeId, edge.toPort)
                    },
                ) {
                    TextButton(
                        onClick = {
                            viewModel.disconnect(edge.fromNodeId, edge.fromPort, edge.toNodeId, edge.toPort)
                        },
                    ) {
                        Text("断开")
                    }
                }
            }
        }
    }
}

internal fun formatDecimal(value: VideoNodeValue.DecimalValue): String {
    // Same rendering rule as the contract's canonical form, kept local because the canonical
    // writer is contract-internal: plain digits, no trailing fraction zeros.
    if (value.scale == 0 || value.unscaled == 0L) {
        return if (value.unscaled == 0L) "0" else value.unscaled.toString()
    }
    val negative = value.unscaled < 0
    val digits = (if (negative) -value.unscaled else value.unscaled).toString()
    return if (digits.length <= value.scale) {
        (if (negative) "-0." else "0.") + "0".repeat(value.scale - digits.length) + digits.trimEnd('0').ifEmpty { "0" }
    } else {
        val intPart = digits.substring(0, digits.length - value.scale)
        val fracPart = digits.substring(digits.length - value.scale).trimEnd('0')
        (if (negative) "-" else "") + intPart + (if (fracPart.isNotEmpty()) ".$fracPart" else "")
    }
}

internal fun parseDecimal(text: String): VideoNodeValue.DecimalValue? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val negative = trimmed.startsWith("-")
    val body = if (negative) trimmed.drop(1) else trimmed
    val parts = body.split('.')
    if (parts.size > 2) return null
    val intPart = parts[0].ifEmpty { "0" }
    if (intPart.any { !it.isDigit() }) return null
    val fracPart = if (parts.size == 2) parts[1] else ""
    if (fracPart.any { !it.isDigit() }) return null
    if (parts.size == 2 && fracPart.isEmpty()) return null
    val scale = fracPart.length
    val unscaledText = intPart + fracPart
    val unscaledMagnitude = unscaledText.toLongOrNull() ?: return null
    val unscaled = if (negative) -unscaledMagnitude else unscaledMagnitude
    if (scale > 9) return null
    return VideoNodeValue.DecimalValue(unscaled, scale)
}

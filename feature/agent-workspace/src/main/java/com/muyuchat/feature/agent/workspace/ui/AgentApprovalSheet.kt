package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentApprovalUi
import com.muyuchat.feature.agent.workspace.AgentOverwriteUi
import com.muyuchat.core.designsystem.McaIconSize

/**
 * One-shot approval sheet in the DSH conversation language. The middle section scrolls inside a
 * 28dp sheet, the deny/allow actions stay pinned and reachable, and nothing requests initial
 * focus, so the destructive allow action is never the default focus target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentApprovalSheet(
    approval: AgentApprovalUi,
    inFlight: Boolean,
    errorMessage: String?,
    onDecision: (AgentApprovalDecision) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .testTag("agent.approval.sheet")
        ) {
            ApprovalHeader(approval)
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (approval.expired) {
                    ApprovalWarningCard("授权已过期，允许操作不会生效。")
                    Spacer(Modifier.height(10.dp))
                }
                approval.scopeSummary?.let { scope ->
                    ApprovalGroupCard("资源范围", listOf(scope))
                    Spacer(Modifier.height(10.dp))
                }
                ApprovalGroupCard("读取目标", approval.readTargets)
                ApprovalGroupCard("写入目标", approval.writeTargets)
                ApprovalGroupCard("网络目标", approval.networkTargets)
                approval.overwrite?.let { overwrite ->
                    Spacer(Modifier.height(10.dp))
                    OverwriteCard(overwrite)
                }
                approval.argumentsSummary?.let { arguments ->
                    Spacer(Modifier.height(10.dp))
                    ApprovalArgumentsCard(
                        arguments = arguments,
                        truncated = approval.argumentsTruncated
                    )
                }
                if (!approval.hasStructuredDetails) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "运行器未提供结构化目标摘要；以下冻结哈希是本次授权的精确绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                ApprovalBoundIdentityCard(approval)
                Spacer(Modifier.height(10.dp))
                ApprovalFrozenBindingCard(approval)
                if (errorMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    ApprovalWarningCard(errorMessage)
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                McaButton(
                    style = McaButtonStyle.Outlined,
                    shape = ButtonDefaults.outlinedShape,
                    onClick = { onDecision(AgentApprovalDecision.DENY) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("agent.approval.deny"),
                    enabled = !inFlight && approval.actionable
                ) {
                    Text("拒绝")
                }
                McaButton(
                    style = McaButtonStyle.Primary,
                    shape = ButtonDefaults.shape,
                    onClick = { onDecision(AgentApprovalDecision.ALLOW_ONCE) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("agent.approval.allowOnce"),
                    enabled = !inFlight && approval.actionable
                ) {
                    Text("允许一次")
                }
            }
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .padding(bottom = 8.dp)
            ) {
                Text("稍后处理")
            }
        }
    }
}

@Composable
private fun ApprovalHeader(approval: AgentApprovalUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = McaIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(McaIconSize.M)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "工具授权请求",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = approval.toolLabel ?: approval.toolAction,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            approval.actionLabel?.takeIf { it != approval.toolLabel }?.let { action ->
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "来源：${approval.source.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("agent.approval.source")
            )
            Text(
                text = "${approval.statusLabel} · ${approval.expiresText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApprovalGroupCard(label: String, targets: List<String>) {
    if (targets.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            targets.forEach { target ->
                Text(
                    text = target,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun OverwriteCard(overwrite: AgentOverwriteUi) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "覆盖变更",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            overwrite.path?.let { path ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "路径：$path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
            OverwriteHashLine("覆盖前", overwrite.beforeHash ?: "新建文件")
            OverwriteHashLine("覆盖后", overwrite.afterHash ?: "未提供")
        }
    }
}

@Composable
private fun OverwriteHashLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ApprovalArgumentsCard(arguments: String, truncated: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "参数摘要",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (truncated) {
                Text(
                    text = "参数摘要已截断，执行只使用冻结后的完整参数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ApprovalBoundIdentityCard(approval: AgentApprovalUi) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.testTag("agent.approval.binding")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "绑定身份",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "身份 ${approval.principal ?: "未提供"} · 授权 ${approval.approvalIdShort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "调用 ${approval.invocationIdShort} · 运行 epoch ${approval.runEpoch}" +
                    " · 策略 v${approval.policyVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApprovalFrozenBindingCard(approval: AgentApprovalUi) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = "冻结绑定",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "参数 ${approval.argsHashShort} · Schema ${approval.schemaHashShort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "资源 ${approval.resourceFingerprintShort} · 策略 v${approval.policyVersion}" +
                    " · 运行 epoch ${approval.runEpoch}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApprovalWarningCard(message: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

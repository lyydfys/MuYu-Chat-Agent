package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.feature.agent.workspace.AgentModelOption
import com.muyuchat.feature.agent.workspace.AgentModelPickerUiState
import com.muyuchat.feature.agent.workspace.CLOUD_MODEL_SECTION_TITLE
import com.muyuchat.feature.agent.workspace.CLOUD_PROTOCOL_UNSUPPORTED_REASON
import com.muyuchat.feature.agent.workspace.LOCAL_MODEL_SECTION_TITLE
import com.muyuchat.feature.agent.workspace.MODEL_DIRECT_LOAD_ACTION
import com.muyuchat.feature.agent.workspace.MODEL_UNLOADED_ACTION
import com.muyuchat.feature.agent.workspace.modelLoadBadge
import com.muyuchat.feature.agent.workspace.modelUnloadedPickerHint

/**
 * Model picker grouped by the only honest source split: 本地模型 (on-device engine) and 云端模型
 * (configured remote providers). Local rows expose the real load state; unloaded local rows keep the
 * required "去聊天页加载" leave-tab action and a real direct load action wired by the host. Selecting
 * an unloaded row never starts a task: `canCreate` stays false until the engine reports the model as
 * loaded. Cloud rows show their protocol; a cloud protocol with no Agent terminal policy is visibly
 * disabled with the exact reason instead of pretending it is available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModelPickerSheet(
    models: AgentModelPickerUiState,
    onSelect: (String) -> Unit,
    onNetworkEnabledChange: (Boolean) -> Unit,
    onLoadModel: (String) -> Unit,
    onOpenChatToLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
                .testTag("agent.model.sheet")
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "允许联网",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "关闭时只能使用离线模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaSwitch(
                    checked = models.networkEnabled,
                    onCheckedChange = onNetworkEnabledChange,
                    contentDescription = "允许联网"
                )
            }
            Text(
                text = "云端模型只列出已配置项；协议为 NONE 的模型不支持 Agent，已禁用选择。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (models.localOptions.any { it.loadState == AgentModelLoadState.UNLOADED }) {
                Text(
                    text = "已安装但未加载的本地模型可以查看，但不能开始任务；请先去聊天页加载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            models.errorMessage?.let { message ->
                AgentInlineBanner(message = message, isError = true)
            }
            models.selectionReason?.let { reason ->
                Text(
                    text = reason,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("agent.model.selection.reason"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (models.options.isEmpty()) {
                AgentStaticLoading(
                    message = if (models.loaded) {
                        "尚未配置可用模型。请先在模型管理中下载或导入模型。"
                    } else {
                        "正在载入模型目录…"
                    }
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (models.localOptions.isNotEmpty()) {
                        item("agent.model.section.local") {
                            AgentSectionHeader(
                                title = LOCAL_MODEL_SECTION_TITLE,
                                trailing = models.localOptions.size.toString(),
                                modifier = Modifier.testTag("agent.model.section.local")
                            )
                        }
                        items(models.localOptions, key = { it.key }) { option ->
                            ModelOptionRow(
                                option = option,
                                selected = option.key == models.selectedKey,
                                onClick = { onSelect(option.key) },
                                onLoad = { onLoadModel(option.modelId) },
                                onOpenChatToLoad = onOpenChatToLoad
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    if (models.cloudOptions.isNotEmpty()) {
                        item("agent.model.section.cloud") {
                            AgentSectionHeader(
                                title = CLOUD_MODEL_SECTION_TITLE,
                                trailing = models.cloudOptions.size.toString(),
                                modifier = Modifier.testTag("agent.model.section.cloud")
                            )
                        }
                        items(models.cloudOptions, key = { it.key }) { option ->
                            ModelOptionRow(
                                option = option,
                                selected = option.key == models.selectedKey,
                                onClick = { onSelect(option.key) },
                                onLoad = { onLoadModel(option.modelId) },
                                onOpenChatToLoad = onOpenChatToLoad
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOptionRow(
    option: AgentModelOption,
    selected: Boolean,
    onClick: () -> Unit,
    onLoad: () -> Unit,
    onOpenChatToLoad: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = option.selectable,
                    onClickLabel = if (option.selectable) "选择此模型" else null,
                    onClick = onClick
                )
                .heightIn(min = 56.dp)
                .padding(vertical = 10.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (option.isLocal) {
                        LocalLoadBadge(option.loadState)
                    }
                    AgentTag(option.protocolLabel)
                    AgentTag(option.offlineLabel)
                    AgentTag(option.runtimeLabel)
                }
            }
            if (selected) {
                Icon(
                    imageVector = McaIcons.Check,
                    contentDescription = "当前选择",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (!option.isLocal && option.protocolMode == AgentProtocolMode.NONE) {
            Text(
                text = CLOUD_PROTOCOL_UNSUPPORTED_REASON,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("agent.model.unsupported.${option.modelId}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (option.isLocal && option.loadState == AgentModelLoadState.UNLOADED) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = modelUnloadedPickerHint(option.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = onOpenChatToLoad,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("agent.model.load.${option.modelId}")
                    ) {
                        Text(
                            text = MODEL_UNLOADED_ACTION,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = onLoad,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("agent.model.load.direct.${option.modelId}")
                    ) {
                        Text(
                            text = MODEL_DIRECT_LOAD_ACTION,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

/** Real load state for a local row; cloud rows do not carry a local-engine state at all. */
@Composable
private fun LocalLoadBadge(loadState: AgentModelLoadState) {
    val loaded = loadState == AgentModelLoadState.LOADED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (loaded) {
            Icon(
                imageVector = McaIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        }
        AgentTag(
            text = modelLoadBadge(loadState),
            color = if (loaded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (loaded) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

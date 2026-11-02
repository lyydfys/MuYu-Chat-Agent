package com.muyuchat.feature.agent.workspace.workflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.feature.agent.workspace.ui.AgentInlineBanner
import com.muyuchat.feature.agent.workspace.ui.AgentPrimaryButton
import com.muyuchat.feature.agent.workspace.ui.AgentSectionHeader
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsEmptyState
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsNote
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsScaffold
import com.muyuchat.feature.agent.workspace.ui.AgentStaticLoading
import com.muyuchat.feature.agent.workspace.ui.AgentTag
import kotlinx.coroutines.launch

/** Intents of [WorkflowRunScreen]; every callback is forwarded to [WorkflowRunStateHolder]. */
data class WorkflowRunActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectDefinition: (String) -> Unit = {},
    val onRun: () -> Unit = {},
    val onDismissDefinitionsError: () -> Unit = {},
    val onDismissHistoryError: () -> Unit = {},
    val onDismissActionError: () -> Unit = {},
    val onDismissNotice: () -> Unit = {}
)

/**
 * Lifecycle-aware route: collects the holder state, launches intents on the composition scope and
 * performs the first load when the surface enters composition. The host only has to mount this
 * behind an existing entry; it owns no MainViewModel/MainActivity reference.
 */
@Composable
fun WorkflowRunRoute(
    holder: WorkflowRunStateHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialDefinitionId: String? = null
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actions = remember(holder, onBack, scope) {
        WorkflowRunActions(
            onBack = onBack,
            onRefresh = { scope.launch { holder.refresh() } },
            onSelectDefinition = { id -> scope.launch { holder.selectDefinition(id) } },
            onRun = { scope.launch { holder.runSelected() } },
            onDismissDefinitionsError = holder::dismissDefinitionsError,
            onDismissHistoryError = holder::dismissHistoryError,
            onDismissActionError = holder::dismissActionError,
            onDismissNotice = holder::dismissNotice
        )
    }
    LaunchedEffect(holder, initialDefinitionId) {
        holder.refresh()
        initialDefinitionId?.let { id -> holder.selectDefinition(id) }
    }
    WorkflowRunScreen(state = state, actions = actions, modifier = modifier)
}

/**
 * Manual workflow run surface: definition selection, explicit run, terminal receipt and bounded
 * history.
 *
 * Rendering only: the immutable [state] decides every branch and [actions] forwards intents. There
 * is no editor, no schedule control and no implicit retry; a repeated click is answered by the
 * facade receipt (shown as a replay notice) rather than by a second local execution.
 */
@Composable
fun WorkflowRunScreen(
    state: WorkflowRunState,
    actions: WorkflowRunActions,
    modifier: Modifier = Modifier
) {
    AgentSettingsScaffold(
        title = "工作流运行",
        listTestTag = TAG_SCREEN,
        backDescription = "返回工作流",
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
        refreshEnabled = state.canRefresh,
        modifier = modifier
    ) {
        state.notice?.let { notice ->
            item("workflow.run.notice") {
                Column(Modifier.testTag(TAG_NOTICE)) {
                    AgentInlineBanner(message = notice, onDismiss = actions.onDismissNotice)
                }
            }
        }
        state.actionError?.let { error ->
            item("workflow.run.actionError") {
                Column(Modifier.testTag(TAG_ACTION_ERROR)) {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.onDismissActionError
                    )
                }
            }
        }

        item("workflow.run.definitions.header") {
            AgentSectionHeader(
                title = "工作流定义",
                trailing = "${state.definitions.size} 个"
            )
        }
        state.definitionsError?.let { error ->
            item("workflow.run.definitions.error") {
                Column(Modifier.testTag(TAG_DEFINITIONS_ERROR)) {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.onDismissDefinitionsError
                    )
                }
            }
        }
        when {
            state.loadingDefinitions && state.definitions.isEmpty() -> item("workflow.run.definitions.loading") {
                AgentStaticLoading(
                    message = "正在载入工作流定义…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            state.definitionsEmpty -> item("workflow.run.definitions.empty") {
                AgentSettingsEmptyState(
                    title = "尚无工作流定义",
                    body = "请先在「工作流」页创建任务配方，再回到这里手动运行。"
                )
            }

            else -> items(state.definitions, key = { it.definitionId }) { definition ->
                DefinitionRow(
                    definition = definition,
                    selected = state.isSelected(definition.definitionId),
                    onClick = { actions.onSelectDefinition(definition.definitionId) }
                )
            }
        }

        item("workflow.run.submit") {
            Column(Modifier.padding(top = 12.dp)) {
                AgentPrimaryButton(
                    text = if (state.running) "正在运行…" else "运行所选工作流",
                    onClick = actions.onRun,
                    enabled = state.canRun,
                    busy = state.running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_RUN)
                        .semantics {
                            contentDescription = if (state.running) {
                                "工作流正在运行，运行按钮暂不可用"
                            } else {
                                "运行所选工作流"
                            }
                        }
                )
                val selected = state.selectedDefinition
                if (selected != null && !selected.runnable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "该定义版本标签「${selected.versionLabel}」无法解析为具体版本，不能运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(TAG_VERSION_HINT)
                    )
                }
            }
        }

        state.receipt?.let { receipt ->
            item("workflow.run.receipt") { ReceiptCard(receipt) }
        }

        item("workflow.run.history.header") {
            AgentSectionHeader(title = "运行历史", trailing = "${state.history.size} 条")
        }
        state.historyError?.let { error ->
            item("workflow.run.history.error") {
                Column(Modifier.testTag(TAG_HISTORY_ERROR)) {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.onDismissHistoryError
                    )
                }
            }
        }
        when {
            state.loadingHistory && state.history.isEmpty() -> item("workflow.run.history.loading") {
                AgentStaticLoading(
                    message = "正在载入运行历史…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            state.historyEmpty -> item("workflow.run.history.empty") {
                AgentSettingsEmptyState(
                    title = "尚无运行记录",
                    body = "运行一次工作流后，运行编号、状态、触发方式和时间会显示在这里。"
                )
            }

            else -> items(state.history, key = { it.runId }) { row -> HistoryRow(row) }
        }

        item("workflow.run.note") {
            AgentSettingsNote(
                text = "这里只发起手动运行并读取既有运行历史；定时触发由后台调度负责。" +
                    "重复点击同一操作会复用上次 receipt，不会创建第二次运行。"
            )
        }
        state.lastRefreshLabel?.let { label ->
            item("workflow.run.refreshed") {
                Text(
                    text = "上次刷新 $label",
                    modifier = Modifier.testTag(TAG_REFRESHED),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DefinitionRow(
    definition: WorkflowRunDefinitionUi,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(TAG_DEFINITION_PREFIX + definition.definitionId)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (definition.runnable) {
                        "工作流 ${definition.displayName}，版本 ${definition.versionLabel}"
                    } else {
                        "工作流 ${definition.displayName}，版本 ${definition.versionLabel}，版本无法解析"
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The icon itself must select too: a null onClick made taps on the radio a no-op on
            // device while the rest of the row worked, so users who tap the control saw nothing.
            RadioButton(selected = selected, onClick = onClick)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text(
                    text = definition.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentTag(
                        text = "版本 ${definition.versionLabel}",
                        modifier = Modifier.testTag(TAG_VERSION)
                    )
                    definition.updatedLabel?.let { label ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "更新 $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (definition.goalPreview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = definition.goalPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ReceiptCard(receipt: WorkflowRunReceiptUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag(TAG_RECEIPT)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("运行结果：").append(receipt.statusLabel)
                    receipt.runId?.let { append("，运行编号 ").append(it) }
                    receipt.errorCode?.let { append("，错误 ").append(it) }
                    if (receipt.replay) append("，复用了上次结果")
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentTag(
                text = receipt.statusLabel,
                modifier = Modifier
                    .testTag(TAG_RECEIPT_STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = receipt.finishedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        receipt.runId?.let { runId ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "运行编号 $runId",
                modifier = Modifier.testTag(TAG_RECEIPT_RUN_ID),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val detail = receipt.errorCode ?: receipt.safeMessage
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(receipt.errorCode, receipt.safeMessage).joinToString(" · "),
                modifier = Modifier.testTag(TAG_RECEIPT_ERROR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (receipt.replay) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = WorkflowRunStateHolder.NOTICE_REPLAY,
                modifier = Modifier.testTag(TAG_RECEIPT_REPLAY),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryRow(row: WorkflowRunHistoryRowUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .testTag(TAG_HISTORY_ITEM_PREFIX + row.runId)
            .semantics(mergeDescendants = true) {
                contentDescription = "运行 ${row.runId}，${row.statusLabel}，" +
                    "${row.triggerLabel}，${row.timeLabel}"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.runId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            AgentTag(text = row.statusLabel, modifier = Modifier.testTag(TAG_HISTORY_STATUS))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${row.detailLabel} · ${row.triggerLabel} · ${row.timeLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        row.errorCode?.let { code ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal const val TAG_SCREEN: String = "workflow.run"
internal const val TAG_NOTICE: String = "workflow.run.notice.text"
internal const val TAG_ACTION_ERROR: String = "workflow.run.actionError.text"
internal const val TAG_DEFINITIONS_ERROR: String = "workflow.run.definitionsError.text"
internal const val TAG_HISTORY_ERROR: String = "workflow.run.historyError.text"
internal const val TAG_DEFINITION_PREFIX: String = "workflow.run.definition."
internal const val TAG_VERSION: String = "workflow.run.version"
internal const val TAG_VERSION_HINT: String = "workflow.run.versionHint"
internal const val TAG_RUN: String = "workflow.run.submit"
internal const val TAG_RECEIPT: String = "workflow.run.receipt"
internal const val TAG_RECEIPT_STATUS: String = "workflow.run.receipt.status"
internal const val TAG_RECEIPT_RUN_ID: String = "workflow.run.receipt.runId"
internal const val TAG_RECEIPT_ERROR: String = "workflow.run.receipt.error"
internal const val TAG_RECEIPT_REPLAY: String = "workflow.run.receipt.replay"
internal const val TAG_HISTORY_ITEM_PREFIX: String = "workflow.run.history.item."
internal const val TAG_HISTORY_STATUS: String = "workflow.run.history.status"
internal const val TAG_REFRESHED: String = "workflow.run.refreshed"

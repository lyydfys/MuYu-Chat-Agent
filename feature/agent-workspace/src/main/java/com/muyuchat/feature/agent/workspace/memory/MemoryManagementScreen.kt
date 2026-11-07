package com.muyuchat.feature.agent.workspace.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.feature.agent.workspace.ui.AgentInlineBanner
import com.muyuchat.feature.agent.workspace.ui.AgentPrimaryButton
import com.muyuchat.feature.agent.workspace.ui.AgentSectionHeader
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsEmptyState
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsNote
import com.muyuchat.feature.agent.workspace.ui.AgentSettingsScaffold
import com.muyuchat.feature.agent.workspace.ui.AgentStaticLoading
import com.muyuchat.feature.agent.workspace.ui.AgentTag
import kotlinx.coroutines.launch

/**
 * Intents of [MemoryManagementScreen]. Every callback is forwarded to
 * [MemoryManagementStateHolder]; the screen itself owns no business rule.
 */
data class MemoryManagementActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelectSpace: (String) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onIncludeRevokedChange: (Boolean) -> Unit = {},
    val onDeleteItem: (String) -> Unit = {},
    val onRestoreItem: (String) -> Unit = {},
    val onEditItem: (String) -> Unit = {},
    val onEditorTitleChange: (String) -> Unit = {},
    val onEditorTextChange: (String) -> Unit = {},
    val onSaveEditor: () -> Unit = {},
    val onCloseEditor: () -> Unit = {},
    val onExtractionInputChange: (String) -> Unit = {},
    val onSubmitExtraction: () -> Unit = {},
    val onAcceptCandidate: (String) -> Unit = {},
    val onRejectCandidate: (String) -> Unit = {},
    val onDismissActionError: () -> Unit = {},
    val onDismissSpacesError: () -> Unit = {},
    val onDismissItemsError: () -> Unit = {},
    val onDismissCandidatesError: () -> Unit = {},
    val onDismissNotice: () -> Unit = {}
)

/**
 * Lifecycle-aware glue: collects the holder state, launches intents on the composition scope and
 * loads the first page when the surface enters composition. A host only needs to place this route
 * behind its existing memory settings entry.
 */
@Composable
fun MemoryManagementRoute(
    holder: MemoryManagementStateHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val actions = remember(holder, onBack, scope) {
        MemoryManagementActions(
            onBack = onBack,
            onRefresh = { scope.launch { holder.refresh() } },
            onSelectSpace = { spaceId -> scope.launch { holder.selectSpace(spaceId) } },
            onQueryChange = holder::onQueryChange,
            onSearch = { scope.launch { holder.search() } },
            onLoadMore = { scope.launch { holder.loadMore() } },
            onIncludeRevokedChange = { enabled -> scope.launch { holder.setIncludeRevoked(enabled) } },
            onDeleteItem = { itemId -> scope.launch { holder.deleteItem(itemId) } },
            onRestoreItem = { itemId -> scope.launch { holder.restoreItem(itemId) } },
            onEditItem = { itemId -> scope.launch { holder.openEditor(itemId) } },
            onEditorTitleChange = holder::onEditorTitleChange,
            onEditorTextChange = holder::onEditorTextChange,
            onSaveEditor = { scope.launch { holder.saveEditor() } },
            onCloseEditor = holder::closeEditor,
            onExtractionInputChange = holder::onExtractionInputChange,
            onSubmitExtraction = { scope.launch { holder.submitExtraction() } },
            onAcceptCandidate = { candidateId ->
                scope.launch { holder.acceptCandidates(listOf(candidateId)) }
            },
            onRejectCandidate = { candidateId ->
                scope.launch { holder.rejectCandidates(listOf(candidateId)) }
            },
            onDismissActionError = holder::dismissActionError,
            onDismissSpacesError = holder::dismissSpacesError,
            onDismissItemsError = holder::dismissItemsError,
            onDismissCandidatesError = holder::dismissCandidatesError,
            onDismissNotice = holder::dismissNotice
        )
    }
    LaunchedEffect(holder) { holder.refresh() }
    MemoryManagementScreen(state = state, actions = actions, modifier = modifier)
}

/**
 * Full memory management surface: spaces, paged/keyword item list, editor, delete/restore and
 * extraction review.
 *
 * Rendering only: the immutable [state] decides every branch and [actions] forwards intents. Loading,
 * empty, typed error and busy states are all explicit; revoking is presented as a recoverable delete
 * because the backend keeps a tombstone.
 */
@Composable
fun MemoryManagementScreen(
    state: MemoryManagementState,
    actions: MemoryManagementActions,
    modifier: Modifier = Modifier
) {
    AgentSettingsScaffold(
        title = "记忆管理",
        listTestTag = TEST_TAG_SCREEN,
        backDescription = "返回任务列表",
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
        refreshEnabled = !state.loadingSpaces,
        modifier = modifier
    ) {
        state.notice?.let { notice ->
            item("memory.management.notice") {
                AgentInlineBanner(
                    message = notice,
                    onDismiss = actions.onDismissNotice
                )
            }
        }
        state.actionError?.let { error ->
            item("memory.management.actionError") {
                ErrorBanner(
                    error = error,
                    onDismiss = actions.onDismissActionError,
                    testTag = "memory.management.actionError.text"
                )
            }
        }

        item("memory.management.spaces.header") {
            AgentSectionHeader(
                title = "记忆空间",
                trailing = "${state.spaces.size} 个空间"
            )
        }
        when {
            state.loadingSpaces && state.spaces.isEmpty() -> item("memory.management.spaces.loading") {
                AgentStaticLoading(
                    message = "正在载入记忆空间…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            state.spacesError != null -> item("memory.management.spaces.error") {
                ErrorBanner(
                    error = requireNotNull(state.spacesError),
                    onDismiss = actions.onDismissSpacesError,
                    testTag = "memory.management.spaces.error.text"
                )
            }

            state.spacesEmpty -> item("memory.management.spaces.empty") {
                AgentSettingsEmptyState(
                    title = "尚无记忆空间",
                    body = "这里只显示本机真实存在的记忆空间；不会创建或补造空间。"
                )
            }

            else -> state.spaces.forEach { space ->
                item("memory.management.space.${space.spaceId}") {
                    SpaceRow(space = space, selected = state.isSelected(space.spaceId), actions = actions)
                }
            }
        }

        val selectedSpace = state.selectedSpace
        if (selectedSpace == null) {
            item("memory.management.selectHint") {
                AgentSettingsNote("选择上方任一空间后，可查看、搜索、编辑、删除或恢复它的条目。")
            }
            return@AgentSettingsScaffold
        }

        item("memory.management.items.header") {
            AgentSectionHeader(
                title = "条目",
                trailing = "共 ${state.itemsTotal} 条 · ${selectedSpace.displayName}"
            )
        }
        item("memory.management.search") {
            SearchRow(state = state, actions = actions)
        }
        item("memory.management.includeRevoked") {
            IncludeRevokedRow(
                checked = state.includeRevoked,
                enabled = !state.busy,
                onCheckedChange = actions.onIncludeRevokedChange
            )
        }

        when {
            state.loadingItems && state.items.isEmpty() -> item("memory.management.items.loading") {
                AgentStaticLoading(
                    message = "正在载入记忆条目…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            state.itemsError != null -> item("memory.management.items.error") {
                ErrorBanner(
                    error = requireNotNull(state.itemsError),
                    onDismiss = actions.onDismissItemsError,
                    testTag = "memory.management.items.error.text"
                )
            }

            state.itemsEmpty -> item("memory.management.items.empty") {
                AgentSettingsEmptyState(
                    title = if (state.query.isBlank()) "该空间还没有条目" else "没有匹配的条目",
                    body = if (state.query.isBlank()) {
                        "可以手动记录、导入文件，或在下方从文本提取候选记忆。"
                    } else {
                        "换一个关键词，或清空搜索后查看全部条目。"
                    }
                )
            }

            else -> state.items.forEach { item ->
                item("memory.management.item.${item.itemId}") {
                    ItemRow(
                        item = item,
                        busy = state.actionInFlight == item.itemId,
                        actions = actions
                    )
                }
            }
        }

        if (state.hasMoreItems) {
            item("memory.management.items.more") {
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = actions.onLoadMore,
                    enabled = state.canLoadMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("memory.management.loadMore")
                ) {
                    Text(if (state.loadingMore) "正在载入更多…" else "加载更多")
                }
            }
        }

        state.editor?.let { editor ->
            item("memory.management.editor") {
                EditorSection(editor = editor, actions = actions)
            }
        }

        item("memory.management.extraction") {
            ExtractionSection(state = state, actions = actions)
        }
        when {
            state.loadingCandidates -> item("memory.management.candidates.loading") {
                AgentStaticLoading(
                    message = "正在载入待审核候选…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            state.candidatesEmpty -> item("memory.management.candidates.empty") {
                AgentSettingsNote("尚无待审核候选。提取结果只会在你接受后写入记忆条目。")
            }

            else -> state.candidates.forEach { candidate ->
                item("memory.management.candidate.${candidate.candidateId}") {
                    CandidateRow(
                        candidate = candidate,
                        busy = state.candidateActionInFlight,
                        actions = actions
                    )
                }
            }
        }

        item("memory.management.semantic") {
            AgentSettingsNote("检索能力：${state.semanticLabel ?: "未知"}。语义检索能力由本机实际配置决定，界面不会伪造向量结果。")
            AgentSettingsNote(
                "删除是可恢复的墓碑：条目会立即从检索中消失，但仍保留在该空间内；开启「显示已删除」后可以恢复。" +
                    "恢复只恢复本机记录，不承诺收回已经外发或已进入任务副本的内容。"
            )
        }
    }
}

@Composable
private fun SpaceRow(
    space: MemorySpaceRowUi,
    selected: Boolean,
    actions: MemoryManagementActions
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { actions.onSelectSpace(space.spaceId) }
                )
                .heightIn(min = 56.dp)
                .testTag("memory.management.space.row.${space.spaceId}")
                .semantics {
                    contentDescription = "记忆空间 ${space.displayName}，${space.itemsLabel}" +
                        if (selected) "，已选择" else ""
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = space.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        AgentTag(text = "已选择")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildSpaceMeta(space),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (space.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = space.description,
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
private fun SearchRow(state: MemoryManagementState, actions: MemoryManagementActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        McaTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier
                .weight(1f)
                .testTag("memory.management.search.field"),
            label = { Text("搜索标题或内容") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            enabled = !state.busy
        )
        McaButton(
            style = McaButtonStyle.Text,
            shape = ButtonDefaults.textShape,
            onClick = actions.onSearch,
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("memory.management.search.submit")
        ) {
            Text("搜索")
        }
    }
}

@Composable
private fun IncludeRevokedRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptics = rememberMcaHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { value ->
                    haptics.toggle(value)
                    onCheckedChange(value)
                }
            )
            .heightIn(min = 48.dp)
            .testTag("memory.management.includeRevoked")
            .semantics { contentDescription = "显示已删除条目" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "显示已删除",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        McaSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ItemRow(
    item: MemoryItemRowUi,
    busy: Boolean,
    actions: MemoryManagementActions
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics {
                contentDescription = "记忆条目 ${item.title}，版本 ${item.revision}" +
                    if (item.revoked) "，已删除" else ""
            }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.excerpt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AgentTag(text = item.sourceLabel)
            AgentTag(text = "版本 ${item.revision}")
            AgentTag(text = item.updatedLabel)
            if (item.revoked) AgentTag(text = "已删除")
            if (!item.hasSourceRef) AgentTag(text = "无来源引用")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.revoked) {
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = { actions.onRestoreItem(item.itemId) },
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("memory.management.item.restore.${item.itemId}")
                ) {
                    Text(if (busy) "正在恢复…" else "恢复")
                }
            } else {
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = { actions.onEditItem(item.itemId) },
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("memory.management.item.edit.${item.itemId}")
                ) {
                    Text("编辑")
                }
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = { actions.onDeleteItem(item.itemId) },
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("memory.management.item.delete.${item.itemId}")
                ) {
                    Text(if (busy) "正在删除…" else "删除")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EditorSection(editor: MemoryEditorUi, actions: MemoryManagementActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        AgentSectionHeader(title = "编辑条目", trailing = "版本 ${editor.expectedRevision}")
        if (editor.loading) {
            AgentStaticLoading(
                message = "正在读取完整内容…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            return@Column
        }
        McaTextField(
            value = editor.title,
            onValueChange = actions.onEditorTitleChange,
            modifier = Modifier.fillMaxWidth().testTag("memory.management.editor.title"),
            label = { Text("标题") },
            singleLine = true,
            enabled = !editor.saving
        )
        Spacer(Modifier.height(8.dp))
        McaTextField(
            value = editor.text,
            onValueChange = actions.onEditorTextChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("memory.management.editor.text"),
            label = { Text("内容") },
            minLines = 4,
            enabled = !editor.saving
        )
        editor.error?.let { error ->
            AgentInlineBanner(
                message = "${error.message}（${error.code}）",
                isError = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentPrimaryButton(
                text = "保存",
                onClick = actions.onSaveEditor,
                enabled = editor.text.isNotBlank() && !editor.saving,
                busy = editor.saving,
                modifier = Modifier.testTag("memory.management.editor.save")
            )
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = actions.onCloseEditor,
                enabled = !editor.saving,
                modifier = Modifier.heightIn(min = 48.dp).testTag("memory.management.editor.cancel")
            ) {
                Text("取消")
            }
        }
        AgentSettingsNote("保存以打开编辑器时读到的版本为准；若条目已被修改，会提示版本冲突，需要重新打开后再保存。")
    }
}

@Composable
private fun ExtractionSection(state: MemoryManagementState, actions: MemoryManagementActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        AgentSectionHeader(title = "从文本提取记忆", trailing = "候选需人工接受")
        McaTextField(
            value = state.extractionInput,
            onValueChange = actions.onExtractionInputChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("memory.management.extraction.input"),
            label = { Text("粘贴会话或任务文本") },
            minLines = 3,
            enabled = !state.busy
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentPrimaryButton(
                text = "提取候选",
                onClick = actions.onSubmitExtraction,
                enabled = state.extractionInput.isNotBlank() && !state.busy,
                busy = state.submittingExtraction,
                modifier = Modifier.testTag("memory.management.extraction.submit")
            )
            state.candidatesSubmittedLabel?.let { label ->
                Text(
                    text = "候选提交于 $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
        state.candidatesError?.let { error ->
            ErrorBanner(
                error = error,
                onDismiss = actions.onDismissCandidatesError,
                testTag = "memory.management.candidates.error.text"
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MemoryCandidateRowUi,
    busy: Boolean,
    actions: MemoryManagementActions
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = candidate.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics {
                contentDescription = "待审核候选 ${candidate.title}"
            }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = candidate.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentTag(text = "置信 ${candidate.confidenceLabel}")
            AgentTag(text = candidate.originLabel)
            candidate.duplicateLabel?.let { AgentTag(text = it) }
            candidate.errorLabel?.let { AgentTag(text = "未接受：$it") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = { actions.onAcceptCandidate(candidate.candidateId) },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("memory.management.candidate.accept.${candidate.candidateId}")
            ) {
                Text("接受")
            }
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = { actions.onRejectCandidate(candidate.candidateId) },
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("memory.management.candidate.reject.${candidate.candidateId}")
            ) {
                Text("忽略")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ErrorBanner(
    error: MemoryManagementErrorUi,
    onDismiss: () -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        AgentInlineBanner(
            message = error.message,
            isError = true,
            onDismiss = onDismiss
        )
        Text(
            text = error.code,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp).testTag(testTag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildSpaceMeta(space: MemorySpaceRowUi): String = buildString {
    append(space.itemsLabel)
    space.egressLabel?.let { append(" · ").append(it) }
    space.updatedLabel?.let { append(" · 更新 ").append(it) }
}

internal const val TEST_TAG_SCREEN: String = "memory.management"

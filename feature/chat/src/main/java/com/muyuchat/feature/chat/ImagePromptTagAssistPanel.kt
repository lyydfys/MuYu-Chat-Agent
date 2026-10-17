package com.muyuchat.feature.chat

import android.os.SystemClock
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun ImagePromptTagAssistPanel(
    value: TextFieldValue,
    focused: Boolean,
    onEdit: (TextFieldValue) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    undoEnabled: Boolean = false,
    redoEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val session = LocalImagePromptTagAutocompleteSession.current
    var dismissal by remember {
        mutableStateOf(ImagePromptTagPanelDismissState(value.text, focused, dismissed = false))
    }
    val observedDismissal = dismissal.observe(value.text, focused)
    if (observedDismissal != dismissal) SideEffect { dismissal = observedDismissal }
    if (!focused || observedDismissal.dismissed) return

    val active = if (value.selection.collapsed) {
        findImagePromptActiveToken(value.text, value.selection.start)
    } else {
        null
    }
    val activeQueryContainsHan = active?.query?.containsImagePromptHanScript() == true
    val hasSuggestionSource = session.hasTagDictionary || session.hasTextualInversionCompletions
    val needsDictionaryEntry = !hasSuggestionSource || !session.enabled
    val showToolbarWithoutActiveTag = active == null && !needsDictionaryEntry
    if (active == null && !needsDictionaryEntry && !focused) return

    var suggestions by remember { mutableStateOf<List<ImagePromptTagSuggestion>>(emptyList()) }
    var queryLoading by remember { mutableStateOf(false) }
    var requestSerial by remember { mutableLongStateOf(0L) }

    LaunchedEffect(
        active?.query,
        focused,
        observedDismissal.dismissed,
        session.enabled,
        session.indexRevision
    ) {
        val request = ++requestSerial
        suggestions = emptyList()
        queryLoading = false
        val query = active?.query ?: return@LaunchedEffect
        if (!session.suggestionsAvailable) return@LaunchedEffect
        queryLoading = true
        delay(QUERY_DEBOUNCE_MILLIS)
        val result = session.suggest(query, PANEL_SUGGESTION_LIMIT)
        if (request == requestSerial) {
            suggestions = result.take(PANEL_SUGGESTION_LIMIT)
            queryLoading = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ASSIST_CONTENT_MAX_HEIGHT_DP)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    showToolbarWithoutActiveTag -> Unit
                    !session.initialized || session.busy && !hasSuggestionSource -> {
                        AssistMessageRow("正在加载标签词典…")
                    }

                    !session.enabled -> {
                        AssistManagerEntry(
                            title = "标签联想已停用",
                            actionLabel = "设置",
                            onClick = session::openManager
                        )
                    }

                    activeQueryContainsHan && !session.hasTagDictionary -> {
                        AssistManagerEntry(
                            title = "中文搜索需要同时导入主标签词典和翻译词典",
                            actionLabel = "管理词典",
                            onClick = session::openManager
                        )
                    }

                    activeQueryContainsHan && !session.hasTranslationDictionary -> {
                        AssistManagerEntry(
                            title = "中文搜索需要翻译词典，点此导入",
                            actionLabel = "导入翻译词典",
                            onClick = session::openManager
                        )
                    }

                    !hasSuggestionSource -> {
                        AssistManagerEntry(
                            title = "导入标签词典后显示联想",
                            actionLabel = "导入词典",
                            onClick = session::openManager
                        )
                    }

                    queryLoading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        AssistMessageRow("正在查找标签…")
                    }

                    suggestions.isEmpty() -> AssistMessageRow("没有匹配的标签")
                    else -> suggestions.forEachIndexed { index, suggestion ->
                        ImagePromptTagSuggestionRow(
                            suggestion = suggestion,
                            onClick = {
                                val edit = applyImagePromptTagSuggestion(
                                    value.text,
                                    value.selection.start,
                                    suggestion
                                ) ?: return@ImagePromptTagSuggestionRow
                                if (!session.activateSuggestion(suggestion)) {
                                    return@ImagePromptTagSuggestionRow
                                }
                                onEdit(TextFieldValue(edit.text, TextRange(edit.cursor)))
                            }
                        )
                        if (index != suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }

            HorizontalDivider()
            ImagePromptTagToolRow(
                tagActionsEnabled = active != null,
                onIncrease = {
                    adjustImagePromptActiveTagWeight(
                        value.text,
                        value.selection.start,
                        0.1
                    )?.let { onEdit(TextFieldValue(it.text, TextRange(it.cursor))) }
                },
                onDecrease = {
                    adjustImagePromptActiveTagWeight(
                        value.text,
                        value.selection.start,
                        -0.1
                    )?.let { onEdit(TextFieldValue(it.text, TextRange(it.cursor))) }
                },
                onClear = {
                    clearImagePromptActiveTag(value.text, value.selection.start)?.let {
                        onEdit(TextFieldValue(it.text, TextRange(it.cursor)))
                    }
                },
                onDismiss = { dismissal = dismissal.dismiss() },
                onUndo = onUndo,
                onRedo = onRedo,
                undoEnabled = undoEnabled,
                redoEnabled = redoEnabled,
            )
        }
    }
}

@Composable
private fun AssistManagerEntry(title: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET_DP)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AssistMessageRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET_DP)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImagePromptTagSuggestionRow(
    suggestion: ImagePromptTagSuggestion,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (suggestion.textualInversionId != null) {
                    suggestion.replacementTag
                } else {
                    suggestion.replacementTag.replace('_', ' ')
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            suggestion.translation?.takeIf(String::isNotBlank)?.let { translation ->
                Text(
                    translation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (suggestion.textualInversionId == null) {
                Text(
                    formatImagePromptTagPopularity(suggestion.postCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (suggestion.textualInversionId != null) {
                    if (suggestion.textualInversionSelected) "已选 embedding" else "embedding"
                } else {
                    imagePromptTagMatchKindLabel(suggestion.matchKind)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ImagePromptTagToolRow(
    tagActionsEnabled: Boolean,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    undoEnabled: Boolean,
    redoEnabled: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PromptTagToolButton(
            Icons.Default.Add,
            "标签权重增加 0.1",
            onIncrease,
            enabled = tagActionsEnabled
        )
        PromptTagToolButton(
            Icons.Default.Remove,
            "标签权重减少 0.1",
            onDecrease,
            enabled = tagActionsEnabled
        )
        PromptTagToolButton(
            Icons.Default.Clear,
            "清除当前标签",
            onClear,
            enabled = tagActionsEnabled,
            destructive = true
        )
        PromptTagToolButton(
            Icons.AutoMirrored.Filled.Undo,
            "撤销提示词编辑",
            onUndo,
            enabled = undoEnabled,
        )
        PromptTagToolButton(
            Icons.AutoMirrored.Filled.Redo,
            "重做提示词编辑",
            onRedo,
            enabled = redoEnabled,
        )
        PromptTagToolButton(Icons.Default.Close, "关闭本次标签建议", onDismiss)
    }
}

@Composable
private fun PromptTagToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MIN_TOUCH_TARGET_DP)
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (destructive && enabled) {
                MaterialTheme.colorScheme.error
            } else {
                LocalContentColor.current
            }
        )
    }
}

private fun String.containsImagePromptHanScript(): Boolean = codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

private val ASSIST_CONTENT_MAX_HEIGHT_DP = 240.dp
private const val QUERY_DEBOUNCE_MILLIS: Long = 90L

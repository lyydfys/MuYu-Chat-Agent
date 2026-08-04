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

internal val LocalImagePromptTagAutocompleteSession =
    staticCompositionLocalOf<ImagePromptTagAutocompleteUiSession> {
        error("ImagePromptTagAutocompleteProvider is missing.")
    }

internal sealed interface ImagePromptTagUiNotice {
    val text: String

    data class Success(override val text: String) : ImagePromptTagUiNotice
    data class Error(override val text: String) : ImagePromptTagUiNotice
}

private const val IMAGE_PROMPT_EDIT_HISTORY_LIMIT = 100
private const val IMAGE_PROMPT_EDIT_HISTORY_COALESCE_MILLIS = 600L

/** Independent bounded undo/redo state for one positive or negative prompt field. */
@Stable
internal class ImagePromptEditHistory {
    private var undoStack by mutableStateOf<List<TextFieldValue>>(emptyList())
    private var redoStack by mutableStateOf<List<TextFieldValue>>(emptyList())
    private var lastContinuousEditAtMillis = 0L

    val undoEnabled: Boolean
        get() = undoStack.isNotEmpty()

    val redoEnabled: Boolean
        get() = redoStack.isNotEmpty()

    fun recordContinuous(previous: TextFieldValue) {
        val now = SystemClock.uptimeMillis()
        val coalesced = undoStack.isNotEmpty() &&
            now - lastContinuousEditAtMillis < IMAGE_PROMPT_EDIT_HISTORY_COALESCE_MILLIS
        if (!coalesced) {
            undoStack = (undoStack + previous).takeLast(IMAGE_PROMPT_EDIT_HISTORY_LIMIT)
        }
        redoStack = emptyList()
        lastContinuousEditAtMillis = now
    }

    fun recordDiscrete(previous: TextFieldValue) {
        undoStack = (undoStack + previous).takeLast(IMAGE_PROMPT_EDIT_HISTORY_LIMIT)
        redoStack = emptyList()
        lastContinuousEditAtMillis = 0L
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        val previous = undoStack.lastOrNull() ?: return null
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + current).takeLast(IMAGE_PROMPT_EDIT_HISTORY_LIMIT)
        lastContinuousEditAtMillis = 0L
        return previous
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = redoStack.lastOrNull() ?: return null
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + current).takeLast(IMAGE_PROMPT_EDIT_HISTORY_LIMIT)
        lastContinuousEditAtMillis = 0L
        return next
    }

    /** External restore/import replaces the baseline and must not become undoable typing. */
    fun replace() {
        undoStack = emptyList()
        redoStack = emptyList()
        lastContinuousEditAtMillis = 0L
    }
}

internal data class ImagePromptTextualInversionCompletion(
    val id: String,
    val name: String,
    val trigger: String,
    val selected: Boolean,
)

internal fun imagePromptTextualInversionSuggestions(
    query: String,
    completions: List<ImagePromptTextualInversionCompletion>,
    limit: Int,
): List<ImagePromptTagSuggestion> {
    if (limit <= 0) return emptyList()
    val normalizedQuery = normalizeTextualInversionCompletionTerm(query)
    if (normalizedQuery.isEmpty()) return emptyList()

    return completions
        .distinctBy(ImagePromptTextualInversionCompletion::id)
        .mapIndexedNotNull { ordinal, completion ->
            val normalizedTrigger = normalizeTextualInversionCompletionTerm(completion.trigger)
            val normalizedName = normalizeTextualInversionCompletionTerm(completion.name)
            val triggerIndex = normalizedTrigger.indexOf(normalizedQuery)
            val nameIndex = normalizedName.indexOf(normalizedQuery)
            if (triggerIndex < 0 && nameIndex < 0) return@mapIndexedNotNull null
            val matchedByTrigger = triggerIndex >= 0 &&
                (nameIndex < 0 || triggerIndex <= nameIndex)
            RankedTextualInversionCompletion(
                prefixRank = if (triggerIndex == 0 || nameIndex == 0) 0 else 1,
                selectedRank = if (completion.selected) 0 else 1,
                matchOffset = when {
                    triggerIndex < 0 -> nameIndex
                    nameIndex < 0 -> triggerIndex
                    else -> minOf(triggerIndex, nameIndex)
                },
                normalizedTrigger = normalizedTrigger,
                ordinal = ordinal,
                suggestion = ImagePromptTagSuggestion(
                    replacementTag = completion.trigger,
                    category = 0,
                    postCount = 0L,
                    matchKind = if (matchedByTrigger) {
                        ImagePromptTagMatchKind.TAG_PREFIX
                    } else {
                        ImagePromptTagMatchKind.ALIAS_PREFIX
                    },
                    matchedText = if (matchedByTrigger) completion.trigger else completion.name,
                    translation = completion.name.takeUnless {
                        it.equals(completion.trigger, ignoreCase = true)
                    },
                    textualInversionId = completion.id,
                    textualInversionSelected = completion.selected,
                ),
            )
        }
        .sortedWith(
            compareBy<RankedTextualInversionCompletion>(
                RankedTextualInversionCompletion::prefixRank,
                RankedTextualInversionCompletion::selectedRank,
                RankedTextualInversionCompletion::matchOffset,
                RankedTextualInversionCompletion::normalizedTrigger,
                RankedTextualInversionCompletion::ordinal,
            )
        )
        .take(minOf(limit, TEXTUAL_INVERSION_SUGGESTION_LIMIT))
        .map(RankedTextualInversionCompletion::suggestion)
}

private data class RankedTextualInversionCompletion(
    val prefixRank: Int,
    val selectedRank: Int,
    val matchOffset: Int,
    val normalizedTrigger: String,
    val ordinal: Int,
    val suggestion: ImagePromptTagSuggestion,
)

private fun normalizeTextualInversionCompletionTerm(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(' ', '_')
    .replace('-', '_')

@Stable
internal class ImagePromptTagAutocompleteUiSession(
    private val store: ImagePromptTagAutocompleteStore,
    private val enabledPreference: ImagePromptTagEnabledPreference
) {
    var status by mutableStateOf(emptyImagePromptTagStoreStatus())
        private set
    var enabled by mutableStateOf(true)
        private set
    var initialized by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<ImagePromptTagUiNotice?>(null)
        private set
    var managerVisible by mutableStateOf(false)
        private set
    var indexRevision by mutableLongStateOf(0L)
        private set

    private var autocomplete by mutableStateOf<ImagePromptTagAutocomplete?>(null)
    private var textualInversionCompletions by mutableStateOf(
        emptyList<ImagePromptTextualInversionCompletion>()
    )
    private var activateTextualInversion: (String) -> Boolean = { false }
    private val mutationMutex = Mutex()

    val hasTagDictionary: Boolean
        get() = status.tags is ImagePromptTagDictionaryState.Available

    val hasTranslationDictionary: Boolean
        get() = status.translations is ImagePromptTagDictionaryState.Available

    val hasTextualInversionCompletions: Boolean
        get() = textualInversionCompletions.isNotEmpty()

    val suggestionsAvailable: Boolean
        get() = enabled && (autocomplete != null || hasTextualInversionCompletions)

    suspend fun initialize() = mutationMutex.withLock {
        busy = true
        notice = null
        try {
            enabled = try {
                enabledPreference.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = ImagePromptTagUiNotice.Error(
                    "标签联想设置已恢复为启用状态，可在词典管理中重新选择。"
                )
                true
            }
            applyLoadResult(store.load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = ImagePromptTagUiNotice.Error("标签词典读取失败，请重试。")
        } finally {
            initialized = true
            busy = false
        }
    }

    suspend fun setEnabled(value: Boolean) = mutationMutex.withLock {
        if (value == enabled) return@withLock
        busy = true
        notice = null
        try {
            enabledPreference.write(value)
            enabled = value
            notice = ImagePromptTagUiNotice.Success(
                if (value) "标签联想已启用。" else "标签联想已停用。"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = ImagePromptTagUiNotice.Error("无法保存标签联想设置，请重试。")
        } finally {
            busy = false
        }
    }

    suspend fun importDictionary(kind: ImagePromptTagDictionaryKind, uri: Uri) =
        mutationMutex.withLock {
            busy = true
            notice = null
            try {
                when (val result = store.importFromContentUri(kind, uri)) {
                    is ImagePromptTagStoreChangeResult.Applied -> {
                        val loaded = result.loadResult ?: store.load()
                        applyLoadResult(loaded)
                        notice = ImagePromptTagUiNotice.Success(
                            if (kind == ImagePromptTagDictionaryKind.TAGS) {
                                "主标签词典导入完成。"
                            } else {
                                "翻译词典导入完成。"
                            }
                        )
                    }

                    is ImagePromptTagStoreChangeResult.Rejected -> {
                        status = result.status
                        notice = ImagePromptTagUiNotice.Error(
                            imagePromptTagStoreIssueMessage(result.issue)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = ImagePromptTagUiNotice.Error("词典导入失败，请重试。")
            } finally {
                busy = false
            }
        }

    suspend fun clearDictionary(kind: ImagePromptTagDictionaryKind) = mutationMutex.withLock {
        busy = true
        notice = null
        try {
            when (val result = store.clear(kind)) {
                is ImagePromptTagStoreChangeResult.Applied -> {
                    applyLoadResult(result.loadResult ?: store.load())
                    notice = ImagePromptTagUiNotice.Success(
                        if (kind == ImagePromptTagDictionaryKind.TAGS) {
                            "主标签词典已清除。"
                        } else {
                            "翻译词典已清除。"
                        }
                    )
                }

                is ImagePromptTagStoreChangeResult.Rejected -> {
                    status = result.status
                    notice = ImagePromptTagUiNotice.Error(
                        imagePromptTagStoreIssueMessage(result.issue)
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = ImagePromptTagUiNotice.Error("无法清除词典，请重试。")
        } finally {
            busy = false
        }
    }

    fun updateTextualInversionCompletions(
        completions: List<ImagePromptTextualInversionCompletion>,
        onActivate: (String) -> Boolean,
    ) {
        val snapshot = completions.distinctBy(ImagePromptTextualInversionCompletion::id).toList()
        if (snapshot != textualInversionCompletions) {
            textualInversionCompletions = snapshot
            indexRevision++
        }
        activateTextualInversion = onActivate
    }

    fun activateSuggestion(suggestion: ImagePromptTagSuggestion): Boolean =
        suggestion.textualInversionId?.let(activateTextualInversion) ?: true

    suspend fun suggest(
        query: String,
        limit: Int = PANEL_SUGGESTION_LIMIT,
    ): List<ImagePromptTagSuggestion> {
        require(limit > 0) { "Suggestion limit is out of range." }
        val index = autocomplete
        val completionSnapshot = textualInversionCompletions
        if (!enabled || (index == null && completionSnapshot.isEmpty())) return emptyList()
        return withContext(Dispatchers.Default) {
            val textualInversions = imagePromptTextualInversionSuggestions(
                query = query,
                completions = completionSnapshot,
                limit = limit,
            )
            val remaining = limit - textualInversions.size
            if (remaining <= 0 || index == null) return@withContext textualInversions
            val triggerKeys = textualInversions.mapTo(mutableSetOf()) {
                normalizeTextualInversionCompletionTerm(it.replacementTag)
            }
            textualInversions + index.suggest(query, limit)
                .filterNot { suggestion ->
                    normalizeTextualInversionCompletionTerm(suggestion.replacementTag) in triggerKeys
                }
                .take(remaining)
        }
    }

    fun openManager() {
        managerVisible = true
        notice = null
    }

    fun closeManager() {
        managerVisible = false
        notice = null
    }

    private fun applyLoadResult(result: ImagePromptTagStoreLoadResult) {
        status = result.status
        autocomplete = (result as? ImagePromptTagStoreLoadResult.Ready)?.autocomplete
        indexRevision++
    }
}

@Composable
internal fun ImagePromptTagAutocompleteProvider(
    textualInversionCompletions: List<ImagePromptTextualInversionCompletion> = emptyList(),
    onActivateTextualInversion: (String) -> Boolean = { false },
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember(context) { ImagePromptTagAutocompleteStore(context) }
    val preference = remember(context) { ImagePromptTagEnabledPreference(context) }
    val session = remember(store, preference) {
        ImagePromptTagAutocompleteUiSession(store, preference)
    }
    val scope = rememberCoroutineScope()
    var pendingClear by remember { mutableStateOf<ImagePromptTagDictionaryKind?>(null) }

    val tagPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            session.importDictionary(ImagePromptTagDictionaryKind.TAGS, uri)
        }
    }
    val translationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            session.importDictionary(ImagePromptTagDictionaryKind.TRANSLATIONS, uri)
        }
    }

    LaunchedEffect(session) {
        session.initialize()
    }

    SideEffect {
        session.updateTextualInversionCompletions(
            completions = textualInversionCompletions,
            onActivate = onActivateTextualInversion,
        )
    }

    CompositionLocalProvider(LocalImagePromptTagAutocompleteSession provides session) {
        content()

        if (session.managerVisible && pendingClear == null) {
            ImagePromptTagDictionaryManagerDialog(
                session = session,
                onDismiss = session::closeManager,
                onImportTags = { tagPicker.launch(TAG_DICTIONARY_MIME_TYPES) },
                onImportTranslations = { translationPicker.launch(TAG_DICTIONARY_MIME_TYPES) },
                onClear = { pendingClear = it },
                onEnabledChange = { value -> scope.launch { session.setEnabled(value) } }
            )
        }
        pendingClear?.let { kind ->
            AlertDialog(
                onDismissRequest = { pendingClear = null },
                title = { Text("清除标签词典？") },
                text = {
                    Text(
                        if (kind == ImagePromptTagDictionaryKind.TAGS) {
                            "清除主词典后，正向和负向提示词都将停止显示标签建议。"
                        } else {
                            "仅清除翻译词典；英文标签建议仍可继续使用。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingClear = null
                            scope.launch { session.clearDictionary(kind) }
                        },
                        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
                    ) {
                        Text("清除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingClear = null },
                        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
                    ) { Text("取消") }
                }
            )
        }
    }
}

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
private fun ImagePromptTagDictionaryManagerDialog(
    session: ImagePromptTagAutocompleteUiSession,
    onDismiss: () -> Unit,
    onImportTags: () -> Unit,
    onImportTranslations: () -> Unit,
    onClear: (ImagePromptTagDictionaryKind) -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签联想词典") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = session.enabled,
                            enabled = !session.busy,
                            role = Role.Switch,
                            onValueChange = onEnabledChange
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用标签联想", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "正向与负向提示词共用同一份词典。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = session.enabled,
                        onCheckedChange = null,
                        enabled = !session.busy
                    )
                }

                HorizontalDivider()
                DictionaryStateSection(
                    title = "主标签词典",
                    supportingText = imagePromptTagDictionaryStateText(session.status.tags),
                    configured = session.status.tags !is ImagePromptTagDictionaryState.NotConfigured,
                    busy = session.busy,
                    onImport = onImportTags,
                    onClear = { onClear(ImagePromptTagDictionaryKind.TAGS) }
                )
                HorizontalDivider()
                DictionaryStateSection(
                    title = "翻译词典（可选）",
                    supportingText = imagePromptTagDictionaryStateText(session.status.translations),
                    configured = session.status.translations !is ImagePromptTagDictionaryState.NotConfigured,
                    busy = session.busy,
                    onImport = onImportTranslations,
                    onClear = { onClear(ImagePromptTagDictionaryKind.TRANSLATIONS) }
                )

                if (session.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                session.notice?.let { notice ->
                    Text(
                        notice.text,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice is ImagePromptTagUiNotice.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
            ) { Text("完成") }
        }
    )
}

@Composable
private fun DictionaryStateSection(
    title: String,
    supportingText: String,
    configured: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
            ) { Text(if (configured) "替换 CSV" else "导入 CSV") }
            if (configured) {
                TextButton(
                    onClick = onClear,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP)
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            }
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

internal data class ImagePromptTagPanelDismissState(
    val observedText: String,
    val wasFocused: Boolean,
    val dismissed: Boolean
) {
    fun observe(text: String, focused: Boolean): ImagePromptTagPanelDismissState {
        val shouldRestore = text != observedText || (focused && !wasFocused)
        return ImagePromptTagPanelDismissState(
            observedText = text,
            wasFocused = focused,
            dismissed = if (shouldRestore) false else dismissed
        )
    }

    fun dismiss(): ImagePromptTagPanelDismissState = copy(dismissed = true)
}

internal fun imagePromptTagDictionaryStateText(state: ImagePromptTagDictionaryState): String =
    when (state) {
        ImagePromptTagDictionaryState.NotConfigured -> "未导入"
        is ImagePromptTagDictionaryState.Available ->
            "${state.rowCount} 条 · ${formatImagePromptTagByteCount(state.byteCount)}"
        is ImagePromptTagDictionaryState.Corrupt ->
            "文件不可用：${imagePromptTagStoreIssueMessage(state.issue)}"
        ImagePromptTagDictionaryState.ReadFailed -> "读取失败，请重新导入。"
    }

internal fun imagePromptTagStoreIssueMessage(issue: ImagePromptTagStoreIssue): String = when (issue) {
    ImagePromptTagStoreIssue.UNSUPPORTED_URI -> "请选择系统文件选择器中的 CSV 文件。"
    ImagePromptTagStoreIssue.SOURCE_UNREADABLE -> "无法读取所选文件，请检查访问权限。"
    ImagePromptTagStoreIssue.FILE_TOO_LARGE -> "文件超过大小限制。"
    ImagePromptTagStoreIssue.TOO_MANY_LINES -> "词典行数超过上限。"
    ImagePromptTagStoreIssue.EMPTY_DICTIONARY -> "词典中没有可用记录。"
    ImagePromptTagStoreIssue.MALFORMED_CSV -> "CSV 格式或 UTF-8 编码无效。"
    ImagePromptTagStoreIssue.INDEX_REJECTED -> "词典包含重复标签或索引规模超限。"
    ImagePromptTagStoreIssue.STORAGE_READ_FAILED -> "词典读取失败，请重新导入。"
    ImagePromptTagStoreIssue.TRANSACTION_FAILED -> "词典保存失败，原有词典未被替换。"
}

internal fun formatImagePromptTagByteCount(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024.0))
}

internal fun formatImagePromptTagPopularity(postCount: Long): String = when {
    postCount >= 100_000_000L -> String.format(Locale.ROOT, "%.1f 亿", postCount / 100_000_000.0)
    postCount >= 10_000L -> String.format(Locale.ROOT, "%.1f 万", postCount / 10_000.0)
    postCount >= 1_000L -> String.format(Locale.ROOT, "%.1f k", postCount / 1_000.0)
    else -> postCount.coerceAtLeast(0L).toString()
}

internal fun imagePromptTagMatchKindLabel(kind: ImagePromptTagMatchKind): String = when (kind) {
    ImagePromptTagMatchKind.TAG_PREFIX -> "标签"
    ImagePromptTagMatchKind.ALIAS_PREFIX -> "别名"
    ImagePromptTagMatchKind.TRANSLATION_PREFIX -> "翻译"
    ImagePromptTagMatchKind.FUZZY_TAG -> "模糊标签"
    ImagePromptTagMatchKind.FUZZY_ALIAS -> "模糊别名"
    ImagePromptTagMatchKind.FUZZY_TRANSLATION -> "模糊翻译"
}

private fun emptyImagePromptTagStoreStatus(): ImagePromptTagStoreStatus =
    ImagePromptTagStoreStatus(
        tags = ImagePromptTagDictionaryState.NotConfigured,
        translations = ImagePromptTagDictionaryState.NotConfigured
    )

private fun String.containsImagePromptHanScript(): Boolean = codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

internal class ImagePromptTagEnabledPreference(context: Context) {
    private val root = File(
        context.applicationContext.noBackupFilesDir,
        "image_prompt_tag_autocomplete_ui"
    )
    private val stateFile = File(root, "enabled.state")

    suspend fun read(): Boolean = withContext(Dispatchers.IO) {
        if (!stateFile.isFile) return@withContext true
        when (stateFile.readText(Charsets.UTF_8).trim()) {
            "1" -> true
            "0" -> false
            else -> throw IllegalStateException("Invalid autocomplete preference state.")
        }
    }

    suspend fun write(enabled: Boolean) = withContext(Dispatchers.IO) {
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Unable to create autocomplete preference directory.")
        }
        check(root.isDirectory) { "Autocomplete preference root is invalid." }
        val staged = File(root, ".enabled.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(staged).use { output ->
                output.write(if (enabled) '1'.code else '0'.code)
                output.flush()
                output.fd.sync()
            }
            replaceImagePromptTagSnapshot(staged, stateFile)
        } finally {
            staged.delete()
        }
    }
}

private val MIN_TOUCH_TARGET_DP = 48.dp
private val ASSIST_CONTENT_MAX_HEIGHT_DP = 240.dp
private const val PANEL_SUGGESTION_LIMIT: Int = 6
private const val TEXTUAL_INVERSION_SUGGESTION_LIMIT: Int = 5
private const val QUERY_DEBOUNCE_MILLIS: Long = 90L
private val TAG_DICTIONARY_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "text/plain",
    "application/octet-stream",
    "*/*"
)

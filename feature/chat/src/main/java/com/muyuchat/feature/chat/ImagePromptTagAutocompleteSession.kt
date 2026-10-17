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

private fun emptyImagePromptTagStoreStatus(): ImagePromptTagStoreStatus =
    ImagePromptTagStoreStatus(
        tags = ImagePromptTagDictionaryState.NotConfigured,
        translations = ImagePromptTagDictionaryState.NotConfigured
    )

private val TAG_DICTIONARY_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "text/plain",
    "application/octet-stream",
    "*/*"
)

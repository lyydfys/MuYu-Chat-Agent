package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaTextField

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
























import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter
import com.muyuchat.core.designsystem.McaIconSize

internal const val ASSISTANT_MEMORY_SAVE_FAILED_MESSAGE =
    "保存角色记忆失败，草稿已保留，请重试。"
internal const val ASSISTANT_MEMORY_SAVE_CANCELLED_MESSAGE =
    "保存已取消，草稿已保留，请重试。"

internal data class AssistantMemoryEditorState(
    val editingId: String?,
    val scope: String,
    val content: String,
    val isSaving: Boolean = false,
    val saveError: String? = null
) {
    fun beginSave(): AssistantMemoryEditorState = copy(isSaving = true, saveError = null)

    fun applySaveResult(result: AssistantMemorySaveResult): AssistantMemoryEditorState = when (result) {
        AssistantMemorySaveResult.Saved -> copy(
            editingId = null,
            content = "",
            isSaving = false,
            saveError = null
        )
        is AssistantMemorySaveResult.Failed -> copy(isSaving = false, saveError = result.message)
    }

    fun cancelSave(): AssistantMemoryEditorState = copy(
        isSaving = false,
        saveError = ASSISTANT_MEMORY_SAVE_CANCELLED_MESSAGE
    )
}

internal sealed interface AssistantMemorySaveResult {
    data object Saved : AssistantMemorySaveResult
    data class Failed(val message: String) : AssistantMemorySaveResult
}

internal suspend fun awaitAssistantMemorySave(submit: suspend () -> Boolean): AssistantMemorySaveResult =
    try {
        if (submit()) AssistantMemorySaveResult.Saved else AssistantMemorySaveResult.Failed(
            ASSISTANT_MEMORY_SAVE_FAILED_MESSAGE
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val detail = error.message?.trim()?.takeIf(String::isNotBlank)
        AssistantMemorySaveResult.Failed(
            if (detail == null) ASSISTANT_MEMORY_SAVE_FAILED_MESSAGE
            else "保存角色记忆失败：$detail；草稿已保留，请重试。"
        )
    }

@Composable
internal fun AssistantMemoryManagerDialog(
    assistant: AssistantUiItem,
    memories: List<AssistantMemoryUiItem>,
    onDismiss: () -> Unit,
    onUpsert: (assistantId: String, id: String?, scope: String, content: String) -> Deferred<Boolean>,
    onDelete: (assistantId: String, id: String) -> Unit
) {
    var selectedScope by rememberSaveable(assistant.id) { mutableStateOf("user_profile") }
    var editingId by rememberSaveable(assistant.id) { mutableStateOf<String?>(null) }
    var content by rememberSaveable(assistant.id) { mutableStateOf("") }
    var saveError by rememberSaveable(assistant.id) { mutableStateOf<String?>(null) }
    var isSaving by remember(assistant.id) { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AssistantMemoryUiItem?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val visibleMemories = memories
        .filter {
            it.assistantId == assistant.id &&
                (it.scope == selectedScope || selectedScope == "role_progress" && it.scope == "auto_summary")
        }
        .sortedWith(compareByDescending<AssistantMemoryUiItem> { it.createdAt }.thenBy { it.id })

    fun applyEditorState(state: AssistantMemoryEditorState) {
        editingId = state.editingId
        selectedScope = state.scope
        content = state.content
        saveError = state.saveError
        isSaving = state.isSaving
    }

    fun clearEditor() {
        applyEditorState(
            AssistantMemoryEditorState(
                editingId = null,
                scope = selectedScope,
                content = ""
            )
        )
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f).widthIn(max = 600.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("角色记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(assistant.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    McaIconButton(contentDescription = "关闭记忆管理", onClick = onDismiss, enabled = !isSaving, glyphSize = McaIconSize.L) {
                        Icon(McaIcons.Close, contentDescription = null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McaChip(
                        selected = selectedScope == "user_profile",
                        enabled = !isSaving,
                        onClick = {
                            selectedScope = "user_profile"
                            clearEditor()
                        },
                        label = { Text("用户信息") }
                    )
                    McaChip(
                        selected = selectedScope == "role_progress",
                        enabled = !isSaving,
                        onClick = {
                            selectedScope = "role_progress"
                            clearEditor()
                        },
                        label = { Text("角色进度") }
                    )
                }
                McaTextField(
                    value = content,
                    onValueChange = { content = it.take(4000) },
                    label = {
                        Text(if (selectedScope == "user_profile") "用户信息" else "角色进度")
                    },
                    minLines = 3,
                    maxLines = 6,
                    readOnly = isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                saveError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    McaButton(style = McaButtonStyle.Primary,
                        onClick = {
                            val attempt = AssistantMemoryEditorState(
                                editingId = editingId,
                                scope = selectedScope,
                                content = content
                            ).beginSave()
                            applyEditorState(attempt)
                            coroutineScope.launch {
                                try {
                                    val result = awaitAssistantMemorySave {
                                        onUpsert(
                                            assistant.id,
                                            attempt.editingId,
                                            attempt.scope,
                                            attempt.content.trim()
                                        ).await()
                                    }
                                    applyEditorState(attempt.applySaveResult(result))
                                } catch (cancelled: CancellationException) {
                                    applyEditorState(attempt.cancelSave())
                                    throw cancelled
                                } finally {
                                    if (isSaving) isSaving = false
                                }
                            }
                        },
                        enabled = content.isNotBlank() && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("保存中…")
                        } else {
                            Text(if (editingId == null) "添加记忆" else "保存修改")
                        }
                    }
                    if (editingId != null) {
                        McaButton(style = McaButtonStyle.Text,onClick = ::clearEditor, enabled = !isSaving) { Text("取消编辑") }
                    }
                }
                HorizontalDivider()
                Text(
                    if (selectedScope == "user_profile") "已保存的用户信息" else "已保存的角色进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visibleMemories.isEmpty()) {
                        item {
                            Text("暂无记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(visibleMemories, key = { it.id }) { memory ->
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        memory.source.equals("manual", ignoreCase = true) -> "手动"
                                        memory.source.equals("automatic", ignoreCase = true) -> "自动总结"
                                        else -> "已保存"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                McaIconButton(contentDescription = "编辑记忆", onClick = {
                                    selectedScope = if (memory.scope == "auto_summary") "role_progress" else memory.scope
                                    editingId = memory.id
                                    content = memory.content
                                    saveError = null
                                }, enabled = !isSaving, glyphSize = McaIconSize.L) {
                                    Icon(McaIcons.Edit, contentDescription = null)
                                }
                                McaIconButton(contentDescription = "删除记忆", onClick = { pendingDelete = memory }, enabled = !isSaving, glyphSize = McaIconSize.L, tint = MaterialTheme.colorScheme.error) {
                                    Icon(McaIcons.Delete, contentDescription = null)
                                }
                            }
                            Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除记忆？") },
            text = { Text(memory.content.take(140)) },
            confirmButton = {
                McaButton(style = McaButtonStyle.Text,onClick = {
                    onDelete(assistant.id, memory.id)
                    if (editingId == memory.id) clearEditor()
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

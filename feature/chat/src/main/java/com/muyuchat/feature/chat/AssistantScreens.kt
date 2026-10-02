package com.muyuchat.feature.chat

// The assistant auxiliary screens, moved verbatim out of ChatScreen.kt.
//
// These are the role-management surfaces that are not the main chat screen: the memory manager
// dialog, the context library, the role import page, the avatar, the capability chip, and the four
// small parsing helpers those screens use (cleanParamText, toAssistantFloat, toAssistantInt,
// toAssistantStopWords). Nothing here is new code -- the block was cut from ChatScreen.kt in one
// piece and pasted unchanged.
//
// Ten declarations widened from private to internal because ChatScreen.kt still calls them:
// the five screens above, the four parsing helpers, and QWEN_IMAGE_21_VERIFIED_SIZE_PAIRS.
// Kotlin has no package-private scope, so a top-level private declaration cannot be read from
// another file; those ten words are the only edits in this move.
//
// Note that AssistantRoleScreen, AssistantCurrentCard, AssistantListCard and AssistantEditorPage
// are NOT here. They are declared inside ChatScreen itself, so they are local functions rather
// than top-level declarations and cannot leave without restructuring the enclosing function.
// This file is therefore the assistant domain as far as it can be separated today.
//
// The import list is the one from ChatScreen.kt, copied whole rather than trimmed: unused imports
// are warnings in Kotlin, and copying them keeps the move mechanical instead of making it a
// judgement call about which imports each declaration still needs.

import android.content.ClipData
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
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
internal fun AssistantMemoryManagerDialog(
    assistant: AssistantUiItem,
    memories: List<AssistantMemoryUiItem>,
    onDismiss: () -> Unit,
    onUpsert: (assistantId: String, id: String?, scope: String, content: String) -> Unit,
    onDelete: (assistantId: String, id: String) -> Unit
) {
    var selectedScope by rememberSaveable(assistant.id) { mutableStateOf("user_profile") }
    var editingId by rememberSaveable(assistant.id) { mutableStateOf<String?>(null) }
    var content by rememberSaveable(assistant.id) { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<AssistantMemoryUiItem?>(null) }
    val visibleMemories = memories
        .filter {
            it.assistantId == assistant.id &&
                (it.scope == selectedScope || selectedScope == "role_progress" && it.scope == "auto_summary")
        }
        .sortedWith(compareByDescending<AssistantMemoryUiItem> { it.createdAt }.thenBy { it.id })

    fun clearEditor() {
        editingId = null
        content = ""
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭记忆管理")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedScope == "user_profile",
                        onClick = {
                            selectedScope = "user_profile"
                            clearEditor()
                        },
                        label = { Text("用户信息") }
                    )
                    FilterChip(
                        selected = selectedScope == "role_progress",
                        onClick = {
                            selectedScope = "role_progress"
                            clearEditor()
                        },
                        label = { Text("角色进度") }
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(4000) },
                    label = {
                        Text(if (selectedScope == "user_profile") "用户信息" else "角色进度")
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            onUpsert(assistant.id, editingId, selectedScope, content.trim())
                            clearEditor()
                        },
                        enabled = content.isNotBlank()
                    ) {
                        Text(if (editingId == null) "添加记忆" else "保存修改")
                    }
                    if (editingId != null) {
                        TextButton(onClick = ::clearEditor) { Text("取消编辑") }
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
                                IconButton(onClick = {
                                    selectedScope = if (memory.scope == "auto_summary") "role_progress" else memory.scope
                                    editingId = memory.id
                                    content = memory.content
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑记忆")
                                }
                                IconButton(onClick = { pendingDelete = memory }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除记忆", tint = MaterialTheme.colorScheme.error)
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
                TextButton(onClick = {
                    onDelete(assistant.id, memory.id)
                    if (editingId == memory.id) clearEditor()
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
internal fun ContextLibraryPage(
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    statusMessage: String?,
    hasActiveConversation: Boolean,
    onBack: () -> Unit,
    onImportWorldBook: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var knowledgeBaseName by rememberSaveable { mutableStateOf("") }
    var worldBookScopeMenuOpen by rememberSaveable { mutableStateOf(false) }
    var showUsageGuide by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("上下文资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "世界书与本地知识库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showUsageGuide = true }) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("使用说明")
            }
        }
        statusMessage
            ?.takeIf { it.contains("世界书") || it.contains("知识库") }
            ?.let { message ->
                Text(
                    text = message,
                    color = if (message.contains("失败") || message.contains("未找到")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
                )
            }
        Spacer(modifier = Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("世界书", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { worldBookScopeMenuOpen = true }) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导入")
                        }
                        DropdownMenu(
                            expanded = worldBookScopeMenuOpen,
                            onDismissRequest = { worldBookScopeMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导入到全局") },
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.GLOBAL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入到当前角色") },
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.ASSISTANT)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入到当前对话") },
                                enabled = hasActiveConversation,
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.CHAT)
                                }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "支持 Tavern World Info JSON 常用字段；角色卡 v2/v3 的内嵌世界书会随角色卡自动导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }
            if (worldBooks.isEmpty()) {
                item {
                    Text(
                        "尚未导入世界书",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(worldBooks, key = { it.id }) { book ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(book.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(book.scopeLabel).append(" · ").append(book.entryCount).append(" 条")
                                    if (book.constantEntryCount > 0) {
                                        append(" · 常驻 ").append(book.constantEntryCount)
                                    }
                                    if (book.keywordEntryCount > 0) {
                                        append(" · 关键词触发 ").append(book.keywordEntryCount)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (book.constantEntryCount > 0) {
                                    "常驻条目每轮注入；其余条目需要消息命中关键词。"
                                } else {
                                    "本轮消息命中关键词后才会注入。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDeleteWorldBook(book.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除世界书")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
            item {
                Spacer(modifier = Modifier.height(14.dp))
                Text("知识库", style = MaterialTheme.typography.titleMedium)
                Text(
                    "导入后会自动选中当前会话。支持 TXT、Markdown、JSON、XML、CSV 和代码文本，每个文件不超过 1 MiB；提问时带上资料里的关键词，更容易检索到相关片段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = knowledgeBaseName,
                        onValueChange = { knowledgeBaseName = it.take(96) },
                        label = { Text("知识库名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val name = knowledgeBaseName.trim()
                            if (name.isNotEmpty()) {
                                onCreateKnowledgeBase(name)
                                knowledgeBaseName = ""
                            }
                        },
                        enabled = knowledgeBaseName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "创建知识库")
                    }
                }
            }
            if (knowledgeBases.isEmpty()) {
                item {
                    Text(
                        "尚未创建知识库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(knowledgeBases, key = { it.id }) { knowledgeBase ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = knowledgeBase.selected,
                            onCheckedChange = { selected ->
                                onSetKnowledgeBaseSelected(knowledgeBase.id, selected)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                knowledgeBase.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                buildList {
                                    knowledgeBase.description.takeIf { it.isNotBlank() }?.let(::add)
                                    add("${knowledgeBase.documentCount} 个文件")
                                    add(if (knowledgeBase.importing) "导入中" else knowledgeBase.indexStateLabel)
                                }.joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onImportKnowledgeDocument(knowledgeBase.id) },
                            enabled = !knowledgeBase.importing
                        ) {
                            if (knowledgeBase.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Folder, contentDescription = "导入知识库文档")
                            }
                        }
                        IconButton(onClick = { onDeleteKnowledgeBase(knowledgeBase.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除知识库")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
    }
    if (showUsageGuide) {
        AlertDialog(
            onDismissRequest = { showUsageGuide = false },
            title = { Text("世界书和知识库怎么用") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("世界书：把设定写成条目。常驻条目每轮都会加入上下文；普通条目需要聊天消息命中关键词才会加入。导入时可选全局、当前角色或当前对话。")
                    Text("Tavern 兼容：读取 World Info JSON 和角色卡 v2/v3 的 character_book；支持常驻、关键词、正则、二级关键词、优先级和大小写选项。不支持的正则会保留并提示，宏和脚本不执行。")
                    Text("知识库：先创建知识库，再点文件夹图标导入文本文件。导入后默认选中；取消勾选后该库不会参与检索。它按关键词找相关片段，不会把整份文档常驻塞进提示词，也不会训练模型。")
                    Text("确认生效：提问时使用资料里的专有名词或关键词；回答下方的上下文详情会显示世界书/知识库命中与跳过情况。")
                    Text("目前不直接导入 PDF、Word 或压缩包；请先另存为 TXT/Markdown。单个文件上限 1 MiB。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showUsageGuide = false }) { Text("知道了") }
            }
        )
    }
}

internal val QWEN_IMAGE_21_VERIFIED_SIZE_PAIRS: Set<Pair<Int, Int>> = setOf(
    512 to 512, 576 to 448, 448 to 576, 640 to 416, 416 to 640,
    672 to 384, 384 to 672,
    384 to 384, 448 to 320, 320 to 448, 480 to 320, 320 to 480,
    512 to 288, 288 to 512,
    320 to 320, 384 to 288, 288 to 384, 384 to 256, 256 to 384,
    416 to 256, 256 to 416
)


@Composable
internal fun AssistantImportPage(
    onBack: () -> Unit,
    onImportFile: () -> Unit,
    onImport: (String) -> Unit,
    statusMessage: String?,
    modifier: Modifier = Modifier
) {
    var rawJson by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("导入角色卡", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("粘贴 MCA 角色卡 JSON", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        statusMessage
            ?.takeIf { it.contains("角色卡") && it.contains("导入") }
            ?.let { message ->
                Text(
                    message,
                    color = if (message.contains("失败") || message.contains("未完成")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp)
        ) {
            OutlinedTextField(
                value = rawJson,
                onValueChange = { rawJson = it },
                label = { Text("角色卡 JSON") },
                minLines = 8,
                modifier = Modifier.fillMaxSize().padding(12.dp)
            )
        }
        Button(
            onClick = onImportFile,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("选择 PNG / JSON 文件")
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("取消")
                }
                Button(
                    onClick = { onImport(rawJson) },
                    enabled = rawJson.isNotBlank(),
                    modifier = Modifier.weight(1.45f).height(48.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("导入")
                }
            }
        }
    }
}

@Composable
internal fun AssistantAvatar(name: String, avatar: String, selected: Boolean) {
    val label = avatar.trim().ifBlank { name.trim() }.firstOrNull()?.toString() ?: "M"
    Surface(
        modifier = Modifier.size(42.dp),
        color = if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun AssistantCapabilityChip(label: String, enabled: Boolean) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        color = when {
            enabled && darkTheme -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            enabled -> Color(0xFFE6F4EA)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            "$label${if (enabled) "开" else "关"}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                enabled && darkTheme -> MaterialTheme.colorScheme.onPrimaryContainer
                enabled -> Color(0xFF137333)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

internal fun Float.cleanParamText(): String =
    if (this % 1f == 0f) toInt().toString() else "%.2f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')

internal fun String.toAssistantFloat(default: Float, min: Float, max: Float): Float =
    trim().toFloatOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

internal fun String.toAssistantInt(default: Int, min: Int, max: Int): Int =
    trim().toIntOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

internal fun String.toAssistantStopWords(default: List<String>): List<String> =
    runCatching {
        val array = JSONArray(trim())
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct().take(32).filter { it.length <= 128 }
    }.getOrDefault(default)
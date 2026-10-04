package com.muyuchat.feature.chat

import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant role page: the role screen, its cards and the assistant editor page.
 *
 * Cut verbatim out of ChatScreen.kt; layout, spacing, colours, iconography, wording and
 * interaction are unchanged. `AssistantRoleScreen` widens from private to internal because
 * ChatScreen.kt still calls it -- Kotlin has no package-private scope. The three declarations
 * only it calls stay private here, and MAX_ASSISTANT_SYSTEM_PROMPT_CHARS comes along because
 * both of its readers are in this file.
 */

private const val MAX_ASSISTANT_SYSTEM_PROMPT_CHARS = 12_000

@Composable
internal fun AssistantRoleScreen(
    assistants: List<AssistantUiItem>,
    assistantMemories: List<AssistantMemoryUiItem>,
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    statusMessage: String?,
    selectedAssistantId: String,
    hasActiveConversation: Boolean,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onSaveAssistant: (AssistantEditorDraft) -> Unit,
    onUpsertAssistantMemory: (assistantId: String, id: String?, scope: String, content: String) -> Unit,
    onDeleteAssistantMemory: (assistantId: String, id: String) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onImportAssistantCard: (String) -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AssistantUiItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var managingContext by remember { mutableStateOf(false) }
    val selected = assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()
    val editingAssistant = editing
    fun closeAssistantSubPage() {
        creating = false
        editing = null
        importing = false
        managingContext = false
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .navigationBarsPadding()
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("助手与角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "当前助手、角色卡、提示词与能力",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { creating = true }) {
                    Text("新建")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AssistantCurrentCard(
                assistant = selected,
                selectedModelName = selectedModelName,
                onEdit = { selected?.let { editing = it } },
                onExport = {
                    selected?.let { item ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                            Toast.makeText(context, "已复制角色卡 JSON", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { managingContext = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("世界书与知识库")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "助手列表",
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 14.dp)
            ) {
                items(assistants) { assistant ->
                    AssistantListCard(
                        assistant = assistant,
                        onSelect = { onSelectAssistant(assistant.id) },
                        onEdit = { editing = assistant },
                        onDelete = { onDeleteAssistant(assistant.id) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importing = true }, modifier = Modifier.weight(1f)) {
                    Text("导入角色卡")
                }
                Button(
                    onClick = {
                        selected?.let { item ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                                Toast.makeText(context, "已复制当前助手角色卡", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出当前助手")
                }
            }
        }

        SmoothRightToLeftPage(
            visible = creating || editingAssistant != null || importing || managingContext,
            onDismiss = ::closeAssistantSubPage
        ) { pageModifier, closePage ->
            when {
                managingContext -> ContextLibraryPage(
                    worldBooks = worldBooks,
                    knowledgeBases = knowledgeBases,
                    statusMessage = statusMessage,
                    hasActiveConversation = hasActiveConversation,
                    onBack = closePage,
                    onImportWorldBook = onImportWorldBookFile,
                    onDeleteWorldBook = onDeleteWorldBook,
                    onCreateKnowledgeBase = onCreateKnowledgeBase,
                    onImportKnowledgeDocument = onImportKnowledgeDocument,
                    onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
                    onDeleteKnowledgeBase = onDeleteKnowledgeBase,
                    modifier = pageModifier
                )
                creating -> AssistantEditorPage(
                    assistant = null,
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    onDelete = onDeleteAssistant,
                    onSave = {
                        onSaveAssistant(it)
                        closePage()
                    },
                    modifier = pageModifier
                )
                editingAssistant != null -> AssistantEditorPage(
                    assistant = editingAssistant,
                    memories = assistantMemories.filter { it.assistantId == editingAssistant.id },
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    onDelete = { id ->
                        onDeleteAssistant(id)
                        closePage()
                    },
                    onSave = {
                        onSaveAssistant(it)
                        closePage()
                    },
                    onUpsertMemory = onUpsertAssistantMemory,
                    onDeleteMemory = onDeleteAssistantMemory,
                    modifier = pageModifier
                )
                importing -> AssistantImportPage(
                    onBack = closePage,
                    onImportFile = onImportAssistantCardFile,
                    onImport = onImportAssistantCard,
                    statusMessage = statusMessage,
                    modifier = pageModifier
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun AssistantCurrentCard(
    assistant: AssistantUiItem?,
    selectedModelName: String?,
    onEdit: () -> Unit,
    onExport: () -> Unit
) {
    val assistantTag = assistant?.tag.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant?.name ?: "MCA", assistant?.avatar.orEmpty(), selected = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant?.name ?: "默认助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (assistantTag.isNotBlank()) {
                        Text(
                            assistantTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        assistant?.modelSummary?.takeIf { it.isNotBlank() } ?: selectedModelName ?: "跟随当前模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantCapabilityChip("记忆", assistant?.memoryEnabled == true)
                AssistantCapabilityChip("联网检索", assistant?.webSearchEnabled == true)
                AssistantCapabilityChip("文件上下文", assistant?.fileContextEnabled != false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onExport) { Text("导出") }
            }
        }
    }
}

@Composable
private fun AssistantListCard(
    assistant: AssistantUiItem,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (assistant.selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFEAF1FF)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF8F9FA)
        },
        shape = RoundedCornerShape(20.dp),
        border = if (assistant.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant.name, assistant.avatar, selected = assistant.selected)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        assistant.tag.ifBlank { assistant.systemPrompt.ifBlank { "未设置提示词" } },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (assistant.selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSelect, enabled = !assistant.selected) { Text(if (assistant.selected) "使用中" else "切换") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = assistant.id != "default") {
                    Text("删除", color = if (assistant.id == "default") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AssistantEditorPage(
    assistant: AssistantUiItem?,
    memories: List<AssistantMemoryUiItem> = emptyList(),
    onBack: () -> Unit,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onDelete: (String) -> Unit,
    onSave: (AssistantEditorDraft) -> Unit,
    onUpsertMemory: (assistantId: String, id: String?, scope: String, content: String) -> Unit = { _, _, _, _ -> },
    onDeleteMemory: (assistantId: String, id: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var name by remember(assistant?.id) { mutableStateOf(assistant?.name ?: "") }
    var avatar by remember(assistant?.id) { mutableStateOf(assistant?.avatar ?: "") }
    var tag by remember(assistant?.id) { mutableStateOf(assistant?.tag ?: "") }
    var prompt by remember(assistant?.id) {
        mutableStateOf((assistant?.systemPrompt ?: "").take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS))
    }
    var roleplayPresetId by remember(assistant?.id) {
        mutableStateOf(assistant?.let { RoleplayStylePresets.selectedPresetId(it.systemPrompt) })
    }
    var defaultModelMode by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelMode ?: "follow_current") }
    var defaultModelId by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelId) }
    var temperatureText by remember(assistant?.id) { mutableStateOf((assistant?.temperature ?: GenerationParams().temperature).cleanParamText()) }
    var topPText by remember(assistant?.id) { mutableStateOf((assistant?.topP ?: GenerationParams().topP).cleanParamText()) }
    var topKText by remember(assistant?.id) { mutableStateOf((assistant?.topK ?: GenerationParams().topK).toString()) }
    var minPText by remember(assistant?.id) { mutableStateOf((assistant?.minP ?: GenerationParams().minP).cleanParamText()) }
    var repeatPenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.repeatPenalty ?: GenerationParams().repeatPenalty).cleanParamText()) }
    var presencePenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.presencePenalty ?: GenerationParams().presencePenalty).cleanParamText()) }
    var frequencyPenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.frequencyPenalty ?: GenerationParams().frequencyPenalty).cleanParamText()) }
    var nPredictText by remember(assistant?.id) { mutableStateOf((assistant?.nPredict ?: GenerationParams().nPredict).toString()) }
    var stopWordsText by remember(assistant?.id) { mutableStateOf(JSONArray(assistant?.stopWords.orEmpty()).toString()) }
    var reasoningMode by remember(assistant?.id) { mutableStateOf(assistant?.reasoningMode ?: GenerationParams().reasoningMode) }
    var memoryEnabled by remember(assistant?.id) { mutableStateOf(assistant?.memoryEnabled ?: false) }
    var memorySummaryIntervalText by remember(assistant?.id) {
        mutableStateOf((assistant?.memorySummaryInterval ?: 12).coerceIn(2, 100).toString())
    }
    var showMemoryManager by remember(assistant?.id) { mutableStateOf(false) }
    var webSearchEnabled by remember(assistant?.id) { mutableStateOf(assistant?.webSearchEnabled ?: false) }
    var fileContextEnabled by remember(assistant?.id) { mutableStateOf(assistant?.fileContextEnabled ?: true) }
    var roleplayPresetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var presetImportMessage by rememberSaveable(assistant?.id) { mutableStateOf<String?>(null) }
    var presetImporting by rememberSaveable(assistant?.id) { mutableStateOf(false) }
    var advancedSamplingExpanded by rememberSaveable(assistant?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val presetImportScope = rememberCoroutineScope()
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            presetImporting = true
            presetImportMessage = "正在读取酒馆预设…"
            presetImportScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val json = requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "无法读取所选文件。"
                        }.use { stream -> readSillyTavernPresetJson(stream) }
                        SillyTavernPresetCodec.parse(
                            rawJson = json,
                            fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "Tavern 预设"
                        )
                    }
                }
                result.onSuccess { preset ->
                    preset.temperature?.let { temperatureText = it.cleanParamText() }
                    preset.topP?.let { topPText = it.cleanParamText() }
                    preset.topK?.let { topKText = it.toString() }
                    preset.minP?.let { minPText = it.cleanParamText() }
                    preset.repeatPenalty?.let { repeatPenaltyText = it.cleanParamText() }
                    preset.presencePenalty?.let { presencePenaltyText = it.cleanParamText() }
                    preset.frequencyPenalty?.let { frequencyPenaltyText = it.cleanParamText() }
                    preset.maxTokens?.let { nPredictText = it.coerceIn(16, 65_536).toString() }
                    preset.stopWords?.let { stopWordsText = JSONArray(it).toString() }
                    preset.promptAppendix?.let { appendix ->
                        prompt = SillyTavernPresetCodec.mergePromptAppendix(prompt, appendix)
                    }
                    advancedSamplingExpanded = true
                    presetImportMessage = buildString {
                        append("已导入酒馆采样参数：").append(preset.name)
                        if (preset.promptAppendix != null) {
                            append("；已合并纯文本提示内容")
                        } else if (preset.ignoredPromptFields.isNotEmpty()) {
                            append("；模板字段未应用，继续使用模型原生聊天模板")
                        }
                    }
                }.onFailure { error ->
                    presetImportMessage = "酒馆预设导入失败：${error.message ?: "请确认选择的是生成参数 JSON 文件。"}"
                }
                presetImporting = false
            }
        }
    }
    fun buildDraft(id: String?, draftName: String = name): AssistantEditorDraft =
        AssistantEditorDraft(
            id = id,
            name = draftName,
            avatar = avatar,
            tag = tag,
            systemPrompt = prompt,
            defaultModelMode = defaultModelMode,
            defaultModelId = defaultModelId,
            temperature = temperatureText.toAssistantFloat(assistant?.temperature ?: GenerationParams().temperature, 0f, 2f),
            topP = topPText.toAssistantFloat(assistant?.topP ?: GenerationParams().topP, 0f, 1f),
            nPredict = nPredictText.toAssistantInt(assistant?.nPredict ?: GenerationParams().nPredict, 16, 65_536),
            reasoningMode = reasoningMode,
            memoryEnabled = memoryEnabled,
            memorySummaryInterval = memorySummaryIntervalText.toAssistantInt(assistant?.memorySummaryInterval ?: 12, 2, 100),
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled,
            topK = topKText.toAssistantInt(assistant?.topK ?: GenerationParams().topK, 0, 1000),
            minP = minPText.toAssistantFloat(assistant?.minP ?: GenerationParams().minP, 0f, 1f),
            repeatPenalty = repeatPenaltyText.toAssistantFloat(assistant?.repeatPenalty ?: GenerationParams().repeatPenalty, 0.5f, 2f),
            presencePenalty = presencePenaltyText.toAssistantFloat(assistant?.presencePenalty ?: GenerationParams().presencePenalty, -2f, 2f),
            frequencyPenalty = frequencyPenaltyText.toAssistantFloat(assistant?.frequencyPenalty ?: GenerationParams().frequencyPenalty, -2f, 2f),
            stopWords = stopWordsText.toAssistantStopWords(assistant?.stopWords.orEmpty())
        )

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
                Text(if (assistant == null) "新建助手" else "编辑助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("角色卡、提示词、默认模型与生成参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
                item {
                    Text("基础信息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = avatar,
                            onValueChange = { avatar = it.take(4) },
                            label = { Text("头像") },
                            singleLine = true,
                            modifier = Modifier.weight(0.72f)
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("助手名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1.28f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it.take(24) },
                        label = { Text("标签") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("角色演绎风格", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "风格说明会加入可编辑提示词，并设置较短的移动端输出上限。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            TextButton(onClick = { roleplayPresetMenuExpanded = true }) {
                                Text(
                                    RoleplayStylePresets.all.firstOrNull { it.id == roleplayPresetId }?.name
                                        ?: "选择预设"
                                )
                            }
                            DropdownMenu(
                                expanded = roleplayPresetMenuExpanded,
                                onDismissRequest = { roleplayPresetMenuExpanded = false }
                            ) {
                                RoleplayStylePresets.all.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            roleplayPresetMenuExpanded = false
                                            roleplayPresetId = preset.id
                                            prompt = RoleplayStylePresets.withPresetPrompt(prompt, preset)
                                            temperatureText = preset.temperature.cleanParamText()
                                            topPText = preset.topP.cleanParamText()
                                            topKText = preset.topK.toString()
                                            repeatPenaltyText = preset.repeatPenalty.cleanParamText()
                                            nPredictText = preset.maxTokens.toString()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("移除风格说明") },
                                    onClick = {
                                        roleplayPresetMenuExpanded = false
                                        roleplayPresetId = null
                                        prompt = RoleplayStylePresets.withPresetPrompt(prompt, null)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it.take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS)
                            roleplayPresetId = RoleplayStylePresets.selectedPresetId(prompt)
                        },
                        label = { Text("系统提示词") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        prompt = GenerationParams().systemPrompt
                        roleplayPresetId = null
                    }) {
                        Text("恢复默认提示词")
                    }
                }
                item {
                    Text("默认模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = defaultModelMode == "follow_current",
                            onClick = {
                                defaultModelMode = "follow_current"
                                defaultModelId = null
                            },
                            label = { Text("跟随当前") }
                        )
                        FilterChip(
                            selected = defaultModelMode != "follow_current",
                            enabled = selectedModelId != null,
                            onClick = {
                                defaultModelMode = if (selectedModelIsCloud) "cloud" else "local"
                                defaultModelId = selectedModelId
                            },
                            label = {
                                Text(
                                    when (defaultModelMode) {
                                        "cloud" -> "已绑定云端"
                                        "local" -> "已绑定本地"
                                        else -> if (selectedModelIsCloud) "绑定当前云端" else "绑定当前本地"
                                    }
                                )
                            }
                        )
                    }
                    Text(
                        when {
                            defaultModelMode == "follow_current" -> "切换到该助手时继续使用聊天页当前模型。"
                            defaultModelId.isNullOrBlank() -> "当前绑定缺少模型，请先在聊天页选择可用模型。"
                            else -> "切换到该助手时优先使用：${assistant?.modelSummary ?: selectedModelName ?: "已绑定模型"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("能力", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = memoryEnabled, onClick = { memoryEnabled = !memoryEnabled }, label = { Text("记忆") })
                        FilterChip(selected = webSearchEnabled, onClick = { webSearchEnabled = !webSearchEnabled }, label = { Text("联网检索") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = fileContextEnabled, onClick = { fileContextEnabled = !fileContextEnabled }, label = { Text("文件上下文") })
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("本地工具预留") })
                    }
                    if (memoryEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = memorySummaryIntervalText,
                            onValueChange = { value -> memorySummaryIntervalText = value.filter(Char::isDigit).take(3) },
                            label = { Text("每几轮总结一次") },
                            supportingText = { Text("2 - 100 轮") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (assistant != null) {
                        TextButton(onClick = { showMemoryManager = true }) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("管理记忆")
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "生成参数",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { presetPicker.launch(arrayOf("*/*")) },
                            enabled = !presetImporting,
                            modifier = Modifier.height(40.dp)
                        ) {
                            if (presetImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(if (presetImporting) "读取中" else "导入酒馆预设")
                        }
                    }
                    presetImportMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "支持 temperature、top_p/top_k、min_p、rep_pen、penalties、max_length/max_tokens 和 stop；模型模板、prompt_order、宏及其他未支持字段会忽略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = temperatureText,
                            onValueChange = { temperatureText = it },
                            label = { Text("温度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = topPText,
                            onValueChange = { topPText = it },
                            label = { Text("top_p") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nPredictText,
                        onValueChange = { nPredictText = it },
                        label = { Text("输出长度") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { advancedSamplingExpanded = !advancedSamplingExpanded }) {
                        Text(if (advancedSamplingExpanded) "收起高级采样参数" else "高级采样参数")
                    }
                    if (advancedSamplingExpanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = topKText,
                                onValueChange = { topKText = it },
                                label = { Text("top_k") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = minPText,
                                onValueChange = { minPText = it },
                                label = { Text("min_p") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = repeatPenaltyText,
                                onValueChange = { repeatPenaltyText = it },
                                label = { Text("重复惩罚") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = presencePenaltyText,
                                onValueChange = { presencePenaltyText = it },
                                label = { Text("presence") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = frequencyPenaltyText,
                                onValueChange = { frequencyPenaltyText = it },
                                label = { Text("frequency") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = stopWordsText,
                                onValueChange = { stopWordsText = it.take(4096) },
                                label = { Text("停止词 JSON 数组") },
                                placeholder = { Text("[\"<END>\", \"\\nUser:\"]") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ReasoningMode.entries.forEach { mode ->
                            FilterChip(
                                selected = reasoningMode == mode,
                                onClick = { reasoningMode = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        "助手只保存生成参数；上下文、线程和 native 加载参数属于当前模型配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("角色卡", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                val copyName = name.trim().ifBlank { assistant?.name ?: "未命名助手" }.let { "$it 副本" }
                                onSave(buildDraft(null, copyName))
                            },
                            enabled = assistant != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制为新助手")
                        }
                        TextButton(
                            onClick = { assistant?.id?.let(onDelete) },
                            enabled = assistant != null && assistant.id != "default",
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "删除助手",
                                color = if (assistant != null && assistant.id != "default") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
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
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(buildDraft(assistant?.id)) },
                    modifier = Modifier.weight(1.45f).height(48.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("保存")
                }
            }
        }
        if (showMemoryManager && assistant != null) {
            AssistantMemoryManagerDialog(
                assistant = assistant,
                memories = memories,
                onDismiss = { showMemoryManager = false },
                onUpsert = onUpsertMemory,
                onDelete = onDeleteMemory
            )
        }
}

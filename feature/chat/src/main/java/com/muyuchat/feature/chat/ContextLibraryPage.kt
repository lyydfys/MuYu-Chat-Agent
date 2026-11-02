package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaSwitch

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

@Composable
internal fun ContextLibraryPage(
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    statusMessage: String?,
    hasActiveConversation: Boolean,
    onBack: () -> Unit,
    onImportWorldBook: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onSetWorldBookEnabled: (String, Boolean) -> Unit = { _, _ -> },
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
            McaIconButton(
                contentDescription = "返回助手列表",
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                glyphSize = McaIconSize.L
            ) {
                Icon(McaIcons.Back, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("上下文资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "世界书与本地知识库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            McaButton(style = McaButtonStyle.Text,onClick = { showUsageGuide = true }) {
                Icon(McaIcons.Info, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                        McaButton(style = McaButtonStyle.Text,onClick = { worldBookScopeMenuOpen = true }) {
                            Icon(McaIcons.Folder, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                            Text(
                                book.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (book.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                buildString {
                                    append(book.scopeLabel).append(" · ").append(book.entryCount).append(" 条")
                                    if (book.constantEntryCount > 0) {
                                        append(" · 常驻 ").append(book.constantEntryCount)
                                    }
                                    if (book.keywordEntryCount > 0) {
                                        append(" · 关键词触发 ").append(book.keywordEntryCount)
                                    }
                                    if (!book.enabled) append(" · 已停用")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when {
                                    !book.enabled -> "已停用，不会注入任何条目。"
                                    book.constantEntryCount > 0 -> "常驻条目每轮注入；其余条目需要消息命中关键词。"
                                    else -> "本轮消息命中关键词后才会注入。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        McaSwitch(
                            checked = book.enabled,
                            onCheckedChange = { enabled -> onSetWorldBookEnabled(book.id, enabled) },
                            contentDescription = if (book.enabled) "停用世界书：${book.name}" else "启用世界书：${book.name}"
                        )
                        McaIconButton(contentDescription = "删除世界书", onClick = { onDeleteWorldBook(book.id) }, glyphSize = McaIconSize.L) {
                            Icon(McaIcons.Delete, contentDescription = null)
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
                    McaIconButton(
                        contentDescription = "创建知识库",
                        onClick = {
                            val name = knowledgeBaseName.trim()
                            if (name.isNotEmpty()) {
                                onCreateKnowledgeBase(name)
                                knowledgeBaseName = ""
                            }
                        },
                        enabled = knowledgeBaseName.isNotBlank(),
                        glyphSize = McaIconSize.L
                    ) {
                        Icon(McaIcons.Add, contentDescription = null)
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
                        McaIconButton(
                            contentDescription = "导入知识库文档",
                            onClick = { onImportKnowledgeDocument(knowledgeBase.id) },
                            enabled = !knowledgeBase.importing,
                            glyphSize = McaIconSize.L
                        ) {
                            if (knowledgeBase.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(McaIcons.Folder, contentDescription = null)
                            }
                        }
                        McaIconButton(contentDescription = "删除知识库", onClick = { onDeleteKnowledgeBase(knowledgeBase.id) }, glyphSize = McaIconSize.L) {
                            Icon(McaIcons.Delete, contentDescription = null)
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
                McaButton(style = McaButtonStyle.Text,onClick = { showUsageGuide = false }) { Text("知道了") }
            }
        )
    }
}

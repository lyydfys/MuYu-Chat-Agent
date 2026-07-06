package com.muyuchat.feature.chat

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Calendar
import kotlin.math.roundToInt

private const val CLOUD_REASONING_LOCKED_TIP = "云端思考由模型服务商控制，MCA 已默认启用，暂不支持切换。"
private val GeminiPrimaryBlue = Color(0xFF3F7DE8)
private val GeminiInputShell = Color(0xFFFEFEFF)
private val GeminiInputField = Color(0xFFF3F7FF)
private val GeminiInputIconSurface = Color(0xFFEAF1FF)
private val GeminiInputText = Color(0xFF1F2937)
private val GeminiInputPlaceholder = Color(0xFF8A93A3)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val history: List<ChatHistoryItem> = emptyList(),
    val localModels: List<ChatModelChoice> = emptyList(),
    val imageModels: List<ChatModelChoice> = emptyList(),
    val assistants: List<AssistantUiItem> = emptyList(),
    val selectedAssistantId: String = "default",
    val images: List<ImageAssetUiItem> = emptyList(),
    val files: List<FileAssetUiItem> = emptyList(),
    val imageJobs: List<ImageGenerationUiJob> = emptyList(),
    val activeConversationId: String? = null,
    val input: String = "",
    val isGenerating: Boolean = false,
    val selectedModelId: String? = null,
    val selectedModelName: String? = null,
    val selectedModelIsCloud: Boolean = false,
    val selectedImageModelId: String? = null,
    val selectedImageModelName: String? = null,
    val selectedImageModelIsCloud: Boolean = false,
    val stats: RuntimeStats = RuntimeStats(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    val reasoningMode: ReasoningMode = ReasoningMode.OFF,
    val webSearchEnabled: Boolean = false,
    val webSearchConfigured: Boolean = false,
    val webSearchEnabledForTurn: Boolean = false,
    val webSearchStatusMessage: String? = null,
    val webSearchTurnModeLabel: String = "",
    val webSearchResearchModeLabel: String = "",
    val webSearchResearchOverridden: Boolean = false,
    val webSearchProviderLabel: String = ""
)

data class AssistantUiItem(
    val id: String,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val modelSummary: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nCtx: Int,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val selected: Boolean,
    val exportJson: String
)

data class AssistantEditorDraft(
    val id: String?,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nCtx: Int,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean
)

data class ImageAssetUiItem(
    val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String,
    val createdAtText: String,
    val sizeText: String,
    val width: Int,
    val height: Int
)

data class FileAssetUiItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val preview: String,
    val createdAtText: String,
    val sizeText: String,
    val truncated: Boolean
)

data class ImageGenerationUiJob(
    val id: String,
    val prompt: String,
    val statusLabel: String,
    val imageAssetId: String? = null,
    val failed: Boolean = false,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis()
)

data class ChatModelChoice(
    val id: String,
    val displayName: String,
    val quant: String? = null,
    val sizeBytes: Long = 0L,
    val loaded: Boolean = false,
    val subtitle: String = "",
    val cloud: Boolean = false
)

data class ChatHistoryItem(
    val id: String,
    val title: String,
    val updatedAtText: String,
    val updatedAtMillis: Long,
    val messageCount: Int,
    val pinned: Boolean,
    val selected: Boolean
)

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onUploadFile: (String) -> Unit,
    onUseImageAsset: (String) -> Unit = {},
    onDeleteImageAsset: (String) -> Unit = {},
    onUseFileAsset: (String) -> Unit = {},
    onDeleteFileAsset: (String) -> Unit = {},
    onGenerateImagePrompt: (String) -> Unit = {},
    onCancelImageGeneration: () -> Unit = {},
    onSelectImageModel: (String) -> Unit = {},
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit = {},
    onToggleWebSearchForTurn: () -> Unit = {},
    onCycleWebSearchResearchMode: () -> Unit = {},
    onOpenWebSearchSettings: () -> Unit = {},
    onLoadModel: (String) -> Unit = {},
    onOpenAgent: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveAssistant: (AssistantEditorDraft) -> Unit = {},
    onSelectAssistant: (String) -> Unit = {},
    onDeleteAssistant: (String) -> Unit = {},
    onImportAssistantCard: (String) -> Unit = {},
    appMenuOpen: Boolean = false,
    onAppMenuOpenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showImages by rememberSaveable { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var showFileLibrary by rememberSaveable { mutableStateOf(false) }
    var imagePrompt by rememberSaveable { mutableStateOf("") }
    fun enqueueImagePrompt(prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        onGenerateImagePrompt(cleanPrompt)
        imagePrompt = ""
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { onUploadFile(saveCameraPreview(context, it).toString()) }
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    val historyBackEnabled = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open
    SystemBackMotionHandler(
        enabled = historyBackEnabled,
        onProgress = {},
        onCancel = {},
        onBack = {
            scope.launch { drawerState.close() }
        }
    )
    BackHandler(enabled = historyBackEnabled) {
        scope.launch { drawerState.close() }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                state = state,
                onClose = { scope.launch { drawerState.close() } },
                onNewConversation = {
                    onNewConversation()
                    scope.launch { drawerState.close() }
                },
                onOpenAppMenu = {
                    onAppMenuOpenChange(true)
                    scope.launch { drawerState.close() }
                },
                onOpenImages = {
                    showImages = true
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { id ->
                    onSelectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = onDeleteConversation,
                onClearHistory = onClearHistory,
                onRenameConversation = onRenameConversation,
                onTogglePinConversation = onTogglePinConversation,
                onExportConversation = onExportConversation
            )
        }
    ) {
        Box(
            modifier = Modifier
                .then(modifier)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatStatusBar(
                    state = state,
                    onOpenHistory = { scope.launch { drawerState.open() } },
                    onNewConversation = onNewConversation,
                    onLoadModel = onLoadModel,
                    onOpenModels = onOpenModels,
                    onReasoningModeChange = onReasoningModeChange,
                    onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                    onOpenAppMenu = { onAppMenuOpenChange(true) }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 26.dp, bottom = 118.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (state.messages.isEmpty()) {
                        item { EmptyChatPanel(state.selectedModelName) }
                    }
                    val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
                    itemsIndexed(state.messages) { index, message ->
                        MessageBubble(
                            message = message,
                            showAssistantActions = index == lastAssistantIndex && message.role == Role.ASSISTANT,
                            canRegenerate = !state.isGenerating,
                            isGenerating = state.isGenerating && index == lastAssistantIndex,
                            onRegenerate = onRegenerate,
                            onDelete = { onDeleteMessage(index) }
                        )
                    }
                }
            }

            ChatInputBar(
                input = state.input,
                isGenerating = state.isGenerating,
                onInputChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                onOpenCamera = {
                    cameraPicker.launch(null)
                },
                onOpenPhoto = {
                    photoPicker.launch("image/*")
                },
                onOpenFile = {
                    filePicker.launch(arrayOf("text/*", "application/json", "application/xml"))
                },
                onOpenFileLibrary = {
                    showFileLibrary = true
                },
                reasoningMode = state.reasoningMode,
                onReasoningModeChange = onReasoningModeChange,
                webSearchEnabled = state.webSearchEnabled,
                webSearchConfigured = state.webSearchConfigured,
                webSearchEnabledForTurn = state.webSearchEnabledForTurn,
                webSearchStatusMessage = state.webSearchStatusMessage,
                webSearchTurnModeLabel = state.webSearchTurnModeLabel,
                webSearchResearchModeLabel = state.webSearchResearchModeLabel,
                webSearchResearchOverridden = state.webSearchResearchOverridden,
                webSearchProviderLabel = state.webSearchProviderLabel,
                onToggleWebSearchForTurn = onToggleWebSearchForTurn,
                onCycleWebSearchResearchMode = onCycleWebSearchResearchMode,
                onOpenWebSearchSettings = onOpenWebSearchSettings,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            SmoothRightToLeftPage(
                visible = showAssistants,
                onDismiss = { showAssistants = false }
            ) { pageModifier, closePage ->
                AssistantRoleScreen(
                    assistants = state.assistants,
                    selectedAssistantId = state.selectedAssistantId,
                    selectedModelName = state.selectedModelName,
                    selectedModelId = state.selectedModelId,
                    selectedModelIsCloud = state.selectedModelIsCloud,
                    onSaveAssistant = onSaveAssistant,
                    onSelectAssistant = onSelectAssistant,
                    onDeleteAssistant = onDeleteAssistant,
                    onImportAssistantCard = onImportAssistantCard,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            SmoothRightToLeftPage(
                visible = showImages,
                onDismiss = { showImages = false }
            ) { pageModifier, closePage ->
                ImagesWorkspaceScreen(
                    images = state.images,
                    jobs = state.imageJobs,
                    imageModels = state.imageModels,
                    selectedImageModelId = state.selectedImageModelId,
                    selectedImageModelName = state.selectedImageModelName,
                    selectedImageModelIsCloud = state.selectedImageModelIsCloud,
                    prompt = imagePrompt,
                    onPromptChange = { imagePrompt = it },
                    onSubmitPrompt = { enqueueImagePrompt(imagePrompt) },
                    onCancelGeneration = onCancelImageGeneration,
                    onRetryJob = { job -> enqueueImagePrompt(job.prompt) },
                    onBack = closePage,
                    onOpenPhoto = { photoPicker.launch("image/*") },
                    onSelectImageModel = onSelectImageModel,
                    onUseImageAsset = {
                        onUseImageAsset(it)
                        closePage()
                    },
                    onDeleteImageAsset = onDeleteImageAsset,
                    modifier = pageModifier
                )
            }

            SmoothRightToLeftPage(
                visible = showFileLibrary,
                onDismiss = { showFileLibrary = false }
            ) { pageModifier, closePage ->
                FileLibraryPage(
                    files = state.files,
                    onInsert = { id ->
                        onUseFileAsset(id)
                        closePage()
                    },
                    onDelete = onDeleteFileAsset,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            PredictiveAppMenuPage(
                visible = appMenuOpen,
                onDismiss = { onAppMenuOpenChange(false) }
            ) { menuModifier, closeMenu ->
                McaAppMenuPage(
                    state = state,
                    onClose = closeMenu,
                    onOpenModels = {
                        onOpenModels()
                    },
                    onOpenAgent = {
                        onOpenAgent()
                    },
                    onOpenApi = {
                        onOpenApi()
                    },
                    onOpenSettings = {
                        onOpenSettings()
                    },
                    onOpenImages = {
                        closeMenu()
                        showImages = true
                    },
                    onOpenAssistants = {
                        closeMenu()
                        showAssistants = true
                    },
                    onClearHistory = onClearHistory,
                    modifier = menuModifier
                )
            }

        }
    }
}

@Composable
private fun AssistantRoleScreen(
    assistants: List<AssistantUiItem>,
    selectedAssistantId: String,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onSaveAssistant: (AssistantEditorDraft) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onImportAssistantCard: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AssistantUiItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val selected = assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()
    val editingAssistant = editing
    fun closeAssistantSubPage() {
        creating = false
        editing = null
        importing = false
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
            visible = creating || editingAssistant != null || importing,
            onDismiss = ::closeAssistantSubPage
        ) { pageModifier, closePage ->
            when {
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
                    modifier = pageModifier
                )
                importing -> AssistantImportPage(
                    onBack = closePage,
                    onImport = {
                        onImportAssistantCard(it)
                        closePage()
                    },
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
    onBack: () -> Unit,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onDelete: (String) -> Unit,
    onSave: (AssistantEditorDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(assistant?.id) { mutableStateOf(assistant?.name ?: "") }
    var avatar by remember(assistant?.id) { mutableStateOf(assistant?.avatar ?: "") }
    var tag by remember(assistant?.id) { mutableStateOf(assistant?.tag ?: "") }
    var prompt by remember(assistant?.id) { mutableStateOf(assistant?.systemPrompt ?: "") }
    var defaultModelMode by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelMode ?: "follow_current") }
    var defaultModelId by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelId) }
    var temperatureText by remember(assistant?.id) { mutableStateOf((assistant?.temperature ?: GenerationParams().temperature).cleanParamText()) }
    var topPText by remember(assistant?.id) { mutableStateOf((assistant?.topP ?: GenerationParams().topP).cleanParamText()) }
    var nCtxText by remember(assistant?.id) { mutableStateOf((assistant?.nCtx ?: GenerationParams().nCtx).toString()) }
    var nPredictText by remember(assistant?.id) { mutableStateOf((assistant?.nPredict ?: GenerationParams().nPredict).toString()) }
    var reasoningMode by remember(assistant?.id) { mutableStateOf(assistant?.reasoningMode ?: GenerationParams().reasoningMode) }
    var memoryEnabled by remember(assistant?.id) { mutableStateOf(assistant?.memoryEnabled ?: false) }
    var webSearchEnabled by remember(assistant?.id) { mutableStateOf(assistant?.webSearchEnabled ?: false) }
    var fileContextEnabled by remember(assistant?.id) { mutableStateOf(assistant?.fileContextEnabled ?: true) }
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
            nCtx = nCtxText.toAssistantInt(assistant?.nCtx ?: GenerationParams().nCtx, 512, 262_144),
            nPredict = nPredictText.toAssistantInt(assistant?.nPredict ?: GenerationParams().nPredict, 128, 65_536),
            reasoningMode = reasoningMode,
            memoryEnabled = memoryEnabled,
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled
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
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("系统提示词") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { prompt = GenerationParams().systemPrompt }) {
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
                }
                item {
                    Text("参数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = nCtxText,
                            onValueChange = { nCtxText = it },
                            label = { Text("上下文") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = nPredictText,
                            onValueChange = { nPredictText = it },
                            label = { Text("输出长度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
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
                        "保存后作为该助手的默认参数；聊天页和智能调参仍可继续调整当前会话。",
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
}

@Composable
private fun AssistantImportPage(
    onBack: () -> Unit,
    onImport: (String) -> Unit,
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
private fun AssistantAvatar(name: String, avatar: String, selected: Boolean) {
    val label = avatar.trim().ifBlank { name.trim() }.firstOrNull()?.toString() ?: "M"
    Surface(
        modifier = Modifier.size(42.dp),
        color = if (selected) GeminiPrimaryBlue else MaterialTheme.colorScheme.surfaceVariant,
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
private fun AssistantCapabilityChip(label: String, enabled: Boolean) {
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

private fun Float.cleanParamText(): String =
    if (this % 1f == 0f) toInt().toString() else "%.2f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')

private fun String.toAssistantFloat(default: Float, min: Float, max: Float): Float =
    trim().toFloatOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

private fun String.toAssistantInt(default: Int, min: Int, max: Int): Int =
    trim().toIntOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

@Composable
private fun ImagesWorkspaceScreen(
    images: List<ImageAssetUiItem>,
    jobs: List<ImageGenerationUiJob>,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSubmitPrompt: () -> Unit,
    onCancelGeneration: () -> Unit,
    onRetryJob: (ImageGenerationUiJob) -> Unit,
    onBack: () -> Unit,
    onOpenPhoto: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    var showGenerationCanvas by rememberSaveable { mutableStateOf(false) }
    var pendingConversationPrompt by rememberSaveable { mutableStateOf("") }
    var previewImageId by rememberSaveable { mutableStateOf<String?>(null) }
    val latestJob = jobs.firstOrNull()
    val activeJob = if (showGenerationCanvas) latestJob else null
    val activePrompt = activeJob?.prompt ?: pendingConversationPrompt
    val activeImage = activeJob?.imageAssetId?.let { imageId ->
        images.firstOrNull { it.id == imageId }
    } ?: activeJob
        ?.takeIf { it.statusLabel == "完成" }
        ?.let { doneJob -> images.firstOrNull { it.prompt == doneJob.prompt } }
    val isImageGenerating = activeJob?.isWorking == true

    BackHandler(enabled = showGenerationCanvas) {
        showGenerationCanvas = false
    }
    BackHandler(enabled = previewImageId != null) {
        previewImageId = null
    }

    fun submitFromImages() {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        pendingConversationPrompt = cleanPrompt
        showGenerationCanvas = true
        onSubmitPrompt()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (darkTheme) MaterialTheme.colorScheme.background else Color.White)
            .navigationBarsPadding()
    ) {
        if (showGenerationCanvas) {
            ImageGenerationCanvas(
                prompt = activePrompt,
                job = activeJob,
                image = activeImage,
                imageModels = imageModels,
                selectedImageModelId = selectedImageModelId,
                selectedImageModelName = selectedImageModelName,
                selectedImageModelIsCloud = selectedImageModelIsCloud,
                onBackToGallery = { showGenerationCanvas = false },
                onSelectImageModel = onSelectImageModel,
                onRetry = { activeJob?.let(onRetryJob) },
                onCancelGeneration = onCancelGeneration,
                onUseImageAsset = onUseImageAsset
            )
        } else {
            ImageGalleryHome(
                images = images,
                imageModels = imageModels,
                selectedImageModelId = selectedImageModelId,
                selectedImageModelName = selectedImageModelName,
                selectedImageModelIsCloud = selectedImageModelIsCloud,
                onBack = onBack,
                onSelectImageModel = onSelectImageModel,
                onPromptChange = onPromptChange,
                onOpenImagePreview = { previewImageId = it },
                onDeleteImageAsset = onDeleteImageAsset
            )
        }

        ImagePromptBar(
            prompt = prompt,
            onPromptChange = onPromptChange,
            onOpenPhoto = onOpenPhoto,
            onSubmit = ::submitFromImages,
            placeholder = if (showGenerationCanvas) "回复 MCA" else "描述图像",
            isGenerating = isImageGenerating,
            onStop = onCancelGeneration,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        previewImageId?.let { imageId ->
            images.firstOrNull { it.id == imageId }?.let { image ->
                ImageAssetPreviewOverlay(
                    image = image,
                    onDismiss = { previewImageId = null },
                    onShare = {
                        shareImageAsset(context, image)
                            .onFailure { error ->
                                Toast.makeText(context, error.message ?: "图片分享失败", Toast.LENGTH_SHORT).show()
                            }
                    },
                    onDelete = {
                        onDeleteImageAsset(image.id)
                        previewImageId = null
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

private val ImageGenerationUiJob.isWorking: Boolean
    get() = !failed && statusLabel != "完成"

@Composable
private fun ImageGalleryHome(
    images: List<ImageAssetUiItem>,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    onBack: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val titleColor = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
    val emptyTileColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { ImageGalleryTitleBar(onBack = onBack) }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                onSelectModel = onSelectImageModel
            )
        }
        item {
            Text(
                "生成图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = ((maxWidth - 27.dp) / 4f).coerceIn(68.dp, 88.dp)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(end = 18.dp)
                ) {
                    items(imagePromptTemplates, key = { it.title }) { template ->
                        ImageTemplateCard(
                            template = template,
                            cardWidth = cardWidth,
                            onClick = { onPromptChange(template.prompt) }
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(58.dp))
        }
        item {
            Text(
                "我的图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        if (images.isEmpty()) {
            items(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(112.dp)
                                .background(emptyTileColor)
                        )
                    }
                }
            }
        } else {
            items(images.chunked(3), key = { row -> row.joinToString("-") { it.id } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { image ->
                        ImageAssetTile(
                            image = image,
                            onOpen = { onOpenImagePreview(image.id) },
                            onDelete = { onDeleteImageAsset(image.id) },
                            modifier = Modifier
                                .weight(1f)
                                .height(118.dp)
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(118.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGalleryTitleBar(onBack: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = CircleShape,
            shadowElevation = 7.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(27.dp))
            }
        }
        Text(
            "图片",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
            fontWeight = FontWeight.Bold,
            color = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
        )
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun ImageGenerationCanvas(
    prompt: String,
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    onBackToGallery: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBackToGallery,
                    color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                    shape = CircleShape,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回图片", modifier = Modifier.size(24.dp))
                        Text("图片", fontSize = 17.sp, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                    shape = CircleShape,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "新建图片对话", modifier = Modifier.size(23.dp))
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多", modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                onSelectModel = onSelectImageModel
            )
        }
        item {
            UserImagePromptBubble(prompt = prompt)
        }
        item {
            ImageAssistantResultCard(
                job = job,
                image = image,
                onRetry = onRetry,
                onCancelGeneration = onCancelGeneration,
                onUseImageAsset = onUseImageAsset
            )
        }
    }
}

@Composable
private fun UserImagePromptBubble(prompt: String) {
    val darkTheme = isSystemInDarkTheme()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 310.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F1F1),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                prompt.ifBlank { "正在准备图片请求" },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124),
                lineHeight = 23.sp,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun ImageAssistantResultCard(
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val actionTint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            job?.failed == true -> ImageGenerationFailureCard(job = job, onRetry = onRetry)
            image != null -> ImageGenerationResultImage(image = image, onUseImageAsset = onUseImageAsset)
            else -> ImageCreatingPlaceholder(
                statusText = job?.statusLabel ?: "正在创建图片",
                statusMessage = job?.message.orEmpty(),
                startedAtMillis = job?.startedAtMillis ?: System.currentTimeMillis(),
                onCancel = onCancelGeneration
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ThumbUp, contentDescription = "喜欢", tint = actionTint, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ThumbDown, contentDescription = "不喜欢", tint = actionTint, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = actionTint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ImageCreatingPlaceholder(
    statusText: String,
    statusMessage: String,
    startedAtMillis: Long,
    onCancel: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val cardColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF5F8FF)
    val titleColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF31415F)
    val bodyColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF66748A)
    val progressTrackColor = if (darkTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val transition = rememberInfiniteTransition(label = "image-create")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
        label = "image-create-phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "image-create-pulse"
    )
    var elapsedSeconds by remember(startedAtMillis) {
        mutableStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val waitingText = when {
        elapsedSeconds >= 240L -> "已等待 ${elapsedSeconds.formatElapsed()}，本地生成仍在运行"
        elapsedSeconds >= 60L -> "已等待 ${elapsedSeconds.formatElapsed()}"
        else -> "已等待 ${elapsedSeconds} 秒"
    }
    Surface(
        modifier = Modifier
            .width(292.dp)
            .height(292.dp),
        color = cardColor,
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (statusText == "完成") "正在整理图片" else "正在创建图片",
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    statusMessage.ifBlank { "MCA 正在等待图像引擎返回结果" },
                    color = bodyColor,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Text(
                    waitingText,
                    color = GeminiPrimaryBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onCancel) {
                    Text("取消生成")
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dot = 1.7.dp.toPx()
                val gap = 12.dp.toPx()
                val groups = listOf(
                    Offset(size.width * 0.25f, size.height * 0.45f),
                    Offset(size.width * 0.62f, size.height * 0.72f)
                )
                groups.forEachIndexed { groupIndex, origin ->
                    repeat(6) { x ->
                        repeat(5) { y ->
                            val wave = ((x + y + groupIndex * 2) / 12f + phase) % 1f
                            val alpha = (0.18f + (1f - kotlin.math.abs(wave - 0.5f) * 2f) * 0.50f) * pulse
                            drawCircle(
                                color = GeminiPrimaryBlue.copy(alpha = alpha.coerceIn(0.12f, 0.62f)),
                                radius = dot,
                                center = Offset(origin.x + x * gap, origin.y + y * gap)
                            )
                        }
                    }
                }
                val trackWidth = size.width * 0.64f
                val trackHeight = 7.dp.toPx()
                val trackLeft = size.width * 0.18f
                val trackTop = size.height - 48.dp.toPx()
                drawRoundRect(
                    color = progressTrackColor,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
                val highlightWidth = trackWidth * 0.34f
                val highlightLeft = trackLeft + (trackWidth + highlightWidth) * phase - highlightWidth
                drawRoundRect(
                    color = GeminiPrimaryBlue.copy(alpha = 0.55f),
                    topLeft = Offset(highlightLeft.coerceIn(trackLeft - highlightWidth, trackLeft + trackWidth), trackTop),
                    size = Size(highlightWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
            }
        }
    }
}

@Composable
private fun ImageGenerationFailureCard(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(job.prompt, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                Text(job.message.ifBlank { "图片生成失败" }, maxLines = 2, color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368), fontSize = 13.sp)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ImageGenerationResultImage(image: ImageAssetUiItem, onUseImageAsset: (String) -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    val ratio = remember(image.width, image.height) {
        if (image.width > 0 && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.72f, 1.78f)
        } else {
            1.22f
        }
    }
    Box(
        modifier = Modifier
            .widthIn(max = 336.dp)
            .fillMaxWidth(0.86f)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(22.dp))
            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .clickable { onUseImageAsset(image.id) },
            color = Color.Black.copy(alpha = 0.54f),
            shape = CircleShape
        ) {
            Text(
                "编辑",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            color = Color.Black.copy(alpha = 0.54f),
            shape = CircleShape
        ) {
            IconButton(onClick = {}, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Share, contentDescription = "分享", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private enum class ImageEngineSource(val title: String, val subtitle: String) {
    LOCAL("本地生图", "设备端图像引擎"),
    CLOUD("云端生图", "API 图像引擎")
}

@Composable
private fun ImageEngineSwitcher(
    models: List<ChatModelChoice>,
    selectedModelId: String?,
    selectedModelName: String?,
    selectedModelIsCloud: Boolean,
    onSelectModel: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val chipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF4F6F9)
    val modelChipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White
    val chipBorder = if (darkTheme) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f) else Color(0xFFE0E3E7)
    val chipTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
    val chipMutedColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368)
    var sourceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuSource by rememberSaveable { mutableStateOf<ImageEngineSource?>(null) }
    val selectedSource = if (selectedModelIsCloud) ImageEngineSource.CLOUD else ImageEngineSource.LOCAL
    val localModels = models.filterNot { it.cloud }.take(3)
    val cloudModels = models.filter { it.cloud }.take(3)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                onClick = { sourceMenuExpanded = true },
                color = chipColor,
                shape = CircleShape,
                border = BorderStroke(1.dp, chipBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipTextColor)
                    Text(selectedSource.title, fontWeight = FontWeight.Bold, color = chipTextColor)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                }
            }
            DropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false }
            ) {
                ImageEngineSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(source.title, fontWeight = FontWeight.Bold)
                                Text(source.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            sourceMenuExpanded = false
                            modelMenuSource = source
                        }
                    )
                }
            }
        }

        AnimatedVisibility(visible = modelMenuSource != null) {
            val source = modelMenuSource ?: selectedSource
            val sourceModels = if (source == ImageEngineSource.CLOUD) cloudModels else localModels
            Box {
                Surface(
                    onClick = { modelMenuSource = source },
                    color = modelChipColor,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, chipBorder),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 210.dp)
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (source == selectedSource) selectedModelName ?: "选择模型" else source.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = chipTextColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                    }
                }
                DropdownMenu(
                    expanded = modelMenuSource == source,
                    onDismissRequest = { modelMenuSource = null }
                ) {
                    if (sourceModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(if (source == ImageEngineSource.CLOUD) "暂无云端生图模型" else "暂无本地生图模型") },
                            onClick = { modelMenuSource = null }
                        )
                    } else {
                        sourceModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(model.quant, model.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectModel(model.id)
                                    modelMenuSource = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ImagePromptTemplate(
    val title: String,
    val prompt: String,
    val imageRes: Int,
    val accent: Color
)

private val imagePromptTemplates = listOf(
    ImagePromptTemplate(
        "液态金属花园",
        "通透温室里的液态金属植物精灵，蓝白柔光，干净未来感，精致 3D 渲染",
        R.drawable.template_liquid_garden,
        Color(0xFF7EA9F5)
    ),
    ImagePromptTemplate(
        "晨光工作岛",
        "漂浮在晨光里的蓝白工作岛，模块化桌面、透明任务面板、清爽高效",
        R.drawable.template_work_island,
        Color(0xFF74A2E8)
    ),
    ImagePromptTemplate(
        "山海便签",
        "山海意象的手工便签拼贴，宣纸纹理、雾蓝水墨、压花和小罗盘",
        R.drawable.template_mountain_memo,
        Color(0xFF8BAFA9)
    ),
    ImagePromptTemplate(
        "纸雕分身",
        "白色纸雕风迷你分身，浅蓝背景，手持发光铅笔，柔和创意氛围",
        R.drawable.template_paper_avatar,
        Color(0xFF8BB7F0)
    ),
    ImagePromptTemplate(
        "旅行手帐",
        "旅行手帐拼贴，山海远景、纸张纹理、压花和暖色电影感，画面没有可读文字",
        R.drawable.template_travel_journal,
        Color(0xFFD4A36D)
    ),
    ImagePromptTemplate(
        "黑白漫画感",
        "黑白漫画风原创角色在桌前绘制小机器人，网点阴影，干净分镜感，无文字气泡",
        R.drawable.template_mono_comic,
        Color(0xFF7F8795)
    ),
    ImagePromptTemplate(
        "未来城市",
        "玻璃穹顶里的微缩未来城市，蓝白低对比光感、微型绿植和发光河道",
        R.drawable.template_future_city,
        Color(0xFF79B6E8)
    ),
    ImagePromptTemplate(
        "陶瓷甜点",
        "浅蓝陶瓷托盘上的抹茶柑橘甜点静物，清晨柔光，高级生活方式摄影",
        R.drawable.template_ceramic_dessert,
        Color(0xFFB7D9A7)
    )
)

@Composable
private fun ImageTemplateCard(
    template: ImagePromptTemplate,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .height(cardWidth * 1.42f),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, template.accent.copy(alpha = 0.18f))
        ) {
            Image(
                painter = painterResource(template.imageRes),
                contentDescription = template.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            template.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF858C98),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ImageJobRow(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                job.statusLabel,
                modifier = Modifier.width(58.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (job.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.prompt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
                )
                if (job.message.isNotBlank()) {
                    Text(
                        job.message,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368)
                    )
                }
            }
            if (job.failed) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun ImageAssetTile(
    image: ImageAssetUiItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
            .clickable(onClick = onOpen)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surface.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.88f),
            shape = CircleShape
        ) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "删除图片", modifier = Modifier.size(16.dp), tint = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
            }
        }
    }
}

@Composable
private fun ImageAssetPreviewOverlay(
    image: ImageAssetUiItem,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
            Text(
                image.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "分享图片", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(
                    onClick = {
                        downloadImageAssetToGallery(context, image)
                            .onSuccess { path ->
                                Toast.makeText(context, "已保存到 $path", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(context, error.message ?: "图片保存失败", Toast.LENGTH_SHORT).show()
                            }
                    }
                ) {
                    Icon(Icons.Default.Download, contentDescription = "下载图片", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除图片", tint = Color.White)
                }
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 12.dp, vertical = 76.dp)
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(42.dp))
                Text("无法读取图片", color = Color.White.copy(alpha = 0.82f))
            }
        }
        if (image.prompt.isNotBlank() || image.createdAtText.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${image.source.displayImageSource()} · ${image.createdAtText} · ${image.sizeText}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    if (image.prompt.isNotBlank()) {
                        Text(
                            image.prompt,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePromptBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onOpenPhoto: () -> Unit,
    onSubmit: () -> Unit,
    placeholder: String = "描述图像",
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else GeminiInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else GeminiInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else GeminiInputPlaceholder
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = inputShellColor,
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(inputIconSurfaceColor)
                    .clickable(onClick = onOpenPhoto),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Image, contentDescription = "添加图片", modifier = Modifier.size(22.dp), tint = GeminiPrimaryBlue)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp),
                color = inputFieldColor,
                shape = CircleShape
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (prompt.isBlank()) {
                        Text(
                            placeholder,
                            color = inputPlaceholderColor,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
                        )
                    }
                    BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        maxLines = 3,
                        textStyle = TextStyle(
                            color = inputTextColor,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(GeminiPrimaryBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            FloatingActionButton(
                onClick = if (isGenerating) onStop else onSubmit,
                containerColor = when {
                    isGenerating -> GeminiPrimaryBlue
                    prompt.isBlank() -> if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFE5ECF8)
                    else -> GeminiPrimaryBlue
                },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                if (isGenerating) {
                    Icon(Icons.Default.Stop, contentDescription = "停止生成", tint = Color.White)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "生成图片", tint = if (prompt.isBlank()) inputPlaceholderColor else Color.White)
                }
            }
        }
    }
}

private fun loadImageBitmap(context: Context, uriString: String): Bitmap? =
    runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme.equals("file", ignoreCase = true)) {
            BitmapFactory.decodeFile(uri.path)
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

private fun downloadImageAssetToGallery(context: Context, image: ImageAssetUiItem): Result<String> =
    runCatching { copyImageAssetToGallery(context, image).displayPath }

private fun shareImageAsset(context: Context, image: ImageAssetUiItem): Result<Unit> =
    runCatching {
        val saved = copyImageAssetToGallery(context, image)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = saved.mimeType
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            if (image.prompt.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, image.prompt)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, image.name, saved.uri)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }

private data class SavedImageCopy(
    val uri: Uri,
    val displayPath: String,
    val mimeType: String
)

private fun copyImageAssetToGallery(context: Context, image: ImageAssetUiItem): SavedImageCopy =
    runCatching {
        val fileName = image.downloadFileName()
        val mimeType = fileName.imageMimeType()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MCA")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建系统图片文件")
        try {
            openImageAssetInputStream(context, image.uriString).use { input ->
                resolver.openOutputStream(outputUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("无法写入系统图片库")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outputUri, values, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(outputUri, null, null)
            throw error
        }
        SavedImageCopy(
            uri = outputUri,
            displayPath = "${Environment.DIRECTORY_PICTURES}/MCA/$fileName",
            mimeType = mimeType
        )
    }.getOrThrow()

private fun openImageAssetInputStream(context: Context, uriString: String): InputStream {
    val uri = Uri.parse(uriString)
    return if (uri.scheme.equals("file", ignoreCase = true)) {
        File(requireNotNull(uri.path) { "图片路径无效" }).inputStream()
    } else {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片文件" }
    }
}

private fun ImageAssetUiItem.downloadFileName(): String {
    val rawName = name.ifBlank { "MCA-${id.take(8)}" }
    val extension = rawName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: uriString.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: "png"
    val base = rawName.substringBeforeLast('.', rawName)
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_')
        .take(64)
        .ifBlank { "MCA-${id.take(8)}" }
    return "$base.$extension"
}

private fun String.imageMimeType(): String =
    when (substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

private fun String.displayImageSource(): String =
    when {
        startsWith("generated:", ignoreCase = true) -> removePrefix("generated:")
            .replace('-', ' ')
            .replace('_', ' ')
            .ifBlank { "生成图片" }
        equals("generated", ignoreCase = true) -> "生成图片"
        equals("uploaded", ignoreCase = true) -> "上传图片"
        isBlank() -> "图片"
        else -> this
    }

private fun Long.formatElapsed(): String {
    val minutes = this / 60L
    val seconds = this % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PredictiveAppMenuPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss,
        content = content
    )
}

@Composable
private fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = appMenuPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            suspend fun closeAnimated() {
                offsetX.animateTo(
                    targetValue = -widthPx,
                    animationSpec = tween(durationMillis = 180)
                )
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch {
                    closeAnimated()
                }
            }

            SystemBackMotionHandler(
                enabled = visible,
                onProgress = { progress ->
                    scope.launch {
                        offsetX.snapTo(-widthPx * progress.coerceIn(0f, 1f))
                    }
                },
                onCancel = {
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 160)
                        )
                    }
                },
                onBack = {
                    scope.launch {
                        if (offsetX.value > -widthPx * 0.08f) {
                            offsetX.snapTo(-widthPx * 0.08f)
                        }
                        closeAnimated()
                    }
                }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

private fun appMenuPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 240),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 140))

private fun appMenuPageExit() = slideOutHorizontally(
    animationSpec = tween(durationMillis = 240),
    targetOffsetX = { -it }
) + fadeOut(animationSpec = tween(durationMillis = 140))

@Composable
private fun SystemBackMotionHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnBack by rememberUpdatedState(onBack)

    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                currentOnProgress(0f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                currentOnProgress(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                currentOnCancel()
            }

            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
    }

    LaunchedEffect(enabled) {
        callback.isEnabled = enabled
    }

    androidx.compose.runtime.DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher?.addCallback(lifecycleOwner, callback)
        onDispose {
            callback.remove()
        }
    }
}

@Composable
private fun McaAppMenuPage(
    state: ChatUiState,
    onClose: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAssistants: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            McaLogoMark(size = 66.dp, cornerRadius = 18.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "MuYu Chat Agent",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            "MCA",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = { Icon(Icons.Default.Psychology, null) },
                title = "助手与角色",
                subtitle = state.assistants.firstOrNull { it.selected }?.let { "${it.name} · 角色卡与能力" } ?: "当前助手、角色卡、提示词与能力",
                onClick = onOpenAssistants
            )
            AppMenuRow(icon = { Icon(Icons.Default.Folder, null) }, title = "模型管理", subtitle = "本地 GGUF 与魔塔下载", onClick = onOpenModels)
            AppMenuRow(icon = { McaLogoMark(size = 22.dp, cornerRadius = 7.dp) }, title = "智能调参", subtitle = "测速、推荐与高级参数", onClick = onOpenAgent)
            AppMenuRow(icon = { Icon(Icons.Default.NetworkWifi, null) }, title = "本地 API", subtitle = "接口地址、Key、API 使用文档", onClick = onOpenApi)
            AppMenuRow(icon = { Icon(Icons.Default.Settings, null) }, title = "系统设置", subtitle = "运行、日志、诊断与实验功能", onClick = onOpenSettings)
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "本机状态",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (state.selectedModelName == null) Color(0xFF9AA0A6) else Color(0xFF34A853))
                    )
                },
                title = displayModelName(state.selectedModelName) ?: "未加载模型",
                subtitle = "${"%.1f".format(state.stats.decodeTps)} token/s · ${state.stats.backend.uppercase()} 后端",
                onClick = {}
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.NetworkWifi, null) },
                title = "本地 API",
                subtitle = if (state.apiEnabled || state.restEnabled) "已启用" else "未启用",
                onClick = onOpenApi
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.Image, null) },
                title = "图像任务",
                subtitle = state.imageTaskSummary(),
                onClick = onOpenImages
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.Psychology, null) },
                title = state.assistants.firstOrNull { it.selected }?.name ?: "默认助手",
                subtitle = state.assistants.firstOrNull { it.selected }?.let {
                    "记忆${if (it.memoryEnabled) "开" else "关"} · 检索${if (it.webSearchEnabled) "开" else "关"}"
                } ?: "助手能力状态",
                onClick = onOpenAssistants
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "历史",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                title = "清空全部",
                subtitle = "删除本机保存的全部聊天记录",
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { confirmClear = true }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AppMenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppMenuRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    contentColor: Color? = null,
    onClick: () -> Unit
) {
    val rowContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = rowContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                color = if (rowContentColor == MaterialTheme.colorScheme.error) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                }
            )
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun McaLogoMark(
    size: Dp,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val markBackground = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF8F9FA)
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val scale = side / 108f
        fun s(value: Float) = value * scale

        drawRoundRect(
            color = markBackground,
            topLeft = Offset.Zero,
            size = Size(side, side),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )

        val bubble = Path().apply {
            moveTo(s(54f), s(20f))
            cubicTo(s(35.22f), s(20f), s(20f), s(33.43f), s(20f), s(50f))
            cubicTo(s(20f), s(59.39f), s(24.87f), s(67.76f), s(32.22f), s(73.19f))
            lineTo(s(28f), s(88f))
            lineTo(s(43.55f), s(83.33f))
            cubicTo(s(46.86f), s(84.45f), s(50.36f), s(85f), s(54f), s(85f))
            cubicTo(s(72.78f), s(85f), s(88f), s(71.57f), s(88f), s(55f))
            cubicTo(s(88f), s(38.43f), s(72.78f), s(20f), s(54f), s(20f))
            close()
        }
        drawPath(bubble, Color(0xFF1A73E8))

        val star = Path().apply {
            moveTo(s(54f), s(34f))
            lineTo(s(57f), s(47f))
            lineTo(s(70f), s(50f))
            lineTo(s(57f), s(53f))
            lineTo(s(54f), s(66f))
            lineTo(s(51f), s(53f))
            lineTo(s(38f), s(50f))
            lineTo(s(51f), s(47f))
            close()
        }
        drawPath(star, Color.White)
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(36f), s(38f)))
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(72f), s(62f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2.5f), center = Offset(s(42f), s(68f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2f), center = Offset(s(66f), s(36f)))
    }
}

@Composable
private fun AnimatedMcaAssistantMark(
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mca-mark")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mca-ring"
    )
    Box(
        modifier = modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val ringInset = 1.5.dp.toPx()
            val ringSize = Size(this.size.width - ringInset * 2f, this.size.height - ringInset * 2f)
            if (isGenerating) {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.88f),
                    startAngle = angle,
                    sweepAngle = 132f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.74f),
                    startAngle = angle + 180f,
                    sweepAngle = 86f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            } else {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.18f),
                    startAngle = 215f,
                    sweepAngle = 94f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.16f),
                    startAngle = 34f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            }
        }
        McaLogoMark(size = 22.dp, cornerRadius = 8.dp)
    }
}

@Composable
private fun ReasoningPanel(
    content: String,
    durationMs: Long,
    awaitingFinalAnswer: Boolean,
    modifier: Modifier = Modifier
) {
    val cleaned = remember(content) { cleanReasoningForDisplay(content) }
    if (cleaned.isBlank()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val seconds = durationMs.div(1000).coerceAtLeast(0)
    val title = if (awaitingFinalAnswer) {
        if (seconds > 0) "正在思考（用时 ${seconds} 秒）" else "正在思考"
    } else if (seconds > 0) {
        "已思考（用时 ${seconds} 秒）"
    } else {
        "已思考"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val displayText = if (expanded) cleaned else reasoningPreview(cleaned)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(muted.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wrapForDisplay(displayText),
                    color = muted.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else REASONING_COLLAPSED_MAX_LINES,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun PendingReasoningPanel(startedAt: Long, modifier: Modifier = Modifier) {
    var elapsedMs by remember(startedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val seconds = elapsedMs.div(1000).coerceAtLeast(0)
    val title = if (seconds > 0) "正在思考（用时 ${seconds} 秒）" else "正在思考"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = muted,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ChatStatusBar(
    state: ChatUiState,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onLoadModel: (String) -> Unit,
    onOpenModels: () -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenAppMenu: () -> Unit
) {
    val apiActive = state.selectedModelIsCloud || state.apiEnabled || state.restEnabled
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val assistantName = state.assistants.firstOrNull { it.selected }?.name ?: "默认助手"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.Menu, contentDescription = "打开历史", modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { modelMenuExpanded = true },
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaLogoMark(size = 24.dp, cornerRadius = 8.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayModelName(state.selectedModelName) ?: "选择模型",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (state.selectedModelName == null) "$assistantName · 未加载" else "$assistantName · ${"%.1f".format(state.stats.decodeTps)} token/s",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = if (apiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(15.dp)
                    )
                }
            }
            ModelSwitcherDropdown(
                expanded = modelMenuExpanded,
                models = state.localModels,
                selectedModelIsCloud = state.selectedModelIsCloud,
                isGenerating = state.isGenerating,
                reasoningMode = state.reasoningMode,
                onDismiss = { modelMenuExpanded = false },
                onLoadModel = { id ->
                    modelMenuExpanded = false
                    onLoadModel(id)
                },
                onReasoningModeChange = onReasoningModeChange,
                onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                onOpenModels = {
                    modelMenuExpanded = false
                    onOpenModels()
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNewConversation, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "新建聊天", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(38.dp)) {
                    McaLogoMark(size = 28.dp, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun ModelSwitcherDropdown(
    expanded: Boolean,
    models: List<ChatModelChoice>,
    selectedModelIsCloud: Boolean,
    isGenerating: Boolean,
    reasoningMode: ReasoningMode,
    onDismiss: () -> Unit,
    onLoadModel: (String) -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenModels: () -> Unit
) {
    val context = LocalContext.current
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf<InferenceSource?>(null) }
    val localModels = models.filterNot { it.cloud }.take(3)
    val cloudModels = models.filter { it.cloud }.take(3)
    val activeReasoningMode = if (selectedModelIsCloud) ReasoningMode.STANDARD else reasoningMode
    val drawerVisible = (reasoningExpanded && !selectedModelIsCloud) || sourceExpanded != null
    LaunchedEffect(selectedModelIsCloud) {
        if (selectedModelIsCloud) reasoningExpanded = false
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        modifier = Modifier
            .widthIn(min = if (drawerVisible) 380.dp else 238.dp, max = if (drawerVisible) 432.dp else 284.dp)
            .clip(RoundedCornerShape(30.dp))
            .padding(vertical = 10.dp, horizontal = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 0.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.width(238.dp)) {
                Text(
                    text = "推理来源",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.LOCAL,
                    selected = !selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.LOCAL,
                    count = localModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.LOCAL) null else InferenceSource.LOCAL
                    }
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.CLOUD,
                    selected = selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.CLOUD,
                    count = cloudModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.CLOUD) null else InferenceSource.CLOUD
                    }
                )
                if (localModels.isEmpty() && cloudModels.isEmpty()) {
                    Text(
                        text = "暂无可用推理模型",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isGenerating) {
                    Text(
                        text = "生成中请先停止，再切换模型。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                )
                CapsuleMenuRow(
                    leading = {
                        ThinkingModeMark(
                            mode = activeReasoningMode,
                            selected = activeReasoningMode != ReasoningMode.OFF,
                            modifier = Modifier.size(34.dp)
                        )
                    },
                    onClick = {
                        sourceExpanded = null
                        if (selectedModelIsCloud) {
                            reasoningExpanded = false
                            Toast.makeText(context, CLOUD_REASONING_LOCKED_TIP, Toast.LENGTH_SHORT).show()
                            onCloudReasoningModeLocked()
                        } else {
                            reasoningExpanded = !reasoningExpanded
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "思考模式",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (selectedModelIsCloud) "默认开启" else reasoningMode.shortLabel(),
                            color = if (activeReasoningMode == ReasoningMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold
                        )
                        if (!selectedModelIsCloud) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .rotate(if (reasoningExpanded) -90f else 0f)
                                    .size(18.dp)
                            )
                        }
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = onOpenModels
                ) {
                    Text(
                        text = "更多模型",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            AnimatedVisibility(
                visible = sourceExpanded != null,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ModelSourceInlineCapsule(
                    source = sourceExpanded ?: InferenceSource.LOCAL,
                    models = if (sourceExpanded == InferenceSource.CLOUD) cloudModels else localModels,
                    isGenerating = isGenerating,
                    onLoadModel = { id ->
                        onLoadModel(id)
                        sourceExpanded = null
                    },
                    onOpenModels = onOpenModels,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 12.dp, top = 48.dp)
                        .width(176.dp)
                )
            }
            AnimatedVisibility(
                visible = reasoningExpanded && !selectedModelIsCloud,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ReasoningModeInlineCapsule(
                    selected = reasoningMode,
                    onSelect = { mode ->
                        onReasoningModeChange(mode)
                        reasoningExpanded = false
                    },
                    modifier = Modifier
                        .padding(start = 4.dp, end = 12.dp, top = 48.dp)
                        .width(132.dp)
                )
            }
        }
    }
}

private enum class InferenceSource(
    val title: String,
    val subtitle: String
) {
    LOCAL("本地推理", "设备端模型"),
    CLOUD("云端推理", "API 推理引擎")
}

@Composable
private fun InferenceSourceMenuRow(
    source: InferenceSource,
    selected: Boolean,
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val selectedColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    CapsuleMenuRow(
        leading = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            } else if (source == InferenceSource.CLOUD) {
                Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = selectedColor, modifier = Modifier.size(21.dp))
            } else {
                McaLogoMark(size = 22.dp, cornerRadius = 7.dp)
            }
        },
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (selected) add("当前")
                        add("${count.coerceAtMost(3)} 个常用模型")
                        add(source.subtitle)
                    }.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                    color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(if (expanded) -90f else 0f)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun ModelSourceInlineCapsule(
    source: InferenceSource,
    models: List<ChatModelChoice>,
    isGenerating: Boolean,
    onLoadModel: (String) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = source.title,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            if (models.isEmpty()) {
                Text(
                    text = if (source == InferenceSource.CLOUD) "暂无云端模型" else "暂无本地模型",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6)
                )
                ReasoningModePill(
                    label = "模型管理",
                    selected = false,
                    onClick = onOpenModels
                )
            } else {
                models.take(3).forEach { model ->
                    ModelChoicePill(
                        model = model,
                        enabled = !isGenerating || model.loaded,
                        onClick = {
                            if (!model.loaded) onLoadModel(model.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelChoicePill(
    model: ChatModelChoice,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (model.loaded) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 16.sp),
                    fontWeight = if (model.loaded) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (model.loaded) add("当前")
                        if (model.cloud) {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                        } else {
                            model.quant?.let(::add)
                            formatModelBytes(model.sizeBytes)?.let(::add)
                        }
                    }.joinToString(" · ").ifBlank { if (model.cloud) "云端模型" else "本地 GGUF" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    fontWeight = if (model.loaded) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ThinkingModeMark(
    mode: ReasoningMode,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = modifier,
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4)
        },
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            if (mode == ReasoningMode.ADVANCED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(5.dp)
                        .background(selectedColor, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ReasoningModeInlineCapsule(
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "思考模式",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            ReasoningMode.entries.forEach { mode ->
                ReasoningModePill(
                    label = mode.shortLabel(),
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

@Composable
private fun CapsuleMenuRow(
    leading: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(38.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            leading()
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun ReasoningModeCapsuleDrawer(
    visible: Boolean,
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(220), initialOffsetX = { it / 2 }) + fadeIn(tween(140)),
        exit = slideOutHorizontally(animationSpec = tween(180), targetOffsetX = { it / 2 }) + fadeOut(tween(120)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 82.dp, end = 18.dp)
                    .width(152.dp)
                    .clickable(enabled = false) {},
                color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "思考模式",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                        fontWeight = FontWeight.Medium
                    )
                    ReasoningMode.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.shortLabel(),
                            selected = selected == mode,
                            onClick = { onSelect(mode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

private fun ReasoningMode.shortLabel(): String = when (this) {
    ReasoningMode.OFF -> "关闭"
    ReasoningMode.STANDARD -> "标准"
    ReasoningMode.ADVANCED -> "进阶"
}

@Composable
private fun ChatHistoryDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenAppMenu: () -> Unit,
    onOpenImages: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clearAllRequested by remember { mutableStateOf(false) }
    val filteredHistory = remember(state.history, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            state.history
        } else {
            state.history.filter { item ->
                item.title.contains(keyword, ignoreCase = true) ||
                    item.updatedAtText.contains(keyword, ignoreCase = true)
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxSize(),
        drawerContainerColor = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .edgeSwipeBack(onClose)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MCA",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "搜索", modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(34.dp)) {
                                McaLogoMark(size = 25.dp, cornerRadius = 9.dp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                AppFeatureButton(icon = { Icon(Icons.Default.Image, null) }, text = "图片", onClick = onOpenImages)
                Spacer(modifier = Modifier.height(34.dp))

                Text(
                    "最近",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 92.dp)
                ) {
                    if (state.history.isEmpty()) {
                        item { HistoryEmptyState("暂无历史记录。发送第一条消息后会自动保存在这里。") }
                    } else if (filteredHistory.isEmpty()) {
                        item { HistoryEmptyState("没有找到匹配的对话。") }
                    } else {
                        historySections(filteredHistory, query).forEach { section ->
                            item(key = "section-${section.title}") {
                                HistorySectionHeader(section.title)
                            }
                            items(section.items, key = { it.id }) { item ->
                                HistoryRow(
                                    item = item,
                                    onClick = { onSelectConversation(item.id) },
                                    onRename = {
                                        renameTarget = item
                                        renameText = item.title
                                    },
                                    onTogglePin = { onTogglePinConversation(item.id) },
                                    onDelete = { deleteTarget = item }
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .height(44.dp)
                    .clickable(onClick = onNewConversation),
                color = MaterialTheme.colorScheme.onBackground,
                shape = CircleShape,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "聊天",
                        color = MaterialTheme.colorScheme.background,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除“${item.title}”吗？此操作不会删除模型文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(item.id)
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("对话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameConversation(item.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (clearAllRequested) {
        AlertDialog(
            onDismissRequest = { clearAllRequested = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        clearAllRequested = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllRequested = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val triggerPx = with(density) { 42.dp.toPx() }
    return pointerInput(onBack, triggerPx) {
        var startedAtEdge = false
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x >= 0f
                totalDrag = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                if (startedAtEdge) {
                    totalDrag += dragAmount
                    if (dragAmount < 0f) change.consume()
                }
            },
            onDragEnd = {
                if (startedAtEdge && totalDrag < -triggerPx) onBack()
                startedAtEdge = false
                totalDrag = 0f
            },
            onDragCancel = {
                startedAtEdge = false
                totalDrag = 0f
            }
        )
    }
}

@Composable
private fun AppFeatureButton(icon: @Composable () -> Unit, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(29.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HistorySectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp, start = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun HistoryEmptyState(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(
    item: ChatHistoryItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        color = if (item.selected) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = if (item.selected) 5.dp else 0.dp,
        border = null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "置顶",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    "${item.updatedAtText} · ${item.messageCount} 条消息",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(216.dp),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        HistoryMenuItem(
                            text = "重命名",
                            icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        HistoryMenuItem(
                            text = if (item.pinned) "取消置顶" else "置顶",
                            icon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                        HistoryMenuItem(
                            text = "删除",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            contentColor = MaterialTheme.colorScheme.error,
                            iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    contentColor: Color? = null,
    iconContainerColor: Color? = null,
    onClick: () -> Unit
) {
    val itemContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val itemIconContainerColor = iconContainerColor ?: MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            color = itemIconContainerColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text,
            color = itemContentColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class HistorySection(
    val title: String,
    val items: List<ChatHistoryItem>
)

private fun historySections(items: List<ChatHistoryItem>, query: String): List<HistorySection> {
    if (query.trim().isNotEmpty()) {
        return listOf(HistorySection("搜索结果", items))
    }
    val order = listOf("置顶", "今天", "昨天", "7 天内", "更早")
    return items
        .groupBy { historySectionTitle(it) }
        .map { (title, sectionItems) -> HistorySection(title, sectionItems) }
        .sortedBy { section -> order.indexOf(section.title).let { if (it < 0) Int.MAX_VALUE else it } }
}

private fun historySectionTitle(item: ChatHistoryItem): String {
    if (item.pinned) return "置顶"
    val now = Calendar.getInstance()
    val updated = Calendar.getInstance().apply { timeInMillis = item.updatedAtMillis }
    if (sameDay(now, updated)) return "今天"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, updated)) return "昨天"

    val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    return if (updated.after(sevenDaysAgo)) "7 天内" else "更早"
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

@Composable
private fun EmptyChatPanel(modelName: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MCA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                displayModelName(modelName) ?: "请先在模型页加载一个模型。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun displayModelName(value: String?): String? =
    value?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.removeModelSuffix()

private fun formatModelBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / 1024.0 / 1024.0)
    }
}

private fun String.removeModelSuffix(): String =
    removeSuffix(".gguf")
        .removeSuffix(".GGUF")
        .removeSuffix("-GGUF")
        .removeSuffix("_GGUF")

private fun extractAttachmentName(input: String): String? =
    ATTACHMENT_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

private fun displayInputWithoutAttachment(input: String): String {
    val marker = firstAttachmentMarker(input)
    if (marker < 0) return input
    return input.substring(0, marker).trimEnd()
}

private fun saveCameraPreview(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "mca_camera").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    return Uri.fromFile(file)
}

private fun removeAttachmentFromInput(input: String): String =
    displayInputWithoutAttachment(input).trimEnd()

private fun mergeInputWithExistingAttachment(currentInput: String, visibleText: String): String {
    val marker = firstAttachmentMarker(currentInput)
    if (marker < 0) return visibleText
    val attachment = currentInput.substring(marker).trimStart()
    return buildString {
        append(visibleText)
        if (visibleText.isNotBlank()) append("\n\n")
        append(attachment)
    }
}

private val ATTACHMENT_PREFIXES = listOf("【上传文件：", "【上传图片：")

private fun firstAttachmentMarker(input: String): Int =
    ATTACHMENT_PREFIXES
        .map { input.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

private val ATTACHMENT_NAME_PATTERNS = listOf(
    Regex("""【上传文件：([^】]+)】"""),
    Regex("""【上传图片：([^】]+)】""")
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatInputBar(
    input: String,
    isGenerating: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFileLibrary: () -> Unit,
    reasoningMode: ReasoningMode,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchStatusMessage: String?,
    webSearchTurnModeLabel: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    onToggleWebSearchForTurn: () -> Unit,
    onCycleWebSearchResearchMode: () -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showActionSheet by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else GeminiInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else GeminiInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else GeminiInputPlaceholder
    if (showActionSheet) {
        CompactInputActionMenu(
            onDismiss = { showActionSheet = false },
            onCamera = {
                showActionSheet = false
                onOpenCamera()
            },
            onPhoto = {
                showActionSheet = false
                onOpenPhoto()
            },
            onFile = {
                showActionSheet = false
                onOpenFile()
            },
            onLibrary = {
                showActionSheet = false
                onOpenFileLibrary()
            },
            onWebSearch = {
                showActionSheet = false
                onToggleWebSearchForTurn()
            },
            onResearchMode = {
                showActionSheet = false
                onCycleWebSearchResearchMode()
            },
            onOpenWebSearchSettings = {
                showActionSheet = false
                onOpenWebSearchSettings()
            },
            webSearchEnabled = webSearchEnabled,
            webSearchConfigured = webSearchConfigured,
            webSearchEnabledForTurn = webSearchEnabledForTurn,
            webSearchTurnModeLabel = webSearchTurnModeLabel,
            webSearchResearchModeLabel = webSearchResearchModeLabel,
            webSearchResearchOverridden = webSearchResearchOverridden,
            webSearchProviderLabel = webSearchProviderLabel,
            modifier = modifier
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = inputShellColor,
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .padding(7.dp)
                .navigationBarsPadding()
        ) {
            if (!webSearchStatusMessage.isNullOrBlank()) {
                WebSearchStatusChip(
                    message = webSearchStatusMessage,
                    active = webSearchEnabledForTurn,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            AttachmentPreview(
                input = input,
                onRemove = { onInputChange(removeAttachmentFromInput(input)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) MaterialTheme.colorScheme.surfaceVariant else inputIconSurfaceColor)
                        .clickable(enabled = !isGenerating) { showActionSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "更多操作", modifier = Modifier.size(24.dp), tint = if (isGenerating) inputPlaceholderColor else GeminiPrimaryBlue)
                }
                Spacer(modifier = Modifier.width(10.dp))
                val visibleInput = displayInputWithoutAttachment(input)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    color = inputFieldColor,
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (visibleInput.isBlank()) {
                            Text(
                                "问问 MCA",
                                color = inputPlaceholderColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
                            )
                        }
                        BasicTextField(
                            value = visibleInput,
                            onValueChange = { visibleText ->
                                onInputChange(mergeInputWithExistingAttachment(input, visibleText))
                            },
                            enabled = !isGenerating,
                            maxLines = 4,
                            textStyle = TextStyle(
                                color = inputTextColor,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(GeminiPrimaryBlue),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = {
                    if (isGenerating) onStop() else onSend()
                },
                    containerColor = GeminiPrimaryBlue,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isGenerating) "停止" else "发送",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchStatusChip(
    message: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (active && !darkTheme) Color(0xFFEAF1FF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) GeminiPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactInputActionMenu(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onLibrary: () -> Unit,
    onWebSearch: () -> Unit,
    onResearchMode: () -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchTurnModeLabel: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onDismiss)
        )
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = tween(180),
                initialOffsetX = { -it / 5 }
            ),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(bottom = 86.dp)
                .width(238.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "相机",
                        onClick = onCamera
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "照片",
                        onClick = onPhoto
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "文件",
                        onClick = onFile
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "从文件库添加",
                        onClick = onLibrary
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = when {
                            !webSearchEnabled -> "联网检索：去启用"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "联网检索：协议自检"
                            !webSearchConfigured -> "联网检索：网页直读"
                            webSearchTurnModeLabel.isNotBlank() -> "联网检索：$webSearchTurnModeLabel"
                            webSearchProviderLabel.contains("智能") -> "联网检索：智能判断"
                            webSearchProviderLabel.contains("始终") -> "联网检索：始终开启"
                            webSearchEnabledForTurn -> "联网检索：本轮开启"
                            else -> "联网检索：手动开启"
                        },
                        subtitle = when {
                            !webSearchEnabled -> "设置中开启"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "可直读链接 · 关键词搜索未接入"
                            !webSearchConfigured -> "可读取链接 · 搜索未配置"
                            else -> webSearchProviderLabel.takeIf { it.isNotBlank() }
                        },
                        onClick = onWebSearch
                    )
                    if (!webSearchConfigured) {
                        CompactInputActionRow(
                            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = if (webSearchEnabled) "配置真实搜索源" else "打开联网检索设置",
                            subtitle = if (webSearchEnabled) {
                                "接入 SearxNG / Brave / Tavily / Jina"
                            } else {
                                "启用后可直读链接并接入搜索服务"
                            },
                            onClick = onOpenWebSearchSettings
                        )
                    }
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "研究模式：${webSearchResearchModeLabel.ifBlank { "自动" }}",
                        subtitle = when {
                            !webSearchEnabled -> "先启用联网检索"
                            webSearchResearchOverridden -> "仅影响下一轮发送"
                            else -> "跟随联网检索默认设置"
                        },
                        onClick = onResearchMode
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionRow(
    icon: @Composable () -> Unit,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class FileLibraryFilter(val label: String) {
    ALL("全部"),
    TEXT("文本"),
    CODE("代码"),
    DATA("数据")
}

@Composable
private fun FileLibraryPage(
    files: List<FileAssetUiItem>,
    onInsert: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FileLibraryFilter.ALL) }
    val darkTheme = isSystemInDarkTheme()
    val libraryInputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputField
    val filtered = remember(files, query, filter) {
        files.filter { file ->
            val queryMatched = query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                file.preview.contains(query, ignoreCase = true)
            queryMatched && file.matchesFilter(filter)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("文件库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${files.size} 个本机文件索引",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "关闭文件库")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件名或内容预览") },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = libraryInputFieldColor,
                    unfocusedContainerColor = libraryInputFieldColor,
                    focusedBorderColor = GeminiPrimaryBlue.copy(alpha = 0.42f),
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FileLibraryFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (files.isEmpty()) "文件库为空" else "没有匹配文件",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (files.isEmpty()) "从输入框上传文本、Markdown、JSON、代码文件后，会自动出现在这里。"
                            else "换个关键词或切换筛选类型试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { file ->
                        FileLibraryRow(
                            file = file,
                            onInsert = { onInsert(file.id) },
                            onDelete = { onDelete(file.id) }
                        )
                    }
                }
            }
        }
    }
@Composable
private fun FileLibraryRow(
    file: FileAssetUiItem,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else GeminiInputIconSurface,
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = GeminiPrimaryBlue
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${file.sizeText} · ${file.createdAtText}${if (file.truncated) " · 已截取" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                file.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
                Button(onClick = onInsert) {
                    Text("插入当前聊天")
                }
            }
        }
    }
}

private fun FileAssetUiItem.matchesFilter(filter: FileLibraryFilter): Boolean {
    if (filter == FileLibraryFilter.ALL) return true
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    return when (filter) {
        FileLibraryFilter.ALL -> true
        FileLibraryFilter.TEXT -> lowerMime.startsWith("text/") &&
            !lowerName.endsWith(".kt") &&
            !lowerName.endsWith(".java") &&
            !lowerName.endsWith(".py") &&
            !lowerName.endsWith(".js") &&
            !lowerName.endsWith(".ts")
        FileLibraryFilter.CODE -> lowerMime.contains("code") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
        FileLibraryFilter.DATA -> lowerMime.contains("json") ||
            lowerMime.contains("xml") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv")
    }
}

private fun ChatUiState.imageTaskSummary(): String {
    val running = imageJobs.firstOrNull { job ->
        !job.failed && job.imageAssetId == null && (job.statusLabel == "排队" || job.statusLabel == "生成中")
    }
    if (running != null) {
        return "${running.statusLabel} · ${running.prompt.take(18)}"
    }
    val failedJob = imageJobs.firstOrNull { it.failed }
    if (failedJob != null) {
        return "失败，可进入图片页重试"
    }
    val completed = imageJobs.firstOrNull { it.imageAssetId != null || it.statusLabel == "完成" }
    return if (completed != null) "最近完成 · 可查看图片库" else "空闲 · 可进入图片页"
}

@Composable
private fun AttachmentPreview(input: String, onRemove: () -> Unit) {
    val name = remember(input) { extractAttachmentName(input) } ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "文件",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showAssistantActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isUser = message.role == Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(message)
        } else {
            AssistantMessageBlock(
                modifier = Modifier.fillMaxWidth(),
                message = message,
                showActions = showAssistantActions,
                canRegenerate = canRegenerate,
                isGenerating = isGenerating,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("MCA", message.content))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun UserMessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    Surface(
        color = Color(0xFFEEF4FF),
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 20.dp,
            bottomEnd = 6.dp
        ),
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (message.imageAttachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    message.imageAttachments.forEach { attachment ->
                        val bitmap = remember(attachment.uriString) {
                            loadImageBitmap(context, attachment.uriString)
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(104.dp)
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = attachment.name.ifBlank { "上传图片" },
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        tint = GeminiPrimaryBlue.copy(alpha = 0.72f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (message.content.isNotBlank()) {
                Text(
                    text = wrapForDisplay(message.content),
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(
    modifier: Modifier = Modifier,
    message: ChatMessage,
    showActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (message.reasoningContent.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    AnimatedMcaAssistantMark(
                        isGenerating = isGenerating,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    ReasoningPanel(
                        content = message.reasoningContent,
                        durationMs = message.reasoningDurationMs,
                        awaitingFinalAnswer = isGenerating && message.content.isBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (message.content.isBlank() && message.reasoningContent.isBlank()) {
                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        AnimatedMcaAssistantMark(
                            isGenerating = true,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        PendingReasoningPanel(
                            startedAt = message.createdAt,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else if (message.content.isNotBlank()) {
                SelectionContainer {
                    AssistantRichText(message.content)
                }
            }
            if (message.sourceReferences.isNotEmpty() || message.webSearchTrace?.hasContent == true) {
                WebSearchSourcesRow(
                    sources = message.sourceReferences,
                    trace = message.webSearchTrace
                )
            }
            if (showActions) {
                AssistantActionRow(
                    canRegenerate = canRegenerate,
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onCopy = onCopy
                )
            }
        }
    }
}

@Composable
private fun WebSearchSourcesRow(
    sources: List<ChatSourceReference>,
    trace: ChatWebSearchTrace?
) {
    val context = LocalContext.current
    var selectedUrl by remember(sources) { mutableStateOf<String?>(null) }
    val selectedSource = sources.firstOrNull { it.url == selectedUrl }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trace?.hasContent == true) {
            WebSearchTraceCard(trace = trace)
        }
        if (sources.isEmpty()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = GeminiPrimaryBlue
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                sources.webSearchSourceSummaryLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources.take(8)) { source ->
                val trustLabel = source.displayTrustLabel()
                val hostLabel = source.displayHostLabel()
                val selected = source.url == selectedUrl
                Surface(
                    modifier = Modifier
                        .widthIn(min = 188.dp, max = 256.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            selectedUrl = if (selected) null else source.url
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(18.dp),
                    border = if (selected) BorderStroke(1.dp, GeminiPrimaryBlue.copy(alpha = 0.46f)) else null
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = source.webSearchTrustColor().copy(alpha = 0.14f),
                                shape = CircleShape
                            ) {
                                Text(
                                    trustLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = source.webSearchTrustColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                hostLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            source.title.ifBlank { source.url },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (source.snippet.isNotBlank()) {
                            Text(
                                source.snippet,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val providerLine = listOf(source.provider, source.displayTrustReason())
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · ")
                        if (providerLine.isNotBlank()) {
                            Text(
                                providerLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selectedSource != null,
            enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
            exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
        ) {
            selectedSource?.let { source ->
                WebSearchSourceDetailCard(
                    source = source,
                    onOpen = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                        }
                    },
                    onClose = { selectedUrl = null }
                )
            }
        }
    }
}

@Composable
private fun WebSearchTraceCard(trace: ChatWebSearchTrace) {
    var expanded by rememberSaveable(trace.query, trace.message, trace.elapsedMs, trace.running) { mutableStateOf(false) }
    val statusColor = trace.webSearchTraceColor()
    val runningRotation = if (trace.running) {
        val transition = rememberInfiniteTransition(label = "web-search-running")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "web-search-running-angle"
        )
        angle
    } else {
        0f
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    color = statusColor.copy(alpha = 0.13f),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(runningRotation),
                            tint = statusColor
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (trace.running) trace.stageLabel.ifBlank { "正在检索" } else "检索过程",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        trace.summaryLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起检索过程" else "展开检索过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trace.running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.10f)
                )
            }
            if (trace.message.isNotBlank()) {
                Text(
                    trace.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebSearchTraceSection(
                        title = "检索目标",
                        values = (trace.searchedQueries + trace.directUrls)
                            .distinct()
                            .take(6)
                    )
                    WebSearchTraceSection(
                        title = "触发依据",
                        values = trace.triggerReasons.take(4)
                    )
                    WebSearchTraceSection(
                        title = "证据分组",
                        values = trace.evidenceGroups.take(5)
                    )
                    WebSearchTraceSection(
                        title = "不确定性",
                        values = (trace.conflictWarnings + trace.warnings)
                            .distinct()
                            .take(5),
                        error = true
                    )
                    WebSearchTraceSection(
                        title = "闭环检查",
                        values = trace.closedLoopChecks.take(6)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchTraceSection(
    title: String,
    values: List<String>,
    error: Boolean = false
) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        values.forEach { value ->
            Text(
                "• $value",
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatWebSearchTrace.webSearchTraceColor(): Color =
    when {
        running -> GeminiPrimaryBlue
        success && qualityScore >= 72 -> GeminiPrimaryBlue
        success -> Color(0xFF0F9D58)
        warnings.isNotEmpty() || conflictWarnings.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun ChatWebSearchTrace.summaryLine(): String {
    val targetCount = (searchedQueries.size + directUrls.size).coerceAtLeast(sourceCount)
    return buildList {
        if (providerLabel.isNotBlank()) add(providerLabel)
        if (targetCount > 0) add(if (running) "计划 ${targetCount} 个目标" else "${targetCount} 个目标")
        if (sourceCount > 0) add("${sourceCount} 个来源")
        if (elapsedMs > 0) add(formatWebSearchElapsed(elapsedMs))
        if (qualityLabel.isNotBlank()) add("质量 $qualityLabel ${qualityScore}/100")
        if (researchConfidenceLabel.isNotBlank()) add("置信度 $researchConfidenceLabel ${researchConfidenceScore}/100")
    }.ifEmpty {
        listOf(message.ifBlank { query.ifBlank { "已记录本轮联网检索" } })
    }.joinToString(" · ")
}

private fun formatWebSearchElapsed(elapsedMs: Long): String =
    if (elapsedMs >= 1000L) {
        "${"%.1f".format(elapsedMs / 1000.0)}s"
    } else {
        "${elapsedMs}ms"
    }

@Composable
private fun WebSearchSourceDetailCard(
    source: ChatSourceReference,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = source.webSearchTrustColor().copy(alpha = 0.14f),
                    shape = CircleShape
                ) {
                    Text(
                        source.displayTrustLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = source.webSearchTrustColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    source.displayHostLabel(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起来源详情",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                source.title.ifBlank { source.url },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val reason = source.displayTrustReason()
            if (reason.isNotBlank() || source.provider.isNotBlank()) {
                Text(
                    listOf(source.provider, reason)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (source.snippet.isNotBlank()) {
                Text(
                    source.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                source.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA 来源链接", source.url))
                            )
                            Toast.makeText(context, "已复制来源链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("复制链接")
                }
                Button(onClick = onOpen) {
                    Text("打开网页")
                }
            }
        }
    }
}

private fun List<ChatSourceReference>.webSearchSourceSummaryLabel(): String {
    if (isEmpty()) return "联网来源"
    val summary = map { it.displayTrustLabel() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(2)
        .joinToString(" · ") { "${it.key} ${it.value}" }
    return buildString {
        append("联网来源")
        append(" · ")
        append(size)
        append(" 个")
        if (summary.isNotBlank()) {
            append(" · ")
            append(summary)
        }
    }
}

private fun ChatSourceReference.displayHostLabel(): String =
    hostLabel.ifBlank {
        runCatching { Uri.parse(url).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore("/") }
    }

private fun ChatSourceReference.displayTrustLabel(): String =
    trustLabel.ifBlank {
        val host = displayHostLabel().lowercase()
        when {
            provider == "安全拦截" -> "安全拦截"
            host == "github.com" || host.endsWith(".github.com") -> "代码仓库"
            host == "huggingface.co" || host == "modelscope.cn" -> "模型社区"
            host == "arxiv.org" || host.endsWith(".arxiv.org") -> "学术论文"
            host.contains("docs") || url.contains("/docs", ignoreCase = true) || url.contains("/developer", ignoreCase = true) -> "开发者文档"
            host.contains("reddit") || host.contains("stackoverflow") || host.contains("zhihu") -> "社区讨论"
            else -> "普通网页"
        }
    }

private fun ChatSourceReference.displayTrustReason(): String =
    trustReason.ifBlank {
        when (displayTrustLabel()) {
            "安全拦截" -> "受限地址未读取"
            "代码仓库" -> "开发者仓库"
            "模型社区" -> "模型托管与下载站点"
            "学术论文" -> "论文或预印本来源"
            "开发者文档" -> "文档、指南或开发者资料"
            "社区讨论" -> "论坛、问答或社交讨论"
            else -> ""
        }
    }

@Composable
private fun ChatSourceReference.webSearchTrustColor(): Color =
    when (displayTrustLabel()) {
        "官方/一手", "开发者文档", "模型社区", "代码仓库" -> GeminiPrimaryBlue
        "学术论文" -> Color(0xFF5E6AD2)
        "社区讨论", "媒体报道" -> Color(0xFF0F9D58)
        "安全拦截" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun AssistantActionRow(
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onRegenerate,
            enabled = canRegenerate,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Replay,
                contentDescription = "重新生成",
                modifier = Modifier.size(17.dp),
                tint = tint.copy(alpha = if (canRegenerate) 0.78f else 0.32f)
            )
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制消息",
                modifier = Modifier.size(17.dp),
                tint = tint.copy(alpha = 0.78f)
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多消息操作",
                    modifier = Modifier.size(18.dp),
                    tint = tint.copy(alpha = 0.78f)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("复制回答") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                    enabled = canRegenerate,
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除本条", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AssistantRichText(content: String) {
    val displayContent = remember(content) { cleanAssistantContentForDisplay(content) }
    val blocks = remember(displayContent) { parseMarkdownBlocks(displayContent) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
                is MarkdownBlock.Heading -> HeadingBlock(block.text, block.level)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.text)
                is MarkdownBlock.BulletList -> ListBlock(block.items, ordered = false)
                is MarkdownBlock.NumberedList -> ListBlock(block.items, ordered = true)
                is MarkdownBlock.Quote -> QuoteBlock(block.text)
                is MarkdownBlock.Table -> TableBlock(block.rows)
                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(text: String, level: Int) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = if (level <= 2) {
            MaterialTheme.typography.titleMedium.copy(lineHeight = 23.sp)
        } else {
            MaterialTheme.typography.titleSmall.copy(lineHeight = 21.sp)
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        softWrap = true
    )
}

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp
        ),
        modifier = Modifier.fillMaxWidth(),
        softWrap = true
    )
}

@Composable
private fun ListBlock(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = if (ordered) 22.dp else 12.dp)
                )
                Text(
                    text = inlineMarkdownText(item),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier.weight(1f),
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
        )
        Text(
            text = inlineMarkdownText(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language?.ifBlank { null } ?: "代码",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA code", code.trimEnd()))
                            )
                            copied = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制代码" else "复制代码",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text(
                text = code.trimEnd(),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.74f)),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.background(
                        if (rowIndex == 0) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
                        } else {
                            Color.Transparent
                        }
                    )
                ) {
                    row.forEach { cell ->
                        Text(
                            text = inlineMarkdownText(cell),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .widthIn(min = 92.dp, max = 190.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            softWrap = true
                        )
                    }
                }
                if (rowIndex != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f))
                }
            }
        }
    }
}

@Composable
private fun inlineMarkdownText(value: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(value, codeColor, codeBackground, linkColor) {
        buildInlineMarkdown(value, codeColor, codeBackground, linkColor)
    }
}

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val text: String, val level: Int) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<String>) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(val language: String?, val code: String) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    var listOrdered = false
    val codeBuffer = StringBuilder()
    val plainCodeLines = mutableListOf<String>()
    val tableLines = mutableListOf<String>()
    var inCode = false
    var codeLanguage: String? = null

    fun flushParagraph() {
        val value = paragraph.joinToString(" ").trim()
        if (value.isNotBlank()) {
            splitReadableParagraphs(value).forEach { blocks += MarkdownBlock.Paragraph(it) }
        }
        paragraph.clear()
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += if (listOrdered) {
                MarkdownBlock.NumberedList(listItems.toList())
            } else {
                MarkdownBlock.BulletList(listItems.toList())
            }
        }
        listItems.clear()
    }

    fun flushPlainCode() {
        if (plainCodeLines.isEmpty()) return
        val nonBlank = plainCodeLines.filter { it.isNotBlank() }
        val shouldRenderAsCode = nonBlank.size >= 2 || nonBlank.any { it.isStrongCodeLine() }
        if (shouldRenderAsCode) {
            blocks += MarkdownBlock.Code(
                language = detectCodeLanguage(nonBlank),
                code = plainCodeLines.joinToString("\n")
            )
        } else {
            paragraph += nonBlank.joinToString(" ").trim()
        }
        plainCodeLines.clear()
    }

    fun flushTable() {
        if (tableLines.isEmpty()) return
        val rows = tableLines
            .filterNot { it.isTableSeparatorLine() }
            .map { it.toTableCells() }
            .filter { it.isNotEmpty() }
        if (rows.isNotEmpty()) {
            blocks += MarkdownBlock.Table(rows)
        }
        tableLines.clear()
    }

    fun flushCode() {
        blocks += MarkdownBlock.Code(codeLanguage, repairCodeText(codeBuffer.toString(), codeLanguage))
        codeBuffer.clear()
        codeLanguage = null
    }

    normalizeChatText(content).lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                flushList()
                inCode = true
                codeLanguage = line.trim().removePrefix("```").trim().ifBlank { null }
            }
        } else if (inCode) {
            codeBuffer.appendLine(line)
        } else if (line.isTableLine()) {
            flushPlainCode()
            flushParagraph()
            flushList()
            tableLines += line
        } else if (line.isBlank()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
        } else if (line.isHorizontalRule()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Divider
        } else if (line.isLikelyCodeLine(plainCodeLines.isNotEmpty())) {
            flushTable()
            flushParagraph()
            flushList()
            plainCodeLines += line
        } else if (line.trimStart().startsWith("#")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            val trimmed = line.trimStart()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MarkdownBlock.Heading(trimmed.drop(level).trim(), level)
        } else if (line.trimStart().startsWith(">")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Quote(line.trimStart().removePrefix(">").trim())
        } else if (line.isBulletLine()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            val ordered = line.isNumberedLine()
            if (listItems.isNotEmpty() && listOrdered != ordered) flushList()
            listOrdered = ordered
            listItems += stripListMarker(line)
        } else {
            flushTable()
            flushPlainCode()
            flushList()
            paragraph += line.trim()
        }
    }
    if (inCode) flushCode()
    flushTable()
    flushPlainCode()
    flushParagraph()
    flushList()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content.trim())) }
}

private fun normalizeChatText(content: String): String =
    content
        .repairInlineCodeFences()
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("""\s+(?=\d{1,2}[.)]\s+)"""), "\n")
        .replace(Regex("""\s+(?=[-*]\s+)"""), "\n")

private fun String.repairInlineCodeFences(): String {
    val withFenceBreaks = replace(Regex("""([^\n])\s*```"""), "$1\n```")
    return CODE_FENCE_LANGUAGE_WITH_CODE_PATTERN.replace(withFenceBreaks) { match ->
        "```${match.groupValues[1]}\n"
    }
}

private fun repairCodeText(value: String, language: String?): String {
    val normalizedLanguage = language?.lowercase().orEmpty()
    if (value.lineSequence().count() > 1) return value
    if (normalizedLanguage !in setOf("python", "py")) return value
    return value
        .replace(Regex("""(?<=[A-Za-z0-9_)\]])(?=(?:import|from|def|class|if|elif|else|for|while|try|except|finally|return|print)\b)"""), "\n")
        .replace(Regex("""(?<=[A-Za-z0-9_)\]])(?=#)"""), "\n")
        .replace(Regex("""(?<=[0-9)\]])(?=[A-Z_]{2,}\s*=)"""), "\n")
        .trimStart('\n')
}

private fun splitReadableParagraphs(value: String): List<String> {
    if (value.length <= 140) return listOf(value)
    val sentences = Regex("""[^。！？!?；;]+[。！？!?；;]?""")
        .findAll(value)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (sentences.size <= 2) return listOf(value)
    val result = mutableListOf<String>()
    val current = StringBuilder()
    sentences.forEach { sentence ->
        if (current.isNotEmpty() && current.length + sentence.length > 110) {
            result += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

private fun String.isBulletLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.matches(Regex("""\d+[.)]\s+.*"""))
}

private fun String.isNumberedLine(): Boolean =
    trimStart().matches(Regex("""\d+[.)]\s+.*"""))

private fun stripListMarker(value: String): String {
    val trimmed = value.trimStart()
    return trimmed
        .removePrefix("- ")
        .removePrefix("* ")
        .replaceFirst(Regex("""^\d+[.)]\s+"""), "")
}

private fun String.isHorizontalRule(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 3 && trimmed.all { it == '-' || it == '_' || it == '*' || it.isWhitespace() }
}

private fun String.isTableLine(): Boolean {
    val trimmed = trim()
    if (!trimmed.contains("|")) return false
    if (trimmed.count { it == '|' } < 2) return false
    return trimmed.startsWith("|") || trimmed.endsWith("|")
}

private fun String.isTableSeparatorLine(): Boolean {
    val compact = trim().trim('|').replace(" ", "")
    return compact.isNotBlank() && compact.all { it == '-' || it == ':' || it == '|' }
}

private fun String.toTableCells(): List<String> =
    trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun String.isLikelyCodeLine(continuingCodeBlock: Boolean): Boolean {
    val trimmed = trim()
    if (trimmed.isBlank()) return false
    if (startsWith("    ") || startsWith("\t")) return true
    if (continuingCodeBlock && (
            trimmed == "}" ||
                trimmed == "};" ||
                trimmed.startsWith("}") ||
                trimmed.startsWith("else") ||
                trimmed.startsWith("elif") ||
                trimmed.startsWith("except") ||
                trimmed.startsWith("finally") ||
                trimmed.startsWith("catch") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("# ")
            )
    ) {
        return true
    }
    return trimmed.isStrongCodeLine() ||
        CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) ||
        CODE_CALL_PATTERN.containsMatchIn(trimmed)
}

private fun String.isStrongCodeLine(): Boolean {
    val trimmed = trim()
    if (trimmed.length < 2) return false
    if (CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)) return true
    if (CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) && (trimmed.endsWith(":") || trimmed.endsWith("{"))) return true
    if (trimmed.startsWith("#include") || trimmed.startsWith("using namespace")) return true
    if (trimmed.startsWith("<") && trimmed.endsWith(">") && "/" in trimmed) return true
    if (trimmed.endsWith(";") && !trimmed.contains("；")) return true
    if (trimmed == "{" || trimmed == "}" || trimmed == "};") return true
    return CODE_ASSIGNMENT_PATTERN.containsMatchIn(trimmed) && !trimmed.any { it.isCjkLike() }
}

private fun detectCodeLanguage(lines: List<String>): String? {
    val sample = lines.joinToString("\n") { it.trim() }
    val lower = sample.lowercase()
    return when {
        Regex("""(?m)^(def|from|import|elif|except|print)\b""").containsMatchIn(sample) -> "python"
        Regex("""(?m)^(fun|val|var|package|override|private|suspend)\b""").containsMatchIn(sample) -> "kotlin"
        Regex("""(?m)^(const|let|function|export|interface|type)\b""").containsMatchIn(sample) -> "typescript"
        "#include" in sample || "std::" in sample -> "cpp"
        Regex("""(?m)^(public|private|protected)\s+(class|static|void|int|String)\b""").containsMatchIn(sample) -> "java"
        Regex("""(?m)^(select|insert|update|delete|create|alter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sample) -> "sql"
        "<html" in lower || "</" in sample -> "html"
        else -> null
    }
}

private val CODE_DECLARATION_PATTERN = Regex(
    pattern = """^(def|class|fun|function|const|let|var|val|public|private|protected|override|interface|type|struct|enum|async\s+def)\b.*"""
)

private val CODE_KEYWORD_PATTERN = Regex(
    pattern = """^(if|else|elif|for|while|when|switch|case|try|catch|except|finally|with|return|break|continue|await|import|from|package|using|namespace|select|insert|update|delete|create|alter)\b.*""",
    option = RegexOption.IGNORE_CASE
)

private val CODE_CALL_PATTERN = Regex(
    pattern = """^(print|console\.log|System\.out\.println|printf|Log\.[diew]|println)\s*\(.*"""
)

private val CODE_ASSIGNMENT_PATTERN = Regex(
    pattern = """^[A-Za-z_][A-Za-z0-9_.$\[\]]*\s*(=|\+=|-=|\*=|/=|:=)\s*.+"""
)

private fun cleanReasoningForDisplay(value: String): String {
    return value
        .replace("<think>", "", ignoreCase = true)
        .replace("</think>", "", ignoreCase = true)
        .replace("<|think|>", "", ignoreCase = true)
        .replace("<|channel>thought", "", ignoreCase = true)
        .replace("<|channel|>thought", "", ignoreCase = true)
        .replace("<|channel>analysis", "", ignoreCase = true)
        .replace("<|channel|>analysis", "", ignoreCase = true)
        .replace("<channel|>", "", ignoreCase = true)
        .replace("<channel>", "", ignoreCase = true)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun cleanAssistantContentForDisplay(value: String): String =
    value
        .replace(Regex("""(?is)<think>.*?</think>"""), "")
        .replace(Regex("""(?i)(?:\bnull\b\s*){3,}"""), "")
        .replace("\u0000", "")
        .trim()

private fun String.removeEnglishReasoningScaffold(): String {
    val parts = split(Regex("""(?=\s*\*\s+)"""))
    if (parts.size <= 1) return this
    val kept = parts.filterNot { part ->
        val compact = part.trim().lowercase()
        ENGLISH_REASONING_SCAFFOLD_PREFIXES.any { compact.startsWith(it) }
    }
    return kept.joinToString("").ifBlank { this }
}

private fun String.removePromptEchoScaffold(): String {
    val parts = split(REASONING_SCAFFOLD_SEGMENT_SPLIT)
    if (parts.size <= 1) {
        return this
    }
    return parts
        .filterNot { it.isEnglishPromptScaffold() }
        .joinToString("")
        .ifBlank { this }
}

private fun String.isEnglishPromptScaffold(): Boolean {
    val compact = trim()
    if (compact.isBlank()) return false
    val lower = compact.lowercase()
    val hasScaffoldKeyword = ENGLISH_REASONING_SCAFFOLD_KEYWORDS.any { it in lower }
    if (!hasScaffoldKeyword) return false
    val latin = compact.count { it in 'a'..'z' || it in 'A'..'Z' }
    val cjk = compact.count { it.isCjkLike() }
    return latin >= 24 && latin > cjk * 2
}

private fun String.preferChineseReasoningWhenEnglishDominates(): String {
    val compact = trim()
    if (compact.isBlank()) return compact
    val latin = compact.count { it in 'a'..'z' || it in 'A'..'Z' }
    val cjk = compact.count { it.isCjkLike() }
    if (cjk <= 0 || latin <= cjk * 2) return compact

    val chinese = compact
        .split(Regex("""(?<=[。！？.!?])\s+|\n+"""))
        .map { it.trim() }
        .filter { segment ->
            val segmentCjk = segment.count { it.isCjkLike() }
            val segmentLatin = segment.count { it in 'a'..'z' || it in 'A'..'Z' }
            segmentCjk >= 6 && segmentCjk >= segmentLatin
        }
        .joinToString("\n")
        .trim()

    return chinese.ifBlank { compact }
}

private fun reasoningPreview(value: String): String {
    val lines = value
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .take(REASONING_COLLAPSED_MAX_LINES)
        .toList()
    return if (lines.isEmpty()) value.take(REASONING_PREVIEW_MAX_CHARS) else {
        val preview = lines.joinToString("\n")
        if (preview.length <= REASONING_PREVIEW_MAX_CHARS) {
            preview
        } else {
            preview.take(REASONING_PREVIEW_MAX_CHARS).trimEnd()
        }
    }
}

private fun cleanInlineMarkdown(value: String): String =
    value
        .replace("**", "")
        .replace("__", "")

private fun buildInlineMarkdown(
    value: String,
    codeColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var index = 0

    fun appendPlain(text: String) {
        append(wrapForDisplay(text))
    }

    while (index < value.length) {
        when {
            value.startsWith("[", index) -> {
                val match = MARKDOWN_LINK_PATTERN.find(value, index)
                if (match != null && match.range.first == index) {
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(wrapForDisplay(match.groupValues[1]))
                    }
                    index = match.range.last + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            URL_PATTERN.find(value, index)?.range?.first == index -> {
                val match = URL_PATTERN.find(value, index)!!
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(wrapForDisplay(match.value))
                }
                index = match.range.last + 1
            }
            value.startsWith("`", index) -> {
                val end = value.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            background = codeBackground,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append(wrapForDisplay(value.substring(index + 1, end)))
                    }
                    index = end + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("__", index) -> {
                val end = value.indexOf("__", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            else -> {
                val next = listOf(
                    value.indexOf('`', startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("**", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("__", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("[", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    URL_PATTERN.find(value, index)?.range?.first ?: value.length
                ).min()
                appendPlain(value.substring(index, next))
                index = next
            }
        }
    }
}

private val MARKDOWN_LINK_PATTERN = Regex("""\[(.+?)]\((https?://[^)\s]+)\)""")
private val URL_PATTERN = Regex("""https?://[^\s)）]+""")
private val CODE_FENCE_LANGUAGE_WITH_CODE_PATTERN = Regex(
    """```(python|py|kotlin|java|javascript|js|typescript|ts|cpp|c\+\+|c|html|css|sql|json|bash|sh)(?=(?:import|from|def|class|#|//|[A-Za-z_][A-Za-z0-9_]*\s*=))""",
    RegexOption.IGNORE_CASE
)
private const val REASONING_COLLAPSED_MAX_LINES = 4
private const val REASONING_PREVIEW_MAX_CHARS = 520
private val REASONING_LEADING_MARKERS = listOf(
    Regex("""(?is)^\s*here(?:'s| is)\s+a\s+thinking\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?is)^\s*this\s+is\s+(?:my\s+)?(?:thinking|reasoning)\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?im)^\s*thinking\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*thought\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*reasoning\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*analysis\s*\d*\s*[:：]?\s*"""),
    Regex("""(?m)^\s*(思考过程|推理过程|分析过程)\s*\d*\s*[:：.]?\s*""")
)
private val REASONING_NUMBERED_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\d+\.\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val REASONING_BOLD_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val ENGLISH_REASONING_SCAFFOLD_PREFIXES = listOf(
    "* user asks",
    "* role:",
    "* system requirements",
    "* default response language",
    "* style:",
    "* specific thinking pattern",
    "* constraint checklist",
    "* output format",
    "* determine the role",
    "* analyze the request",
    "* context:",
    "* question:"
)

private val ENGLISH_REASONING_SCAFFOLD_KEYWORDS = listOf(
    "the user is asking",
    "user asks",
    "identify core",
    "identity constraints",
    "determine persona",
    "determine the role",
    "formulate the answer",
    "final review",
    "self-correction",
    "system prompt",
    "system instruction",
    "system requirements",
    "default response language",
    "output format",
    "constraint checklist",
    "role:",
    "question:",
    "context:",
    "language:",
    "style:",
    "developer:",
    "nature:",
    "must use",
    "use /think",
    "thinking mode is open"
)

private val REASONING_SCAFFOLD_SEGMENT_SPLIT = Regex(
    pattern = """(?=(?:^|\s)(?:\d+\.\s*)?(?:\*\*)?(?:User|Role|Question|Context|Language|Style|Constraint|Constraints|System|Output|Identify|Determine|Formulate|Final Review|Self-Correction|Developer|Nature|Type)\b)""",
    option = RegexOption.IGNORE_CASE
)

private fun wrapForDisplay(value: String): String {
    val out = StringBuilder(value.length + value.length / 24)
    var runLength = 0
    value.forEach { ch ->
        out.append(ch)
        runLength = if (ch.isWhitespace()) 0 else runLength + 1
        if (ch == '/' || ch == '\\' || ch == '_' || ch == '-' || ch == '.' || ch == '=' || ch == '&' || ch == '?') {
            out.append('\u200B')
            runLength = 0
        } else if (runLength >= 18 && !ch.isCjkLike()) {
            out.append('\u200B')
            runLength = 0
        }
    }
    return out.toString()
}

private fun Char.isCjkLike(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

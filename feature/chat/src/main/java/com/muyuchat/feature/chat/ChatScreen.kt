package com.muyuchat.feature.chat

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
    val images: List<ImageAssetUiItem> = emptyList(),
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
    val reasoningMode: ReasoningMode = ReasoningMode.OFF
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
    onGenerateImagePrompt: (String) -> Unit = {},
    onCancelImageGeneration: () -> Unit = {},
    onSelectImageModel: (String) -> Unit = {},
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit = {},
    onLoadModel: (String) -> Unit = {},
    onOpenAgent: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    appMenuOpen: Boolean = false,
    onAppMenuOpenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showImages by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(enabled = showImages && !historyBackEnabled) {
        showImages = false
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
            if (showImages) {
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
                    onBack = { showImages = false },
                    onOpenPhoto = { photoPicker.launch("image/*") },
                    onSelectImageModel = onSelectImageModel,
                    onUseImageAsset = {
                        onUseImageAsset(it)
                        showImages = false
                    },
                    onDeleteImageAsset = onDeleteImageAsset,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
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
                reasoningMode = state.reasoningMode,
                onReasoningModeChange = onReasoningModeChange,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

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
                    onClearHistory = onClearHistory,
                    modifier = menuModifier
                )
            }
            }

        }
    }
}

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
            .background(Color.White)
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
                color = Color(0xFF202124)
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
                color = Color(0xFF202124)
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
                                .background(Color(0xFFEDEFF1))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = Color.White,
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
            color = Color(0xFF202124)
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
    onUseImageAsset: (String) -> Unit
) {
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
                    color = Color.White,
                    shape = CircleShape,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回图片", modifier = Modifier.size(24.dp))
                        Text("图片", fontSize = 17.sp, color = Color(0xFF202124))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    color = Color.White,
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
                onUseImageAsset = onUseImageAsset
            )
        }
    }
}

@Composable
private fun UserImagePromptBubble(prompt: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 310.dp),
            color = Color(0xFFF1F1F1),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                prompt.ifBlank { "正在准备图片请求" },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                color = Color(0xFF202124),
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
    onUseImageAsset: (String) -> Unit
) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            job?.failed == true -> ImageGenerationFailureCard(job = job, onRetry = onRetry)
            image != null -> ImageGenerationResultImage(image = image, onUseImageAsset = onUseImageAsset)
            else -> ImageCreatingPlaceholder(
                statusText = job?.statusLabel ?: "正在创建图片",
                statusMessage = job?.message.orEmpty(),
                startedAtMillis = job?.startedAtMillis ?: System.currentTimeMillis()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ThumbUp, contentDescription = "喜欢", tint = Color(0xFF5F6368), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ThumbDown, contentDescription = "不喜欢", tint = Color(0xFF5F6368), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = Color(0xFF5F6368), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ImageCreatingPlaceholder(statusText: String, statusMessage: String, startedAtMillis: Long) {
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
        color = Color(0xFFF5F8FF),
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (statusText == "完成") "正在整理图片" else "正在创建图片",
                    color = Color(0xFF31415F),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    statusMessage.ifBlank { "MCA 正在等待图像引擎返回结果" },
                    color = Color(0xFF66748A),
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
                    color = Color.White.copy(alpha = 0.92f),
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(job.prompt, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF202124))
                Text(job.message.ifBlank { "图片生成失败" }, maxLines = 2, color = Color(0xFF5F6368), fontSize = 13.sp)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ImageGenerationResultImage(image: ImageAssetUiItem, onUseImageAsset: (String) -> Unit) {
    val context = LocalContext.current
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
            .background(Color(0xFFEDEFF1))
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
                tint = Color(0xFF9AA0A6),
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
                color = Color(0xFFF4F6F9),
                shape = CircleShape,
                border = BorderStroke(1.dp, Color(0xFFE0E3E7))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF202124))
                    Text(selectedSource.title, fontWeight = FontWeight.Bold, color = Color(0xFF202124))
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF5F6368))
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
                    color = Color.White,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color(0xFFE0E3E7)),
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
                            color = Color(0xFF202124),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF5F6368))
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
            color = Color.White,
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
            color = Color(0xFF858C98),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ImageJobRow(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF4F6F9),
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
                color = if (job.failed) MaterialTheme.colorScheme.error else Color(0xFF1A73E8),
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.prompt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF202124)
                )
                if (job.message.isNotBlank()) {
                    Text(
                        job.message,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF5F6368)
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
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFEDEFF1))
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
                tint = Color(0xFF9AA0A6),
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
            color = Color.White.copy(alpha = 0.88f),
            shape = CircleShape
        ) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "删除图片", modifier = Modifier.size(16.dp), tint = Color(0xFF202124))
            }
        }
    }
}

@Composable
private fun ImageAssetPreviewOverlay(
    image: ImageAssetUiItem,
    onDismiss: () -> Unit,
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
        if (image.prompt.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    image.prompt,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.86f),
                    fontSize = 13.sp
                )
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = GeminiInputShell,
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
                    .background(GeminiInputIconSurface)
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
                color = GeminiInputField,
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
                            color = GeminiInputPlaceholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
                        )
                    }
                    BasicTextField(
                        value = prompt,
                        onValueChange = onPromptChange,
                        maxLines = 3,
                        textStyle = TextStyle(
                            color = GeminiInputText,
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
                    prompt.isBlank() -> Color(0xFFE5ECF8)
                    else -> GeminiPrimaryBlue
                },
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                shape = CircleShape,
                modifier = Modifier.size(40.dp)
            ) {
                if (isGenerating) {
                    Icon(Icons.Default.Stop, contentDescription = "停止生成", tint = Color.White)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "生成图片", tint = if (prompt.isBlank()) GeminiInputPlaceholder else Color.White)
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
        "${Environment.DIRECTORY_PICTURES}/MCA/$fileName"
    }

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
    initialOffsetX = { -it }
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
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
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
            AppMenuRow(icon = { Icon(Icons.Default.Folder, null) }, title = "模型管理", subtitle = "本地 GGUF 与魔塔下载", onClick = onOpenModels)
            AppMenuRow(icon = { McaLogoMark(size = 22.dp, cornerRadius = 7.dp) }, title = "智能调参", subtitle = "测速、推荐与高级参数", onClick = onOpenAgent)
            AppMenuRow(icon = { Icon(Icons.Default.NetworkWifi, null) }, title = "本地 API", subtitle = "接口地址、Key 与网页对话", onClick = onOpenApi)
            AppMenuRow(icon = { Icon(Icons.Default.Settings, null) }, title = "系统设置", subtitle = "运行、日志与诊断", onClick = onOpenSettings)
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
        color = Color(0xFFF1F3F4),
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
    contentColor: Color = Color(0xFF202124),
    onClick: () -> Unit
) {
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
                color = contentColor,
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
                color = if (contentColor == MaterialTheme.colorScheme.error) {
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
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val scale = side / 108f
        fun s(value: Float) = value * scale

        drawRoundRect(
            color = Color(0xFFF8F9FA),
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

/*
 * Legacy block replaced by GPT/Gemini layout.
 */
@Composable
private fun LegacyChatScreenBlock() {
    if (false) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = Color.White,
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
                color = Color.White,
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
                            color = Color(0xFF202124),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (state.selectedModelName == null) "未加载" else "${"%.1f".format(state.stats.decodeTps)} token/s",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = Color(0xFF5F6368)
                        )
                    }
                    Surface(
                        color = if (apiActive) Color(0xFFE8F0FE) else Color(0xFFF1F3F4),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) Color(0xFF1A73E8) else Color(0xFF9AA0A6),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color(0xFF9AA0A6),
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
            color = Color.White,
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
        containerColor = Color.White,
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
                    color = Color(0xFF80868B),
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
                        color = Color(0xFF9AA0A6)
                    )
                }
                if (isGenerating) {
                    Text(
                        text = "生成中请先停止，再切换模型。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = Color(0xFF9AA0A6)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    color = Color(0xFFE8EAED)
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
                            color = Color(0xFF202124),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (selectedModelIsCloud) "默认开启" else reasoningMode.shortLabel(),
                            color = if (activeReasoningMode == ReasoningMode.OFF) Color(0xFF80868B) else Color(0xFF1A73E8),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold
                        )
                        if (!selectedModelIsCloud) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color(0xFF9AA0A6),
                                modifier = Modifier
                                    .rotate(if (reasoningExpanded) -90f else 0f)
                                    .size(18.dp)
                            )
                        }
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = Color(0xFF202124))
                    },
                    onClick = onOpenModels
                ) {
                    Text(
                        text = "更多模型",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = Color(0xFF202124),
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
    CapsuleMenuRow(
        leading = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF202124), modifier = Modifier.size(21.dp))
            } else if (source == InferenceSource.CLOUD) {
                Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = Color(0xFF1A73E8), modifier = Modifier.size(21.dp))
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
                    color = Color(0xFF202124),
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
                    color = if (selected) Color(0xFF1A73E8) else Color(0xFF80868B),
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF9AA0A6),
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
    Surface(
        modifier = modifier,
        color = Color.White,
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
                color = Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            if (models.isEmpty()) {
                Text(
                    text = if (source == InferenceSource.CLOUD) "暂无云端模型" else "暂无本地模型",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = Color(0xFF9AA0A6)
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (model.loaded) Color(0xFFE8F0FE) else Color.Transparent,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF1A73E8))
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) Color(0xFF1A73E8) else Color(0xFF202124),
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
                    color = if (model.loaded) Color(0xFF1A73E8) else Color(0xFF80868B),
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
    Surface(
        modifier = modifier,
        color = if (selected) Color(0xFFE8F0FE) else Color(0xFFF1F3F4),
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) Color(0xFF1A73E8) else Color(0xFF5F6368),
                modifier = Modifier.size(20.dp)
            )
            if (mode == ReasoningMode.ADVANCED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(5.dp)
                        .background(Color(0xFF1A73E8), CircleShape)
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
    Surface(
        modifier = modifier,
        color = Color.White,
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
                color = Color(0xFF80868B),
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
                color = Color.White,
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
                        color = Color(0xFF80868B),
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFFE8F0FE) else Color.Transparent,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = Color(0xFF1A73E8))
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) Color(0xFF1A73E8) else Color(0xFF202124),
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
        drawerContainerColor = Color.White
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .edgeSwipeBack(onClose)
                .background(Color.White)
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
                        color = Color.White,
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
                color = Color(0xFF202124),
                shape = CircleShape,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "聊天",
                        color = Color.White,
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
            color = Color(0xFF202124),
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
            Color.White
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
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(Color.White)
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
                            color = Color(0xFFE8EAED)
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
    contentColor: Color = Color(0xFF202124),
    iconContainerColor: Color = Color(0xFFF1F3F4),
    onClick: () -> Unit
) {
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
            color = iconContainerColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text,
            color = contentColor,
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
    reasoningMode: ReasoningMode,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var showActionSheet by rememberSaveable { mutableStateOf(false) }
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
            modifier = modifier
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = GeminiInputShell,
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .padding(7.dp)
                .navigationBarsPadding()
        ) {
            AttachmentPreview(
                input = input,
                onRemove = { onInputChange(removeAttachmentFromInput(input)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) Color(0xFFE9EDF5) else GeminiInputIconSurface)
                        .clickable(enabled = !isGenerating) { showActionSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "上传文件", modifier = Modifier.size(24.dp), tint = if (isGenerating) GeminiInputPlaceholder else GeminiPrimaryBlue)
                }
                Spacer(modifier = Modifier.width(10.dp))
                val visibleInput = displayInputWithoutAttachment(input)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    color = GeminiInputField,
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
                                color = GeminiInputPlaceholder,
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
                                color = GeminiInputText,
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
private fun CompactInputActionMenu(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                .width(206.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = Color.White,
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
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
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
            color = Color(0xFFF1F3F4),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = Color(0xFF202124),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
            fontWeight = FontWeight.Medium
        )
    }
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
            UserMessageBubble(message.content)
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
private fun UserMessageBubble(content: String) {
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
        Text(
            text = wrapForDisplay(content),
            color = Color(0xFF202124),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
            softWrap = true
        )
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

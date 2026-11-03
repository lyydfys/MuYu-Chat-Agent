package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaTonal
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaEnterReveal
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import kotlinx.coroutines.delay
import com.muyuchat.core.designsystem.McaIconSize

/** Fraction of the drawer sheet that must be on screen before the content reveal starts. */
private const val HISTORY_DRAWER_REVEAL_TRIGGER = 0.15f

/** Window after an open during which newly composed rows may still join the stagger. */
private const val HISTORY_DRAWER_REVEAL_WINDOW_MILLIS = 700L

/**
 * Plain (non-snapshot) gate read from placement callbacks, so tracking the drawer's slide does not
 * invalidate composition. Snapshot state is written only on a real closed -> open crossing.
 */
private class DrawerRevealGate {
    var armed = false
    var opened = false
}

/**
 * History-row motion for pin/unpin reorders, deletes and search filtering. The same calm spring is
 * used as the message list so the two lists feel like one product; no scroll logic lives here, so
 * there is nothing to fight with ChatScrollCoordinator.
 */
private val historyRowAppearSpec: FiniteAnimationSpec<Float> =
    spring(
        dampingRatio = ChatMotion.CONTROL_DAMPING_RATIO,
        stiffness = ChatMotion.CONTROL_STIFFNESS
    )
private val historyRowPlacementSpec: FiniteAnimationSpec<IntOffset> =
    spring(
        dampingRatio = ChatMotion.ITEM_DAMPING_RATIO,
        stiffness = ChatMotion.ITEM_STIFFNESS,
        visibilityThreshold = IntOffset.VisibilityThreshold
    )
private val historyRowRemoveSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 140)

// P9/S11：从 ChatChromeScreens.kt 等值移出的历史抽屉（会话列表）簇。
// 原样逐字迁移，未做行为改动；无新增提升（ChatHistoryDrawer 原即 internal）。
// 折叠屏/大屏：rail = true 时同一面板被 ChatWideRailHost 当作常驻左栏复用（不套模态 sheet、
// 不装滑动关闭手势、不参与开合动画）；rail = false 时容器、手势与视觉与迁移前逐字一致。
@OptIn(ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun ChatHistoryDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenAppMenu: () -> Unit,
    onOpenImages: () -> Unit,
    /** Opens the independent Agent workspace; the smart-tuning entry is unchanged elsewhere. */
    onOpenAgentWorkspace: () -> Unit,
    /** Opens the independent official-DSH workspace. Order is fixed: image -> Agent -> DSH. */
    onOpenDshWorkspace: () -> Unit,
    /** Opens the native video workspace. Order is fixed: image -> Agent -> DSH -> video. */
    onOpenVideoWorkspace: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onBranchMixedConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit,
    /**
     * True on MEDIUM/EXPANDED, where this same panel is the permanent left column of
     * `ChatWideRailHost`: it is not wrapped in a modal sheet and the swipe-to-dismiss gesture is
     * not installed (there is nothing to dismiss). The default keeps the phone drawer, its sheet
     * geometry and its gestures exactly as they are.
     */
    rail: Boolean = false
) {
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var branchTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clearAllRequested by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val searchButtonFocusRequester = remember { FocusRequester() }
    var restoreSearchFocus by remember { mutableStateOf(false) }
    val filteredHistory = remember(state.history, query) {
        filterHistoryItems(state.history, query)
    }
    // Keep the cached grouping date-aware so it still refreshes across midnight.
    val groupedHistory = remember(filteredHistory, query, java.time.LocalDate.now()) {
        historySections(filteredHistory, query)
    }
    // M3 keeps ModalNavigationDrawer's sheet composed while closed, so "first composition" is not
    // the open moment. Placement callbacks below track the sheet actually sliding on screen; the
    // epoch only changes on a real closed -> open crossing, one snapshot write per open and none
    // while the drawer sits closed, so the closed state produces no animation or extra composition.
    var revealEpoch by remember { mutableIntStateOf(0) }
    var revealActive by remember { mutableStateOf(false) }
    val revealGate = remember { DrawerRevealGate() }
    val revealTracker: (LayoutCoordinates) -> Unit = remember {
        { coordinates ->
            val width = coordinates.size.width
            if (width > 0) {
                // 0 = fully closed (sheet starts one width off screen), 1 = fully open.
                val visibleFraction = 1f + coordinates.positionInRoot().x / width
                if (visibleFraction <= 0.03f) {
                    // Re-arm only after the sheet is effectively parked off screen; this also
                    // discards the pre-anchor closed offset that could look partly visible.
                    revealGate.armed = true
                    revealGate.opened = false
                } else if (
                    revealGate.armed &&
                    !revealGate.opened &&
                    visibleFraction >= HISTORY_DRAWER_REVEAL_TRIGGER
                ) {
                    revealGate.opened = true
                    revealActive = true
                    revealEpoch += 1
                }
            }
        }
    }
    LaunchedEffect(revealEpoch) {
        if (revealEpoch == 0) return@LaunchedEffect
        delay(HISTORY_DRAWER_REVEAL_WINDOW_MILLIS)
        revealActive = false
    }

    fun closeSearch() {
        query = ""
        searchActive = false
        restoreSearchFocus = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    BackHandler(enabled = searchActive && !WindowInsets.isImeVisible) { closeSearch() }

    LaunchedEffect(searchActive, restoreSearchFocus) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        } else if (restoreSearchFocus) {
            searchButtonFocusRequester.requestFocus()
            restoreSearchFocus = false
        }
    }

    ChatHistorySurface(
        rail = rail,
        // Track the sheet surface itself (full-screen width, existing Modifier.fillMaxSize()):
        // closed it sits exactly one width off screen and open it reaches x == 0, so the visible
        // fraction below is direction-agnostic and needs no hard-coded drawer width. The permanent
        // rail is never off screen, so it never arms the reveal and the tracker is simply unread.
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned(revealTracker)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (rail) {
                        Modifier
                    } else {
                        Modifier.edgeSwipeBack {
                            if (searchActive) closeSearch() else onClose()
                        }
                    }
                )
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Title <-> search is the same two rows as before, cross-faded while the shared
                    // row height animates with McaMotion.sizeChangePx. Final geometry is unchanged:
                    // the animated container takes the full row width and each mode keeps its
                    // original fillMaxWidth/weight structure.
                    AnimatedContent(
                        targetState = searchActive,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            (fadeIn(animationSpec = McaMotion.fade(millis = 140)) togetherWith
                                fadeOut(animationSpec = McaMotion.fade(millis = 100))).using(
                                SizeTransform(
                                    clip = false,
                                    sizeAnimationSpec = { _, _ -> McaMotion.sizeChangePx }
                                )
                            )
                        },
                        label = "history-search-mode"
                    ) { active ->
                        if (active) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(searchFocusRequester)
                                        .testTag("history.search.field"),
                                    placeholder = {
                                        Text(
                                            "搜索历史",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            McaIcons.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(McaIconSize.M)
                                        )
                                    },
                                    trailingIcon = if (query.isNotEmpty()) {
                                        {
                                            McaIconButton(contentDescription = "清空搜索", onClick = { query = "" }, glyphSize = McaIconSize.M) {
                                                Icon(
                                                    McaIcons.Clear,
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    shape = RoundedCornerShape(20.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { keyboardController?.hide() }
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                McaIconButton(
                                    contentDescription = "关闭搜索",
                                    onClick = { closeSearch() },
                                    modifier = Modifier.testTag("history.search.close"),
                                    glyphSize = McaIconSize.M
                                ) {
                                    Icon(
                                        McaIcons.Close,
                                        contentDescription = null
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "MCA",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = CircleShape,
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                    shadowElevation = 3.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        McaIconButton(
                                            contentDescription = "搜索历史",
                                            onClick = { searchActive = true },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .focusRequester(searchButtonFocusRequester)
                                                .testTag("history.search"),
                                            glyphSize = McaIconSize.M
                                        ) {
                                            Icon(
                                                McaIcons.Search,
                                                contentDescription = null
                                            )
                                        }
                                        McaIconButton(
                                            contentDescription = "打开应用菜单",
                                            onClick = onOpenAppMenu,
                                            modifier = Modifier.size(48.dp),
                                            glyphSize = 25.dp
                                        ) {
                                            McaLogoMark(size = 25.dp, cornerRadius = 9.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                AppFeatureButton(
                    icon = { Icon(McaIcons.Image, null) },
                    text = "图片",
                    onClick = onOpenImages,
                    testTag = "drawer.images",
                    accessibilityLabel = "打开图片工作区"
                )
                // Agent workspace sits directly below the image entry. The existing "recent" area,
                // smart-tuning entry and image overlay behavior are unchanged.
                AppFeatureButton(
                    icon = { Icon(McaIcons.AutoAwesome, null) },
                    text = "Agent",
                    onClick = onOpenAgentWorkspace,
                    testTag = "drawer.agent_workspace",
                    accessibilityLabel = "打开 Agent 工作区"
                )
                // Independent official-DSH mode sits directly below Agent. It is a separate
                // kernel/frontend/data mode and never opens the Kotlin Agent workspace.
                AppFeatureButton(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_dsh_whale),
                            contentDescription = null,
                            modifier = Modifier.size(McaIconSize.L)
                        )
                    },
                    text = "DSH",
                    onClick = onOpenDshWorkspace,
                    testTag = "drawer.dsh_workspace",
                    accessibilityLabel = "打开 DSH 工作区"
                )
                // Native video workflow sits directly below DSH. It is a separate workspace with
                // its own node editor and run views; leaving it never cancels a video run.
                AppFeatureButton(
                    icon = { Icon(McaIcons.PlayArrow, null) },
                    text = "视频",
                    onClick = onOpenVideoWorkspace,
                    testTag = "drawer.video_workflow",
                    accessibilityLabel = "打开视频工作区"
                )
                Spacer(modifier = Modifier.height(34.dp))

                Text(
                    "最近",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                var nextRevealIndex = 0
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
                        groupedHistory.forEach { section ->
                            val sectionRevealIndex = nextRevealIndex
                            item(key = "section-${section.title}") {
                                // Recreated per drawer-open epoch so mcaEnterReveal replays from zero
                                // without rebuilding the LazyColumn itself (scroll position stays).
                                key(revealEpoch) {
                                    HistorySectionHeader(
                                        section.title,
                                        modifier = Modifier
                                            .animateItem(
                                                fadeInSpec = historyRowAppearSpec,
                                                placementSpec = historyRowPlacementSpec,
                                                fadeOutSpec = historyRowRemoveSpec
                                            )
                                            // Filtering keeps the existing animateItem movement
                                            // only: the stagger is off whenever a query narrows
                                            // the list, so no two displacements stack.
                                            .mcaEnterReveal(
                                                index = sectionRevealIndex,
                                                animate = revealActive && query.isBlank()
                                            )
                                    )
                                }
                            }
                            nextRevealIndex += 1
                            itemsIndexed(
                                items = section.items,
                                key = { _, item -> item.id }
                            ) { itemIndex, item ->
                                val rowRevealIndex = sectionRevealIndex + 1 + itemIndex
                                key(revealEpoch) {
                                    HistoryRow(
                                        item = item,
                                        onClick = { onSelectConversation(item.id) },
                                        onRename = {
                                            renameTarget = item
                                            renameText = item.title
                                        },
                                        onTogglePin = { onTogglePinConversation(item.id) },
                                        onBranch = { branchTarget = item },
                                        onDelete = { deleteTarget = item },
                                        modifier = Modifier
                                            .animateItem(
                                                fadeInSpec = historyRowAppearSpec,
                                                placementSpec = historyRowPlacementSpec,
                                                fadeOutSpec = historyRowRemoveSpec
                                            )
                                            .mcaEnterReveal(
                                                index = rowRevealIndex,
                                                animate = revealActive && query.isBlank()
                                            )
                                    )
                                }
                            }
                            nextRevealIndex += section.items.size
                        }
                    }
                }
            }

            val haptics = rememberMcaHaptics()
            val newChatPillInteraction = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .height(48.dp)
                    .mcaPressScale(newChatPillInteraction)
                    .clickable(
                        interactionSource = newChatPillInteraction,
                        indication = ripple(bounded = true),
                        onClick = {
                            haptics.tick()
                            onNewConversation()
                        }
                    ),
                color = MaterialTheme.colorScheme.onBackground,
                shape = CircleShape,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(McaIcons.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(McaIconSize.M))
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
            text = { Text("确定删除“${item.displayName}”吗？此操作不会删除模型文件。") },
            confirmButton = {
                McaButton(style = McaButtonStyle.Text,
                    onClick = {
                        onDeleteConversation(item.id)
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    branchTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { branchTarget = null },
            title = { Text("选择分支角色") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("原始记录保持只读，新分支从空对话开始。")
                    state.assistants.forEach { assistant ->
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                onBranchMixedConversation(item.id, assistant.id)
                                branchTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(assistant.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { McaButton(style = McaButtonStyle.Text,onClick = { branchTarget = null }) { Text("取消") } }
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
                McaButton(style = McaButtonStyle.Text,
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
                McaButton(style = McaButtonStyle.Text,onClick = { renameTarget = null }) {
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
                McaButton(style = McaButtonStyle.Text,
                    onClick = {
                        onClearHistory()
                        clearAllRequested = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = { clearAllRequested = false }) {
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
private fun AppFeatureButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    testTag: String? = null,
    accessibilityLabel: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .mcaPressScale(interactionSource)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = {
                    haptics.tick()
                    onClick()
                }
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .then(
                if (accessibilityLabel != null) {
                    Modifier.semantics { contentDescription = accessibilityLabel }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        McaIconPlate(
            size = McaPlateSize.DrawerBadge,
            shape = McaShape.Badge,
            containerColor = McaTonal.badgeFill(),
            borderColor = Color.Transparent,
            animateColors = false,
        ) {
            Box(modifier = Modifier.size(McaIconSize.L), contentAlignment = Alignment.Center) {
                icon()
            }
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

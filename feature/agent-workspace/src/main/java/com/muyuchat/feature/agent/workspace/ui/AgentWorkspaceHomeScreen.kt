package com.muyuchat.feature.agent.workspace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaEnterReveal
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentTaskListItem
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.muyuchat.core.designsystem.McaIconSize

/**
 * Chat-shaped Agent home.
 *
 * Branded top bar (the single 48dp drawer menu plus the MCA mark and a two-line title), a
 * conversation-style body (empty thread prompt or a compact list of task entries), and the pinned
 * composer. Task history, tools and every secondary action stay in the left drawer; the body is
 * never a brand landing and never scrolls the composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentWorkspaceHomeScreen(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    initialScrollIndex: Int,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val hasTasks = state.home.activeTasks.isNotEmpty() || state.home.recentTasks.isNotEmpty()
    val latestHasTasks = rememberUpdatedState(hasTasks)
    var taskRevealActive by remember { mutableStateOf(true) }

    // A lazy item can leave composition when scrolled off screen. Restrict the stagger to the
    // first populated entrance, and close the window permanently as soon as the user scrolls;
    // recycled rows then appear immediately instead of replaying their entrance on every visit.
    LaunchedEffect(Unit) {
        snapshotFlow { latestHasTasks.value }.first { it }
        delay(700)
        taskRevealActive = false
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        taskRevealActive = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { actions.onHomeScrollAnchor(it) }
    }
    LaunchedEffect(Unit) {
        // The old composer-first anchor never mapped to a task list; keep the stored value at 0.
        if (initialScrollIndex != 0) actions.onHomeScrollAnchor(0)
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch {
            drawerState.close()
            action()
        }
    }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .requiredWidthIn(max = 300.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                AgentHomeDrawerContent(
                    state = state,
                    actions = actions,
                    onClose = { scope.launch { drawerState.close() } },
                    closeThen = closeThen
                )
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AgentHomeTopBar(
                onOpenDrawer = openDrawer
            )

            val activeTasks = state.home.activeTasks
            val recentTasks = state.home.recentTasks

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("agent.home")
            ) {
                AgentHomeBanners(state = state, actions = actions)

                val taskError = state.home.errorMessage
                if (taskError != null) {
                    AgentHomeTaskFeedError(message = taskError, onRetry = actions.onRetryTasks)
                }

                when {
                    state.home.loading && !hasTasks -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在载入任务…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    !hasTasks -> Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        AgentHomeEmptyState()
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 4.dp,
                            bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activeTasks.isNotEmpty()) {
                            item("home.active.header") {
                                AgentSectionHeader(
                                    title = "进行中",
                                    trailing = activeTasks.size.toString()
                                )
                            }
                            itemsIndexed(activeTasks, key = { _, task -> "home.active.${task.taskId}" }) { index, task ->
                                Box(
                                    modifier = Modifier
                                        .animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                                        .mcaEnterReveal(
                                            index = index,
                                            travelDp = 8,
                                            animate = taskRevealActive && index <= 8
                                        )
                                ) {
                                    AgentHomeTaskRow(
                                        task = task,
                                        onOpen = { actions.onOpenTask(task.taskId) }
                                    )
                                }
                            }
                        }
                        if (recentTasks.isNotEmpty()) {
                            item("home.recent.header") {
                                AgentSectionHeader(
                                    title = "最近",
                                    trailing = recentTasks.size.toString()
                                )
                            }
                            itemsIndexed(recentTasks, key = { _, task -> "home.recent.${task.taskId}" }) { index, task ->
                                Box(
                                    modifier = Modifier
                                        .animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                                        .mcaEnterReveal(
                                            index = activeTasks.size + index,
                                            travelDp = 8,
                                            animate = taskRevealActive && activeTasks.size + index <= 8
                                        )
                                ) {
                                    AgentHomeTaskRow(
                                        task = task,
                                        onOpen = { actions.onOpenTask(task.taskId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AgentHomeComposer(
                state = state,
                actions = actions,
                onOpenHistory = openDrawer
            )
        }
    }
}

/**
 * Branded home header.
 *
 * Frozen chrome, one control: the 48dp circular outline menu at the 18dp start inset, a 12dp gap,
 * then the existing [BrandMark] and a two-line title ("MCA Agent" over a muted workspace line).
 * The mark and title share one restrained entrance via [mcaEnterReveal] (fade plus an 8dp rise);
 * the helper snaps to the final state when the system animator duration scale is 0, so reduced
 * motion needs no extra branch here. The 1dp `outlineVariant` hairline separates the surface-tinted
 * header from the page background in both themes instead of adding elevation or a shadow.
 */
@Composable
private fun AgentHomeTopBar(
    onOpenDrawer: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chat's vertical grid (14/10) and 18dp start; a real 1dp outline makes the 48dp target
            // visible instead of relying on a shadow-only disc.
            Surface(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                McaIconButton(
                    contentDescription = "打开任务与工具抽屉",
                    onClick = onOpenDrawer,
                    glyphSize = McaIconSize.L,
                    tint = LocalContentColor.current
                ) {
                    Icon(
                        imageVector = McaIcons.Menu,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.L)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // BrandMark takes no modifier, so a transparent Box owns the reveal layer; the outer
            // row's single centre alignment still aligns the mark with the title block.
            Box(modifier = Modifier.mcaEnterReveal(index = 0, travelDp = 8)) {
                BrandMark(size = 30.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "MCA Agent",
                modifier = Modifier
                    .weight(1f)
                    .mcaEnterReveal(index = 0, travelDp = 8),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun AgentHomeBanners(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions
) {
    val models = state.models
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        state.notice?.let { message ->
            AgentInlineBanner(message = message, onDismiss = actions.onDismissNotice)
        }
        state.error?.let { error ->
            AgentErrorBanner(error = error, onDismiss = actions.onDismissNotice)
        }
        AgentPackageStatusBanner(
            operation = state.packageState.operation,
            onDismiss = actions.onDismissPackageStatus
        )
        if (models.selected != null && !models.protocolSupported) {
            AgentInlineBanner(
                message = "当前模型不支持 Agent 工具协议，无法开始任务。",
                isError = true
            )
        }
        val selectedModel = models.selected
        if (selectedModel != null && selectedModel.loadState == AgentModelLoadState.UNLOADED) {
            AgentModelUnloadedNotice(
                displayName = selectedModel.displayName,
                onOpenChatToLoad = actions.onOpenChatToLoad
            )
        }
        if (models.selected != null && !models.offlineCompatible) {
            AgentInlineBanner(
                message = "当前为离线模式，所选模型需要联网。开启“允许联网”或选择离线模型。"
            )
        }
        models.errorMessage?.let { message ->
            AgentInlineBanner(message = message, isError = true)
        }
    }
}

@Composable
private fun AgentHomeTaskFeedError(
    message: String,
    onRetry: () -> Unit
) {
    val haptics = rememberMcaHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        McaButton(
            style = McaButtonStyle.Text,
            shape = ButtonDefaults.textShape,
            onClick = {
                onRetry()
            },
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("重试")
        }
    }
}

@Composable
private fun AgentHomeEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .mcaEnterReveal(index = 0, travelDp = 10)
            .testTag("agent.home.empty"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = McaIcons.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "开始一个 Agent 任务",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "描述目标，Agent 会规划步骤并在本机执行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AgentHomeTaskRow(
    task: AgentTaskListItem,
    onOpen: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .mcaPressScale(interactionSource)
            .testTag("agent.home.task.${task.taskId}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClickLabel = "打开任务",
                    onClick = {
                        haptics.tick()
                        onOpen()
                    }
                )
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.archived) {
                        AgentTag(text = task.statusLabel)
                    } else {
                        AgentStatusChip(task.status)
                    }
                    Text(
                        text = task.updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                    imageVector = McaIcons.Forward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

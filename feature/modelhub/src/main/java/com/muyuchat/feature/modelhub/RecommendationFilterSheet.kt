@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaMotion

/** 推荐列表控制行：内容范围在左，排序与视图模式在右。 */
@Composable
internal fun RecommendationToolbar(
    query: RecommendationQuery,
    viewMode: RecommendationViewMode,
    onShowAll: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenSort: () -> Unit,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            McaButton(
                style = if (!query.hasFilters) McaButtonStyle.Tonal else McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                onClick = onShowAll,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "全部：清除筛选、恢复推荐顺序和详细视图"
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text("全部", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            McaButton(
                style = if (query.hasFilters) McaButtonStyle.Tonal else McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                onClick = onOpenFilter,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = if (query.hasFilters) {
                        "筛选，已应用 ${query.filterCount} 个条件"
                    } else {
                        "筛选"
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                leadingIcon = {
                    Icon(McaIcons.FilterList, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                }
            ) {
                Text("筛选", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                if (query.filterCount > 0) {
                    Text("· ${query.filterCount}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            if (compact) {
                McaIconButton(
                    contentDescription = "排序，当前为${query.sort.label}",
                    onClick = onOpenSort,
                    selected = query.sort != RecommendationSort.CATALOG,
                    glyphSize = McaIconSize.M
                ) {
                    Icon(McaIcons.Tune, contentDescription = null)
                }
                McaIconButton(
                    contentDescription = when (viewMode) {
                        RecommendationViewMode.DETAIL -> "详细视图，点击切换为紧凑视图"
                        RecommendationViewMode.COMPACT -> "紧凑视图，点击切换为详细视图"
                    },
                    onClick = onToggleViewMode,
                    selected = viewMode == RecommendationViewMode.COMPACT,
                    glyphSize = McaIconSize.M
                ) {
                    Icon(McaIcons.List, contentDescription = null)
                }
            } else {
                McaButton(
                    style = if (query.sort != RecommendationSort.CATALOG) McaButtonStyle.Tonal else McaButtonStyle.Text,
                    size = McaButtonSize.CardAction,
                    onClick = onOpenSort,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "排序，当前为${query.sort.label}"
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    leadingIcon = {
                        Icon(McaIcons.Tune, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                    }
                ) {
                    Text(
                        if (query.sort == RecommendationSort.CATALOG) "排序" else query.sort.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                McaButton(
                    style = if (viewMode == RecommendationViewMode.COMPACT) McaButtonStyle.Tonal else McaButtonStyle.Text,
                    size = McaButtonSize.CardAction,
                    onClick = onToggleViewMode,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = when (viewMode) {
                            RecommendationViewMode.DETAIL -> "详细视图，点击切换为紧凑视图"
                            RecommendationViewMode.COMPACT -> "紧凑视图，点击切换为详细视图"
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(recommendationViewModeChipLabel(viewMode), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** One end-aligned panel keeps filter and sort drafts in the same apply/cancel transaction. */
@Composable
internal fun RecommendationFilterSheet(
    visible: Boolean,
    draft: RecommendationQuery,
    focus: RecommendationPanelFocus,
    ramTiers: List<Int>,
    matchedCount: Int,
    onFocusChange: (RecommendationPanelFocus) -> Unit,
    onDraftChange: (RecommendationQuery) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val visibilityState = rememberEnterVisibility(visible)
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.32f else 0f,
        animationSpec = McaMotion.fade(180),
        label = "recommendationFilterScrim"
    )

    if (visibilityState.currentState || visibilityState.targetState) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
            ) {
                val panelWidth = (maxWidth * 0.68f).coerceAtMost(420.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(maxWidth - panelWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "关闭筛选与排序",
                            onClick = onDismiss
                        )
                        .semantics { contentDescription = "关闭筛选与排序" }
                )

                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = slideIn(
                        animationSpec = McaMotion.pageSlide,
                        initialOffset = { size: IntSize -> IntOffset(size.width, 0) }
                    ) + fadeIn(McaMotion.fade(160)),
                    exit = slideOut(
                        animationSpec = McaMotion.pageSlide,
                        targetOffset = { size: IntSize -> IntOffset(size.width, 0) }
                    ) + fadeOut(McaMotion.fade(140)),
                    label = "recommendationFilterPanel",
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Surface(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .testTag("modelhub.recommendation.filter.panel")
                            .semantics { paneTitle = "筛选与排序" },
                        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "筛选与排序",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                McaIconButton(
                                    contentDescription = "关闭筛选与排序",
                                    onClick = onDismiss,
                                    glyphSize = McaIconSize.L
                                ) {
                                    Icon(McaIcons.Close, contentDescription = null)
                                }
                            }

                            SecondaryTabRow(selectedTabIndex = if (focus == RecommendationPanelFocus.FILTER) 0 else 1) {
                                Tab(
                                    selected = focus == RecommendationPanelFocus.FILTER,
                                    onClick = { onFocusChange(RecommendationPanelFocus.FILTER) },
                                    text = { Text("筛选") }
                                )
                                Tab(
                                    selected = focus == RecommendationPanelFocus.SORT,
                                    onClick = { onFocusChange(RecommendationPanelFocus.SORT) },
                                    text = { Text("排序") }
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 16.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                when (focus) {
                                    RecommendationPanelFocus.FILTER -> {
                                        FacetSection(
                                            title = "任务",
                                            anyLabel = "不限",
                                            options = RecommendationTask.entries,
                                            optionLabel = { it.label },
                                            selected = draft.tasks,
                                            onToggle = { task ->
                                                onDraftChange(
                                                    draft.copy(
                                                        tasks = if (task in draft.tasks) draft.tasks - task else draft.tasks + task
                                                    )
                                                )
                                            },
                                            onClear = { onDraftChange(draft.copy(tasks = emptySet())) }
                                        )
                                        FacetSection(
                                            title = "运行路线",
                                            anyLabel = "不限",
                                            options = RecommendationBackend.entries,
                                            optionLabel = { it.label },
                                            selected = draft.backends,
                                            onToggle = { backend ->
                                                onDraftChange(
                                                    draft.copy(
                                                        backends = if (backend in draft.backends) {
                                                            draft.backends - backend
                                                        } else {
                                                            draft.backends + backend
                                                        }
                                                    )
                                                )
                                            },
                                            onClear = { onDraftChange(draft.copy(backends = emptySet())) }
                                        )
                                        FacetSection(
                                            title = "资源档",
                                            anyLabel = "不限",
                                            options = ramTiers,
                                            optionLabel = { recommendationRamTierLabel(it) },
                                            selected = draft.ramTiers,
                                            onToggle = { tier ->
                                                onDraftChange(
                                                    draft.copy(
                                                        ramTiers = if (tier in draft.ramTiers) draft.ramTiers - tier else draft.ramTiers + tier
                                                    )
                                                )
                                            },
                                            onClear = { onDraftChange(draft.copy(ramTiers = emptySet())) },
                                            hint = "“未标注”表示目录未提供建议内存。"
                                        )
                                    }

                                    RecommendationPanelFocus.SORT -> SortSection(
                                        selected = draft.sort,
                                        onSelect = { onDraftChange(draft.copy(sort = it)) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                McaButton(style = McaButtonStyle.Text, onClick = onReset) {
                                    Text("重置")
                                }
                                McaButton(
                                    style = McaButtonStyle.Primary,
                                    onClick = onApply,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        recommendationResultCountLabel(matchedCount),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> FacetSection(
    title: String,
    anyLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    onClear: () -> Unit,
    hint: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            McaChip(
                selected = selected.isEmpty(),
                onClick = onClear,
                label = { Text(anyLabel) }
            )
            options.forEach { option ->
                McaChip(
                    selected = option in selected,
                    onClick = { onToggle(option) },
                    label = { Text(optionLabel(option)) }
                )
            }
        }
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SortSection(
    selected: RecommendationSort,
    onSelect: (RecommendationSort) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("排序", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        RecommendationSort.entries.forEach { sort ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == sort,
                        role = Role.RadioButton,
                        onClick = { onSelect(sort) }
                    )
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == sort, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(sort.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        recommendationSortHint(sort),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun recommendationSortHint(sort: RecommendationSort): String = when (sort) {
    RecommendationSort.CATALOG -> "沿用目录分组与组内推荐顺序"
    RecommendationSort.DOWNLOAD_SIZE_ASC -> "按默认下载计划总体积，未知体积排在末尾"
    RecommendationSort.MINIMUM_RAM_ASC -> "按目录声明的建议内存，未标注排在末尾"
}

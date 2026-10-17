package com.muyuchat.feature.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun WebSearchSourcesRow(
    sources: List<ChatSourceReference>,
    trace: ChatWebSearchTrace?,
    sessionId: String?,
    messageId: String,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit
) {
    var selectedUrl by remember(sources) { mutableStateOf<String?>(null) }
    val selectedSource = sources.firstOrNull { it.url == selectedUrl }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trace?.hasContent == true) {
            WebSearchTraceCard(
                trace = trace,
                onOpenBrowser = { query ->
                    InternalBrowserUrlPolicy.buildSearchUrl("https://www.google.com/search?q={query}", query)?.let { url ->
                        onOpenBrowserTask(BrowserTaskLaunch(
                            owner = "chat:$sessionId:$messageId", initialUrl = url,
                            kind = BrowserTaskKind.SEARCH, sessionId = sessionId,
                            messageId = messageId, query = query
                        ))
                    }
                }
            )
        }
        if (sources.isEmpty()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = McaPrimaryBlue
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
                    border = if (selected) BorderStroke(1.dp, McaPrimaryBlue.copy(alpha = 0.46f)) else null
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
                        onOpenBrowserTask(BrowserTaskLaunch(
                            owner = "chat:$sessionId:$messageId", initialUrl = source.url,
                            sessionId = sessionId, messageId = messageId
                        ))
                    },
                    onClose = { selectedUrl = null }
                )
            }
        }
    }
}

@Composable
private fun WebSearchTraceCard(
    trace: ChatWebSearchTrace,
    onOpenBrowser: (String) -> Unit
) {
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
                    if (trace.query.isNotBlank() && !trace.running) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { onOpenBrowser(trace.query) }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("在内置浏览器中查看搜索")
                            }
                        }
                    }
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


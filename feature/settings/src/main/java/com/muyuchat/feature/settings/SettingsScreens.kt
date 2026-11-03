package com.muyuchat.feature.settings

import com.muyuchat.core.designsystem.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.telemetry.RuntimeMetrics
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class SettingsUiState(
    val params: GenerationParams = GenerationParams(),
    val stats: RuntimeStats = RuntimeStats(),
    val logs: List<RuntimeMetrics> = emptyList(),
    val agentLogs: List<String> = emptyList(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    val apiKey: String = "",
    val localApiAddress: String = "",
    val openApiAddress: String = "",
    val loadedModelId: String? = null,
    val nativeStatsJson: String = "{}",
    val diagnosticReport: String = "",
    val chatSessionCount: Int = 0,
    val imageAssetCount: Int = 0,
    val imageAssetBytes: Long = 0L,
    val fileAssetCount: Int = 0,
    val fileAssetBytes: Long = 0L,
    val persistentPrefixCacheEnabled: Boolean = true,
    val persistentPrefixCacheEntryCount: Int = 0,
    val persistentPrefixCacheBytes: Long = 0L,
    val cleanupBusy: SettingsCleanupBusyUiState = SettingsCleanupBusyUiState(),
    val statusMessage: String? = null,
    val appUpdate: AppUpdateSettingsUiState = AppUpdateSettingsUiState(),
    val webSearch: WebSearchSettingsUiState = WebSearchSettingsUiState()
)

@Immutable
data class SettingsCleanupBusyUiState(
    val chatHistory: Boolean = false,
    val imageLibrary: Boolean = false,
    val fileLibrary: Boolean = false,
    val persistentPrefixCache: Boolean = false
) {
    val any: Boolean
        get() = chatHistory || imageLibrary || fileLibrary || persistentPrefixCache
}

@Immutable
data class AppUpdateSettingsUiState(
    val autoCheckEnabled: Boolean = true,
    val status: String = "IDLE",
    val currentVersionName: String = "",
    val latestVersionName: String? = null,
    val latestTitle: String? = null,
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val apkSizeText: String = "",
    val canDownload: Boolean = false,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val lastCheckedText: String = "",
    val message: String? = null
)

@Immutable
data class WebSearchSettingsUiState(
    val enabled: Boolean = false,
    val provider: String = "SEARXNG",
    val providerLabel: String = "SearxNG",
    val endpoint: String = "",
    val apiKey: String = "",
    val maxResults: Int = 5,
    val fetchPageContent: Boolean = true,
    val triggerMode: String = "SMART",
    val triggerModeLabel: String = "智能",
    val researchMode: String = "AUTO",
    val researchModeLabel: String = "自动",
    val configured: Boolean = false,
    val realSearchConfigured: Boolean = false,
    val realSearchProviderLabel: String = "",
    val backupProviders: List<WebSearchBackupProviderUiState> = emptyList(),
    val statusMessage: String? = null,
    val operationBusy: Boolean = false,
    val diagnostics: List<WebSearchDiagnosticUiItem> = emptyList()
)

@Immutable
data class WebSearchBackupProviderUiState(
    val enabled: Boolean = false,
    val provider: String = "SEARXNG",
    val providerLabel: String = "SearxNG",
    val endpoint: String = "",
    val apiKey: String = "",
    val configured: Boolean = false
)

@Immutable
data class WebSearchDiagnosticUiItem(
    val createdAtText: String,
    val providerLabel: String,
    val triggerModeLabel: String,
    val query: String,
    val success: Boolean,
    val message: String,
    val sourceCount: Int,
    val elapsedMs: Long,
    val searchedQueries: List<String>,
    val directUrls: List<String>,
    val healthScore: Int = 0,
    val healthLabel: String = "",
    val healthReasons: List<String> = emptyList(),
    val qualityScore: Int,
    val qualityLabel: String,
    val qualityReasons: List<String>,
    val sourceTrustSummary: List<String> = emptyList(),
    val researchConfidenceScore: Int = 0,
    val researchConfidenceLabel: String = "",
    val researchEvidenceGroups: List<String> = emptyList(),
    val researchConflictWarnings: List<String> = emptyList(),
    val researchSynthesisGuidance: List<String> = emptyList(),
    val triggerReasons: List<String>,
    val warnings: List<String>,
    val cacheStatus: String = "",
    val closedLoopChecks: List<String> = emptyList(),
    val topSources: List<WebSearchDiagnosticSourceUiItem>
)

data class WebSearchDiagnosticSourceUiItem(
    val title: String,
    val url: String,
    val snippet: String = "",
    val provider: String = "",
    val trustLabel: String = "",
    val hostLabel: String = ""
)

data class WebSearchSettingsDraft(
    val enabled: Boolean,
    val provider: String,
    val endpoint: String,
    val apiKey: String,
    val maxResults: Int,
    val fetchPageContent: Boolean,
    val triggerMode: String,
    val researchMode: String = "AUTO",
    val backupProviders: List<WebSearchBackupProviderDraft> = emptyList()
)

data class WebSearchBackupProviderDraft(
    val enabled: Boolean,
    val provider: String,
    val endpoint: String,
    val apiKey: String
)

private enum class SettingsSection(val title: String, val summary: String) {
    RUNTIME("运行", "模型加载、生成参数、后端与内存。"),
    LOGS("日志与诊断", "运行日志、API 日志与诊断导出。"),
    PRIVACY("隐私与数据", "本机数据清理与缓存边界。"),
    SEARCH("联网检索", "搜索服务、触发策略与来源质量。"),
    EXPERIMENTS("实验功能", "仍在打磨的能力，可能随版本调整。"),
    ABOUT("关于", "版本更新、开源许可与项目信息。")
}

private enum class SettingsSectionTone {
    ONLINE,
    INFO,
    WARNING,
    NEUTRAL
}

private data class SettingsSectionStatus(
    val label: String,
    val tone: SettingsSectionTone
)

@Composable
fun SettingsHubScreen(
    state: SettingsUiState,
    onRefreshLogs: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearChatHistory: () -> Unit,
    onClearImageLibrary: () -> Unit,
    onClearFileLibrary: () -> Unit,
    onPersistentPrefixCacheEnabledChanged: (Boolean) -> Unit,
    onClearPersistentPrefixCache: () -> Unit,
    offlineTranslationStatus: String = "未安装",
    offlineTranslationInstalled: Boolean = false,
    offlineTranslationInstalling: Boolean = false,
    offlineTranslationDownloading: Boolean = false,
    offlineTranslationDownloadProgress: Float? = null,
    onImportOfflineTranslation: () -> Unit = {},
    onCancelOfflineTranslationImport: () -> Unit = {},
    onDownloadOfflineTranslation: () -> Unit = {},
    onCancelOfflineTranslationDownload: () -> Unit = {},
    onSaveWebSearchSettings: (WebSearchSettingsDraft) -> Unit,
    onPreflightWebSearch: (WebSearchSettingsDraft) -> Unit,
    onTestWebSearch: (String, WebSearchSettingsDraft) -> Unit,
    onTestWebSearchTurn: (String, WebSearchSettingsDraft, Boolean) -> Unit,
    onClearWebSearchDiagnostics: () -> Unit,
    onBack: () -> Unit,
    startInWebSearch: Boolean = false,
    modifier: Modifier = Modifier,
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onOpenRelease: () -> Unit = {},
    onAutoCheckChanged: (Boolean) -> Unit = {},
    onOpenWebPage: (String) -> Unit = {}
) {
    val startSection = if (startInWebSearch) SettingsSection.SEARCH else null
    // Null means the dashboard. A section opens as a full-height detail page with a draggable
    // header, so the old chip strip no longer has to compete with six tabs at large font scales.
    var section by rememberSaveable(startInWebSearch) {
        mutableStateOf<SettingsSection?>(startSection)
    }
    val pageStateHolder = rememberSaveableStateHolder()
    // The dashboard grid owns the single tactile tick for opening a section; coming back out is
    // navigation, not a selection change, so it stays silent and never double-fires.
    val navigateBackToHub = {
        section = null
    }

    BackHandler(enabled = section != null) { navigateBackToHub() }

    AnimatedContent(
        targetState = section,
        transitionSpec = {
            if (initialState == null && targetState != null) {
                (fadeIn(McaMotion.fade(180)) + slideInHorizontally(animationSpec = tween(260)) { it / 10 }) togetherWith
                    fadeOut(McaMotion.fade(120))
            } else {
                fadeIn(McaMotion.fade(180)) togetherWith
                    (slideOutHorizontally(animationSpec = tween(220)) { it / 10 } + fadeOut(McaMotion.fade(140)))
            }
        },
        label = "settingsSectionContent",
        modifier = modifier.fillMaxSize()
    ) { targetSection ->
        val pageKey = targetSection?.name ?: "settings-hub"
        pageStateHolder.SaveableStateProvider(pageKey) {
            if (targetSection == null) {
                SettingsHubContent(
                    state = state,
                    onOpenSection = { target ->
                        section = target
                    },
                    onClose = onBack
                )
            } else {
                val detailSection = targetSection
                SettingsDetailScreen(
                    section = detailSection,
                    onNavigateUp = navigateBackToHub,
                    onCloseSettings = onBack
                ) {
                    when (detailSection) {
                        SettingsSection.RUNTIME -> RuntimeScreen(
                            state = state,
                            offlineTranslationStatus = offlineTranslationStatus,
                            offlineTranslationInstalled = offlineTranslationInstalled,
                            offlineTranslationInstalling = offlineTranslationInstalling,
                            offlineTranslationDownloading = offlineTranslationDownloading,
                            offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
                            onImportOfflineTranslation = onImportOfflineTranslation,
                            onCancelOfflineTranslationImport = onCancelOfflineTranslationImport,
                            onDownloadOfflineTranslation = onDownloadOfflineTranslation,
                            onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload,
                            onCheckUpdate = onCheckUpdate,
                            onDownloadUpdate = onDownloadUpdate,
                            onInstallUpdate = onInstallUpdate,
                            onOpenRelease = onOpenRelease,
                            onAutoCheckChanged = onAutoCheckChanged,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsSection.LOGS -> LogsApiScreen(
                            state = state,
                            onRefreshLogs = onRefreshLogs,
                            onRefreshDiagnostics = onRefreshDiagnostics,
                            onExportDiagnostics = onExportDiagnostics,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsSection.PRIVACY -> PrivacyDataScreen(
                            state = state,
                            onClearChatHistory = onClearChatHistory,
                            onClearImageLibrary = onClearImageLibrary,
                            onClearFileLibrary = onClearFileLibrary,
                            onPersistentPrefixCacheEnabledChanged = onPersistentPrefixCacheEnabledChanged,
                            onClearPersistentPrefixCache = onClearPersistentPrefixCache,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsSection.SEARCH -> SearchSettingsScreen(
                            state = state.webSearch,
                            onSave = onSaveWebSearchSettings,
                            onPreflight = onPreflightWebSearch,
                            onTest = onTestWebSearch,
                            onTestTurn = onTestWebSearchTurn,
                            onClearDiagnostics = onClearWebSearchDiagnostics,
                            onOpenWebPage = onOpenWebPage,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsSection.EXPERIMENTS -> ExperimentsScreen(
                            state = state,
                            modifier = Modifier.fillMaxSize()
                        )
                        SettingsSection.ABOUT -> SettingsAboutScreen(
                            state = state,
                            onCheckUpdate = onCheckUpdate,
                            onDownloadUpdate = onDownloadUpdate,
                            onInstallUpdate = onInstallUpdate,
                            onOpenRelease = onOpenRelease,
                            onOpenWebPage = onOpenWebPage,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubContent(
    state: SettingsUiState,
    onOpenSection: (SettingsSection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberMcaHaptics()
    val listState = rememberLazyListState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsHubBackdrop(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            SettingsHubTopBar(onClose = onClose)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "hero") {
                    SettingsHero(state = state)
                }
                item(key = "sections-title") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "配置中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${SettingsSection.entries.size} 个分区",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item(key = "sections-grid") {
                    SettingsSectionGrid(
                        state = state,
                        onSectionClick = { target ->
                            // The dashboard has no selected chip to re-tap, so this grid is the
                            // single tactile owner: one tick per opened section, and the detail
                            // page's back gesture never ticks a second time.
                            haptics.tick()
                            onOpenSection(target)
                        }
                    )
                }
                item(key = "privacy-note") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = McaIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "配置即时生效，敏感数据留在本机。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "MCA · LOCAL CONTROL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.1.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "系统设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "模型、数据与联网能力的一站式控制台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        McaIconButton(
            // The glyph is a close X, but the action leaves settings and returns to chat; the
            // frozen accessibility contract names what the button does, not which glyph it draws.
            contentDescription = "返回聊天",
            onClick = onClose,
            glyphSize = McaIconSize.M
        ) {
            Icon(McaIcons.Close, contentDescription = null)
        }
    }
}

@Composable
private fun SettingsHubBackdrop(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (dark) 0.12f else 0.055f
    )
    Canvas(modifier) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = if (dark) 0.16f else 0.075f), Color.Transparent),
                center = Offset(size.width * 0.96f, 0f),
                radius = size.width * 0.92f
            ),
            radius = size.width * 0.92f,
            center = Offset(size.width * 0.96f, 0f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = if (dark) 0.10f else 0.045f), Color.Transparent),
                center = Offset(0f, size.height * 0.86f),
                radius = size.width * 0.74f
            ),
            radius = size.width * 0.74f,
            center = Offset(0f, size.height * 0.86f)
        )
        val step = 20.dp.toPx()
        val originX = size.width - 18.dp.toPx()
        val originY = 58.dp.toPx()
        for (column in 0 until 6) {
            for (row in 0 until 4) {
                drawCircle(
                    color = dotColor,
                    radius = 1.dp.toPx(),
                    center = Offset(originX - column * step, originY + row * step)
                )
            }
        }
    }
}

@Composable
private fun SettingsHero(
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val orbitTransition = rememberInfiniteTransition(label = "settingsHeroOrbit")
    val orbitAngle = orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing)
        ),
        label = "settingsHeroOrbitAngle"
    )
    val shape = RoundedCornerShape(28.dp)
    val stats = state.stats
    val search = state.webSearch

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 202.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0B1424),
                        Color(0xFF0F1D33),
                        Color(0xFF0A1725)
                    )
                )
            )
            .border(1.dp, primary.copy(alpha = 0.42f), shape)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val orbitCenter = Offset(size.width * 0.78f, size.height * 0.47f)
            val orbitRadius = size.minDimension * 0.40f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4C8DFF).copy(alpha = 0.26f), Color.Transparent),
                    center = orbitCenter,
                    radius = orbitRadius * 1.9f
                ),
                radius = orbitRadius * 1.9f,
                center = orbitCenter
            )
            drawCircle(
                color = Color(0xFF8AB4F8).copy(alpha = 0.25f),
                radius = orbitRadius,
                center = orbitCenter,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF8AB4F8).copy(alpha = 0.11f),
                radius = orbitRadius * 0.62f,
                center = orbitCenter,
                style = Stroke(width = 1.dp.toPx())
            )
            for (line in 1..3) {
                val y = size.height * (0.24f + line * 0.17f)
                drawLine(
                    color = Color.White.copy(alpha = 0.028f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            val radians = (orbitAngle.value * PI / 180.0)
            val satellite = Offset(
                x = orbitCenter.x + cos(radians).toFloat() * orbitRadius,
                y = orbitCenter.y + sin(radians).toFloat() * orbitRadius
            )
            drawCircle(Color(0xFF8AB4F8).copy(alpha = 0.16f), 9.dp.toPx(), satellite)
            drawCircle(Color(0xFFDCEAFF), 3.dp.toPx(), satellite)
            val inner = Offset(
                x = orbitCenter.x + cos(radians + PI * 0.7).toFloat() * orbitRadius * 0.62f,
                y = orbitCenter.y + sin(radians + PI * 0.7).toFloat() * orbitRadius * 0.62f
            )
            drawCircle(Color(0xFF8AB4F8).copy(alpha = 0.82f), 2.dp.toPx(), inner)
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "LOCAL INTELLIGENCE",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8AB4F8),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.6.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "本地智能，一处掌控",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF2F6FF)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "把模型、数据与联网能力的边界留在设备上。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFA9BDDA)
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeroStatusPill(
                    label = "状态 · ${stats.hubRuntimeLabel()}",
                    tone = stats.hubRuntimeTone()
                )
                HeroStatusPill(
                    label = "模型 · ${stats.hubModelLabel()}",
                    tone = if (stats.loaded) SettingsSectionTone.ONLINE else SettingsSectionTone.NEUTRAL
                )
                HeroStatusPill(
                    label = "检索 · ${search.hubStatusLabel()}",
                    tone = search.hubStatusTone()
                )
            }
        }
    }
}

@Composable
private fun HeroStatusPill(
    label: String,
    tone: SettingsSectionTone,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .widthIn(max = 240.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(heroToneColor(tone))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD9E6F7),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsSectionGrid(
    state: SettingsUiState,
    onSectionClick: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (maxWidth < 350.dp || fontScale >= 1.3f) 1 else 2
        val rows = remember(columns) { SettingsSection.entries.chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { rowSections ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowSections.forEach { section ->
                        SettingsSectionCard(
                            section = section,
                            status = section.hubStatus(state),
                            onClick = { onSectionClick(section) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    repeat(columns - rowSections.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    status: SettingsSectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
    val surface = if (dark) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val borderAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.62f else if (dark) 0.24f else 0.14f,
        animationSpec = tween(160),
        label = "settingsCardBorder"
    )

    Box(
        modifier = modifier
            .heightIn(min = 188.dp)
            .shadow(
                elevation = if (dark) 8.dp else 2.dp,
                shape = shape,
                clip = false
            )
            .clip(shape)
            .background(surface)
            .border(1.dp, accent.copy(alpha = borderAlpha), shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "打开${section.title}",
                onClick = onClick
            )
            .mcaPressScale(interaction)
            .semantics(mergeDescendants = true) {
                stateDescription = status.label
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = if (dark) 0.15f else 0.075f),
                        Color.Transparent
                    ),
                    center = Offset(size.width, 0f),
                    radius = size.width * 0.92f
                ),
                radius = size.width * 0.92f,
                center = Offset(size.width, 0f)
            )
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = if (dark) 0.16f else 0.09f))
                        .border(
                            width = 1.dp,
                            color = accent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = section.icon(),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                SettingsStatusBadge(status)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = section.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "进入",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = McaIcons.ChevronRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsStatusBadge(
    status: SettingsSectionStatus,
    modifier: Modifier = Modifier
) {
    val color = toneColor(status.tone)
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.20f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsDetailScreen(
    section: SettingsSection,
    onNavigateUp: () -> Unit,
    onCloseSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var dragY by remember(section) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 88.dp.toPx() }
    val flickThreshold = with(density) { 900.dp.toPx() }
    val minFlickDistance = with(density) { 24.dp.toPx() }
    val scope = rememberCoroutineScope()
    val settleAnimation = remember(section) { Animatable(0f) }
    val dragState = rememberDraggableState { delta ->
        dragY = (dragY + delta).coerceAtLeast(0f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = dragY }
        ) {
            SettingsDetailHeader(
                section = section,
                onNavigateUp = onNavigateUp,
                onCloseSettings = onCloseSettings,
                modifier = Modifier.draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = {
                        scope.launch { settleAnimation.stop() }
                    },
                    onDragStopped = { velocity ->
                        val flicked = velocity > flickThreshold && dragY > minFlickDistance
                        if (dragY > dismissThreshold || flicked) {
                            dragY = 0f
                            onNavigateUp()
                        } else {
                            scope.launch {
                                settleAnimation.snapTo(dragY)
                                settleAnimation.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(220)
                                ) {
                                    dragY = value
                                }
                            }
                        }
                    }
                )
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    section: SettingsSection,
    onNavigateUp: () -> Unit,
    onCloseSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The handle is decorative; the whole header row below is the drag target so the sheet
        // can be pulled down from either the indicator or the title area.
        Box(
            modifier = Modifier.fillMaxWidth().height(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f))
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            McaIconButton(
                contentDescription = "返回设置中心",
                onClick = onNavigateUp,
                glyphSize = McaIconSize.L
            ) {
                Icon(McaIcons.Back, contentDescription = null)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = "设置中心 / ${section.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            McaIconButton(
                contentDescription = "关闭设置",
                onClick = onCloseSettings
            ) {
                Icon(McaIcons.Close, contentDescription = null)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        )
    }
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.RUNTIME -> McaIcons.Speed
    SettingsSection.LOGS -> McaIcons.Description
    SettingsSection.PRIVACY -> McaIcons.Shield
    SettingsSection.SEARCH -> McaIcons.Search
    SettingsSection.EXPERIMENTS -> McaIcons.Science
    SettingsSection.ABOUT -> McaIcons.Info
}

private fun SettingsSection.hubStatus(state: SettingsUiState): SettingsSectionStatus {
    val stats = state.stats
    return when (this) {
        SettingsSection.RUNTIME -> SettingsSectionStatus(
            label = stats.hubRuntimeLabel(),
            tone = stats.hubRuntimeTone()
        )
        SettingsSection.LOGS -> SettingsSectionStatus(
            label = when {
                state.logs.isEmpty() -> "暂无"
                state.logs.size > 99 -> "99+ 条"
                else -> "${state.logs.size} 条"
            },
            tone = if (state.logs.isEmpty()) SettingsSectionTone.NEUTRAL else SettingsSectionTone.INFO
        )
        SettingsSection.PRIVACY -> SettingsSectionStatus(
            label = "本机",
            tone = SettingsSectionTone.ONLINE
        )
        SettingsSection.SEARCH -> SettingsSectionStatus(
            label = state.webSearch.hubStatusLabel(),
            tone = state.webSearch.hubStatusTone()
        )
        SettingsSection.EXPERIMENTS -> SettingsSectionStatus(
            label = "预览",
            tone = SettingsSectionTone.INFO
        )
        SettingsSection.ABOUT -> {
            val update = state.appUpdate
            val rawVersion = update.currentVersionName.trim()
            val versionLabel = when {
                rawVersion.isBlank() -> "版本"
                rawVersion.startsWith("v", ignoreCase = true) -> rawVersion
                else -> "v$rawVersion"
            }
            when {
                update.status.contains("DOWNLOAD", ignoreCase = true) -> SettingsSectionStatus(
                    label = "更新中",
                    tone = SettingsSectionTone.INFO
                )
                update.status.contains("READY", ignoreCase = true) ||
                    update.status.contains("DOWNLOADED", ignoreCase = true) -> SettingsSectionStatus(
                    label = "待安装",
                    tone = SettingsSectionTone.WARNING
                )
                else -> SettingsSectionStatus(
                    label = versionLabel,
                    tone = SettingsSectionTone.INFO
                )
            }
        }
    }
}

private fun RuntimeStats.hubRuntimeLabel(): String = when {
    loaded -> "在线"
    !modelPath.isNullOrBlank() -> "加载中"
    else -> "待机"
}

private fun RuntimeStats.hubRuntimeTone(): SettingsSectionTone = when {
    loaded -> SettingsSectionTone.ONLINE
    !modelPath.isNullOrBlank() -> SettingsSectionTone.INFO
    else -> SettingsSectionTone.NEUTRAL
}

private fun RuntimeStats.hubModelLabel(): String {
    if (!loaded && modelPath.isNullOrBlank()) return "未加载"
    val fileName = modelPath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: return "本地模型"
    val base = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
    return if (base.length <= 22) base else base.take(21) + "…"
}

private fun WebSearchSettingsUiState.hubStatusLabel(): String = when {
    !enabled -> "已停用"
    realSearchConfigured -> "已配置"
    configured -> "链接可用"
    else -> "待配置"
}

private fun WebSearchSettingsUiState.hubStatusTone(): SettingsSectionTone = when {
    !enabled -> SettingsSectionTone.NEUTRAL
    realSearchConfigured -> SettingsSectionTone.ONLINE
    configured -> SettingsSectionTone.INFO
    else -> SettingsSectionTone.WARNING
}

@Composable
private fun toneColor(tone: SettingsSectionTone): Color {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        SettingsSectionTone.ONLINE -> if (dark) Color(0xFF81C995) else Color(0xFF188038)
        SettingsSectionTone.INFO -> MaterialTheme.colorScheme.primary
        SettingsSectionTone.WARNING -> if (dark) Color(0xFFFDD663) else Color(0xFFB06000)
        SettingsSectionTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun heroToneColor(tone: SettingsSectionTone): Color = when (tone) {
    SettingsSectionTone.ONLINE -> Color(0xFF81C995)
    SettingsSectionTone.INFO -> Color(0xFF8AB4F8)
    SettingsSectionTone.WARNING -> Color(0xFFFDD663)
    SettingsSectionTone.NEUTRAL -> Color(0xFF9AA0A6)
}

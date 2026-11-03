package com.muyuchat.feature.settings

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.rememberMcaHaptics
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.core.designsystem.McaIconSize

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

private enum class SettingsSection(val title: String) {
    RUNTIME("运行"),
    LOGS("日志/诊断"),
    PRIVACY("隐私与数据"),
    SEARCH("联网检索"),
    EXPERIMENTS("实验功能"),
    ABOUT("关于")
}

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
    val haptics = rememberMcaHaptics()
    val startSection = if (startInWebSearch) SettingsSection.SEARCH else SettingsSection.RUNTIME
    var section by rememberSaveable(startInWebSearch) { mutableStateOf(startSection) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The title block takes the remaining width so the gear keeps its slot even at
                // large font scales instead of being pushed against the window edge.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaIconButton(
                        contentDescription = "返回聊天",
                        onClick = onBack,
                        glyphSize = McaIconSize.L,
                        tint = LocalContentColor.current
                    ) {
                        Icon(McaIcons.Close, contentDescription = null)
                    }
                    Column {
                        Text("系统设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("运行、日志、隐私、检索与关于", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(McaIcons.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            // Wrap instead of scrolling: with four sections a phone-width row could clip the last
            // tab, and a horizontal scroll made it look truncated at 2.0x font scale.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSection.entries.forEach { item ->
                    McaChip(
                        selected = section == item,
                        onClick = {
                            if (section != item) haptics.tick()
                            section = item
                        },
                        // This selection owner ticks only when the selected section changes.
                        hapticsEnabled = false,
                        label = { Text(item.title) }
                    )
                }
            }
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                fadeIn(McaMotion.fade(150)) togetherWith fadeOut(McaMotion.fade(100))
            },
            label = "settingsSectionContent",
            modifier = Modifier.weight(1f)
        ) { targetSection ->
            when (targetSection) {
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

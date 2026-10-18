package com.muyuchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

data class SettingsCleanupBusyUiState(
    val chatHistory: Boolean = false,
    val imageLibrary: Boolean = false,
    val fileLibrary: Boolean = false,
    val persistentPrefixCache: Boolean = false
) {
    val any: Boolean
        get() = chatHistory || imageLibrary || fileLibrary || persistentPrefixCache
}

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
    val lastCheckedText: String = "",
    val message: String? = null
)

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

data class WebSearchBackupProviderUiState(
    val enabled: Boolean = false,
    val provider: String = "SEARXNG",
    val providerLabel: String = "SearxNG",
    val endpoint: String = "",
    val apiKey: String = "",
    val configured: Boolean = false
)

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
    EXPERIMENTS("实验功能")
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "返回聊天")
                    }
                    Column {
                        Text("系统设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("运行、日志、隐私与实验功能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                SettingsSection.entries.forEach { item ->
                    FilterChip(
                        selected = section == item,
                        onClick = { section = item },
                        label = { Text(item.title) }
                    )
                }
            }
        }
        when (section) {
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
                modifier = Modifier.weight(1f)
            )
            SettingsSection.LOGS -> LogsApiScreen(
                state = state,
                onRefreshLogs = onRefreshLogs,
                onRefreshDiagnostics = onRefreshDiagnostics,
                onExportDiagnostics = onExportDiagnostics,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.PRIVACY -> PrivacyDataScreen(
                state = state,
                onClearChatHistory = onClearChatHistory,
                onClearImageLibrary = onClearImageLibrary,
                onClearFileLibrary = onClearFileLibrary,
                onPersistentPrefixCacheEnabledChanged = onPersistentPrefixCacheEnabledChanged,
                onClearPersistentPrefixCache = onClearPersistentPrefixCache,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.SEARCH -> SearchSettingsScreen(
                state = state.webSearch,
                onSave = onSaveWebSearchSettings,
                onPreflight = onPreflightWebSearch,
                onTest = onTestWebSearch,
                onTestTurn = onTestWebSearchTurn,
                onClearDiagnostics = onClearWebSearchDiagnostics,
                onOpenWebPage = onOpenWebPage,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.EXPERIMENTS -> ExperimentsScreen(
                state = state,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

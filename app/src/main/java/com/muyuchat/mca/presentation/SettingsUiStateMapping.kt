package com.muyuchat.mca.presentation

// MainUiState -> SettingsUiState, moved verbatim from MainActivity during P2.
// This is the largest single mapping in the app: it is the list the settings screen renders.
// No field, default, count or ordering was edited.


import android.content.Context
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.feature.settings.AppUpdateSettingsUiState
import com.muyuchat.feature.settings.SettingsUiState
import com.muyuchat.feature.settings.SettingsCleanupBusyUiState
import com.muyuchat.feature.settings.WebSearchBackupProviderUiState
import com.muyuchat.feature.settings.WebSearchDiagnosticSourceUiItem
import com.muyuchat.feature.settings.WebSearchDiagnosticUiItem
import com.muyuchat.feature.settings.WebSearchSettingsUiState
import com.muyuchat.mca.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

internal fun MainUiState.settingsUiState(): SettingsUiState = SettingsUiState(
    params = params,
    stats = stats,
    logs = logs,
    agentLogs = agentLogs.map { log ->
        val recommendation = runCatching { org.json.JSONObject(log.recommendationJson) }.getOrNull()
        val name = recommendation
            ?.optJSONObject("recommended")
            ?.optJSONObject("model")
            ?.optString("displayName")
            .orEmpty()
        val risk = recommendation?.optString("risk").orEmpty()
        "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.time))} · ${name.ifBlank { "无推荐" }} · risk=$risk · confirmed=${log.userConfirmed}"
    },
    apiEnabled = apiEnabled,
    restEnabled = restEnabled,
    apiKey = apiKey,
    localApiAddress = localApiAddress,
    openApiAddress = openApiAddress,
    loadedModelId = loadedModelId,
    nativeStatsJson = nativeStatsJson,
    diagnosticReport = diagnosticReport,
    chatSessionCount = chatSessions.size,
    imageAssetCount = images.size,
    imageAssetBytes = images.sumOf { it.sizeBytes },
    fileAssetCount = files.size,
    fileAssetBytes = files.sumOf { it.sizeBytes },
    persistentPrefixCacheEnabled = persistentPrefixCacheEnabled,
    persistentPrefixCacheEntryCount = persistentPrefixCacheEntryCount,
    persistentPrefixCacheBytes = persistentPrefixCacheBytes,
    cleanupBusy = SettingsCleanupBusyUiState(
        chatHistory = settingsOperations.cleanup == SettingsCleanupOperation.CHAT_HISTORY,
        imageLibrary = settingsOperations.cleanup == SettingsCleanupOperation.IMAGE_LIBRARY,
        fileLibrary = settingsOperations.cleanup == SettingsCleanupOperation.FILE_LIBRARY,
        persistentPrefixCache = settingsOperations.cleanup == SettingsCleanupOperation.PERSISTENT_PREFIX_CACHE
    ),
    statusMessage = statusMessage,
    appUpdate = AppUpdateSettingsUiState(
        autoCheckEnabled = appUpdate.autoCheckEnabled,
        status = appUpdate.status.name,
        currentVersionName = appUpdate.currentVersionName,
        latestVersionName = appUpdate.latestVersionName,
        latestTitle = appUpdate.latestTitle,
        releaseNotes = appUpdate.releaseNotes,
        releaseUrl = appUpdate.releaseUrl,
        apkSizeText = appUpdate.apkSizeBytes.takeIf { it > 0L }?.let(::formatAppUpdateBytes).orEmpty(),
        canDownload = appUpdate.canDownload,
        downloadedBytes = appUpdate.downloadedBytes,
        downloadTotalBytes = appUpdate.downloadTotalBytes,
        lastCheckedText = appUpdate.lastCheckedAtMillis.takeIf { it > 0L }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        }.orEmpty(),
        message = appUpdate.message
    ),
    webSearch = WebSearchSettingsUiState(
        enabled = webSearchConfig.enabled,
        provider = webSearchConfig.provider.name,
        providerLabel = webSearchConfig.providerLabel,
        endpoint = webSearchConfig.endpoint,
        apiKey = webSearchConfig.apiKey,
        maxResults = webSearchConfig.maxResults,
        fetchPageContent = webSearchConfig.fetchPageContent,
        triggerMode = webSearchConfig.triggerMode.name,
        triggerModeLabel = webSearchConfig.triggerMode.label,
        researchMode = webSearchConfig.researchMode.name,
        researchModeLabel = webSearchConfig.researchMode.label,
        configured = webSearchConfig.configured,
        realSearchConfigured = webSearchConfig.realSearchConfigured,
        realSearchProviderLabel = webSearchConfig.realSearchProviderLabel,
        backupProviders = webSearchConfig.backupProviders.take(3).map { backup ->
            WebSearchBackupProviderUiState(
                enabled = backup.enabled,
                provider = backup.provider.name,
                providerLabel = backup.providerLabel,
                endpoint = backup.endpoint,
                apiKey = backup.apiKey,
                configured = backup.configured
            )
        },
        statusMessage = webSearchStatusMessage ?: statusMessage,
        operationBusy = settingsOperations.webSearch != null,
        diagnostics = webSearchDiagnostics.map { record ->
            WebSearchDiagnosticUiItem(
                createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(record.createdAt)),
                providerLabel = record.providerLabel,
                triggerModeLabel = record.triggerModeLabel,
                query = record.query,
                success = record.success,
                message = record.message,
                sourceCount = record.sourceCount,
                elapsedMs = record.elapsedMs,
                searchedQueries = record.searchedQueries,
                directUrls = record.directUrls,
                healthScore = record.healthScore,
                healthLabel = record.healthLabel,
                healthReasons = record.healthReasons,
                qualityScore = record.qualityScore,
                qualityLabel = record.qualityLabel,
                qualityReasons = record.qualityReasons,
                sourceTrustSummary = record.sourceTrustSummary,
                researchConfidenceScore = record.researchConfidenceScore,
                researchConfidenceLabel = record.researchConfidenceLabel,
                researchEvidenceGroups = record.researchEvidenceGroups,
                researchConflictWarnings = record.researchConflictWarnings,
                researchSynthesisGuidance = record.researchSynthesisGuidance,
                triggerReasons = record.triggerReasons,
                warnings = record.warnings,
                cacheStatus = record.cacheStatus,
                closedLoopChecks = record.closedLoopChecks,
                topSources = record.topSources.map { source ->
                    val trustClass = source.webSearchSourceTrustClass()
                    WebSearchDiagnosticSourceUiItem(
                        title = source.title.ifBlank { source.url },
                        url = source.url,
                        snippet = source.snippet,
                        provider = source.provider,
                        trustLabel = trustClass.label,
                        hostLabel = source.webSearchHostLabel()
                    )
                }
            )
        }
    )
)

internal fun performUpdateParams(
    params: GenerationParams,
    context: Context,
    uiStateStore: UiStateWriter<SettingsParameterFields>,
    engine: McaInferenceService,
    assistantProfileCoordinator: AssistantProfileCoordinator,
    runtimeUserOverrideFields: MutableSet<String>,
    directParameterStageGeneration: AtomicLong,
    getDirectParameterStageJob: () -> Job?,
    getPendingProfileTransactionId: () -> String?,
    discardPendingAgentProfile: () -> Unit,
    persistModelGenerationParams: (MainUiState, GenerationParams) -> Unit,
    stageDirectParameterProfile: (GenerationParams) -> Unit,
) {
    val state = uiStateStore.current
    val previous = state.params
    val scopedParams = params.copy(systemPrompt = state.selectedAssistant()?.systemPrompt ?: previous.systemPrompt)
    val changedRuntimeFields = runtimeParameterChanges(previous, scopedParams)
    if (changedRuntimeFields.isNotEmpty()) {
        runtimeUserOverrideFields += changedRuntimeFields
        GenerationPreferences.persistRuntimeUserOverrideFields(context, runtimeUserOverrideFields)
    }
    GenerationPreferences.persistGenerationParams(context, scopedParams)
    persistModelGenerationParams(state, scopedParams)
    val updatedAssistants = updatedAssistantsWithParams(state.assistants, state.selectedAssistantId, scopedParams)
    val hasLoadedModel = uiStateStore.current.loadedModelId != null
    val reloadRequired = hasLoadedModel && profileDiffersFrom(scopedParams, engine.activeExecutionProfile())
    uiStateStore.update { current ->
        current.copy(
            params = scopedParams,
            assistants = updatedAssistants,
            reloadRequired = reloadRequired,
            statusMessage = if (reloadRequired) {
                "运行时参数已修改，将在下次生成或重载后生效；当前模型继续使用原参数。"
            } else if (changedRuntimeFields.isNotEmpty() && current.loadedModelId == null) {
                "运行时参数已保存，下次加载模型时将自动应用该配置。"
            } else {
                current.statusMessage
            }
        )
    }
    assistantProfileCoordinator.persistAssistantsAsync()
    if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
        stageDirectParameterProfile(scopedParams)
    } else if (!reloadRequired && changedRuntimeFields.isNotEmpty()) {
        directParameterStageGeneration.incrementAndGet()
        getDirectParameterStageJob()?.cancel()
        if (getPendingProfileTransactionId() != null) discardPendingAgentProfile()
    }
}

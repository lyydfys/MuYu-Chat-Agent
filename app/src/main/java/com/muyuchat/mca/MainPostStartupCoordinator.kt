package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.composition.McaAppContainer
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class MainPostStartupCoordinator(
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val application: Application,
    private val container: McaAppContainer,
    private val engine: McaInferenceService,
    private val modelStore: ModelStoreRepository,
    private val imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator,
    private val offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    private val chatModelCoordinator: ChatModelCoordinator,
    private val restorePendingContentImportPreview: () -> Unit,
    private val observeManagedDownloadsAction: () -> Unit,
    private val schedulePersistentPrefixCacheClear: (Long, String, String) -> Unit,
    private val persistentPrefixCacheOperationSequence: AtomicLong,
    private val managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
    private val chatSessionPersistenceMutex: Mutex,
    private val modelIdentityReferenceMigration: ModelIdentityReferenceMigration,
    private val getDurableChatSessions: () -> List<ChatSessionRecord>,
    private val setDurableChatSessions: (List<ChatSessionRecord>) -> Unit,
    private val skipInitialModelDiscovery: Boolean
) {
    private val modelImportCoordinator = modelImportAndDownloadCoordinator

    fun runPostStartupWork() {
        restorePendingContentImportPreview()
        imageLibraryStartupReconciliation.start()
        observeManagedDownloadsAction()
        modelImportCoordinator.observeManagedImports()

        scope.launch {
            offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle()?.let { bundle ->
                store.update { state ->
                    if (state.offlineTranslationDownloading || state.offlineTranslationInstalling) state
                    else state.copy(
                        offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                        offlineTranslationStatus = offlinePromptTranslationAndChatImageCoordinator.offlineTranslationBundleReadyStatus(bundle)
                    )
                }
            }
        }

        val persistentPrefixCacheEnabled = store.current.persistentPrefixCacheEnabled
        engine.setPersistentPrefixCacheEnabled(persistentPrefixCacheEnabled)
        if (persistentPrefixCacheEnabled) {
            refreshPersistentPrefixCacheSummary()
        } else {
            val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
            schedulePersistentPrefixCacheClear(
                operation,
                "已保持关闭持久化前缀缓存，并清空本机缓存",
                "持久化前缀缓存已关闭；旧缓存清理未完成，可稍后重试"
            )
        }

        runCatching {
            File(application.cacheDir, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).deleteRecursively()
        }

        scope.launch(Dispatchers.IO) {
            val loaded = loadInitialTextualInversionLibraryState(container.localImageTextualInversionStore)
            store.update { state ->
                if (!state.localImageTextualInversionLoading) {
                    state
                } else {
                    state.copy(
                        localImageTextualInversions = loaded.records,
                        localImageTextualInversionLoading = false,
                        localImageTextualInversionMessage = loaded.message
                    )
                }
            }
        }

        chatModelCoordinator.launchModelOperation("恢复运行记录失败") {
            chatModelCoordinator.recoverInterruptedRuntimeProfiles()
        }

        scope.launch {
            engine.stats.collect { stats ->
                val nativeStats = withContext(Dispatchers.IO) {
                    runCatching { engine.nativeStatsJson() }.getOrDefault("{}")
                }
                if (engine.stats.value != stats) return@collect
                store.update { state ->
                    val lifecycle = when {
                        state.isGenerating -> AgentEngineLifecycle.GENERATING
                        stats.loaded -> AgentEngineLifecycle.READY
                        state.engineLifecycle in setOf(
                            AgentEngineLifecycle.LOADING,
                            AgentEngineLifecycle.RELOADING,
                            AgentEngineLifecycle.ROLLING_BACK,
                            AgentEngineLifecycle.STOPPING
                        ) -> state.engineLifecycle
                        stats.lastError?.isNotBlank() == true -> AgentEngineLifecycle.ERROR
                        else -> AgentEngineLifecycle.UNLOADED
                    }
                    state.copy(
                        stats = stats,
                        nativeStatsJson = nativeStats,
                        engineLifecycle = lifecycle
                    )
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            val readinessPort = managedRuntimeReadinessPortProvider()
            refreshRuntimeReadiness(readinessPort)
            if (!skipInitialModelDiscovery) {
                recoverModelOperation(
                    operation = { modelStore.recoverInstalledQairtBundles() },
                    onSuccess = { recovered ->
                        if (recovered.isNotEmpty()) {
                            refreshRuntimeReadiness(readinessPort)
                            store.update { state ->
                                state.copy(statusMessage =
                                    "已恢复 ${recovered.size} 个现有 QAIRT NPU 模型；首次加载会自动隔离安全启动。")
                            }
                        }
                    },
                    onFailure = { error ->
                        Log.e("McaModelOperation", "恢复模型目录失败", error)
                        store.update { state ->
                            state.copy(statusMessage =
                                "恢复模型目录失败：${error.message.orEmpty()}。请检查模型文件和存储空间后重试。")
                        }
                    }
                )
            }
            reconcileModelIdentities()
            refreshRuntimeReadiness(readinessPort)
        }
    }

    fun refreshPersistentPrefixCacheSummary() {
        scope.launch(Dispatchers.IO) {
            val summary = engine.persistentPrefixCacheSummary()
            store.update {
                it.copy(
                    persistentPrefixCacheEntryCount = summary.entryCount,
                    persistentPrefixCacheBytes = summary.totalBytes
                )
            }
        }
    }

    suspend fun reconcileModelIdentities() =
        reconcileModelIdentitiesFor(
            chatSessionPersistenceMutex = chatSessionPersistenceMutex,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            store = store,
            getDurableChatSessions = getDurableChatSessions,
            setDurableChatSessions = setDurableChatSessions
        )
}

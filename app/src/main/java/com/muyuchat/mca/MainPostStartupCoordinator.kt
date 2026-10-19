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

// Startup timing goes through StartupLog (owned by AppStartupViewModel) so these lines share one
// tag and one T0 with the phase stamps instead of forming a second MCAStartup timeline.

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
        val startupThread = Thread.currentThread().name
        StartupLog.stamp("post-work-begin", thread = startupThread)
        restorePendingContentImportPreview()
        val reconcileStartedAt = android.os.SystemClock.elapsedRealtime()
        imageLibraryStartupReconciliation.start()
        StartupLog.stamp("image-reconcile-submit", opStartedAt = reconcileStartedAt, thread = Thread.currentThread().name)
        observeManagedDownloadsAction()
        modelImportCoordinator.observeManagedImports()

        scope.launch {
            // Bundle verification walks and hashes files. It used to run on the main dispatcher
            // (viewModelScope) and could freeze the first chat frame for seconds — the MIUI hang
            // watchdog caught it inside LocalImageModelIdentity's FileInputStream read. Only the
            // resulting state write stays on main.
            val verifyStartedAt = android.os.SystemClock.elapsedRealtime()
            val bundle = withContext(Dispatchers.IO) {
                offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle()
            }
            StartupLog.stamp("offline-bundle", opStartedAt = verifyStartedAt)
            bundle?.let { verified ->
                store.update { state ->
                    if (state.offlineTranslationDownloading || state.offlineTranslationInstalling) state
                    else state.copy(
                        offlineTranslationInstalled = verified.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                        offlineTranslationStatus = offlinePromptTranslationAndChatImageCoordinator.offlineTranslationBundleReadyStatus(verified)
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
            val inversionStartedAt = android.os.SystemClock.elapsedRealtime()
            val loaded = loadInitialTextualInversionLibraryState(container.localImageTextualInversionStore)
            StartupLog.stamp("textual-inversion", opStartedAt = inversionStartedAt)
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
            val recoverStartedAt = android.os.SystemClock.elapsedRealtime()
            chatModelCoordinator.recoverInterruptedRuntimeProfiles()
            StartupLog.stamp("recover-profiles", opStartedAt = recoverStartedAt, thread = Thread.currentThread().name)
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
            val readinessStartedAt = android.os.SystemClock.elapsedRealtime()
            val readinessPort = managedRuntimeReadinessPortProvider()
            refreshRuntimeReadiness(readinessPort)
            StartupLog.stamp("readiness-first", opStartedAt = readinessStartedAt)
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
            val reconcileIdsStartedAt = android.os.SystemClock.elapsedRealtime()
            reconcileModelIdentities()
            StartupLog.stamp("reconcile-identities", opStartedAt = reconcileIdsStartedAt)
            val readinessSecondStartedAt = android.os.SystemClock.elapsedRealtime()
            refreshRuntimeReadiness(readinessPort)
            StartupLog.stamp("readiness-second", opStartedAt = readinessSecondStartedAt)
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

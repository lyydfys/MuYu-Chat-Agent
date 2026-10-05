package com.muyuchat.mca

import android.app.Application
import android.content.Context
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity

/**
 * R3/C3-S1（插队裁定 2）：自 MainViewModel 具名化的端口适配器——只持有真实依赖
 * （store / 容器对象 / 锁实例 / 运行期准入 holder），不再反向捕获 ViewModel。
 */

internal class LocalImageAdapterPortAdapter(
    private val store: UiStateStore,
    private val loraStoreRef: LocalImageLoraStore,
    private val textualInversionStoreRef: TextualInversionStore,
    private val loraLifecycleLock: Any,
    private val textualInversionLifecycleLock: Any
) : LocalImageAdapterPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override val loraStore: LocalImageLoraStore
        get() = loraStoreRef

    override val textualInversionStore: TextualInversionStore
        get() = textualInversionStoreRef

    override fun <T> withLoraLifecycleLock(block: () -> T): T =
        synchronized(loraLifecycleLock) { block() }

    override fun <T> withTextualInversionLifecycleLock(block: () -> T): T =
        synchronized(textualInversionLifecycleLock) { block() }
}

internal class WebSearchConfigPortAdapter(
    private val store: UiStateStore,
    private val appContext: Application,
    private val webSearchStoreRef: WebSearchStore,
    private val diagnosticStoreRef: WebSearchDiagnosticStore,
    private val providerRef: WebSearchProvider
) : WebSearchConfigPort {
    override val context: Application
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun saveConfig(config: WebSearchConfig) {
        webSearchStoreRef.save(config)
    }

    override fun appendDiagnostic(
        record: WebSearchDiagnosticRecord
    ): List<WebSearchDiagnosticRecord> = diagnosticStoreRef.add(record)

    override val provider: WebSearchProvider
        get() = providerRef
}

internal class VisionAttachmentPortAdapter(
    private val store: UiStateStore,
    private val appContext: Application,
    private val engineRef: McaInferenceService,
    private val admissionRef: RuntimeAdmissionState
) : VisionAttachmentPort {
    override val context: Context
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun nativeStatsJson(): String = engineRef.nativeStatsJson()

    override val activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = admissionRef.activeRuntimeIdentity

    override val activeVisionProjectorStamp: LoadedVisionProjectorStamp?
        get() = admissionRef.activeVisionProjectorStamp
}

internal class LocalApiPayloadPortAdapter(
    private val store: UiStateStore,
    private val engineRef: McaInferenceService,
    private val runtimeProfileStoreRef: ModelRuntimeProfileStore,
    private val tuningRef: TuningOwner,
    private val admissionRef: RuntimeAdmissionState
) : LocalApiPayloadPort {
    override fun uiState(): MainUiState = store.current

    override val activeTuningJobId: String?
        get() = tuningRef.activeTuningJobId

    override fun tuningCancelRequested(): Boolean =
        tuningRef.adaptiveTuningCancelRequested.get()

    override fun tuningPauseRequested(): Boolean =
        tuningRef.adaptiveTuningPauseRequested.get()

    override fun runtimeProfileJson(): String =
        runtimeProfileJsonFor(store.current, engineRef, admissionRef.activeRuntimeIdentity)

    override fun nativeStatsJson(): String = engineRef.nativeStatsJson()

    override fun runtimeLoaded(): Boolean = engineRef.stats.value.loaded

    override suspend fun modelRuntimeSummaries(
        modelIds: Collection<String>
    ): List<PersistedModelRuntimeSummary> =
        runtimeProfileStoreRef.modelRuntimeSummaries(modelIds)
}

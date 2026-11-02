package com.muyuchat.mca

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.presentation.LocalApiImageWorkerGateway
import com.muyuchat.mca.presentation.LocalApiImageLoraGateway
import com.muyuchat.mca.presentation.LocalApiImageBounds
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.ChatImageActionKind
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.LocalApiTuningJobCreateRequest
import com.muyuchat.api.local.LocalApiIdempotencyContext
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.TuningExecutionProfile
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.api.local.LocalApiControlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.benchmark.BenchmarkRunner
import kotlinx.coroutines.Deferred
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.mca.presentation.chatContextSnapshot
import com.muyuchat.mca.presentation.lifecycleAfterGeneration

/**
 * R3/C3-S1（插队裁定 2）：自 MainViewModel 具名化的端口适配器——只持有真实依赖
 * （store / 容器对象 / 锁实例 / 运行期准入 holder），不再反向捕获 ViewModel。
 */

internal class LocalImageAdapterPortAdapter(
    private val store: UiStateWriter<LocalImageAdapterFields>,
    private val loraStoreRef: LocalImageLoraStore,
    private val textualInversionStoreRef: TextualInversionStore,
    private val loraLifecycleLock: Any,
    private val textualInversionLifecycleLock: Any
) : LocalImageAdapterPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (LocalImageAdapterFields) -> LocalImageAdapterFields) {
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
    private val store: UiStateWriter<WebSearchFields>,
    private val appContext: Application,
    private val webSearchStoreRef: WebSearchStore,
    private val diagnosticStoreRef: WebSearchDiagnosticStore,
    private val providerRef: WebSearchProvider
) : WebSearchConfigPort {
    override val context: Application
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (WebSearchFields) -> WebSearchFields) {
        store.update(transform)
    }

    override fun saveConfig(config: WebSearchConfig) {
        webSearchStoreRef.save(config)
    }

    override fun appendDiagnostic(
        record: WebSearchDiagnosticRecord
    ): List<WebSearchDiagnosticRecord> = diagnosticStoreRef.add(record)

    override fun clearDiagnostics(): List<WebSearchDiagnosticRecord> =
        diagnosticStoreRef.clear()

    override val provider: WebSearchProvider
        get() = providerRef
}

internal class VisionAttachmentPortAdapter(
    private val store: UiStateWriter<VisionAttachmentFields>,
    private val appContext: Application,
    private val engineRef: McaInferenceService,
    private val admissionRef: RuntimeAdmissionState
) : VisionAttachmentPort {
    override val context: Context
        get() = appContext

    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (VisionAttachmentFields) -> VisionAttachmentFields) {
        store.update(transform)
    }

    override fun nativeStatsJson(): String = engineRef.nativeStatsJson()

    override val activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = admissionRef.activeRuntimeIdentity

    override val activeVisionProjectorStamp: LoadedVisionProjectorStamp?
        get() = admissionRef.activeVisionProjectorStamp
}

internal class LocalApiPayloadPortAdapter(
    private val store: UiStateReadPort,
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

internal class ImagePromptTokenMeasurementHostAdapter(
    private val store: UiStateReadPort,
    private val measurementCacheRef: ImagePromptTokenMeasurementCache,
    private val tokenizerDescriptorsRef: java.util.concurrent.ConcurrentHashMap<String, LocalImagePromptTokenizerDescriptor>
) : ImagePromptTokenMeasurementHost {
    override val models: List<LocalImageModelRecord>
        get() = store.current.localImageModels

    override fun cachedMeasurement(cacheKey: String): ImagePromptTokenMeasurement? =
        measurementCacheRef.get(cacheKey)

    override fun cacheMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement) {
        measurementCacheRef.put(cacheKey, measurement)
    }

    override fun cachedDescriptor(modelKey: String): LocalImagePromptTokenizerDescriptor? =
        tokenizerDescriptorsRef[modelKey]

    override fun cacheDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor) {
        tokenizerDescriptorsRef.putIfAbsent(modelKey, descriptor)
    }
}

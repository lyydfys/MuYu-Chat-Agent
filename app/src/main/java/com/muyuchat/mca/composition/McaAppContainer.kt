package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.mca.AssistantStore
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.mca.ManagedRuntimeReadinessRefreshGate
import com.muyuchat.mca.RuntimeAdmissionState
import com.muyuchat.mca.GenerationPreferences
import com.muyuchat.mca.BackgroundImageStore
import com.muyuchat.mca.ChatImageToolAuthorizationStore
import com.muyuchat.mca.ChatSessionStore
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.GlobalChatAppearanceStore
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.LocalImageLoraStore
import com.muyuchat.mca.LocalImageModelStore
import com.muyuchat.mca.LocalImageUpscalerStore
import com.muyuchat.mca.ModelGenerationProfileStore
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.mca.OfflinePromptTranslationBundleInstaller
import com.muyuchat.mca.TextualInversionStore
import com.muyuchat.mca.WebSearchDiagnosticStore
import com.muyuchat.mca.WebSearchStore
import com.muyuchat.mca.WorldBookStore

/**
 * R2：组合根骨架（方案 §9 / §14.4）。
 *
 * 职责边界：只装配“仅依赖 Application（或零依赖）”的稳定叶子对象；不承载状态机、数据库逻辑或 UI 分支。
 * ViewModel/UI/Worker/Local API 通过接口或本容器读取装配结果，而不是各自 new 出平行实例。
 *
 * 逐域扩张（构造参数 1:1 保留，零行为变化）：
 * - S12：runtime/model 配置存储、会话与授权存储、世界书/知识库/助手存储（7 个）；
 * - S13：外观/背景/云 API/离线翻译/webSearch/图像模型资产族（+10 个）；
 * - 后续切片：带 port/scope 的 owner（如 AppUpdateOwner）待其 port 依赖先行解耦后再迁入。
 */
internal class McaAppContainer(private val application: Application) {

    val runtimeProfileStore = ModelRuntimeProfileStore(application)

    val modelGenerationProfileStore = ModelGenerationProfileStore(application)

    val chatSessionStore = ChatSessionStore(application)

    val chatImageToolAuthorizationStore = ChatImageToolAuthorizationStore(application)

    val worldBookStore = WorldBookStore(application)

    val knowledgeBaseStore = KnowledgeBaseStore(application)

    val assistantStore = AssistantStore(application)

    val globalChatAppearanceStore = GlobalChatAppearanceStore(application)

    val backgroundImageStore = BackgroundImageStore(application)

    val cloudApiStore = CloudApiStore(application)

    val offlineTranslationInstaller = OfflinePromptTranslationBundleInstaller(application)

    val webSearchStore = WebSearchStore(application)

    val webSearchDiagnosticStore = WebSearchDiagnosticStore(application)

    val localImageModelStore = LocalImageModelStore(application)

    val localImageLoraStore = LocalImageLoraStore(application)

    val localImageTextualInversionStore = TextualInversionStore(application)

    val localImageUpscalerStore = LocalImageUpscalerStore(application)

    // R3/C2（插队裁定 2/3）：自 MainViewModel 迁入的 application-only 装配与运行期准入态 holder。
    val deviceProfileReader = DeviceProfileReader(application)

    val modelStore = ModelStoreRepository(application)

    val benchmarkHistoryLogger = BenchmarkHistoryLogger(application)

    val readinessRefreshGate = ManagedRuntimeReadinessRefreshGate()

    val advisor = AgentAdvisor()

    /** R3/C2（插队裁定 2）：调参 owner 工厂——VM 只传它自有的运行时对象（store/scope/engine），
     *  其余依赖由容器装配。 */
    fun createTuningOwner(store: UiStateStore, scope: CoroutineScope, engine: McaInferenceService): TuningOwner =
        TuningOwner(
            store = store,
            scope = scope,
            engine = engine,
            runtimeProfileStore = runtimeProfileStore,
            modelStore = modelStore,
            benchmarkHistoryLogger = benchmarkHistoryLogger,
            readinessGate = readinessRefreshGate,
            deviceProfileReader = deviceProfileReader,
            application = application,
            advisor = advisor,
            admission = runtimeAdmission
        )

    val runtimeAdmission = RuntimeAdmissionState(
        GenerationPreferences.loadRuntimeUserOverrideFields(application).toMutableSet()
    )
}

package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicLong
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.engine.ModelRuntimeIdentity

/**
 * R3/C2（插队裁定 3）：自 MainViewModel 迁出的“运行期准入态”，由组合根持有；
 * VM 以 get/set 别名共享同一实例，owner 以构造注入读取——字段所有权离开 God object，
 * 但读写语义、初始化时点与调用点全部不变（零行为）。
 */
internal class RuntimeAdmissionState(
    val runtimeUserOverrideFields: MutableSet<String>
) {
    var activeRuntimeIdentity: ModelRuntimeIdentity? = null

    var activeModelForRuntimeProfile: ModelManifest? = null

    var activeAdaptiveRecommendation: AdaptiveTuningRecommendation? = null

    var activeVisionProjectorStamp: LoadedVisionProjectorStamp? = null

    @Volatile
    var activeImageGenerationJobId: String? = null

    @Volatile
    var activeImageGenerationBackend: ImageBackend? = null

    @Volatile
    var activeImageGenerationModelId: String? = null

    var imageGenerationJob: Job? = null

    @Volatile
    var imageLibraryBackupJob: Job? = null

    @Volatile
    var activeWebSearchDeferred: Deferred<WebSearchResult>? = null

    val imageFavoriteMutationSequence = AtomicLong(0L)

    val latestImageFavoriteMutations =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    val contentImportSelectionRevision = AtomicLong(0L)
}

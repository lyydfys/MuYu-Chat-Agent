package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
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
}

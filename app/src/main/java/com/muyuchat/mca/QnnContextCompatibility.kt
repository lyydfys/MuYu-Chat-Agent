package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile

internal fun qnnContextSocCompatibilityMessage(
    device: DeviceProfile,
    binaryMetadata: QnnBinaryMetadataDiagnostics,
    allowKnownForwardCompatibility: Boolean = false
): String? {
    if (!binaryMetadata.targetSocKnown) return null
    val currentChipset = device.accelerationProfile.chipsetCode.ifBlank { device.socModel }
    val expectedSocModel = DeviceAccelerationAnalyzer
        .expectedQnnSocModelForChipsetCode(currentChipset)
        ?: return null
    if (binaryMetadata.socModel == expectedSocModel) return null
    // SD1.5 QNN 2.28 contexts exported for SM8550 have been verified on
    // SM8750. They remain eligible only when the caller also proves a real
    // graph execution; reverse compatibility is deliberately not assumed.
    if (allowKnownForwardCompatibility &&
        binaryMetadata.socModel == 43 &&
        expectedSocModel == 69
    ) {
        return null
    }
    val bundleChipset = DeviceAccelerationAnalyzer.userFacingQnnSocModelName(binaryMetadata.socModel)
    val deviceChipset = DeviceAccelerationAnalyzer.userFacingChipsetName(currentChipset)
        ?: DeviceAccelerationAnalyzer.userFacingQnnSocModelName(expectedSocModel)
    return "QNN 模型包适配 $bundleChipset，当前设备为 $deviceChipset。" +
        "请选择为当前骁龙平台导出的 QNN 模型包。"
}

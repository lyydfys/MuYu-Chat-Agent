package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.QnnRuntimeProfileSelector

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
    // A context built for an older HTP architecture can run on a newer
    // physical transport. This function supplies diagnostics only; callers
    // must never override a successful real graph execution with this hint.
    val contextArch = QnnRuntimeProfileSelector.htpArchVersionForSocModel(binaryMetadata.socModel)
    val deviceArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(currentChipset)
    if (allowKnownForwardCompatibility && contextArch != null && deviceArch != null && deviceArch >= contextArch) {
        return null
    }
    val bundleChipset = DeviceAccelerationAnalyzer.userFacingQnnSocModelName(binaryMetadata.socModel)
    val deviceChipset = DeviceAccelerationAnalyzer.userFacingChipsetName(currentChipset)
        ?: DeviceAccelerationAnalyzer.userFacingQnnSocModelName(expectedSocModel)
    return "QNN 模型包适配 $bundleChipset，当前设备为 $deviceChipset。" +
        "请选择为当前骁龙平台导出的 QNN 模型包。"
}

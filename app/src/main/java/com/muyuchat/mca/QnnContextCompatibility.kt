package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile

private val QNN_CONTEXT_BUILD_SDK_REGEX = Regex("""(?:^|[^A-Za-z0-9])v(\d+\.\d+(?:\.\d+)?)(?:\.|_|$)""")

/**
 * QNN System metadata does not expose a separate SDK string on every context
 * binary.  Qualcomm build IDs do carry the QAIRT/QNN version (for example
 * `v2.28.0.241029232508_102474`), so expose the stable major/minor/patch
 * prefix for install-time diagnostics.  Returning null is intentional: the
 * installer must call the package experimental when the SDK cannot be proven.
 */
internal fun qnnSdkVersionFromContextBuildId(buildId: String): String? =
    QNN_CONTEXT_BUILD_SDK_REGEX.find(buildId.trim())?.groupValues?.getOrNull(1)

/** Compare a context build prefix with the catalog's declared QAIRT/QNN SDK. */
internal fun qnnContextSdkMatchesDeclared(
    observed: String?,
    declared: String?
): Boolean? {
    val actual = observed?.trim().orEmpty()
    val expected = declared?.trim().orEmpty()
    if (actual.isBlank() || expected.isBlank()) return null
    val actualParts = actual.split('.', '-', '_')
    val expectedParts = expected.split('.', '-', '_')
    val comparableCount = minOf(2, actualParts.size, expectedParts.size)
    if (comparableCount == 0) return null
    return actualParts.take(comparableCount) == expectedParts.take(comparableCount)
}

internal data class QnnContextTargetIdentity(
    val socModel: Int? = null,
    val socVersion: String? = null,
    val htpArch: Int? = null,
    val sdkVersion: String? = null,
    val buildId: String? = null
)

internal fun qnnContextTargetIdentity(
    socModel: Int,
    socVersion: String,
    buildId: String,
    htpArch: Int?
): QnnContextTargetIdentity = QnnContextTargetIdentity(
    socModel = socModel.takeIf { it > 0 },
    socVersion = socVersion.trim().takeIf { it.isNotBlank() },
    htpArch = htpArch,
    sdkVersion = qnnSdkVersionFromContextBuildId(buildId),
    buildId = buildId.trim().takeIf { it.isNotBlank() }
)

internal fun qnnContextSocCompatibilityMessage(
    device: DeviceProfile,
    binaryMetadata: QnnBinaryMetadataDiagnostics
): String? {
    if (!binaryMetadata.targetSocKnown) return null
    val currentChipset = device.accelerationProfile.chipsetCode.ifBlank { device.socModel }
    val expectedSocModel = DeviceAccelerationAnalyzer
        .expectedQnnSocModelForChipsetCode(currentChipset)
        ?: return null
    if (binaryMetadata.socModel == expectedSocModel) return null
    // Never infer forward compatibility from numeric HTP generations.  A
    // context compiled for one target is only proven compatible by the real
    // native load/graph execution.  This function is diagnostic-only.
    val bundleChipset = DeviceAccelerationAnalyzer.userFacingQnnSocModelName(binaryMetadata.socModel)
    val deviceChipset = DeviceAccelerationAnalyzer.userFacingChipsetName(currentChipset)
        ?: DeviceAccelerationAnalyzer.userFacingQnnSocModelName(expectedSocModel)
    return "QNN 模型包适配 $bundleChipset，当前设备为 $deviceChipset。" +
        "请选择为当前骁龙平台导出的 QNN 模型包。"
}

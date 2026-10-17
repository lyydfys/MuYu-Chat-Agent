package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtExecutionAdmissionMode
import java.util.Locale


    private const val GIB = 1024L * 1024L * 1024L
    internal const val MAX_SPARSE_MOE_16_GIB_TIER_BYTES = 16L * GIB
    internal const val VERIFIED_QWEN36_MTP_SHA256 =
        "1fb8a998362ebb5f7f3c8ece6d4803a74ba32211c751de2e76b81e3379fbf050"

    internal fun qairtAdmissionState(
        runtime: LocalChatRuntime,
        admission: QairtExecutionAdmission?,
        admissionPassed: Boolean
    ): String {
        if (runtime != LocalChatRuntime.GENIEX_QAIRT) return "not-applicable"
        val risk = admission?.graphRisk?.name?.lowercase(Locale.US) ?: "unknown"
        if (admissionPassed || admission?.mode == QairtExecutionAdmissionMode.VERIFIED_ALLOW) {
            return "verified:$risk"
        }
        return when (admission?.mode) {
            QairtExecutionAdmissionMode.ISOLATED_DRY_RUN -> "isolated-dry-run:$risk"
            null -> "unverified:unknown"
            QairtExecutionAdmissionMode.VERIFIED_ALLOW -> "verified:$risk"
        }
    }

    /** Stable capability-only device material. Do not add telemetry fields here. */
    internal fun stableDeviceMaterial(device: DeviceProfile): String {
        val acceleration = device.accelerationProfile
        val qnn = acceleration.qnnRuntime
        return listOf(
            "socManufacturer=${device.socManufacturer.normalizedOrUnknown()}",
            "socModel=${device.socModel.normalizedOrUnknown()}",
            "socFamily=${device.socFamily.name}",
            "primaryAbi=${device.primaryAbi.normalizedOrUnknown()}",
            "supportedAbis=${device.supportedAbis.map(String::trim).filter(String::isNotBlank).distinct().sorted().joinToString(",")}",
            "cpuCores=${device.cpuCores.coerceAtLeast(0)}",
            "estimatedBigCores=${device.estimatedBigCores.coerceAtLeast(0)}",
            // ActivityManager.totalMem is the stable physical-memory value;
            // advertised/display RAM is a UI rounding hint and must not be
            // allowed to silently change a runtime profile key.
            "totalRamBytes=${(device.totalRamBytes.takeIf { it > 0L } ?: device.advertisedRamBytes).coerceAtLeast(0L)}",
            "androidApi=${device.androidApi.coerceAtLeast(0)}",
            "accelTier=${acceleration.snapdragonTier.name}",
            "chipsetCode=${acceleration.chipsetCode.normalizedOrUnknown()}",
            "qnnHtpGeneration=${acceleration.qnnHtpGeneration.normalizedOrUnknown()}",
            "qnnReady=${qnn.ready}",
            "qnnLoadable=${qnn.loadable}",
            "qnnTransportVerified=${qnn.htpTransportVerified}",
            "qnnHtpArch=${qnn.htpArchVersion}",
            "localChatStatus=${acceleration.localChat.status.name}",
            "localChatBackend=${acceleration.localChat.backend.normalizedOrUnknown()}",
            "localVisionStatus=${acceleration.localVision.status.name}",
            "localVisionBackend=${acceleration.localVision.backend.normalizedOrUnknown()}",
            "localImageStatus=${acceleration.localImage.status.name}",
            "localImageBackend=${acceleration.localImage.backend.normalizedOrUnknown()}",
            "sd15NpuCandidate=${acceleration.stableDiffusion15NpuCandidate}",
            "sdxlNpuCandidate=${acceleration.sdxlNpuCandidate}"
        ).joinToString("\n")
    }

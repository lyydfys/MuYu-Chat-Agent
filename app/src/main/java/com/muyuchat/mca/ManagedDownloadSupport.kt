package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.RemoteModelFile
import java.util.Locale

internal fun preferredQairtChipsets(device: DeviceProfile): List<String> =
        when (device.accelerationProfile.chipsetCode.trim().uppercase(Locale.US)) {
            "SM8850", "SM8850P" -> listOf(
                "qualcomm-snapdragon-8-elite-gen5",
                "qualcomm-snapdragon-8-elite"
            )
            "SM8750", "SM8750P" -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8650", "SM8650P" -> listOf(
                "qualcomm-snapdragon-8gen3",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8550", "SM8550P" -> listOf(
                "qualcomm-snapdragon-8gen2",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            // Unknown/future devices must never produce an empty admission
            // list. The repository client will prefer an exact key when one
            // exists and otherwise choose a deterministic published fallback.
            else -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
        }


/** A partially known component list is not a known total download size. */
internal fun knownManagedBundleDownloadSize(components: List<RemoteModelFile>): Long {
    if (components.isEmpty() || components.any { (it.sizeBytes ?: 0L) <= 0L }) return 0L
    return components.fold(0L) { total, file ->
        val size = requireNotNull(file.sizeBytes)
        if (total > Long.MAX_VALUE - size) return 0L
        total + size
    }
}

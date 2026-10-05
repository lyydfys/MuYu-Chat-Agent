package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelBundleInstallPlan
import com.muyuchat.core.download.ModelBundleInstaller
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.sourceIdentity
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

/** P5/S47: bundle-reuse check extracted from the download installer; pure filesystem/plan read. */
internal fun isReusableDownloadedImageCandidate(
    installer: ModelBundleInstaller,
    plan: ModelBundleInstallPlan
): Boolean {
    if (!plan.bundleRoot.isDirectory) return false
    val verification = installer.verifyInstalledBundle(plan.bundleRoot)
    if (!verification.isVerified) return false
    val verifiedByPath = verification.components.associateBy { it.audit.relativePath }
    if (verifiedByPath.keys != plan.targets.mapTo(mutableSetOf()) { it.relativePath }) return false
    return plan.targets.all { target ->
        val audit = verifiedByPath[target.relativePath]?.audit ?: return@all false
        val file = target.finalFile
        if (!file.isFile || file.length() <= 0L) return@all false
        val expectedSize = target.remote.sizeBytes
        if (expectedSize != null && expectedSize > 0L && file.length() != expectedSize) return@all false
        val expectedSha = target.remote.sha256?.takeIf(String::isNotBlank)
        (audit.sourceIdentity == target.remote.sourceIdentity() ||
            (expectedSha != null && expectedSha.equals(audit.observedSha256, ignoreCase = true))) &&
            (expectedSha == null || expectedSha.equals(audit.sourceSha256, ignoreCase = true))
    }
}

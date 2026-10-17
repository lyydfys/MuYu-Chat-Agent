package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import java.io.File

internal fun SdxlTwoPhaseCoordinator.cleanupHandoff(vararg files: File) {
    files.forEach { file ->
        runCatching { file.delete() }
        runCatching { File(file.path + ".part").delete() }
        file.parentFile?.mkdirs()
    }
}

internal fun SdxlTwoPhaseCoordinator.stageBothRuntimeProfiles(
    bundleRoot: File,
    packagedRuntimeDirsJson: String
): SdxlPhaseRuntimeDirectories {
    val bundleContextProfile = qnnImageBundleRuntimeProfileForArchOrNull(
        bundleRoot,
        SDXL_ARCHIVE_CONTEXT_HTP_ARCH
    )
    val preferredTransportArch = runCatching {
        DeviceProfileReader(appContext).read().accelerationProfile
    }.getOrNull()?.let { acceleration ->
        DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
            acceleration.chipsetCode
        ) ?: acceleration.qnnRuntime.htpArchVersion.takeIf { it > 0 }
    }
    val candidates = if (bundleContextProfile == null) {
        // Public SDXL archives contain graph contexts and prompt encoders,
        // but no host/Skel/Stub libraries. Enumerate every complete APK/OEM
        // transport. Device discovery only ranks the first attempt; all
        // candidates remain available to real load in disposable workers.
        sdxlRuntimeCandidatesForPackagedDirs(
            runtimeDirsJson = packagedRuntimeDirsJson,
            preferredHtpArch = preferredTransportArch
        )
    } else {
        val stager = QnnImageRuntimeStager(
            File(appContext.codeCacheDir, "qnn-image-runtime-sdxl-phases")
        )
        val availableProfiles = qnnImageBundleRuntimeProfiles(bundleRoot)
        val orderedProfiles = buildList {
            preferredTransportArch
                ?.let { arch -> availableProfiles.firstOrNull { it.htpArchVersion == arch } }
                ?.let(::add)
            add(bundleContextProfile)
            addAll(availableProfiles)
        }.distinctBy { profile -> profile.directory.canonicalPath to profile.htpArchVersion }
        val staged = orderedProfiles.mapNotNull { transportProfile ->
            val result = if (transportProfile.htpArchVersion == bundleContextProfile.htpArchVersion) {
                stager.stage(bundleContextProfile)
            } else {
                stager.stage(
                    QnnImageRuntimeStagePlan(
                        contextProfile = bundleContextProfile,
                        transportProfile = transportProfile
                    )
                )
            }
            result.runtime?.let { runtime ->
                val directory = runtime.directory.canonicalPath
                SdxlPhaseRuntimeCandidate(
                    runtimeProfile = SdxlQnnRuntimeProfile(
                        hostDirectory = directory,
                        dspDirectory = directory,
                        htpArchVersion = transportProfile.htpArchVersion
                    )
                )
            }
        }
        require(staged.isNotEmpty()) {
            "Unable to stage any complete SDXL QNN runtime candidate for real execution."
        }
        staged
    }
    // Every attempt gets one exact coherent host/DSP tuple. Side-loaded and staged
    // runtimes stay self-contained; an OEM platform runtime may bind two platform
    // directories explicitly. A retry advances only after the prior process has died.
    return SdxlPhaseRuntimeDirectories(
        encoderCandidates = candidates,
        unetCandidates = candidates,
        vaeCandidates = candidates
    )
}

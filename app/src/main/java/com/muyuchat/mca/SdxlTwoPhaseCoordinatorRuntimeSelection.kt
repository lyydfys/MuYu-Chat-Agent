package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

internal data class SdxlPhaseRuntimeDirectories(
    val encoderCandidates: List<SdxlPhaseRuntimeCandidate>,
    val unetCandidates: List<SdxlPhaseRuntimeCandidate>,
    val vaeCandidates: List<SdxlPhaseRuntimeCandidate>
)

internal data class SdxlPhaseRuntimeCandidate(
    val runtimeProfile: SdxlQnnRuntimeProfile
) {
    val phaseHtpArch: Int get() = runtimeProfile.htpArchVersion
}

internal data class SdxlPhaseCandidateCompletion(
    val candidate: SdxlPhaseRuntimeCandidate,
    val completion: SdxlPhaseCompletion
)

internal data class SdxlRuntimeCandidateExecution<T>(
    val candidate: SdxlPhaseRuntimeCandidate,
    val value: T
)

internal fun shouldRetrySdxlRuntimeCandidate(
    phase: SdxlImagePhase,
    errorCode: String,
    cancelled: Boolean,
    hasNextCandidate: Boolean
): Boolean = !cancelled && hasNextCandidate &&
    isSdxlRuntimeCandidateFailureCode(phase, errorCode)

internal suspend fun <T> executeSdxlRuntimeCandidates(
    phase: SdxlImagePhase,
    candidates: List<SdxlPhaseRuntimeCandidate>,
    isCancelled: () -> Boolean,
    execute: suspend (SdxlPhaseRuntimeCandidate) -> T
): SdxlRuntimeCandidateExecution<T> {
    require(candidates.isNotEmpty()) { "SDXL ${phase.wireName} has no runtime candidate." }
    for ((index, candidate) in candidates.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw CancellationException("SDXL generation was cancelled.")
        try {
            return SdxlRuntimeCandidateExecution(candidate, execute(candidate))
        } catch (error: LocalImageWorkerRemoteException) {
            currentCoroutineContext().ensureActive()
            if (!shouldRetrySdxlRuntimeCandidate(
                    phase = phase,
                    errorCode = error.code,
                    cancelled = isCancelled(),
                    hasNextCandidate = index < candidates.lastIndex
                )
            ) {
                throw error
            }
        }
    }
    error("SDXL ${phase.wireName} exhausted its runtime candidates without a result.")
}

@Suppress("UNUSED_PARAMETER")
internal fun orderedSdxlRuntimeDirs(primary: String, fallback: List<String>): String =
    JSONArray(listOf(File(primary).canonicalPath)).toString()

/**
 * Selects one complete APK/app-private QNN runtime for an isolated SDXL phase.
 * The directory may contain several physical-device transports; native context
 * metadata selects the compatible one and a real graph execute decides support.
 */
internal fun isolatedSdxlPackagedRuntimeDirs(runtimeDirsJson: String): String {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    val coherent = directories.firstOrNull(File::hasCoherentSdxlQnnRuntime)
        ?: error("The APK does not contain a complete QNN runtime for SDXL graph execution.")
    return orderedSdxlRuntimeDirs(coherent.path, emptyList())
}

internal fun sdxlRuntimeCandidatesForPackagedDirs(
    runtimeDirsJson: String,
    preferredHtpArch: Int? = null
): List<SdxlPhaseRuntimeCandidate> {
    val raw = JSONArray(runtimeDirsJson)
    val directories = buildList {
        for (index in 0 until raw.length()) {
            raw.optString(index)
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?.takeIf(File::isDirectory)
                ?.let(::add)
        }
    }.distinctBy(File::getPath)
    require(
        directories.any { directory ->
            File(directory, "libQnnSystem.so").isFile && File(directory, "libQnnHtp.so").isFile
        }
    ) { "The packaged QNN runtime has no host System/HTP pair." }
    val candidates = qnnImagePackagedRuntimeCandidates(
        searchDirectories = directories,
        preferredHtpArchVersion = preferredHtpArch
    )
    require(candidates.isNotEmpty()) {
        "The packaged QNN runtime has no coherent executable HTP transport candidate."
    }
    return candidates.map { candidate ->
        SdxlPhaseRuntimeCandidate(
            runtimeProfile = candidate.runtimeProfile
        )
    }
}

private fun File.hasCoherentSdxlQnnRuntime(): Boolean {
    if (!File(this, "libQnnSystem.so").isFile || !File(this, "libQnnHtp.so").isFile) return false
    return listFiles().orEmpty().any { skel ->
        val arch = SDXL_HTP_SKEL.matchEntire(skel.name)?.groupValues?.getOrNull(1) ?: return@any false
        File(this, "libQnnHtpV${arch}Stub.so").isFile
    }
}

private val SDXL_HTP_SKEL = Regex("^libQnnHtpV(\\d+)Skel\\.so$")
internal const val SDXL_REQUEST_LEASE_TOKEN_FIELD = "sdxlRequestLeaseToken"
internal const val SDXL_REQUEST_LEASE_LOCK_PATH_FIELD = "sdxlRequestLeaseLockPath"

package com.muyuchat.core.deviceprofile

import java.io.File
import java.util.Locale

/**
 * Selects internally coherent QNN profiles. The preferred HTP architecture is
 * only an ordering hint: every other discovered architecture and every
 * permissible host/DSP tuple remains available for real native load.
 */
object QnnRuntimeProfileSelector {
    fun htpArchVersionForChipsetCode(chipsetCode: String): Int? {
        val normalized = chipsetCode.trim().uppercase(Locale.US)
        val numericSocModel = normalized.toIntOrNull()
        return when {
            numericSocModel == 30 -> 68
            numericSocModel == 36 || numericSocModel == 42 -> 69
            numericSocModel == 43 || numericSocModel == 66 -> 73
            numericSocModel == 57 || numericSocModel == 68 -> 75
            numericSocModel == 69 || numericSocModel == 85 -> 79
            numericSocModel == 87 -> 81
            else -> when (normalized) {
            "SM8350" -> 68
            "SM8450", "SM8475" -> 69
            "SM8550", "SM8550P", "QCS8550", "QCM8550" -> 73
            "SM8635", "SM8650", "SM8650P" -> 75
            // SM8735 (Snapdragon 8s Gen 4) uses the same HTP v79 class as
            // SM8750. Keep it explicit so a context built for socModel=85
            // is never mistaken for an unknown/future device.
            "SM8735", "SM8750", "SM8750P" -> 79
            "SM8850", "SM8850P" -> 81
            else -> null
            }
        }
    }

    fun htpArchVersionForSocModel(socModel: Int): Int? =
        when (socModel) {
            30 -> 68
            36, 42 -> 69
            43, 66 -> 73
            57, 68 -> 75
            69, 85 -> 79
            87 -> 81
            else -> null
        }

    fun select(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int? = null
    ): QnnRuntimeProfile? = candidateSequence(
        searchDirectories = searchDirectories,
        preferredHtpArchVersion = preferredHtpArchVersion,
        isPlatformDirectory = ::isPlatformDirectory
    ).firstOrNull()

    internal fun select(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int?,
        isPlatformDirectory: (File) -> Boolean
    ): QnnRuntimeProfile? = candidateSequence(
        searchDirectories = searchDirectories,
        preferredHtpArchVersion = preferredHtpArchVersion,
        isPlatformDirectory = isPlatformDirectory
    ).firstOrNull()

    fun candidates(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int? = null
    ): List<QnnRuntimeProfile> = candidateSequence(
        searchDirectories = searchDirectories,
        preferredHtpArchVersion = preferredHtpArchVersion,
        isPlatformDirectory = ::isPlatformDirectory
    ).toList()

    internal fun candidates(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int?,
        isPlatformDirectory: (File) -> Boolean
    ): List<QnnRuntimeProfile> = candidateSequence(
        searchDirectories = searchDirectories,
        preferredHtpArchVersion = preferredHtpArchVersion,
        isPlatformDirectory = isPlatformDirectory
    ).toList()

    private fun candidateSequence(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int?,
        isPlatformDirectory: (File) -> Boolean
    ): Sequence<QnnRuntimeProfile> = sequence {
        val dirs = searchDirectories
            .mapNotNull { directory -> runCatching { directory.canonicalFile }.getOrNull() }
            .filter(File::isDirectory)
            .distinctBy { directory -> directory.absolutePath }
        val hosts = dirs.mapNotNull { directory ->
            val system = File(directory, "libQnnSystem.so")
            val htp = File(directory, "libQnnHtp.so")
            if (system.isFile && htp.isFile) HostCandidate(directory, system, htp) else null
        }
        if (hosts.isEmpty()) return@sequence

        val availableVersions = dirs
            .flatMap { directory -> directory.listFiles()?.asList().orEmpty() }
            .mapNotNull { file -> htpLibraryVersion(file.name) }
            .distinct()
            .sortedDescending()
        val versions = buildList {
            preferredHtpArchVersion?.let(::add)
            addAll(availableVersions)
        }.distinct()

        val emitted = mutableSetOf<Triple<String, String, Int>>()
        for (archVersion in versions) {
            val dspCandidates = dirs.mapNotNull { directory ->
                val skel = File(directory, "libQnnHtpV${archVersion}Skel.so")
                if (!skel.isFile) {
                    null
                } else {
                    DspCandidate(
                        directory = directory,
                        skel = skel,
                        stub = File(directory, "libQnnHtpV${archVersion}Stub.so").takeIf(File::isFile)
                    )
                }
            }
            for (host in hosts) {
                for (dsp in dspCandidates) {
                    if (mayPair(host.directory, dsp.directory, isPlatformDirectory)) {
                        val identity = Triple(
                            host.directory.absolutePath,
                            dsp.directory.absolutePath,
                            archVersion
                        )
                        if (emitted.add(identity)) {
                            yield(
                                QnnRuntimeProfile(
                                    hostDirectory = host.directory,
                                    dspDirectory = dsp.directory,
                                    systemLibrary = host.system,
                                    htpLibrary = host.htp,
                                    htpSkelLibrary = dsp.skel,
                                    htpStubLibrary = dsp.stub,
                                    cdspRpcLibrary = File(host.directory, "libcdsprpc.so")
                                        .takeIf(File::isFile),
                                    htpArchVersion = archVersion
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private data class HostCandidate(
        val directory: File,
        val system: File,
        val htp: File
    )

    private data class DspCandidate(
        val directory: File,
        val skel: File,
        val stub: File?
    )

    private fun htpLibraryVersion(name: String): Int? =
        Regex("""^libQnnHtpV(\d+)(?:Skel|Stub)\.so$""")
            .matchEntire(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun mayPair(
        hostDirectory: File,
        dspDirectory: File,
        isPlatformDirectory: (File) -> Boolean
    ): Boolean {
        if (hostDirectory == dspDirectory) return true
        return isPlatformDirectory(hostDirectory) && isPlatformDirectory(dspDirectory)
    }

    private fun isPlatformDirectory(directory: File): Boolean {
        val path = directory.absolutePath.replace('\\', '/')
        return QNN_PLATFORM_RUNTIME_DIRECTORY_PREFIXES.any(path::startsWith)
    }
}

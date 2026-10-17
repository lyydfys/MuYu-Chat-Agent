package com.muyuchat.core.deviceprofile

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class QnnRuntimeStatus(
    val qnnSystemLibraryPresent: Boolean,
    val qnnHtpLibraryPresent: Boolean,
    val htpSkelLibraryPresent: Boolean,
    val htpStubLibraryPresent: Boolean = false,
    val cdspRpcLibraryPresent: Boolean = false,
    val cdspRpcLibraryLoadable: Boolean = false,
    val searchDirectories: List<String> = emptyList(),
    val qnnSystemLibraryPath: String? = null,
    val qnnHtpLibraryPath: String? = null,
    val htpSkelLibraryPath: String? = null,
    val htpStubLibraryPath: String? = null,
    val cdspRpcLibraryPath: String? = null,
    val cdspRpcMessage: String = "",
    val htpArchVersion: Int = 0,
    /** Device-required HTP architecture, when the SoC is known. */
    val preferredHtpArchVersion: Int? = null,
    val runtimeDirectory: String? = null,
    val dspRuntimeDirectory: String? = null,
    val probeState: QnnRuntimeProbeState = QnnRuntimeProbeState.NOT_REQUESTED,
    val probeMessage: String = ""
) {
    val ready: Boolean
        get() = qnnSystemLibraryPresent && qnnHtpLibraryPresent && htpSkelLibraryPresent

    /** Prevents a complete but wrong-generation transport from being advertised as usable. */
    val exactArchMatch: Boolean
        get() = preferredHtpArchVersion == null ||
            (htpArchVersion > 0 && htpArchVersion == preferredHtpArchVersion)

    val loadable: Boolean
        get() = probeState == QnnRuntimeProbeState.LOADABLE

    val hostRuntimeLoadable: Boolean
        get() = loadable

    val htpTransportVerified: Boolean
        get() = ready && htpStubLibraryPresent && loadable && exactArchMatch

    val transportDependencyBlocked: Boolean
        get() = probeState == QnnRuntimeProbeState.LOAD_FAILED &&
            (probeMessage.contains("libcdsprpc", ignoreCase = true) ||
                probeMessage.contains("libhidlbase", ignoreCase = true))

    val usableForSmoke: Boolean
        get() = ready &&
            htpStubLibraryPresent &&
            probeState == QnnRuntimeProbeState.LOADABLE &&
            !transportDependencyBlocked &&
            exactArchMatch

    fun toJson(): JSONObject = JSONObject()
        .put("ready", ready)
        .put("loadable", loadable)
        .put("usableForSmoke", usableForSmoke)
        .put("qnnSystemLibraryPresent", qnnSystemLibraryPresent)
        .put("qnnHtpLibraryPresent", qnnHtpLibraryPresent)
        .put("htpSkelLibraryPresent", htpSkelLibraryPresent)
        .put("htpStubLibraryPresent", htpStubLibraryPresent)
        .put("cdspRpcLibraryPresent", cdspRpcLibraryPresent)
        .put("cdspRpcLibraryLoadable", cdspRpcLibraryLoadable)
        .put("hostRuntimeLoadable", hostRuntimeLoadable)
        .put("htpTransportVerified", htpTransportVerified)
        .put("transportDependencyBlocked", transportDependencyBlocked)
        .put("qnnSystemLibraryPath", qnnSystemLibraryPath)
        .put("qnnHtpLibraryPath", qnnHtpLibraryPath)
        .put("htpSkelLibraryPath", htpSkelLibraryPath)
        .put("htpStubLibraryPath", htpStubLibraryPath)
        .put("cdspRpcLibraryPath", cdspRpcLibraryPath)
        .put("cdspRpcMessage", cdspRpcMessage)
        .put("htpArchVersion", htpArchVersion)
        .put("preferredHtpArchVersion", preferredHtpArchVersion ?: JSONObject.NULL)
        .put("exactArchMatch", exactArchMatch)
        .put("runtimeDirectory", runtimeDirectory)
        .put("dspRuntimeDirectory", dspRuntimeDirectory)
        .put("probeState", probeState.name.lowercase(Locale.US))
        .put("probeMessage", probeMessage)
        .put("searchDirectories", JSONArray(searchDirectories))

    companion object {
        val Missing: QnnRuntimeStatus = QnnRuntimeStatus(
            qnnSystemLibraryPresent = false,
            qnnHtpLibraryPresent = false,
            htpSkelLibraryPresent = false,
            probeState = QnnRuntimeProbeState.FILES_MISSING,
            probeMessage = "QNN runtime libraries were not found."
        )

        fun inspect(
            searchDirectories: List<File>,
            probeLibraries: Boolean = false,
            preferredHtpArchVersion: Int? = null,
            libraryLoader: (String) -> Unit = { path -> System.load(path) }
        ): QnnRuntimeStatus {
            val dirs = searchDirectories
                .mapNotNull { dir -> runCatching { dir.canonicalFile }.getOrNull() }
                .distinctBy { it.absolutePath }
            val profile = QnnRuntimeProfileSelector.select(dirs, preferredHtpArchVersion)
            val filesReady = profile != null
            val probe = when {
                !filesReady -> QnnRuntimeProbeState.FILES_MISSING to missingQnnRuntimeMessage(
                    searchDirectories = dirs,
                    preferredHtpArchVersion = preferredHtpArchVersion
                )
                !probeLibraries -> QnnRuntimeProbeState.NOT_REQUESTED to "QNN runtime files were found; native load probe was not requested."
                else -> runCatching {
                    // Keep libQnnHtp unloaded until native code has read the
                    // context metadata and selected the best coherent HTP
                    // profile, including generic fallback when the preferred
                    // device transport is not packaged.
                    libraryLoader(requireNotNull(profile).systemLibrary.absolutePath)
                }.fold(
                    onSuccess = {
                        QnnRuntimeProbeState.LOADABLE to
                            "QNN System metadata preflight loaded; Snapdragon NPU profile selection is deferred until context metadata is parsed."
                    },
                    onFailure = { error ->
                        QnnRuntimeProbeState.LOAD_FAILED to (error.message ?: error::class.java.simpleName)
                    }
                )
            }
            val rpcProbe = if (profile?.cdspRpcLibrary != null) {
                false to "The device transport library is present but not probed directly; the Snapdragon NPU runtime will resolve it during graph smoke."
            } else {
                false to "The device transport library was not found; this is acceptable when using a QAIRT-packaged Snapdragon NPU runtime."
            }
            return QnnRuntimeStatus(
                qnnSystemLibraryPresent = profile != null,
                qnnHtpLibraryPresent = profile != null,
                htpSkelLibraryPresent = profile != null,
                htpStubLibraryPresent = profile?.htpStubLibrary != null,
                cdspRpcLibraryPresent = profile?.cdspRpcLibrary != null,
                cdspRpcLibraryLoadable = rpcProbe.first,
                searchDirectories = dirs.map { it.absolutePath },
                qnnSystemLibraryPath = profile?.systemLibrary?.absolutePath,
                qnnHtpLibraryPath = profile?.htpLibrary?.absolutePath,
                htpSkelLibraryPath = profile?.htpSkelLibrary?.absolutePath,
                htpStubLibraryPath = profile?.htpStubLibrary?.absolutePath,
                cdspRpcLibraryPath = profile?.cdspRpcLibrary?.absolutePath,
                cdspRpcMessage = rpcProbe.second,
                htpArchVersion = profile?.htpArchVersion ?: 0,
                preferredHtpArchVersion = preferredHtpArchVersion,
                runtimeDirectory = profile?.runtimeDirectory,
                dspRuntimeDirectory = profile?.dspDirectory?.absolutePath,
                probeState = probe.first,
                probeMessage = probe.second
            )
        }

        private fun missingQnnRuntimeMessage(
            searchDirectories: List<File>,
            preferredHtpArchVersion: Int?
        ): String {
            val files = searchDirectories
                .flatMap { directory -> directory.listFiles()?.asList().orEmpty() }
                .filter(File::isFile)
            val missing = buildList<String> {
                if (files.none { it.name == "libQnnSystem.so" }) add("libQnnSystem.so")
                if (files.none { it.name == "libQnnHtp.so" }) add("libQnnHtp.so")
                val expectedSkel = preferredHtpArchVersion?.let { "libQnnHtpV${it}Skel.so" }
                    ?: "libQnnHtpVxxSkel.so"
                val hasExpectedSkel = if (preferredHtpArchVersion == null) {
                    files.any { it.name.matches(Regex("""^libQnnHtpV\d+Skel\.so$""")) }
                } else {
                    files.any { it.name == expectedSkel }
                }
                if (!hasExpectedSkel) add(expectedSkel)
            }
            return if (missing.isEmpty()) {
                "No coherent QNN host/DSP profile was found in one side-loaded bundle or compatible OEM runtime directories."
            } else {
                "Missing QNN runtime libraries: ${missing.joinToString(", ")}."
            }
        }
    }
}

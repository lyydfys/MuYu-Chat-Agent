package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.QnnRuntimeStatus
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Chooses directories for image-graph smoke without allowing a generic APK
 * runtime to shadow a coherent runtime shipped beside the model bundle.
 *
 * A bundle directory is promoted only when it contains one complete HTP
 * profile: System, HTP, and a matching-version Skel + Stub.  Merely finding a
 * newer Skel is intentionally not enough, because QNN retains a mixed HTP
 * selection in the process and later reports incompatible context binaries.
 */
internal data class QnnImageBundleRuntimeProfile(
    val directory: File,
    val htpArchVersion: Int
)

internal data class QnnImageSmokeRuntimeSelection(
    val directories: List<String>,
    val bundleProfile: QnnImageBundleRuntimeProfile?
)

internal fun qnnImageSmokeRuntimeSelection(
    bundleRoot: File?,
    runtimeStatus: QnnRuntimeStatus
): QnnImageSmokeRuntimeSelection {
    val bundleProfile = bundleRoot?.let(::coherentQnnImageBundleRuntimeProfileOrNull)
    val selectedDirectories = buildList {
        bundleProfile?.directory?.absolutePath?.let(::add)
        listOf(
            runtimeStatus.qnnSystemLibraryPath,
            runtimeStatus.qnnHtpLibraryPath,
            runtimeStatus.htpSkelLibraryPath,
            runtimeStatus.htpStubLibraryPath
        ).mapNotNull { path -> path?.let(::File)?.parentFile?.absolutePath }
            .forEach(::add)
        addAll(runtimeStatus.searchDirectories)
    }.canonicalDistinctDirectories()
    return QnnImageSmokeRuntimeSelection(
        directories = selectedDirectories,
        bundleProfile = bundleProfile
    )
}

internal fun qnnImageSmokeRuntimeDirectories(
    bundleRoot: File?,
    runtimeStatus: QnnRuntimeStatus
): List<String> = qnnImageSmokeRuntimeSelection(bundleRoot, runtimeStatus).directories

/**
 * Returns a runtime only when its host pair and the exact HTP Skel/Stub pair
 * live together.  Callers that need to make the runtime linker-loadable must
 * still stage this profile into app-private storage first.
 */
internal fun coherentQnnImageBundleRuntimeProfileOrNull(bundleRoot: File): QnnImageBundleRuntimeProfile? {
    return qnnImageBundleRuntimeCandidateDirectories(bundleRoot).asSequence()
        .mapNotNull(::completeProfileInDirectoryOrNull)
        .maxByOrNull(QnnImageBundleRuntimeProfile::htpArchVersion)
}

/** Returns the exact architecture profile required by a persisted bundle contract. */
internal fun qnnImageBundleRuntimeProfileForArchOrNull(
    bundleRoot: File,
    htpArchVersion: Int
): QnnImageBundleRuntimeProfile? =
    qnnImageBundleRuntimeCandidateDirectories(bundleRoot).asSequence()
        .mapNotNull { directory -> completeProfileInDirectoryOrNull(directory, htpArchVersion) }
        .firstOrNull()

internal fun qnnRequiredBundleRuntimeReadinessMessage(
    bundleRoot: File,
    profile: LocalImageQnnRuntimeProfile?
): String? {
    val required = profile?.takeIf { it.completeBundleRuntime } ?: return null
    return qnnRequiredBundleRuntimeResolution(bundleRoot, required).error
}

internal data class QnnRequiredBundleRuntimeResolution(
    val runtimeProfile: QnnImageBundleRuntimeProfile? = null,
    val error: String? = null
)

/**
 * Resolves a contract-bound runtime only after checking its public metadata
 * and every library digest.  The HTP filename alone cannot distinguish two
 * incompatible QNN SDK releases that target the same architecture.
 */
internal fun qnnRequiredBundleRuntimeResolution(
    bundleRoot: File,
    required: LocalImageQnnRuntimeProfile
): QnnRequiredBundleRuntimeResolution {
    val publicRuntime = qnnPublicNpuRuntimeName(required.htpArch)
    val runtimeProfile = qnnImageBundleRuntimeProfileForArchOrNull(bundleRoot, required.htpArch)
        ?: return QnnRequiredBundleRuntimeResolution(
            error = "缺少完整的 $publicRuntime。请重新下载包含 QNN ${required.qnnSdk} 运行库的模型包。"
        )
    val metadataFile = File(runtimeProfile.directory, QNN_RUNTIME_METADATA_FILE)
    if (!metadataFile.isFile) {
        return QnnRequiredBundleRuntimeResolution(
            error = "$publicRuntime 的校验信息缺失，无法确认 QNN ${required.qnnSdk} 版本和完整性。" +
                "请重新下载完整模型包。"
        )
    }
    val metadata = runCatching { JSONObject(metadataFile.readText(Charsets.UTF_8)) }.getOrElse {
        return QnnRequiredBundleRuntimeResolution(
            error = "$publicRuntime 的校验信息格式无效。请重新下载完整模型包。"
        )
    }
    if (metadata.optString("schema") != QNN_RUNTIME_METADATA_SCHEMA) {
        return QnnRequiredBundleRuntimeResolution(
            error = "$publicRuntime 的校验信息版本不兼容。请更新应用或重新下载模型包。"
        )
    }
    val actualSdk = metadata.optString("qnnSdk").trim()
    if (actualSdk != required.qnnSdk) {
        return QnnRequiredBundleRuntimeResolution(
            error = "$publicRuntime 的 QNN 版本不兼容：模型要求 ${required.qnnSdk}，" +
                "模型包提供 ${actualSdk.ifBlank { "未声明" }}。"
        )
    }
    val actualArch = metadata.optInt("htpArch", 0)
    if (actualArch != required.htpArch) {
        return QnnRequiredBundleRuntimeResolution(
            error = "模型包中的 NPU 运行环境与 $publicRuntime 不匹配，请下载适用于该芯片的模型包。"
        )
    }
    val hashes = metadata.optJSONObject("files")
        ?: return QnnRequiredBundleRuntimeResolution(
            error = "$publicRuntime 缺少 QNN 运行库的 SHA-256 校验清单。请重新下载完整模型包。"
        )
    qnnImageRuntimeFileNames(required.htpArch).forEach { name ->
        val declared = when (val entry = hashes.opt(name)) {
            is String -> entry
            is JSONObject -> entry.optString("sha256")
            else -> ""
        }.trim().lowercase()
        if (!QNN_SHA256.matches(declared)) {
            return QnnRequiredBundleRuntimeResolution(
                error = "$publicRuntime 的 QNN 运行库校验信息无效。请重新下载完整模型包。"
            )
        }
        val actual = File(runtimeProfile.directory, name).qnnRuntimeSha256()
        if (actual != declared) {
            return QnnRequiredBundleRuntimeResolution(
                error = "$publicRuntime 的 QNN 运行库完整性校验失败（SHA-256 不一致）。" +
                    "请重新下载完整模型包。"
            )
        }
    }
    return QnnRequiredBundleRuntimeResolution(runtimeProfile = runtimeProfile)
}

private fun qnnImageBundleRuntimeCandidateDirectories(bundleRoot: File): Set<File> {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull()
        ?.takeIf(File::isDirectory)
        ?: return emptySet()
    val candidates = linkedSetOf<File>()
    fun addCandidate(candidate: File?) {
        val canonical = runCatching { candidate?.canonicalFile }.getOrNull()
            ?.takeIf(File::isDirectory)
            ?: return
        if (canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)) {
            candidates += canonical
        }
    }
    // The documented layout is bundle/runtime. Also accept a versioned ABI
    // directory or a root-level profile when all four exact artifacts coexist.
    addCandidate(File(root, "runtime"))
    addCandidate(root)
    root.walkTopDown()
        .maxDepth(3)
        .filter { it.isFile && it.name == QNN_SYSTEM_LIBRARY }
        .forEach { file -> addCandidate(file.parentFile) }
    return candidates
}

private fun completeProfileInDirectoryOrNull(directory: File): QnnImageBundleRuntimeProfile? {
    if (!File(directory, QNN_SYSTEM_LIBRARY).isFile || !File(directory, QNN_HTP_LIBRARY).isFile) {
        return null
    }
    val versions = directory.listFiles()
        ?.asSequence()
        ?.mapNotNull { file -> HTP_SKEL_NAME.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        ?.sortedDescending()
        ?.toList()
        .orEmpty()
    val arch = versions.firstOrNull { version ->
        File(directory, "libQnnHtpV${version}Skel.so").isFile &&
            File(directory, "libQnnHtpV${version}Stub.so").isFile
    } ?: return null
    return QnnImageBundleRuntimeProfile(directory = directory, htpArchVersion = arch)
}

private fun completeProfileInDirectoryOrNull(
    directory: File,
    htpArchVersion: Int
): QnnImageBundleRuntimeProfile? {
    if (htpArchVersion <= 0 ||
        !File(directory, QNN_SYSTEM_LIBRARY).isFile ||
        !File(directory, QNN_HTP_LIBRARY).isFile ||
        !File(directory, "libQnnHtpV${htpArchVersion}Skel.so").isFile ||
        !File(directory, "libQnnHtpV${htpArchVersion}Stub.so").isFile
    ) {
        return null
    }
    return QnnImageBundleRuntimeProfile(directory = directory, htpArchVersion = htpArchVersion)
}

private fun List<String>.canonicalDistinctDirectories(): List<String> =
    asSequence()
        .filter(String::isNotBlank)
        .map { path ->
            runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
        }
        .distinct()
        .toList()

internal fun qnnImageRuntimeFileNames(htpArchVersion: Int): List<String> = listOf(
    QNN_SYSTEM_LIBRARY,
    QNN_HTP_LIBRARY,
    "libQnnHtpV${htpArchVersion}Skel.so",
    "libQnnHtpV${htpArchVersion}Stub.so"
)

internal fun qnnImageTransportRuntimeFileNames(htpArchVersion: Int): List<String> = buildList {
    // QAIRT 2.39/2.45 packages use a versioned host-side transport library on
    // V79+ devices. Older V73/V75 packages carry only their Skel/Stub pair.
    if (htpArchVersion >= 79) add("libQnnHtpV${htpArchVersion}.so")
    add("libQnnHtpV${htpArchVersion}Skel.so")
    add("libQnnHtpV${htpArchVersion}Stub.so")
}

private fun File.qnnRuntimeSha256(): String =
    MessageDigest.getInstance("SHA-256").let { digest ->
        inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

internal fun qnnPublicNpuRuntimeName(htpArchVersion: Int): String = when (htpArchVersion) {
    68 -> "骁龙 888 / 骁龙 888+ NPU 运行环境"
    69 -> "骁龙 8 Gen 1 / 骁龙 8+ Gen 1 NPU 运行环境"
    73 -> "骁龙 8 Gen 2 NPU 运行环境"
    75 -> "骁龙 8 Gen 3 NPU 运行环境"
    79 -> "骁龙 8 Elite NPU 运行环境"
    81 -> "骁龙 8 Elite Gen 5 NPU 运行环境"
    else -> "当前设备的骁龙 NPU 运行环境"
}

private const val QNN_SYSTEM_LIBRARY = "libQnnSystem.so"
private const val QNN_HTP_LIBRARY = "libQnnHtp.so"
internal const val QNN_RUNTIME_METADATA_FILE = "mca_qnn_runtime.json"
internal const val QNN_RUNTIME_METADATA_SCHEMA = "mca.qnn.runtime.v1"
private val QNN_SHA256 = Regex("^[0-9a-f]{64}$")
private val HTP_SKEL_NAME = Regex("""^libQnnHtpV(\d+)Skel\.so$""")

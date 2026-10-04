package com.muyuchat.mca

import android.content.Context
import android.os.Build
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * QNN runtime-directory resolution and identity verification (P5/L3). Moved verbatim out of
 * LocalImageProvider.kt: runtime search/resolution for packaged and adb-staged QNN runtimes,
 * SDK pinning, verification stamps and device/runtime identity JSON. Same package and internal
 * visibility, so the local-image provider, model store and tests are unchanged.
 */

internal fun qnnRuntimeSearchDirectories(
    bundleRoot: File?,
    existingDirectories: List<String>
): List<String> {
    val bundleRuntime = bundleRoot?.canonicalQnnRuntimeDirectoryOrNull()?.absolutePath
    return (listOfNotNull(bundleRuntime) + existingDirectories)
        .filter { it.isNotBlank() }
        .distinctBy { directory ->
            runCatching { File(directory).canonicalPath }
                .getOrElse { File(directory).absolutePath }
        }
}

/**
 * Builds the native QNN search path around one coherent private runtime. Once
 * a bundle or the APK has been staged, other app-private discovery results are
 * deliberately excluded: the native resolver must not combine a host library
 * from one QNN profile with a Skel/Stub pair from another. OEM platform paths
 * remain as a fallback for device transport dependencies such as libcdsprpc.
 */
internal fun qnnRuntimeDirectoriesForResolvedRuntime(
    privateRuntimeDirectory: String?,
    packagedNativeDirectory: String,
    fallbackDirectories: List<String>,
    detectedSearchDirectories: List<String>
): List<String> {
    val hasPrivateRuntime = !privateRuntimeDirectory.isNullOrBlank()
    val fallback = if (hasPrivateRuntime) {
        fallbackDirectories.filter(::isQnnPlatformRuntimeDirectory)
    } else {
        fallbackDirectories
    }
    val detected = if (hasPrivateRuntime) emptyList() else detectedSearchDirectories
    return qnnRuntimeSearchDirectories(
        bundleRoot = null,
        existingDirectories = listOfNotNull(
            privateRuntimeDirectory,
            packagedNativeDirectory.takeIf { !hasPrivateRuntime }
        ) + fallback + detected
    )
}

private fun isQnnPlatformRuntimeDirectory(path: String): Boolean {
    val normalized = path.trim().replace('\\', '/')
    return normalized.startsWith("/vendor/") ||
        normalized.startsWith("/odm/") ||
        normalized.startsWith("/system/") ||
        normalized.startsWith("/system_ext/") ||
        normalized.startsWith("/product/")
}

/**
 * Resolves only linker-loadable QNN directories for an image bundle. A
 * complete runtime found beside a downloaded bundle is staged to private
 * code-cache storage first; it is never returned from external storage.
 */
internal data class QnnRuntimeDirectoryResolution(
    val directories: List<String>,
    val stagedRuntime: QnnImageStagedRuntime? = null,
    val stagingError: String? = null
)

internal fun qnnSdkForPackagedImageRuntime(manifest: LocalImageBundleManifest?): String? =
    manifest?.requiredRuntimeProfile?.qnnSdk ?: manifest?.executionProfileQnnSdk

internal fun qnnRuntimeDirectoryResolutionFor(
    context: Context,
    bundleRoot: File?
): QnnRuntimeDirectoryResolution {
    val appContext = context.applicationContext
    val manifest = bundleRoot?.let(::localImageBundleManifestFromRoot)
    val requiredRuntimeProfile = manifest?.requiredRuntimeProfile
    val staged = stageQnnImageBundleRuntime(appContext, bundleRoot, requiredRuntimeProfile)
    // Bundle-specific staging is an optimization, not an admission gate. A
    // device may still expose a coherent OEM/system QNN runtime, so preserve
    // the diagnostic but continue through generic runtime discovery and let
    // the real native graph load decide compatibility.
    // A downloaded image bundle may contain only graph/model artifacts. In
    // that case use the APK's pinned Qualcomm asset set, staged into the
    // app-private code cache. This is important on devices where the APK also
    // carries another HTP profile in nativeLibraryDir: passing that directory
    // alongside the selected profile lets the native resolver pick the wrong
    // (usually newer) Skel/Stub pair and hang while opening the context.
    val packagedQualcommRuntime = runCatching {
        LiteRtQualcommRuntimeStager.stageForImageBundle(
            context = appContext,
            bundleRuntimeAlreadyStaged = staged.runtime != null,
            qnnSdk = qnnSdkForPackagedImageRuntime(manifest)
        )
    }.getOrNull()
    val privateRuntimeDirectory = staged.runtime?.directory?.absolutePath
        ?: packagedQualcommRuntime?.directory?.absolutePath
    val fallback = listOf(
        File(appContext.filesDir, "qnnlibs").absolutePath,
        File(appContext.filesDir, "runtime_libs").absolutePath,
        "/vendor/lib64",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/product/lib64"
    )
    val detectedRuntime = if (privateRuntimeDirectory == null) {
        runCatching { DeviceProfileReader(appContext).read().accelerationProfile.qnnRuntime }
            .getOrNull()
    } else {
        // Do not probe another host library after a coherent stage has been
        // selected. QNN keeps process-level state after dlopen, so probing a
        // stale app/OEM profile here could poison the exact staged graph load.
        null
    }
    val packagedNativeDirectory = appContext.applicationInfo.nativeLibraryDir
        .let(::File)
        .canonicalPath
    val detectedSearchDirectories = detectedRuntime?.searchDirectories.orEmpty().filterNot { path ->
        runCatching { File(path).canonicalPath == packagedNativeDirectory }.getOrDefault(false)
    }
    return QnnRuntimeDirectoryResolution(
        directories = qnnRuntimeDirectoriesForResolvedRuntime(
            privateRuntimeDirectory = privateRuntimeDirectory,
            packagedNativeDirectory = appContext.applicationInfo.nativeLibraryDir,
            fallbackDirectories = fallback,
            detectedSearchDirectories = detectedSearchDirectories
        ),
        stagedRuntime = staged.runtime,
        stagingError = staged.error
    )
}

internal fun qnnRuntimeDirectoriesFor(context: Context, bundleRoot: File?): List<String> =
    qnnRuntimeDirectoryResolutionFor(context, bundleRoot).directories

/** Puts the device-selected coherent QNN host profile ahead of generic paths. */
internal fun qnnRuntimeDirectoriesFor(
    runtimeStatus: com.muyuchat.core.deviceprofile.QnnRuntimeStatus
): List<String> {
    val selectedHostDirectory = runtimeStatus.qnnSystemLibraryPath
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    return qnnRuntimeSearchDirectories(
        bundleRoot = null,
        existingDirectories = listOfNotNull(selectedHostDirectory) + runtimeStatus.searchDirectories
    )
}

private fun File.canonicalQnnRuntimeDirectoryOrNull(): File? {
    val root = runCatching { canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    val runtime = runCatching { File(root, "runtime").canonicalFile }.getOrNull() ?: return null
    return runtime.takeIf {
        it.isDirectory && it.path.startsWith(root.path + File.separator)
    }
}

internal fun qnnImageVerificationStampFor(
    context: Context,
    bundleRoot: File,
    execution: QnnImageExecutionEvidence? = null
): String {
    val appContext = context.applicationContext
    val bundle = QnnImageBundleIdentity.fromDirectory(bundleRoot)
    require(bundle.status == QnnImageBundleIdentityStatus.AVAILABLE) {
        "QNN image bundle is unavailable for verification."
    }
    return QnnImageVerificationStamp.create(
        device = currentQnnImageDeviceIdentity(appContext),
        runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
        bundleDirectory = bundleRoot,
        execution = execution
    ).toJsonString()
}

internal fun LocalImageModelRecord.hasCurrentQnnVerificationStamp(
    context: Context,
    bundleRoot: File
): Boolean {
    if (qnnVerificationStamp.isBlank()) return false
    val appContext = context.applicationContext
    return runCatching {
        QnnImageVerificationStamp.fromJson(qnnVerificationStamp).matchesCurrent(
            device = currentQnnImageDeviceIdentity(appContext),
            runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
            bundleDirectory = bundleRoot
        )
    }.getOrDefault(false)
}

private fun currentQnnImageDeviceIdentity(context: Context): QnnImageDeviceIdentity {
    val profile = DeviceProfileReader(context).read()
    return QnnImageDeviceIdentity(
        soc = profile.accelerationProfile.chipsetCode.ifBlank { profile.socModel.ifBlank { "unknown" } },
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
        buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }
    )
}

private fun currentQnnImageRuntimeIdentity(
    context: Context,
    bundleRoot: File
): QnnImageRuntimeIdentity {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    val appIdentity = buildString {
        append(context.packageName)
        append('/')
        append(packageInfo.versionName.orEmpty().ifBlank { "unknown" })
        append('#')
        append(versionCode)
    }
    val nativeRuntime = runCatching {
        val runtimeDirsJson = JSONArray(qnnRuntimeDirectoriesFor(context, bundleRoot)).toString()
        qnnImageNativeRuntimeFingerprint(qnnRuntimeIdentityJson(NativeQnnBridge().inspectRuntime(runtimeDirsJson)))
    }.getOrDefault("unavailable")
    return QnnImageRuntimeIdentity(
        app = appIdentity,
        nativeRuntime = nativeRuntime,
        apkSha256 = runCatching { File(context.applicationInfo.sourceDir).sha256Contents() }.getOrNull()
    )
}

internal fun qnnRuntimeIdentityJson(runtimeProbeJson: String): String {
    val probe = JSONObject(runtimeProbeJson)
    val identity = JSONObject()
        .put("schema", "mca.qnn.runtime.identity.v1")
        .put("ready", probe.optBoolean("ready", false))
        .put("loadable", probe.optBoolean("loadable", false))
        .put("qnnInterfacePresent", probe.optBoolean("qnnInterfacePresent", false))
        .put("qnnSystemInterfacePresent", probe.optBoolean("qnnSystemInterfacePresent", false))
        .put("compile", probe.optJSONObject("compile") ?: JSONObject())
    val libraries = JSONArray()
    QNN_RUNTIME_LIBRARY_KEYS.forEach { (role, key) ->
        libraries.put(qnnRuntimeFileIdentity(role, probe.optString(key)))
    }
    return identity.put("selectedLibraries", libraries).toString()
}

private fun qnnRuntimeFileIdentity(role: String, rawPath: String): JSONObject {
    val identity = JSONObject().put("role", role)
    if (rawPath.isBlank()) return identity.put("status", "not_selected")

    val file = File(rawPath)
    val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    identity.put("path", canonicalPath)
    if (!file.exists()) return identity.put("status", "missing")
    if (!file.isFile) return identity.put("status", "not_file")

    identity
        .put("length", file.length())
        .put("lastModified", file.lastModified())
    val digest = runCatching { file.sha256Contents() }.getOrNull()
        ?: return identity.put("status", "unreadable")
    return identity
        .put("status", "available")
        .put("sha256", digest)
}

internal fun sdxlQnnConditioningGraphSha256(conditioningRoot: File): String {
    val executionAssetNames = buildList {
        add(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.first())
        File(conditioningRoot, SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME)
            .takeIf { file -> file.isFile && file.length() > 0L }
            ?.let { add(SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME) }
        addAll(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.drop(1))
    }
    val payload = executionAssetNames.joinToString(
        separator = "\n",
        postfix = "\n"
    ) { name ->
        val asset = File(conditioningRoot, name)
        require(asset.isFile && asset.length() > 0L) {
            "MNN SDXL conditioning execution asset is missing or empty: ${asset.absolutePath}"
        }
        "$name=${asset.sha256Contents()}"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private val QNN_RUNTIME_LIBRARY_KEYS = listOf(
    "qnn_system" to "qnnSystemLibraryPath",
    "qnn_htp" to "qnnHtpLibraryPath",
    "htp_skel" to "htpSkelLibraryPath",
    "htp_stub" to "htpStubLibraryPath",
    "cdsp_rpc" to "cdspRpcLibraryPath"
)

private val SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES = listOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight"
)

private const val SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME = "clip.mnn.weight"

package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.QnnRuntimeProfileSelector
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import org.json.JSONObject

/**
 * QNN package inspection for the managed model download installer (P6/M8), moved verbatim from
 * ManagedModelDownloadInstaller: metadata-only context/bundle preflight (never creates a QNN
 * device/context/graph). device + context arrive as explicit parameters; the installer keeps a
 * same-signature delegate so its call sites stay unchanged. QnnBundlePreflight is internal because
 * the installer consumes its return type; the metadata/diagnostic types stay file-private.
 */

internal data class QnnBundlePreflight(val status: String, val message: String)

private data class QnnPackageMetadata(
    val socModel: Int? = null,
    val socVersion: String? = null,
    val htpArch: Int? = null,
    val qnnSdk: String? = null,
    val source: String = ""
)

private data class QnnContextDiagnostic(
    val text: String,
    val metadataParsed: Boolean,
    val packageMetadata: QnnPackageMetadata? = null,
    val target: QnnContextTargetIdentity? = null
)

private fun readQnnPackageMetadata(bundleDir: File): QnnPackageMetadata? {
    val candidates = bundleDir.walkTopDown()
        .filter { file ->
            file.isFile && file.name.lowercase() in setOf(
                "metadata.json", "qnn_metadata.json", "context.json",
                "qnn_context.json", "runtime.json", "release_assets.json"
            )
        }
        .take(24)
        .toList()
    for (file in candidates) {
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
        val objects = buildList {
            fun collect(value: Any?, depth: Int) {
                if (depth > 4) return
                when (value) {
                    is JSONObject -> {
                        add(value)
                        val keys = value.keys()
                        while (keys.hasNext()) collect(value.opt(keys.next()), depth + 1)
                    }
                    is org.json.JSONArray -> {
                        for (index in 0 until value.length()) collect(value.opt(index), depth + 1)
                    }
                }
            }
            collect(root, 0)
        }
        fun firstInt(vararg keys: String): Int? = objects.asSequence()
            .flatMap { objectValue -> keys.asSequence().map { key -> objectValue.optInt(key, 0) } }
            .firstOrNull { it > 0 }
        fun firstText(vararg keys: String): String? = objects.asSequence()
            .flatMap { objectValue -> keys.asSequence().map { key -> objectValue.optString(key) } }
            .map { it.trim() }
            .firstOrNull(String::isNotBlank)
        val socModel = firstInt("soc_model", "socModel", "target_soc", "targetSoc")
        val htpArch = firstInt("htp_version", "htpVersion", "htp_arch", "htpArch", "htp_arch_version")
        val qnnSdk = firstText("qnnSdk", "qnn_sdk", "qairt", "qnn", "sdkVersion", "sdk")
        val socVersion = firstText("socVersion", "soc_version", "marketing_name", "chipset", "name")
        if (socModel != null || htpArch != null || qnnSdk != null || socVersion != null) {
            return QnnPackageMetadata(
                socModel = socModel,
                socVersion = socVersion,
                htpArch = htpArch,
                qnnSdk = qnnSdk,
                source = file.relativeTo(bundleDir).invariantSeparatorsPath
            )
        }
    }
    return null
}

/** Metadata-only check: it never creates a QNN device, context, or graph. */
internal fun inspectQnnBundleCompatibility(
    bundleDir: File,
    installedBundle: ImageEngineBundleSpec,
    device: DeviceProfile,
    context: Context
): QnnBundlePreflight {
    val profile = device.accelerationProfile
    val currentChipset = profile.chipsetCode.ifBlank { device.socModel }
    val currentSocModel = DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode(currentChipset)
    val deviceArch = profile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
        ?: DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
            currentChipset
        )
    val expectedProfile = installedBundle.requiredRuntimeProfile
    val contextSpecs = installedBundle.qnnSmokeSpecs
    val runtimeStatus = profile.qnnRuntime
    val packagedRuntimeAvailable = qnnImageBundleRuntimeProfiles(bundleDir).isNotEmpty()
    val runtimeIssues = buildList {
        // A device-wide probe can inspect a different SDK from the runtime
        // packaged with this model. The bundle tuple is checked at staging.
        if (!packagedRuntimeAvailable) {
            if (!runtimeStatus.qnnSystemLibraryPresent) add("缺少 libQnnSystem.so")
            if (!runtimeStatus.qnnHtpLibraryPresent) add("缺少 libQnnHtp.so")
            if (!runtimeStatus.htpSkelLibraryPresent) {
                val arch = deviceArch?.toString()?.let { "V${it}" } ?: "V*"
                add("缺少 libQnnHtp${arch}Skel.so")
            }
            if (!runtimeStatus.htpStubLibraryPresent) {
                add("缺少 QNN HTP stub transport（libQnnHtpV*Stub.so）")
            }
            if (runtimeStatus.probeState.name == "LOAD_FAILED") {
                runtimeStatus.probeMessage.takeIf { it.isNotBlank() }
                    ?.let { add("设备全局 QNN runtime 探测失败：$it") }
            }
        }
    }
    if (contextSpecs.isEmpty()) {
        android.util.Log.i("McaModelDownload", "QNN metadata unavailable: ${installedBundle.id} has no context specifications")
        return QnnBundlePreflight(
            "EXPERIMENTAL",
            buildString {
                append("包未声明可检查的 QNN context/目标 SoC/HTP 信息；安装不会被设备型号拦截，首次真实加载决定结果。")
                expectedProfile?.let { append(" 声明 SDK=${it.qnnSdk ?: "未知"}、HTP V${it.htpArch ?: "未知"}。") }
                if (runtimeIssues.isNotEmpty()) append(" 本机运行时：${runtimeIssues.joinToString("；")}。")
            }
        )
    }
    val packageMetadata = readQnnPackageMetadata(bundleDir)
    val observedContextArchs = mutableSetOf<Int>()
    val observedContextSocModels = mutableSetOf<Int>()
    val observedContextSocVersions = mutableSetOf<String>()
    val observedContextSdkVersions = mutableSetOf<String>()
    val diagnostics = contextSpecs.map { spec ->
        val relative = resolvedDownloadedQnnContextPath(spec.contextBinary, emptyList())
        val direct = File(bundleDir, relative).canonicalFile.takeIf {
            it.path.startsWith(bundleDir.canonicalPath + File.separator)
        }
        val file = direct?.takeIf(File::isFile) ?: bundleDir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals(spec.contextBinary.substringAfterLast('/'), ignoreCase = true) }
            ?: return@map QnnContextDiagnostic(
                text = "${spec.contextBinary}: context 文件缺失",
                metadataParsed = false
            )
        if (file.length() <= 0L) return@map QnnContextDiagnostic(
            text = "${spec.contextBinary}: context 文件为空",
            metadataParsed = false
        )
        val json = runCatching {
            NativeQnnBridge().inspectContextMetadata(
                file.absolutePath,
                org.json.JSONArray(qnnRuntimeDirectoriesFor(context, bundleDir)).toString()
            )
        }.getOrElse { error -> return@map QnnContextDiagnostic(
            text = "${spec.contextBinary}: 预检不可用（${error.message ?: "native bridge unavailable"}）",
            metadataParsed = false
        ) }
        val root = JSONObject(json)
        val runtime = root.optJSONObject("runtime")
        val metadata = root.optJSONObject("binaryMetadata")
        val parsed = metadata?.optBoolean("parsed", false) == true
        val socModel = metadata?.optInt("socModel", 0)?.takeIf { it > 0 }
        val socVersion = metadata?.optString("socVersion").orEmpty().trim().takeIf { it.isNotBlank() }
        val build = metadata?.optString("buildId").orEmpty()
        val runtimeArch = runtime?.optInt("htpArchVersion", 0)?.takeIf { it > 0 }
        val arch = socModel?.let(QnnRuntimeProfileSelector::htpArchVersionForSocModel)
        val sdk = qnnSdkVersionFromContextBuildId(build)
        socModel?.let(observedContextSocModels::add)
        socVersion?.let(observedContextSocVersions::add)
        arch?.let(observedContextArchs::add)
        sdk?.let(observedContextSdkVersions::add)
        val target = if (parsed) {
            qnnContextTargetIdentity(
                socModel = socModel ?: 0,
                socVersion = socVersion.orEmpty(),
                buildId = build,
                htpArch = arch
            )
        } else null
        QnnContextDiagnostic(
            text = buildString {
            append(spec.contextBinary).append(": ")
            if (!parsed) {
                append("context 元数据无法读取：").append(metadata?.optString("message").orEmpty())
            } else {
                append("目标 SoC=").append(socModel ?: "未知")
                arch?.let { append("/HTP V").append(it) }
                sdk?.let { append("，context SDK=").append(it) }
                metadata.optString("coreApiVersion").takeIf { it.isNotBlank() }?.let { append("，QNN core API=").append(it) }
                metadata.optString("backendApiVersion").takeIf { it.isNotBlank() }?.let { append("，backend API=").append(it) }
                runtime?.optJSONObject("compile")?.let { compile ->
                    append("，APK SDK headers=").append(if (compile.optBoolean("sdkHeadersPresent", false)) "有" else "无")
                }
                runtimeArch?.let { append("，本次探测所选 runtime HTP V").append(it) }
                if (deviceArch != null && arch != null && deviceArch != arch) {
                    append("；当前设备 HTP V").append(deviceArch).append(" 与 context 不同")
                }
                if (build.isNotBlank()) append("，buildId=").append(build)
            }
            },
            metadataParsed = parsed,
            packageMetadata = packageMetadata,
            target = target
        )
    }
    val text = diagnostics.joinToString("；")
    val targetSocMismatch = currentSocModel != null && observedContextSocModels.any { it != currentSocModel }
    val targetArchMismatch = deviceArch != null && when {
        observedContextArchs.isNotEmpty() -> observedContextArchs.any { it != deviceArch }
        else -> expectedProfile?.htpArch?.let { it != deviceArch } == true
    }
    val currentSocLabel = currentSocModel?.toString() ?: "未知"
    val deviceArchLabel = deviceArch?.toString() ?: "未知"
    val declaredSdkMismatch = expectedProfile?.qnnSdk?.let { declared ->
        observedContextSdkVersions.any { observed ->
            qnnContextSdkMatchesDeclared(observed, declared) == false
        }
    } == true
    val metadataUnavailable = diagnostics.any { diagnostic ->
        !diagnostic.metadataParsed ||
            diagnostic.target?.socModel == null ||
            diagnostic.target?.sdkVersion == null
    }
    val missingContextFiles = diagnostics.any { diagnostic ->
        diagnostic.text.contains("文件缺失") || diagnostic.text.contains("文件为空")
    }
    val preflightUnavailable = diagnostics.any { diagnostic ->
        diagnostic.text.contains("预检不可用") || diagnostic.text.contains("元数据无法读取")
    }
    val mismatchReasons = buildList {
        if (targetSocMismatch) {
            add("目标 SoC ${observedContextSocModels.joinToString()} 与当前 SoC $currentSocLabel 不同")
        }
        if (targetArchMismatch) {
            val target = observedContextArchs.takeIf { it.isNotEmpty() }
                ?.joinToString("/V") ?: expectedProfile?.htpArch?.toString() ?: "未知"
            add("包/context 目标 HTP V$target 与当前设备探测 HTP V$deviceArchLabel 不同")
        }
        if (declaredSdkMismatch) {
            add("context SDK ${observedContextSdkVersions.joinToString()} 与声明 SDK ${expectedProfile.qnnSdk} 不同")
        }
    }
    val reasons = mismatchReasons + listOfNotNull(
        "QNN context 的 SoC/HTP/SDK 元数据不完整".takeIf { metadataUnavailable }
    )
    return if (missingContextFiles || preflightUnavailable || reasons.isNotEmpty() || runtimeIssues.isNotEmpty()) {
        val profileText = expectedProfile?.let { "包声明 QNN ${it.qnnSdk} / HTP V${it.htpArch}" } ?: "包未声明 QNN SDK/HTP"
        val reasonText = reasons.joinToString("；").ifBlank { "无法完整读取 context 元数据" }
        val runtimeText = runtimeIssues.takeIf { it.isNotEmpty() }?.joinToString("；")
        android.util.Log.i("McaModelDownload", "QNN preflight ${installedBundle.id}: $profileText; $reasonText; runtime=${runtimeText ?: "ok"}; $text")
        val userMessage = buildString {
            packageMetadata?.let { declared ->
                append("包元数据 ${declared.source}：SoC=${declared.socModel ?: "未知"}、HTP V${declared.htpArch ?: "未知"}、SDK=${declared.qnnSdk ?: "未知"}。")
                append(" ")
            }
            when {
                missingContextFiles -> append("QNN context 文件缺失或为空（文件完整性与运行包兼容性分开报告），请重新校验或重新下载。")
                mismatchReasons.isNotEmpty() -> append(mismatchReasons.joinToString("；")).append("。")
                preflightUnavailable -> append("本机暂时无法读取 QNN context 元数据；这不是文件损坏，首次真实加载仍会给出最终结果。")
                metadataUnavailable -> append("QNN context 的目标 SoC/HTP/SDK 元数据不完整，暂不能确认精确兼容性。")
            }
            runtimeText?.let {
                if (isNotEmpty()) append(" ")
                append("本机 QNN 运行时：").append(it).append("。")
            }
            if (isNotEmpty() && text.isNotBlank()) append(" 诊断：").append(text)
            if (isEmpty()) append("安装后由真实 QNN graph smoke 决定能否执行；未知设备仍保留通用加载路径。")
        }
        QnnBundlePreflight(
            if (missingContextFiles) "FAILED" else "EXPERIMENTAL",
            userMessage
        )
    } else {
        val deviceText = deviceArch?.let { "当前设备 HTP V$it" } ?: "当前设备 HTP 未知"
        android.util.Log.i("McaModelDownload", "QNN preflight ${installedBundle.id}: $deviceText; $text")
        QnnBundlePreflight(
            "PREFLIGHT_PASSED",
            "包声明、context 元数据与运行库预检信息已分别记录；实际兼容性由隔离进程加载和 graph 执行决定。可在本地页选择模型。"
        )
    }
}

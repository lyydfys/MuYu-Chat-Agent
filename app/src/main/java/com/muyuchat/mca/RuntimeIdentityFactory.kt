package com.muyuchat.mca

import android.content.Context
import android.os.Build
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ParameterFieldPolicyRegistry
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtExecutionAdmissionMode
import com.muyuchat.core.modelstore.sparseMoeInfo
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds the identity used by the runtime/profile coordinator.
 *
 * This intentionally lives in the app layer.  The core identity type is a
 * value object, while the app is the only layer that knows where the installed
 * APK libraries and user model files live.  No transient measurement (free
 * memory, temperature, battery, storage pressure, or foreground state) is
 * allowed to enter this factory's identity material.
 */
internal object RuntimeIdentityFactory {
    private const val HASH_SCHEMA = "runtime-identity-hash-v2"
    private const val ENGINE_CONTRACT_VERSION = "mca-local-chat-engine-v1"
    private const val IDENTITY_SCHEMA_VERSION = "runtime-parameters-schema-v1"
    private const val RULE_SET_VERSION = "model-family-rules-v3-target-model-adaptive-tuning"
    private const val EVALUATOR_VERSION =
        "bootstrap-load-v1:1|minimum-text-v1:2|canary:text-v1|safety:safety-v1"
    private const val EMBEDDED_TOKENIZER_VERSION = "embedded-tokenizer-v1"
    private const val EMBEDDED_TEMPLATE_VERSION = "embedded-template-v1"
    private const val EMBEDDED_CONFIG_VERSION = "embedded-config-v1"
    private const val DIRECTORY_MERKLE_VERSION = "directory-merkle-v1"
    private const val NATIVE_SET_VERSION = "native-library-set-v1"
    private const val MAX_TEMPLATE_CONFIG_BYTES = 4L * 1024L * 1024L
    private const val MNN_RUNTIME_CONFIG_FILE_NAME = "mca_runtime_config.json"
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** Android values needed by the factory; kept separate so JVM tests do not
     * need a running Activity, PackageManager, or native library loader. */
    internal data class PlatformSnapshot(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val nativeLibraryDir: File?
    )

    /**
     * Material is useful to tests and diagnostics.  [identity] remains the
     * canonical persisted value; config has no dedicated field in the current
     * core schema, so its domain-separated digest is included in
     * [ModelRuntimeIdentity.backendFingerprint].
     */
    internal data class BuildResult(
        val identity: ModelRuntimeIdentity,
        val configFingerprint: String,
        val nativeLibraries: List<String>
    )

    /**
     * Public app entry point.  The manifest SHA is trusted when it is a full
     * SHA-256; otherwise the selected file/bundle is content-hashed below.
     */
    fun create(
        context: Context,
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        qairtAdmission: QairtExecutionAdmission? = null
    ): ModelRuntimeIdentity = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platformSnapshot(context),
        qairtAdmission = qairtAdmission,
        qairtAdmissionPassed = false
    ).identity

    /** Compatibility overload for callers that only persist the admission
     * result and do not retain the full advisory object. */
    fun create(
        context: Context,
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        qairtAdmissionPassed: Boolean
    ): ModelRuntimeIdentity = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platformSnapshot(context),
        qairtAdmission = null,
        qairtAdmissionPassed = qairtAdmissionPassed
    ).identity

    /** Pure/JVM-friendly entry point used by unit tests and migration tools. */
    internal fun buildForTesting(
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        platform: PlatformSnapshot = PlatformSnapshot(
            packageName = "com.muyuchat.mca",
            versionName = "test",
            versionCode = 0L,
            nativeLibraryDir = null
        ),
        qairtAdmission: QairtExecutionAdmission? = null,
        qairtAdmissionPassed: Boolean = false
    ): BuildResult = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platform,
        qairtAdmission = qairtAdmission,
        qairtAdmissionPassed = qairtAdmissionPassed
    )

    /** Testable native fingerprint helper; it hashes each selected .so once. */
    internal fun nativeFingerprint(
        nativeLibraryDir: File?,
        runtime: LocalChatRuntime,
        device: DeviceProfile
    ): Pair<String, List<String>> {
        return nativeFingerprint(nativeLibraryDir, runtime, device, HashSession())
    }

    private fun nativeFingerprint(
        nativeLibraryDir: File?,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        session: HashSession
    ): Pair<String, List<String>> {
        val names = nativeLibraryNames(runtime, device)
        val entries = names.map { name ->
            val file = nativeLibraryDir?.let { File(it, name) }
            val sha = if (file?.isFile == true) session.fileSha256(file) else "missing"
            name to sha
        }
        return session.entriesDigest(NATIVE_SET_VERSION, entries) to names
    }

    private fun build(
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        platform: PlatformSnapshot,
        qairtAdmission: QairtExecutionAdmission?,
        qairtAdmissionPassed: Boolean
    ): BuildResult {
        val scope = installationScopeId.trim()
        require(scope.isNotBlank()) { "installationScopeId must not be blank." }
        val modelPath = model.path.trim()
        require(modelPath.isNotBlank()) { "Model manifest path must not be blank." }
        val root = canonicalFile(File(modelPath))
        require(root.exists()) { "Model manifest path does not exist: $modelPath" }

        val session = HashSession()
        val bundleFiles = if (root.isDirectory) safeFilesUnder(root, runtime) else emptyList()
        val manifestArtifactSha = normalizedSha(model.sha256)
        val artifactFingerprint = manifestArtifactSha ?: when {
            root.isFile -> session.fileSha256(root)
            root.isDirectory -> directoryMerkle(root, session, bundleFiles)
            else -> error("Model manifest path is neither a file nor a directory: $modelPath")
        }
        // MNN and QAIRT manifests already persist a bundle SHA.  Preserve it
        // as-is when present; fallback is the complete path+content Merkle.
        val bundleFingerprint = if (root.isDirectory) {
            manifestArtifactSha ?: directoryMerkle(root, session, bundleFiles)
        } else {
            ""
        }

        val projectorFile = resolveProjector(root, model.visionProjectorPath)
        val projectorFingerprint = normalizedSha(model.visionProjectorSha256)
            ?: projectorFile?.takeIf(File::isFile)?.let(session::fileSha256).orEmpty()

        val tokenizerFiles = componentFiles(root, ComponentKind.TOKENIZER, bundleFiles)
        val templateFiles = componentFiles(root, ComponentKind.TEMPLATE, bundleFiles)
        val configFiles = componentFiles(root, ComponentKind.CONFIG, bundleFiles)
        val tokenizerFingerprint = componentFingerprint(
            root = root,
            artifactFingerprint = artifactFingerprint,
            kind = ComponentKind.TOKENIZER,
            files = tokenizerFiles,
            session = session
        )
        val templateFingerprint = componentFingerprint(
            root = root,
            artifactFingerprint = artifactFingerprint,
            kind = ComponentKind.TEMPLATE,
            files = templateFiles,
            session = session
        )
        val configFingerprint = componentFingerprint(
            root = root,
            artifactFingerprint = artifactFingerprint,
            kind = ComponentKind.CONFIG,
            files = configFiles,
            session = session
        )

        val (nativeLibrarySha256, nativeLibraries) = nativeFingerprint(
            nativeLibraryDir = platform.nativeLibraryDir,
            runtime = runtime,
            device = device,
            session = session
        )
        val admissionState = qairtAdmissionState(runtime, qairtAdmission, qairtAdmissionPassed)
        val stableDeviceMaterial = stableDeviceMaterial(device)
        val backendFingerprint = session.textDigest(
            "backend-capability-v2",
            listOf(
                "runtime=${runtime.name}",
                "backendId=${runtime.backendId}",
                "abi=${device.primaryAbi.normalizedOrUnknown()}",
                "native=$nativeLibrarySha256",
                "config=${configFingerprint.ifBlank { "none" }}",
                "device=$stableDeviceMaterial",
                "qairtAdmission=$admissionState"
            )
        )

        val capabilities = capabilities(
            model = model,
            root = root,
            runtime = runtime,
            device = device,
            projectorFingerprint = projectorFingerprint,
            tokenizerFingerprint = tokenizerFingerprint,
            templateFingerprint = templateFingerprint,
            qairtAdmissionState = admissionState,
            bundleFiles = bundleFiles
        )
        val runtimeVersion = runtimeVersion(runtime, platform)
        val ruleSetFingerprint = session.textDigest(
            "rule-set-v1",
            listOf(RULE_SET_VERSION, runtime.name)
        )
        val evaluatorFingerprint = session.textDigest(
            "evaluator-v1",
            listOf(EVALUATOR_VERSION, runtime.name)
        )
        val identity = ModelRuntimeIdentity(
            modelId = model.id.trim().ifBlank {
                model.fileName.trim().ifBlank { root.name.ifBlank { "local-model" } }
            },
            artifactFingerprint = artifactFingerprint,
            runtime = runtime,
            runtimeVersion = runtimeVersion,
            nativeLibrarySha256 = nativeLibrarySha256,
            abi = device.primaryAbi.normalizedOrUnknown(),
            backendFingerprint = backendFingerprint,
            projectorFingerprint = projectorFingerprint,
            bundleFingerprint = bundleFingerprint,
            tokenizerFingerprint = tokenizerFingerprint,
            templateFingerprint = templateFingerprint,
            deviceCapabilityFingerprint = session.textDigest(
                "device-capability-v2",
                listOf(stableDeviceMaterial)
            ),
            installationScopeId = scope,
            ruleSetFingerprint = ruleSetFingerprint,
            evaluatorFingerprint = evaluatorFingerprint,
            engineContractVersion = ENGINE_CONTRACT_VERSION,
            schemaFingerprint = IDENTITY_SCHEMA_VERSION,
            parameterPolicyVersion = ParameterFieldPolicyRegistry.BUILTIN_POLICY_VERSION,
            capabilities = capabilities
        )
        return BuildResult(
            identity = identity,
            configFingerprint = configFingerprint,
            nativeLibraries = nativeLibraries
        )
    }

    private fun platformSnapshot(context: Context): PlatformSnapshot {
        val app = context.applicationContext ?: context
        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 0L
        } else {
            packageInfo?.versionCode?.toLong() ?: 0L
        }
        val nativeDir = app.applicationInfo.nativeLibraryDir
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
        return PlatformSnapshot(
            packageName = app.packageName,
            versionName = packageInfo?.versionName.orEmpty().ifBlank { "unknown" },
            versionCode = versionCode,
            nativeLibraryDir = nativeDir
        )
    }

    private fun runtimeVersion(runtime: LocalChatRuntime, platform: PlatformSnapshot): String {
        val implementation = when (runtime) {
            LocalChatRuntime.LLAMA_CPP -> "llama.cpp@4d8cc0c56ffba3f8b7fdb0130627fed2a6f71958"
            LocalChatRuntime.MNN_CPU -> "mnn@3.6.0-cc20f672af9e177e2fa338c332dc097de2fc9264"
            LocalChatRuntime.GENIEX_LLAMA_CPP -> "geniex@0.3.12-mca1+llama.cpp"
            LocalChatRuntime.GENIEX_QAIRT -> "geniex-qairt@0.3.12-mca1"
        }
        return "$implementation|apk=${platform.packageName}/${platform.versionName}#${platform.versionCode}"
    }

    private enum class ComponentKind(val domain: String) {
        TOKENIZER("tokenizer"),
        TEMPLATE("template"),
        CONFIG("config")
    }

    private fun componentFingerprint(
        root: File,
        artifactFingerprint: String,
        kind: ComponentKind,
        files: List<File>,
        session: HashSession
    ): String {
        if (root.isFile) {
            val embedded = session.textDigest(
                when (kind) {
                    ComponentKind.TOKENIZER -> EMBEDDED_TOKENIZER_VERSION
                    ComponentKind.TEMPLATE -> EMBEDDED_TEMPLATE_VERSION
                    ComponentKind.CONFIG -> EMBEDDED_CONFIG_VERSION
                },
                listOf(artifactFingerprint)
            )
            if (files.isEmpty()) return embedded
            return session.textDigest(
                "${kind.domain}-with-sidecars-v1",
                listOf(embedded, session.entriesDigest(kind.domain, files.map { relativeSidecarPath(it) to session.fileSha256(it) }))
            )
        }
        return if (files.isEmpty()) "" else session.entriesDigest(
            kind.domain,
            files.map { relativePath(root, it) to session.fileSha256(it) }
        )
    }

    private fun componentFiles(root: File, kind: ComponentKind, bundleFiles: List<File>): List<File> {
        val candidates = if (root.isDirectory) {
            bundleFiles
        } else {
            val parent = root.parentFile ?: return emptyList()
            val stem = root.name.substringBeforeLast('.', root.name).lowercase(Locale.US)
            parent.listFiles().orEmpty()
                .filter { it.isFile && it != root }
                .filter { file ->
                    val lower = file.name.lowercase(Locale.US)
                    val scoped = lower.startsWith("$stem.") || lower.startsWith("${stem}_")
                    scoped || lower in GENERIC_SIDECAR_NAMES
                }
        }
        return candidates
            .filter { file -> when (kind) {
                ComponentKind.TOKENIZER -> isTokenizerFile(file)
                ComponentKind.TEMPLATE -> isTemplateFile(file)
                ComponentKind.CONFIG -> isConfigFile(file)
            } }
            .distinctBy { canonicalFile(it).path }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun isTokenizerFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return "tokenizer" in name ||
            name in setOf("vocab.json", "vocab.txt", "merges.txt", "spiece.model", "sentencepiece.model") ||
            name.endsWith(".model") && ("sentence" in name || "spiece" in name)
    }

    private fun isTemplateFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        if ("template" in name || name.endsWith(".jinja") || name.endsWith(".jinja2") ||
            name.endsWith(".tmpl") || name.endsWith(".prompt")) return true
        // Standard HuggingFace/MNN config files may carry an inline
        // chat_template. Inspect only bounded metadata files so an unrelated
        // execution-config edit does not invalidate the template fingerprint.
        return name in TEMPLATE_CONFIG_NAMES &&
            file.length() <= MAX_TEMPLATE_CONFIG_BYTES &&
            hasInlineTemplateSignal(file)
    }

    private fun hasInlineTemplateSignal(file: File): Boolean = runCatching {
        val text = file.readText(StandardCharsets.UTF_8).lowercase(Locale.US)
        "chat_template" in text || "chattemplate" in text ||
            "prompt_template" in text || ("jinja" in text && "template" in text)
    }.getOrDefault(false)

    private fun isConfigFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        // Tokenizer assets are a separate identity component. In particular,
        // tokenizer_config.json must not make a tokenizer edit look like a
        // model execution-config edit.
        if (isTokenizerFile(file)) return false
        val extension = name.substringAfterLast('.', "")
        return "config" in name || "metadata" in name || "manifest" in name ||
            extension in CONFIG_EXTENSIONS
    }

    private fun resolveProjector(root: File, rawPath: String?): File? {
        val raw = rawPath?.trim().orEmpty()
        if (raw.isBlank()) return null
        val candidate = File(raw)
        val candidates = if (candidate.isAbsolute) {
            listOf(candidate)
        } else {
            buildList {
                if (root.isDirectory) add(File(root, raw))
                root.parentFile?.let { add(File(it, raw)) }
            }
        }
        return candidates.asSequence()
            .map(::canonicalFile)
            .firstOrNull(File::isFile)
    }

    private fun capabilities(
        model: ModelManifest,
        root: File,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        projectorFingerprint: String,
        tokenizerFingerprint: String,
        templateFingerprint: String,
        qairtAdmissionState: String,
        bundleFiles: List<File>
    ): Set<String> = buildSet {
        val sparseMoe = model.sparseMoeInfo()
        add("local_chat")
        add("text_generation")
        add("parameter_coordinator_v1")
        add("runtime:${runtime.backendId}")
        add("abi:${device.primaryAbi.normalizedOrUnknown()}")
        add("acceleration:${device.accelerationProfile.localChat.status.name.lowercase(Locale.US)}")
        if (tokenizerFingerprint.isNotBlank()) add("tokenizer")
        if (templateFingerprint.isNotBlank()) add("chat_template")
        if (projectorFingerprint.isNotBlank() || model.hasVisionProjector) add("projector")
        when (runtime) {
            LocalChatRuntime.LLAMA_CPP -> {
                add("llama_cpp_cpu")
                if (sparseMoe.isSparseMoe) {
                    add("sparse_moe")
                    val verifiedQwen36 = model.sha256.equals(
                        VERIFIED_QWEN36_MTP_SHA256,
                        ignoreCase = true
                    )
                    if (verifiedQwen36) {
                        add("draft_mtp")
                    }
                    val physicalRam = device.totalRamBytes.takeIf { it > 0L }
                        ?: device.advertisedRamBytes
                    val conservativeMemoryTier = physicalRam <= 0L ||
                        physicalRam <= MAX_SPARSE_MOE_16_GIB_TIER_BYTES
                    if (conservativeMemoryTier) {
                        add("sparse_moe_16gb_tier")
                        if (verifiedQwen36) add("verified_q4_kv_cache")
                    }
                }
            }
            LocalChatRuntime.MNN_CPU -> {
                add("mnn_cpu")
                if (root.isDirectory && hasVisionSignal(bundleFiles)) add("mnn_vision_candidate")
            }
            LocalChatRuntime.GENIEX_LLAMA_CPP -> {
                add("geniex_llama_cpp")
                if (device.accelerationProfile.qnnRuntime.ready) add("qnn_htp_candidate")
                if (device.accelerationProfile.qnnRuntime.usableForSmoke) add("gpu_offload")
            }
            LocalChatRuntime.GENIEX_QAIRT -> {
                add("geniex_qairt")
                add("qairt_admission:$qairtAdmissionState")
                if (device.accelerationProfile.qnnRuntime.ready) add("qnn_htp_candidate")
            }
        }
    }

    private fun hasVisionSignal(files: List<File>): Boolean = files.any { file ->
        val lower = file.name.lowercase(Locale.US)
        "vision" in lower || "visual" in lower || "processor" in lower || "mmproj" in lower
    }

    private const val GIB = 1024L * 1024L * 1024L
    private const val MAX_SPARSE_MOE_16_GIB_TIER_BYTES = 16L * GIB
    private const val VERIFIED_QWEN36_MTP_SHA256 =
        "1fb8a998362ebb5f7f3c8ece6d4803a74ba32211c751de2e76b81e3379fbf050"

    private fun qairtAdmissionState(
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
    private fun stableDeviceMaterial(device: DeviceProfile): String {
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

    private fun nativeLibraryNames(
        runtime: LocalChatRuntime,
        device: DeviceProfile
    ): List<String> {
        val base = when (runtime) {
            LocalChatRuntime.LLAMA_CPP -> listOf("libmca_native.so")
            LocalChatRuntime.MNN_CPU -> listOf(
                "libmca_mnn_native.so", "libMNN.so", "libMNN_Express.so", "libMNN_CL.so",
                "libMNNOpenCV.so", "libMNNAudio.so", "libllm.so"
            )
            LocalChatRuntime.GENIEX_LLAMA_CPP -> listOf(
                "libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_plugin_llama_cpp.so",
                "libllama.so", "libggml.so", "libggml-base.so", "libggml-cpu.so", "libmtmd.so"
            )
            LocalChatRuntime.GENIEX_QAIRT -> listOf(
                "libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_plugin_qairt.so",
                "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
                "libQnnHtpV79.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so",
                "libQnnHtpV81.so", "libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so"
            )
        }
        // A future QAIRT package may use a different HTP generation. Include
        // the generation selected by the device profile without guessing a
        // exact chipset-name table; missing files remain explicit in the digest.
        val generation = Regex("v(\\d+)", RegexOption.IGNORE_CASE)
            .find(device.accelerationProfile.qnnHtpGeneration)
            ?.groupValues
            ?.getOrNull(1)
        val generationNames = generation?.let { version ->
            listOf("libQnnHtpV${version}.so", "libQnnHtpV${version}Stub.so", "libQnnHtpV${version}Skel.so")
        }.orEmpty()
        return (base + generationNames).distinct()
    }

    private fun directoryMerkle(root: File, session: HashSession, files: List<File>): String {
        val entries = files.map { file ->
            relativePath(root, file) to session.fileSha256(file)
        }
        return session.entriesDigest(DIRECTORY_MERKLE_VERSION, entries)
    }

    private fun safeFilesUnder(root: File, runtime: LocalChatRuntime): List<File> {
        if (!root.isDirectory) return emptyList()
        val canonicalRoot = canonicalFile(root)
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return root.walkTopDown()
            .filter(File::isFile)
            .mapNotNull { file ->
                val canonical = runCatching { canonicalFile(file) }.getOrNull() ?: return@mapNotNull null
                if (canonical.path == canonicalRoot.path || canonical.path.startsWith(prefix)) file else null
            }
            .filterNot { file ->
                // Native writes this deterministic compatibility projection
                // after identity creation; it is derived runtime state, not a
                // user model component.
                runtime == LocalChatRuntime.MNN_CPU &&
                    relativePath(root, file).equals(MNN_RUNTIME_CONFIG_FILE_NAME, ignoreCase = true)
            }
            .toList()
    }

    private fun relativePath(root: File, file: File): String = runCatching {
        // Keep the lexical relative name (including a symlink's name) while
        // HashSession still hashes the canonical target content. This makes a
        // rename or symlink-path change part of the Merkle identity.
        root.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize())
            .toString().replace('\\', '/')
    }.getOrElse { file.name }

    private fun relativeSidecarPath(file: File): String =
        "sidecar/${file.name}"

    private fun canonicalFile(file: File): File = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

    private fun normalizedSha(value: String?): String? = value
        ?.trim()
        ?.takeIf { SHA256.matches(it) }
        ?.lowercase(Locale.US)

    private fun String.normalizedOrUnknown(): String = trim().ifBlank { "unknown" }

    private class HashSession {
        private val fileHashes = LinkedHashMap<String, String>()

        fun fileSha256(file: File): String {
            val canonical = canonicalFile(file)
            val key = canonical.path
            return fileHashes.getOrPut(key) {
                require(canonical.isFile && canonical.canRead()) {
                    "Unable to hash runtime identity component: ${canonical.absolutePath}"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                canonical.inputStream().buffered().use { input -> digestStream(digest, input) }
                digestHex(digest.digest())
            }
        }

        fun entriesDigest(domain: String, entries: List<Pair<String, String>>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("$HASH_SCHEMA/$domain\n".toByteArray(StandardCharsets.UTF_8))
            entries.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .forEach { (path, sha) ->
                    digest.update(path.replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(sha.lowercase(Locale.US).toByteArray(StandardCharsets.US_ASCII))
                    digest.update(0.toByte())
                }
            return digestHex(digest.digest())
        }

        fun textDigest(domain: String, values: List<String>): String = entriesDigest(
            domain,
            values.mapIndexed { index, value -> index.toString() to value }
        )
    }

    private fun digestStream(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }

    private fun digestHex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        "%02x".format(Locale.US, byte.toInt() and 0xff)
    }

    private val GENERIC_SIDECAR_NAMES = setOf(
        "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "added_tokens.json",
        "vocab.json", "vocab.txt", "merges.txt", "spiece.model", "sentencepiece.model",
        "config.json", "generation_config.json", "chat_template.jinja", "chat_template.jinja2",
        "template.jinja", "template.jinja2"
    )
    private val TEMPLATE_CONFIG_NAMES = setOf(
        "config.json", "tokenizer_config.json", "llm_config.json", "generation_config.json",
        "genie_config.json", "metadata.json"
    )
    private val CONFIG_EXTENSIONS = setOf("json", "yaml", "yml", "toml", "ini", "properties")
}

package com.muyuchat.mca

import android.content.Context
import android.os.Build
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ParameterFieldPolicyRegistry
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.mca.RuntimeIdentityFactory.BuildResult
import com.muyuchat.mca.RuntimeIdentityFactory.ENGINE_CONTRACT_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.EVALUATOR_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.IDENTITY_SCHEMA_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.NATIVE_SET_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.PlatformSnapshot
import com.muyuchat.mca.RuntimeIdentityFactory.RULE_SET_VERSION
import java.io.File

    internal fun nativeFingerprint(
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

    internal fun build(
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
        val artifactFingerprint = when {
            root.isFile -> session.fileSha256(root)
            root.isDirectory -> manifestArtifactSha ?: directoryMerkle(root, session, bundleFiles)
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
        require(model.visionProjectorPath.isNullOrBlank() || projectorFile != null) {
            "Bound vision projector is missing or unreadable: ${model.visionProjectorPath}"
        }
        val projectorFingerprint = projectorFile?.let(session::fileSha256).orEmpty()

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

    internal fun platformSnapshot(context: Context): PlatformSnapshot {
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
            LocalChatRuntime.LLAMA_CPP -> "llama.cpp@4ceb1719101f32637b841206c172f3f058ffc182+mca-stq1_0"
            LocalChatRuntime.MNN_CPU -> "mnn@3.6.1-d407447ed56c4121a11ccbd266dc184ca1ead0c2"
            LocalChatRuntime.GENIEX_LLAMA_CPP -> "geniex@0.10.0-mca1+llama.cpp"
            LocalChatRuntime.GENIEX_QAIRT -> "geniex-qairt@0.10.0-mca1"
            LocalChatRuntime.LITERT_LM -> "litert-lm@0.16.1"
        }
        return "$implementation|apk=${platform.packageName}/${platform.versionName}#${platform.versionCode}"
    }

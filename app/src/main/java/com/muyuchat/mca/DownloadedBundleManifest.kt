package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.VisionModelBundleComponentRole
import com.muyuchat.core.download.VisionModelBundleSpec
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelSource
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Downloaded model bundle manifests, QNN runtime resolution and runtime mapping.
//
// Moved here verbatim from the end of MainViewModel during P2. These were already top-level
// functions after the class body; the move only gives them a file named for what they do.
//
// Kept in this package rather than moved to presentation, because this is not presentation:
// it writes and reads the manifest JSON that records what a downloaded bundle contains, maps
// bundle runtimes to local runtimes, and resolves QNN context and execution graph paths.
// Staying in com.muyuchat.mca also means no import changes anywhere -- the class and the
// tests reach these exactly as before.
//
// Two functions needed internal rather than private, because MainViewModel's class body calls
// them and a top-level private would no longer be visible from another file:
// toLocalChatRuntime and toFilePreview. Their bodies are unchanged. The other two top-level
// private functions, resolveDownloadedExecutionGraphPaths and relativePathFromCommonRoot, are
// used only inside this file and keep private.
//
// No manifest field, path rule, default or ordering was edited.

internal fun recommendedQairtArchitecture(model: ModelScopeRecommendedModel): String = when (model.id) {
    "qwen3_vl_4b_qairt_w4a16" -> "qwen3-vl"
    "qwen25_vl_7b_qairt_w4a16" -> "qwen2.5-vl"
    else -> "qwen3"
}

internal fun downloadedImageBundleManifestJson(
    displayName: String,
    bundle: ImageEngineBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>,
    primarySha256: String? = null,
    bundleRoot: File? = null
): JSONObject {
    val primary = targets.firstOrNull { it.first.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
    val familyHint = "${bundle.id}/$displayName/${primary?.second?.name.orEmpty()}"
    val family = bundle.modelFamily
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: LocalImageModelFamily.infer(familyHint).name
    val components = JSONArray()
    targets.forEach { (remote, file) ->
        val role = remote.bundleRole ?: ImageEngineBundleComponentRole.OPTIONAL
        components.put(
            JSONObject()
                .put("role", role.name)
                .put("path", remote.relativePath)
                .put("fileName", remote.relativePath)
                .put("sourceRepo", remote.repoId)
                .put("sourcePath", remote.path)
                .put("provider", remote.provider.name)
                .put("required", role != ImageEngineBundleComponentRole.OPTIONAL)
        )
    }
    val manifest = JSONObject()
        .put("schema", "mca.image_engine.bundle.v1")
        .put("id", bundle.id)
        .put("recommendationId", bundle.recommendationId)
        .put("title", displayName)
        .put("task", bundle.task.name)
        .put("runtime", bundle.runtime.name)
        .put("accelerator", bundle.accelerator.name)
        .put("minDeviceTier", bundle.minDeviceTier.name)
        .put("family", family)
        .put("requiresQnnRuntime", bundle.requiresQnnRuntime)
        .put("requiresSmokeTest", bundle.requiresSmokeTest)
        .put("imageSize", "${bundle.smokeSpec.width}x${bundle.smokeSpec.height}")
        .put(
            "smoke",
            JSONObject()
                .put("width", bundle.smokeSpec.width)
                .put("height", bundle.smokeSpec.height)
                .put("steps", bundle.smokeSpec.steps)
                .put("timeoutSeconds", bundle.smokeSpec.timeoutSeconds)
                .put("prompt", bundle.smokeSpec.prompt)
                .put("expectedOutputMime", bundle.smokeSpec.expectedOutputMime)
        )
        .put("components", components)
    // Runtime requirements participate in the observed manifest contract. They
    // must be present before binding the execution profile to that contract.
    bundle.requiredRuntimeProfile?.let { profile ->
        manifest.put(
            "requiredRuntimeProfile",
            JSONObject()
                .put("qnnSdk", profile.qnnSdk)
                .put("htpArch", profile.htpArch)
                .put("completeBundleRuntime", profile.completeBundleRuntime)
        )
    }
    bundle.executionProfile?.let {
        val primaryFile = primary?.second?.takeIf(File::isFile)
            ?: error("Image execution profile requires a concrete downloaded primary model file.")

        // For a multi-file bundle the execution profile must bind to the complete
        // observed package.  The primary SHA is still useful for transfer
        // integrity, but it is not a sufficient profile identity because VAE,
        // tokenizer, text encoder, context and manifest changes can alter native
        // execution.  Build the identity against the final manifest contract with
        // a provisional fingerprint; captureLocalImageContentSnapshot removes the
        // self-referential modelFingerprint field from that contract.
        val provisionalFingerprint = "0".repeat(64)
        val provisionalProfile = requireNotNull(
            materializeDownloadedImageExecutionProfile(bundle, provisionalFingerprint)
        ).resolveDownloadedExecutionGraphPaths(targets).let { profile ->
            bundleRoot?.let { root ->
                profile.withDownloadedTextualInversionConsumerPins(
                    bundleRoot = root,
                    primaryModel = primaryFile
                )
            } ?: profile
        }
        manifest.put("executionProfile", ImageExecutionProfileJson.toJson(provisionalProfile))
        val fingerprint = if (bundleRoot != null) {
            val observedRecord = LocalImageModelRecord(
                displayName = displayName,
                path = primaryFile.absolutePath,
                fileName = primaryFile.name,
                sizeBytes = primaryFile.length(),
                sha256 = primarySha256.orEmpty(),
                runtime = bundle.runtime.toLocalImageRuntime(),
                family = LocalImageModelFamily.from(family),
                imageSize = "${bundle.smokeSpec.width}x${bundle.smokeSpec.height}",
                source = "download",
                bundleRoot = bundleRoot.canonicalPath,
                recommendationId = bundle.recommendationId
            )
            captureLocalImageContentSnapshot(
                observedRecord,
                manifestOverride = manifest
            ).fingerprint
        } else {
            primarySha256
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                ?: primaryFile.sha256ForProfile()
        }
        val resolvedProfile = requireNotNull(
            materializeDownloadedImageExecutionProfile(bundle, fingerprint)
        ).resolveDownloadedExecutionGraphPaths(targets)
        val profile = bundleRoot?.let { root ->
            resolvedProfile.withDownloadedTextualInversionConsumerPins(
                bundleRoot = root,
                primaryModel = primaryFile
            )
        } ?: resolvedProfile
        manifest.put("executionProfile", ImageExecutionProfileJson.toJson(profile))
    }
    if (bundle.qnnSmokeSpecs.isNotEmpty()) {
        manifest.put(
            "smokes",
            JSONArray(
                bundle.qnnSmokeSpecs.map { smoke ->
                    JSONObject()
                        .put("width", smoke.width)
                        .put("height", smoke.height)
                        .put("steps", smoke.steps)
                        .put("timeoutSeconds", smoke.timeoutSeconds)
                        .put("prompt", smoke.prompt)
                        .put("graphName", smoke.graphName)
                        .put(
                            "contextBinary",
                            resolvedDownloadedQnnContextPath(smoke.contextBinary, targets)
                        )
                        .also { json ->
                            smoke.expectedContextSizeBytes?.let { size ->
                                json.put("expectedContextSizeBytes", size)
                            }
                            smoke.expectedContextSha256?.let { sha256 ->
                                json.put("expectedContextSha256", sha256)
                            }
                        }
                        .put(
                            "inputs",
                            JSONArray(
                                smoke.inputs.map { tensor ->
                                    JSONObject()
                                        .put("name", tensor.name)
                                        .put("role", tensor.role)
                                        .put("dataType", tensor.dataType)
                                        .put("shape", JSONArray(tensor.shape))
                                        .put("fill", tensor.fill)
                                }
                            )
                        )
                        .put(
                            "outputs",
                            JSONArray(
                                smoke.outputs.map { tensor ->
                                    JSONObject()
                                        .put("name", tensor.name)
                                        .put("role", tensor.role)
                                        .put("dataType", tensor.dataType)
                                        .put("shape", JSONArray(tensor.shape))
                                        .put("fill", tensor.fill)
                                }
                            )
                        )
                }
            )
        )
    }
    return manifest
}

/**
 * Public QNN ZIPs often wrap graph binaries in a publisher-specific directory.
 * Persist the extracted relative path instead of a root-only catalog basename;
 * native workers then resolve the exact immutable artifact recorded in the
 * component manifest. Ambiguous filenames deliberately remain unresolved so
 * the structural/native smoke reports a concrete package error.
 */
internal fun resolvedDownloadedQnnContextPath(
    requested: String,
    targets: List<Pair<RemoteModelFile, File>>
): String {
    val normalized = requested.replace('\\', '/').trim().trimStart('/')
    val paths = targets.map { it.first.relativePath.replace('\\', '/').trim().trimStart('/') }
    paths.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
    val requestedName = normalized.substringAfterLast('/')
    val matchingNames = paths.filter {
        it.substringAfterLast('/').equals(requestedName, ignoreCase = true)
    }.distinct()
    return matchingNames.singleOrNull() ?: normalized
}

private fun ImageExecutionProfile.resolveDownloadedExecutionGraphPaths(
    targets: List<Pair<RemoteModelFile, File>>
): ImageExecutionProfile {
    fun ImageGraphArtifactContract?.resolved(): ImageGraphArtifactContract? = this?.copy(
        relativePath = resolvedDownloadedQnnContextPath(relativePath, targets)
    )
    fun String?.resolvedSidecar(): String? = this?.let { requested ->
        resolvedDownloadedQnnContextPath(requested, targets)
    }
    return copy(
        graph = graph.copy(
            textEncoder = graph.textEncoder.resolved(),
            unet = graph.unet.resolved(),
            vae = graph.vae.resolved(),
            vaeEncoder = graph.vaeEncoder.resolved(),
            controlNet = graph.controlNet.resolved(),
            schedulerSidecar = graph.schedulerSidecar.resolvedSidecar(),
            tokenizerSidecar = graph.tokenizerSidecar.resolvedSidecar(),
            configSidecars = graph.configSidecars.map { path ->
                resolvedDownloadedQnnContextPath(path, targets)
            }
        )
    )
}

internal fun expandedImageBundleManifestTargets(
    bundleDir: File,
    resolvedPrimary: File,
    targets: List<Pair<RemoteModelFile, File>>
): List<Pair<RemoteModelFile, File>> {
    val root = bundleDir.canonicalFile
    val primaryFile = resolvedPrimary.canonicalFile
    val archiveTarget = targets.firstOrNull { (remote, file) ->
        remote.bundleRole == ImageEngineBundleComponentRole.DIFFUSION &&
            file.extension.equals("zip", ignoreCase = true)
    }
    if (archiveTarget == null) return targets
    val archiveRemote = archiveTarget.first
    val existingTargetsByPath = targets
        .filterNot { it === archiveTarget }
        .mapNotNull { (remote, file) ->
            runCatching { file.canonicalFile }.getOrNull()
                ?.takeIf(File::isFile)
                ?.let { it.path to remote }
        }
        .toMap()
    return root.walkTopDown()
        .filter { file -> file.isFile && file.length() > 0L }
        .filterNot { it.name == "manifest.json" || it.name.endsWith(".part") }
        .map { file ->
            val canonical = file.canonicalFile
            val relative = canonical.relativeTo(root).invariantSeparatorsPath
            val existingRemote = existingTargetsByPath[canonical.path]
            val remote = when {
                existingRemote != null -> existingRemote.copy(relativePath = relative)
                canonical == primaryFile -> archiveRemote.copy(
                    path = "${archiveRemote.path}!/$relative",
                    name = canonical.name,
                    bundleRole = ImageEngineBundleComponentRole.DIFFUSION,
                    relativePath = relative
                )
                else -> archiveRemote.copy(
                    path = "${archiveRemote.path}!/$relative",
                    name = canonical.name,
                    sizeBytes = canonical.length(),
                    sha256 = null,
                    bundleRole = ImageEngineBundleComponentRole.CONFIG,
                    relativePath = relative
                )
            }
            remote to canonical
        }
        .toList()
}

internal fun writeDownloadedImageBundleManifest(
    displayName: String,
    bundleDir: File,
    bundle: ImageEngineBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>,
    primarySha256: String
) {
    bundleDir.mkdirs()
    File(bundleDir, "manifest.json").writeText(
        downloadedImageBundleManifestJson(
            displayName = displayName,
            bundle = bundle,
            targets = targets,
            primarySha256 = primarySha256,
            bundleRoot = bundleDir
        ).toString(2),
        Charsets.UTF_8
    )
}

internal fun preparePinnedQnnRuntimeMetadataIfRequired(
    bundleDir: File,
    bundle: ImageEngineBundleSpec
) {
    val profile = bundle.requiredRuntimeProfile
        ?.takeIf { it.completeBundleRuntime }
        ?: return
    val archive = bundle.components.singleOrNull { component ->
        component.role == ImageEngineBundleComponentRole.DIFFUSION &&
            component.fileName.endsWith(".zip", ignoreCase = true)
    } ?: error("QNN runtime contract requires one pinned archive component.")
    val expectedArchiveBytes = archive.expectedSizeBytes
    require(expectedArchiveBytes != null && expectedArchiveBytes > 0L) {
        "QNN runtime archive is missing a pinned byte size."
    }
    val archiveSha256 = archive.sha256?.trim()?.lowercase().orEmpty()
    require(Regex("^[0-9a-f]{64}$").matches(archiveSha256)) {
        "QNN runtime archive is missing a pinned SHA-256."
    }
    writePinnedQnnRuntimeMetadata(
        bundleRoot = bundleDir,
        qnnSdk = profile.qnnSdk,
        contextHtpArch = profile.htpArch,
        sourceArchiveSha256 = archiveSha256
    )
}

/** The selected device transport is staged later; this is the package's declared context target. */
internal fun resolveInstalledQnnRuntimeProfile(
    bundleDir: File,
    bundle: ImageEngineBundleSpec,
    preferredHtpArch: Int?
): ImageEngineBundleSpec {
    // Keep this adapter for older installer callers; runtime selection happens
    // in stageQnnImageRuntime with distinct context and transport profiles.
    @Suppress("UNUSED_VARIABLE")
    val transportHint = preferredHtpArch?.let { qnnImageBundleRuntimeProfileForArchOrNull(bundleDir, it) }
    return bundle
}

internal fun downloadedVisionBundleManifestJson(
    displayName: String,
    bundle: VisionModelBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>
): JSONObject {
    val components = JSONArray()
    targets.forEach { (remote, file) ->
        val role = remote.visionBundleRole ?: VisionModelBundleComponentRole.OPTIONAL
        components.put(
            JSONObject()
                .put("role", role.name)
                .put("path", file.relativePathFromCommonRoot(targets))
                .put("fileName", file.name)
                .put("sourceRepo", remote.repoId)
                .put("sourcePath", remote.path)
                .put("provider", remote.provider.name)
                .put("required", role != VisionModelBundleComponentRole.OPTIONAL)
        )
    }
    return JSONObject()
        .put("schema", "mca.vision_engine.bundle.v1")
        .put("id", bundle.id)
        .put("title", displayName)
        .put("runtime", bundle.runtime.name)
        .put("accelerator", bundle.accelerator.name)
        .put("minDeviceTier", bundle.minDeviceTier.name)
        .put("requiresQnnRuntime", bundle.requiresQnnRuntime)
        .put("requiresSmokeTest", bundle.requiresSmokeTest)
        .put(
            "smoke",
            JSONObject()
                .put("imageWidth", bundle.smokeSpec.imageWidth)
                .put("imageHeight", bundle.smokeSpec.imageHeight)
                .put("prompt", bundle.smokeSpec.prompt)
                .put("timeoutSeconds", bundle.smokeSpec.timeoutSeconds)
        )
        .put("npuActive", false)
        .put("components", components)
}

internal fun writeDownloadedVisionBundleManifest(
    displayName: String,
    bundleDir: File,
    bundle: VisionModelBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>
) {
    bundleDir.mkdirs()
    File(bundleDir, "manifest.json").writeText(
        downloadedVisionBundleManifestJson(
            displayName = displayName,
            bundle = bundle,
            targets = targets
        ).toString(2),
        Charsets.UTF_8
    )
}

private fun File.relativePathFromCommonRoot(targets: List<Pair<RemoteModelFile, File>>): String {
    val root = targets.firstOrNull()?.second?.parentFile ?: parentFile
    return if (root != null) {
        runCatching { relativeTo(root).invariantSeparatorsPath }.getOrDefault(name)
    } else {
        name
    }
}

internal fun ModelRepositoryProvider.toModelSource(): ModelSource =
    when (this) {
        ModelRepositoryProvider.MODELSCOPE -> ModelSource.MODELSCOPE
        ModelRepositoryProvider.HUGGING_FACE -> ModelSource.HUGGING_FACE
    }

internal fun ChatModelRuntime.toLocalChatRuntime(): LocalChatRuntime =
    when (this) {
        ChatModelRuntime.MNN -> LocalChatRuntime.MNN_CPU
        ChatModelRuntime.LLAMA_CPP -> LocalChatRuntime.LLAMA_CPP
        ChatModelRuntime.GENIEX_QAIRT -> LocalChatRuntime.GENIEX_QAIRT
        ChatModelRuntime.LITERT_LM -> LocalChatRuntime.LITERT_LM
    }

internal fun ImageEngineBundleRuntime.toLocalImageRuntime(): LocalImageRuntime =
    when (this) {
        ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP -> LocalImageRuntime.STABLE_DIFFUSION_CPP
        ImageEngineBundleRuntime.MNN_DIFFUSION -> LocalImageRuntime.MNN_DIFFUSION
        ImageEngineBundleRuntime.QNN_HTP -> LocalImageRuntime.QNN_HTP
    }

internal fun String.toFilePreview(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(4)
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .take(180)
        .ifBlank { "无可预览文本" }

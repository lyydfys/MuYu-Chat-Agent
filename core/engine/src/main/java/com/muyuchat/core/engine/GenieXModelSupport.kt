package com.muyuchat.core.engine

import com.geniex.sdk.bean.ModelType
import java.io.File

internal data class PreparedGenieXModel(
    val modelName: String,
    val modelPath: String,
    val tokenizerPath: String?,
    val runtimeId: String,
    val modelType: ModelType,
    val mmprojPath: String? = null
)

internal fun detectGenieXModelType(
    input: File,
    explicitMmprojPath: String? = null
): ModelType {
    if (!explicitMmprojPath.isNullOrBlank()) return ModelType.VLM
    val signalText = genieXModelSignalText(input)
    return if (hasGenieXVisionSignal(input, signalText)) ModelType.VLM else ModelType.LLM
}

/**
 * Picks an automatically discovered GGUF projector for the selected model.
 *
 * A bundle directory can contain more than one model (and more than one
 * mmproj).  Choosing the largest projector is unsafe: projector size is not
 * an identity and can bind model A to model B.  Prefer a normalized stem
 * match (Qwen2-VL-Q4_K_M with mmproj-Qwen2-VL-f16, for example), then a
 * partial match. A generic same-directory projector is accepted only when it
 * is the sole candidate, so directory proximity cannot choose among unrelated
 * vision encoders.
 * An explicitly persisted projector still bypasses this helper entirely.
 */
internal fun chooseGenieXVisionProjector(model: File, candidates: List<File>): File? {
    if (candidates.isEmpty()) return null

    fun normalizedStem(fileName: String): String {
        var value = fileName.substringBeforeLast('.', fileName).lowercase()
            .replace('_', '-')
            .replace(Regex("^(mmproj|projector)-"), "")
            .replace(Regex("-(f16|f32|bf16)$"), "")
            .replace(Regex("-(q[0-9].*)$"), "")
            .replace(Regex("-+"), "-")
            .trim('-')
        return value
    }

    val modelStem = normalizedStem(model.name)
    fun score(candidate: File): Int {
        val candidateStem = normalizedStem(candidate.name)
        return when {
            candidateStem.isNotBlank() && modelStem.isNotBlank() && candidateStem == modelStem -> 400
            candidateStem.isNotBlank() && modelStem.isNotBlank() &&
                (candidateStem.contains(modelStem) || modelStem.contains(candidateStem)) -> 300
            modelStem.isNotBlank() && candidate.nameWithoutExtension.lowercase().contains(modelStem) -> 200
            else -> 0
        }
    }
    val usableCandidates = candidates
        .asSequence()
        .filter { it.isFile && it.length() > 0L && it.canRead() }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        .toList()
    val ranked = usableCandidates
        .map { candidate -> candidate to score(candidate) }
        .maxWithOrNull(
            compareBy<Pair<File, Int>> { it.second }
                .thenBy { it.first.parentFile?.canonicalFile == model.parentFile?.canonicalFile }
                .thenBy { it.first.length() }
        )
    // A stem match is a strong identity signal. When exporters use a generic
    // projector name, accept a colocated fallback only if it is the sole
    // candidate; choosing the largest of multiple unidentifiable projectors
    // can silently bind the wrong model. An explicitly persisted projector
    // still bypasses discovery and is validated by the loader.
    ranked?.takeIf { it.second >= 200 }?.let { return it.first }
    val modelParent = runCatching { model.parentFile?.canonicalFile }.getOrNull()
    val colocated = usableCandidates.filter { candidate ->
        modelParent != null && runCatching { candidate.parentFile?.canonicalFile == modelParent }
            .getOrDefault(false)
    }
    return colocated.singleOrNull()
}

/**
 * Computes the public vision readiness state from the same binding that is
 * passed to the native create call.  A GGUF text model with a stale/missing
 * mmproj must never be advertised as ready merely because its model name looks
 * like a VLM.  QAIRT bundles carry the visual component in the bundle itself,
 * so they do not require a separate projector path.
 */
internal fun genieXVisionReady(
    runtime: LocalChatRuntime,
    loaded: Boolean,
    modelType: ModelType,
    mmprojPath: String?,
    supportsVision: Boolean?
): Boolean {
    // A VLM label and a bound projector are only package metadata. They do not
    // prove that the native engine actually created a vision-capable runner.
    // In particular, getCapabilities() may fail on worker/runtime mismatches;
    // never publish multimodal readiness when that check is unavailable.
    if (!loaded || modelType != ModelType.VLM || supportsVision != true) return false
    if (runtime == LocalChatRuntime.GENIEX_LLAMA_CPP) {
        val projectorPath = mmprojPath?.trim().orEmpty()
        // A stale path in the model registry must never be advertised as a
        // ready visual runner. The native VLM create call may otherwise fail
        // late (or dereference a missing projector on older GenieX builds).
        if (projectorPath.isBlank()) return false
        val projector = runCatching { File(projectorPath).canonicalFile }.getOrNull()
            ?: return false
        if (!projector.isFile || projector.length() <= 0L || !projector.canRead()) return false
    }
    return true
}

/** Stable, user-facing reason for a false visual readiness bit. */
internal fun genieXVisionFailureReason(
    runtime: LocalChatRuntime,
    loaded: Boolean,
    modelType: ModelType,
    mmprojPath: String?,
    supportsVision: Boolean?
): String? {
    if (!loaded) return "model_not_loaded"
    if (runtime == LocalChatRuntime.LITERT_LM) return "text_only_runtime"
    if (modelType != ModelType.VLM) return "model_type_is_text_only"
    if (supportsVision == false) return "native_capability_reports_no_vision"
    if (supportsVision == null) return "native_capability_unavailable"
    if (runtime == LocalChatRuntime.GENIEX_LLAMA_CPP) {
        val path = mmprojPath?.trim().orEmpty()
        if (path.isBlank()) return "mmproj_not_bound"
        val projector = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return "mmproj_path_invalid"
        if (!projector.isFile) return "mmproj_file_missing"
        if (projector.length() <= 0L) return "mmproj_file_empty"
        if (!projector.canRead()) return "mmproj_file_unreadable"
    }
    return null
}

internal fun destroyGenieXHandle(engine: Any, nativeHandle: Long): Int {
    val destroy = engine.javaClass
        .getDeclaredMethod("destroy", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }
    return destroy.invoke(engine, nativeHandle) as? Int
        ?: error("GenieX destroy returned no result.")
}

private fun genieXModelSignalText(input: File): String {
    val metadata = input.takeIf(File::isDirectory)
        ?.resolve("metadata.json")
        ?.takeIf(File::isFile)
        ?.let { runCatching { it.readText() }.getOrNull() }
        .orEmpty()
    return listOf(input.name, input.parentFile?.name.orEmpty(), metadata)
        .joinToString(" ")
        .lowercase()
}

internal fun resolveQairtBundleRootForLoad(bundleDir: File): File? {
    val root = runCatching { bundleDir.canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    if (root.hasQairtLoadRootMarker()) return root
    val entries = root.listFiles()?.toList() ?: return null
    if (entries.size != 1) return null
    val child = runCatching { entries.single().canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    if (child.parentFile?.canonicalFile?.path != root.path || !child.hasQairtLoadRootMarker()) return null
    return child
}

private fun File.hasQairtLoadRootMarker(): Boolean = listOf("metadata.json", "genie_config.json")
    .any { name ->
        val candidate = File(this, name)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
        canonical?.isFile == true && canonical.parentFile?.canonicalFile?.path == path
    }

private fun hasGenieXVisionSignal(input: File, signalText: String): Boolean {
    val namedSignal = listOf(
        "multimodal",
        "vision-language",
        "vision_language",
        "vision language",
        "qwen3-vl",
        "qwen3_vl",
        "qwen2.5-vl",
        "qwen2_5_vl",
        "minicpm-v",
        "minicpm_v",
        "fastvlm"
    ).any(signalText::contains)
    val shortSignal = Regex("(^|[^a-z0-9])vlm?([^a-z0-9]|$)").containsMatchIn(signalText)
    val visionFile = input.takeIf(File::isDirectory)
        ?.walkTopDown()
        ?.maxDepth(3)
        ?.any { candidate ->
            if (!candidate.isFile) return@any false
            val name = candidate.name.lowercase()
            name.contains("vision_encoder") ||
                name.contains("visual_encoder") ||
                name.contains("mmproj") ||
                name.contains("projector")
        } == true
    return namedSignal || shortSignal || visionFile
}

internal fun requireSuccessfulGenieXSdkInit(reportedFailure: String?) {
    val failure = reportedFailure?.trim().orEmpty()
    if (failure.isNotEmpty()) throw IllegalStateException(failure)
}

/**
 * GenieX exposes a process-wide SDK singleton.  Keep its one-time init state
 * outside individual runner instances so switching between the llama.cpp and
 * QAIRT runners cannot register the same native plugin more than once.
 */
internal object GenieXProcessInitState {
    val lock = Any()
    var sdkInitAttempted = false
    var sdkInitError: String? = null
    var modelManagerInitAttempted = false
    var modelManagerInitError: String? = null
}

internal val GENIEX_PROCESS_INIT = GenieXProcessInitState

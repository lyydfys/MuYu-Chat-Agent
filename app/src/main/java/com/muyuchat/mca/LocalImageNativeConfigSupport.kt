package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native-config support helpers for the local image provider (P5/L6). Moved verbatim out of
 * class LocalImageProvider as top-level functions: progress parsing for the SD/MNN bridges, the
 * default thread count, QNN runtime-directory JSON, and the QNN semantic-defaults /
 * text-encoder-evidence JSON builders. Only instance dependency was appContext, now an explicit
 * qnnRuntimeDirsJson(context, ...) parameter; the two call sites pass the provider's appContext.
 */

internal fun NativeStableDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
    runCatching {
        localImageProgressFromJson(JSONObject(getProgress()))
    }.getOrNull()

internal fun NativeMnnDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
    runCatching {
        localImageProgressFromJson(JSONObject(getProgress()))
    }.getOrNull()

internal fun localImageProgressFromJson(json: JSONObject): LocalImageProgress =
    LocalImageProgress(
        phase = json.optString("phase"),
        message = json.optString("message"),
        step = json.optInt("step"),
        steps = json.optInt("steps"),
        elapsedMs = json.optLong("elapsedMs"),
        secondsPerStep = json.optDouble("secondsPerStep"),
        threads = json.optInt("threads"),
        width = json.optInt("width"),
        height = json.optInt("height"),
        cancelRequested = json.optBoolean("cancelRequested"),
        componentSelectionJson = json.optJSONObject("componentSelection")?.toString().orEmpty(),
        previewPath = json.optString("previewPath"),
        previewMimeType = json.optString("previewMimeType"),
        previewMode = json.optString("previewMode"),
        previewStep = json.optInt("previewStep"),
        previewRevision = json.optLong("previewRevision"),
        previewWidth = json.optInt("previewWidth"),
        previewHeight = json.optInt("previewHeight"),
        previewFrameCount = json.optInt("previewFrameCount"),
        previewNoisy = json.optBoolean("previewNoisy"),
        previewVaeExecutionAttemptCount = json.optInt("previewVaeExecutionAttemptCount"),
        previewVaeExecutionCount = json.optInt("previewVaeExecutionCount"),
        previewVaeExecutionMsTotal = json.optLong("previewVaeExecutionMsTotal"),
        previewPublicationCount = json.optInt("previewPublicationCount"),
        previewLastStep = json.optInt("previewLastStep"),
        previewLastRevision = json.optLong("previewLastRevision"),
        previewFailureCode = json.optString("previewFailureCode"),
        stageTrace = json.optJSONArray("stageTrace")?.let { trace ->
            buildList {
                for (index in 0 until trace.length()) {
                    trace.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.orEmpty()
    )

internal fun defaultLocalImageThreads(): Int {
    val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return when {
        available >= 10 -> 5
        available >= 8 -> 4
        available >= 6 -> 4
        available >= 4 -> 3
        else -> 2
    }.coerceAtMost(available).coerceAtLeast(1)
}

internal fun qnnRuntimeDirsJson(context: Context, bundleRoot: File): String {
    return JSONArray(qnnRuntimeDirectoriesFor(context, bundleRoot)).toString()
}

internal fun JSONObject.putQnnSemanticDefaults(
    bundleRoot: File,
    profile: ImageExecutionProfile,
    includeVaeEncoderEvidence: Boolean
): JSONObject {
    val manifest = localImageBundleManifestFromRoot(bundleRoot)
    val smokes = manifest?.qnnSmokeSpecs.orEmpty()
    val unet = smokes.firstOrNull { spec ->
        val lower = spec.contextBinary.lowercase()
        "unet" in lower || "diffusion" in lower || spec.inputs.size >= 3
    } ?: smokes.firstOrNull()
    val vae = smokes.firstOrNull { spec ->
        val lower = spec.contextBinary.lowercase()
        "vae" in lower || "decoder" in lower || (spec.inputs.size == 1 && spec.outputs.any { it.shape.contains(3) })
    }
    val manifestBound = profile.provenance.primarySource == ImageProfileSource.MANIFEST
    fun resolveProfileArtifact(
        artifact: ImageGraphArtifactContract?,
        required: Boolean,
        vararg legacyNames: String
    ): String? {
        artifact?.relativePath?.trim()?.takeIf(String::isNotEmpty)?.let { relativePath ->
            require(!File(relativePath).isAbsolute && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(relativePath)) {
                "Image execution graph path must stay inside the installed bundle."
            }
            val root = bundleRoot.canonicalFile
            val candidate = File(root, relativePath).canonicalFile
            require(candidate.path.startsWith(root.path + File.separator)) {
                "Image execution graph path escapes the installed bundle."
            }
            if (candidate.isFile) return candidate.relativeTo(root).invariantSeparatorsPath
            if (manifestBound && required) {
                error("Installed image execution profile is missing required graph: $relativePath")
            }
        }
        return qnnFirstContextPath(bundleRoot, *legacyNames)
    }
    val profileUnet = resolveProfileArtifact(profile.graph.unet, true, "unet.bin")
    val profileVae = resolveProfileArtifact(
        profile.graph.vae,
        true,
        "vae.bin",
        "vae_decoder.bin"
    )
    val textEncoderContext = resolveProfileArtifact(
        profile.graph.textEncoder?.takeIf { it.relativePath.endsWith(".bin", ignoreCase = true) },
        false,
        "text_encoder.bin"
    )
    val vaeEncoderContext = if (includeVaeEncoderEvidence) {
        requireNotNull(
            resolveProfileArtifact(
                profile.graph.vaeEncoder,
                true,
                "vae_encoder.bin"
            )
        ) { "Resolved img2img profile is missing its required VAE encoder context." }
    } else {
        null
    }
    val controlNetContext = resolveProfileArtifact(
        profile.graph.controlNet,
        profile.task == ImageTask.CONTROL_IMAGE,
        "controlnet.bin"
    )
    put(
        "unetContextBinary",
        profileUnet
            ?: unet?.contextBinary?.takeIf { it.isNotBlank() }
            ?: "unet.bin"
    )
    put(
        "vaeDecoderContextBinary",
        profileVae
            ?: vae?.contextBinary?.takeIf { it.isNotBlank() }
            ?: "vae_decoder.bin"
    )
    textEncoderContext?.let { put("textEncoderContextBinary", it) }
    vaeEncoderContext?.let { relativePath ->
        val root = bundleRoot.canonicalFile
        val encoderContext = File(root, relativePath).canonicalFile
        require(
            encoderContext.path.startsWith(root.path + File.separator) &&
                encoderContext.isFile && encoderContext.length() > 0L
        ) { "Resolved VAE encoder context is missing or escapes the installed bundle." }
        put("vaeEncoderContextBinary", relativePath)
        put("vaeEncoderContextSha256", encoderContext.sha256Contents())
        val normalizedEncoderPath = relativePath.replace('\\', '/').trimStart('/')
        val smokeGraphName = smokes.firstOrNull { spec ->
            spec.contextBinary.replace('\\', '/').trimStart('/')
                .equals(normalizedEncoderPath, ignoreCase = true)
        }?.graphName?.trim()?.takeIf(String::isNotEmpty)
        put(
            "vaeEncoderGraphName",
            profile.graph.vaeEncoder?.graphName?.trim()?.takeIf(String::isNotEmpty)
                ?: smokeGraphName
                ?: "model"
        )
    }
    controlNetContext?.let { put("controlNetContextBinary", it) }
    put("graphName", profile.graph.unet?.graphName?.takeIf { it.isNotBlank() }
        ?: unet?.graphName?.takeIf { it.isNotBlank() }
        ?: "model")
    profile.graph.textEncoder?.graphName?.takeIf { it.isNotBlank() }
        ?.let { put("textEncoderGraphName", it) }
    profile.graph.controlNet?.graphName?.takeIf { it.isNotBlank() }
        ?.let { put("controlNetGraphName", it) }
    return this
}

/**
 * Enables the QNN bridge's strict text-encoder path only for a profile that has already
 * passed the generic topology and evidence checks. The bridge receives bundle-relative paths
 * exclusively and re-hashes the file before mmap, after mmap, and after graph execution.
 */
internal fun JSONObject.putQnnNativeMultilingualTextEncoderEvidence(
    bundleRoot: File,
    profile: ImageExecutionProfile
): JSONObject {
    if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return this
    require(profile.runtime == LocalImageRuntime.QNN_HTP &&
        profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
    ) {
        "Direct Chinese QNN prompts require a shared native text-encoder graph."
    }
    val evidence = requireNotNull(profile.textEncoderLanguage?.evidence) {
        "Direct Chinese QNN prompts require a pinned text-encoder evidence asset."
    }
    val tokenizerEvidence = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Direct Chinese QNN prompts require one pinned tokenizer JSON evidence asset."
    )
    val expectedGraphName = requireNotNull(profile.graph.textEncoder)
        .graphName
        .trim()
        .takeIf(String::isNotEmpty)
        ?: error("Direct Chinese QNN prompts require an explicit text-encoder graph name.")
    val expectedRelativePath = evidence.textEncoderAsset.relativePath
        .replace('\\', '/')
        .trim()
    val expectedSizeBytes = requireNotNull(evidence.textEncoderAsset.sizeBytes) {
        "Direct Chinese QNN prompts require a size-pinned text-encoder evidence asset."
    }
    val requestedRelativePath = optString("textEncoderContextBinary")
        .replace('\\', '/')
        .trim()
    require(requestedRelativePath == expectedRelativePath) {
        "QNN semantic defaults selected a text encoder different from the language evidence asset."
    }
    val root = bundleRoot.canonicalFile
    val encoder = File(root, requestedRelativePath).canonicalFile
    require(encoder.path.startsWith(root.path + File.separator) &&
        encoder.isFile &&
        encoder.length() == expectedSizeBytes
    ) {
        "Pinned QNN text-encoder evidence asset is unavailable or has an unexpected size."
    }
    val actualSha256 = encoder.sha256Contents()
    require(actualSha256.equals(evidence.textEncoderAsset.fingerprint, ignoreCase = true)) {
        "Pinned QNN text-encoder evidence asset SHA-256 differs from the resolved profile."
    }
    val tokenizer = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    val expectedTokenizerSizeBytes = requireNotNull(tokenizerEvidence.sizeBytes) {
        "Direct Chinese QNN prompts require a size-pinned tokenizer JSON evidence asset."
    }
    val actualTokenizerSha256 = tokenizer.sha256Contents()
    require(actualTokenizerSha256.equals(tokenizerEvidence.fingerprint, ignoreCase = true) &&
        tokenizer.length() == expectedTokenizerSizeBytes
    ) {
        "Pinned QNN tokenizer JSON evidence asset differs from the resolved profile."
    }
    val tokenizerRelativePath = tokenizer.relativeTo(root).invariantSeparatorsPath
    require(tokenizerRelativePath == tokenizerEvidence.relativePath.replace('\\', '/').trim()) {
        "Pinned QNN tokenizer JSON evidence path differs from the resolved profile."
    }
    val proofSha256 = requireNotNull(
        profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
    ) { "Direct Chinese QNN prompts require a verified signed semantic proof." }
    require(optString("textEncoderGraphName").trim() == expectedGraphName) {
        "QNN semantic defaults selected a text-encoder graph different from the signed language proof."
    }
    return put("textEncoderPath", requestedRelativePath)
        .put("textEncoderSha256", actualSha256.lowercase())
        .put("textEncoderSizeBytes", encoder.length())
        .put("textEncoderGraphName", expectedGraphName)
        .put("tokenizerJsonPath", tokenizerRelativePath)
        .put("tokenizerJsonSha256", actualTokenizerSha256.lowercase())
        .put("tokenizerJsonSizeBytes", tokenizer.length())
        .put("promptToEncoderClosureSha256", evidence.promptToEncoderClosureSha256())
        .put("languageProofSha256", proofSha256)
}

/**
 * The token-ID conditioner is implemented by the MNN bridge even when the text encoder runs on
 * QNN. The serializable fields below are checked only as diagnostics; authorization comes from
 * the one-time native handoff that QNN consumes against the observed artifact and closure.
 */
internal data class QnnNativeMultilingualPromptHandoff(
    val opaqueHandle: String
)

internal fun verifyQnnNativeMultilingualPromptHandoff(
    bundleRoot: File,
    evidence: ImageTextEncoderLanguageEvidence,
    encoded: JSONObject
): QnnNativeMultilingualPromptHandoff {
    val tokenizerAsset = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Verified native-multilingual QNN execution requires exactly one TOKENIZER_JSON asset."
    )
    val expectedFile = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    val expectedPath = expectedFile.canonicalPath
    val expectedSha256 = tokenizerAsset.fingerprint.lowercase()
    val expectedSizeBytes = requireNotNull(tokenizerAsset.sizeBytes) {
        "Verified native-multilingual QNN execution requires a size-pinned tokenizer JSON asset."
    }
    require(expectedFile.length() == expectedSizeBytes) {
        "Pinned native-multilingual tokenizer asset size changed before native conditioning."
    }
    require(expectedFile.sha256Contents().equals(expectedSha256, ignoreCase = true)) {
        "Pinned native-multilingual tokenizer asset digest changed before native conditioning."
    }

    val actualPath = (encoded.opt("tokenizerAssetPath") as? String)
        ?.takeIf(String::isNotBlank)
        ?: error("Native tokenizer receipt is missing tokenizerAssetPath.")
    val actualSha256 = (encoded.opt("tokenizerAssetSha256") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing tokenizerAssetSha256.")
    val actualSizeValue = encoded.opt("tokenizerAssetSizeBytes")
    val actualSizeBytes = actualSizeValue
        .takeIf { value ->
            value is Byte || value is Short || value is Int || value is Long
        }
        ?.let { value -> (value as Number).toLong() }
        ?: error("Native tokenizer receipt is missing an exact tokenizerAssetSizeBytes value.")
    val bindingStage = (encoded.opt("tokenizerAssetBindingStage") as? String)
        ?: error("Native tokenizer receipt is missing tokenizerAssetBindingStage.")
    val receiptFile = runCatching { File(actualPath).canonicalFile }.getOrNull()
        ?: error("Native tokenizer receipt path is invalid.")
    require(
        receiptFile.path == expectedPath &&
            actualSha256 == expectedSha256 &&
            actualSizeBytes == expectedSizeBytes &&
            bindingStage == "tokenizer_consumed" &&
            Regex("^[0-9a-f]{64}$").matches(actualSha256)
    ) {
        "Native tokenizer receipt differs from the signed QNN prompt-to-encoder closure."
    }
    val opaqueHandle = (encoded.opt("mnnPromptHandoff") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing its one-time MNN-to-QNN handoff.")
    require(Regex("^[0-9a-f]{64}$").matches(opaqueHandle)) {
        "Native tokenizer handoff must be a 256-bit lowercase opaque handle."
    }
    return QnnNativeMultilingualPromptHandoff(opaqueHandle = opaqueHandle)
}

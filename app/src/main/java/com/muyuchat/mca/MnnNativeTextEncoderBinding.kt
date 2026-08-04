package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

private const val MNN_CONSUMED_TEXT_ENCODER_CLOSURE_PREFIX =
    "mca.mnn.consumed-text-encoder-assets.v1"
private const val MNN_CONSUMED_TEXT_ENCODER_BINDING_STAGE = "opened_descriptor"
private val MNN_NATIVE_TEXT_ENCODER_SHA256 = Regex("[0-9a-f]{64}")
private val MNN_WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:")

/**
 * Exact MNN text-encoder assets declared before native execution and proved after descriptor use.
 *
 * The graph is always present. MNN's optional external tensor sidecar is represented only when a
 * regular, in-bundle file exists at the graph's exact native `<graph>.weight` path.
 */
internal data class MnnNativeTextEncoderBinding(
    val graph: TextualInversionExecutionAssetDescriptor,
    val weight: TextualInversionExecutionAssetDescriptor? = null
) {
    init {
        require(graph.sizeBytes > 0L && MNN_NATIVE_TEXT_ENCODER_SHA256.matches(graph.sha256)) {
            "MNN text-encoder graph descriptor is invalid."
        }
        weight?.let { auxiliary ->
            require(auxiliary.path == graph.path + ".weight") {
                "MNN text-encoder auxiliary asset must use the graph's exact .weight path."
            }
            require(auxiliary.sizeBytes > 0L && MNN_NATIVE_TEXT_ENCODER_SHA256.matches(auxiliary.sha256)) {
                "MNN text-encoder auxiliary descriptor is invalid."
            }
        }
    }

    fun toNativeJson(): JSONObject = JSONObject()
        .put("path", graph.path)
        .put("sha256", graph.sha256)
        .put("sizeBytes", graph.sizeBytes)
        .apply {
            weight?.let { auxiliary ->
                put(
                    "auxiliaryAssets",
                    JSONArray().put(
                        JSONObject()
                            .put("path", auxiliary.path)
                            .put("sha256", auxiliary.sha256)
                            .put("sizeBytes", auxiliary.sizeBytes)
                    )
                )
            }
        }

    /** Rejects a successful MNN result unless native consumed this exact descriptor-backed closure. */
    fun verifyNativeReceipt(nativeResult: JSONObject) {
        val expectedAssets = buildList {
            add(graph.toReceiptAsset())
            weight?.let { add(it.toReceiptAsset()) }
        }
        val expectedClosure = consumedTextEncoderClosureSha256()
        val outer = nativeResult.parseTextEncoderReceipt("outer")
        val nativeEffective = nativeResult.optJSONObject("nativeEffective")
            ?: error("MNN result is missing nativeEffective text-encoder evidence.")
        val inner = nativeEffective.parseTextEncoderReceipt("nativeEffective")

        require(outer == inner) {
            "MNN outer and nativeEffective text-encoder receipts conflict."
        }
        require(outer.assets == expectedAssets) {
            "MNN text-encoder receipt does not identify the descriptor-backed assets requested by Android."
        }
        require(outer.closureSha256 == expectedClosure) {
            "MNN text-encoder receipt does not bind the requested asset closure."
        }
        require(outer.bindingStage == MNN_CONSUMED_TEXT_ENCODER_BINDING_STAGE) {
            "MNN text-encoder receipt was not published after descriptor-backed consumption."
        }
    }

    internal fun consumedTextEncoderClosureSha256(): String {
        val payload = buildList {
            add(MNN_CONSUMED_TEXT_ENCODER_CLOSURE_PREFIX)
            add(graph.path)
            add(graph.sizeBytes.toString())
            add(graph.sha256)
            weight?.let { auxiliary ->
                add(auxiliary.path)
                add(auxiliary.sizeBytes.toString())
                add(auxiliary.sha256)
            }
        }.joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/**
 * Captures the MNN graph and its optional external tensor sidecar for a verified multilingual
 * profile. Other MNN profiles deliberately make no declaration and therefore require no receipt.
 */
internal fun captureMnnNativeTextEncoderBinding(
    bundleRoot: File,
    profile: ImageExecutionProfile
): MnnNativeTextEncoderBinding? {
    if (profile.runtime != LocalImageRuntime.MNN_DIFFUSION ||
        profile.textEncoderLanguageCapability() !=
            LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
    ) {
        return null
    }

    require(profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) {
        "MNN native multilingual profile lacks an evidence-bound text-encoder graph."
    }
    // This re-hashes the pinned graph immediately before the descriptor snapshot is sent native.
    verifyNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)

    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "MNN text-encoder bundle root is missing." }
    val languageEvidence = requireNotNull(profile.textEncoderLanguage?.evidence)
    val evidenceAsset = languageEvidence.textEncoderAsset
    val expectedRelativePath = evidenceAsset.relativePath.replace('\\', '/').trim()
    val graphCandidate = resolveMnnBundleAsset(root, expectedRelativePath, "graph")
    val graph = captureImageExecutionAssetDescriptor(root, graphCandidate)
    val expectedSize = requireNotNull(evidenceAsset.sizeBytes) {
        "MNN native multilingual text-encoder evidence has no size pin."
    }
    require(
        graph.label == expectedRelativePath &&
            graph.path == graphCandidate.canonicalFile.path &&
            graph.sizeBytes == expectedSize &&
            graph.sha256 == evidenceAsset.fingerprint.lowercase()
    ) {
        "MNN text-encoder descriptor differs from its multilingual language-evidence pin."
    }

    return MnnNativeTextEncoderBinding(
        graph = graph,
        weight = captureMnnTextEncoderWeight(
            bundleRoot = root,
            graph = graph,
            declaredAuxiliaryAssets = languageEvidence.auxiliaryAssets
        )
    )
}

private fun captureMnnTextEncoderWeight(
    bundleRoot: File,
    graph: TextualInversionExecutionAssetDescriptor,
    declaredAuxiliaryAssets: List<ImageProfileAsset>
): TextualInversionExecutionAssetDescriptor? {
    val expectedRelativePath = graph.label + ".weight"
    require(declaredAuxiliaryAssets.size <= 1) {
        "MNN multilingual text-encoder evidence may declare at most one .weight sidecar."
    }
    val declared = declaredAuxiliaryAssets.singleOrNull()
    if (declared == null) {
        val undeclared = File(bundleRoot, expectedRelativePath)
        require(!Files.exists(undeclared.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "MNN text-encoder .weight sidecar exists but is absent from the language evidence closure."
        }
        return null
    }
    require(
        declared.relativePath.replace('\\', '/').trim().equals(
            expectedRelativePath,
            ignoreCase = true
        )
    ) {
        "MNN multilingual text-encoder evidence must pin the graph's exact .weight sidecar."
    }
    val candidate = resolveMnnBundleAsset(
        bundleRoot,
        declared.relativePath.replace('\\', '/').trim(),
        "auxiliary .weight"
    )
    val attributes = try {
        Files.readAttributes(
            candidate.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
    } catch (_: NoSuchFileException) {
        throw IllegalArgumentException(
            "MNN text-encoder auxiliary .weight asset is missing despite its catalog evidence pin."
        )
    } catch (error: IOException) {
        throw IllegalArgumentException("MNN text-encoder auxiliary .weight asset cannot be read.", error)
    }
    require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() > 0L) {
        "MNN text-encoder auxiliary .weight asset is not a non-empty regular file."
    }

    val descriptor = captureImageExecutionAssetDescriptor(bundleRoot, candidate)
    require(
        descriptor.label == expectedRelativePath &&
            descriptor.path == graph.path + ".weight" &&
            descriptor.sizeBytes == declared.sizeBytes &&
            descriptor.sha256.equals(declared.fingerprint, ignoreCase = true)
    ) {
        "MNN text-encoder auxiliary descriptor differs from the language evidence closure."
    }
    return descriptor
}

/** Resolves a bundle-relative path while refusing every symbolic-link component before capture. */
private fun resolveMnnBundleAsset(
    bundleRoot: File,
    relativePath: String,
    label: String
): File {
    val normalized = relativePath.replace('\\', '/').trim()
    require(
        normalized.isNotBlank() &&
            !normalized.startsWith('/') &&
            !MNN_WINDOWS_ABSOLUTE_PATH.containsMatchIn(normalized) &&
            normalized.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".."
            }
    ) {
        "MNN text-encoder $label path must be a safe bundle-relative path."
    }

    val root = bundleRoot.canonicalFile
    var requestedPath = root.toPath()
    normalized.split('/').forEach { segment ->
        requestedPath = requestedPath.resolve(segment)
        require(!Files.isSymbolicLink(requestedPath)) {
            "MNN text-encoder $label path must not traverse a symbolic link: $requestedPath"
        }
    }
    val requested = requestedPath.toFile().absoluteFile
    val canonical = requested.canonicalFile
    require(canonical.toPath().startsWith(root.toPath()) && canonical.toPath() != root.toPath()) {
        "MNN text-encoder $label path escapes its bundle root."
    }
    return requested
}

private data class MnnNativeTextEncoderReceiptAsset(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

private data class MnnNativeTextEncoderReceipt(
    val assets: List<MnnNativeTextEncoderReceiptAsset>,
    val closureSha256: String,
    val bindingStage: String
)

private fun TextualInversionExecutionAssetDescriptor.toReceiptAsset():
    MnnNativeTextEncoderReceiptAsset = MnnNativeTextEncoderReceiptAsset(path, sha256, sizeBytes)

private fun JSONObject.parseTextEncoderReceipt(layer: String): MnnNativeTextEncoderReceipt {
    val assets = strictTextEncoderArray("consumedTextEncoderAssets", layer)
    require(assets.length() in 1..2) {
        "MNN $layer text-encoder receipt must contain one graph and at most one .weight asset."
    }
    val parsedAssets = buildList {
        for (index in 0 until assets.length()) {
            val asset = assets.opt(index) as? JSONObject
                ?: error("MNN $layer text-encoder receipt asset $index must be an object.")
            add(
                MnnNativeTextEncoderReceiptAsset(
                    path = asset.strictTextEncoderString("path", "$layer asset $index"),
                    sha256 = asset.strictTextEncoderSha256("sha256", "$layer asset $index"),
                    sizeBytes = asset.strictTextEncoderPositiveLong("sizeBytes", "$layer asset $index")
                )
            )
        }
    }
    return MnnNativeTextEncoderReceipt(
        assets = parsedAssets,
        closureSha256 = strictTextEncoderSha256("consumedTextEncoderClosureSha256", layer),
        bindingStage = strictTextEncoderString("consumedTextEncoderBindingStage", layer)
    )
}

private fun JSONObject.strictTextEncoderArray(field: String, layer: String): JSONArray =
    opt(field) as? JSONArray
        ?: error("MNN $layer text-encoder receipt is missing array field $field.")

private fun JSONObject.strictTextEncoderString(field: String, layer: String): String =
    (opt(field) as? String)
        ?.takeIf(String::isNotBlank)
        ?: error("MNN $layer text-encoder receipt is missing string field $field.")

private fun JSONObject.strictTextEncoderSha256(field: String, layer: String): String =
    strictTextEncoderString(field, layer).also { value ->
        require(MNN_NATIVE_TEXT_ENCODER_SHA256.matches(value)) {
            "MNN $layer text-encoder receipt field $field must be a lowercase SHA-256 value."
        }
    }

private fun JSONObject.strictTextEncoderPositiveLong(field: String, layer: String): Long {
    val raw = opt(field)
    require(raw is Byte || raw is Short || raw is Int || raw is Long) {
        "MNN $layer text-encoder receipt field $field must be an exact integer."
    }
    return (raw as Number).toLong().also { value ->
        require(value > 0L) {
            "MNN $layer text-encoder receipt field $field must be positive."
        }
    }
}

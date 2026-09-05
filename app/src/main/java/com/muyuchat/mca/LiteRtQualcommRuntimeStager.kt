package com.muyuchat.mca

import android.content.Context
import android.os.Build
import java.util.Locale
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Stages the Qualcomm runtime shipped for LiteRT-LM into an app-private
 * directory. GenieX carries a different QAIRT revision under the APK's
 * nativeLibraryDir, so passing that directory directly to LiteRT can make the
 * Android linker resolve the wrong QNN SONAMEs.
 *
 * The asset sets mirror the Edge Gallery Qualcomm packages (QAIRT 2.47): the
 * LiteRT dispatch plugin plus the QNN host/system libraries and the matching
 * V73/V75/V79/V81 HTP transport pair. The model's native load remains
 * the compatibility authority; this class only makes the selected runtime
 * directory coherent.
 */
internal data class LiteRtQualcommRuntimeStage(
    val directory: File,
    val fingerprint: String,
    val reused: Boolean,
    val variant: String
)

internal object LiteRtQualcommRuntimeStager {
    private const val ASSET_ROOT = "litert-qualcomm"
    private const val STAGE_ROOT = "litert-qualcomm-runtime"
    private const val ABI = "arm64-v8a"
    private const val BUFFER_SIZE = 128 * 1024
    private const val GENERIC = "generic"
    private const val V73 = "v73"
    private const val V75 = "v75"
    private const val V79 = "v79"
    private const val V81 = "v81"
    private const val DEFAULT_VARIANT = GENERIC
    private const val PACKAGED_QNN_SDK_BUILD = "2.47.0.260601114230"

    // Keep the required set aligned with the Edge Gallery SM8850 APK. The
    // compiler plugin is optional for callers that explicitly provide it, but
    // the production asset sync excludes it: precompiled Qualcomm .litertlm
    // files use dispatch and the coherent QNN/HTP set only.
    private val COMMON_FILES = listOf(
        "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so",
        "libQnnSystem.so"
    )
    private val HTP_FILES_BY_VARIANT = mapOf(
        V73 to listOf("libQnnHtpV73Skel.so", "libQnnHtpV73Stub.so"),
        V75 to listOf("libQnnHtpV75Skel.so", "libQnnHtpV75Stub.so"),
        V79 to listOf("libQnnHtpV79Skel.so", "libQnnHtpV79Stub.so"),
        V81 to listOf("libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so"),
        // The generic/default profile uses the current V81 asset layout. It is
        // still labelled generic so callers can distinguish a fallback from a
        // model explicitly matched to SM8850.
        GENERIC to listOf("libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so")
    )
    private val OPTIONAL_FILES = listOf("libLiteRtCompilerPlugin_Qualcomm.so")

    private val EXPECTED_COMMON_SHA256 = mapOf(
        "libLiteRtCompilerPlugin_Qualcomm.so" to
            "425e5caf007f834748c6bf67aff265d7e21512a01910f219fab6b7749ef57732",
        "libLiteRtDispatch_Qualcomm.so" to
            "c4abfff6c99ec218f545415a81a2a03a3ee3e21df2ea911902d6b7bbfeda80bf",
        "libQnnHtp.so" to
            "c0488f2df87932a42ca0a563883e6fba190896bca439ad0fdaa2428358ab5092",
        "libQnnHtpV81Skel.so" to
            "0b4fa7419e7265ae33d5e57f214d49cd11abbf7302149bb7ff78d9b602903899",
        "libQnnHtpV81Stub.so" to
            "479da62bd52bb7cb5791d5898442457272d5403e42244af690a137b52d67f939",
        "libQnnSystem.so" to
            "077a8b20a53b216d006b85b58dd754a9e958e02f98e9c79d46619db6f8edfec9"
    )
    private val EXPECTED_VARIANT_SHA256 = mapOf(
        V73 to mapOf(
            "libQnnHtpV73Skel.so" to
                "b5084469a693b05e372eedf8861e29e92c6e012c1cf543144c894f6141a5348f",
            "libQnnHtpV73Stub.so" to
                "baeaa618d456ec59a40007084a411eb172e530701f770ef8b71a629cc0f12d97"
        ),
        V75 to mapOf(
            "libQnnHtpV75Skel.so" to
                "607be3d7ec64df053f019438ad6eea59dd3eea5d5f985001709d52d5663479ba",
            "libQnnHtpV75Stub.so" to
                "b126a79ebeabf09949656be42326546b03f3216ad671c9c0e4ac935cefa5a631"
        ),
        V79 to mapOf(
            "libQnnHtpV79Skel.so" to
                "dd8987c17928e53877e51ce5739c550d3e74393bd5b1b5ccf311326eddb08612",
            "libQnnHtpV79Stub.so" to
                "5f7460da65239d97be7b1845c2cebe20a4b8a92805721d9be796b2c4e706f132"
        ),
        V81 to mapOf(
            "libQnnHtpV81Skel.so" to
                "0b4fa7419e7265ae33d5e57f214d49cd11abbf7302149bb7ff78d9b602903899",
            "libQnnHtpV81Stub.so" to
                "479da62bd52bb7cb5791d5898442457272d5403e42244af690a137b52d67f939"
        )
    )
    // Keep every profile hash in the default map so file-backed callers can
    // select a transport using only a SoC hint. sourceFiles still verifies
    // only the files belonging to the selected profile.
    private val DEFAULT_EXPECTED_SHA256 =
        EXPECTED_COMMON_SHA256 + EXPECTED_VARIANT_SHA256.getValue(V73) +
            EXPECTED_VARIANT_SHA256.getValue(V75) +
            EXPECTED_VARIANT_SHA256.getValue(V81) +
            EXPECTED_VARIANT_SHA256.getValue(V79)

    private fun expectedSha256ForVariant(variant: String): Map<String, String> =
        EXPECTED_COMMON_SHA256 + EXPECTED_VARIANT_SHA256.getValue(
            if (variant == GENERIC) V81 else variant
        )

    /**
     * Returns null when assets are unavailable so an older or reduced APK can
     * still take the generic native path and let LiteRT report its real load
     * error. The SoC hint only chooses a transport profile; it is not an
     * admission check.
     */
    fun stage(
        context: Context,
        socHint: String? = null,
        variantOverride: String? = null
    ): LiteRtQualcommRuntimeStage? {
        val appContext = context.applicationContext
        val rawSocHint = resolvedSocModel(socHint)
        // A missing/unknown SoC must not be mapped to the V81 transport by
        // default.  Leave that case to generic runtime discovery and real
        // native graph execution; only an explicit override may request the
        // generic packaged layout.
        val variant = variantOverride ?: variantForSocModel(rawSocHint) ?: return null
        val destinationRoot = File(appContext.codeCacheDir, STAGE_ROOT)
        return stage(
            destinationRoot = destinationRoot,
            variant = variant,
            expectedSha256 = expectedSha256ForVariant(variant),
            openAsset = { path -> appContext.assets.open(path) }
        )
    }

    /**
     * Stages the APK's QAIRT 2.47 runtime only for a graph that explicitly
     * declares that SDK. A null result means the caller must continue through
     * normal OEM/APK discovery and real native load; it is never a device
     * admission failure.
     */
    internal fun stageForImageBundle(
        context: Context,
        bundleRuntimeAlreadyStaged: Boolean,
        qnnSdk: String?,
        socHint: String? = null
    ): LiteRtQualcommRuntimeStage? {
        val rawSocHint = resolvedSocModel(socHint)
        val packagedVariant = packagedRuntimeVariantForImageBundle(
                bundleRuntimeAlreadyStaged = bundleRuntimeAlreadyStaged,
                qnnSdk = qnnSdk,
                rawSocModel = rawSocHint
            )
        if (packagedVariant == null) {
            return null
        }
        return stage(context, rawSocHint, packagedVariant)
    }

    /**
     * Selects the transport ABI for a known Qualcomm SoC. This is only a
     * runtime transport preference. Unknown or missing SoCs return null here
     * so callers can distinguish a tuned match; staging uses the generic
     * package and lets native execution decide compatibility.
     */
    internal fun variantForSocModel(rawSocModel: String?): String? {
        val model = rawSocModel.orEmpty().trim().uppercase(Locale.US)
        return when {
            model.contains("SM8550") || model.contains("QCS8550") || model.contains("QCM8550") -> V73
            model.contains("SM8635") || model.contains("SM8650") -> V75
            model.contains("SM8750") -> V79
            model.contains("SM8850") -> V81
            else -> null
        }
    }

    /**
     * LiteRT v2.2's Qualcomm assets are one exact QAIRT/QNN build. Precompiled
     * contexts are not assumed compatible across 2.47 patch/build IDs, so a
     * graph-only bundle must declare the same complete build before these
     * assets can be selected. Other values continue through generic discovery
     * and real native graph execution.
     */
    internal fun packagedRuntimeSupportsQnnSdk(qnnSdk: String?): Boolean {
        val value = qnnSdk?.trim().orEmpty()
        val normalized = if (value.startsWith("v", ignoreCase = true)) value.drop(1) else value
        return normalized == PACKAGED_QNN_SDK_BUILD
    }

    /** Pure image-runtime selection used by production and the compatibility matrix tests. */
    internal fun packagedRuntimeVariantForImageBundle(
        bundleRuntimeAlreadyStaged: Boolean,
        qnnSdk: String?,
        rawSocModel: String?
    ): String? {
        if (bundleRuntimeAlreadyStaged || !packagedRuntimeSupportsQnnSdk(qnnSdk)) return null
        // Unknown/future SoCs do not receive the V81 asset set implicitly.
        // Returning null keeps the universal generic/native path available.
        return variantForSocModel(rawSocModel)
    }

    /** Returns true only when the SoC has an exact packaged HTP transport. */
    internal fun shouldUsePackagedRuntimeForSocModel(rawSocModel: String?): Boolean =
        variantForSocModel(rawSocModel) != null

    private fun resolvedSocModel(socHint: String?): String? =
        socHint ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.BOARD
        }

    /**
     * File-backed entry point used by JVM contract tests.  [sourceRoot] is the
     * root of the packaged assets tree, so it contains
     * `litert-qualcomm/arm64-v8a/...`.
     */
    internal fun stageFromDirectory(
        sourceRoot: File,
        destinationRoot: File,
        expectedSha256: Map<String, String> = DEFAULT_EXPECTED_SHA256,
        variant: String = DEFAULT_VARIANT
    ): LiteRtQualcommRuntimeStage? = stage(
        destinationRoot = destinationRoot,
        expectedSha256 = expectedSha256,
        variant = variant,
        openAsset = { path ->
            FileInputStream(File(sourceRoot, path.replace('/', File.separatorChar)))
        }
    )

    /**
     * File-backed entry point that derives the profile from a SoC hint.
     *
     * Only an exact known SoC receives a packaged HTP transport. Unknown or
     * older SoCs return null so callers continue through generic/native runtime
     * discovery; they are never given the V81 asset layout implicitly.
     */
    internal fun stageFromDirectory(
        sourceRoot: File,
        destinationRoot: File,
        socHint: String?,
        expectedSha256: Map<String, String> = DEFAULT_EXPECTED_SHA256
    ): LiteRtQualcommRuntimeStage? = stageFromDirectory(
        sourceRoot = sourceRoot,
        destinationRoot = destinationRoot,
        expectedSha256 = expectedSha256,
        variant = variantForSocModel(socHint) ?: return null
    )

    private fun stage(
        destinationRoot: File,
        expectedSha256: Map<String, String> = DEFAULT_EXPECTED_SHA256,
        variant: String = DEFAULT_VARIANT,
        openAsset: (String) -> InputStream
    ): LiteRtQualcommRuntimeStage? {
        if (variant !in HTP_FILES_BY_VARIANT) return null
        val source = sourceFiles(openAsset, variant, expectedSha256) ?: return null
        val fingerprint = fingerprint(source, variant)
        val destination = File(destinationRoot, fingerprint)
        if (verifyDestination(destination, source)) {
            return LiteRtQualcommRuntimeStage(destination, fingerprint, reused = true, variant = variant)
        }
        if (destination.exists()) {
            // A content-addressed directory with the same name but a bad file
            // is never repaired in place.  Leave it untouched and use the
            // generic path below; this prevents a partial runtime from being
            // mistaken for a verified one.
            return null
        }

        var staging: File? = null
        return runCatching {
            require(destinationRoot.exists() || destinationRoot.mkdirs()) {
                "Unable to create LiteRT Qualcomm staging root."
            }
            val temporary = File(
                destinationRoot,
                ".${fingerprint}.staging-${UUID.randomUUID().toString().replace("-", "")}"
            )
            staging = temporary
            require(temporary.mkdirs()) { "Unable to create LiteRT Qualcomm staging directory." }
            source.forEach { (name, assetPath, size, sha) ->
                copyAssetAndVerify(openAsset, assetPath, File(temporary, name), size, sha)
            }
            require(verifyDestination(temporary, source)) {
                "LiteRT Qualcomm staging verification failed."
            }
            if (!temporary.renameTo(destination)) {
                require(verifyDestination(destination, source)) {
                    "Unable to publish LiteRT Qualcomm staging directory."
                }
                temporary.deleteRecursively()
                LiteRtQualcommRuntimeStage(destination, fingerprint, reused = true, variant = variant)
            } else {
                staging = null
                LiteRtQualcommRuntimeStage(destination, fingerprint, reused = false, variant = variant)
            }
        }.getOrNull().also {
            staging?.deleteRecursively()
        }
    }

    private data class SourceFile(
        val name: String,
        val assetPath: String,
        val sizeBytes: Long,
        val sha256: String
    )

    private fun sourceFiles(
        openAsset: (String) -> InputStream,
        variant: String,
        expectedSha256: Map<String, String>
    ): List<SourceFile>? {
        val assetRoot = if (variant == V81 || variant == GENERIC) {
            // Preserve the original V81 asset layout for already-built APKs.
            "$ASSET_ROOT/$ABI"
        } else {
            "$ASSET_ROOT/$variant/$ABI"
        }
        return runCatching {
            val required = (COMMON_FILES + HTP_FILES_BY_VARIANT.getValue(variant)).map { name ->
                val path = "$assetRoot/$name"
                inspectAsset(
                    openAsset = openAsset,
                    assetPath = path,
                    name = name,
                    expectedSha256 = expectedSha256.getValue(name)
                )
            }
            val optional = OPTIONAL_FILES.mapNotNull { name ->
                // Variant directories may intentionally contain only the
                // precompiled five-file set. If a caller ships the optional
                // JIT compiler plugin once in the generic V81 asset root,
                // make it available to every transport variant without
                // duplicating or mixing variant-specific binaries.
                val candidatePaths = buildList {
                    add("$assetRoot/$name")
                    if (assetRoot != "$ASSET_ROOT/$ABI") add("$ASSET_ROOT/$ABI/$name")
                }
                candidatePaths.firstNotNullOfOrNull { path ->
                    runCatching {
                        val expected = expectedSha256[name] ?: return@runCatching null
                        inspectAsset(
                            openAsset = openAsset,
                            assetPath = path,
                            name = name,
                            expectedSha256 = expected
                        )
                    }.getOrNull()
                }
            }
            required + optional
        }.getOrNull()
    }

    private fun inspectAsset(
        openAsset: (String) -> InputStream,
        assetPath: String,
        name: String,
        expectedSha256: String
    ): SourceFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L
        openAsset(assetPath).buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                sizeBytes += read
            }
        }
        require(sizeBytes > 0L) { "Empty LiteRT Qualcomm asset: $name" }
        val actualSha256 = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        require(actualSha256 == expectedSha256) {
            "LiteRT Qualcomm asset failed SHA-256 verification: $name"
        }
        return SourceFile(name, assetPath, sizeBytes, expectedSha256)
    }

    private fun fingerprint(source: List<SourceFile>, variant: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("mca.litert.qualcomm.runtime.v2/$variant\n".toByteArray(Charsets.UTF_8))
        source.forEach { file ->
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.sizeBytes.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(file.sha256.toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun copyAssetAndVerify(
        openAsset: (String) -> InputStream,
        assetPath: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String
    ) {
        val partial = File(destination.parentFile, ".${destination.name}.part")
        openAsset(assetPath).buffered().use { input ->
            FileOutputStream(partial).use { output ->
                input.copyTo(output, BUFFER_SIZE)
                output.fd.sync()
            }
        }
        require(partial.length() == expectedSize && partial.sha256() == expectedSha256) {
            "LiteRT Qualcomm asset changed or copied incompletely: $assetPath"
        }
        require(partial.renameTo(destination)) { "Unable to publish $assetPath" }
        destination.setReadable(true, true)
        destination.setExecutable(true, true)
    }

    private fun verifyDestination(destination: File, source: List<SourceFile>): Boolean {
        if (!destination.isDirectory) return false
        val files = destination.listFiles().orEmpty().filter(File::isFile)
        if (files.map(File::getName).toSet() != source.map(SourceFile::name).toSet()) return false
        return source.all { expected ->
            val actual = File(destination, expected.name)
            actual.isFile && actual.length() == expected.sizeBytes &&
                runCatching { actual.sha256() == expected.sha256 }.getOrDefault(false)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

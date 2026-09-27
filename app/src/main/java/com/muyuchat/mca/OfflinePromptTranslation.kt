package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Fixed package contract for the optional, explicit zh-Hans to English translator.
 *
 * This is intentionally separate from image-model profiles and from the V4/chat path. A bundle
 * that passes this verifier is only layout and integrity verified; it does not prove that the
 * separately packaged native runtime can load or execute on the current device.
 */
internal object OfflinePromptTranslationContract {
    const val MANIFEST_CONTRACT_VERSION = 1
    const val MANIFEST_KIND = "mca-offline-prompt-translation"

    const val TRANSLATION_DIRECTORY = "translation"
    const val MANIFEST_FILE_NAME = "translation_manifest.json"
    const val MANIFEST_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MANIFEST_FILE_NAME"
    const val MODEL_FILE_NAME = "m2m100-418m-q4_k.gguf"
    const val MODEL_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MODEL_FILE_NAME"
    const val MODEL_NOTICE_FILE_NAME = "NOTICE.facebook-m2m100-418m-MIT.txt"
    const val MODEL_NOTICE_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MODEL_NOTICE_FILE_NAME"
    const val RUNTIME_NOTICE_FILE_NAME = "NOTICE.crispstrobe-crispasr-MIT.txt"
    const val RUNTIME_NOTICE_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$RUNTIME_NOTICE_FILE_NAME"

    const val MODEL_SOURCE_ID = "facebook/m2m100_418M"
    const val MODEL_SOURCE_REVISION = "55c2e61bbf05dfb8d7abccdc3fae6fc8512fd636"
    const val MODEL_LICENSE = "MIT"
    const val MODEL_ARCHITECTURE = "M2M100"
    const val MODEL_QUANTIZATION = "Q4_K"
    // These byte pins are the trust anchors; package manifests only bind the fixed layout to them.
    const val MODEL_ARTIFACT_SHA256 =
        "b3360f7a416f43f1631fd1888bf11d80ee3876d4683d3de99a00bcd238ed08e2"
    const val MODEL_ARTIFACT_SIZE_BYTES = 284_568_416L
    const val MODEL_NOTICE_ARTIFACT_SHA256 =
        "1fd660f130aedc5ecf1796b47ab47d43d3c4b36541f5387b7adb3df75cb5dfdb"
    const val MODEL_NOTICE_ARTIFACT_SIZE_BYTES = 4_603L

    const val RUNTIME_SOURCE_ID = "CrispStrobe/CrispASR"
    const val RUNTIME_SOURCE_REVISION = "7ed71ce78720650d362c202e43ce6a7ddfec71a8"
    const val RUNTIME_LICENSE = "MIT"
    const val RUNTIME_BACKEND = "CrispASR_M2M100_GGUF"
    const val NATIVE_LIBRARY_FILE_NAME = "libmca_translation_native.so"
    const val RUNTIME_NOTICE_ARTIFACT_SHA256 =
        "bcd8ec749126d45cb06737d0690295d73df4b6e7e194205bcf91190368f27285"
    const val RUNTIME_NOTICE_ARTIFACT_SIZE_BYTES = 1_099L

    const val SOURCE_LANGUAGE = "zh-Hans"
    const val TARGET_LANGUAGE = "en"
    const val SOURCE_M2M100_LANGUAGE_CODE = "zh"
    const val TARGET_M2M100_LANGUAGE_CODE = "en"

    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_NOTICE_BYTES = 1 * 1024 * 1024
    const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
    const val MAX_SOURCE_TEXT_CHARS = 4 * 1024
    const val MAX_OUTPUT_TEXT_CHARS = 8 * 1024

    val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    private val SAFE_FILE_NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isSafeFileName(value: String): Boolean =
        SAFE_FILE_NAME_PATTERN.matches(value) && !value.contains("..")

    fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toLowercaseHex()
}

/** The only language pair currently supported by the isolated offline translator. */
internal enum class OfflinePromptTranslationLanguage(
    val wireName: String,
    val m2m100LanguageCode: String
) {
    ZH_HANS(OfflinePromptTranslationContract.SOURCE_LANGUAGE, "zh"),
    ENGLISH(OfflinePromptTranslationContract.TARGET_LANGUAGE, "en");

    companion object {
        fun fromWireName(value: String): OfflinePromptTranslationLanguage? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal data class OfflinePromptTranslationProvenance(
    val sourceId: String,
    val sourceRevision: String,
    val license: String
) {
    init {
        require(sourceId.isNotBlank()) { "Translation provenance source id is required." }
        require(sourceRevision.matches(Regex("[0-9a-f]{40}"))) {
            "Translation provenance revision must be a lowercase Git SHA-1."
        }
        require(license == OfflinePromptTranslationContract.MODEL_LICENSE) {
            "Translation provenance license must be MIT."
        }
    }
}

internal data class OfflinePromptTranslationNotice(
    val relativePath: String,
    val file: File,
    val provenance: OfflinePromptTranslationProvenance,
    val sha256: String,
    val sizeBytes: Long
) {
    init {
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(sha256)) {
            "Translation notice SHA-256 is invalid."
        }
        require(sizeBytes > 0L) { "Translation notice must not be empty." }
    }
}

/** Identity pinned by compiled artifact anchors and by the verified local bytes. */
internal data class OfflinePromptTranslationBundleIdentity(
    val model: OfflinePromptTranslationProvenance,
    val runtime: OfflinePromptTranslationProvenance,
    val modelArchitecture: String,
    val modelQuantization: String,
    val sourceLanguage: OfflinePromptTranslationLanguage,
    val targetLanguage: OfflinePromptTranslationLanguage,
    val sourceM2m100LanguageCode: String,
    val targetM2m100LanguageCode: String,
    val modelSha256: String,
    val modelSizeBytes: Long,
    val nativeLibraryFileName: String,
    val notices: List<OfflinePromptTranslationNotice>
) {
    init {
        require(model.sourceId == OfflinePromptTranslationContract.MODEL_SOURCE_ID)
        require(model.sourceRevision == OfflinePromptTranslationContract.MODEL_SOURCE_REVISION)
        require(model.license == OfflinePromptTranslationContract.MODEL_LICENSE)
        require(runtime.sourceId == OfflinePromptTranslationContract.RUNTIME_SOURCE_ID)
        require(runtime.sourceRevision == OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION)
        require(runtime.license == OfflinePromptTranslationContract.RUNTIME_LICENSE)
        require(modelArchitecture == OfflinePromptTranslationContract.MODEL_ARCHITECTURE)
        require(modelQuantization == OfflinePromptTranslationContract.MODEL_QUANTIZATION)
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS)
        require(targetLanguage == OfflinePromptTranslationLanguage.ENGLISH)
        require(sourceM2m100LanguageCode == OfflinePromptTranslationContract.SOURCE_M2M100_LANGUAGE_CODE)
        require(targetM2m100LanguageCode == OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE)
        require(modelSha256 == OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256)
        require(modelSizeBytes == OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES)
        require(nativeLibraryFileName == OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME)
        require(notices.size == NOTICE_EXPECTATIONS.size)
        notices.zip(NOTICE_EXPECTATIONS).forEach { (notice, expectation) ->
            require(notice.relativePath == expectation.relativePath)
            require(notice.provenance == expectation.provenance)
            require(notice.sha256 == expectation.sha256)
            require(notice.sizeBytes == expectation.sizeBytes)
        }
    }

    val fingerprint: String
        get() = OfflinePromptTranslationContract.sha256Utf8(
            (
                listOf(
                    "mca-offline-prompt-translation-bundle-v1",
                    model.sourceId,
                    model.sourceRevision,
                    model.license,
                    runtime.sourceId,
                    runtime.sourceRevision,
                    runtime.license,
                    modelArchitecture,
                    modelQuantization,
                    sourceLanguage.wireName,
                    targetLanguage.wireName,
                    sourceM2m100LanguageCode,
                    targetM2m100LanguageCode,
                    modelSha256,
                    modelSizeBytes.toString(),
                    nativeLibraryFileName
                ) + notices.flatMap { notice ->
                    listOf(
                        notice.relativePath,
                        notice.provenance.sourceId,
                        notice.provenance.sourceRevision,
                        notice.provenance.license,
                        notice.sha256,
                        notice.sizeBytes.toString()
                    )
                }
            )
                .joinToString("\u001f")
        )
}

/**
 * Immutable output of a successful package verification. The File values are canonical paths
 * captured during verification. A future native adapter must re-run verification immediately
 * before loading if a package can change concurrently.
 */
internal class VerifiedOfflinePromptTranslationBundle internal constructor(
    val rootDirectory: File,
    val manifestFile: File,
    val modelFile: File,
    val identity: OfflinePromptTranslationBundleIdentity
) {
    fun createResult(
        request: OfflinePromptTranslationRequest,
        translatedText: String,
        translatedNegativePrompt: String? = null
    ): OfflinePromptTranslationResult {
        require(translatedText.length <= request.maxOutputChars) {
            "Offline prompt translation output exceeds the request limit."
        }
        return OfflinePromptTranslationResult(
            requestFingerprint = request.fingerprint,
            bundleFingerprint = identity.fingerprint,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            translatedText = translatedText,
            translatedNegativePrompt = translatedNegativePrompt,
            maxOutputChars = request.maxOutputChars
        )
    }
}

internal enum class OfflinePromptTranslationBundleRejectionCode {
    ROOT_UNAVAILABLE,
    LAYOUT_INVALID,
    MANIFEST_UNREADABLE,
    MANIFEST_INVALID,
    PROVENANCE_MISMATCH,
    FILE_UNSAFE,
    INTEGRITY_MISMATCH
}

internal sealed interface OfflinePromptTranslationBundleVerification {
    data class Verified(val bundle: VerifiedOfflinePromptTranslationBundle) :
        OfflinePromptTranslationBundleVerification

    data class Rejected(
        val code: OfflinePromptTranslationBundleRejectionCode,
        val message: String
    ) : OfflinePromptTranslationBundleVerification
}

/**
 * Verifies a self-contained package with one M2M100 model and the two required MIT notices.
 * It deliberately accepts no alternate model family, source revision, filename, or runtime.
 */
internal object OfflinePromptTranslationBundleVerifier {
    fun verify(bundleRoot: File?): OfflinePromptTranslationBundleVerification {
        if (bundleRoot == null) {
            return OfflinePromptTranslationBundleVerification.Rejected(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                "Offline translation package directory is not selected."
            )
        }
        return try {
            OfflinePromptTranslationBundleVerification.Verified(verifyOrThrow(bundleRoot))
        } catch (error: OfflinePromptTranslationBundleException) {
            OfflinePromptTranslationBundleVerification.Rejected(error.code, error.message.orEmpty())
        } catch (_: IOException) {
            OfflinePromptTranslationBundleVerification.Rejected(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_UNREADABLE,
                "Offline translation package could not be read."
            )
        } catch (_: SecurityException) {
            OfflinePromptTranslationBundleVerification.Rejected(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation package cannot be accessed safely."
            )
        } catch (_: JSONException) {
            OfflinePromptTranslationBundleVerification.Rejected(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation manifest is not valid JSON."
            )
        }
    }

    fun requireVerified(bundleRoot: File): VerifiedOfflinePromptTranslationBundle =
        when (val verification = verify(bundleRoot)) {
            is OfflinePromptTranslationBundleVerification.Verified -> verification.bundle
            is OfflinePromptTranslationBundleVerification.Rejected ->
                throw IllegalArgumentException(verification.message)
        }

    private fun verifyOrThrow(bundleRoot: File): VerifiedOfflinePromptTranslationBundle {
        val root = requireCanonicalDirectory(bundleRoot, "package root")
        requireExactDirectoryEntries(root, setOf(OfflinePromptTranslationContract.TRANSLATION_DIRECTORY))
        val translationDirectory = requireDirectDirectory(
            root,
            OfflinePromptTranslationContract.TRANSLATION_DIRECTORY
        )
        requireExactDirectoryEntries(
            translationDirectory,
            setOf(
                OfflinePromptTranslationContract.MANIFEST_FILE_NAME,
                OfflinePromptTranslationContract.MODEL_FILE_NAME,
                OfflinePromptTranslationContract.MODEL_NOTICE_FILE_NAME,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_FILE_NAME
            )
        )

        val manifestFile = requireDirectRegularFile(
            translationDirectory,
            OfflinePromptTranslationContract.MANIFEST_FILE_NAME
        )
        val manifest = parseManifest(readUtf8File(manifestFile, OfflinePromptTranslationContract.MAX_MANIFEST_BYTES))

        val modelFile = requireDirectRegularFile(
            translationDirectory,
            OfflinePromptTranslationContract.MODEL_FILE_NAME
        )
        verifyFileIntegrity(
            file = modelFile,
            expectedSha256 = OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256,
            expectedSizeBytes = OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES,
            maxSizeBytes = OfflinePromptTranslationContract.MAX_MODEL_BYTES,
            captureContents = false
        )

        val notices = NOTICE_EXPECTATIONS.map { expected ->
            val file = requireDirectRegularFile(translationDirectory, expected.fileName)
            val bytes = requireNotNull(
                verifyFileIntegrity(
                    file = file,
                    expectedSha256 = expected.sha256,
                    expectedSizeBytes = expected.sizeBytes,
                    maxSizeBytes = OfflinePromptTranslationContract.MAX_NOTICE_BYTES.toLong(),
                    captureContents = true
                )
            )
            val text = decodeStrictUtf8(bytes)
            if (!MIT_TOKEN.containsMatchIn(text)) {
                fail(
                    OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                    "Offline translation notice ${expected.fileName} does not identify the MIT license."
                )
            }
            OfflinePromptTranslationNotice(
                relativePath = expected.relativePath,
                file = file,
                provenance = expected.provenance,
                sha256 = expected.sha256,
                sizeBytes = expected.sizeBytes
            )
        }

        val identity = OfflinePromptTranslationBundleIdentity(
            model = manifest.model.provenance,
            runtime = manifest.runtime.provenance,
            modelArchitecture = manifest.model.architecture,
            modelQuantization = manifest.model.quantization,
            sourceLanguage = manifest.model.sourceLanguage,
            targetLanguage = manifest.model.targetLanguage,
            sourceM2m100LanguageCode = manifest.model.sourceM2m100LanguageCode,
            targetM2m100LanguageCode = manifest.model.targetM2m100LanguageCode,
            modelSha256 = OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256,
            modelSizeBytes = OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES,
            nativeLibraryFileName = manifest.runtime.nativeLibraryFileName,
            notices = notices
        )
        return VerifiedOfflinePromptTranslationBundle(
            rootDirectory = root,
            manifestFile = manifestFile,
            modelFile = modelFile,
            identity = identity
        )
    }

    private fun parseManifest(text: String): OfflinePromptTranslationManifest {
        val root = try {
            JSONObject(text)
        } catch (error: JSONException) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation manifest is not valid JSON.",
                error
            )
        }
        root.requireExactlyKeys(
            "manifest",
            setOf("kind", "contractVersion", "model", "runtime", "notices")
        )
        if (root.requireString("manifest", "kind") != OfflinePromptTranslationContract.MANIFEST_KIND ||
            root.requireInt("manifest", "contractVersion") !=
            OfflinePromptTranslationContract.MANIFEST_CONTRACT_VERSION
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation manifest kind or version is unsupported."
            )
        }
        val model = parseModelDeclaration(root.requireObject("manifest", "model"))
        val runtime = parseRuntimeDeclaration(root.requireObject("manifest", "runtime"))
        val rawNotices = root.requireArray("manifest", "notices")
        if (rawNotices.length() != NOTICE_EXPECTATIONS.size) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation manifest must contain exactly two license notices."
            )
        }
        val notices = NOTICE_EXPECTATIONS.mapIndexed { index, expectation ->
            parseNoticeDeclaration(
                rawNotices.requireObject("notices[$index]", index),
                expectation
            )
        }
        return OfflinePromptTranslationManifest(model = model, runtime = runtime, notices = notices)
    }

    private fun parseModelDeclaration(json: JSONObject): OfflinePromptTranslationModelDeclaration {
        json.requireExactlyKeys(
            "model",
            setOf(
                "fileName",
                "relativePath",
                "sha256",
                "sizeBytes",
                "sourceId",
                "sourceRevision",
                "license",
                "architecture",
                "quantization",
                "sourceLanguage",
                "targetLanguage",
                "sourceM2m100LanguageCode",
                "targetM2m100LanguageCode",
                "forcedTargetLanguageCode"
            )
        )
        val fileName = json.requireString("model", "fileName")
        val relativePath = json.requireString("model", "relativePath")
        if (fileName != OfflinePromptTranslationContract.MODEL_FILE_NAME ||
            relativePath != OfflinePromptTranslationContract.MODEL_RELATIVE_PATH ||
            !OfflinePromptTranslationContract.isSafeFileName(fileName)
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation model path is invalid."
            )
        }
        val provenance = requireExactProvenance(
            json = json,
            context = "model",
            sourceId = OfflinePromptTranslationContract.MODEL_SOURCE_ID,
            sourceRevision = OfflinePromptTranslationContract.MODEL_SOURCE_REVISION,
            license = OfflinePromptTranslationContract.MODEL_LICENSE
        )
        val architecture = json.requireString("model", "architecture")
        val quantization = json.requireString("model", "quantization")
        val sourceLanguage = json.requireString("model", "sourceLanguage")
        val targetLanguage = json.requireString("model", "targetLanguage")
        val sourceLanguageCode = json.requireString("model", "sourceM2m100LanguageCode")
        val targetLanguageCode = json.requireString("model", "targetM2m100LanguageCode")
        val forcedTargetLanguageCode = json.requireString("model", "forcedTargetLanguageCode")
        if (architecture != OfflinePromptTranslationContract.MODEL_ARCHITECTURE ||
            quantization != OfflinePromptTranslationContract.MODEL_QUANTIZATION ||
            sourceLanguage != OfflinePromptTranslationContract.SOURCE_LANGUAGE ||
            targetLanguage != OfflinePromptTranslationContract.TARGET_LANGUAGE ||
            sourceLanguageCode != OfflinePromptTranslationContract.SOURCE_M2M100_LANGUAGE_CODE ||
            targetLanguageCode != OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE ||
            forcedTargetLanguageCode != OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation model does not match the fixed M2M100 zh-Hans to English contract."
            )
        }
        val sha256 = json.requireSha256("model", "sha256")
        val sizeBytes = json.requirePositiveLong(
            "model",
            "sizeBytes",
            OfflinePromptTranslationContract.MAX_MODEL_BYTES
        )
        if (sha256 != OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256 ||
            sizeBytes != OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation model is not a code-pinned approved artifact."
            )
        }
        return OfflinePromptTranslationModelDeclaration(
            fileName = fileName,
            relativePath = relativePath,
            sha256 = OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256,
            sizeBytes = OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES,
            provenance = provenance,
            architecture = architecture,
            quantization = quantization,
            sourceLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
            targetLanguage = OfflinePromptTranslationLanguage.ENGLISH,
            sourceM2m100LanguageCode = sourceLanguageCode,
            targetM2m100LanguageCode = targetLanguageCode
        )
    }

    private fun parseRuntimeDeclaration(json: JSONObject): OfflinePromptTranslationRuntimeDeclaration {
        json.requireExactlyKeys(
            "runtime",
            setOf("sourceId", "sourceRevision", "license", "backend", "nativeLibraryFileName")
        )
        val provenance = requireExactProvenance(
            json = json,
            context = "runtime",
            sourceId = OfflinePromptTranslationContract.RUNTIME_SOURCE_ID,
            sourceRevision = OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION,
            license = OfflinePromptTranslationContract.RUNTIME_LICENSE
        )
        val backend = json.requireString("runtime", "backend")
        val nativeLibraryFileName = json.requireString("runtime", "nativeLibraryFileName")
        if (backend != OfflinePromptTranslationContract.RUNTIME_BACKEND ||
            nativeLibraryFileName != OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME ||
            !OfflinePromptTranslationContract.isSafeFileName(nativeLibraryFileName)
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation runtime does not match the fixed CrispASR M2M100 contract."
            )
        }
        return OfflinePromptTranslationRuntimeDeclaration(
            provenance = provenance,
            backend = backend,
            nativeLibraryFileName = nativeLibraryFileName
        )
    }

    private fun parseNoticeDeclaration(
        json: JSONObject,
        expected: OfflinePromptTranslationNoticeExpectation
    ): OfflinePromptTranslationNoticeDeclaration {
        json.requireExactlyKeys(
            "notice",
            setOf("fileName", "relativePath", "sha256", "sizeBytes", "sourceId", "sourceRevision", "license")
        )
        val fileName = json.requireString("notice", "fileName")
        val relativePath = json.requireString("notice", "relativePath")
        if (fileName != expected.fileName || relativePath != expected.relativePath ||
            !OfflinePromptTranslationContract.isSafeFileName(fileName)
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation notice path is invalid."
            )
        }
        val provenance = requireExactProvenance(
            json = json,
            context = "notice",
            sourceId = expected.provenance.sourceId,
            sourceRevision = expected.provenance.sourceRevision,
            license = expected.provenance.license
        )
        val sha256 = json.requireSha256("notice", "sha256")
        val sizeBytes = json.requirePositiveLong(
            "notice",
            "sizeBytes",
            OfflinePromptTranslationContract.MAX_NOTICE_BYTES.toLong()
        )
        if (sha256 != expected.sha256 || sizeBytes != expected.sizeBytes) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation notice $fileName is not a code-pinned approved artifact."
            )
        }
        return OfflinePromptTranslationNoticeDeclaration(
            fileName = fileName,
            relativePath = relativePath,
            sha256 = expected.sha256,
            sizeBytes = expected.sizeBytes,
            provenance = provenance
        )
    }

    private fun requireExactProvenance(
        json: JSONObject,
        context: String,
        sourceId: String,
        sourceRevision: String,
        license: String
    ): OfflinePromptTranslationProvenance {
        val actualSourceId = json.requireString(context, "sourceId")
        val actualSourceRevision = json.requireString(context, "sourceRevision")
        val actualLicense = json.requireString(context, "license")
        if (actualSourceId != sourceId || actualSourceRevision != sourceRevision ||
            actualLicense != license
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH,
                "Offline translation $context provenance is not the approved source and revision."
            )
        }
        return OfflinePromptTranslationProvenance(
            sourceId = actualSourceId,
            sourceRevision = actualSourceRevision,
            license = actualLicense
        )
    }

    private fun requireCanonicalDirectory(input: File, description: String): File {
        val lexical = input.absoluteFile
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isDirectory || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                "Offline translation $description is missing, not a directory, or symbolic."
            )
        }
        val canonical = lexical.canonicalFile
        val canonicalAttributes = readAttributes(canonical)
        if (!canonicalAttributes.isDirectory || canonicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                "Offline translation $description is not a regular directory."
            )
        }
        return canonical
    }

    private fun requireDirectDirectory(parent: File, name: String): File {
        require(OfflinePromptTranslationContract.isSafeFileName(name))
        val lexical = File(parent, name)
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isDirectory || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name is missing or unsafe."
            )
        }
        val canonical = lexical.canonicalFile
        if (canonical.parentFile?.path != parent.path || canonical.name != name) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name escapes the package boundary."
            )
        }
        val attributes = readAttributes(canonical)
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation directory $name is not a regular directory."
            )
        }
        return canonical
    }

    private fun requireDirectRegularFile(parent: File, name: String): File {
        if (!OfflinePromptTranslationContract.isSafeFileName(name)) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation filename is unsafe."
            )
        }
        val lexical = File(parent, name)
        val lexicalAttributes = readAttributes(lexical)
        if (!lexicalAttributes.isRegularFile || lexicalAttributes.isSymbolicLink) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name is missing or unsafe."
            )
        }
        val canonical = lexical.canonicalFile
        if (canonical.parentFile?.path != parent.path || canonical.name != name) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name escapes the package boundary."
            )
        }
        val attributes = readAttributes(canonical)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() <= 0L) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.FILE_UNSAFE,
                "Offline translation file $name is not a non-empty regular file."
            )
        }
        return canonical
    }

    private fun requireExactDirectoryEntries(directory: File, expectedNames: Set<String>) {
        val children = directory.listFiles() ?: fail(
            OfflinePromptTranslationBundleRejectionCode.LAYOUT_INVALID,
            "Offline translation package directory cannot be listed."
        )
        val actualNames = children.map(File::getName).toSet()
        if (children.size != expectedNames.size || actualNames != expectedNames) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.LAYOUT_INVALID,
                "Offline translation package has an unexpected file layout."
            )
        }
    }

    private fun readUtf8File(file: File, maxBytes: Int): String {
        val attributes = readAttributes(file)
        if (!attributes.isRegularFile || attributes.isSymbolicLink ||
            attributes.size() <= 0L || attributes.size() > maxBytes.toLong()
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_UNREADABLE,
                "Offline translation manifest has an invalid size or type."
            )
        }
        val bytes = readBoundedBytes(file, maxBytes.toLong())
        return decodeStrictUtf8(bytes)
    }

    /** Reads and hashes one stable file snapshot. Notice contents are returned only when requested. */
    private fun verifyFileIntegrity(
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxSizeBytes: Long,
        captureContents: Boolean
    ): ByteArray? {
        val before = readAttributes(file)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != expectedSizeBytes ||
            before.size() <= 0L || before.size() > maxSizeBytes
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file size or type does not match its manifest."
            )
        }
        val captured = if (captureContents) ByteArrayOutputStream(expectedSizeBytes.toInt()) else null
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > expectedSizeBytes || total > maxSizeBytes) {
                    fail(
                        OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                        "Offline translation file changed while it was being read."
                    )
                }
                digest.update(buffer, 0, count)
                captured?.write(buffer, 0, count)
            }
        }
        val after = readAttributes(file)
        if (!after.isRegularFile || after.isSymbolicLink || total != expectedSizeBytes ||
            !sameFileSnapshot(before, after)
        ) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file changed while it was being verified."
            )
        }
        if (digest.digest().toLowercaseHex() != expectedSha256) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                "Offline translation file SHA-256 does not match its manifest."
            )
        }
        return captured?.toByteArray()
    }

    private fun readBoundedBytes(file: File, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > maxBytes) {
                    fail(
                        OfflinePromptTranslationBundleRejectionCode.MANIFEST_UNREADABLE,
                        "Offline translation manifest exceeds its size limit."
                    )
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun readAttributes(file: File): BasicFileAttributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )

    private fun sameFileSnapshot(
        before: BasicFileAttributes,
        after: BasicFileAttributes
    ): Boolean = before.fileKey()?.toString() == after.fileKey()?.toString() &&
        before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()

    private fun decodeStrictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation package contains invalid UTF-8 text.",
            error
        )
    }

    private fun JSONObject.requireExactlyKeys(context: String, expected: Set<String>) {
        val actual = buildSet {
            val iterator = keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (actual != expected) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation $context has missing or unsupported fields."
            )
        }
    }

    private fun JSONObject.requireObject(context: String, name: String): JSONObject =
        opt(name) as? JSONObject ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an object."
        )

    private fun JSONObject.requireArray(context: String, name: String): JSONArray =
        opt(name) as? JSONArray ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an array."
        )

    private fun JSONArray.requireObject(context: String, index: Int): JSONObject =
        opt(index) as? JSONObject ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context must be an object."
        )

    private fun JSONObject.requireString(context: String, name: String): String {
        val value = opt(name) as? String ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be a string."
        )
        if (value.isBlank() || value != value.trim()) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation $context.$name must be a trimmed non-empty string."
            )
        }
        return value
    }

    private fun JSONObject.requireInt(context: String, name: String): Int {
        val value = opt(name)
        return when (value) {
            is Int -> value
            is Long -> value.toInt().takeIf { it.toLong() == value }
            else -> null
        } ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an integer."
        )
    }

    private fun JSONObject.requirePositiveLong(
        context: String,
        name: String,
        maximum: Long
    ): Long {
        val value = when (val raw = opt(name)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        } ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an integer."
        )
        if (value !in 1L..maximum) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation $context.$name is outside the supported range."
            )
        }
        return value
    }

    private fun JSONObject.requireSha256(context: String, name: String): String {
        val value = requireString(context, name)
        if (!OfflinePromptTranslationContract.SHA256_PATTERN.matches(value)) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation $context.$name must be lowercase SHA-256."
            )
        }
        return value
    }

    private fun fail(
        code: OfflinePromptTranslationBundleRejectionCode,
        message: String,
        cause: Throwable? = null
    ): Nothing = throw OfflinePromptTranslationBundleException(code, message, cause)

    private val MIT_TOKEN = Regex("\\bMIT\\b", RegexOption.IGNORE_CASE)
}

private class OfflinePromptTranslationBundleException(
    val code: OfflinePromptTranslationBundleRejectionCode,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

private data class OfflinePromptTranslationManifest(
    val model: OfflinePromptTranslationModelDeclaration,
    val runtime: OfflinePromptTranslationRuntimeDeclaration,
    val notices: List<OfflinePromptTranslationNoticeDeclaration>
)

private data class OfflinePromptTranslationModelDeclaration(
    val fileName: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val provenance: OfflinePromptTranslationProvenance,
    val architecture: String,
    val quantization: String,
    val sourceLanguage: OfflinePromptTranslationLanguage,
    val targetLanguage: OfflinePromptTranslationLanguage,
    val sourceM2m100LanguageCode: String,
    val targetM2m100LanguageCode: String
)

private data class OfflinePromptTranslationRuntimeDeclaration(
    val provenance: OfflinePromptTranslationProvenance,
    val backend: String,
    val nativeLibraryFileName: String
)

private data class OfflinePromptTranslationNoticeDeclaration(
    val fileName: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val provenance: OfflinePromptTranslationProvenance
)

private data class OfflinePromptTranslationNoticeExpectation(
    val fileName: String,
    val relativePath: String,
    val provenance: OfflinePromptTranslationProvenance,
    val sha256: String,
    val sizeBytes: Long
)

private val NOTICE_EXPECTATIONS: List<OfflinePromptTranslationNoticeExpectation> = listOf(
    OfflinePromptTranslationNoticeExpectation(
        fileName = OfflinePromptTranslationContract.MODEL_NOTICE_FILE_NAME,
        relativePath = OfflinePromptTranslationContract.MODEL_NOTICE_RELATIVE_PATH,
        provenance = OfflinePromptTranslationProvenance(
            sourceId = OfflinePromptTranslationContract.MODEL_SOURCE_ID,
            sourceRevision = OfflinePromptTranslationContract.MODEL_SOURCE_REVISION,
            license = OfflinePromptTranslationContract.MODEL_LICENSE
        ),
        sha256 = OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SHA256,
        sizeBytes = OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SIZE_BYTES
    ),
    OfflinePromptTranslationNoticeExpectation(
        fileName = OfflinePromptTranslationContract.RUNTIME_NOTICE_FILE_NAME,
        relativePath = OfflinePromptTranslationContract.RUNTIME_NOTICE_RELATIVE_PATH,
        provenance = OfflinePromptTranslationProvenance(
            sourceId = OfflinePromptTranslationContract.RUNTIME_SOURCE_ID,
            sourceRevision = OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION,
            license = OfflinePromptTranslationContract.RUNTIME_LICENSE
        ),
        sha256 = OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256,
        sizeBytes = OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SIZE_BYTES
    )
)

/** A request has no implicit fallback: only zh-Hans to English is permitted. */
internal data class OfflinePromptTranslationRequest(
    val sourceText: String,
    val sourceLanguage: OfflinePromptTranslationLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
    val targetLanguage: OfflinePromptTranslationLanguage = OfflinePromptTranslationLanguage.ENGLISH,
    val maxOutputChars: Int = OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS,
    /** Copied unchanged so a translator can never drop the user's negative prompt. */
    val negativePrompt: String = ""
) {
    init {
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS &&
            targetLanguage == OfflinePromptTranslationLanguage.ENGLISH
        ) { "Offline prompt translation only supports zh-Hans to English." }
        require(sourceText.isNotBlank() && sourceText.length <= OfflinePromptTranslationContract.MAX_SOURCE_TEXT_CHARS) {
            "Offline prompt translation source text is invalid."
        }
        require(sourceText.containsHanForOfflineTranslation()) {
            "Offline prompt translation source text must contain Han script."
        }
        require(!sourceText.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation source text contains unsafe control characters."
        }
        require(negativePrompt.length <= OfflinePromptTranslationContract.MAX_SOURCE_TEXT_CHARS) {
            "Offline prompt translation negative prompt is invalid."
        }
        require(!negativePrompt.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation negative prompt contains unsafe control characters."
        }
        require(maxOutputChars in 1..OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS) {
            "Offline prompt translation output limit is invalid."
        }
    }

    val fingerprint: String
        get() = OfflinePromptTranslationContract.sha256Utf8(
            listOf(
                "mca-offline-prompt-translation-request-v1",
                sourceLanguage.wireName,
                targetLanguage.wireName,
                maxOutputChars.toString(),
                sourceText,
                negativePrompt
            ).joinToString("\u001f")
        )
}

/**
 * A native adapter must construct this through [VerifiedOfflinePromptTranslationBundle.createResult].
 * The constructor enforces its request-bound character limit and rejects Han script or control data.
 */
internal data class OfflinePromptTranslationResult internal constructor(
    val requestFingerprint: String,
    val bundleFingerprint: String,
    val sourceLanguage: OfflinePromptTranslationLanguage,
    val targetLanguage: OfflinePromptTranslationLanguage,
    val translatedText: String,
    val translatedNegativePrompt: String? = null,
    private val maxOutputChars: Int
) {
    init {
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(requestFingerprint)) {
            "Offline prompt translation request fingerprint is invalid."
        }
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(bundleFingerprint)) {
            "Offline prompt translation bundle fingerprint is invalid."
        }
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS &&
            targetLanguage == OfflinePromptTranslationLanguage.ENGLISH
        ) { "Offline prompt translation result has an unsupported language pair." }
        require(maxOutputChars in 1..OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS) {
            "Offline prompt translation result output limit is invalid."
        }
        require(translatedText.isNotBlank() &&
            translatedText.length <= maxOutputChars
        ) { "Offline prompt translation output is invalid." }
        require(!translatedText.containsHanForOfflineTranslation()) {
            "Offline prompt translation output must not contain Han script."
        }
        require(!translatedText.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation output contains unsafe control characters."
        }
        require(translatedText.isSafeAsciiDiffusionPrompt()) {
            "Offline prompt translation output must use safe ASCII diffusion prompt syntax."
        }
        require(translatedNegativePrompt == null ||
            (translatedNegativePrompt.isNotBlank() &&
                translatedNegativePrompt.length <= maxOutputChars &&
                !translatedNegativePrompt.containsHanForOfflineTranslation() &&
                !translatedNegativePrompt.containsUnsafeTranslationCharacters() &&
                translatedNegativePrompt.isSafeAsciiDiffusionPrompt())
        ) { "Offline prompt translation negative output is invalid." }
    }

    fun matches(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): Boolean = requestFingerprint == request.fingerprint &&
        bundleFingerprint == bundle.identity.fingerprint &&
        sourceLanguage == request.sourceLanguage &&
        targetLanguage == request.targetLanguage &&
        maxOutputChars == request.maxOutputChars &&
        translatedText.length <= request.maxOutputChars

    /**
     * Rebuilds a result after the service has appended a missing protected token. Keeping this
     * constructor helper next to the invariant checks prevents an adapter from manufacturing a
     * result that bypasses the ASCII/output limits.
     */
    internal fun withTranslatedText(value: String): OfflinePromptTranslationResult =
        OfflinePromptTranslationResult(
            requestFingerprint = requestFingerprint,
            bundleFingerprint = bundleFingerprint,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translatedText = value,
            translatedNegativePrompt = translatedNegativePrompt,
            maxOutputChars = maxOutputChars
        )

    internal fun withTranslatedNegativePrompt(value: String): OfflinePromptTranslationResult =
        OfflinePromptTranslationResult(
            requestFingerprint = requestFingerprint,
            bundleFingerprint = bundleFingerprint,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translatedText = translatedText,
            translatedNegativePrompt = value,
            maxOutputChars = maxOutputChars
        )
}

/**
 * Isolated native boundary. Implementations must use only the verified M2M100 bundle and a
 * separately packaged libmca_translation_native.so. They must never route to V4, a chat LLM,
 * MNN, llama, cloud translation, or any heuristic substitute.
 */
internal interface OfflinePromptTranslationRuntime {
    val nativeLibraryFileName: String

    suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationRuntimeOutcome
}

internal enum class OfflinePromptTranslationUnavailableReason {
    NATIVE_LIBRARY_NOT_PACKAGED,
    NATIVE_RUNTIME_NOT_INITIALIZED,
    NATIVE_RUNTIME_UNSUPPORTED
}

internal sealed interface OfflinePromptTranslationRuntimeOutcome {
    data class Translated(val result: OfflinePromptTranslationResult) :
        OfflinePromptTranslationRuntimeOutcome

    data class Unavailable(
        val reason: OfflinePromptTranslationUnavailableReason,
        val message: String
    ) : OfflinePromptTranslationRuntimeOutcome

    data class Failed(val message: String) : OfflinePromptTranslationRuntimeOutcome
}

/**
 * Runtime lookup is deliberately injected. The contract file does not load a .so by itself and
 * therefore cannot accidentally claim that Hy-MT2, M2M100, or any other package is executable.
 */
internal fun interface OfflinePromptTranslationRuntimeProvider {
    fun runtimeFor(bundle: VerifiedOfflinePromptTranslationBundle): OfflinePromptTranslationRuntime
}

internal object DefaultOfflinePromptTranslationRuntimeProvider : OfflinePromptTranslationRuntimeProvider {
    override fun runtimeFor(bundle: VerifiedOfflinePromptTranslationBundle): OfflinePromptTranslationRuntime =
        UnavailableOfflinePromptTranslationRuntime
}

internal enum class OfflinePromptTranslationFallbackReason {
    BUNDLE_UNAVAILABLE,
    NATIVE_LIBRARY_NOT_PACKAGED,
    NATIVE_RUNTIME_NOT_INITIALIZED,
    NATIVE_RUNTIME_UNSUPPORTED,
    RUNTIME_BUSY,
    TIMEOUT,
    RUNTIME_FAILED,
    INVALID_OUTPUT,
    PROTECTED_SYNTAX_LOST
}

/**
 * Result consumed by the image prompt layer. A fallback is intentionally not represented as a
 * translated result: callers can then decide whether to invoke the existing chat-model bridge
 * or ask the user to edit the prompt, while retaining the exact original positive and negative
 * text. No fallback silently submits Chinese to an English-only encoder.
 */
internal sealed interface OfflinePromptTranslationResolution {
    data class Translated(
        val originalPrompt: String,
        val translatedPrompt: String,
        val originalNegativePrompt: String,
        val effectiveNegativePrompt: String,
        val result: OfflinePromptTranslationResult,
        val protectedTokens: List<String>
    ) : OfflinePromptTranslationResolution

    data class Fallback(
        val originalPrompt: String,
        val originalNegativePrompt: String,
        val protectedTokens: List<String>,
        val reason: OfflinePromptTranslationFallbackReason,
        /** Stable user-facing message; native exception text is never exposed here. */
        val message: String
    ) : OfflinePromptTranslationResolution
}

internal object OfflinePromptTranslationFallbackMessages {
    const val BUNDLE_UNAVAILABLE = "没有可核验的本地翻译模型，已保留原始提示词。"
    const val NATIVE_LIBRARY_NOT_PACKAGED = "本地离线翻译运行时未随应用安装，已保留原始提示词。"
    const val NATIVE_RUNTIME_NOT_INITIALIZED = "本地离线翻译运行时尚未初始化，已保留原始提示词。"
    const val NATIVE_RUNTIME_UNSUPPORTED = "当前本地翻译运行时不支持此模型，已保留原始提示词。"
    const val NATIVE_RUNTIME_BUSY = "本地离线翻译正在处理另一项请求，已保留原始提示词。"
    /** Kept for callers that only need a generic label. */
    const val NATIVE_UNAVAILABLE = "本地离线翻译运行时不可用，已保留原始提示词。"
    const val TIMEOUT = "中文提示词转换超时，已保留原始提示词。"
    const val FAILED = "中文提示词转换失败，已保留原始提示词。"
    const val INVALID_OUTPUT = "翻译结果未通过格式校验，已保留原始提示词。"
    const val PROTECTED_SYNTAX_LOST = "翻译结果未完整保留 LoRA 或权重标记，已保留原始提示词。"
}

/**
 * Coordinates one verified runtime invocation. It provides timeout/cancellation boundaries and
 * a safe fallback without coupling to the chat model lifecycle. A CancellationException from the
 * caller is rethrown so stop/back navigation remains coroutine-owned; only a timeout is converted
 * to a user-visible fallback.
 */
internal class OfflinePromptTranslationService(
    private val runtimeProvider: OfflinePromptTranslationRuntimeProvider =
        DefaultOfflinePromptTranslationRuntimeProvider,
    private val timeoutMs: Long = DEFAULT_OFFLINE_PROMPT_TRANSLATION_TIMEOUT_MS
) {
    /**
     * M2M100 is a heavyweight native session.  Do not let two image requests race the same
     * bundle/runtime and exhaust the app's memory.  A busy translator is a normal, recoverable
     * state; the caller keeps the editable original prompt instead of waiting behind an
     * unrelated request.
     */
    private val invocationGate = Mutex()

    init {
        require(timeoutMs > 0L) { "Offline prompt translation timeout must be positive." }
    }

    suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle?,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationResolution {
        val protectedTokens = (
            extractOfflinePromptProtectedTokens(request.sourceText) +
                extractOfflinePromptProtectedTokens(request.negativePrompt)
            ).distinct()
        if (bundle == null) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.BUNDLE_UNAVAILABLE,
                message = OfflinePromptTranslationFallbackMessages.BUNDLE_UNAVAILABLE
            )
        }

        val runtime = try {
            runtimeProvider.runtimeFor(bundle)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_NOT_INITIALIZED,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_NOT_INITIALIZED
            )
        }

        // Runtime adapters are injected so the contract module does not load arbitrary .so files.
        // Still bind the adapter to the verified bundle here: an adapter for another model family
        // must never be allowed to claim that this M2M100 package was executed.
        if (runtime.nativeLibraryFileName != bundle.identity.nativeLibraryFileName) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_UNSUPPORTED
            )
        }

        if (!invocationGate.tryLock()) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_BUSY,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_BUSY
            )
        }

        val outcome = try {
            withTimeout(timeoutMs) { runtime.translate(bundle, request) }
        } catch (_: TimeoutCancellationException) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.TIMEOUT,
                message = OfflinePromptTranslationFallbackMessages.TIMEOUT
            )
        } catch (error: CancellationException) {
            // Do not turn a user stop into a normal prompt result. The parent job owns cancel.
            throw error
        } catch (_: Throwable) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_FAILED,
                message = OfflinePromptTranslationFallbackMessages.FAILED
            )
        } finally {
            invocationGate.unlock()
        }

        return when (outcome) {
            is OfflinePromptTranslationRuntimeOutcome.Unavailable -> fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = outcome.reason.toFallbackReason(),
                message = outcome.reason.toFallbackMessage()
            )

            is OfflinePromptTranslationRuntimeOutcome.Failed -> fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_FAILED,
                message = OfflinePromptTranslationFallbackMessages.FAILED
            )

            is OfflinePromptTranslationRuntimeOutcome.Translated ->
                validateTranslatedResult(bundle, request, outcome.result, protectedTokens)
        }
    }

    private fun validateTranslatedResult(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest,
        result: OfflinePromptTranslationResult,
        protectedTokens: List<String>
    ): OfflinePromptTranslationResolution {
        if (!result.matches(bundle, request)) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.INVALID_OUTPUT,
                message = OfflinePromptTranslationFallbackMessages.INVALID_OUTPUT
            )
        }
        val positiveTokens = extractOfflinePromptProtectedTokens(request.sourceText)
        val negativeTokens = extractOfflinePromptProtectedTokens(request.negativePrompt)
        val positiveMissing = positiveTokens.filterNot { token -> result.translatedText.contains(token) }
        val translatedNegative = result.translatedNegativePrompt
            ?: if (request.negativePrompt.containsHanForOfflineTranslation()) {
                return fallback(
                    request = request,
                    protectedTokens = protectedTokens,
                    reason = OfflinePromptTranslationFallbackReason.INVALID_OUTPUT,
                    message = OfflinePromptTranslationFallbackMessages.INVALID_OUTPUT
                )
            } else {
                request.negativePrompt
            }
        val negativeMissing = negativeTokens.filterNot { token -> translatedNegative.contains(token) }
        if (positiveMissing.isEmpty() && negativeMissing.isEmpty()) {
            return OfflinePromptTranslationResolution.Translated(
                originalPrompt = request.sourceText,
                translatedPrompt = result.translatedText,
                originalNegativePrompt = request.negativePrompt,
                effectiveNegativePrompt = translatedNegative,
                result = result,
                protectedTokens = protectedTokens
            )
        }

        // Preserve user-authored control syntax in its original positive/negative field. If a
        // non-ASCII protected token cannot be safely appended, fall back instead of dropping it.
        val appendedPositive = listOf(result.translatedText, positiveMissing.joinToString(", "))
            .filter(String::isNotBlank)
            .joinToString(", ")
        val appendedNegative = listOf(translatedNegative, negativeMissing.joinToString(", "))
            .filter(String::isNotBlank)
            .joinToString(", ")
        if (appendedPositive.length > request.maxOutputChars ||
            !appendedPositive.isSafeAsciiDiffusionPrompt() ||
            (appendedNegative.isNotBlank() &&
                (appendedNegative.length > request.maxOutputChars ||
                    !appendedNegative.isSafeAsciiDiffusionPrompt()))
        ) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST,
                message = OfflinePromptTranslationFallbackMessages.PROTECTED_SYNTAX_LOST
            )
        }
        val repaired = try {
            result.withTranslatedText(appendedPositive)
                .let { safe ->
                    if (appendedNegative.isBlank() || appendedNegative == result.translatedNegativePrompt) {
                        safe
                    } else {
                        safe.withTranslatedNegativePrompt(appendedNegative)
                    }
                }
        } catch (_: IllegalArgumentException) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST,
                message = OfflinePromptTranslationFallbackMessages.PROTECTED_SYNTAX_LOST
            )
        }
        return OfflinePromptTranslationResolution.Translated(
            originalPrompt = request.sourceText,
            translatedPrompt = repaired.translatedText,
            originalNegativePrompt = request.negativePrompt,
            effectiveNegativePrompt = repaired.translatedNegativePrompt ?: request.negativePrompt,
            result = repaired,
            protectedTokens = protectedTokens
        )
    }

    private fun fallback(
        request: OfflinePromptTranslationRequest,
        protectedTokens: List<String>,
        reason: OfflinePromptTranslationFallbackReason,
        message: String
    ): OfflinePromptTranslationResolution.Fallback = OfflinePromptTranslationResolution.Fallback(
        originalPrompt = request.sourceText,
        originalNegativePrompt = request.negativePrompt,
        protectedTokens = protectedTokens,
        reason = reason,
        message = message
    )

    private fun OfflinePromptTranslationUnavailableReason.toFallbackReason():
        OfflinePromptTranslationFallbackReason = when (this) {
        OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED ->
            OfflinePromptTranslationFallbackReason.NATIVE_LIBRARY_NOT_PACKAGED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_NOT_INITIALIZED ->
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_NOT_INITIALIZED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_UNSUPPORTED ->
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED
    }

    private fun OfflinePromptTranslationUnavailableReason.toFallbackMessage(): String = when (this) {
        OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_LIBRARY_NOT_PACKAGED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_NOT_INITIALIZED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_NOT_INITIALIZED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_UNSUPPORTED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_UNSUPPORTED
    }

    private companion object {
        const val DEFAULT_OFFLINE_PROMPT_TRANSLATION_TIMEOUT_MS = 15_000L
    }
}

/**
 * Extracts syntax owned by the user rather than by the translator. The list is metadata for the
 * caller and is also used to repair a translated positive prompt. Negative prompt text itself is
 * always copied unchanged by [OfflinePromptTranslationResolution].
 */
internal fun extractOfflinePromptProtectedTokens(prompt: String): List<String> {
    val patterns = listOf(
        Regex("<[^>\\r\\n]{1,200}>") ,
        Regex("(?i)(?:lora|lyco|embedding):[A-Za-z0-9_+./-]{1,120}(?::[+-]?\\d+(?:\\.\\d+)?)?"),
        Regex("\\([^\\r\\n()]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\)")
    )
    val tokens = buildList {
        patterns.forEach { pattern ->
            pattern.findAll(prompt).forEach { match ->
                if (match.value !in this) add(match.value)
            }
        }
    }
    return tokens.filterNot { token ->
        tokens.any { other -> other != token && other.length > token.length && other.contains(token) }
    }
}

/**
 * The only runtime supplied by this contract file. It intentionally does not try to load a
 * library, so callers receive an explicit unavailable result until an isolated native adapter is
 * implemented and packaged.
 */
internal object UnavailableOfflinePromptTranslationRuntime : OfflinePromptTranslationRuntime {
    override val nativeLibraryFileName: String = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME

    override suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationRuntimeOutcome {
        // Touch the fixed identity so an adapter cannot accidentally use this stub for another bundle.
        require(bundle.identity.nativeLibraryFileName == nativeLibraryFileName)
        require(request.sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS)
        require(request.targetLanguage == OfflinePromptTranslationLanguage.ENGLISH)
        return OfflinePromptTranslationRuntimeOutcome.Unavailable(
            reason = OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED,
            message = "Offline zh-Hans to English translation is not installed."
        )
    }
}

private fun String.containsHanForOfflineTranslation(): Boolean =
    codePoints().anyMatch { codePoint ->
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }

private fun String.containsUnsafeTranslationCharacters(): Boolean =
    codePoints().anyMatch { codePoint ->
        val type = Character.getType(codePoint)
        type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        append((byte.toInt() ushr 4).and(0x0f).toString(16))
        append((byte.toInt() and 0x0f).toString(16))
    }
}

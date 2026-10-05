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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Offline prompt-translation bundle verification (P7/S18), moved verbatim from
 * OfflinePromptTranslation.kt: the rejection codes, the strict bundle verifier and the private
 * manifest declaration types feeding it. NOTICE_EXPECTATIONS was widened private->internal here
 * (also consumed by the retained contract object); ByteArray.toLowercaseHex was widened in the
 * retained file and is now shared by this file and the installer.
 */

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
 * Verifies either pinned translation package without relaxing the legacy M2M100 trust anchors.
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
        val selectedManifest = requireDirectRegularFile(translationDirectory, OfflinePromptTranslationContract.MANIFEST_FILE_NAME)
        val selectedJson = JSONObject(readUtf8File(selectedManifest, OfflinePromptTranslationContract.MAX_MANIFEST_BYTES))
        if (selectedJson.optString("translatorFamily") == OfflineTranslatorFamily.HY_MT2.name) {
            return verifyHyMt2OrThrow(root, translationDirectory, selectedManifest, selectedJson)
        }
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

    private fun verifyHyMt2OrThrow(
        root: File,
        translationDirectory: File,
        manifestFile: File,
        json: JSONObject
    ): VerifiedOfflinePromptTranslationBundle {
        json.requireExactlyKeys("manifest", setOf("kind", "contractVersion", "translatorFamily", "runtimeKind", "model", "runtime"))
        if (json.requireString("manifest", "kind") != OfflinePromptTranslationContract.MANIFEST_KIND ||
            json.requireInt("manifest", "contractVersion") != 2 ||
            json.requireString("manifest", "runtimeKind") != OfflineTranslationRuntimeKind.LLAMA_CPP.name
        ) fail(OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID, "Unsupported Hy-MT2 translation contract.")
        val model = json.requireObject("manifest", "model")
        val runtime = json.requireObject("manifest", "runtime")
        model.requireExactlyKeys("model", setOf("sourceId", "sourceRevision", "license", "fileName", "sha256", "sizeBytes", "architecture", "quantization"))
        runtime.requireExactlyKeys("runtime", setOf("sourceId", "sourceRevision", "license", "nativeLibraryFileName"))
        if (model.requireString("model", "sourceId") != HyMt2PromptTranslationContract.SOURCE_ID ||
            model.requireString("model", "sourceRevision") != HyMt2PromptTranslationContract.SOURCE_REVISION ||
            model.requireString("model", "license") != "Apache-2.0" ||
            model.requireString("model", "fileName") != HyMt2PromptTranslationContract.MODEL_FILE ||
            model.requireString("model", "sha256") != HyMt2PromptTranslationContract.MODEL_SHA256 ||
            model.requirePositiveLong("model", "sizeBytes", OfflinePromptTranslationContract.MAX_MODEL_BYTES) != HyMt2PromptTranslationContract.MODEL_BYTES ||
            model.requireString("model", "architecture") != HyMt2PromptTranslationContract.ARCHITECTURE ||
            model.requireString("model", "quantization") != "Q4_K_M" ||
            runtime.requireString("runtime", "sourceId") != HyMt2PromptTranslationContract.RUNTIME_SOURCE_ID ||
            runtime.requireString("runtime", "sourceRevision") != HyMt2PromptTranslationContract.RUNTIME_REVISION ||
            runtime.requireString("runtime", "license") != "MIT" ||
            runtime.requireString("runtime", "nativeLibraryFileName") != HyMt2PromptTranslationContract.NATIVE_LIBRARY
        ) fail(OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH, "Hy-MT2 model or llama.cpp provenance does not match the pinned contract.")
        requireExactDirectoryEntries(translationDirectory, setOf(
            OfflinePromptTranslationContract.MANIFEST_FILE_NAME, HyMt2PromptTranslationContract.MODEL_FILE,
            HyMt2PromptTranslationContract.MODEL_NOTICE, HyMt2PromptTranslationContract.RUNTIME_NOTICE
        ))
        val modelFile = requireDirectRegularFile(translationDirectory, HyMt2PromptTranslationContract.MODEL_FILE)
        verifyFileIntegrity(modelFile, HyMt2PromptTranslationContract.MODEL_SHA256, HyMt2PromptTranslationContract.MODEL_BYTES,
            OfflinePromptTranslationContract.MAX_MODEL_BYTES, false)
        val metadata = com.muyuchat.core.modelstore.GgufMetadataReader.read(modelFile)
        if (!metadata.isGguf || metadata.architecture != HyMt2PromptTranslationContract.ARCHITECTURE) {
            fail(OfflinePromptTranslationBundleRejectionCode.PROVENANCE_MISMATCH, "Pinned translation artifact is not a hunyuan-dense GGUF.")
        }
        val noticeSpecs = listOf(
            Triple(HyMt2PromptTranslationContract.MODEL_NOTICE, HyMt2PromptTranslationContract.MODEL_NOTICE_SHA256, 11_639L),
            Triple(HyMt2PromptTranslationContract.RUNTIME_NOTICE, OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256, 1_099L)
        )
        val notices = noticeSpecs.mapIndexed { index, (name, hash, bytes) ->
            val file = requireDirectRegularFile(translationDirectory, name)
            verifyFileIntegrity(file, hash, bytes, OfflinePromptTranslationContract.MAX_NOTICE_BYTES.toLong(), false)
            OfflinePromptTranslationNotice("translation/$name", file,
                if (index == 0) HyMt2PromptTranslationContract.modelProvenance else HyMt2PromptTranslationContract.runtimeProvenance,
                hash, bytes)
        }
        return VerifiedOfflinePromptTranslationBundle(root, manifestFile, modelFile, OfflinePromptTranslationBundleIdentity(
            model = HyMt2PromptTranslationContract.modelProvenance,
            runtime = HyMt2PromptTranslationContract.runtimeProvenance,
            modelArchitecture = HyMt2PromptTranslationContract.ARCHITECTURE,
            modelQuantization = "Q4_K_M",
            sourceLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
            targetLanguage = OfflinePromptTranslationLanguage.ENGLISH,
            sourceM2m100LanguageCode = "zh", targetM2m100LanguageCode = "en",
            modelSha256 = HyMt2PromptTranslationContract.MODEL_SHA256,
            modelSizeBytes = HyMt2PromptTranslationContract.MODEL_BYTES,
            nativeLibraryFileName = HyMt2PromptTranslationContract.NATIVE_LIBRARY,
            notices = notices,
            translatorFamily = OfflineTranslatorFamily.HY_MT2,
            runtimeKind = OfflineTranslationRuntimeKind.LLAMA_CPP
        ))
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

// P7/S18: widened private->internal with NOTICE_EXPECTATIONS (consumed by OfflinePromptTranslation.kt).
internal data class OfflinePromptTranslationNoticeExpectation(
    val fileName: String,
    val relativePath: String,
    val provenance: OfflinePromptTranslationProvenance,
    val sha256: String,
    val sizeBytes: Long
)

// P7/S18: shared with OfflinePromptTranslation.kt.
internal val NOTICE_EXPECTATIONS: List<OfflinePromptTranslationNoticeExpectation> = listOf(
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

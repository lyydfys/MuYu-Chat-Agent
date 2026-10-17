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

    private val MIT_TOKEN = Regex("\\bMIT\\b", RegexOption.IGNORE_CASE)
}

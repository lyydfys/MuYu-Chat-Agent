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
 * T2B21-T4（b 工位）拆片：manifest / JSON 解析与 require* 断言 helper 自
 * OfflinePromptTranslationBundleVerification.kt 逐字节平移；仅声明行 `private fun`→`internal fun`
 * 去缩进登记。
 */

internal fun parseManifest(text: String): OfflinePromptTranslationManifest {
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

internal fun parseModelDeclaration(json: JSONObject): OfflinePromptTranslationModelDeclaration {
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

internal fun parseRuntimeDeclaration(json: JSONObject): OfflinePromptTranslationRuntimeDeclaration {
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

internal fun parseNoticeDeclaration(
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


internal fun JSONObject.requireExactlyKeys(context: String, expected: Set<String>) {
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

internal fun JSONObject.requireObject(context: String, name: String): JSONObject =
        opt(name) as? JSONObject ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an object."
        )

internal fun JSONObject.requireArray(context: String, name: String): JSONArray =
        opt(name) as? JSONArray ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context.$name must be an array."
        )

internal fun JSONArray.requireObject(context: String, index: Int): JSONObject =
        opt(index) as? JSONObject ?: fail(
            OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
            "Offline translation $context must be an object."
        )

internal fun JSONObject.requireString(context: String, name: String): String {
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

internal fun JSONObject.requireInt(context: String, name: String): Int {
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

internal fun JSONObject.requirePositiveLong(
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

internal fun JSONObject.requireSha256(context: String, name: String): String {
        val value = requireString(context, name)
        if (!OfflinePromptTranslationContract.SHA256_PATTERN.matches(value)) {
            fail(
                OfflinePromptTranslationBundleRejectionCode.MANIFEST_INVALID,
                "Offline translation $context.$name must be lowercase SHA-256."
            )
        }
        return value
    }

internal fun fail(
        code: OfflinePromptTranslationBundleRejectionCode,
        message: String,
        cause: Throwable? = null
    ): Nothing = throw OfflinePromptTranslationBundleException(code, message, cause)

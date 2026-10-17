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
 * T2B21-T4（b 工位）拆片：拒绝码 enum / 验证结果 sealed interface / manifest 声明数据类、
 * 异常与 NOTICE_EXPECTATIONS 常量自 OfflinePromptTranslationBundleVerification.kt 逐字节平移；
 * 5 处 `private`→`internal` 可见性登记放宽（跨文件消费所需）。
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

internal class OfflinePromptTranslationBundleException(
    val code: OfflinePromptTranslationBundleRejectionCode,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

internal data class OfflinePromptTranslationManifest(
    val model: OfflinePromptTranslationModelDeclaration,
    val runtime: OfflinePromptTranslationRuntimeDeclaration,
    val notices: List<OfflinePromptTranslationNoticeDeclaration>
)

internal data class OfflinePromptTranslationModelDeclaration(
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

internal data class OfflinePromptTranslationRuntimeDeclaration(
    val provenance: OfflinePromptTranslationProvenance,
    val backend: String,
    val nativeLibraryFileName: String
)

internal data class OfflinePromptTranslationNoticeDeclaration(
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

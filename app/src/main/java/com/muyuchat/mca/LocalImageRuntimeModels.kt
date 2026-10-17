package com.muyuchat.mca

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Runtime-side local-image records (moved from LocalImageDomainModel.kt). */

internal data class LocalImageQnnRuntimeProfile(
    val qnnSdk: String,
    val htpArch: Int,
    val completeBundleRuntime: Boolean
)

data class LocalImageModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily = LocalImageModelFamily.CUSTOM,
    val imageSize: String = "512x512",
    val source: String = "local",
    val bundleRoot: String? = null,
    /** Stable catalog identity used to connect a downloaded bundle to its recommendation card. */
    val recommendationId: String? = null,
    val componentCount: Int = 1,
    val verificationStatus: LocalImageVerificationStatus = LocalImageVerificationStatus.UNKNOWN,
    val verificationMessage: String = "",
    val verifiedAt: Long = 0L,
    val qnnVerificationStamp: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Retired duplicate ids retained for selections, history and Local API references. */
    val aliases: List<String> = emptyList(),
    /** Complete component identity; never inferred from a primary-only/source hash. */
    val contentFingerprint: String = "",
    val componentSnapshots: List<LocalImageComponentSnapshot> = emptyList(),
    val physicalCopies: List<LocalImageModelPhysicalCopy> = emptyList(),
    /** Content observed when this path actually passed native execution. */
    val verifiedContentFingerprint: String = ""
) {
    fun matchesCatalogId(candidateId: String?): Boolean =
        candidateId != null && (id == candidateId || candidateId in aliases)

    val configured: Boolean
        get() {
            val primary = File(path)
            val root = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
            return path.isNotBlank() &&
                primary.isFile &&
                primary.canRead() &&
                primary.length() > 0L &&
                (root == null || (root.isDirectory && root.canRead()))
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("path", path)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("runtime", runtime.name)
        .put("family", family.name)
        .put("imageSize", imageSize)
        .put("source", source)
        .put("bundleRoot", bundleRoot)
        .put("recommendationId", recommendationId)
        .put("componentCount", componentCount)
        .put("verificationStatus", verificationStatus.name)
        .put("verificationMessage", verificationMessage)
        .put("verifiedAt", verifiedAt)
        .put("qnnVerificationStamp", qnnVerificationStamp)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("aliases", JSONArray().apply { aliases.distinct().forEach { put(it) } })
        .put("contentFingerprint", contentFingerprint)
        .put("componentSnapshots", JSONArray().apply { componentSnapshots.forEach { put(it.toJson()) } })
        .put("physicalCopies", JSONArray().apply { physicalCopies.forEach { put(it.toJson()) } })
        .put("verifiedContentFingerprint", verifiedContentFingerprint)

    companion object {
        fun fromJson(json: JSONObject): LocalImageModelRecord =
        LocalImageModelRecord(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                displayName = json.optString("displayName"),
                path = json.optString("path"),
                fileName = json.optString("fileName"),
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
                runtime = LocalImageRuntime.from(json.optString("runtime")),
                family = LocalImageModelFamily.from(json.optString("family")),
                imageSize = json.optString("imageSize", "512x512"),
                source = json.optString("source", "local"),
                bundleRoot = json.optString("bundleRoot").takeIf { it.isNotBlank() && it != "null" },
                recommendationId = json.optString("recommendationId").takeIf { it.isNotBlank() && it != "null" },
                componentCount = json.optInt("componentCount", 1).coerceAtLeast(1),
                verificationStatus = LocalImageVerificationStatus.from(json.optString("verificationStatus")),
                verificationMessage = json.optString("verificationMessage"),
                verifiedAt = json.optLong("verifiedAt", 0L),
                qnnVerificationStamp = json.optString("qnnVerificationStamp"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                aliases = json.optJSONArray("aliases")?.let { values ->
                    (0 until values.length()).mapNotNull { index ->
                        values.optString(index).takeIf { it.isNotBlank() && it != "null" }
                    }
                }.orEmpty(),
                contentFingerprint = json.optString("contentFingerprint"),
                componentSnapshots = json.optJSONArray("componentSnapshots").imageComponentSnapshots(),
                physicalCopies = json.optJSONArray("physicalCopies")?.let { copies ->
                    (0 until copies.length()).mapNotNull { index ->
                        copies.optJSONObject(index)?.let(LocalImageModelPhysicalCopy::fromJson)
                            ?.takeIf { it.path.isNotBlank() && it.fileName.isNotBlank() }
                    }
                }.orEmpty(),
                verifiedContentFingerprint = json.optString("verifiedContentFingerprint")
            )
    }
}

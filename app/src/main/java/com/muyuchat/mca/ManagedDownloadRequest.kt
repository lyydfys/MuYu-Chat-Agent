package com.muyuchat.mca

import com.muyuchat.core.download.*
import org.json.JSONObject
import java.security.MessageDigest

internal fun downloadIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun managedRemoteIdentity(remote: RemoteModelFile): String = downloadIdentity(
    listOf(remote.provider.name, remote.repoId, remote.revision, remote.path, remote.sha256.orEmpty()).joinToString("\n")
)

internal fun managedCatalogFingerprint(model: ModelScopeRecommendedModel): String = downloadIdentity(model.toString())

/** Only serializable download intent lives in WorkManager; no ViewModel or Activity is required. */
internal data class ManagedDownloadRequest(
    val recommendationId: String? = null,
    val catalogFingerprint: String? = null,
    val remote: RemoteModelFile? = null,
    val projectorTargetId: String? = null
) {
    val identity: String get() = downloadIdentity(
        (recommendationId?.let { "$it:$catalogFingerprint" } ?: managedRemoteIdentity(requireNotNull(remote))) +
            ":" + projectorTargetId.orEmpty())

    fun toJson(): String = JSONObject().apply {
        put("recommendationId", recommendationId)
        put("catalogFingerprint", catalogFingerprint)
        put("projectorTargetId", projectorTargetId)
        remote?.let { file -> put("remote", JSONObject().apply {
            put("repoId", file.repoId); put("revision", file.revision); put("path", file.path)
            put("name", file.name); put("sizeBytes", file.sizeBytes); put("sha256", file.sha256)
            put("license", file.license); put("url", file.downloadUrl); put("provider", file.provider.name)
            put("mnnRole", file.mnnBundleRole?.name); put("imageRole", file.bundleRole?.name)
            put("visionRole", file.visionBundleRole?.name); put("relativePath", file.relativePath)
        }) }
    }.toString()

    companion object {
        fun recommended(model: ModelScopeRecommendedModel) = ManagedDownloadRequest(
            recommendationId = model.id, catalogFingerprint = managedCatalogFingerprint(model))

        fun fromJson(raw: String): ManagedDownloadRequest {
            val json = JSONObject(raw)
            fun JSONObject.optional(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
            val remote = json.optJSONObject("remote")?.let { file -> RemoteModelFile(
                repoId = file.getString("repoId"), revision = file.getString("revision"),
                path = file.getString("path"), name = file.getString("name"),
                sizeBytes = if (file.isNull("sizeBytes")) null else file.getLong("sizeBytes"),
                sha256 = file.optional("sha256"), license = file.optional("license"),
                downloadUrl = file.getString("url"), provider = ModelRepositoryProvider.valueOf(file.getString("provider")),
                mnnBundleRole = file.optional("mnnRole")?.let(MnnModelBundleComponentRole::valueOf),
                bundleRole = file.optional("imageRole")?.let(ImageEngineBundleComponentRole::valueOf),
                visionBundleRole = file.optional("visionRole")?.let(VisionModelBundleComponentRole::valueOf),
                relativePath = file.getString("relativePath")) }
            return ManagedDownloadRequest(json.optional("recommendationId"), json.optional("catalogFingerprint"),
                remote, json.optional("projectorTargetId")).also {
                require((it.recommendationId != null) != (it.remote != null)) { "下载任务缺少明确的模型来源。" }
            }
        }
    }
}

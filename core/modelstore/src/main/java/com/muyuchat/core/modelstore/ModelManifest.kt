package com.muyuchat.core.modelstore

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

data class ModelManifest(
    val id: String,
    val displayName: String,
    val path: String,
    val runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP,
    val source: ModelSource,
    val repoId: String? = null,
    val revision: String? = null,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val quant: String? = null,
    val architecture: String? = null,
    val license: String? = null,
    val visionProjectorPath: String? = null,
    val visionProjectorFileName: String? = null,
    val visionProjectorSizeBytes: Long = 0L,
    val visionProjectorSha256: String? = null,
    /**
     * Legacy persisted certification bit retained for manifest/API backward
     * compatibility. MNN vision is now enabled on every compatible device once
     * the native runner has successfully loaded a readable visual component.
     */
    val visionValidated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoadedAt: Long? = null,
    /** Old ids retained after an explicit stable identity reconciliation. */
    val aliases: List<String> = emptyList(),
    /** Physical copies are retained as evidence; reconciliation never deletes them. */
    val physicalCopies: List<ModelPhysicalCopy> = emptyList(),
    val integrityState: ModelIntegrityState = ModelIntegrityState.COMPLETE,
    val diagnostic: String? = null
) {
    val hasVisionProjector: Boolean
        get() = !visionProjectorPath.isNullOrBlank()

    /**
     * Product image admission is device-agnostic. The native runner owns runtime,
     * bundle and visual-component validation; once it reports readiness the same
     * CPU path is available across ARM64 chipset vendors. Device-specific issues
     * should be handled as explicit compatibility exceptions, not an allowlist.
     */
    fun acceptsImageInput(nativeVisionReady: Boolean): Boolean =
        nativeVisionReady

    /**
     * Exact component identity used for reconciliation. A missing digest is
     * deliberately represented by the canonical path so anonymous/damaged
     * records cannot merge with another file merely because names and sizes
     * happen to match.
     */
    val stableIdentity: String
        get() = stableModelIdentity(this)

    /** Content identity is separate from where a copy was obtained. */
    val componentIdentity: String?
        get() = stableComponentIdentity(this)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("path", path)
        .put("runtime", runtime.storageValue)
        .put("source", source.name.lowercase())
        .put("repoId", repoId)
        .put("revision", revision)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("quant", quant)
        .put("architecture", architecture)
        .put("license", license)
        .put("visionProjectorPath", visionProjectorPath)
        .put("visionProjectorFileName", visionProjectorFileName)
        .put("visionProjectorSizeBytes", visionProjectorSizeBytes)
        .put("visionProjectorSha256", visionProjectorSha256)
        .put("visionValidated", visionValidated)
        .put("createdAt", createdAt)
        .put("lastLoadedAt", lastLoadedAt)
        .put("aliases", JSONArray().apply { aliases.distinct().filter { it.isNotBlank() }.forEach(::put) })
        .put("physicalCopies", JSONArray().apply { physicalCopies.forEach { put(it.toJson()) } })
        .put("integrityState", integrityState.storageValue)
        .put("diagnostic", diagnostic)

    companion object {
        fun fromJson(json: JSONObject): ModelManifest {
            val rawId = json.optString("id").takeIf { it.isNotBlank() && it != "null" }
            val rawPath = json.optString("path").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val rawFileName = json.optString("fileName").takeIf { it.isNotBlank() && it != "null" }
                ?: rawPath.substringAfterLast('/').substringAfterLast('\\')
            val digest = json.optString("sha256").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val damaged = rawId == null || rawPath.isBlank() || rawFileName.isBlank()
            val stableFallbackId = rawId ?: "damaged-" + sha256(json.toString()).take(32)
            val aliases = json.optJSONArray("aliases")?.let { values ->
                buildList { for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add) }
            }.orEmpty()
            val copies = json.optJSONArray("physicalCopies")?.let { values ->
                buildList { for (index in 0 until values.length()) runCatching {
                    ModelPhysicalCopy.fromJson(values.getJSONObject(index))
                }.getOrNull()?.let(::add) }
            }.orEmpty()
            val persistedState = if (json.has("integrityState")) {
                ModelIntegrityState.from(json.optString("integrityState"))
            } else ModelIntegrityState.UNKNOWN
            val invalidDigest = digest.isNotBlank() && !digest.matches(Regex("[0-9a-fA-F]{64}")) &&
                !isFastRecoveryFingerprint(digest)
            val invalidProjectorDigest = json.optString("visionProjectorSha256").let { value ->
                value.isNotBlank() && value != "null" && !value.matches(Regex("[0-9a-fA-F]{64}"))
            }
            return ModelManifest(
            id = stableFallbackId,
            displayName = json.optString("displayName"),
            path = rawPath,
            runtime = ChatModelRuntime.from(json.optString("runtime", json.optString("chatRuntime"))),
            source = ModelSource.from(json.optString("source")),
            repoId = json.optString("repoId").takeIf { it.isNotBlank() && it != "null" },
            revision = json.optString("revision").takeIf { it.isNotBlank() && it != "null" },
            fileName = rawFileName,
            sizeBytes = json.optLong("sizeBytes"),
            sha256 = digest,
            quant = json.optString("quant").takeIf { it.isNotBlank() && it != "null" },
            architecture = json.optString("architecture").takeIf { it.isNotBlank() && it != "null" },
            license = json.optString("license").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorPath = json.optString("visionProjectorPath").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorFileName = json.optString("visionProjectorFileName").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorSizeBytes = json.optLong("visionProjectorSizeBytes"),
            visionProjectorSha256 = json.optString("visionProjectorSha256").takeIf { it.isNotBlank() && it != "null" },
            visionValidated = json.optBoolean("visionValidated", false),
            createdAt = json.optLong("createdAt"),
            lastLoadedAt = json.optLong("lastLoadedAt").takeIf { json.has("lastLoadedAt") && !json.isNull("lastLoadedAt") },
            aliases = aliases,
            physicalCopies = copies,
            integrityState = if (damaged || invalidDigest || invalidProjectorDigest) ModelIntegrityState.DAMAGED else persistedState,
            diagnostic = json.optString("diagnostic").takeIf { it.isNotBlank() && it != "null" }
                ?: if (damaged) "模型清单字段缺失：id/path/fileName"
                else if (invalidDigest || invalidProjectorDigest) "模型清单 SHA-256 格式错误" else null
        )
        }
    }
}

data class ModelPhysicalCopy(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val visionProjectorPath: String? = null,
    val visionProjectorSizeBytes: Long = 0L,
    val visionProjectorSha256: String? = null,
    val source: ModelSource = ModelSource.LOCAL,
    val repoId: String? = null,
    val revision: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("visionProjectorPath", visionProjectorPath)
        .put("visionProjectorSizeBytes", visionProjectorSizeBytes)
        .put("visionProjectorSha256", visionProjectorSha256)
        .put("source", source.name.lowercase())
        .put("repoId", repoId)
        .put("revision", revision)

    companion object {
        fun fromJson(json: JSONObject): ModelPhysicalCopy = ModelPhysicalCopy(
            path = json.optString("path"),
            sizeBytes = json.optLong("sizeBytes"),
            sha256 = json.optString("sha256"),
            visionProjectorPath = json.optString("visionProjectorPath").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorSizeBytes = json.optLong("visionProjectorSizeBytes"),
            visionProjectorSha256 = json.optString("visionProjectorSha256").takeIf { it.isNotBlank() && it != "null" },
            source = ModelSource.from(json.optString("source")),
            repoId = json.optString("repoId").takeIf { it.isNotBlank() && it != "null" },
            revision = json.optString("revision").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}

enum class ModelIntegrityState(val storageValue: String) {
    COMPLETE("complete"),
    DAMAGED("damaged"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String?): ModelIntegrityState = entries.firstOrNull { it.storageValue == value?.lowercase() }
            ?: ModelIntegrityState.UNKNOWN
    }
}

internal fun stableModelIdentity(model: ModelManifest): String {
    val source = model.source.name.lowercase()
    val origin = listOf(model.repoId.orEmpty().trim().lowercase(), model.revision.orEmpty().trim()).joinToString("@")
    val mainDigest = model.sha256.trim().lowercase().takeIf {
        it.matches(Regex("[0-9a-f]{64}")) && !isFastRecoveryFingerprint(it)
    }
    val main = if (mainDigest != null) "digest:$mainDigest:${model.sizeBytes}" else {
        val canonical = runCatching { File(model.path).canonicalPath }.getOrElse { model.path }
        "path:$canonical:${model.sizeBytes}"
    }
    val projector = when {
        model.visionProjectorPath.isNullOrBlank() -> "none"
        model.visionProjectorSha256?.trim()?.matches(Regex("[0-9a-fA-F]{64}")) == true ->
            "digest:${model.visionProjectorSha256!!.lowercase()}:${model.visionProjectorSizeBytes}"
        else -> {
            val path = runCatching { File(model.visionProjectorPath!!).canonicalPath }
                .getOrElse { model.visionProjectorPath!! }
            "path:$path:${model.visionProjectorSizeBytes}"
        }
    }
    return "v2|runtime=${model.runtime.storageValue}|source=$source|origin=$origin|main=$main|projector=$projector"
}

internal fun stableComponentIdentity(model: ModelManifest): String? {
    val digest = model.sha256.trim().lowercase()
    if (!digest.matches(Regex("[0-9a-f]{64}")) || isFastRecoveryFingerprint(digest)) return null
    val projector = when {
        model.visionProjectorPath.isNullOrBlank() -> "none"
        model.visionProjectorSha256?.trim()?.matches(Regex("[0-9a-fA-F]{64}")) == true ->
            "${model.visionProjectorSha256!!.lowercase()}:${model.visionProjectorSizeBytes}"
        else -> return null
    }
    return "v2|${model.runtime.storageValue}|$digest:${model.sizeBytes}|$projector"
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

enum class ModelSource {
    LOCAL,
    MODELSCOPE,
    HUGGING_FACE;

    companion object {
        fun from(value: String): ModelSource = when (value.lowercase()) {
            "modelscope" -> MODELSCOPE
            "hugging_face", "huggingface", "hugging-face" -> HUGGING_FACE
            else -> LOCAL
        }
    }
}

enum class ChatModelRuntime(val storageValue: String, val label: String) {
    MNN("mnn", "MNN 高速引擎"),
    LLAMA_CPP("llama_cpp", "GGUF 兼容引擎"),
    GENIEX_QAIRT("geniex_qairt", "GenieX QAIRT NPU"),
    LITERT_LM("litert_lm", "LiteRT-LM 引擎");

    companion object {
        fun from(value: String?): ChatModelRuntime = when (value?.lowercase()) {
            "mnn", "mnn_llm", "mnn-llm" -> MNN
            "llama", "llama_cpp", "llama.cpp", "gguf" -> LLAMA_CPP
            "geniex", "geniex_qairt", "qairt", "qnn", "qnn_htp" -> GENIEX_QAIRT
            "litertlm", "litert_lm", "litert-lm", "litert" -> LITERT_LM
            else -> LLAMA_CPP
        }
    }
}

data class GgufMetadata(
    val isGguf: Boolean,
    val version: Int? = null,
    val architecture: String? = null,
    val quant: String? = null,
    val fileType: Int? = null,
    val causalAttention: Boolean? = null,
    val poolingType: Int? = null,
    /** Model-declared training/runtime context limit from `<arch>.context_length`. */
    val contextLength: Int? = null,
    /** Native MTP/NextN head count from `<arch>.nextn_predict_layers`. */
    val nextnPredictLayers: Int? = null
)

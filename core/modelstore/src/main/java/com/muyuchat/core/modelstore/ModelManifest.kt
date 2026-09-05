package com.muyuchat.core.modelstore

import org.json.JSONObject

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
    val lastLoadedAt: Long? = null
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

    companion object {
        fun fromJson(json: JSONObject): ModelManifest = ModelManifest(
            id = json.getString("id"),
            displayName = json.optString("displayName"),
            path = json.optString("path"),
            runtime = ChatModelRuntime.from(json.optString("runtime", json.optString("chatRuntime"))),
            source = ModelSource.from(json.optString("source")),
            repoId = json.optString("repoId").takeIf { it.isNotBlank() && it != "null" },
            revision = json.optString("revision").takeIf { it.isNotBlank() && it != "null" },
            fileName = json.optString("fileName"),
            sizeBytes = json.optLong("sizeBytes"),
            sha256 = json.optString("sha256"),
            quant = json.optString("quant").takeIf { it.isNotBlank() && it != "null" },
            architecture = json.optString("architecture").takeIf { it.isNotBlank() && it != "null" },
            license = json.optString("license").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorPath = json.optString("visionProjectorPath").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorFileName = json.optString("visionProjectorFileName").takeIf { it.isNotBlank() && it != "null" },
            visionProjectorSizeBytes = json.optLong("visionProjectorSizeBytes"),
            visionProjectorSha256 = json.optString("visionProjectorSha256").takeIf { it.isNotBlank() && it != "null" },
            visionValidated = json.optBoolean("visionValidated", false),
            createdAt = json.optLong("createdAt"),
            lastLoadedAt = json.optLong("lastLoadedAt").takeIf { json.has("lastLoadedAt") && !json.isNull("lastLoadedAt") }
        )
    }
}

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

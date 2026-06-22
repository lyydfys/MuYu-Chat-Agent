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
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoadedAt: Long? = null
) {
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
    LLAMA_CPP("llama_cpp", "llama.cpp GGUF");

    companion object {
        fun from(value: String?): ChatModelRuntime = LLAMA_CPP
    }
}

data class GgufMetadata(
    val isGguf: Boolean,
    val version: Int? = null,
    val architecture: String? = null,
    val quant: String? = null,
    val fileType: Int? = null
)

package com.muyuchat.mca

import org.json.JSONObject
import com.muyuchat.api.local.looksLikePrivatePath

/** T2B22-T4: LoRA history selection model and JSON codec; moved verbatim from ImageGenerationHistoryMetadata.kt. */

data class ImageGenerationLoraSelection(
    val id: String,
    val name: String,
    val multiplier: Double
) {
    init {
        require(UUID_PATTERN.matches(id)) { "Image history LoRA id must be a UUID." }
        require(name.isNotBlank() &&
            name.length <= MAX_NAME_CHARS &&
            !name.looksLikePrivatePath()
        ) {
            "Image history LoRA name is invalid."
        }
        require(multiplier.isFinite() &&
            multiplier in LocalImagePreparedLora.MIN_MULTIPLIER..LocalImagePreparedLora.MAX_MULTIPLIER &&
            kotlin.math.abs(multiplier) >= LocalImagePreparedLora.MIN_ABSOLUTE_MULTIPLIER
        ) { "Image history LoRA multiplier is invalid." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("multiplier", multiplier)

    companion object {
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val MAX_NAME_CHARS = 128
        private val LORA_HISTORY_FIELDS = setOf("id", "name", "multiplier")

        fun fromJson(json: JSONObject): ImageGenerationLoraSelection {
            require(json.keys().asSequence().toSet() == LORA_HISTORY_FIELDS) {
                "Image history LoRA contains unknown or missing fields."
            }
            return ImageGenerationLoraSelection(
                id = json.getString("id").trim().lowercase(),
                name = json.getString("name").trim(),
                multiplier = json.getDouble("multiplier")
            )
        }
    }
}

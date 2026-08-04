package com.muyuchat.mca

import org.json.JSONObject

/** Path-free identity for one image produced as part of a product-level batch. */
data class ImageGenerationBatchLineage(
    val parentRequestId: String,
    val index: Int,
    val count: Int,
    val seed: Int
) {
    init {
        require(parentRequestId.isNotBlank()) { "Image batch parent requestId must not be blank." }
        require(count in 1..MAX_BATCH_COUNT) { "Image batch count must be between 1 and $MAX_BATCH_COUNT." }
        require(index in 0 until count) { "Image batch index must be within the batch." }
        require(seed >= 0) { "Image batch seed must be non-negative." }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("parentRequestId", parentRequestId)
        .put("index", index)
        .put("count", count)
        .put("seed", seed)

    companion object {
        const val MAX_BATCH_COUNT = 8

        internal fun fromJson(json: JSONObject): ImageGenerationBatchLineage =
            ImageGenerationBatchLineage(
                parentRequestId = json.getString("parentRequestId"),
                index = json.getInt("index"),
                count = json.getInt("count"),
                seed = json.getInt("seed")
            )
    }
}

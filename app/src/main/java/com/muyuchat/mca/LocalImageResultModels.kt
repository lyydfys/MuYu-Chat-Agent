package com.muyuchat.mca


/** Result/progress records for local-image execution (moved from LocalImageDomainModel.kt). */

data class LocalImageOutput(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val seed: Long? = null,
    val index: Int = 0
) {
    init {
        require(bytes.isNotEmpty()) { "Local image output must not be empty." }
        require(mimeType.startsWith("image/")) { "Local image output MIME type must use image/*." }
        require(index >= 0) { "Local image output index must be non-negative." }
    }
}

data class LocalImageResult(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    /** Native execution audit returned through the isolated product worker. */
    val executionMetadataJson: String = "",
    val seed: Long? = null,
    /** Ordered batch outputs. The legacy bytes/mimeType fields always mirror index 0. */
    val outputs: List<LocalImageOutput> = listOf(
        LocalImageOutput(bytes = bytes, mimeType = mimeType, seed = seed, index = 0)
    )
) {
    init {
        require(outputs.isNotEmpty()) { "Local image result must contain at least one output." }
        require(outputs.map(LocalImageOutput::index) == outputs.indices.toList()) {
            "Local image output indices must be contiguous and start at zero."
        }
        require(outputs.first().bytes.contentEquals(bytes) &&
            outputs.first().mimeType == mimeType && outputs.first().seed == seed
        ) {
            "Legacy local image result fields must mirror output index zero."
        }
    }
}

data class LocalImageProgress(
    val phase: String,
    val message: String,
    val step: Int,
    val steps: Int,
    val elapsedMs: Long,
    val secondsPerStep: Double,
    val threads: Int,
    val width: Int,
    val height: Int,
    val cancelRequested: Boolean,
    /** Requested/effective controls for audit only; never native execution proof. */
    val requestOptionsJson: String = "",
    /** Actual stable-diffusion.cpp component paths selected by native execution. */
    val componentSelectionJson: String = "",
    /** Monotonic native stage evidence; useful even when a short stage is missed by polling. */
    val stageTrace: List<String> = emptyList(),
    /** App-private PNG written by a native preview publisher and valid only for this request. */
    val previewPath: String = "",
    val previewMimeType: String = "",
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val previewFrameCount: Int = 0,
    val previewNoisy: Boolean = false,
    /** Shared-QNN preview audit; these counters never satisfy final VAE execution evidence. */
    val previewVaeExecutionAttemptCount: Int = 0,
    val previewVaeExecutionCount: Int = 0,
    val previewVaeExecutionMsTotal: Long = 0L,
    val previewPublicationCount: Int = 0,
    val previewLastStep: Int = 0,
    val previewLastRevision: Long = 0L,
    val previewFailureCode: String = ""
)

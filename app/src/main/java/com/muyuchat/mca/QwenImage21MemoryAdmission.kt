package com.muyuchat.mca

/**
 * Conservative admission check for the Android MNN Qwen-Image-2.1 bundle.
 *
 * The native graph can allocate a large DiT working set after the text encoder
 * has already been loaded.  Checking only the model file size is therefore not
 * useful. The estimate is advisory for every memory reading: one observed peak
 * cannot prove a different graph or allocator will fail.
 */
internal data class QwenImage21MemoryAdmission(
    val availableMemoryMb: Int,
    val requiredMemoryMb: Int,
    val width: Int,
    val height: Int,
    val allowed: Boolean,
    val message: String? = null
)

internal object QwenImage21MemoryAdmissionPolicy {
    // Observed on the pinned Android MNN conversion at 512x512.  Keep this
    // value in one place so a future native benchmark can update the contract.
    private const val OBSERVED_512_PEAK_MB = 5_529L
    private const val SAFETY_RESERVE_MB = 512
    private const val FIXED_ESTIMATE_MB = 3_000L
    private const val BASE_AREA = 512L * 512L

    fun evaluate(availableMemoryMb: Int, width: Int, height: Int): QwenImage21MemoryAdmission {
        require(width > 0 && height > 0) { "Qwen-Image-2.1 dimensions must be positive." }
        val area = width.toLong() * height.toLong()
        // Separate resident encoder/model cost from the image-area workset. Saturate before
        // multiplying so very large dimensions cannot wrap into a reassuring low estimate.
        val imageWorkset = OBSERVED_512_PEAK_MB - FIXED_ESTIMATE_MB
        val scaledWorkset = if (area > (Long.MAX_VALUE - BASE_AREA + 1L) / imageWorkset) {
            Long.MAX_VALUE
        } else {
            (imageWorkset * area + BASE_AREA - 1L) / BASE_AREA
        }
        val required = (FIXED_ESTIMATE_MB +
            scaledWorkset.coerceAtMost(Long.MAX_VALUE - FIXED_ESTIMATE_MB - SAFETY_RESERVE_MB) +
            SAFETY_RESERVE_MB)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (availableMemoryMb < 0) {
            return QwenImage21MemoryAdmission(
                availableMemoryMb = availableMemoryMb,
                requiredMemoryMb = required,
                width = width,
                height = height,
                allowed = true,
                message = null
            )
        }
        return QwenImage21MemoryAdmission(
            availableMemoryMb = availableMemoryMb,
            requiredMemoryMb = required,
            width = width,
            height = height,
            allowed = true,
            message = if (availableMemoryMb >= required) null else failureMessage(availableMemoryMb, required, width, height)
        )
    }

    fun failureMessage(availableMemoryMb: Int, requiredMemoryMb: Int, width: Int, height: Int): String =
        "Qwen-Image-2.1 内存估算约需 ${requiredMemoryMb} MB，当前报告约 " +
            "${availableMemoryMb.coerceAtLeast(0)} MB（${width}×${height}）。" +
            "可先释放聊天模型和后台应用，或改用 384×384 / Tiny 尺寸；仍可尝试真实生成。"
}

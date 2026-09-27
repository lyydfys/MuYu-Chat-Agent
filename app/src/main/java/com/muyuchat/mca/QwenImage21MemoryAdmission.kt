package com.muyuchat.mca

/**
 * Conservative admission check for the Android MNN Qwen-Image-2.1 bundle.
 *
 * The native graph can allocate a large DiT working set after the text encoder
 * has already been loaded.  Checking only the model file size is therefore not
 * useful.  This policy is deliberately advisory when the native runtime cannot
 * report available memory (-1), but it fails early when the measured budget is
 * below the observed peak plus a safety reserve.
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
    private const val OBSERVED_512_PEAK_MB = 5_529
    private const val SAFETY_RESERVE_MB = 512
    private const val MINIMUM_SAFE_BUDGET_MB = 4_000
    private const val BASE_AREA = 512L * 512L

    fun evaluate(availableMemoryMb: Int, width: Int, height: Int): QwenImage21MemoryAdmission {
        require(width > 0 && height > 0) { "Qwen-Image-2.1 dimensions must be positive." }
        val area = width.toLong() * height.toLong()
        val scaledPeak = ((OBSERVED_512_PEAK_MB * area) + BASE_AREA - 1L) / BASE_AREA
        val required = (scaledPeak + SAFETY_RESERVE_MB.toLong())
            .coerceAtLeast(MINIMUM_SAFE_BUDGET_MB.toLong())
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
        val allowed = availableMemoryMb >= required
        return QwenImage21MemoryAdmission(
            availableMemoryMb = availableMemoryMb,
            requiredMemoryMb = required,
            width = width,
            height = height,
            allowed = allowed,
            message = if (allowed) null else failureMessage(availableMemoryMb, required, width, height)
        )
    }

    fun failureMessage(availableMemoryMb: Int, requiredMemoryMb: Int, width: Int, height: Int): String =
        "Qwen-Image-2.1 预计需要约 ${requiredMemoryMb} MB 可用内存，但当前只有约 " +
            "${availableMemoryMb.coerceAtLeast(0)} MB（${width}×${height}）。" +
            "请先释放聊天模型和后台应用，或改用 384×384 / Tiny 尺寸后重试。"
}

package com.muyuchat.mca

/**
 * Size facts for the pinned MCA Qwen-Image-2.1 MNN bundle.
 *
 * The upstream 7B model documents 2K-class sizes.  The Android MNN port used
 * by MCA is a different, memory-bounded conversion: its VAE is dynamic and
 * accepts dimensions aligned to 32 pixels, but its verified UI grid is the
 * three pixel-budget tiers below.  Keeping this grid explicit prevents a
 * user-selected arbitrary aligned size from turning into a phone OOM.
 */
internal object QwenImage21SizeContract {
    /** Native MNN shape rule observed in the pinned qwen-image21 runtime. */
    const val NATIVE_MIN_DIMENSION: Int = 256
    const val NATIVE_DIMENSION_MULTIPLE: Int = 32
    const val DEFAULT_WIDTH: Int = 512
    const val DEFAULT_HEIGHT: Int = 512
    // Compatibility aliases for callers that only need the default canvas.
    const val BUNDLE_WIDTH: Int = DEFAULT_WIDTH
    const val BUNDLE_HEIGHT: Int = DEFAULT_HEIGHT

    val SUPPORTED_SIZES: List<Pair<Int, Int>> = listOf(
        // Standard (~512², highest detail)
        512 to 512, 576 to 448, 448 to 576, 640 to 416, 416 to 640,
        672 to 384, 384 to 672,
        // Fast (~384²)
        384 to 384, 448 to 320, 320 to 448, 480 to 320, 320 to 480,
        512 to 288, 288 to 512,
        // Tiny (~320²)
        320 to 320, 384 to 288, 288 to 384, 384 to 256, 256 to 384,
        416 to 256, 256 to 416
    )

    private val supportedSizeSet = SUPPORTED_SIZES.toSet()

    val VERIFIED_SIZE_TIERS: Map<String, List<Pair<Int, Int>>> = linkedMapOf(
        "Standard" to SUPPORTED_SIZES.take(7),
        "Fast" to SUPPORTED_SIZES.slice(7 until 14),
        "Tiny" to SUPPORTED_SIZES.drop(14)
    )

    val VERIFIED_SIZE_TIERS_LABEL: String = VERIFIED_SIZE_TIERS.entries.joinToString("；") { (tier, sizes) ->
        "$tier：${sizes.joinToString("、") { "${it.first}×${it.second}" }}"
    }

    fun isSupported(width: Int, height: Int): Boolean = width to height in supportedSizeSet

    /**
     * Shape-level admission exposed by the native port. This is deliberately
     * broader than [isSupported]: native setImageSize accepts any dimensions
     * at least 256 that are aligned to 32 (rounding down to the nearest 32).
     * It is not a product guarantee because memory and graph stability still
     * require a real-device PNG smoke.
     */
    fun isNativeShape(width: Int, height: Int): Boolean =
        width >= NATIVE_MIN_DIMENSION &&
            height >= NATIVE_MIN_DIMENSION &&
            width % NATIVE_DIMENSION_MULTIPLE == 0 &&
            height % NATIVE_DIMENSION_MULTIPLE == 0

    val SUPPORTED_SIZES_LABEL: String = SUPPORTED_SIZES.joinToString("、") {
        "${it.first}×${it.second}"
    }

    const val MNN_SIZE_LINE: String =
        "MNN 原生形状规则是宽高至少 256 且为 32 的倍数；MCA 已在真机验证 7 种比例 × 3 档，共 21 个尺寸组合（Standard/Fast/Tiny）。其他原生形状仍需真机 PNG 和内存验证，不能直接视为可用。"

    /** Official upstream example sizes; they require a separate high-resolution export. */
    val OFFICIAL_EXAMPLE_SIZES: List<Pair<Int, Int>> = listOf(
        2048 to 2048,
        2400 to 1792,
        1792 to 2400,
        2528 to 1696,
        1696 to 2528,
        2752 to 1536,
        1536 to 2752,
    )

    val OFFICIAL_EXAMPLE_SIZES_LABEL: String = OFFICIAL_EXAMPLE_SIZES.joinToString("、") {
        "${it.first}×${it.second}"
    }

    val RECOMMENDATION_SIZE_LINE: String =
        "$MNN_SIZE_LINE $VERIFIED_SIZE_TIERS_LABEL；官方原始模型还支持 2K 示例尺寸（$OFFICIAL_EXAMPLE_SIZES_LABEL），" +
            "这些高分辨率尺寸需要对应专用导出，当前 Android MNN 包不宣称支持。"

    fun unsupportedSizeMessage(width: Int, height: Int): String =
        "当前 MCA Qwen-Image-2.1 MNN 包未验证 ${width}×${height}。原生端只接受至少 ${NATIVE_MIN_DIMENSION} 且宽高为 ${NATIVE_DIMENSION_MULTIPLE} 的倍数；" +
            "请从已验证尺寸中选择：$SUPPORTED_SIZES_LABEL。" +
            "官方原始模型的 2K 示例（$OFFICIAL_EXAMPLE_SIZES_LABEL）需要另一套高分辨率运行包。"
}

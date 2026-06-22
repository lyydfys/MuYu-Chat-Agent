package com.muyuchat.core.telemetry

import android.os.Build
import java.util.Locale

data class SocInfo(
    val manufacturer: String,
    val model: String,
    val family: SocFamily
) {
    val label: String = listOf(manufacturer, model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Unknown SoC" }
}

enum class SocFamily {
    Snapdragon,
    Dimensity,
    Exynos,
    Tensor,
    Kirin,
    Unknown
}

object SocDetector {
    fun detect(): SocInfo {
        val manufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.orEmpty() else Build.HARDWARE.orEmpty()
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else Build.BOARD.orEmpty()
        val haystack = "$manufacturer $model ${Build.HARDWARE} ${Build.BOARD}".lowercase(Locale.US)
        val family = when {
            "snapdragon" in haystack || "qcom" in haystack || "qualcomm" in haystack -> SocFamily.Snapdragon
            "dimensity" in haystack || "mediatek" in haystack || "mt" in haystack -> SocFamily.Dimensity
            "exynos" in haystack -> SocFamily.Exynos
            "tensor" in haystack -> SocFamily.Tensor
            "kirin" in haystack || "hisilicon" in haystack -> SocFamily.Kirin
            else -> SocFamily.Unknown
        }
        return SocInfo(manufacturer = manufacturer, model = model, family = family)
    }
}

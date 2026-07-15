package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.sparseMoeInfo

internal enum class LocalModelMemoryAdmissionMode {
    ALLOW,
    SPARSE_MOE_MMAP,
    DENY
}

internal data class LocalModelMemoryAdmission(
    val mode: LocalModelMemoryAdmissionMode,
    val blocker: String? = null,
    val advisory: String? = null,
    val estimatedDenseNeedBytes: Long = 0L
) {
    val allowed: Boolean
        get() = mode != LocalModelMemoryAdmissionMode.DENY
}

/**
 * Product load admission. File-backed mmap pages and anonymous runtime memory
 * are deliberately not treated as the same thing.
 *
 * A metadata-identified sparse MoE on llama.cpp is admitted in supervised mmap
 * mode even when the GGUF is larger than the generic dense-model budget. The
 * runtime profile coordinator forces mmap on and mlock off for this capability;
 * native/runtime pressure guards remain authoritative after admission.
 */
internal object LocalModelMemoryAdmissionPolicy {
    fun evaluate(model: ModelManifest, device: DeviceProfile): LocalModelMemoryAdmission {
        if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
            return LocalModelMemoryAdmission(LocalModelMemoryAdmissionMode.ALLOW)
        }

        val sparseMoe = model.sparseMoeInfo()
        if (model.runtime == ChatModelRuntime.LLAMA_CPP && sparseMoe.isSparseMoe) {
            val totalParametersB = sparseMoe.totalParametersB
            val activeParametersB = sparseMoe.activeParametersB
            val scale = if (activeParametersB != null && totalParametersB != null) {
                "总 ${formatScale(totalParametersB)}B / 每 token 激活约 ${formatScale(activeParametersB)}B"
            } else {
                "GGUF 架构已确认为稀疏 MoE"
            }
            val pressureAdvisory = when {
                device.isLowMemory ->
                    "系统当前报告低内存，仍将按 mmap 模式尝试；建议先关闭后台应用。"
                device.availableRamBytes in 1 until 2L * GIB ->
                    "当前可用内存偏低，仍将按 mmap 模式尝试。"
                else -> null
            }
            return LocalModelMemoryAdmission(
                mode = LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP,
                advisory = listOfNotNull(
                    "$scale；使用 mmap 文件页按需调入，不按完整 GGUF 大小做静态拒绝。",
                    pressureAdvisory
                ).joinToString(" ")
            )
        }

        val totalRam = device.displayTotalRamBytes
        val availableRam = device.availableRamBytes
        val modelBudget = device.modelMemoryBudgetBytes.takeIf { it > 0L }
            ?: maxOf(availableRam, (device.totalRamBytes * 0.70).toLong())
        val estimatedNeed = denseEstimate(model.sizeBytes)
        val blocker = when {
            totalRam > 0L && estimatedNeed > (totalRam * 0.88).toLong() ->
                "模型约需 ${formatBytes(estimatedNeed)} 内存，已接近或超过本机总内存 ${formatBytes(totalRam)}。建议换用更小的量化文件。"
            device.isLowMemory ->
                "系统已进入低内存状态（可用约 ${formatBytes(availableRam)}），建议关闭后台应用后再加载。"
            estimatedNeed > modelBudget && availableRam < 2L * GIB ->
                "当前系统可用内存约 ${formatBytes(availableRam)}，运行预算偏紧。建议关闭后台应用，或先用短基准确认。"
            else -> null
        }
        return if (blocker == null) {
            LocalModelMemoryAdmission(
                mode = LocalModelMemoryAdmissionMode.ALLOW,
                estimatedDenseNeedBytes = estimatedNeed
            )
        } else {
            LocalModelMemoryAdmission(
                mode = LocalModelMemoryAdmissionMode.DENY,
                blocker = blocker,
                estimatedDenseNeedBytes = estimatedNeed
            )
        }
    }

    private fun denseEstimate(fileBytes: Long): Long {
        if (fileBytes <= 0L) return 768L * MIB
        val scaled = if (fileBytes > Long.MAX_VALUE / 118L) {
            Long.MAX_VALUE
        } else {
            fileBytes * 118L / 100L
        }
        return if (scaled > Long.MAX_VALUE - 768L * MIB) Long.MAX_VALUE else scaled + 768L * MIB
    }

    private fun formatScale(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / GIB.toDouble()
        val mib = bytes / MIB.toDouble()
        return if (gib >= 1.0) "%.2f GB".format(gib) else "%.1f MB".format(mib)
    }

    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * 1024L * 1024L
}

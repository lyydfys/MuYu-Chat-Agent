package com.muyuchat.core.modelstore

import java.util.Locale

enum class SparseMoeEvidence {
    GGUF_ARCHITECTURE,
    NONE
}

data class SparseMoeModelInfo(
    val isSparseMoe: Boolean,
    val totalParametersB: Double? = null,
    val activeParametersB: Double? = null,
    val evidence: SparseMoeEvidence = SparseMoeEvidence.NONE
) {
    val activeRatio: Double?
        get() = if (
            totalParametersB != null && totalParametersB > 0.0 &&
            activeParametersB != null && activeParametersB > 0.0
        ) {
            (activeParametersB / totalParametersB).coerceIn(0.0, 1.0)
        } else {
            null
        }
}

/**
 * Detects sparse-MoE GGUFs without opening tensor data.
 *
 * Architecture metadata is authoritative. A model name may supply the
 * total/active scale used in diagnostics only after the GGUF architecture has
 * independently proved that the model is sparse. A filename must never weaken
 * load admission on its own.
 */
object SparseMoeModelPolicy {
    private val totalActiveScale = Regex(
        pattern = """(?i)(\d+(?:\.\d+)?)\s*b\s*[-_/]?\s*a(\d+(?:\.\d+)?)\s*b"""
    )

    fun inspect(model: ModelManifest): SparseMoeModelInfo = inspect(
        architecture = model.architecture,
        names = listOf(model.displayName, model.fileName)
    )

    fun inspect(architecture: String?, names: List<String>): SparseMoeModelInfo {
        val scale = names.asSequence()
            .mapNotNull(totalActiveScale::find)
            .mapNotNull { match ->
                val total = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                val active = match.groupValues.getOrNull(2)?.toDoubleOrNull()
                if (total != null && active != null && total > active && active > 0.0) {
                    total to active
                } else {
                    null
                }
            }
            .firstOrNull()

        if (architecture.isSparseMoeArchitecture()) {
            return SparseMoeModelInfo(
                isSparseMoe = true,
                totalParametersB = scale?.first,
                activeParametersB = scale?.second,
                evidence = SparseMoeEvidence.GGUF_ARCHITECTURE
            )
        }
        return SparseMoeModelInfo(isSparseMoe = false)
    }

    private fun String?.isSparseMoeArchitecture(): Boolean {
        val normalized = this
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('-', '_')
            .orEmpty()
        if (normalized.isBlank()) return false
        return "moe" in normalized || normalized in knownSparseArchitectures
    }

    private val knownSparseArchitectures = setOf(
        "mixtral",
        "dbrx",
        "deepseek2",
        "deepseek_v2",
        "deepseek_v3",
        "grok",
        "arctic"
    )
}

fun ModelManifest.sparseMoeInfo(): SparseMoeModelInfo = SparseMoeModelPolicy.inspect(this)
